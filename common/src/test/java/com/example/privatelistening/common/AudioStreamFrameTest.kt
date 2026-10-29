package com.example.privatelistening.common

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

class AudioStreamFrameTest {
    @Test
    fun roundTripPreservesHeaderAndPcm() {
        val packet = AudioPacket.Packet(
            streamId = UUID.randomUUID(),
            epoch = 7,
            sequence = 42,
            ptsUs = 9_876_543,
            sentAtTvUs = 123_456_789,
            sampleRate = 44_100,
            channels = 2,
            pcm = ByteArray(3_528) { index -> (index and 0xFF).toByte() }
        )
        val encoded = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { AudioStreamFrame.write(it, packet) }
        }.toByteArray()

        assertEquals(AudioStreamFrame.HEADER_BYTES + packet.pcm.size, encoded.size)
        val decoded = DataInputStream(ByteArrayInputStream(encoded)).use(AudioStreamFrame::read)
        assertEquals(packet.streamId, decoded.streamId)
        assertEquals(packet.epoch, decoded.epoch)
        assertEquals(packet.sequence, decoded.sequence)
        assertEquals(packet.ptsUs, decoded.ptsUs)
        assertEquals(packet.sentAtTvUs, decoded.sentAtTvUs)
        assertEquals(packet.sampleRate, decoded.sampleRate)
        assertEquals(packet.channels, decoded.channels)
        assertArrayEquals(packet.pcm, decoded.pcm)
    }
}
