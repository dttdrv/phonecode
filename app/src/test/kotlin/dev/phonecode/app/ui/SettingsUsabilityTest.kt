package dev.phonecode.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.phonecode.app.MainActivity
import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.app.agent.ChatUiState
import dev.phonecode.app.data.ManagedSkill
import dev.phonecode.app.data.SkillScope
import dev.phonecode.app.data.SkillStatus
import dev.phonecode.app.ui.settings.SettingsScreen
import dev.phonecode.app.ui.settings.SettingsNavigation
import dev.phonecode.app.ui.settings.SettingsRoute
import dev.phonecode.app.ui.settings.customProviderDraftIsDirty
import dev.phonecode.app.ui.settings.openExternalUrl
import dev.phonecode.app.ui.theme.PhoneCodeTheme
import dev.phonecode.tools.skills.SkillManifest
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    qualifiers = "w412dp-h915dp-xhdpi",
    shadows = [UiTestSecureKeyStore::class],
)
class SettingsUsabilityTest {

    private lateinit var settingsNavController: NavHostController

    private val seedSettings = object : ExternalResource() {
        override fun before() {
            val filesDir = ApplicationProvider
                .getApplicationContext<Context>().filesDir
            UiTestSecureKeyStore.replaceWith(emptyMap())
            java.io.File(filesDir, "app_settings.json")
                .writeText("""{"onboarded":true}""")
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seedSettings).around(compose)

    private fun app() = ApplicationProvider
        .getApplicationContext<PhoneCodeApplication>()

    private fun showSettings(page: String, context: Context = compose.activity) {
        val application = app()
        val settingsVm = SettingsViewModel(application)
        compose.activity.setContent {
            PhoneCodeTheme(darkTheme = false) {
                CompositionLocalProvider(LocalContext provides context) {
                    SettingsScreen(
                        vm = application.chatViewModel,
                        settingsVm = settingsVm,
                        onBack = {},
                        initialPage = page,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun showSkillRoute(id: String) {
        val application = app()
        val settingsVm = SettingsViewModel(application)
        compose.activity.setContent {
            PhoneCodeTheme(darkTheme = false) {
                val navController = rememberNavController()
                SideEffect { settingsNavController = navController }
                SettingsNavigation(
                    vm = application.chatViewModel,
                    settingsVm = settingsVm,
                    onExit = {},
                    startRoute = SettingsRoute.Skills,
                    navController = navController,
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { settingsNavController.navigate(SettingsRoute.Skill(id)) }
        compose.waitForIdle()
    }

    private fun stateFlow(): MutableStateFlow<ChatUiState> {
        val field = app().chatViewModel.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return field.get(app().chatViewModel) as MutableStateFlow<ChatUiState>
    }

    private fun contextWithoutBrowser(): Context = object : ContextWrapper(compose.activity) {
        override fun startActivity(intent: Intent) {
            throw ActivityNotFoundException("No browser installed")
        }
    }

    private fun skill(
        name: String,
        status: SkillStatus = SkillStatus.ACTIVE,
        issue: String? = null,
    ) = ManagedSkill(
        id = "global/$name",
        name = name,
        manifest = if (status == SkillStatus.INVALID) {
            null
        } else {
            SkillManifest(
                name = name,
                description = "Helps with $name work.",
                body = "Follow the $name workflow.",
                license = "Apache-2.0",
            )
        },
        location = "/skills/$name/SKILL.md",
        scope = SkillScope.GLOBAL,
        status = status,
        issue = issue,
    )

    @Test
    fun addCustomProviderIsAvailableBeforeTheProviderCatalog() {
        showSettings("providers")

        compose.onNodeWithText("Add custom provider").assertIsDisplayed()
        val addTop = compose.onNodeWithText("Add custom provider")
            .fetchSemanticsNode().boundsInRoot.top
        val firstProviderTop = compose.onNodeWithText("OpenAI")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(addTop < firstProviderTop)
    }

    @Test
    fun customProviderDirtyPolicyCoversEveryEditableField() {
        assertFalse(customProviderDraftIsDirty("", "", "", anthropicFormat = false))
        assertTrue(customProviderDraftIsDirty("Draft provider", "", "", anthropicFormat = false))
        assertTrue(customProviderDraftIsDirty("", "", "", anthropicFormat = true))
    }

    @Test
    fun radioChoicesExposeTheirSelectableGroup() {
        showSettings("appearance")

        val build = compose.onNodeWithText("Light", useUnmergedTree = true)
            .fetchSemanticsNode()
        assertTrue(
            generateSequence(build.parent) { it.parent }
                .any { SemanticsProperties.SelectableGroup in it.config },
        )
    }

    @Test
    fun personalizationSavesOnlyThroughItsEnabledPrimaryAction() {
        showSettings("personal")
        compose.onAllNodesWithText("Custom instructions").onLast().performClick()
        compose.waitForIdle()
        val field = compose.onNodeWithContentDescription("Instructions")
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Save custom instructions").assertIsNotEnabled()

        field.performTextReplacement("Prefer concise status updates.")
        compose.onNodeWithContentDescription("Save custom instructions").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Configured").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Configured").assertIsDisplayed()
    }

    @Test
    fun externalBrowserFailureReturnsAnActionableMessage() {
        val message = openExternalUrl(
            contextWithoutBrowser(),
            "https://example.com/setup",
        )

        assertTrue(message?.contains("Could not open your browser") == true)
    }

    @Test
    fun gitBrowserFailureIsShownInContext() {
        val vm = app().chatViewModel
        val stateField = vm.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(vm) as MutableStateFlow<ChatUiState>
        val original = state.value
        state.value = original.copy(
            githubAuthCode = "ABCD-EFGH",
            githubVerifyUri = "https://github.com/login/device",
        )

        try {
            showSettings("git", contextWithoutBrowser())
            compose.onNodeWithText("Open github.com/login/device").performClick()

            compose.onNodeWithText("Could not open your browser", substring = true)
                .assertIsDisplayed()
        } finally {
            state.value = original
        }
    }

    @Test
    fun aboutBrowserFailureIsShownAndFullConfigPathCanBeCopied() {
        val vm = app().chatViewModel
        showSettings("about", contextWithoutBrowser())

        compose.onNodeWithText("Website").performClick()
        compose.onNodeWithText("Could not open your browser", substring = true)
            .assertIsDisplayed()

        compose.onNodeWithText(vm.configDirPath()).assertIsDisplayed()
        compose.onNodeWithContentDescription("Copy config directory path").performClick()
        compose.onNodeWithText("Copied config path").assertIsDisplayed()
    }

    @Test
    fun skillInventoryRowsNavigateWithoutDuplicatingTheEnableSwitch() {
        val vm = app().chatViewModel
        val stateField = vm.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(vm) as MutableStateFlow<ChatUiState>
        val original = state.value
        state.value = original.copy(skills = listOf(skill("release-pilot")))

        try {
            showSettings("skills")

            compose.onNodeWithText("release-pilot").assertIsDisplayed()
            assertTrue(
                compose.onAllNodesWithContentDescription("release-pilot enabled")
                    .fetchSemanticsNodes().isEmpty(),
            )
        } finally {
            state.value = original
        }
    }

    @Test
    fun restoredSkillRouteWaitsForItsInventoryThenRendersThePersistedSkill() {
        val state = stateFlow()
        val original = state.value
        state.value = original.copy(
            skillInventoryLoaded = false,
            skills = emptyList(),
        )
        try {
            showSkillRoute("global/release-pilot")
            compose.onNodeWithText("Loading skills…").assertIsDisplayed()

            state.value = state.value.copy(
                skillInventoryLoaded = true,
                skills = listOf(skill("release-pilot")),
            )
            compose.onNodeWithContentDescription("Edit skill").assertIsDisplayed()
        } finally {
            state.value = original
        }
    }

    @Test
    fun loadedMissingSkillRoutePopsToTheSkillsInventory() {
        val state = stateFlow()
        val original = state.value
        state.value = original.copy(
            skillInventoryLoaded = true,
            skills = emptyList(),
        )
        try {
            showSkillRoute("global/missing")
            compose.onNodeWithText("Skills").assertIsDisplayed()
        } finally {
            state.value = original
        }
    }

    @Test
    fun invalidSkillExplainsItsIssueInTheInventory() {
        val vm = app().chatViewModel
        val stateField = vm.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(vm) as MutableStateFlow<ChatUiState>
        val original = state.value
        state.value = original.copy(
            skills = listOf(
                skill(
                    name = "legacy-deploy",
                    status = SkillStatus.INVALID,
                    issue = "Invalid SKILL.md frontmatter",
                ),
            ),
        )

        try {
            showSettings("skills")

            compose.onNodeWithText("1 need attention", substring = true).assertIsDisplayed()
            compose.onNodeWithText("legacy-deploy").performClick()
            compose.onNodeWithText("Invalid SKILL.md frontmatter").assertIsDisplayed()
        } finally {
            state.value = original
        }
    }

    @Test
    fun emptySkillInventoryOffersAUsefulCreateAction() {
        val vm = app().chatViewModel
        val stateField = vm.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(vm) as MutableStateFlow<ChatUiState>
        val original = state.value
        state.value = original.copy(skills = emptyList())

        try {
            showSettings("skills")

            compose.onNodeWithText("No skills installed.").assertIsDisplayed()
            compose.onNodeWithText("Create skill").assertIsDisplayed()
            assertTrue(compose.onAllNodesWithText("Search skills").fetchSemanticsNodes().isEmpty())
        } finally {
            state.value = original
        }
    }

    @Test
    fun skillSearchAppearsAtTheTwelveSkillThreshold() {
        val vm = app().chatViewModel
        val stateField = vm.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(vm) as MutableStateFlow<ChatUiState>
        val original = state.value
        state.value = original.copy(
            skillInventoryLoaded = true,
            skills = (1..12).map { skill("skill-$it") },
        )

        try {
            showSettings("skills")
            compose.onNodeWithContentDescription("Search skills").assertIsDisplayed()
            compose.onNodeWithContentDescription("Search skills").performTextReplacement("missing")

            compose.onNodeWithText("No skills match “missing”.").assertIsDisplayed()
        } finally {
            state.value = original
        }
    }

    @Test
    fun skillDetailKeepsEditProminentAndEditorSemanticallyIsolated() {
        val vm = app().chatViewModel
        val stateField = vm.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(vm) as MutableStateFlow<ChatUiState>
        val original = state.value
        state.value = original.copy(skills = listOf(skill("release-pilot")))

        try {
            showSettings("skills")
            compose.onNodeWithText("release-pilot").performClick()

            compose.onNodeWithContentDescription("Edit skill").assertIsDisplayed()
            compose.onNodeWithText("Danger zone").assertIsDisplayed()
            compose.onNodeWithContentDescription("Edit skill").performClick()

            compose.onNodeWithContentDescription("When to use this skill").assertIsDisplayed()
            compose.onNodeWithContentDescription("Skill instructions").assertIsDisplayed()
            compose.onNodeWithText("Advanced source").assertIsDisplayed()
            assertTrue(compose.onAllNodesWithText("Enabled").fetchSemanticsNodes().isEmpty())
        } finally {
            state.value = original
        }
    }

    @Test
    fun structuredSkillEditorGeneratesAValidSkillFile() {
        val vm = app().chatViewModel
        val stateField = vm.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(vm) as MutableStateFlow<ChatUiState>
        val original = state.value
        val skillDir = java.io.File(app().filesDir, "config/skills/release-guardian")
        skillDir.deleteRecursively()
        state.value = original.copy(skills = emptyList())

        try {
            showSettings("skills")
            compose.onNodeWithText("Create skill").performClick()
            compose.onNodeWithText("Save").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Skill name").performTextReplacement("release-guardian")
            compose.onNodeWithContentDescription("When to use this skill")
                .performTextReplacement("Checks a release before it is published.")
            compose.onNodeWithContentDescription("Skill instructions")
                .performTextReplacement("Verify the build, metadata, and release evidence.")
            compose.onNodeWithText("Save").performClick()

            compose.waitUntil(5_000) { java.io.File(skillDir, "SKILL.md").isFile }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Skills").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Skills").assertIsDisplayed()
            assertTrue(compose.onAllNodesWithContentDescription("Skill name").fetchSemanticsNodes().isEmpty())
            val source = java.io.File(skillDir, "SKILL.md").readText()
            assertTrue(source.contains("name: release-guardian"))
            assertTrue(source.contains("description: Checks a release before it is published."))
            assertTrue(source.contains("Verify the build, metadata, and release evidence."))
            assertTrue(source.contains("license: Apache-2.0"))
        } finally {
            skillDir.deleteRecursively()
            state.value = original
            vm.refreshSkills()
        }
    }
}
