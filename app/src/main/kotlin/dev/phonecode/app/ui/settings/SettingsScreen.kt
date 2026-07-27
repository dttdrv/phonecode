package dev.phonecode.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonecode.app.agent.ChatViewModel
import dev.phonecode.app.agent.ChatUiState
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
import dev.phonecode.app.ui.components.PcButton
import dev.phonecode.app.ui.components.PcField
import dev.phonecode.app.ui.components.PcGroup
import dev.phonecode.app.ui.components.PcIconButton
import dev.phonecode.app.ui.components.PcRow
import dev.phonecode.app.ui.components.PcSectionLabel
import dev.phonecode.app.ui.components.PcToggle
import dev.phonecode.app.ui.components.contentVerticalScroll
import dev.phonecode.app.ui.components.predictiveBackTransform
import dev.phonecode.app.ui.components.pressFeedback
import dev.phonecode.app.ui.components.rememberPredictiveBackMotion
import dev.phonecode.app.ui.theme.PcMono
import dev.phonecode.app.ui.theme.PhoneEasings
import dev.phonecode.app.ui.theme.PhoneSprings
import dev.phonecode.app.ui.theme.Spacing
import dev.phonecode.agent.AgentMode
import dev.phonecode.tools.mcp.McpServerConfig
import dev.phonecode.tools.mcp.McpServerSnapshot
import java.text.SimpleDateFormat
import java.security.MessageDigest
import java.util.Date
import java.util.Locale

/** The page each settings route pops back to - doc pages return to About, provider details to
 *  Providers. The old "always home" pop sent the back GESTURE somewhere different from the back
 *  BUTTON on nested pages (round-4: "sometimes it brings back to settings... sometimes the About"). */
private fun parentOf(page: String): String = when {
    page.startsWith("doc:") -> "about"
    page.startsWith("provider:") -> "providers"
    else -> "home"
}

/** Nav depth per route - the slide direction keys off depth, so popping doc → about animates as a
 *  POP even though neither side is "home" (the old home-test played nested pops backwards). */
private fun depthOf(page: String): Int = when {
    page == "home" -> 0
    page.startsWith("doc:") || page.startsWith("provider:") -> 2
    else -> 1
}

private fun revisionOf(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private fun revisionOf(config: McpServerConfig): String = revisionOf(buildString {
    append(config.type).append('\u0000').append(config.url).append('\u0000')
    config.headers.toSortedMap().forEach { (name, value) ->
        append(name).append('\u0000').append(value).append('\u0000')
    }
    append(config.enabled).append('\u0000').append(config.timeout)
})

private fun ChatUiState.settingsSnapshot(): ChatUiState = copy(
    lines = emptyList(),
    streaming = "",
    streamingReasoning = "",
    isRunning = false,
    sessionLoading = false,
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
private fun collectSettingsState(vm: ChatViewModel): State<ChatUiState> {
    val flow = remember(vm) {
        vm.state.map(ChatUiState::settingsSnapshot).distinctUntilChanged()
    }
    return flow.collectAsStateWithLifecycle(initialValue = ChatUiState().settingsSnapshot())
}

/** Settings: a home list + every sub-page, navigated with an iOS-style slide.
 *  [initialPage] lets callers (onboarding) deep-link straight to a sub-page. */
@Composable
fun SettingsScreen(vm: ChatViewModel, settingsVm: SettingsViewModel, onBack: () -> Unit, initialPage: String = "home") {
    val errorMessage by remember(vm) {
        vm.state.map { it.error }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    var page by rememberSaveable(initialPage) { mutableStateOf(initialPage) }
    var predictiveCommit by remember { mutableStateOf(false) }
    var nestedBackActive by remember { mutableStateOf(false) }
    val navigateBack = {
        if (page == initialPage) onBack() else page = parentOf(page)
    }

    val backMotion = rememberPredictiveBackMotion(enabled = page != initialPage && !nestedBackActive) {
        predictiveCommit = true
        navigateBack()
    }
    LaunchedEffect(page) {
        predictiveCommit = false
        nestedBackActive = false
    }

    Box(Modifier.fillMaxSize()) {
        if (backMotion.active) {
            Box(Modifier.fillMaxSize()) {
                SettingsPageContent(parentOf(page), vm, settingsVm, onBack = {}, navigate = {}, onNestedBackActive = {})
            }
        }
        Box(Modifier.fillMaxSize().predictiveBackTransform(backMotion)) {
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    if (predictiveCommit) {
                        androidx.compose.animation.EnterTransition.None togetherWith
                            androidx.compose.animation.ExitTransition.None
                    } else {
                        val pop = depthOf(targetState) < depthOf(initialState)
                        (if (pop) {
                            (slideInHorizontally(tween(260, easing = PhoneEasings.easeInOut)) { -it / 4 }) togetherWith
                                slideOutHorizontally(tween(220, easing = PhoneEasings.easeInOut)) { it }
                        } else {
                            (slideInHorizontally(tween(260, easing = PhoneEasings.easeInOut)) { it }) togetherWith
                                slideOutHorizontally(tween(220, easing = PhoneEasings.easeInOut)) { -it / 4 }
                        }).apply { targetContentZIndex = if (pop) -1f else 1f }
                    }
                },
                label = "settings",
            ) { p ->
                Box(Modifier.fillMaxSize()) {
                    SettingsPageContent(p, vm, settingsVm, navigateBack, { page = it }) { nestedBackActive = it }
                }
            }
        }
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
                PcIconButton(
                    Icons.Filled.Close,
                    "Dismiss error",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    onClick = vm::clearError,
                )
            }
        }
    }
}

