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
class PlanExitTool(private val onApproved: () -> Unit) : Tool {
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
        // Accept the "Yes" option or any free-text affirmation that starts with "yes" (the dialog invites custom text).
        val approved = answers.firstOrNull()?.answers?.any { it.trim().startsWith("Yes", ignoreCase = true) } == true
        return if (approved) {
            onApproved()
            ToolResult("The plan was approved. You are now in build mode and may edit files - execute the plan.")
        } else {
            ToolResult("The user did not approve the plan. Keep refining it; do not edit files yet.")
        }
    }
}
