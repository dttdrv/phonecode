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
        val ioStart = init.indexOf("viewModelScope.launch(Dispatchers.IO)")
        val defer = init.indexOf("delay(STARTUP_DEFER_MILLIS)", startIndex = ioStart)
        val mkdirs = init.indexOf("configDir.mkdirs()", startIndex = defer)
        val seed = init.indexOf("repo.seedBundledSkills(app.assets)", startIndex = mkdirs)
        val refresh = init.indexOf("refreshSkillsNow()", startIndex = seed)

        assertTrue(ioStart >= 0)
        assertTrue(defer > ioStart)
        assertTrue(mkdirs > defer)
        assertTrue(seed > mkdirs)
        assertTrue(refresh > seed)
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
