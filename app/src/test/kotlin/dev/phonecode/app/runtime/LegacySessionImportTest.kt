package dev.phonecode.app.runtime

import dev.phonecode.app.data.PersistedMessage
import dev.phonecode.app.data.PersistedPart
import dev.phonecode.app.data.PersistedRole
import dev.phonecode.app.data.PersistedSession
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacySessionImportTest {
    @Test
    fun mapsReasoningToolCallsAndResultsWithoutReplayingThem() {
        val session = PersistedSession(
            id = "legacy-1",
            title = "Legacy",
            updatedAt = 42,
            messages = listOf(
                PersistedMessage(PersistedRole.USER, listOf(PersistedPart.Text("Inspect it"))),
                PersistedMessage(PersistedRole.ASSISTANT, listOf(
                    PersistedPart.Reasoning("Need the file"),
                    PersistedPart.ToolCall("call-1", "read_file", "{\"path\":\"README.md\"}"),
                )),
                PersistedMessage(PersistedRole.USER, listOf(PersistedPart.ToolResult("call-1", "contents"))),
                PersistedMessage(PersistedRole.ASSISTANT, listOf(PersistedPart.Text("Done"))),
            ),
        )

        val imported = session.toMisulImportSession("openai", "gpt-5.6", "openai_chat").messages

        assertEquals(4, imported.length())
        assertEquals("Inspect it", imported.getJSONObject(0).getJSONObject("user")
            .getJSONArray("content").getJSONObject(0).getJSONObject("text").getString("text"))
        assertEquals("read_file", imported.getJSONObject(1).getJSONObject("assistant")
            .getJSONArray("content").getJSONObject(1).getJSONObject("tool_call").getString("name"))
        assertEquals("read_file", imported.getJSONObject(2).getJSONObject("tool_result").getString("tool_name"))
        assertEquals("Done", imported.getJSONObject(3).getJSONObject("assistant")
            .getJSONArray("content").getJSONObject(0).getJSONObject("text").getString("text"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedLegacySessionsBeforeNativeWrites() {
        PersistedSession(
            id = "too-large",
            title = "Too large",
            updatedAt = 1,
            messages = List(257) { PersistedMessage(PersistedRole.USER, emptyList()) },
        ).toMisulImportSession("openai", "gpt-5.6", "openai_chat")
    }
}
