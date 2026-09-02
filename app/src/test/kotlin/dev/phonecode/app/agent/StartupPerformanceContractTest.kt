package dev.phonecode.app.agent

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupPerformanceContractTest {
    private val root = generateSequence(
        File(requireNotNull(System.getProperty("user.dir"))).absoluteFile,
    ) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    @Test fun skillExtractionAndCatalogScanRunOffTheMainThread() {
        val source = File(
            root,
            "app/src/main/kotlin/dev/phonecode/app/agent/ChatViewModel.kt",
        ).readText()

        val init = source.substringAfter("    init {").substringBefore("\n    fun refreshModels")
        assertTrue(
            init.contains(
                "viewModelScope.launch(Dispatchers.IO) {\n" +
                    "            delay(STARTUP_DEFER_MILLIS)\n" +
                    "            configDir.mkdirs()\n" +
                    "            repo.seedBundledSkills(app.assets)\n" +
                    "            refreshSkillsNow()",
            ),
        )
        assertFalse(init.startsWith("\n        configDir.mkdirs()\n        repo.seedBundledSkills"))
        assertTrue(init.windowed("delay(STARTUP_DEFER_MILLIS)".length).count { it == "delay(STARTUP_DEFER_MILLIS)" } >= 3)
    }

    @Test fun commandRuntimeIsNotConstructedDuringViewModelCreation() {
        val source = File(
            root,
            "app/src/main/kotlin/dev/phonecode/app/agent/ChatViewModel.kt",
        ).readText()

        assertTrue(source.contains("private val shellBackend by lazy"))
        assertTrue(source.contains("private val baseTools: List<Tool> by lazy"))
        assertTrue(source.contains("private val tools by lazy"))
        assertFalse(source.contains("shellBackend::stopAll"))
    }
}
