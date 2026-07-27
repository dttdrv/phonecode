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
import dev.phonecode.app.ui.theme.PhoneCodeTheme
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
        compose.onNodeWithText("Connect a model").performClick()

        compose.onNodeWithText("Choose how to connect").assertIsDisplayed()
        compose.onNodeWithText("API key providers").assertIsDisplayed()
        compose.onAllNodesWithText("Add custom provider").assertCountEquals(0)

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Skip setup for now").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Connect a model to start").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Set up a model").assertIsDisplayed()
        compose.onNodeWithContentDescription("Message").assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/26-chat-unconfigured.png")

        compose.onNodeWithText("Set up a model").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/25-model-setup.png")
        compose.onNodeWithText("OpenAI").performClick()
        compose.onNodeWithText("Save and continue").assertIsNotEnabled()
        compose.onRoot().captureRoboImage("screenshots/27-model-setup-api-key.png")
        compose.onNodeWithContentDescription("OpenAI API key").performTextInput("test-key")
        assertNull(UiTestSecureKeyStore.stored("openai"))
        compose.onNodeWithText("Save and continue").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("What should we build?").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Message").assertIsEnabled()
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
