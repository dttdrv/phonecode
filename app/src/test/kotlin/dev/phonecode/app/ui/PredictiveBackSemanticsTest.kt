package dev.phonecode.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictiveBackSemanticsTest {
    private val root = generateSequence(
        File(requireNotNull(System.getProperty("user.dir"))).absoluteFile,
    ) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    private fun source(relativePath: String) = File(root, relativePath).readText()

    @Test
    fun modelSetupPredictiveBackHasAParentPreviewAndNoCommitReplay() {
        val modelSetup = source(
            "app/src/main/kotlin/dev/phonecode/app/ui/onboarding/ModelSetupScreen.kt",
        )

        assertTrue(modelSetup.contains("if (detailBackMotion.active)"))
        assertTrue(modelSetup.contains("predictiveCommit"))
        assertTrue(modelSetup.contains("EnterTransition.None togetherWith ExitTransition.None"))
        assertTrue(modelSetup.contains(".clearAndSetSemantics {}"))
    }

    @Test
    fun settingsDelegatesCleanRouteProgressAndSemanticsToItsNavHost() {
        val settings = source(
            "app/src/main/kotlin/dev/phonecode/app/ui/settings/SettingsScreen.kt",
        )
        val settingsRoot = settings.substringAfter("fun SettingsScreen(")
            .substringBefore("// ---------------------------------------------------------------------------------------------\n// Scaffolding")

        assertTrue(settingsRoot.contains("SettingsNavigation(vm, settingsVm, onExit = onBack, startRoute = initialRoute)"))
        assertFalse(settingsRoot.contains("AnimatedContent("))
        assertFalse(settingsRoot.contains("rememberPredictiveBackMotion("))
    }

    @Test
    fun mcpAndSkillsHaveOnlyRequiredTypedRouteCallbacksAndNoSecondNavigationSystem() {
        val navigation = source(
            "app/src/main/kotlin/dev/phonecode/app/ui/settings/SettingsNavigation.kt",
        )
        val mcp = source("app/src/main/kotlin/dev/phonecode/app/ui/settings/McpSettings.kt")
            .substringAfter("internal fun McpPage(")
            .substringBefore("internal fun McpServerPage(")
        val skills = source("app/src/main/kotlin/dev/phonecode/app/ui/settings/SkillSettings.kt")
            .substringAfter("internal fun SkillsPage(")
            .substringBefore("internal fun SkillDetailPage(")

        assertTrue(mcp.contains("onOpenServer: (String) -> Unit"))
        assertTrue(skills.contains("onOpenSkill: (String) -> Unit"))
        assertTrue(skills.contains("onNewSkill: () -> Unit"))
        listOf(mcp, skills).forEach { page ->
            assertFalse(page.contains("AnimatedContent("))
            assertFalse(page.contains("rememberPredictiveBackMotion("))
            assertFalse(page.contains("onNestedBackActive"))
        }
        assertTrue(navigation.contains("DiscardChangesBackHandler"))
    }
}
