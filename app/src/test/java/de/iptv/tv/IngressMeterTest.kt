package de.iptv.tv

import org.junit.Assert.*
import org.junit.Test

class IngressMeterTest {
    @Test fun transferRateUsesActualBytesAndElapsedTimeIncludingIdlePeriods() {
        val meter=IngressMeter()
        assertNull(meter.sample(1000))
        assertFalse(meter.hasData)
        meter.add(500_000)
        assertEquals(250_000L,meter.sample(3000))
        assertTrue(meter.hasData)
        assertEquals(0L,meter.sample(4000))
        meter.add(125_000)
        assertNull(meter.sample(4000))
        assertEquals(125_000L,meter.sample(5000))
    }
    @Test fun restartedStreamHasAnIndependentMeter() {
        val previous=IngressMeter().apply { sample(0); add(400) }
        assertEquals(400L,previous.sample(1000))
        val next=IngressMeter()
        assertFalse(next.hasData)
        assertNull(next.sample(2000))
        assertEquals(0L,next.sample(3000))
    }
}
