package com.example.privatelistening.tv

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.util.ArrayDeque
//引入共享数据包、UDP网络接口，以及把发送异常转回界面线程的工具
import android.os.Handler
import android.os.Looper
import com.example.privatelistening.common.PcmFramer
import com.example.privatelistening.common.AudioPacket
import com.example.privatelistening.common.AudioStreamFrame
import com.example.privatelistening.common.DemoConfig
import com.example.privatelistening.common.ListeningTiming
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * 电视端音频模块。
 * 1. 从播放器取得解码后的PCM。
 * 2. 保留采样格式、媒体时间戳和时间线版本。
 * 3. 跨回调拼成20ms小块，放入按真实媒体时长限制容量的队列。
 */
@UnstableApi
class TvAudio {

    /**
     * 进入 PCM 获取路径的音频格式。
     */
    data class OutputFormat(
        val sampleRate: Int,
        val channels: Int
    )

    @Volatile
    var outputFormat: OutputFormat? = null
        private set

    /**
     * PTS是节目媒体时间；AtTvUs是电视开机后的单调时钟。
     * 手机收到后会用已校准的 clockOffset 换算到手机单调时钟。
     */
    data class Diagnostics(
        val decodedPcmPtsUs: Long,
        val decodedPcmAtTvUs: Long,
        val capturedChunkPtsUs: Long,
        val capturedChunkAtTvUs: Long,
        val tvCaptureQueueUs: Long,
        val tcpSentPtsUs: Long,
        val tcpSendStartTvUs: Long,
        val tcpSendDoneTvUs: Long,
        val senderPendingChunks: Int
    )

    @Volatile private var lastDecodedPcmPtsUs = -1L
    @Volatile private var lastDecodedPcmAtTvUs = -1L
    @Volatile private var lastCapturedChunkPtsUs = -1L
    @Volatile private var lastCapturedChunkAtTvUs = -1L

    /**
     * 一小块音频数据。
     * ptsUs：这一块首个采样帧的媒体时间戳，单位微秒。
     * epoch：时间线版本，跳转或刷新后递增。
     * data：独立复制的 PCM16 数据，不引用播放器原始缓冲区。
     * 一个采样帧包含同一时刻所有声道的采样：
     */
    data class PcmChunk(
        val epoch: Long,
        val ptsUs: Long,
        val sampleRate: Int,
        val channels: Int,
        val data: ByteArray
    )

    private val queue = ArrayDeque<PcmChunk>()

    private var epoch = 0L
    private var totalBytes = 0L
    private var droppedChunks = 0L
    private var lastLogTimeMs = 0L

    // 按真实媒体时长限制内存；提前解码的未来音频不能按块数静默丢弃。
    private val maxQueueDurationUs = 2_600_000L
    private var queuedDurationUs = 0L
    private var overflowReported = false
    private var holdStartupInput = true
    private fun durationUs(chunk: PcmChunk) =
        chunk.data.size.toLong() / (chunk.channels * 2) * 1_000_000L / chunk.sampleRate
    private data class PositionAnchor(val ptsUs: Long, val atUs: Long, val playing: Boolean)
    @Volatile private var positionAnchor = PositionAnchor(0, 0, false)
    fun updatePosition(positionUs: Long, playing: Boolean) {
        positionAnchor = PositionAnchor(positionUs, android.os.SystemClock.elapsedRealtimeNanos() / 1000, playing)
    }
    // 100ms主线程更新之间按单调时钟推进，避免每100ms突发发送5帧。
    // 最多外推120ms；主线程阻塞、暂停或缓冲时不能无限预测播放位置。
    private val sendPositionUs: Long get() {
        val anchor = positionAnchor
        val elapsed = (android.os.SystemClock.elapsedRealtimeNanos() / 1000 - anchor.atUs).coerceIn(0, 120_000)
        return anchor.ptsUs + if (anchor.playing) elapsed else 0
    }

