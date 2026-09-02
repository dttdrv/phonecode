package dev.phonecode.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiPolishRegressionTest {
    private val root = generateSequence(
        File(requireNotNull(System.getProperty("user.dir"))).absoluteFile,
    ) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    private fun source(relativePath: String) = File(root, relativePath).readText()

    @Test
    fun settingsHeaderPaintsThroughTheStatusBar() {
        val settings = source(
            "app/src/main/kotlin/dev/phonecode/app/ui/settings/SettingsScreen.kt",
        )
        val shell = source("app/src/main/kotlin/dev/phonecode/app/ui/PhoneCodeApp.kt")

        assertTrue(settings.contains(".height(statusInset + Spacing.navBarHeight)"))
        assertFalse(
            settings.contains(
                ".statusBarsPadding()\n" +
                    "                .height(Spacing.navBarHeight)",
            ),
        )
        assertTrue(shell.contains(".height(statusInset + 112.dp)"))
        assertFalse(shell.contains(".statusBarsPadding()\n" +
            "                .shadow(if (listScrolled)"))
    }

    @Test
    fun compactVisualSurfacesRemainInsideAccessibleTouchTargets() {
        val spacing = source("app/src/main/kotlin/dev/phonecode/app/ui/theme/Spacing.kt")
        val kit = source("app/src/main/kotlin/dev/phonecode/app/ui/components/Kit.kt")

        assertTrue(spacing.contains("val controlVisual = 40.dp"))
        assertTrue(spacing.contains("val compactVisual = 36.dp"))
        assertTrue(kit.contains("modifier.size(Spacing.touchTarget)"))
        assertTrue(kit.contains("Modifier.size(Spacing.controlVisual)"))
        assertTrue(kit.contains(".height(Spacing.controlVisual)"))
    }

    @Test
    fun chatChromeAndComposerUseCompactVisualHeights() {
        val chat = source("app/src/main/kotlin/dev/phonecode/app/ui/chat/ChatScreen.kt")
        val shapes = source("app/src/main/kotlin/dev/phonecode/app/ui/theme/Shapes.kt")

        assertTrue(chat.contains("Modifier.height(Spacing.compactVisual)"))
        assertTrue(chat.contains(".padding(horizontal = 6.dp, vertical = 0.dp)"))
        assertTrue(shapes.contains("val ShapeComposer = RoundedCornerShape(24.dp)"))
    }

    @Test
    fun settingsRowsAreOpenAndSkillSummariesStayCompact() {
        val kit = source("app/src/main/kotlin/dev/phonecode/app/ui/components/Kit.kt")
        val settings = source(
            "app/src/main/kotlin/dev/phonecode/app/ui/settings/SettingsScreen.kt",
        )

        assertFalse(kit.contains("background(colors.surface)"))
        assertTrue(kit.contains("HorizontalDivider"))
        assertTrue(settings.contains("style = MaterialTheme.typography.bodySmall,\n" +
            "                                        color = colors.onSurfaceVariant,\n" +
            "                                        maxLines = 1,"))
    }

    @Test
    fun scrollingChromeUsesOneProgressiveEdgeDissolve() {
        val settings = source("app/src/main/kotlin/dev/phonecode/app/ui/settings/SettingsScreen.kt")
        val blur = source("app/src/main/kotlin/dev/phonecode/app/ui/theme/Blur.kt")

        assertTrue(settings.contains("hazeSource(hazeState)"))
        assertTrue(settings.contains("progressiveBlurEdge("))
        assertFalse(settings.contains(".shadow(if (scrolled) 2.dp"))
        assertTrue(blur.contains("fun Modifier.progressiveBlurEdge("))
    }

    @Test
    fun settingsExposeOneAgentWithoutAModeDashboard() {
        val settings = source("app/src/main/kotlin/dev/phonecode/app/ui/settings/SettingsScreen.kt")
        val chat = source("app/src/main/kotlin/dev/phonecode/app/ui/chat/ChatScreen.kt")

        assertFalse(settings.contains("GeneralPage("))
        assertFalse(settings.contains("Default agent mode"))
        assertFalse(chat.contains("AgentMode.entries"))
        assertTrue(settings.contains("PcSectionLabel(\"Agent\")"))
        assertTrue(settings.contains("PcSectionLabel(\"Capabilities\")"))
        assertTrue(settings.contains("PcSectionLabel(\"App\")"))
    }

    @Test
    fun appThemeInstallsTheNativeStretchOverscrollFactory() {
        val theme = source("app/src/main/kotlin/dev/phonecode/app/ui/theme/Theme.kt")

        assertTrue(theme.contains("rememberPlatformOverscrollFactory()"))
        assertTrue(theme.contains("LocalOverscrollFactory provides overscrollFactory"))
    }

    @Test
    fun rootBackNavigationAnimatesBothScreens() {
        val shell = source("app/src/main/kotlin/dev/phonecode/app/ui/PhoneCodeApp.kt")

        assertFalse(
            shell.contains(
                "popEnterTransition = { androidx.compose.animation.EnterTransition.None }",
            ),
        )
        assertTrue(shell.contains("popEnterTransition = {\n" +
            "                            slideInHorizontally("))
        assertTrue(shell.contains(
            "drawerState.animateTo(DrawerValue.CLOSED, PhoneSprings.drawer)\n" +
                "                navController.navigate(destination)",
        ))
    }

    @Test
    fun githubSideloadArtifactUsesOptimizedNonDebuggableCode() {
        val build = source("app/build.gradle.kts")

        assertTrue(build.contains("create(\"sideload\")"))
        assertTrue(build.contains("isDebuggable = false"))
        assertTrue(build.contains("isMinifyEnabled = true"))
        assertTrue(build.contains("signingConfig = signingConfigs.getByName(\"debug\")"))
        assertTrue(build.contains("sourceSets.getByName(\"sideload\")"))
        assertTrue(build.contains("withBuildType(\"sideload\")"))
        assertTrue(build.contains("output.versionCode.set(53)"))
        assertTrue(build.contains("output.versionName.set(\"0.6.0-alpha\")"))
    }
}
