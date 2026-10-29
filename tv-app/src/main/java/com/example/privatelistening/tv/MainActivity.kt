package com.example.privatelistening.tv

import android.Manifest
import android.app.AlertDialog
import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import android.app.Dialog
import android.view.Window
import android.view.WindowManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.ImageView
import android.media.MediaFormat

import com.example.privatelistening.common.PairingInfo
import com.example.privatelistening.tv.pairing.LocalNetworkAddress
import com.example.privatelistening.tv.pairing.PairingQrCode
import com.example.privatelistening.tv.pairing.PairingServer

import java.util.UUID
import java.net.InetSocketAddress
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.common.Format
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

@androidx.media3.common.util.UnstableApi
class MainActivity : ComponentActivity() {

    /**
     * 电视端收听会话状态。
     * 所有状态均由页面主线程修改。
     */
    private enum class ListeningState {
        IDLE,           // 没有收听会话
        PAIRING,        // 等待手机扫码
        PREPARING,      // 手机正在创建音频资源
        PREPARED,       // 资源已创建，但还不能认为正在收听
        STARTING,       // 正在协调实际输出和起播
        LISTENING,      // 已进入正式收听
        HELD,           // 普通暂停：保留TCP、应用队列和AudioTrack
        PAUSED,         // 用户暂停或播放器缓冲，控制连接仍保留
        WAITING_HEADPHONES, // 兼容旧手机端：手机仍连接，等待音频输出恢复
        NEEDS_ALIGNMENT,    // 手机仍连接，等待用户重新建立音画基准
        FAILED          // 会话失败，等待用户关闭或重试
    }

    /**
     * 浮窗关闭不一定意味着结束收听。
     */
    private enum class DialogCloseReason {
        CANCEL,         // 用户取消配对
        START_SUCCEEDED // 正式起播成功，只关闭浮窗
    }

    private var listeningState = ListeningState.IDLE

    private var dialogCloseReason = DialogCloseReason.CANCEL

    private var player: ExoPlayer? = null
    //由电视页面持有一个音频模块，后续音频发送线程也会使用这个实例
    private var tvAudio = TvAudio()
    private lateinit var playerView: PlayerView
    private lateinit var videoLibrary: View
    private lateinit var videoCards: LinearLayout
    private lateinit var controllerTitle: TextView
    private lateinit var realignButton: Button
    private lateinit var status: TextView
    private var controllerVisible = false
    /**
     * 用户明确按下暂停后保持为 true。它和 Player.playWhenReady 分开保存：
     * 部分电视盒子的控制层在自动隐藏、焦点切换时会产生一次多余的播放请求，
     * 该请求不能被当成用户要求继续播放。
     */
    private var userPauseHold = false
    private var libraryVisible = true
    private var videoUri: String? = null
    private var position = 0L
    private var resumePlayback = true
    //保存当前浮窗，避免用户连续点击时打开多个窗口。
    private var listeningDialog: Dialog? = null
    private var pairingServer: PairingServer? = null
    private var pairingHost: String? = null
    private var phoneAudioTarget: InetSocketAddress? = null
    private var preparedStreamId: UUID? = null
    private val pairingHandler = Handler(Looper.getMainLooper())

    private var pairingOpenedAt = 0L
    private var playbackBeforePairing: Boolean? = null

    private val localNetworkPermission = "android.permission.ACCESS_LOCAL_NETWORK"

    private val localNetworkRequestCode = 2001
    private val mediaReadRequestCode = 2002

    private var activeStartRequestId: UUID? = null

    private var startupCheck: Runnable? = null

    // 后续发送起播消息时使用。
    private var startupMediaPositionMs = 0L
    private var startupFirstPtsUs: Long? = null
    private var sessionDialog: Dialog? = null
    private var sessionGeneration = 0L
    private var privateAudioActive = false
    private var savedVolume = 1f
    private var clockOffsetUs = 0L
    private var clockBest: com.example.privatelistening.common.ListeningTiming.Sample? = null
    private var clockSamples = 0
    private var pingT0 = -1L
    private var pendingTvStartUs = 0L
    @Volatile private var lastScheduledVideoStartTvUs = -1L
    @Volatile private var lastVideoPlayCallTvUs = -1L
    private var startArmed = false
    private var startDeadline: Runnable? = null
    private var scheduleStart: Runnable? = null
    private var silentRecovery = false
    private var silentRestart: Runnable? = null
    private var resumeWhenPlayerReady = false
    private var resumingHeld = false
    private var streamRecoveryAttempts = 0
    private var liveAudioRecovery = false
    private var stablePlaybackSinceMs = 0L
    private val recoveryTimes = java.util.ArrayDeque<Long>()
    private val maxStreamRecoveryAttempts = 2
    // 缓冲、输出检查和七次校时均已在此之前完成；局域网内500ms足以完成
    // START_AT -> AudioTrack预填 -> START_ARMED -> COMMIT_START，同时明显缩短等待。
    private val synchronizedStartLeadUs = 500_000L
    private fun nowUs() = SystemClock.elapsedRealtimeNanos() / 1000
    @Volatile private var lastVideoFramePtsUs = -1L
    @Volatile private var lastVideoFrameCallbackTvUs = -1L
    @Volatile private var lastVideoFrameReleaseTvUs = -1L
    @Volatile private var firstFrameRenderedTvUs = -1L
    private var lastDiagnosticsSentTvUs = 0L

    private val syncTick = object : Runnable {
        override fun run() {
            if (listeningState != ListeningState.LISTENING && !liveAudioRecovery) return
            val p = player ?: return
            tvAudio.updatePosition(p.currentPosition * 1000, p.isPlaying)
            if (listeningState != ListeningState.LISTENING) {
                pairingHandler.postDelayed(this, 20)
                return
            }
            if (stablePlaybackSinceMs != 0L && SystemClock.elapsedRealtime() - stablePlaybackSinceMs >= 30_000) {
                streamRecoveryAttempts = 0
            }
            if (pairingServer?.sendControl(org.json.JSONObject().put("type", "SYNC")
                    .put("positionUs", p.currentPosition * 1000)
                    .put("phoneTimeUs", nowUs())) != true) {
                showPairingFailure("同步连接中断，已暂停视频。")
                return
            }
            val diagnosticsNowUs = nowUs()
            if (diagnosticsNowUs - lastDiagnosticsSentTvUs >= 1_000_000L) {
                lastDiagnosticsSentTvUs = diagnosticsNowUs
                sendTimelineDiagnostics(p, diagnosticsNowUs)
            }
            pairingHandler.postDelayed(this, 100)
        }
    }

    /**
     * 每秒汇总一次，不逐帧发送，避免诊断本身占用WebSocket并改变播放结果。
     * videoFrameReleaseTvUs 是系统计划送显时刻；普通Android应用无法读取面板像素真正发光时刻。
     */
    private fun sendTimelineDiagnostics(p: ExoPlayer, sampledAtTvUs: Long) {
        pairingServer?.sendDiagnostics { peerId ->
            val audio = tvAudio.diagnosticsForPeer(peerId)
            org.json.JSONObject()
                .put("sampledAtTvUs", sampledAtTvUs)
                .put("videoPositionUs", p.currentPosition * 1000)
                .put("videoPlaying", p.isPlaying)
                .put("videoPlaybackState", p.playbackState)
                .put("videoFramePtsUs", lastVideoFramePtsUs)
                .put("videoFrameCallbackTvUs", lastVideoFrameCallbackTvUs)
                .put("videoFrameReleaseTvUs", lastVideoFrameReleaseTvUs)
                .put("firstFrameRenderedTvUs", firstFrameRenderedTvUs)
                .put("scheduledVideoStartTvUs", lastScheduledVideoStartTvUs)
                .put("videoPlayCallTvUs", lastVideoPlayCallTvUs)
                .put("audioDecodedPtsUs", audio.decodedPcmPtsUs)
                .put("audioDecodedTvUs", audio.decodedPcmAtTvUs)
                .put("audioCapturedPtsUs", audio.capturedChunkPtsUs)
                .put("audioCapturedTvUs", audio.capturedChunkAtTvUs)
                .put("tvCaptureQueueUs", audio.tvCaptureQueueUs)
                .put("audioSentPtsUs", audio.tcpSentPtsUs)
                .put("audioSendStartTvUs", audio.tcpSendStartTvUs)
                .put("audioSendDoneTvUs", audio.tcpSendDoneTvUs)
                .put("senderPendingChunks", audio.senderPendingChunks)
        }
    }

    /** 手机分别完成七次校时；播放器只接受全组就绪回调。 */
    private fun beginClockSync() {
        startArmed = false
        val request = activeStartRequestId
        val deadline = Runnable {
            if (activeStartRequestId == request && listeningState == ListeningState.STARTING)
                showPairingFailure("多人同步起播超时，请重试。")
        }
        startDeadline = deadline
        pairingHandler.postDelayed(deadline, 6000)
        if (pairingServer?.beginClockSync() != true) showPairingFailure("无法开始多人校时。")
    }

