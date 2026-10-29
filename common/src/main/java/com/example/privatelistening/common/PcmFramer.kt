package com.example.privatelistening.common

/** 跨解码回调拼接 PCM；时间戳只由锚点与累计采样数计算。 */
class PcmFramer(private val rate: Int, private val channels: Int) {
    private val frameBytes = channels * 2
    private val block = ByteArray((rate / 50).coerceAtLeast(1) * frameBytes)
    private var size = 0
    private var anchor: Long? = null
    private var frames = 0L

    fun append(ptsUs: Long, data: ByteArray, emit: (Long, ByteArray) -> Unit) {
        require(data.size % frameBytes == 0)
        val expected = anchor?.let { it + frames * 1_000_000L / rate }
        // 仅容忍两个采样帧的边界舍入；真实跳变必须保留，不能伪造连续时间线。
        if (expected == null || kotlin.math.abs(ptsUs - expected) > (2_000_000L + rate - 1) / rate) {
            drain(emit)
            anchor = ptsUs
            frames = 0
        }
        var offset = 0
        while (offset < data.size) {
            val count = minOf(block.size - size, data.size - offset)
            data.copyInto(block, size, offset, offset + count)
            size += count
            offset += count
            frames += count / frameBytes
            if (size == block.size) drain(emit)
        }
    }

    /** 结束流时保留不足20ms的尾部。 */
    fun drain(emit: (Long, ByteArray) -> Unit) {
        if (size == 0) return
        val pts = checkNotNull(anchor) + (frames - size / frameBytes) * 1_000_000L / rate
        emit(pts, block.copyOf(size))
        size = 0
    }

    companion object {
        fun gapFrames(gapUs: Long, rate: Int): Int =
            if (kotlin.math.abs(gapUs) <= (2_000_000L + rate - 1) / rate) 0
            else (gapUs * rate / 1_000_000L).toInt()
    }
}
