package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceReportPerfTest {
    @Test fun thermalStatusesHaveTheirPlatformNames() {
        assertEquals("NONE", DeviceReport.thermalStatusName(0))
        assertEquals("MODERATE", DeviceReport.thermalStatusName(2))
        assertEquals("CRITICAL", DeviceReport.thermalStatusName(4))
        assertEquals("SHUTDOWN", DeviceReport.thermalStatusName(6))
        assertEquals("STATUS_9", DeviceReport.thermalStatusName(9))
    }

    @Test fun gameModesHaveTheirPlatformNames() {
        assertEquals("UNSUPPORTED", DeviceReport.gameModeName(0))
        assertEquals("STANDARD", DeviceReport.gameModeName(1))
        assertEquals("PERFORMANCE", DeviceReport.gameModeName(2))
        assertEquals("BATTERY", DeviceReport.gameModeName(3))
        assertEquals("MODE_7", DeviceReport.gameModeName(7))
    }

    @Test fun refreshModesAreRoundedDeduplicatedAndListedFastestFirst() {
        assertEquals("120 Hz, 60 Hz", DeviceReport.refreshModesSummary(listOf(60.0f, 120.00001f, 60.00001f)))
        assertEquals("60 Hz", DeviceReport.refreshModesSummary(listOf(59.94f)))
        assertEquals("", DeviceReport.refreshModesSummary(emptyList()))
    }

    @Test fun aSessionDisplayIsNamedWithItsOwnRates() {
        assertEquals(
            "id 48 · HDMI screen · 75.00 Hz · modes 75 Hz, 60 Hz",
            DeviceReport.sessionDisplayText(48, "HDMI screen", 75f, listOf(60f, 75f)),
        )
    }

    @Test fun aHeldClockIsReadBesideThePlatformsCeiling() {
        assertEquals(
            "475 of 719 MHz (platform level 4 caps it at 475 MHz)",
            DeviceReport.gpuClockText(475, 719, 475, 4),
        )
        assertEquals("719 MHz (top level)", DeviceReport.gpuClockText(719, 719, 719, 0))
        assertEquals(
            "475 of 719 MHz (below the ceiling 719 MHz)",
            DeviceReport.gpuClockText(475, 719, 719, 0),
        )
        assertEquals(
            "295 of 719 MHz (platform level 7 caps it at 295 MHz)",
            DeviceReport.gpuClockText(295, 719, 295, 7),
        )
    }

    @Test fun aClockWithNoCeilingToCompareIsNotReported() {
        assertEquals(null, DeviceReport.gpuClockText(null, 719, 719, 0))
        assertEquals(null, DeviceReport.gpuClockText(475, 719, null, 4))
        assertEquals("475 MHz (below the ceiling 719 MHz)", DeviceReport.gpuClockText(475, null, 719, 0))
    }

    @Test fun aDisplayWithoutANameOrModesStillReads() {
        assertEquals("id 0 · unnamed · 60.00 Hz · modes not reported", DeviceReport.sessionDisplayText(0, null, 60f, emptyList()))
        assertEquals("id 0 · unnamed · 60.00 Hz · modes not reported", DeviceReport.sessionDisplayText(0, "  ", 60f, emptyList()))
    }
}
