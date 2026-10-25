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
 * `scaling_max_freq` falling below `cpuinfo_max_freq` means the platform took the clock away, and
 * on the GPU side `max_clock_mhz` falling is the same statement about the power level.
 * Both are written to `perf.csv` every two seconds beside the game's frame rate, and a line is
 * logged the moment a cap changes, so a report can tell the three apart instead of guessing.
 *
 * This is also where a held GPU clock is re-asserted: it is the one place that sees both the clock
 * and the ceiling the platform allows, so a pin that stopped taking can be repeated and the result
 * read back from the next row.
 */
object ThrottleWatch {
    private const val TAG = "ThrottleWatch"
    private const val PERIOD_MS = 2_000L
    private const val LOG_EVERY = 5
    private const val CPU_ROOT = "/sys/devices/system/cpu"
    private const val CPU_ONLINE = "/sys/devices/system/cpu/online"
    private const val KGSL = "/sys/class/kgsl/kgsl-3d0"
    private const val THERMAL_ROOT = "/sys/class/thermal"

    /** Samples between GPU-hold re-asserts, so about every [PERIOD_MS] * this. */
    private const val REASSERT_EVERY = 3
    private val CPU_DIR = Regex("cpu\\d+")

    // A held GPU is the first thing a platform thermal engine takes away, and the level it jumps to
    // is the floor. The guard releases the hold before that jump so the governor can settle the
    // clock instead; the hysteresis keeps it from flapping, and the minimum hold keeps a release
    // from being undone by the next sample.
    private const val GUARD_RELEASE = 0.89
    private const val GUARD_RESUME = 0.82
    private const val GUARD_MIN_MS = 8_000L

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
    private var lastGpuTop: Long? = null
    private var lastThermal = Int.MIN_VALUE
    private var lastOnline: String? = null
    private var wasBelowCeiling = false
    private var ddrNode: String? = null
    private var zones = emptyList<ThermalZone>()
    private var guardEnabled = false
    private var guardSince = 0L

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
        lastGpuTop = null
        lastThermal = Int.MIN_VALUE
        lastOnline = null
        ddrNode = thermalNode("ddr")
        zones = thermalZones()
        guardEnabled = SessionPrefs.thermalGuard(app)
        guardSince = 0L
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
        else runCatching { power?.getThermalHeadroom(10)?.toDouble() }.getOrNull()

        val currents = clusters.map { it.current() }
        val nowMhz = currents.map { values -> if (values.isEmpty()) null else (values.sum() / values.size / 1000L).toInt() }
        val peakMhz = currents.map { values -> values.maxOrNull()?.div(1000L)?.toInt() }
        val limits = clusters.map { it.capMhz() }

        val gpuMhz = GpuStatsComponent.gpuMhz()
        val gpuTemp = thermalCelsius(GpuStatsComponent.gpuMilliCelsius())
        val cpuTemp = thermalCelsius(LinuxRuntime.cpuTempSource()?.let { firstNumber(it) })
        val ddrTemp = thermalCelsius(ddrNode?.let { firstNumber(it) })
        val hottest = hottestZone(zones)
        val batMa = BatterySnapshot.currentMa
        val batMw = BatterySnapshot.powerMw
        val batTemp = BatterySnapshot.tempC
        val plugged = BatterySnapshot.plugged
        val gpuBusy = firstNumber("$KGSL/gpu_busy_percentage")
        // The devfreq governor's own load estimate, which is the number its clock decision follows.
        // KGSL's `gpu_busy_percentage` reads a constant on the measured device (13% idle and 13%
        // with a game drawing), so a second, independent estimate is what a report needs.
        val gpuGovLoad = firstNumber("$KGSL/devfreq/gpu_load")
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
            gpuGovLoad?.toString() ?: "",
            if (GpuClockPin.pinned) "1" else "0",
            if (GpuClockPin.suspended) "1" else "0",
            gpuTemp?.let { String.format(Locale.US, "%.1f", it) } ?: "",
            gpuFloor?.toString() ?: "",
            gpuTop?.toString() ?: "",
            pwrLevel?.toString() ?: "",
            throttling?.toString() ?: "",
            bcl?.toString() ?: "",
            lm?.toString() ?: "",
            cpuTemp?.let { String.format(Locale.US, "%.1f", it) } ?: "",
            ddrTemp?.let { String.format(Locale.US, "%.1f", it) } ?: "",
            hottest?.name ?: "",
            hottest?.c?.let { String.format(Locale.US, "%.1f", it) } ?: "",
            batMa?.toString() ?: "",
            batMw?.toString() ?: "",
            batTemp?.let { String.format(Locale.US, "%.1f", it) } ?: "",
            plugged?.let { if (it) "1" else "0" } ?: "",
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

