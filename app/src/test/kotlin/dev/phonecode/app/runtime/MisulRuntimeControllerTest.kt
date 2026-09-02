package dev.phonecode.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MisulRuntimeControllerTest {
    @Test
    fun runtimeSpecCarriesTheSelectedPhoneCodeProviderAndModel() {
        val json = fixtureSpec().toJson()

        assertEquals("openrouter", json.getJSONObject("default_selection").getString("provider"))
        assertEquals("openai/gpt-5", json.getJSONObject("default_selection").getString("model"))
        assertEquals(
            "openrouter_chat",
            json.getJSONArray("provider_configs").getJSONObject(0).getString("dialect"),
        )
        assertEquals(
            "secret-token",
            json.getJSONArray("provider_configs").getJSONObject(0).getString("credential_ref"),
        )
        assertFalse(json.getBoolean("allow_mutating_tools"))
    }

    @Test
    fun parserProjectsStreamingRecordsAndTerminalSettlement() {
        val delta = parseMisulRecord(
            """{"jsonrpc":"2.0","method":"message_delta","params":{"reasoning_delta":"think","text_delta":"answer"}}""",
            expectedId = 7,
        )
        assertEquals(listOf(MisulRuntimeEvent.Reasoning("think"), MisulRuntimeEvent.Text("answer")), delta.events)
        assertEquals(null, delta.settlement)

        val terminal = parseMisulRecord(
            """{"jsonrpc":"2.0","id":7,"result":{"status":"completed","content":"answer","failure":"none"}}""",
            expectedId = 7,
        )
        assertTrue(terminal.events.isEmpty())
        assertEquals(MisulPromptResult("completed", "answer", "none"), terminal.settlement)
    }

    @Test
    fun parserRejectsMalformedAndMismatchedTerminalRecords() {
        assertTrue(parseMisulRecord("not-json", 9).events.single() is MisulRuntimeEvent.ProtocolError)
        assertFalse(parseMisulRecord("""{"jsonrpc":"2.0","id":8,"result":{}}""", 9).terminal)
    }

    @Test
    fun parserProjectsNativeToolLifecycle() {
        val started = parseMisulRecord(
            """{"jsonrpc":"2.0","method":"tool_start","params":{"id":"call-1","name":"read_file","input":{"path":"README.md"}}}""",
            1,
        )
        val finished = parseMisulRecord(
            """{"jsonrpc":"2.0","method":"tool_end","params":{"id":"call-1","name":"read_file","is_error":false}}""",
            1,
        )

        assertEquals(MisulRuntimeEvent.ToolStarted("call-1", "read_file", "{\"path\":\"README.md\"}"), started.events.single())
        assertEquals(MisulRuntimeEvent.ToolFinished("call-1", "read_file", false), finished.events.single())
    }

    @Test
    fun parserProjectsNativeApprovalRequest() {
        val approval = parseMisulRecord(
            """{"jsonrpc":"2.0","method":"approval_request","params":{"id":"call-2","name":"write_file","input":{"path":"note.txt","content":"hello"}}}""",
            1,
        )

        assertEquals(
            MisulRuntimeEvent.ApprovalRequested(
                "call-2",
                "write_file",
                "{\"path\":\"note.txt\",\"content\":\"hello\"}",
            ),
            approval.events.single(),
        )
    }

    private fun fixtureSpec() = MisulRuntimeSpec(
        workspaceRoot = File("/tmp/workspace"),
        stateRoot = File("/tmp/state"),
        systemPrompt = "PhoneCode",
        model = MisulModel(
            id = "openai/gpt-5",
            name = "GPT-5",
            provider = "openrouter",
            contextWindow = 128_000,
            outputLimit = 16_000,
            reasoning = "high",
        ),
        provider = MisulProvider(
            id = "openrouter",
            endpoint = "https://openrouter.ai/api/v1",
            credential = "secret-token",
            dialect = "openrouter_chat",
            headers = mapOf("X-Title" to "PhoneCode"),
        ),
    )
}
