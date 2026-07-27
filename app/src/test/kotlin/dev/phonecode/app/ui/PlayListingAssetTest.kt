package dev.phonecode.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.phonecode.app.R
import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.app.agent.ChatUiState
import dev.phonecode.app.ui.onboarding.ModelSetupScreen
import dev.phonecode.app.ui.onboarding.OnboardingScreen
import dev.phonecode.app.ui.theme.PhoneCodeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.flow.MutableStateFlow

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    qualifiers = "w1024dp-h500dp-mdpi",
)
class PlayListingAssetTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun featureGraphicUsesTheRealWelcomeExperience() {
        compose.setContent {
            PhoneCodeTheme(darkTheme = true) {
                OnboardingScreen(
                    step = 0,
                    onStepChange = {},
                    onConnectModels = {},
                    onConnectGitHub = {},
                    onCreateProject = {},
                    onDone = {},
                )
            }
        }

        compose.onRoot().captureRoboImage(
            "../play/0.5.0/graphics/feature-graphic-source.png",
        )
    }

    @Test
    @Config(qualifiers = "w512dp-h512dp-mdpi")
    fun playIconUsesTheRealAdaptiveIconArtwork() {
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
            }
        }

        compose.onRoot().captureRoboImage("../play/0.5.0/graphics/app-icon.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xxxhdpi")
    fun playPhoneOnboardingScreenshots() {
        compose.setContent {
            var step by remember { mutableIntStateOf(0) }
            PhoneCodeTheme(darkTheme = true) {
                OnboardingScreen(
                    step = step,
                    onStepChange = { step = it },
                    onConnectModels = {},
                    onConnectGitHub = {},
                    onCreateProject = {},
                    onDone = {},
                )
            }
        }

        compose.onRoot().captureRoboImage(
            "../play/0.5.0/graphics/phone/05-welcome.png",
        )
        compose.onNodeWithText("Get started").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(
            "../play/0.5.0/graphics/phone/06-setup.png",
        )
    }

    @Test
    @Config(
        qualifiers = "w360dp-h640dp-xxxhdpi",
        shadows = [UiTestSecureKeyStore::class],
    )
    fun playPhoneProviderScreenshot() {
        UiTestSecureKeyStore.clear()
        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
        val stateField = app.chatViewModel.javaClass.getDeclaredField("_state").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(app.chatViewModel) as MutableStateFlow<ChatUiState>
        state.value = state.value.copy(
            codexOAuthAvailable = false,
            codexConnected = false,
        )
        compose.setContent {
            PhoneCodeTheme(darkTheme = true) {
                ModelSetupScreen(
                    vm = app.chatViewModel,
                    onBack = {},
                    onConfigured = {},
                )
            }
        }

        compose.onRoot().captureRoboImage(
            "../play/0.5.0/graphics/phone/07-model-providers.png",
        )
    }
}
