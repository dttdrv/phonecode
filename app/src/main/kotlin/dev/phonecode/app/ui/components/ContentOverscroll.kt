package dev.phonecode.app.ui.components

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.scrollableArea
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun rememberContentOverscroll(): OverscrollEffect? = rememberOverscrollEffect()

@Composable
fun Modifier.contentVerticalScroll(
    state: ScrollState,
): Modifier {
    val effect = rememberContentOverscroll()
    val fallback = rememberScrollableState { 0f }
    val canScroll = state.maxValue > 0
    return verticalScroll(
        state = state,
        overscrollEffect = effect.takeIf { canScroll },
        enabled = canScroll,
    ).scrollableArea(
        state = fallback,
        orientation = Orientation.Vertical,
        overscrollEffect = effect.takeIf { !canScroll },
        enabled = !canScroll,
    )
}

@Composable
fun Modifier.shortContentVerticalOverscroll(
    enabled: Boolean,
    effect: OverscrollEffect? = rememberContentOverscroll(),
): Modifier {
    val fallback = rememberScrollableState { 0f }
    return scrollableArea(
        state = fallback,
        orientation = Orientation.Vertical,
        overscrollEffect = effect.takeIf { enabled },
        enabled = enabled,
    )
}
