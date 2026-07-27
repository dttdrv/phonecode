package dev.phonecode.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

interface SecretValueStore {
    val available: Boolean
    fun get(name: String): String?
    fun put(name: String, value: String)
}

class SecureKeyStore(context: Context) : SecretValueStore {
    private val prefs = createPrefs(context)
    override val available = prefs != null
    val secureStorageUnavailable = !available

    override fun get(name: String): String? = prefs?.getString(name, null)

    override fun put(name: String, value: String) {
        putAll(mapOf(name to value))
    }

    fun putAll(values: Map<String, String>) {
        val editor = checkNotNull(prefs) { "Secure storage is unavailable on this device" }.edit()
        values.forEach { (name, value) ->
            if (value.isBlank()) editor.remove(name) else editor.putString(name, value)
        }
        check(editor.commit()) { "Secure storage update failed" }
    }

    private companion object {
        private const val FILE = "phonecode_provider_keys"

        fun createPrefs(context: Context): SharedPreferences? = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            build(context, masterKey)
        } catch (e: Exception) {
            null
        }

        private fun build(context: Context, masterKey: MasterKey) = EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
