package com.example.privatelistening.tv.pairing
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.example.privatelistening.common.DemoConfig
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.java_websocket.WebSocket
import org.json.JSONObject
import java.util.UUID
/*
 * 电视端配对功能集中在本文件：
 * 1. LocalNetworkAddress：取得电视当前局域网 IPv4 地址。
 * 2. PairingServer：绑定该地址并启动服务，由系统分配可用端口。
 * 3. MainActivity 收到 onListening 后，使用 PairingInfo 组织连接信息，再调用 PairingQrCode 生成浮窗中的二维码。
 * 4. 手机扫码后连接 /listen，发送 JOIN；会话凭据通过后返回 JOINED。
 * 5. 取消浮窗或退出页面时，MainActivity 调用 close 清理连接。
 */

/**
 * 查询地址与检查地址是否仍然存在。
 * find 只选择 Wi-Fi / 以太网的 IPv4；没有可用地址时返回 null。
 * 地址存在并不保证手机一定可达，路由器隔离等问题仍需连接阶段判断。
 */
object LocalNetworkAddress {

    fun find(context: Context): String? {
        val manager =
            context.getSystemService(ConnectivityManager::class.java)

        // 优先检查当前默认网络，再检查其他已连接网络。
        val networks = linkedSetOf<Network>()

        manager.activeNetwork?.let { networks.add(it) }
        networks.addAll(manager.allNetworks.toList())

        for (network in networks) {
            val capabilities = manager.getNetworkCapabilities(network) ?: continue

            val isLocalTransport = capabilities.hasTransport(
                    NetworkCapabilities.TRANSPORT_WIFI
                ) || capabilities.hasTransport(
                            NetworkCapabilities.TRANSPORT_ETHERNET
                        )

            if (!isLocalTransport) continue

            val properties = manager.getLinkProperties(network) ?: continue

            val address = properties.linkAddresses
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull {
                    !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress
                }

            if (address != null) {
                return address.hostAddress
            }
        }

        return null
    }

    fun isStillAvailable(
        context: Context,
        host: String
    ): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)

        return manager.allNetworks.any { network ->
            val properties = manager.getLinkProperties(network)

            properties?.linkAddresses?.any {
                it.address.hostAddress == host
            } == true
        }
    }
}

/**
 * 将连接信息编码为二维码图片。
 * 必须等服务 onListening 返回实际端口后再生成二维码。
 */
object PairingQrCode {

    fun create(
        text: String,
        size: Int = 600
    ): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 2
        )

        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            hints
        )

        val pixels = IntArray(size * size)

        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] =
                    if (matrix[x, y]) {
                        Color.BLACK
                    } else {
                        Color.WHITE
                    }
            }
        }

        return Bitmap.createBitmap(
            pixels,
            size,
            size,
            Bitmap.Config.ARGB_8888
        )
    }
}

