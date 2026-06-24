package dev.phonecode.tools.external

import dev.phonecode.tools.Tool
import dev.phonecode.tools.ToolContext
import dev.phonecode.tools.ToolResult
import dev.phonecode.tools.files.int
import dev.phonecode.tools.files.intSchema
import dev.phonecode.tools.files.objectSchema
import dev.phonecode.tools.files.str
import dev.phonecode.tools.files.strSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Reads a file or lists a directory at an absolute path OUTSIDE the workspace (mirrors OpenCode
 * external-directory). Because it deliberately escapes workspace confinement, it self-gates: every call
 * asks [ToolContext.requestPermission] with the absolute path and only proceeds when the user approves.
 *
 * It is a read (mutating=false, so usable in PLAN), but the permission prompt - not the mode - is what
 * guards it. Output is capped so a huge file or directory can't blow up the context window.
 */
class ExternalDirectoryTool : Tool {
    override val name = "external_directory"
    override val description =
        "Read a file or list a directory at an ABSOLUTE path outside the workspace. Requires an absolute " +
            "path and asks the user for permission on every call. Use only when the user points you at a " +
            "specific external file or folder; prefer the workspace file tools otherwise."
    override val promptSnippet = "read a file or list a directory outside the workspace (asks permission)"
    override val parameters: JsonObject = objectSchema(
        mapOf(
            "path" to strSchema("Absolute path to a file or directory outside the workspace"),
            "maxBytes" to intSchema("Maximum bytes of output to return (optional; capped at $HARD_CAP)"),
        ),
        required = listOf("path"),
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult("external_directory: missing 'path'", true)
        val file = File(path)
        if (!file.isAbsolute) {
            return ToolResult("external_directory: 'path' must be absolute (got: $path)", true)
        }
        val cap = (args.int("maxBytes") ?: HARD_CAP).coerceIn(1, HARD_CAP)

        // Operate on exactly the path we prompt with (no canonicalization) so the approved string is the
        // path actually read - the user can't approve one string and have a different target resolved.
        val target = file.absoluteFile

        // Self-gate: this tool escapes workspace confinement, so it cannot proceed without explicit approval.
        if (!context.requestPermission(name, target.path)) {
            return ToolResult("external_directory: permission denied for ${target.path}", true)
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                when {
                    !target.exists() -> ToolResult("external_directory: not found: ${target.path}", true)
                    target.isDirectory -> listDirectory(target, cap)
                    else -> readFile(target, cap)
                }
            }.getOrElse { ToolResult("external_directory: ${it.message}", true) }
        }
    }

    private fun listDirectory(dir: File, cap: Int): ToolResult {
        val entries = dir.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            ?.joinToString("\n") { if (it.isDirectory) "${it.name}/" else it.name }
            ?: return ToolResult("external_directory: cannot list ${dir.path}", true)
        return ToolResult(capBytes(entries.ifEmpty { "(empty directory)" }, cap))
    }

    private fun readFile(file: File, cap: Int): ToolResult {
        val bytes = file.readBytes()
        val text = String(bytes.copyOf(minOf(bytes.size, cap)), Charsets.UTF_8)
        val truncated = if (bytes.size > cap) "\n[Truncated at $cap bytes of ${bytes.size}.]" else ""
        return ToolResult(text + truncated)
    }

    private fun capBytes(text: String, cap: Int): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= cap) return text
        return String(bytes.copyOf(cap), Charsets.UTF_8) + "\n[Truncated at $cap bytes of ${bytes.size}.]"
    }

    private companion object {
        const val HARD_CAP = 1_000_000
    }
}
