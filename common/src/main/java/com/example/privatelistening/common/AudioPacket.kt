package com.example.privatelistening.common

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * 电视与手机共用的 UDP 音频包格式。
 * 包头采用大端序；PCM 内容保持原样，仍然是小端 PCM16。
 * encode / decode 不做音频压缩，也不改变采样率。
 * streamId：每次启动发送时生成，隔离上一次收听的残留数据。
 * epoch：播放器时间线版本，跳转或刷新后变化。
 * sequence：本次发送的递增序号，用于识别丢包和乱序。
 * ptsUs：本包第一个采样帧在视频中的时间，单位微秒。
 */
object AudioPacket {

    private const val MAGIC = 0x504C4155
    private const val VERSION = 2

    // 60 字节包头 + 最多 1140 字节 PCM。
    // 将 UDP 负载控制在 1200 字节以内，降低常见局域网中 IP 分片的概率。
    const val HEADER_BYTES = 60
    const val MAX_DATAGRAM_BYTES = 1200
    const val MAX_PCM_BYTES = MAX_DATAGRAM_BYTES - HEADER_BYTES

    data class Packet(
        val streamId: UUID,
        val epoch: Long,
        val sequence: Long,
        val ptsUs: Long,
        /** 电视写入网络前的单调时钟，用于手机测量同一包的单向传输耗时。 */
        val sentAtTvUs: Long = 0L,
        val sampleRate: Int,
        val channels: Int,
        val pcm: ByteArray
    )

    fun encode(packet: Packet): ByteArray {
        require(packet.channels in 1..2) { "仅支持单声道或双声道 PCM16" }
        require(packet.sampleRate in 8_000..192_000) { "采样率超出当前协议支持范围" }
        require(packet.epoch >= 0 && packet.sequence >= 0) { "时间线版本和序号不能为负数" }

        val bytesPerFrame = packet.channels * 2

        require(packet.pcm.isNotEmpty()) { "PCM 不能为空" }
        require(packet.pcm.size <= MAX_PCM_BYTES) { "PCM 超过单包容量" }
        require(packet.pcm.size % bytesPerFrame == 0) { "PCM 必须按完整采样帧对齐" }

        return ByteBuffer
            .allocate(HEADER_BYTES + packet.pcm.size)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putInt(MAGIC)
                put(VERSION.toByte())
                put(packet.channels.toByte())
                putShort(packet.pcm.size.toShort())

                putLong(packet.streamId.mostSignificantBits)
                putLong(packet.streamId.leastSignificantBits)

                putLong(packet.epoch)
                putLong(packet.sequence)
                putLong(packet.ptsUs)
                putLong(packet.sentAtTvUs)
                putInt(packet.sampleRate)

                // 原样复制，不能把 PCM 按包头字节序重新转换。
                put(packet.pcm)
            }
            .array()
    }

    /**
     * 手机传入 DatagramPacket 的 data / offset / length。
     * 无效数据返回 null，由接收端丢弃，不让网络异常包导致崩溃。
     */
    fun decode(
        data: ByteArray,
        offset: Int,
        length: Int
    ): Packet? {
        if (length !in HEADER_BYTES..MAX_DATAGRAM_BYTES) return null
        if (offset < 0 || offset > data.size) return null
        if (length > data.size - offset) return null

        val buffer = ByteBuffer.wrap(data, offset, length).order(ByteOrder.BIG_ENDIAN)

        if (buffer.int != MAGIC) return null

        val version = buffer.get().toInt() and 0xFF
        val channels = buffer.get().toInt() and 0xFF
        val pcmLength = buffer.short.toInt() and 0xFFFF

        if (version != VERSION) return null
        if (channels !in 1..2) return null
        if (pcmLength !in 1..MAX_PCM_BYTES) return null
        if (length != HEADER_BYTES + pcmLength) return null
        if (pcmLength % (channels * 2) != 0) return null

        val streamId = UUID(buffer.long, buffer.long)
        val epoch = buffer.long
        val sequence = buffer.long
        val ptsUs = buffer.long
        val sentAtTvUs = buffer.long
        val sampleRate = buffer.int

        if (epoch < 0 || sequence < 0) return null
        if (sampleRate !in 8_000..192_000) return null

        val pcm = ByteArray(pcmLength)
        buffer.get(pcm)

        return Packet(
            streamId = streamId,
            epoch = epoch,
            sequence = sequence,
            ptsUs = ptsUs,
            sentAtTvUs = sentAtTvUs,
            sampleRate = sampleRate,
            channels = channels,
            pcm = pcm
        )
    }
}
