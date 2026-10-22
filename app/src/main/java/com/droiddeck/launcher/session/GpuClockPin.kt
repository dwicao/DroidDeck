package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File

/**
 * The GPU's clock, held at its top level for the length of a session.
 *
 * Two different things take the clock away while a session runs, and they answer to different
 * levers:
 *
 * - The platform's thermal engine writes the GPU's power levels itself. `/sys/class/kgsl/kgsl-3d0`
 *   is root-owned on a stock build, so an app cannot write the level; the write is tried anyway,
 *   because it is the lever that holds the level directly on a device that allows it, and the
 *   refusal is logged instead of swallowed.
 * - KGSL's own governor lowers the level when the load it sees drops. `KGSL_PROP_PWRCTRL=0` (the
 *   adrenotools turbo ioctl) asks the driver to stop managing the level, which is the only switch an
 *   unprivileged app has. It is device-wide and lasts until it is cleared, so it is cleared when the
 *   session ends and on the next app start if a session died with it set.
 *
 * Neither lever raises the ceiling the platform sets. When the thermal engine decides the top level
 * is 295 MHz, "held at the top" means held at 295 MHz, and a held clock that still fell says the
 * platform moved the ceiling rather than that the pin did not take. `perf.csv` writes the pin state
 * next to the clock and the ceiling so a report can tell the two apart.
 *
 * The switch only acts when its value changes. A clamp moves the clock down to the level the
 * platform allows, and the clamp is undone later by raising the ceiling, not by moving the clock:
 * repeating the same hold then does nothing, and the clock stays at the clamped level while the
 * ceiling is back at the top. That is why a hold can be forced ([reassert] with `force`), which
 * cycles the switch so the driver reevaluates the level. This is the one case where a held clock
 * reads below the ceiling: the platform allows more than the clock is at.
 *
 * `min_pwrlevel` and `max_pwrlevel` are left alone: on this driver they are the same level index
 * under different names (0 is the top, 4 is the 475 MHz floor), and the level the thermal engine
 * writes is the one that decides, so writing either would be a claim the pin cannot keep.
 */
object GpuClockPin {
    private const val TAG = "GpuClockPin"
    private const val KGSL_NODE = "/dev/kgsl-3d0"
    private const val PWRLEVEL_NODE = "/sys/class/kgsl/kgsl-3d0/thermal_pwrlevel"

    private const val MARKER = "gpu-clock-pinned"

    @Volatile
    var pinned = false
        private set

    /** Which lever the hold is using, for a report: `level`, `ioctl`, or empty while not held. */
    @Volatile
    var lever = ""
        private set

    /**
     * Whether the thermal guard has released a session's hold so the platform's own governor can
     * settle the clock before the platform clamps it to the floor.
     */
    @Volatile
    var suspended = false
        private set

    /**
     * Whether the last session ended with a hold in place. A report can be written after [stop],
     * and saying "not held" there reads as a pin that failed during the session.
     */
    @Volatile
    var justStopped = false
        private set

    /** Why the level node could not be written, for a report, when the ioctl is the lever in use. */
    @Volatile
    var levelRefused: String? = null
        private set

    private var lastLever = ""

    /** Null until the sysfs node has been tried, then whether it accepts a write on this device. */
    private var levelWritable: Boolean? = null

    /** Whether `KGSL_PROP_PWRCTRL` is currently zeroed, so [reassert] knows a cycle is needed. */
    private var ioctlOn = false

    private val supported: Boolean by lazy { File(KGSL_NODE).exists() }

    @Synchronized
    fun start(context: Context): String {
        suspended = false
        justStopped = false
        lastLever = ""
        if (!SessionPrefs.gpuClockPin(context)) {
            pinned = false
            lever = ""
            return "gpu clock: left to the platform"
        }
        if (!supported) return "gpu clock: no $KGSL_NODE, left to the platform"
        return try {
            File(context.filesDir, MARKER).createNewFile()
            val via = hold()
            pinned = true
            lever = via
            lastLever = via
            "gpu clock: held at its top ($via)"
        } catch (t: Throwable) {
            Log.w(TAG, "pin", t)
            pinned = false
            lever = ""
            "gpu clock: pin failed"
        }
    }

