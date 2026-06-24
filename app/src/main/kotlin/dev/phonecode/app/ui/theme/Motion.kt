package dev.phonecode.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

// iOS-feel motion (from design/specs/motion.md + design-tokens.md). Springs for spatial moves,
// tweens for opacity/colour. damping 0.9 = crisp settle, 0.85 = slight weighty overshoot for drawers.
object PhoneSprings {
    val standard get() = spring<Float>(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow)
    val emphasized get() = spring<Float>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
    val quick get() = spring<Float>(dampingRatio = 0.9f, stiffness = 600f)

    // Full-screen surfaces (the sidebar drawer): lower stiffness for a longer fluid glide,
    // critical damping so a screen-sized panel never visibly overshoots - the old emphasized
    // spring stopped so fast the drawer read as mechanical (round-3: "opens too robotically").
    val drawer get() = spring<Float>(dampingRatio = 1f, stiffness = 280f)

    fun <T> standardSpec() = spring<T>(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow)
    fun <T> emphasizedSpec() = spring<T>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
    fun <T> quickSpec() = spring<T>(dampingRatio = 0.9f, stiffness = 600f)
}

object PhoneEasings {
    val iOSStandard = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
    val emphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val emphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

object PhoneTweens {
    val scrimEnter get() = tween<Float>(durationMillis = 300, easing = PhoneEasings.emphasizedDecelerate)
    val scrimExit get() = tween<Float>(durationMillis = 200, easing = PhoneEasings.emphasizedAccelerate)
    val popEnter get() = tween<Float>(durationMillis = 220, easing = PhoneEasings.iOSStandard)
    val popExit get() = tween<Float>(durationMillis = 180, easing = PhoneEasings.emphasizedAccelerate)
    val listEntrance get() = tween<Float>(durationMillis = 450, easing = PhoneEasings.iOSStandard)
}