        val held = GpuClockPin.pinned
        val topMoved = logCapChanges(
            clusters, limits, gpuFloor, gpuTop, status, onlineNow, held, pwrLevel, gpuTemp,
            headroom, hottest,
        )
        lastLimits = limits
        lastGpuFloor = gpuFloor
        lastGpuTop = gpuTop
        lastThermal = status
        lastOnline = onlineNow
        maybeGuard(status, headroom, pwrLevel, gpuMhz, gpuTop, gpuTemp)

        // A held clock is repeated when the ceiling moves, and now and then anyway: whether it
        // still takes shows up in the next row's clock against that ceiling.
        //
        // Below the ceiling is the other case: the platform clamped the level, then raised the
        // ceiling again, and a repeated hold no longer changes anything the driver acts on. The
        // switch is cycled instead so the driver takes the top the platform now allows.
        val belowCeiling = gpuMhz != null && gpuTop != null && gpuMhz < gpuTop - 5
        val due = ticks % REASSERT_EVERY == 0
        if (held && (topMoved || due)) {
            // Cycling enables the governor for the width of two ioctls, so a gap that will not close
            // is retried with the re-asserts rather than every sample.
            if (belowCeiling && !wasBelowCeiling) {
                Log.i(TAG, "pin: clock $gpuMhz of $gpuTop MHz, cycling the switch for the top")
            }
            GpuClockPin.reassert(force = belowCeiling)
        }
        wasBelowCeiling = belowCeiling

        ticks++
        if (ticks % LOG_EVERY == 0) {
            Log.i(TAG, buildString {
                append(String.format(Locale.US, "game %.0f fps", gameFps))
                append(" | ").append(thermalName(status))
                append(" | cpu ").append(clusters.indices.joinToString(" ") { i ->
                    clusters[i].text(nowMhz[i], limits[i])
                })
                if (gpuMhz != null) append(String.format(Locale.US, " | gpu %d MHz", gpuMhz))
                val load = gpuGovLoad ?: gpuBusy
                if (load != null) append(String.format(Locale.US, " %d%%", load))
                if (gpuTemp != null) append(String.format(Locale.US, " @ %.1f C", gpuTemp))
                if (GpuClockPin.pinned) append(if (GpuClockPin.suspended) " guard" else " held")
                if (cpuTemp != null) append(String.format(Locale.US, " | cpu %.1f C", cpuTemp))
                if (headroom != null) append(String.format(Locale.US, " | headroom %.2f", headroom))
                hottest?.let { append(String.format(Locale.US, " | hottest %s %.1f C", it.name, it.c)) }
                if (batMa != null) append(String.format(Locale.US, " | bat %+d mA %.1f W", batMa, (batMw ?: 0) / 1000.0))
                if (memAvailable != null) append(String.format(Locale.US, " | mem %d MB", memAvailable / 1024))
            })
        }

