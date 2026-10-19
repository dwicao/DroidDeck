package com.droiddeck.launcher.session

import com.droiddeck.launcher.gpu.FrameGen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionPacingTest {
    private val lsfg2 = FrameGen.Mode(FrameGen.ENGINE_LSFG, 2)

    private fun game(choice: Int, mode: FrameGen.Mode, panelHz: Float, armed: Boolean) =
        SessionPacing.plan(choice, mode, panelHz, armed, gameSession = true)

    @Test fun aChosenLimitIsKeptAndTheLayerStillRunsAtThePanel() {
        assertEquals(SessionPacing.Plan(30, 60f), game(30, lsfg2, 60f, armed = true))
        assertEquals(SessionPacing.Plan(30, 30f), game(30, lsfg2, 60f, armed = false))
    }

    @Test fun withFrameGenerationTheGuestDrawsAtThePanelSplitByTheMultiplier() {
        assertEquals(SessionPacing.Plan(30, 60f), game(0, lsfg2, 60f, armed = true))
        assertEquals(
            SessionPacing.Plan(30, 120f),
            game(0, FrameGen.Mode(FrameGen.ENGINE_LSFG, 4), 120f, armed = true),
        )
        assertEquals(
            SessionPacing.Plan(40, 120f),
            game(0, FrameGen.Mode(FrameGen.ENGINE_LSFG, 3), 120f, armed = true),
        )
    }

    @Test fun nothingIsCappedWithoutAFixedGenerator() {
        assertEquals(SessionPacing.Plan(0, 60f), game(0, FrameGen.Mode.OFF, 60f, armed = false))
        assertEquals(SessionPacing.Plan(0, 60f), game(0, FrameGen.Mode.OFF, 60f, armed = true))
        assertEquals(SessionPacing.Plan(0, 60f), game(0, lsfg2, 60f, armed = false))
        assertEquals(
            SessionPacing.Plan(0, 120f),
            game(0, FrameGen.Mode(FrameGen.ENGINE_LSFG, target = 90), 120f, armed = true),
        )
    }

    @Test fun aDesktopSessionKeepsThePanelRateEvenWithAGenerator() {
        assertEquals(
            SessionPacing.Plan(0, 60f),
            SessionPacing.plan(0, lsfg2, 60f, armed = true, gameSession = false),
        )
        assertEquals(
            SessionPacing.Plan(30, 60f),
            SessionPacing.plan(30, lsfg2, 60f, armed = true, gameSession = false),
        )
    }

    @Test fun aPanelThatDoesNotDivideKeepsTheRateUnderIt() {
        // 75 / 2 = 37.5: 38 fps would produce 76 Hz, one frame a second more than the panel takes.
        assertEquals(SessionPacing.Plan(37, 75f), game(0, lsfg2, 75f, armed = true))
        // 59.94 / 2 = 29.97: 30 fps produces 60 Hz, which the panel takes.
        assertEquals(SessionPacing.Plan(30, 59.94f), game(0, lsfg2, 59.94f, armed = true))
    }

    @Test fun anImpossiblePanelRateFallsBackToSixty() {
        assertEquals(SessionPacing.Plan(30, 60f), game(0, lsfg2, 0f, armed = true))
        assertEquals(SessionPacing.Plan(30, 60f), game(0, lsfg2, Float.NaN, armed = true))
    }

    private fun note(plan: SessionPacing.Plan, mode: FrameGen.Mode, panelHz: Float) =
        SessionPacing.note(plan, mode, panelHz)

    @Test fun aPlanThatLinesUpWithThePanelSaysNothing() {
        assertNull(note(SessionPacing.Plan(30, 60f), lsfg2, 60f))
        assertNull(note(SessionPacing.Plan(60, 120f), FrameGen.Mode(FrameGen.ENGINE_LSFG, 4), 120f))
        // One frame a second short of the panel is a repeated frame, not a line in the report.
        assertNull(note(SessionPacing.Plan(37, 75f), lsfg2, 75f))
    }

    @Test fun aPlanAboveThePanelSaysFramesAreHeldBack() {
        assertEquals(
            "guest 38 fps × 2 = 76 Hz above the 75.00 Hz panel, frames are held back",
            note(SessionPacing.Plan(38, 75f), lsfg2, 75f),
        )
    }

    @Test fun aPlanWellBelowThePanelSaysFramesAreRepeated() {
        assertEquals(
            "guest 30 fps × 2 = 60 Hz below the 75.00 Hz panel, frames are repeated",
            note(SessionPacing.Plan(30, 75f), lsfg2, 75f),
        )
    }

    @Test fun nothingIsSaidWithoutAGeneratorPacedForThePanel() {
        assertNull(note(SessionPacing.Plan(0, 75f), lsfg2, 75f))
        assertNull(note(SessionPacing.Plan(38, 75f), FrameGen.Mode.OFF, 75f))
        assertNull(note(SessionPacing.Plan(38, 75f), FrameGen.Mode(FrameGen.ENGINE_LSFG, target = 90), 75f))
        // A desktop session is paced at the guest's own rate, not the panel's.
        assertNull(note(SessionPacing.Plan(38, 38f), lsfg2, 75f))
    }
}
