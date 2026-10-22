package com.droiddeck.launcher.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LinuxFexTest {
    @get:Rule val temp = TemporaryFolder()

    private fun steamapps(name: String, vararg appIds: Int): File {
        val dir = File(temp.newFolder(name), "steamapps")
        dir.mkdirs()
        for (id in appIds) File(dir, "appmanifest_$id.acf").writeText("\"AppState\"\n{\n\t\"appid\"\t\t\"$id\"\n}\n")
        return dir
    }

    @Test fun bothDepotManifestsAbsentAreReported() {
        val missing = LinuxFex.missing(listOf(steamapps("a")))
        assertEquals(listOf(LinuxFex.SNIPER_APPID, LinuxFex.FEX_APPID), missing.map { it.first })
        assertTrue(missing.any { it.second == "Steam Linux Runtime 3.0" })
    }

    @Test fun theRuntimeIsAskedForBeforeTheEmulator() {
        assertEquals(LinuxFex.SNIPER_APPID, LinuxFex.DEPENDENCIES.first().first)
    }

    @Test fun oneInstalledStillLeavesTheOtherMissing() {
        val missing = LinuxFex.missing(listOf(steamapps("a", LinuxFex.SNIPER_APPID)))
        assertEquals(listOf(LinuxFex.FEX_APPID), missing.map { it.first })
    }

    @Test fun aDepotInAnyLibraryCounts() {
        val dirs = listOf(steamapps("a", LinuxFex.SNIPER_APPID), steamapps("b", LinuxFex.FEX_APPID))
        assertEquals(emptyList<Int>(), LinuxFex.missing(dirs).map { it.first })
    }

    @Test fun aLibraryThatIsNotThereYetReportsEverything() {
        assertEquals(2, LinuxFex.missing(emptyList()).size)
    }
}