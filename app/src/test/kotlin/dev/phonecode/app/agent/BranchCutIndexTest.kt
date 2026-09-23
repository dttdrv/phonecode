package dev.phonecode.app.agent

import dev.phonecode.provider.domain.ChatMessage
import dev.phonecode.provider.domain.MessagePart
import dev.phonecode.provider.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Test

/** Branching keeps whole turns: a tool result riding Role.USER never counts as a new prompt. */
class BranchCutIndexTest {
    private fun user(text: String) = ChatMessage(Role.USER, listOf(MessagePart.Text(text)))
    private fun assistantToolCall() = ChatMessage(Role.ASSISTANT, listOf(MessagePart.ToolCall("c1", "read", "{}")))
    private fun toolResult() = ChatMessage(Role.USER, listOf(MessagePart.ToolResult("c1", "ok")))
    private fun assistant(text: String) = ChatMessage(Role.ASSISTANT, listOf(MessagePart.Text(text)))

    private val history = listOf(
        user("first"), assistant("1"),
        user("second"), assistantToolCall(), toolResult(), assistant("2"),
        user("third"), assistant("3"),
    )

    @Test fun firstTurnStopsBeforeTheSecondPrompt() = assertEquals(2, branchCutIndex(history, 1))

    @Test fun toolTurnKeepsItsToolResultAndAnswer() = assertEquals(6, branchCutIndex(history, 2))

    @Test fun lastTurnKeepsEverything() = assertEquals(history.size, branchCutIndex(history, 3))

    @Test fun unknownTurnBranchesNothing() {
        assertEquals(0, branchCutIndex(history, 0))
        assertEquals(0, branchCutIndex(history, 4))
    }
}
