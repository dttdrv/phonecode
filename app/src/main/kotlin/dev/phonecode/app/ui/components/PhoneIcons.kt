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
    /** Sidebar: a window with its left panel ruled off - opens the workspace drawer. */
    val Menu: ImageVector by lazy {
        stroked("PhoneIcons.Menu") {
            moveTo(6.5f, 5f); lineTo(17.5f, 5f)
            curveTo(18.9f, 5f, 20f, 6.1f, 20f, 7.5f); lineTo(20f, 16.5f)
            curveTo(20f, 17.9f, 18.9f, 19f, 17.5f, 19f); lineTo(6.5f, 19f)
            curveTo(5.1f, 19f, 4f, 17.9f, 4f, 16.5f); lineTo(4f, 7.5f)
            curveTo(4f, 6.1f, 5.1f, 5f, 6.5f, 5f); close()
            moveTo(9.5f, 5f); lineTo(9.5f, 19f)
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

    /** A speech bubble with a plus: start a new chat. */
    val NewChat: ImageVector by lazy {
        stroked("PhoneIcons.NewChat") {
            moveTo(12f, 4.5f)
            curveTo(16.7f, 4.5f, 20f, 7.6f, 20f, 11.5f)
            curveTo(20f, 15.4f, 16.7f, 18.5f, 12f, 18.5f)
            curveTo(10.9f, 18.5f, 9.9f, 18.3f, 9f, 18f)
            lineTo(4.8f, 19.6f)
            lineTo(5.9f, 16f)
            curveTo(4.7f, 14.8f, 4f, 13.2f, 4f, 11.5f)
            curveTo(4f, 7.6f, 7.3f, 4.5f, 12f, 4.5f)
            close()
            moveTo(12f, 8.5f); lineTo(12f, 14.5f)
            moveTo(9f, 11.5f); lineTo(15f, 11.5f)
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
