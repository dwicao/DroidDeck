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
import java.io.Writer
import java.util.Locale

/**
 * What the hardware is doing while a session runs: the clock each CPU cluster is actually capped
 * to, the GPU's clock, load and thermal level, the temperatures, the memory headroom, and the
 * game's own frame rate.
 *
 * A session that starts fast and settles lower looks the same from the panel whether the guest is
 * being paced too slowly, the clocks are being taken away, or the host's own work is stealing the
 * frame. The cap a cluster is held to and the clock it could reach are different numbers: only
 * `scaling_max_freq` falling below `cpuinfo_max_freq` means the platform took the clock away.
 * Both are written to `perf.csv` every two seconds beside the game's frame rate, and a line is
 * logged the moment a cap changes, so a report can tell the three apart instead of guessing.
 */
object ThrottleWatch {
    private const val TAG = "ThrottleWatch"
    private const val PERIOD_MS = 2_000L
    private const val LOG_EVERY = 5
    private const val CPU_ROOT = "/sys/devices/system/cpu"
    private const val CPU_ONLINE = "/sys/devices/system/cpu/online"
    private const val KGSL = "/sys/class/kgsl/kgsl-3d0"
    private const val THERMAL_ROOT = "/sys/class/thermal"
    private val CPU_DIR = Regex("cpu\\d+")

    /** For the panel, so a frame rate can be read next to the state of the chips. */
    var summary by mutableStateOf("")
        private set

    private var worker: Thread? = null
    @Volatile private var out: Writer? = null
    private var lastGameFrames = 0L
    private var lastAt = 0L
    private var startedAt = 0L
    private var ticks = 0
    private var lastLimits = emptyList<Int?>()
    private var lastGpuFloor: Long? = null
    private var lastThermal = Int.MIN_VALUE
    private var lastOnline: String? = null
    private var ddrNode: String? = null

    /** A session's start. */
    @Synchronized
    fun start(context: Context): String {
        val app = context.applicationContext
        val clusters = clusters()
        if (clusters.isEmpty()) return "throttle watch: no cpufreq nodes, not watching"
        worker?.interrupt()
        closeCsv()
        val now = System.currentTimeMillis()
        lastGameFrames = runCatching { WaylandCompositor.gameFrameCount() }.getOrDefault(0L)
        lastAt = now
        startedAt = now
        ticks = 0
        lastLimits = emptyList()
        lastGpuFloor = null
        lastThermal = Int.MIN_VALUE
        lastOnline = null
        ddrNode = thermalNode("ddr")
        val file = SessionState.logDirectory?.let { File(it, "perf.csv") }
        worker = Thread({
            val power = runCatching { app.getSystemService(PowerManager::class.java) }.getOrNull()
            val writer = file?.let { f -> runCatching { f.bufferedWriter() }.getOrNull() }
            out = writer
            if (writer != null) runCatching {
                writer.write("# clusters: " + clusters.mapIndexed { i, c -> "c$i=${c.label} (top ${c.topMhz} MHz)" }.joinToString(" "))
                writer.write("\n")
                writer.write(csvHeader(clusters.size))
                writer.write("\n")
                writer.flush()
            }
            while (!Thread.currentThread().isInterrupted) {
                try { Thread.sleep(PERIOD_MS) } catch (e: InterruptedException) { break }
                if (!Thread.currentThread().isInterrupted) sample(clusters, power)
            }
            closeCsv()
        }, "throttle-watch").apply { isDaemon = true; start() }
        return "throttle watch: ${clusters.size} CPU cluster(s) every ${PERIOD_MS / 1000} s"
    }

    /** A session's end. */
    @Synchronized
    fun stop() {
        worker?.interrupt()
        worker = null
        summary = ""
        closeCsv()
    }

    @Synchronized
    private fun closeCsv() {
        val writer = out
        out = null
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
    }

