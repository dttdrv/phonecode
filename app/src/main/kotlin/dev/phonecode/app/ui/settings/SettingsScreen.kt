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
import dev.phonecode.app.ui.components.MisulIconButton
import dev.phonecode.app.ui.components.MisulDialog
import dev.phonecode.app.ui.components.MisulDialogAction
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

internal fun revisionOf(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

internal fun revisionOf(config: McpServerConfig): String = revisionOf(buildString {
    append(config.type).append('\u0000').append(config.url).append('\u0000')
    config.headers.toSortedMap().forEach { (name, value) ->
        append(name).append('\u0000').append(value).append('\u0000')
    }
    append(config.enabled).append('\u0000').append(config.timeout)
})

internal fun mcpConnectionDraftRevision(name: String, config: McpServerConfig): String =
    revisionOf(buildString {
        append(name.trim()).append('\u0000')
        append(config.type).append('\u0000').append(config.url.trim()).append('\u0000')
        config.headers.toSortedMap().forEach { (headerName, value) ->
            append(headerName).append('\u0000').append(value).append('\u0000')
        }
        append(config.timeout)
    })

internal data class TestedMcpDraft(
    val revision: String,
    val snapshot: McpServerSnapshot,
)

internal fun openExternalUrl(context: Context, url: String): String? =
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.fold(
        onSuccess = { null },
        onFailure = { "Could not open your browser. Check that a browser is installed, then try again." },
    )

internal fun customProviderDraftIsDirty(
    name: String,
    baseUrl: String,
    modelsText: String,
    anthropicFormat: Boolean,
): Boolean = name.isNotBlank() || baseUrl.isNotBlank() || modelsText.isNotBlank() || anthropicFormat

private fun ChatUiState.settingsSnapshot(): ChatUiState = copy(
    lines = emptyList(),
    streaming = "",
    streamingReasoning = "",
    isRunning = false,
    queued = emptyList(),
    pendingPermission = null,
    pendingQuestion = null,
    retry = null,
    todos = emptyList(),
    timelineEpoch = 0,
    usageInput = 0,
    usageOutput = 0,
    contextLimit = null,
    lastCompletedAt = null,
    interruptedTurn = false,
    draftPhotos = emptyMap(),
)

@Composable
internal fun collectSettingsState(vm: ChatViewModel): State<ChatUiState> {
    val flow = remember(vm) {
        vm.state.map(ChatUiState::settingsSnapshot).distinctUntilChanged()
    }
    val initialState = remember(vm) { vm.state.value.settingsSnapshot() }
    return flow.collectAsStateWithLifecycle(initialValue = initialState)
}

/** Settings: a home list + every sub-page, navigated with an iOS-style slide.
 *  [initialPage] lets callers (onboarding) deep-link straight to a sub-page. */
@Composable
fun SettingsScreen(vm: ChatViewModel, settingsVm: SettingsViewModel, onBack: () -> Unit, initialPage: String = "home") {
    val errorMessage by remember(vm) {
        vm.state.map { it.error }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    val initialRoute = remember(initialPage) { SettingsRoute.fromLegacyPage(initialPage) }

    Box(Modifier.fillMaxSize()) {
        SettingsNavigation(vm, settingsVm, onExit = onBack, startRoute = initialRoute)
        errorMessage?.let { message ->
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(Spacing.m)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .semantics {
                        error(message)
                        liveRegion = LiveRegionMode.Polite
                    }
                    .padding(start = Spacing.m, top = Spacing.xs, bottom = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                MisulIconButton(
                    Icons.Filled.Close,
                    "Dismiss error",
                    onClick = vm::clearError,
                )
            }
        }
    }
}

@Composable
internal fun SettingsInventoryLoadingPage(
    title: String,
    message: String,
    onBack: () -> Unit,
) {
    SettingsPageShell(title, onBack) {
        SettingsNote(message, announce = true)
    }
}

@Composable
internal fun ConfirmDiscardDialog(
    message: String = "This server has unsaved changes.",
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit,
) {
    MisulDialog(
        title = "Discard changes?",
        onDismissRequest = onKeepEditing,
        body = {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        actions = {
            MisulDialogAction("Keep editing", onKeepEditing)
            Spacer(Modifier.weight(1f))
            MisulDialogAction("Discard", onDiscard, destructive = true)
        },
    )
}

@Composable
internal fun ConfirmActionDialog(
    title: String,
    message: String,
    action: String,
    progressAction: String = action,
    inProgress: Boolean = false,
    inlineError: String? = null,
    secondaryAction: String? = null,
    onSecondary: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    MisulDialog(
        title = title,
        onDismissRequest = { if (!inProgress) onDismiss() },
        body = {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            inlineError?.let {
                SettingsErrorText(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = Spacing.s),
                )
            }
        },
        actions = {
            MisulDialogAction("Cancel", onDismiss, enabled = !inProgress)
            if (secondaryAction != null && onSecondary != null) {
                MisulDialogAction(secondaryAction, onSecondary, enabled = !inProgress)
            }
            Spacer(Modifier.weight(1f))
            MisulDialogAction(
                if (inProgress) progressAction else action,
                onConfirm,
                destructive = true,
                enabled = !inProgress,
            )
        },
    )
}
