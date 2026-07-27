package dev.phonecode.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AppSettingsStoreTest {

    @Test
    fun restoredSettingsNeverElevateApprovalWithoutFreshConsent() {
        val restored = AppSettings(autoAccept = true).safeAfterRestore()

        assertFalse(restored.autoAccept)
    }

    @Test fun defaultsAndPersistence() {
        val dir = Files.createTempDirectory("appsettings").toFile()
        try {
            val store = AppSettingsStore(File(dir, "settings.json"))
            assertEquals(ThemeMode.SYSTEM, store.load().mode)
            assertTrue(store.load().sendOnEnter)

            store.update { it.copy(themeMode = "DARK", customInstructions = "be terse", autoAccept = true, activeSessionId = "session-a") }

            val reloaded = AppSettingsStore(File(dir, "settings.json")).load()
            assertEquals(ThemeMode.DARK, reloaded.mode)
            assertEquals("be terse", reloaded.customInstructions)
            assertTrue(reloaded.autoAccept)
            assertEquals("session-a", reloaded.activeSessionId)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun unknownModeFallsBackToSystem() {
        val dir = Files.createTempDirectory("appsettings2").toFile()
        try {
            val store = AppSettingsStore(File(dir, "s.json"))
            store.save(AppSettings(themeMode = "GARBAGE"))
            assertEquals(ThemeMode.SYSTEM, store.load().mode)
        } finally {
            dir.deleteRecursively()
        }
    }
}
