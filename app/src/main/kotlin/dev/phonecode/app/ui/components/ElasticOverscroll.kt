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
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun Modifier.elasticOverscroll(): Modifier {
    val scope = rememberCoroutineScope()
    var pull by remember { mutableFloatStateOf(0f) }
    var settle by remember { mutableStateOf<Job?>(null) }
    val reset = remember(scope) {
        {
            settle?.cancel()
            settle = scope.launch {
                animate(
                    initialValue = pull,
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
                ) { value, _ -> pull = value }
            }
        }
    }
    val connection = remember(reset) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || pull == 0f || available.y * pull >= 0f) return Offset.Zero
                settle?.cancel()
                val consumed = if (abs(available.y) < abs(pull)) available.y else -pull
                pull += consumed
                return Offset(0f, consumed)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (available.y != 0f) {
                    settle?.cancel()
                    pull += available.y
                } else if (consumed.y != 0f && pull != 0f) {
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
            val distance = (1f - 1f / (abs(pull) * 0.55f / size.height + 1f)) * size.height * if (pull < 0f) -1f else 1f
            transformOrigin = TransformOrigin(0.5f, if (distance >= 0f) 0f else 1f)
            translationY = distance * 0.55f
            scaleY = 1f + abs(distance) / size.height * 0.25f
        }
    }
}
