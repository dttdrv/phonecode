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
        val settings = source("app/src/main/kotlin/dev/phonecode/app/ui/settings/SettingsComponents.kt")
        val drawer = source("app/src/main/kotlin/dev/phonecode/app/ui/drawer/WorkspaceDrawer.kt")

        assertTrue(settings.contains(".height(statusInset + Spacing.navBarHeight)"))
        assertFalse(
            settings.contains(
                ".statusBarsPadding()\n" +
                    "                .height(Spacing.navBarHeight)",
            ),
        )
        assertTrue(drawer.contains(".height(statusInset + 56.dp)"))
        assertFalse(drawer.contains(".statusBarsPadding()\n" +
            "                .shadow(if (listScrolled)"))
    }

    @Test
    fun compactVisualSurfacesRemainInsideAccessibleTouchTargets() {
        val spacing = source("app/src/main/kotlin/dev/phonecode/app/ui/theme/Spacing.kt")
        val actions = source("app/src/main/kotlin/dev/phonecode/app/ui/components/Actions.kt")

        assertTrue(spacing.contains("val controlVisual = 40.dp"))
        assertTrue(spacing.contains("val compactVisual = 36.dp"))
        assertTrue(actions.contains("private val ActionHeight = 48.dp"))
        assertTrue(actions.contains("private val IconTarget = 48.dp"))
        assertTrue(actions.contains("private val IconSurface = 40.dp"))
        assertTrue(actions.contains("private val IconGlyph = 22.dp"))
    }

    @Test
    fun chatChromeAndComposerUseCompactVisualHeights() {
        val chat = source("app/src/main/kotlin/dev/phonecode/app/ui/chat/ChatScreen.kt")
        val composer = source("app/src/main/kotlin/dev/phonecode/app/ui/chat/ChatComposer.kt")

        assertTrue(chat.contains("val topChromeHeight = Spacing.navBarHeight + 20.dp"))
        assertFalse(chat.contains("val topChromeHeight = Spacing.navBarHeight + 34.dp"))
        assertTrue(composer.contains("MaterialTheme.typography.bodyLarge"))
        assertTrue(composer.contains("CircleShape"))
        assertTrue(composer.contains("ComposerActionTarget = 48.dp"))
        assertTrue(composer.contains("heightIn(min = ComposerHeight)"))
        assertTrue(composer.contains("maxLines = ComposerMaxLines"))
        assertTrue(composer.contains("Box(Modifier.weight(1f)) {"))
        assertTrue(composer.contains(".align(Alignment.BottomEnd)"))
        val photoRemove = composer.substringAfter("PhotoThumbnail").substringBefore("if (value.isEmpty())")
        assertTrue(photoRemove.contains(".size(ComposerActionTarget)\n                                            .clickable"))
        assertFalse(photoRemove.contains(".clip(CircleShape).clickable"))
        assertTrue(photoRemove.contains("Modifier.size(24.dp).offset(x = 12.dp, y = (-12).dp)"))
        assertTrue(composer.contains("SizeTransform(clip = false)"))
        assertFalse(composer.contains("ShapeComposer"))
    }

    @Test
    fun toolTimelineAndDrawerDestinationsStayUnboxed() {
        val turns = source("app/src/main/kotlin/dev/phonecode/app/ui/chat/ChatTurn.kt")
        val drawer = source("app/src/main/kotlin/dev/phonecode/app/ui/drawer/WorkspaceDrawer.kt")
        val toolActivity = turns.substringAfter("internal fun ToolActivityView").substringBefore("private fun toolAction")
        val destination = drawer.substringAfter("private fun DrawerDestination").substringBefore("private fun timeBuckets")

        assertFalse(toolActivity.contains("colors.surfaceContainerLow"))
        assertFalse(toolActivity.contains("rememberNeuralBreath"))
        assertTrue(toolActivity.contains("Modifier.width(1.dp)"))
        assertFalse(destination.contains(".background("))
    }

    @Test
    fun approvalsUseAContentHeightNativeSheet() {
        val overlays = source("app/src/main/kotlin/dev/phonecode/app/ui/chat/ChatOverlays.kt")
        val permission = overlays.substringAfter("private fun PermissionDialog").substringBefore("private const val APPROVAL_DETAILS_PAGE_CHARS")

        assertTrue(permission.contains("PcSheet("))
        assertTrue(permission.contains("weight(1f, fill = false)"))
        assertFalse(permission.contains("fillMaxHeight(0.9f)"))
    }

    @Test
    fun reportCompletionUsesThePrimaryFinalAction() {
        val report = source("app/src/main/kotlin/dev/phonecode/app/ui/chat/ChatOverlays.kt")
            .substringAfter("private fun AiReportFlow")
            .substringBefore("private fun ReportReview")

        assertTrue(report.contains("MisulActionButton("))
        assertTrue(report.contains("label = \"Done\""))
        assertTrue(report.contains("role = ActionRole.PRIMARY"))
    }

    @Test
    fun settingsRowsAreOpenAndSkillSummariesStayCompact() {
        val rows = source("app/src/main/kotlin/dev/phonecode/app/ui/components/Rows.kt")
        val settings = source("app/src/main/kotlin/dev/phonecode/app/ui/settings/SkillSettings.kt")

        assertFalse(rows.contains("background(colors.surface)"))
        assertTrue(rows.contains("HorizontalDivider"))
        assertTrue(settings.contains("style = MaterialTheme.typography.bodySmall,"))
        assertTrue(settings.contains("color = colors.onSurfaceVariant,"))
        assertTrue(settings.contains("maxLines = 1,"))
    }

    @Test
    fun scrollingChromeUsesOneProgressiveEdgeDissolve() {
        val settings = source("app/src/main/kotlin/dev/phonecode/app/ui/settings/SettingsComponents.kt")
        val blur = source("app/src/main/kotlin/dev/phonecode/app/ui/theme/Blur.kt")
        val chrome = source("app/src/main/kotlin/dev/phonecode/app/ui/components/ScrollChrome.kt")

        assertTrue(settings.contains("StretchSyncedScrollChrome("))
        assertFalse(settings.contains("hazeSource(hazeState)"))
        assertTrue(chrome.contains("progressiveBlurEdge(hazeState, hazeStyle"))
        assertFalse(settings.contains(".shadow(if (scrolled) 2.dp"))
        assertTrue(blur.contains("fun Modifier.progressiveBlurEdge("))
        assertTrue(blur.contains("blurRadius = 4.dp"))
        assertTrue(blur.contains("0f to edgeColor.copy(alpha = EdgeTintAlpha)"))
        assertTrue(blur.contains("1f to edgeColor.copy(alpha = EdgeTintAlpha)"))
        assertTrue(blur.contains("if (isRobolectric) return edgeDissolve"))
        assertFalse(blur.contains("copy(alpha = 0.96f)"))
        assertFalse(blur.contains("0.62f to edgeColor"))
        assertFalse(blur.contains("0.38f to edgeColor"))
    }

    @Test
    fun settingsExposeOneAgentWithoutAModeDashboard() {
        val settings = source("app/src/main/kotlin/dev/phonecode/app/ui/settings/SettingsHome.kt")
        val chat = source("app/src/main/kotlin/dev/phonecode/app/ui/chat/ChatScreen.kt")

        assertFalse(settings.contains("GeneralPage("))
        assertFalse(settings.contains("Default agent mode"))
        assertFalse(chat.contains("AgentMode.entries"))
        assertTrue(settings.contains("SettingsRootGroup(\"Agent\")"))
        assertTrue(settings.contains("SettingsRootGroup(\"Capabilities\")"))
        assertTrue(settings.contains("SettingsRootGroup(\"App\")"))
        assertTrue(settings.indexOf("Personalization") < settings.indexOf("Models & providers"))
        assertFalse(settings.contains("SettingsNavigationRow(\"Providers\""))
    }

    @Test
    fun settingsUseOpenHelperCopyAndCobaltSelection() {
        val settings = source("app/src/main/kotlin/dev/phonecode/app/ui/settings/SettingsComponents.kt")
        val rows = source("app/src/main/kotlin/dev/phonecode/app/ui/components/Rows.kt")
        val note = settings.substringAfter("fun SettingsNote(").substringBefore("fun SettingsErrorText")

        assertFalse(note.contains(".clip("))
        assertFalse(note.contains(".background("))
        assertTrue(rows.contains("LocalMisulAccent.current"))
        assertTrue(rows.contains("SwitchDefaults.colors("))
        assertTrue(rows.contains("checkedTrackColor = LocalMisulAccent.current"))
    }

    @Test
    fun appendedConversationTurnsUseTheOnlyTimelineEntryMotion() {
        val chat = source("app/src/main/kotlin/dev/phonecode/app/ui/chat/ChatScreen.kt")

        assertTrue(chat.contains("ChatAppendTransitionTracker"))
        assertTrue(chat.contains("appendTransitions.observe("))
        assertTrue(chat.contains(".messageEnter(entryMotion)"))
        assertTrue(chat.contains("ChatEntryMotion.START"))
        assertFalse(chat.contains("initialCount"))
        assertFalse(chat.contains("animatedIndices"))
        assertFalse(chat.contains("rememberNeuralPhase"))
        assertFalse(chat.contains("rememberNeuralBreath"))
        assertFalse(chat.contains("neuralSweepBrush"))
        assertFalse(File(root, "app/src/main/kotlin/dev/phonecode/app/ui/theme/NeuralAccent.kt").exists())
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
        val motion = source("app/src/main/kotlin/dev/phonecode/app/ui/navigation/MisulNavigationMotion.kt")

        assertFalse(
            shell.contains(
                "popEnterTransition = { androidx.compose.animation.EnterTransition.None }",
            ),
        )
        assertTrue(shell.contains("popEnterTransition = { MisulNavigationMotion.backEnter() }"))
        assertTrue(motion.contains("fun backEnter(): EnterTransition =\n" +
            "        slideInHorizontally(tween(220, easing = PhoneEasings.easeOut)) { -it / 4 }"))
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
        assertTrue(build.contains("output.versionCode.set(54)"))
        assertTrue(build.contains("output.versionName.set(\"0.6.0-beta\")"))
    }
}
