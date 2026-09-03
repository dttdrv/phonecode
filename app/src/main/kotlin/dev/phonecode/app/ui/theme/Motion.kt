package dev.phonecode.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

object PhoneSprings {
    val standard get() = spring<Float>(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)
    val quick get() = spring<Float>(dampingRatio = 1f, stiffness = 600f)

    val drawer get() = spring<Float>(dampingRatio = 1f, stiffness = 280f)

    fun <T> standardSpec() = spring<T>(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)
    fun <T> emphasizedSpec() = spring<T>(dampingRatio = 0.92f, stiffness = Spring.StiffnessMediumLow)
    fun <T> quickSpec() = spring<T>(dampingRatio = 1f, stiffness = 600f)
}

object PhoneEasings {
    /** Enter/exit, fades, banners, and small UI feedback. */
    val easeOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

    /** Elements moving or morphing while remaining on screen. */
    val easeInOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

    /** Reserved for drawer-like movement; gesture-driven drawers remain spring-driven. */
    val drawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
}

object PhoneDurations {
    const val PRESS_IN = 70
    const val PRESS_OUT = 140
    const val POPOVER_IN = 180
    const val POPOVER_OUT = 120
    const val NAV_IN = 240
    const val NAV_OUT = 180
    const val MESSAGE_IN = 180
    const val STATE_CHANGE = 140
}

object PhoneTweens {
    val popEnter get() = tween<Float>(durationMillis = 220, easing = PhoneEasings.easeOut)
    val popExit get() = tween<Float>(durationMillis = 150, easing = PhoneEasings.easeOut)
}