        summary = buildString {
            append(thermalName(status))
            val top = clusters.indices.lastOrNull() ?: return@buildString
            append(String.format(Locale.US, "  cpu %d/%d", nowMhz[top] ?: 0, clusters[top].topMhz))
            val limit = limits.getOrNull(top)
            if (limit != null && limit < clusters[top].topMhz - 1) append("(cap $limit)")
            if (gpuMhz != null) {
                append(String.format(Locale.US, "  gpu %d", gpuMhz))
                // The ceiling is the platform's; showing it next to the clock is what keeps "475"
                // from reading as the pin holding the wrong level.
                if (gpuTop != null && gpuTop > gpuMhz + 5) append("/").append(gpuTop)
                append(" MHz")
                if (GpuClockPin.suspended) append(" guard")
            }
            gpuTemp?.let { append(String.format(Locale.US, "  gpu %.0f°C", it)) }
            cpuTemp?.let { append(String.format(Locale.US, "  cpu %.0f°C", it)) }
        }
    }

    /**
     * The platform ceiling at its best and at the end, from a `perf.csv` dump —
     * but only when the GPU pin was held for at least one sample, so an
     * unpinned session never reports a "drop". Returns null when the file has
     * no ceiling history or the ceiling never fell. Pure for tests.
     */
    internal fun ceilingDrop(csv: String): Pair<Long, Long>? {
        val lines = csv.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.toList()
        if (lines.size < 2) return null
        val cols = lines[0].split(",")
        val top = cols.indexOf("gpu_max_mhz")
        val pin = cols.indexOf("gpu_pin")
        if (top < 0 || pin < 0) return null
        var best = 0L
        var last = 0L
        var held = false
        for (row in lines.subList(1, lines.size)) {
            val cells = row.split(",")
            if (cells.size <= maxOf(top, pin)) continue
            if (cells[pin].trim() == "1") held = true
            cells[top].trim().toLongOrNull()?.let {
                if (it > best) best = it
                last = it
            }
        }
        if (!held || best <= 0 || last <= 0 || best - last <= 5) return null
        return best to last
    }

    /** A line the moment the platform takes a clock away or gives one back, with the reason. */
    private fun logCapChanges(
        clusters: List<Cluster>,
        limits: List<Int?>,
        gpuFloor: Long?,
        gpuTop: Long?,
        status: Int,
        onlineNow: String,
        held: Boolean,
        pwrLevel: Long?,
        gpuTempC: Double?,
        headroom: Double?,
        hottest: ZoneReading?,
    ): Boolean {
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
        val wasTop = lastGpuTop
        val topMoved = wasTop != null && gpuTop != null && wasTop != gpuTop
        if (topMoved) {
            // The ceiling is the platform's, so a held clock follows it down rather than past it:
            // saying so here keeps the two cases apart when the frame rate falls with it. The
            // level that set it, and the temperature at the time, are what a report is read for.
            val why = ArrayList<String>(6)
            pwrLevel?.let { why += "level $it" }
            if (held) why += if (GpuClockPin.suspended) "guard" else "held"
            gpuTempC?.let { why += String.format(Locale.US, "gpu %.1f C", it) }
            headroom?.let { why += String.format(Locale.US, "headroom %.2f", it) }
            hottest?.let { why += String.format(Locale.US, "hottest %s %.1f C", it.name, it.c) }
            BatterySnapshot.currentMa?.let { why += "bat $it mA" }
            changed += "gpu top $wasTop→$gpuTop MHz" +
                (if (why.isEmpty()) "" else " (" + why.joinToString(", ") + ")")
        }
        if (lastThermal != Int.MIN_VALUE && lastThermal != status) changed += thermalName(status)
        val wasOnline = lastOnline
        if (wasOnline != null && onlineNow.isNotEmpty() && wasOnline != onlineNow) {
            changed += "cores online $wasOnline→$onlineNow"
        }
        if (changed.isNotEmpty()) Log.i(TAG, "cap: " + changed.joinToString(", "))
        return topMoved
    }

    /**
     * A held GPU is released when the thermal headroom says the platform is about to take it to the
     * floor. The platform's own governor then picks the clock, and the hold is restored once the
     * headroom has recovered. This is opt-in because it trades the top clock for a softer landing.
     */
    private fun maybeGuard(
        status: Int,
        headroom: Double?,
        pwrLevel: Long?,
        gpuMhz: Long?,
        gpuTop: Long?,
        gpuTemp: Double?,
    ) {
        if (!guardEnabled || !GpuClockPin.pinned || GpuClockPin.lever != "ioctl") return
        if (status < PowerManager.THERMAL_STATUS_LIGHT) return
        val h = headroom ?: return
        if (pwrLevel != 0L) return
        val now = System.currentTimeMillis()
        if (!GpuClockPin.suspended) {
            val atTop = gpuMhz != null && gpuTop != null && gpuMhz >= gpuTop - 5
            if (!atTop || h < GUARD_RELEASE) return
            if (!GpuClockPin.suspendForGuard()) return
            guardSince = now
            Log.i(TAG, String.format(
                Locale.US,
                "pin: released at headroom %.2f, gpu %d/%d MHz, gpu %.1f C — letting the governor settle it before the platform clamp",
                h, gpuMhz, gpuTop, gpuTemp ?: Double.NaN,
            ))
        } else if (h <= GUARD_RESUME && now - guardSince >= GUARD_MIN_MS) {
            if (!GpuClockPin.resumeFromGuard()) return
            guardSince = 0L
            Log.i(TAG, String.format(
                Locale.US,
                "pin: re-held at headroom %.2f, gpu %s/%s MHz",
                h, gpuMhz?.toString() ?: "?", gpuTop?.toString() ?: "?",
            ))
        }
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
            "gpu_mhz", "gpu_busy_pct", "gpu_gov_load", "gpu_pin", "gpu_guard", "gpu_temp_c", "gpu_min_mhz", "gpu_max_mhz",
            "gpu_pwrlevel", "gpu_throttle", "bcl", "lm",
            "cpu_temp_c", "ddr_temp_c", "hottest_zone", "hottest_c",
            "bat_ma", "bat_mw", "bat_temp_c", "plugged", "mem_avail_kb", "swap_free_kb",
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

    /**
     * Every zone's name and temp path, captured once: the GPU's own sensor is not necessarily the
     * one the platform's thermal engine follows, and a report that names only `gpuss` can miss the
     * CPU zone that crossed the line.
     */
    private fun thermalZones(): List<ThermalZone> {
        val dirs = File(THERMAL_ROOT).listFiles { f: File -> f.name.startsWith("thermal_zone") } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val name = runCatching { File(dir, "type").readText().trim() }.getOrNull()
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ThermalZone(name, File(dir, "temp").path)
        }
    }

    private fun hottestZone(zones: List<ThermalZone>): ZoneReading? {
        var best: ZoneReading? = null
        for (zone in zones) {
            val c = thermalCelsius(firstNumber(zone.path)) ?: continue
            if (best == null || c > best.c) best = ZoneReading(zone.name, c)
        }
        return best
    }

    private class ThermalZone(val name: String, val path: String)

    private class ZoneReading(val name: String, val c: Double)

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

/**
 * GPU load from KGSL's `gpubusy` pair: microseconds busy and microseconds elapsed, over the window
 * the node restarts at every read. The kernel's own `gpu_busy_percentage` node is not usable for
 * this on the measured device — it kept a window that closed only when the GPU idled, and printed a
 * constant 13% through a session that had the GPU at its top clock — while the pair read every
 * sample gives the load over that sample.
 *
 * A window that does not match the sampling period is not a window (a driver that counts from boot
 * would land here), so nothing is reported rather than something wrong.
 */
internal fun gpuLoadPercent(pair: List<Long>, periodMs: Long): Long? {
    val busy = pair.getOrNull(0) ?: return null
    val window = pair.getOrNull(1) ?: return null
    if (busy < 0 || window <= 0 || busy > window) return null
    if (window < periodMs * 1000 / 4 || window > periodMs * 1000 * 2) return null
    return busy * 100 / window
}
