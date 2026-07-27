package dev.phonecode.app.ui.components

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.phonecode.app.ui.theme.ShapePill
import dev.phonecode.app.ui.theme.ShapeButton
import dev.phonecode.app.ui.theme.Spacing
import dev.phonecode.app.ui.theme.PhoneSprings
import androidx.compose.material3.Icon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

@Stable
class PredictiveBackMotion internal constructor(
    internal val progress: State<Float>,
    internal val swipeEdge: State<Int>,
    internal val activeState: State<Boolean>,
) {
    /** Changes only when a predictive gesture starts or finishes, not for every progress frame. */
    val active: Boolean get() = activeState.value
}

@Composable
fun rememberPredictiveBackMotion(
    enabled: Boolean = true,
    onBack: suspend () -> Unit,
): PredictiveBackMotion {
    val progress = remember { mutableFloatStateOf(0f) }
    val swipeEdge = remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    val active = remember { mutableStateOf(false) }
    val currentOnBack by rememberUpdatedState(onBack)
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    PredictiveBackHandler(enabled = enabled && !imeVisible) { events ->
        try {
            events.collect { event ->
                active.value = true
                progress.floatValue = event.progress
                swipeEdge.intValue = event.swipeEdge
            }
            currentOnBack()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                animate(progress.floatValue, 0f, animationSpec = PhoneSprings.quick) { value, _ ->
                    progress.floatValue = value
                }
            }
            throw cancelled
        } finally {
            progress.floatValue = 0f
            active.value = false
        }
    }
    return remember { PredictiveBackMotion(progress, swipeEdge, active) }
}

fun Modifier.predictiveBackTransform(motion: PredictiveBackMotion): Modifier = graphicsLayer {
    val fraction = motion.progress.value.coerceIn(0f, 1f)
    val direction = if (motion.swipeEdge.value == BackEventCompat.EDGE_RIGHT) -1f else 1f
    val scale = 1f - 0.04f * fraction
    translationX = size.width * 0.1f * fraction * direction
    scaleX = scale
    scaleY = scale
    shadowElevation = 8.dp.toPx() * fraction
    transformOrigin = TransformOrigin.Center
    shape = RoundedCornerShape(24.dp)
    clip = fraction > 0f
}

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
        animationSpec = if (pressed) snap() else spring(dampingRatio = 1f, stiffness = 600f),
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

@Composable
fun PcIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onBackground,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(Spacing.touchTarget)
            .pressFeedback(interaction, pressedScale = 0.96f)
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .clip(ShapePill)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(22.dp)) }
}

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
        modifier.size(Spacing.touchTarget)
            .pressFeedback(interaction, pressedScale = 0.95f)
            .clip(ShapePill).background(if (enabled) bg else colors.surfaceContainerHigh)
            .clickable(interactionSource = interaction, indication = ripple(), enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, tint = fg, modifier = Modifier.size(20.dp)) }
}

/** Platform switch - Material's own component IS the native feel; the theme keeps it monochrome. */
@Composable
fun PcToggle(
    checked: Boolean,
    onChange: ((Boolean) -> Unit)?,
    contentDescription: String = "Toggle",
    modifier: Modifier = Modifier,
) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        modifier = modifier.semantics { this.contentDescription = contentDescription },
    )
}

@Composable
fun PcGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
        verticalArrangement = Arrangement.spacedBy(1.dp),
        content = content,
    )
}

@Composable
fun PcRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val base = Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)).background(colors.surface)
        .heightIn(min = 52.dp)
    val clickableBase = if (onClick != null) {
        base.pressFeedback(interaction, pressedScale = 0.99f)
            .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button, onClick = onClick)
    } else {
        base
    }
    Row(
        clickableBase.then(modifier).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        content = content,
    )
}

@Composable
fun PcSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 14.dp, top = 16.dp, bottom = 6.dp)
            .semantics { heading() },
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
    contentDescription: String = placeholder,
    label: String? = null,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    var passwordVisible by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget)
            .clip(MaterialTheme.shapes.small).background(colors.surfaceContainerHighest)
            .padding(start = 13.dp, end = if (password) 2.dp else 13.dp, top = 7.dp, bottom = 7.dp),
    ) {
        label?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (label == null) 34.dp else 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty() && label == null) {
                    Text(placeholder, style = MaterialTheme.typography.bodySmall, color = colors.tertiary)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onBackground),
                    cursorBrush = SolidColor(colors.primary),
                    singleLine = singleLine,
                    minLines = minLines,
                    visualTransformation = if (password && !passwordVisible) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    // Password keyboard type keeps secrets out of IME learning/suggestions.
                    keyboardOptions = if (password) {
                        KeyboardOptions(keyboardType = KeyboardType.Password)
                    } else {
                        KeyboardOptions.Default
                    },
                    modifier = Modifier.fillMaxWidth().semantics {
                        this.contentDescription = contentDescription
                    },
                )
            }
            if (password) {
                PcIconButton(
                    icon = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (passwordVisible) {
                        "Hide $contentDescription"
                    } else {
                        "Show $contentDescription"
                    },
                    tint = colors.onSurfaceVariant,
                ) {
                    passwordVisible = !passwordVisible
                }
            }
        }
    }
}

/** Full-width button: filled (primary) or hairline (alt) - iOS large-control press (0.97 + dim). */
@Composable
fun PcButton(
    text: String,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val background = when {
        !enabled -> colors.surfaceContainerHigh
        destructive -> colors.errorContainer
        filled -> colors.primary
        else -> colors.surfaceContainerHigh
    }
    val foreground = when {
        !enabled -> colors.tertiary
        destructive -> colors.onErrorContainer
        filled -> colors.onPrimary
        else -> colors.onBackground
    }
    Row(
        modifier.fillMaxWidth().pressFeedback(interaction, pressedScale = 0.97f).clip(ShapeButton)
            .background(background)
            .clickable(interactionSource = interaction, indication = ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = Spacing.touchTarget).padding(horizontal = Spacing.m),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = foreground, modifier = Modifier.size(18.dp))
            Box(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = foreground)
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
