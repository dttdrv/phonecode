package dev.phonecode.app.ui.theme

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeEffectScope
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.HazeTint
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.border

/**
 * The DISSOLVE band style (status-bar / behind-composer zones): NO TINT AT ALL - the bar areas
 * are clear glass. Legibility comes from light progressive blur plus a gentle progressive darken
 * (see [blurFade]), never from a background wash (device feedback: any tint there reads as a
 * translucent navbar strip).
 */
@Composable
fun phoneHazeBand(): HazeStyle {
    return HazeStyle(
        backgroundColor = Color.Transparent,
        tints = emptyList(),
        blurRadius = 4.dp,
        noiseFactor = 0f,
    )
}

private val isRobolectric = Build.FINGERPRINT == "robolectric"
private const val EdgeTintAlpha = 0.88f

private fun HazeEffectScope.applyDefaults() {
    if (isRobolectric) blurEnabled = false
}

/**
 * A dissolve band: a light blur that ramps in a little before the bar, with the content fading
 * into the page background right at the edge - a clean fade-out, not a darkening frost slab.
 * [edgeColor] is the page background the content dissolves into.
 */
fun Modifier.progressiveBlurEdge(
    state: HazeState,
    style: HazeStyle,
    fromTop: Boolean,
    edgeColor: Color,
): Modifier {
    if (isRobolectric) return edgeDissolve(fromTop, edgeColor)
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S) return edgeDissolve(fromTop, edgeColor)

    val onset = CubicBezierEasing(0.55f, 0f, 0.82f, 0.6f)
    return hazeEffect(state, style) {
        applyDefaults()
        progressive = HazeProgressive.verticalGradient(
            easing = onset,
            startIntensity = if (fromTop) 1f else 0f,
            endIntensity = if (fromTop) 0f else 1f,
        )
    }.edgeDissolve(fromTop, edgeColor)
}

private fun Modifier.edgeDissolve(fromTop: Boolean, edgeColor: Color): Modifier =
    background(
        Brush.verticalGradient(
            colorStops = if (fromTop) {
                arrayOf(
                    0f to edgeColor.copy(alpha = EdgeTintAlpha),
                    0.50f to edgeColor.copy(alpha = 0.82f),
                    0.78f to edgeColor.copy(alpha = 0.32f),
                    1f to Color.Transparent,
                )
            } else {
                arrayOf(
                    0f to Color.Transparent,
                    0.20f to edgeColor.copy(alpha = 0.24f),
                    0.45f to edgeColor.copy(alpha = 0.70f),
                    1f to edgeColor.copy(alpha = EdgeTintAlpha),
                )
            },
        ),
    )

fun Modifier.blurFade(state: HazeState, style: HazeStyle, fromTop: Boolean, edgeColor: Color): Modifier =
    progressiveBlurEdge(state, style, fromTop, edgeColor)


/** The page content behind floating chrome; screens that float controls over content provide it. */
val LocalGlassHaze = staticCompositionLocalOf<HazeState?> { null }

/**
 * Liquid-glass material: the content behind is blurred and lightly tinted, with a bright rim
 * catching the light along the top edge. Without a blur source (or before API 32) it falls back to
 * the same tint made opaque, so the control stays legible.
 */
fun Modifier.liquidGlass(state: HazeState, shape: Shape, dark: Boolean, surface: Color): Modifier {
    val tint = surface.copy(alpha = if (dark) 0.52f else 0.72f)
    val style = HazeStyle(
        backgroundColor = surface,
        tints = listOf(HazeTint(tint)),
        blurRadius = 20.dp,
        noiseFactor = 0f,
        fallbackTint = HazeTint(surface),
    )
    // Dark glass catches light along its top edge; light glass needs a faint ink edge instead,
    // since a white rim vanishes against a white page.
    val rim = Brush.verticalGradient(
        if (dark) listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.05f))
        else listOf(Color.Black.copy(alpha = 0.06f), Color.Black.copy(alpha = 0.11f)),
    )
    return this
        .hazeEffect(state, style) { applyDefaults() }
        .border(1.dp, rim, shape)
}
