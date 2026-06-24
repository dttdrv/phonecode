package dev.phonecode.provider.http

import dev.phonecode.provider.Fixtures
import dev.phonecode.provider.domain.StopReason
import dev.phonecode.provider.domain.StreamEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class AnthropicSseMappingTest {

    private fun run(path: String): List<StreamEvent> {
        val mapper = AnthropicStreamMapper()
        val events = mutableListOf<StreamEvent>()
        SseParser.parseAll(Fixtures.load(path)).forEach { events += mapper.map(it) }
        events += mapper.finish()
        return events
    }

    @Test fun thinkingThenTextThenUsage() {
        assertEquals(
            listOf(
                StreamEvent.ReasoningDelta("Let me "),
                StreamEvent.ReasoningDelta("think"),
                StreamEvent.TextDelta("Hi"),
                StreamEvent.Usage(input = 25, output = 89, cacheRead = 5, cacheWrite = 0),
                StreamEvent.Done(StopReason.END_TURN),
            ),
            run("anthropic/text_thinking.sse"),
        )
    }

    @Test fun singleToolUseAccumulatesArgs() {
        val events = run("anthropic/single_tool_use.sse")
        assertEquals(
            listOf(
                StreamEvent.ToolCallStart(0, "toolu_01", "get_weather"),
                StreamEvent.ToolCallArgsDelta(0, "{\"location\":"),
                StreamEvent.ToolCallArgsDelta(0, "\"Paris\"}"),
                StreamEvent.ToolCallEnd(0),
                StreamEvent.Usage(input = 30, output = 40),
                StreamEvent.Done(StopReason.TOOL_USE),
            ),
            events,
        )
        val args = events.filterIsInstance<StreamEvent.ToolCallArgsDelta>().joinToString("") { it.jsonFragment }
        assertEquals("{\"location\":\"Paris\"}", args)
    }

    @Test fun parallelToolUseGatesEndByToolIndices() {
        assertEquals(
            listOf(
                StreamEvent.TextDelta("Calling"),
                StreamEvent.ToolCallStart(1, "t1", "f1"),
                StreamEvent.ToolCallArgsDelta(1, "{\"a\":1}"),
                StreamEvent.ToolCallEnd(1),
                StreamEvent.ToolCallStart(2, "t2", "f2"),
                StreamEvent.ToolCallArgsDelta(2, "{\"b\":2}"),
                StreamEvent.ToolCallEnd(2),
                StreamEvent.Usage(input = 50, output = 12),
                StreamEvent.Done(StopReason.TOOL_USE),
            ),
            run("anthropic/parallel_tool_use.sse"),
        )
    }

    @Test fun pingIgnoredAndErrorTerminates() {
        assertEquals(
            listOf(StreamEvent.Failed("Overloaded")),
            run("anthropic/ping_then_error.sse"),
        )
    }
}
