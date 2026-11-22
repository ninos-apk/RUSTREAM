package de.iptv.tv

import org.junit.Assert.*
import org.junit.Test

class VodVideoWatchdogTest {
    private val watchdog = VodVideoWatchdog()
    private fun tick(second: Int, frames: Long = 20, position: Long = second * 1000L, active: Boolean = true) =
        watchdog.shouldReload(second * 1000L, position, frames, active)

    @Test fun advancingClockWithFrozenVideoTriggersAtEightSeconds() {
        for (second in 0..7) assertFalse(tick(second))
        assertTrue(tick(8))
    }

    @Test fun healthyVideoStoppedClockAndInactiveSamplesDoNotTrigger() {
        for (second in 0..20) assertFalse(tick(second, frames = second.toLong()))
        for (second in 21..40) assertFalse(tick(second, position = 20_000))
        for (second in 41..60) assertFalse(tick(second, active = false))
        for (second in 61..68) assertFalse(tick(second))
    }

    @Test fun seekAndLongPollingGapStartNewObservationWindows() {
        for (second in 0..7) assertFalse(tick(second))
        watchdog.interrupt()
        for (second in 8..15) assertFalse(tick(second, position = second * 1000L + 30_000))
        for (second in 25..32) assertFalse(tick(second))
        assertTrue(tick(33))
    }

    @Test fun failedReloadsStayBoundedUntilVideoProgressIsSustained() {
        val retries = (0..50).filter { tick(it, frames = if (it < 11) 20 else 21) }
        assertEquals(listOf(8, 19), retries)
        watchdog.interrupt()
        for (second in 51..58) assertFalse(tick(second, frames = second.toLong()))
        for (second in 59..65) assertFalse(tick(second, frames = 58))
        assertTrue(tick(66, frames = 58))
    }
}
