package dev.phonecode.app.ui

import dev.phonecode.app.data.SecureKeyStore
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.util.concurrent.ConcurrentHashMap

@Implements(SecureKeyStore::class, isInAndroidSdk = false)
class UiTestSecureKeyStore {
    @Implementation
    fun get(name: String): String? = values[name]

    @Implementation
    fun put(name: String, value: String) {
        if (value.isBlank()) values.remove(name) else values[name] = value
    }

    @Implementation
    fun getAvailable() = true

    @Implementation
    fun getSecureStorageUnavailable() = false

    companion object {
        private val values = ConcurrentHashMap<String, String>()

        fun clear() = values.clear()

        fun replaceWith(entries: Map<String, String>) {
            values.clear()
            values.putAll(entries)
        }

        fun stored(name: String): String? = values[name]
    }
}
