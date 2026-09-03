package dev.phonecode.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.material3.ripple
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonecode.app.agent.ChatViewModel
import dev.phonecode.app.agent.ChatUiState
import dev.phonecode.app.agent.mcpDeleteOperationKey
import dev.phonecode.app.agent.providerDeleteOperationKey
import dev.phonecode.app.agent.skillDeleteOperationKey
import dev.phonecode.app.R
import dev.phonecode.app.data.CustomModel
import dev.phonecode.app.data.CustomProvider
import dev.phonecode.app.data.ManagedSkill
import dev.phonecode.app.data.isSafeMcpEndpoint
import dev.phonecode.app.data.isSafeCustomProviderId
import dev.phonecode.app.data.isSafeProviderEndpoint
import dev.phonecode.app.data.SkillScope
import dev.phonecode.app.data.SkillStatus
import dev.phonecode.app.data.ThemeMode
import dev.phonecode.app.ui.SettingsViewModel
import dev.phonecode.app.ui.chat.MarkdownBlocks
import dev.phonecode.app.ui.components.ActionRole
import dev.phonecode.app.ui.components.MisulActionButton
import dev.phonecode.app.ui.components.MisulActionRow
import dev.phonecode.app.ui.components.MisulField
import dev.phonecode.app.ui.components.MisulGroup
import dev.phonecode.app.ui.components.MisulIconButton
import dev.phonecode.app.ui.components.MisulNavigationRow
import dev.phonecode.app.ui.components.MisulTextAction
import dev.phonecode.app.ui.components.MisulToggleRow
import dev.phonecode.app.ui.components.MisulContentRow
import dev.phonecode.app.ui.components.MisulSectionLabel
import dev.phonecode.app.ui.components.MisulSearchField
import dev.phonecode.app.ui.components.MisulInlineToggle
import dev.phonecode.app.ui.components.StretchSyncedScrollChrome
import dev.phonecode.app.ui.components.contentVerticalScroll
import dev.phonecode.app.ui.components.pressFeedback
import dev.phonecode.app.ui.theme.LocalMisulAccent
import dev.phonecode.app.ui.theme.PcMono
import dev.phonecode.app.ui.theme.PhoneSprings
import dev.phonecode.app.ui.theme.Spacing
import dev.phonecode.tools.mcp.McpServerConfig
import dev.phonecode.tools.mcp.McpServerSnapshot
import dev.phonecode.tools.skills.parseSkillMarkdown
import java.text.SimpleDateFormat
import java.security.MessageDigest
import java.util.Date
import java.util.Locale

@Composable
internal fun ProvidersPage(vm: ChatViewModel, onOpenProvider: (String) -> Unit, onBack: () -> Unit) {
    val state by collectSettingsState(vm)
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var addingCustom by remember { mutableStateOf(false) }
    var browserError by remember { mutableStateOf<String?>(null) }
    SettingsPageShell("Providers", onBack) {
        MisulSectionLabel("Connections")
        MisulGroup {
            if (state.codexOAuthAvailable && !state.codexConnected) {
                MisulActionRow(
                    label = "Sign in with ChatGPT",
                    supportingText = "Use models included with your ChatGPT plan",
                    icon = Icons.Outlined.Cloud,
                ) {
                    vm.startCodexSignIn()?.let { url ->
                        browserError = openExternalUrl(context, url)
                    }
                }
            }
            MisulActionRow(
                label = "Add custom provider",
                supportingText = "Connect an OpenAI-compatible endpoint",
                icon = Icons.Filled.Add,
                enabled = state.providerConfigError == null,
                showDivider = false,
            ) {
                addingCustom = true
            }
        }
        browserError?.let { SettingsErrorText(it, modifier = Modifier.padding(top = Spacing.xs)) }
        MisulSectionLabel("Providers")
        if (vm.secureStorageUnavailable()) {
            SettingsErrorText(
                "Secure storage is unavailable on this device. Misul Agent will not save API keys or sign-in credentials.",
                modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs),
            )
        }
        state.providerConfigError?.let {
            SettingsErrorText(it)
            SettingsNote("The existing providers.json was preserved. Fix it before changing custom providers here.")
        }
        // Hoist the provider list and the key lookup out of per-recomposition work: keyFor() decrypts from
        // EncryptedSharedPreferences, so doing it per row per frame ran crypto on every toggle/recompose.
        // Keyed on state.models (changes when custom providers reload); a fresh entry after editing a key
        // on the detail page resets this remember because the page leaves this route's composition.
        val providers = remember(state.models, state.codexConnected) { vm.allProviders() }
        val keyedIds = remember(state.models) { providers.filter { vm.keyFor(it.id).isNotBlank() }.map { it.id }.toSet() }
        MisulGroup {
            providers.forEachIndexed { index, preset ->
                val runtimeAvailable = vm.providerAvailableInMisul(preset.id)
                val enabled = preset.id !in state.disabledProviders
                val connected = preset.id == "codex" && state.codexConnected
                val hasKey = connected || preset.id in keyedIds
                val setupStatus = when {
                    connected -> "Signed in with ChatGPT"
                    hasKey -> "API key saved"
                    else -> "Setup required"
                }
                val status = when {
                    !runtimeAvailable -> "$setupStatus · Not yet available"
                    !enabled -> "$setupStatus · Hidden"
                    else -> setupStatus
                }
                MisulNavigationRow(
                    label = preset.displayName,
                    supportingText = status,
                    onClick = { onOpenProvider(preset.id) },
                    showDivider = index != providers.lastIndex,
                )
            }
        }
    }
    if (addingCustom) {
        CustomProviderDialog(
            existingIds = vm.allProviders().map { it.id }.toSet(),
            onSave = vm::saveCustomProvider,
            onSaved = { id ->
                addingCustom = false
                onOpenProvider(id)
            },
            onDismiss = { addingCustom = false },
        )
    }
}