    private fun sample(clusters: List<Cluster>, power: PowerManager?) {
        val now = System.currentTimeMillis()
        val seconds = ((now - lastAt) / 1000.0).coerceAtLeast(0.5)
        val frames = runCatching { WaylandCompositor.gameFrameCount() }.getOrDefault(0L)
        val gameFps = (frames - lastGameFrames) / seconds
        lastGameFrames = frames
        lastAt = now

        val status = thermalStatus(power)
        val onlineNow = online()
        val headroom = if (Build.VERSION.SDK_INT < 30) null
        else runCatching { power?.getThermalHeadroom(10) }.getOrNull()

        val currents = clusters.map { it.current() }
        val nowMhz = currents.map { values -> if (values.isEmpty()) null else (values.sum() / values.size / 1000L).toInt() }
        val peakMhz = currents.map { values -> values.maxOrNull()?.div(1000L)?.toInt() }
        val limits = clusters.map { it.capMhz() }

        val gpuMhz = GpuStatsComponent.gpuMhz()
        val gpuTemp = thermalCelsius(GpuStatsComponent.gpuMilliCelsius())
        val cpuTemp = thermalCelsius(LinuxRuntime.cpuTempSource()?.let { firstNumber(it) })
        val ddrTemp = thermalCelsius(ddrNode?.let { firstNumber(it) })
        val gpuBusy = firstNumber("$KGSL/gpu_busy_percentage")
        val gpuFloor = firstNumber("$KGSL/min_clock_mhz")
        val gpuTop = firstNumber("$KGSL/max_clock_mhz")
        val pwrLevel = firstNumber("$KGSL/thermal_pwrlevel")
        val throttling = firstNumber("$KGSL/throttling")
        val bcl = firstNumber("$KGSL/bcl")
        val lm = firstNumber("$KGSL/lm")
        val memAvailable = memKb("MemAvailable")
        val swapFree = memKb("SwapFree")

        val cells = mutableListOf(
            String.format(Locale.US, "%.1f", (now - startedAt) / 1000.0),
            String.format(Locale.US, "%.1f", gameFps),
            thermalToken(status),
            onlineNow,
            headroom?.let { String.format(Locale.US, "%.2f", it) } ?: "",
            gpuMhz?.toString() ?: "",
            gpuBusy?.toString() ?: "",
            gpuTemp?.let { String.format(Locale.US, "%.1f", it) } ?: "",
            gpuFloor?.toString() ?: "",
            gpuTop?.toString() ?: "",
            pwrLevel?.toString() ?: "",
            throttling?.toString() ?: "",
            bcl?.toString() ?: "",
            lm?.toString() ?: "",
            cpuTemp?.let { String.format(Locale.US, "%.1f", it) } ?: "",
            ddrTemp?.let { String.format(Locale.US, "%.1f", it) } ?: "",
            memAvailable?.toString() ?: "",
            swapFree?.toString() ?: "",
        )
        for (i in clusters.indices) {
            cells += nowMhz[i]?.toString() ?: ""
            cells += peakMhz[i]?.toString() ?: ""
            cells += limits[i]?.toString() ?: ""
        }
        val writer = out
        if (writer != null) runCatching {
            writer.write(cells.joinToString(","))
            writer.write("\n")
            writer.flush()
        }

        logCapChanges(clusters, limits, gpuFloor, status, onlineNow)
        lastLimits = limits
        lastGpuFloor = gpuFloor
        lastThermal = status
        lastOnline = onlineNow

        ticks++
        if (ticks % LOG_EVERY == 0) {
            Log.i(TAG, buildString {
                append(String.format(Locale.US, "game %.0f fps", gameFps))
                append(" | ").append(thermalName(status))
                append(" | cpu ").append(clusters.indices.joinToString(" ") { i ->
                    clusters[i].text(nowMhz[i], limits[i])
                })
                if (gpuMhz != null) append(String.format(Locale.US, " | gpu %d MHz", gpuMhz))
                if (gpuBusy != null) append(String.format(Locale.US, " %d%%", gpuBusy))
                if (gpuTemp != null) append(String.format(Locale.US, " @ %.1f C", gpuTemp))
                if (cpuTemp != null) append(String.format(Locale.US, " | cpu %.1f C", cpuTemp))
                if (headroom != null) append(String.format(Locale.US, " | headroom %.2f", headroom))
                if (memAvailable != null) append(String.format(Locale.US, " | mem %d MB", memAvailable / 1024))
            })
        }

        summary = buildString {
            append(thermalName(status))
            val top = clusters.indices.lastOrNull() ?: return@buildString
            append(String.format(Locale.US, "  cpu %d/%d", nowMhz[top] ?: 0, clusters[top].topMhz))
            val limit = limits.getOrNull(top)
            if (limit != null && limit < clusters[top].topMhz - 1) append("(cap $limit)")
            if (gpuMhz != null) append(String.format(Locale.US, "  gpu %d MHz", gpuMhz))
        }
    }

    /** A line the moment the platform takes a clock away or gives one back, with the reason. */
    private fun logCapChanges(
        clusters: List<Cluster>,
        limits: List<Int?>,
        gpuFloor: Long?,
        status: Int,
        onlineNow: String,
    ) {
        val changed = ArrayList<String>(4)
        if (lastLimits.size == limits.size) {
            clusters.indices.forEach { i ->
                val was = lastLimits[i]
                val now = limits[i]
                if (was != null && now != null && was != now) changed += "c$i $was→$now MHz"
            }
        }
        val wasFloor = lastGpuFloor
        if (wasFloor != null && gpuFloor != null && wasFloor != gpuFloor) changed += "gpu floor $wasFloor→$gpuFloor MHz"
        if (lastThermal != Int.MIN_VALUE && lastThermal != status) changed += thermalName(status)
        val wasOnline = lastOnline
        if (wasOnline != null && onlineNow.isNotEmpty() && wasOnline != onlineNow) {
            changed += "cores online $wasOnline→$onlineNow"
        }
        if (changed.isNotEmpty()) Log.i(TAG, "cap: " + changed.joinToString(", "))
    }

