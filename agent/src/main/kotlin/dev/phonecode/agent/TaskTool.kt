package dev.phonecode.agent

import dev.phonecode.tools.Tool
import dev.phonecode.tools.ToolContext
import dev.phonecode.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Launches a subagent to handle a focused subtask and returns its result (mirrors OpenCode `task`).
 * The heavy lifting - building and running a child [AgentLoop] - is supplied by [runner] so this tool
 * stays free of provider/loop wiring and is trivially testable. The runner is responsible for excluding
 * `task` from the child's toolset (no infinite recursion) and for inheriting the parent's mode.
 */
class TaskTool(
    private val runner: suspend (description: String, prompt: String, subagentType: String) -> String,
) : Tool {
    override val name = "task"
    override val description =
        "Launch a subagent to autonomously handle a focused, well-scoped subtask (e.g. research a " +
            "question, or carry out a contained change) and return its final result. The subagent has " +
            "your file tools but cannot launch further subagents. Prefer this for self-contained work " +
            "you can delegate; do simple steps yourself."
    override val promptSnippet = "delegate a focused subtask to a subagent and get back its result"
    override val mutating = false // the subagent gates its OWN mutating tools via permission
    override val sequential = true
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("description") { put("type", "string"); put("description", "3-5 word description of the subtask.") }
            putJsonObject("prompt") { put("type", "string"); put("description", "The full task for the subagent to perform.") }
            putJsonObject("subagent_type") { put("type", "string"); put("description", "Subagent kind (optional; default general).") }
        }
        put("required", buildJsonArray { add("description"); add("prompt") })
        put("additionalProperties", false)
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val prompt = (args["prompt"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return ToolResult("task: missing 'prompt'", isError = true)
        val description = (args["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "task"
        val subagentType = (args["subagent_type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "general"
        return runCatching {
            val output = runner(description, prompt, subagentType)
            ToolResult("<task description=\"$description\">\n${output.ifBlank { "(subagent produced no output)" }}\n</task>")
        }.getOrElse { ToolResult("task '$description' failed: ${it.message}", isError = true) }
    }
}
