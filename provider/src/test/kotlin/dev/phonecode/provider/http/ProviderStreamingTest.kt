package dev.phonecode.provider.http

import dev.phonecode.provider.Fixtures
import dev.phonecode.provider.domain.ChatMessage
import dev.phonecode.provider.domain.FailureKind
import dev.phonecode.provider.domain.ChatRequest
import dev.phonecode.provider.domain.MessagePart
import dev.phonecode.provider.domain.Role
import dev.phonecode.provider.domain.StopReason
import dev.phonecode.provider.domain.StreamEvent
import dev.phonecode.provider.preset.AuthScheme
import dev.phonecode.provider.preset.CodexCompatibility
import dev.phonecode.provider.preset.ProviderPreset
import dev.phonecode.provider.preset.WireFormat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun codexUsesCurrentLoginHeadersAndSession() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: [DONE]\n\n"))
        server.start()
        val preset = ProviderPreset(
            "codex", "ChatGPT", server.url("/").toString().trimEnd('/'),
            WireFormat.OPENAI_RESPONSES, AuthScheme.BEARER,
            extraHeaders = mapOf(
                "originator" to CodexCompatibility.ORIGINATOR,
                "version" to CodexCompatibility.CLIENT_VERSION,
                "User-Agent" to CodexCompatibility.USER_AGENT,
            ),
        )
        CodexProvider(preset, "token", OkHttpClient()).stream(userReq().copy(sessionId = "session-7")).toList()
        val request = server.takeRequest()
        assertEquals("/responses", request.path)
        assertEquals("session-7", request.getHeader("session-id"))
        assertEquals(CodexCompatibility.ORIGINATOR, request.getHeader("originator"))
        assertEquals(CodexCompatibility.CLIENT_VERSION, request.getHeader("version"))
        assertEquals(CodexCompatibility.USER_AGENT, request.getHeader("User-Agent"))
        assertEquals(null, request.getHeader("OpenAI-Beta"))
        server.shutdown()
    }

    @Test fun transientHttpFailureCarriesRetryMetadata() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Retry-After", "3")
                .setBody("""{"error":{"message":"overloaded"}}"""),
        )
        server.start()
        val preset = ProviderPreset(
            "t", "T", server.url("/v1").toString().trimEnd('/'),
            WireFormat.OPENAI_COMPAT, AuthScheme.BEARER,
        )

        val failure = OpenAiCompatProvider(preset, "k", OkHttpClient()).stream(userReq()).toList().single() as StreamEvent.Failed

        assertTrue(failure.retryable)
        assertEquals(3_000L, failure.retryAfterMillis)
        assertEquals(FailureKind.SERVER, failure.kind)
        assertEquals(503, failure.statusCode)
        assertTrue(failure.message.contains("overloaded"))
        server.shutdown()
    }

    @Test fun quotaFailureCarriesStructuredCode() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"error":{"code":"usage_limit_exceeded","message":"Go usage limit exceeded"}}"""),
        )
        server.start()
        val preset = ProviderPreset(
            "opencode-go", "OpenCode Go", server.url("/v1").toString().trimEnd('/'),
            WireFormat.OPENAI_COMPAT, AuthScheme.BEARER,
        )

        val failure = OpenAiCompatProvider(preset, "k", OkHttpClient()).stream(userReq()).toList().single() as StreamEvent.Failed

        assertEquals(FailureKind.QUOTA, failure.kind)
        assertEquals("usage_limit_exceeded", failure.code)
        assertEquals(429, failure.statusCode)
        assertFalse(failure.retryable)
        server.shutdown()
    }
}
