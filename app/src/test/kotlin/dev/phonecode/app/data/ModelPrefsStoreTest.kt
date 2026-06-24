package dev.phonecode.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ModelPrefsStoreTest {

    private lateinit var dir: File
    private lateinit var store: ModelPrefsStore

    @Before fun setUp() {
        dir = Files.createTempDirectory("modelprefs").toFile()
        store = ModelPrefsStore(File(dir, "prefs.json"))
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test fun togglesFavouriteAndPersists() {
        assertTrue(store.favourites().isEmpty())
        store.toggleFavourite("anthropic/claude-opus-4-8")
        assertTrue(store.favourites().contains("anthropic/claude-opus-4-8"))
        // A fresh instance over the same file sees it (persisted).
        assertTrue(ModelPrefsStore(File(dir, "prefs.json")).favourites().contains("anthropic/claude-opus-4-8"))
        store.toggleFavourite("anthropic/claude-opus-4-8")
        assertFalse(store.favourites().contains("anthropic/claude-opus-4-8"))
    }

    @Test fun recentsAreDedupedNewestFirstAndCapped() {
        store.recordRecent("a")
        store.recordRecent("b")
        store.recordRecent("a") // re-touch moves to front, no dup
        assertEquals(listOf("a", "b"), store.recents())
        repeat(10) { store.recordRecent("m$it") }
        assertEquals(5, store.recents().size)
        assertEquals("m9", store.recents().first())
    }
}