    /**
     * 起播准备期间：
     * 只保留指定位置之后的PCM；
     * 已有约500ms连续数据时，让音频输入暂时等待。
     */
    private var preparingStartup = false
    private var minimumPtsUs = 0L
    private var startupOverflowed = false

    data class StartupSnapshot(
        val firstPtsUs: Long,
        val bufferedDurationUs: Long,
        val sampleRate: Int,
        val channels: Int,
        val epoch: Long,
        val continuous: Boolean,
        val overflowed: Boolean
    )

    /**
     * 只对本次新建的 TvAudio 调用。
     * startPositionUs 是媒体位置。
     */
    @Synchronized
    fun prepareStartup(startPositionUs: Long, holdInput: Boolean = true) {
        require(startPositionUs >= 0)

        clear()

        minimumPtsUs = startPositionUs
        holdStartupInput = holdInput
        preparingStartup = true
        startupOverflowed = false
    }

    /**
     * 取消起播准备时解除输入等待。
     * 原播放器仍可继续正常播放。
     */
    @Synchronized
    fun cancelStartup() {
        preparingStartup = false
        minimumPtsUs = 0L
        startupOverflowed = false
        clear()
    }

    @Synchronized
    fun startupSnapshot(): StartupSnapshot? {
        val first = queue.peekFirst() ?: return null

        var durationUs = 0L
        var expectedPtsUs = first.ptsUs
        var continuous = true

        for (chunk in queue) {
            if (chunk.epoch != first.epoch || chunk.sampleRate != first.sampleRate || chunk.channels != first.channels
            ) {
                continuous = false
            }

            if (kotlin.math.abs(chunk.ptsUs - expectedPtsUs) > 100L) {
                continuous = false
            }

            val frames = chunk.data.size / (chunk.channels * 2)
            val chunkDurationUs = frames.toLong() * 1_000_000L / chunk.sampleRate

            durationUs += chunkDurationUs
            expectedPtsUs = chunk.ptsUs + chunkDurationUs
        }

        return StartupSnapshot(
            firstPtsUs = first.ptsUs,
            bufferedDurationUs = durationUs,
            sampleRate = first.sampleRate,
            channels = first.channels,
            epoch = first.epoch,
            continuous = continuous,
            overflowed = startupOverflowed
        )
    }

    /**
     * 只在起播准备期间限制继续向本地 AudioSink 输入。
     * 返回 true 时，handleBuffer 返回 false，播放器稍后重试同一缓冲。
     */
    @Synchronized
    private fun shouldHoldStartupInput(): Boolean {
        // 正式播放后始终消费播放器输入。网络发送变慢导致真实溢出时报告一次故障，
        // 不允许把 TCP 背压传回视频播放器。
        if (!preparingStartup || !holdStartupInput) return false

        val durationUs = queue.sumOf { chunk ->
            val frames = chunk.data.size / (chunk.channels * 2)
            frames.toLong() * 1_000_000L / chunk.sampleRate
        }
        return durationUs >= STARTUP_BUFFER_TARGET_US
    }

    /**
     * 一次音频发送任务。
     * 每次开始都创建新任务，旧任务不会影响新的收听会话。
     */
    private class SendTask(
        val id: String,
        val target: InetSocketAddress,
        val streamId: UUID,
        @Volatile var startupOnly: Boolean,
        val onError: (String) -> Unit
    ) {
        @Volatile
        var stopped = false

        @Volatile
        var socket: Closeable? = null

        var thread: Thread? = null
        @Volatile var continuousStartedAtUs = 0L
        @Volatile var lastSentPtsUs = -1L
        @Volatile var lastSendStartTvUs = -1L
        @Volatile var lastSendDoneTvUs = -1L
        // 20ms PCM块，硬容量约3.2秒；正常起播和持续发送目标约2秒。
        val pending = java.util.concurrent.ArrayBlockingQueue<PcmChunk>(160)
    }

    private val sendHandler = Handler(Looper.getMainLooper())

