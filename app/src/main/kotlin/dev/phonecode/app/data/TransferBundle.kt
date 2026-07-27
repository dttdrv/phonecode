package dev.phonecode.app.data

import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Export/import of all user data as a single ZIP bundle. Pure JVM (java.util.zip + kotlinx
 * serialization, no Android APIs) so it is fully unit-testable; the caller supplies the streams
 * (e.g. from SAF ContentResolver URIs) and the app's filesDir.
 *
 * Bundle layout (version 1): manifest.json, sessions/<id>.json, projects.json, model_prefs.json,
 * app_settings.json, config/providers.json. Import only restores entries matching this whitelist -
 * anything with path traversal ("..", absolute paths, backslashes) or an unknown name is skipped.
 */
object TransferBundle {

    @Serializable
    private data class Manifest(val app: String = "phonecode", val version: Int = 1, val exportedAt: Long)

    private val json = storeJson

    /** Fixed single-file entries; entry name doubles as the path relative to filesDir. */
    private val KNOWN_FILES = listOf("projects.json", "model_prefs.json", "app_settings.json", "config/providers.json")
    private val SESSION_ENTRY = Regex("sessions/[^/]+\\.json")

    private const val BUNDLE_VERSION = 1
    private const val MAX_ENTRY_BYTES = 5L * 1024 * 1024 // a single chat/settings file should never be this big
    private const val MAX_TOTAL_BYTES = 100L * 1024 * 1024 // hard stop against zip bombs / disk fill
    private const val ROLLBACK_DIR = ".import-rollback"
    private const val ROLLBACK_JOURNAL = "journal"
    private const val COMMIT_MARKER = "commit-complete"

