package com.example.privatelistening.tv

import android.os.Looper
import android.os.SystemClock
import com.example.privatelistening.common.AudioStreamFrame
import com.example.privatelistening.common.DemoConfig
import com.example.privatelistening.common.ListeningTiming
import com.example.privatelistening.tv.pairing.PairingServer
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** 使用真实回环WebSocket/TCP，模拟三部手机；不替代三机耳机听感验收。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class MultiListenerTest {
    private fun nowUs() = SystemClock.elapsedRealtimeNanos() / 1000
    private fun await(message: String, condition: () -> Boolean) {
        val end = System.nanoTime() + 12_000_000_000L
        while (!condition() && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5))
            Thread.sleep(5)
        }
        assertTrue(message, condition())
    }

    private inner class Phone(port: Int, val offset: Long = 0) : WebSocketClient(URI("ws://127.0.0.1:$port/listen")) {
        val messages = CopyOnWriteArrayList<JSONObject>()
        @Volatile var stream = ""
        @Volatile var request = ""
        @Volatile var output = true
        @Volatile var closeReason = ""
        override fun onOpen(h: ServerHandshake) {
            send(JSONObject().put("type", "JOIN").put("version", DemoConfig.PROTOCOL_VERSION)
                .put("sessionId", "test-session").put("token", "test-token").put("deviceName", "模拟手机").toString())
        }
        fun emit(type: String, extra: JSONObject = JSONObject()) {
            extra.put("type", type).put("streamId", stream).put("requestId", request)
            send(extra.toString())
        }
        override fun onMessage(text: String) {
            val j = JSONObject(text)
            messages.add(j)
            when (j.optString("type")) {
                "PREPARE_AUDIO" -> {
                    stream = j.getString("streamId")
                    emit("AUDIO_PREPARED", JSONObject().put("port", 45678).put("transport", DemoConfig.AUDIO_TRANSPORT.wireName))
                }
                "ARM_AUDIO" -> {
                    request = j.getString("requestId")
                    if (output) emit("OUTPUT_READY")
                }
                "TIME_PING" -> emit("TIME_PONG", JSONObject().put("t0", j.getLong("t0"))
                    .put("t1", nowUs() + offset).put("t2", nowUs() + offset))
                "START_AT" -> emit("START_ARMED")
            }
        }
        override fun onClose(code: Int, reason: String, remote: Boolean) { closeReason = reason }
        override fun onError(ex: Exception) { closeReason = ex.toString() }
        fun has(type: String) = messages.any { it.optString("type") == type }
    }

    @Test fun threeMembersIndependentClocksCapacityExitAndTimeout() {
        // 模拟已经开机的设备；负时钟偏移也必须仍是非负的开机时间。
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        val events = CopyOnWriteArrayList<String>()
        var port = 0
        var request: UUID? = null
        val server = PairingServer("127.0.0.1", "test-session", "test-token", object : PairingServer.Listener {
            override fun onListening(value: Int) { port = value }
            override fun onControl(message: JSONObject) { events.add(message.getString("type")) }
            override fun onPhoneConnected(name: String) { events.add("joined") }
            override fun onPhoneAudioPrepared(target: InetSocketAddress, streamId: UUID) { events.add("prepared") }
            override fun onPhoneDisconnected() { events.add("disconnected") }
            override fun onFailure(message: String) { events.add("failure:$message") }
            override fun onPhoneOutputReady(requestId: UUID) { request = requestId; events.add("output") }
            override fun onPhoneBufferReady(requestId: UUID, firstPtsUs: Long, bufferedDurationUs: Long) { events.add("buffer") }
            override fun onMembersChanged() {}
            override fun onPeerUnavailable(id: String) { events.add("excluded") }
        })
        val phones = mutableListOf<Phone>()
        try {
            server.start(); await("服务启动") { port > 0 }
            listOf(0L, 5_000_000L, -3_000_000L).forEach { offset ->
                val p = Phone(port, offset); phones.add(p); p.connect()
                await("成员加入") { server.connectedCount == phones.size }
            }
            val fourth = Phone(port); phones.add(fourth); fourth.connect()
            await("拒绝第四人") { fourth.closeReason.contains("Session full") }
            assertEquals(3, server.connectedCount)
            assertTrue(server.requestAudioPreparation(48000, 2))
            await("三人准备") { events.contains("prepared") }
            server.requestOutputPreparation()
            await("三人输出检查") { request != null }
            assertTrue(server.expectPhoneBuffer(request!!, 30_020_000, 2))
            phones.take(3).forEach { it.emit("BUFFER_READY", JSONObject().put("firstPtsUs", 30_020_000)
                .put("epoch", 2).put("bufferedDurationUs", ListeningTiming.STARTUP_READY_US)) }
            await("三人缓冲确认") { events.contains("buffer") }
            assertTrue(server.beginClockSync())
            await("分别校时完成") { events.contains("GROUP_CLOCK_READY") }
            assertEquals("健康成员不能被校时阶段排除", 3, server.audioTargets().size)
            val target = nowUs() + 1_500_000
            assertTrue(server.sendControl(JSONObject().put("type", "START_AT").put("phoneStartUs", target)))
            await("全部约定起播") { events.contains("START_ARMED") }
            phones.take(3).forEach { p ->
                val actual = p.messages.last { it.optString("type") == "START_AT" }.getLong("phoneStartUs")
                assertTrue("时钟偏移不能共用", kotlin.math.abs(actual - p.offset - target) < 50_000)
            }
            assertTrue(server.sendControl(JSONObject().put("type", "COMMIT_START")))
            await("三人提交") { phones.take(3).all { it.has("COMMIT_START") } }
            assertTrue(server.sendControl(JSONObject().put("type", "HOLD_PAUSE")))
            await("普通暂停保留三人会话") { phones.take(3).all { it.has("HOLD_PAUSE") } }
            val resumeAt = nowUs() + 500_000
            assertTrue(server.sendControl(JSONObject().put("type", "RESUME_AT").put("phoneStartUs", resumeAt)))
            await("三人收到快速恢复时刻") { phones.take(3).all { it.has("RESUME_AT") } }
            phones.take(3).forEach { p ->
                val actual = p.messages.last { it.optString("type") == "RESUME_AT" }.getLong("phoneStartUs")
                assertTrue("快速恢复也必须使用各自时钟偏移", kotlin.math.abs(actual - p.offset - resumeAt) < 50_000)
            }
            phones[1].emit("HEADPHONES_REQUIRED")
            await("只排除无耳机成员") { server.audioTargets().size == 2 }
            assertEquals(3, server.connectedCount)
            assertFalse(events.contains("END_LISTENING"))
            // 新一轮中第三人不确认输出，超时后其余两人仍可继续。
            events.clear(); request = null; phones[2].output = false
            assertTrue(server.requestAudioPreparation(48000, 2))
            await("重新准备") { events.contains("prepared") }
            server.requestOutputPreparation()
            await("慢成员超时但健康成员前进") { request != null }
            assertEquals(2, server.audioTargets().size)
            phones[0].emit("END_LISTENING")
            await("退出释放名额") { server.connectedCount == 2 }
            assertFalse(events.contains("END_LISTENING"))
            val replacement = Phone(port); phones.add(replacement); replacement.connect()
            await("名额可复用") { server.connectedCount == 3 }
            listOf(phones[1], phones[2], replacement).forEach { it.emit("END_LISTENING") }
            await("最后一人结束会话") { events.contains("END_LISTENING") }
            assertEquals(0, server.connectedCount)
        } finally { phones.forEach { it.close() }; server.close() }
    }

    @Test fun pcmFanoutIsIdenticalAndOneExitDoesNotStopOthers() {
        val audio = TvAudio()
        val receivers = List(3) { ServerSocket(0) }
        val packets = List(3) { CopyOnWriteArrayList<com.example.privatelistening.common.AudioPacket.Packet>() }
        val errors = CopyOnWriteArrayList<String>()
        val sockets = CopyOnWriteArrayList<java.net.Socket>()
        val byIndex = java.util.concurrent.ConcurrentHashMap<Int, java.net.Socket>()
        val readers = receivers.mapIndexed { index, listener ->
            Thread {
                try {
                    val socket = listener.accept(); sockets.add(socket); byIndex[index] = socket
                    val input = DataInputStream(socket.getInputStream())
                    while (!socket.isClosed) packets[index].add(AudioStreamFrame.read(input))
                } catch (_: Exception) { /* 退出该成员时EOF是预期结果。 */ }
            }.apply { isDaemon = true; start() }
        }
        val offer = TvAudio::class.java.getDeclaredMethod("offer", Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, ByteArray::class.java).apply { isAccessible = true }
        fun feed(i: Int) { offer.invoke(audio, i * 20_000L, 48000, 2, ByteArray(3840) { (i + it).toByte() }) }
        try {
            audio.prepareStartup(0)
            val startupPacketCount = (ListeningTiming.STARTUP_SEND_US / 20_000L).toInt()
            repeat(startupPacketCount, ::feed)
            val stream = UUID.randomUUID()
            audio.startSendingGroup(receivers.mapIndexed { i, s -> "$i" to InetSocketAddress("127.0.0.1", s.localPort) }.toMap(),
                stream, { id, reason -> errors.add("$id:$reason") }, { errors.add(it) })
            await("每人获得完整500ms预填") { packets.all { it.size == startupPacketCount } }
            repeat(startupPacketCount) { i ->
                packets.forEach { assertEquals(stream, it[i].streamId); assertEquals(i.toLong(), it[i].sequence) }
                assertArrayEquals(packets[0][i].pcm, packets[1][i].pcm)
                assertArrayEquals(packets[0][i].pcm, packets[2][i].pcm)
                assertEquals(i * 20_000L, packets[2][i].ptsUs)
            }
            audio.stopPeer("1")
            assertTrue(audio.resumeContinuousSending())
            audio.updatePosition(500_000, true)
            (startupPacketCount until startupPacketCount + 10).forEach(::feed)
            await("一人离开另两路继续") {
                packets[0].size == startupPacketCount + 10 && packets[2].size == startupPacketCount + 10
            }
            assertEquals(startupPacketCount, packets[1].size)
            assertTrue(errors.toString(), errors.isEmpty())
            // 第三路异常断网（RST），第一路仍应继续，不能触发全组错误回调。
            byIndex.getValue(2).apply { setSoLinger(true, 0); close() }
            audio.updatePosition(700_000, true)
            (startupPacketCount + 10 until startupPacketCount + 20).forEach(::feed)
            await("断网只报告对应成员") { errors.any { it.startsWith("2:") } }
            await("故障后健康路继续收到完整音频") { packets[0].size == startupPacketCount + 20 }
            assertTrue(errors.toString(), errors.all { it.startsWith("2:") })
        } finally {
            audio.stopSending(); sockets.forEach { it.close() }; receivers.forEach { it.close() }
            readers.forEach { it.join(1000) }
        }
    }
}
