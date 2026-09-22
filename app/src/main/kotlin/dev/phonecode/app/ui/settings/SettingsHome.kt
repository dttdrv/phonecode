package dev.phonecode.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonecode.app.agent.ChatViewModel
import dev.phonecode.app.data.SkillStatus
import dev.phonecode.app.data.ThemeMode
import dev.phonecode.app.data.ResponseStyle
import dev.phonecode.app.ui.SettingsViewModel
import dev.phonecode.app.ui.components.MisulField
import dev.phonecode.app.ui.components.MisulGroup
import dev.phonecode.app.ui.components.MisulIconButton
import dev.phonecode.app.ui.components.MisulSelectionRow
import dev.phonecode.app.ui.components.MisulSectionLabel
import dev.phonecode.app.ui.components.MisulToggleRow
import dev.phonecode.app.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            SettingsNavigationRow("Plugins", state.mcpServers.size.toString(), Icons.Outlined.Extension) { onOpen(SettingsRoute.Plugins) }
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
    MisulSectionLabel(label)
    MisulGroup(content = { content() })
}

@Composable
internal fun AppearancePage(settingsVm: SettingsViewModel, onBack: () -> Unit) {
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    SettingsPageShell("Appearance", onBack) {
        MisulSectionLabel("Color theme")
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
    onOpenProfile: () -> Unit,
    onOpenCustomInstructions: () -> Unit,
) {
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    SettingsPageShell("Personalization", onBack) {
        MisulSectionLabel("In conversations")
        MisulGroup {
            SettingsToggleRow(
                label = "Use personalization",
                sub = "Profile, response style, and custom instructions",
                checked = settings.usePersonalization,
                showDivider = false,
            ) { value -> settingsVm.update { it.copy(usePersonalization = value) } }
        }
        SettingsNote(
            if (settings.usePersonalization) {
                "Saved on this phone. Included in new requests to your selected model provider."
            } else {
                "Your choices stay on this phone and are left out of model requests."
            },
        )

        MisulSectionLabel("About you")
        MisulGroup {
            SettingsNavigationRow(
                label = "Your profile",
                value = settings.preferredName.ifBlank { "Not set" },
                supportingText = "Preferred name, work, and background",
                onClick = onOpenProfile,
            )
            SettingsNavigationRow(
                label = "Custom instructions",
                value = if (settings.customInstructions.isBlank()) "Not set" else "Configured",
                showDivider = false,
                onClick = onOpenCustomInstructions,
            )
        }
        MisulSectionLabel("Response style")
        MisulGroup(Modifier.selectableGroup()) {
            ResponseStyle.entries.forEachIndexed { index, style ->
                SettingsSelectionRow(
                    label = style.label,
                    selected = settings.responseStyle == style,
                    sub = style.description,
                    showDivider = index != ResponseStyle.entries.lastIndex,
                    onClick = { settingsVm.update { it.copy(responseStyleName = style.name) } },
                )
            }
        }
        SettingsNote("Style changes how replies are written. It does not change the agent's tools or permissions.")

        MisulSectionLabel("Message input")
        MisulGroup {
            MisulToggleRow(
                label = "Send on Enter",
                checked = settings.sendOnEnter,
                onCheckedChange = { value -> settingsVm.update { it.copy(sendOnEnter = value) } },
                supportingText = "When off, Enter adds a new line",
                showDivider = false,
            )
        }
    }
}

@Composable
internal fun ProfilePage(
    settingsVm: SettingsViewModel,
    onBack: () -> Unit,
    onDirtyChange: (Boolean) -> Unit,
    onSaved: () -> Unit,
) {
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    var preferredName by rememberSaveable(settings.preferredName) { mutableStateOf(settings.preferredName) }
    var occupation by rememberSaveable(settings.occupation) { mutableStateOf(settings.occupation) }
    var aboutYou by rememberSaveable(settings.aboutYou) { mutableStateOf(settings.aboutYou) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val changed = preferredName != settings.preferredName || occupation != settings.occupation || aboutYou != settings.aboutYou
    LaunchedEffect(changed) { onDirtyChange(changed) }

    SettingsPageShell(
        title = "Your profile",
        onBack = onBack,
        action = {
            MisulIconButton(
                icon = Icons.Filled.Check,
                contentDescription = "Save profile",
                enabled = changed && !saving,
                onClick = {
                    saving = true
                    saveError = null
                    scope.launch {
                        val result = settingsVm.updateAndWait {
                            it.copy(
                                preferredName = preferredName.trim(),
                                occupation = occupation.trim(),
                                aboutYou = aboutYou.trim(),
                            )
                        }
                        withContext(Dispatchers.Main.immediate) {
                            saving = false
                            result.fold(
                                onSuccess = { onSaved() },
                                onFailure = { saveError = "Could not save profile: ${it.message ?: "try again"}" },
                            )
                        }
                    }
                },
            )
        },
    ) {
        if (saving) SettingsNote("Saving profile…", announce = true)
        saveError?.let { SettingsErrorText(it) }
        SettingsNote("Saved on this phone. Used in future model requests when personalization is on.")
        MisulField(
            value = preferredName,
            onValueChange = { preferredName = it.take(80) },
            label = "Preferred name",
            placeholder = "What should the agent call you?",
        )
        Spacer(Modifier.height(Spacing.m))
        MisulField(
            value = occupation,
            onValueChange = { occupation = it.take(120) },
            label = "Occupation",
            placeholder = "What do you do?",
        )
        Spacer(Modifier.height(Spacing.m))
        MisulField(
            value = aboutYou,
            onValueChange = { aboutYou = it.take(1_500) },
            label = "More about you",
            placeholder = "Background, interests, or preferences",
            singleLine = false,
            minLines = 4,
        )
        SettingsNote("Leave out passwords, tokens, and other secrets.")
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
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val changed = draft != settings.customInstructions
    androidx.compose.runtime.LaunchedEffect(changed) { onDirtyChange(changed) }
    SettingsPageShell(
        title = "Custom instructions",
        onBack = onBack,
        action = {
            MisulIconButton(
                icon = Icons.Filled.Check,
                contentDescription = "Save custom instructions",
                enabled = changed && !saving,
                onClick = {
                    saving = true
                    saveError = null
                    scope.launch {
                        val result = settingsVm.updateAndWait { it.copy(customInstructions = draft) }
                        withContext(Dispatchers.Main.immediate) {
                            saving = false
                            result.fold(
                                onSuccess = { onSaved() },
                                onFailure = { saveError = "Could not save instructions: ${it.message ?: "try again"}" },
                            )
                        }
                    }
                },
            )
        },
    ) {
        if (saving) SettingsNote("Saving instructions…", announce = true)
        saveError?.let { SettingsErrorText(it) }
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
