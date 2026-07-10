package dev.phonecode.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
                OnboardingScreen(onConnectModels = {}, onConnectGitHub = {}, onCreateProject = {}, onDone = {})
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/15-onboarding-welcome.png")
        compose.onNodeWithText("Get started").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/16-onboarding-connect.png")
    }
}
