package dev.phonecode.provider.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The whole models.dev JSON deserializes into this: Map<providerId, ProviderInfo>. */
typealias Catalog = Map<String, ProviderInfo>

@Serializable
data class ProviderInfo(
    val id: String,
    val name: String,
    val api: String? = null,
    val env: List<String> = emptyList(),
    val doc: String? = null,
    val models: Map<String, ModelInfo> = emptyMap(),
)

@Serializable
data class ModelInfo(
    val id: String,
    val name: String,
    val reasoning: Boolean = false,
    @SerialName("tool_call") val toolCall: Boolean = false,
    val attachment: Boolean = false,
    val cost: Cost? = null,
    val limit: Limit? = null,
)

@Serializable
data class Cost(
    val input: Double? = null,
    val output: Double? = null,
    @SerialName("cache_read") val cacheRead: Double? = null,
    @SerialName("cache_write") val cacheWrite: Double? = null,
)

@Serializable
data class Limit(
    val context: Long? = null,
    val output: Long? = null,
)

/**
 * Single shared Json for catalog parsing. ignoreUnknownKeys is essential -
 * models.dev carries many fields we don't need (modalities, release_date, ...);
 * drop them silently. isLenient/coerceInputValues guard minor wire drift.
 */
val catalogJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}
