package dev.phonecode.app.runtime

import dev.phonecode.app.data.PersistedPart
import dev.phonecode.app.data.PersistedRole
import dev.phonecode.app.data.PersistedSession
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal data class MisulImportSession(val id: String, val messages: JSONArray)

internal fun PersistedSession.toMisulImportSession(
    provider: String,
    model: String,
    api: String,
): MisulImportSession {
    require(messages.size <= 256) { "Session $id exceeds the 256-message migration limit" }
    val toolNames = buildMap {
        messages.flatMap { it.parts }.filterIsInstance<PersistedPart.ToolCall>().forEach { put(it.id, it.name) }
    }
    val imported = JSONArray()
    messages.forEach { message ->
        when (message.role) {
            PersistedRole.USER -> {
                val userContent = JSONArray()
                message.parts.forEach { part ->
                    when (part) {
                        is PersistedPart.Text -> userContent.put(tagged("text", JSONObject().put("text", part.text)))
                        is PersistedPart.Image -> userContent.put(tagged("image", JSONObject()
                            .put("data", part.data)
                            .put("mime_type", part.mimeType)))
                        is PersistedPart.ToolResult -> imported.put(tagged("tool_result", JSONObject()
                            .put("tool_call_id", part.callId)
                            .put("tool_name", toolNames[part.callId] ?: "unknown_tool")
                            .put("content", JSONArray().put(tagged("text", JSONObject().put("text", part.content))))
                            .put("is_error", part.isError)
                            .put("timestamp_ms", updatedAt)))
                        is PersistedPart.Reasoning, is PersistedPart.ToolCall -> Unit
                    }
                }
                if (userContent.length() > 0) imported.put(tagged("user", JSONObject()
                    .put("content", userContent)
                    .put("timestamp_ms", updatedAt)))
            }
            PersistedRole.ASSISTANT -> {
                val assistantContent = JSONArray()
                message.parts.forEach { part ->
                    when (part) {
                        is PersistedPart.Text -> assistantContent.put(tagged("text", JSONObject().put("text", part.text)))
                        is PersistedPart.Reasoning -> assistantContent.put(tagged("thinking", JSONObject().put("thinking", part.text)))
                        is PersistedPart.ToolCall -> assistantContent.put(tagged("tool_call", JSONObject()
                            .put("id", part.id)
                            .put("name", part.name)
                            .put("arguments", parseArguments(part.argsJson))))
                        is PersistedPart.ToolResult -> imported.put(tagged("tool_result", JSONObject()
                            .put("tool_call_id", part.callId)
                            .put("tool_name", toolNames[part.callId] ?: "unknown_tool")
                            .put("content", JSONArray().put(tagged("text", JSONObject().put("text", part.content))))
                            .put("is_error", part.isError)
                            .put("timestamp_ms", updatedAt)))
                        is PersistedPart.Image -> Unit
                    }
                }
                if (assistantContent.length() > 0) imported.put(tagged("assistant", JSONObject()
                    .put("content", assistantContent)
                    .put("api", api)
                    .put("provider", provider)
                    .put("model", model)
                    .put("stop_reason", if (message.parts.any { it is PersistedPart.ToolCall }) "tool_use" else "stop")
                    .put("timestamp_ms", updatedAt)))
            }
        }
    }
    return MisulImportSession(id, imported)
}

private fun tagged(name: String, value: JSONObject) = JSONObject().put(name, value)

private fun parseArguments(raw: String): Any = runCatching { JSONTokener(raw).nextValue() }
    .getOrNull() ?: raw
