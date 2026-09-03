package dev.phonecode.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemBarMotionContractTest {
    private val root = generateSequence(
        File(requireNotNull(System.getProperty("user.dir"))).absoluteFile,
    ) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    private fun source(relativePath: String) = File(root, relativePath).readText()

    @Test
    fun opaqueScreenChromeDoesNotOwnSystemBarInsets() {
        val chat = source("app/src/main/kotlin/dev/phonecode/app/ui/chat/ChatScreen.kt")
        val settings = source("app/src/main/kotlin/dev/phonecode/app/ui/settings/SettingsScreen.kt")
        val onboarding = source("app/src/main/kotlin/dev/phonecode/app/ui/onboarding/OnboardingScreen.kt")
        val modelSetup = source("app/src/main/kotlin/dev/phonecode/app/ui/onboarding/ModelSetupScreen.kt")
        val shell = source("app/src/main/kotlin/dev/phonecode/app/ui/PhoneCodeApp.kt")

        assertFalse(chat.contains(".background(colors.background)\n                // Union of ime+navbar"))
        assertFalse(settings.contains(".background(colors.background).statusBarsPadding()"))
        assertFalse(onboarding.contains(".background(colors.background).systemBarsPadding()"))
        assertFalse(
            modelSetup.contains(
                ".background(MaterialTheme.colorScheme.background)\n            .statusBarsPadding()",
            ),
        )
        assertFalse(shell.contains(".windowInsetsPadding(WindowInsets.systemBars).clipToBounds()"))
    }

    @Test
    fun settingsNestedPagesUseDepthAwareAnimatedContent() {
        val navigation = source("app/src/main/kotlin/dev/phonecode/app/ui/settings/SettingsNavigation.kt")

        assertTrue(navigation.contains("NavHost("))
        assertTrue(navigation.contains("settingsRouteGraph"))
        assertTrue(navigation.contains("enterTransition = { if (motionEnabled) MisulNavigationMotion.forwardEnter()"))
        assertTrue(navigation.contains("exitTransition = { if (motionEnabled) MisulNavigationMotion.forwardExit()"))
        assertTrue(navigation.contains("popEnterTransition = { if (motionEnabled) MisulNavigationMotion.backEnter()"))
        assertTrue(navigation.contains("popExitTransition = { if (motionEnabled) MisulNavigationMotion.backExit()"))
    }
}
