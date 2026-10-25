package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.core.GameEnvironmentOptions
import java.io.File
import org.json.JSONObject

/**
 * Self-repair for failed sessions: match the session log against known failure
 * signatures and apply the proven fix, then retry. Only rules with a safe,
 * reversible, programmatic fix live here; everything else keeps the existing
 * hint-and-retry path.
 *
 * Rules run in order and the first match wins. A rule fires only when its fix
 * would actually change something, so a repeated crash never claims a fix twice.
 * A rule that was already applied [MAX_APPLIES] times for the same game without
 * the session recovering is suppressed: the fix demonstrably does not work for
 * this install, and the plain retry button stays available.
 */
object LaunchDoctor {
    data class Signals(
        val logTail: String,
        val exitStatus: Int,
        val gameId: String?,
        val scope: String?,
    )

    data class Prescription(
        val id: String,
        val detail: String,
        val apply: (Context) -> Boolean,
    )

    private const val LOG_NAME = "launch-doctor.log"
    private const val HISTORY_LINES = 50
    internal const val MAX_APPLIES = 2

    fun diagnose(context: Context, signals: Signals): Prescription? {
        if (!suppressed(context, "mesh-shader-12_1", signals.gameId))
            meshShaderRule(context, signals)?.let { return it }
        if (!suppressed(context, "steam-download-retry", signals.gameId))
            steamDownloadRule(context, signals)?.let { return it }
        if (!suppressed(context, "steam-setup-retry", signals.gameId))
            steamSetupRule(context, signals)?.let { return it }
        return null
    }

    fun matchMeshShaderCrash(logTail: String): Boolean {
        val mentionsMeshShaders = "Disabling mesh shaders" in logTail || "Mesh shaders are supported" in logTail
        val diedInPipeline = "vkCreateGraphicsPipelines" in logTail || "build_bindless" in logTail || "0xc0000005" in logTail
        return mentionsMeshShaders && diedInPipeline
    }

    /**
     * UE5/DX12 games crash in Turnip's pipeline creation when feature level 12_2
     * is advertised: vkd3d disables mesh shaders on Adreno, but the pipeline
     * layout still expects them (RoboCop: Rogue City, #1410). Pinning the game
     * to 12_1 removes the crash. Fires only when the effective level is 12_2.
     */
    internal fun meshShaderRule(context: Context, signals: Signals): Prescription? {
        val scope = signals.scope?.takeIf { it.isNotEmpty() } ?: return null
        if (!GameEnvironment.validScope(scope)) return null
        if (!matchMeshShaderCrash(signals.logTail)) return null
        if (effectiveFeatureLevel(context, scope) != "12_2") return null
        return Prescription(
            id = "mesh-shader-12_1",
            detail = context.getString(com.droiddeck.launcher.R.string.doctor_mesh_detail),
            apply = {
                runCatching {
                    GameEnvironmentStore.setGameEnv(it, scope, mapOf("VKD3D_FEATURE_LEVEL" to "12_1"))
                    true
                }.getOrDefault(false)
            },
        )
    }

    /**
     * The Steam client failed to download (transient network error). Nothing
     * needs fixing; retrying the session is the repair.
     */
    internal fun steamDownloadRule(context: Context, signals: Signals): Prescription? {
        if (!matchSteamDownload(signals.logTail)) return null
        return Prescription(
            id = "steam-download-retry",
            detail = context.getString(com.droiddeck.launcher.R.string.doctor_download_detail),
            apply = { true },
        )
    }

    internal fun matchSteamDownload(logTail: String): Boolean {
        val failure = SteamClientSetupFailure.find(logTail.lineSequence()) ?: return false
        return failure.download
    }

    /**
     * The Steam client setup script failed for a non-network reason. Re-running
     * setup on retry is the only safe action; repeated failures are left to the
     * plain retry path by the loop guard.
     */
    internal fun steamSetupRule(context: Context, signals: Signals): Prescription? {
        val failure = SteamClientSetupFailure.find(signals.logTail.lineSequence()) ?: return null
        if (failure.download) return null
        return Prescription(
            id = "steam-setup-retry",
            detail = context.getString(com.droiddeck.launcher.R.string.doctor_setup_detail),
            apply = { true },
        )
    }

    private fun effectiveFeatureLevel(context: Context, scope: String): String? {
        val config = runCatching { GameEnvironmentStore.read(context) }.getOrNull() ?: return null
        config.games[scope]?.get("VKD3D_FEATURE_LEVEL")?.takeIf { it != null }?.let { return it }
        config.shared["VKD3D_FEATURE_LEVEL"]?.takeIf { it != null }?.let { return it }
        return GameEnvironmentOptions.find("VKD3D_FEATURE_LEVEL")?.value
    }

    /**
     * Counts applied fixes for this rule+game in recent history. A fix applied
     * [MAX_APPLIES] times while the session keeps failing demonstrably does not
     * work for this install, so the rule is suppressed. Never throws.
     */
    internal fun suppressed(context: Context, ruleId: String, gameId: String?): Boolean =
        appliedCount(context, ruleId, gameId) >= MAX_APPLIES

    internal fun appliedCount(context: Context, ruleId: String, gameId: String?): Int {
        val lines = runCatching {
            val file = File(context.filesDir, LOG_NAME)
            if (!file.isFile) return 0
            file.readLines().filter { it.isNotBlank() }.takeLast(HISTORY_LINES)
        }.getOrDefault(emptyList())
        var count = 0
        for (line in lines) {
            val entry = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (!entry.optBoolean("applied", false)) continue
            if (entry.optString("rule", "") != ruleId) continue
            val game = if (entry.isNull("game")) null else entry.optString("game")
            if (game == gameId) count++
        }
        return count
    }

    /** Appends one JSON line per diagnosis for the knowledge base. Never throws. */
    fun record(context: Context, prescription: Prescription?, gameId: String?, applied: Boolean) {
        runCatching {
            val entry = JSONObject()
                .put("t", System.currentTimeMillis())
                .put("rule", prescription?.id ?: "none")
                .put("game", gameId ?: JSONObject.NULL)
                .put("applied", applied)
                .toString()
            File(context.filesDir, LOG_NAME).appendText(entry + "\n")
        }
    }
}
