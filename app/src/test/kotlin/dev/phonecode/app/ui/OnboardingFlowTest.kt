package dev.phonecode.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.phonecode.app.R
import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.app.agent.ChatViewModel
import dev.phonecode.app.ui.chat.ChatScreen
import dev.phonecode.app.ui.onboarding.ModelSetupScreen
import dev.phonecode.app.ui.onboarding.OnboardingScreen
import dev.phonecode.app.ui.onboarding.providerSetupFailureMessage
import dev.phonecode.app.ui.theme.PhoneCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    qualifiers = "w412dp-h915dp-xhdpi",
    shadows = [UiTestSecureKeyStore::class],
)
class OnboardingFlowTest {

    private val compose = createComposeRule()

    @get:Rule
    val rule = compose

    @Test
    fun onboardingAndModelSetupUseTheSharedControlRoles() {
        val onboarding = java.io.File(
            "src/main/kotlin/dev/phonecode/app/ui/onboarding/OnboardingScreen.kt",
        ).readText()
        val setup = java.io.File(
            "src/main/kotlin/dev/phonecode/app/ui/onboarding/ModelSetupScreen.kt",
        ).readText()

        assertFalse(onboarding.contains("PcButton"))
        assertFalse(onboarding.contains("PcGroup"))
        assertFalse(onboarding.contains("PcIconButton"))
        assertFalse(onboarding.contains("PcRow"))
        assertTrue(onboarding.contains("MisulContentRow"))
        assertTrue(onboarding.contains("MisulNavigationRow"))
        assertTrue(onboarding.contains("MisulActionButton"))
        assertTrue(onboarding.contains("ActionRole.PRIMARY"))
        assertTrue(onboarding.contains("ActionRole.QUIET"))
        assertTrue(onboarding.contains("MisulNavigationMotion"))

        assertFalse(setup.contains("PcButton"))
        assertFalse(setup.contains("PcField"))
        assertFalse(setup.contains("PcGroup"))
        assertFalse(setup.contains("PcIconButton"))
        assertFalse(setup.contains("PcRow"))
        assertFalse(setup.contains("PcSectionLabel"))
        assertTrue(setup.contains("MisulSelectionRow"))
        assertTrue(setup.contains("MisulField"))
        assertTrue(setup.contains("secure = true"))
        assertTrue(setup.contains("MisulActionButton"))
        assertTrue(setup.contains("MisulNavigationMotion"))
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun onboardingUsesFocusedModelSetupAndSkipLeadsToHonestChat() {
        UiTestSecureKeyStore.clear()
        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
        assertEquals("Misul Agent", app.getString(R.string.app_name))
        compose.setContent {
            PhoneCodeTheme(darkTheme = false) {
                OnboardingFlowHost(app.chatViewModel)
            }
        }

        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Required for agent work").assertIsDisplayed()
        compose.onNodeWithText("Optional access to files already on your phone").assertIsDisplayed()
        compose.onNodeWithText("Optional for repository sync").assertIsDisplayed()
        compose.onNodeWithText("Connect a model").performClick()

        compose.onNodeWithText("Choose how to connect").assertIsDisplayed()
        compose.onNodeWithText("Recommended providers").assertIsDisplayed()
        compose.onAllNodesWithText("Mistral").assertCountEquals(0)
        compose.onNodeWithText("More providers").performClick()
        compose.onNodeWithText("Mistral").assertIsDisplayed()
        compose.onAllNodesWithText("Add custom provider").assertCountEquals(0)

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Explore without a model").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Connect a model to start").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Set up a model").assertIsDisplayed()
        compose.onNodeWithContentDescription("Message").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Add attachment").assertIsNotEnabled()
        compose.onAllNodesWithText("Set up a model to chat").assertCountEquals(0)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/26-chat-unconfigured.png")

        compose.onNodeWithText("Set up a model").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/25-model-setup.png")
        compose.onNodeWithText("OpenAI").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.onNodeWithText("Save and continue").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Show API key").assertIsDisplayed()
        assertStableDetailChrome("OpenAI")
        captureScreenRoboImage("screenshots/27-model-setup-api-key.png")
        compose.onNodeWithContentDescription("OpenAI API key").performTextInput("test-key")
        compose.onNodeWithContentDescription("OpenAI API key").assertIsDisplayed()
        assertNull(UiTestSecureKeyStore.stored("openai"))
        compose.onNodeWithText("Save and continue").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("What should we build?").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Inspect this project").assertIsDisplayed()
        compose.onNodeWithText("Explain a build failure").assertIsDisplayed()
        compose.onNodeWithText("Plan a safe code change").assertIsDisplayed()
        compose.onAllNodesWithText("Build a small web app").assertCountEquals(0)
        compose.onNodeWithContentDescription("Message").assertIsEnabled()
    }

