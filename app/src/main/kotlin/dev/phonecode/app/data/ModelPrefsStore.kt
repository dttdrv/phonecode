package dev.phonecode.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists model-catalog preferences in a small JSON file: favourites + recents (keyed
 * "providerId/modelId"), models the user hid from the picker, and providers turned off entirely.
 * All access serializes on a process-wide lock (multiple readers/writers on Dispatchers.IO).
 */
class ModelPrefsStore(private val file: File) {
    @Serializable
    private data class Prefs(
        val favourites: List<String> = emptyList(),
        val recents: List<String> = emptyList(),
        val hiddenModels: List<String> = emptyList(),
        val disabledProviders: List<String> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun load(): Prefs =
        if (file.exists()) runCatching { json.decodeFromString(Prefs.serializer(), file.readText()) }.getOrDefault(Prefs()) else Prefs()

    private fun save(p: Prefs) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(Prefs.serializer(), p))
    }

    private inline fun <T> locked(block: () -> T): T = synchronized(LOCK, block)

    fun favourites(): Set<String> = locked { load().favourites.toSet() }

    /** Toggle [key]'s favourite status; returns the new favourite set. */
    fun toggleFavourite(key: String): Set<String> = locked {
        val p = load()
        val f = p.favourites.toMutableList()
        if (!f.remove(key)) f.add(key)
        save(p.copy(favourites = f))
        f.toSet()
    }

    fun recents(): List<String> = locked { load().recents }

    /** Record [key] as most-recent (deduped, capped at [max]); returns the new recents list. */
    fun recordRecent(key: String, max: Int = 5): List<String> = locked {
        val p = load()
        val r = (listOf(key) + p.recents.filter { it != key }).take(max)
        save(p.copy(recents = r))
        r
    }

    fun hiddenModels(): Set<String> = locked { load().hiddenModels.toSet() }

    /** Toggle whether "providerId/modelId" [key] is hidden from the picker; returns the new hidden set. */
    fun toggleHidden(key: String): Set<String> = locked {
        val p = load()
        val h = p.hiddenModels.toMutableList()
        if (!h.remove(key)) h.add(key)
        save(p.copy(hiddenModels = h))
        h.toSet()
    }

    /** Bulk-set visibility for [keys] ("All on" / "All off"); returns the new hidden set. */
    fun setHidden(keys: Collection<String>, hidden: Boolean): Set<String> = locked {
        val p = load()
        val h = p.hiddenModels.toMutableSet()
        if (hidden) h.addAll(keys) else h.removeAll(keys.toSet())
        save(p.copy(hiddenModels = h.toList()))
        h.toSet()
    }

    fun disabledProviders(): Set<String> = locked { load().disabledProviders.toSet() }

    /** Toggle whether provider [id] is disabled (hidden from the picker entirely); returns the new set. */
    fun toggleProviderDisabled(id: String): Set<String> = locked {
        val p = load()
        val d = p.disabledProviders.toMutableList()
        if (!d.remove(id)) d.add(id)
        save(p.copy(disabledProviders = d))
        d.toSet()
    }

    private companion object {
        val LOCK = Any()
    }
}
