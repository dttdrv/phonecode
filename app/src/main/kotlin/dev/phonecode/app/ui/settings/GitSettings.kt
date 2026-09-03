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
import androidx.compose.animation.animateContentSize
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
import dev.phonecode.app.ui.components.MisulField
import dev.phonecode.app.ui.components.MisulGroup
import dev.phonecode.app.ui.components.MisulIconButton
import dev.phonecode.app.ui.components.MisulContentRow
import dev.phonecode.app.ui.components.MisulDisclosureRow
import dev.phonecode.app.ui.components.MisulSectionLabel
import dev.phonecode.app.ui.components.MisulTextAction
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
internal fun GitPage(
    vm: ChatViewModel,
    settingsVm: SettingsViewModel,
    onBack: () -> Unit,
    openUrl: (Context, String) -> String? = ::openExternalUrl,
) {
    val state by collectSettingsState(vm)
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var advanced by rememberSaveable { mutableStateOf(false) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var savedUsername by remember { mutableStateOf(vm.keyFor("git.username")) }
    var manualUsername by rememberSaveable { mutableStateOf(savedUsername) }
    // Do not put credentials in a SavedState Bundle. Keep the token in memory and guard Back.
    var manualToken by remember { mutableStateOf("") }
    var hasSavedToken by remember { mutableStateOf(vm.keyFor("git.token").isNotBlank()) }
    var manualError by remember { mutableStateOf<String?>(null) }
    var browserError by remember { mutableStateOf<String?>(null) }
    val secureStorageAvailable = !vm.secureStorageUnavailable()
    val manualDraftIsDirty = manualUsername != savedUsername || manualToken.isNotBlank()
    DiscardChangesBackHandler(
        dirty = manualDraftIsDirty,
        message = "These Git settings have unsaved changes.",
        onDiscard = onBack,
    ) { guardedBack ->
    SettingsPageShell("Git", guardedBack) {
        MisulSectionLabel("GitHub")
        when {
            state.githubAuthCode != null -> {
                // Device flow in progress: show the code big, open the browser, keep polling.
                // Rows now paint their own card surface, so freeform group content does too.
                MisulGroup {
                    Column(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(Spacing.m),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Enter this code on GitHub", style = MaterialTheme.typography.labelMedium, color = colors.secondary)
                        Text(
                            state.githubAuthCode.orEmpty(),
                            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = PcMono, letterSpacing = 2.sp),
                            color = colors.onBackground,
                            modifier = Modifier.padding(vertical = Spacing.s),
                        )
                        MisulActionButton("Open github.com/login/device", role = ActionRole.PRIMARY) {
                            browserError = openUrl(
                                context,
                                state.githubVerifyUri ?: "https://github.com/login/device",
                            )
                        }
                        Spacer(Modifier.height(Spacing.xs))
                        MisulActionButton("Cancel", onClick = vm::cancelGitHubSignIn, role = ActionRole.QUIET)
                    }
                }
                SettingsNote("Waiting for you to authorize on GitHub - this completes automatically.")
            }
            state.githubLogin != null -> {
                MisulGroup {
                    MisulContentRow(showDivider = false) {
                        Column(Modifier.weight(1f)) {
                            Text("@${state.githubLogin}", style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
                            Text("GitHub account connected", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                        MisulTextAction("Sign out", onClick = { confirmSignOut = true }, destructive = true)
                    }
                }
                SettingsNote("Push and pull also require a local Git repository with a valid HTTPS origin.")
            }
            else -> {
                MisulActionButton("Sign in with GitHub", onClick = vm::startGitHubSignIn, role = ActionRole.PRIMARY)
                Spacer(Modifier.height(6.dp))
            }
        }
        browserError?.let {
            SettingsErrorText(it, modifier = Modifier.padding(top = Spacing.xs))
        }
        MisulSectionLabel("Advanced")
        MisulGroup(Modifier.animateContentSize(animationSpec = PhoneSprings.standardSpec())) {
            MisulDisclosureRow(
                label = "Advanced Git settings",
                supportingText = "Task branches and manual credentials",
                expanded = advanced,
                showDivider = advanced,
                onClick = { advanced = !advanced },
            )
            if (advanced) {
                SettingsToggleRow(
                    "Auto-branch each task",
                    "Each new chat works on its own branch of the project",
                    checked = settings.gitAutoBranch,
                ) { v -> settingsVm.update { it.copy(gitAutoBranch = v) } }
                MisulContentRow(showDivider = true) {
                    Column(Modifier.weight(1f)) {
                        Text("Git username", style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
                        Spacer(Modifier.height(6.dp))
                        MisulField(
                            manualUsername,
                            { manualUsername = it; manualError = null },
                            "Account username",
                            contentDescription = "Git username",
                            enabled = secureStorageAvailable,
                        )
                    }
                }
                MisulContentRow(showDivider = false) {
                    Column(Modifier.weight(1f)) {
                        Text("Manual access token", style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
                        Spacer(Modifier.height(6.dp))
                        MisulField(
                            manualToken,
                            { manualToken = it; manualError = null },
                            if (hasSavedToken) "New PAT (leave blank to keep saved)" else "Fine-grained PAT",
                            secure = true,
                            contentDescription = "Manual Git access token",
                            enabled = secureStorageAvailable,
                        )
                    }
                }
            }
        }
        if (advanced) {
            if (!secureStorageAvailable) {
                SettingsErrorText("Secure storage is unavailable on this device, so manual Git credentials cannot be changed.")
            }
            manualError?.let { SettingsErrorText(it, modifier = Modifier.padding(top = Spacing.xs)) }
            Spacer(Modifier.height(Spacing.xs))
            MisulActionButton(
                "Save manual credentials",
                role = ActionRole.PRIMARY,
                enabled = secureStorageAvailable && manualUsername.isNotBlank() && (manualToken.isNotBlank() || hasSavedToken),
            ) {
                val updates = buildMap {
                    put("git.username", manualUsername)
                    if (manualToken.isNotBlank()) put("git.token", manualToken)
                }
                if (vm.setKeys(updates)) {
                    if (manualToken.isNotBlank()) hasSavedToken = true
                    savedUsername = manualUsername
                    manualToken = ""
                    manualError = null
                } else {
                    manualError = "Manual Git credentials could not be saved securely."
                }
            }
        }
    }
    }
    if (confirmSignOut) {
        ConfirmActionDialog(
            title = "Sign out of GitHub?",
            message = "This disconnects the GitHub account and clears manual Git credentials. Local repositories and commits stay on this device.",
            action = "Sign out",
            onDismiss = { confirmSignOut = false },
        ) {
            vm.signOutGitHub()
            confirmSignOut = false
        }
    }
}
