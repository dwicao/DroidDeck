package com.droiddeck.launcher.ui

import com.droiddeck.launcher.session.SessionDisplay
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.ui.GamepadGeometry.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadGeometryTest {
    private data class Panel(val name: String, val width: Int, val controlsHeight: Int, val density: Float)

    private val screens = listOf(
        Triple("Fold 8 upright", 1848 to 2448, 2.625f),
        Triple("Fold 8 as a tablet", 2448 to 1848, 2.625f),
        Triple("Fold 7 as a tablet", 2184 to 1968, 2.625f),
        Triple("Pixel Fold upright", 1840 to 2208, 2.625f),
        Triple("Pixel Fold as a tablet", 2208 to 1840, 2.625f),
        Triple("OnePlus Open upright", 2268 to 2440, 2.75f),
        Triple("OnePlus Open as a tablet", 2440 to 2268, 2.75f),
        Triple("Fold 8 upright, large display size", 1848 to 2448, 3.5f),
        Triple("Fold 8 as a tablet, small display size", 2448 to 1848, 2.0f),
    )

    private val panels = screens.flatMap { (name, size, density) ->
        val (w, h) = size
        listOf(
            Panel("$name, unfolded", w, h - SessionDisplay.unfoldedGameSize(w, h, SessionDisplay.FOLDED_16X9).second, density),
            Panel("$name, half folded", w, h / 2, density),
        )
    }

    private val portraitPanels = listOf(
        Panel("Thor portrait", 1080, 1920, 369 / 160f),
        Panel("720p phone portrait", 720, 1280, 2f),
        Panel("Phone portrait", 1080, 2400, 2.75f),
        Panel("312dp phone portrait", 720, 1280, 369 / 160f),
    )
    private fun portraitBoxes(panel: Panel) =
        GamepadGeometry.layout(panel.width.toFloat(), panel.controlsHeight.toFloat(), panel.density, portraitDock = true)

    private fun boxes(panel: Panel, scale: Float = 1f) =
        GamepadGeometry.layout(panel.width.toFloat(), panel.controlsHeight.toFloat(), panel.density, scale = scale)

    @Test fun smallerControlsStayOnThePanelWithoutOverlapping() {
        for (panel in panels) for (percent in SessionPrefs.DECK_CONTROLS_SCALES) {
            val placed = boxes(panel, percent / 100f).entries.toList()
            for ((slot, box) in placed) {
                val where = "${panel.name} at $percent %: $slot $box"
                assertTrue(where, box.left >= 0f && box.top >= 0f && box.right <= panel.width && box.bottom <= panel.controlsHeight)
            }
            for (i in placed.indices) for (j in i + 1 until placed.size) {
                assertFalse("${panel.name} at $percent %: ${placed[i].key} overlaps ${placed[j].key}",
                    placed[i].value.overlaps(placed[j].value))
            }
        }
    }

    @Test fun everyControlIsPlacedAndPanelsOnlyWhenTall() {
        for (panel in panels) {
            val tall = GamepadGeometry.tall(panel.width.toFloat(), panel.controlsHeight.toFloat())
            val expected = Slot.values().toSet() - if (tall) emptySet() else setOf(Slot.PANELS)
            assertEquals(panel.name, expected, boxes(panel).keys)
        }
    }

    @Test fun everyControlStaysOnThePanel() {
        for (panel in panels) for ((slot, box) in boxes(panel)) {
            val where = "${panel.name}: $slot $box"
            assertTrue(where, box.left >= 0f && box.top >= 0f)
            assertTrue(where, box.right <= panel.width && box.bottom <= panel.controlsHeight)
        }
    }

    @Test fun noControlsOverlap() {
        for (panel in panels) {
            val placed = boxes(panel).entries.toList()
            for (i in placed.indices) for (j in i + 1 until placed.size) {
                assertFalse("${panel.name}: ${placed[i].key} overlaps ${placed[j].key}",
                    placed[i].value.overlaps(placed[j].value))
            }
        }
    }

    @Test fun everyControlIsBigEnoughForAThumb() {
        for (panel in panels) for ((slot, box) in boxes(panel)) {
            val minPx = 40 * panel.density
            assertTrue("${panel.name}: $slot is ${box.width}x${box.height}, under 40dp",
                box.width >= minPx - 1 && box.height >= minPx - 1)
        }
    }

    @Test fun mainControlsSitUnderTheThumbsAndMirror() {
        for (panel in panels) {
            val b = boxes(panel)
            val left = b.getValue(Slot.LEFT_MAIN)
            val right = b.getValue(Slot.RIGHT_MAIN)
            assertEquals(panel.name, left.cy, right.cy, 0.5f)
            assertEquals(panel.name, left.cx, panel.width - right.cx, 0.5f)
            assertTrue(panel.name, left.cx < b.getValue(Slot.LEFT_SECOND).cx)
            assertTrue(panel.name, left.cy > panel.controlsHeight / 2f)
        }
    }

    @Test fun middleButtonsKeepTheDeckOrder() {
        for (panel in panels) {
            val b = boxes(panel)
            fun before(a: Slot, c: Slot) = b.getValue(a).let { x -> b.getValue(c).let { y ->
                x.cx < y.cx - 0.5f || (kotlin.math.abs(x.cx - y.cx) <= 0.5f && x.cy < y.cy) } }
            assertTrue(panel.name, before(Slot.VIEW, Slot.MENU))
            assertTrue(panel.name, before(Slot.STEAM, Slot.QAM))
        }
    }

    @Test fun portraitDockPlacesAllControlsWithinThePanel() {
        for (panel in portraitPanels) {
            val b = portraitBoxes(panel)
            assertEquals(panel.name, Slot.values().toSet(), b.keys)
            for ((slot, box) in b) {
                val where = "${panel.name}: $slot $box"
                assertTrue(where, box.left >= 0f && box.top >= 0f)
                assertTrue(where, box.right <= panel.width && box.bottom <= panel.controlsHeight)
                assertTrue(where, box.width >= 44 * panel.density - 1 && box.height >= 44 * panel.density - 1)
            }
        }
    }

    @Test fun portraitDockControlsDoNotOverlap() {
        for (panel in portraitPanels) {
            val placed = portraitBoxes(panel).entries.toList()
            for (i in placed.indices) for (j in i + 1 until placed.size) {
                assertFalse("${panel.name}: ${placed[i].key} overlaps ${placed[j].key}",
                    placed[i].value.overlaps(placed[j].value))
            }
        }
    }

    @Test fun portraitDockUsesMoreSpaceForTheThumbSurfaces() {
        for (panel in portraitPanels) {
            val old = boxes(panel)
            val b = portraitBoxes(panel)
            for (slot in listOf(Slot.LEFT_MAIN, Slot.RIGHT_MAIN, Slot.LEFT_SECOND, Slot.RIGHT_SECOND)) {
                assertTrue("${panel.name}: $slot", b.getValue(slot).width >= old.getValue(slot).width * 1.2f)
            }
            assertEquals(panel.name, b.getValue(Slot.LEFT_MAIN).cx, b.getValue(Slot.LEFT_SECOND).cx, 0.5f)
            assertEquals(panel.name, panel.width - b.getValue(Slot.LEFT_MAIN).cx, b.getValue(Slot.RIGHT_MAIN).cx, 0.5f)
        }
    }

    @Test fun portraitDockMovesUtilitiesAndGripsAboveTheGamepad() {
        for (panel in portraitPanels) {
            val b = portraitBoxes(panel)
            assertEquals(panel.name, 8 * panel.density, b.getValue(Slot.VIEW).top, 0.5f)
            assertTrue(panel.name, b.getValue(Slot.MENU).bottom < b.getValue(Slot.L4).top)
            assertTrue(panel.name, b.getValue(Slot.L4).bottom < b.getValue(Slot.LEFT_OUTER_SHOULDER).top)
            assertTrue(panel.name, b.getValue(Slot.LEFT_OUTER_SHOULDER).bottom < b.getValue(Slot.LEFT_SECOND).top)
        }
    }

    @Test fun shortPanelsKeepTheExistingLayoutEvenWhenPortraitDockIsRequested() {
        for ((width, height, density) in listOf(Triple(1920f, 1080f, 2f), Triple(720f, 600f, 2.3f))) {
            assertEquals(GamepadGeometry.layout(width, height, density),
                GamepadGeometry.layout(width, height, density, portraitDock = true))
        }
    }
}
