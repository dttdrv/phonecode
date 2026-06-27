package dev.phonecode.provider.http

import dev.phonecode.provider.domain.StopReason
import dev.phonecode.provider.domain.StreamEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class ResponsesStreamMapperTest {

    private fun run(vararg events: Pair<String, String>): List<StreamEvent> {
        val mapper = ResponsesStreamMapper()
        val out = mutableListOf<StreamEvent>()
        events.forEach { (event, data) -> out += mapper.map(RawSse(event, data)) }
        out += mapper.finish()
        return out
    }

    @Test fun textDeltasThenUsageThenDone() {
        val events = run(
            "response.output_text.delta" to """{"type":"response.output_text.delta","delta":"Hello"}""",
            "response.output_text.delta" to """{"type":"response.output_text.delta","delta":" world"}""",
            "response.completed" to """{"type":"response.completed","response":{"usage":{"input_tokens":12,"output_tokens":3}}}""",
        )
        assertEquals(
            listOf(
                StreamEvent.TextDelta("Hello"),
                StreamEvent.TextDelta(" world"),
                StreamEvent.Usage(input = 12, output = 3),
                StreamEvent.Done(StopReason.END_TURN),
            ),
            events,
        )
    }

    @Test fun functionCallStartsStreamsArgsAndEndsWithToolUse() {
        val events = run(
            "response.output_item.added" to """{"type":"response.output_item.added","item":{"type":"function_call","id":"fc_1","call_id":"call_9","name":"read"}}""",
            "response.function_call_arguments.delta" to """{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\"path\":"}""",
            "response.function_call_arguments.delta" to """{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"\"a\"}"}""",
            "response.output_item.done" to """{"type":"response.output_item.done","item":{"type":"function_call","id":"fc_1","call_id":"call_9","name":"read","arguments":"{\"path\":\"a\"}"}}""",
            "response.completed" to """{"type":"response.completed","response":{"usage":{"input_tokens":5,"output_tokens":2}}}""",
        )
        assertEquals(
            listOf(
                StreamEvent.ToolCallStart(0, "call_9", "read"),
                StreamEvent.ToolCallArgsDelta(0, "{\"path\":"),
                StreamEvent.ToolCallArgsDelta(0, "\"a\"}"),
                StreamEvent.ToolCallEnd(0),
                StreamEvent.Usage(input = 5, output = 2),
                StreamEvent.Done(StopReason.TOOL_USE),
            ),
            events,
        )
    }

    @Test fun surfacesApiError() {
        val events = run(
            "response.failed" to """{"type":"response.failed","response":{"error":{"message":"quota exceeded"}}}""",
        )
        assertEquals(listOf(StreamEvent.Failed("quota exceeded")), events)
    }
}
