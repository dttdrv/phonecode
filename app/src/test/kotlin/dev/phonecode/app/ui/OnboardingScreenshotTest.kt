package dev.phonecode.app.ui

import android.content.ComponentName
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.phonecode.app.ui.onboarding.OnboardingScreen
import dev.phonecode.app.ui.theme.PhoneCodeTheme
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
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

    /** createComposeRule's host activity ships in ui-test-manifest, merged into the DEBUG manifest
     *  only - on the release unit-test variant the activity doesn't exist, so skip (not fail). */
    private val debugManifestOnly = TestRule { base, _: Description ->
        object : Statement() {
            override fun evaluate() {
                val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
                val present = runCatching {
                    ctx.packageManager.getActivityInfo(
                        ComponentName(ctx, "androidx.activity.ComponentActivity"), 0,
                    )
                }.isSuccess
                Assume.assumeTrue("ui-test-manifest activity absent (release variant)", present)
                base.evaluate()
            }
        }
    }

    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(debugManifestOnly).around(compose)

    @Test
    fun onboardingPages() {
        compose.setContent {
            PhoneCodeTheme(darkTheme = true) {
                OnboardingScreen(onConnectModels = {}, onConnectGitHub = {}, onDone = {})
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/15-onboarding-welcome.png")
        compose.onNodeWithText("Get started").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/16-onboarding-connect.png")
    }
}
