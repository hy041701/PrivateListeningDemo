package com.example.privatelistening.common

import org.junit.Assert.*
import org.junit.Test

class PcmFramerTest {
    @Test fun irregularDecoderChunksPreserveEveryByteAndSampleTime() {
        val rate = 44100
        val input = ByteArray(44100 * 4) { (it % 251).toByte() }
        val framer = PcmFramer(rate, 2)
        val chunks = mutableListOf<Pair<Long, ByteArray>>()
        var offset = 0
        val sizes = intArrayOf(1024, 3, 431, 29, 890)
        var n = 0
        while (offset < input.size) {
            val count = minOf(sizes[n++ % sizes.size] * 4, input.size - offset)
            framer.append(1_000_000L + offset / 4 * 1_000_000L / rate,
                input.copyOfRange(offset, offset + count)) { pts, pcm -> chunks.add(pts to pcm) }
            offset += count
        }
        framer.drain { pts, pcm -> chunks.add(pts to pcm) }
        assertEquals(50, chunks.size)
        assertArrayEquals(input, chunks.flatMap { it.second.asIterable() }.toByteArray())
        chunks.forEachIndexed { i, block -> assertEquals(1_000_000L + i * 20_000L, block.first) }
    }

    @Test fun roundingDoesNotInsertSilenceButRealGapsRemainVisible() {
        assertEquals(0, PcmFramer.gapFrames(24, 44100))
        assertEquals(0, PcmFramer.gapFrames(-24, 44100))
        assertEquals(4410, PcmFramer.gapFrames(100_000, 44100))
        val framer = PcmFramer(44100, 1)
        val pts = mutableListOf<Long>()
        framer.append(0, ByteArray(882)) { t, _ -> pts.add(t) }
        framer.append(100_000, ByteArray(1764)) { t, _ -> pts.add(t) }
        assertEquals(listOf(0L, 100_000L), pts)
    }

    @Test fun repeatedOneSampleTimestampErrorsDoNotChangeWaveform() {
        val framer = PcmFramer(44100, 2)
        val chunks = mutableListOf<Pair<Long, ByteArray>>()
        val input = ByteArray(1764 * 4) { (it % 127).toByte() }
        for (i in 0..3) {
            framer.append(i * 10_000L + if (i == 0) 0 else 24,
                input.copyOfRange(i * 1764, (i + 1) * 1764)) { t, b -> chunks.add(t to b) }
        }
        framer.drain { t, b -> chunks.add(t to b) }
        assertEquals(listOf(0L, 20_000L), chunks.map { it.first })
        assertArrayEquals(input, chunks.flatMap { it.second.asIterable() }.toByteArray())
    }
}
