package com.droiddeck.launcher.session

import com.droiddeck.launcher.gpu.FrameGen
import java.util.Locale
import kotlin.math.roundToInt

/**
 * How fast the guest draws, and how often the compositor's layer is sampled.
 *
 * These are not the same number once frame generation is on: the generator interpolates between
 * guest frames, so the guest is asked for the panel rate split by the multiplier while the layer
 * still updates at the panel rate. Voting the guest rate to SurfaceFlinger instead would have the
 * panel sample every other generated frame and the work would be invisible.
 *
 * A limit the user chose is always kept. Without one, a fixed multiplier derives the base rate
 * itself: drawing faster than the divider only makes frames the generator cannot use, which on a
 * phone is heat that comes back as a lower clock. Adaptive targets pick their own base rate, and a
 * desktop session keeps the panel rate — a cursor wants the least delay, not the most frames.
 */
object SessionPacing {
    data class Plan(val guestFps: Int, val outputHz: Float)

    fun plan(choice: Int, mode: FrameGen.Mode, panelHz: Float, armed: Boolean, gameSession: Boolean): Plan {
        val panel = if (panelHz.isFinite() && panelHz > 1f) panelHz else 60f
        if (choice > 0) return Plan(choice, if (armed) panel else choice.toFloat())
        if (!gameSession || !armed || mode.engine == FrameGen.ENGINE_OFF || mode.target > 0 || panel < 20f) {
            return Plan(0, panel)
        }
        val exact = panel / mode.multiplier
        val rounded = exact.roundToInt()
        // A generator that produces more than the panel takes holds frames back, which reads as
        // judder, where one frame short is a repeated frame. So a rounding that lands above the panel
        // steps down, and a fractional panel (59.94) keeps the rate that nearly fills it.
        val base = if (rounded * mode.multiplier > panel + 0.5f) rounded - 1 else rounded
        return Plan(base.coerceAtLeast(1), panel)
    }

    /**
     * What the numbers leave on the table, said once so a report carries it: a guest rate times the
     * multiplier is what the generator produces, and above the panel's rate those frames cannot be
     * shown at all — work that becomes heat. A rate well under the panel's leaves the panel repeating
     * frames, which is worth a line only when it is a visible share of the panel. A limit the user
     * chose is theirs to keep, so this is only ever a log line and a line in the report.
     */
    fun note(plan: Plan, mode: FrameGen.Mode, panelHz: Float): String? {
        if (plan.guestFps <= 0 || mode.engine == FrameGen.ENGINE_OFF || mode.target > 0) return null
        if (mode.multiplier < 2 || !panelHz.isFinite() || panelHz < 20f) return null
        // Only when this plan is the one that feeds the generator at the panel's rate.
        if (kotlin.math.abs(plan.outputHz - panelHz) > 0.5f) return null
        val produced = plan.guestFps * mode.multiplier
        val delta = produced - panelHz
        val panel = String.format(Locale.US, "%.2f", panelHz)
        val side = when {
            delta > 0.5f -> "above the $panel Hz panel, frames are held back"
            delta < -5f -> "below the $panel Hz panel, frames are repeated"
            else -> return null
        }
        return "guest ${plan.guestFps} fps × ${mode.multiplier} = $produced Hz $side"
    }
}
