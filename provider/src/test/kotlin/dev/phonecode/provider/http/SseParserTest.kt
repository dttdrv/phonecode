package dev.phonecode.provider.http

import org.junit.Assert.assertEquals
import org.junit.Test

class SseParserTest {

    @Test fun joinsMultilineDataWithNewline() {
        assertEquals(listOf(RawSse(null, "a\nb")), SseParser.parseAll("data: a\ndata: b\n\n"))
    }

    @Test fun stripsSingleLeadingSpaceAfterColon() {
        assertEquals(listOf(RawSse(null, "x")), SseParser.parseAll("data: x\n\n"))
    }

    @Test fun ignoresCommentLines() {
        assertEquals(listOf(RawSse(null, "x")), SseParser.parseAll(": keep-alive\ndata: x\n\n"))
    }

    @Test fun capturesEventType() {
        assertEquals(listOf(RawSse("ping", "{}")), SseParser.parseAll("event: ping\ndata: {}\n\n"))
    }

    @Test fun doneSentinelIsPlainData() {
        assertEquals(listOf(RawSse(null, "[DONE]")), SseParser.parseAll("data: [DONE]\n\n"))
    }

    @Test fun flushesTrailingEventWithoutBlankLine() {
        assertEquals(listOf(RawSse(null, "tail")), SseParser.parseAll("data: tail"))
    }

    @Test fun normalizesCarriageReturns() {
        assertEquals(listOf(RawSse(null, "x")), SseParser.parseAll("data: x\r\n\r\n"))
    }
}