    /**
     * While a session runs: a level written once is not held forever, and the ceiling itself can
     * move. Repeating the hold keeps the clock at whatever the platform currently allows, and the
     * clock next to the ceiling in `perf.csv` shows whether it stayed there.
     *
     * [force] cycles the switch instead of repeating it, for when the clock is below the ceiling:
     * a repeated value is a no-op to the driver, a changed one makes it reevaluate the level.
     */
    @Synchronized
    fun reassert(force: Boolean = false): Boolean {
        if (!pinned || suspended) return false
        return try {
            lever = hold(force)
            lastLever = lever
            true
        } catch (t: Throwable) {
            Log.w(TAG, "re-assert", t)
            false
        }
    }

    /**
     * Lets the platform's governor take the level back while the session keeps its marker. The next
     * [reassert] is ignored until [resumeFromGuard], so a sampler that repeats its hold every few
     * seconds cannot undo the release.
     */
    @Synchronized
    fun suspendForGuard(): Boolean {
        if (!pinned || lever != "ioctl" || suspended) return false
        return try {
            WaylandCompositor.nativeSetGpuTurbo(false)
            ioctlOn = false
            suspended = true
            true
        } catch (t: Throwable) {
            Log.w(TAG, "guard release", t)
            false
        }
    }

    @Synchronized
    fun resumeFromGuard(): Boolean {
        if (!pinned || lever != "ioctl" || !suspended) return false
        return try {
            WaylandCompositor.nativeSetGpuTurbo(true)
            ioctlOn = true
            suspended = false
            true
        } catch (t: Throwable) {
            Log.w(TAG, "guard resume", t)
            false
        }
    }

    @Synchronized
    fun stop(context: Context) {
        if (pinned) clear(context, held = true)
    }

    @Synchronized
    fun clearLeftover(context: Context) {
        if (File(context.filesDir, MARKER).exists()) clear(context, held = false)
    }

    private fun hold(force: Boolean = false): String {
        if (levelWritable != false) {
            try {
                File(PWRLEVEL_NODE).writeText("0")
                if (levelWritable == null) Log.i(TAG, "hold: the power level is writable, held without an ioctl")
                levelWritable = true
                return "level"
            } catch (t: Throwable) {
                if (levelWritable == null) {
                    levelRefused = refusal(t)
                    Log.i(TAG, "hold: $PWRLEVEL_NODE refused (${t.message}), asking the driver instead")
                }
                levelWritable = false
            }
        }
        if (force && ioctlOn) WaylandCompositor.nativeSetGpuTurbo(false)
        WaylandCompositor.nativeSetGpuTurbo(true)
        ioctlOn = true
        return "ioctl"
    }

    /** `Permission denied` out of a Kotlin file error that wraps it in its own path and type. */
    private fun refusal(t: Throwable): String {
        val message = t.message ?: return "refused"
        val open = message.lastIndexOf('(')
        val close = message.lastIndexOf(')')
        return if (open in 0 until close) message.substring(open + 1, close) else message
    }

    private fun clear(context: Context, held: Boolean) {
        if (supported) {
            try {
                WaylandCompositor.nativeSetGpuTurbo(false)
            } catch (t: Throwable) {
                Log.w(TAG, "clear", t)
            }
            ioctlOn = false
        }
        // A written power level is not taken back: that node is the platform's, and its thermal
        // engine owns the value from here on.
        lastLever = if (held) lever else ""
        justStopped = held && lastLever.isNotEmpty()
        pinned = false
        lever = ""
        suspended = false
        File(context.filesDir, MARKER).delete()
    }
}
