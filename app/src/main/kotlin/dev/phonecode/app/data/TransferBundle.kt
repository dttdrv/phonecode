package dev.phonecode.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Export/import of all user data as a single ZIP bundle. Pure JVM (java.util.zip + kotlinx
 * serialization, no Android APIs) so it is fully unit-testable; the caller supplies the streams
 * (e.g. from SAF ContentResolver URIs) and the app's filesDir.
 *
 * Bundle layout (version 1): manifest.json, sessions/<id>.json, projects.json, model_prefs.json,
 * config/providers.json. Import only restores entries matching this whitelist - anything with
 * path traversal ("..", absolute paths, backslashes) or an unknown name is skipped.
 */
object TransferBundle {

    @Serializable
    private data class Manifest(val app: String = "phonecode", val version: Int = 1, val exportedAt: Long)

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** Fixed single-file entries; entry name doubles as the path relative to filesDir. */
    private val KNOWN_FILES = listOf("projects.json", "model_prefs.json", "app_settings.json", "config/providers.json")
    private val SESSION_ENTRY = Regex("sessions/[^/]+\\.json")

    private const val BUNDLE_VERSION = 1
    private const val MAX_ENTRY_BYTES = 5L * 1024 * 1024 // a single chat/settings file should never be this big
    private const val MAX_TOTAL_BYTES = 100L * 1024 * 1024 // hard stop against zip bombs / disk fill

    /** Zip every present data file under [filesDir] into [out], prefixed by a manifest entry. */
    fun export(filesDir: File, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            val manifest = Manifest(exportedAt = System.currentTimeMillis())
            zos.write(json.encodeToString(Manifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            val sessionFiles = File(filesDir, "sessions")
                .listFiles { f -> f.isFile && f.extension == "json" } ?: emptyArray()
            sessionFiles.sortedBy { it.name }.forEach { writeEntry(zos, "sessions/${it.name}", it) }

            KNOWN_FILES.forEach { name ->
                val file = File(filesDir, name)
                if (file.isFile) writeEntry(zos, name, file)
            }
        }
    }

    /**
     * Restore a previously exported bundle from [input] into [filesDir], overwriting existing
     * files. Unknown or unsafe entries are skipped; oversized entries/totals and bundles from a
     * newer format version fail loudly. Returns the number of files restored.
     *
     * Entries are STAGED to a temp directory and only moved into place after the whole stream -
     * including the manifest, wherever it appears - has validated. A hostile/newer bundle that
     * orders data before its manifest can therefore never half-overwrite real data (review #2).
     * Scope note: the commit phase itself is not transactional - a mid-commit I/O failure (disk
     * full) can leave a mix of old/new files. Validation failures never write; I/O failures are
     * surfaced to the user as a failed import.
     */
    fun import(filesDir: File, input: InputStream): Int {
        var totalBytes = 0L
        var manifestSeen = false
        val staged = mutableListOf<Pair<String, File>>()
        val stagingDir = File(filesDir, ".import-staging").apply { deleteRecursively(); mkdirs() }
        try {
            ZipInputStream(input.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    when {
                        entry.isDirectory -> Unit
                        entry.name == "manifest.json" -> {
                            manifestSeen = true
                            val manifest = runCatching {
                                json.decodeFromString(Manifest.serializer(), zis.readBounded(MAX_ENTRY_BYTES).toString(Charsets.UTF_8))
                            }.getOrNull()
                            if (manifest != null && manifest.version > BUNDLE_VERSION) {
                                throw IOException("This backup was made by a newer version of PhoneCode (format v${manifest.version}).")
                            }
                        }
                        isAllowed(entry.name) -> {
                            val bytes = zis.readBounded(MAX_ENTRY_BYTES)
                            totalBytes += bytes.size
                            if (totalBytes > MAX_TOTAL_BYTES) throw IOException("Backup exceeds the ${MAX_TOTAL_BYTES / (1024 * 1024)} MB import limit.")
                            val stage = File(stagingDir, staged.size.toString())
                            stage.writeBytes(bytes)
                            staged += entry.name to stage
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            if (!manifestSeen) throw IOException("Not a PhoneCode backup (missing manifest).")
            // Stream fully validated - commit the staged files into place.
            staged.forEach { (name, stage) ->
                val target = File(filesDir, name)
                target.parentFile?.mkdirs()
                stage.copyTo(target, overwrite = true)
            }
            return staged.size
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    /** Read at most [limit] bytes from the current zip entry; an entry that exceeds it fails loudly. */
    private fun ZipInputStream.readBounded(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw IOException("Backup entry exceeds the ${limit / (1024 * 1024)} MB per-file limit.")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun writeEntry(zos: ZipOutputStream, name: String, file: File) {
        zos.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zos) }
        zos.closeEntry()
    }

    /** Whitelist check; rejects traversal ("..") , backslashes, and absolute paths outright. */
    private fun isAllowed(name: String): Boolean {
        if (name.contains("..") || name.contains('\\') || name.contains(':') || name.startsWith("/")) return false
        return name in KNOWN_FILES || SESSION_ENTRY.matches(name)
    }
}
