package dev.phonecode.app.ui

import java.io.File
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import dev.phonecode.app.ui.components.ActionRole
import dev.phonecode.app.ui.components.MisulPressTarget
import dev.phonecode.app.ui.components.actionVisuals
import dev.phonecode.app.ui.components.iconVisuals
import dev.phonecode.app.ui.components.pressedScaleFor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionSystemContractTest {
    private val root = generateSequence(
        File(requireNotNull(System.getProperty("user.dir"))).absoluteFile,
    ) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun obsoleteUniversalControlsHaveNoDefinitionsOrCallers() {
        val uiRoot = File(root, "app/src/main/kotlin/dev/phonecode/app/ui")
        val obsolete = listOf(
            "PcButton",
            "PcIconButton",
            "PcRoundButton",
            "PcToggle",
            "PcGroup",
            "PcRow",
            "PcField",
            "stretchEnabled",
        )
        val source = uiRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        obsolete.forEach { name ->
            assertFalse("Obsolete UI API remains: $name", source.contains(name))
        }
    }

    @Test
    fun roleSpecificComponentsExist() {
        val actions = File(root, "app/src/main/kotlin/dev/phonecode/app/ui/components/Actions.kt")

        assertTrue("Missing role-specific action component source", actions.isFile)
        val source = actions.readText()
        assertTrue(source.contains("enum class ActionRole"))
        assertTrue(source.contains("fun MisulActionButton("))
        assertTrue(source.contains("fun MisulIconButton("))
        assertTrue(source.contains("fun MisulTextAction("))
    }

    @Test
    fun sharedActionsUseRoleColorsStableGeometryAndMotionTokens() {
        val actions = File(root, "app/src/main/kotlin/dev/phonecode/app/ui/components/Actions.kt")
        val motion = File(root, "app/src/main/kotlin/dev/phonecode/app/ui/theme/Motion.kt")

        assertTrue("Missing action source", actions.isFile)
        val source = actions.readText()
        assertTrue(source.contains("private val ActionHeight = 48.dp"))
        assertTrue(source.contains("private val ActionCorner = 14.dp"))
        assertTrue(source.contains("private val IconTarget = 48.dp"))
        assertTrue(source.contains("private val IconSurface = 40.dp"))
        assertTrue(source.contains("private val IconGlyph = 22.dp"))
        assertTrue(source.contains("LocalMisulAccent.current"))
        assertTrue(source.contains("colors.onPrimary"))
        assertTrue(source.contains("colors.surfaceContainerHigh"))
        assertTrue(source.contains("colors.error"))
        assertTrue(source.contains("CircularProgressIndicator"))
        assertTrue(source.contains("MisulPressMotion"))
        assertTrue(source.contains("PhoneDurations.PRESS_IN"))
        assertTrue(source.contains("tween("))
        assertTrue(source.contains("stiffness = 600f"))

        val motionSource = motion.readText()
        assertTrue(motionSource.contains("object PhoneDurations"))
        assertTrue(motionSource.contains("const val PRESS_IN = 70"))
        assertTrue(motionSource.contains("const val PRESS_OUT = 140"))
        assertTrue(motionSource.contains("const val POPOVER_IN = 180"))
        assertTrue(motionSource.contains("const val POPOVER_OUT = 120"))
        assertTrue(motionSource.contains("const val NAV_IN = 240"))
        assertTrue(motionSource.contains("const val NAV_OUT = 180"))
        assertTrue(motionSource.contains("const val MESSAGE_IN = 180"))
        assertTrue(motionSource.contains("const val STATE_CHANGE = 140"))
    }

    @Test
    fun sharedChromeUsesRoleSpecificIconActions() {
        val chat = File(root, "app/src/main/kotlin/dev/phonecode/app/ui/chat/ChatScreen.kt").readText()
        val settings = File(root, "app/src/main/kotlin/dev/phonecode/app/ui/settings/SettingsComponents.kt").readText()
        val composer = File(root, "app/src/main/kotlin/dev/phonecode/app/ui/chat/ChatComposer.kt").readText()
        val settingsHeader = settings.substringAfter("fun SettingsPageShell(").substringBefore("fun SettingsNavigationRow(")

        assertTrue(chat.substringBefore("private fun ReportReview(").contains("MisulIconButton(\n                Icons.Filled.Menu"))
        assertFalse(composer.contains("PcIconButton("))
        assertFalse(composer.contains("PcRoundButton("))
        assertTrue(composer.contains("MisulIconButton("))
        assertTrue(settingsHeader.contains("MisulIconButton("))
        assertFalse(settingsHeader.contains("PcIconButton("))
    }

    @Test
    fun actionVisualStatesDimDisabledCobaltAndKeepSelectedIconsSemantic() {
        val colors = lightColorScheme()
        val cobalt = Color(0xFF0B57D0)

        val primary = actionVisuals(ActionRole.PRIMARY, enabled = true, cobalt = cobalt, colors = colors)
        val disabledPrimary = actionVisuals(ActionRole.PRIMARY, enabled = false, cobalt = cobalt, colors = colors)
        val destructive = actionVisuals(ActionRole.DESTRUCTIVE, enabled = true, cobalt = cobalt, colors = colors)
        val selectedIcon = iconVisuals(selected = true, filled = false, enabled = true, cobalt = cobalt, colors = colors)
        val disabledSelectedIcon = iconVisuals(selected = true, filled = false, enabled = false, cobalt = cobalt, colors = colors)

        assertTrue(primary.container == cobalt)
        assertTrue(primary.content == colors.onPrimary)
        assertTrue(disabledPrimary.container == colors.surfaceContainerHigh)
        assertTrue(disabledPrimary.content == colors.onSurfaceVariant.copy(alpha = 0.38f))
        assertTrue(destructive.container == Color.Transparent)
        assertTrue(destructive.content == colors.error)
        assertTrue(selectedIcon.emphasized)
        assertTrue(selectedIcon.container == cobalt)
        assertTrue(disabledSelectedIcon.container == colors.surfaceContainerHigh)
        assertTrue(disabledSelectedIcon.content == colors.onSurfaceVariant.copy(alpha = 0.38f))
    }

    @Test
    fun sharedActionSourceClipsToneToItsVisualSurfaceAndExposesSelectedState() {
        val actions = File(root, "app/src/main/kotlin/dev/phonecode/app/ui/components/Actions.kt").readText()
        val icon = actions.substringAfter("fun MisulIconButton(").substringBefore("fun MisulTextAction(")
        val action = actions.substringAfter("fun MisulActionButton(").substringBefore("fun MisulIconButton(")
        val text = actions.substringAfter("fun MisulTextAction(").substringBefore("fun Modifier.misulPressMotion(")

        assertTrue(action.contains(".clip(shape)\n            .background(visual.container)\n            .misulTonalFeedback"))
        assertTrue(icon.contains("Modifier.size(IconSurface)\n                .clip(CircleShape)\n                .background(visual.container)\n                .misulTonalFeedback"))
        assertTrue(icon.contains("this.selected = selected"))
        assertTrue(action.contains(".height(ActionHeight)"))
        assertTrue(action.contains("actionVisuals(role, enabled || loading"))
        assertTrue(action.contains("graphicsLayer { alpha = if (loading) 0f else 1f }"))
        assertTrue(text.contains("pressedScale = pressedScaleFor(MisulPressTarget.TEXT)"))
    }

    @Test
    fun sharedActionPressScalesMatchTheirInteractionRoles() {
        assertTrue(pressedScaleFor(MisulPressTarget.ACTION) == 0.97f)
        assertTrue(pressedScaleFor(MisulPressTarget.ICON) == 0.96f)
        assertTrue(pressedScaleFor(MisulPressTarget.TEXT) == 0.99f)
    }

    @Test
    fun primaryScrollSurfacesDoNotDisableStretch() {
        listOf("PhoneCodeApp.kt", "chat/ChatScreen.kt", "settings/SettingsScreen.kt")
            .map { File(root, "app/src/main/kotlin/dev/phonecode/app/ui/$it").readText() }
            .forEach { source ->
                assertFalse(source.contains("stretchEnabled = false"))
                assertFalse(source.contains("rememberContentOverscroll(false)"))
            }
    }

    @Test
    fun primaryScrollChromeCapturesTheScrollableChildBeforeDrawingBothBands() {
        val overscroll = File(root, "app/src/main/kotlin/dev/phonecode/app/ui/components/ContentOverscroll.kt").readText()
        val chrome = File(root, "app/src/main/kotlin/dev/phonecode/app/ui/components/ScrollChrome.kt")
        val surfaces = listOf("drawer/WorkspaceDrawer.kt", "chat/ChatScreen.kt", "settings/SettingsComponents.kt")
            .map { File(root, "app/src/main/kotlin/dev/phonecode/app/ui/$it").readText() }

        assertTrue(overscroll.contains("fun rememberContentOverscroll(): OverscrollEffect? = rememberOverscrollEffect()"))
        assertFalse(overscroll.contains("stretchEnabled"))
        assertTrue("Missing shared scroll chrome", chrome.isFile)
        val source = chrome.readText()
        assertTrue(source.contains("enum class ProgressiveEdge { TOP, BOTTOM }"))
        assertTrue(source.contains("fun StretchSyncedScrollChrome("))
        assertTrue(source.contains("Modifier.fillMaxSize().hazeSource(hazeState)"))
        assertTrue(source.indexOf(".hazeSource(hazeState)") < source.indexOf("content(hazeState)"))
        assertTrue(source.contains("ProgressiveBlurBand(ProgressiveEdge.TOP, hazeState, topHeight)"))
        assertTrue(source.contains("ProgressiveBlurBand(ProgressiveEdge.BOTTOM, hazeState, bottomHeight)"))
        surfaces.forEach { surface ->
            assertTrue(surface.contains("StretchSyncedScrollChrome("))
            assertFalse(surface.contains(".hazeSource("))
        }
    }

    @Test
    fun composerUsesTrueCapsuleShape() {
        val composer = File(root, "app/src/main/kotlin/dev/phonecode/app/ui/chat/ChatComposer.kt")

        assertTrue("Missing dedicated composer source", composer.isFile)
        val source = composer.readText()
        assertTrue(source.contains("CircleShape"))
        assertFalse(source.contains("RoundedCornerShape(24.dp)"))
    }

    @Test
    fun settingsUsesNestedNavigation() {
        val navigation = File(root, "app/src/main/kotlin/dev/phonecode/app/ui/settings/SettingsNavigation.kt")

        assertTrue("Missing settings navigation source", navigation.isFile)
        val source = navigation.readText()
        assertTrue(source.contains("NavHost("))
        assertTrue(source.contains("popBackStack()"))
    }

    @Test
    fun rowFieldAndDialogContractsExposeTheFocusedInteractionSystem() {
        val rows = File(root, "app/src/main/kotlin/dev/phonecode/app/ui/components/Rows.kt")
        val fields = File(root, "app/src/main/kotlin/dev/phonecode/app/ui/components/Fields.kt")
        val overlays = File(root, "app/src/main/kotlin/dev/phonecode/app/ui/components/Overlays.kt")

        assertTrue("Missing row component source", rows.isFile)
        assertTrue("Missing field component source", fields.isFile)
        assertTrue("Missing overlay component source", overlays.isFile)

        val rowSource = rows.readText()
        assertTrue(rowSource.contains("fun MisulGroup("))
        assertTrue(rowSource.contains("fun MisulNavigationRow("))
        assertTrue(rowSource.contains("fun MisulDisclosureRow("))
        assertTrue(rowSource.contains("fun MisulToggleRow("))
        assertTrue(rowSource.contains("fun MisulSelectionRow("))
        assertTrue(rowSource.contains("fun MisulFilter("))
        assertTrue(rowSource.contains("showDivider: Boolean = true"))
        assertTrue(rowSource.contains("Switch("))
        assertTrue(rowSource.contains("onCheckedChange = null"))
        assertTrue(rowSource.contains("ToggleableState(checked)"))
        assertTrue(rowSource.contains("if (showDivider)"))
        val navigation = rowSource.substringAfter("fun MisulNavigationRow(").substringBefore("fun MisulToggleRow(")
        val disclosure = rowSource.substringAfter("fun MisulDisclosureRow(").substringBefore("fun MisulToggleRow(")
        val toggle = rowSource.substringAfter("fun MisulToggleRow(").substringBefore("fun MisulSelectionRow(")
        val selection = rowSource.substringAfter("fun MisulSelectionRow(").substringBefore("fun MisulFilter(")
        assertTrue(navigation.contains("misulRowPressTreatment(interaction)"))
        assertTrue(disclosure.contains("role = Role.Button"))
        assertTrue(disclosure.contains("stateDescription = if (expanded) \"Expanded\" else \"Collapsed\""))
        assertFalse(disclosure.contains("Role.Switch"))
        assertTrue(toggle.contains("misulRowPressTreatment(interaction)"))
        assertTrue(selection.contains("misulRowPressTreatment(interaction)"))
        assertFalse(navigation.contains("misulPressMotion"))
        assertFalse(selection.contains("misulPressMotion"))
        assertTrue(rowSource.contains("drawRect(pressColor)"))

        val fieldSource = fields.readText()
        assertTrue(fieldSource.contains("fun MisulField("))
        assertTrue(fieldSource.contains("fun MisulSearchField("))
        assertTrue(fieldSource.contains("MaterialTheme.typography.bodyLarge"))
        assertTrue(fieldSource.contains("this.error(error)"))

        val overlaySource = overlays.readText()
        assertTrue(overlaySource.contains("fun MisulDialog("))
        assertTrue(overlaySource.contains("fun MisulDialogActions("))
        assertTrue(overlaySource.contains("fun RowScope.MisulDialogAction("))
        assertTrue(overlaySource.contains("MisulTextAction("))
        assertFalse(overlaySource.contains("fillMaxWidth().MisulDialogAction"))
    }

    @Test
    fun task10UsesSharedSearchFiltersStableLoadingAndDirtyGitExit() {
        val settingsDir = File(root, "app/src/main/kotlin/dev/phonecode/app/ui/settings")
        val tools = File(settingsDir, "AgentToolsSettings.kt").readText()
        val providers = File(settingsDir, "ProviderSettings.kt").readText()
        val mcp = File(settingsDir, "McpSettings.kt").readText()
        val skills = File(settingsDir, "SkillSettings.kt").readText()
        val git = File(settingsDir, "GitSettings.kt").readText()

        listOf(tools, providers, mcp, skills).forEach { source ->
            assertTrue(source.contains("MisulSearchField("))
        }
        assertTrue(tools.contains("MisulFilter("))
        assertTrue(skills.contains("MisulFilter("))
        assertTrue(tools.contains("Clear search"))
        assertTrue(git.contains("animateContentSize("))
        assertTrue(git.contains("manualDraftIsDirty"))
        assertTrue(git.contains("DiscardChangesBackHandler("))
        listOf(providers, mcp, skills).forEach { source ->
            assertFalse(source.contains("if (saving) \"Saving…\" else"))
        }
        assertFalse(mcp.contains("if (testing) \"Testing…\" else"))
        assertFalse(mcp.contains("if (reconnecting) \"Reconnecting…\" else"))
    }
}
