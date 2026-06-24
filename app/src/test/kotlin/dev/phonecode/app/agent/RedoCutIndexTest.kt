package dev.phonecode.app.agent

import dev.phonecode.provider.domain.ChatMessage
import dev.phonecode.provider.domain.MessagePart
import dev.phonecode.provider.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * redo() rewinds to the last HUMAN prompt. Tool RESULTS also ride Role.USER (loop convention),
 * so a naive last-USER cut lands on the tool-result message and orphans the preceding tool_use -
 * the exact defect the verification round caught. These pin the cut to the Text-bearing prompt.
 */
class RedoCutIndexTest {
    private fun user(text: String) = ChatMessage(Role.USER, listOf(MessagePart.Text(text)))
    private fun assistantToolCall() = ChatMessage(Role.ASSISTANT, listOf(MessagePart.ToolCall("c1", "read", "{}")))
    private fun toolResult() = ChatMessage(Role.USER, listOf(MessagePart.ToolResult("c1", "ok")))
    private fun assistant(text: String) = ChatMessage(Role.ASSISTANT, listOf(MessagePart.Text(text)))

    @Test fun plainTurnCutsAtThePrompt() {
        assertEquals(0, redoCutIndex(listOf(user("hi"), assistant("hello"))))
    }

    @Test fun toolTurnCutsAtThePromptNotTheToolResult() {
        // [U(prompt), A(tool_use), U(tool_result), A(answer)] -> cut at 0; cutting at the
        // tool-result (2) would leave a dangling tool_use that strict providers reject.
        val history = listOf(user("do it"), assistantToolCall(), toolResult(), assistant("done"))
        assertEquals(0, redoCutIndex(history))
    }

    @Test fun multiTurnCutsAtTheLastPrompt() {
        val history = listOf(
            user("first"), assistant("1"),
            user("second"), assistantToolCall(), toolResult(), assistant("2"),
        )
        assertEquals(2, redoCutIndex(history))
    }

    @Test fun emptyHistoryReturnsMinusOne() {
        assertEquals(-1, redoCutIndex(emptyList()))
    }
}
