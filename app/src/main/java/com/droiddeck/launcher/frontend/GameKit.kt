package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.ArchivePaths
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.session.SessionState
import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.Locale

/**
 * A game's kit: its files and the manifest that says which depot build they are, in one archive
 * laid out the way Steam lays a library out.
 *
 * Steam cannot adopt a folder it did not download. It compares the manifest's `InstalledDepots`
 * against the depot it offers now and keeps every file that matches, so a copied install that keeps
 * its manifest beside it is recognised and nothing is downloaded again. Export copies that manifest
 * as it is - only `installdir` follows the exported folder - and import puts the files where the
 * manifest says, so the client reads the install off disk at its next start instead of turning the
 * title into a shortcut.
 */
object GameKit {
    private const val TAG = "GameKit"
    const val KIT_INFO = "droiddeck-kit.json"
    const val EXTENSION = "tar.zst"
    private const val COMMON = "steamapps/common"
    private const val ZSTD_LEVEL = 3
    private const val BUFFER = 1 shl 18
    private const val SHORT_TOLERANCE = 0.02

    private const val MODE_FILE = 0b110_100_100      // 0644
    private const val MODE_EXEC = 0b111_101_101      // 0755
    private const val MODE_DIR = 0b111_101_101
    private const val MODE_LINK = 0b111_111_111
    private const val MODE_OWNER_EXEC = 0b100_000_000

    private val APPID = Regex("\"appid\"\\s*\"(\\d+)\"", RegexOption.IGNORE_CASE)
    private val NAME = Regex("\"name\"\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val DIR = Regex("\"installdir\"\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val FLAGS = Regex("\"StateFlags\"\\s*\"(\\d+)\"", RegexOption.IGNORE_CASE)
    private val BUILD = Regex("\"buildid\"\\s*\"(\\d+)\"", RegexOption.IGNORE_CASE)
    private val SIZE = Regex("\"SizeOnDisk\"\\s*\"(\\d+)\"", RegexOption.IGNORE_CASE)
    private val DEPOT = Regex("\"InstalledDepots\"\\s*\\{\\s*\"(\\d+)\"", RegexOption.IGNORE_CASE)
    private val UNSAFE = Regex("""[\\/:*?"<>|]""")
    private val SPACES = Regex("\\s+")

    /** What the client currently believes about an installed game, and where its files are. */
    data class Source(
        val appId: Int,
        val name: String,
        val installDir: String,
        val installed: Boolean,
        val depots: Int,
        val buildId: String,
        val sizeOnDisk: Long,
        val manifestFile: File,
        val gameDir: File,
        val manifestText: String,
    )

    data class Measured(val bytes: Long, val files: Int)

    /** Why a game is not ready to be packaged, worst first. Export refuses while any is set. */
    enum class Gate { NOT_INSTALLED, NO_DEPOT_RECORD, NO_FILES, SHORT }

    /** What a kit's manifest says. */
    data class Kit(
        val appId: Int,
        val name: String,
        val installDir: String,
        val depots: Int,
        val buildId: String,
        val sizeOnDisk: Long,
    )

    data class Result(val kit: Kit, val archive: File, val measured: Measured)

    data class Imported(
        val kit: Kit,
        val library: File,
        val gameDir: File,
        val manifestFile: File,
        val measured: Measured,
        val shortBy: Long,
    )

    /** The live install of [appId], or null when no library holds a manifest for it. */
    fun source(context: Context, appId: Int): Source? {
        for (steamapps in SessionInstalls.steamappsDirs(context)) {
            val manifest = File(steamapps, "appmanifest_$appId.acf")
            if (!manifest.isFile) continue
            val parsed = parse(manifest.readText(), manifest) ?: continue
            return parsed
        }
        return null
    }

    internal fun parse(text: String, manifest: File): Source? {
        val appId = APPID.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val installDir = DIR.find(text)?.groupValues?.get(1).orEmpty()
        return Source(
            appId = appId,
            name = NAME.find(text)?.groupValues?.get(1).orEmpty().ifBlank { appId.toString() },
            installDir = installDir,
            installed = FLAGS.find(text)?.groupValues?.get(1)?.toIntOrNull() == 4,
            depots = DEPOT.findAll(text).count(),
            buildId = BUILD.find(text)?.groupValues?.get(1).orEmpty(),
            sizeOnDisk = SIZE.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L,
            manifestFile = manifest,
            gameDir = File(manifest.parentFile, "common/$installDir"),
            manifestText = text,
        )
    }

