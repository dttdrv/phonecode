package dev.phonecode.provider.http

import dev.phonecode.provider.domain.StopReason
import dev.phonecode.provider.domain.StreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the review-driven robustness fixes in the SSE mappers. */
class MapperRobustnessTest {

    @Test fun openAiStopsAfterTerminalFailure() {
        val mapper = OpenAiStreamMapper()
        val events = buildList {
            addAll(mapper.map(RawSse(null, "{not valid json")))
            addAll(mapper.map(RawSse(null, "{also bad")))
            addAll(mapper.map(RawSse(null, """{"choices":[{"index":0,"delta":{"content":"x"}}]}""")))
            addAll(mapper.finish())
        }
        assertEquals(1, events.size)
        assertTrue(events.single() is StreamEvent.Failed)
    }

    @Test fun openAiFlushesOpenToolCallOnTruncatedStream() {
        val mapper = OpenAiStreamMapper()
        val events = buildList {
            addAll(mapper.map(RawSse(null, """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c","type":"function","function":{"name":"f","arguments":"{}"}}]}}]}""")))
            // stream drops here - no finish_reason chunk
            addAll(mapper.finish())
        }
        assertEquals(
            listOf(
                StreamEvent.ToolCallStart(0, "c", "f"),
                StreamEvent.ToolCallArgsDelta(0, "{}"),
                StreamEvent.ToolCallEnd(0),
                StreamEvent.Done(StopReason.END_TURN),
            ),
            events,
        )
    }

    /** Azure / some gateways co-locate usage on the same chunk as finish_reason. */
    @Test fun openAiToolEndPrecedesUsageWhenCoLocated() {
        val mapper = OpenAiStreamMapper()
        val events = buildList {
            addAll(mapper.map(RawSse(null, """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c","type":"function","function":{"name":"f","arguments":"{}"}}]}}]}""")))
            addAll(mapper.map(RawSse(null, """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":1,"completion_tokens":2}}""")))
            addAll(mapper.finish())
        }
        assertEquals(
            listOf(
                StreamEvent.ToolCallStart(0, "c", "f"),
                StreamEvent.ToolCallArgsDelta(0, "{}"),
                StreamEvent.ToolCallEnd(0),
                StreamEvent.Usage(input = 1, output = 2),
                StreamEvent.Done(StopReason.TOOL_USE),
            ),
            events,
        )
    }

    @Test fun anthropicStopsAfterError() {
        val mapper = AnthropicStreamMapper()
        val events = buildList {
            addAll(mapper.map(RawSse("error", """{"type":"error","error":{"message":"boom"}}""")))
            addAll(mapper.map(RawSse("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"x"}}""")))
            addAll(mapper.finish())
        }
        assertEquals(listOf(StreamEvent.Failed("boom")), events)
    }

    @Test fun openAiUsageNetsCacheFromInputAndReasoningFromOutput() {
        val mapper = OpenAiStreamMapper()
        val events = mapper.map(
            RawSse(
                null,
                """{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":20,"prompt_tokens_details":{"cached_tokens":30},"completion_tokens_details":{"reasoning_tokens":5}}}""",
            ),
        )
        val usage = events.single() as StreamEvent.Usage
        assertEquals(70L, usage.input) // 100 - 30 cached
        assertEquals(15L, usage.output) // 20 - 5 reasoning
        assertEquals(30L, usage.cacheRead)
        assertEquals(5L, usage.reasoning)
    }
}
