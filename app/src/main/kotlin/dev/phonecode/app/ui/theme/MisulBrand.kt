package dev.phonecode.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlin.math.cos
import kotlin.math.sin

/** Misul.org's canonical cobalt values, preserved in their native OKLCH/Oklab color space. */
private fun oklch(lightness: Float, chroma: Float, hueDegrees: Float): Color {
    val hue = Math.toRadians(hueDegrees.toDouble())
    return Color(
        lightness,
        (chroma * cos(hue)).toFloat(),
        (chroma * sin(hue)).toFloat(),
        1f,
        ColorSpaces.Oklab,
    )
}

val MisulCobaltDark = oklch(lightness = 0.720f, chroma = 0.190f, hueDegrees = 255f)
val MisulCobaltLight = oklch(lightness = 0.500f, chroma = 0.220f, hueDegrees = 255f)

val LocalMisulAccent = staticCompositionLocalOf { MisulCobaltDark }
