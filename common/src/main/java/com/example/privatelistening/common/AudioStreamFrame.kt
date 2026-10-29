package com.example.privatelistening.common

import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * TCP 音频帧格式。TCP 本身没有消息边界，因此每帧必须包含固定包头和 PCM 长度。
 */
object AudioStreamFrame {
    private const val MAGIC = 0x504C5443 // "PLTC"
    private const val VERSION = 2
    private const val RESERVED_FLAGS = 0
    const val HEADER_BYTES = 64
    const val MAX_PCM_BYTES = 64 * 1024

    fun write(output: DataOutputStream, packet: AudioPacket.Packet) {
        validate(packet)
        output.writeInt(MAGIC)
        output.writeByte(VERSION)
        output.writeByte(packet.channels)
        output.writeShort(RESERVED_FLAGS)
        output.writeInt(packet.pcm.size)
        output.writeLong(packet.streamId.mostSignificantBits)
        output.writeLong(packet.streamId.leastSignificantBits)
        output.writeLong(packet.epoch)
        output.writeLong(packet.sequence)
        output.writeLong(packet.ptsUs)
        output.writeLong(packet.sentAtTvUs)
        output.writeInt(packet.sampleRate)
        output.write(packet.pcm)
    }

    /** 无效或被截断的帧抛出异常，由上层保留WebSocket并重建音频流。 */
    fun read(input: DataInputStream): AudioPacket.Packet {
        check(input.readInt() == MAGIC) { "TCP 音频帧标识无效" }
        check(input.readUnsignedByte() == VERSION) { "TCP 音频帧版本不兼容" }
        val channels = input.readUnsignedByte()
        input.readUnsignedShort() // flags，当前保留
        val pcmLength = input.readInt()
        check(channels in 1..2) { "TCP 音频声道无效" }
        check(pcmLength in 1..MAX_PCM_BYTES) { "TCP 音频帧长度无效" }
        check(pcmLength % (channels * 2) == 0) { "TCP PCM 未按采样帧对齐" }

        val packet = AudioPacket.Packet(
            streamId = UUID(input.readLong(), input.readLong()),
            epoch = input.readLong(),
            sequence = input.readLong(),
            ptsUs = input.readLong(),
            sentAtTvUs = input.readLong(),
            sampleRate = input.readInt(),
            channels = channels,
            pcm = ByteArray(pcmLength).also(input::readFully)
        )
        validate(packet)
        return packet
    }

    private fun validate(packet: AudioPacket.Packet) {
        require(packet.channels in 1..2) { "仅支持单声道或双声道 PCM16" }
        require(packet.sampleRate in 8_000..192_000) { "采样率超出支持范围" }
        require(packet.epoch >= 0 && packet.sequence >= 0) { "时间线或序号无效" }
        require(packet.pcm.isNotEmpty() && packet.pcm.size <= MAX_PCM_BYTES) { "PCM 长度无效" }
        require(packet.pcm.size % (packet.channels * 2) == 0) { "PCM 未按采样帧对齐" }
    }
}
