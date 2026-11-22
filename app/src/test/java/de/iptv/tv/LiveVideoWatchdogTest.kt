package de.iptv.tv

import org.junit.Assert.*
import org.junit.Test

class LiveVideoWatchdogTest {
    @Test fun alternatingPlaybackAndRefillCannotMaskTwoMinutesOfFrozenVideo() {
        val watchdog = LiveVideoWatchdog()
        val retries = (0..120).filter { second ->
            watchdog.shouldRetry(second * 1000L, true, second.coerceAtMost(2) * 25L,
                buffering = second > 2 && second % 10 == 0)
        }
        assertEquals(15, retries.first())
        assertTrue(retries.none { it % 10 == 0 })
    }

    @Test fun longRefillDoesNotConsumePlayingTimeOrStartAnEarlyRetry() {
        val watchdog = LiveVideoWatchdog()
        assertFalse(watchdog.shouldRetry(0, true, 30))
        assertFalse(watchdog.shouldRetry(2_000, true, 30, buffering = true))
        assertFalse(watchdog.shouldRetry(25_000, true, 30, buffering = true))
        assertFalse(watchdog.shouldRetry(27_000, true, 30))
        assertFalse(watchdog.shouldRetry(36_999, true, 30))
        assertTrue(watchdog.shouldRetry(37_000, true, 30))
    }

    @Test fun recoveryNeedsSustainedRenderedFramesOrAnAdvancingRadioClock() {
        val proof = LiveRecoveryConfirmation()
        for (i in 0..10) assertFalse(proof.confirmed(i * 1000L, 1, i * 1000L))
        assertFalse(proof.confirmed(11_000, 2, null))
        assertFalse(proof.confirmed(12_000, 2, null))
        assertFalse(proof.confirmed(13_000, 3, null))
        assertTrue(proof.confirmed(14_000, 4, null))
        assertEquals(13_000L, proof.firstAdvanceMs)
        assertFalse(proof.confirmed(15_000, null, 5_000))
        assertFalse(proof.confirmed(16_000, null, 6_000))
        assertTrue(proof.confirmed(17_000, null, 7_000))
        proof.reset()
        assertFalse(proof.confirmed(18_000, null, 8_000))
        assertFalse(proof.confirmed(19_000, null, null))
        assertFalse(proof.confirmed(20_000, null, 10_000))
    }
    @Test fun frozenVideoRetriesEvenWhenTheAudioClockCouldBeMoving() {
        val watchdog = LiveVideoWatchdog()
        assertFalse(watchdog.shouldRetry(0, true, 50))
        assertFalse(watchdog.shouldRetry(11_999, true, 50))
        assertTrue(watchdog.shouldRetry(12_000, true, 50))
    }
    @Test fun arrivingFramesAndInactivePlaybackResetTheDeadline() {
        val watchdog = LiveVideoWatchdog()
        assertFalse(watchdog.shouldRetry(0, true, 1))
        assertFalse(watchdog.shouldRetry(11_000, true, 2))
        assertFalse(watchdog.shouldRetry(22_999, true, 2))
        assertFalse(watchdog.shouldRetry(30_000, false, 2))
        assertFalse(watchdog.shouldRetry(90_000, true, 2))
        assertFalse(watchdog.shouldRetry(101_999, true, 2))
        assertTrue(watchdog.shouldRetry(102_000, true, 2))
        watchdog.reset()
        assertFalse(watchdog.shouldRetry(200_000, true, 0))
    }
}
