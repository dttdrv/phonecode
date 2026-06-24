package dev.phonecode.provider.http

import dev.phonecode.provider.domain.StopReason
import dev.phonecode.provider.domain.StreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Maps Anthropic Messages SSE events to normalized [StreamEvent]s. Stateful per
 * stream: tracks which content-block indices are tool_use blocks (so only those
 * emit [StreamEvent.ToolCallEnd]) and combines input-side usage from
 * `message_start` with output-side usage from `message_delta`. Once a terminal
 * `Done`/`Failed` is produced, no further events emit.
 */
internal class AnthropicStreamMapper : SseStreamMapper {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val toolIndices = mutableSetOf<Int>()
    private val endedTools = mutableSetOf<Int>()
    private var startInput = 0L
    private var startCacheRead: Long? = null
    private var startCacheWrite: Long? = null
    private var ended = false

    override fun map(raw: RawSse): List<StreamEvent> {
        if (ended) return emptyList()
        val type = raw.event ?: return emptyList() // Anthropic always sends `event:`
        val obj = raw.data.trim().takeIf { it.isNotEmpty() }?.let {
            try {
                json.parseToJsonElement(it).jsonObject
            } catch (e: Exception) {
                ended = true
                return listOf(StreamEvent.Failed("anthropic parse error: ${e.message}"))
            }
        }
        return when (type) {
            "message_start" -> { captureStartUsage(obj); emptyList() }
            "content_block_start" -> handleBlockStart(obj)
            "content_block_delta" -> handleBlockDelta(obj)
            "content_block_stop" -> handleBlockStop(obj)
            "message_delta" -> handleMessageDelta(obj)
            "error" -> {
                ended = true
                listOf(StreamEvent.Failed(obj?.obj("error")?.str("message") ?: "anthropic error"))
            }
            else -> emptyList() // ping, message_stop
        }
    }

    override fun finish(): List<StreamEvent> {
        if (ended) return emptyList()
        val out = mutableListOf<StreamEvent>()
        // Flush any tool_use block left open by a truncated stream.
        toolIndices.forEach { if (endedTools.add(it)) out.add(StreamEvent.ToolCallEnd(it)) }
        out.add(StreamEvent.Done(StopReason.OTHER))
        return out
    }

    private fun captureStartUsage(obj: JsonObject?) {
        obj?.obj("message")?.obj("usage")?.let { u ->
            startInput = u.longOf("input_tokens") ?: 0
            startCacheRead = u.longOf("cache_read_input_tokens")
            startCacheWrite = u.longOf("cache_creation_input_tokens")
        }
    }

    private fun handleBlockStart(obj: JsonObject?): List<StreamEvent> {
        val block = obj?.obj("content_block") ?: return emptyList()
        if (block.str("type") != "tool_use") return emptyList()
        val index = obj.intOf("index") ?: 0
        toolIndices.add(index)
        return listOf(StreamEvent.ToolCallStart(index, block.str("id") ?: "", block.str("name") ?: ""))
    }

    private fun handleBlockDelta(obj: JsonObject?): List<StreamEvent> {
        val delta = obj?.obj("delta") ?: return emptyList()
        val index = obj.intOf("index") ?: 0
        return when (delta.str("type")) {
            "text_delta" -> delta.str("text")?.let { listOf(StreamEvent.TextDelta(it)) } ?: emptyList()
            "thinking_delta" -> delta.str("thinking")?.let { listOf(StreamEvent.ReasoningDelta(it)) } ?: emptyList()
            "input_json_delta" -> delta.str("partial_json")?.let { listOf(StreamEvent.ToolCallArgsDelta(index, it)) } ?: emptyList()
            else -> emptyList() // signature_delta, etc.
        }
    }

    private fun handleBlockStop(obj: JsonObject?): List<StreamEvent> {
        val index = obj?.intOf("index") ?: return emptyList()
        return if (index in toolIndices && endedTools.add(index)) {
            listOf(StreamEvent.ToolCallEnd(index))
        } else {
            emptyList()
        }
    }

    private fun handleMessageDelta(obj: JsonObject?): List<StreamEvent> {
        val out = mutableListOf<StreamEvent>()
        obj?.obj("usage")?.let { u ->
            out.add(
                StreamEvent.Usage(
                    input = u.longOf("input_tokens") ?: startInput,
                    output = u.longOf("output_tokens") ?: 0,
                    cacheRead = u.longOf("cache_read_input_tokens") ?: startCacheRead,
                    cacheWrite = u.longOf("cache_creation_input_tokens") ?: startCacheWrite,
                ),
            )
        }
        obj?.obj("delta")?.str("stop_reason")?.let { reason ->
            ended = true
            out.add(StreamEvent.Done(stopReasonToStop(reason)))
        }
        return out
    }

    private fun stopReasonToStop(reason: String): StopReason = when (reason) {
        "end_turn" -> StopReason.END_TURN
        "tool_use" -> StopReason.TOOL_USE
        "max_tokens" -> StopReason.MAX_TOKENS
        "stop_sequence" -> StopReason.STOP_SEQUENCE
        "refusal" -> StopReason.REFUSAL
        else -> StopReason.OTHER
    }
}