/** 最多三人。所有成员/起播状态只在主线程修改，音频队列由TvAudio独立维护。 */
class PairingServer(
    host: String,
    private val sessionId: String,
    private val token: String,
    private val listener: Listener
) {
    interface Listener {
        fun onControl(message: JSONObject)
        fun onListening(port: Int)
        fun onPhoneConnected(name: String)
        fun onPhoneAudioPrepared(target: InetSocketAddress, streamId: UUID)
        fun onPhoneDisconnected()
        fun onFailure(message: String)
        fun onPhoneOutputReady(requestId: UUID)
        fun onPhoneBufferReady(requestId: UUID, firstPtsUs: Long, bufferedDurationUs: Long)
        fun onMembersChanged()
        fun onPeerUnavailable(id: String)
    }
    private class Peer(val socket: WebSocket, val name: String) {
        val id = UUID.randomUUID().toString()
        var participating = false
        var target: InetSocketAddress? = null
        var outputReady = false
        var bufferedUs = 0L
        var armed = false
        var state = "已连接"
        var ping = -1L
        var samples = 0
        var best: com.example.privatelistening.common.ListeningTiming.Sample? = null
    }
    private enum class Phase { IDLE, PREPARE, PREPARED, OUTPUT, OUTPUT_READY, BUFFER, BUFFERED, CLOCK, CLOCK_READY, ARM, ARMED, PLAYING }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val peers = linkedMapOf<WebSocket, Peer>()
    private var closed = false
    private var phase = Phase.IDLE
    private var streamId: UUID? = null
    private var requestId: UUID? = null
    private var firstPtsUs = 0L
    private var epoch = 0L
    private var timeout: Runnable? = null
    private fun nowUs() = android.os.SystemClock.elapsedRealtimeNanos() / 1000
    private fun active() = peers.values.filter { it.participating }
    val connectedCount get() = peers.size
    fun memberSummary(): String = "已连接 ${peers.size}/3 人\n" + peers.values.joinToString("\n") { "${it.name} · ${it.state}" }
    fun audioTargets(): Map<String, InetSocketAddress> = active().mapNotNull { p -> p.target?.let { p.id to it } }.toMap()
    fun hasConnectedPhone() = !closed && peers.isNotEmpty()

    /**
     * 每部手机收到自己的TCP发送统计和自己的时钟换算参数。
     */
    fun sendDiagnostics(build: (peerId: String) -> JSONObject): Boolean {
        if (closed) return false
        var sent = 0
        active().forEach { peer ->
            val json = build(peer.id)
                .put("type", "DIAGNOSTICS")
                .put("clockOffsetUs", peer.best?.offsetUs ?: 0L)
                .put("clockRttUs", peer.best?.roundTripUs ?: -1L)
                .put("streamId", streamId?.toString())
            if (send(peer, json)) sent++
        }
        return sent > 0
    }

    private val server = object : WebSocketServer(InetSocketAddress(host, 0)) {
        override fun onStart() {
            connectionLostTimeout = 45
            mainHandler.post { if (!closed) listener.onListening(port) }
        }
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val path = handshake.resourceDescriptor
            mainHandler.post {
                if (closed || path != "/listen") { conn.close(1008, "Invalid session"); return@post }
                mainHandler.postDelayed({ if (!closed && conn !in peers) conn.close(1008, "Join timeout") }, 5000)
            }
        }
        override fun onMessage(conn: WebSocket, message: String) {
            if (message.length > 4096) { conn.close(1009, "Message too large"); return }
            mainHandler.post {
                if (closed) return@post
                val json = runCatching { JSONObject(message) }.getOrNull()
                if (json == null) conn.close(1008, "Invalid JSON") else handle(conn, json)
            }
        }
        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            mainHandler.post { if (!closed) remove(conn, false) }
        }
        override fun onError(conn: WebSocket?, ex: Exception) {
            mainHandler.post { if (!closed && conn == null) listener.onFailure(ex.message ?: "连接服务异常") }
        }
    }
    fun start() = server.start()
    private fun send(p: Peer, json: JSONObject): Boolean = runCatching {
        if (!p.socket.isOpen) return false
        p.socket.send(json.toString()); true
    }.getOrDefault(false)
    private fun envelope(type: String) = JSONObject().put("type", type)
        .put("streamId", streamId?.toString()).put("requestId", requestId?.toString())
    private fun changed() {
        listener.onMembersChanged()
        peers.values.toList().forEach { send(it, JSONObject().put("type", "PARTICIPANTS").put("count", peers.size).put("maximum", 3)) }
    }
    private fun cancelTimeout() { timeout?.let { mainHandler.removeCallbacks(it) }; timeout = null }
    private fun deadline(ms: Long, missing: (Peer) -> Boolean) {
        cancelTimeout()
        val round = streamId; val stage = phase
        timeout = Runnable {
            if (closed || streamId != round || phase != stage) return@Runnable
            val late = active().filter(missing)
            late.forEach { exclude(it, "准备超时，请点击音画对齐重试", advance = false) }
            progress()
        }.also { mainHandler.postDelayed(it, ms) }
    }
    /** 只停止故障成员，保留配对占位；用户手动对齐/新一轮播放才重新尝试。 */
    private fun exclude(p: Peer, reason: String, advance: Boolean = true) {
        if (!p.participating) return
        p.participating = false; p.state = "暂不可用"
        send(p, JSONObject().put("type", "SESSION_PAUSED")
            .put("message", "本机收听已暂停：$reason。其他手机不受影响，可发起全体音画对齐重试。"))
        listener.onPeerUnavailable(p.id)
        changed()
        if (advance) progress()
    }
    fun failPeer(id: String, reason: String) { peers.values.firstOrNull { it.id == id }?.let { exclude(it, reason) } }
    private fun remove(conn: WebSocket, explicit: Boolean) {
        val p = peers.remove(conn) ?: return
        listener.onPeerUnavailable(p.id)
        changed()
        if (peers.isEmpty()) {
            cancelTimeout(); phase = Phase.IDLE
            if (explicit) listener.onControl(JSONObject().put("type", "END_LISTENING")) else listener.onPhoneDisconnected()
        } else progress()
    }
    private fun join(conn: WebSocket, j: JSONObject) {
        if (j.optInt("version") != DemoConfig.PROTOCOL_VERSION || j.optString("sessionId") != sessionId || j.optString("token") != token) {
            conn.close(1008, "Invalid session"); return
        }
        if (conn in peers) return
        if (peers.size >= 3) { conn.close(1008, "Session full: maximum 3 listeners"); return }
        val p = Peer(conn, j.optString("deviceName", "Android 手机").take(60).ifBlank { "Android 手机" })
        peers[conn] = p
        if (!send(p, JSONObject().put("type", "JOINED").put("version", DemoConfig.PROTOCOL_VERSION).put("sessionId", sessionId))) {
            remove(conn, false); return
        }
        changed()
        listener.onPhoneConnected(p.name)
    }
    fun requestAudioPreparation(sampleRate: Int, channels: Int): Boolean {
        if (!hasConnectedPhone() || sampleRate !in 8000..192000 || channels !in 1..2) return false
        cancelTimeout(); streamId = UUID.randomUUID(); requestId = null; phase = Phase.PREPARE
        peers.values.forEach { p ->
            p.participating = true; p.target = null; p.outputReady = false; p.bufferedUs = 0; p.armed = false
            p.best = null; p.samples = 0; p.ping = -1; p.state = "准备音频"
        }
        deadline(12_000) { it.target == null }
        active().forEach { p ->
            if (!send(p, envelope("PREPARE_AUDIO").put("encoding", "PCM16_LE")
                .put("transport", DemoConfig.AUDIO_TRANSPORT.wireName).put("sampleRate", sampleRate).put("channels", channels))) exclude(p, "控制连接不可用")
        }
        changed(); return true
    }
    fun requestOutputPreparation(): UUID? {
        if (phase != Phase.PREPARED || active().isEmpty()) return null
        requestId = UUID.randomUUID(); phase = Phase.OUTPUT
        deadline(5000) { !it.outputReady }
        active().forEach { p -> if (!send(p, envelope("ARM_AUDIO"))) exclude(p, "输出检查发送失败") }
        return requestId
    }
    fun expectPhoneBuffer(requestId: UUID, firstPtsUs: Long, epoch: Long): Boolean {
        if (this.requestId != requestId || phase != Phase.OUTPUT_READY || active().isEmpty()) return false
        this.firstPtsUs = firstPtsUs; this.epoch = epoch; phase = Phase.BUFFER
        deadline(4000) { it.bufferedUs == 0L }; return true
    }
    fun beginClockSync(): Boolean {
        if (phase != Phase.BUFFERED) return false
        phase = Phase.CLOCK
        deadline(3500) { it.samples < 7 }
        active().forEach { ping(it) }; return true
    }
    private fun ping(p: Peer) {
        if (phase != Phase.CLOCK || !p.participating || p.socket !in peers) return
        p.ping = nowUs()
        if (!send(p, envelope("TIME_PING").put("t0", p.ping))) exclude(p, "校时发送失败")
    }
    /** 每一阶段只有当前参与者全部确认才能前进。 */
    private fun progress() {
        val group = active()
        if (group.isEmpty()) {
            if (phase != Phase.IDLE) {
                phase = Phase.IDLE; cancelTimeout()
                listener.onControl(JSONObject().put("type", "GROUP_UNAVAILABLE"))
            }
            return
        }
        when {
            phase == Phase.PREPARE && group.all { it.target != null } -> {
                phase = Phase.PREPARED; cancelTimeout()
                listener.onPhoneAudioPrepared(group.first().target!!, streamId!!)
            }
            phase == Phase.OUTPUT && group.all { it.outputReady } -> {
                phase = Phase.OUTPUT_READY; cancelTimeout(); listener.onPhoneOutputReady(requestId!!)
            }
            phase == Phase.BUFFER && group.all { it.bufferedUs > 0 } -> {
                phase = Phase.BUFFERED; cancelTimeout()
                listener.onPhoneBufferReady(requestId!!, firstPtsUs, group.minOf { it.bufferedUs })
            }
            phase == Phase.CLOCK && group.all { it.samples >= 7 } -> {
                phase = Phase.CLOCK_READY; cancelTimeout()
                listener.onControl(JSONObject().put("type", "GROUP_CLOCK_READY"))
            }
            phase == Phase.ARM && group.all { it.armed } -> {
                phase = Phase.ARMED; cancelTimeout(); listener.onControl(JSONObject().put("type", "START_ARMED"))
            }
        }
    }
    /** START_AT/SYNC传入电视时钟，逐个换算为手机时钟。 */
    fun sendControl(json: JSONObject): Boolean {
        if (closed) return false
        val type = json.optString("type")
        val everyone = type == "SESSION_PAUSED" || type == "PAUSE"
        val recipients = if (everyone) peers.values.toList() else active()
        if (recipients.isEmpty()) return false
        if (type == "START_AT") {
            if (phase != Phase.CLOCK_READY) return false
            phase = Phase.ARM
            deadline(750) { !it.armed }
        }
        var sent = 0
        recipients.forEach { p ->
            val j = JSONObject(json.toString()).put("streamId", streamId?.toString()).put("requestId", requestId?.toString())
            val key = when (type) {
                "START_AT", "RESUME_AT" -> "phoneStartUs"
                "SYNC" -> "phoneTimeUs"
                else -> null
            }
            if (key != null) j.put(key, j.getLong(key) + (p.best?.offsetUs ?: 0L))
            if (send(p, j)) sent++ else exclude(p, "控制通道发送失败", advance = false)
        }
        if (type == "COMMIT_START") {
            phase = Phase.PLAYING
            active().forEach { it.state = "正在收听" }; changed()
        }
        // Failure callbacks must not reenter the caller while it is committing a start.
        if (!everyone) mainHandler.post { if (!closed) progress() }
        return sent > 0
    }
    private fun handle(conn: WebSocket, j: JSONObject) {
        val type = j.optString("type")
        if (type == "JOIN") { join(conn, j); return }
        val p = peers[conn] ?: return
        if (type == "END_LISTENING" || type == "LEAVE") { remove(conn, true); conn.close(1000, "Left session"); return }
        if (type == "REQUEST_REALIGN") { listener.onControl(j); return }
        if (!p.participating || j.optString("streamId") != streamId?.toString()) return
        if (type in setOf("HEADPHONES_REQUIRED", "REALIGN_REQUIRED", "AUTO_REALIGN_REQUIRED", "AUDIO_RECOVERY_REQUIRED", "PHONE_ERROR")) {
            // 单人保留已验证的限次自动恢复；多人不能因一个故障反复暂停全体。
            if (peers.size == 1) { listener.onControl(j); return }
            exclude(p, j.optString("message", "音频暂不可用")); return
        }
        if (type == "AUDIO_PREPARED" && phase == Phase.PREPARE) {
            val port = j.optInt("port", -1)
            val address = conn.remoteSocketAddress?.address
            if (address == null || port !in 1..65535 || j.optString("transport") != DemoConfig.AUDIO_TRANSPORT.wireName) {
                exclude(p, "接收端信息无效"); return
            }
            p.target = InetSocketAddress(address, port); progress(); return
        }
        if (j.optString("requestId") != requestId?.toString()) return
        when (type) {
            "OUTPUT_READY" -> if (phase == Phase.OUTPUT) { p.outputReady = true; progress() }
            "BUFFER_READY" -> if (phase == Phase.BUFFER) {
                val duration = j.optLong("bufferedDurationUs", -1)
                if (j.optLong("firstPtsUs", -1) != firstPtsUs || j.optLong("epoch", -1) != epoch ||
                    !com.example.privatelistening.common.ListeningTiming.acceptsStartupBuffer(duration)) {
                    exclude(p, "起播时间线不匹配"); return
                }
                p.bufferedUs = duration; progress()
            }
            "TIME_PONG" -> if (phase == Phase.CLOCK && p.ping >= 0 && j.optLong("t0", -2) == p.ping) {
                val sample = com.example.privatelistening.common.ListeningTiming.sample(p.ping, j.optLong("t1", -1), j.optLong("t2", -1), nowUs()) ?: return
                p.ping = -1
                if (p.best == null || sample.roundTripUs < p.best!!.roundTripUs) p.best = sample
                p.samples++
                val round = streamId
                if (p.samples < 7) mainHandler.postDelayed({
                    if (streamId == round) ping(p)
                }, 40) else progress()
            }
            "START_ARMED" -> if (phase == Phase.ARM) { p.armed = true; progress() }
        }
    }
    fun close() {
        if (closed) return
        closed = true; peers.clear(); mainHandler.removeCallbacksAndMessages(null)
        Thread({ runCatching { server.stop(1000) } }, "PairingServerStop").start()
    }
}
