package de.iptv.tv

import org.junit.Assert.*
import org.junit.Test

class SeekHoldTest {
    @Test fun shortPressMovesExactlyThirtySecondsAndRepeatsDoNotAddJumps() {
        val hold=SeekHold()
        assertEquals(150_000L,hold.begin(1,0,120_000,600_000))
        assertNull(hold.begin(1,100,120_000,600_000))
        assertNull(hold.advance(399))
        hold.cancel()
        assertNull(hold.advance(2_000))
        assertEquals(150_000L,hold.targetMs)
    }
    @Test fun holdingAcceleratesAndDependsOnElapsedTimeRatherThanRepeatRate() {
        val dense=SeekHold();val sparse=SeekHold()
        dense.begin(1,0,0,3_600_000);sparse.begin(1,0,0,3_600_000)
        for(t in 500L..4_000L step 100)dense.advance(t)
        sparse.advance(4_000)
        assertEquals(dense.targetMs,sparse.targetMs)
        assertTrue(dense.speed(4_000)>dense.speed(1_000))
        assertEquals(180.0,dense.speed(60_000),0.001)
    }
    @Test fun reverseDirectionResetsAccelerationAndStartsFromCurrentSeekTarget() {
        val hold=SeekHold()
        hold.begin(1,0,120_000,600_000);hold.advance(2_000)
        val target=hold.targetMs
        assertEquals(target-30_000,hold.begin(-1,2_000,120_000,600_000))
        assertNull(hold.advance(2_399))
        assertTrue(hold.speed(2_400)<hold.speed(4_000))
    }
    @Test fun beginningAndEndClampWithoutWrapping() {
        val hold=SeekHold()
        assertEquals(0L,hold.begin(-1,0,10_000,120_000))
        hold.advance(30_000);assertEquals(0L,hold.targetMs)
        hold.cancel()
        assertEquals(120_000L,hold.begin(1,0,110_000,120_000))
        hold.advance(30_000);assertEquals(120_000L,hold.targetMs)
    }
    @Test fun cancelledGestureCannotMoveTheNextEpisode() {
        val hold=SeekHold()
        hold.begin(1,0,60_000,600_000);hold.advance(2_000);hold.cancel()
        assertFalse(hold.active);assertNull(hold.advance(20_000))
        assertEquals(30_000L,hold.begin(1,20_000,0,120_000))
        assertNull(hold.advance(20_399))
    }
}
