package dev.phonecode.app.ui.theme

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeEffectScope
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * OUR blur (device feedback: "not Liquid Glass, our own kind of blur"): a backdrop blur tinted
 * with the theme background at ~55% - monochrome light passing through frosted tone, no hue,
 * no refraction. Real blur on Android 12+; Haze's scrim fallback below; forced to scrim under
 * Robolectric so screenshots stay deterministic and RenderEffect never runs on the JVM.
 * Radii trimmed twice on device feedback ("minimize the blur even more") - frost, not fog.
 */
@Composable
fun phoneHaze(): HazeStyle {
    val colors = MaterialTheme.colorScheme
    return HazeStyle(
        backgroundColor = colors.background,
        tints = listOf(HazeTint(colors.background.copy(alpha = 0.55f))),
        blurRadius = 14.dp,
        noiseFactor = 0f,
    )
}

/**
 * The DISSOLVE band style (status-bar / behind-composer zones): NO TINT AT ALL - the bar areas
 * are clear glass. Legibility comes from light progressive blur plus a gentle progressive darken
 * (see [blurFade]), never from a background wash (device feedback: any tint there reads as a
 * translucent navbar strip).
 */
@Composable
fun phoneHazeBand(): HazeStyle {
    val colors = MaterialTheme.colorScheme
    return HazeStyle(
        backgroundColor = colors.background,
        tints = emptyList(),
        blurRadius = 6.dp,
        noiseFactor = 0f,
    )
}

private val isRobolectric = Build.FINGERPRINT == "robolectric"

private fun HazeEffectScope.applyDefaults() {
    if (isRobolectric) blurEnabled = false
}

/** A floating blurred pill/chip - the v2 chrome surface (clip + tinted backdrop blur). */
fun Modifier.blurPill(state: HazeState, style: HazeStyle, shape: Shape = ShapePill): Modifier =
    clip(shape).hazeEffect(state, style) { applyDefaults() }

/** Blur WITHOUT clipping - for surfaces whose clip animates (the morph popouts). */
fun Modifier.blurSurface(state: HazeState, style: HazeStyle): Modifier =
    hazeEffect(state, style) { applyDefaults() }

/**
 * A dissolve band: light blur that STARTS LATE (eased onset - most of the band is untouched,
 * the frost ramps only near the bar edge) plus a gentle progressive darken riding the same ramp.
 * No tint anywhere (device feedback: "remove the tint... progressively darker once the blur
 * starts; less blur, starting later").
 */
fun Modifier.blurFade(state: HazeState, style: HazeStyle, fromTop: Boolean, darken: Float = 0.12f): Modifier {
    // Keeps intensity near zero through ~60% of the band, then ramps to the edge.
    val lateOnset = CubicBezierEasing(0.7f, 0f, 0.95f, 0.4f)
    return hazeEffect(state, style) {
        applyDefaults()
        progressive = HazeProgressive.verticalGradient(
            easing = lateOnset,
            startIntensity = if (fromTop) 1f else 0f,
            endIntensity = if (fromTop) 0f else 1f,
        )
    }.background(
        Brush.verticalGradient(
            if (fromTop) listOf(Color.Black.copy(alpha = darken), Color.Transparent)
            else listOf(Color.Transparent, Color.Black.copy(alpha = darken)),
        ),
    )
}