    private class SendGroup(val onError: (String) -> Unit) {
        val tasks = java.util.concurrent.ConcurrentHashMap<String, SendTask>()
        @Volatile var stopped = false
        @Volatile var startupOnly = true
        var dispatcher: Thread? = null
    }
    @Volatile private var sendGroup: SendGroup? = null

    /** 返回某一手机对应发送任务的最近时间点 */
    @Synchronized
    fun diagnosticsForPeer(peerId: String): Diagnostics {
        val task = sendGroup?.tasks?.get(peerId)
        return Diagnostics(
            decodedPcmPtsUs = lastDecodedPcmPtsUs,
            decodedPcmAtTvUs = lastDecodedPcmAtTvUs,
            capturedChunkPtsUs = lastCapturedChunkPtsUs,
            capturedChunkAtTvUs = lastCapturedChunkAtTvUs,
            tvCaptureQueueUs = queuedDurationUs,
            tcpSentPtsUs = task?.lastSentPtsUs ?: -1L,
            tcpSendStartTvUs = task?.lastSendStartTvUs ?: -1L,
            tcpSendDoneTvUs = task?.lastSendDoneTvUs ?: -1L,
            senderPendingChunks = task?.pending?.size ?: 0
        )
    }

    /** 同一份不可变PCM分发给每个人；每一路独立写TCP */
    @Synchronized
    fun startSendingGroup(
        targets: Map<String, InetSocketAddress>, streamId: UUID,
        onPeerError: (String, String) -> Unit, onError: (String) -> Unit
    ) {
        require(targets.isNotEmpty() && targets.size <= 3)
        val snapshot = startupSnapshot()
        check(preparingStartup && snapshot != null && snapshot.continuous && !snapshot.overflowed &&
            snapshot.bufferedDurationUs >= STARTUP_BUFFER_TARGET_US) { "起播音频未准备完整" }
        stopSending()
        val group = SendGroup(onError)
        sendGroup = group
        targets.forEach { (id, target) ->
            val task = SendTask(id, target, streamId, true) { message -> onPeerError(id, message) }
            group.tasks[id] = task
        }
        group.tasks.values.forEach { task ->
            task.thread = Thread({ runSender(task) }, "AudioSender-${task.id.take(8)}").also { it.start() }
        }
        group.dispatcher = Thread({
            try {
                var sentFrames = 0L
                var rate = 0
                while (sendGroup === group && !group.stopped && group.tasks.isNotEmpty()) {
                    // 起播额度到齐后等统一提交，不能提前把长段解码数据灌进暂停的手机。
                    if (group.startupOnly && rate > 0 && sentFrames * 1_000_000 / rate >= STARTUP_BUFFER_TARGET_US) {
                        Thread.sleep(2); continue
                    }
                    val chunk = poll()
                    if (chunk == null) { Thread.sleep(2); continue }
                    rate = chunk.sampleRate
                    sentFrames += chunk.data.size / (chunk.channels * 2)
                    group.tasks.values.toList().forEach { task ->
                        if (!task.pending.offer(chunk)) failTask(task, "本机音频发送积压超过2秒")
                    }
                }
            } catch (error: Exception) {
                if (sendGroup === group && !group.stopped) sendHandler.post {
                    if (sendGroup === group && !group.stopped) group.onError("PCM分发失败：${error.message}")
                }
            }
        }, "AudioFanout").also { it.start() }
    }
    private fun isCurrent(task: SendTask): Boolean =
        !task.stopped && sendGroup?.tasks?.get(task.id) === task

