package dev.phonecode.app.ui.theme

import android.animation.ValueAnimator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

val LocalNeuralPhase = staticCompositionLocalOf<State<Float>?> { null }

/**
 * The ethereal accent layer: Neural Expressive's "gradient = cognition" idea rendered entirely
 * in Misul cobalt. The accent is reserved for moments the model is alive (thinking, streaming);
 * all general chrome and primary actions stay monochrome.
 */
object Ethereal {
    /** Shimmer stops built from the theme's ink: bright → faint → mid → bright (seamless loop). */
    fun cycle(ink: Color): List<Color> = listOf(
        ink.copy(alpha = 0.85f),
        ink.copy(alpha = 0.18f),
        ink.copy(alpha = 0.55f),
        ink.copy(alpha = 0.85f),
    )
}

/** A 0→1 phase that loops forever - one slow clock drives every ethereal animation. */
@Composable
fun rememberNeuralPhase(durationMillis: Int = 3600): State<Float> {
    if (!ValueAnimator.areAnimatorsEnabled()) return remember { mutableFloatStateOf(0.5f) }
    return rememberInfiniteTransition(label = "ethereal").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis, easing = LinearEasing)),
        label = "etherealPhase",
    )
}

/** A slow breathe (0→1→0) for idle "alive" pulses - unhurried, installation-like. */
@Composable
fun rememberNeuralBreath(durationMillis: Int = 2600): State<Float> {
    LocalNeuralPhase.current?.let { phase ->
        return remember(phase) { derivedStateOf { 1f - abs(phase.value * 2f - 1f) } }
    }
    if (!ValueAnimator.areAnimatorsEnabled()) return remember { mutableFloatStateOf(0.5f) }
    return rememberInfiniteTransition(label = "breath").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis, easing = LinearEasing), RepeatMode.Reverse),
        label = "etherealBreath",
    )
}

/**
 * A sweeping monochrome shimmer brush whose position follows [phase]. SEAMLESS loop: the stops
 * begin and end on the same color, the tile mode repeats, and one full phase translates the
 * pattern by exactly one period ALONG the gradient axis - phase 1 is pixel-identical to phase 0
 * (device feedback: the old half-period Mirror translation visibly snapped at every wrap).
 */
fun neuralSweepBrush(phase: Float, ink: Color, extent: Float = 660f): Brush =
    Brush.linearGradient(
        colors = Ethereal.cycle(ink),
        start = Offset(phase * extent, phase * extent),
        end = Offset(phase * extent + extent, phase * extent + extent),
        tileMode = TileMode.Repeated,
    )

/**
 * A faint luminous shimmer ring around a control - the "model is running" treatment for the
 * composer pill. Quiet by design: thin, low-alpha, slow. Draws nothing when [active] is false.
 */
@Composable
fun Modifier.neuralRing(active: Boolean, shape: Shape, width: Dp = 1.dp): Modifier {
    if (!active) return this
    val ink = LocalMisulAccent.current
    val shared = LocalNeuralPhase.current
    val local = if (shared == null) rememberNeuralPhase() else null
    val phase = requireNotNull(shared ?: local)
    return this.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val stroke = Stroke(width.toPx())
        onDrawWithContent {
            drawContent()
            drawOutline(
                outline = outline,
                brush = neuralSweepBrush(phase.value, ink.copy(alpha = 0.7f)),
                style = stroke,
            )
        }
    }
}
