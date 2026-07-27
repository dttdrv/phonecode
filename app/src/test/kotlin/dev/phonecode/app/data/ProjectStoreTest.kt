package dev.phonecode.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class ProjectStoreTest {
    @Test fun restoredProjectsDropFolderReferencesWithoutAGrant() {
        val projects = listOf(
            Project("one", "One", "still-linked"),
            Project("two", "Two", "missing-grant"),
            Project("three", "Three"),
        )

        assertEquals(
            listOf(
                Project("one", "One", "still-linked"),
                Project("two", "Two"),
                Project("three", "Three"),
            ),
            projects.safeAfterRestore(setOf("still-linked")),
        )
    }

    @Test fun restoredChatsWithUnknownProjectsBecomeUnsorted() {
        assertEquals("known", "known".safeProjectAfterRestore(setOf("known")))
        assertEquals(null, "missing".safeProjectAfterRestore(setOf("known")))
    }

    @Test fun replaceRestoresTheExactProjectSnapshot() {
        val dir = Files.createTempDirectory("project-store-test").toFile()
        try {
            val store = ProjectStore(dir.resolve("projects.json"))
            val original = listOf(
                Project("project-one", "One", "folder-one"),
                Project("project-two", "Two", "folder-two"),
            )
            store.replace(original)
            store.delete("project-one")
            store.replace(original)
            assertEquals(original, store.list())
        } finally {
            dir.deleteRecursively()
        }
    }
}