    private fun failTask(task: SendTask, message: String) {
        if (!isCurrent(task)) return
        val group = sendGroup ?: return
        if (!group.tasks.remove(task.id, task)) return
        task.stopped = true
        runCatching { task.socket?.close() }
        task.thread?.interrupt()
        task.pending.clear()
        sendHandler.post { if (sendGroup === group && !group.stopped) task.onError(message) }
    }
    fun stopPeer(id: String) {
        val task = sendGroup?.tasks?.remove(id) ?: return
        task.stopped = true
        runCatching { task.socket?.close() }
        task.thread?.interrupt(); task.pending.clear()
    }
    @Synchronized
    fun resumeContinuousSending(): Boolean {
        val group = sendGroup ?: return false
        if (group.stopped || group.tasks.isEmpty()) return false
        preparingStartup = false; minimumPtsUs = 0; startupOverflowed = false
        group.startupOnly = false
        val startedAtUs = SystemClock.elapsedRealtimeNanos() / 1000
        group.tasks.values.forEach {
            it.continuousStartedAtUs = startedAtUs
            it.startupOnly = false
        }
        return true
    }

    /**
     * 从500ms起播储备开始，约3.4秒内把允许发送的未来PCM平滑增加到2.2秒。
     */
    private fun progressiveSendAheadUs(task: SendTask): Long {
        val startedAtUs = task.continuousStartedAtUs
        if (startedAtUs <= 0L) return ListeningTiming.STARTUP_SEND_US
        val elapsedUs = SystemClock.elapsedRealtimeNanos() / 1000 - startedAtUs
        return ListeningTiming.progressiveSendAheadUs(elapsedUs)
    }
    @Synchronized
    fun stopSending() {
        val group = sendGroup ?: return
        sendGroup = null; group.stopped = true
        group.dispatcher?.interrupt()
        group.tasks.values.forEach {
            it.stopped = true; runCatching { it.socket?.close() }; it.thread?.interrupt(); it.pending.clear()
        }
        group.tasks.clear()
    }

    /** 根据统一配置选择音频传输；播放器线程始终只负责复制 PCM。 */
    private fun runSender(task: SendTask) {
        // 网络发送服务于实时音频，减少电视后台线程调度抖动造成的短时断粮。
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        if (DemoConfig.AUDIO_TRANSPORT == DemoConfig.AudioTransport.TCP) {
            runTcpSender(task)
        } else {
            runUdpSender(task)
        }
    }