    @Test
    fun configuredModelUsesNeutralReadyCopyAndHidesModelSkip() {
        compose.setContent {
            PhoneCodeTheme(darkTheme = false) {
                OnboardingScreen(
                    step = 1,
                    onStepChange = {},
                    onConnectModels = {},
                    onConnectGitHub = {},
                    onCreateProject = {},
                    modelReady = true,
                    errorMessage = "The saved provider could not be activated.",
                    onDone = {},
                    onSkip = {},
                )
            }
        }

        compose.onNodeWithText("Model configured on this device").assertIsDisplayed()
        compose.onNodeWithText("The saved provider could not be activated.").assertIsDisplayed()
        compose.onAllNodesWithText("Explore without a model").assertCountEquals(0)
    }

    @Test
    fun modelSetupKeepsConnectionFailuresInContextUntilDismissed() {
        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
        val vm = app.chatViewModel
        compose.setContent {
            PhoneCodeTheme(darkTheme = false) {
                ModelSetupScreen(vm = vm, onBack = {}, onConfigured = {})
            }
        }

        vm.surfaceError("Could not open the sign-in page.")

        compose.onNodeWithText("Could not open the sign-in page.").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        compose.onAllNodesWithText("Could not open the sign-in page.").assertCountEquals(0)
    }

    @Test
    fun modelSetupVisibleBackReturnsFromDetailBeforeDismissingRoot() {
        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
        var rootBackCount = 0
        compose.setContent {
            PhoneCodeTheme(darkTheme = false) {
                ModelSetupScreen(
                    vm = app.chatViewModel,
                    onBack = { rootBackCount++ },
                    onConfigured = {},
                )
            }
        }

        compose.onNodeWithText("OpenAI").performClick()
        compose.onNodeWithContentDescription("OpenAI API key").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Choose how to connect").assertIsDisplayed()
        assertEquals(0, rootBackCount)

        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, rootBackCount)
    }

    @Test
    fun providerSetupFailureCopyDistinguishesStorageFromActivation() {
        assertEquals(
            "Misul Agent could not save this API key in secure storage.",
            providerSetupFailureMessage(keySaved = false),
        )
        assertEquals(
            "API key saved, but Misul Agent could not activate an available model for this provider.",
            providerSetupFailureMessage(keySaved = true),
        )
    }

    private fun assertStableDetailChrome(title: String) {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val back = compose.onNodeWithContentDescription("Back").fetchSemanticsNode().boundsInRoot
        val heading = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        assertTrue("Back is outside the capture root: $back vs $root", back.left >= root.left)
        assertTrue("Back is outside the capture root: $back vs $root", back.right <= root.right)
        assertTrue("Title is above the capture root: $heading vs $root", heading.top >= root.top)
        assertTrue(
            "Title is not centered in the capture root: $heading vs $root",
            abs((heading.left + heading.right - root.left - root.right) / 2f) <= 8f * compose.density.density,
        )
    }
}

@Composable
private fun OnboardingFlowHost(vm: ChatViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var route by remember { mutableStateOf("onboarding") }
    var setupReturnRoute by remember { mutableStateOf("onboarding") }
    var onboardingStep by remember { mutableIntStateOf(0) }

    when (route) {
        "onboarding" -> OnboardingScreen(
            step = onboardingStep,
            onStepChange = { onboardingStep = it },
            onConnectModels = {
                setupReturnRoute = "onboarding"
                route = "model-setup"
            },
            onConnectGitHub = {},
            onCreateProject = {},
            modelReady = state.models.isNotEmpty() && vm.hasConfiguredProvider(),
            onDone = {
                if (vm.activateConfiguredModel()) route = "chat"
            },
            onSkip = { route = "chat" },
        )
        "model-setup" -> ModelSetupScreen(
            vm = vm,
            onBack = { route = setupReturnRoute },
            onConfigured = { route = setupReturnRoute },
        )
        else -> ChatScreen(
            vm = vm,
            onOpenDrawer = {},
            onOpenModelSetup = {
                setupReturnRoute = "chat"
                route = "model-setup"
            },
            onOpenProviderSetup = {
                setupReturnRoute = "chat"
                route = "model-setup"
            },
        )
    }
}
