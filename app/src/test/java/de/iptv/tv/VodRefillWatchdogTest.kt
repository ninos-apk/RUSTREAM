package de.iptv.tv

import org.junit.Assert.*
import org.junit.Test

class VodRefillWatchdogTest {
    private val watchdog = VodRefillWatchdog()
    private fun poll(time: Long, active: Boolean = true, buffered: Long = 30_000,
                     end: Long = 60_000, duration: Long? = 300_000, bytes: Long = 100) =
        watchdog.shouldRetry(time, active, end - buffered, buffered, end, duration, bytes)

    @Test fun stalledDownloadRetriesWhilePlaybackStillConsumesTheReserve() {
        assertFalse(poll(0))
        assertFalse(poll(9_999, buffered = 20_001))
        assertTrue(poll(10_000, buffered = 20_000))
        assertFalse(poll(11_000, buffered = 19_000))
        assertTrue(poll(20_000, buffered = 10_000))
    }

    @Test fun newBytesOrCacheProgressPostponeRetryEvenOnASlowSource() {
        assertFalse(poll(0))
        assertFalse(poll(9_000, bytes = 101))
        assertFalse(poll(18_000, end = 61_000, bytes = 101))
        assertFalse(poll(27_999, end = 61_000, bytes = 101))
        assertTrue(poll(28_000, end = 61_000, bytes = 101))
    }

    @Test fun fullReservePauseLiveStopAndBackoffDoNotRequestReloads() {
        assertFalse(poll(0))
        assertFalse(poll(30_000, active = false))
        assertFalse(poll(31_000))
        assertFalse(poll(40_999))
        assertTrue(poll(41_000))
        assertFalse(poll(100_000, buffered = 60_000))
        assertFalse(poll(200_000, buffered = 120_000))
        assertFalse(poll(300_000, buffered = 59_999))
        assertTrue(poll(310_000, buffered = 59_999))
    }

    @Test fun loadedRemainderDoesNotRetryAndUnknownDurationStillRefills() {
        assertFalse(poll(0, end = 300_000))
        assertFalse(poll(50_000, end = 300_000))
        assertFalse(poll(60_000, end = 299_600))
        watchdog.reset()
        assertFalse(poll(100_000, duration = null))
        assertTrue(poll(110_000, duration = null))
    }

    @Test fun seekingOrReplacingThePlayerStartsAFreshDeadline() {
        assertFalse(poll(0))
        watchdog.reset()
        assertFalse(poll(20_000, end = 200_000, bytes = 1))
        assertFalse(poll(29_999, end = 200_000, bytes = 1))
        assertTrue(poll(30_000, end = 200_000, bytes = 1))
    }
}
