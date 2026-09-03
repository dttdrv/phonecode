package dev.phonecode.app.ui.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.phonecode.app.data.Project
import dev.phonecode.app.data.SessionMeta
import dev.phonecode.app.ui.components.MisulDialog
import dev.phonecode.app.ui.components.MisulDialogAction
import dev.phonecode.app.ui.components.MisulField
import dev.phonecode.app.ui.components.MisulIconButton
import dev.phonecode.app.ui.components.MorphingMenu
import dev.phonecode.app.ui.components.pressFeedback
import dev.phonecode.app.ui.theme.Spacing

/** One anchored host keeps project, chat, and move-submenu motion tied to the trigger. */
@Composable
internal fun WorkspaceDrawerMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier.width(280.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    MorphingMenu(
        expanded = expanded,
        onDismiss = onDismiss,
        above = false,
        alignEnd = true,
        anchorSize = Spacing.touchTarget,
        modifier = modifier,
        content = content,
    )
}

@Composable
private fun DrawerMenuRow(
    label: String,
    icon: ImageVector,
    destructive: Boolean = false,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val contentColor = when {
        !enabled -> colors.tertiary
        destructive -> colors.error
        else -> colors.onBackground
    }
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .pressFeedback(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, pressedScale = 0.97f)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.Button
                if (selected) this.selected = true
            }
            .heightIn(min = 48.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(icon, null, tint = if (destructive) contentColor else colors.secondary, modifier = Modifier.size(19.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = contentColor,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ChatOptionsMenu(
    meta: SessionMeta,
    projects: List<Project>,
    onDismiss: () -> Unit,
    onPin: () -> Unit,
    onRequestRename: () -> Unit,
    onMove: (String?) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    lifecycleMutationsEnabled: Boolean,
) {
    var moving by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(6.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 42.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (moving) {
                MisulIconButton(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Back",
                    onClick = { moving = false },
                    modifier = Modifier.graphicsLayer { rotationZ = 180f },
                )
            }
            Text(
                if (moving) "Move to" else meta.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (moving) {
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                item("unsorted") {
                    val current = meta.projectId == null
                    DrawerMenuRow(if (current) "Unsorted (current)" else "Unsorted", Icons.Outlined.Inbox, enabled = !current, selected = current) {
                        onMove(null); onDismiss()
                    }
                }
                items(projects, key = { "project:${it.id}" }) { project ->
                    val current = meta.projectId == project.id
                    DrawerMenuRow(if (current) "${project.name} (current)" else project.name, Icons.Outlined.Folder, enabled = !current, selected = current) {
                        onMove(project.id); onDismiss()
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                DrawerMenuRow(if (meta.pinned) "Unpin" else "Pin", Icons.Outlined.PushPin) { onPin(); onDismiss() }
                DrawerMenuRow("Rename", Icons.Outlined.Edit) { onDismiss(); onRequestRename() }
                if (lifecycleMutationsEnabled) {
                    DrawerMenuRow("Move to…", Icons.Outlined.Folder) { moving = true }
                    DrawerMenuRow(if (meta.archived) "Unarchive" else "Archive", Icons.Outlined.Archive) { onArchive(); onDismiss() }
                    DrawerMenuRow("Delete", Icons.Outlined.DeleteOutline, destructive = true) { onDelete(); onDismiss() }
                } else {
                    Text(
                        "Stop the agent to move, archive, or delete this chat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun ProjectOptionsMenu(
    project: Project,
    onDismiss: () -> Unit,
    onNewChat: () -> Unit,
    onRequestRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(6.dp)) {
        Text(
            project.name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
        )
        DrawerMenuRow("New chat", Icons.Filled.Add) { onDismiss(); onNewChat() }
        DrawerMenuRow("Rename", Icons.Outlined.Edit) { onDismiss(); onRequestRename() }
        DrawerMenuRow("Delete project", Icons.Outlined.DeleteOutline, destructive = true) { onDelete(); onDismiss() }
    }
}

@Composable
internal fun ConfirmDrawerDeleteDialog(title: String, detail: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    MisulDialog(
        title = title,
        onDismissRequest = onDismiss,
        body = { Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        actions = {
            MisulDialogAction("Cancel", onDismiss)
            Spacer(Modifier.weight(1f))
            MisulDialogAction("Delete", onConfirm, primary = true, destructive = true)
        },
    )
}

@Composable
internal fun DrawerRenameDialog(title: String, placeholder: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val trimmed = value.trim()
    val enabled = trimmed.isNotEmpty() && trimmed != initial.trim()
    MisulDialog(
        title = title,
        onDismissRequest = onDismiss,
        body = { MisulField(value, { value = it }, placeholder) },
        actions = {
            MisulDialogAction("Cancel", onDismiss)
            Spacer(Modifier.weight(1f))
            MisulDialogAction("Save", { onConfirm(trimmed) }, primary = true, enabled = enabled)
        },
    )
}
