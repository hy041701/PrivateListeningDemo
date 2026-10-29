package com.example.privatelistening.phone

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.os.*
import java.net.InetAddress
import java.util.UUID

/** 会话属于前台播放服务，页面旋转、切后台、锁屏不会创建第二份音频线程。 */
class PhoneListeningService : Service() {
    data class OutputChoice(val id: Int, val label: String)

    inner class LocalBinder : Binder() { val service get() = this@PhoneListeningService }
    private lateinit var audioManager: AudioManager
    private lateinit var pairing: PhonePairing
    private lateinit var phoneAudio: PhoneAudio
    private var listeningDeviceId: Int? = null
    /** 用户主动选中后保持该设备；设备消失时自动回退到其他可用输出。 */
    private var manuallySelectedDeviceId: Int? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var foreground = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    var observer: (() -> Unit)? = null
    var state = PhonePairing.State.IDLE
        private set
    var detail = "扫描电视二维码即可开始；支持蓝牙、有线耳机和手机扬声器。"
        private set(value) { field = value; observer?.invoke() }
    var diagnostics = "连接并开始播放后，这里每秒显示一次电视与手机的完整时间线。"
        private set
    var tvTimelineColumn = "等待电视端采样…"
        private set
    var phoneTimelineColumn = "等待手机端采样…"
        private set
    var diagnosticDetails = ""
        private set
    private var tvDiagnostics = ""
    private var phoneDiagnostics = ""
    /** 保留旧页面字段以兼容当前布局；新流程不再强制弹出耳机提示。 */
    var headphonePromptVersion = 0
        private set
    var participantCount = 1
        private set
    val hasSession get() = pairing.hasSession
    val headphoneDescription: String
        get() = currentListeningDevice()?.let { describeOutput(it) }
            ?: "未检测到可用的音频输出设备"

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change < 0 && hasAudioFocus) {
            requestRealignment("音频被其他应用暂时占用，请关闭其他播放后点击电视端“音画对齐”。")
        }
    }
    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            phoneAudio.beginOutputRouteChange()
            scheduleOutputRefresh()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            phoneAudio.beginOutputRouteChange()
            scheduleOutputRefresh()
        }
    }
    override fun onBind(intent: Intent): IBinder = LocalBinder()
    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        // 创建对象不会开始收听；收到电视参数后才 prepare。
        phoneAudio = PhoneAudio(object : PhoneAudio.Listener {
            override fun onStartArmed() {
                if (!pairing.sendControl(org.json.JSONObject().put("type", "START_ARMED"))) {
                    disconnect("起播就绪确认发送失败，请重新扫码。")
                } else detail = "音频输出已预填，等待电视提交…"
            }
            override fun onStats(message: String) {
                phoneDiagnostics = message
                rebuildDiagnostics()
            }
            override fun onPrepared(port: Int) {
                if (!pairing.sendAudioPrepared(port)) {
                    phoneAudio.stop()
                    pairing.disconnect("音频准备结果发送失败，请重新扫码。")
                    return
                }
                detail = "音频资源已准备，请在电视点击“开始自在听”。"
            }

            override fun onFirstAudioWritten() {
                detail = "已收到电视音频，正在通过${currentOutputLabel()}播放。"
            }

            override fun onOutputChanged(device: AudioDeviceInfo) {
                listeningDeviceId = device.id
                detail = "当前已切换到${outputKind(device)}。"
                observer?.invoke()
            }

            override fun onError(message: String) {
                // AudioTrack/UDP 的瞬时错误只废弃本轮音频流，保留已经扫码建立的 WebSocket。
                // 电视会按当前位置限次重建音频；无需用户重新扫码，也不会无限重试。
                requestStreamRecovery("手机音频暂时中断：$message")
            }

            override fun onAlignmentRequired(message: String) {
                requestAutomaticRealignment(message)
            }

            override fun onOutputReady() {
                if (!pairing.sendOutputReady()) {
                    phoneAudio.stop()
                    pairing.disconnect("输出准备确认发送失败，请重新扫码。")
                    return
                }
                detail = "手机输出检查通过，正在等待电视准备起播音频。"
            }

            override fun onBufferReady(
                firstPtsUs: Long,
                epoch: Long,
                bufferedDurationUs: Long
            ) {
                if (!pairing.sendBufferReady(firstPtsUs, epoch, bufferedDurationUs)) {
                    phoneAudio.stop()
                    pairing.disconnect("起播缓冲确认发送失败，请重新扫码。")
                    return
                }
                detail = "已收到约 ${bufferedDurationUs / 1000} ms 起播音频，等待同步开始。"
            }
        })

        pairing = PhonePairing(
            object : PhonePairing.Listener {
                override fun onControl(message: org.json.JSONObject) {
                    when (message.optString("type")) {
                        "PARTICIPANTS" -> {
                            participantCount = message.optInt("count", 1).coerceIn(1, 3)
                            observer?.invoke()
                        }
                        "START_AT" -> {
                            if (!phoneAudio.schedulePlayback(message.optLong("phoneStartUs", -1))) {
                                requestStreamRecovery("同步起播准备失败，请发起音画对齐重试。")
                            } else detail = "已约定起播时间，等待电视提交…"
                        }
                        "COMMIT_START" -> if (!phoneAudio.commitPlayback()) {
                            requestStreamRecovery("起播提交过晚，请发起音画对齐重试。")
                        }
                        "SYNC" -> phoneAudio.updateSync(message.optLong("positionUs"), message.optLong("phoneTimeUs"))
                        "DIAGNOSTICS" -> updateTvDiagnostics(message)
                        "HOLD_PAUSE" -> {
                            if (!phoneAudio.holdPlayback()) {
                                requestStreamRecovery("暂停保留失败，正在重新同步音频。")
                            } else {
                                detail = "电视已暂停 · 已保留音频缓存，继续播放时无需重新准备。"
                            }
                        }
                        "RESUME_AT" -> {
                            if (!phoneAudio.resumeHeldAt(message.optLong("phoneStartUs", -1))) {
                                requestStreamRecovery("快速恢复调度失败，正在重新同步音频。")
                            } else {
                                detail = "即将从暂停位置继续收听…"
                            }
                        }
                        "PAUSE", "SESSION_PAUSED" -> {
                            phoneAudio.stop()
                            releaseAudioFocus()
                            detail = message.optString("message", "电视已暂停，等待继续收听。")
                        }
                    }
                }
                override fun onStateChanged(
                    state: PhonePairing.State,
                    message: String
                ) {
                    if (state != PhonePairing.State.CONNECTED) {
                        phoneAudio.stop()
                        releaseAudioFocus()
                        listeningDeviceId = null
                    }
                    showState(state, message)
                }

                override fun onPrepareAudio(
                    tvAddress: InetAddress,
                    streamId: UUID,
                    sampleRate: Int,
                    channels: Int
                ) {
                    val device = findListeningDevice()
                    if (device == null) {
                        requestStreamRecovery("手机没有可用的音频输出设备。")
                        return
                    }
                    listeningDeviceId = device.id
                    detail = "正在准备音频：$sampleRate Hz，$channels 声道…"
                    try {
                        phoneAudio.prepare(tvAddress, streamId, sampleRate, channels, device)
                    } catch (error: Exception) {
                requestStreamRecovery("手机音频准备失败：${error.message ?: "未知错误"}")
                    }
                }

                override fun onArmAudio() {
                    val device = findListeningDevice()
                    if (device == null) {
                        requestStreamRecovery("手机没有可用的音频输出设备。")
                        return
                    }
                    if (listeningDeviceId != device.id) {
                        if (!phoneAudio.switchOutputDevice(device)) {
                            requestStreamRecovery("手机音频输出切换失败，请重试。")
                            return
                        }
                        listeningDeviceId = device.id
                    }
                    if (!acquireAudioFocus()) {
                        requestRealignment("暂时无法取得音频焦点，请关闭其他播放或通话后点击“音画对齐”。")
                        return
                    }

                    detail = "正在检查手机实际音频输出…"

                    if (!phoneAudio.armOutput()) {
                requestStreamRecovery("手机音频资源已失效，正在重新建立。")
                    }
                }
            }
        )


        audioManager.registerAudioDeviceCallback(audioCallback, Handler(Looper.getMainLooper()))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { disconnect("已结束收听。"); return START_NOT_STICKY }
        val qr = intent?.getStringExtra("qr")
        if (qr == null || pairing.hasSession) return START_NOT_STICKY
        try {
            // 从可见页面的扫码结果启动；必须先进入前台，再申请音频焦点。
            foreground = true
            startForeground(1, notification())
            acquireLocks()
            pairing.connect(qr)
        } catch (error: Exception) {
            disconnect("无法启动收听服务：${error.message}")
        }
        return START_NOT_STICKY // 系统杀进程后不自动恢复旧 token 或播放旧节目。
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel("listening", "自在听播放", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, PhoneListeningService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "listening") else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("同屏自在听")
            .setContentText("正在连接或收听电视声音，点击返回应用")
            .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "结束收听", stop).build()).build()
    }

    @Suppress("WakelockTimeout", "DEPRECATION")
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PrivateListening:audio").apply { acquire() }
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager)
            .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PrivateListening:wifi").apply { acquire() }
    }

    private fun releaseResources() {
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }; wifiLock = null
        if (foreground) { foreground = false; stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    fun disconnect(message: String) { pairing.disconnect(message) }

    /** 手机端主动要求电视以当前位置重建声音时间线。 */
    fun requestManualAlignment() {
        detail = if (pairing.sendUserCommand("REQUEST_REALIGN")) {
            "已请求全体音画对齐，电视和所有手机将暂停后一起恢复…"
        } else {
            "当前没有可对齐的电视连接。"
        }
    }

    /** 先可靠地发出结束命令，再稍后关闭 WebSocket，让电视恢复扬声器。 */
    fun endListeningFromPhone() {
        val notified = pairing.sendUserCommand("END_LISTENING")
        detail = "正在退出本机收听，其他成员不受影响…"
        Handler(Looper.getMainLooper()).postDelayed({
            pairing.disconnect(if (notified) "已退出自在听，可重新扫码连接。" else "连接已断开。")
        }, 250)
    }

    /** 重新校准只废弃当前 PCM/AudioTrack，不废弃手机与电视的配对。 */
    private fun requestRealignment(message: String) {
        phoneAudio.stop()
        releaseAudioFocus()
        listeningDeviceId = null
        detail = message
        pairing.sendRecoverableStatus("REALIGN_REQUIRED", message)
    }

    /** 偏差检测属于可自动恢复事件，与耳机/音频焦点异常分开处理。 */
    private fun requestAutomaticRealignment(message: String) {
        phoneAudio.stop()
        releaseAudioFocus()
        listeningDeviceId = null
        detail = if (participantCount > 1) "检测到音画偏差，本机收听暂停，可点击音画对齐重试。"
            else "检测到音画偏差，正在自动对齐…"
        pairing.sendRecoverableStatus("AUTO_REALIGN_REQUIRED", message)
    }

    /** 音频资源异常交给电视限次恢复，控制连接本身继续保留。 */
    private fun requestStreamRecovery(message: String) {
        android.util.Log.w("PrivateListenDiag", "AUDIO_RECOVERY_REQUESTED $message")
        phoneAudio.stop()
        releaseAudioFocus()
        listeningDeviceId = null
        detail = if (participantCount > 1) "声音连接波动，本机收听暂停，可点击音画对齐重试。"
            else "声音连接波动，正在自动恢复…"
        pairing.sendRecoverableStatus("AUDIO_RECOVERY_REQUIRED", message)
    }
    private fun showState(newState: PhonePairing.State, message: String) {
        state = newState
        detail = message
        if (state == PhonePairing.State.IDLE || state == PhonePairing.State.ERROR) {
            tvDiagnostics = ""
            phoneDiagnostics = ""
            diagnostics = "连接并开始播放后，这里每秒显示一次电视与手机的完整时间线。"
            tvTimelineColumn = "等待电视端采样…"
            phoneTimelineColumn = "等待手机端采样…"
            diagnosticDetails = ""
            observer?.invoke()
            releaseResources()
        }
    }

    /**
     * 电视的 elapsedRealtime 与手机不是同一个原点。电视把本成员的校时偏移一并发来，
     * 这里把所有电视 t 换算到手机单调时钟域，才能直接做减法比较。
     */
    private fun updateTvDiagnostics(message: org.json.JSONObject) {
        val offsetUs = message.optLong("clockOffsetUs", Long.MIN_VALUE)
        val rttUs = message.optLong("clockRttUs", -1L)
        phoneAudio.updateClockCalibration(offsetUs, rttUs)

        fun raw(name: String): Long = message.optLong(name, -1L)

        val isPlaying = message.optBoolean("videoPlaying", false)
        val rtt = if (rttUs >= 0) "${rttUs / 1000.0} ms" else "--"
        val sampledTvUs = raw("sampledAtTvUs")
        val comparisonPhoneUs = if (sampledTvUs >= 0 && offsetUs != Long.MIN_VALUE) sampledTvUs + offsetUs else -1L
        val phoneAtComparison = if (comparisonPhoneUs >= 0) phoneAudio.timelineSnapshot(comparisonPhoneUs) else null
        val scheduledTvAtPhoneUs = raw("scheduledVideoStartTvUs").let {
            if (it >= 0 && offsetUs != Long.MIN_VALUE) it + offsetUs else -1L
        }
        val playCallTvAtPhoneUs = raw("videoPlayCallTvUs").let {
            if (it >= 0 && offsetUs != Long.MIN_VALUE) it + offsetUs else -1L
        }
        val videoPositionUs = raw("videoPositionUs")
        val framePtsUs = raw("videoFramePtsUs")
        val frameReleaseTvUs = raw("videoFrameReleaseTvUs")
        fun projectTvPtsToComparison(ptsUs: Long, eventTvUs: Long): Long =
            if (ptsUs >= 0 && eventTvUs >= 0 && sampledTvUs >= 0 && isPlaying)
                ptsUs + sampledTvUs - eventTvUs else ptsUs
        fun projectPhonePtsToComparison(ptsUs: Long, eventPhoneUs: Long): Long =
            if (ptsUs >= 0 && eventPhoneUs >= 0 && comparisonPhoneUs >= 0 && isPlaying)
                ptsUs + comparisonPhoneUs - eventPhoneUs else ptsUs

        val frameAtComparisonUs = projectTvPtsToComparison(framePtsUs, frameReleaseTvUs)
        val decodedAtComparisonUs = projectTvPtsToComparison(raw("audioDecodedPtsUs"), raw("audioDecodedTvUs"))
        val capturedAtComparisonUs = projectTvPtsToComparison(raw("audioCapturedPtsUs"), raw("audioCapturedTvUs"))
        val sentAtComparisonUs = projectTvPtsToComparison(raw("audioSentPtsUs"), raw("audioSendDoneTvUs"))
        val receivedAtComparisonUs = phoneAtComparison?.let {
            projectPhonePtsToComparison(it.receivedPtsUs, it.receivedAtPhoneUs)
        } ?: -1L
        val queuedAtComparisonUs = phoneAtComparison?.let {
            projectPhonePtsToComparison(it.newestQueuedEndPtsUs, it.queuedAtPhoneUs)
        } ?: -1L
        val writtenAtComparisonUs = phoneAtComparison?.let {
            projectPhonePtsToComparison(it.writtenEndPtsUs, it.writtenAtPhoneUs)
        } ?: -1L
        val playedAtComparisonUs = phoneAtComparison?.playedPtsUs ?: -1L
        val playerAvUs = phoneAtComparison?.takeIf { videoPositionUs >= 0 }?.let { videoPositionUs - it.playedPtsUs }
        val frameAvUs = phoneAtComparison?.takeIf { frameAtComparisonUs >= 0 }?.let { frameAtComparisonUs - it.playedPtsUs }

        fun videoAtTvEvent(eventTvUs: Long): Long =
            if (videoPositionUs >= 0 && sampledTvUs >= 0 && eventTvUs >= 0 && isPlaying)
                videoPositionUs + eventTvUs - sampledTvUs else videoPositionUs
        fun mediaLead(ptsUs: Long, eventTvUs: Long): Long? =
            if (ptsUs >= 0 && videoPositionUs >= 0 && eventTvUs >= 0) ptsUs - videoAtTvEvent(eventTvUs) else null
        fun durationBetween(startName: String, endName: String): Long {
            val start = raw(startName)
            val end = raw(endName)
            return if (start >= 0 && end >= start) end - start else -1L
        }

        fun pending(later: Long, earlier: Long): Long =
            if (later >= 0 && earlier >= 0) (later - earlier).coerceAtLeast(0L) else -1L
        val applicationPendingAtComparisonUs = pending(queuedAtComparisonUs, writtenAtComparisonUs)
        val outputPendingAtComparisonUs = pending(writtenAtComparisonUs, playedAtComparisonUs)
        val totalPendingAtComparisonUs = pending(queuedAtComparisonUs, playedAtComparisonUs)

        val scheduleDeltaUs = phoneAtComparison?.scheduledStartPhoneUs?.takeIf { it > 0 }
            ?.let { if (scheduledTvAtPhoneUs >= 0) it - scheduledTvAtPhoneUs else null }
        val playCallDeltaUs = phoneAtComparison?.playCalledPhoneUs?.takeIf { it > 0 }
            ?.let { if (playCallTvAtPhoneUs >= 0) it - playCallTvAtPhoneUs else null }
        // 第③～⑤行都已经投影到同一个采样时刻，使用“手机列 - 电视列”即可看出
        // 音频在解码、传输、排队和写入各阶段之间相隔了多少节目时间。
        fun rightMinusLeft(rightUs: Long, leftUs: Long): Long? =
            if (rightUs >= 0 && leftUs >= 0) rightUs - leftUs else null
        val decodeToReceiveDeltaUs = rightMinusLeft(receivedAtComparisonUs, decodedAtComparisonUs)
        val captureToQueueDeltaUs = rightMinusLeft(queuedAtComparisonUs, capturedAtComparisonUs)
        val sendToWriteDeltaUs = rightMinusLeft(writtenAtComparisonUs, sentAtComparisonUs)

        diagnostics = "【关键差值｜正数表示手机端更晚】\n" +
            "约定起播：${formatSignedRawUs(scheduleDeltaUs)} · 实际play调用：${formatSignedRawUs(playCallDeltaUs)}\n" +
            "同步播放PTS：${formatSignedDurationUs(frameAvUs)}"

        tvTimelineColumn =
            "①约定起播\nt ${formatUs(scheduledTvAtPhoneUs)}\n" +
            "②实际调用play\nt ${formatUs(playCallTvAtPhoneUs)}\n" +
            "③音频解码前沿\nPTS ${formatUs(decodedAtComparisonUs)}\n" +
            "④形成20ms PCM\nPTS ${formatUs(capturedAtComparisonUs)}\n" +
            "⑤TCP写出前沿\nPTS ${formatUs(sentAtComparisonUs)}\n" +
            "⑥视频帧待送显\nPTS ${formatUs(frameAtComparisonUs)}"

        phoneTimelineColumn =
            "①约定起播\nt ${formatUs(phoneAtComparison?.scheduledStartPhoneUs ?: -1L)}\n" +
            "②调用AudioTrack.play\nt ${formatUs(phoneAtComparison?.playCalledPhoneUs ?: -1L)}\n" +
            "③TCP收包前沿\nPTS ${formatUs(receivedAtComparisonUs)}\n" +
            "④应用队列末端\nPTS ${formatUs(queuedAtComparisonUs)}\n" +
            "⑤AudioTrack写入末端\nPTS ${formatUs(writtenAtComparisonUs)}\n" +
            "⑥AudioTrack实际播放\nPTS ${formatUs(playedAtComparisonUs)}"

        diagnosticDetails =
            "【逐行分析｜①～⑤均为手机列－电视列】\n" +
            "①约定起播差　${formatSignedRawUs(scheduleDeltaUs)}（含首个PCM补偿）\n" +
            "②play调用差　${formatSignedRawUs(playCallDeltaUs)}\n" +
            "③解码→收包PTS差　${formatSignedRawUs(decodeToReceiveDeltaUs)}\n" +
            "④PCM→应用队列PTS差　${formatSignedRawUs(captureToQueueDeltaUs)}\n" +
            "⑤TCP→AudioTrack写入PTS差　${formatSignedRawUs(sendToWriteDeltaUs)}\n" +
            "⑥最终音画差　${formatSignedDurationUs(frameAvUs)}\n" +
            "网络单向耗时 ${formatDurationUs(phoneAtComparison?.networkTransitUs ?: -1L)} · " +
            "应用待写 ${formatDurationUs(applicationPendingAtComparisonUs)} · 输出待播 ${formatDurationUs(outputPendingAtComparisonUs)}\n" +
            "总储备 ${formatDurationUs(totalPendingAtComparisonUs)} · 校时RTT $rtt\n" +
            "手机队列 ${formatDurationUs(phoneAtComparison?.queuedDurationUs ?: -1L)} · " +
            "软件纠偏 ${String.format(java.util.Locale.US, "%.3f", phoneAtComparison?.playbackMediaRate ?: 1f)}x\n" +
            "欠载 ${phoneAtComparison?.underrunCount ?: 0}次 · 丢包 ${phoneAtComparison?.missingPackets ?: 0}包/${phoneAtComparison?.gapEvents ?: 0}次"

        tvDiagnostics = diagnostics
        rebuildDiagnostics()
    }

    private fun rebuildDiagnostics() {
        if (tvDiagnostics.isBlank()) diagnostics = phoneDiagnostics.ifBlank {
            "连接并开始播放后，这里每秒显示一次电视与手机的完整时间线。"
        }
        observer?.invoke()
    }

    private fun formatSignedRawUs(valueUs: Long?): String {
        if (valueUs == null) return "--"
        val sign = if (valueUs > 0) "+" else ""
        return "$sign${valueUs / 1000.0} ms"
    }

    private fun formatUs(valueUs: Long): String {
        if (valueUs < 0) return "--"
        val seconds = valueUs / 1_000_000L
        val millis = valueUs % 1_000_000L / 1_000L
        return "$seconds.${millis.toString().padStart(3, '0')} s"
    }

    private fun formatDurationUs(valueUs: Long): String =
        if (valueUs < 0) "--" else "${valueUs / 1000.0} ms"

    private fun formatSignedDurationUs(
        valueUs: Long?,
        positiveLabel: String = "声音落后",
        negativeLabel: String = "声音提前"
    ): String {
        if (valueUs == null) return "--"
        val label = if (valueUs >= 0) positiveLabel else negativeLabel
        return "${valueUs / 1000.0} ms（$label）"
    }

    @Suppress("DEPRECATION")
    private fun acquireAudioFocus(): Boolean {
        if (hasAudioFocus) return true

        val result = if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN
            )
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setWillPauseWhenDucked(true)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(
                    focusChangeListener,
                    Handler(Looper.getMainLooper())
                )
                .build()

            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        if (!hasAudioFocus) {
            audioFocusRequest = null
        }

        return hasAudioFocus
    }

    @Suppress("DEPRECATION")
    private fun releaseAudioFocus() {
        if (!hasAudioFocus) return

        hasAudioFocus = false

        if (Build.VERSION.SDK_INT >= 26) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
            audioFocusRequest = null
        } else {
            audioManager.abandonAudioFocus(focusChangeListener)
        }
    }

    /**
     * 默认优先级：3.5mm/USB有线耳机 > 蓝牙媒体耳机 > 手机扬声器。
     * 用户从页面主动选择设备后优先保持该设备，直到它断开。
     */
    private fun findListeningDevice(): AudioDeviceInfo? {
        val devices = supportedOutputDevices()
        manuallySelectedDeviceId?.let { selectedId ->
            devices.firstOrNull { it.id == selectedId }?.let { return it }
            manuallySelectedDeviceId = null
        }
        return devices.minByOrNull(::outputPriority)
    }

    private fun currentListeningDevice(): AudioDeviceInfo? {
        val devices = supportedOutputDevices()
        return listeningDeviceId?.let { id -> devices.firstOrNull { it.id == id } }
            ?: findListeningDevice()
    }

    fun availableOutputChoices(): List<OutputChoice> = supportedOutputDevices()
        .sortedWith(compareBy<AudioDeviceInfo>({ outputPriority(it) }, { it.id }))
        .map { OutputChoice(it.id, describeOutput(it).removePrefix("当前输出：")) }

    /** 页面主动切换本机输出；不暂停电视，也不会修改其他成员的AudioTrack。 */
    fun selectOutputDevice(deviceId: Int): Boolean {
        val device = supportedOutputDevices().firstOrNull { it.id == deviceId } ?: return false
        manuallySelectedDeviceId = device.id
        if (listeningDeviceId == device.id) {
            observer?.invoke()
            return true
        }
        if (phoneAudio.hasActiveSession && !phoneAudio.switchOutputDevice(device)) {
            detail = "无法切换到${outputKind(device)}，请确认设备仍已连接。"
            return false
        }
        listeningDeviceId = device.id
        detail = if (phoneAudio.hasActiveSession) {
            "正在切换到${outputKind(device)}…"
        } else {
            "已选择${outputKind(device)}，开始收听时生效。"
        }
        observer?.invoke()
        return true
    }

    private fun supportedOutputDevices(): List<AudioDeviceInfo> {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            // 没有蓝牙权限时仍允许扬声器和有线输出，不让权限问题破坏基本收听。
            return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { isWiredOutput(it) || it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        }
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { isSupportedOutput(it) }
    }

    private fun outputPriority(device: AudioDeviceInfo): Int = when {
        isWiredOutput(device) -> 0
        isBluetoothOutput(device) -> 1
        device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 2
        else -> 3
    }

    /**
     * 插拔只切换本机AudioTrack的首选路由，不发送全组暂停或重新对齐命令。
     * 稍作延迟，避免Android设备列表在插拔回调瞬间尚未完成刷新。
     */
    private fun scheduleOutputRefresh() {
        Handler(Looper.getMainLooper()).postDelayed({
            val device = findListeningDevice()
            if (device == null) {
                if (phoneAudio.hasActiveSession) requestStreamRecovery("手机没有可用的音频输出设备。")
                observer?.invoke()
                return@postDelayed
            }
            if (listeningDeviceId == device.id) {
                observer?.invoke()
                return@postDelayed
            }
            if (phoneAudio.hasActiveSession && !phoneAudio.switchOutputDevice(device)) {
                requestStreamRecovery("手机音频输出切换失败，请点击音画对齐重试。")
                return@postDelayed
            }
            listeningDeviceId = device.id
            detail = "已自动切换到${outputKind(device)}：${device.productName}"
            observer?.invoke()
        }, 150)
    }

    private fun isWiredOutput(device: AudioDeviceInfo): Boolean = when (device.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> true
        else -> Build.VERSION.SDK_INT >= 26 && device.type in setOf(
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY
        )
    }

    private fun isBluetoothOutput(device: AudioDeviceInfo): Boolean = when {
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> true
        Build.VERSION.SDK_INT >= 28 && device.type == AudioDeviceInfo.TYPE_HEARING_AID -> true
        Build.VERSION.SDK_INT >= 31 && device.type in setOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER
        ) -> true
        else -> false
    }

    private fun isSupportedOutput(device: AudioDeviceInfo): Boolean =
        device.isSink && (isWiredOutput(device) || isBluetoothOutput(device) ||
            device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)

    private fun outputKind(device: AudioDeviceInfo): String = when {
        isBluetoothOutput(device) -> "蓝牙耳机"
        isWiredOutput(device) -> "有线耳机"
        else -> "手机扬声器"
    }

    private fun describeOutput(device: AudioDeviceInfo): String = when {
        isBluetoothOutput(device) -> "当前输出：蓝牙耳机 · ${device.productName}"
        isWiredOutput(device) -> "当前输出：有线耳机 · ${device.productName}"
        else -> "当前输出：手机扬声器"
    }

    private fun currentOutputLabel(): String = findListeningDevice()?.let(::outputKind) ?: "手机音频设备"


    override fun onDestroy() {
        observer = null
        pairing.disconnect()
        phoneAudio.stop()
        releaseAudioFocus()
        audioManager.unregisterAudioDeviceCallback(audioCallback)
        releaseResources()
        super.onDestroy()
    }
}
