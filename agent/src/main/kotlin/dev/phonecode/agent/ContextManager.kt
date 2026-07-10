package dev.phonecode.agent

import dev.phonecode.provider.domain.ChatMessage
import dev.phonecode.provider.domain.ChatRequest
import dev.phonecode.provider.domain.LlmProvider
import dev.phonecode.provider.domain.MessagePart
import dev.phonecode.provider.domain.Role
import dev.phonecode.provider.domain.StreamEvent
import kotlinx.coroutines.flow.collect

/**
 * Keeps the conversation within the model's context window. Detects overflow
 * against `limit.input - reserved`, and compacts by summarizing the older head
 * (via the provider) while keeping a recent verbatim tail - snapping the cut to a
 * real user turn so a tool result is never separated from its tool call.
 */
class ContextManager(
    private val provider: LlmProvider,
    private val reservedTokens: Long = 20_000,
    private val keepRecentTokens: Int = 8_000,
) {
    fun isOverflow(totalTokens: Long, contextLimit: Long?): Boolean {
        if (contextLimit == null || contextLimit <= 0) return false
        val usable = (contextLimit - reservedTokens).coerceAtLeast(0)
        return totalTokens >= usable
    }

    /** Rough token estimate of a whole message list, used to seed the overflow check before the
     *  first turn reports real usage (a fresh AgentLoop starts with lastUsageTotal=0). */
    fun estimatedSize(messages: List<ChatMessage>): Long = messages.sumOf { estimateTokens(it).toLong() }

    suspend fun compact(model: String, messages: List<ChatMessage>): List<ChatMessage> {
        val cut = findCutPoint(messages)
        if (cut <= 0) return messages // nothing safely summarizable
        val head = messages.subList(0, cut)
        val tail = messages.subList(cut, messages.size)
        val summary = summarize(model, head) ?: return messages // summary failed/empty - keep the head, never destroy it
        val summaryMessage = ChatMessage(
            Role.USER,
            listOf(
                MessagePart.Text(
                    "<system-reminder>Summary of the earlier conversation (older messages were compacted to save context):\n$summary</system-reminder>",
                ),
            ),
        )
        return listOf(summaryMessage) + tail
    }

    /** Index of the first KEPT message: walk back ~keepRecentTokens, then snap to a real user turn. */
    private fun findCutPoint(messages: List<ChatMessage>): Int {
        var acc = 0
        var cut = messages.size
        for (i in messages.indices.reversed()) {
            acc += estimateTokens(messages[i])
            cut = i
            if (acc >= keepRecentTokens) break
        }
        var snapped = cut
        while (snapped > 0 && !isValidCutPoint(messages[snapped])) snapped--
        return snapped
    }

    /**
     * A safe place to start the kept tail: an assistant message (its tool calls and their results
     * stay together in the tail) or a real user turn. Never a pure tool_result user message - that
     * would orphan the result from a tool call left behind in the summarized head. Allowing assistant
     * boundaries is what lets tool-heavy single-user-turn sessions actually compact.
     */
    private fun isValidCutPoint(message: ChatMessage): Boolean =
        message.role == Role.ASSISTANT || message.parts.any { it is MessagePart.Text || it is MessagePart.Image }

    private fun estimateTokens(message: ChatMessage): Int = message.parts.sumOf { partLength(it) } / 4

    private fun partLength(part: MessagePart): Int = when (part) {
        is MessagePart.Text -> part.text.length
        is MessagePart.Image -> 4_000
        is MessagePart.ToolCall -> part.name.length + part.argsJson.length
        is MessagePart.ToolResult -> part.content.length
        is MessagePart.Reasoning -> part.text.length
    }

    /** Returns the summary, or null if the provider failed or produced nothing (caller keeps the original). */
    private suspend fun summarize(model: String, head: List<ChatMessage>): String? {
        val request = ChatRequest(model = model, system = SUMMARY_PROMPT, messages = head)
        val out = StringBuilder()
        var failed = false
        provider.stream(request).collect { event ->
            when (event) {
                is StreamEvent.TextDelta -> out.append(event.text)
                is StreamEvent.Failed -> failed = true
                else -> Unit
            }
        }
        return if (failed) null else out.toString().trim().ifBlank { null }
    }

    private companion object {
        const val SUMMARY_PROMPT =
            "You are compacting a long coding conversation so work can continue without loss. " +
                "Summarize everything so far using EXACTLY this structure:\n" +
                "## Goal\n## Constraints & Preferences\n## Progress (Done / In Progress / Blocked)\n" +
                "## Key Decisions\n## Next Steps\n## Critical Context\n" +
                "Preserve exact file paths, function names, and error messages. Be concise but complete."
    }
}
