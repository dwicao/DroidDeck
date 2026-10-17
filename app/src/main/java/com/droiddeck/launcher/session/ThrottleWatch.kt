package com.droiddeck.launcher.session

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File
import java.util.Locale

/**
 * What the hardware is doing while a session runs: the thermal status the platform reports, where
 * each CPU cluster sits against the clock it can reach, the GPU's clock and temperature, and the
 * game's own frame rate.
 *
 * A session that starts fast and settles lower looks the same from the panel whether the guest is
 * being paced too slowly, the clocks are being taken away, or the host's own work is stealing the
 * frame. Sampling the first two every 10 s puts them in the session log beside the compositor's
 * own line, so a report can tell the three apart instead of guessing.
 */
object ThrottleWatch {
    private const val TAG = "ThrottleWatch"
    private const val PERIOD_MS = 10_000L
    private const val CPU_ROOT = "/sys/devices/system/cpu"
    private val CPU_DIR = Regex("cpu\\d+")

    /** For the panel, so a frame rate can be read next to the state of the chips. */
    var summary by mutableStateOf("")
        private set

    private var worker: Thread? = null
    private var lastGameFrames = 0L
    private var lastAt = 0L

    /** A session's start. */
    @Synchronized
    fun start(context: Context): String {
        val app = context.applicationContext
        val clusters = clusters()
        if (clusters.isEmpty()) return "throttle watch: no cpufreq nodes, not watching"
        worker?.interrupt()
        lastGameFrames = 0
        lastAt = 0
        worker = Thread({
            val power = runCatching { app.getSystemService(PowerManager::class.java) }.getOrNull()
            while (!Thread.currentThread().isInterrupted) {
                try { Thread.sleep(PERIOD_MS) } catch (e: InterruptedException) { break }
                if (!Thread.currentThread().isInterrupted) sample(clusters, power)
            }
        }, "throttle-watch").apply { isDaemon = true; start() }
        return "throttle watch: ${clusters.size} CPU cluster(s) every ${PERIOD_MS / 1000} s"
    }

    /** A session's end. */
    @Synchronized
    fun stop() {
        worker?.interrupt()
        worker = null
        summary = ""
    }

    private fun sample(clusters: List<Cluster>, power: PowerManager?) {
        val now = System.currentTimeMillis()
        val seconds = ((now - lastAt) / 1000.0).coerceAtLeast(0.5)
        val frames = runCatching { WaylandCompositor.gameFrameCount() }.getOrDefault(0L)
        val gameFps = (frames - lastGameFrames) / seconds
        lastGameFrames = frames
        lastAt = now

        val status = thermalStatus(power)
        val gpu = GpuStatsComponent.gpuMhz()
        val gpuTemp = GpuStatsComponent.gpuMilliCelsius()
        val cpuTemp = LinuxRuntime.cpuTempSource()?.let { firstNumber(it) }

        Log.i(TAG, buildString {
            append(String.format(Locale.US, "game %.0f fps", gameFps))
            append(" | thermal ").append(thermalName(status))
            append(" | cpu ").append(clusters.joinToString(" ") { it.text() })
            if (gpu != null) append(String.format(Locale.US, " | gpu %d MHz", gpu))
            if (gpuTemp != null && gpuTemp > 0)
                append(String.format(Locale.US, " @ %.1f C", gpuTemp / 100.0))
            if (cpuTemp != null && cpuTemp > 0)
                append(String.format(Locale.US, " | cpu %.1f C", cpuTemp / 100.0))
        })

        summary = buildString {
            append(thermalName(status))
            clusters.firstOrNull()?.let { append("  ").append(it.short()) }
            if (gpu != null) append(String.format(Locale.US, "  gpu %d MHz", gpu))
        }
    }

    /** One clock domain: the cores that can reach the same top frequency. */
    private class Cluster(private val topMhz: Int) {
        private val cores = ArrayList<Int>()

        fun add(core: Int) {
            cores.add(core)
        }

        private fun nowMhz(): Int? {
            val values = cores.mapNotNull { firstNumber("$CPU_ROOT/cpu$it/cpufreq/scaling_cur_freq") }
            return if (values.isEmpty()) null else (values.sum() / values.size).toInt()
        }

        fun text(): String = "${nowMhz() ?: "?"}/$topMhz MHz"

        fun short(): String = "cpu ${nowMhz() ?: "?"}/$topMhz"
    }

    /** The online cores grouped by the clock they can reach, slowest cluster first. */
    private fun clusters(): List<Cluster> {
        val dirs = File(CPU_ROOT).listFiles { file: File -> file.name.matches(CPU_DIR) } ?: return emptyList()
        return dirs.sortedBy { it.name }
            .mapNotNull { dir ->
                val core = dir.name.removePrefix("cpu").toIntOrNull() ?: return@mapNotNull null
                val top = firstNumber("${dir.path}/cpufreq/cpuinfo_max_freq")?.div(1000)?.toInt()
                    ?: return@mapNotNull null
                top to core
            }
            .groupBy({ it.first }, { it.second })
            .entries
            .sortedBy { it.key }
            .map { (top, cores) -> Cluster(top).apply { cores.forEach(::add) } }
    }

    private fun firstNumber(path: String): Long? =
        runCatching { Regex("-?\\d+").find(File(path).readText())?.value?.toLong() }.getOrNull()

    /** PowerManager reports the thermal status from Android 10; -1 where it does not. */
    private fun thermalStatus(power: PowerManager?): Int =
        if (Build.VERSION.SDK_INT < 29 || power == null) -1
        else runCatching { power.currentThermalStatus }.getOrDefault(-1)

    private fun thermalName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "thermal none"
        PowerManager.THERMAL_STATUS_LIGHT -> "thermal light"
        PowerManager.THERMAL_STATUS_MODERATE -> "thermal moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "thermal severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "thermal critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "thermal emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "thermal shutdown"
        else -> "thermal unknown"
    }
}