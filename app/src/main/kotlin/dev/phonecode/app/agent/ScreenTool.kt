package dev.phonecode.app.agent

import android.app.Application
import android.content.Intent
import android.provider.Settings
import dev.phonecode.tools.Tool
import dev.phonecode.tools.ToolContext
import dev.phonecode.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Screen navigation (round-3 feedback). Backed by [NavAccessibilityService], which the USER must
 * enable once in system Settings > Accessibility - the agent cannot grant itself this power.
 * mutating = true: every single action passes the user permission dialog, named precisely
 * ("screen: tap 'Sign in'"), so navigation is always visible and consented.
 */
class ScreenTool(private val app: Application) : Tool {
    override val name = "screen"
    override val description =
        "Navigate the phone screen (requires the PhoneCode accessibility service, user-enabled). " +
            "Actions: read (visible view tree with tap targets), tap (text/id/description), " +
            "type (into the focused field), swipe (up|down|left|right), back, home, recents, " +
            "notifications, enable (opens accessibility settings)."
    override val mutating = true

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("enum", buildJsonArray {
                    listOf("read", "tap", "type", "swipe", "back", "home", "recents", "notifications", "enable")
                        .forEach { add(it) }
                })
            }
            putJsonObject("value") {
                put("type", "string")
                put("description", "tap: the target's text/id/description; type: the text; swipe: the direction")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
    }

    override val promptSnippet = "screen - read and navigate the phone UI (accessibility; user-enabled)"
    override val promptGuidelines = listOf(
        "screen: ALWAYS read before tapping; act on what is actually on screen, never from memory.",
        "If screen reports the service is disabled, ask the user to enable it (action 'enable' opens the page).",
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val action = (args["action"] as? JsonPrimitive)?.contentOrNull
            ?: return@withContext ToolResult("screen: missing 'action'", isError = true)
        val value = (args["value"] as? JsonPrimitive)?.contentOrNull.orEmpty()

        if (action == "enable") {
            app.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return@withContext ToolResult("accessibility settings opened - the user enables 'PhoneCode' there")
        }
        val service = NavAccessibilityService.instance
            ?: return@withContext ToolResult(
                "screen navigation is OFF: the PhoneCode accessibility service isn't enabled. " +
                    "Ask the user, then call screen with action 'enable' to open the settings page.",
                isError = true,
            )
        runCatching {
            when (action) {
                "read" -> ToolResult(service.readScreen())
                "tap" -> {
                    if (value.isBlank()) return@runCatching ToolResult("tap: pass the target in 'value'", isError = true)
                    val out = service.tap(value)
                    ToolResult(out, isError = !out.startsWith("tapped"))
                }
                "type" -> {
                    if (value.isBlank()) return@runCatching ToolResult("type: pass the text in 'value'", isError = true)
                    val out = service.type(value)
                    ToolResult(out, isError = !out.startsWith("typed"))
                }
                "swipe" -> {
                    val out = service.swipe(value)
                    ToolResult(out, isError = !out.startsWith("swiped"))
                }
                "back", "home", "recents", "notifications" -> {
                    val out = service.global(action)
                    ToolResult(out, isError = out.endsWith("failed"))
                }
                else -> ToolResult("screen: unknown action '$action'", isError = true)
            }
        }.getOrElse { e -> ToolResult("screen $action failed: ${e.message}", isError = true) }
    }
}
