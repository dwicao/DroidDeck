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

    /** Null until the sysfs node has been tried, then whether it accepts a write on this device. */
    private var levelWritable: Boolean? = null

    private val supported: Boolean by lazy { File(KGSL_NODE).exists() }

    @Synchronized
    fun start(context: Context): String {
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
     */
    @Synchronized
    fun reassert(): Boolean {
        if (!pinned) return false
        return try {
            lever = hold()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "re-assert", t)
            false
        }
    }

    @Synchronized
    fun stop(context: Context) {
        if (pinned) clear(context)
    }

    @Synchronized
    fun clearLeftover(context: Context) {
        if (File(context.filesDir, MARKER).exists()) clear(context)
    }

    private fun hold(): String {
        if (levelWritable != false) {
            try {
                File(PWRLEVEL_NODE).writeText("0")
                if (levelWritable == null) Log.i(TAG, "hold: the power level is writable, held without an ioctl")
                levelWritable = true
                return "level"
            } catch (t: Throwable) {
                if (levelWritable == null) {
                    Log.i(TAG, "hold: $PWRLEVEL_NODE refused (${t.message}), asking the driver instead")
                }
                levelWritable = false
            }
        }
        WaylandCompositor.nativeSetGpuTurbo(true)
        return "ioctl"
    }

    private fun clear(context: Context) {
        if (supported) {
            try {
                WaylandCompositor.nativeSetGpuTurbo(false)
            } catch (t: Throwable) {
                Log.w(TAG, "clear", t)
            }
        }
        // A written power level is not taken back: that node is the platform's, and its thermal
        // engine owns the value from here on.
        pinned = false
        lever = ""
        File(context.filesDir, MARKER).delete()
    }
}
