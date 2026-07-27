package dev.phonecode.app.data

import dev.phonecode.provider.domain.ChatMessage
import dev.phonecode.provider.domain.MessagePart
import dev.phonecode.provider.domain.Role
import dev.phonecode.tools.todo.TodoItem
import dev.phonecode.tools.todo.TodoPriority
import dev.phonecode.tools.todo.TodoStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        assertEquals("hi there", store.list().single().preview)
    }

    @Test fun roundTripsTerminalTurnOutcomeThroughPersistence() {
        val outcomeField = PersistedSession::class.java.declaredFields
            .firstOrNull { it.name == "turnOutcome" }
        assertNotNull("Persisted sessions must retain a stopped or failed turn outcome", outcomeField)
        outcomeField!!.isAccessible = true
        val session = PersistedSession("outcome", "Stopped chat", 1000L, sample.map { it.toPersisted() })
        outcomeField.set(session, "STOPPED")

        store.save(session)

        assertEquals("STOPPED", outcomeField.get(store.load("outcome")))
    }

    @Test fun roundTripsRecoverableFollowUpsThroughPersistence() {
        val queuedField = PersistedSession::class.java.declaredFields
            .firstOrNull { it.name == "queuedMessages" }
        assertNotNull("Persisted sessions must retain recoverable follow-ups", queuedField)
        queuedField!!.isAccessible = true
        val session = PersistedSession("queued", "Queued chat", 1000L, sample.map { it.toPersisted() })
        queuedField.set(session, listOf("first unsent follow-up", "second unsent follow-up"))

        store.save(session)

        assertEquals(
            listOf("first unsent follow-up", "second unsent follow-up"),
            queuedField.get(store.load("queued")),
        )
    }

    @Test fun listReturnsMetaNewestFirst() {
        store.save(PersistedSession("a", "A", 100L, emptyList()))
        store.save(PersistedSession("b", "B", 300L, emptyList()))
        store.save(PersistedSession("c", "C", 200L, emptyList()))
        assertEquals(listOf("b", "c", "a"), store.list().map { it.id })
    }

    @Test fun externalRestoreInvalidatesCatalogBeforeNormalizationReads() {
        store.save(PersistedSession("old", "Old", 1L, emptyList()))
        assertEquals("old", store.list().single().id) // populate the catalog cache

        store.reconcileExternalRestore(writeOrderBoundary = 1L) {
            File(dir, "old.json").delete()
            SessionStore(dir).save(PersistedSession("restored", "Restored", 2L, emptyList()))
            assertEquals("restored", store.list().single().id)
        }

        assertEquals("restored", store.list().single().id)
    }

    @Test fun deleteRemovesSession() {
        store.save(PersistedSession("x", "X", 1L, emptyList()))
        store.delete("x")
        assertNull(store.load("x"))
        assertTrue(store.list().isEmpty())
    }

    @Test fun createNeverOverwritesAnExistingCheckpoint() {
        store.save(PersistedSession("x", "Started", 2L, sample.map { it.toPersisted() }))

        assertFalse(store.create(PersistedSession("x", "New chat", 1L, emptyList())))
        assertEquals(sample, store.load("x")!!.messages.map { it.toDomain() })
    }

    @Test fun checkpointCreatesFirstSave() {
        val todos = listOf(TodoItem("1", "Inspect", TodoStatus.IN_PROGRESS, TodoPriority.HIGH))
        val checkpoint = PersistedSession(
            "new",
            "First title",
            2L,
            sample.map { it.toPersisted() },
            "project",
            activeTurn = true,
            todos = todos,
        )

        assertEquals(checkpoint, store.checkpoint(checkpoint))
        assertEquals(checkpoint, store.load("new"))
    }

    @Test fun transcriptCheckpointCannotOverwriteIndependentlySavedAgentMode() {
        val initial = PersistedSession(
            id = "mode-race",
            title = "Mode race",
            updatedAt = 1L,
            messages = sample.map { it.toPersisted() },
            agentMode = "PLAN",
        )
        store.save(initial)
        store.setAgentMode(initial.id, "BUILD")

        store.checkpoint(
            initial.copy(
                updatedAt = 2L,
                messages = sample.dropLast(1).map { it.toPersisted() },
                agentMode = "PLAN",
            ),
        )

        assertEquals("BUILD", store.load(initial.id)?.agentMode)
    }

    @Test fun lateCancellationCannotRollbackImmediateResendCheckpoint() {
        val first = PersistedMessage(PersistedRole.USER, listOf(PersistedPart.Text("first")))
        val second = PersistedMessage(PersistedRole.USER, listOf(PersistedPart.Text("second")))
        store.save(PersistedSession("race", "First", 1L, listOf(first), activeTurn = true))

        val cancelled = PersistedSession("race", "First", 2L, listOf(first), activeTurn = false)
        val resent = PersistedSession("race", "First", 3L, listOf(first, second), activeTurn = true)
        store.checkpoint(resent, writeOrder = 2L)
        store.checkpoint(cancelled, writeOrder = 1L)

        assertEquals(resent, store.load("race"))
    }

    @Test fun lateCheckpointCannotRecreateDeletedSession() {
        val initial = PersistedSession("race", "First", 1L, sample.map { it.toPersisted() }, activeTurn = true)
        store.checkpoint(initial, writeOrder = 1L)
        val staleWriter = SessionStore(dir)

        store.delete("race")
        staleWriter.checkpoint(initial.copy(updatedAt = 2L, activeTurn = false), writeOrder = 2L)
        staleWriter.setActiveTurn("race", false, writeOrder = 3L)

        assertNull(store.load("race"))
        assertFalse(File(dir, "race.json").exists())
    }

    @Test fun explicitCreateClearsDeletionTombstone() {
        store.save(PersistedSession("race", "Old", 1L, emptyList()))
        store.delete("race")
        val replacement = PersistedSession("race", "Replacement", 2L, emptyList())

        assertTrue(store.create(replacement))
        val updated = replacement.copy(updatedAt = 3L, messages = sample.map { it.toPersisted() })
        store.checkpoint(updated, writeOrder = 1L)

        assertEquals(updated, store.load("race"))
    }

    @Test fun externalRestoreRejectsStaleCheckpointsAcrossInstances() {
        val stale = PersistedSession("race", "Stale", 1L, sample.map { it.toPersisted() }, activeTurn = true)
        store.save(stale)
        val staleWriter = SessionStore(dir)
        store.delete("race")
        val restored = PersistedSession("race", "Restored", 2L, emptyList())

        store.reconcileExternalRestore(10L) {
            File(dir, "race.json").writeText(storeJson.encodeToString(PersistedSession.serializer(), restored))
        }
        staleWriter.checkpoint(stale.copy(updatedAt = 3L), writeOrder = 9L)
        staleWriter.checkpoint(stale.copy(updatedAt = 4L))

        assertEquals(restored, store.load("race"))
    }

    @Test fun externalRestoreAcceptsFutureOrderedCheckpoint() {
        val restored = PersistedSession("race", "Restored", 1L, emptyList())
        store.reconcileExternalRestore(10L) {
            File(dir, "race.json").writeText(storeJson.encodeToString(PersistedSession.serializer(), restored))
        }
        val updated = restored.copy(updatedAt = 2L, messages = sample.map { it.toPersisted() })

        SessionStore(dir).checkpoint(updated, writeOrder = 11L)

        assertEquals(updated, store.load("race"))
    }

    @Test fun checkpointCannotLoseConcurrentRenameOrPin() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(100) { index ->
                store.save(
                    PersistedSession(
                        "race",
                        "Original",
                        index.toLong(),
                        emptyList(),
                        "project",
                        archived = true,
                    ),
                )
                val start = CountDownLatch(1)
                val todos = listOf(TodoItem("1", "Inspect", TodoStatus.IN_PROGRESS, TodoPriority.HIGH))
                val transcript = executor.submit {
                    start.await()
                    store.checkpoint(
                        PersistedSession(
                            "race",
                            "Stale title",
                            index + 1L,
                            sample.map { it.toPersisted() },
                            activeTurn = true,
                            todos = todos,
                        ),
                    )
                }
                val metadata = executor.submit {
                    start.await()
                    store.rename("race", "Renamed")
                    store.setPinned("race", true)
                }
                start.countDown()
                transcript.get(5, TimeUnit.SECONDS)
                metadata.get(5, TimeUnit.SECONDS)

                val saved = store.load("race")!!
                assertEquals("Renamed", saved.title)
                assertTrue(saved.pinned)
                assertEquals("project", saved.projectId)
                assertTrue(saved.archived)
                assertEquals(index + 1L, saved.updatedAt)
                assertEquals(sample, saved.messages.map { it.toDomain() })
                assertTrue(saved.activeTurn)
                assertEquals(todos, saved.todos)
            }
        } finally {
            executor.shutdownNow()
        }
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

    @Test fun persistsActiveTurnAndDefaultsLegacySessionsToInactive() {
        store.save(PersistedSession("active", "Active", 1L, emptyList(), activeTurn = true))
        assertTrue(store.load("active")!!.activeTurn)

        File(dir, "legacy.json").writeText("""{"id":"legacy","title":"Legacy","updatedAt":1,"messages":[]}""")
        assertFalse(store.load("legacy")!!.activeTurn)
    }

    @Test fun changingActiveTurnPreservesSessionMetadata() {
        store.save(PersistedSession("x", "X", 1L, emptyList(), "project", pinned = true, archived = true))
        store.setActiveTurn("x", true)
        val session = store.load("x")!!
        assertEquals("project", session.projectId)
        assertTrue(session.pinned)
        assertTrue(session.archived)
        assertTrue(session.activeTurn)
    }

    @Test fun persistsSessionTodoState() {
        val todos = listOf(TodoItem("1", "Verify the build", TodoStatus.IN_PROGRESS, TodoPriority.HIGH))

        store.save(PersistedSession("todo", "Todo", 1L, emptyList(), todos = todos))

        assertEquals(todos, store.load("todo")!!.todos)
    }

    @Test fun rejectsInvalidSessionIds() {
        assertNull(store.load("../../config"))
        assertThrows(IllegalArgumentException::class.java) {
            store.save(PersistedSession("../../config", "Bad", 1L, emptyList()))
        }
    }
}
