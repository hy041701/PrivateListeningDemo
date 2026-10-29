package com.example.privatelistening.common

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class StartupBufferTest {
    @Test fun preparedPhoneBufferIsAcceptedBeforeTvStopsStartupSending() {
        // 模拟不同采样率下每20ms一帧：确认发生在发送额度以内，且不会被双端校验拒绝。
        for (rate in listOf(8000, 44100, 48000, 96000, 192000)) {
            var frames = 0L
            while (frames * 1_000_000 / rate < ListeningTiming.STARTUP_READY_US) {
                frames += rate / 50
            }
            val readyUs = frames * 1_000_000 / rate
            assertTrue(ListeningTiming.acceptsStartupBuffer(readyUs))
            assertTrue(readyUs <= ListeningTiming.STARTUP_SEND_US)
            assertTrue(ListeningTiming.STARTUP_SEND_US <= ListeningTiming.STARTUP_MAX_US)
        }
    }

    @Test fun insufficientBufferAndSteadyStateBufferAreRejectedForStartup() {
        assertFalse(ListeningTiming.acceptsStartupBuffer(-1))
        assertFalse(ListeningTiming.acceptsStartupBuffer(480_000))
        assertTrue(ListeningTiming.acceptsStartupBuffer(500_000))
        assertTrue(ListeningTiming.acceptsStartupBuffer(540_000))
        assertFalse(ListeningTiming.acceptsStartupBuffer(580_001))
        assertFalse(ListeningTiming.acceptsStartupBuffer(ListeningTiming.STEADY_APPLICATION_TARGET_US))
    }

    @Test fun sendAheadWindowGrowsGraduallyAndStopsAtSteadyTarget() {
        assertTrue(ListeningTiming.progressiveSendAheadUs(0) == 500_000L)
        assertTrue(ListeningTiming.progressiveSendAheadUs(1_000_000) == 1_000_000L)
        assertTrue(ListeningTiming.progressiveSendAheadUs(2_000_000) == 1_500_000L)
        assertTrue(ListeningTiming.progressiveSendAheadUs(3_400_000) == 2_200_000L)
        assertTrue(ListeningTiming.progressiveSendAheadUs(4_600_000) == 2_800_000L)
        assertTrue(ListeningTiming.progressiveSendAheadUs(30_000_000) == 2_800_000L)
    }
}
