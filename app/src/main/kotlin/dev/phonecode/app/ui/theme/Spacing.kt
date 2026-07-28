package dev.phonecode.app.ui.theme

import androidx.compose.ui.unit.dp

/** Apple-HIG spacing scale (4dp base) - design/specs/design-tokens.md. The monochrome UI snaps to these. */
object Spacing {
    val xs = 8.dp
    val s = 12.dp
    val m = 16.dp
    val l = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
    /** Painted control size; the surrounding interactive target remains [touchTarget]. */
    val controlVisual = 40.dp
    /** Painted size for dense selectors and filter pills inside a 48dp target. */
    val compactVisual = 36.dp
    val touchTarget = 48.dp
    val navBarHeight = 52.dp
}
