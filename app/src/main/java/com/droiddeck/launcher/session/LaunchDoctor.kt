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

    fun diagnose(context: Context, signals: Signals): Prescription? {
        meshShaderRule(context, signals)?.let { return it }
        steamDownloadRule(context, signals)?.let { return it }
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
        val failure = SteamClientSetupFailure.find(signals.logTail.lineSequence()) ?: return null
        if (!failure.download) return null
        return Prescription(
            id = "steam-download-retry",
            detail = context.getString(com.droiddeck.launcher.R.string.doctor_download_detail),
            apply = { true },
        )
    }

    private fun effectiveFeatureLevel(context: Context, scope: String): String? {
        val config = runCatching { GameEnvironmentStore.read(context) }.getOrNull() ?: return null
        config.games[scope]?.get("VKD3D_FEATURE_LEVEL")?.takeIf { it != null }?.let { return it }
        config.shared["VKD3D_FEATURE_LEVEL"]?.takeIf { it != null }?.let { return it }
        return GameEnvironmentOptions.find("VKD3D_FEATURE_LEVEL")?.value
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
