package dev.phonecode.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.app.MainActivity
import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.app.agent.ChatUiState
import dev.phonecode.app.data.ManagedSkill
import dev.phonecode.app.data.SkillScope
import dev.phonecode.app.data.SkillStatus
import dev.phonecode.app.ui.settings.SettingsScreen
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
    fun personalizationHasAConciseNameAndSaveFeedback() {
        showSettings("personal")
        val field = compose.onNodeWithContentDescription("Custom instructions")
            .assertIsDisplayed()

        field.performTextReplacement("Prefer concise status updates.")
        compose.onNodeWithText("Saving…").assertIsDisplayed()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Saved").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Saved").assertIsDisplayed()
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

            compose.onNodeWithText("Invalid SKILL.md frontmatter").assertIsDisplayed()
            compose.onNodeWithText("1 skill needs attention").assertIsDisplayed()
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

            compose.onNodeWithText("No skills yet").assertIsDisplayed()
            compose.onNodeWithText("Create skill").assertIsDisplayed()
            assertTrue(compose.onAllNodesWithText("Search skills").fetchSemanticsNodes().isEmpty())
        } finally {
            state.value = original
        }
    }

    @Test
    fun skillSearchAndFiltersOfferDifferentRecoveryActions() {
        val vm = app().chatViewModel
        val stateField = vm.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(vm) as MutableStateFlow<ChatUiState>
        val original = state.value
        state.value = original.copy(skills = (1..6).map { skill("skill-$it") })

        try {
            showSettings("skills")
            compose.onNodeWithContentDescription("Search skills").performTextReplacement("missing")

            compose.onNodeWithText("No skills match “missing”.").assertIsDisplayed()
            compose.onNodeWithText("Clear search").performClick()
            compose.onNodeWithText("Issues").performClick()
            compose.onNodeWithText("No skills need attention.").assertIsDisplayed()
            compose.onNodeWithText("Show all skills").assertIsDisplayed()
        } finally {
            state.value = original
        }
    }

    @Test
    fun skillFiltersExposeASelectableGroup() {
        val vm = app().chatViewModel
        val stateField = vm.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(vm) as MutableStateFlow<ChatUiState>
        val original = state.value
        state.value = original.copy(skills = listOf(skill("release-pilot"), skill("disabled", SkillStatus.DISABLED)))

        try {
            showSettings("skills")
            val all = compose.onNodeWithText("All", useUnmergedTree = true).fetchSemanticsNode()

            assertTrue(
                generateSequence(all.parent) { it.parent }
                    .any { SemanticsProperties.SelectableGroup in it.config },
            )
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

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    qualifiers = "w320dp-h640dp-xhdpi",
    shadows = [UiTestSecureKeyStore::class],
)
class SettingsCompactLayoutTest {

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

    @Test
    fun skillFiltersWrapInsteadOfCrowdingOnANarrowScreen() {
        val application = ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val settingsVm = SettingsViewModel(application)
        compose.activity.setContent {
            PhoneCodeTheme(darkTheme = false) {
                SettingsScreen(
                    vm = application.chatViewModel,
                    settingsVm = settingsVm,
                    onBack = {},
                    initialPage = "skills",
                )
            }
        }
        compose.waitForIdle()

        val firstRowTop = compose.onNodeWithText("All")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithText("Active").assertIsDisplayed()
        val secondRowTop = compose.onNodeWithText("Inactive")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithText("Issues").assertIsDisplayed()
        assertTrue(secondRowTop > firstRowTop)
    }
}
