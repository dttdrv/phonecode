package dev.phonecode.agent

import dev.phonecode.tools.Tool
import dev.phonecode.tools.ToolContext
import dev.phonecode.tools.ToolResult
import dev.phonecode.tools.UserOption
import dev.phonecode.tools.UserQuestion
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Ends PLAN mode (mirrors OpenCode's `plan_exit`). When the agent has finished planning it calls this
 * tool, which asks the user - through the same [ToolContext.askUser] primitive the `question` tool uses -
 * to approve switching to BUILD and implementing. On approval it invokes [onApproved] (the app flips the
 * session to BUILD; the loop picks that up next turn and unlocks the mutating tools) and instructs the
 * model to execute; on rejection it tells the model to keep refining. Plan-only, so it is hidden in BUILD.
 */
class PlanExitTool(private val onApproved: suspend () -> Boolean) : Tool {
    override val name = "plan_exit"
    override val description =
        "Call this once your plan is complete and you are ready to implement it. It asks the user to " +
            "approve switching from plan mode to build mode. Only available in plan mode."
    override val promptSnippet =
        "finish planning and ask the user to approve switching to build mode (then implement the plan)"
    override val mutating = false
    override val sequential = true
    override val planOnly = true
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
        put("additionalProperties", false)
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val answers = context.askUser(
            listOf(
                UserQuestion(
                    question = "Switch to build mode and start implementing this plan?",
                    header = "Plan",
                    options = listOf(
                        UserOption("Yes", "Approve the plan and begin implementing"),
                        UserOption("No", "Keep refining the plan first"),
                    ),
                ),
            ),
        )
        // Build is an authority boundary: only the exact structured option may cross it. The app marks
        // free-text replies as "Custom: …", so conditional or ambiguous prose can never impersonate Yes.
        val approved = answers.firstOrNull()?.answers == listOf("Yes")
        return if (approved) {
            if (onApproved()) {
                ToolResult("The plan was approved. You are now in build mode and may edit files - execute the plan.")
            } else {
                ToolResult(
                    "The plan was approved, but Build mode could not be saved. This chat remains in Plan mode; do not edit files.",
                    isError = true,
                )
            }
        } else {
            ToolResult("The user did not approve the plan. Keep refining it; do not edit files yet.")
        }
    }
}
