package dev.phonecode.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.phonecode.app.ui.theme.phoneHazeBand
import dev.phonecode.app.ui.theme.progressiveBlurEdge

enum class ProgressiveEdge { TOP, BOTTOM }

@Composable
fun StretchSyncedScrollChrome(
    modifier: Modifier = Modifier,
    showTop: Boolean,
    showBottom: Boolean,
    topHeight: Dp,
    bottomHeight: Dp,
    content: @Composable BoxScope.(HazeState) -> Unit,
) {
    val hazeState = remember { HazeState() }
    Box(modifier) {
        Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
            content(hazeState)
        }
        if (showTop) ProgressiveBlurBand(ProgressiveEdge.TOP, hazeState, topHeight)
        if (showBottom) ProgressiveBlurBand(ProgressiveEdge.BOTTOM, hazeState, bottomHeight)
    }
}

@Composable
private fun BoxScope.ProgressiveBlurBand(
    edge: ProgressiveEdge,
    hazeState: HazeState,
    height: Dp,
) {
    val fromTop = edge == ProgressiveEdge.TOP
    val hazeStyle = phoneHazeBand()
    Box(
        Modifier
            .align(if (fromTop) Alignment.TopCenter else Alignment.BottomCenter)
            .fillMaxWidth()
            .height(height)
            .progressiveBlurEdge(hazeState, hazeStyle, fromTop, androidx.compose.material3.MaterialTheme.colorScheme.background),
    )
}
