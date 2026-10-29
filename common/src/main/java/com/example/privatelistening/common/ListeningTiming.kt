package com.example.privatelistening.common

/** 只用单调时钟，系统日期调整不会改变会话的时间轴。单位均为微秒。 */
object ListeningTiming {
    // 起播只要求约500ms连续PCM：其中约240ms预填到AudioTrack，其余留在应用队列。
    // 正式播放后再逐步把总储备增长到约2秒，缩短首次起播、跳转和换片等待。
    const val STARTUP_PREFILL_MS = 500
    const val STARTUP_READY_US = STARTUP_PREFILL_MS * 1000L
    const val STARTUP_SEND_US = 500_000L
    // 允许最后一个约20ms块及不同采样率的边界舍入，但拒绝把稳态缓存误报成起播缓存。
    const val STARTUP_MAX_US = 580_000L

    // 应用队列目标约2.5秒；电视最多提前发送约2.8秒，
    // AudioTrack中已经写入但尚未出声的约240~280ms。
    const val STEADY_APPLICATION_TARGET_US = 2_500_000L
    const val STEADY_SEND_AHEAD_US = 2_800_000L
    // 起播后每播放1秒，把允许的提前发送范围增加约500ms：
    const val SEND_AHEAD_GROWTH_US_PER_SECOND = 500_000L

    fun acceptsStartupBuffer(durationUs: Long): Boolean =
        durationUs in STARTUP_READY_US..STARTUP_MAX_US

    /** 正式播放后的渐进发送窗口； */
    fun progressiveSendAheadUs(elapsedSinceStartUs: Long): Long {
        val elapsed = elapsedSinceStartUs.coerceAtLeast(0L)
        val growth = elapsed * SEND_AHEAD_GROWTH_US_PER_SECOND / 1_000_000L
        return (STARTUP_SEND_US + growth).coerceAtMost(STEADY_SEND_AHEAD_US)
    }

    /** 手机局部追帧门限：滤除瞬时抖动，落后才追帧，恢复后留出稳定时间。 */
    class CatchupGate {
        private var lateSinceUs: Long? = null
        private var recoveredAtUs: Long? = null
        fun shouldRecover(errorUs: Long, nowUs: Long): Boolean {
            if (errorUs <= 250_000) { lateSinceUs = null; return false }
            val since = lateSinceUs ?: nowUs.also { lateSinceUs = it }
            return nowUs - since >= 1_000_000 &&
                (recoveredAtUs == null || nowUs - recoveredAtUs!! >= 3_000_000)
        }
        fun recovered(nowUs: Long) { recoveredAtUs = nowUs; lateSinceUs = null }
    }

    /**
     * 有界乱序窗口。起播不能掩盖缺包；持续播放时最多等待 60ms。
     */
    class PacketWindow {
        private val pending = java.util.TreeMap<Long, AudioPacket.Packet>()
        private var nextSequence = 0L
        private var gapSinceMs: Long? = null
        fun offer(packet: AudioPacket.Packet): Boolean {
            if (packet.sequence < nextSequence || pending.containsKey(packet.sequence)) return false
            check(packet.sequence - nextSequence <= 256) { "音频序列跳跃过大，请重新开始" }
            check(pending.size < 256) { "乱序缓冲已满" }
            pending[packet.sequence] = packet
            return true
        }
        fun poll(nowMs: Long, allowGaps: Boolean): AudioPacket.Packet? {
            pending.remove(nextSequence)?.let { nextSequence++; gapSinceMs = null; return it }
            if (pending.isEmpty()) return null
            val since = gapSinceMs ?: nowMs.also { gapSinceMs = it }
            if (nowMs - since < REORDER_WAIT_MS) return null
            check(allowGaps) { "起播音频缺包，请重新开始。" }
            nextSequence = pending.firstKey()
            return poll(nowMs, allowGaps)
        }
    }

    private const val REORDER_WAIT_MS = 60L
    data class Sample(val offsetUs: Long, val roundTripUs: Long)
    fun sample(t0: Long, t1: Long, t2: Long, t3: Long): Sample? {
        if (t0 < 0 || t1 < 0 || t2 < t1 || t3 < t0) return null
        val rtt = (t3 - t0) - (t2 - t1)
        if (rtt !in 0..500_000) return null
        return Sample(((t1 - t0) + (t2 - t3)) / 2, rtt)
    }

    // 声音落后时略微加速，领先时略微减速；保持音调。
    fun speed(errorUs: Long): Float = when {
        kotlin.math.abs(errorUs) < 12_000 -> 1f
        else -> (1.0 + errorUs / 2_000_000.0).coerceIn(0.98, 1.02).toFloat()
    }
}
