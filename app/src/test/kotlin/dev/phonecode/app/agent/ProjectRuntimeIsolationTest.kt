package dev.phonecode.app.agent

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectRuntimeIsolationTest {
    private val root = generateSequence(
        File(requireNotNull(System.getProperty("user.dir"))).absoluteFile,
    ) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    @Test fun nativeStateIsNamespacedByPhoneCodeProject() {
        val filesDir = createTempDirectory("phonecode-runtime-state").toFile()

        val alpha = misulStateRoot(filesDir, "project-alpha")
        val beta = misulStateRoot(filesDir, "project-beta")
        val unassigned = misulStateRoot(filesDir, null)

        assertEquals(File(filesDir, "misul/state/projects/project-alpha").canonicalFile, alpha)
        assertEquals(File(filesDir, "misul/state/projects/project-beta").canonicalFile, beta)
        assertEquals(File(filesDir, "misul/state/unassigned").canonicalFile, unassigned)
        assertNotEquals(alpha, beta)
        assertNotEquals(alpha, unassigned)
        assertNotEquals(unassigned, misulStateRoot(filesDir, "unassigned"))
    }

    @Test fun invalidProjectIdCannotEscapeNativeStateRoot() {
        val filesDir = createTempDirectory("phonecode-runtime-state").toFile()

        listOf("../escape", "/absolute", "nested/project", "", ".").forEach { projectId ->
            val result = runCatching { misulStateRoot(filesDir, projectId) }
            assertTrue("Expected invalid project id to fail: $projectId", result.isFailure)
        }
    }

    @Test fun symlinkCannotRedirectProjectStateOutsideNativeRoot() {
        val filesDir = createTempDirectory("phonecode-runtime-state").toFile()
        val stateRoot = File(filesDir, "misul/state").apply { mkdirs() }
        val outside = createTempDirectory("phonecode-runtime-outside").toFile()
        Files.createSymbolicLink(File(stateRoot, "projects").toPath(), outside.toPath())

        val result = runCatching { misulStateRoot(filesDir, "project-alpha") }

        assertTrue(result.isFailure)
    }

    @Test fun onlyTheActiveSessionIsImportedIntoItsProjectRuntime() {
        val source = File(
            root,
            "app/src/main/kotlin/dev/phonecode/app/agent/ChatViewModel.kt",
        ).readText()
        val importer = source.substringAfter("    private suspend fun importActiveSession(")
            .substringBefore("\n    private fun fail(")

        assertTrue(importer.contains("sessionStore.load(activeSessionId)"))
        assertFalse(importer.contains("sessionStore.list()"))
        assertFalse(importer.contains("LEGACY_SESSION_IMPORT_RECEIPT"))
        assertTrue(importer.contains("toBoundedMisulImportSession("))
    }
}
