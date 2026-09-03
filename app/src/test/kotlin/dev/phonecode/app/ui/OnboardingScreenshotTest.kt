package dev.phonecode.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.phonecode.app.ui.onboarding.OnboardingScreen
import dev.phonecode.app.ui.theme.PhoneCodeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Onboarding renders DIRECTLY (createComposeRule, no MainActivity): the app-level path depends
 * on an async settings load that is flaky under Robolectric's stale-looper quirk for any test
 * that isn't first in its worker process. Composing the screen itself is deterministic.
 * (ui-test-manifest ships ComponentActivity as debugImplementation so Robolectric resolves it.)
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class OnboardingScreenshotTest {

    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(debugManifestOnlyRule()).around(compose)

    @Test
    fun onboardingPages() {
        compose.setContent {
            PhoneCodeTheme(darkTheme = true) {
                val step = remember { mutableIntStateOf(0) }
                OnboardingScreen(
                    step = step.intValue,
                    onStepChange = { step.intValue = it },
                    onConnectModels = {},
                    onConnectGitHub = {},
                    onCreateProject = {},
                    onDone = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Misul Agent").assertIsDisplayed()
        compose.onNodeWithText("Get started").assertIsDisplayed()
        compose.onNodeWithText("Private project workspaces").assertHasNoClickAction()
        compose.onAllNodesWithTag("misul-row-divider-content").assertCountEquals(2)
        compose.onRoot().captureRoboImage("screenshots/15-onboarding-welcome.png")
        compose.onNodeWithText("Get started").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithText("Setup").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/16-onboarding-connect.png")
    }
}
