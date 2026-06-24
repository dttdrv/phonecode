package dev.phonecode.agent

import dev.phonecode.provider.domain.ChatMessage
import dev.phonecode.provider.domain.ChatRequest
import dev.phonecode.provider.domain.LlmProvider
import dev.phonecode.provider.domain.MessagePart
import dev.phonecode.provider.domain.Role
import dev.phonecode.provider.domain.StopReason
import dev.phonecode.provider.domain.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextManagerTest {

    private class SummaryProvider(private val summary: String) : LlmProvider {
        var calls = 0
        override fun stream(request: ChatRequest): Flow<StreamEvent> {
            calls++
            return listOf(StreamEvent.TextDelta(summary), StreamEvent.Done(StopReason.END_TURN)).asFlow()
        }
    }

    @Test fun isOverflowRespectsLimitAndReserved() {
        val cm = ContextManager(SummaryProvider("s"), reservedTokens = 1000)
        assertFalse(cm.isOverflow(100, contextLimit = null)) // unknown limit -> never
        assertFalse(cm.isOverflow(100, contextLimit = 0))
        assertFalse(cm.isOverflow(100, contextLimit = 5000)) // 100 < 4000 usable
        assertTrue(cm.isOverflow(4000, contextLimit = 5000)) // 4000 >= 4000 usable
        assertTrue(cm.isOverflow(9999, contextLimit = 5000))
    }

    @Test fun compactSummarizesHeadKeepsRecentTailAndPreservesToolPairs() = runTest {
        val provider = SummaryProvider("SUMMARY-OF-HEAD")
        val cm = ContextManager(provider, keepRecentTokens = 10)
        val messages = listOf(
            ChatMessage(Role.USER, listOf(MessagePart.Text("old context ".repeat(30)))), // boundary
            ChatMessage(Role.ASSISTANT, listOf(MessagePart.ToolCall("c", "read", "{}"))),
            ChatMessage(Role.USER, listOf(MessagePart.ToolResult("c", "file contents here"))),
            ChatMessage(Role.ASSISTANT, listOf(MessagePart.Text("here is the answer"))),
            ChatMessage(Role.USER, listOf(MessagePart.Text("recent question ".repeat(5)))), // boundary, ~20 tokens
        )
        val result = cm.compact("m", messages)
        assertEquals(1, provider.calls) // head summarized once
        assertEquals(2, result.size) // [summary, recent user turn]
        assertTrue((result[0].parts[0] as MessagePart.Text).text.contains("SUMMARY-OF-HEAD"))
        assertEquals(messages.last(), result[1]) // recent turn kept verbatim
        // the tool_call/tool_result pair was entirely in the summarized head - never orphaned in the tail
        assertFalse(result.drop(1).any { m -> m.parts.any { it is MessagePart.ToolResult } })
    }

    @Test fun noCompactionWhenNothingSafelySummarizable() = runTest {
        val provider = SummaryProvider("s")
        val cm = ContextManager(provider, keepRecentTokens = 1) // everything is "recent"
        val messages = listOf(ChatMessage(Role.USER, listOf(MessagePart.Text("only message"))))
        val result = cm.compact("m", messages)
        assertEquals(0, provider.calls) // never summarized
        assertEquals(messages, result) // unchanged
    }

    @Test fun compactsToolHeavySessionViaAssistantBoundary() = runTest {
        val provider = SummaryProvider("SUMMARY")
        val cm = ContextManager(provider, keepRecentTokens = 10)
        // One user-text turn, then only tool exchanges - must still compact (assistant boundary), no wedge.
        val messages = listOf(
            ChatMessage(Role.USER, listOf(MessagePart.Text("do a big task ".repeat(20)))),
            ChatMessage(Role.ASSISTANT, listOf(MessagePart.ToolCall("c1", "read", "{}"))),
            ChatMessage(Role.USER, listOf(MessagePart.ToolResult("c1", "result one ".repeat(10)))),
            ChatMessage(Role.ASSISTANT, listOf(MessagePart.ToolCall("c2", "read", "{}"))),
            ChatMessage(Role.USER, listOf(MessagePart.ToolResult("c2", "result two ".repeat(10)))),
        )
        val result = cm.compact("m", messages)
        assertEquals(1, provider.calls)
        assertTrue(result.size < messages.size) // actually reduced - no wedge
        assertTrue((result.first().parts[0] as MessagePart.Text).text.contains("SUMMARY"))
        // the first kept message is not an orphaned tool_result
        val firstKept = result[1]
        assertFalse(firstKept.role == Role.USER && firstKept.parts.all { it is MessagePart.ToolResult })
    }

    private class FailingProvider : LlmProvider {
        var calls = 0
        override fun stream(request: ChatRequest): Flow<StreamEvent> {
            calls++
            return listOf<StreamEvent>(StreamEvent.Failed("provider down")).asFlow()
        }
    }

    @Test fun summaryFailureKeepsOriginalConversation() = runTest {
        val failing = FailingProvider()
        val cm = ContextManager(failing, keepRecentTokens = 10)
        val messages = listOf(
            ChatMessage(Role.USER, listOf(MessagePart.Text("old context ".repeat(20)))),
            ChatMessage(Role.ASSISTANT, listOf(MessagePart.Text("answer here"))),
            ChatMessage(Role.USER, listOf(MessagePart.Text("recent question ".repeat(5)))),
        )
        val result = cm.compact("m", messages)
        assertEquals(1, failing.calls) // summary was attempted
        assertEquals(messages, result) // but the head was preserved, never destroyed
    }
}