    /** 原UDP方法的实现 */
    private fun runUdpSender(task: SendTask) {
        var localSocket: DatagramSocket? = null

        try {
            val socket = DatagramSocket()
            localSocket = socket
            task.socket = socket

            if (!isCurrent(task)) return

            socket.connect(task.target)

            var sequence = 0L
            var sentDurationUs = 0L
            var startupSentFrames = 0L
            var startupSampleRate = 0

            while (isCurrent(task)) {
                val chunk = task.pending.poll()

                if (chunk == null) {
                    // 没有数据时短暂休息，避免线程一直空转占用 CPU。
                    Thread.sleep(2)
                    continue
                }

                if (task.startupOnly) {
                    if (startupSampleRate == 0) startupSampleRate = chunk.sampleRate
                    check(chunk.sampleRate == startupSampleRate) { "起播发送期间音频格式发生变化" }
                }

                val bytesPerFrame = chunk.channels * 2

                // 单包最多容纳的完整采样帧数量。
                val maxFramesPerPacket = AudioPacket.MAX_PCM_BYTES / bytesPerFrame

                var offsetBytes = 0

                while (
                    offsetBytes < chunk.data.size && isCurrent(task)
                ) {
                    // UDP仅保留作A/B对比分支；正式TCP路径使用500ms起播和渐进蓄水。
                    // 保留当前 chunk 和 offset，不丢弃未发送部分。
                    while (task.startupOnly && sentDurationUs >= STARTUP_BUFFER_TARGET_US && isCurrent(task)
                    ) {
                        Thread.sleep(2)
                    }
                    if (!isCurrent(task)) break

                    val remainingFrames = (chunk.data.size - offsetBytes) / bytesPerFrame

                    val frames = minOf(remainingFrames, maxFramesPerPacket)

                    check(frames > 0) { "PCM 数据未按完整采样帧对齐" }

                    val lengthBytes = frames * bytesPerFrame

                    // 拆包后，时间戳也必须移动到当前包的第一个采样帧。
                    val frameOffset = offsetBytes.toLong() / bytesPerFrame

                    val packetPtsUs = chunk.ptsUs + frameOffset * 1_000_000L / chunk.sampleRate

                    // 最多提前 150ms，避免把播放器提前解码的整段 PCM 瞬间塞满手机。
                    while (!task.startupOnly && packetPtsUs > sendPositionUs + 150_000L && isCurrent(task))
                        Thread.sleep(2)
                    if (!isCurrent(task)) break
                    val pcm = chunk.data.copyOfRange(offsetBytes, offsetBytes + lengthBytes)

                    val sendStartedAtTvUs = SystemClock.elapsedRealtimeNanos() / 1000
                    val bytes = AudioPacket.encode(
                        AudioPacket.Packet(
                            streamId = task.streamId,
                            epoch = chunk.epoch,
                            sequence = sequence,
                            ptsUs = packetPtsUs,
                            sentAtTvUs = sendStartedAtTvUs,
                            sampleRate = chunk.sampleRate,
                            channels = chunk.channels,
                            pcm = pcm
                        )
                    )

                    socket.send(DatagramPacket(bytes, bytes.size))
                    task.lastSentPtsUs = packetPtsUs
                    task.lastSendStartTvUs = sendStartedAtTvUs
                    task.lastSendDoneTvUs = SystemClock.elapsedRealtimeNanos() / 1000
                    // 先累计完整采样帧，再换算时长；逐包取整会累积误差，
                    // 例如 192kHz 双声道时可能额外发送第 33 包，挤满手机队列。
                    if (task.startupOnly) {
                        startupSentFrames += frames
                        sentDurationUs = startupSentFrames * 1_000_000L / chunk.sampleRate
                    }

                    sequence += 1
                    offsetBytes += lengthBytes
                }
            }
        } catch (error: Exception) {
            // 用户主动停止时，关闭 socket 或打断等待也会产生异常。
            // 只有当前任务的非主动停止异常才通知界面。
            if (isCurrent(task)) {
                val message = "音频发送失败：${error.message ?: error.javaClass.simpleName}"

                failTask(task, message)
            }
        } finally {
            localSocket?.close()
            task.socket = null
        }
    }

    /**
     * TCP 保证PCM帧按序完整到达。每个捕获块约20ms，写入后flush，
     */
    private fun runTcpSender(task: SendTask) {
        var localSocket: Socket? = null
        try {
            val socket = Socket()
            localSocket = socket
            task.socket = socket
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.sendBufferSize = TCP_SOCKET_BUFFER_BYTES
            socket.connect(task.target, DemoConfig.TCP_CONNECT_TIMEOUT_MS)
            if (!isCurrent(task)) return

            val output = DataOutputStream(
                BufferedOutputStream(socket.getOutputStream(), TCP_STREAM_BUFFER_BYTES)
            )
            var sequence = 0L
            var startupSentFrames = 0L
            var startupSampleRate = 0

            while (isCurrent(task)) {
                val chunk = task.pending.poll()
                if (chunk == null) {
                    Thread.sleep(2)
                    continue
                }

                if (task.startupOnly) {
                    if (startupSampleRate == 0) startupSampleRate = chunk.sampleRate
                    check(chunk.sampleRate == startupSampleRate) { "起播发送期间音频格式发生变化" }
                    val sentDurationUs = startupSentFrames * 1_000_000L / startupSampleRate
                    while (task.startupOnly && sentDurationUs >= STARTUP_BUFFER_TARGET_US &&
                        isCurrent(task)) {
                        Thread.sleep(2)
                    }
                }
                if (!isCurrent(task)) break

                // 起播时只允许约500ms未来PCM；随后每秒扩大约500ms发送窗口，
                while (!task.startupOnly &&
                    chunk.ptsUs > sendPositionUs + progressiveSendAheadUs(task) && isCurrent(task)) {
                    Thread.sleep(2)
                }
                if (!isCurrent(task)) break

                val sendStartedAtTvUs = SystemClock.elapsedRealtimeNanos() / 1000
                AudioStreamFrame.write(
                    output,
                    AudioPacket.Packet(
                        streamId = task.streamId,
                        epoch = chunk.epoch,
                        sequence = sequence,
                        ptsUs = chunk.ptsUs,
                        sentAtTvUs = sendStartedAtTvUs,
                        sampleRate = chunk.sampleRate,
                        channels = chunk.channels,
                        pcm = chunk.data
                    )
                )
                output.flush()
                task.lastSentPtsUs = chunk.ptsUs
                task.lastSendStartTvUs = sendStartedAtTvUs
                task.lastSendDoneTvUs = SystemClock.elapsedRealtimeNanos() / 1000
                if (task.startupOnly) {
                    startupSentFrames += chunk.data.size / (chunk.channels * 2)
                }
                sequence += 1
            }
        } catch (error: Exception) {
            if (isCurrent(task)) {
                val message = "TCP音频发送失败：${error.message ?: error.javaClass.simpleName}"
                failTask(task, message)
            }
        } finally {
            runCatching { localSocket?.close() }
            task.socket = null
        }
    }

