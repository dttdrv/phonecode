package dev.phonecode.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SharedFolderStoreTest {
    @Test fun persistsAndRemovesFolders() {
        val dir = Files.createTempDirectory("shared-folders").toFile()
        try {
            val file = File(dir, "folders.json")
            val store = SharedFolderStore(file)
            store.add(SharedFolder("one", "Documents", "android-tree", "content://documents", true))
            assertEquals("Documents", SharedFolderStore(file).list().single().name)
            assertEquals(emptyList<SharedFolder>(), store.remove("one"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun oneHandleCreatesOneGrant() {
        val dir = Files.createTempDirectory("shared-folder-grant").toFile()
        try {
            val store = SharedFolderStore(File(dir, "folders.json"))
            store.add(SharedFolder("one", "Old", "android-tree", "content://same", true))
            val folders = store.add(SharedFolder("two", "New", "android-tree", "content://same", false))
            assertEquals(1, folders.size)
            assertEquals("New", folders.single().name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun projectKeepsFolderLink() {
        val dir = Files.createTempDirectory("project-folder").toFile()
        try {
            val file = File(dir, "projects.json")
            ProjectStore(file).add("project-one", "Documents", "folder-one")
            assertEquals("folder-one", ProjectStore(file).list().single().folderId)
        } finally {
            dir.deleteRecursively()
        }
    }
}
