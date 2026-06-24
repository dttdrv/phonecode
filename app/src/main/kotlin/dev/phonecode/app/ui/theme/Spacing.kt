package dev.phonecode.app.ui.theme

import androidx.compose.ui.unit.dp

/** 8pt spacing grid from the design system - snap layout values to these, no more 6/9/10dp one-offs. */
object Spacing {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
    val s8 = 32.dp
    val s10 = 40.dp

    // Named Apple-HIG scale (4dp base) - design/specs/design-tokens.md. Used by the new monochrome UI.
    val xxs = 4.dp
    val xs = 8.dp
    val s = 12.dp
    val m = 16.dp
    val l = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
    val touchTarget = 44.dp
    val navBarHeight = 52.dp
    val inputHeight = 40.dp
}

/** Corner-radius scale: chips/code (sm), cards/rows/bubbles (md), composer/dialogs/sheets (lg), pills. */
object Radius {
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val pill = 999.dp
}

/** Minimum touch target (a smaller-looking control still reserves this via heightIn/sizeIn). */
val MinTouch = 44.dp