    /**
     * 没有数据时返回 null，不等待。
     */
    @Synchronized
    fun poll(): PcmChunk? {
        return queue.pollFirst()?.also { queuedDurationUs -= durationUs(it) }
    }

    /**
     * 清除旧时间线的数据。
     * 拖动、切换音频配置或释放播放器时使用。
     */
    @Synchronized
    fun clear() {
        queue.clear()
        queuedDurationUs = 0
        overflowReported = false
        epoch += 1
        totalBytes = 0
        droppedChunks = 0
        lastLogTimeMs = 0
        lastDecodedPcmPtsUs = -1L
        lastDecodedPcmAtTvUs = -1L
        lastCapturedChunkPtsUs = -1L
        lastCapturedChunkAtTvUs = -1L
    }

    @Synchronized
    private fun offer(
        ptsUs: Long,
        sampleRate: Int,
        channels: Int,
        data: ByteArray
    ) {
        val bytesPerFrame = channels * 2
        var acceptedPtsUs = ptsUs
        var acceptedData = data

        if (preparingStartup && ptsUs < minimumPtsUs) {
            val totalFrames = data.size / bytesPerFrame

            // 向上取整，确保不会保留目标位置之前的完整采样帧。
            val framesToSkip = ((minimumPtsUs - ptsUs) * sampleRate + 999_999L) / 1_000_000L

            if (framesToSkip >= totalFrames) {
                return
            }

            val skipBytes = framesToSkip.toInt() * bytesPerFrame
            acceptedData = data.copyOfRange(skipBytes, data.size)
            acceptedPtsUs = ptsUs + framesToSkip * 1_000_000L / sampleRate
        }

        val incomingUs = acceptedData.size.toLong() / bytesPerFrame * 1_000_000L / sampleRate
        if (queuedDurationUs + incomingUs > maxQueueDurationUs) {
            if (preparingStartup) startupOverflowed = true
            val task = sendGroup
            if (task == null) {
                // 未收听时仅保留滚动缓存，不影响电视正常播放。
                while (queue.isNotEmpty() && queuedDurationUs + incomingUs > maxQueueDurationUs) {
                    queuedDurationUs -= durationUs(queue.removeFirst())
                }
            } else {
                // 正在收听时不能删掉未来PCM再把剩余帧编号为连续流。
                // 整轮作废并报告一次；网络线程不阻塞播放器。
                if (!overflowReported) {
                    overflowReported = true
                    Log.e("TvAudio", "CAPTURE_OVERFLOW queuedUs=$queuedDurationUs incomingUs=$incomingUs")
                    sendHandler.post {
                        if (sendGroup === task) {
                            stopSending()
                            task.onError("电视音频待发队列超过2秒，正在恢复音频通道")
                        }
                    }
                }
                return
            }
        }
        queuedDurationUs += incomingUs

        queue.addLast(
            PcmChunk(
                epoch = epoch,
                ptsUs = acceptedPtsUs,
                sampleRate = sampleRate,
                channels = channels,
                data = acceptedData
            )
        )
        lastCapturedChunkPtsUs = acceptedPtsUs
        lastCapturedChunkAtTvUs = SystemClock.elapsedRealtimeNanos() / 1000

        totalBytes += acceptedData.size

        val now = SystemClock.elapsedRealtime()

        if (now - lastLogTimeMs >= 2000) {
            lastLogTimeMs = now

            Log.d(
                "TvAudio",
                "PCM16: rate=$sampleRate, channels=$channels, " +
                        "epoch=$epoch, ptsUs=$acceptedPtsUs, " +
                        "bytes=$totalBytes, queued=${queue.size}, " +
                        "queuedUs=$queuedDurationUs dropped=$droppedChunks"
            )
        }
    }

