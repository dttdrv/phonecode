package dev.phonecode.tools.shell

import dev.phonecode.tools.Tool
import dev.phonecode.tools.ToolContext
import dev.phonecode.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class ShellTool(
    private val backend: ShellBackend,
) : Tool {
    override val name = "bash"
    override val description =
        "Run a shell command in the active workspace using the configured private command runtime. " +
            "Set background=true for any long-running command that must outlive the tool call."
    override val mutating = true

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("command") { put("type", "string"); put("description", "The shell command line to execute") }
            putJsonObject("timeout_s") { put("type", "integer"); put("description", "Wall-clock limit in seconds (default 60, max 1800)") }
            putJsonObject("background") { put("type", "boolean"); put("description", "Keep the command running and return a process session") }
        }
        put("required", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("command")) })
    }

    override val promptSnippet = "bash - run commands or start managed background processes in the workspace"
    override val promptGuidelines = listOf(
        "bash runs inside the configured private command runtime with only the active workspace mounted at /workspace.",
        "Do not download or install executable packages unless the environment explicitly permits it.",
        "Long operations: pass timeout_s (max 1800); the process is killed at the limit.",
        "Long-running commands: set background=true and do not append '&'; use the process tool for logs, stdin, and shutdown.",
        "Verify background work with its logs and a capability-specific check before reporting success.",
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val command = (args["command"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return ToolResult("bash: missing 'command'", isError = true)
        val background = (args["background"] as? JsonPrimitive)?.booleanOrNull == true
        if (background) {
            return backend.start(command, context.workspacePath)
        }
        val timeoutS = ((args["timeout_s"] as? JsonPrimitive)?.intOrNull ?: 60).coerceIn(1, 1800)
        return backend.execute(command, context.workspacePath, timeoutS)
    }
}
