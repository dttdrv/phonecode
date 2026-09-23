package dev.phonecode.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Line glyphs the Material set lacks. Drawn at 24dp with a 1.8 stroke so they sit with the
 * outlined Material icons used elsewhere.
 */
object PhoneIcons {
    /** Two-bar menu: a full top bar over a shorter bottom bar. */
    val Menu: ImageVector by lazy {
        stroked("PhoneIcons.Menu") {
            moveTo(4f, 8.5f); lineTo(20f, 8.5f)
            moveTo(4f, 15.5f); lineTo(14f, 15.5f)
        }
    }

    /** iOS-style back chevron; mirrored for right-to-left layouts by the caller's autoMirror. */
    val Back: ImageVector by lazy {
        stroked("PhoneIcons.Back", autoMirror = true) {
            moveTo(14.5f, 5f); lineTo(7.5f, 12f); lineTo(14.5f, 19f)
        }
    }

    /** Branch: a trunk with one limb splitting off to a second tip. */
    val Branch: ImageVector by lazy {
        stroked("PhoneIcons.Branch") {
            // trunk
            moveTo(7f, 8.2f); lineTo(7f, 15.8f)
            // limb from trunk to the right tip
            moveTo(17f, 8.2f)
            curveTo(17f, 12f, 12f, 12f, 7.6f, 15f)
            // three tips as small circles
            moveTo(9.2f, 6f); arcTo(2.2f, 2.2f, 0f, true, true, 4.8f, 6f); arcTo(2.2f, 2.2f, 0f, true, true, 9.2f, 6f)
            moveTo(19.2f, 6f); arcTo(2.2f, 2.2f, 0f, true, true, 14.8f, 6f); arcTo(2.2f, 2.2f, 0f, true, true, 19.2f, 6f)
            moveTo(9.2f, 18f); arcTo(2.2f, 2.2f, 0f, true, true, 4.8f, 18f); arcTo(2.2f, 2.2f, 0f, true, true, 9.2f, 18f)
        }
    }

    /** Open square with a pen crossing its corner: start a new chat. */
    val NewChat: ImageVector by lazy {
        stroked("PhoneIcons.NewChat") {
            moveTo(11f, 4.5f); lineTo(7f, 4.5f)
            curveTo(5.6f, 4.5f, 4.5f, 5.6f, 4.5f, 7f)
            lineTo(4.5f, 17f)
            curveTo(4.5f, 18.4f, 5.6f, 19.5f, 7f, 19.5f)
            lineTo(17f, 19.5f)
            curveTo(18.4f, 19.5f, 19.5f, 18.4f, 19.5f, 17f)
            lineTo(19.5f, 13f)
            moveTo(17.6f, 3.9f)
            lineTo(20.1f, 6.4f)
            lineTo(12.2f, 14.3f)
            lineTo(9f, 15f)
            lineTo(9.7f, 11.8f)
            close()
        }
    }

    private fun stroked(
        name: String,
        autoMirror: Boolean = false,
        block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
    ): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = autoMirror).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()
}
