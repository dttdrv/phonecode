package dev.phonecode.agent

import dev.phonecode.provider.domain.ChatMessage

/** High-level, UI-facing events produced by the agent loop. */
sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent
    data class ReasoningDelta(val text: String) : AgentEvent
    data class ToolStarted(val id: String, val name: String, val argsJson: String) : AgentEvent
    data class ToolFinished(val id: String, val output: String, val isError: Boolean) : AgentEvent
    data class Usage(val input: Long, val output: Long) : AgentEvent

    /** Older messages were summarized to stay within the context window. */
    data class Compacted(val messageCount: Int) : AgentEvent

    data class Error(val message: String) : AgentEvent

    /**
     * Terminal event: the assistant turn finished with no further tool calls.
     * Carries the full updated conversation so the caller can persist it.
     */
    data class TurnComplete(val messages: List<ChatMessage>) : AgentEvent
}
