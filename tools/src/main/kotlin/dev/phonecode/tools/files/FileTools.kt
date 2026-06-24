package dev.phonecode.tools.files

import dev.phonecode.tools.Tool
import dev.phonecode.tools.ToolContext
import dev.phonecode.tools.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files

/** The native, zero-setup file tools available on any device. */
fun defaultFileTools(): List<Tool> = listOf(ReadTool(), WriteTool(), EditTool(), LsTool(), GlobTool(), GrepTool())

private const val MAX_RESULTS = 200
private const val MAX_FILE_BYTES = 5_000_000L
private val json = Json { ignoreUnknownKeys = true; isLenient = true }

private fun resolve(context: ToolContext, path: String): Result<File> =
    runCatching { resolveInWorkspace(context.workspacePath, path) }

class ReadTool : Tool {
    override val name = "read"
    override val description =
        "Read a UTF-8 text file from the workspace. Returns numbered lines; use offset/limit for large files."
    override val promptSnippet = "read a file's contents (prefer this over shell cat/head/tail)"
    override val parameters = objectSchema(
        mapOf(
            "path" to strSchema("File path relative to the workspace"),
            "offset" to intSchema("1-based line to start at (optional)"),
            "limit" to intSchema("maximum lines to return (optional)"),
        ),
        required = listOf("path"),
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult("read: missing 'path'", isError = true)
        val file = resolve(context, path).getOrElse { return ToolResult(it.message ?: "read: bad path", true) }
        if (!file.exists()) return ToolResult("read: file not found: $path", true)
        if (file.isDirectory) return ToolResult("read: $path is a directory", true)
        if (file.length() > MAX_FILE_BYTES) {
            return ToolResult("read: file too large (${file.length()} bytes); narrow with offset/limit or use grep", true)
        }
        val lines = file.readLines()
        val offset = (args.int("offset") ?: 1).coerceAtLeast(1)
        val limit = (args.int("limit") ?: DEFAULT_LIMIT).coerceAtLeast(1)
        val start = offset - 1
        if (lines.isNotEmpty() && start >= lines.size) {
            return ToolResult("read: offset $offset is past end of file (${lines.size} lines)", true)
        }
        val slice = lines.drop(start).take(limit)
        val body = slice.mapIndexed { i, line -> "${start + i + 1}\t$line" }.joinToString("\n")
        val end = start + slice.size
        val more = if (end < lines.size) "\n[Showing lines $offset-$end of ${lines.size}. Use offset=${end + 1} to continue.]" else ""
        return ToolResult(if (slice.isEmpty()) "(empty file)" else body + more)
    }

    private companion object { const val DEFAULT_LIMIT = 2000 }
}

class WriteTool : Tool {
    override val name = "write"
    override val description = "Create or overwrite a UTF-8 text file in the workspace."
    override val mutating = true
    override val promptSnippet = "create or overwrite a file (prefer editing an existing file when possible)"
    override val parameters = objectSchema(
        mapOf("path" to strSchema("File path relative to the workspace"), "content" to strSchema("Full file content")),
        required = listOf("path", "content"),
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult("write: missing 'path'", true)
        val content = args.str("content") ?: return ToolResult("write: missing 'content'", true)
        val file = resolve(context, path).getOrElse { return ToolResult(it.message ?: "write: bad path", true) }
        if (file.isDirectory) return ToolResult("write: $path is a directory", true)
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(content)
            ToolResult("wrote ${content.toByteArray(Charsets.UTF_8).size} bytes to $path")
        }.getOrElse { ToolResult("write: ${it.message}", true) }
    }
}

class EditTool : Tool {
    override val name = "edit"
    override val description =
        "Replace exact text in a file. Each oldText must appear exactly once and must not overlap another edit in the same call."
    override val mutating = true
    override val promptSnippet = "make exact-text edits to a file (prefer this over rewriting the whole file)"
    override val parameters = objectSchema(
        mapOf(
            "path" to strSchema("File path relative to the workspace"),
            "edits" to arraySchema(
                items = objectSchema(
                    mapOf(
                        "oldText" to strSchema("exact text to find (must be unique in the file)"),
                        "newText" to strSchema("replacement text"),
                    ),
                    required = listOf("oldText", "newText"),
                ),
                description = "List of {oldText,newText} replacements applied in order",
            ),
        ),
        required = listOf("path", "edits"),
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult("edit: missing 'path'", true)
        val file = resolve(context, path).getOrElse { return ToolResult(it.message ?: "edit: bad path", true) }
        if (!file.exists() || file.isDirectory) return ToolResult("edit: file not found: $path", true)
        val edits = parseEdits(args) ?: return ToolResult("edit: missing or invalid 'edits'", true)
        if (edits.isEmpty()) return ToolResult("edit: no edits provided", true)

        var content = file.readText()
        for ((index, edit) in edits.withIndex()) {
            val (oldText, newText) = edit
            if (oldText.isEmpty()) return ToolResult("edit: edit #${index + 1} has an empty oldText", true)
            val occurrences = content.split(oldText).size - 1
            when {
                occurrences == 0 -> return ToolResult("edit: oldText not found: ${oldText.take(60)}", true)
                occurrences > 1 -> return ToolResult("edit: oldText is not unique ($occurrences matches): ${oldText.take(60)}", true)
            }
            content = content.replaceFirst(oldText, newText)
        }
        file.writeText(content)
        return ToolResult("applied ${edits.size} edit(s) to $path")
    }

