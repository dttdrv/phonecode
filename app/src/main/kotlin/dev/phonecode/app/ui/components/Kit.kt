package dev.phonecode.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.phonecode.app.ui.theme.ShapePill
import dev.phonecode.app.ui.theme.ShapeMediumIcon
import dev.phonecode.app.ui.theme.Spacing
import androidx.compose.material3.Icon

// PhoneCode's monochrome component kit (Apple-feel: spring press feedback, hairline borders,
// generous radii). Everything reads MaterialTheme.colorScheme so light/dark both work.

/**
 * Press feedback: the scale lands INSTANTLY on touch-down (snap) and springs back on release with
 * a small physical pop (apple-motion-exact.md §2). Visual touch confirmation is Android's own
 * RIPPLE (restored - suppressing it was the single biggest "web app" tell); the spring keeps the
 * fluid hand-feel on top of the platform's language.
 */
@Composable
fun Modifier.pressFeedback(
    interaction: MutableInteractionSource,
    pressedScale: Float? = null,
    pressedAlpha: Float = 1f,
): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (pressed) pressedAlpha else 1f,
        animationSpec = if (pressed) snap() else spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium),
        label = "pressAlpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) (pressedScale ?: 1f) else 1f,
        animationSpec = if (pressed) snap() else spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "pressScale",
    )
    return this.graphicsLayer {
        this.alpha = alpha
        if (pressedScale != null) {
            scaleX = scale
            scaleY = scale
        }
    }
}

/** 40dp icon button - ripple + a 0.90 scale pop, instant down / spring back. */
@Composable
fun PcIconButton(icon: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onBackground, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(Spacing.inputHeight)
            .pressFeedback(interaction, pressedScale = 0.90f)
            .clip(ShapeMediumIcon)
            .clickable(interactionSource = interaction, indication = ripple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(22.dp)) }
}

/** 40dp circular filled button (composer wrench/send) - round-control press: scale 0.86. */
@Composable
fun PcRoundButton(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val bg = if (filled) colors.primary else colors.surfaceContainerHigh
    val fg = when {
        !enabled -> colors.tertiary
        filled -> colors.onPrimary
        else -> colors.onBackground
    }
    Box(
        modifier.size(Spacing.inputHeight)
            .pressFeedback(interaction, pressedScale = 0.86f)
            .clip(ShapePill).background(if (enabled) bg else colors.surfaceContainerHigh)
            .clickable(interactionSource = interaction, indication = ripple(), enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, tint = fg, modifier = Modifier.size(20.dp)) }
}

/** Platform switch - Material's own component IS the native feel; the theme keeps it monochrome. */
@Composable
fun PcToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(checked = checked, onCheckedChange = onChange)
}

/**
 * M3 Expressive connected-card group (round-3: "make it look like the new Material3 Expressive
 * menus", the new Grok settings language). Each row paints its OWN surface with 4dp corners; the
 * group clips the whole stack to 16dp, so outer corners of the first/last row render at 16dp while
 * every gap-facing corner stays 4dp - the connected-card geometry without any position tracking.
 * 2dp transparent gaps (page background showing through) replace the old hairline dividers.
 */
@Composable
fun PcGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

/**
 * One segment of a connected-card group: self-backgrounded (4dp inner corners - the group clip
 * rounds the outer ones), M3 list metrics (56dp min height, 16dp side padding), platform ripple.
 */
@Composable
fun PcRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val base = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(colors.surface)
        .heightIn(min = 56.dp)
    // List rows ripple like every native Android list row.
    val clickableBase = if (onClick != null) base.clickable(onClick = onClick) else base
    Row(
        clickableBase.then(modifier).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        content = content,
    )
}

/** Sentence-case section header OUTSIDE the cards (M3 Expressive dropped the all-caps eyebrow). */
@Composable
fun PcSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** Filled text field (mockup .fi): hairline border, no Material underline. */
@Composable
fun PcField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    password: Boolean = false,
    minLines: Int = 1,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(colors.surfaceContainerHighest)
            .padding(horizontal = 13.dp, vertical = 11.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodySmall, color = colors.tertiary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onBackground),
            cursorBrush = SolidColor(colors.primary),
            singleLine = singleLine,
            minLines = minLines,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            // Password keyboard type keeps secrets out of IME learning/suggestions.
            keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Full-width button: filled (primary) or hairline (alt) - iOS large-control press (0.97 + dim). */
@Composable
fun PcButton(text: String, modifier: Modifier = Modifier, filled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier.fillMaxWidth().pressFeedback(interaction, pressedScale = 0.97f).clip(MaterialTheme.shapes.small)
            .background(if (filled) colors.primary else colors.surfaceContainerHigh)
            .clickable(interactionSource = interaction, indication = ripple(), onClick = onClick)
            .heightIn(min = Spacing.touchTarget).padding(horizontal = Spacing.m),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (filled) colors.onPrimary else colors.onBackground, modifier = Modifier.size(18.dp))
            Box(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (filled) colors.onPrimary else colors.onBackground)
    }
}

/** Hairline divider. */
@Composable
fun PcDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
}

/** Context-usage ring (Claude-Code style). [fraction] 0..1 of the window used. */
@Composable
fun ContextRing(fraction: Float, modifier: Modifier = Modifier, stroke: Float = 3.5f, color: Color = MaterialTheme.colorScheme.onBackground) {
    val track = MaterialTheme.colorScheme.outlineVariant
    androidx.compose.foundation.Canvas(modifier) {
        val inset = stroke.dp.toPx() / 2
        val arcSize = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(track, 0f, 360f, false, topLeft, arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, topLeft, arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}
