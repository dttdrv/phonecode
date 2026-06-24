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

// The composer capsule: FIXED 26dp radius - exactly half the resting (single-line) height, so at
// rest it is a mathematically perfect pill, and unlike percent-50 the corners do not warp while
// animateContentSize grows the box (device feedback: "the chat input box is not perfectly rounded").
val ShapeComposer = RoundedCornerShape(26.dp)
val ShapePhone = RoundedCornerShape(52.dp)
val ShapeSmallIcon = RoundedCornerShape(8.dp)
val ShapeMediumIcon = RoundedCornerShape(13.dp)