    private fun handleControl(message: org.json.JSONObject) {
        when (message.optString("type")) {
            "HEADPHONES_REQUIRED" -> showRecoverableListeningState(
                ListeningState.WAITING_HEADPHONES,
                message.optString("message", "请连接有线或 USB 耳机。"),
                "重新检查耳机"
            )
            "REALIGN_REQUIRED" -> showRecoverableListeningState(
                ListeningState.NEEDS_ALIGNMENT,
                message.optString("message", "当前音画需要重新对齐。"),
                "音画对齐"
            )
            "AUTO_REALIGN_REQUIRED" -> showPairingFailure("检测到持续音画偏差，正在暂停并重新对齐。")
            "AUDIO_RECOVERY_REQUIRED" -> showPairingFailure(message.optString("message", "手机音频暂时中断，正在恢复。"))
            "REQUEST_REALIGN" -> beginSynchronizedRealignment("手机已请求音画对齐，画面与声音正在同步暂停…")
            "END_LISTENING" -> endListeningFromPhone()
            "PHONE_ERROR" -> showPairingFailure("手机播放异常，已暂停电视。")
            "GROUP_UNAVAILABLE" -> showRecoverableListeningState(
                ListeningState.NEEDS_ALIGNMENT,
                "所有手机暂不可用，画面已暂停。请检查耳机后重新同步，或结束全部收听。",
                "重新同步"
            )
            "GROUP_CLOCK_READY" -> {
                if (listeningState != ListeningState.STARTING || pendingTvStartUs != 0L) return
                pendingTvStartUs = nowUs() + synchronizedStartLeadUs
                lastScheduledVideoStartTvUs = pendingTvStartUs
                val ptsDelta = (startupFirstPtsUs ?: return) - startupMediaPositionMs * 1000
                if (kotlin.math.abs(ptsDelta) > 100_000) {
                    showPairingFailure("音频首帧与视频位置不匹配，未继续起播。")
                    return
                }
                if (pairingServer?.sendControl(org.json.JSONObject().put("type", "START_AT")
                        .put("phoneStartUs", pendingTvStartUs + ptsDelta)) != true)
                    showPairingFailure("多人起播指令发送失败。")
            }
            "START_ARMED" -> {
                if (listeningState != ListeningState.STARTING || pendingTvStartUs == 0L || startArmed) return
                if (pendingTvStartUs - nowUs() < 150_000 || player?.playbackState != Player.STATE_READY) {
                    showPairingFailure("起播准备过晚或电视未就绪，请重试。")
                    return
                }
                startArmed = true
                if (pairingServer?.sendControl(org.json.JSONObject().put("type", "COMMIT_START")) != true) {
                    showPairingFailure("起播提交失败。")
                    return
                }
                val request = activeStartRequestId
                val action = object : Runnable {
                    override fun run() {
                        if (activeStartRequestId != request || listeningState != ListeningState.STARTING) return
                        val remaining = pendingTvStartUs - nowUs()
                        if (remaining > 1000) { pairingHandler.postDelayed(this, maxOf(1, remaining / 1000)); return }
                        if (remaining < -80_000) { showPairingFailure("电视起播调度超时。"); return }
                        val p = player ?: return
                        // 自在听期间保留进入功能前的播放器音量，让电视和手机同时出声。
                        p.volume = savedVolume
                        privateAudioActive = true
                        tvAudio.updatePosition(p.currentPosition * 1000, p.isPlaying)
                        if (!tvAudio.resumeContinuousSending()) { showPairingFailure("音频发送任务已失效。"); return }
                        listeningState = ListeningState.LISTENING
                        // 稳定播放30秒后才清零故障次数，短暂起播不能刷新重试预算。
                        stablePlaybackSinceMs = SystemClock.elapsedRealtime()
                        startDeadline?.let { pairingHandler.removeCallbacks(it) }
                        startDeadline = null
                        if (!liveAudioRecovery) {
                            lastVideoPlayCallTvUs = nowUs()
                            p.play()
                        }
                        liveAudioRecovery = false
                        status.text = "同屏自在听中 · 电视与手机同时播放"
                        dismissDialogAfterStart()
                        pairingHandler.removeCallbacks(syncTick)
                        pairingHandler.post(syncTick)
                    }
                }
                scheduleStart = action
                pairingHandler.post(action)
            }
        }
    }

    /** 管理窗口复用已经认证的 WebSocket，继续时重新协商流，旧UDP自动失效。 */
    private fun manageListening() {
        if (listeningDialog != null) return
        val dialog = sessionDialog ?: return
        player?.pause()
        pairingHandler.removeCallbacks(syncTick)
        pairingServer?.sendControl(org.json.JSONObject().put("type", "PAUSE"))
        tvAudio.stopSending()
        tvAudio.cancelStartup()
        activeStartRequestId = null
        pendingTvStartUs = 0
        startArmed = false
        playbackBeforePairing = false
        silentRecovery = false
        listeningState = ListeningState.PREPARING
        listeningDialog = dialog
        dialogCloseReason = DialogCloseReason.CANCEL
        dialog.findViewById<Button>(R.id.cancel_listening).text = "结束全部"
        dialog.findViewById<Button>(R.id.start_listening).apply { text = "正在准备…"; isEnabled = false }
        dialog.findViewById<TextView>(R.id.audio_status).text = "已暂停，正在为当前位置重新准备音频…"
        dialog.show()
        refreshGroupUi()
        val format = tvAudio.outputFormat
        if (format == null || pairingServer?.requestAudioPreparation(format.sampleRate, format.channels) != true)
            showPairingFailure("无法重新准备收听，请重新扫码。")
    }

    /**
     * 普通暂停只暂停画面和手机声音，保留扫码连接，不打开管理浮窗。
     * 再次播放时会从当前媒体位置静默建立新流，避免继续消费暂停前的 PCM。
     */
    private fun pauseListeningWithoutDialog(message: String, resumeWhenReady: Boolean = false) {
        if (listeningState !in setOf(ListeningState.LISTENING, ListeningState.HELD) && !liveAudioRecovery) return
        startDeadline?.let { pairingHandler.removeCallbacks(it) }
        scheduleStart?.let { pairingHandler.removeCallbacks(it) }
        startupCheck?.let { pairingHandler.removeCallbacks(it) }
        silentRestart?.let { pairingHandler.removeCallbacks(it) }
        startDeadline = null
        scheduleStart = null
        startupCheck = null
        silentRestart = null
        liveAudioRecovery = false
        resumingHeld = false
        listeningState = ListeningState.PAUSED
        resumeWhenPlayerReady = resumeWhenReady
        pairingHandler.removeCallbacks(syncTick)
        pairingServer?.sendControl(org.json.JSONObject().put("type", "PAUSE"))
        tvAudio.stopSending()
        tvAudio.cancelStartup()
        activeStartRequestId = null
        pendingTvStartUs = 0
        startArmed = false
        player?.pause()
        status.apply {
            text = message
            visibility = View.VISIBLE
        }
    }

    /** 普通暂停不清空约2秒PCM，也不关闭TCP或释放手机AudioTrack。 */
    private fun holdListeningWithoutDialog() {
        if (listeningState != ListeningState.LISTENING || liveAudioRecovery) return
        pairingHandler.removeCallbacks(syncTick)
        startDeadline?.let { pairingHandler.removeCallbacks(it) }
        scheduleStart?.let { pairingHandler.removeCallbacks(it) }
        startupCheck?.let { pairingHandler.removeCallbacks(it) }
        silentRestart?.let { pairingHandler.removeCallbacks(it) }
        startDeadline = null
        scheduleStart = null
        startupCheck = null
        silentRestart = null
        resumeWhenPlayerReady = false
        resumingHeld = false
        userPauseHold = true
        val p = player ?: return
        p.pause()
        tvAudio.updatePosition(p.currentPosition * 1000, false)
        if (pairingServer?.sendControl(org.json.JSONObject().put("type", "HOLD_PAUSE")) != true) {
            pauseListeningWithoutDialog("暂停保留失败，继续播放时将重新同步。")
            return
        }
        listeningState = ListeningState.HELD
        status.apply {
            text = "同屏自在听已暂停 · 音频缓存已保留"
            visibility = View.VISIBLE
        }
    }

    /** 复用原TCP、PCM队列和校时偏移，在很近的共同时间恢复。 */
    private fun resumeHeldListening() {
        if (listeningState != ListeningState.HELD || pairingServer == null) return
        val p = player ?: return
        // 只有显式播放按键会调用本方法；从这里开始才允许恢复。
        userPauseHold = false
        val tvStartUs = nowUs() + 400_000L
        lastScheduledVideoStartTvUs = tvStartUs
        if (pairingServer?.sendControl(org.json.JSONObject().put("type", "RESUME_AT")
                .put("phoneStartUs", tvStartUs)) != true) {
            pauseListeningWithoutDialog("快速继续失败，正在重新同步手机声音…")
            scheduleSilentRestart(0)
            return
        }
        listeningState = ListeningState.LISTENING
        stablePlaybackSinceMs = SystemClock.elapsedRealtime()
        status.apply { text = "正在继续同屏自在听…"; visibility = View.VISIBLE }
        pairingHandler.removeCallbacks(syncTick)
        pairingHandler.post(syncTick)
        val action = object : Runnable {
            override fun run() {
                if (listeningState != ListeningState.LISTENING || player !== p) return
                val remainingUs = tvStartUs - nowUs()
                if (remainingUs > 1_000) {
                    pairingHandler.postDelayed(this, maxOf(1L, remainingUs / 1000))
                    return
                }
                scheduleStart = null
                resumingHeld = false
                lastVideoPlayCallTvUs = nowUs()
                p.play()
            }
        }
        scheduleStart = action
        pairingHandler.post(action)
    }

