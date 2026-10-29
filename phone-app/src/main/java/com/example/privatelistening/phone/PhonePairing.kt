package com.example.privatelistening.phone

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.privatelistening.common.DemoConfig
import com.example.privatelistening.common.PairingInfo
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.net.InetAddress
import java.util.UUID

/**
 * 手机端配对逻辑集中在本文件。
 * 扫码文字
 * → PairingInfo 校验
 * → WebSocket 连接
 * → 发送 JOIN
 * → 收到 JOINED 后报告配对成功。
 * connect / disconnect 由页面主线程调用；
 * 网络回调也切回主线程再处理状态。
 */
class PhonePairing(
    private val listener: Listener
) {

    enum class State {
        IDLE,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    interface Listener {
        fun onPrepareAudio(
            tvAddress: InetAddress,
            streamId: UUID,
            sampleRate: Int,
            channels: Int
        )
        fun onStateChanged(
            state: State,
            message: String
        )
        fun onControl(message: JSONObject)
        fun onArmAudio()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var client: WebSocketClient? = null
    private var audioStreamId: UUID? = null
    // 区分本次启动请求，避免旧确认影响新的启动。
    private var outputRequestId: UUID? = null
    private var outputReadySent = false

    var state: State = State.IDLE
        private set

    val hasSession: Boolean
        get() = client != null

    fun connect(qrText: String) {
        // 新的一次扫码连接不复用旧客户端。
        disconnect()

        val info = try {
            PairingInfo.fromQrText(qrText)
        } catch (error: IllegalArgumentException) {
            update(State.ERROR, error.message ?: "二维码无效")
            return
        }

        val uri = URI("ws://${info.host}:${info.port}/listen")

        val newClient = object : WebSocketClient(
            uri,
            Draft_6455(),
            null,
            8_000
        ) {

            override fun onOpen(
                handshakedata: ServerHandshake
            ) {
                val source = this

                mainHandler.post {
                    // 忽略已经取消或被替换的旧连接回调。
                    if (client !== source) return@post

                    val join = JSONObject()
                        .put("type", "JOIN")
                        .put(
                            "version",
                            DemoConfig.PROTOCOL_VERSION
                        )
                        .put("sessionId", info.sessionId)
                        .put("token", info.token)
                        .put(
                            "deviceName",
                            "${Build.MANUFACTURER} ${Build.MODEL}"
                                .take(60)
                        )

                    try {
                        source.send(join.toString())

                        update(
                            State.CONNECTING,
                            "已连到电视，正在确认配对…"
                        )
                    } catch (_: Exception) {
                        fail("配对请求发送失败，请重新扫码。")
                    }
                }
            }

            override fun onMessage(message: String) {
                val source = this

                mainHandler.post {
                    if (client !== source) return@post

                    if (message.length > 4096) {
                        fail("电视返回的信息异常。")
                        return@post
                    }

                    val json = try {
                        JSONObject(message)
                    } catch (_: Exception) {
                        fail("电视返回的信息格式无效。")
                        return@post
                    }

                    when (json.optString("type")) {
                        "JOINED" -> {
                            if (state != State.CONNECTING) {
                                fail("收到重复的配对确认，请重新扫码。")
                                return@post
                            }
                            val valid = json.optInt("version", -1) == DemoConfig.PROTOCOL_VERSION && json.optString("sessionId") == info.sessionId

                            if (!valid) {
                                fail("电视返回的会话信息不匹配。")
                                return@post
                            }

                            // 只有收到正确 JOINED，才显示已连接。
                            // 原连接超时任务会自行检查 CONNECTED 状态。
                            update(State.CONNECTED, "已连接：${info.tvName}")
                        }

                        "PREPARE_AUDIO" -> {
                            if (state != State.CONNECTED) {
                                fail("音频准备请求状态不匹配，请重新扫码。")
                                return@post
                            }
                            val streamId = runCatching {
                                UUID.fromString(json.getString("streamId"))
                            }.getOrNull()
                            val sampleRate = json.optInt("sampleRate", -1)
                            val channels = json.optInt("channels", -1)
                            val transport = json.optString("transport")
                            if (streamId == null || sampleRate !in 8_000..192_000 ||
                                channels !in 1..2 || json.optString("encoding") != "PCM16_LE" ||
                                transport != DemoConfig.AUDIO_TRANSPORT.wireName) {
                                fail("电视提供的音频参数无效。")
                                return@post
                            }
                            val address = source.remoteSocketAddress?.address
                            if (address == null) {
                                fail("无法取得电视连接地址。")
                                return@post
                            }
                            audioStreamId = streamId
                            outputRequestId = null
                            outputReadySent = false
                            listener.onPrepareAudio(address, streamId, sampleRate, channels)
                        }

                        "ARM_AUDIO" -> {
                            val streamId = audioStreamId

                            if (state != State.CONNECTED || streamId == null || json.optString("streamId") != streamId.toString()
                            ) {
                                fail("输出准备请求与当前会话不匹配。")
                                return@post
                            }

                            val requestId = runCatching {
                                UUID.fromString(json.getString("requestId"))
                            }.getOrNull()

                            if (requestId == null) {
                                fail("输出准备请求标识无效。")
                                return@post
                            }

                            // 同一请求重复到达不重复检查；不同请求需重新建立准备流程。
                            if (outputRequestId != null) {
                                if (outputRequestId != requestId) {
                                    fail("启动请求已变化，请重新扫码。")
                                }
                                return@post
                            }
                            outputRequestId = requestId
                            listener.onArmAudio()
                        }

                        "TIME_PING" -> {
                            if (matchesControl(json)) {
                                val received = android.os.SystemClock.elapsedRealtimeNanos() / 1000
                                sendControl(JSONObject().put("type", "TIME_PONG")
                                    .put("t0", json.optLong("t0", -1)).put("t1", received)
                                    .put("t2", android.os.SystemClock.elapsedRealtimeNanos() / 1000))
                            }
                        }
                        "SESSION_PAUSED", "PARTICIPANTS", "DIAGNOSTICS" -> {
                            // Authenticated WebSocket session state survives invalidation of the audio stream.
                            if (state == State.CONNECTED) listener.onControl(json)
                        }
                        "START_AT", "COMMIT_START", "SYNC", "PAUSE", "HOLD_PAUSE", "RESUME_AT" -> {
                            if (matchesControl(json)) listener.onControl(json)
                        }
                        "ERROR" -> {
                            fail("电视未能处理配对请求，请重新扫码。")
                        }
                    }
                }
            }

            override fun onClose(
                code: Int,
                reason: String,
                remote: Boolean
            ) {
                Log.w(
                    "PrivateListenDiag",
                    "WEBSOCKET_CLOSED code=$code remote=$remote reason=${reason.ifBlank { "<empty>" }} state=$state"
                )
                val source = this

                mainHandler.post {
                    if (client !== source) return@post
                    val wasConnected = state == State.CONNECTED

                    client = null
                    audioStreamId = null
                    outputRequestId = null
                    outputReadySent = false
                    mainHandler.removeCallbacksAndMessages(null)

                    val message = when (reason) {
                        "Session full", "Session full: maximum 3 listeners" -> "当前已满3人，请等待一位成员退出后再扫码。"
                        "Invalid session" -> "二维码已失效或配对信息不匹配，请重新扫码。"
                        "Join timeout" -> "配对确认超时，请重新扫码。"

                        else -> {
                            if (wasConnected) {
                                "与电视的连接已断开，请重新扫码。"
                            } else {
                                "连接未完成，请确认电视浮窗仍然打开。"
                            }
                        }
                    }
                    update(if (wasConnected) State.IDLE else State.ERROR, message)
                }
            }

            override fun onError(ex: Exception) {
                Log.e("PrivateListenDiag", "WEBSOCKET_ERROR ${ex.javaClass.simpleName}: ${ex.message}", ex)
                val source = this

                mainHandler.post {
                    if (client !== source) return@post
                    fail("连接异常，请检查两端局域网、网络权限和电视二维码。")
                }
            }
        }

        client = newClient
        newClient.connectionLostTimeout = 45

        update(
            State.CONNECTING,
            "正在连接：${info.tvName}"
        )

        // 包括网络建立和 JOIN 确认在内的整体超时。
        mainHandler.postDelayed({
            if (client === newClient &&
                state != State.CONNECTED
            ) {
                fail("连接超时，请确认两端在同一局域网，且电视浮窗未关闭。")
            }
        }, 12_000)

        try {
            // connect 是异步连接，不阻塞页面。
            newClient.connect()
        } catch (_: Exception) {
            fail("连接启动失败，请重新扫码。")
        }
    }

    /** 只有手机实际音频输出（耳机或扬声器）检查通过后才能调用。 */
    fun sendOutputReady(): Boolean {
        val connection = client ?: return false
        val streamId = audioStreamId ?: return false
        val requestId = outputRequestId ?: return false

        if (state != State.CONNECTED || !connection.isOpen) {
            return false
        }

        val sent = runCatching {
            connection.send(
                JSONObject()
                    .put("type", "OUTPUT_READY")
                    .put("streamId", streamId.toString())
                    .put("requestId", requestId.toString())
                    .toString()
            )

            true
        }.getOrDefault(false)
        outputReadySent = sent
        return sent
    }

    /** 手机确实收到连续 PCM 后，返回本次起播的起点和实际缓冲时长。 */
    fun sendBufferReady(
        firstPtsUs: Long,
        epoch: Long,
        bufferedDurationUs: Long
    ): Boolean {
        val connection = client ?: return false
        val streamId = audioStreamId ?: return false
        val requestId = outputRequestId ?: return false
        if (state != State.CONNECTED || !connection.isOpen || !outputReadySent) return false
        if (firstPtsUs < 0 || epoch < 0 ||
            !com.example.privatelistening.common.ListeningTiming.acceptsStartupBuffer(bufferedDurationUs)) return false

        return runCatching {
            connection.send(JSONObject()
                .put("type", "BUFFER_READY")
                .put("streamId", streamId.toString())
                .put("requestId", requestId.toString())
                .put("firstPtsUs", firstPtsUs)
                .put("epoch", epoch)
                .put("bufferedDurationUs", bufferedDurationUs)
                .toString())
            true
        }.getOrDefault(false)
    }

    /** 通过已认证控制连接返回系统实际分配的音频接收端口。 */
    fun sendAudioPrepared(port: Int): Boolean {
        val connection = client ?: return false
        val streamId = audioStreamId ?: return false
        if (state != State.CONNECTED || !connection.isOpen || port !in 1..65535) return false
        return runCatching {
            connection.send(JSONObject()
                .put("type", "AUDIO_PREPARED")
                .put("streamId", streamId.toString())
                .put("transport", DemoConfig.AUDIO_TRANSPORT.wireName)
                .put("port", port).toString())
            true
        }.getOrDefault(false)
    }

    private fun matchesControl(json: JSONObject): Boolean =
        state == State.CONNECTED && audioStreamId != null &&
            json.optString("streamId") == audioStreamId.toString() &&
            json.optString("requestId") == outputRequestId?.toString()

    fun sendControl(json: JSONObject): Boolean {
        val connection = client ?: return false
        if (state != State.CONNECTED || !connection.isOpen) return false
        return runCatching {
            json.put("streamId", audioStreamId?.toString()).put("requestId", outputRequestId?.toString())
            connection.send(json.toString())
            true
        }.getOrDefault(false)
    }

    /**
     * 告知电视当前音频暂时不能继续，但不关闭 WebSocket 配对。
     * streamId 用来淘汰旧状态。
     */
    fun sendRecoverableStatus(type: String, message: String): Boolean {
        // 恢复状态可能由音频工作线程异常后切回主线程触发。这里不能用 require
        // 让一个无法发送的状态再次把整个前台服务崩溃；未知类型安全返回 false。
        if (type !in RECOVERABLE_STATUS_TYPES) return false
        val connection = client ?: return false
        val streamId = audioStreamId ?: return false
        if (state != State.CONNECTED || !connection.isOpen) return false
        return runCatching {
            val json = JSONObject()
                .put("type", type)
                .put("streamId", streamId.toString())
                .put("message", message)
            outputRequestId?.let { json.put("requestId", it.toString()) }
            connection.send(json.toString())
            true
        }.getOrDefault(false)
    }

    fun sendUserCommand(type: String): Boolean {
        if (type != "REQUEST_REALIGN" && type != "END_LISTENING") return false
        val connection = client ?: return false
        if (state != State.CONNECTED || !connection.isOpen) return false
        return runCatching {
            connection.send(JSONObject().put("type", type).toString())
            true
        }.getOrDefault(false)
    }

    fun disconnect(message: String? = null) {
        val oldClient = client

        client = null
        audioStreamId = null
        outputRequestId = null
        outputReadySent = false
        state = State.IDLE

        mainHandler.removeCallbacksAndMessages(null)

        if (oldClient != null) {
            runCatching {
                if (oldClient.isOpen) {
                    oldClient.send(
                        JSONObject()
                            .put("type", "LEAVE")
                            .toString()
                    )
                }
            }
            runCatching {
                oldClient.close()
            }
        }
        if (message != null) {
            update(State.IDLE, message)
        }
    }

    private fun fail(message: String) {
        disconnect()
        update(State.ERROR, message)
    }

    private fun update(
        newState: State,
        message: String
    ) {
        state = newState
        listener.onStateChanged(newState, message)
    }

    private companion object {
        val RECOVERABLE_STATUS_TYPES = setOf(
            "HEADPHONES_REQUIRED",
            "REALIGN_REQUIRED",
            "AUTO_REALIGN_REQUIRED",
            "AUDIO_RECOVERY_REQUIRED"
        )
    }
}
