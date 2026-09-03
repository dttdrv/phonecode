package dev.phonecode.app.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.ColorScheme
import dev.phonecode.app.ui.theme.LocalMisulAccent
import dev.phonecode.app.ui.theme.PhoneDurations
import dev.phonecode.app.ui.theme.PhoneEasings

enum class ActionRole {
    PRIMARY,
    SECONDARY,
    QUIET,
    DESTRUCTIVE,
}

enum class MisulPressMotion {
    SCALE,
    TONAL,
}

private val ActionHeight = 48.dp
private val ActionCorner = 14.dp
private val IconTarget = 48.dp
private val IconSurface = 40.dp
private val IconGlyph = 22.dp
private const val ButtonPressedScale = 0.97f
private const val IconPressedScale = 0.96f
private const val TextPressedScale = 0.99f

internal enum class MisulPressTarget {
    ACTION,
    ICON,
    TEXT,
}

internal fun pressedScaleFor(target: MisulPressTarget): Float = when (target) {
    MisulPressTarget.ACTION -> ButtonPressedScale
    MisulPressTarget.ICON -> IconPressedScale
    MisulPressTarget.TEXT -> TextPressedScale
}

internal data class ActionVisuals(val container: Color, val content: Color)

internal fun actionVisuals(
    role: ActionRole,
    enabled: Boolean,
    cobalt: Color,
    colors: ColorScheme,
): ActionVisuals {
    if (!enabled) {
        return ActionVisuals(
            container = when (role) {
                ActionRole.PRIMARY, ActionRole.SECONDARY -> colors.surfaceContainerHigh
                ActionRole.QUIET, ActionRole.DESTRUCTIVE -> Color.Transparent
            },
            content = colors.onSurfaceVariant.copy(alpha = 0.38f),
        )
    }
    return when (role) {
        ActionRole.PRIMARY -> ActionVisuals(cobalt, colors.onPrimary)
        ActionRole.SECONDARY -> ActionVisuals(colors.surfaceContainerHigh, colors.onSurface)
        ActionRole.QUIET -> ActionVisuals(Color.Transparent, colors.onSurface)
        ActionRole.DESTRUCTIVE -> ActionVisuals(Color.Transparent, colors.error)
    }
}

internal data class IconVisuals(
    val container: Color,
    val content: Color,
    val emphasized: Boolean,
)

internal fun iconVisuals(
    selected: Boolean,
    filled: Boolean,
    enabled: Boolean,
    cobalt: Color,
    colors: ColorScheme,
): IconVisuals {
    val emphasized = selected || filled
    return IconVisuals(
        container = when {
            !enabled && emphasized -> colors.surfaceContainerHigh
            emphasized -> cobalt
            else -> Color.Transparent
        },
        content = when {
            !enabled -> colors.onSurfaceVariant.copy(alpha = 0.38f)
            emphasized -> colors.onPrimary
            else -> colors.onSurface
        },
        emphasized = emphasized,
    )
}

@Composable
fun MisulActionButton(
    label: String,
    modifier: Modifier = Modifier,
    role: ActionRole = ActionRole.PRIMARY,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val accent = LocalMisulAccent.current
    val interaction = remember { MutableInteractionSource() }
    // Loading blocks repeat taps but keeps the action's role color and geometry visible.
    val visual = actionVisuals(role, enabled || loading, accent, colors)
    val shape = RoundedCornerShape(ActionCorner)
    Box(
        modifier
            .height(ActionHeight)
            .misulPressMotion(
                interaction,
                pressedScale = pressedScaleFor(MisulPressTarget.ACTION),
            )
            .clip(shape)
            .background(visual.container)
            .misulTonalFeedback(interaction, visual.content)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                enabled = enabled && !loading,
                role = Role.Button,
                onClick = onClick,
            )
            .then(if (loading) Modifier.semantics { stateDescription = "Loading" } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp).graphicsLayer { alpha = if (loading) 0f else 1f },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let { Icon(it, null, tint = visual.content, modifier = Modifier.size(18.dp)) }
            Text(label, style = MaterialTheme.typography.labelLarge, color = visual.content)
        }
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = visual.content,
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
fun MisulIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    filled: Boolean = false,
    enabled: Boolean = true,
    visualOffsetY: Dp = 0.dp,
) {
    val colors = MaterialTheme.colorScheme
    val accent = LocalMisulAccent.current
    val interaction = remember { MutableInteractionSource() }
    val visual = iconVisuals(selected, filled, enabled, accent, colors)
    Box(
        modifier
            .size(IconTarget)
            .misulPressMotion(interaction, pressedScale = pressedScaleFor(MisulPressTarget.ICON))
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true, radius = IconSurface / 2),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(IconSurface)
                .offset(y = visualOffsetY)
                .clip(CircleShape)
                .background(visual.container)
                .misulTonalFeedback(interaction, visual.content),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription, tint = visual.content, modifier = Modifier.size(IconGlyph))
        }
    }
}

@Composable
fun MisulTextAction(
    label: String,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .height(ActionHeight)
            .misulPressMotion(interaction, pressedScale = pressedScaleFor(MisulPressTarget.TEXT))
            .clip(RoundedCornerShape(ActionCorner))
            .misulTonalFeedback(interaction, colors.onSurface)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> colors.onSurfaceVariant.copy(alpha = 0.38f)
                destructive -> colors.error
                else -> colors.onSurface
            },
        )
    }
}

@Composable
fun Modifier.misulPressMotion(
    interaction: MutableInteractionSource,
    pressedScale: Float,
): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val motion = if (ValueAnimator.areAnimatorsEnabled()) {
        MisulPressMotion.SCALE
    } else {
        MisulPressMotion.TONAL
    }
    val scale by animateFloatAsState(
        targetValue = if (pressed && motion == MisulPressMotion.SCALE) pressedScale else 1f,
        animationSpec = if (pressed) {
            tween(PhoneDurations.PRESS_IN, easing = PhoneEasings.easeOut)
        } else {
            spring(dampingRatio = 1f, stiffness = 600f)
        },
        label = "misulPressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
private fun Modifier.misulTonalFeedback(
    interaction: MutableInteractionSource,
    tonalColor: Color,
): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val tonalAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.08f else 0f,
        animationSpec = tween(PhoneDurations.PRESS_IN, easing = PhoneEasings.easeOut),
        label = "misulPressTone",
    )
    return this.drawWithCache {
            onDrawWithContent {
                drawContent()
                if (tonalAlpha > 0f) drawRect(tonalColor.copy(alpha = tonalAlpha))
            }
        }
}
