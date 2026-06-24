package dev.phonecode.app.data

import dev.phonecode.provider.catalog.CatalogCache
import java.io.File

/** Disk cache for the models.dev catalog (raw JSON) under the app cache dir. */
class FileCatalogCache(cacheDir: File) : CatalogCache {
    private val file = File(cacheDir, "models-dev.json")

    override fun read(): String? =
        if (file.exists()) runCatching { file.readText() }.getOrNull() else null

    override fun write(json: String) {
        runCatching { file.writeText(json) }
    }

    override fun ageMillis(): Long? =
        if (file.exists()) System.currentTimeMillis() - file.lastModified() else null
}
