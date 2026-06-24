package dev.phonecode.tools.patch

import dev.phonecode.tools.Tool
import dev.phonecode.tools.ToolContext
import dev.phonecode.tools.ToolResult
import dev.phonecode.tools.files.objectSchema
import dev.phonecode.tools.files.resolveInWorkspace
import dev.phonecode.tools.files.str
import dev.phonecode.tools.files.strSchema
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException

/**
 * Applies a V4A/OpenCode-style patch envelope describing add/update/delete file operations, all confined
 * to the workspace. The patch is parsed and fully validated (paths resolve, files exist or don't, every
 * update hunk's context and removals match) before anything is written; if any op fails, no file changes.
 *
 * Envelope:
 * ```
 * *** Begin Patch
 * *** Add File: path
 * +new line
 * *** Update File: path
 * @@
 *  context
 * -removed
 * +added
 * *** Delete File: path
 * *** End Patch
 * ```
 */
class ApplyPatchTool : Tool {
    override val name = "apply_patch"
    override val description =
        "Apply a patch describing file add/update/delete operations within the workspace. The patch uses " +
            "*** Begin Patch / *** End Patch with *** Add File:, *** Update File: (one or more @@ hunks of " +
            "context, - removals, + additions), and *** Delete File: sections. All operations are applied " +
            "atomically: if any part fails to match, no file is changed."
    override val mutating = true
    override val sequential = true
    override val promptSnippet = "apply a multi-file patch (add/update/delete) atomically"
    override val parameters: JsonObject = objectSchema(
        mapOf("patch" to strSchema("The patch text, wrapped in *** Begin Patch / *** End Patch")),
        required = listOf("patch"),
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val patchText = args.str("patch") ?: return ToolResult("apply_patch: missing 'patch'", isError = true)

        val ops = try {
            parsePatch(patchText)
        } catch (e: PatchException) {
            return ToolResult("apply_patch: ${e.message}", isError = true)
        }
        if (ops.isEmpty()) return ToolResult("apply_patch: patch contains no file operations", isError = true)

        // Phase 1: validate everything. Resolve paths, check existence, and compute the new content of every
        // updated file (which is where context/old-text mismatches surface) without touching the filesystem.
        val planned = mutableListOf<PlannedOp>()
        for (op in ops) {
            val file = try {
                resolveInWorkspace(context.workspacePath, op.path)
            } catch (e: IllegalArgumentException) {
                return ToolResult("apply_patch: ${e.message}", isError = true)
            }
            when (op) {
                is FileOp.Add -> {
                    if (file.exists()) return abort("Add File: ${op.path} already exists")
                    planned += PlannedOp.Write(op.path, file, op.content)
                }
                is FileOp.Delete -> {
                    if (!file.exists()) return abort("Delete File: ${op.path} does not exist")
                    if (file.isDirectory) return abort("Delete File: ${op.path} is a directory")
                    planned += PlannedOp.Delete(op.path, file)
                }
                is FileOp.Update -> {
                    if (!file.exists() || file.isDirectory) return abort("Update File: ${op.path} does not exist")
                    val original = file.readText()
                    val updated = applyHunks(original, op.hunks)
                        ?: return abort("Update File: ${op.path} - context did not match the file")
                    planned += PlannedOp.Write(op.path, file, updated)
                }
            }
        }

        // Phase 2: commit. Snapshot prior state first so a mid-commit failure rolls back to all-or-nothing.
        val snapshots = planned.map { p ->
            val existed = p.file.exists()
            Snapshot(p.file, existed, if (existed && p.file.isFile) p.file.readText() else null)
        }
        val applied = ArrayDeque<Int>()
        try {
            planned.forEachIndexed { index, p ->
                when (p) {
                    is PlannedOp.Write -> {
                        p.file.parentFile?.mkdirs()
                        p.file.writeText(p.content)
                    }
                    is PlannedOp.Delete -> if (!p.file.delete()) throw IOException("failed to delete ${p.path}")
                }
                applied.addLast(index)
            }
        } catch (t: Throwable) {
            // Undo whatever was applied, newest first, restoring prior content/existence.
            while (applied.isNotEmpty()) {
                val snap = snapshots[applied.removeLast()]
                runCatching { if (snap.existed) snap.priorContent?.let { snap.file.writeText(it) } else snap.file.delete() }
            }
            return ToolResult("apply_patch: commit failed (${t.message}); changes rolled back", isError = true)
        }
        val added = planned.indices.count { planned[it] is PlannedOp.Write && !snapshots[it].existed }
        val updated = planned.indices.count { planned[it] is PlannedOp.Write && snapshots[it].existed }
        val deleted = planned.count { it is PlannedOp.Delete }
        return ToolResult("apply_patch: $added added, $updated updated, $deleted deleted")
    }

    private fun abort(message: String): ToolResult = ToolResult("apply_patch: $message", isError = true)

    /**
     * Applies an update's hunks to [original], returning the new content, or null if any hunk's context/
     * removal lines fail to match. Hunks are matched in order, each searched from where the previous ended,
     * so repeated context can't be matched out of sequence.
     */
    private fun applyHunks(original: String, hunks: List<Hunk>): String? {
        // Preserve a trailing newline by tracking it separately from the line list.
        val endsWithNewline = original.endsWith("\n")
        val originalLines = if (original.isEmpty()) emptyList() else original.removeSuffix("\n").split("\n")

        val result = mutableListOf<String>()
        var cursor = 0
        for (hunk in hunks) {
            val match = findHunk(originalLines, hunk, cursor) ?: return null
            // Carry over untouched lines between the cursor and this hunk's match.
            result += originalLines.subList(cursor, match)
            var i = match
            for (line in hunk.lines) {
                when (line.kind) {
                    LineKind.CONTEXT -> { result += originalLines[i]; i++ }
                    LineKind.REMOVE -> { i++ } // verified to match in findHunk; drop it
                    LineKind.ADD -> { result += line.text }
                }
            }
            cursor = i
        }
        result += originalLines.subList(cursor, originalLines.size)

        val joined = result.joinToString("\n")
        return if (endsWithNewline && result.isNotEmpty()) joined + "\n" else joined
    }

