package dev.phonecode.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.phonecode.app.data.AppSettings
import dev.phonecode.app.data.AppSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** App-level settings (theme mode, custom instructions, toggles) for the root theme + settings pages. */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = AppSettingsStore(File(app.filesDir, "app_settings.json"))

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    // One ordered write queue off the main thread: a queued write must never wait for the main
    // looper to release the previous one, or a later toggle is lost when the UI thread is busy.
    private val writes = Dispatchers.IO.limitedParallelism(1)

    // True once the on-disk settings have actually been read - gates first-run UI (onboarding)
    // so the unloaded default (onboarded=false) never flashes the overlay for existing users.
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        val initial = _settings.value
        viewModelScope.launch(writes) {
            // An edit made before this read already queued a write whose result replaces it.
            _settings.compareAndSet(initial, store.load())
            _loaded.value = true
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val previous = _settings.value
        val updated = transform(previous)
        _settings.value = updated
        viewModelScope.launch(writes) {
            runCatching {
                // Apply the field-level edit to the latest persisted record. ChatViewModel owns
                // activeSessionId and may have changed it since this screen loaded; saving our
                // whole snapshot would silently resurrect the previous chat on next launch.
                store.update(transform)
            }.onSuccess { persisted ->
                _settings.compareAndSet(updated, persisted)
            }.onFailure {
                _settings.compareAndSet(updated, previous)
            }
        }
    }

    /** Saves an editor's draft before its screen reports success and closes. */
    suspend fun updateAndWait(transform: (AppSettings) -> AppSettings): Result<AppSettings> =
        withContext(writes) { runCatching { store.update(transform) } }
            .onSuccess { _settings.value = it }

    /** Re-reads settings from disk - called after a backup import overwrites app_settings.json. */
    fun reload() {
        viewModelScope.launch(Dispatchers.IO) { _settings.value = store.load() }
    }
}
