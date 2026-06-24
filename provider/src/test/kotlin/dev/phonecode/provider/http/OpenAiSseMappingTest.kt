package dev.phonecode.provider.http

import dev.phonecode.provider.Fixtures
import dev.phonecode.provider.domain.StopReason
import dev.phonecode.provider.domain.StreamEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAiSseMappingTest {

    private fun run(path: String): List<StreamEvent> {
        val mapper = OpenAiStreamMapper()
        val events = mutableListOf<StreamEvent>()
        SseParser.parseAll(Fixtures.load(path)).forEach { events += mapper.map(it) }
        events += mapper.finish()
        return events
    }

    @Test fun textOnly() {
        assertEquals(
            listOf(
                StreamEvent.TextDelta("Hello"),
                StreamEvent.TextDelta(" world"),
                StreamEvent.Usage(input = 32, output = 7, cacheRead = 10), // input nets out the 10 cached tokens
                StreamEvent.Done(StopReason.END_TURN),
            ),
            run("openai/text_only.sse"),
        )
    }

    @Test fun singleToolCallAccumulatesArgs() {
        val events = run("openai/single_tool_call.sse")
        assertEquals(
            listOf(
                StreamEvent.ToolCallStart(0, "call_abc", "get_weather"),
                StreamEvent.ToolCallArgsDelta(0, "{\"ci"),
                StreamEvent.ToolCallArgsDelta(0, "ty\":\"Paris\"}"),
                StreamEvent.ToolCallEnd(0),
                StreamEvent.Done(StopReason.TOOL_USE),
            ),
            events,
        )
        val args = events.filterIsInstance<StreamEvent.ToolCallArgsDelta>().joinToString("") { it.jsonFragment }
        assertEquals("{\"city\":\"Paris\"}", args)
    }

    /** choices[].index stays 0 throughout; tool_calls[].index is what we must key on. */
    @Test fun parallelToolCallsKeyByToolIndex() {
        assertEquals(
            listOf(
                StreamEvent.ToolCallStart(0, "call_0", "f0"),
                StreamEvent.ToolCallStart(1, "call_1", "f1"),
                StreamEvent.ToolCallArgsDelta(0, "{\"a\":1}"),
                StreamEvent.ToolCallArgsDelta(1, "{\"b\":2}"),
                StreamEvent.ToolCallEnd(0),
                StreamEvent.ToolCallEnd(1),
                StreamEvent.Done(StopReason.TOOL_USE),
            ),
            run("openai/parallel_tool_calls.sse"),
        )
    }

    @Test fun completesWithoutDoneSentinel() {
        assertEquals(
            listOf(
                StreamEvent.TextDelta("Hi"),
                StreamEvent.Done(StopReason.END_TURN),
            ),
            run("openai/no_done_sentinel.sse"),
        )
    }

    @Test fun joinsMultilineDataChunk() {
        assertEquals(
            listOf(
                StreamEvent.TextDelta("X"),
                StreamEvent.Done(StopReason.END_TURN),
            ),
            run("openai/multiline_data.sse"),
        )
    }
}
