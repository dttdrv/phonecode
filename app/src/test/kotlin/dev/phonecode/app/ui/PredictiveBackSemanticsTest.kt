package dev.phonecode.app.ui

import dev.phonecode.app.ui.settings.nestedBackgroundSemanticsHidden
import dev.phonecode.app.ui.settings.outgoingNestedContentSemanticsHidden
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
    fun settingsKeepsBackgroundSemanticsHiddenForTheWholeTransition() {
        val settings = source(
            "app/src/main/kotlin/dev/phonecode/app/ui/settings/SettingsScreen.kt",
        )

        assertTrue(settings.contains("nestedBackgroundSemanticsHidden("))
        assertTrue(
            settings.contains(
                "if (backMotion.active) {\n" +
                    "            Box(Modifier.fillMaxSize().clearAndSetSemantics {})",
            ),
        )
    }

    @Test
    fun nestedBackgroundIsHiddenWhileEitherTransitionSideIsNested() {
        assertTrue(nestedBackgroundSemanticsHidden(currentNested = false, targetNested = true))
        assertTrue(nestedBackgroundSemanticsHidden(currentNested = true, targetNested = false))
        assertTrue(nestedBackgroundSemanticsHidden(currentNested = true, targetNested = true))
        assertFalse(nestedBackgroundSemanticsHidden(currentNested = false, targetNested = false))
    }

    @Test
    fun onlyTheTargetNestedPageKeepsItsSemanticsDuringMotion() {
        assertFalse(outgoingNestedContentSemanticsHidden(isTargetContent = true))
        assertTrue(outgoingNestedContentSemanticsHidden(isTargetContent = false))
    }
}
