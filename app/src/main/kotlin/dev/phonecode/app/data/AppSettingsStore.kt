package dev.phonecode.app.data

import kotlinx.serialization.Serializable
import java.io.File

/** Light / Dark follow the explicit choice; System tracks the device setting. */
enum class ThemeMode { LIGHT, DARK, SYSTEM }

@Serializable
data class AppSettings(
    val themeMode: String = "SYSTEM",
    val customInstructions: String = "",
    val autoAccept: Boolean = false,
    val sendOnEnter: Boolean = true,
    val gitAutoBranch: Boolean = false,
    val defaultMode: String = "BUILD",
    /** First-run onboarding shown and dismissed (round-4). */
    val onboarded: Boolean = false,
    val activeSessionId: String? = null,
) {
    val mode: ThemeMode get() = runCatching { ThemeMode.valueOf(themeMode) }.getOrDefault(ThemeMode.SYSTEM)
}

/** Restoring a backup must never silently elevate the authority granted to the agent. */
fun AppSettings.safeAfterRestore(): AppSettings = copy(autoAccept = false)

/**
 * App-level preferences (theme, custom instructions, toggles), persisted as one small JSON file.
 * All access serializes on a process-wide lock so multiple store instances over the same file
 * (ChatViewModel + SettingsViewModel) can't interleave a load→save cycle and lose an update.
 */
class AppSettingsStore(private val file: File) {
    private val json = storeJson

    fun load(): AppSettings = synchronized(LOCK) { loadLocked() }

    fun save(settings: AppSettings) = synchronized(LOCK) { saveLocked(settings) }

    /** Atomically load, apply [transform], save, and return the updated settings. */
    fun update(transform: (AppSettings) -> AppSettings): AppSettings = synchronized(LOCK) {
        val updated = transform(loadLocked())
        saveLocked(updated)
        updated
    }

    private fun loadLocked(): AppSettings =
        if (file.exists()) runCatching { json.decodeFromString(AppSettings.serializer(), file.readText()) }.getOrDefault(AppSettings())
        else AppSettings()

    private fun saveLocked(settings: AppSettings) {
        file.parentFile?.mkdirs()
        file.writeTextAtomically(json.encodeToString(AppSettings.serializer(), settings))
    }

    private companion object {
        val LOCK = Any()
    }
}
