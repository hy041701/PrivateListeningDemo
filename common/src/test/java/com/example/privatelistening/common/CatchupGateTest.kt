package com.example.privatelistening.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchupGateTest {
    @Test fun transientLagAndAudioAheadDoNotTriggerSkipping() {
        val gate = ListeningTiming.CatchupGate()
        assertFalse(gate.shouldRecover(600_000, 0))
        assertFalse(gate.shouldRecover(100_000, 500_000))
        assertFalse(gate.shouldRecover(600_000, 1_000_000))
        assertFalse(gate.shouldRecover(-900_000, 2_000_000))
        assertFalse(gate.shouldRecover(-900_000, 4_000_000))
    }
    @Test fun persistentLagTriggersButRecoveryHasCooldown() {
        val gate = ListeningTiming.CatchupGate()
        assertFalse(gate.shouldRecover(600_000, 0))
        assertTrue(gate.shouldRecover(600_000, 1_000_000))
        gate.recovered(1_100_000)
        assertFalse(gate.shouldRecover(600_000, 1_200_000))
        assertFalse(gate.shouldRecover(600_000, 2_200_000))
        assertTrue(gate.shouldRecover(600_000, 4_100_000))
    }
}
