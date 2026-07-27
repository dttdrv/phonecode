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
import com.github.takahirom.roborazzi.captureRoboImage
import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.app.agent.ChatViewModel
import dev.phonecode.app.ui.chat.ChatScreen
import dev.phonecode.app.ui.onboarding.ModelSetupScreen
import dev.phonecode.app.ui.onboarding.OnboardingScreen
import dev.phonecode.app.ui.onboarding.providerSetupFailureMessage
import dev.phonecode.app.ui.theme.PhoneCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
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
class OnboardingFlowTest {

    private val compose = createComposeRule()

    @get:Rule
    val rule = compose

    @Test
    fun onboardingUsesFocusedModelSetupAndSkipLeadsToHonestChat() {
        UiTestSecureKeyStore.clear()
        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
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
        compose.onNodeWithContentDescription("Show OpenAI API key").assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/27-model-setup-api-key.png")
        compose.onNodeWithContentDescription("OpenAI API key").performTextInput("test-key")
        compose.onNodeWithText("API key").assertIsDisplayed()
        assertNull(UiTestSecureKeyStore.stored("openai"))
        compose.onNodeWithText("Save and continue").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("What should we build?").fetchSemanticsNodes().isNotEmpty()
        }
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
            "PhoneCode could not save this API key in secure storage.",
            providerSetupFailureMessage(keySaved = false),
        )
        assertEquals(
            "API key saved, but PhoneCode could not activate an available model for this provider.",
            providerSetupFailureMessage(keySaved = true),
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
