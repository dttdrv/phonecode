package dev.phonecode.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.phonecode.app.data.AppSettingsStore
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelTest {
    @Test
    fun settingEditPreservesSessionChangedByAnotherOwner() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File(app.filesDir, "app_settings.json")
        file.delete()
        val store = AppSettingsStore(file)
        val vm = SettingsViewModel(app)
        try {
            waitUntil { vm.loaded.value }
            store.update { it.copy(activeSessionId = "session-new") }

            vm.update { it.copy(themeMode = "DARK") }

            waitUntil { store.load().themeMode == "DARK" }
            assertEquals("session-new", store.load().activeSessionId)
        } finally {
            file.delete()
        }
    }

    @Test
    fun awaitedProfileSaveReturnsOnlyAfterLocalPersistence() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File(app.filesDir, "app_settings.json")
        file.delete()
        val vm = SettingsViewModel(app)
        try {
            waitUntil { vm.loaded.value }

            val result = vm.updateAndWait { it.copy(preferredName = "Alex", occupation = "Designer") }

            assertTrue(result.isSuccess)
            assertEquals("Alex", AppSettingsStore(file).load().preferredName)
            assertEquals("Designer", vm.settings.value.occupation)
        } finally {
            file.delete()
        }
    }

    @Test
    fun failedProfileSaveLeavesDraftUnsaved() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File(app.filesDir, "app_settings.json")
        file.deleteRecursively()
        file.mkdir()
        val vm = SettingsViewModel(app)
        try {
            waitUntil { vm.loaded.value }

            val result = vm.updateAndWait { it.copy(preferredName = "Alex") }

            assertTrue(result.isFailure)
            assertEquals("", vm.settings.value.preferredName)
        } finally {
            file.deleteRecursively()
        }
    }

    private suspend fun waitUntil(predicate: () -> Boolean) {
        repeat(200) {
            if (predicate()) return
            delay(10)
        }
        error("condition was not reached")
    }
}
