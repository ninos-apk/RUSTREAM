package de.iptv.tv

import org.junit.Assert.*
import org.junit.Test

class LiveVideoWatchdogTest {
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
