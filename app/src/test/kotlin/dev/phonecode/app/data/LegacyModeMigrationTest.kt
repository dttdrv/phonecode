package dev.phonecode.app.data

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LegacyModeMigrationTest {
    private val root = Files.createTempDirectory("legacy-mode-migration").toFile()

    @After fun tearDown() {
        root.deleteRecursively()
    }

    @Test fun legacyDefaultModeIsIgnoredAndRemovedOnNextSave() {
        val file = File(root, "app_settings.json")
        file.writeText("""{"themeMode":"DARK","defaultMode":"PLAN","sendOnEnter":false}""")
        val store = AppSettingsStore(file)

        val loaded = store.load()
        assertEquals(ThemeMode.DARK, loaded.mode)
        assertFalse(loaded.sendOnEnter)

        store.save(loaded)
        assertFalse(file.readText().contains("defaultMode"))
    }

    @Test fun legacySessionModeIsIgnoredAndRemovedOnNextSave() {
        val sessions = File(root, "sessions").apply { mkdirs() }
        val file = File(sessions, "legacy.json")
        file.writeText(
            """{"id":"legacy","title":"Existing chat","updatedAt":1,"messages":[],"agentMode":"PLAN"}""",
        )
        val store = SessionStore(sessions)

        val loaded = requireNotNull(store.load("legacy"))
        assertEquals("Existing chat", loaded.title)

        store.save(loaded)
        assertFalse(file.readText().contains("agentMode"))
    }
}
