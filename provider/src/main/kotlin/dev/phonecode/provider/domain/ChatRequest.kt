package dev.phonecode.provider.domain

import kotlinx.serialization.json.JsonObject

/**
 * Provider-agnostic request. Not @Serializable: each provider serializes it
 * into its own wire shape (see RequestBodyBuilders). `stream` defaults true -
 * an on-device coding agent always wants incremental output.
 */
data class ChatRequest(
    val model: String,
    val system: String? = null,
    val messages: List<ChatMessage>,
    val tools: List<ToolDef> = emptyList(),
    val stream: Boolean = true,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT,
    val maxTokens: Int? = null,
    /** Stable per-session key for OpenAI-family automatic prompt caching. */
    val sessionId: String? = null,
)

data class ChatMessage(
    val role: Role,
    val parts: List<MessagePart>,
)

/**
 * No SYSTEM role: the system prompt is a top-level field on [ChatRequest].
 * Anthropic takes it as top-level `system`; OpenAI gets a synthetic system
 * message injected at send time. Keeping it off the enum removes an
 * impossible-state branch from every consumer.
 */
enum class Role { USER, ASSISTANT }

sealed interface MessagePart {
    /** Plain text - a user turn, or assistant prose. */
    data class Text(val text: String) : MessagePart

    /**
     * Assistant-issued tool call. [argsJson] is the COMPLETE arguments object
     * as a JSON string (accumulated from stream fragments before it lands here).
     */
    data class ToolCall(val id: String, val name: String, val argsJson: String) : MessagePart

    /** Result fed back. [callId] matches a prior [ToolCall.id]. */
    data class ToolResult(val callId: String, val content: String, val isError: Boolean = false) : MessagePart

    /** Assistant reasoning, replayed back in multi-turn context. */
    data class Reasoning(val text: String) : MessagePart
}

data class ToolDef(
    val name: String,
    val description: String,
    /** Raw JSON Schema object, passed straight through to both wire formats. */
    val parametersJsonSchema: JsonObject,
)

/**
 * DEFAULT = send no reasoning field (provider default). Mapped per-provider:
 * OpenAI → `reasoning_effort` string; Anthropic → `thinking{enabled,budget_tokens}`.
 */
enum class ReasoningEffort { DEFAULT, LOW, MEDIUM, HIGH }
