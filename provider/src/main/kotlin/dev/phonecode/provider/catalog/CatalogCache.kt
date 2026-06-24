package dev.phonecode.provider.catalog

/**
 * Abstracts the on-disk cache of the raw models.dev JSON, so `:provider` stays
 * free of any `android.content.Context`. `:app` supplies a file-backed impl
 * (cacheDir); tests supply an in-memory fake.
 */
interface CatalogCache {
    fun read(): String?
    fun write(json: String)

    /** Age of the cached entry in milliseconds, or null if absent. */
    fun ageMillis(): Long?
}
