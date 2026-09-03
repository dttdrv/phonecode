package dev.phonecode.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonecode.app.agent.ChatViewModel
import dev.phonecode.app.data.SkillStatus
import dev.phonecode.app.data.ThemeMode
import dev.phonecode.app.ui.SettingsViewModel
import dev.phonecode.app.ui.components.ActionRole
import dev.phonecode.app.ui.components.MisulActionButton
import dev.phonecode.app.ui.components.MisulField
import dev.phonecode.app.ui.components.MisulGroup
import dev.phonecode.app.ui.components.MisulIconButton
import dev.phonecode.app.ui.components.MisulSelectionRow
import dev.phonecode.app.ui.components.MisulToggleRow
import dev.phonecode.app.ui.theme.Spacing

@Composable
internal fun HomePage(
    vm: ChatViewModel,
    settingsVm: SettingsViewModel,
    onBack: () -> Unit,
    onOpen: (SettingsRoute) -> Unit,
) {
    val state by collectSettingsState(vm)
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    SettingsPageShell("Settings", onBack) {
        SettingsRootGroup("Agent") {
            SettingsNavigationRow("Personalization", icon = Icons.Outlined.Person) { onOpen(SettingsRoute.Personalization) }
            SettingsNavigationRow("Models & providers", icon = Icons.Outlined.Cloud, showDivider = false) { onOpen(SettingsRoute.Providers) }
        }
        SettingsRootGroup("Capabilities") {
            SettingsNavigationRow("Agent tools", vm.availableTools().size.toString(), Icons.Outlined.Build) { onOpen(SettingsRoute.AgentTools) }
            SettingsNavigationRow("MCP servers", state.mcpServers.size.toString(), Icons.Outlined.Extension) { onOpen(SettingsRoute.Mcp) }
            SettingsNavigationRow("Skills", state.skills.count { it.status == SkillStatus.ACTIVE }.toString(), Icons.Outlined.AutoAwesome, showDivider = false) { onOpen(SettingsRoute.Skills) }
        }
        SettingsRootGroup("Workspace") {
            SettingsNavigationRow(
                "Files & permissions",
                if (state.sharedFolders.isEmpty()) "Private" else "${state.sharedFolders.size} linked",
                Icons.Outlined.Folder,
            ) { onOpen(SettingsRoute.Files) }
            SettingsNavigationRow("Git", icon = Icons.Outlined.AccountTree, showDivider = false) { onOpen(SettingsRoute.Git) }
        }
        SettingsRootGroup("App") {
            SettingsNavigationRow("Appearance", settings.mode.name.lowercase().replaceFirstChar { it.uppercase() }, Icons.Outlined.Palette) { onOpen(SettingsRoute.Appearance) }
            SettingsNavigationRow("Export & import", icon = Icons.Outlined.SwapVert) { onOpen(SettingsRoute.Data) }
            SettingsNavigationRow("About", icon = Icons.Outlined.Info, showDivider = false) { onOpen(SettingsRoute.About) }
        }
    }
}

@Composable
private fun SettingsRootGroup(label: String, content: @Composable () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = androidx.compose.ui.Modifier.padding(start = Spacing.s, top = Spacing.m, bottom = Spacing.xs),
    )
    MisulGroup(content = { content() })
}

@Composable
internal fun AppearancePage(settingsVm: SettingsViewModel, onBack: () -> Unit) {
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    SettingsPageShell("Appearance", onBack) {
        Text("Color theme", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = androidx.compose.ui.Modifier.padding(Spacing.s))
        MisulGroup(androidx.compose.ui.Modifier.selectableGroup()) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                MisulSelectionRow(
                    label = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                    selected = settings.mode == mode,
                    onClick = { settingsVm.update { it.copy(themeMode = mode.name) } },
                    supportingText = when (mode) {
                        ThemeMode.SYSTEM -> "Match your phone's appearance"
                        ThemeMode.LIGHT -> "Always use the light theme"
                        ThemeMode.DARK -> "Always use the dark theme"
                    },
                    showDivider = index != ThemeMode.entries.lastIndex,
                )
            }
        }
    }
}

@Composable
internal fun PersonalPage(
    settingsVm: SettingsViewModel,
    onBack: () -> Unit,
    onOpenCustomInstructions: () -> Unit,
) {
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    SettingsPageShell("Personalization", onBack) {
        Text("Message input", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = androidx.compose.ui.Modifier.padding(Spacing.s))
        MisulGroup {
            MisulToggleRow(
                label = "Send on Enter",
                checked = settings.sendOnEnter,
                onCheckedChange = { value -> settingsVm.update { it.copy(sendOnEnter = value) } },
                supportingText = "When off, Enter adds a new line",
                showDivider = false,
            )
        }
        Text("Custom instructions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = androidx.compose.ui.Modifier.padding(start = Spacing.s, top = Spacing.m, bottom = Spacing.xs))
        MisulGroup {
            SettingsNavigationRow(
                label = "Custom instructions",
                value = if (settings.customInstructions.isBlank()) "Not set" else "Configured",
                showDivider = false,
                onClick = onOpenCustomInstructions,
            )
        }
    }
}

@Composable
internal fun CustomInstructionsPage(
    settingsVm: SettingsViewModel,
    onBack: () -> Unit,
    onDirtyChange: (Boolean) -> Unit,
    onSaved: () -> Unit,
) {
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    var draft by androidx.compose.runtime.saveable.rememberSaveable(settings.customInstructions) {
        mutableStateOf(settings.customInstructions)
    }
    val changed = draft != settings.customInstructions
    androidx.compose.runtime.LaunchedEffect(changed) { onDirtyChange(changed) }
    SettingsPageShell(
        title = "Custom instructions",
        onBack = onBack,
        action = {
            MisulIconButton(
                icon = Icons.Filled.Check,
                contentDescription = "Save custom instructions",
                enabled = changed,
                onClick = {
                    settingsVm.update { it.copy(customInstructions = draft) }
                    onSaved()
                },
            )
        },
    ) {
        MisulField(
            value = draft,
            onValueChange = { draft = it },
            label = "Instructions",
            placeholder = "How should Misul work with you?",
            singleLine = false,
            minLines = 8,
        )
        SettingsNote("Applied to new turns. Do not include passwords, tokens, or other secrets.")
    }
}
