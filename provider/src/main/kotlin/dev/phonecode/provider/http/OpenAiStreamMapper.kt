package dev.phonecode.provider.http

import dev.phonecode.provider.domain.StopReason
import dev.phonecode.provider.domain.StreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Maps OpenAI-compatible Chat Completions SSE chunks to normalized [StreamEvent]s.
 * Stateful per stream: tracks which tool-call indices have been announced/closed
 * and the final stop reason. [StreamEvent.Done] is deferred to [finish] so
 * [StreamEvent.Usage] (which arrives in the trailing chunk, after `finish_reason`)
 * precedes it; ToolCallEnd precedes Usage even on gateways that co-locate usage
 * with `finish_reason`. Once a terminal `Failed` is produced, no further events emit.
 */
internal class OpenAiStreamMapper : SseStreamMapper {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val startedTools = sortedSetOf<Int>()
    private val endedTools = sortedSetOf<Int>()
    private var stopReason: StopReason = StopReason.END_TURN
    private var terminated = false

    override fun map(raw: RawSse): List<StreamEvent> {
        if (terminated) return emptyList()
        val data = raw.data.trim()
        if (data.isEmpty() || data == "[DONE]") return emptyList()

        val obj = try {
            json.parseToJsonElement(data).jsonObject
        } catch (e: Exception) {
            terminated = true
            return listOf(StreamEvent.Failed("openai parse error: ${e.message}"))
        }

        obj.obj("error")?.let { err ->
            terminated = true
            return listOf(StreamEvent.Failed(err.str("message") ?: "openai error"))
        }

        val out = mutableListOf<StreamEvent>()
        val choices = obj.arr("choices")
        if (choices.isNullOrEmpty()) {
            obj.obj("usage")?.let { out.add(parseUsage(it)) }
            return out
        }

        val choice = choices[0] as? JsonObject ?: return out
        choice.obj("delta")?.let { delta ->
            delta.str("content")?.takeIf { it.isNotEmpty() }?.let { out.add(StreamEvent.TextDelta(it)) }
            (delta.str("reasoning_content") ?: delta.str("reasoning"))
                ?.takeIf { it.isNotEmpty() }?.let { out.add(StreamEvent.ReasoningDelta(it)) }
            delta.arr("tool_calls")?.forEach { element ->
                val tc = element as? JsonObject ?: return@forEach
                val index = tc.intOf("index") ?: 0
                val fn = tc.obj("function")
                // OpenAI sends id + name on the FIRST fragment for an index; capture once.
                if (startedTools.add(index)) {
                    out.add(StreamEvent.ToolCallStart(index, tc.str("id") ?: "", fn?.str("name") ?: ""))
                }
                fn?.str("arguments")?.takeIf { it.isNotEmpty() }
                    ?.let { out.add(StreamEvent.ToolCallArgsDelta(index, it)) }
            }
        }

        // Close tool calls BEFORE reporting usage, even when a gateway co-locates both on one chunk.
        choice.str("finish_reason")?.let { reason ->
            stopReason = finishReasonToStop(reason)
            startedTools.forEach { if (endedTools.add(it)) out.add(StreamEvent.ToolCallEnd(it)) }
        }
        obj.obj("usage")?.let { out.add(parseUsage(it)) }
        return out
    }

    override fun finish(): List<StreamEvent> {
        if (terminated) return emptyList()
        val out = mutableListOf<StreamEvent>()
        // Flush any tool call left open by a truncated stream (no finish_reason chunk arrived).
        startedTools.forEach { if (endedTools.add(it)) out.add(StreamEvent.ToolCallEnd(it)) }
        out.add(StreamEvent.Done(stopReason))
        return out
    }

    private fun parseUsage(usage: JsonObject): StreamEvent.Usage {
        val promptTokens = usage.longOf("prompt_tokens") ?: 0
        val completionTokens = usage.longOf("completion_tokens") ?: 0
        val cacheRead = usage.obj("prompt_tokens_details")?.longOf("cached_tokens")
        val reasoning = usage.obj("completion_tokens_details")?.longOf("reasoning_tokens")
        // OpenAI folds cached tokens into prompt_tokens and reasoning into completion_tokens;
        // split them out so `input` is fresh (non-cached) and `output` excludes reasoning.
        return StreamEvent.Usage(
            input = (promptTokens - (cacheRead ?: 0)).coerceAtLeast(0),
            output = (completionTokens - (reasoning ?: 0)).coerceAtLeast(0),
            cacheRead = cacheRead,
            reasoning = reasoning,
        )
    }

    private fun finishReasonToStop(reason: String): StopReason = when (reason) {
        "stop" -> StopReason.END_TURN
        "tool_calls" -> StopReason.TOOL_USE
        "length" -> StopReason.MAX_TOKENS
        "content_filter" -> StopReason.REFUSAL
        else -> StopReason.OTHER
    }
}
