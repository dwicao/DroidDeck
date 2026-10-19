package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThrottleWatchTest {
    @Test fun millidegreesAreDegrees() {
        assertEquals(48.5, thermalCelsius(48_500)!!, 0.001)
        assertEquals(50.9, thermalCelsius(50_900)!!, 0.001)
    }

    @Test fun otherScalesAreReadByMagnitude() {
        assertEquals(50.0, thermalCelsius(5_000)!!, 0.001)
        assertEquals(50.0, thermalCelsius(500)!!, 0.001)
        assertEquals(42.0, thermalCelsius(42)!!, 0.001)
    }

    @Test fun nonsenseIsDropped() {
        assertNull(thermalCelsius(null))
        assertNull(thermalCelsius(0))
        assertNull(thermalCelsius(-273_000))
        assertNull(thermalCelsius(9_000_000))
    }
}