    /**
     * 播放/暂停按钮由页面直接处理，不把 PlayerView 的焦点或自动隐藏事件当成播放意图。
     */
    private fun handleExplicitPlaybackToggle() {
        val current = player ?: return
        when {
            current.playWhenReady -> current.pause()
            listeningState == ListeningState.HELD && pairingServer != null -> {
                // 兼容升级前残留的HELD状态，但不再复用旧PCM和AudioTrack播放头。
                // 旧缓存可能已经领先暂停画面，必须丢弃并从准确媒体位置重建。
                pauseListeningWithoutDialog("正在从暂停位置重新准备手机声音…")
                userPauseHold = false
                scheduleSilentRestart(0)
            }
            listeningState == ListeningState.PAUSED && pairingServer != null -> {
                userPauseHold = false
                scheduleSilentRestart(0)
            }
            else -> {
                userPauseHold = false
                current.play()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isMediaKey = event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
            event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
            event.keyCode == KeyEvent.KEYCODE_HEADSETHOOK
        if (isMediaKey) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> if (player?.playWhenReady != true) handleExplicitPlaybackToggle()
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> if (player?.playWhenReady == true) handleExplicitPlaybackToggle()
                    else -> handleExplicitPlaybackToggle()
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 快进可能连续产生多个seek。先立即停掉旧声音，再等待短暂稳定后只重建一次。
     */
    private fun scheduleSilentRestart(delayMs: Long = 350L) {
        if (pairingServer == null) return
        if (listeningState == ListeningState.HELD) {
            pauseListeningWithoutDialog("正在定位画面并同步手机声音…")
        }
        if (listeningState == ListeningState.LISTENING) {
            pauseListeningWithoutDialog("正在定位画面并同步手机声音…")
        }
        if (listeningState != ListeningState.PAUSED) return
        resumeWhenPlayerReady = false
        silentRestart?.let { pairingHandler.removeCallbacks(it) }
        val restart = Runnable {
            silentRestart = null
            beginSilentAudioPreparation()
        }
        silentRestart = restart
        pairingHandler.postDelayed(restart, delayMs)
    }

    /** 保留 WebSocket，在后台完成 PREPARE→ARM→缓冲→校时→起播，不显示浮窗。 */
    private fun beginSilentAudioPreparation() {
        if (pairingServer == null || listeningState != ListeningState.PAUSED) return
        val dialog = sessionDialog ?: return
        val format = tvAudio.outputFormat
        if (format == null) {
            showPairingFailure("当前视频没有可用的 PCM 音频格式。")
            return
        }
        if (!liveAudioRecovery) player?.pause()
        playerView.hideController()
        playerView.requestFocus()
        listeningState = ListeningState.PREPARING
        silentRecovery = true
        listeningDialog = dialog // 作为本轮异步回调的会话标识；Dialog 本身不 show。
        phoneAudioTarget = null
        preparedStreamId = null
        status.apply {
            text = "正在同步手机声音…"
            visibility = View.VISIBLE
        }
        dialog.findViewById<TextView>(R.id.audio_status).text = "正在后台重新准备音频…"
        dialog.findViewById<Button>(R.id.start_listening).apply {
            text = "正在准备…"
            isEnabled = false
        }
        if (pairingServer?.requestAudioPreparation(format.sampleRate, format.channels) != true) {
            showPairingFailure("无法重新准备音频，请检查手机连接。")
        }
    }

    /**
     * 用户手动对齐和持续大偏差都使用一次完整的同步重启：
     * 先同时暂停电视画面与手机声音，再从同一媒体位置准备新 PCM，最后通过
     * START_AT/COMMIT_START 在同一单调时钟目标上恢复。这样不会再出现画面继续走、
     * 手机声音单独消失并追赶的体验。
     */
    private fun beginSynchronizedRealignment(message: String) {
        if (pairingServer?.hasConnectedPhone() != true || libraryVisible) return
        if (listeningState == ListeningState.PREPARING || listeningState == ListeningState.STARTING) return
        streamRecoveryAttempts = 0 // Explicit user action starts a new bounded retry budget.
        silentRestart?.let { pairingHandler.removeCallbacks(it) }
        silentRestart = null
        if (listeningState == ListeningState.LISTENING || liveAudioRecovery) {
            pauseListeningWithoutDialog(message)
        } else {
            pairingHandler.removeCallbacks(syncTick)
            startDeadline?.let { pairingHandler.removeCallbacks(it) }
            scheduleStart?.let { pairingHandler.removeCallbacks(it) }
            startupCheck?.let { pairingHandler.removeCallbacks(it) }
            activeStartRequestId = null
            pendingTvStartUs = 0
            startArmed = false
            tvAudio.cancelStartup()
            tvAudio.stopSending()
            pairingServer?.sendControl(org.json.JSONObject().put("type", "PAUSE"))
            player?.pause()
            listeningState = ListeningState.PAUSED
            status.apply { text = message; visibility = View.VISIBLE }
        }
        liveAudioRecovery = false
        resumeWhenPlayerReady = false
        notifySessionPaused("画面与声音已暂停，正在音画对齐，完成后将一起播放。")
        // Close a previous failure dialog without invoking its cancel/disconnect callback.
        listeningDialog = null
        sessionDialog?.dismiss()
        scheduleSilentRestart(150)
    }

    /** 会话级暂停状态不依赖已被清理的 streamId/requestId，失败后两端仍能一致显示。 */
    private fun notifySessionPaused(message: String) {
        pairingServer?.sendControl(org.json.JSONObject()
            .put("type", "SESSION_PAUSED").put("message", message))
    }

    /** 手机明确退出自在听：结束手机会话，电视保留原音量继续播放。 */
    private fun endListeningFromPhone() {
        val canResumeVideo = !libraryVisible && player?.mediaItemCount != 0
        listeningDialog?.setOnDismissListener(null)
        listeningDialog?.setOnCancelListener(null)
        listeningDialog?.dismiss()
        listeningDialog = null
        player?.pause()
        stopPairing()
        status.apply {
            text = if (canResumeVideo) "自在听已结束 · 电视继续播放" else "自在听连接已结束"
            visibility = View.VISIBLE
        }
        if (canResumeVideo) player?.play()

        showStyledNotice(
            title = "自在听已结束",
            message = if (canResumeVideo) "手机已退出连接，电视保持原音量继续播放。" else "手机已退出连接，可选择视频后重新连接。",
            primaryText = "知道了",
            secondaryText = if (canResumeVideo) "重新连接" else null,
            secondaryAction = if (canResumeVideo) ({ requestPrivateListening() }) else null
        )
    }

    /**
     * 手机输出异常和音画偏差只结束对应音频流，扫码建立的 WebSocket 会话继续保留。
     * 视频先暂停并保留播放器原音量，等待用户恢复或重新同步。
     */
    private fun showRecoverableListeningState(
        nextState: ListeningState,
        message: String,
        actionText: String
    ) {
        liveAudioRecovery = false
        silentRestart?.let { pairingHandler.removeCallbacks(it) }
        silentRestart = null
        player?.pause()
        pairingHandler.removeCallbacks(syncTick)
        startDeadline?.let { pairingHandler.removeCallbacks(it) }
        scheduleStart?.let { pairingHandler.removeCallbacks(it) }
        startupCheck?.let { pairingHandler.removeCallbacks(it) }
        startDeadline = null
        scheduleStart = null
        startupCheck = null
        pendingTvStartUs = 0
        pingT0 = -1
        startArmed = false
        startupFirstPtsUs = null
        activeStartRequestId = null
        tvAudio.cancelStartup()
        tvAudio.stopSending()
        phoneAudioTarget = null
        preparedStreamId = null
        if (privateAudioActive) player?.volume = savedVolume
        privateAudioActive = false
        playbackBeforePairing = false
        listeningState = nextState
        notifySessionPaused(message)
        status.text = message
        realignButton.visibility = View.VISIBLE

        val dialog = sessionDialog ?: return
        silentRecovery = false
        if (!dialog.isShowing) {
            listeningDialog = dialog
            dialogCloseReason = DialogCloseReason.CANCEL
            dialog.show()
        }
        dialog.findViewById<ImageView>(R.id.pairing_qr).visibility = View.GONE
        dialog.findViewById<TextView>(R.id.qr_hint).apply {
            visibility = View.VISIBLE
            text = "手机保持连接"
        }
        dialog.findViewById<TextView>(R.id.connection_status).text = "手机已连接，无需重新扫码"
        dialog.findViewById<TextView>(R.id.audio_status).text = message
        dialog.findViewById<Button>(R.id.cancel_listening).text = "结束全部"
        dialog.findViewById<Button>(R.id.start_listening).apply {
            text = actionText
            isEnabled = true
            requestFocus()
        }
        refreshGroupUi()
    }

    private fun handleListeningPrimaryAction(dialog: Dialog) {
        when (listeningState) {
            ListeningState.PREPARED -> beginListeningStart(dialog)
            ListeningState.WAITING_HEADPHONES,
            ListeningState.NEEDS_ALIGNMENT -> retryAudioPreparation(dialog)
            else -> Unit
        }
    }

    /** 重新读取当前媒体位置与 PCM 格式，并让手机创建一轮全新的接收资源。 */
    private fun retryAudioPreparation(dialog: Dialog) {
        if (listeningDialog !== dialog) return
        streamRecoveryAttempts = 0 // 用户明确重试，重新开放两次自动恢复机会。
        val format = tvAudio.outputFormat
        if (format == null) {
            showPairingFailure("当前视频没有可用的PCM音频格式。")
            return
        }
        listeningState = ListeningState.PREPARING
        // “重新同步/重新准备”本身就是用户对恢复的完整确认。准备成功后直接继续
        // 输出检查、缓冲、校时和统一起播，不再让用户第二次点击“开始自在听”。
        silentRecovery = true
        phoneAudioTarget = null
        preparedStreamId = null
        dialog.findViewById<TextView>(R.id.audio_status).text = "正在重新准备音频…"
        dialog.findViewById<Button>(R.id.start_listening).apply {
            text = "正在准备…"
            isEnabled = false
        }
        if (pairingServer?.requestAudioPreparation(format.sampleRate, format.channels) != true) {
            showPairingFailure("无法重新准备音频，请检查手机连接。")
        }
    }

    private fun askRestoreTvAudio(message: String) {
        player?.pause()
        showStyledNotice(
            title = "自在听已停止",
            message = message,
            primaryText = "恢复电视播放",
            primaryAction = { player?.play() },
            secondaryText = "保持暂停"
        )
    }

    /** 统一自在听提示窗口的面板、字体、按钮尺寸和遥控器焦点表现。 */
    private fun showStyledNotice(
        title: String,
        message: String,
        primaryText: String,
        primaryAction: (() -> Unit)? = null,
        secondaryText: String? = null,
        secondaryAction: (() -> Unit)? = null
    ) {
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(R.layout.dialog_notice)
            setCanceledOnTouchOutside(false)
        }
        dialog.findViewById<TextView>(R.id.notice_title).text = title
        dialog.findViewById<TextView>(R.id.notice_message).text = message
        val primary = dialog.findViewById<Button>(R.id.notice_primary)
        primary.text = primaryText
        primary.setOnClickListener {
            dialog.dismiss()
            primaryAction?.invoke()
        }
        dialog.findViewById<Button>(R.id.notice_secondary).apply {
            visibility = if (secondaryText == null) View.GONE else View.VISIBLE
            text = secondaryText.orEmpty()
            setOnClickListener {
                dialog.dismiss()
                secondaryAction?.invoke()
            }
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.68f }
            val width = (560 * resources.displayMetrics.density).toInt()
            setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        primary.requestFocus()
    }

    private val pairingHealthCheck = object : Runnable {
        override fun run() {
            if (pairingServer == null) return

            val host = pairingHost ?: return

            when {
                !LocalNetworkAddress.isStillAvailable(this@MainActivity, host
                ) -> { showPairingFailure("网络连接发生变化，请取消后重新打开自在听。")
                }

                listeningState == ListeningState.PAIRING && SystemClock.elapsedRealtime() - pairingOpenedAt >
                        5 * 60_000L -> { showPairingFailure("本次配对已超时，请取消后重新生成二维码。")
                }

                else -> { pairingHandler.postDelayed(this, 1000)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 电视播放页使用沉浸式全屏；遥控器确认键或方向键仍会唤起播放器控制层。
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        playerView = findViewById(R.id.player_view)
        // 固定为5秒，避免DefaultTimeBar默认按视频总时长的1/20跳转。
        playerView.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)
            ?.setKeyTimeIncrement(5_000L)
        videoLibrary = findViewById(R.id.video_library)
        videoCards = findViewById(R.id.video_cards)
        controllerTitle = playerView.findViewById(R.id.controller_title)
        realignButton = playerView.findViewById(R.id.realign_audio)
        status = findViewById(R.id.status)
        playerView.controllerAutoShow = false
        playerView.controllerHideOnTouch = true
        playerView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && !libraryVisible && listeningDialog == null &&
                (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_MENU)
            ) {
                playerView.showController()
                playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress)?.requestFocus()
                true
            } else false
        }
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            controllerVisible = visibility == View.VISIBLE
            // 控制层超时隐藏只允许改变界面，绝不能结束用户暂停。
            if (!controllerVisible && userPauseHold && player?.playWhenReady == true) {
                player?.pause()
            }
            if (!controllerVisible &&
                (listeningState == ListeningState.IDLE || listeningState == ListeningState.LISTENING)
            ) {
                status.visibility = View.GONE
            } else if (controllerVisible && listeningState == ListeningState.LISTENING) {
                status.apply {
                    text = "同屏自在听中 · 电视与手机同时播放"
                    this.visibility = View.VISIBLE
                }
            }
        })
        videoUri = savedInstanceState?.getString("uri")
            ?: getPreferences(MODE_PRIVATE).getString("uri", null)
        position = savedInstanceState?.getLong("position") ?: 0L
        resumePlayback = savedInstanceState?.getBoolean("playing") ?: true
        // 选集和自在听都属于播放器控制层，不再悬浮在正常播放画面上。
        playerView.findViewById<Button>(R.id.choose).setOnClickListener { showFixedVideoLibrary() }
        val playPause = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)
        playPause.setOnClickListener { handleExplicitPlaybackToggle() }
        playPause.setOnKeyListener { _, keyCode, event ->
            val isConfirm = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (!isConfirm) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                handleExplicitPlaybackToggle()
            }
            // 同时消费 DOWN/UP，避免盒子固件把同一次确认键再交给 PlayerView。
            true
        }
        playerView.findViewById<Button>(R.id.private_listening).setOnClickListener {
            requestPrivateListening()
        }
        realignButton.setOnClickListener {
            if (pairingServer != null && listeningDialog == null &&
                (listeningState == ListeningState.LISTENING || listeningState == ListeningState.HELD ||
                    listeningState == ListeningState.PAUSED)
            ) {
                beginSynchronizedRealignment("正在重新对齐画面与声音…")
            }
        }
        findViewById<Button>(R.id.choose_media).setOnClickListener { requestVideoSelection() }
        findViewById<Button>(R.id.refresh_library).setOnClickListener { rebuildFixedVideoCards() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!libraryVisible && listeningDialog == null) {
                    showFixedVideoLibrary()
                } else {
                    // 已在片库时交还系统，第二次返回才真正退出电视应用。
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        playerView.hideController()
    }

    /**
     * 播放页默认不显示控件。上键唤起进度条并把焦点放到时间轴，
     * 左右键随后由 Media3 DefaultTimeBar 处理快退和快进。
     */
    /** 固定片库目录：/sdcard/Android/data/<包名>/files/videos/ */
    private fun fixedVideoDirectory(): File = File(getExternalFilesDir(null) ?: filesDir, "videos")

    private fun showFixedVideoLibrary() {
        pauseListeningForLibrary()
        player?.pause()
        libraryVisible = true
        playerView.hideController()
        status.visibility = View.GONE
        videoLibrary.visibility = View.VISIBLE
        rebuildFixedVideoCards()
    }

    /** 返回片库只暂停当前音频流，手机的 WebSocket 配对继续保留。 */
    private fun pauseListeningForLibrary() {
        liveAudioRecovery = false
        if (pairingServer == null) return
        pairingHandler.removeCallbacks(syncTick)
        startDeadline?.let { pairingHandler.removeCallbacks(it) }
        scheduleStart?.let { pairingHandler.removeCallbacks(it) }
        startupCheck?.let { pairingHandler.removeCallbacks(it) }
        silentRestart?.let { pairingHandler.removeCallbacks(it) }
        startDeadline = null
        scheduleStart = null
        startupCheck = null
        silentRestart = null
        silentRecovery = false
        resumeWhenPlayerReady = false
        activeStartRequestId = null
        pendingTvStartUs = 0
        startArmed = false
        phoneAudioTarget = null
        preparedStreamId = null
        pairingServer?.sendControl(org.json.JSONObject().put("type", "PAUSE"))
        tvAudio.cancelStartup()
        tvAudio.stopSending()
        player?.pause()
        listeningState = ListeningState.PAUSED
    }

    /**
     * 从固定目录即时生成封面卡片。视频和海报使用同名文件，
     * 例如“第一集.mp4 + 第一集.jpg”，选中前完全不读取 MP4 内容。
     */
    private fun rebuildFixedVideoCards() {
        val directory = fixedVideoDirectory().apply { mkdirs() }
        val legacyDemo = File(getExternalFilesDir(null) ?: filesDir, "demo.mp4")
        val files = buildList {
            if (legacyDemo.isFile) add(legacyDemo)
            addAll(directory.listFiles().orEmpty().filter {
                it.isFile && it.extension.lowercase() in setOf("mp4", "m4v", "mkv", "webm")
            }.sortedBy { it.name.lowercase() })
        }.distinctBy { it.absolutePath }.take(20)

        videoCards.removeAllViews()
        if (files.isEmpty()) {
            videoCards.addView(TextView(this).apply {
                text = "固定片库暂无视频\n请把 MP4 放入：\n${directory.absolutePath}"
                setTextColor(Color.WHITE)
                textSize = 20f
                setPadding(dp(24), dp(24), dp(24), dp(24))
            })
            findViewById<Button>(R.id.refresh_library).requestFocus()
            return
        }

        files.forEach { file -> addVideoCard(file) }
        videoCards.getChildAt(0)?.requestFocus()
    }

    private fun addVideoCard(file: File) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(252), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(20)
            }
            setPadding(dp(8), dp(8), dp(8), dp(14))
            setBackgroundResource(R.drawable.bg_video_card)
            isFocusable = true
            isClickable = true
            setOnClickListener { selectVideo(Uri.fromFile(file), file.nameWithoutExtension) }
            setOnFocusChangeListener { view, focused ->
                view.animate().scaleX(if (focused) 1.045f else 1f)
                    .scaleY(if (focused) 1.045f else 1f).setDuration(140).start()
                view.elevation = if (focused) dp(12).toFloat() else 0f
            }
        }
        val cover = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(135))
            scaleType = ImageView.ScaleType.CENTER_CROP
            isFocusable = false
            isClickable = false
            setImageResource(R.drawable.app_icon)
        }
        val poster = listOf("jpg", "jpeg", "png", "webp")
            .map { extension -> File(file.parentFile, "${file.nameWithoutExtension}.$extension") }
            .firstOrNull { it.isFile }
        if (poster != null) {
            BitmapFactory.decodeFile(poster.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 })?.let {
                cover.setImageBitmap(it)
            }
        }
        val title = TextView(this).apply {
            text = file.nameWithoutExtension.replace('_', ' ')
            setTextColor(Color.WHITE)
            textSize = 18f
            maxLines = 2
            setPadding(dp(8), dp(14), dp(8), 0)
        }
        card.addView(cover)
        card.addView(title)
        videoCards.addView(card)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** 盒子无需安装文件管理器：直接读取Android MediaStore中已索引的视频。 */
    private fun requestVideoSelection() {
        val permissions = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (permissions.none { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(permissions, mediaReadRequestCode)
            return
        }
        showMediaStoreVideoPicker()
    }

    private data class LocalVideo(val name: String, val uri: Uri, val durationMs: Long)

    private fun showMediaStoreVideoPicker() {
        val videos = mutableListOf<LocalVideo>()
        val fallback = File(getExternalFilesDir(null) ?: filesDir, "demo.mp4")
        if (fallback.exists()) videos += LocalVideo("demo.mp4（应用演示文件）", Uri.fromFile(fallback), 0)

        try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION
            )
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                while (cursor.moveToNext() && videos.size < 200) {
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idColumn)
                    )
                    videos += LocalVideo(cursor.getString(nameColumn) ?: "未命名视频", uri, cursor.getLong(durationColumn))
                }
            }
        } catch (error: Exception) {
            status.text = "读取视频媒体库失败：${error.message}"
            playerView.showController()
            return
        }

        if (videos.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("未找到本地视频")
                .setMessage("请先把 MP4 复制到盒子的视频目录；也可以按README用ADB放到应用目录并命名为demo.mp4。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        val labels = videos.map { video ->
            if (video.durationMs > 0) {
                val totalSeconds = video.durationMs / 1000
                "${video.name}  ·  ${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
            } else video.name
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择本地视频")
            .setItems(labels) { _, which -> selectVideo(videos[which].uri, videos[which].name) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun selectVideo(uri: Uri, title: String = uri.lastPathSegment ?: "本地视频") {
        val continuePrivateListening = pairingServer != null
        if (continuePrivateListening) pauseListeningForLibrary()
        videoUri = uri.toString()
        position = 0
        // 换片时先暂停画面；播放器READY后再自动同步电视和手机声音。
        resumePlayback = !continuePrivateListening
        resumeWhenPlayerReady = continuePrivateListening
        libraryVisible = false
        videoLibrary.visibility = View.GONE
        controllerTitle.text = title
        getPreferences(MODE_PRIVATE).edit().putString("uri", videoUri).apply()
        loadVideo()
        status.apply { text = "视频加载中…"; visibility = View.VISIBLE }
        playerView.requestFocus()
        playerView.hideController()
    }

    /**
     * 普通播放和起播重新准备共用播放器创建入口。
     */
    private fun createTvPlayer(): ExoPlayer {
        val renderersFactory = tvAudio.createRenderersFactory(this)

        val newPlayer = ExoPlayer.Builder(this, renderersFactory).setSeekBackIncrementMs(5_000L).setSeekForwardIncrementMs(5_000L).build()

        player = newPlayer
        playerView.player = newPlayer
        lastVideoFramePtsUs = -1L
        lastVideoFrameCallbackTvUs = -1L
        lastVideoFrameReleaseTvUs = -1L
        firstFrameRenderedTvUs = -1L
        lastDiagnosticsSentTvUs = 0L

        newPlayer.setVideoFrameMetadataListener(object : VideoFrameMetadataListener {
            override fun onVideoFrameAboutToBeRendered(
                presentationTimeUs: Long,
                releaseTimeNs: Long,
                format: Format,
                mediaFormat: MediaFormat?
            ) {
                // 回调发生在解码帧即将交给Surface时；releaseTimeNs采用System.nanoTime时基。
                // 先用“距当前还有多久”映射到项目统一的elapsedRealtime时基，再发给手机比较。
                val callbackElapsedUs = nowUs()
                val releaseDelayUs = (releaseTimeNs - System.nanoTime()) / 1000L
                lastVideoFramePtsUs = presentationTimeUs
                lastVideoFrameCallbackTvUs = callbackElapsedUs
                lastVideoFrameReleaseTvUs = callbackElapsedUs + releaseDelayUs
            }
        })

        newPlayer.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (player === newPlayer) firstFrameRenderedTvUs = nowUs()
            }
            override fun onPlayerError(error: PlaybackException) {
                // 忽略已被替换的播放器回调。
                if (player !== newPlayer) return

                val message = "播放失败：${error.errorCodeName}。请检查视频或文件权限。"

                if (pairingServer != null) {
                    showPairingFailure(message)
                } else {
                    status.apply { text = message; visibility = View.VISIBLE }
                    playerView.showController()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (player !== newPlayer) return
                if (liveAudioRecovery && state == Player.STATE_BUFFERING) {
                    pauseListeningWithoutDialog("视频加载中，等待画面恢复…", resumeWhenReady = true)
                }
                if (listeningState == ListeningState.LISTENING || liveAudioRecovery) {
                    if (state == Player.STATE_ENDED) {
                        // 节目播完回到片库，手机仍保持连接，下一集可自动继续自在听。
                        pauseListeningForLibrary()
                        showFixedVideoLibrary()
                        return
                    }
                    if (state == Player.STATE_BUFFERING) {
                        // 普通缓冲不展示管理浮窗。先暂停手机声音，播放器 READY 后自动重同步。
                        pairingHandler.post {
                            if (listeningState == ListeningState.LISTENING) {
                                pauseListeningWithoutDialog("视频加载中，声音将在加载完成后自动同步…", resumeWhenReady = true)
                            }
                        }
                    }
                }
                if (state == Player.STATE_READY && listeningState == ListeningState.PAUSED && resumeWhenPlayerReady) {
                    resumeWhenPlayerReady = false
                    scheduleSilentRestart(0)
                }
                // 准备阶段不能被普通播放器提示覆盖。
                if (state == Player.STATE_READY && listeningState == ListeningState.IDLE
                ) {
                    status.text = "本地视频已就绪 · 当前声音由电视播放"
                    status.visibility = if (controllerVisible) View.VISIBLE else View.GONE
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (player !== newPlayer || reason != Player.DISCONTINUITY_REASON_SEEK) return
                if (liveAudioRecovery) {
                    pauseListeningWithoutDialog("正在定位画面并同步手机声音…")
                    scheduleSilentRestart()
                    return
                }
                // 已连接自在听时，跳转后按新位置自动准备，不再展示浮窗。
                pairingHandler.post {
                    when {
                        listeningState == ListeningState.LISTENING && listeningDialog == null -> scheduleSilentRestart()
                        listeningState == ListeningState.HELD && listeningDialog == null -> {
                            pauseListeningWithoutDialog("正在定位画面并同步手机声音…")
                            status.apply {
                                text = "已定位到新位置 · 点击播放后自动同步手机声音"
                                visibility = View.VISIBLE
                            }
                        }
                        listeningState == ListeningState.PAUSED && silentRestart != null -> scheduleSilentRestart()
                        listeningState == ListeningState.PAUSED -> status.apply {
                            text = "已定位到新位置 · 点击播放后自动同步手机声音"
                            visibility = View.VISIBLE
                        }
                    }
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (player !== newPlayer || reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) return
                if (liveAudioRecovery && !playWhenReady) {
                    userPauseHold = true
                    pauseListeningWithoutDialog("同屏自在听已暂停 · 点击播放即可继续")
                    return
                }
                pairingHandler.post {
                    when {
                        !playWhenReady && listeningState == ListeningState.LISTENING && listeningDialog == null && !resumingHeld -> {
                            // AudioTrack内部播放头、手机应用队列和电视解码队列无法在不同设备上
                            // 原子冻结。暂停时结束本轮音频流，继续时从当前画面位置重新建流，
                            // 避免复用约2秒未来PCM造成声音领先或随后触发自动对齐。
                            userPauseHold = true
                            pauseListeningWithoutDialog("同屏自在听已暂停 · 点击播放后从当前位置重新同步")
                        }

                        playWhenReady && listeningState == ListeningState.HELD && listeningDialog == null -> {
                            // HELD 只能由 handleExplicitPlaybackToggle() 解锁。若这里只收到
                            // 播放器请求，说明它不是可确认的用户操作，继续保持暂停。
                            newPlayer.pause()
                        }

                        playWhenReady && listeningState == ListeningState.PAUSED && listeningDialog == null -> {
                            // PlayerView或盒子固件产生的播放请求不能解除用户暂停；
                            // 只有handleExplicitPlaybackToggle()会启动可靠的重新准备流程。
                            newPlayer.pause()
                        }
                    }
                }
            }
        })
        return newPlayer
    }

    /**
     * 手机音频输出检查通过后调用。
     * 本阶段只准备电视 PCM：不恢复视频、不开始手机节目播放、不关闭浮窗。
     */
    private fun prepareStartupAudio(
        dialog: Dialog,
        requestId: UUID
    ) {
        if (listeningDialog !== dialog) return
        if (listeningState != ListeningState.STARTING) return
        if (activeStartRequestId != requestId) return

        val oldPlayer = player ?: return
        val mediaItem = oldPlayer.currentMediaItem

        if (mediaItem == null) {
            showPairingFailure("没有可重新准备的视频。")
            return
        }

        val expectedFormat = tvAudio.outputFormat

        if (expectedFormat == null) {
            showPairingFailure("没有可用的音频格式，请重新打开视频。")
            return
        }

        // 音频单独恢复选未来位置，等正常解码产生对应PCM；不seek、不重建视频播放器。
        val startPositionMs = oldPlayer.currentPosition.coerceAtLeast(0L) + if (liveAudioRecovery) 1200L else 0L

        // 临近片尾可能没有足够的起播音频。
        val durationMs = oldPlayer.duration
        if (durationMs >= 0 && durationMs - startPositionMs < 100L) {
            showPairingFailure("视频已接近结尾，请向前跳转后再开启自在听。")
            return
        }

        startupCheck?.let { pairingHandler.removeCallbacks(it) }
        startupCheck = null

        startupMediaPositionMs = startPositionMs
        startupFirstPtsUs = null

        dialog.findViewById<TextView>(R.id.audio_status).text = "音频：正在准备暂停位置对应的声音…"

        val originalVolume = oldPlayer.volume

        try {
            tvAudio.stopSending()
            val startupAudio: TvAudio
            val newPlayer: ExoPlayer
            if (liveAudioRecovery) {
                startupAudio = tvAudio
                newPlayer = oldPlayer
                startupAudio.prepareStartup(startPositionMs * 1000L, holdInput = false)
            } else {
                oldPlayer.pause()
                playerView.setKeepContentOnPlayerReset(true)
                playerView.player = null
                player = null
                oldPlayer.release()
                startupAudio = TvAudio()
                startupAudio.prepareStartup(startPositionMs * 1000L)
                tvAudio = startupAudio
                newPlayer = createTvPlayer()
                newPlayer.volume = originalVolume
                newPlayer.setSeekParameters(SeekParameters.EXACT)
                newPlayer.setMediaItem(mediaItem, startPositionMs)
                newPlayer.playWhenReady = false
                newPlayer.prepare()
            }

            // 低性能盒子也需要时间准备约500ms连续PCM；播放后再渐进补足约2秒储备。
            val deadline = SystemClock.elapsedRealtime() + 10_000

            val check = object : Runnable {
                override fun run() {
                    if (listeningDialog !== dialog || activeStartRequestId != requestId ||
                        listeningState != ListeningState.STARTING || player !== newPlayer || tvAudio !== startupAudio
                    ) {
                        return
                    }

                    val snapshot = startupAudio.startupSnapshot()

                    if (snapshot != null) {
                        if (snapshot.overflowed) {
                            showPairingFailure("起播音频队列超出容量，未继续启动。")
                            return
                        }

                        if (!snapshot.continuous) {
                            showPairingFailure("起播音频时间戳不连续，需要检查音频获取路径。")
                            return
                        }

                        if (snapshot.sampleRate != expectedFormat.sampleRate || snapshot.channels != expectedFormat.channels) {
                            showPairingFailure("重新准备后的音频格式发生变化，请重新扫码。")
                            return
                        }

                        if (snapshot.bufferedDurationUs >= TvAudio.STARTUP_BUFFER_TARGET_US) {
                            startupFirstPtsUs = snapshot.firstPtsUs
                            startupCheck = null
                            val bufferedMs = snapshot.bufferedDurationUs / 1000
                            dialog.findViewById<TextView>(R.id.audio_status).text = "音频：电视起播PCM已准备，" + "已缓存约 $bufferedMs ms"
                            sendStartupAudio(dialog, requestId, snapshot)
                            return
                        }
                    }

                    if (SystemClock.elapsedRealtime() >= deadline) {
                        showPairingFailure("暂停状态下未取得足够的起播PCM。" + "需要检查播放器音频准备路径。")
                        return
                    }
                    pairingHandler.postDelayed(this, 20)
                }
            }

            startupCheck = check
            pairingHandler.post(check)
        } catch (error: Exception) {
            showPairingFailure("起播音频准备失败：${error.message}")
        }
    }

    /** 起播 PCM 已准备：登记准确起点，然后保留队列并启动限量发送。 */
    private fun sendStartupAudio(
        dialog: Dialog,
        requestId: UUID,
        snapshot: TvAudio.StartupSnapshot
    ) {
        if (listeningDialog !== dialog || listeningState != ListeningState.STARTING || activeStartRequestId != requestId)
            return

        val target = phoneAudioTarget
        val streamId = preparedStreamId
        if (target == null || streamId == null) {
            showPairingFailure("手机音频接收信息已失效。")
            return
        }
        if (pairingServer?.expectPhoneBuffer(requestId, snapshot.firstPtsUs, snapshot.epoch) != true) {
            showPairingFailure("无法登记起播音频确认，请重新扫码。")
            return
        }
        dialog.findViewById<TextView>(R.id.audio_status).text = "音频：正在向手机发送起播音频…"
        val sendingAudio = tvAudio
        try {
            sendingAudio.startSendingGroup(pairingServer?.audioTargets().orEmpty(), streamId,
                onPeerError = { id, message ->
                    if (activeStartRequestId == requestId && tvAudio === sendingAudio)
                        pairingServer?.failPeer(id, message)
                }, onError = { message ->
                    if (activeStartRequestId == requestId && tvAudio === sendingAudio) showPairingFailure(message)
                })
        } catch (error: Exception) {
            showPairingFailure("起播音频发送启动失败：${error.message}")
        }
    }

    override fun onStart() {
        super.onStart()
        createTvPlayer()
        if (!libraryVisible && videoUri != null) {
            loadVideo()
            videoLibrary.visibility = View.GONE
            playerView.requestFocus()
            playerView.hideController()
        } else {
            showFixedVideoLibrary()
        }
    }

    private fun requestPrivateListening() {
        val needsPermission = Build.VERSION.SDK_INT >= 37 && checkSelfPermission(localNetworkPermission) != PackageManager.PERMISSION_GRANTED

        if (needsPermission) {
            requestPermissions(arrayOf(localNetworkPermission), localNetworkRequestCode)
            return
        }

        if (pairingServer != null && listeningDialog == null) manageListening()
        else showPrivateListeningDialog()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        // Android 14+ 允许用户只授权选中的视频，两种授权都能查询对应的 MediaStore 条目。
        val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }

        when (requestCode) {
            localNetworkRequestCode -> {
                if (granted) showPrivateListeningDialog()
                else status.text = "未获得局域网访问权限，无法连接手机。请允许权限后重试。"
            }
            mediaReadRequestCode -> {
                if (granted) showMediaStoreVideoPicker()
                else status.text = "未获得视频读取权限。请在系统设置中允许，或按 README 用 ADB 放入 demo.mp4。"
            }
        }
    }

    private fun showPrivateListeningDialog() {
        if (listeningDialog != null) return

        val currentPlayer = player ?: return

        if (currentPlayer.mediaItemCount == 0) {
            status.text = "请先选择视频，再开启同屏自在听。"
            return
        }

        // 每次新配对默认按“取消”处理关闭事件。
        // 只有正式起播成功后，才会修改关闭原因。
        dialogCloseReason = DialogCloseReason.CANCEL
        listeningState = ListeningState.PAIRING
        savedVolume = currentPlayer.volume
        playbackBeforePairing = currentPlayer.playWhenReady
        currentPlayer.pause()
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_private_listening)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(false)
        dialog.findViewById<Button>(R.id.cancel_listening)
            .setOnClickListener {
                dialogCloseReason = DialogCloseReason.CANCEL
                dialog.dismiss()
            }

        // 用户按遥控器返回键取消浮窗时，同样属于取消配对。
        dialog.setOnCancelListener {
            dialogCloseReason = DialogCloseReason.CANCEL
        }

        dialog.findViewById<Button>(R.id.start_listening).apply {
            isEnabled = false

            setOnClickListener {
                handleListeningPrimaryAction(dialog)
            }
        }

        dialog.setOnDismissListener {
            // 避免旧浮窗的关闭回调清理新浮窗对应的会话。
            if (listeningDialog === dialog) {
                val closeReason = dialogCloseReason
                val shouldResume = playbackBeforePairing ?: false

                listeningDialog = null
                playbackBeforePairing = null

                // 下一次打开浮窗重新从取消语义开始。
                dialogCloseReason = DialogCloseReason.CANCEL

                when (closeReason) {
                    DialogCloseReason.CANCEL -> {
                        // 用户取消：结束配对并恢复进入浮窗前的播放意图。
                        val wasPrivate = privateAudioActive
                        player?.pause()
                        stopPairing()
                        if (wasPrivate) askRestoreTvAudio("是否恢复电视扬声器和视频播放？")
                        else player?.playWhenReady = shouldResume
                    }

                    DialogCloseReason.START_SUCCEEDED -> {
                        // 起播成功：仅移除浮窗。
                        // 不关闭 WebSocket，不停止音频发送， 也不在这里调用 player.play()。
                        // 实际恢复视频由后续的起播协调流程决定。
                    }
                }

                if (closeReason == DialogCloseReason.CANCEL) {
                    playerView.showController()
                    playerView.findViewById<Button>(R.id.private_listening).requestFocus()
                } else {
                    status.visibility = View.GONE
                    playerView.hideController()
                    playerView.requestFocus()
                }
            }
        }

        listeningDialog = dialog
        sessionDialog = dialog
        dialog.show()

        dialog.window?.apply {
            val metrics = resources.displayMetrics
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(
                minOf((520 * metrics.density).toInt(), (metrics.widthPixels * 0.9f).toInt()),
                WindowManager.LayoutParams.WRAP_CONTENT
            )

            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val params = attributes
            params.dimAmount = 0.55f
            attributes = params
        }
        dialog.findViewById<Button>(R.id.cancel_listening).requestFocus()
        startPairing(dialog)
    }

    /**
     * 点击开始后先检查手机输出。
     * 不在这里直接恢复视频、发送旧 PCM 或关闭浮窗。
     */
    private fun beginListeningStart(dialog: Dialog) {
        if (listeningDialog !== dialog) return
        if (listeningState != ListeningState.PREPARED) return

        if (phoneAudioTarget == null || preparedStreamId == null) {
            showPairingFailure("音频接收信息不完整，请重新扫码。")
            return
        }

        listeningState = ListeningState.STARTING

        dialog.findViewById<Button>(R.id.start_listening).apply {
            isEnabled = false
            text = "正在启动…"
        }

        dialog.findViewById<TextView>(R.id.audio_status).text = "音频：正在检查手机音频输出…"

        val requestId = pairingServer?.requestOutputPreparation()

        if (requestId == null) {
            showPairingFailure("无法启动手机输出检查，请重新扫码。")
            return
        }

        activeStartRequestId = requestId
    }

    /** 二维码在未满员时持续可见，复用同一会话，不重新生成或断开老成员。 */
    private fun refreshGroupUi() {
        val server = pairingServer ?: return
        val dialog = sessionDialog ?: return
        dialog.findViewById<TextView>(R.id.connection_status).text = server.memberSummary()
        val full = server.connectedCount >= 3
        dialog.findViewById<ImageView>(R.id.pairing_qr).visibility = if (full) View.GONE else View.VISIBLE
        dialog.findViewById<TextView>(R.id.qr_hint).apply {
            visibility = if (full) View.VISIBLE else View.GONE
            text = "已满员（3/3）"
        }
    }

    private fun startPairing(dialog: Dialog) {
        val qrView = dialog.findViewById<ImageView>(R.id.pairing_qr)
        val qrHint = dialog.findViewById<TextView>(R.id.qr_hint)
        val connectionStatus = dialog.findViewById<TextView>(R.id.connection_status)
        val audioStatus = dialog.findViewById<TextView>(R.id.audio_status)
        qrView.visibility = View.GONE
        qrHint.visibility = View.VISIBLE
        qrHint.text = "正在启动连接服务…"

        val host = LocalNetworkAddress.find(this)
        if (host == null) {
            showPairingFailure("没有可用的局域网 IPv4 地址，请连接 Wi-Fi 或网线后重试。")
            return
        }

        val sessionId = UUID.randomUUID().toString()
        val token = UUID.randomUUID().toString()

        pairingHost = host
        pairingOpenedAt = SystemClock.elapsedRealtime()

        val generation = ++sessionGeneration
        val listener = object : PairingServer.Listener {
            override fun onControl(message: org.json.JSONObject) {
                if (generation == sessionGeneration) handleControl(message)
            }

            override fun onListening(port: Int) {
                if (listeningDialog !== dialog || pairingServer == null
                ) {
                    return
                }

                val info = PairingInfo(host = host, port = port, sessionId = sessionId, token = token, tvName = "${Build.MANUFACTURER} ${Build.MODEL}".take(60))

                try {
                    val bitmap = PairingQrCode.create(info.toQrText())

                    qrView.setImageBitmap(bitmap)
                    qrView.visibility = View.VISIBLE
                    qrHint.visibility = View.GONE
                    connectionStatus.text = "等待手机扫码，请确保手机与电视处于同一局域网。"
                    audioStatus.text = "音频：尚未接入"
                } catch (error: Exception) {
                    showPairingFailure("二维码生成失败：${error.message}")
                }
            }

            override fun onMembersChanged() {
                if (generation != sessionGeneration) return
                refreshGroupUi()
            }

            override fun onPeerUnavailable(id: String) {
                if (generation == sessionGeneration) tvAudio.stopPeer(id)
            }

            override fun onPhoneConnected(name: String) {
                if (generation != sessionGeneration) return
                // 片库没有播放中的媒体；保留成员，选择节目时再统一准备。
                if (libraryVisible) {
                    notifySessionPaused("已连接，等待电视选择节目。")
                    refreshGroupUi()
                    return
                }
                val wasRunning = privateAudioActive || silentRecovery
                // 第二、三人加入时先取消上一轮，避免旧START_AT恢复画面。
                startDeadline?.let { pairingHandler.removeCallbacks(it) }
                scheduleStart?.let { pairingHandler.removeCallbacks(it) }
                startupCheck?.let { pairingHandler.removeCallbacks(it) }
                silentRestart?.let { pairingHandler.removeCallbacks(it) }
                startDeadline = null; scheduleStart = null; startupCheck = null; silentRestart = null
                activeStartRequestId = null; pendingTvStartUs = 0; startArmed = false
                pairingHandler.removeCallbacks(syncTick)
                player?.pause()
                tvAudio.stopSending(); tvAudio.cancelStartup()
                notifySessionPaused("有新成员加入，画面与声音已暂停，准备后全体一起播放。")
                listeningDialog = dialog
                listeningState = ListeningState.PREPARING
                silentRecovery = wasRunning && !dialog.isShowing
                phoneAudioTarget = null; preparedStreamId = null
                audioStatus.text = "正在为所有手机准备音频…"
                realignButton.visibility = View.VISIBLE
                dialog.findViewById<Button>(R.id.start_listening).isEnabled = false
                refreshGroupUi()
                val format = tvAudio.outputFormat
                if (format == null || pairingServer?.requestAudioPreparation(format.sampleRate, format.channels) != true)
                    showPairingFailure("当前视频音频尚未准备好，请检查视频后重试。")
            }

            override fun onPhoneAudioPrepared(target: InetSocketAddress, streamId: UUID) {
                if (listeningDialog !== dialog) return

                if (listeningState != ListeningState.PREPARING) return

                listeningState = ListeningState.PREPARED

                phoneAudioTarget = target
                preparedStreamId = streamId
                audioStatus.text = "音频：手机接收端和输出资源已创建"
                refreshGroupUi()
                // 此按钮启动检查流程，不表示此刻已经可以播放节目。
                dialog.findViewById<Button>(R.id.start_listening).apply {
                    text = "开始自在听"
                    isEnabled = true
                }
                if (silentRecovery) {
                    beginListeningStart(dialog)
                }
            }

            override fun onPhoneOutputReady(requestId: UUID) {
                if (listeningDialog !== dialog) return
                if (listeningState != ListeningState.STARTING) return
                if (activeStartRequestId != requestId) return
                prepareStartupAudio(dialog, requestId)
            }

            override fun onPhoneBufferReady(
                requestId: UUID,
                firstPtsUs: Long,
                bufferedDurationUs: Long
            ) {
                if (listeningDialog !== dialog || listeningState != ListeningState.STARTING || activeStartRequestId != requestId || startupFirstPtsUs != firstPtsUs)
                    return
                dialog.findViewById<TextView>(R.id.audio_status).text = "音频：手机已收到约 ${bufferedDurationUs / 1000} ms，等待同步起播"
                beginClockSync()
            }

            override fun onPhoneDisconnected() {
                if (generation != sessionGeneration) return
                if (privateAudioActive || listeningDialog == null) {
                    showPairingFailure("手机已断开，视频已暂停。")
                    return
                }
                startDeadline?.let { pairingHandler.removeCallbacks(it) }
                scheduleStart?.let { pairingHandler.removeCallbacks(it) }
                pendingTvStartUs = 0
                startupCheck?.let { pairingHandler.removeCallbacks(it) }
                startupCheck = null
                startupFirstPtsUs = null

                tvAudio.cancelStartup()
                activeStartRequestId = null
                phoneAudioTarget = null
                preparedStreamId = null
                tvAudio.stopSending()
                if (listeningDialog !== dialog) return

                //当前二维码浮窗仍然打开时，断开后回到等待扫码状态
                listeningState = ListeningState.PAIRING
                audioStatus.text = "音频：手机已断开"
                connectionStatus.text = "手机已断开，可重新扫码连接。"
                if (qrView.drawable != null) {
                    qrView.visibility = View.VISIBLE
                    qrHint.visibility = View.GONE
                }
                dialog.findViewById<Button>(R.id.start_listening).isEnabled = false
            }

            override fun onFailure(message: String) {
                if (generation != sessionGeneration) return
                showPairingFailure("连接服务异常：$message")
            }
        }

        val server = PairingServer(host = host, sessionId = sessionId, token = token, listener = listener)

        pairingServer = server

        try {
            server.start()
            pairingHandler.postDelayed(pairingHealthCheck, 1000)
        } catch (error: Exception) {
            showPairingFailure("连接服务启动失败：${error.message}")
        }
    }

    /**
     * 后续正式起播流程确认成功后调用。
     * 不能在扫码成功或 AUDIO_PREPARED 时调用，
     * 因为这些事件还不能证明音频已具备正式起播条件。
     */
    private fun dismissDialogAfterStart() {
        if (listeningState != ListeningState.LISTENING) return
        val dialog = listeningDialog ?: return
        if (silentRecovery && !dialog.isShowing) {
            silentRecovery = false
            listeningDialog = null
            status.apply {
                text = "同屏自在听中 · 已自动完成音画同步"
                visibility = if (controllerVisible) View.VISIBLE else View.GONE
            }
            playerView.hideController()
            return
        }
        dialogCloseReason = DialogCloseReason.START_SUCCEEDED
        dialog.dismiss()
    }

    private fun showPairingFailure(message: String) {
        // 暂停恢复会重新创建 UDP 流和手机 AudioTrack。准备超时或瞬时音频错误不代表
        // 已认证的 WebSocket 已断开，因此先自动重试，避免用户看到“手机断开”。
        if (recoverStreamWithoutDisconnect(message)) return
        val wasPrivate = privateAudioActive
        player?.pause()
        playbackBeforePairing = false
        stopPairing()
        listeningState = ListeningState.FAILED
        status.apply {
            text = message
            visibility = View.VISIBLE
        }

        val dialog = listeningDialog ?: return

        // 静默恢复失败属于需要用户处理的异常，此时才显示窗口。
        if (!dialog.isShowing) {
            silentRecovery = false
            dialogCloseReason = DialogCloseReason.CANCEL
            dialog.show()
        }

        dialog.findViewById<ImageView>(R.id.pairing_qr)
            .visibility = View.GONE

        dialog.findViewById<TextView>(R.id.qr_hint).apply {
            visibility = View.VISIBLE
            text = "暂时无法配对"
        }

        dialog.findViewById<TextView>(R.id.connection_status).text = message

        dialog.findViewById<Button>(R.id.start_listening).isEnabled = false

        if (wasPrivate) {
            dialog.findViewById<Button>(R.id.cancel_listening).text = "关闭"
        }
    }

    /**
     * 保留手机连接并最多自动重建两次音频流。只有 WebSocket 确实不可用时，
     * showPairingFailure 才会继续执行真正的断开流程。
     */
    private fun recoverStreamWithoutDisconnect(message: String): Boolean {
        val server = pairingServer ?: return false
        val dialog = sessionDialog ?: return false
        if (!server.hasConnectedPhone()) return false
        if (listeningState !in setOf(
                ListeningState.PREPARING,
                ListeningState.PREPARED,
                ListeningState.STARTING,
                ListeningState.PAUSED,
                ListeningState.HELD,
                ListeningState.LISTENING,
                ListeningState.NEEDS_ALIGNMENT
            )) return false
        if (listeningState == ListeningState.NEEDS_ALIGNMENT) return true

        // 首次扫码后尚未开始收听时，只保留连接并让用户重试；不能自动替用户开始播放。
        liveAudioRecovery = false
        val mayAutoResume = !userPauseHold && (silentRecovery || listeningState in setOf(
            ListeningState.STARTING,
            ListeningState.PAUSED,
            ListeningState.HELD,
            ListeningState.LISTENING
        ))
        player?.pause()
        pairingHandler.removeCallbacks(syncTick)
        startDeadline?.let { pairingHandler.removeCallbacks(it) }
        scheduleStart?.let { pairingHandler.removeCallbacks(it) }
        startupCheck?.let { pairingHandler.removeCallbacks(it) }
        silentRestart?.let { pairingHandler.removeCallbacks(it) }
        startDeadline = null
        scheduleStart = null
        startupCheck = null
        silentRestart = null
        pendingTvStartUs = 0
        pingT0 = -1
        startArmed = false
        startupFirstPtsUs = null
        activeStartRequestId = null
        tvAudio.cancelStartup()
        tvAudio.stopSending()
        phoneAudioTarget = null
        preparedStreamId = null
        pairingServer?.sendControl(org.json.JSONObject().put("type", "PAUSE"))
        listeningState = ListeningState.PAUSED
        silentRecovery = true
        listeningDialog = dialog

        if (!mayAutoResume) {
            streamRecoveryAttempts = 0
            showRecoverableListeningState(
                ListeningState.NEEDS_ALIGNMENT,
                "$message 手机仍保持连接，可点击“重新准备”继续。",
                "重新准备"
            )
            return true
        }

        if (streamRecoveryAttempts < maxStreamRecoveryAttempts) {
            streamRecoveryAttempts++
            status.apply {
                text = "画面与声音已暂停，正在自动恢复（$streamRecoveryAttempts/$maxStreamRecoveryAttempts）…"
                visibility = View.VISIBLE
            }
            notifySessionPaused("画面与声音已暂停，正在自动恢复（$streamRecoveryAttempts/$maxStreamRecoveryAttempts）…")
            val retry = Runnable {
                silentRestart = null
                if (listeningState == ListeningState.PAUSED && server.hasConnectedPhone()) {
                    beginSilentAudioPreparation()
                }
            }
            silentRestart = retry
            pairingHandler.postDelayed(retry, 500)
        } else {
            showRecoverableListeningState(
                ListeningState.NEEDS_ALIGNMENT,
                "画面与声音已暂停，自动恢复未成功。连接保留，请点击音画对齐重试或退出。原因：$message",
                "重新同步"
            )
        }
        return true
    }

    private fun stopPairing() {
        userPauseHold = false
        liveAudioRecovery = false
        stablePlaybackSinceMs = 0
        recoveryTimes.clear()
        pairingServer?.sendControl(org.json.JSONObject().put("type", "PAUSE"))
        if (privateAudioActive) player?.pause()
        sessionGeneration++
        pairingHandler.removeCallbacks(syncTick)
        startDeadline?.let { pairingHandler.removeCallbacks(it) }
        scheduleStart?.let { pairingHandler.removeCallbacks(it) }
        startDeadline = null
        scheduleStart = null
        silentRestart?.let { pairingHandler.removeCallbacks(it) }
        silentRestart = null
        silentRecovery = false
        streamRecoveryAttempts = 0
        resumeWhenPlayerReady = false
        pendingTvStartUs = 0
        pingT0 = -1
        sessionDialog = null
        // 保留进入自在听前的播放器音量；当前模式下自在听期间电视也正常出声。
        if (privateAudioActive) player?.volume = savedVolume
        privateAudioActive = false
        realignButton.visibility = View.GONE
        startupCheck?.let { pairingHandler.removeCallbacks(it) }
        startupCheck = null
        startupFirstPtsUs = null

        tvAudio.cancelStartup()
        activeStartRequestId = null
        listeningState = ListeningState.IDLE
        tvAudio.stopSending()
        phoneAudioTarget = null
        preparedStreamId = null
        pairingHandler.removeCallbacks(pairingHealthCheck)

        val oldServer = pairingServer
        pairingServer = null
        pairingHost = null

        oldServer?.close()
    }

    private fun loadVideo() {
        val uri = videoUri ?: return
        player?.apply {
            setMediaItem(MediaItem.fromUri(uri))
            seekTo(position)
            prepare()
            playWhenReady = resumePlayback
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("uri", videoUri)
        outState.putLong("position", player?.currentPosition ?: position)
        outState.putBoolean("playing", if (pairingServer != null) false else player?.playWhenReady ?: resumePlayback)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        // 离开电视页面会结束收听，返回时保持暂停，不能悄悄改成扬声器外放。
        val shouldResume = if (pairingServer != null) false else player?.playWhenReady ?: resumePlayback

        player?.let { position = it.currentPosition }

        // 页面退出直接清理资源，不走普通浮窗取消回调。
        listeningDialog?.setOnDismissListener(null)
        listeningDialog?.setOnCancelListener(null)
        listeningDialog?.dismiss()
        listeningDialog = null

        dialogCloseReason = DialogCloseReason.CANCEL
        playbackBeforePairing = null

        stopPairing()

        resumePlayback = shouldResume

        //电视应用退出播放页后，不再保留发送线程和 UDP socket
        tvAudio.stopSending()

        playerView.player = null
        player?.release()
        player = null

        //页面退出后不保留上一段视频的音频数据
        tvAudio.clear()

        super.onStop()
    }
}


