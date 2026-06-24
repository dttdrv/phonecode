package dev.phonecode.provider.http

import dev.phonecode.provider.preset.AuthScheme
import dev.phonecode.provider.preset.ProviderPreset
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request

internal val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/** Attaches the preset's auth header and any extra headers to a request. */
internal fun Request.Builder.applyAuth(preset: ProviderPreset, apiKey: String): Request.Builder {
    when (preset.authScheme) {
        AuthScheme.BEARER -> header("Authorization", "Bearer $apiKey")
        AuthScheme.X_API_KEY -> header("x-api-key", apiKey)
    }
    preset.extraHeaders.forEach { (name, value) -> header(name, value) }
    return this
}
