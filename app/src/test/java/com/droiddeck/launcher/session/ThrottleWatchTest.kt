package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThrottleWatchTest {
    private fun csv(vararg rows: String) =
        "# clusters: c0=0\nt_s,game_fps,thermal,online,headroom,gpu_mhz,gpu_busy_pct,gpu_gov_load,gpu_pin,gpu_guard,gpu_temp_c,gpu_min_mhz,gpu_max_mhz\n" +
            rows.joinToString("\n")

    @Test fun ceilingFallWhilePinnedIsReported() {
        val found = ThrottleWatch.ceilingDrop(csv(
            "2.0,0.0,none,0-7,0.5,719,15,17,1,0,46.1,475,719",
            "96.4,29.1,none,0-7,0.7,615,65,60,1,0,66.5,475,615",
            "137.3,27.0,none,0-7,0.7,348,99,95,1,0,66.9,475,348",
        ))
        assertEquals(719L to 348L, found)
    }

    @Test fun steadyCeilingIsNotReported() {
        assertNull(ThrottleWatch.ceilingDrop(csv(
            "2.0,0.0,none,0-7,0.5,719,15,17,1,0,46.1,475,719",
            "4.0,30.0,none,0-7,0.6,719,50,48,1,0,55.0,475,719",
        )))
    }

    @Test fun unpinnedSessionNeverReports() {
        assertNull(ThrottleWatch.ceilingDrop(csv(
            "2.0,0.0,none,0-7,0.5,719,15,17,0,0,46.1,475,719",
            "4.0,30.0,none,0-7,0.6,348,90,88,0,0,60.0,475,348",
        )))
    }

    @Test fun malformedCsvIsNotReported() {
        assertNull(ThrottleWatch.ceilingDrop(""))
        assertNull(ThrottleWatch.ceilingDrop("t_s,game_fps\n1.0,30.0\n"))
        assertNull(ThrottleWatch.ceilingDrop("t_s,gpu_max_mhz,gpu_pin\n1.0,abc,1\n"))
    }
}
