package dev.phonecode.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.phonecode.app.agent.ChatViewModel
import dev.phonecode.app.ui.components.ActionRole
import dev.phonecode.app.ui.components.MisulActionButton
import dev.phonecode.app.ui.components.MisulGroup
import dev.phonecode.app.ui.components.MisulIconButton
import dev.phonecode.app.ui.components.MisulSelectionRow
import dev.phonecode.app.ui.components.MisulStatusRow
import dev.phonecode.app.ui.theme.Spacing

@Composable
internal fun FilesPage(vm: ChatViewModel, onBack: () -> Unit) {
    val state by collectSettingsState(vm)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.linkSharedFolder(uri)
    }
    var pendingUnlinkId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmAutomaticApproval by rememberSaveable { mutableStateOf(false) }
    var enablingAutomaticApproval by rememberSaveable { mutableStateOf(false) }
    var automaticApprovalError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.autoAccept, state.error, enablingAutomaticApproval) {
        if (!enablingAutomaticApproval) return@LaunchedEffect
        when {
            state.autoAccept -> {
                enablingAutomaticApproval = false
                automaticApprovalError = null
                confirmAutomaticApproval = false
            }
            state.error != null -> {
                enablingAutomaticApproval = false
                automaticApprovalError = state.error
            }
        }
    }
    SettingsPageShell("Files & permissions", onBack) {
        SettingsSection("Workspace")
        MisulGroup {
            FolderStatusRow("Private project workspace", "Permanent and fully available to the agent", showDivider = false)
        }
        SettingsSection("Phone folders")
        if (state.sharedFolders.isNotEmpty()) {
            MisulGroup {
                state.sharedFolders.forEachIndexed { index, folder ->
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().padding(start = Spacing.m, end = Spacing.xs),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f).padding(start = Spacing.s)) {
                            Text(folder.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            Text(if (folder.writable) "Read & write" else "Read only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        MisulIconButton(Icons.Filled.Delete, "Remove ${folder.name}", onClick = { pendingUnlinkId = folder.id })
                    }
                    if (index != state.sharedFolders.lastIndex) androidx.compose.material3.HorizontalDivider(
                        Modifier.padding(start = Spacing.m),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f),
                    )
                }
            }
            Spacer(Modifier.height(Spacing.s))
        }
        MisulActionButton(
            label = "Link a folder",
            role = if (state.sharedFolders.isEmpty()) ActionRole.PRIMARY else ActionRole.SECONDARY,
            onClick = { picker.launch(null) },
        )
        SettingsNote("The system picker grants access only to the folder you choose. Linked access survives app restarts and can be removed here or in system settings.")
        SettingsSection("Approval policy")
        MisulGroup(Modifier.selectableGroup()) {
            MisulSelectionRow(
                label = "Ask before each change",
                selected = !state.autoAccept,
                onClick = { vm.setAutoAccept(false) },
                supportingText = "Review every action before it runs",
            )
            MisulSelectionRow(
                label = "Allow changes automatically",
                selected = state.autoAccept,
                onClick = { if (!state.autoAccept) confirmAutomaticApproval = true },
                supportingText = "Run workspace changes without approval prompts",
                showDivider = false,
            )
        }
        SettingsNote("Reading the active workspace and linked folders is always allowed. Reads outside those locations always ask. Automatic approval controls writes, commands, Git operations, and actions from enabled MCP servers that can change data.")
        state.notice?.let { notice ->
            SettingsNote(notice, announce = true)
            LaunchedEffect(notice) { kotlinx.coroutines.delay(3000); vm.clearNotice() }
        }
    }
    pendingUnlinkId?.let { folderId ->
        val folder = state.sharedFolders.firstOrNull { it.id == folderId }
        val projects = state.projects.count { it.folderId == folderId }
        ConfirmActionDialog(
            title = "Remove folder access?",
            message = if (projects == 0) "Misul Agent will lose access to ${folder?.name ?: "this folder"}. The phone folder itself is not deleted." else "Misul Agent will unlink ${folder?.name ?: "this folder"}, move $projects project${if (projects == 1) "" else "s"} and their chats to Unsorted, and keep private workspace files under Recovered projects. The phone folder itself is not deleted.",
            action = "Remove access",
            onDismiss = { pendingUnlinkId = null },
        ) { vm.unlinkSharedFolder(folderId); pendingUnlinkId = null }
    }
    if (confirmAutomaticApproval) {
        ConfirmActionDialog(
            title = "Enable automatic approval?",
            message = "Misul Agent will run writes in the private workspace and linked phone folders, commands, Git operations, and mutating MCP actions without asking each time. Reads outside linked locations will still ask.",
            action = "Enable automatic approval",
            progressAction = "Enabling…",
            inProgress = enablingAutomaticApproval,
            inlineError = automaticApprovalError,
            onDismiss = { automaticApprovalError = null; confirmAutomaticApproval = false },
        ) { vm.clearError(); automaticApprovalError = null; enablingAutomaticApproval = true; vm.setAutoAccept(true) }
    }
}

@Composable
private fun SettingsSection(label: String) = Text(
    label,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = Spacing.s, top = Spacing.m, bottom = Spacing.xs),
)

@Composable
private fun FolderStatusRow(label: String, detail: String, showDivider: Boolean) {
    MisulStatusRow(label, supportingText = detail, showDivider = showDivider)
}
