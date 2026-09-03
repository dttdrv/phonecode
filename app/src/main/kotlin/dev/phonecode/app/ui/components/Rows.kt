package dev.phonecode.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import dev.phonecode.app.ui.theme.LocalMisulAccent
import dev.phonecode.app.ui.theme.PhoneDurations

private val GroupCorner = 16.dp
private val RowInset = 16.dp
private val RowIconSize = 24.dp
private val RowIconGap = 12.dp
private val OneLineRowHeight = 56.dp
private val SupportingRowHeight = 64.dp

@Composable
fun MisulGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth()
            .clip(RoundedCornerShape(GroupCorner))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        content = content,
    )
}

/** A shared row shell for settings that need richer trailing controls than a standard navigation row. */
@Composable
fun MisulContentRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val rowModifier = if (onClick == null) {
        modifier
    } else {
        modifier
            .misulRowPressTreatment(interaction)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Button,
                onClick = onClick,
            )
    }
    RowShell(
        modifier = rowModifier,
        icon = null,
        dividerTag = "content",
        supportingText = null,
        showDivider = showDivider,
        content = content,
    )
}

/** Read-only information presented with row geometry but no interactive semantics. */
@Composable
fun MisulStatusRow(
    label: String,
    supportingText: String? = null,
    value: String? = null,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
) {
    RowShell(
        modifier = modifier,
        icon = null,
        dividerTag = label,
        supportingText = supportingText,
        showDivider = showDivider,
    ) {
        RowText(label = label, supportingText = supportingText, modifier = Modifier.weight(1f))
        value?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Standalone switch for a content row whose text remains independently selectable. */
@Composable
fun MisulInlineToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    contentDescription: String = "Toggle",
    modifier: Modifier = Modifier,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = misulSwitchColors(),
        modifier = modifier.semantics(mergeDescendants = true) {
            this.contentDescription = contentDescription
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        },
    )
}

@Composable
fun MisulSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 14.dp, top = 16.dp, bottom = 6.dp)
            .semantics { heading() },
    )
}

@Composable
fun MisulNavigationRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    supportingText: String? = null,
    value: String? = null,
    showDivider: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    RowShell(
        modifier = modifier
            .misulRowPressTreatment(interaction)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Button,
                onClick = onClick,
            ),
        icon = icon,
        dividerTag = label,
        supportingText = supportingText,
        showDivider = showDivider,
    ) {
        icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(RowIconSize)) }
        RowText(label = label, supportingText = supportingText, modifier = Modifier.weight(1f))
        value?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** A button row that reveals or hides content below it without posing as a saved setting. */
@Composable
fun MisulDisclosureRow(
    label: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    showDivider: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(PhoneDurations.STATE_CHANGE),
        label = "Disclosure chevron",
    )
    RowShell(
        modifier = modifier
            .misulRowPressTreatment(interaction)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
        icon = null,
        dividerTag = label,
        supportingText = supportingText,
        showDivider = showDivider,
    ) {
        RowText(label = label, supportingText = supportingText, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = chevronRotation },
        )
    }
}

@Composable
fun MisulToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
    showDivider: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    RowShell(
        modifier = modifier
            .misulRowPressTreatment(interaction)
            .toggleable(
                value = checked,
                interactionSource = interaction,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = label
                toggleableState = ToggleableState(checked)
            },
        icon = null,
        dividerTag = label,
        supportingText = supportingText,
        showDivider = showDivider,
    ) {
        RowText(label = label, supportingText = supportingText, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = misulSwitchColors(),
            modifier = Modifier
                .size(MisulMinimumInteractiveSize)
                .clearAndSetSemantics {},
        )
    }
}

@Composable
fun MisulSelectionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    showDivider: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    RowShell(
        modifier = modifier
            .misulRowPressTreatment(interaction)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics { this.selected = selected },
        icon = null,
        dividerTag = label,
        supportingText = supportingText,
        showDivider = showDivider,
    ) {
        RowText(label = label, supportingText = supportingText, modifier = Modifier.weight(1f))
        if (selected) {
            Text("Selected", style = MaterialTheme.typography.labelLarge, color = LocalMisulAccent.current)
        }
    }
}

@Composable
fun MisulFilter(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .heightIn(min = MisulMinimumInteractiveSize)
            .widthIn(min = MisulMinimumInteractiveSize)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) LocalMisulAccent.current else colors.surfaceContainerHigh)
            .misulPressMotion(interaction, pressedScale = pressedScaleFor(MisulPressTarget.TEXT))
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.onPrimary else colors.onSurface,
        )
    }
}

@Composable
private fun RowShell(
    modifier: Modifier,
    icon: ImageVector?,
    dividerTag: String,
    supportingText: String?,
    showDivider: Boolean,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = modifier.fillMaxWidth()
                .heightIn(min = if (supportingText == null) OneLineRowHeight else SupportingRowHeight)
                .padding(horizontal = RowInset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RowIconGap),
            content = content,
        )
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier
                    .padding(start = if (icon == null) RowInset else RowInset + RowIconSize + RowIconGap)
                    .testTag("misul-row-divider-$dividerTag"),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f),
            )
        }
    }
}

@Composable
private fun RowText(label: String, supportingText: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        supportingText?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Modifier.misulRowPressTreatment(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val pressColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    return drawWithCache {
        onDrawWithContent {
            drawContent()
            if (pressed) drawRect(pressColor)
        }
    }
}

@Composable
private fun misulSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = LocalMisulAccent.current,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    disabledCheckedThumbColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.72f),
    disabledCheckedTrackColor = LocalMisulAccent.current.copy(alpha = 0.52f),
)
