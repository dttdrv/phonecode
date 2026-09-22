package dev.phonecode.app.agent

import dev.phonecode.app.data.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProjectInstructionsTest {
    private lateinit var workspace: File

    @Before fun setUp() {
        workspace = Files.createTempDirectory("phonecode-instructions").toFile()
    }

    @After fun tearDown() {
        workspace.deleteRecursively()
    }

    @Test fun loadsPreferencesAndRootFilesInDeterministicOrder() {
        File(workspace, "AGENTS.md").writeText("Use the formatter.")
        File(workspace, "CLAUDE.md").writeText("Run tests.")

        val instructions = loadProjectInstructions(workspace, "Keep changes small.")

        assertEquals(3, instructions.size)
        assertTrue(instructions[0].startsWith("PhoneCode preferences:"))
        assertTrue(instructions[1].startsWith("AGENTS.md:"))
        assertTrue(instructions[2].startsWith("CLAUDE.md:"))
    }

    @Test fun ignoresMissingOversizedAndEscapingFiles() {
        File(workspace, "AGENTS.md").writeBytes(ByteArray(64 * 1024 + 1) { 'a'.code.toByte() })
        val outside = Files.createTempFile("phonecode-outside", ".md")
        runCatching { Files.createSymbolicLink(File(workspace, "CLAUDE.md").toPath(), outside) }

        val instructions = loadProjectInstructions(workspace)

        assertTrue(instructions.isEmpty())
        assertFalse(instructions.any { it.contains("phonecode-outside") })
        Files.deleteIfExists(outside)
    }

    @Test fun localProfilePrecedesCustomAndProjectInstructions() {
        File(workspace, "AGENTS.md").writeText("Keep the build green.")
        val settings = AppSettings(
            preferredName = "Alex\nSmith",
            occupation = "Mobile developer",
            aboutYou = "I work on Android apps.",
            responseStyleName = "CONCISE",
            customInstructions = "Prefer small changes.",
        )

        val instructions = loadProjectInstructions(workspace, settings)

        assertEquals(3, instructions.size)
        assertTrue(instructions[0].contains("Preferred name: Alex Smith"))
        assertTrue(instructions[0].contains("Occupation: Mobile developer"))
        assertTrue(instructions[0].contains("More about the user: I work on Android apps."))
        assertTrue(instructions[0].contains("Response style: Be concise and direct."))
        assertTrue(instructions[1].contains("Prefer small changes."))
        assertTrue(instructions[2].startsWith("AGENTS.md:"))
    }

    @Test fun disablingPersonalizationExcludesProfileAndCustomInstructions() {
        File(workspace, "AGENTS.md").writeText("Keep the build green.")
        val settings = AppSettings(
            preferredName = "Alex",
            responseStyleName = "CONCISE",
            customInstructions = "Be terse.",
            usePersonalization = false,
        )

        assertEquals("", settings.profileForPrompt())
        assertEquals(listOf("AGENTS.md:\nKeep the build green."), loadProjectInstructions(workspace, settings))
    }
}
