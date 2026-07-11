package dev.phonecode.provider.http

import dev.phonecode.provider.preset.ProviderPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class CodexReasoningLevel(
    val effort: String,
)

@Serializable
data class CodexModelInfo(
    val slug: String,
    @SerialName("display_name") val displayName: String,
    val visibility: String = "list",
    val priority: Int = Int.MAX_VALUE,
    @SerialName("supported_in_api") val supportedInApi: Boolean = true,
    @SerialName("supported_reasoning_levels") val supportedReasoningLevels: List<CodexReasoningLevel> = emptyList(),
    @SerialName("context_window") val contextWindow: Long? = null,
    @SerialName("max_context_window") val maxContextWindow: Long? = null,
)

@Serializable
private data class CodexModelsResponse(
    val models: List<CodexModelInfo> = emptyList(),
)

class CodexModelsClient(
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(
        preset: ProviderPreset,
        accessToken: String,
        clientVersion: String,
    ): List<CodexModelInfo> = withContext(Dispatchers.IO) {
        val url = "${preset.baseUrl.trimEnd('/')}/models".toHttpUrl().newBuilder()
            .addQueryParameter("client_version", clientVersion)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .applyAuth(preset, accessToken)
            .build()
        client.newCall(request).apply { timeout().timeout(5, TimeUnit.SECONDS) }.execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Codex models returned HTTP ${response.code}: ${body.take(300)}")
            json.decodeFromString(CodexModelsResponse.serializer(), body).models
        }
    }
}
