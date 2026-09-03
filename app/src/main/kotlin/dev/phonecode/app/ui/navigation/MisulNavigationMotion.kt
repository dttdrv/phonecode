package dev.phonecode.app.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import dev.phonecode.app.ui.theme.PhoneDurations
import dev.phonecode.app.ui.theme.PhoneEasings

/** The one non-gesture transition vocabulary for the app's navigation hosts. */
object MisulNavigationMotion {
    fun forwardEnter(): EnterTransition =
        slideInHorizontally(tween(PhoneDurations.NAV_IN, easing = PhoneEasings.easeOut)) { it }

    fun forwardExit(): ExitTransition =
        slideOutHorizontally(tween(PhoneDurations.NAV_OUT, easing = PhoneEasings.easeInOut)) { -it / 4 }

    fun backEnter(): EnterTransition =
        slideInHorizontally(tween(220, easing = PhoneEasings.easeOut)) { -it / 4 }

    fun backExit(): ExitTransition =
        slideOutHorizontally(tween(PhoneDurations.NAV_OUT, easing = PhoneEasings.easeInOut)) { it }
}
