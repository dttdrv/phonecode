package dev.phonecode.app.agent

import dev.phonecode.app.data.toDomain
import dev.phonecode.app.data.toPersisted
import dev.phonecode.provider.domain.ChatMessage
import dev.phonecode.provider.domain.MessagePart
import dev.phonecode.provider.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolTranscriptRestoreTest {
    @Test
    fun nativeToolStepsSurviveSaveAndRestoreInOrder() {
        // Event order from one native turn: text, two parallel tools, their results, a failing tool, final text.
        val history = listOf(ChatMessage(Role.USER, listOf(MessagePart.Text("fix it"))))
            .withToolStarted(listOf(MessagePart.Text("Looking.")), MessagePart.ToolCall("a", "read_file", """{"path":"a.kt"}"""))
            .withToolStarted(emptyList(), MessagePart.ToolCall("b", "grep_files", """{"pattern":"x"}"""))
            .withToolFinished(MessagePart.ToolResult("b", "Completed"))
            .withToolFinished(MessagePart.ToolResult("a", "Completed"))
            .withToolStarted(emptyList(), MessagePart.ToolCall("c", "edit_file", """{"path":"a.kt"}"""))
            .withToolFinished(MessagePart.ToolResult("c", "Tool failed", isError = true))
            .plus(ChatMessage(Role.ASSISTANT, listOf(MessagePart.Text("Done."))))

        val restored = history.map { it.toPersisted() }.map { it.toDomain() }.toChatLines()

        assertEquals(
            listOf(
                ChatLine.User("fix it"),
                ChatLine.Assistant("Looking."),
                ChatLine.ToolActivity("a", "read_file", ToolStatus.DONE, "Completed", """{"path":"a.kt"}"""),
                ChatLine.ToolActivity("b", "grep_files", ToolStatus.DONE, "Completed", """{"pattern":"x"}"""),
                ChatLine.ToolActivity("c", "edit_file", ToolStatus.ERROR, "Tool failed", """{"path":"a.kt"}"""),
                ChatLine.Assistant("Done."),
            ),
            restored,
        )
        // Parallel calls share one assistant message so a fresh native import stays well formed.
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT), history.map { it.role })
    }
}
