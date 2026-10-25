package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.core.GameEnvironment
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LaunchDoctorTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        File(context.filesDir, "game-environment.json").delete()
        File(context.filesDir, "launch-doctor.log").delete()
    }

    private fun signals(tail: String, gameId: String? = "349040") =
        LaunchDoctor.Signals(logTail = tail, exitStatus = 1, gameId = gameId, scope = gameId)

    @Test
    fun meshShaderCrashIsMatched() {
        val tail = """
            warn:vkd3d-proton:vkd3d_physical_device_info_info: Mesh shaders are supported, but not barycentrics. Disabling mesh shaders as a global UE5 workaround.
            err:vulkan:vkCreateGraphicsPipelines Exception 0xc0000005 in Unix call.
        """.trimIndent()
        val found = LaunchDoctor.matchMeshShaderCrash(tail)
        assertTrue(found)
    }

    @Test
    fun meshShaderCrashNeedsBothSignalParts() {
        assertEquals(null, boolToNull(LaunchDoctor.matchMeshShaderCrash("Disabling mesh shaders")))
        assertEquals(null, boolToNull(LaunchDoctor.matchMeshShaderCrash("vkCreateGraphicsPipelines failed")))
        assertEquals(null, boolToNull(LaunchDoctor.matchMeshShaderCrash("clean exit, no errors")))
    }

    private fun boolToNull(v: Boolean): Any? = if (v) true else null

    @Test
    fun steamDownloadFailureIsMatchedOnlyWithCurlError() {
        val tail = "curl: (7) Couldn't connect to server\n== steam: the Steam client could not be set up\n"
        assertTrue(LaunchDoctor.steamDownloadRule(context, LaunchDoctor.Signals(tail, 1, null, null)) != null)
        val noCurl = "== steam: the Steam client could not be set up\n"
        assertNull(LaunchDoctor.steamDownloadRule(context, LaunchDoctor.Signals(noCurl, 1, null, null)))
        assertNull(LaunchDoctor.steamDownloadRule(context, LaunchDoctor.Signals("clean log", 1, null, null)))
    }

    @Test
    fun meshPrescriptionSetsPerGameFeatureLevel() {
        GameEnvironmentStore.save(context, GameEnvironment.Config(shared = mapOf("VKD3D_FEATURE_LEVEL" to "12_2")))
        val tail = "Disabling mesh shaders as a global UE5 workaround\n0xc0000005 in vkCreateGraphics\n"
        val found = LaunchDoctor.diagnose(context, signals(tail))
        assertNotNull(found)
        assertEquals("mesh-shader-12_1", found!!.id)
        assertTrue(found.apply(context))
        val stored = GameEnvironmentStore.read(context)
        assertEquals("12_1", stored.games["349040"]?.get("VKD3D_FEATURE_LEVEL"))
    }

    @Test
    fun meshPrescriptionSkippedWhenAlreadyPinned() {
        GameEnvironmentStore.setGameEnv(context, "349040", mapOf("VKD3D_FEATURE_LEVEL" to "12_1"))
        val tail = "Disabling mesh shaders\n0xc0000005\n"
        assertNull(LaunchDoctor.diagnose(context, signals(tail)))
    }

    @Test
    fun meshPrescriptionNeedsAScope() {
        val tail = "Disabling mesh shaders\n0xc0000005\n"
        assertNull(LaunchDoctor.diagnose(context, signals(tail, gameId = null)))
    }

    @Test
    fun downloadDiagnosisIsRecorded() {
        val tail = "curl: (7) boom\n== steam: the Steam client could not be set up\n"
        val before = journalLines().size
        val found = LaunchDoctor.diagnose(context, LaunchDoctor.Signals(tail, 1, null, null))
        assertNotNull(found)
        assertEquals("steam-download-retry", found!!.id)
        assertTrue(found.apply(context))
        LaunchDoctor.record(context, found, null, applied = true)
        val after = journalLines()
        assertEquals(before + 1, after.size)
        val last = JSONObject(after.last())
        assertEquals("steam-download-retry", last.getString("rule"))
        assertTrue(last.getBoolean("applied"))
    }

    private fun journalLines(): List<String> {
        val f = File(context.filesDir, "launch-doctor.log")
        return if (f.isFile) f.readLines().filter { it.isNotBlank() } else emptyList()
    }

    @Test
    fun unknownLogYieldsNoPrescription() {
        assertNull(LaunchDoctor.diagnose(context, signals("all good, exit 0")))
    }
}
