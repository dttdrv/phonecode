package dev.phonecode.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.phonecode.app.ui.theme.Spacing

private val DialogShape = RoundedCornerShape(28.dp)

/** Dialog surface shared by every confirmation: the raised card color used by floating chrome. */
@Composable
internal fun dialogContainerColor() = MaterialTheme.colorScheme.let {
    if (it.background.luminance() < 0.5f) it.surfaceContainerHigh else it.surfaceContainerLowest
}

/** Tonal fill for controls placed on a raised surface (sheet, dialog, menu): one step above it. */
@Composable
internal fun raisedFillColor() = MaterialTheme.colorScheme.let {
    if (it.background.luminance() < 0.5f) it.surfaceContainerHighest else it.surfaceContainerHigh
}

/** Content drawn on a raised surface: fields and tonal fills step one above that surface. */
@Composable
fun RaisedSurfaceTheme(content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val fill = raisedFillColor()
    MaterialTheme(colorScheme = colors.copy(surfaceContainerHighest = fill), content = content)
}

@Composable
fun MisulDialog(
    title: String,
    onDismissRequest: () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = DialogShape,
        containerColor = dialogContainerColor(),
        tonalElevation = 0.dp,
        title = {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = { RaisedSurfaceTheme { Column(content = body) } },
        confirmButton = { MisulDialogActions(content = actions) },
    )
}

@Composable
fun MisulDialogActions(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Equal-width pill action for dialogs. Primary is solid ink, destructive is red text on a
 * tonal pill (never a red fill), and everything else is a quiet tonal pill.
 */
@Composable
fun RowScope.MisulDialogAction(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val container = when {
        primary && !destructive -> if (enabled) colors.onSurface else colors.surfaceContainerHighest
        else -> raisedFillColor()
    }
    val content = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.5f)
        destructive -> colors.error
        primary -> colors.inverseOnSurface
        else -> colors.onSurface
    }
    Box(
        Modifier
            .weight(1f)
            .heightIn(min = Spacing.touchTarget)
            .misulPressMotion(interaction, pressedScale = pressedScaleFor(MisulPressTarget.TEXT))
            .clip(RoundedCornerShape(50))
            .background(container)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                if (!enabled) disabled()
                if (primary) stateDescription = "Primary action"
                if (destructive) stateDescription = "Destructive action"
            }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = content)
    }
}