    /** One clock domain: the cores that can reach the same top frequency. */
    private class Cluster(val topMhz: Int) {
        private val cores = ArrayList<Int>()

        val label: String get() = cores.joinToString(",")

        fun add(core: Int) {
            cores.add(core)
        }

        fun current(): List<Long> =
            cores.mapNotNull { firstNumber("$CPU_ROOT/cpu$it/cpufreq/scaling_cur_freq") }

        /** What the platform allows right now, which is below [topMhz] once it is limiting. */
        fun capMhz(): Int? =
            cores.mapNotNull { firstNumber("$CPU_ROOT/cpu$it/cpufreq/scaling_max_freq") }
                .minOrNull()?.div(1000L)?.toInt()

        fun text(nowMhz: Int?, capMhz: Int?): String {
            val base = "${nowMhz ?: "?"}/$topMhz MHz"
            return if (capMhz != null && capMhz < topMhz - 1) "$base (cap $capMhz)" else base
        }
    }

    /** The online cores grouped by the clock they can reach, slowest cluster first. */
    private fun clusters(): List<Cluster> {
        val dirs = File(CPU_ROOT).listFiles { file: File -> file.name.matches(CPU_DIR) } ?: return emptyList()
        return dirs.sortedBy { it.name }
            .mapNotNull { dir ->
                val core = dir.name.removePrefix("cpu").toIntOrNull() ?: return@mapNotNull null
                val top = firstNumber("${dir.path}/cpufreq/cpuinfo_max_freq")?.div(1000L)?.toInt()
                    ?: return@mapNotNull null
                top to core
            }
            .groupBy({ it.first }, { it.second })
            .entries
            .sortedBy { it.key }
            .map { (top, cores) -> Cluster(top).apply { cores.forEach(::add) } }
    }

    private fun csvHeader(count: Int): String {
        val cells = mutableListOf(
            "t_s", "game_fps", "thermal", "online", "headroom",
            "gpu_mhz", "gpu_busy_pct", "gpu_temp_c", "gpu_min_mhz", "gpu_max_mhz",
            "gpu_pwrlevel", "gpu_throttle", "bcl", "lm",
            "cpu_temp_c", "ddr_temp_c", "mem_avail_kb", "swap_free_kb",
        )
        for (i in 0 until count) {
            cells += "c${i}_cur_mhz"
            cells += "c${i}_hottest_mhz"
            cells += "c${i}_limit_mhz"
        }
        return cells.joinToString(",")
    }

    private fun firstNumber(path: String): Long? =
        runCatching { Regex("-?\\d+").find(File(path).readText())?.value?.toLong() }.getOrNull()

    private fun online(): String = runCatching { File(CPU_ONLINE).readText().trim() }.getOrDefault("")

    private fun memKb(key: String): Long? = runCatching {
        File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("$key:") }
                ?.let { Regex("\\d+").find(it.substringAfter(':'))?.value?.toLong() }
        }
    }.getOrNull()

    private fun thermalNode(match: String): String? {
        val dirs = File(THERMAL_ROOT).listFiles { f: File -> f.name.startsWith("thermal_zone") } ?: return null
        var fallback: String? = null
        for (dir in dirs) {
            val type = runCatching { File(dir, "type").readText().trim().lowercase(Locale.US) }.getOrNull() ?: continue
            if (type == match) return File(dir, "temp").path
            if (fallback == null && type.contains(match)) fallback = File(dir, "temp").path
        }
        return fallback
    }

    /** PowerManager reports the thermal status from Android 10; -1 where it does not. */
    private fun thermalStatus(power: PowerManager?): Int =
        if (Build.VERSION.SDK_INT < 29 || power == null) -1
        else runCatching { power.currentThermalStatus }.getOrDefault(-1)

    private fun thermalToken(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown"
    }

    private fun thermalName(status: Int): String = "thermal " + thermalToken(status)
}

/**
 * A thermal node in °C. Android's kernel drivers report millidegrees, but a few report tenths or
 * whole degrees; the magnitude says which, and anything outside a plausible silicon temperature is
 * dropped rather than shown as 512 °C.
 */
internal fun thermalCelsius(raw: Long?): Double? {
    val value = raw ?: return null
    if (value <= 0) return null
    val degrees = when {
        value >= 10_000 -> value / 1000.0
        value >= 1_000 -> value / 100.0
        value >= 100 -> value / 10.0
        else -> value.toDouble()
    }
    return if (degrees in 1.0..150.0) degrees else null
}
