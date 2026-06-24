package dev.phonecode.provider.domain

/**
 * The single normalized event stream the agent consumes, provider-independent.
 * Tool calls are addressed by [index] (position within the assistant turn):
 * both wire formats key streaming tool-arg fragments by an integer index, and
 * id/name may not arrive until after the first fragment (OpenAI).
 */
sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    data class ReasoningDelta(val text: String) : StreamEvent

    data class ToolCallStart(val index: Int, val id: String, val name: String) : StreamEvent
    data class ToolCallArgsDelta(val index: Int, val jsonFragment: String) : StreamEvent
    data class ToolCallEnd(val index: Int) : StreamEvent

    /**
     * Cumulative token usage. cacheRead/cacheWrite/reasoning nullable - only
     * some providers report them. Emitted once near end-of-stream.
     */
    data class Usage(
        val input: Long,
        val output: Long,
        val cacheRead: Long? = null,
        val cacheWrite: Long? = null,
        val reasoning: Long? = null,
    ) : StreamEvent

    /** Normal terminal event. */
    data class Done(val stopReason: StopReason) : StreamEvent

    /**
     * Transport/parse/API error surfaced into the stream rather than thrown,
     * so a single collector handles both success and failure.
     */
    data class Failed(val message: String) : StreamEvent
}

enum class StopReason { END_TURN, TOOL_USE, MAX_TOKENS, STOP_SEQUENCE, REFUSAL, OTHER }
