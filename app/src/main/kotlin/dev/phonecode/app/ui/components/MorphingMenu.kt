package dev.phonecode.app.ui.components

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.phonecode.app.ui.theme.PhoneEasings

@Composable
fun MorphingMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    above: Boolean,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    anchorSize: Dp = 40.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val margin = with(density) { 8.dp.roundToPx() }
    val anchorPixels = with(density) { anchorSize.toPx() }
    val finalCorner = with(density) { 24.dp.toPx() }
    val outlineWidth = with(density) { 1.dp.toPx() }
    val background = MaterialTheme.colorScheme.surfaceContainerHigh
    val outline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val shadow = MaterialTheme.colorScheme.scrim.copy(alpha = 0.055f)
    val positionProvider = remember(above, alignEnd, margin) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val rawX = if (alignEnd) anchorBounds.right - popupContentSize.width else anchorBounds.left
                val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
                val rawY = if (above) anchorBounds.bottom - popupContentSize.height else anchorBounds.top
                val maxY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)
                return IntOffset(rawX.coerceIn(margin, maxX), rawY.coerceIn(margin, maxY))
            }
        }
    }
    val state = remember { MutableTransitionState(false) }
    LaunchedEffect(expanded) { state.targetState = expanded }
    if (expanded || state.currentState || !state.isIdle) {
        val transition = rememberTransition(state, label = "morphingMenu")
        val progress = transition.animateFloat(
            transitionSpec = {
                if (targetState) tween(200, easing = PhoneEasings.easeInOut)
                else tween(150, easing = PhoneEasings.easeInOut)
            },
            label = "menuProgress",
        ) { if (it) 1f else 0f }
        val clip = remember { Path() }
        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true),
        ) {
            Box(
                modifier.drawWithContent {
                    val value = progress.value
                    val width = anchorPixels + (size.width - anchorPixels) * value
                    val height = anchorPixels + (size.height - anchorPixels) * value
                    val left = if (alignEnd) size.width - width else 0f
                    val top = if (above) size.height - height else 0f
                    val radius = anchorPixels / 2f + (finalCorner - anchorPixels / 2f) * value
                    repeat(4) { index ->
                        val spread = (4 - index) * density.density
                        drawRoundRect(
                            shadow,
                            Offset(left - spread, top - spread + 2 * density.density),
                            Size(width + spread * 2, height + spread * 2),
                            CornerRadius(radius + spread),
                        )
                    }
                    drawRoundRect(background, Offset(left, top), Size(width, height), CornerRadius(radius))
                    clip.reset()
                    clip.addRoundRect(RoundRect(left, top, left + width, top + height, radius, radius))
                    clipPath(clip) { this@drawWithContent.drawContent() }
                    drawRoundRect(
                        outline,
                        Offset(left + outlineWidth / 2f, top + outlineWidth / 2f),
                        Size(width - outlineWidth, height - outlineWidth),
                        CornerRadius((radius - outlineWidth / 2f).coerceAtLeast(0f)),
                        style = Stroke(outlineWidth),
                    )
                },
            ) {
                Column(
                    Modifier.graphicsLayer {
                        val contentProgress = ((progress.value - 0.35f) / 0.65f).coerceIn(0f, 1f)
                        alpha = contentProgress
                        translationY = (1f - contentProgress) * if (above) 4.dp.toPx() else (-4).dp.toPx()
                    },
                    content = content,
                )
            }
        }
    }
}