@Composable
internal fun ProviderDetailPage(vm: ChatViewModel, providerId: String, onBack: () -> Unit) {
    val state by collectSettingsState(vm)
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val preset = vm.allProviders().firstOrNull { it.id == providerId }
    val deleteOperationKey = providerDeleteOperationKey(providerId)
    val deleteOperation = state.settingsOperations[deleteOperationKey]
    var key by remember(providerId) { mutableStateOf("") }
    var hasStoredKey by remember(providerId) { mutableStateOf(vm.keyFor(providerId).isNotBlank()) }
    var pendingCodexDisconnect by rememberSaveable(providerId) { mutableStateOf(false) }
    var pendingRemoveKey by remember(providerId) { mutableStateOf(false) }
    var pendingRemoveProvider by rememberSaveable(providerId) { mutableStateOf(false) }
    var keyError by remember(providerId) { mutableStateOf<String?>(null) }
    val secureStorageAvailable = !vm.secureStorageUnavailable()
    LaunchedEffect(preset, deleteOperation, providerId) {
        if (preset == null &&
            deleteOperation?.running == false &&
            deleteOperation.error == null
        ) {
            onBack()
        }
    }
    SettingsPageShell(preset?.displayName ?: providerId, onBack) {
        val providerEnabled = providerId !in state.disabledProviders
        val providerAvailable = vm.providerAvailableInMisul(providerId)
        MisulSectionLabel("Availability")
        MisulGroup {
            MisulToggleRow(
                label = "Show in model picker",
                checked = providerEnabled,
                onCheckedChange = { vm.toggleProviderDisabled(providerId) },
                enabled = providerAvailable,
                supportingText = if (providerAvailable) null else "Not yet available",
                showDivider = false,
            )
        }
        if (vm.isCustomProvider(providerId)) {
            MisulSectionLabel("Custom provider")
            MisulGroup {
                MisulContentRow(onClick = { pendingRemoveProvider = true }, showDivider = false) {
                    Text(
                        "Remove this provider",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.error,
                    )
                }
            }
        }
        if (providerId == "codex") {
            MisulSectionLabel("Account")
            MisulGroup {
                MisulContentRow(onClick = { pendingCodexDisconnect = true }, showDivider = false) {
                    Column(Modifier.weight(1f)) {
                        Text("ChatGPT", style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
                        Text("Signed in", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    Text("Disconnect", style = MaterialTheme.typography.labelLarge, color = colors.error)
                }
            }
        } else {
            MisulSectionLabel("API key")
            MisulField(
                key,
                { key = it },
                if (hasStoredKey) "New API key" else "API key",
                secure = true,
                contentDescription = "${preset?.displayName ?: providerId} API key",
                enabled = secureStorageAvailable,
            )
            SettingsNote(
                if (hasStoredKey) {
                    "A key is saved securely. Enter a replacement and save it explicitly."
                } else {
                    "Keys are saved in Android secure storage and excluded from exports."
                },
            )
            if (!secureStorageAvailable) {
                SettingsErrorText("Secure storage is unavailable on this device, so Misul Agent cannot change this key.")
            }
            keyError?.let { SettingsErrorText(it, modifier = Modifier.padding(top = Spacing.xs)) }
            Spacer(Modifier.height(Spacing.xs))
            MisulActionButton("Save key", role = ActionRole.PRIMARY, enabled = key.isNotBlank() && secureStorageAvailable) {
                if (vm.setKey(providerId, key)) {
                    key = ""
                    hasStoredKey = true
                    keyError = null
                } else {
                    keyError = "The API key could not be saved securely."
                }
            }
            if (hasStoredKey) {
                Spacer(Modifier.height(Spacing.xs))
                MisulTextAction("Remove saved key", destructive = true, enabled = secureStorageAvailable) {
                    pendingRemoveKey = true
                }
            }
        }
        val models = state.models.filter { it.providerId == providerId }
        MisulSectionLabel("Models · ${models.size}")
        if (models.isEmpty()) {
            SettingsNote("No models loaded for this provider yet. Models refresh automatically when Misul Agent opens.")
        } else {
            // Search + bulk visibility (device feedback): long provider lists need both.
            var modelQuery by remember(providerId) { mutableStateOf("") }
            MisulSearchField(modelQuery, { modelQuery = it }, "Search models")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MisulActionButton("All on", role = ActionRole.QUIET) { vm.setAllModelsHidden(models, hidden = false) }
                MisulActionButton("All off", role = ActionRole.QUIET) { vm.setAllModelsHidden(models, hidden = true) }
            }
            Spacer(Modifier.height(8.dp))
            val shown = models.filter { modelQuery.isBlank() || it.label.contains(modelQuery, ignoreCase = true) || it.modelId.contains(modelQuery, ignoreCase = true) }
            if (shown.isEmpty()) SettingsNote("No models match \"$modelQuery\".")
            MisulGroup {
                shown.forEachIndexed { index, option ->
                    val k = "${option.providerId}/${option.modelId}"
                    val visible = k !in state.hiddenModels
                    MisulContentRow(showDivider = index != shown.lastIndex) {
                        Text(
                            option.label.substringAfterLast(" · "),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (visible) colors.onBackground else colors.tertiary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        MisulInlineToggle(visible, { vm.toggleModelHidden(option) }, "${option.label} visible")
                    }
                }
            }
        }
    }
    if (pendingCodexDisconnect) {
        ConfirmActionDialog(
            title = "Disconnect ChatGPT?",
            message = "This signs out of ChatGPT and removes the saved sign-in credentials. Existing chats stay on this device.",
            action = "Disconnect",
            onDismiss = { pendingCodexDisconnect = false },
        ) {
            vm.signOutCodex()
            pendingCodexDisconnect = false
            onBack()
        }
    }
    if (pendingRemoveKey) {
        ConfirmActionDialog(
            title = "Remove saved API key?",
            message = "${preset?.displayName ?: providerId} will stop working until you save another key.",
            action = "Remove key",
            onDismiss = { pendingRemoveKey = false },
        ) {
            if (vm.setKey(providerId, "")) {
                hasStoredKey = false
                pendingRemoveKey = false
                keyError = null
            } else {
                pendingRemoveKey = false
                keyError = "The saved API key could not be removed."
            }
        }
    }
    if (pendingRemoveProvider) {
        ConfirmActionDialog(
            title = "Remove custom provider?",
            message = "This removes the provider configuration and its saved API key. Existing chats stay on this device.",
            action = "Remove provider",
            progressAction = "Removing…",
            inProgress = deleteOperation?.running == true,
            inlineError = deleteOperation?.error,
            onDismiss = {
                vm.clearSettingsOperation(deleteOperationKey)
                pendingRemoveProvider = false
            },
        ) {
            vm.clearError()
            vm.clearSettingsOperation(deleteOperationKey)
            scope.launch {
                vm.deleteCustomProviderAndWait(providerId).fold(
                    onSuccess = {
                        pendingRemoveProvider = false
                        onBack()
                    },
                    onFailure = {},
                )
            }
        }
    }
}


@Composable
internal fun CustomProviderTextFields(
    name: String,
    onNameChange: (String) -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    modelsText: String,
    onModelsTextChange: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Text("Provider name", style = MaterialTheme.typography.labelMedium, color = colors.secondary)
    MisulField(
        name,
        onNameChange,
        "e.g. My LM Studio",
        contentDescription = "Provider name",
    )
    Spacer(Modifier.height(6.dp))
    Text("Base URL", style = MaterialTheme.typography.labelMedium, color = colors.secondary)
    MisulField(
        baseUrl,
        onBaseUrlChange,
        "e.g. https://host/v1",
        contentDescription = "Base URL",
    )
    Spacer(Modifier.height(6.dp))
    Text("Model IDs", style = MaterialTheme.typography.labelMedium, color = colors.secondary)
    MisulField(
        modelsText,
        onModelsTextChange,
        "One model id per line",
        singleLine = false,
        minLines = 2,
        contentDescription = "Model IDs",
    )
}

/**
 * Add a user-defined provider (round-3 feedback): name, base URL, wire format, and the model ids
 * to expose. Saved to providers.json - the same file the agent edits - so both arrival paths feed
 * one catalog. The API key is set afterwards on the provider's own detail page, like every preset.
 */
@Composable
private fun CustomProviderDialog(
    existingIds: Set<String>,
    onSave: suspend (String, CustomProvider) -> Result<Unit>,
    onSaved: (String) -> Unit,
    onDismiss: () -> Unit,
) = CustomProviderEditor(
    existingIds = existingIds,
    onSave = onSave,
    onSaved = onSaved,
    onDismiss = onDismiss,
    renderAsDialog = true,
)

/** The production editor surface, renderable without a platform dialog for deterministic UI evidence. */
@Composable
internal fun CustomProviderEditor(
    existingIds: Set<String>,
    onSave: suspend (String, CustomProvider) -> Result<Unit>,
    onSaved: (String) -> Unit,
    onDismiss: () -> Unit,
    renderAsDialog: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    var name by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var anthropicFormat by rememberSaveable { mutableStateOf(false) }
    var modelsText by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val id = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    val models = modelsText.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val validationError = when {
        name.isBlank() -> "Enter a provider name"
        id.isBlank() -> "Name needs at least one letter or digit"
        !isSafeCustomProviderId(id) -> "Use a shorter, unique provider name"
        id in existingIds -> "\"$id\" already exists"
        baseUrl.isBlank() -> "Enter the provider base URL"
        !isSafeProviderEndpoint(baseUrl.trim()) -> "Use HTTPS, or HTTP only for localhost"
        models.isEmpty() -> "Add at least one model id"
        else -> null
    }
    val hasInput = name.isNotBlank() || baseUrl.isNotBlank() || modelsText.isNotBlank()
    val draftDirty = customProviderDraftIsDirty(name, baseUrl, modelsText, anthropicFormat)
    val requestDismiss = {
        when {
            saving -> Unit
            draftDirty -> confirmDiscard = true
            else -> onDismiss()
        }
    }
    val maxHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height.toDp() - 32.dp)
            .coerceAtLeast(Spacing.touchTarget * 4f)
    }
    val content: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxHeight)
                .windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom))
                .shadow(24.dp, MaterialTheme.shapes.extraLarge, clip = false)
                .clip(MaterialTheme.shapes.extraLarge).background(colors.surfaceContainerHigh)
                .padding(Spacing.m),
        ) {
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false)
                    .contentVerticalScroll(rememberScrollState()),
            ) {
                Text("Add custom provider", style = MaterialTheme.typography.titleMedium, color = colors.onBackground, modifier = Modifier.padding(bottom = Spacing.s))
                CustomProviderTextFields(
                    name,
                    { name = it; error = null },
                    baseUrl,
                    { baseUrl = it; error = null },
                    modelsText,
                    { modelsText = it; error = null },
                )
                Spacer(Modifier.height(Spacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Anthropic format", style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
                        Text("Off = OpenAI-compatible (most servers)", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    MisulInlineToggle(anthropicFormat, { anthropicFormat = it }, "Use Anthropic Messages format")
                }
                (error ?: validationError?.takeIf { hasInput })?.let {
                    Spacer(Modifier.height(6.dp))
                    SettingsErrorText(it, style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(Spacing.s))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                MisulActionButton("Cancel", onClick = requestDismiss, role = ActionRole.QUIET)
                MisulActionButton(
                    "Save",
                    role = ActionRole.PRIMARY,
                    loading = saving,
                    enabled = !saving && validationError == null,
                ) {
                        if (validationError != null) return@MisulActionButton
                        saving = true
                        scope.launch {
                            onSave(id, CustomProvider(
                                name = name.trim(),
                                baseUrl = baseUrl.trim().trimEnd('/'),
                                format = if (anthropicFormat) "anthropic" else "openai",
                                models = models.associateWith { CustomModel(name = it) },
                            )).onSuccess { onSaved(id) }.onFailure { failure ->
                                error = failure.message ?: "Custom provider could not be saved"
                            }
                            saving = false
                        }
                    }
            }
        }
    }
    if (renderAsDialog) {
        Dialog(onDismissRequest = requestDismiss) { content() }
    } else {
        content()
    }
    if (confirmDiscard) {
        ConfirmDiscardDialog(
            message = "This custom provider has unsaved changes.",
            onKeepEditing = { confirmDiscard = false },
            onDiscard = {
                confirmDiscard = false
                onDismiss()
            },
        )
    }
}
