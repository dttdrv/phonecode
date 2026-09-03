package dev.phonecode.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Apple-leaning rounded corners (from design/specs/design-tokens.md): larger than M3 defaults to
// approximate iOS continuous corners with circular arcs.
val PhoneShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val ShapePill = RoundedCornerShape(percent = 50)

val ShapeButton = RoundedCornerShape(18.dp)
val ShapeMediumIcon = RoundedCornerShape(13.dp)
