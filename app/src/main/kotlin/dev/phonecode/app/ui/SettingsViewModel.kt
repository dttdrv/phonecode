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
import java.io.File

/** App-level settings (theme mode, custom instructions, toggles) for the root theme + settings pages. */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = AppSettingsStore(File(app.filesDir, "app_settings.json"))

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    // True once the on-disk settings have actually been read - gates first-run UI (onboarding)
    // so the unloaded default (onboarded=false) never flashes the overlay for existing users.
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _settings.value = store.load()
            _loaded.value = true
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch(Dispatchers.IO) { _settings.value = store.update(transform) }
    }

    /** Re-reads settings from disk - called after a backup import overwrites app_settings.json. */
    fun reload() {
        viewModelScope.launch(Dispatchers.IO) { _settings.value = store.load() }
    }
}
