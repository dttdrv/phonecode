package dev.phonecode.provider.http

import dev.phonecode.provider.domain.ChatRequest
import dev.phonecode.provider.domain.LlmProvider
import dev.phonecode.provider.domain.StreamEvent
import dev.phonecode.provider.preset.ProviderPreset
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Streams from the Anthropic Messages endpoint. */
class AnthropicProvider(
    private val preset: ProviderPreset,
    private val apiKey: String,
    private val client: OkHttpClient,
) : LlmProvider {
    override fun stream(request: ChatRequest): Flow<StreamEvent> {
        val httpRequest = Request.Builder()
            .url("${preset.baseUrl.trimEnd('/')}/v1/messages")
            .post(RequestBodyBuilders.toAnthropicBody(request).toRequestBody(JSON_MEDIA))
            .applyAuth(preset, apiKey)
            .build()
        return streamSse(client, httpRequest, AnthropicStreamMapper())
    }
}
