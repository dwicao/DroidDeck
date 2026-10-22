package com.droiddeck.launcher.core

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Environment
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import android.view.Display
import com.droiddeck.launcher.gpu.LinuxVulkanDriver
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.runtime.DesktopCatalog
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import com.droiddeck.launcher.session.EsyncPacks
import com.droiddeck.launcher.session.GpuClockPin
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.thermalCelsius
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DeviceReport {
    private const val TAG = "DeviceReport"

    fun write(context: Context, target: File, mode: String) {
        try {
            target.writeText(build(context, mode))
        } catch (e: Exception) {
            Log.w(TAG, "could not write $target", e)
        }
    }

    /**
     * Lines added to a written report once the session's surface is up, which is after the report is
     * first written: what the guest is asked to draw, what the layer is sampled at, which display it
     * is on, where the GPU's clock sits beside the ceiling the platform allows, and whether the pin
     * is holding it. The pacing numbers are only known here (the display's mode settles after the
     * first frame), and the block is written again when they change and once more when the session
     * ends, so the numbers are the ones the session actually ran at.
     */
    fun appendPacing(
        context: Context,
        target: File,
        displayText: String?,
        displayHz: Float,
        guestFps: Int,
        outputHz: Float,
        note: String? = null,
    ) {
        val guest = if (guestFps > 0) "$guestFps fps" else "follows the panel"
        val layer = if (outputHz > 1f) hz(outputHz) else "unknown"
        val block = buildString {
            append(BLOCK_MARK).append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append(" --\n")
            line(this, "Session pacing", "guest $guest · layer $layer · display ${hz(displayHz)}" +
                " · ${com.droiddeck.launcher.gpu.FrameGen.label(context)}")
            displayText?.let { line(this, "Session display", it) }
            gpuClockText(kgslNumber("clock_mhz"), kgslTop(), kgslNumber("max_clock_mhz"), kgslNumber("thermal_pwrlevel"))
                ?.let { line(this, "GPU clock", it) }
            line(this, "GPU clock pin", pinText())
            gameModePacing(context)?.let { line(this, "Game Mode", it) }
            if (note != null) line(this, "Pacing note", note)
        }
        writeBlock(target, block)
    }

    /**
     * Replaces the block this wrote before instead of adding another: a session notes its pacing
     * several times, and a report that repeated the block for each would bury the rest. Only the
     * last block is ours, so the file is cut at its marker.
     */
    private fun writeBlock(target: File, block: String) {
        try {
            val old = if (target.isFile) target.readText() else ""
            val cut = old.lastIndexOf(BLOCK_MARK)
            val head = (if (cut >= 0) old.substring(0, cut) else old).trimEnd()
            target.writeText((if (head.isEmpty()) "" else head + "\n\n") + block)
        } catch (e: Exception) {
            Log.w(TAG, "could not append pacing to $target", e)
        }
    }

    private fun pinText(): String {
        if (GpuClockPin.pinned) {
            val refused = (if (GpuClockPin.levelRefused != null) "; the level node refused: ${GpuClockPin.levelRefused}" else "")
            if (GpuClockPin.suspended) {
                return "released by the thermal guard (${GpuClockPin.lever}); the hold returns when headroom recovers$refused"
            }
            return "held at its top (${GpuClockPin.lever})$refused"
        }
        if (GpuClockPin.justStopped) return "held during the session, released at stop"
        return "not held"
    }

    /**
     * The GPU's clock beside the ceiling the platform allows: the number a report is read for when
     * a held clock looks low. `thermal_pwrlevel` is the index into `freq_table_mhz` that the
     * platform's thermal engine writes; level 0 is the top, and anything above it caps
     * `max_clock_mhz` below the top the silicon can reach.
     */
    internal fun gpuClockText(clockMhz: Long?, topMhz: Long?, ceilingMhz: Long?, level: Long?): String? {
        if (clockMhz == null || ceilingMhz == null) return null
        return when {
            level != null && level > 0 -> buildString {
                append("$clockMhz MHz (platform level $level allows $ceilingMhz MHz")
                if (topMhz != null && topMhz > ceilingMhz) append("; hardware top $topMhz MHz")
                append(")")
            }
            clockMhz < ceilingMhz - 5 -> "$clockMhz MHz (below the ceiling $ceilingMhz MHz)"
            else -> "$clockMhz MHz (top level)"
        }
    }

    private const val KGSL_DIR = "/sys/class/kgsl/kgsl-3d0"
    private const val BLOCK_MARK = "-- session pacing "

    private fun kgslNumber(name: String): Long? =
        runCatching { File(KGSL_DIR, name).readText().trim().toLong() }.getOrNull()

    private fun kgslTop(): Long? = runCatching {
        File(KGSL_DIR, "freq_table_mhz").readText().trim().split(Regex("\\s+")).first().toLong()
    }.getOrNull()

    /**
     * The hottest zone, not just the GPU's: the platform's engine can follow a CPU cluster, and a
     * report that shows only the GPU reads as cool while another sensor is what crossed the line.
     */
    private fun hottestThermalZoneText(): String {
        val dirs = File("/sys/class/thermal").listFiles { f: File -> f.name.startsWith("thermal_zone") }
            ?: return "not reported"
        var bestName: String? = null
        var bestC = 0.0
        for (dir in dirs) {
            val name = runCatching { File(dir, "type").readText().trim() }.getOrNull()
                ?.takeIf { it.isNotEmpty() } ?: continue
            val raw = runCatching { File(dir, "temp").readText().trim().toLong() }.getOrNull() ?: continue
            val c = thermalCelsius(raw) ?: continue
            if (bestName == null || c > bestC) {
                bestName = name
                bestC = c
            }
        }
        return if (bestName == null) "not reported"
        else String.format(Locale.US, "%s %.1f C", bestName, bestC)
    }

    private fun line(b: StringBuilder, key: String, value: String) {
        b.append(key.padEnd(24)).append(value).append('\n')
    }

    /**
     * Which display the session ended up on, and the rates it reports. The panel lines above describe
     * the phone's own display; a session on an external screen reports its own, and a report that
     * named only the phone led a rate mismatch to be read as a pacing bug.
     */
    internal fun sessionDisplayLine(display: Display?): String? {
        val d = display ?: return null
        val rates = runCatching { d.supportedModes.map { it.refreshRate } }.getOrDefault(emptyList())
        val name = runCatching { d.name }.getOrNull()
        return sessionDisplayText(d.displayId, name, d.refreshRate, rates)
    }

    /** The text half of [sessionDisplayLine], so what a report says about a display can be tested. */
    internal fun sessionDisplayText(id: Int, name: String?, refreshHz: Float, rates: List<Float>): String {
        val label = name?.takeIf { it.isNotBlank() } ?: "unnamed"
        val modes = refreshModesSummary(rates).ifEmpty { "not reported" }
        return "id $id · $label · ${hz(refreshHz)} · modes $modes"
    }

    /**
     * Battery state and temperature, from the sticky power broadcast. A session that slowed down can
     * look the same whether the phone was on a charger or on the cell, and charging warms the pack
     * while the SoC's own thermals are still fine, so a report carries both.
     */
    internal fun batteryLines(context: Context): List<Pair<String, String>> {
        val intent = runCatching {
            context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return emptyList()
        val lines = ArrayList<Pair<String, String>>(2)
        val status = when (intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)) {
            android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            android.os.BatteryManager.BATTERY_STATUS_FULL -> "full"
            android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
            else -> "status unknown"
        }
        val plugged = when (intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0)) {
            android.os.BatteryManager.BATTERY_PLUGGED_AC -> ", AC"
            android.os.BatteryManager.BATTERY_PLUGGED_USB -> ", USB"
            android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> ", wireless"
            else -> ""
        }
        val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) String.format(Locale.US, " (%d%%)", level * 100 / scale) else ""
        lines += "Battery" to "$status$plugged$percent"
        val tenths = intent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tenths in 1..1000) {
            lines += "Battery temperature" to String.format(Locale.US, "%.1f C", tenths / 10.0)
        }
        val bm = runCatching { context.getSystemService(android.os.BatteryManager::class.java) }.getOrNull()
        val currentRaw = runCatching {
            bm?.getLongProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrNull()
        if (currentRaw != null && currentRaw != Long.MIN_VALUE) {
            val currentUa = com.droiddeck.launcher.session.BatteryComponent.toMicroamps(currentRaw)
            lines += "Battery current" to String.format(Locale.US, "%+d mA", currentUa / 1000L)
            val voltageMv = intent.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0)
            if (voltageMv > 0 && currentUa != 0L) {
                val powerMw = kotlin.math.abs(currentUa) * (voltageMv.toLong() * 1000L) / 1_000_000_000L
                lines += "Battery power" to String.format(Locale.US, "%.2f W", powerMw / 1000.0)
            }
        }
        return lines
    }

    private fun hz(value: Float): String = String.format(Locale.US, "%.2f Hz", value)

    /** Name of an ApplicationExitInfo reason code, so a report says "LOW_MEMORY" instead of "3". */
    internal fun exitReasonName(reason: Int): String = when (reason) {
        0 -> "UNKNOWN"
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "REASON_$reason"
    }

    internal fun thermalStatusName(status: Int): String = when (status) {
        0 -> "NONE"
        1 -> "LIGHT"
        2 -> "MODERATE"
        3 -> "SEVERE"
        4 -> "CRITICAL"
        5 -> "EMERGENCY"
        6 -> "SHUTDOWN"
        else -> "STATUS_$status"
    }

    internal fun gameModeName(mode: Int): String = when (mode) {
        0 -> "UNSUPPORTED"
        1 -> "STANDARD"
        2 -> "PERFORMANCE"
        3 -> "BATTERY"
        4 -> "CUSTOM"
        else -> "MODE_$mode"
    }

    private fun gameMode(context: Context): Int? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) null
        else runCatching { context.getSystemService(android.app.GameManager::class.java)?.gameMode }.getOrNull()

    /**
     * The mode is the framework's, not the app's: a game declared in the manifest is put in
     * STANDARD unless the user picks Performance in Game Booster, and STANDARD is the policy that
     * took the GPU to 295 MHz at 69 C on the measured device. The pacing block repeats it because
     * the setting can be changed while the app is installed.
     */
    private fun gameModePacing(context: Context): String? {
        val mode = gameMode(context) ?: return null
        return when (mode) {
            android.app.GameManager.GAME_MODE_PERFORMANCE -> "PERFORMANCE"
            android.app.GameManager.GAME_MODE_STANDARD ->
                "STANDARD (GOS may cap the GPU; set Game Booster → Game optimisation → Performance)"
            android.app.GameManager.GAME_MODE_BATTERY -> "BATTERY"
            else -> gameModeName(mode)
        }
    }

    internal fun refreshModesSummary(rates: List<Float>): String =
        rates.map { Math.round(it) }.distinct().sortedDescending().joinToString(", ") { "$it Hz" }

    internal fun exitSummary(processName: String?, reason: Int, status: Int, description: String?): String =
        buildString {
            append(processName ?: "?").append("  ").append(exitReasonName(reason)).append("  status=").append(status)
            if (!description.isNullOrBlank()) append("  ").append(description.trim())
        }

    fun build(context: Context, mode: String): String {
        val b = StringBuilder()
        fun h(title: String) {
            b.append('\n').append(title).append('\n').append("-".repeat(title.length)).append('\n')
        }
        fun k(key: String, value: Any?) {
            b.append(key.padEnd(24)).append(value ?: "unknown").append('\n')
        }

        b.append("DroidDeck session report\n")
        b.append("========================\n")
        k("Written", SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US).format(Date()))
        k("Session mode", when (mode) { SessionService.MODE_DESKTOP -> "desktop (KDE Plasma)"; SessionService.MODE_RUN -> "a program under gamescope"; else -> "Steam client (gamescope)" })

        h("App")
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            k("Version", "${info.versionName} (${info.longVersionCode})")
        }
        k("Package", context.packageName)
        k("targetSdk", context.applicationInfo.targetSdkVersion)
        k("Native lib dir", context.applicationInfo.nativeLibraryDir)

        h("Device")
        k("Model", "${Build.MANUFACTURER} ${Build.MODEL}")
        k("Device / product", "${Build.DEVICE} / ${Build.PRODUCT}")
        k("Board / hardware", "${Build.BOARD} / ${Build.HARDWARE}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            k("SoC", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        }
        k("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        k("Security patch", Build.VERSION.SECURITY_PATCH)
        k("Build", Build.DISPLAY)
        k("Fingerprint", Build.FINGERPRINT)
        k("Kernel", System.getProperty("os.version"))
        k("ABIs", Build.SUPPORTED_ABIS.joinToString(", "))
        k("Memory page size", runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) }.getOrNull()?.let { "$it bytes" })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            h("Recent process exits")
            val exits = runCatching {
                context.getSystemService(android.app.ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 5)
            }.getOrNull().orEmpty()
            if (exits.isEmpty()) k("Exits", "none recorded") else {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                for (e in exits) {
                    k(stamp.format(Date(e.timestamp)), exitSummary(e.processName, e.reason, e.status, e.description))
                }
            }
        }

        h("CPU and memory")
        k("Cores", CpuCores.all.size)
        b.append("Core ceilings          ")
        b.append(CpuCores.all.joinToString(", ") { c ->
            "cpu$c " + (CpuCores.maxGhz(c)?.let { String.format(Locale.US, "%.2f GHz", it) } ?: "?")
        })
        b.append('\n')
        runCatching {
            val mi = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            k("RAM total", FileUtils.sizeToString(mi.totalMem))
            k("RAM available", FileUtils.sizeToString(mi.availMem))
        }
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) k("Thermal status", thermalStatusName(pm.currentThermalStatus))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                k("Thermal headroom (10 s)", pm.getThermalHeadroom(10).let { if (it.isNaN()) "not reported" else String.format(Locale.US, "%.2f", it) })
            }
            k("Power saver", pm.isPowerSaveMode)
        }
        for ((key, value) in batteryLines(context)) k(key, value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val rate = runCatching { context.getSystemService(PerformanceHintManager::class.java)?.preferredUpdateRateNanos }.getOrNull()
            k("ADPF hint sessions", if (rate == null || rate <= 0) "not supported" else "supported (preferred update ${rate / 1000} us)")
            val mode = gameMode(context)
            k("Game Mode", mode?.let { gameModeName(it) })
            if (mode == android.app.GameManager.GAME_MODE_STANDARD) {
                k("Game Booster", "Standard; set Game optimisation to Performance so GOS stops capping the GPU")
            }
        }
        runCatching {
            val fs = StatFs(context.filesDir.path)
            k("App storage free", FileUtils.sizeToString(fs.availableBlocksLong * fs.blockSizeLong))
        }
        runCatching {
            val fs = StatFs(Environment.getExternalStorageDirectory().path)
            k("Shared storage free", FileUtils.sizeToString(fs.availableBlocksLong * fs.blockSizeLong))
        }

        h("GPU")
        k("KGSL gpu_model", readSys("/sys/class/kgsl/kgsl-3d0/gpu_model"))
        k("KGSL chip id", readSys("/sys/class/kgsl/kgsl-3d0/gpu_chipid"))
        k("GPU clock pin", if (SessionPrefs.gpuClockPin(context)) "on, a session holds the top level" else "off, the platform governs the level")
        k("GPU thermal guard", if (SessionPrefs.thermalGuard(context)) "on, releases the hold near the clamp" else "off, the hold stays until the platform takes it")
        k("GPU clock now", gpuClockText(kgslNumber("clock_mhz"), kgslTop(), kgslNumber("max_clock_mhz"), kgslNumber("thermal_pwrlevel")) ?: "not reported")
        k("Hottest thermal zone", hottestThermalZoneText())
        k("GPU power levels", runCatching {
            File(KGSL_DIR, "freq_table_mhz").readText().trim().replace(Regex("\\s+"), " ")
        }.getOrNull()?.let { "$it MHz" } ?: "not reported")
        k("System Vulkan ICD", if (File("/vendor/lib64/hw/vulkan.adreno.so").exists()) "/vendor/lib64/hw/vulkan.adreno.so" else "not at the usual path")

        h("Display")
        k("Session output", SessionState.outputSize?.let { "${it.first}x${it.second}" })
        k("Session refresh", String.format(Locale.US, "%.2f Hz", SessionState.refreshHz))
        runCatching {
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            k("Panel refresh now", String.format(Locale.US, "%.2f Hz", display.refreshRate))
            k("Panel refresh modes", refreshModesSummary(display.supportedModes.map { it.refreshRate }))
        }
        k("peak_refresh_rate", runCatching { Settings.System.getString(context.contentResolver, "peak_refresh_rate") }.getOrNull() ?: "not set")
        k("refresh_rate_mode", runCatching { Settings.Secure.getString(context.contentResolver, "refresh_rate_mode") }.getOrNull() ?: "not set")
        val panelSize = com.droiddeck.launcher.session.SessionDisplay.panelSize(context)
        for ((label, mode) in listOf("Resolution (Steam)" to SessionService.MODE_STEAM, "Resolution (Desktop)" to SessionService.MODE_DESKTOP)) {
            val choice = SessionPrefs.resolutionChoice(context, mode, panelSize)
            val size = com.droiddeck.launcher.session.SessionDisplay.resolveChoice(panelSize, choice)
            k(label, "$choice (${size.first}x${size.second})")
        }
        k("Foldable", context.packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle"))

        h("Drivers")
        val gpu = com.droiddeck.launcher.gpu.GpuInfo.detect()
        k("GPU", "${gpu.name} · ${gpu.family.label} · ${gpu.supportText}" + if (gpu.oneUi8Gen2) " · One UI 8 Gen 2" else "")
        if (gpu.model == 740) k("TP UBWC hint", com.droiddeck.launcher.gpu.TpUbwcHint.forThisDevice() ?: "driver default")
        k("Driver mode", SessionPrefs.gpuDriverMode(context) +
            (com.droiddeck.launcher.gpu.DriverPairs.recommendedKey(gpu, com.droiddeck.launcher.gpu.DriverPairs.from(
                com.droiddeck.launcher.gpu.TurnipReleases.cached(context)), com.droiddeck.launcher.gpu.TurnipReleases.autoPicks(context),
                com.droiddeck.launcher.gpu.AutoDriverTrial.bad(context))?.let { " (recommended pair: $it)" } ?: ""))
        val turnip = TurnipDriver(context)
        val androidChoice = SessionPrefs.androidDriver(context)
        k("Display driver (chosen)", if (androidChoice.isEmpty()) "Auto -> ${turnip.autoId()}" else androidChoice)
        k("  name / version", "${turnip.displayName(if (androidChoice.isEmpty()) turnip.autoId() else androidChoice)} ${turnip.driverVersion(if (androidChoice.isEmpty()) turnip.autoId() else androidChoice)}".trim())
        k("  imported available", turnip.enumerateImported().ifEmpty { listOf("none") }.joinToString(", "))
        val lm = LinuxVulkanDriverManager(context)
        run {
            val id = SessionPrefs.linuxDriver(context)
            val label = if (id.isEmpty()) "runtime default (the Turnip built into the runtime)"
            else "${lm.getDriverName(id)} ${lm.getDriverVersion(id)}".trim() +
                lm.getMinGlibc(id).let { if (it.isEmpty()) "" else " (glibc $it+)" } +
                if (lm.isInstalled(id)) "" else "  [MISSING - falls back to the runtime's own]"
            k("Linux driver", label)
        }
        k("Runtime's own ICD", LinuxRuntime.vulkanIcd(context)?.path)

        h("Runtime")
        k("Installed version", LinuxRuntimeInstaller.installedVersion(context))
        k("Runtime ready", LinuxRuntime.isInstalled(context))
        k("Desktop installed", DesktopCatalog.desktopInstalled(context))
        k("Interrupted installs", DesktopCatalog.interrupted(context).joinToString { "${it.id} ${it.version}" }.ifEmpty { "none" })
        val broken = runCatching { com.droiddeck.launcher.runtime.RuntimeHealth.brokenLibraryLinks(context) }.getOrDefault(emptyList())
        k("Broken library links", if (broken.isEmpty()) "none" else "${broken.size}: ${broken.take(8).joinToString()}")
        k("Runtime root", LinuxRuntime.rootDir(context).path)

        h("Settings in effect")
        k("Client core override", SessionPrefs.clientCpusOverride(context))
        k("  client cores", CpuCores.listOrAll(SessionPrefs.clientCpus(context)))
        k("  game cores", CpuCores.restrictionOrEmpty(SessionPrefs.gameCpus(context)).ifEmpty { "every core" })
        k("Turnip sysmem", SessionPrefs.tuSysmem(context))
        k("Zink lazy descriptors", SessionPrefs.zinkLazy(context))
        k("Threaded GL (glthread)", SessionPrefs.glThread(context))
        k("No GL error checks", SessionPrefs.noGlError(context))
        k("Steam controller", SessionPrefs.steamController(context))
        k("Upscaler", (SessionPrefs.upscalerChoices.firstOrNull { it.first == SessionPrefs.upscaler(context) }?.second ?: "Off") +
            " (sharpness ${SessionPrefs.upscaleSharpness(context)}%)")
        k("FEX preset", SessionPrefs.fexPreset(context).ifEmpty { "FEX defaults" })
        k("Skip xalia", SessionPrefs.noXalia(context))
        k("Wine sync", SessionPrefs.syncBackend(context))
        EsyncPacks.status(LinuxRuntime.rootDir(context)).let { k("droiddeck-esync packs", "${it.installed} installed, ${it.wanted} wanted") }
        k("Linux x86 (FEX)", if (com.droiddeck.launcher.runtime.LinuxFex.ready(context)) "ready" else "not set up")
        k("gamescope realtime", SessionPrefs.gamescopeRealtime(context))
        k("proot without seccomp", SessionPrefs.prootNoSeccomp(context))
        k("proot fast path", SessionPrefs.prootFastPath(context))
        k("Guest host name", SessionPrefs.guestHostname(context))
        k("DirectAudio for games", SessionPrefs.directAudio(context))
        k("Stretch 16:9 to panel", SessionPrefs.stretch16x9(context))
        k("Return to launcher after a game", SessionPrefs.returnAfterGame(context))
        k("Microphone", SessionPrefs.micEnabled(context))
        k("On-screen controls", SessionPrefs.oscMode(context))
        k("Touch mode", SessionPrefs.touchMode(context))
        k("Performance HUD", SessionPrefs.hudEnabled(context))
        k("Game storage", when (val g = SessionPrefs.gameStorage(context)) {
            "" -> "automatic (" + (com.droiddeck.launcher.session.GameStorage.effective(context)?.path ?: "no card present") + ")"
            SessionPrefs.GAME_STORAGE_OFF -> "internal only"
            else -> g
        })

        h("Android process limits")
        k("Phantom proc monitor", PhantomProcessLimit.reportValue(PhantomProcessLimit.read(context)))

        h("Device switch files")
        SessionService.envSwitchFile(context)?.takeIf { it.isFile }?.let { f ->
            k("droiddeck-env", FileUtils.readString(f)?.trim()?.replace('\n', ' ')?.ifEmpty { "(present, empty)" } ?: "(present)")
        }
        if (File(Environment.getExternalStorageDirectory(), "Download/droiddeck-env").isFile) {
            k("Download/droiddeck-env", "(ignored - it moved to Android/data/${context.packageName}/files)")
        }
        for (name in listOf("droiddeck-tu-debug", "droiddeck-driver",
                            "droiddeck-osc", "droiddeck-no-pad",
                            "droiddeck-no-hud")) {
            val f = File(Environment.getExternalStorageDirectory(), "Download/$name")
            if (f.isFile) k(name, FileUtils.readString(f)?.trim()?.replace('\n', ' ')?.ifEmpty { "(present, empty)" } ?: "(present)")
        }

        b.append("\nNothing identifying is collected here: no serial number, no device or advertising\n")
        b.append("id, no account name, no network names. Safe to attach to a bug report as it is.\n")
        return b.toString()
    }

    private fun readSys(path: String): String? = FileUtils.readString(File(path))?.trim()?.ifEmpty { null }
}
