package dev.phonecode.app.ui

import dev.phonecode.app.data.SecureKeyStore
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

@Implements(SecureKeyStore::class, isInAndroidSdk = false)
class UiTestSecureKeyStore {
    @Implementation
    fun get(name: String): String? {
        val blockedName = blockedReadName
        val started = blockedReadStarted
        val release = blockedReadRelease
        if (name == blockedName && started != null && release != null) {
            blockedReadName = null
            blockedReadStarted = null
            blockedReadRelease = null
            started.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                "Timed out waiting to release secure storage read"
            }
        }
        return values[name]
    }

    @Implementation
    fun put(name: String, value: String) {
        beforeWrite()
        if (value.isBlank()) values.remove(name) else values[name] = value
    }

    @Implementation
    fun putAll(entries: Map<String, String>) {
        beforeWrite()
        entries.forEach { (name, value) ->
            if (value.isBlank()) values.remove(name) else values[name] = value
        }
    }

    @Implementation
    fun getAvailable() = true

    @Implementation
    fun getSecureStorageUnavailable() = false

    companion object {
        private val values = ConcurrentHashMap<String, String>()
        @Volatile private var failNextWrite = false
        @Volatile private var blockedWriteStarted: CountDownLatch? = null
        @Volatile private var blockedWriteRelease: CountDownLatch? = null
        @Volatile private var blockedReadName: String? = null
        @Volatile private var blockedReadStarted: CountDownLatch? = null
        @Volatile private var blockedReadRelease: CountDownLatch? = null

        fun clear() {
            values.clear()
            failNextWrite = false
            blockedWriteStarted = null
            blockedWriteRelease = null
            blockedReadName = null
            blockedReadStarted = null
            blockedReadRelease = null
        }

        fun replaceWith(entries: Map<String, String>) {
            values.clear()
            values.putAll(entries)
            failNextWrite = false
            blockedWriteStarted = null
            blockedWriteRelease = null
            blockedReadName = null
            blockedReadStarted = null
            blockedReadRelease = null
        }

        fun stored(name: String): String? = values[name]

        fun failNextWrite() {
            failNextWrite = true
        }

        fun blockNextWrite(started: CountDownLatch, release: CountDownLatch) {
            blockedWriteStarted = started
            blockedWriteRelease = release
        }

        fun blockNextRead(name: String, started: CountDownLatch, release: CountDownLatch) {
            blockedReadName = name
            blockedReadStarted = started
            blockedReadRelease = release
        }

        private fun beforeWrite() {
            val started = blockedWriteStarted
            val release = blockedWriteRelease
            if (started != null && release != null) {
                blockedWriteStarted = null
                blockedWriteRelease = null
                started.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    "Timed out waiting to release secure storage write"
                }
            }
            if (failNextWrite) {
                failNextWrite = false
                error("Secure storage update failed")
            }
        }
    }
}
