package com.example.privatelistening.phone

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioAttributes
import android.media.AudioTrack
import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.privatelistening.common.PcmFramer
import com.example.privatelistening.common.AudioPacket
import com.example.privatelistening.common.AudioStreamFrame
import com.example.privatelistening.common.DemoConfig
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedChangingAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.BufferedInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 手机音频模块：
 * - TCP/UDP 接收（目前默认TCP）、数据校验；
 * - 有界音频队列；
 * - PCM16 输出；
 * - 停止与异常清理。
 * prepare 只准备资源，不播放节目声音。
 * schedulePlayback 和 commitPlayback 共同控制正式起播。
 */
class PhoneAudio(
    private val listener: Listener
) {

    /**
     * 可动态追加变速点的媒体时间表。
     */
    @SuppressLint("UnsafeOptInUsageError")
    private class CorrectionSpeedProvider : SpeedProvider {
        private val changes = TreeMap<Long, Float>().apply { put(0L, 1f) }

        @Synchronized
        override fun getSpeed(timeUs: Long): Float = changes.floorEntry(timeUs)?.value ?: 1f

        @Synchronized
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long =
            changes.higherKey(timeUs) ?: C.TIME_UNSET

        @Synchronized
        fun addChange(timeUs: Long, speed: Float) {
            changes[timeUs.coerceAtLeast(0L)] = speed
        }
    }

    /**
     * PCM16 软件时间伸缩器。Sonic 只改变时长、不改变音高；整个流从起播开始都经过同一处理器。
     */
    @SuppressLint("RestrictedApi", "UnsafeOptInUsageError")
    private class AdaptivePcmTimeStretch(
        private val sampleRate: Int,
        private val channels: Int
    ) {
        private val speedProvider = CorrectionSpeedProvider()
        private val processor = SpeedChangingAudioProcessor(
            speedProvider,
            /* areInputTimestampsAdjusted = */ false,
            /* shouldMaintainPitch = */ true
        )
        private val bytesPerFrame = channels * 2

        var inputFrames: Long = 0L
            private set
        var outputFrames: Long = 0L
            private set
        var requestedSpeed: Float = 1f
            private set
        private var lastScheduledInputUs = Long.MIN_VALUE

        init {
            processor.configure(
                AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT)
            )
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        }

        fun process(pcm: ByteArray): ByteArray {
            if (pcm.isEmpty()) return pcm
            check(pcm.size % bytesPerFrame == 0) { "PCM数据没有按完整采样帧对齐" }
            val input = ByteBuffer.wrap(pcm).order(ByteOrder.nativeOrder())
            val output = ByteArrayOutputStream(pcm.size + pcm.size / 20)
            while (input.hasRemaining()) {
                val before = input.position()
                processor.queueInput(input)
                drainTo(output)
                check(input.position() > before) { "Sonic没有消费PCM输入" }
            }
            drainTo(output)
            inputFrames += pcm.size / bytesPerFrame
            val result = output.toByteArray()
            check(result.size % bytesPerFrame == 0) { "Sonic输出没有按完整采样帧对齐" }
            outputFrames += result.size / bytesPerFrame
            return result
        }

        private fun drainTo(destination: ByteArrayOutputStream) {
            while (true) {
                val buffer = processor.output
                if (!buffer.hasRemaining()) return
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                destination.write(bytes, 0, bytes.size)
            }
        }

        /** 在当前输入前沿之后预留切换距离；返回是否真正加入了新的速度点。 */
        fun scheduleSpeed(speed: Float): Boolean {
            val bounded = speed.coerceIn(MIN_CORRECTION_SPEED, MAX_CORRECTION_SPEED)
            if (kotlin.math.abs(bounded - requestedSpeed) < MIN_SPEED_CHANGE) return false
            val inputUs = inputFrames * 1_000_000L / sampleRate
            if (lastScheduledInputUs != Long.MIN_VALUE &&
                inputUs - lastScheduledInputUs < MIN_SPEED_SEGMENT_US) return false
            val changeAtUs = inputUs + SPEED_CHANGE_LOOKAHEAD_US
            speedProvider.addChange(changeAtUs, bounded)
            requestedSpeed = bounded
            lastScheduledInputUs = changeAtUs
            return true
        }

        /** AudioTrack输出帧位置对应的节目媒体时长。 */
        fun mediaDurationForOutputFrames(frames: Long): Long {
            val playoutUs = frames.coerceAtLeast(0L) * 1_000_000L / sampleRate
            return processor.getMediaDurationUs(playoutUs)
        }

        fun mediaRateForOutputFrames(frames: Long): Float =
            speedProvider.getSpeed(mediaDurationForOutputFrames(frames))
    }

    /** 2秒滑窗中值 + 死区/滞回，过滤单个视频回调和AudioTimestamp的瞬时抖动。 */
    private class DriftController {
        private data class Sample(val atUs: Long, val errorUs: Long)
        private val samples = ArrayDeque<Sample>()
        private var stableDirection = 0
        private var stableSinceUs = 0L
        private var lastDecisionUs = 0L

        data class Decision(val filteredErrorUs: Long, val speed: Float?)

        fun update(nowUs: Long, errorUs: Long, currentSpeed: Float): Decision {
            samples.addLast(Sample(nowUs, errorUs))
            while (samples.isNotEmpty() && nowUs - samples.first().atUs > DRIFT_WINDOW_US) {
                samples.removeFirst()
            }
            val filtered = samples.map { it.errorUs }.sorted().let { values ->
                if (values.isEmpty()) errorUs else values[values.size / 2]
            }
            if (samples.size < MIN_DRIFT_SAMPLES ||
                nowUs - samples.first().atUs < MIN_DRIFT_OBSERVATION_US) {
                return Decision(filtered, null)
            }

            val direction = when {
                filtered > CORRECTION_ENTER_US -> 1
                filtered < -CORRECTION_ENTER_US -> -1
                kotlin.math.abs(filtered) <= CORRECTION_EXIT_US -> 0
                else -> stableDirection // 50~80ms滞回区保持当前方向，避免频繁来回切速。
            }
            if (direction != stableDirection) {
                stableDirection = direction
                stableSinceUs = nowUs
                return Decision(filtered, null)
            }
            if (nowUs - stableSinceUs < DIRECTION_HOLD_US || nowUs - lastDecisionUs < DECISION_GAP_US) {
                return Decision(filtered, null)
            }

            val target = when (direction) {
                1 -> (1f + filtered / CORRECTION_TIME_CONSTANT_US.toFloat())
                    .coerceIn(1f, MAX_CORRECTION_SPEED)
                -1 -> (1f + filtered / CORRECTION_TIME_CONSTANT_US.toFloat())
                    .coerceIn(MIN_CORRECTION_SPEED, 1f)
                else -> 1f
            }
            if (kotlin.math.abs(target - currentSpeed) < MIN_SPEED_CHANGE) {
                return Decision(filtered, null)
            }
            lastDecisionUs = nowUs
            return Decision(filtered, target)
        }

        fun reset() {
            samples.clear()
            stableDirection = 0
            stableSinceUs = 0L
            lastDecisionUs = 0L
        }
    }

    /** 把各缓存前沿和播放头统一投影到同一个手机单调时钟时刻。 */
    data class TimelineSnapshot(
        val comparisonAtUs: Long,
        val scheduledStartPhoneUs: Long,
        val playCalledPhoneUs: Long,
        val packetSentAtTvUs: Long,
        val receivedPtsUs: Long,
        val receivedAtPhoneUs: Long,
        val receivedSequence: Long,
        val networkTransitUs: Long,
        val queuedAtPhoneUs: Long,
        val newestQueuedEndPtsUs: Long,
        val writtenAtPhoneUs: Long,
        val writtenEndPtsUs: Long,
        val playbackSampleAtPhoneUs: Long,
        val playedPtsUs: Long,
        val playbackSampleAgeUs: Long,
        val applicationPendingUs: Long,
        val audioOutputPendingUs: Long,
        val totalPendingUs: Long,
        val queuedDurationUs: Long,
        val validPackets: Long,
        val missingPackets: Long,
        val gapEvents: Long,
        val maxPacketIntervalMs: Long,
        val underrunCount: Int,
        val filteredSkewUs: Long,
        val playbackMediaRate: Float
    )

    interface Listener {
        /** 工作线程已经把首批 PCM 放入暂停的 AudioTrack，才允许电视提交起播。 */
        fun onStartArmed()
        fun onStats(message: String)
        fun onPrepared(port: Int)
        fun onFirstAudioWritten()

        /** Android确认实际路由变化后回传，用于同步页面状态*/
        fun onOutputChanged(device: AudioDeviceInfo)

        fun onError(message: String)

        /** 音画偏差持续过大时停止本轮音频，但保留扫码建立的控制连接。 */
        fun onAlignmentRequired(message: String)
        fun onOutputReady()
        fun onBufferReady(firstPtsUs: Long, epoch: Long, bufferedDurationUs: Long)
    }

    private data class SyncPoint(val positionUs: Long, val atUs: Long, val receivedUs: Long)

    private class Session(
        val tvAddress: InetAddress,
        val streamId: UUID,
        val sampleRate: Int,
        val channels: Int,
        @Volatile var outputDevice: AudioDeviceInfo
    ) {
        @Volatile
        var stopped = false

        @Volatile
        var armRequested = false

        @Volatile
        var outputVerified = false

        @Volatile
        var playRequested = false
        @Volatile var startAtUs = 0L
        @Volatile var playCalledPhoneUs = -1L
        @Volatile var committed = false
        @Volatile var sync = SyncPoint(0, 0, 0)

        /** 普通暂停保留TCP、应用队列和AudioTrack；跳转/换片仍会stop并清空。 */
        @Volatile var held = false
        @Volatile var holdGeneration = 0L
        @Volatile var resumeGraceUntilUs = 0L
        @Volatile var track: AudioTrack? = null

        /** 动态切换扬声器/耳机时，系统路由需要短暂生效时间。 */
        @Volatile var routeChangeGraceUntilMs = 0L

        @Volatile
        var listenSocket: Closeable? = null

        @Volatile
        var streamSocket: Closeable? = null

        var worker: Thread? = null

        // TCP每帧约20ms。160帧可容纳约3.2秒；
        val queue = ArrayBlockingQueue<AudioPacket.Packet>(160)
        val queueLock = Any()
        val queuedFrames = AtomicLong(0)
        val maxReadMs = AtomicLong(0)
        val maxHighWaterMs = AtomicLong(0)
        val maxWriteMs = AtomicLong(0)
        val validPackets = AtomicLong(0)
        val networkMissingPackets = AtomicLong(0)
        val networkGapEvents = AtomicLong(0)
        val maxPacketIntervalMs = AtomicLong(0)

        /** 用于手机诊断面板，不参与播放控制。 */
        @Volatile var clockOffsetUs = Long.MIN_VALUE
        @Volatile var clockRttUs = -1L
        @Volatile var lastPacketSentTvUs = -1L
        @Volatile var lastPacketReceivedPhoneUs = -1L
        @Volatile var lastReceivedPtsUs = -1L
        @Volatile var lastReceivedSequence = -1L
        @Volatile var lastNetworkTransitUs = -1L
        @Volatile var lastQueueOfferPhoneUs = -1L
        @Volatile var lastQueuedPtsUs = -1L
        @Volatile var lastQueuedEndPtsUs = -1L
        @Volatile var lastTrackWritePhoneUs = -1L
        @Volatile var lastTrackWrittenEndPtsUs = -1L
        @Volatile var lastTrackTimestampPhoneUs = -1L
        @Volatile var lastTrackPlayedPtsUs = -1L
        @Volatile var playbackMediaRate = 1f
        @Volatile var filteredSkewUs = 0L

        @Volatile
        var bufferReady = false
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 这类异常可以通过电视端重新准备音频恢复，不应按连接故障处理。 */
    private class AlignmentRequiredException(message: String) : Exception(message)

    @Volatile
    private var session: Session? = null

    val hasActiveSession: Boolean
        get() = session != null

    /**
     * 按当前软件纠偏速度投影最近播放头。超过2秒的旧样本不再推算。
     */
    fun timelineSnapshot(atPhoneUs: Long = SystemClock.elapsedRealtimeNanos() / 1000): TimelineSnapshot? {
        val current = session ?: return null
        val sampleAt = current.lastTrackTimestampPhoneUs
        val samplePts = current.lastTrackPlayedPtsUs
        if (sampleAt < 0 || samplePts < 0) return null
        val ageUs = atPhoneUs - sampleAt
        val playedAtComparison = if (!current.held && ageUs in -2_000_000L..2_000_000L) {
            samplePts + (ageUs * current.playbackMediaRate).toLong()
        } else samplePts
        val queuedEnd = current.lastQueuedEndPtsUs
        val writtenEnd = current.lastTrackWrittenEndPtsUs
        fun nonNegativeDifference(laterPts: Long, earlierPts: Long): Long =
            if (laterPts >= 0 && earlierPts >= 0) (laterPts - earlierPts).coerceAtLeast(0L) else -1L
        return TimelineSnapshot(
            comparisonAtUs = atPhoneUs,
            scheduledStartPhoneUs = current.startAtUs,
            playCalledPhoneUs = current.playCalledPhoneUs,
            packetSentAtTvUs = current.lastPacketSentTvUs,
            receivedPtsUs = current.lastReceivedPtsUs,
            receivedAtPhoneUs = current.lastPacketReceivedPhoneUs,
            receivedSequence = current.lastReceivedSequence,
            networkTransitUs = current.lastNetworkTransitUs,
            queuedAtPhoneUs = current.lastQueueOfferPhoneUs,
            newestQueuedEndPtsUs = queuedEnd,
            writtenAtPhoneUs = current.lastTrackWritePhoneUs,
            writtenEndPtsUs = writtenEnd,
            playbackSampleAtPhoneUs = sampleAt,
            playedPtsUs = playedAtComparison,
            playbackSampleAgeUs = ageUs,
            applicationPendingUs = nonNegativeDifference(queuedEnd, writtenEnd),
            audioOutputPendingUs = nonNegativeDifference(writtenEnd, playedAtComparison),
            totalPendingUs = nonNegativeDifference(queuedEnd, playedAtComparison),
            queuedDurationUs = current.queuedFrames.get() * 1_000_000L / current.sampleRate,
            validPackets = current.validPackets.get(),
            missingPackets = current.networkMissingPackets.get(),
            gapEvents = current.networkGapEvents.get(),
            maxPacketIntervalMs = current.maxPacketIntervalMs.get(),
            underrunCount = current.track?.underrunCount ?: 0,
            filteredSkewUs = current.filteredSkewUs,
            playbackMediaRate = current.playbackMediaRate
        )
    }

    /**
     * 音频格式来自电视播放器；
     * outputDevice 来自手机实际检测到并选定的媒体输出设备：优先耳机，否则扬声器。
     */
    @Synchronized
    fun prepare(
        tvAddress: InetAddress,
        streamId: UUID,
        sampleRate: Int,
        channels: Int,
        outputDevice: AudioDeviceInfo
    ) {
        require(sampleRate in 8_000..192_000)
        require(channels in 1..2)
        require(isSupportedOutput(outputDevice)) { "手机没有可用的音频输出设备" }

        stop()

        val current = Session(
            tvAddress = tvAddress,
            streamId = streamId,
            sampleRate = sampleRate,
            channels = channels,
            outputDevice = outputDevice
        )

        session = current

        current.worker = Thread(
            { runSession(current) },
            "PrivateListeningPhoneAudio"
        ).also {
            it.start()
        }
    }

    //两个阶段：先确认耳机输出，再等待节目起播
    @Synchronized
    fun armOutput(): Boolean {
        val current = session ?: return false

        if (!isCurrent(current)) return false

        current.armRequested = true
        return true
    }

    /**
     * 在不销毁TCP、应用队列和播放时间线的前提下切换本机输出。
     * 每部手机各自执行此操作，不会暂停电视或影响其他成员。
     */
    @Synchronized
    fun switchOutputDevice(outputDevice: AudioDeviceInfo): Boolean {
        val current = session ?: return false
        val output = current.track ?: return false
        if (!isCurrent(current) || !isSupportedOutput(outputDevice)) return false
        if (current.outputDevice.id == outputDevice.id) return true

        return runCatching {
            // setPreferredDevice 生效前系统可能已经自动换路由，必须先关闭严格ID校验。
            current.routeChangeGraceUntilMs = SystemClock.elapsedRealtime() +
                if (isBluetoothOutput(outputDevice)) BLUETOOTH_ROUTE_CHANGE_GRACE_MS else ROUTE_CHANGE_GRACE_MS
            check(output.setPreferredDevice(outputDevice)) { "无法切换手机音频输出设备" }
            current.outputDevice = outputDevice
            Log.i(
                DIAGNOSTIC_TAG,
                "OUTPUT_ROUTE_SWITCH requestedId=${outputDevice.id} type=${outputDevice.type} name=${outputDevice.productName}"
            )
            true
        }.getOrElse {
            Log.e(DIAGNOSTIC_TAG, "OUTPUT_ROUTE_SWITCH_FAILED ${it.message}", it)
            false
        }
    }

    @Synchronized
    fun beginOutputRouteChange() {
        val current = session ?: return
        if (!isCurrent(current)) return
        current.routeChangeGraceUntilMs = maxOf(
            current.routeChangeGraceUntilMs,
            SystemClock.elapsedRealtime() + ROUTE_CHANGE_GRACE_MS
        )
    }

    /**
     * 后续收到正式起播指令时调用。尚未检查输出时，不允许直接播放。
     */
    @Synchronized
    fun schedulePlayback(startAtUs: Long): Boolean {
        val current = session ?: return false

        if (!isCurrent(current) || !current.outputVerified || !current.bufferReady
        ) {
            return false
        }

        val remaining = startAtUs - SystemClock.elapsedRealtimeNanos() / 1000
        if (remaining !in 150_000..3_000_000 || current.playRequested) return false
        current.startAtUs = startAtUs
        current.playRequested = true
        return true
    }

    fun commitPlayback(): Boolean {
        val current = session ?: return false
        if (!isCurrent(current) || !current.playRequested || current.committed ||
            current.startAtUs - SystemClock.elapsedRealtimeNanos() / 1000 < 30_000) return false
        current.committed = true
        return true
    }

    fun updateSync(positionUs: Long, phoneTimeUs: Long) {
        val current = session ?: return
        // 时间和位置成对发布，避免读取到两次SYNC各一半，误判为大偏差。
        current.sync = SyncPoint(positionUs, phoneTimeUs, SystemClock.elapsedRealtimeNanos() / 1000)
    }

    /** PairingServer测得 phoneClock - tvClock，用于把音频包发送时刻换算到手机时钟。 */
    fun updateClockCalibration(offsetUs: Long, roundTripUs: Long) {
        val current = session ?: return
        current.clockOffsetUs = offsetUs
        current.clockRttUs = roundTripUs
    }

    /**
     * 普通暂停：只冻结输出，不销毁本轮时间线。
     */
    @Synchronized
    fun holdPlayback(): Boolean {
        val current = session ?: return false
        val output = current.track ?: return false
        if (!isCurrent(current) || !current.committed || current.held) return false
        current.held = true
        current.holdGeneration += 1
        (current.streamSocket as? Socket)?.soTimeout = 0
        return runCatching { output.pause(); true }.getOrDefault(false)
    }

    /** 在电视换算后的手机单调时钟上恢复保留的AudioTrack。 */
    @Synchronized
    fun resumeHeldAt(startAtUs: Long): Boolean {
        val current = session ?: return false
        val output = current.track ?: return false
        val nowUs = SystemClock.elapsedRealtimeNanos() / 1000
        val remainingUs = startAtUs - nowUs
        if (!isCurrent(current) || !current.held || remainingUs !in 120_000..2_000_000) return false
        val generation = ++current.holdGeneration
        mainHandler.postDelayed({
            if (!isCurrent(current) || !current.held || current.holdGeneration != generation) return@postDelayed
            runCatching {
                (current.streamSocket as? Socket)?.soTimeout = DemoConfig.TCP_READ_TIMEOUT_MS
                current.startAtUs = startAtUs
                current.playCalledPhoneUs = SystemClock.elapsedRealtimeNanos() / 1000
                output.play()
                current.held = false
                current.sync = SyncPoint(0, 0, 0)
                current.resumeGraceUntilUs = startAtUs + 3_000_000L
            }.onFailure { reportError(current, Exception("恢复耳机播放失败", it)) }
        }, maxOf(1L, remainingUs / 1000))
        return true
    }

    @Synchronized
    fun stop() {
        val old = session
        session = null

        if (old != null) {
            old.stopped = true
            old.streamSocket?.close()
            old.listenSocket?.close()
            old.worker?.interrupt()
            synchronized(old.queueLock) { old.queue.clear(); old.queuedFrames.set(0) }
        }
    }

    private fun packetFrames(packet: AudioPacket.Packet): Long =
        packet.pcm.size.toLong() / (packet.channels * 2)
    private fun offerPacket(current: Session, packet: AudioPacket.Packet): Boolean =
        synchronized(current.queueLock) {
            if (!isCurrent(current) || !current.queue.offer(packet)) false
            else {
                current.queuedFrames.addAndGet(packetFrames(packet))
                current.lastQueueOfferPhoneUs = SystemClock.elapsedRealtimeNanos() / 1000
                current.lastQueuedPtsUs = packet.ptsUs
                current.lastQueuedEndPtsUs = packet.ptsUs +
                    packetFrames(packet) * 1_000_000L / current.sampleRate
                true
            }
        }

    private fun recordPacketArrival(current: Session, packet: AudioPacket.Packet, receivedPhoneUs: Long) {
        current.lastPacketSentTvUs = packet.sentAtTvUs
        current.lastPacketReceivedPhoneUs = receivedPhoneUs
        current.lastReceivedPtsUs = packet.ptsUs
        current.lastReceivedSequence = packet.sequence
        val offset = current.clockOffsetUs
        current.lastNetworkTransitUs = if (packet.sentAtTvUs > 0 && offset != Long.MIN_VALUE) {
            receivedPhoneUs - (packet.sentAtTvUs + offset)
        } else -1L
    }

    private fun pollPacket(current: Session): AudioPacket.Packet? = synchronized(current.queueLock) {
        current.queue.poll()?.also { current.queuedFrames.addAndGet(-packetFrames(it)) }
    }

    private fun pollPacket(current: Session, timeout: Long, unit: TimeUnit): AudioPacket.Packet? {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        do {
            pollPacket(current)?.let { return it }
            if (!isCurrent(current) || System.nanoTime() >= deadline) return null
            Thread.sleep(2)
        } while (true)
    }

    private fun updateMaximum(target: AtomicLong, value: Long) {
        var previous = target.get()
        while (value > previous && !target.compareAndSet(previous, value)) {
            previous = target.get()
        }
    }

    private fun isCurrent(current: Session): Boolean {
        return !current.stopped && session === current
    }

    private fun reportError(
        current: Session,
        error: Exception
    ) {
        Log.e(
            DIAGNOSTIC_TAG,
            "AUDIO_PIPELINE_ERROR ${error.javaClass.simpleName}: ${error.message}",
            error
        )
        mainHandler.postDelayed({
            // 只检查它是否仍属于当前会话。
            if (session === current) {
                stop()
                listener.onError(error.message ?: "手机音频处理失败")
            }
        }, if (error is java.io.EOFException) 250L else 0L)
    }

    private fun reportAlignmentRequired(
        current: Session,
        error: AlignmentRequiredException
    ) {
        mainHandler.post {
            if (session === current) {
                stop()
                listener.onAlignmentRequired(error.message ?: "音画需要重新对齐")
            }
        }
    }

    private fun isSupportedOutput(device: AudioDeviceInfo): Boolean {
        if (!device.isSink) return false

        return when (device.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> true

            else -> {
                (Build.VERSION.SDK_INT >= 26 && device.type in setOf(
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_ACCESSORY
                )) || (Build.VERSION.SDK_INT >= 28 && device.type == AudioDeviceInfo.TYPE_HEARING_AID) ||
                    (Build.VERSION.SDK_INT >= 31 && device.type in setOf(
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_BLE_SPEAKER
                    ))
            }
        }
    }

    private fun isUsbOutput(device: AudioDeviceInfo): Boolean =
        Build.VERSION.SDK_INT >= 26 && device.type in setOf(
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY
        )

    private fun isBluetoothOutput(device: AudioDeviceInfo): Boolean = when {
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> true
        Build.VERSION.SDK_INT >= 28 && device.type == AudioDeviceInfo.TYPE_HEARING_AID -> true
        Build.VERSION.SDK_INT >= 31 && device.type in setOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER
        ) -> true
        else -> false
    }

    /**
     * 按电视提供的真实采样率和声道数创建 PCM 输出。
     */
    private fun createTrack(current: Session): AudioTrack {
        val channelMask =
            if (current.channels == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }

        val minimumBytes = AudioTrack.getMinBufferSize(
            current.sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )

        check(minimumBytes > 0) { "手机不支持当前音频格式" }

        val bytesPerFrame = current.channels * 2

        // 为实时TCP准备约320ms物理容量，运行时请求约240ms有效缓冲。
        // 这些是抗抖储备
        val requestedBytes = current.sampleRate * bytesPerFrame * INITIAL_CAPACITY_MS / 1000

        val rawSize = maxOf(minimumBytes, requestedBytes)
        val alignedSize = ((rawSize + bytesPerFrame - 1) / bytesPerFrame) * bytesPerFrame

        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(current.sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(alignedSize)

        if (Build.VERSION.SDK_INT >= 26 &&
            !isUsbOutput(current.outputDevice) && !isBluetoothOutput(current.outputDevice)) {
            // 3.5mm耳机/扬声器可请求低延迟；USB声卡和蓝牙链路有各自的系统缓冲，
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }

        val track = builder.build()

        try {
            check(track.state == AudioTrack.STATE_INITIALIZED) { "手机音频输出初始化失败" }

            // 先保持静音，实际路由检查通过后再输出节目声音。
            // AudioTrack只保留约240ms低层播放缓冲。起播总储备约500ms，
            // 正式播放后应用队列再渐进增长到约2秒，避免把长缓存全部交给系统。
            val resizedFrames = track.setBufferSizeInFrames(
                maxOf(1, current.sampleRate * OUTPUT_BUFFER_MS / 1000)
            )
            check(resizedFrames > 0) { "无法设置手机音频缓冲" }
            if (Build.VERSION.SDK_INT >= 31) {
                // 系统内部重新启动时至少积累约160ms。
                track.setStartThresholdInFrames(
                    minOf(track.bufferSizeInFrames, current.sampleRate * START_THRESHOLD_MS / 1000)
                )
            }
            track.setVolume(0f)

            check(track.setPreferredDevice(current.outputDevice)) { "无法请求使用选定的音频输出设备" }

            track.addOnRoutingChangedListener(
                { routing ->
                    val actual = (routing as? AudioTrack)?.routedDevice ?: return@addOnRoutingChangedListener
                    if (!isCurrent(current) || !isSupportedOutput(actual)) return@addOnRoutingChangedListener
                    if (current.outputDevice.id != actual.id) {
                        current.outputDevice = actual
                        current.routeChangeGraceUntilMs = SystemClock.elapsedRealtime() +
                            if (isBluetoothOutput(actual)) BLUETOOTH_ROUTE_CHANGE_GRACE_MS else ROUTE_CHANGE_GRACE_MS
                    }
                    listener.onOutputChanged(actual)
                },
                mainHandler
            )

            return track
        } catch (error: Exception) {
            track.release()
            throw error
        }
    }

    /**
     * 专门接收网络数据。
     */
    private fun receivePackets(
        current: Session,
        socket: DatagramSocket
    ) {
        // 多分配一个字节，可以识别超过协议上限的 UDP 包。
        val buffer = ByteArray(AudioPacket.MAX_DATAGRAM_BYTES + 1)
        val datagram = DatagramPacket(buffer, buffer.size)

        var newestEpoch = -1L
        var lastSequence = -1L
        // 以下变量只由接收线程访问。
        var startupFirstPtsUs: Long? = null
        var startupEpoch = -1L
        var startupFrames = 0L
        var startupNextPtsUs = 0L
        var expectedStartupSequence = 0L
        val window = com.example.privatelistening.common.ListeningTiming.PacketWindow()
        var lastReceived = SystemClock.elapsedRealtime()
        var previousPacketAt = 0L
        fun nextPacket(): AudioPacket.Packet? {
            while (isCurrent(current)) {
                val now = SystemClock.elapsedRealtime()
                window.poll(now, current.committed)?.let { return it }
                check(!current.committed || now - lastReceived < 2500) { "音频数据中断，已停止收听" }
                datagram.length = buffer.size
                try { socket.receive(datagram) } catch (_: SocketTimeoutException) { continue }
                if (datagram.address != current.tvAddress) continue
                val p = AudioPacket.decode(datagram.data, datagram.offset, datagram.length) ?: continue
                if (p.streamId != current.streamId) continue
                check(p.sampleRate == current.sampleRate && p.channels == current.channels) { "音频格式变化" }
                if (window.offer(p)) {
                    recordPacketArrival(current, p, SystemClock.elapsedRealtimeNanos() / 1000)
                    val receivedAt = SystemClock.elapsedRealtime()
                    if (previousPacketAt != 0L) {
                        updateMaximum(current.maxPacketIntervalMs, receivedAt - previousPacketAt)
                    }
                    previousPacketAt = receivedAt
                    lastReceived = receivedAt
                    current.validPackets.incrementAndGet()
                }
            }
            return null
        }

        try {
            while (isCurrent(current)) {
                val packet = nextPacket() ?: break
                if (!current.playRequested) {
                    check(current.outputVerified) { "输出检查完成前收到起播音频" }
                    // 经过乱序窗口后，起播仍要求严格连续；缺包不能伪装成准备成功。
                    check(packet.sequence == expectedStartupSequence) { "起播音频丢包或乱序，请重新开始。" }

                    if (startupFirstPtsUs == null) {
                        startupFirstPtsUs = packet.ptsUs
                        startupEpoch = packet.epoch
                        startupNextPtsUs = packet.ptsUs
                    }

                    check(packet.epoch == startupEpoch) { "起播音频时间线发生变化，请重新开始。" }
                    check(kotlin.math.abs(packet.ptsUs - startupNextPtsUs) <= 100L) { "起播音频不连续，请重新开始。" }

                    val frames = packet.pcm.size / (packet.channels * 2)
                    check((startupFrames + frames) * 1_000_000L / packet.sampleRate <= MAX_STARTUP_BUFFER_US) { "起播音频超过时长上限，未继续启动。" }
                    check(offerPacket(current, packet)) { "起播音频缓冲已满，未继续启动。" }

                    startupFrames += frames
                    startupNextPtsUs = packet.ptsUs + frames.toLong() * 1_000_000L / packet.sampleRate

                    expectedStartupSequence += 1

                    // 让后续连续播放阶段从当前序号与时间线继续。
                    newestEpoch = packet.epoch
                    lastSequence = packet.sequence

                    val durationUs = startupFrames * 1_000_000L / packet.sampleRate

                    if (!current.bufferReady && durationUs >= STARTUP_READY_US) {
                        current.bufferReady = true

                        val firstPtsUs = checkNotNull(startupFirstPtsUs)
                        val readyEpoch = startupEpoch

                        mainHandler.post {
                            if (isCurrent(current)) {
                                listener.onBufferReady(
                                    firstPtsUs = firstPtsUs,
                                    epoch = readyEpoch,
                                    bufferedDurationUs = durationUs
                                )
                            }
                        }
                    }

                    continue
                }

                check(packet.epoch == newestEpoch) { "电视音频时间线变化，请重新准备" }
                if (packet.sequence <= lastSequence) continue
                val missing = packet.sequence - lastSequence - 1
                if (missing > 0) {
                    current.networkMissingPackets.addAndGet(missing)
                    current.networkGapEvents.incrementAndGet()
                    Log.w(
                        DIAGNOSTIC_TAG,
                        "UDP_GAP missingPackets=$missing afterSequence=$lastSequence " +
                            "nextSequence=${packet.sequence} queueFrames=${current.queuedFrames.get()}"
                    )
                }
                lastSequence = packet.sequence
                check(offerPacket(current, packet)) { "手机音频积压，请重新开始" }

            }
        } catch (error: Exception) {
            if (isCurrent(current)) {
                // 先使已排队的“缓冲成功”回调失效，再报告错误。
                // 否则接收线程发现后续起播包有缺口时，旧成功事件仍可能被发送。
                current.stopped = true
                current.streamSocket?.close()
                current.listenSocket?.close()
                current.worker?.interrupt()
                reportError(current, error)
            }
        }
    }

    /**
     * TCP 已保证字节完整有序。
     * 如果序号不连续，说明应用协议或发送端发生错误，直接重建本轮音频流。
     */
    private fun receiveTcpPackets(current: Session, server: ServerSocket) {
        try {
            val socket = server.accept()
            current.streamSocket = socket
            runCatching { server.close() }
            current.listenSocket = null

            check(socket.inetAddress == current.tvAddress) { "音频连接来源与已配对电视不一致" }
            socket.tcpNoDelay = true
            socket.keepAlive = true
            // 多人预填后可能还需等待其他成员超时；正式流到达后恢复播放超时。
            // 不重试半帧读取，防止超时后TCP帧边界错位。
            socket.soTimeout = 20_000
            socket.receiveBufferSize = TCP_SOCKET_BUFFER_BYTES

            val input = DataInputStream(
                BufferedInputStream(socket.getInputStream(), TCP_STREAM_BUFFER_BYTES)
            )
            var newestEpoch = -1L
            var lastSequence = -1L
            var startupFirstPtsUs: Long? = null
            var startupEpoch = -1L
            var startupFrames = 0L
            var startupNextPtsUs = 0L
            var expectedStartupSequence = 0L
            var previousFrameAt = 0L
            var measuringContinuousPlayback = false

            while (isCurrent(current)) {
                val waitStart = SystemClock.elapsedRealtime()
                while (current.playRequested &&
                    (current.held || current.queuedFrames.get() * 1000 / current.sampleRate >= APPLICATION_HIGH_WATER_MS) &&
                    isCurrent(current)) {
                    // 应用队列到达低延迟高水位后暂停读取，让 TCP 自身限制电视发送速度。
                    // 电视端连续播放路径不会再因此阻塞播放器或视频画面。
                    Thread.sleep(2)
                }
                updateMaximum(current.maxHighWaterMs, SystemClock.elapsedRealtime() - waitStart)
                val readStart = SystemClock.elapsedRealtime()
                val packet = AudioStreamFrame.read(input)
                val receivedPhoneUs = SystemClock.elapsedRealtimeNanos() / 1000
                updateMaximum(current.maxReadMs, SystemClock.elapsedRealtime() - readStart)
                check(packet.streamId == current.streamId) { "TCP音频流标识不匹配" }
                check(packet.sampleRate == current.sampleRate && packet.channels == current.channels) { "TCP音频格式发生变化" }
                recordPacketArrival(current, packet, receivedPhoneUs)
                val receivedAt = SystemClock.elapsedRealtime()

                // 起播预填完成后，电视会有意等待统一的播放时刻。
                // 这段等待不是网络卡顿，因此正式流的第一帧重新开始统计收包间隔。
                if (current.playRequested && !measuringContinuousPlayback) {
                    socket.soTimeout = DemoConfig.TCP_READ_TIMEOUT_MS
                    measuringContinuousPlayback = true
                    previousFrameAt = 0L
                    current.maxPacketIntervalMs.set(0L)
                }
                if (current.playRequested && previousFrameAt != 0L) {
                    updateMaximum(current.maxPacketIntervalMs, receivedAt - previousFrameAt)
                }
                previousFrameAt = receivedAt
                current.validPackets.incrementAndGet()

                if (!current.playRequested) {
                    check(current.outputVerified) { "输出检查完成前收到起播音频" }
                    check(packet.sequence == expectedStartupSequence) { "TCP起播音频序号不连续" }
                    if (startupFirstPtsUs == null) {
                        startupFirstPtsUs = packet.ptsUs
                        startupEpoch = packet.epoch
                        startupNextPtsUs = packet.ptsUs
                    }
                    check(packet.epoch == startupEpoch) { "起播音频时间线发生变化" }
                    check(kotlin.math.abs(packet.ptsUs - startupNextPtsUs) <= 100L) { "起播音频PTS不连续" }
                    val frames = packet.pcm.size / (packet.channels * 2)
                    check((startupFrames + frames) * 1_000_000L / packet.sampleRate <= MAX_STARTUP_BUFFER_US) { "起播音频超过时长上限" }
                    check(offerPacket(current, packet)) { "起播音频缓冲已满" }
                    startupFrames += frames
                    startupNextPtsUs = packet.ptsUs + frames.toLong() * 1_000_000L / packet.sampleRate
                    expectedStartupSequence += 1
                    newestEpoch = packet.epoch
                    lastSequence = packet.sequence
                    val durationUs = startupFrames * 1_000_000L / packet.sampleRate
                    if (!current.bufferReady && durationUs >= STARTUP_READY_US) {
                        current.bufferReady = true
                        val firstPtsUs = checkNotNull(startupFirstPtsUs)
                        val readyEpoch = startupEpoch
                        mainHandler.post {
                            if (isCurrent(current)) {
                                listener.onBufferReady(firstPtsUs, readyEpoch, durationUs)
                            }
                        }
                    }
                    continue
                }

                check(packet.epoch == newestEpoch) { "电视音频时间线变化，请重新准备" }
                check(packet.sequence == lastSequence + 1) { "TCP音频序号不连续，请重新准备" }
                lastSequence = packet.sequence
                check(offerPacket(current, packet)) { "手机音频积压，请重新开始" }
            }
        } catch (error: Exception) {
            if (isCurrent(current)) {
                current.stopped = true
                current.streamSocket?.close()
                current.listenSocket?.close()
                current.worker?.interrupt()
                if (error is java.io.EOFException || error is java.net.SocketException) {
                    // 电视在暂停、对齐或换流时会主动关闭旧TCP。EOF/SocketException表示 对端结束了这轮字节流，并不等于手机AudioTrack处理失败。
                    // 电视端是流生命周期的唯一协调者，它会通过WebSocket下发PAUSE/新PREPARE；
                    // 手机若再上报AUDIO_RECOVERY_REQUIRED，会把一次恢复放大成第二次恢复。
                    Log.i(DIAGNOSTIC_TAG, "AUDIO_STREAM_CLOSED_BY_TV ${error.javaClass.simpleName}")
                } else {
                    reportError(current, error)
                }
            }
        }
    }

    private fun writePcm(
        current: Session,
        track: AudioTrack,
        data: ByteArray,
        checkRoute: Boolean
    ) {
        var offset = 0
        var lastProgressAt = SystemClock.elapsedRealtime()

        while (offset < data.size && isCurrent(current)) {
            val routeCheckAt = SystemClock.elapsedRealtime()
            if (checkRoute && routeCheckAt >= current.routeChangeGraceUntilMs) {
                val actualDevice = track.routedDevice
                when {
                    // 某些系统在路由交接瞬间暂时返回null；继续播放并稍后复查。
                    actualDevice == null -> {
                        current.routeChangeGraceUntilMs = routeCheckAt + ROUTE_PROBE_RETRY_MS
                    }
                    actualDevice.id == current.outputDevice.id -> Unit
                    // Android已经在支持的设备间完成了自动切换，直接接纳，不能把它当故障。
                    isSupportedOutput(actualDevice) -> {
                        Log.i(
                            DIAGNOSTIC_TAG,
                            "OUTPUT_ROUTE_AUTO_ACCEPT oldId=${current.outputDevice.id} " +
                                "actualId=${actualDevice.id} type=${actualDevice.type} name=${actualDevice.productName}"
                        )
                        current.outputDevice = actualDevice
                        current.routeChangeGraceUntilMs = routeCheckAt + ROUTE_CHANGE_GRACE_MS
                        mainHandler.post {
                            if (isCurrent(current)) listener.onOutputChanged(actualDevice)
                        }
                    }
                    else -> throw IllegalStateException("手机音频进入了不支持的输出设备")
                }
            }

            val writeStart = SystemClock.elapsedRealtime()
            val written = track.write(
                data,
                offset,
                data.size - offset,
                AudioTrack.WRITE_BLOCKING
            )

            updateMaximum(current.maxWriteMs, SystemClock.elapsedRealtime() - writeStart)
            check(written >= 0) { "音频写入失败，错误码：$written" }

            if (written > 0) {
                offset += written
                lastProgressAt = SystemClock.elapsedRealtime()
            } else {
                check(SystemClock.elapsedRealtime() - lastProgressAt < 2_000) { "手机音频输出长时间没有响应" }
                Thread.sleep(2)
            }
        }
    }

    private fun fadeInPcm(data: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val bytesPerFrame = channels * 2
        val frameCount = data.size / bytesPerFrame
        val fadeFrames = minOf(frameCount, sampleRate * CLICK_FADE_MS / 1000)
        if (fadeFrames <= 1) return data
        val output = data.copyOf()
        for (frame in 0 until fadeFrames) {
            val gain = frame.toFloat() / fadeFrames
            for (channel in 0 until channels) {
                val offset = frame * bytesPerFrame + channel * 2
                writePcm16Le(output, offset, (readPcm16Le(output, offset) * gain).toInt())
            }
        }
        return output
    }

    private fun createSmoothedGap(
        lastFrame: IntArray,
        gapFrames: Int,
        sampleRate: Int,
        channels: Int
    ): ByteArray {
        val bytesPerFrame = channels * 2
        val output = ByteArray(gapFrames * bytesPerFrame)
        val fadeFrames = minOf(gapFrames, sampleRate * CLICK_FADE_MS / 1000)
        if (fadeFrames <= 0) return output
        for (frame in 0 until fadeFrames) {
            val gain = 1f - (frame + 1).toFloat() / fadeFrames
            for (channel in 0 until channels) {
                writePcm16Le(
                    output,
                    frame * bytesPerFrame + channel * 2,
                    (lastFrame[channel] * gain).toInt()
                )
            }
        }
        return output
    }

    private fun rememberLastFrame(data: ByteArray, channels: Int, destination: IntArray) {
        val bytesPerFrame = channels * 2
        if (data.size < bytesPerFrame) return
        val frameOffset = data.size - bytesPerFrame
        for (channel in 0 until channels) {
            destination[channel] = readPcm16Le(data, frameOffset + channel * 2)
        }
    }

    private fun readPcm16Le(data: ByteArray, offset: Int): Int =
        (((data[offset + 1].toInt() shl 8) or (data[offset].toInt() and 0xFF)).toShort()).toInt()

    private fun writePcm16Le(data: ByteArray, offset: Int, value: Int) {
        val sample = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        data[offset] = (sample and 0xFF).toByte()
        data[offset + 1] = ((sample ushr 8) and 0xFF).toByte()
    }

    /**
     * 使用静音数据启动输出，以便查询实际路由。
     * 检查通过前，不写入节目声音。
     */
    private fun verifyRoute(
        current: Session,
        track: AudioTrack
    ) {
        track.setVolume(0f)
        track.play()

        val bytesPerFrame = current.channels * 2

        // 约 10ms 的静音采样。
        val silence = ByteArray(maxOf(1, current.sampleRate / 100) * bytesPerFrame)

        val deadline = SystemClock.elapsedRealtime() +
            if (isBluetoothOutput(current.outputDevice)) BLUETOOTH_ROUTE_VERIFY_MS else 1_500L

        while (isCurrent(current)) {
            writePcm(
                current = current,
                track = track,
                data = silence,
                checkRoute = false
            )

            if (track.routedDevice?.id == current.outputDevice.id) {
                track.pause()
                track.flush()
                // USB路由建立后系统可能重配缓冲，重新请求并记录实际值。
                val actual = track.setBufferSizeInFrames(current.sampleRate * OUTPUT_BUFFER_MS / 1000)
                Log.i(DIAGNOSTIC_TAG, "ROUTE_READY sdk=${Build.VERSION.SDK_INT} requestedMs=$OUTPUT_BUFFER_MS actualFrames=$actual capacityFrames=${track.bufferCapacityInFrames}")
                return
            }

            check(SystemClock.elapsedRealtime() < deadline) { "音频没有路由到选定输出设备，请检查手机音频设置" }
            Thread.sleep(10)
        }
    }

    /**
     * 一个工作线程负责创建并最终释放 AudioTrack。另一个线程专门接收 TCP。
     */
    private fun runSession(current: Session) {
        var track: AudioTrack? = null

        try {
            // AudioTrack写入线程使用音频优先级，降低GC或普通后台任务抢占造成的瞬时断粮。
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val output = createTrack(current)
            track = output
            current.track = output

            // 端口为0，让系统选择当前可用端口。TCP默认由手机监听，电视按已认证 WebSocket对端地址回连
            val actualPort = if (DemoConfig.AUDIO_TRANSPORT == DemoConfig.AudioTransport.TCP) {
                val server = ServerSocket(0).apply { reuseAddress = true }
                current.listenSocket = server
                Thread(
                    { receiveTcpPackets(current, server) },
                    "PrivateListeningTcpReceiver"
                ).start()
                server.localPort
            } else {
                val receiver = DatagramSocket(0).apply { soTimeout = 5 }
                current.listenSocket = receiver
                Thread(
                    { receivePackets(current, receiver) },
                    "PrivateListeningUdpReceiver"
                ).start()
                receiver.localPort
            }

            if (!isCurrent(current)) return

            mainHandler.post {
                if (isCurrent(current)) {
                    listener.onPrepared(actualPort)
                }
            }

            // 第一阶段：等待用户在电视上点击开始。
            val armDeadline = SystemClock.elapsedRealtime() + 60_000

            while (isCurrent(current) && !current.armRequested) {
                check(SystemClock.elapsedRealtime() < armDeadline) { "等待电视开始超时，请重新扫码。" }
                Thread.sleep(10)
            }

            if (!isCurrent(current)) return

        // 只使用静音数据检查实际输出设备。
        // verifyRoute 成功时，AudioTrack 已暂停并清空静音缓冲。
            verifyRoute(current, output)

            if (!isCurrent(current)) return

            current.outputVerified = true

            mainHandler.post {
                if (isCurrent(current)) {
                    listener.onOutputReady()
                }
            }

            // 第二阶段：输出检查通过，但仍然不播放节目。
            // 收到 START_AT 后，schedulePlayback 发布单调时钟上的起播时刻。
            // 覆盖全组输出检查、电视预解码、缓冲确认、校时和约定起播的上限。
            val startDeadline = SystemClock.elapsedRealtime() + 25_000

            while (isCurrent(current) && !current.playRequested) {
                check(SystemClock.elapsedRealtime() < startDeadline) { "输出已准备，但未收到正式起播指令，请重新扫码。" }
                Thread.sleep(10)
            }

            if (!isCurrent(current)) return

            // 先预填实际输出缓冲，再报告 START_ARMED；
            // 双阶段提交：电视收到 START_ARMED 才发送 COMMIT_START。
            val first = checkNotNull(pollPacket(current)) { "起播队列为空" }
            val anchorPtsUs = first.ptsUs
            val timeStretch = AdaptivePcmTimeStretch(current.sampleRate, current.channels)
            // 新流第一段从0平滑进入，避免AudioTrack由静音直接跳到非零采样产生爆音。
            val firstPcm = fadeInPcm(first.pcm, current.sampleRate, current.channels)
            val bytesPerFrame = current.channels * 2
            var sourceFramesConsumed = first.pcm.size.toLong() / bytesPerFrame
            val firstOutput = timeStretch.process(firstPcm)
            if (firstOutput.isNotEmpty()) writePcm(current, output, firstOutput, false)
            current.lastTrackWritePhoneUs = SystemClock.elapsedRealtimeNanos() / 1000
            current.lastTrackWrittenEndPtsUs = anchorPtsUs +
                timeStretch.mediaDurationForOutputFrames(timeStretch.outputFrames)
            val lastOutputFrame = IntArray(current.channels)
            rememberLastFrame(firstOutput, current.channels, lastOutputFrame)
            val capacity = output.bufferSizeInFrames
            // 起播总储备约500ms；AudioTrack只接收其物理容量，剩余未来PCM留在应用队列。
            // 正式播放后电视逐步扩大提前发送窗口，应用队列会继续增长到约2秒。
            val targetFrames = minOf(capacity, current.sampleRate * STARTUP_PREFILL_MS / 1000)
            while (isCurrent(current) && timeStretch.outputFrames < targetFrames) {
                val next = current.queue.peek() ?: break
                val frames = next.pcm.size / bytesPerFrame
                if (timeStretch.outputFrames + frames > capacity) break // 暂停的声卡不会消费，不能等待腾出空间。
                check(next.epoch == first.epoch && kotlin.math.abs(next.ptsUs -
                    (anchorPtsUs + sourceFramesConsumed * 1_000_000L / current.sampleRate)) <= 100) { "预填音频不连续，未提交起播" }
                pollPacket(current)
                val stretched = timeStretch.process(next.pcm)
                if (stretched.isNotEmpty()) writePcm(current, output, stretched, false)
                sourceFramesConsumed += frames
                current.lastTrackWritePhoneUs = SystemClock.elapsedRealtimeNanos() / 1000
                current.lastTrackWrittenEndPtsUs = anchorPtsUs +
                    timeStretch.mediaDurationForOutputFrames(timeStretch.outputFrames)
                rememberLastFrame(stretched, current.channels, lastOutputFrame)
            }
            mainHandler.post { if (isCurrent(current)) listener.onStartArmed() }
            while (isCurrent(current) && SystemClock.elapsedRealtimeNanos() / 1000 < current.startAtUs) {
                Thread.sleep(1)
            }
            if (!isCurrent(current)) return
            check(current.committed) { "未收到电视起播提交，未播放声音" }
            check(SystemClock.elapsedRealtimeNanos() / 1000 - current.startAtUs < 80_000) { "起播调度超时" }
            output.setVolume(1f)
            current.playCalledPhoneUs = SystemClock.elapsedRealtimeNanos() / 1000
            output.play()
            mainHandler.post { if (isCurrent(current)) listener.onFirstAudioWritten() }

            var correctionAt = 0L
            var lastStatsAt = 0L
            var skewUs: Long? = null
            var networkConcealedFrames = 0L
            var sourceGapFrames = 0L
            var lastWrittenSequence = first.sequence
            var largeErrorSince = 0L
            var observedUnderruns = output.underrunCount
            var bufferExpanded = false
            var resumedFromHold = false
            val driftController = DriftController()
            val timestamp = android.media.AudioTimestamp()
            while (isCurrent(current)) {
                if (current.held) {
                    resumedFromHold = true
                    Thread.sleep(2)
                    continue
                }
                if (resumedFromHold) {
                    // 暂停时长不能计入偏差持续时间；恢复后等待新的SYNC再判断。
                    largeErrorSince = 0L
                    correctionAt = SystemClock.elapsedRealtimeNanos() / 1000
                    driftController.reset()
                    resumedFromHold = false
                }
                val packet = pollPacket(current, 20, TimeUnit.MILLISECONDS) ?: continue
                check(packet.epoch == first.epoch) { "声音时间线已改变" }
                val expectedPts = anchorPtsUs + sourceFramesConsumed * 1_000_000L / current.sampleRate
                val gapUs = packet.ptsUs - expectedPts
                check(kotlin.math.abs(gapUs) < 120_000) { "PCM时间线缺口过大：${gapUs}us，需要恢复音频通道" }
                // 丢包补等长静音，保留时间轴；不能把后面的声音直接前移。
                val gapFrames = PcmFramer.gapFrames(gapUs, current.sampleRate)
                if (gapFrames > 0) {
                    val smoothedGap = createSmoothedGap(
                        lastOutputFrame, gapFrames, current.sampleRate, current.channels
                    )
                    val stretchedGap = timeStretch.process(smoothedGap)
                    if (stretchedGap.isNotEmpty()) writePcm(current, output, stretchedGap, true)
                    sourceFramesConsumed += gapFrames
                    if (packet.sequence > lastWrittenSequence + 1) {
                        networkConcealedFrames += gapFrames
                    } else {
                        sourceGapFrames += gapFrames
                        Log.w(
                            DIAGNOSTIC_TAG,
                            "SOURCE_PTS_GAP gapUs=$gapUs sequence=${packet.sequence} " +
                                "queueFrames=${current.queuedFrames.get()}"
                        )
                    }
                    rememberLastFrame(stretchedGap, current.channels, lastOutputFrame)
                }
                val skip = (-gapFrames).coerceAtLeast(0).coerceAtMost(packet.pcm.size / bytesPerFrame)
                val rawData = if (skip == 0) packet.pcm else packet.pcm.copyOfRange(skip * bytesPerFrame, packet.pcm.size)
                val data = if (gapFrames > 0) {
                    fadeInPcm(rawData, current.sampleRate, current.channels)
                } else rawData
                val stretchedData = timeStretch.process(data)
                if (stretchedData.isNotEmpty()) writePcm(current, output, stretchedData, true)
                sourceFramesConsumed += data.size / bytesPerFrame
                current.lastTrackWritePhoneUs = SystemClock.elapsedRealtimeNanos() / 1000
                current.lastTrackWrittenEndPtsUs = anchorPtsUs +
                    timeStretch.mediaDurationForOutputFrames(timeStretch.outputFrames)
                lastWrittenSequence = packet.sequence
                rememberLastFrame(stretchedData, current.channels, lastOutputFrame)

                val now = SystemClock.elapsedRealtimeNanos() / 1000
                val currentUnderruns = output.underrunCount
                if (currentUnderruns > observedUnderruns) {
                    Log.w(
                        DIAGNOSTIC_TAG,
                        "AUDIOTRACK_UNDERRUN total=$currentUnderruns " +
                            "delta=${currentUnderruns - observedUnderruns} " +
                            "queueFrames=${current.queuedFrames.get()}"
                    )
                    if (!bufferExpanded) {
                        // 真机仍发生播放欠载时，把有效容量从240ms提高到280ms。
                        val expanded = output.setBufferSizeInFrames(
                            maxOf(1, current.sampleRate * ADAPTIVE_BUFFER_MS / 1000)
                        )
                        bufferExpanded = expanded > 0
                    }
                }
                observedUnderruns = currentUnderruns
                val sync = current.sync
                check((sync.receivedUs != 0L && now - sync.receivedUs < 3_000_000) ||
                    now - current.startAtUs < 3_000_000 || now < current.resumeGraceUntilUs) { "电视同步信号中断" }
                if (now - correctionAt >= CORRECTION_SAMPLE_INTERVAL_US &&
                    sync.atUs > 0 && output.getTimestamp(timestamp)) {
                    correctionAt = now
                    // AudioTimestamp 使用 nanoTime；换算到当前时刻后再与媒体位置比较。
                    val outputAgeUs = (System.nanoTime() - timestamp.nanoTime) / 1000
                    val projectedOutputFrames = timestamp.framePosition +
                        outputAgeUs.coerceAtLeast(0L) * current.sampleRate / 1_000_000L
                    if (outputAgeUs in -20_000..300_000 &&
                        timestamp.framePosition in 0..timeStretch.outputFrames &&
                        projectedOutputFrames <= timeStretch.outputFrames + current.sampleRate / 10) {
                        val audiblePts = anchorPtsUs +
                            timeStretch.mediaDurationForOutputFrames(projectedOutputFrames)
                        val actualRate = timeStretch.mediaRateForOutputFrames(projectedOutputFrames)
                        current.lastTrackTimestampPhoneUs = now
                        current.lastTrackPlayedPtsUs = audiblePts
                        current.playbackMediaRate = actualRate
                        val targetPts = sync.positionUs + now - sync.atUs
                        val errorUs = targetPts - audiblePts
                        skewUs = errorUs
                        val decision = driftController.update(
                            nowUs = now,
                            errorUs = errorUs,
                            currentSpeed = timeStretch.requestedSpeed
                        )
                        current.filteredSkewUs = decision.filteredErrorUs
                        // 中等偏差在手机本地通过保持音调的PCM时间伸缩逐步吸收，不暂停电视、 不清空应用队列，也不影响同一会话中的其他手机。
                        if (now - current.startAtUs >= CORRECTION_START_GRACE_US &&
                            now >= current.resumeGraceUntilUs) {
                            decision.speed?.let { speed ->
                                if (timeStretch.scheduleSpeed(speed)) {
                                    Log.i(
                                        DIAGNOSTIC_TAG,
                                        "SOFT_DRIFT_CORRECTION filteredUs=${decision.filteredErrorUs} " +
                                            "speed=$speed inputFrames=${timeStretch.inputFrames}"
                                    )
                                }
                            }
                        }
                        if (kotlin.math.abs(decision.filteredErrorUs) > ALIGNMENT_ERROR_THRESHOLD_US) {
                            if (largeErrorSince == 0L) largeErrorSince = now
                            if (now - largeErrorSince >= ALIGNMENT_ERROR_HOLD_US) {
                                throw AlignmentRequiredException("音画偏差连续 5 秒超过 500 ms，正在自动对齐")
                            }
                        } else largeErrorSince = 0
                    }
                }
                if (now - lastStatsAt > 1_000_000) {
                    lastStatsAt = now
                    val unified = timelineSnapshot(now)
                    val unifiedSkewUs = unified?.let { snapshot ->
                        if (sync.atUs > 0) sync.positionUs + now - sync.atUs - snapshot.playedPtsUs else null
                    }
                    val skew = (unifiedSkewUs ?: skewUs)?.let {
                        "${it / 1000} ms（正数表示声音落后）"
                    } ?: "设备暂未提供有效时间戳"
                    val bufferMs = output.bufferSizeInFrames * 1000 / current.sampleRate
                    val queuedMs = current.queuedFrames.get() * 1000 / current.sampleRate
                    val transport = DemoConfig.AUDIO_TRANSPORT.wireName
                    val network = if (current.lastNetworkTransitUs >= 0) "${current.lastNetworkTransitUs / 1000.0} ms" else "待校时"
                    val clockRtt = if (current.clockRttUs >= 0) "${current.clockRttUs / 1000.0} ms" else "待校时"
                    fun projectToNow(ptsUs: Long, eventUs: Long): Long =
                        if (ptsUs >= 0 && eventUs >= 0 && !current.held) ptsUs + now - eventUs else ptsUs
                    val unifiedLines = unified?.let {
                        "AudioTrack播放：PTS ${formatPtsUs(it.playedPtsUs)} · 样本年龄 ${it.playbackSampleAgeUs / 1000} ms\n" +
                            "应用待写 ${formatDurationUs(it.applicationPendingUs)} · 输出待播 ${formatDurationUs(it.audioOutputPendingUs)} · 总储备 ${formatDurationUs(it.totalPendingUs)}\n"
                    } ?: "AudioTrack播放：等待有效播放头\n"
                    val stats = "【手机本地状态｜PTS已统一到同一采样时刻】\n" +
                        "TCP收包：PTS ${formatPtsUs(projectToNow(current.lastReceivedPtsUs, current.lastPacketReceivedPhoneUs))} · seq ${current.lastReceivedSequence}\n" +
                        "应用队列末端：PTS ${formatPtsUs(projectToNow(current.lastQueuedEndPtsUs, current.lastQueueOfferPhoneUs))}\n" +
                        "AudioTrack写入末端：PTS ${formatPtsUs(projectToNow(current.lastTrackWrittenEndPtsUs, current.lastTrackWritePhoneUs))}\n" +
                        unifiedLines +
                        "\n【链路状态】$transport · 音画估算：$skew\n" +
                        "平滑偏差 ${current.filteredSkewUs / 1000} ms · " +
                        "软件纠偏 ${String.format(java.util.Locale.US, "%.3f", current.playbackMediaRate)}x\n" +
                        "网络单向 $network · 校时RTT $clockRtt\n" +
                        "收包 ${current.validPackets.get()} · 最大间隔 ${current.maxPacketIntervalMs.get()} ms\n" +
                        "接收帧：${current.validPackets.get()} · 序号缺口：${current.networkMissingPackets.get()} 包 / " +
                        "${current.networkGapEvents.get()} 次 · 最大收包间隔：${current.maxPacketIntervalMs.get()} ms\n" +
                        "网络缺口补偿：${networkConcealedFrames * 1000 / current.sampleRate} ms · " +
                        "源 PCM 缺口：${sourceGapFrames * 1000 / current.sampleRate} ms\n" +
                        "应用队列：${queuedMs} ms · 输出欠载：${output.underrunCount} 次 · " +
                        "AudioTrack 容量：约 ${bufferMs} ms"
                    Log.i(
                        DIAGNOSTIC_TAG,
                        "SNAPSHOT received=${current.validPackets.get()} " +
                            "missing=${current.networkMissingPackets.get()} " +
                            "gapEvents=${current.networkGapEvents.get()} " +
                            "maxIntervalMs=${current.maxPacketIntervalMs.get()} " +
                            "networkConcealedMs=${networkConcealedFrames * 1000 / current.sampleRate} " +
                            "sourceGapMs=${sourceGapFrames * 1000 / current.sampleRate} " +
                            "queueMs=$queuedMs underruns=${output.underrunCount} " +
                            "outputCapacityMs=$bufferMs skewUs=$skewUs " +
                            "maxReadMs=${current.maxReadMs.get()} maxHighWaterMs=${current.maxHighWaterMs.get()} maxWriteMs=${current.maxWriteMs.get()} " +
                            "sourceFrames=$sourceFramesConsumed stretchedInputFrames=${timeStretch.inputFrames} " +
                            "outputFrames=${timeStretch.outputFrames} playedFrames=${output.playbackHeadPosition} " +
                            "filteredSkewUs=${current.filteredSkewUs} correctionRate=${current.playbackMediaRate}"
                    )
                    mainHandler.post { if (isCurrent(current)) listener.onStats(stats) }
                }
            }

        } catch (error: AlignmentRequiredException) {
            if (isCurrent(current)) {
                reportAlignmentRequired(current, error)
            }
        } catch (error: Exception) {
            if (isCurrent(current)) {
                reportError(current, error)
            }
        } finally {
            current.stopped = true
            current.track = null
            current.streamSocket?.close()
            current.listenSocket?.close()
            current.streamSocket = null
            current.listenSocket = null
            synchronized(current.queueLock) { current.queue.clear(); current.queuedFrames.set(0) }

            track?.let { output ->
                runCatching { output.pause() }
                runCatching { output.flush() }
                output.release()
            }
        }
    }

    /** 单调时钟只用于比较同一次开机内事件的先后和耗时 */
    private fun formatMonotonicUs(valueUs: Long): String = formatSeconds(valueUs)

    /** PTS 是节目媒体时间线位置，0 表示本节目开头*/
    private fun formatPtsUs(valueUs: Long): String = formatSeconds(valueUs)

    private fun formatDurationUs(valueUs: Long): String =
        if (valueUs < 0) "--" else "${valueUs / 1000} ms"

    private fun formatSeconds(valueUs: Long): String {
        if (valueUs < 0) return "--"
        val seconds = valueUs / 1_000_000L
        val millis = valueUs % 1_000_000L / 1_000L
        return "$seconds.${millis.toString().padStart(3, '0')} s"
    }

    private companion object {
        const val INITIAL_CAPACITY_MS = 320
        const val OUTPUT_BUFFER_MS = 240
        const val ADAPTIVE_BUFFER_MS = 280
        const val START_THRESHOLD_MS = 160
        const val STARTUP_PREFILL_MS = com.example.privatelistening.common.ListeningTiming.STARTUP_PREFILL_MS
        const val STARTUP_READY_US = com.example.privatelistening.common.ListeningTiming.STARTUP_READY_US
        const val MAX_STARTUP_BUFFER_US = com.example.privatelistening.common.ListeningTiming.STARTUP_MAX_US
        const val APPLICATION_HIGH_WATER_MS =
            com.example.privatelistening.common.ListeningTiming.STEADY_APPLICATION_TARGET_US / 1000L
        const val TCP_SOCKET_BUFFER_BYTES = 128 * 1024
        const val TCP_STREAM_BUFFER_BYTES = 64 * 1024
        const val CLICK_FADE_MS = 5
        const val ROUTE_CHANGE_GRACE_MS = 2_000L
        const val BLUETOOTH_ROUTE_CHANGE_GRACE_MS = 5_000L
        const val BLUETOOTH_ROUTE_VERIFY_MS = 6_000L
        const val ROUTE_PROBE_RETRY_MS = 500L
        const val DIAGNOSTIC_TAG = "PrivateListenDiag"
        const val ALIGNMENT_ERROR_THRESHOLD_US = 500_000L
        const val ALIGNMENT_ERROR_HOLD_US = 5_000_000L
        const val CORRECTION_SAMPLE_INTERVAL_US = 100_000L
        const val DRIFT_WINDOW_US = 2_000_000L
        const val MIN_DRIFT_OBSERVATION_US = 1_500_000L
        const val MIN_DRIFT_SAMPLES = 10
        const val CORRECTION_ENTER_US = 80_000L
        const val CORRECTION_EXIT_US = 50_000L
        const val DIRECTION_HOLD_US = 600_000L
        const val DECISION_GAP_US = 1_500_000L
        const val CORRECTION_START_GRACE_US = 4_000_000L
        const val CORRECTION_TIME_CONSTANT_US = 5_000_000L
        const val SPEED_CHANGE_LOOKAHEAD_US = 250_000L
        const val MIN_SPEED_SEGMENT_US = 1_500_000L
        const val MIN_CORRECTION_SPEED = 0.98f
        const val MAX_CORRECTION_SPEED = 1.02f
        const val MIN_SPEED_CHANGE = 0.002f
    }
}