@Composable
private fun SettingsPageContent(
    page: String,
    vm: ChatViewModel,
    settingsVm: SettingsViewModel,
    onBack: () -> Unit,
    navigate: (String) -> Unit,
    onNestedBackActive: (Boolean) -> Unit,
) {
    when (page) {
        "general" -> GeneralPage(settingsVm, onBack)
        "appearance" -> AppearancePage(settingsVm, onBack)
        "personal" -> PersonalPage(settingsVm, onBack)
        "providers" -> ProvidersPage(vm, onOpenProvider = { navigate("provider:$it") }, onBack = onBack)
        "mcp" -> McpPage(vm, onBack, onNestedBackActive)
        "skills" -> SkillsPage(vm, onBack, onNestedBackActive)
        "tools" -> AgentToolsPage(vm, onBack)
        "files" -> FilesPage(vm, onBack)
        "git" -> GitPage(vm, settingsVm, onBack)
        "export" -> ExportPage(vm, settingsVm, onBack)
        "about" -> AboutPage(vm, onOpenDoc = navigate, onBack = onBack)
        "doc:terms" -> DocPage("Terms of Service", "terms.md", onBack)
        "doc:privacy" -> DocPage("Privacy Policy", "privacy.md", onBack)
        "doc:licenses" -> DocPage("Open-source notices", "licenses.md", onBack)
        else -> {
            if (page.startsWith("provider:")) {
                ProviderDetailPage(vm, page.removePrefix("provider:"), onBack)
            } else {
                HomePage(vm, settingsVm, onBack = onBack, onOpen = navigate)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Scaffolding
// ---------------------------------------------------------------------------------------------

@Composable
private fun Page(
    title: String,
    onBack: () -> Unit,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()
    val scrolled by remember { derivedStateOf { scrollState.value > 0 } }
    Box(Modifier.fillMaxSize().background(colors.background).statusBarsPadding()) {
        Column(
            Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight()
                .contentVerticalScroll(scrollState)
                .background(colors.background)
                .padding(start = Spacing.m, end = Spacing.m, top = Spacing.navBarHeight + 4.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        ) {
            content()
            Spacer(Modifier.height(Spacing.xxl))
        }
        Row(
            Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxWidth().height(Spacing.navBarHeight)
                .shadow(if (scrolled) 2.dp else 0.dp, RectangleShape, clip = false)
                .background(colors.background)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PcIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = onBack)
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = colors.onBackground,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (action == null) Spacer(Modifier.width(Spacing.touchTarget)) else Box(Modifier.width(Spacing.touchTarget)) { action() }
        }
    }
}

// M3 Expressive list metrics (round-3 settings redesign): bodyLarge headline + bodyMedium
// supporting text, 24dp leading icon in onSurfaceVariant, trailing chevron - no hairlines,
// the connected-card gaps do the separating.

@Composable
private fun NavRow(label: String, value: String? = null, icon: ImageVector? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    PcRow(onClick = onClick) {
        if (icon != null) Icon(icon, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.tertiary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ToggleRow(label: String, sub: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    PcRow(
        modifier = Modifier.semantics(mergeDescendants = true) {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        },
        onClick = { onChange(!checked) },
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp))
        }
        PcToggle(
            checked,
            onChange = null,
            contentDescription = label,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun CheckRow(label: String, selected: Boolean, sub: String? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    PcRow(
        modifier = Modifier.semantics {
            this.selected = selected
            role = Role.RadioButton
        },
        onClick = onClick,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        if (selected) Icon(Icons.Filled.Check, null, tint = colors.onBackground, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Note(text: String, announce: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().padding(top = Spacing.xs).clip(MaterialTheme.shapes.medium)
            .background(colors.surface)
            .then(if (announce) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
            .padding(Spacing.m),
    ) { Text(text, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
}

@Composable
private fun ErrorText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
) {
    Text(
        text,
        style = style,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.semantics {
            error(text)
            liveRegion = LiveRegionMode.Polite
        },
    )
}

// ---------------------------------------------------------------------------------------------
// Pages
// ---------------------------------------------------------------------------------------------

@Composable
private fun HomePage(vm: ChatViewModel, settingsVm: SettingsViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val state by collectSettingsState(vm)
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    Page("Settings", onBack) {
        // The first group carries no label - an unlabeled lead group is the platform convention
        // (Grok's settings open straight into the Customize cards).
        PcGroup {
            NavRow("General", icon = Icons.Outlined.Tune) { onOpen("general") }
            NavRow("Appearance", value = settings.mode.name.lowercase().replaceFirstChar { it.uppercase() }, icon = Icons.Outlined.Palette) { onOpen("appearance") }
            NavRow("Personalization", icon = Icons.Outlined.Person) { onOpen("personal") }
        }
        PcSectionLabel("Models")
        PcGroup {
            NavRow("Providers", icon = Icons.Outlined.Cloud) { onOpen("providers") }
        }
        PcSectionLabel("Tools")
        PcGroup {
            NavRow("Agent tools", value = vm.availableTools().size.toString(), icon = Icons.Outlined.Build) { onOpen("tools") }
            NavRow("MCP servers", value = "${state.mcpServers.size}", icon = Icons.Outlined.Extension) { onOpen("mcp") }
            NavRow(
                "Skills",
                value = state.skills.count { it.status == SkillStatus.ACTIVE }.toString(),
                icon = Icons.Outlined.AutoAwesome,
            ) { onOpen("skills") }
        }
        // "GIT > Git" was the same duplication as the old GENERAL group - the workspace label
        // says what the section governs (per-project repos), the row keeps the familiar name.
        PcSectionLabel("Workspace")
        PcGroup {
            NavRow(
                "Files & permissions",
                value = if (state.sharedFolders.isEmpty()) "Private" else "${state.sharedFolders.size} linked",
                icon = Icons.Outlined.Folder,
            ) { onOpen("files") }
            NavRow("Git", icon = Icons.Outlined.AccountTree) { onOpen("git") }
        }
        PcSectionLabel("Data")
        PcGroup {
            NavRow("Export & import", icon = Icons.Outlined.SwapVert) { onOpen("export") }
        }
        Spacer(Modifier.height(8.dp))
        PcGroup {
            NavRow("About", icon = Icons.Outlined.Info) { onOpen("about") }
        }
    }
}

@Composable
private fun AgentToolsPage(vm: ChatViewModel, onBack: () -> Unit) {
    val state by collectSettingsState(vm)
    var query by rememberSaveable { mutableStateOf("") }
    val inventory = remember(state.mcpToolCount, state.skills.size) { vm.availableTools() }
    val tools = remember(inventory, query) {
        inventory.filter {
            query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true) || it.source.contains(query, true)
        }
    }
    val colors = MaterialTheme.colorScheme
    Page("Agent tools", onBack) {
        Note("${inventory.size} available · read-only tools work in Plan mode · mutating actions follow your approval setting")
        PcField(query, { query = it }, "Search tools", contentDescription = "Search tools")
        if (tools.isEmpty()) {
            Note("No tools match “${query.trim()}”. Try a shorter name or description.")
        } else {
            tools.groupBy { it.source }.forEach { (source, entries) ->
                PcSectionLabel(source)
                PcGroup {
                    entries.forEach { tool ->
                        PcRow {
                            Column(Modifier.weight(1f)) {
                                Text(tool.name, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = PcMono), color = colors.onBackground)
                                Text(tool.description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text(tool.access, style = MaterialTheme.typography.labelSmall, color = colors.tertiary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneralPage(settingsVm: SettingsViewModel, onBack: () -> Unit) {
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    Page("General", onBack) {
        PcSectionLabel("Default agent mode")
        PcGroup {
            AgentMode.entries.forEach { mode ->
                CheckRow(
                    mode.name.lowercase().replaceFirstChar { it.uppercase() },
                    selected = settings.defaultMode == mode.name,
                    sub = if (mode == AgentMode.BUILD) {
                        "Can use tools and make approved changes"
                    } else {
                        "Explores and proposes a plan without changing files"
                    },
                ) {
                    // Default governs NEW conversations (applied in ChatViewModel.newChat / init); changing
                    // it must not retroactively flip the active chat's mode - that's the per-chat Plan toggle.
                    settingsVm.update { it.copy(defaultMode = mode.name) }
                }
            }
        }
        Note("This applies to new chats. You can switch the active chat from the model menu.")
        PcSectionLabel("Message input")
        PcGroup {
            ToggleRow(
                "Send on Enter",
                "When off, Enter adds a new line",
                checked = settings.sendOnEnter,
            ) { v -> settingsVm.update { it.copy(sendOnEnter = v) } }
        }
    }
}

@Composable
private fun FilesPage(vm: ChatViewModel, onBack: () -> Unit) {
    val state by collectSettingsState(vm)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.linkSharedFolder(uri)
    }
    var pendingUnlinkId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmAutomaticApproval by rememberSaveable { mutableStateOf(false) }
    Page("Files & permissions", onBack) {
        PcSectionLabel("Workspace")
        PcGroup {
            PcRow {
                Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text("Private project workspace", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
                    Text("Permanent and fully available to the agent", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(20.dp))
            }
        }
        PcSectionLabel("Phone folders")
        if (state.sharedFolders.isNotEmpty()) {
            PcGroup {
                state.sharedFolders.forEach { folder ->
                    PcRow {
                        Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text(folder.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
                            Text(if (folder.writable) "Read & write" else "Read only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        PcIconButton(Icons.Filled.Delete, "Remove ${folder.name}") { pendingUnlinkId = folder.id }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        PcButton("Link a folder", filled = false) { picker.launch(null) }
        Note("The system picker grants access only to the folder you choose. Linked access survives app restarts and can be removed here or in system settings.")
        PcSectionLabel("Approval policy")
        PcGroup {
            CheckRow(
                "Ask before each change",
                selected = !state.autoAccept,
                sub = "Review every action before it runs",
            ) {
                vm.setAutoAccept(false)
            }
            CheckRow(
                "Allow changes automatically",
                selected = state.autoAccept,
                sub = "Run workspace changes without approval prompts",
            ) {
                if (!state.autoAccept) confirmAutomaticApproval = true
            }
        }
        Note("Reading the active workspace and linked folders is always allowed. Reads outside those locations always ask. Automatic approval controls writes, commands, Git operations, and actions from enabled MCP servers that can change data.")
        state.notice?.let {
            Spacer(Modifier.height(10.dp))
            Note(it, announce = true)
            LaunchedEffect(it) { delay(3000); vm.clearNotice() }
        }
    }
    pendingUnlinkId?.let { folderId ->
        val folder = state.sharedFolders.firstOrNull { it.id == folderId }
        val projects = state.projects.count { it.folderId == folderId }
        ConfirmActionDialog(
            title = "Remove folder access?",
            message = if (projects == 0) {
                "PhoneCode will lose access to ${folder?.name ?: "this folder"}. The phone folder itself is not deleted."
            } else {
                "PhoneCode will unlink ${folder?.name ?: "this folder"}, move $projects project${if (projects == 1) "" else "s"} and their chats to Unsorted, and keep private workspace files under Recovered projects. The phone folder itself is not deleted."
            },
            action = "Remove access",
            onDismiss = { pendingUnlinkId = null },
        ) {
            vm.unlinkSharedFolder(folderId)
            pendingUnlinkId = null
        }
    }
    if (confirmAutomaticApproval) {
        ConfirmActionDialog(
            title = "Enable automatic approval?",
            message = "PhoneCode will run writes in the private workspace and linked phone folders, commands, Git operations, and mutating MCP actions without asking each time. Reads outside linked locations will still ask.",
            action = "Enable automatic approval",
            onDismiss = { confirmAutomaticApproval = false },
        ) {
            vm.setAutoAccept(true)
            confirmAutomaticApproval = false
        }
    }
}

@Composable
private fun AppearancePage(settingsVm: SettingsViewModel, onBack: () -> Unit) {
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    Page("Appearance", onBack) {
        PcSectionLabel("Color theme")
        PcGroup {
            ThemeMode.entries.forEach { mode ->
                CheckRow(
                    mode.name.lowercase().replaceFirstChar { it.uppercase() },
                    selected = settings.mode == mode,
                    sub = when (mode) {
                        ThemeMode.SYSTEM -> "Match your phone's appearance"
                        ThemeMode.LIGHT -> "Always use the light theme"
                        ThemeMode.DARK -> "Always use the dark theme"
                    },
                ) { settingsVm.update { it.copy(themeMode = mode.name) } }
            }
        }
    }
}

@Composable
private fun PersonalPage(settingsVm: SettingsViewModel, onBack: () -> Unit) {
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    var text by remember(settings.customInstructions) { mutableStateOf(settings.customInstructions) }
    // Persist on a debounce instead of per keystroke (the old onValueChange rewrote the whole settings
    // file on every character). The debounce coalesces typing; the onDispose flush below covers leaving
    // the page within the debounce window so no edit is lost.
    LaunchedEffect(text) {
        if (text != settings.customInstructions) {
            delay(400)
            settingsVm.update { it.copy(customInstructions = text) }
        }
    }
    val latest by rememberUpdatedState(text)
    DisposableEffect(Unit) {
        onDispose { settingsVm.update { if (latest != it.customInstructions) it.copy(customInstructions = latest) else it } }
    }
    Page("Personalization", onBack) {
        PcSectionLabel("Custom instructions")
        PcField(
            text,
            onValueChange = { text = it },
            placeholder = "Tell the agent how you like to work - style, tools, conventions...",
            singleLine = false,
            minLines = 5,
        )
        Note("These instructions are included in new agent turns. Do not add passwords, tokens, or other secrets.")
    }
}

@Composable
private fun ProvidersPage(vm: ChatViewModel, onOpenProvider: (String) -> Unit, onBack: () -> Unit) {
    val state by collectSettingsState(vm)
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var addingCustom by remember { mutableStateOf(false) }
    Page("Providers", onBack) {
        if (state.codexOAuthAvailable && !state.codexConnected) {
            PcSectionLabel("ChatGPT")
            PcButton("Sign in with ChatGPT (Codex)") {
                vm.startCodexSignIn()?.let { url ->
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        PcSectionLabel("Providers")
        if (vm.secureStorageUnavailable()) {
            ErrorText(
                "Secure storage is unavailable on this device. PhoneCode will not save API keys or sign-in credentials.",
                modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs),
            )
        }
        state.providerConfigError?.let {
            ErrorText(it)
            Note("The existing providers.json was preserved. Fix it before changing custom providers here.")
        }
        // Hoist the provider list and the key lookup out of per-recomposition work: keyFor() decrypts from
        // EncryptedSharedPreferences, so doing it per row per frame ran crypto on every toggle/recompose.
        // Keyed on state.models (changes when custom providers reload); a fresh entry after editing a key
        // on the detail page resets this remember because the page leaves the AnimatedContent composition.
        val providers = remember(state.models, state.codexConnected) {
            vm.allProviders().filter { it.id != "codex" || state.codexConnected }
        }
        val keyedIds = remember(state.models) { providers.filter { vm.keyFor(it.id).isNotBlank() }.map { it.id }.toSet() }
        PcGroup {
            providers.forEach { preset ->
                val enabled = preset.id !in state.disabledProviders
                val connected = preset.id == "codex" && state.codexConnected
                val hasKey = connected || preset.id in keyedIds
                PcRow(onClick = { onOpenProvider(preset.id) }) {
                    Column(Modifier.weight(1f)) {
                        Text(preset.displayName, style = MaterialTheme.typography.bodyLarge, color = if (enabled) colors.onBackground else colors.tertiary)
                        Text(
                            if (connected) "Signed in with ChatGPT" else if (hasKey) "Key set" else "No key",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (hasKey) colors.onSurfaceVariant else colors.tertiary,
                        )
                    }
                    PcToggle(enabled, { vm.toggleProviderDisabled(preset.id) }, "${preset.displayName} enabled")
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.tertiary, modifier = Modifier.size(20.dp))
                }
            }
        }
        // Custom providers (round-3: "Add the option to add a custom provider") - any
        // OpenAI-compatible or Anthropic-style endpoint, stored in providers.json (the same
        // file the agent can edit), so both paths land in one catalog.
        Spacer(Modifier.height(Spacing.s))
        PcButton("Add custom provider", filled = false, icon = Icons.Filled.Add, enabled = state.providerConfigError == null) { addingCustom = true }
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
private fun ProviderDetailPage(vm: ChatViewModel, providerId: String, onBack: () -> Unit) {
    val state by collectSettingsState(vm)
    val colors = MaterialTheme.colorScheme
    val preset = vm.allProviders().firstOrNull { it.id == providerId }
    var key by remember(providerId) { mutableStateOf("") }
    var hasStoredKey by remember(providerId) { mutableStateOf(vm.keyFor(providerId).isNotBlank()) }
    var pendingCodexDisconnect by rememberSaveable(providerId) { mutableStateOf(false) }
    var pendingRemoveKey by remember(providerId) { mutableStateOf(false) }
    var pendingRemoveProvider by remember(providerId) { mutableStateOf(false) }
    var keyError by remember(providerId) { mutableStateOf<String?>(null) }
    val secureStorageAvailable = !vm.secureStorageUnavailable()
    Page(preset?.displayName ?: providerId, onBack) {
        if (providerId == "codex") {
            PcSectionLabel("Account")
            PcGroup {
                PcRow(onClick = { pendingCodexDisconnect = true }) {
                    Column(Modifier.weight(1f)) {
                        Text("ChatGPT", style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
                        Text("Signed in", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    Text("Disconnect", style = MaterialTheme.typography.labelLarge, color = colors.error)
                }
            }
        } else {
            PcSectionLabel("API key")
            PcField(
                key,
                { key = it },
                if (hasStoredKey) "New API key" else "API key",
                password = true,
                contentDescription = "${preset?.displayName ?: providerId} API key",
                enabled = secureStorageAvailable,
            )
            Note(
                if (hasStoredKey) {
                    "A key is saved securely. Enter a replacement and save it explicitly."
                } else {
                    "Keys are saved in Android secure storage and excluded from exports."
                },
            )
            if (!secureStorageAvailable) {
                ErrorText("Secure storage is unavailable on this device, so PhoneCode cannot change this key.")
            }
            keyError?.let { ErrorText(it, modifier = Modifier.padding(top = Spacing.xs)) }
            Spacer(Modifier.height(Spacing.xs))
            PcButton("Save key", enabled = key.isNotBlank() && secureStorageAvailable) {
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
                PcButton("Remove saved key", filled = false, destructive = true, enabled = secureStorageAvailable) {
                    pendingRemoveKey = true
                }
            }
        }
        val models = state.models.filter { it.providerId == providerId }
        PcSectionLabel("Models · ${models.size}")
        if (models.isEmpty()) {
            Note("No models loaded for this provider yet. Models refresh automatically when PhoneCode opens.")
        } else {
            // Search + bulk visibility (device feedback): long provider lists need both.
            var modelQuery by remember(providerId) { mutableStateOf("") }
            PcField(modelQuery, { modelQuery = it }, "Search models")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PcButton("All on", filled = false, modifier = Modifier.weight(1f)) { vm.setAllModelsHidden(models, hidden = false) }
                PcButton("All off", filled = false, modifier = Modifier.weight(1f)) { vm.setAllModelsHidden(models, hidden = true) }
            }
            Spacer(Modifier.height(8.dp))
            val shown = models.filter { modelQuery.isBlank() || it.label.contains(modelQuery, ignoreCase = true) || it.modelId.contains(modelQuery, ignoreCase = true) }
            if (shown.isEmpty()) Note("No models match \"$modelQuery\".")
            PcGroup {
                shown.forEach { option ->
                    val k = "${option.providerId}/${option.modelId}"
                    val visible = k !in state.hiddenModels
                    PcRow {
                        Text(
                            option.label.substringAfterLast(" · "),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (visible) colors.onBackground else colors.tertiary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        PcToggle(visible, { vm.toggleModelHidden(option) }, "${option.label} visible")
                    }
                }
            }
        }
        if (vm.isCustomProvider(providerId)) {
            PcSectionLabel("Custom provider")
            PcGroup {
                PcRow(onClick = { pendingRemoveProvider = true }) {
                    Text(
                        "Remove this provider",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.error,
                    )
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
            onDismiss = { pendingRemoveProvider = false },
        ) {
            vm.deleteCustomProvider(providerId)
            pendingRemoveProvider = false
            onBack()
        }
    }
}

@Composable
private fun McpPage(vm: ChatViewModel, onBack: () -> Unit, onNestedBackActive: (Boolean) -> Unit) {
    val state by collectSettingsState(vm)
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var editorDirty by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val closeEditor = {
        if (editorDirty) confirmDiscard = true else editing = null
    }
    val detailBackMotion = rememberPredictiveBackMotion(enabled = editing != null && !confirmDiscard) { closeEditor() }
    DisposableEffect(editing) {
        onNestedBackActive(editing != null)
        onDispose { onNestedBackActive(false) }
    }
    val visible = remember(state.mcpServers, query) {
        state.mcpServers.filter { (name, server) ->
            query.isBlank() || name.contains(query, true) || server.url.contains(query, true)
        }
    }
    Box(Modifier.fillMaxSize()) {
    Box(
        Modifier.fillMaxSize().then(
            if (editing != null) Modifier.clearAndSetSemantics {} else Modifier,
        ),
    ) {
    Page("MCP servers", onBack) {
        val connected = state.mcpSnapshots.count { it.value.connected }
        Note("$connected connected · ${state.mcpServers.count { it.value.enabled }} enabled · ${state.mcpToolCount} tools")
        state.mcpConfigError?.let {
            ErrorText(it)
            Note("The existing opencode.json has been preserved. Fix it before changing MCP servers here.")
        }
        if (state.mcpServers.isNotEmpty()) PcField(query, { query = it }, "Search servers")
        PcSectionLabel("Servers")
        if (state.mcpServers.isEmpty()) {
            Note("No MCP servers configured. Add one over HTTPS, or use local HTTP for a server on this device.")
        } else if (visible.isEmpty()) {
            Note("No servers match “${query.trim()}”.")
        } else {
            PcGroup {
                visible.entries.forEach { (name, server) ->
                    val snapshot = state.mcpSnapshots[name]
                    val status = when {
                        !server.enabled -> "Off"
                        name in state.mcpConnecting -> "Connecting"
                        snapshot?.connected == true -> "Connected · ${snapshot.tools.size} tools"
                        snapshot?.error?.isNotBlank() == true -> "Failed · ${snapshot.error}"
                        else -> "Not tested"
                    }
                    PcRow(onClick = if (state.mcpConfigError == null) ({ editorDirty = false; editing = name }) else null) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
                            Text(
                                status,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (snapshot?.connected == true) colors.primary else colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (state.mcpConfigError == null) PcToggle(server.enabled, { vm.setMcpEnabled(name, it) }, "$name enabled")
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.tertiary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        if (state.mcpConfigError == null) {
            Spacer(Modifier.height(Spacing.s))
            PcButton("Add server", filled = false, icon = Icons.Filled.Add) { editorDirty = false; editing = "" }
            if (state.mcpServers.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.xs))
                PcButton("Reconnect enabled servers", filled = false) { vm.reconnectMcp() }
            }
        }
    }
    }
    editing?.let { initialName ->
        Box(Modifier.fillMaxSize().predictiveBackTransform(detailBackMotion).background(colors.background)) {
            McpServerPage(
                vm = vm,
                initialName = initialName,
                initial = state.mcpServers[initialName] ?: McpServerConfig(),
                existingNames = state.mcpServers.keys,
                snapshot = state.mcpSnapshots[initialName],
                onBack = closeEditor,
                onDirtyChange = { editorDirty = it },
                onSaved = { editorDirty = false; editing = null },
            )
        }
    }
    if (confirmDiscard) {
        ConfirmDiscardDialog(
            onKeepEditing = { confirmDiscard = false },
            onDiscard = { confirmDiscard = false; editorDirty = false; editing = null },
        )
    }
    }
}

@Composable
private fun McpServerPage(
    vm: ChatViewModel,
    initialName: String,
    initial: McpServerConfig,
    existingNames: Set<String>,
    snapshot: McpServerSnapshot?,
    onBack: () -> Unit,
    onDirtyChange: (Boolean) -> Unit,
    onSaved: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val isNew = initialName.isEmpty()
    val scope = rememberCoroutineScope()
    var baseline by remember(initialName) { mutableStateOf(initial) }
    val currentRevision = remember(initial) { revisionOf(initial) }
    var acceptedRevision by rememberSaveable(initialName) { mutableStateOf(currentRevision) }
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var url by rememberSaveable(initialName) { mutableStateOf(initial.url) }
    var headers by rememberSaveable(initialName) { mutableStateOf(initial.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }) }
    var timeout by rememberSaveable(initialName) { mutableStateOf(initial.timeout.toString()) }
    var enabled by rememberSaveable(initialName) { mutableStateOf(initial.enabled) }
    var error by remember(initialName) { mutableStateOf<String?>(null) }
    var testing by remember(initialName) { mutableStateOf(false) }
    var saving by remember(initialName) { mutableStateOf(false) }
    var testResult by remember(initialName) { mutableStateOf<McpServerSnapshot?>(null) }
    var confirmDelete by remember(initialName) { mutableStateOf(false) }
    val externalChange = !isNew && currentRevision != acceptedRevision

    fun validationMessage(): String? {
        val finalName = if (isNew) name.trim() else initialName
        val finalTimeout = timeout.toLongOrNull()
        val invalidHeader = headers.lineSequence().filter { it.isNotBlank() }.firstOrNull { line ->
            val separator = line.indexOf(':')
            separator <= 0 || line.substring(0, separator).isBlank() || line.substring(separator + 1).isBlank()
        }
        return when {
            finalName.isBlank() -> "Name is required"
            !isSafeMcpEndpoint(url.trim()) -> "Use HTTPS, or HTTP only for localhost"
            isNew && finalName in existingNames -> "A server named $finalName already exists"
            invalidHeader != null -> "Each header must use Name: Value"
            finalTimeout == null || finalTimeout !in 1_000L..60_000L -> "Timeout must be between 1000 and 60000 ms"
            else -> null
        }
    }

    fun draft(): Pair<String, McpServerConfig>? {
        val finalName = if (isNew) name.trim() else initialName
        val finalTimeout = timeout.toLongOrNull()
        error = validationMessage()
        return if (error == null) {
            finalName to McpServerConfig("remote", url.trim(), parseHeaders(headers), enabled, finalTimeout!!)
        } else null
    }

    val initialHeaders = baseline.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    val changed = if (isNew) {
        name.isNotBlank() || url.isNotBlank() || headers.isNotBlank() || timeout != baseline.timeout.toString() || enabled != baseline.enabled
    } else {
        url != baseline.url || headers != initialHeaders || timeout != baseline.timeout.toString() || enabled != baseline.enabled
    }
    // Keep the actions available while a draft is invalid so tapping one can reveal the exact
    // field error. Disabled buttons with no explanation are a dead end, especially for TalkBack.
    val canAttemptSubmit = !testing && !saving && !externalChange
    LaunchedEffect(changed) { onDirtyChange(changed) }
    val shownSnapshot = testResult ?: snapshot.takeUnless { changed }
    Page(if (isNew) "Add MCP server" else initialName, onBack) {
        if (externalChange) {
            ErrorText("This server changed elsewhere. Reload before saving.")
            Spacer(Modifier.height(Spacing.xs))
            PcButton("Reload latest", filled = false) {
                baseline = initial
                url = initial.url
                headers = initial.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                timeout = initial.timeout.toString()
                enabled = initial.enabled
                acceptedRevision = currentRevision
                error = null
                testResult = null
            }
            Spacer(Modifier.height(Spacing.s))
        }
        when {
            testing -> Note("Testing this configuration…")
            shownSnapshot?.connected == true -> Note("Connected to ${shownSnapshot.serverTitle.ifBlank { shownSnapshot.serverName }.ifBlank { name }}")
            shownSnapshot?.error?.isNotBlank() == true -> ErrorText(shownSnapshot.error)
            !isNew && !changed -> Note(if (enabled) "Not tested" else "Off")
        }
        PcSectionLabel("Connection")
        if (isNew) {
            McpFieldLabel("Server name")
            PcField(name, { name = it; error = null; testResult = null }, "context7", contentDescription = "Server name")
            error?.takeIf { it == "Name is required" || it.startsWith("A server named ") }?.let {
                ErrorText(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Spacing.xs))
            }
        }
        McpFieldLabel("Remote URL")
        PcField(url, { url = it; error = null; testResult = null }, "https://host/mcp", contentDescription = "Remote URL")
        error?.takeIf { it.startsWith("Use HTTPS") }?.let {
            ErrorText(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Spacing.xs))
        }
        McpFieldLabel("HTTP headers")
        PcField(
            headers,
            { headers = it; error = null; testResult = null },
            "Authorization: Bearer …",
            singleLine = false,
            minLines = 2,
            contentDescription = "HTTP headers",
        )
        error?.takeIf { it.startsWith("Each header") }?.let {
            ErrorText(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Spacing.xs))
        }
        Note("One Name: Value header per line. Values are encrypted with Android Keystore.")
        McpFieldLabel("Connection timeout")
        PcField(
            timeout,
            { timeout = it.filter(Char::isDigit); error = null; testResult = null },
            "5000 milliseconds",
            contentDescription = "Connection timeout in milliseconds",
        )
        error?.takeIf { it.startsWith("Timeout") }?.let {
            ErrorText(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Spacing.xs))
        }
        Spacer(Modifier.height(Spacing.xs))
        ToggleRow("Enabled", checked = enabled) { enabled = it; error = null; testResult = null }
        error?.takeUnless { message ->
            message == "Name is required" || message.startsWith("A server named ") ||
                message.startsWith("Use HTTPS") || message.startsWith("Each header") ||
                message.startsWith("Timeout")
        }?.let { message ->
            ErrorText(
                message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        Spacer(Modifier.height(Spacing.s))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Box(Modifier.weight(1f)) {
                PcButton(if (testing) "Testing…" else "Test", filled = false, enabled = canAttemptSubmit) {
                    if (!testing) draft()?.let { (draftName, server) ->
                        scope.launch {
                            testing = true
                            testResult = vm.testMcpServer(draftName, server)
                            testing = false
                        }
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                PcButton(if (saving) "Saving…" else "Save", enabled = canAttemptSubmit) {
                    draft()?.let { (draftName, server) ->
                        scope.launch {
                            saving = true
                            vm.saveMcpServerAndWait(draftName, server, baseline.takeUnless { isNew }).fold(
                                onSuccess = { onSaved() },
                                onFailure = { error = it.message ?: "MCP configuration could not be saved" },
                            )
                            saving = false
                        }
                    }
                }
            }
        }
        shownSnapshot?.takeIf { it.connected }?.let { connectedSnapshot ->
            PcSectionLabel("Server")
            PcGroup {
                McpValueRow("Name", connectedSnapshot.serverTitle.ifBlank { connectedSnapshot.serverName }.ifBlank { name })
                McpValueRow("Version", connectedSnapshot.serverVersion.ifBlank { "Unknown" })
                McpValueRow("Protocol", connectedSnapshot.protocolVersion)
                McpValueRow("Capabilities", connectedSnapshot.capabilities.sorted().joinToString().ifBlank { "None" })
            }
            if (connectedSnapshot.instructions.isNotBlank()) {
                PcSectionLabel("Instructions")
                Note(connectedSnapshot.instructions)
            }
            PcSectionLabel("Tools")
            if (connectedSnapshot.tools.isEmpty()) Note("This server exposes no tools.") else {
                PcGroup {
                    connectedSnapshot.tools.take(30).forEach { tool ->
                        PcRow {
                            Column {
                                Text(tool.title.ifBlank { tool.name }, style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
                                if (tool.description.isNotBlank()) Text(tool.description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (connectedSnapshot.tools.size > 30) Note("${connectedSnapshot.tools.size - 30} more tools")
            }
        }
        if (!isNew) {
            Spacer(Modifier.height(Spacing.l))
            PcButton("Delete server", filled = false, destructive = true) { confirmDelete = true }
        }
    }
    if (confirmDelete) {
        ConfirmActionDialog(
            title = "Delete MCP server?",
            message = "This removes $initialName and its encrypted headers. Tools from this server will no longer be available.",
            action = "Delete server",
            onDismiss = { confirmDelete = false },
        ) {
            vm.deleteMcpServer(initialName)
            confirmDelete = false
            onSaved()
        }
    }
}

@Composable
private fun ConfirmDiscardDialog(
    message: String = "This server has unsaved changes.",
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit,
) {
    val maxHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height.toDp() - 32.dp)
            .coerceAtLeast(Spacing.touchTarget * 3f)
    }
    Dialog(onDismissRequest = onKeepEditing) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxHeight)
                .windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom))
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(Spacing.m),
        ) {
            Column(Modifier.weight(1f, fill = false).contentVerticalScroll(rememberScrollState())) {
                Text("Discard changes?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.m),
                )
            }
            PcButton("Keep editing", onClick = onKeepEditing)
            Spacer(Modifier.height(Spacing.xs))
            PcButton("Discard", filled = false, destructive = true, onClick = onDiscard)
        }
    }
}

@Composable
private fun ConfirmActionDialog(
    title: String,
    message: String,
    action: String,
    secondaryAction: String? = null,
    onSecondary: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val maxHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height.toDp() - 32.dp)
            .coerceAtLeast(Spacing.touchTarget * 3f)
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxHeight)
                .windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom))
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(Spacing.m),
        ) {
            Column(Modifier.weight(1f, fill = false).contentVerticalScroll(rememberScrollState())) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.m),
                )
            }
            PcButton("Cancel", onClick = onDismiss)
            if (secondaryAction != null && onSecondary != null) {
                Spacer(Modifier.height(Spacing.xs))
                PcButton(secondaryAction, filled = false, onClick = onSecondary)
            }
            Spacer(Modifier.height(Spacing.xs))
            PcButton(action, filled = false, destructive = true, onClick = onConfirm)
        }
    }
}

@Composable
private fun McpFieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun McpValueRow(label: String, value: String) {
    val colors = MaterialTheme.colorScheme
    PcRow {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onBackground,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

private enum class SkillFilter { ALL, ACTIVE, OFF, ISSUES }

@Composable
private fun SkillsPage(vm: ChatViewModel, onBack: () -> Unit, onNestedBackActive: (Boolean) -> Unit) {
    val state by collectSettingsState(vm)
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(SkillFilter.ALL) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorDirty by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val selectedSkill = state.skills.firstOrNull { it.id == selectedId }
    val editorSkill = state.skills.firstOrNull { it.id == editingId }
    val nested = selectedSkill != null || editingId != null
    val closeNested = {
        if (editingId != null) {
            if (editorDirty) confirmDiscard = true else editingId = null
        } else {
            selectedId = null
        }
    }
    val detailBackMotion = rememberPredictiveBackMotion(enabled = nested && !confirmDiscard) { closeNested() }
    DisposableEffect(nested) {
        onNestedBackActive(nested)
        onDispose { onNestedBackActive(false) }
    }
    val filtered = remember(state.skills, query, filter) {
        state.skills.filter { skill ->
            val matchesQuery = query.isBlank() || skill.name.contains(query, true) ||
                skill.manifest?.description?.contains(query, true) == true || skill.issue?.contains(query, true) == true
            val matchesFilter = when (filter) {
                SkillFilter.ALL -> true
                SkillFilter.ACTIVE -> skill.status == SkillStatus.ACTIVE
                SkillFilter.OFF -> skill.status == SkillStatus.DISABLED || skill.status == SkillStatus.SHADOWED
                SkillFilter.ISSUES -> skill.status == SkillStatus.INVALID
            }
            matchesQuery && matchesFilter
        }
    }
    Box(Modifier.fillMaxSize()) {
    Box(
        Modifier.fillMaxSize().then(
            if (nested) Modifier.clearAndSetSemantics {} else Modifier,
        ),
    ) {
    Page(
        "Skills",
        onBack,
        action = {
            PcIconButton(Icons.Filled.Add, "New skill") {
                editorDirty = false
                editingId = NEW_SKILL_ID
            }
        },
    ) {
        val active = state.skills.count { it.status == SkillStatus.ACTIVE }
        val issues = state.skills.count { it.status == SkillStatus.INVALID }
        Note("$active active · ${state.skills.size} discovered${if (issues > 0) " · $issues need attention" else ""}")
        PcField(query, { query = it }, "Search skills")
        SkillFilters(filter) { filter = it }
        PcSectionLabel(filter.label())
        if (filtered.isEmpty()) {
            Note("No matching skills.")
        } else {
            PcGroup {
                filtered.forEach { skill ->
                    PcRow(onClick = { selectedId = skill.id }) {
                        Column(Modifier.weight(1f)) {
                            Text(skill.name, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
                            Text(
                                "${skill.scope.label()} · ${skill.status.label()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (skill.status == SkillStatus.INVALID) colors.error else colors.onSurfaceVariant,
                            )
                            skill.manifest?.description?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (skill.manifest != null && skill.status != SkillStatus.SHADOWED && skill.status != SkillStatus.INVALID) {
                            PcToggle(
                                skill.status == SkillStatus.ACTIVE,
                                { vm.setSkillEnabled(skill.id, it) },
                                "${skill.name} enabled",
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.tertiary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        Note("Skill files reload automatically. The agent can create and edit global or project skills with your permission.")
    }
    }
    selectedSkill?.let { skill ->
        val modifier = if (editingId == null) Modifier.predictiveBackTransform(detailBackMotion) else Modifier
        Box(Modifier.fillMaxSize().then(modifier).background(colors.background)) {
            SkillDetailPage(
                vm,
                skill,
                onEdit = { editorDirty = false; editingId = skill.id },
                onBack = { selectedId = null },
            )
        }
    }
    editingId?.let { id ->
        Box(Modifier.fillMaxSize().predictiveBackTransform(detailBackMotion).background(colors.background)) {
            SkillEditorPage(
                vm = vm,
                skillId = id.takeUnless { it == NEW_SKILL_ID },
                skill = editorSkill,
                isNew = id == NEW_SKILL_ID,
                onDirtyChange = { editorDirty = it },
                onBack = closeNested,
                onSaved = { editorDirty = false; editingId = null },
            )
        }
    }
    if (confirmDiscard) {
        ConfirmDiscardDialog(
            message = "This skill has unsaved changes.",
            onKeepEditing = { confirmDiscard = false },
            onDiscard = { confirmDiscard = false; editorDirty = false; editingId = null },
        )
    }
    }
}

@Composable
private fun SkillDetailPage(vm: ChatViewModel, skill: ManagedSkill, onEdit: () -> Unit, onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val manifest = skill.manifest
    var confirmDelete by remember(skill.id) { mutableStateOf(false) }
    Page(skill.name, onBack = onBack) {
        Note("${skill.scope.label()} · ${skill.status.label()}")
        skill.issue?.let { ErrorText(it) }
        manifest?.description?.takeIf { it.isNotBlank() }?.let { Note(it) }
        if (manifest != null && skill.status != SkillStatus.SHADOWED && skill.status != SkillStatus.INVALID) {
            ToggleRow(
                "Enabled",
                sub = "Applies immediately to the current agent session",
                checked = skill.status != SkillStatus.DISABLED,
            ) { vm.setSkillEnabled(skill.id, it) }
        } else if (skill.status == SkillStatus.SHADOWED) {
            Note("Another skill with this name takes precedence. Disable or edit the active copy to use this one.")
        }
        manifest?.body?.takeIf { it.isNotBlank() }?.let { instructions ->
            PcSectionLabel("Instructions")
            Box(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                    .background(colors.surface).padding(Spacing.m),
            ) {
                MarkdownBlocks(instructions)
            }
            Note("The agent can edit this skill with permission. Changes reload into this session automatically.")
        }
        PcSectionLabel("Details")
        PcGroup {
            if (!manifest?.compatibility.isNullOrBlank()) PcRow {
                Column {
                    Text("Compatibility", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    Text(manifest.compatibility, style = MaterialTheme.typography.bodyMedium, color = colors.onBackground, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (!manifest?.license.isNullOrBlank()) PcRow {
                Text("License", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Text(manifest.license, style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
            }
            if (skill.location.isNotBlank()) PcRow {
                Column {
                    Text("Location", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    Text(skill.location, style = MaterialTheme.typography.bodySmall, color = colors.onBackground, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(Spacing.l))
        PcButton("Edit skill", filled = false, onClick = onEdit)
        Spacer(Modifier.height(Spacing.xs))
        PcButton("Delete skill", filled = false, destructive = true) { confirmDelete = true }
    }
    if (confirmDelete) {
        ConfirmActionDialog(
            title = "Delete skill?",
            message = "This permanently removes ${skill.name}. There is no built-in restore or undo.",
            action = "Delete skill",
            onDismiss = { confirmDelete = false },
        ) {
            vm.deleteSkill(skill.id)
            confirmDelete = false
            onBack()
        }
    }
}

@Composable
private fun SkillEditorPage(
    vm: ChatViewModel,
    skillId: String?,
    skill: ManagedSkill?,
    isNew: Boolean,
    onDirtyChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val editorKey = skillId ?: NEW_SKILL_ID
    val initialName = skill?.name ?: skillId?.substringBeforeLast('/')?.substringAfterLast('/') ?: "new-skill"
    val initialContent = remember(editorKey) { if (isNew) newSkillTemplate(initialName) else "" }
    val clipboard = LocalClipboardManager.current
    var name by rememberSaveable(editorKey) { mutableStateOf(initialName) }
    var skillScope by rememberSaveable(editorKey) { mutableStateOf(skill?.scope ?: SkillScope.GLOBAL) }
    var baselineRevision by rememberSaveable(editorKey) { mutableStateOf(if (isNew) revisionOf(initialContent) else "") }
    var content by rememberSaveable(editorKey) { mutableStateOf(initialContent) }
    var loaded by rememberSaveable(editorKey) { mutableStateOf(isNew) }
    var baseline by remember(editorKey) { mutableStateOf(initialContent) }
    var baselineReady by remember(editorKey) { mutableStateOf(isNew) }
    var loading by remember(editorKey) { mutableStateOf(!isNew) }
    var unavailable by remember(editorKey) { mutableStateOf(false) }
    var conflict by remember(editorKey) { mutableStateOf(false) }
    var saving by remember(editorKey) { mutableStateOf(false) }
    var error by remember(editorKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(editorKey, skill) {
        if (isNew) return@LaunchedEffect
        loading = true
        if (skill == null || skillId == null) {
            unavailable = true
            conflict = false
            baselineReady = false
            loading = false
            return@LaunchedEffect
        }
        vm.readSkill(skillId).fold(
            onSuccess = { latest ->
                val latestRevision = revisionOf(latest)
                unavailable = false
                if (!loaded) {
                    baseline = latest
                    baselineRevision = latestRevision
                    content = latest
                    loaded = true
                    baselineReady = true
                    conflict = false
                } else if (latestRevision == baselineRevision) {
                    baseline = latest
                    baselineReady = true
                    conflict = false
                } else {
                    baselineReady = false
                    conflict = true
                }
            },
            onFailure = {
                unavailable = true
                baselineReady = false
                error = it.message ?: "Skill could not be read"
            },
        )
        loading = false
    }
    val changed = !loading && (content != baseline || isNew && (name != "new-skill" || skillScope != SkillScope.GLOBAL))
    LaunchedEffect(changed, loading) { if (!loading) onDirtyChange(changed) }
    Page(if (isNew) "New skill" else "Edit $name", onBack) {
        if (isNew) {
            PcSectionLabel("Identity")
            McpFieldLabel("Skill name")
            PcField(
                name,
                { value ->
                    val next = value.lowercase().replace(Regex("[^a-z0-9-]"), "")
                    content = content.replaceFirst(Regex("(?m)^name:.*$"), "name: $next")
                    name = next
                    error = null
                },
                "my-skill",
                contentDescription = "Skill name",
            )
            PcGroup {
                CheckRow("Global", skillScope == SkillScope.GLOBAL) { skillScope = SkillScope.GLOBAL }
                CheckRow("Current project", skillScope == SkillScope.PROJECT) { skillScope = SkillScope.PROJECT }
            }
        } else {
            Note("${skillScope.label()} · changes reload into the current session")
        }
        if (unavailable) {
            ErrorText("This skill was removed or renamed. Your draft is preserved here.")
            if (content.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.xs))
                PcButton("Copy draft", filled = false) { clipboard.setText(AnnotatedString(content)) }
            }
        } else if (conflict) {
            ErrorText("This skill changed elsewhere. Your draft is preserved; reopen the editor to load the latest file.")
        }
        PcSectionLabel("SKILL.md")
        PcField(
            content,
            { content = it; error = null },
            if (loading) "Loading…" else "Skill instructions",
            singleLine = false,
            minLines = 12,
            contentDescription = "Skill instructions",
        )
        error?.let { ErrorText(it, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(Spacing.s))
        PcButton(
            if (saving) "Saving…" else "Save",
            enabled = !loading && !saving && !unavailable && !conflict && baselineReady && name.isNotBlank() && content.isNotBlank(),
        ) {
            scope.launch {
                saving = true
                vm.saveSkillAndWait(skillId, skillScope, name, content, baseline.takeUnless { isNew }).fold(
                    onSuccess = { onSaved() },
                    onFailure = { error = it.message ?: "Skill could not be saved" },
                )
                saving = false
            }
        }
    }
}

private fun newSkillTemplate(name: String): String =
    "---\nname: $name\ndescription: Describe when the agent should use this skill.\nlicense: Apache-2.0\n---\n\nWrite concise, actionable instructions here."

private const val NEW_SKILL_ID = "__phonecode_new_skill__"

@Composable
private fun SkillFilters(selected: SkillFilter, onSelect: (SkillFilter) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(top = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SkillFilter.entries.forEach { filter ->
            val active = filter == selected
            val interaction = remember(filter) { MutableInteractionSource() }
            Box(
                Modifier.weight(1f).heightIn(min = Spacing.touchTarget)
                    .pressFeedback(interaction, pressedScale = 0.98f)
                    .clip(MaterialTheme.shapes.large)
                    .background(if (active) colors.primary else colors.surfaceContainerHigh)
                    .semantics { this.selected = active; role = Role.Tab }
                    .clickable(interactionSource = interaction, indication = ripple(), role = Role.Tab) { onSelect(filter) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(filter.shortLabel(), style = MaterialTheme.typography.labelMedium, color = if (active) colors.onPrimary else colors.onBackground)
            }
        }
    }
}

private fun SkillScope.label() = if (this == SkillScope.PROJECT) "Project" else "Global"

private fun SkillStatus.label() = when (this) {
    SkillStatus.ACTIVE -> "Active"
    SkillStatus.DISABLED -> "Off"
    SkillStatus.SHADOWED -> "Overridden"
    SkillStatus.INVALID -> "Needs attention"
}

private fun SkillFilter.label() = when (this) {
    SkillFilter.ALL -> "All skills"
    SkillFilter.ACTIVE -> "Active"
    SkillFilter.OFF -> "Off and overridden"
    SkillFilter.ISSUES -> "Needs attention"
}

private fun SkillFilter.shortLabel() = when (this) {
    SkillFilter.ALL -> "All"
    SkillFilter.ACTIVE -> "Active"
    SkillFilter.OFF -> "Off"
    SkillFilter.ISSUES -> "Issues"
}

@Composable
private fun GitPage(vm: ChatViewModel, settingsVm: SettingsViewModel, onBack: () -> Unit) {
    val state by collectSettingsState(vm)
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var advanced by rememberSaveable { mutableStateOf(false) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var manualUsername by remember { mutableStateOf(vm.keyFor("git.username")) }
    var manualToken by remember { mutableStateOf("") }
    var hasSavedToken by remember { mutableStateOf(vm.keyFor("git.token").isNotBlank()) }
    var manualError by remember { mutableStateOf<String?>(null) }
    val secureStorageAvailable = !vm.secureStorageUnavailable()
    Page("Git", onBack) {
        PcSectionLabel("GitHub")
        when {
            state.githubAuthCode != null -> {
                // Device flow in progress: show the code big, open the browser, keep polling.
                // Rows now paint their own card surface, so freeform group content does too.
                PcGroup {
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
                        PcButton("Open github.com/login/device") {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.githubVerifyUri ?: "https://github.com/login/device")))
                            }
                        }
                        Spacer(Modifier.height(Spacing.xs))
                        PcButton("Cancel", filled = false) { vm.cancelGitHubSignIn() }
                    }
                }
                Note("Waiting for you to authorize on GitHub - this completes automatically.")
            }
            state.githubLogin != null -> {
                PcGroup {
                    PcRow {
                        Column(Modifier.weight(1f)) {
                            Text("@${state.githubLogin}", style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
                            Text("GitHub account connected", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                        Text(
                            "Sign out",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.error,
                            modifier = Modifier.clip(MaterialTheme.shapes.extraSmall).heightIn(min = Spacing.touchTarget)
                                .clickable { confirmSignOut = true }.padding(horizontal = 8.dp),
                        )
                    }
                }
                Note("Push and pull also require a local Git repository with a valid HTTPS origin.")
            }
            else -> {
                PcButton("Sign in with GitHub") { vm.startGitHubSignIn() }
                Spacer(Modifier.height(6.dp))
            }
        }
        PcSectionLabel("Advanced")
        PcGroup {
            ToggleRow(
                "Advanced Git settings",
                "Task branches and manual credentials",
                checked = advanced,
            ) { advanced = it }
            if (advanced) {
                ToggleRow(
                    "Auto-branch each task",
                    "Each new chat works on its own branch of the project",
                    checked = settings.gitAutoBranch,
                ) { v -> settingsVm.update { it.copy(gitAutoBranch = v) } }
                PcRow {
                    Column(Modifier.weight(1f)) {
                        Text("Git username", style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
                        Spacer(Modifier.height(6.dp))
                        PcField(
                            manualUsername,
                            { manualUsername = it; manualError = null },
                            "Account username",
                            contentDescription = "Git username",
                            enabled = secureStorageAvailable,
                        )
                    }
                }
                PcRow {
                    Column(Modifier.weight(1f)) {
                        Text("Manual access token", style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
                        Spacer(Modifier.height(6.dp))
                        PcField(
                            manualToken,
                            { manualToken = it; manualError = null },
                            if (hasSavedToken) "New PAT (leave blank to keep saved)" else "Fine-grained PAT",
                            password = true,
                            contentDescription = "Manual Git access token",
                            enabled = secureStorageAvailable,
                        )
                    }
                }
            }
        }
        if (advanced) {
            if (!secureStorageAvailable) {
                ErrorText("Secure storage is unavailable on this device, so manual Git credentials cannot be changed.")
            }
            manualError?.let { ErrorText(it, modifier = Modifier.padding(top = Spacing.xs)) }
            Spacer(Modifier.height(Spacing.xs))
            PcButton(
                "Save manual credentials",
                enabled = secureStorageAvailable && manualUsername.isNotBlank() && (manualToken.isNotBlank() || hasSavedToken),
            ) {
                val updates = buildMap {
                    put("git.username", manualUsername)
                    if (manualToken.isNotBlank()) put("git.token", manualToken)
                }
                if (vm.setKeys(updates)) {
                    if (manualToken.isNotBlank()) hasSavedToken = true
                    manualToken = ""
                    manualError = null
                } else {
                    manualError = "Manual Git credentials could not be saved securely."
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

@Composable
private fun ExportPage(vm: ChatViewModel, settingsVm: SettingsViewModel, onBack: () -> Unit) {
    val state by collectSettingsState(vm)
    val stamp = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    var confirmImport by rememberSaveable { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportTo(uri)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // reload AFTER the import lands: it overwrote app_settings.json on disk, and the
        // theme/instructions must go live without a restart (review finding #3).
        if (uri != null) vm.importFrom(uri) { settingsVm.reload() }
    }
    Page("Export & import", onBack) {
        PcSectionLabel("Your data")
        Note("Exports are not encrypted. Saved provider and sign-in credentials are excluded, but chats and tool activity may contain sensitive content.")
        Note("Import replaces chats and settings with the backup. Linked phone folders, provider keys, MCP servers, and skills are not included. Approval always returns to Ask before each change.")
        Spacer(Modifier.height(10.dp))
        PcButton("Export chats & settings") { exporter.launch("phonecode-backup-$stamp.zip") }
        Spacer(Modifier.height(10.dp))
        PcButton("Import from a file", filled = false) { confirmImport = true }
        state.notice?.let {
            Spacer(Modifier.height(10.dp))
            Note(it, announce = true)
            LaunchedEffect(it) { kotlinx.coroutines.delay(4000); vm.clearNotice() }
        }
    }
    if (confirmImport) {
        ConfirmActionDialog(
            title = "Replace chats and settings?",
            message = "The backup replaces current chats and settings. Existing chats not in the backup are removed, and approval returns to Ask before each change. This cannot be undone unless you export first.",
            action = "Choose backup file",
            secondaryAction = "Export first",
            onSecondary = {
                confirmImport = false
                exporter.launch("phonecode-backup-$stamp.zip")
            },
            onDismiss = { confirmImport = false },
        ) {
            confirmImport = false
            importer.launch(arrayOf("application/zip", "application/octet-stream"))
        }
    }
}

@Composable
private fun AboutPage(vm: ChatViewModel, onOpenDoc: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0.1"
    }
    Page("About", onBack) {
        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ic_phonecode_mark), null, tint = colors.onBackground, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(14.dp))
            Text("PhoneCode", style = MaterialTheme.typography.headlineMedium, color = colors.onBackground)
            Text("version $version", style = MaterialTheme.typography.labelMedium, color = colors.tertiary, modifier = Modifier.padding(top = 4.dp))
        }
        PcGroup {
            PcRow(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dttdrv.xyz/phonecode"))) }
            }) {
                Text("Website", style = MaterialTheme.typography.bodyLarge, color = colors.onBackground, modifier = Modifier.weight(1f))
                Text("dttdrv.xyz/phonecode", style = MaterialTheme.typography.labelMedium, color = colors.tertiary)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.tertiary, modifier = Modifier.size(20.dp))
            }
            PcRow {
                Text("Config directory", style = MaterialTheme.typography.bodyLarge, color = colors.onBackground, modifier = Modifier.weight(1f))
                Text(vm.configDirPath().substringAfterLast("/"), style = MaterialTheme.typography.labelSmall.copy(fontFamily = PcMono), color = colors.tertiary)
            }
            NavRow("Terms of Service") { onOpenDoc("doc:terms") }
            NavRow("Privacy Policy") { onOpenDoc("doc:privacy") }
            NavRow("Open-source licenses") { onOpenDoc("doc:licenses") }
        }
    }
}

@Composable
private fun DocPage(title: String, assetName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val text = remember(assetName) {
        runCatching { context.assets.open(assetName).bufferedReader().use { it.readText() } }
            .getOrDefault("Document unavailable.")
    }
    Page(title, onBack) {
        // Render the markdown (headings, bold, lists) instead of dumping the raw source: the old single
        // Text showed literal '#', '**' and '-' in a tiny caption font. The doc's own H1 title is dropped
        // since the page header already shows it.
        val body = remember(text) { text.replace(Regex("^#\\s+.*(\\R+)?"), "") }
        Box(Modifier.padding(vertical = Spacing.xs)) {
            MarkdownBlocks(body, color = colors.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Custom provider dialog
// ---------------------------------------------------------------------------------------------

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
    PcField(
        name,
        onNameChange,
        "e.g. My LM Studio",
        contentDescription = "Provider name",
    )
    Spacer(Modifier.height(6.dp))
    Text("Base URL", style = MaterialTheme.typography.labelMedium, color = colors.secondary)
    PcField(
        baseUrl,
        onBaseUrlChange,
        "e.g. https://host/v1",
        contentDescription = "Base URL",
    )
    Spacer(Modifier.height(6.dp))
    Text("Model IDs", style = MaterialTheme.typography.labelMedium, color = colors.secondary)
    PcField(
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
) {
    val colors = MaterialTheme.colorScheme
    var name by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var anthropicFormat by rememberSaveable { mutableStateOf(false) }
    var modelsText by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
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
    val maxHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height.toDp() - 32.dp)
            .coerceAtLeast(Spacing.touchTarget * 4f)
    }
    Dialog(onDismissRequest = onDismiss) {
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
                    PcToggle(anthropicFormat, { anthropicFormat = it }, "Use Anthropic Messages format")
                }
                (error ?: validationError?.takeIf { hasInput })?.let {
                    Spacer(Modifier.height(6.dp))
                    ErrorText(it, style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(Spacing.s))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Box(Modifier.weight(1f)) { PcButton("Cancel", filled = false, onClick = onDismiss) }
                Box(Modifier.weight(1f)) {
                    PcButton(if (saving) "Saving…" else "Save", enabled = !saving && validationError == null) {
                        if (validationError != null) return@PcButton
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
    }
}

private fun parseHeaders(text: String): Map<String, String> =
    text.lineSequence().mapNotNull { line ->
        val i = line.indexOf(':')
        if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
    }.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }.toMap()
