package dev.phonecode.provider.http

import dev.phonecode.provider.Fixtures
import dev.phonecode.provider.domain.ChatMessage
import dev.phonecode.provider.domain.ChatRequest
import dev.phonecode.provider.domain.MessagePart
import dev.phonecode.provider.domain.Role
import dev.phonecode.provider.domain.StopReason
import dev.phonecode.provider.domain.StreamEvent
import dev.phonecode.provider.preset.AuthScheme
import dev.phonecode.provider.preset.ProviderPreset
import dev.phonecode.provider.preset.WireFormat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** End-to-end: real OkHttp streaming over MockWebServer through SseParser + mapper. */
class ProviderStreamingTest {

    private fun userReq() =
        ChatRequest(model = "m", messages = listOf(ChatMessage(Role.USER, listOf(MessagePart.Text("hi")))))

    @Test fun openAiCompatStreamsOverHttp() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(Fixtures.load("openai/text_only.sse")),
        )
        server.start()
        val preset = ProviderPreset(
            "t", "T", server.url("/v1").toString().trimEnd('/'),
            WireFormat.OPENAI_COMPAT, AuthScheme.BEARER,
        )
        val events = OpenAiCompatProvider(preset, "k", OkHttpClient()).stream(userReq()).toList()
        assertTrue(events.any { it is StreamEvent.TextDelta && it.text == "Hello" })
        assertEquals(StreamEvent.Done(StopReason.END_TURN), events.last())
        server.shutdown()
    }

    @Test fun anthropicStreamsOverHttp() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(Fixtures.load("anthropic/text_thinking.sse")),
        )
        server.start()
        val preset = ProviderPreset(
            "t", "T", server.url("/").toString().trimEnd('/'),
            WireFormat.ANTHROPIC, AuthScheme.X_API_KEY,
        )
        val events = AnthropicProvider(preset, "k", OkHttpClient()).stream(userReq()).toList()
        assertTrue(events.any { it is StreamEvent.TextDelta && it.text == "Hi" })
        assertEquals(StreamEvent.Done(StopReason.END_TURN), events.last())
        server.shutdown()
    }
}