    /** Zip every present data file under [filesDir] into [out], prefixed by a manifest entry. */
    fun export(filesDir: File, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            val manifest = Manifest(exportedAt = System.currentTimeMillis())
            zos.write(json.encodeToString(Manifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            val sessionFiles = File(filesDir, "sessions")
                .listFiles { f -> f.isFile && f.extension == "json" } ?: emptyArray()
            var totalBytes = 0L
            sessionFiles.sortedBy { it.name }.forEach {
                totalBytes = writeEntry(zos, "sessions/${it.name}", it, MAX_TOTAL_BYTES, totalBytes)
            }

            KNOWN_FILES.forEach { name ->
                val file = File(filesDir, name)
                if (file.isFile) totalBytes = writeEntry(zos, name, file, MAX_ENTRY_BYTES, totalBytes)
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
    fun import(filesDir: File, input: InputStream, afterCommit: () -> Unit = {}): Int {
        recoverInterruptedImport(filesDir)
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
                            if (manifestSeen) throw IOException("Backup contains more than one manifest.")
                            manifestSeen = true
                            val manifest = try {
                                json.decodeFromString(Manifest.serializer(), zis.readBounded(MAX_ENTRY_BYTES).toString(Charsets.UTF_8))
                            } catch (error: Exception) {
                                throw IOException("Backup manifest is invalid.", error)
                            }
                            if (manifest.app != "phonecode" || manifest.version < 1) {
                                throw IOException("Backup manifest is not supported.")
                            }
                            if (manifest.version > BUNDLE_VERSION) {
                                throw IOException("This backup was made by a newer version of PhoneCode (format v${manifest.version}).")
                            }
                        }
                        isAllowed(entry.name) -> {
                            val stage = File(stagingDir, staged.size.toString())
                            val entryLimit = if (SESSION_ENTRY.matches(entry.name)) MAX_TOTAL_BYTES else MAX_ENTRY_BYTES
                            stage.outputStream().use { output ->
                                val buffer = ByteArray(16 * 1024)
                                var entryBytes = 0L
                                while (true) {
                                    val count = zis.read(buffer)
                                    if (count < 0) break
                                    entryBytes += count
                                    totalBytes += count
                                    if (entryBytes > entryLimit) {
                                        throw IOException("Backup entry exceeds the ${entryLimit / (1024 * 1024)} MB per-file limit.")
                                    }
                                    if (totalBytes > MAX_TOTAL_BYTES) {
                                        throw IOException("Backup exceeds the ${MAX_TOTAL_BYTES / (1024 * 1024)} MB import limit.")
                                    }
                                    output.write(buffer, 0, count)
                                }
                            }
                            staged += entry.name to stage
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            if (!manifestSeen) throw IOException("Not a PhoneCode backup (missing manifest).")
            // Stream fully validated - commit the staged files into place. Sessions are a
            // replacement set, not a merge: omitted chats can reference projects that no longer
            // exist and disappear from the drawer. Move every affected current file into a
            // rollback area first so any commit failure restores the complete pre-import state.
            // Sessions are replaced as one directory so a callback-created/repaired session is
            // also covered by rollback. Settings and projects are always covered because restore
            // normalization updates them even when an older/minimal bundle omitted either file.
            val affectedFiles = (staged.asSequence().map { it.first }
                .filterNot(SESSION_ENTRY::matches) + sequenceOf("projects.json", "app_settings.json"))
                .distinct()
                .toList()
            val sessionsDir = File(filesDir, "sessions")
            val rollbackDir = File(filesDir, ROLLBACK_DIR).apply { mkdirs() }
            val previousRoot = File(rollbackDir, "previous")
            val journalLines = buildList {
                add("sessions\t${sessionsDir.exists()}")
                affectedFiles.forEach { name -> add("$name\t${File(filesDir, name).isFile}") }
            }
            writeDurably(File(rollbackDir, ROLLBACK_JOURNAL), journalLines.joinToString("\n"))
            var rollbackComplete = false
            try {
                if (sessionsDir.exists()) {
                    moveReplacing(sessionsDir, File(previousRoot, "sessions"))
                }
                sessionsDir.mkdirs()
                affectedFiles.forEach { name ->
                    val current = File(filesDir, name)
                    if (current.isFile) {
                        moveReplacing(current, File(previousRoot, name))
                    }
                }
                staged.forEach { (name, stage) ->
                    val target = File(filesDir, name)
                    target.parentFile?.mkdirs()
                    moveReplacing(stage, target)
                }
                afterCommit()
                writeDurably(File(rollbackDir, COMMIT_MARKER), "complete")
            } catch (commitError: Exception) {
                val rollbackError = runCatching { restoreRollback(filesDir, rollbackDir) }.exceptionOrNull()
                if (rollbackError != null) {
                    commitError.addSuppressed(rollbackError)
                    throw IOException("Import failed and the previous data could not be fully restored.", commitError)
                }
                rollbackComplete = true
                throw IOException("Import could not replace the current data; no changes were kept.", commitError)
            } finally {
                if (File(rollbackDir, COMMIT_MARKER).isFile || rollbackComplete) {
                    rollbackDir.deleteRecursively()
                }
            }
            return staged.size
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    /**
     * Completes recovery from a process death during import. A durable commit marker means the
     * new data won; otherwise the mapped previous tree is restored before any store is opened.
     */
    fun recoverInterruptedImport(filesDir: File) {
        val rollbackDir = File(filesDir, ROLLBACK_DIR)
        if (!rollbackDir.exists()) return
        if (File(rollbackDir, COMMIT_MARKER).isFile) {
            rollbackDir.deleteRecursively()
            return
        }
        restoreRollback(filesDir, rollbackDir)
        rollbackDir.deleteRecursively()
    }

    private fun restoreRollback(filesDir: File, rollbackDir: File) {
        val journal = File(rollbackDir, ROLLBACK_JOURNAL)
        if (!journal.isFile) throw IOException("Import recovery journal is missing.")
        val previousRoot = File(rollbackDir, "previous")
        journal.readLines().asReversed().forEach { line ->
            val split = line.split('\t', limit = 2)
            if (split.size != 2 || split[0] != "sessions" && !isAllowed(split[0])) {
                throw IOException("Import recovery journal is invalid.")
            }
            val name = split[0]
            val existed = split[1].toBooleanStrictOrNull()
                ?: throw IOException("Import recovery journal is invalid.")
            val target = File(filesDir, name)
            if (existed) {
                val backup = File(previousRoot, name)
                // The journal is synced before the first move. If the process died before this
                // particular move, the original is still at the target and there is nothing to
                // restore for this entry.
                if (!backup.exists()) {
                    if (target.exists()) return@forEach
                    throw IOException("Import recovery data for $name is missing.")
                }
                if (target.exists() && !target.deleteRecursively()) {
                    throw IOException("Could not remove partially imported $name.")
                }
                moveReplacing(backup, target)
            } else if (target.exists() && !target.deleteRecursively()) {
                throw IOException("Could not remove partially imported $name.")
            }
        }
    }

    private fun writeDurably(file: File, value: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output ->
            output.write(value.toByteArray(Charsets.UTF_8))
            output.fd.sync()
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

    private fun writeEntry(zos: ZipOutputStream, name: String, file: File, entryLimit: Long, previousTotal: Long): Long {
        if (file.length() > entryLimit) {
            throw IOException("$name exceeds the ${entryLimit / (1024 * 1024)} MB backup limit.")
        }
        val total = previousTotal + file.length()
        if (total > MAX_TOTAL_BYTES) throw IOException("Backup exceeds the ${MAX_TOTAL_BYTES / (1024 * 1024)} MB export limit.")
        zos.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zos) }
        zos.closeEntry()
        return total
    }

    private fun moveReplacing(source: File, target: File) {
        target.parentFile?.mkdirs()
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Whitelist check; rejects traversal ("..") , backslashes, and absolute paths outright. */
    private fun isAllowed(name: String): Boolean {
        if (name.contains("..") || name.contains('\\') || name.contains(':') || name.startsWith("/")) return false
        return name in KNOWN_FILES || SESSION_ENTRY.matches(name)
    }
}
