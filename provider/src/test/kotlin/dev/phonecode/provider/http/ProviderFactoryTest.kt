package dev.phonecode.provider.http

import dev.phonecode.provider.domain.ChatMessage
import dev.phonecode.provider.domain.ChatRequest
import dev.phonecode.provider.domain.MessagePart
import dev.phonecode.provider.domain.Role
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFactoryTest {

    @Test fun factoryDoesNotFollowCredentialBearingRedirects() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(307)
                .setHeader("Location", "/redirected"),
        )
        server.enqueue(MockResponse().setBody("data: [DONE]\n\n"))
        server.start()
        val preset = ProviderPreset(
            id = "test",
            displayName = "Test",
            baseUrl = server.url("").toString().trimEnd('/'),
            wireFormat = WireFormat.ANTHROPIC,
            authScheme = AuthScheme.X_API_KEY,
        )

        val events = ProviderFactory.create(preset, "secret", OkHttpClient()).stream(
            ChatRequest(
                model = "model",
                messages = listOf(ChatMessage(Role.USER, listOf(MessagePart.Text("hello")))),
            ),
        ).toList()

        assertEquals(1, server.requestCount)
        assertTrue(events.single() is StreamEvent.Failed)
        server.shutdown()
    }

    @Test fun factoryDisablesBothRedirectModesForEveryWireFormat() {
        WireFormat.entries.forEach { wireFormat ->
            val preset = ProviderPreset(
                id = "test",
                displayName = "Test",
                baseUrl = "https://example.com",
                wireFormat = wireFormat,
                authScheme = AuthScheme.BEARER,
            )

            val provider = ProviderFactory.create(preset, "secret", OkHttpClient())
            val field = provider.javaClass.getDeclaredField("client").apply { isAccessible = true }
            val client = field.get(provider) as OkHttpClient

            assertFalse("HTTP redirects enabled for $wireFormat", client.followRedirects)
            assertFalse("SSL redirects enabled for $wireFormat", client.followSslRedirects)
        }
    }
}
