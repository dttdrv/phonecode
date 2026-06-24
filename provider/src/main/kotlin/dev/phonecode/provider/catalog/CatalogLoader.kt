package dev.phonecode.provider.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import okhttp3.OkHttpClient
import okhttp3.Request

/** A provider and its models, shaped for the provider-grouped model picker. */
data class ProviderGroup(
    val providerId: String,
    val displayName: String,
    val models: List<ModelInfo>,
)

enum class Source { NETWORK, CACHE, BUNDLED }

/** Result of a catalog load. [staleAgeMillis] is non-null only when serving a stale cache. */
data class CatalogResult(
    val catalog: Catalog,
    val source: Source,
    val staleAgeMillis: Long?,
)

/**
 * Loads the models.dev catalog with a network → cache → bundled fallback chain,
 * so the model picker is never empty offline. Stateless; `:app` owns any
 * in-memory observable around it.
 */
class CatalogLoader(
    private val httpClient: OkHttpClient,
    private val cache: CatalogCache,
    private val catalogUrl: String = "https://models.dev/api.json",
    private val ttlMillis: Long = 24L * 60 * 60 * 1000,
    private val bundledFallback: () -> String? = { null },
) {
    suspend fun load(forceRefresh: Boolean = false): CatalogResult = withContext(Dispatchers.IO) {
        // Every parse is defensive: a corrupt cache or a malformed 200 must fall
        // THROUGH the chain, never throw out of load() - the picker is never empty offline.

        // 1. Fresh cache.
        if (!forceRefresh) {
            val cached = cache.read()
            val age = cache.ageMillis()
            if (cached != null && age != null && age <= ttlMillis) {
                tryParse(cached)?.let { return@withContext CatalogResult(it, Source.CACHE, null) }
            }
        }
        // 2. Network - only cache bodies that actually parse, so one bad 200 can't poison the cache.
        runCatching { fetch() }.getOrNull()?.let { fetched ->
            tryParse(fetched)?.let {
                cache.write(fetched)
                return@withContext CatalogResult(it, Source.NETWORK, null)
            }
        }
        // 3. Stale cache.
        cache.read()?.let { cached ->
            tryParse(cached)?.let { return@withContext CatalogResult(it, Source.CACHE, cache.ageMillis()) }
        }
        // 4. Bundled snapshot - last resort.
        bundledFallback()?.let { bundled ->
            tryParse(bundled)?.let { return@withContext CatalogResult(it, Source.BUNDLED, null) }
        }
        error("models.dev catalog unavailable: network failed and no usable cache or bundled fallback")
    }

    /** Groups a catalog for the picker, providers and models sorted by display name. */
    fun grouped(catalog: Catalog): List<ProviderGroup> =
        catalog.entries
            .map { (id, info) -> ProviderGroup(id, info.name, info.models.values.sortedBy { it.name }) }
            .sortedBy { it.displayName }

    private fun tryParse(text: String): Catalog? =
        runCatching { catalogJson.decodeFromString<Catalog>(text) }.getOrNull()

    private fun fetch(): String {
        val request = Request.Builder().url(catalogUrl).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("models.dev returned HTTP ${response.code}")
            return response.body?.string() ?: error("models.dev returned an empty body")
        }
    }
}
