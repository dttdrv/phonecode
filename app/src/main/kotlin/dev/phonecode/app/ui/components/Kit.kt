package dev.phonecode.app.ui.components

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.phonecode.app.ui.theme.Spacing
import dev.phonecode.app.ui.theme.PhoneDurations
import dev.phonecode.app.ui.theme.PhoneEasings
import dev.phonecode.app.ui.theme.PhoneSprings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/** Shared minimum target for compact interaction primitives. */
internal val MisulMinimumInteractiveSize = Spacing.touchTarget

@Stable
class PredictiveBackMotion internal constructor(
    internal val progress: State<Float>,
    internal val swipeEdge: State<Int>,
    internal val activeState: State<Boolean>,
) {
    /** Changes only when a predictive gesture starts or finishes, not for every progress frame. */
    val active: Boolean get() = activeState.value
}

@Composable
fun rememberPredictiveBackMotion(
    enabled: Boolean = true,
    onBack: suspend () -> Unit,
): PredictiveBackMotion {
    val progress = remember { mutableFloatStateOf(0f) }
    val swipeEdge = remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    val active = remember { mutableStateOf(false) }
    val currentOnBack by rememberUpdatedState(onBack)
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    PredictiveBackHandler(enabled = enabled && !imeVisible) { events ->
        try {
            events.collect { event ->
                active.value = true
                progress.floatValue = event.progress
                swipeEdge.intValue = event.swipeEdge
            }
            currentOnBack()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                animate(progress.floatValue, 0f, animationSpec = PhoneSprings.quick) { value, _ ->
                    progress.floatValue = value
                }
            }
            throw cancelled
        } finally {
            progress.floatValue = 0f
            active.value = false
        }
    }
    return remember { PredictiveBackMotion(progress, swipeEdge, active) }
}

fun Modifier.predictiveBackTransform(motion: PredictiveBackMotion): Modifier = graphicsLayer {
    val fraction = motion.progress.value.coerceIn(0f, 1f)
    val direction = if (motion.swipeEdge.value == BackEventCompat.EDGE_RIGHT) -1f else 1f
    val scale = 1f - 0.04f * fraction
    translationX = size.width * 0.1f * fraction * direction
    scaleX = scale
    scaleY = scale
    shadowElevation = 8.dp.toPx() * fraction
    transformOrigin = TransformOrigin.Center
    shape = RoundedCornerShape(24.dp)
    clip = fraction > 0f
}

@Composable
fun Modifier.pressFeedback(
    interaction: MutableInteractionSource,
    pressedScale: Float? = null,
    pressedAlpha: Float = 1f,
): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (pressed) pressedAlpha else 1f,
        animationSpec = if (pressed) {
            tween(PhoneDurations.PRESS_IN, easing = PhoneEasings.easeOut)
        } else {
            spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)
        },
        label = "pressAlpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) (pressedScale ?: 1f) else 1f,
        animationSpec = if (pressed) {
            tween(PhoneDurations.PRESS_IN, easing = PhoneEasings.easeOut)
        } else {
            spring(dampingRatio = 1f, stiffness = 600f)
        },
        label = "pressScale",
    )
    return this.graphicsLayer {
        this.alpha = alpha
        if (pressedScale != null) {
            scaleX = scale
            scaleY = scale
        }
    }
}

/** Context-usage ring (Claude-Code style). [fraction] 0..1 of the window used. */
@Composable
fun ContextRing(fraction: Float, modifier: Modifier = Modifier, stroke: Float = 3.5f, color: Color = MaterialTheme.colorScheme.onBackground) {
    val track = MaterialTheme.colorScheme.outlineVariant
    androidx.compose.foundation.Canvas(modifier) {
        val inset = stroke.dp.toPx() / 2
        val arcSize = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(track, 0f, 360f, false, topLeft, arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, topLeft, arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}
