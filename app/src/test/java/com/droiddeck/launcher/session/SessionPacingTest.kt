package com.droiddeck.launcher.session

import com.droiddeck.launcher.gpu.FrameGen
import org.junit.Assert.assertEquals
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

    @Test fun anImpossiblePanelRateFallsBackToSixty() {
        assertEquals(SessionPacing.Plan(30, 60f), game(0, lsfg2, 0f, armed = true))
        assertEquals(SessionPacing.Plan(30, 60f), game(0, lsfg2, Float.NaN, armed = true))
    }
}
