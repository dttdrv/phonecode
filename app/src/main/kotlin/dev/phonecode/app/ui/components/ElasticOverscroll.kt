package dev.phonecode.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun Modifier.elasticOverscroll(): Modifier {
    val scope = rememberCoroutineScope()
    val limit = with(LocalDensity.current) { 72.dp.toPx() }
    var offset by remember { mutableFloatStateOf(0f) }
    var settle by remember { mutableStateOf<Job?>(null) }
    val reset = remember(scope) {
        {
            settle?.cancel()
            settle = scope.launch {
                animate(
                    initialValue = offset,
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
                ) { value, _ -> offset = value }
            }
        }
    }
    val connection = remember(limit, reset) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (available.y != 0f) {
                    settle?.cancel()
                    offset = (offset + available.y * 0.18f).coerceIn(-limit, limit)
                } else if (consumed.y != 0f && offset != 0f) {
                    reset()
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                reset()
                return Velocity.Zero
            }
        }
    }
    return nestedScroll(connection).graphicsLayer {
        if (size.height > 0f) {
            transformOrigin = TransformOrigin(0.5f, if (offset >= 0f) 0f else 1f)
            translationY = offset * 0.34f
            scaleY = 1f + abs(offset) / size.height * 0.32f
        }
    }
}
