package dev.phonecode.provider.http

import dev.phonecode.provider.preset.BuiltInPresets
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexModelsClientTest {
    @Test
    fun fetchesAuthenticatedVisibleMetadata() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                {"models":[{
                  "slug":"gpt-5.6-sol",
                  "display_name":"GPT-5.6-Sol",
                  "visibility":"list",
                  "priority":0,
                  "supported_in_api":true,
                  "supported_reasoning_levels":[{"effort":"low","description":"Fast"},{"effort":"ultra","description":"Deep"}],
                  "context_window":372000,
                  "unknown_future_field":true
                }]}
                """.trimIndent(),
            ),
        )
        server.start()
        try {
            val preset = BuiltInPresets.codex.copy(
                baseUrl = server.url("/backend-api/codex").toString().trimEnd('/'),
                extraHeaders = mapOf(
                    "originator" to "phonecode",
                    "version" to "0.144.1",
                    "chatgpt-account-id" to "account-1",
                ),
            )
            val models = CodexModelsClient(OkHttpClient()).fetch(preset, "token-1", "0.144.1")

            assertEquals("gpt-5.6-sol", models.single().slug)
            assertEquals(372_000L, models.single().contextWindow)
            assertEquals(listOf("low", "ultra"), models.single().supportedReasoningLevels.map { it.effort })

            val request = server.takeRequest()
            assertEquals("/backend-api/codex/models?client_version=0.144.1", request.path)
            assertEquals("Bearer token-1", request.getHeader("Authorization"))
            assertEquals("0.144.1", request.getHeader("version"))
            assertEquals("account-1", request.getHeader("chatgpt-account-id"))
        } finally {
            server.shutdown()
        }
    }
}