    /** Auto-repairs the common model mistakes: edits sent as a JSON string, or a single top-level {oldText,newText}. */
    private fun parseEdits(args: JsonObject): List<Pair<String, String>>? {
        args.arr("edits")?.let { array ->
            return array.mapNotNull { element -> (element as? JsonObject)?.toEdit() }
        }
        args.str("edits")?.let { encoded ->
            return runCatching {
                json.parseToJsonElement(encoded).jsonArray.mapNotNull { (it as? JsonObject)?.toEdit() }
            }.getOrNull()
        }
        val oldText = args.str("oldText")
        val newText = args.str("newText")
        return if (oldText != null && newText != null) listOf(oldText to newText) else null
    }

    private fun JsonObject.toEdit(): Pair<String, String>? {
        val oldText = str("oldText") ?: return null
        val newText = str("newText") ?: return null
        return oldText to newText
    }
}

class LsTool : Tool {
    override val name = "ls"
    override val description = "List the entries of a directory in the workspace (directories end with '/')."
    override val promptSnippet = "list a directory's entries"
    override val parameters = objectSchema(
        mapOf("path" to strSchema("Directory path (default: workspace root)")),
        required = emptyList(),
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val path = args.str("path") ?: "."
        val dir = resolve(context, path).getOrElse { return ToolResult(it.message ?: "ls: bad path", true) }
        if (!dir.exists()) return ToolResult("ls: not found: $path", true)
        if (!dir.isDirectory) return ToolResult("ls: $path is not a directory", true)
        val entries = dir.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            ?.joinToString("\n") { if (it.isDirectory) "${it.name}/" else it.name }
            ?: ""
        return ToolResult(entries.ifEmpty { "(empty directory)" })
    }
}

class GlobTool : Tool {
    override val name = "glob"
    override val description = "Find files matching a glob pattern (e.g. **/*.kt), relative to the workspace."
    override val promptSnippet = "find files by glob pattern (prefer this over shell find)"
    override val parameters = objectSchema(
        mapOf("pattern" to strSchema("Glob, e.g. **/*.kt"), "path" to strSchema("Base directory (default: workspace root)")),
        required = listOf("pattern"),
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val pattern = args.str("pattern") ?: return ToolResult("glob: missing 'pattern'", true)
        val base = resolve(context, args.str("path") ?: ".").getOrElse { return ToolResult(it.message ?: "glob: bad path", true) }
        if (!base.isDirectory) return ToolResult("glob: not a directory: ${args.str("path")}", true)
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
        val basePath = base.toPath()
        val matches = mutableListOf<String>()
        Files.walk(basePath).use { stream ->
            for (p in stream) {
                if (Files.isRegularFile(p) && matcher.matches(basePath.relativize(p))) {
                    matches += basePath.relativize(p).toString().replace('\\', '/')
                    if (matches.size >= MAX_RESULTS) break
                }
            }
        }
        return ToolResult(if (matches.isEmpty()) "(no matches)" else matches.sorted().joinToString("\n"))
    }
}

class GrepTool : Tool {
    override val name = "grep"
    override val description = "Search file contents with a regular expression; returns file:line: matches."
    override val promptSnippet = "search file contents by regex (prefer this over shell grep)"
    override val parameters = objectSchema(
        mapOf(
            "pattern" to strSchema("Regular expression"),
            "path" to strSchema("Base directory (default: workspace root)"),
            "glob" to strSchema("Optional file glob to filter, e.g. *.kt"),
        ),
        required = listOf("pattern"),
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val pattern = args.str("pattern") ?: return ToolResult("grep: missing 'pattern'", true)
        val regex = runCatching { Regex(pattern) }.getOrElse { return ToolResult("grep: invalid regex: ${it.message}", true) }
        val base = resolve(context, args.str("path") ?: ".").getOrElse { return ToolResult(it.message ?: "grep: bad path", true) }
        if (!base.isDirectory) return ToolResult("grep: not a directory: ${args.str("path")}", true)
        val fileGlob = args.str("glob")?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }
        val basePath = base.toPath()
        val hits = mutableListOf<String>()
        Files.walk(basePath).use { stream ->
            outer@ for (p in stream) {
                if (!Files.isRegularFile(p)) continue
                if (Files.size(p) > MAX_FILE_BYTES) continue
                if (fileGlob != null && !fileGlob.matches(basePath.relativize(p))) continue
                val rel = basePath.relativize(p).toString().replace('\\', '/')
                val lines = runCatching { Files.readAllLines(p) }.getOrNull() ?: continue
                for (i in lines.indices) {
                    if (regex.containsMatchIn(lines[i])) {
                        hits += "$rel:${i + 1}: ${lines[i].trim().take(200)}"
                        if (hits.size >= MAX_RESULTS) break@outer
                    }
                }
            }
        }
        return ToolResult(if (hits.isEmpty()) "(no matches)" else hits.joinToString("\n"))
    }
}