    /**
     * 创建自定义显示控件。
     */
    fun createRenderersFactory(
        context: Context
    ): DefaultRenderersFactory {
        return object : DefaultRenderersFactory(context) {

            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): AudioSink {
                val originalSink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    // 起播阶段先让播放器交付约500ms PCM；正式播放后继续提前解码，
                    // 使手机应用队列在数秒内渐进增长到约2秒。
                    .setAudioTrackBufferSizeProvider(
                        DefaultAudioTrackBufferSizeProvider.Builder()
                            .setTargetPcmBufferDurationUs(TV_PCM_BUFFER_US)
                            .build()
                    )
                    .build()

                return CapturingAudioSink(originalSink)
            }
        }
    }

    /**
     * 保留原有 AudioSink 的播放和时钟行为，
     * 复制已经处理的 PCM。
     */
    private inner class CapturingAudioSink(
        originalSink: AudioSink
    ) : ForwardingAudioSink(originalSink) {

        private var sampleRate = 0
        private var channels = 0
        private var bytesPerFrame = 0
        private var captureSupported = false

        // 跟踪同一个缓冲区的重复调用，避免重复复制音频。
        private var trackedBuffer: ByteBuffer? = null
        private var bufferStartPosition = 0

        // Media3 可能给输入时间戳添加流偏移，需要保留并减去。
        private var outputStreamOffsetUs = 0L
        private var framer: PcmFramer? = null

        /**
         * PCM 的具体播放能力仍交给原有 AudioSink 判断。
         */
        override fun supportsFormat(format: Format): Boolean {
            return getFormatSupport(format) !=
                    AudioSink.SINK_FORMAT_UNSUPPORTED
        }

        override fun getFormatSupport(format: Format): Int {
            if (format.sampleMimeType != MimeTypes.AUDIO_RAW) {
                return AudioSink.SINK_FORMAT_UNSUPPORTED
            }

            return super.getFormatSupport(format)
        }

        /**
         * 不启用音频 offload。
         */
        override fun getFormatOffloadSupport(
            format: Format
        ): AudioOffloadSupport {
            return AudioOffloadSupport.DEFAULT_UNSUPPORTED
        }

        override fun configure(
            audioSinkConfig: AudioSink.AudioSinkConfig
        ) {
            super.configure(audioSinkConfig)

            val format = audioSinkConfig.format

            sampleRate = format.sampleRate
            channels = format.channelCount

            captureSupported = format.sampleMimeType == MimeTypes.AUDIO_RAW && format.pcmEncoding == C.ENCODING_PCM_16BIT && sampleRate > 0 && channels in 1..2

            bytesPerFrame = if (captureSupported) channels * 2 else 0
            framer = if (captureSupported) PcmFramer(sampleRate, channels) else null

            outputFormat = if (captureSupported) {
                    OutputFormat(sampleRate = sampleRate, channels = channels)
                } else {
                    null
                }

            trackedBuffer = null
            clear()

            if (captureSupported) {
                Log.i("TvAudio", "音频获取已配置：PCM16，" + "$sampleRate Hz，$channels 声道")
            } else {
                Log.w("TvAudio", "当前音频可继续交给原播放器处理，" + "但自在听获取暂不支持此格式：" + "encoding=${format.pcmEncoding}, " + "rate=$sampleRate, channels=$channels")
            }
        }

        override fun setOutputStreamOffsetUs(
            outputStreamOffsetUs: Long
        ) {
            this.outputStreamOffsetUs = outputStreamOffsetUs
            super.setOutputStreamOffsetUs(outputStreamOffsetUs)
        }

        override fun handleBuffer(
            buffer: ByteBuffer,
            presentationTimeUs: Long,
            encodedAccessUnitCount: Int
        ): Boolean {
            //防止电视仍在准备时不断产生音频
            if (shouldHoldStartupInput()) {
                return false
            }
            if (trackedBuffer !== buffer) {
                trackedBuffer = buffer
                bufferStartPosition = buffer.position()
            }

            val before = buffer.position()

            // 必须保留原始消费逻辑，让电视音频和播放时钟正常工作。
            val handled = super.handleBuffer(
                buffer,
                presentationTimeUs,
                encodedAccessUnitCount
            )

            val after = buffer.position()

            if (captureSupported && after > before && presentationTimeUs != C.TIME_UNSET && presentationTimeUs != C.TIME_END_OF_SOURCE
            ) {
                copyConsumedPcm(
                    buffer = buffer,
                    from = before,
                    to = after,
                    presentationTimeUs = presentationTimeUs
                )
            }

            if (handled) {
                // 缓冲区可能被播放器复用，处理结束后解除跟踪。
                trackedBuffer = null
            }

            return handled
        }

        private fun copyConsumedPcm(
            buffer: ByteBuffer,
            from: Int,
            to: Int,
            presentationTimeUs: Long
        ) {
            val consumedBytes = to - from
            val offsetBytes = from - bufferStartPosition

            if (consumedBytes % bytesPerFrame != 0 || offsetBytes % bytesPerFrame != 0) {
                Log.w("TvAudio", "PCM 数据未按采样帧对齐，本段未复制")
                return
            }

            // duplicate 共享内容，但 position / limit 独立。
            // 读取副本，不再改变播放器原始缓冲区的位置。
            val source = buffer.duplicate()
            source.limit(to)
            source.position(from)

            val startFrameOffset = offsetBytes.toLong() / bytesPerFrame

            val data = ByteArray(source.remaining())
            source.get(data)
            val ptsUs = presentationTimeUs - outputStreamOffsetUs + startFrameOffset * 1_000_000L / sampleRate
            // 此处已经是解码后的PCM到达AudioSink；不是压缩包到达，也不是扬声器出声。
            lastDecodedPcmPtsUs = ptsUs
            lastDecodedPcmAtTvUs = SystemClock.elapsedRealtimeNanos() / 1000
            framer?.append(ptsUs, data) { pts, pcm -> offer(pts, sampleRate, channels, pcm) }
        }

        override fun playToEndOfStream() {
            framer?.drain { pts, pcm -> offer(pts, sampleRate, channels, pcm) }
            super.playToEndOfStream()
        }

        override fun flush() {
            super.flush()
            framer = if (captureSupported) PcmFramer(sampleRate, channels) else null
            trackedBuffer = null
            clear()
        }

        override fun reset() {
            super.reset()
            framer = null
            trackedBuffer = null
            captureSupported = false
            outputFormat = null
            clear()
        }

        override fun release() {
            super.release()
            framer = null
            trackedBuffer = null
            captureSupported = false
            outputFormat = null
            clear()
        }
    }

    companion object {
        /** 起播阶段只保留并发送约500ms连续PCM。 */
        const val STARTUP_BUFFER_TARGET_US = com.example.privatelistening.common.ListeningTiming.STARTUP_SEND_US
        private const val TV_PCM_BUFFER_US = 2_400_000
        private const val TCP_SOCKET_BUFFER_BYTES = 128 * 1024
        private const val TCP_STREAM_BUFFER_BYTES = 64 * 1024
    }
}
