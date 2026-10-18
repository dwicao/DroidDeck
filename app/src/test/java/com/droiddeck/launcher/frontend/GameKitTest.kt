package com.droiddeck.launcher.frontend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class GameKitTest {
    @get:Rule val temp = TemporaryFolder()

    private val manifestTemplate = """
        "AppState"
        {
        	"appid"		"620"
        	"Universe"		"1"
        	"name"		"Portal 2"
        	"StateFlags"		"4"
        	"installdir"		"Portal 2"
        	"LastUpdated"		"1700000000"
        	"SizeOnDisk"		"%d"
        	"buildid"		"7534921"
        	"InstalledDepots"
        	{
        		"621"
        	{
        		"manifest"		"55123456"
        		"size"		"13123456"
        	}
        	}
        }
    """.trimIndent()

    /** Build a live install whose files actually add up to the manifest's SizeOnDisk. */
    private fun source(dir: File, sizeOnDisk: Long = 5120, installdir: String = "Portal 2"): GameKit.Source {
        val manifestText = manifestTemplate.format(sizeOnDisk)
        val manifestFile = File(dir, "appmanifest_620.acf")
        manifestFile.writeText(manifestText)
        val gameDir = File(dir, "common/$installdir").apply { mkdirs() }
        File(gameDir, "portal2.exe").writeBytes(ByteArray(sizeOnDisk.toInt()))
        return GameKit.parse(manifestText, manifestFile)!!
    }

    private fun textOnlyManifest(sizeOnDisk: Long = 0): String =
        manifestTemplate.format(sizeOnDisk)

    @Test fun readsEveryFieldSteamWrote() {
        val parsed = GameKit.parse(textOnlyManifest(13123456), File("appmanifest_620.acf"))!!
        assertEquals(620, parsed.appId)
        assertEquals("Portal 2", parsed.name)
        assertEquals("Portal 2", parsed.installDir)
        assertTrue(parsed.installed)
        assertEquals(1, parsed.depots)
        assertEquals("7534921", parsed.buildId)
        assertEquals(13123456L, parsed.sizeOnDisk)
    }

    @Test fun aManifestWithoutAnAppIdIsNotAKit() {
        assertNull(GameKit.parse("\"AppState\"\n{\n\t\"name\"\t\t\"x\"\n}\n", File("m.acf")))
    }

    @Test fun sizeOnDiskIsReadAsTheDepotBlockItIs() {
        val parsed = GameKit.parse(textOnlyManifest(98765432100), File("m.acf"))!!
        assertEquals(98765432100L, parsed.sizeOnDisk)
    }

    @Test fun onlyTheInstallDirFollowsTheExportedFolder() {
        val parsed = GameKit.parse(textOnlyManifest(), File("m.acf"))!!
        val moved = GameKit.manifestFor(parsed, "Portal 2 (kit)")
        assertTrue(moved.contains("\"installdir\"\t\"Portal 2 (kit)\""))
        assertTrue(moved.contains("\"manifest\"\t\t\"55123456\""))
        assertTrue(moved.contains("\"buildid\"\t\t\"7534921\""))
        assertTrue(moved.contains("\"appid\"\t\t\"620\""))
        assertEquals(textOnlyManifest(), GameKit.manifestFor(parsed, "Portal 2"))
    }

    @Test fun anInstallWithNoDepotRecordIsRefused() {
        val dir = temp.newFolder("steamapps")
        File(File(dir, "common"), "Portal 2").mkdirs()
        File(File(dir, "common/Portal 2"), "x.bin").writeBytes(ByteArray(10))
        val bare = "\"AppState\"\n{\n\t\"appid\"\t\t\"620\"\n\t\"StateFlags\"\t\t\"4\"\n\t\"installdir\"\t\t\"Portal 2\"\n}\n"
        val parsed = GameKit.parse(bare, File(dir, "appmanifest_620.acf"))!!
        assertEquals(listOf(GameKit.Gate.NO_DEPOT_RECORD), GameKit.gates(parsed))
    }

    @Test fun anUnfinishedInstallIsRefused() {
        val dir = temp.newFolder("steamapps2")
        File(File(dir, "common"), "Portal 2").mkdirs()
        File(File(dir, "common/Portal 2"), "x.bin").writeBytes(ByteArray(10))
        val queued = textOnlyManifest().replace("\"StateFlags\"\t\t\"4\"", "\"StateFlags\"\t\t\"1026\"")
        val parsed = GameKit.parse(queued, File(dir, "appmanifest_620.acf"))!!
        assertEquals(listOf(GameKit.Gate.NOT_INSTALLED), GameKit.gates(parsed))
    }

    @Test fun anInstallShorterThanItsRecordIsRefused() {
        val dir = temp.newFolder("steamapps3")
        File(File(dir, "common"), "Portal 2").mkdirs()
        File(File(dir, "common/Portal 2"), "x.bin").writeBytes(ByteArray(10))
        val big = textOnlyManifest(99999999999)
        val parsed = GameKit.parse(big, File(dir, "appmanifest_620.acf"))!!
        assertTrue(GameKit.gates(parsed).contains(GameKit.Gate.SHORT))
    }

    @Test fun aCompleteInstallPassesEveryGate() {
        val dir = temp.newFolder("steamapps4")
        val source = source(dir)
        assertEquals(emptyList<GameKit.Gate>(), GameKit.gates(source))
    }

    @Test fun measuringCountsBytesAndFilesButNotLinks() {
        val dir = temp.newFolder("measure")
        val sub = File(dir, "sub")
        sub.mkdirs()
        File(dir, "a.bin").writeBytes(ByteArray(100))
        File(sub, "b.bin").writeBytes(ByteArray(50))
        Files.createSymbolicLink(File(sub, "link").toPath(), File(dir, "a.bin").toPath())
        val measured = GameKit.measure(dir)
        assertEquals(150L, measured.bytes)
        assertEquals(2, measured.files)
    }

    @Test fun exportedFolderNamesAreOnesSteamAccepts() {
        assertEquals("Naruto Ninja Storm 4", GameKit.installDirFor("Naruto: Ninja Storm 4", 2590300))
        assertEquals("Half-Life 2", GameKit.installDirFor("Half-Life 2", 220))
        assertTrue(GameKit.installDirFor("///", 7).isNotBlank())
        assertEquals("game-7", GameKit.installDirFor("", 7))
    }

    @Test fun archiveNamesCarryTheAppIdAndNoPathCharacters() {
        val dir = temp.newFolder("steamapps5")
        val name = GameKit.archiveName(source(dir))
        assertTrue(name.endsWith("-620.tar.zst"))
        assertTrue(!name.contains('/'))
    }

    @Test fun aKitRoundTripsIntoAnotherLibrary() {
        val dir = temp.newFolder("from")
        val source = source(dir)
        val destination = temp.newFolder("out")

        GameKit.codec = GameKit.Codec.None
        try {
            val exported = GameKit.export(source, destination)
            assertEquals("steamapps/common/Portal 2/portal2.exe", firstEntry(exported.archive, "portal2.exe"))
            val kit = GameKit.inspect(exported.archive) ?: error("kit is null")
            assertEquals(620, kit.appId)
            assertEquals(1, kit.depots)

            val library = temp.newFolder("Steam")
            val imported = GameKit.import(exported.archive, library)
            assertEquals(exported.kit.installDir, imported.kit.installDir)
            assertTrue(File(library, "steamapps/common/${exported.kit.installDir}/portal2.exe").isFile)
            assertEquals(exported.measured.bytes, imported.measured.bytes)
            assertEquals(exported.measured.files, imported.measured.files)
            assertEquals(0L, imported.shortBy)
            val written = File(library, "steamapps/appmanifest_620.acf").readText()
            assertTrue(written.contains("\"manifest\"\t\t\"55123456\""))
            assertTrue(written.contains("\"installdir\"\t\t\"${exported.kit.installDir}\""))
        } finally {
            GameKit.codec = GameKit.Codec.Zstd
        }
    }

    @Test fun importingTwiceOverTheSameLibraryIsRefused() {
        val dir = temp.newFolder("from2")
        val source = source(dir)
        val destination = temp.newFolder("out2")
        GameKit.codec = GameKit.Codec.None
        try {
            val exported = GameKit.export(source, destination)
            val library = temp.newFolder("Steam2")
            GameKit.import(exported.archive, library)
            val again = runCatching { GameKit.import(exported.archive, library) }.exceptionOrNull()
            assertTrue(again is IllegalArgumentException)
        } finally {
            GameKit.codec = GameKit.Codec.Zstd
        }
    }

    @Test fun anArchiveWithoutAManifestIsRefused() {
        val empty = temp.newFile("nothing.tar.zst")
        GameKit.codec = GameKit.Codec.None
        try {
            empty.outputStream().use { it.close() }
            assertNull(GameKit.inspect(empty))
        } finally {
            GameKit.codec = GameKit.Codec.Zstd
        }
    }

    @Test fun anEntryPointingOutsideTheLibraryIsSkipped() {
        GameKit.codec = GameKit.Codec.None
        try {
            val archive = File(temp.newFolder("evil-out"), "escape.tar.zst")
            archive.outputStream().use { out ->
                org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(out).use { tar ->
                    val escape = org.apache.commons.compress.archivers.tar.TarArchiveEntry("../escaped.txt")
                    escape.size = 0
                    tar.putArchiveEntry(escape)
                    tar.closeArchiveEntry()
                    val manifestBytes = textOnlyManifest().toByteArray()
                    val manifestEntry = org.apache.commons.compress.archivers.tar.TarArchiveEntry("steamapps/appmanifest_620.acf")
                    manifestEntry.size = manifestBytes.size.toLong()
                    tar.putArchiveEntry(manifestEntry)
                    tar.write(manifestBytes)
                    tar.closeArchiveEntry()
                    val dummyBytes = "game".toByteArray()
                    val dummyEntry = org.apache.commons.compress.archivers.tar.TarArchiveEntry("steamapps/common/Portal 2/dummy.bin")
                    dummyEntry.size = dummyBytes.size.toLong()
                    tar.putArchiveEntry(dummyEntry)
                    tar.write(dummyBytes)
                    tar.closeArchiveEntry()
                }
            }
            assertNotNull(GameKit.inspect(archive))
            val library = temp.newFolder("Steam3")
            GameKit.import(archive, library)
            assertTrue(!File(library.parentFile, "escaped.txt").exists())
        } finally {
            GameKit.codec = GameKit.Codec.Zstd
        }
    }

    private fun firstEntry(archive: File, endsWith: String): String? {
        return archive.inputStream().use { input ->
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(input).use { tar ->
                var entry = tar.nextEntry
                var found: String? = null
                while (entry != null) {
                    if (entry.isFile && entry.name.endsWith(endsWith)) found = entry.name
                    entry = tar.nextEntry
                }
                found
            }
        }
    }
}