    /** Returns the index in [lines] at or after [from] where [hunk]'s context+removal lines match, or null. */
    private fun findHunk(lines: List<String>, hunk: Hunk, from: Int): Int? {
        val expected = hunk.lines.filter { it.kind != LineKind.ADD }.map { it.text }
        if (expected.isEmpty()) return from // pure addition: anchor at the cursor
        val last = lines.size - expected.size
        for (start in from..last) {
            var ok = true
            for (k in expected.indices) {
                if (lines[start + k] != expected[k]) { ok = false; break }
            }
            if (ok) return start
        }
        return null
    }

    // --- Parsing ---

    private fun parsePatch(text: String): List<FileOp> {
        val lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        var i = 0
        // Skip leading blank lines before the envelope.
        while (i < lines.size && lines[i].isBlank()) i++
        if (i >= lines.size || lines[i].trim() != BEGIN) {
            throw PatchException("patch must start with '$BEGIN'")
        }
        i++

        val ops = mutableListOf<FileOp>()
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trim() == END -> return ops
                line.startsWith(ADD) -> {
                    val path = line.removePrefix(ADD).trim()
                    if (path.isEmpty()) throw PatchException("'$ADD' is missing a path")
                    i++
                    val content = StringBuilder()
                    var first = true
                    while (i < lines.size && lines[i].isContentBody()) {
                        if (!lines[i].startsWith("+")) {
                            throw PatchException("Add File: $path - content lines must start with '+'")
                        }
                        if (!first) content.append("\n")
                        content.append(lines[i].substring(1))
                        first = false
                        i++
                    }
                    // Added files carry a trailing newline (the common convention for text files).
                    val body = if (content.isEmpty()) "" else content.toString() + "\n"
                    ops += FileOp.Add(path, body)
                }
                line.startsWith(DELETE) -> {
                    val path = line.removePrefix(DELETE).trim()
                    if (path.isEmpty()) throw PatchException("'$DELETE' is missing a path")
                    ops += FileOp.Delete(path)
                    i++
                }
                line.startsWith(UPDATE) -> {
                    val path = line.removePrefix(UPDATE).trim()
                    if (path.isEmpty()) throw PatchException("'$UPDATE' is missing a path")
                    i++
                    val hunks = mutableListOf<Hunk>()
                    if (i >= lines.size || !lines[i].startsWith("@@")) {
                        throw PatchException("Update File: $path - expected at least one '@@' hunk")
                    }
                    while (i < lines.size && lines[i].startsWith("@@")) {
                        i++ // consume the @@ marker
                        val hunkLines = mutableListOf<PatchLine>()
                        while (i < lines.size && lines[i].isHunkBody()) {
                            val l = lines[i]
                            val pl = when (l.firstOrNull()) {
                                '+' -> PatchLine(LineKind.ADD, l.substring(1))
                                '-' -> PatchLine(LineKind.REMOVE, l.substring(1))
                                ' ' -> PatchLine(LineKind.CONTEXT, l.substring(1))
                                else -> PatchLine(LineKind.CONTEXT, "") // a bare blank line is empty context
                            }
                            hunkLines += pl
                            i++
                        }
                        if (hunkLines.none { it.kind != LineKind.CONTEXT }) {
                            throw PatchException("Update File: $path - a hunk has no +/- changes")
                        }
                        hunks += Hunk(hunkLines)
                    }
                    ops += FileOp.Update(path, hunks)
                }
                line.isBlank() -> i++ // tolerate blank separators between sections
                else -> throw PatchException("unexpected line outside a section: '${line.take(60)}'")
            }
        }
        throw PatchException("patch is missing '$END'")
    }

    /** A line belongs to an Add File body until the next section marker or end-of-patch. */
    private fun String.isContentBody(): Boolean = !isSectionBoundary()

    /** A line belongs to a hunk body until the next hunk, section marker, or end-of-patch. */
    private fun String.isHunkBody(): Boolean = !startsWith("@@") && !isSectionBoundary()

    private fun String.isSectionBoundary(): Boolean =
        startsWith(ADD) || startsWith(DELETE) || startsWith(UPDATE) || trim() == END || trim() == BEGIN

    private class PatchException(message: String) : Exception(message)

    private enum class LineKind { CONTEXT, ADD, REMOVE }
    private data class PatchLine(val kind: LineKind, val text: String)
    private data class Hunk(val lines: List<PatchLine>)

    private sealed interface FileOp {
        val path: String
        data class Add(override val path: String, val content: String) : FileOp
        data class Delete(override val path: String) : FileOp
        data class Update(override val path: String, val hunks: List<Hunk>) : FileOp
    }

    private sealed interface PlannedOp {
        val path: String
        val file: File
        data class Write(override val path: String, override val file: File, val content: String) : PlannedOp
        data class Delete(override val path: String, override val file: File) : PlannedOp
    }

    private data class Snapshot(val file: File, val existed: Boolean, val priorContent: String?)

    private companion object {
        const val BEGIN = "*** Begin Patch"
        const val END = "*** End Patch"
        const val ADD = "*** Add File:"
        const val DELETE = "*** Delete File:"
        const val UPDATE = "*** Update File:"
    }
}
