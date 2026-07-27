package dev.phonecode.agent

import dev.phonecode.provider.domain.ChatMessage
import dev.phonecode.provider.domain.FailureKind

/** High-level, UI-facing events produced by the agent loop. */
sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent
    data class ReasoningDelta(val text: String) : AgentEvent
    data class ToolAwaitingApproval(val id: String, val name: String, val argsJson: String) : AgentEvent
    data class ToolStarted(val id: String, val name: String, val argsJson: String) : AgentEvent
    data class ToolFinished(val id: String, val output: String, val isError: Boolean) : AgentEvent
    data class Usage(val input: Long, val output: Long) : AgentEvent
    data class Retrying(val attempt: Int, val message: String) : AgentEvent
    data class HistoryCheckpoint(val messages: List<ChatMessage>) : AgentEvent

    /** A queued/steering message the user sent mid-turn was just folded into the conversation, so the UI
     *  can drop it into the timeline at the right point and clear it from the pending list. */
    data class UserMessage(val text: String) : AgentEvent

    /**
     * The turn failed. [messages] carries the conversation accumulated up to the failure (prior history
     * plus the user's message) when it should be preserved, so a dropped connection does not lose context;
     * empty means leave the existing history untouched.
     */
    data class Error(
        val message: String,
        val messages: List<ChatMessage> = emptyList(),
        val kind: FailureKind = FailureKind.UNKNOWN,
        val statusCode: Int? = null,
        val retryAfterMillis: Long? = null,
        val code: String? = null,
    ) : AgentEvent

    /**
     * Terminal event: the assistant turn finished with no further tool calls.
     * Carries the full updated conversation so the caller can persist it.
     */
    data class TurnComplete(val messages: List<ChatMessage>) : AgentEvent
}
