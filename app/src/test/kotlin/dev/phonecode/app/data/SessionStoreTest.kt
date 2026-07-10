package dev.phonecode.app.data

import dev.phonecode.provider.domain.ChatMessage
import dev.phonecode.provider.domain.MessagePart
import dev.phonecode.provider.domain.Role
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionStoreTest {

    private lateinit var dir: File
    private lateinit var store: SessionStore

    @Before fun setUp() {
        dir = Files.createTempDirectory("sessions-test").toFile()
        store = SessionStore(dir)
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private val sample = listOf(
        ChatMessage(Role.USER, listOf(MessagePart.Text("hello"), MessagePart.Image("image/jpeg", "AQID"))),
        ChatMessage(
            Role.ASSISTANT,
            listOf(
                MessagePart.Reasoning("thinking"),
                MessagePart.Text("hi there"),
                MessagePart.ToolCall("c1", "read", """{"path":"a.kt"}"""),
            ),
        ),
        ChatMessage(Role.USER, listOf(MessagePart.ToolResult("c1", "file body", isError = false))),
    )

    @Test fun roundTripsEveryPartTypeThroughPersistence() {
        val session = PersistedSession("session-1", "First chat", 1000L, sample.map { it.toPersisted() })
        store.save(session)
        val restored = store.load("session-1")!!.messages.map { it.toDomain() }
        assertEquals(sample, restored) // data-class equality proves every field survived the round trip
    }

    @Test fun listReturnsMetaNewestFirst() {
        store.save(PersistedSession("a", "A", 100L, emptyList()))
        store.save(PersistedSession("b", "B", 300L, emptyList()))
        store.save(PersistedSession("c", "C", 200L, emptyList()))
        assertEquals(listOf("b", "c", "a"), store.list().map { it.id })
    }

    @Test fun deleteRemovesSession() {
        store.save(PersistedSession("x", "X", 1L, emptyList()))
        store.delete("x")
        assertNull(store.load("x"))
        assertTrue(store.list().isEmpty())
    }

    @Test fun loadMissingReturnsNull() {
        assertNull(store.load("nope"))
    }

    @Test fun loadLatestReturnsMostRecentlyWritten() {
        // loadLatest backs the at-startup restore; it must return the conversation last saved (the one the
        // user was in), so a relaunch after a process kill continues it instead of starting blank.
        assertNull(store.loadLatest())
        store.save(PersistedSession("old", "Old", 100L, emptyList()))
        store.save(PersistedSession("current", "Current", 200L, sample.map { it.toPersisted() }))
        // Pin mtimes so the assertion can't flake on a filesystem with coarse timestamp resolution.
        File(dir, "old.json").setLastModified(1_000L)
        File(dir, "current.json").setLastModified(2_000L)
        val latest = store.loadLatest()!!
        assertEquals("current", latest.id)
        assertEquals(sample, latest.messages.map { it.toDomain() })
    }

    @Test fun toleratesCorruptFile() {
        File(dir, "broken.json").writeText("{ not valid json")
        // A corrupt file must not crash listing; it is simply skipped.
        assertTrue(store.list().isEmpty())
    }
}
