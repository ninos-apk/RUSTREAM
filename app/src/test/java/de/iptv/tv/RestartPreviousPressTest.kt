package de.iptv.tv

import org.junit.Assert.*
import org.junit.Test

class RestartPreviousPressTest {
    @Test fun firstPressRestartsAndSecondWithinTenSecondsGoesBack() {
        val press = RestartPreviousPress()
        assertFalse(press.previous(500, true))
        assertTrue(press.previous(10_500, true))
        assertFalse(press.previous(10_600, true))
    }

    @Test fun expiredWindowStartsAgainAndSelectionResetDisarmsIt() {
        val press = RestartPreviousPress()
        assertFalse(press.previous(0, true))
        assertFalse(press.previous(10_001, true))
        assertTrue(press.previous(10_002, true))
        assertFalse(press.previous(20_000, true))
        press.reset()
        assertFalse(press.previous(20_001, true))
    }

    @Test fun filmsAndFirstEpisodesAlwaysRestart() {
        val press = RestartPreviousPress()
        for (time in listOf(0L, 1L, 500L, 9_999L, 20_000L)) assertFalse(press.previous(time, false))
    }
}
