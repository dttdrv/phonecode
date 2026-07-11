package dev.phonecode.tools.shell

import dev.phonecode.tools.Tool
import dev.phonecode.tools.ToolContext
import dev.phonecode.tools.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class ProcessTool(
    private val manager: ProcessManager,
) : Tool {
    override val name = "process"
    override val description = "List, inspect, send input to, or stop managed background commands started by bash."
    override val mutating = true
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("enum", buildJsonArray { listOf("list", "output", "input", "stop").forEach { add(JsonPrimitive(it)) } })
            }
            putJsonObject("session_id") { put("type", "string") }
            putJsonObject("data") { put("type", "string") }
            putJsonObject("append_newline") { put("type", "boolean") }
        }
        put("required", JsonArray(listOf(JsonPrimitive("action"))))
    }
    override val promptSnippet = "process - inspect, interact with, or stop managed background commands"
    override val promptGuidelines = listOf(
        "Use action=output to inspect startup and later logs; use action=input when a running command expects stdin.",
        "Stop managed processes when they are no longer needed.",
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val action = (args["action"] as? JsonPrimitive)?.contentOrNull
            ?: return ToolResult("process: missing 'action'", true)
        val id = (args["session_id"] as? JsonPrimitive)?.contentOrNull
        return when (action) {
            "list" -> manager.list()
            "output" -> id?.let(manager::output) ?: ToolResult("process: output requires 'session_id'", true)
            "input" -> when {
                id == null -> ToolResult("process: input requires 'session_id'", true)
                args["data"] !is JsonPrimitive -> ToolResult("process: input requires 'data'", true)
                else -> manager.input(
                    id,
                    (args["data"] as JsonPrimitive).content,
                    (args["append_newline"] as? JsonPrimitive)?.booleanOrNull ?: true,
                )
            }
            "stop" -> id?.let { manager.stop(it) } ?: ToolResult("process: stop requires 'session_id'", true)
            else -> ToolResult("process: unsupported action '$action'", true)
        }
    }
}