    /** Bytes and files under [dir]; one walk, no per-entry allocation beyond the directory list. */
    fun measure(dir: File): Measured {
        if (!dir.isDirectory) return Measured(0, 0)
        var bytes = 0L
        var files = 0
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val here = stack.removeLast()
            val children = here.listFiles() ?: continue
            for (child in children) {
                if (Files.isSymbolicLink(child.toPath())) continue
                if (child.isDirectory) stack.addLast(child)
                else {
                    bytes += child.length()
                    files++
                }
            }
        }
        return Measured(bytes, files)
    }

    /** The reasons [source] cannot be packaged as a kit Steam will recognise, worst first. */
    @JvmOverloads
    fun gates(source: Source, measured: Measured = measure(source.gameDir)): List<Gate> {
        val blocked = ArrayList<Gate>(4)
        if (!source.installed) blocked.add(Gate.NOT_INSTALLED)
        if (source.depots == 0) blocked.add(Gate.NO_DEPOT_RECORD)
        if (!source.gameDir.isDirectory || measured.files == 0) blocked.add(Gate.NO_FILES)
        if (blocked.isEmpty() && source.sizeOnDisk > 0 &&
            measured.bytes.toDouble() < source.sizeOnDisk * (1.0 - SHORT_TOLERANCE)) blocked.add(Gate.SHORT)
        return blocked
    }

    /** A folder Steam is happy with: the game's own name rather than "DroidDeck-2590300". */
    fun installDirFor(name: String, appId: Int): String =
        UNSAFE.replace(name, " ").replace(SPACES, " ").trim().trim('.').take(120).ifBlank { "game-$appId" }

    fun archiveName(source: Source): String {
        val title = UNSAFE.replace(source.name, "-").replace(SPACES, "-").trim('-').take(60)
        return "$title-${source.appId}.$EXTENSION"
    }

    /**
     * The kit's manifest: Steam's own text with only `installdir` following the exported folder, so
     * the depot record it wrote is carried over untouched.
     */
    internal fun manifestFor(source: Source, installDir: String): String =
        if (installDir == source.installDir) source.manifestText
        else DIR.replaceFirst(source.manifestText, "\"installdir\"\t\"${installDir.replace("\"", "")}\"")

    /** What the kit says about itself, for anyone reading the archive without this app. */
    internal fun info(kit: Kit, bytes: Long, files: Int): String =
        "{" + listOf(
            "\"format\": 1",
            "\"appid\": ${kit.appId}",
            "\"name\": ${json(kit.name)}",
            "\"installdir\": ${json(kit.installDir)}",
            "\"buildid\": ${json(kit.buildId)}",
            "\"depots\": ${kit.depots}",
            "\"sizeOnDisk\": ${kit.sizeOnDisk}",
            "\"bytes\": $bytes",
            "\"files\": $files",
        ).joinToString(", ") + "}"

    private fun json(value: String): String = buildString {
        append('"')
        for (c in value) when {
            c == '"' || c == '\\' -> append('\\').append(c)
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    /**
     * Package [source] into [destination]. The archive is written to a staging name and only takes
     * the destination once it is whole, so an interrupted export never leaves a kit that imports.
     */
    @JvmOverloads
    fun export(
        source: Source,
        destination: File,
        onProgress: ((Float) -> Unit)? = null,
        cancelled: () -> Boolean = { false },
    ): Result {
        val measured = measure(source.gameDir)
        val blocked = gates(source, measured)
        require(blocked.isEmpty()) { "game ${source.appId} cannot be packaged: $blocked" }
        val installDir = installDirFor(source.name, source.appId)
        val kit = Kit(source.appId, source.name, installDir, source.depots, source.buildId, source.sizeOnDisk)
        val archive = File(destination, archiveName(source))
        val staging = File(archive.path + ".part")
        staging.delete()
        archive.delete()
        val total = measured.bytes.coerceAtLeast(1L)
        var done = 0L
        var shown = -1
        fun report(bytes: Long) {
            done += bytes
            val percent = (done * 100 / total).toInt()
            if (percent != shown) {
                shown = percent
                onProgress?.invoke(percent / 100f)
            }
        }
        try {
            BufferedOutputStream(staging.outputStream()).use { raw ->
                codec.compress(raw).use { packed ->
                    TarArchiveOutputStream(packed.buffered(BUFFER)).use { tar ->
                        val manifest = manifestFor(source, installDir).toByteArray()
                        putBytes(tar, "steamapps/appmanifest_${source.appId}.acf", manifest)
                        putBytes(tar, KIT_INFO, info(kit, measured.bytes, measured.files).toByteArray())
                        putTree(tar, "$COMMON/$installDir", source.gameDir.canonicalFile, ::report, cancelled)
                        tar.flush()
                    }
                    packed.flush()
                }
                raw.flush()
            }
            if (!staging.renameTo(archive)) {
                staging.copyTo(archive, overwrite = true)
                staging.delete()
            }
        } catch (e: Throwable) {
            staging.delete()
            throw e
        }
        onProgress?.invoke(1f)
        return Result(kit, archive, measured)
    }

    private fun putTree(
        tar: TarArchiveOutputStream,
        prefix: String,
        root: File,
        report: (Long) -> Unit,
        cancelled: () -> Boolean,
    ) {
        put(tar, TarArchiveEntry(root.toPath(), prefix))
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val here = stack.removeLast()
            for (child in here.listFiles()?.sortedBy { it.name } ?: emptyList()) {
                if (cancelled()) throw InterruptedIOException("export cancelled")
                val name = "$prefix/${relative(here, child, root)}"
                // Built from the path itself, so the entry keeps the link, the directory flag and
                // the executable bit the game needs.
                val entry = TarArchiveEntry(child.toPath(), name)
                if (entry.isSymbolicLink) {
                    put(tar, entry)
                } else if (child.isDirectory) {
                    put(tar, entry)
                    stack.addLast(child)
                } else {
                    put(tar, entry) {
                        child.inputStream().use { input ->
                            val buffer = ByteArray(BUFFER)
                            var n = input.read(buffer)
                            while (n >= 0) {
                                tar.write(buffer, 0, n)
                                n = input.read(buffer)
                            }
                        }
                    }
                    report(entry.size)
                }
            }
        }
    }

    private fun relative(from: File, child: File, root: File): String {
        val base = from.relativeTo(root).path
        return if (base.isEmpty()) child.name else "$base/${child.name}"
    }

    private fun putBytes(tar: TarArchiveOutputStream, name: String, bytes: ByteArray) {
        val entry = TarArchiveEntry(name)
        entry.size = bytes.size.toLong()
        entry.mode = MODE_FILE
        put(tar, entry) { tar.write(bytes) }
    }

    private fun put(tar: TarArchiveOutputStream, entry: TarArchiveEntry, body: (() -> Unit)? = null) {
        // A fixed stamp keeps two exports of the same files comparable.
        entry.setModTime(0L)
        tar.putArchiveEntry(entry)
        body?.invoke()
        tar.closeArchiveEntry()
    }

    /** Read a kit's manifest without unpacking the archive. */
    fun inspect(archive: File): Kit? {
        val source = manifestIn(archive) ?: return null
        return Kit(source.appId, source.name, source.installDir, source.depots, source.buildId, source.sizeOnDisk)
    }

    /**
     * Unpack [archive] into [library] - the root of a Steam library, internal or on game storage.
     * The files land where the kit's manifest says, and that manifest is written last, so the
     * client never sees an install whose files are still arriving.
     */
    @JvmOverloads
    fun import(
        archive: File,
        library: File,
        onProgress: ((Float) -> Unit)? = null,
        cancelled: () -> Boolean = { false },
    ): Imported {
        val kit = requireNotNull(inspect(archive)) { "no manifest in ${archive.name}" }
        require(library.isDirectory || library.mkdirs()) { "cannot create $library" }
        val steamapps = File(library, "steamapps")
        val manifestName = "steamapps/appmanifest_${kit.appId}.acf"
        val manifestFile = File(steamapps, "appmanifest_${kit.appId}.acf")
        require(!manifestFile.exists()) { "${manifestFile.name} is already in ${library.name}" }
        val gameDir = File(steamapps, "common/${kit.installDir}")
        val expected = kit.sizeOnDisk.coerceAtLeast(1L)
        var written = 0L
        var files = 0
        var manifest: String? = null
        open(archive) { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                if (cancelled()) throw InterruptedIOException("import cancelled")
                if (entry.isFile && entry.name.equals(manifestName, ignoreCase = true)) {
                    manifest = tar.readBytes().toString(Charsets.UTF_8)
                } else if (entry.isFile) {
                    val target = ArchivePaths.inside(library, entry.name)
                    if (target == null) {
                        Log.w(TAG, "skipping ${entry.name}: it points outside the library")
                    } else {
                        target.parentFile?.mkdirs()
                        if (entry.isSymbolicLink) {
                            target.delete()
                            Files.createSymbolicLink(target.toPath(), File(entry.linkName).toPath())
                        } else if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.outputStream().use { out ->
                                val buffer = ByteArray(BUFFER)
                                var n = tar.read(buffer)
                                while (n >= 0) {
                                    out.write(buffer, 0, n)
                                    n = tar.read(buffer)
                                }
                            }
                            val executable = (entry.mode and MODE_OWNER_EXEC) != 0
                            target.setExecutable(executable, false)
                            target.setWritable(true, false)
                            files++
                            written += target.length()
                            onProgress?.invoke((written.toDouble() / expected).coerceIn(0.0, 1.0).toFloat())
                        }
                    }
                }
                entry = tar.nextEntry
            }
        }
        val text = requireNotNull(manifest) { "no manifest in ${archive.name}" }
        require(gameDir.isDirectory) { "the kit carries no common/${kit.installDir}/" }
        require(steamapps.isDirectory || steamapps.mkdirs()) { "cannot create $steamapps" }
        manifestFile.writeText(text)
        val measured = measure(gameDir)
        onProgress?.invoke(1f)
        return Imported(kit, library, gameDir, manifestFile, measured, (kit.sizeOnDisk - measured.bytes).coerceAtLeast(0L))
    }

    /** The libraries a kit can be unpacked into, the game storage first. */
    fun targets(context: Context): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        GameStorage.effective(context)?.let { out.add(it.label to File(it.path)) }
        out.add(context.getString(R.string.kit_library_internal) to
            File(LinuxRuntime.rootDir(context), "root/.local/share/Steam"))
        return out
    }

    /**
     * Ask a running session to bring the client back up so it reads the library just written: the
     * client reads manifests and shortcuts only at start.
     */
    fun requestPickup(context: Context): Boolean {
        if (!SessionState.running) return false
        val restart = File(LinuxRuntime.rootDir(context), "root/.bl-restart-once")
        val stop = File(LinuxRuntime.sessionRoot(context), "steam-stop")
        return runCatching {
            restart.parentFile?.mkdirs()
            restart.writeText("")
            stop.parentFile?.mkdirs()
            stop.writeText("")
            true
        }.getOrElse {
            Log.w(TAG, "could not ask the client to restart", it)
            false
        }
    }

    private fun manifestIn(archive: File): Source? = open(archive) { tar ->
        var entry = tar.nextEntry
        var found: Source? = null
        while (entry != null && found == null) {
            if (entry.isFile && entry.name.startsWith("steamapps/appmanifest_") && entry.name.endsWith(".acf"))
                found = parse(tar.readBytes().toString(Charsets.UTF_8), archive)
            entry = tar.nextEntry
        }
        found
    }

    /** How the tar is wrapped. Swapped for a pass-through where zstd's native library is absent. */
    internal interface Codec {
        fun compress(out: OutputStream): OutputStream
        fun decompress(input: InputStream): InputStream

        object Zstd : Codec {
            override fun compress(out: OutputStream) = ZstdOutputStream(out, ZSTD_LEVEL)
            override fun decompress(input: InputStream) = ZstdInputStream(input)
        }

        object None : Codec {
            override fun compress(out: OutputStream) = out
            override fun decompress(input: InputStream) = input
        }
    }

    @VisibleForTesting
    @Volatile
    internal var codec: Codec = Codec.Zstd

    private fun <T> open(archive: File, block: (TarArchiveInputStream) -> T): T =
        codec.decompress(BufferedInputStream(archive.inputStream(), BUFFER)).use { raw ->
            TarArchiveInputStream(raw.buffered(BUFFER)).use { tar -> block(tar) }
        }

    fun formatSize(bytes: Long): String = String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
}