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
import dev.phonecode.app.ui.components.MisulField
import dev.phonecode.app.ui.components.MisulGroup
import dev.phonecode.app.ui.components.MisulIconButton
import dev.phonecode.app.ui.components.MisulContentRow
import dev.phonecode.app.ui.components.MisulSectionLabel
import dev.phonecode.app.ui.components.MisulSearchField
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
internal fun McpPage(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onOpenServer: (String) -> Unit,
) {
    val state by collectSettingsState(vm)
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var reconnecting by remember { mutableStateOf(false) }
    var reconnectError by remember { mutableStateOf<String?>(null) }
    val visible = remember(state.mcpServers, query) {
        state.mcpServers.filter { (name, server) ->
            query.isBlank() || name.contains(query, true) || server.url.contains(query, true)
        }
    }

    SettingsPageShell("MCP servers", onBack) {
        val connected = state.mcpSnapshots.count { it.value.connected }
        MisulGroup {
            SettingsNavigationRow(
                label = "Add server",
                supportingText = "Connect an HTTPS or on-device MCP server",
                icon = Icons.Filled.Add,
                showDivider = false,
                onClick = { onOpenServer("") },
            )
        }
        SettingsNote("$connected connected · ${state.mcpToolCount} tools available")
        state.mcpConfigError?.let {
            SettingsErrorText(it)
            SettingsNote("The existing opencode.json has been preserved. Fix it before changing MCP servers here.")
        }
        state.mcpOperationError?.let {
            SettingsErrorText(it)
            SettingsNote("Your saved server list is still available. Review the affected server and try again.")
        }
        if (state.mcpServers.size >= 12 || query.isNotBlank()) {
            MisulSearchField(query, { query = it }, "Search servers")
        }
        MisulSectionLabel("Servers")
        if (state.mcpServers.isEmpty()) {
            SettingsNote("No servers configured.")
        } else if (visible.isEmpty()) {
            SettingsNote("No servers match “${query.trim()}”.")
        } else {
            MisulGroup {
                visible.entries.toList().forEachIndexed { index, (name, server) ->
                    val snapshot = state.mcpSnapshots[name]
                    val status = when {
                        !server.enabled -> "Off · Test to enable"
                        name in state.mcpConnecting -> "Connecting"
                        snapshot?.connected == true -> "Connected · ${snapshot.tools.size} reported tools"
                        snapshot?.error?.isNotBlank() == true -> "Needs attention · ${snapshot.error}"
                        else -> "Not tested"
                    }
                    SettingsNavigationRow(
                        label = name,
                        supportingText = status,
                        showDivider = index != visible.size - 1,
                        onClick = { onOpenServer(name) },
                    )
                }
            }
        }
        if (state.mcpConfigError == null && state.mcpServers.isNotEmpty()) {
            MisulSectionLabel("Connection")
            MisulGroup {
                dev.phonecode.app.ui.components.MisulActionRow(
                    label = "Reconnect enabled servers",
                    supportingText = if (reconnecting) "Reconnecting" else "Refresh tools from every enabled server",
                    enabled = !reconnecting,
                    showDivider = false,
                ) {
                    vm.clearError()
                    reconnectError = null
                    reconnecting = true
                    scope.launch {
                        vm.reconnectMcpAndWait().onFailure { failure ->
                            reconnectError = failure.message ?: "MCP servers could not be reconnected"
                        }
                        reconnecting = false
                    }
                }
            }
            reconnectError?.let { SettingsErrorText(it, modifier = Modifier.padding(top = Spacing.xs)) }
        }
    }
}

@Composable
internal fun McpServerPage(
    vm: ChatViewModel,
    initialName: String,
    initial: McpServerConfig,
    existingNames: Set<String>,
    snapshot: McpServerSnapshot?,
    onBack: () -> Unit,
    onDirtyChange: (Boolean) -> Unit,
    onSaved: () -> Unit,
) {
    val state by collectSettingsState(vm)
    val colors = MaterialTheme.colorScheme
    val isNew = initialName.isEmpty()
    val scope = rememberCoroutineScope()
    var baseline by remember(initialName) { mutableStateOf(initial) }
    val currentRevision = remember(initial) { revisionOf(initial) }
    var acceptedRevision by rememberSaveable(initialName) { mutableStateOf(currentRevision) }
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var url by rememberSaveable(initialName) { mutableStateOf(initial.url) }
    var headers by rememberSaveable(initialName) { mutableStateOf(headersForEditor(initial.headers)) }
    var timeout by rememberSaveable(initialName) { mutableStateOf(initial.timeout.toString()) }
    var enabled by rememberSaveable(initialName) { mutableStateOf(initial.enabled) }
    var error by remember(initialName) { mutableStateOf<String?>(null) }
    var testing by remember(initialName) { mutableStateOf(false) }
    var saving by remember(initialName) { mutableStateOf(false) }
    var testResult by remember(initialName) { mutableStateOf<TestedMcpDraft?>(null) }
    var reviewedDraftRevision by remember(initialName) { mutableStateOf<String?>(null) }
    var toolQuery by rememberSaveable(initialName) { mutableStateOf("") }
    var showAllTools by rememberSaveable(initialName) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(initialName) { mutableStateOf(false) }
    val deleteOperationKey = mcpDeleteOperationKey(initialName)
    val deleteOperation = state.settingsOperations[deleteOperationKey]
    val externalChange = !isNew && currentRevision != acceptedRevision
    fun invalidateProbeReview() {
        testResult = null
        reviewedDraftRevision = null
    }

    fun validationMessage(): String? {
        val finalName = if (isNew) name.trim() else initialName
        val finalTimeout = timeout.toLongOrNull()
        val headerLines = headers.lineSequence().filter { it.isNotBlank() }.toList()
        val invalidHeader = headerLines.firstOrNull { line ->
            val separator = line.indexOf(':')
            separator <= 0 ||
                !MCP_HEADER_NAME.matches(line.substring(0, separator).trim()) ||
                line.substring(separator + 1).isBlank() ||
                line.substring(separator + 1).trim().length > 8_192
        }
        return when {
            finalName.isBlank() -> "Name is required"
            finalName.length > 80 || !MCP_SERVER_NAME.matches(finalName) ->
                "Use letters, numbers, spaces, dots, underscores, or hyphens"
            !isSafeMcpEndpoint(url.trim()) -> "Use HTTPS, or HTTP only for localhost"
            isNew && finalName in existingNames -> "A server named $finalName already exists"
            headerLines.size > 32 -> "Use no more than 32 headers"
            invalidHeader != null -> "Each header needs a valid name and value"
            finalTimeout == null || finalTimeout !in 1_000L..60_000L -> "Timeout must be between 1000 and 60000 ms"
            else -> null
        }
    }

    fun draft(): Pair<String, McpServerConfig>? {
        val finalName = if (isNew) name.trim() else initialName
        val finalTimeout = timeout.toLongOrNull()
        error = validationMessage()
        return if (error == null) {
            finalName to McpServerConfig(
                "remote",
                url.trim(),
                parseHeaders(headers, baseline.headers),
                enabled,
                finalTimeout!!,
            )
        } else null
    }

    val initialHeaders = headersForEditor(baseline.headers)
    val changed = if (isNew) {
        name.isNotBlank() || url.isNotBlank() || headers.isNotBlank() || timeout != baseline.timeout.toString() || enabled != baseline.enabled
    } else {
        url != baseline.url || headers != initialHeaders || timeout != baseline.timeout.toString() || enabled != baseline.enabled
    }
    val connectionChanged = if (isNew) {
        name.isNotBlank() || url.isNotBlank() || headers.isNotBlank() || timeout != baseline.timeout.toString()
    } else {
        url != baseline.url || headers != initialHeaders || timeout != baseline.timeout.toString()
    }
    val validationError = validationMessage()
    val currentDraftRevision = if (validationError == null) {
        mcpConnectionDraftRevision(
            name = if (isNew) name.trim() else initialName,
            config = McpServerConfig(
                type = "remote",
                url = url.trim(),
                headers = parseHeaders(headers, baseline.headers),
                enabled = false,
                timeout = requireNotNull(timeout.toLongOrNull()),
            ),
        )
    } else {
        null
    }
    val latestDraftRevision by rememberUpdatedState(currentDraftRevision)
    val currentTestResult = testResult?.takeIf { it.revision == currentDraftRevision }
    val reviewedCurrentDraft =
        currentTestResult?.snapshot?.connected == true &&
            reviewedDraftRevision == currentDraftRevision
    val canTest = !testing && !saving && !externalChange
    val enabledDraftNeedsReview = enabled && (isNew || !baseline.enabled || connectionChanged)
    val canSave =
        canTest && changed && validationError == null && (!enabledDraftNeedsReview || reviewedCurrentDraft)
    // Turning a server off is always available. Turning one on requires a successful probe for
    // this exact draft, including servers that were previously saved in the Off state.
    val canEnable = enabled || reviewedCurrentDraft
    LaunchedEffect(changed) { onDirtyChange(changed) }
    val shownSnapshot = currentTestResult?.snapshot ?: snapshot.takeUnless { changed }
    SettingsPageShell(if (isNew) "Add MCP server" else initialName, onBack) {
        if (externalChange) {
            SettingsErrorText("This server changed elsewhere. Reload before saving.")
            Spacer(Modifier.height(Spacing.xs))
            MisulActionButton("Reload latest", role = ActionRole.QUIET) {
                baseline = initial
                url = initial.url
                headers = headersForEditor(initial.headers)
                timeout = initial.timeout.toString()
                enabled = initial.enabled
                acceptedRevision = currentRevision
                error = null
                invalidateProbeReview()
            }
            Spacer(Modifier.height(Spacing.s))
        }
        when {
            testing -> SettingsNote("Testing this configuration…")
            shownSnapshot?.connected == true -> SettingsNote("Connected to ${shownSnapshot.serverTitle.ifBlank { shownSnapshot.serverName }.ifBlank { name }}")
            shownSnapshot?.error?.isNotBlank() == true -> SettingsErrorText(shownSnapshot.error)
            !isNew && !changed -> SettingsNote(if (enabled) "Not tested" else "Off")
        }
        MisulSectionLabel("Connection")
        if (isNew) {
            SettingsFieldLabel("Server name")
            MisulField(
                name,
                {
                    name = it
                    error = null
                    invalidateProbeReview()
                },
                "e.g. context7",
                contentDescription = "Server name",
            )
            error?.takeIf {
                it == "Name is required" || it.startsWith("A server named ") ||
                    it.startsWith("Use letters")
            }?.let {
                SettingsErrorText(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Spacing.xs))
            }
        }
        SettingsFieldLabel("Remote URL")
        MisulField(
            url,
            {
                url = it
                error = null
                invalidateProbeReview()
            },
            "e.g. https://host/mcp",
            contentDescription = "Remote URL",
        )
        error?.takeIf { it.startsWith("Use HTTPS") }?.let {
            SettingsErrorText(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Spacing.xs))
        }
        SettingsFieldLabel("HTTP headers")
        val headerRows = headerRowsFromEditor(headers)
        headerRows.forEachIndexed { index, header ->
            MisulGroup(Modifier.padding(bottom = Spacing.xs)) {
                MisulContentRow {
                    Box(Modifier.weight(1f)) {
                        MisulField(
                            header.first,
                            {
                                headers = updateHeaderRow(headers, index, name = it)
                                error = null
                                invalidateProbeReview()
                            },
                            "e.g. Authorization",
                            contentDescription = "Header name ${index + 1}",
                        )
                    }
                    MisulIconButton(
                        Icons.Filled.Delete,
                        "Remove header ${index + 1}",
                        onClick = {
                        headers = removeHeaderRow(headers, index)
                        error = null
                        invalidateProbeReview()
                    },
                )
                }
                MisulContentRow(showDivider = false) {
                    MisulField(
                        header.second,
                        {
                            headers = updateHeaderRow(headers, index, value = it)
                            error = null
                            invalidateProbeReview()
                        },
                        "Secret value",
                        secure = true,
                        contentDescription = "Header value ${index + 1}",
                    )
                }
            }
        }
        MisulActionButton("Add header", role = ActionRole.QUIET, icon = Icons.Filled.Add) {
            headers = addHeaderRow(headers)
            error = null
            invalidateProbeReview()
        }
        error?.takeIf {
            it.startsWith("Each header") || it.startsWith("Use no more")
        }?.let {
            SettingsErrorText(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Spacing.xs))
        }
        SettingsNote(
            "Header values are concealed after saving and encrypted with Android Keystore.",
        )
        SettingsFieldLabel("Connection timeout")
        MisulField(
            timeout,
            {
                timeout = it.filter(Char::isDigit)
                error = null
                invalidateProbeReview()
            },
            "5000 milliseconds",
            contentDescription = "Connection timeout in milliseconds",
        )
        error?.takeIf { it.startsWith("Timeout") }?.let {
            SettingsErrorText(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Spacing.xs))
        }
        Spacer(Modifier.height(Spacing.xs))
        MisulGroup {
            SettingsToggleRow(
                "Enabled",
                sub = if (!canEnable) "Test successfully before enabling" else null,
                checked = enabled,
                enabled = canEnable,
                showDivider = false,
            ) {
                enabled = it
                error = null
            }
        }
        if (enabledDraftNeedsReview && !reviewedCurrentDraft) {
            SettingsNote(
                "Test this changed configuration and review its reported tools before saving it enabled.",
            )
        }
        if (isNew) {
            SettingsNote(
                "MCP servers receive tool inputs from the agent. Review the reported tools before " +
                    "enabling; mutating actions follow your approval setting.",
            )
        }
        error?.takeUnless { message ->
            message == "Name is required" || message.startsWith("A server named ") ||
                message.startsWith("Use HTTPS") || message.startsWith("Each header") ||
                message.startsWith("Timeout")
        }?.let { message ->
            SettingsErrorText(
                message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        Spacer(Modifier.height(Spacing.s))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End)) {
                MisulActionButton(
                    "Test",
                    role = ActionRole.SECONDARY,
                    loading = testing,
                    enabled = canTest,
                ) {
                    if (!testing) draft()?.let { (draftName, server) ->
                        scope.launch {
                            val testedRevision = mcpConnectionDraftRevision(draftName, server)
                            testing = true
                            invalidateProbeReview()
                            val result = vm.testMcpServer(draftName, server)
                            if (latestDraftRevision == testedRevision) {
                                testResult = TestedMcpDraft(testedRevision, result)
                            }
                            testing = false
                        }
                    }
                }
                MisulActionButton(
                    "Save",
                    role = ActionRole.PRIMARY,
                    loading = saving,
                    enabled = canSave,
                ) {
                    draft()?.let { (draftName, server) ->
                        scope.launch {
                            saving = true
                            vm.saveMcpServerAndWait(draftName, server, baseline.takeUnless { isNew }).fold(
                                onSuccess = { withContext(Dispatchers.Main.immediate) { onSaved() } },
                                onFailure = { error = it.message ?: "MCP configuration could not be saved" },
                            )
                            saving = false
                        }
                    }
                }
        }
        shownSnapshot?.takeIf { it.connected }?.let { connectedSnapshot ->
            MisulSectionLabel("Server")
            MisulGroup {
                McpValueRow("Name", connectedSnapshot.serverTitle.ifBlank { connectedSnapshot.serverName }.ifBlank { name })
                McpValueRow("Version", connectedSnapshot.serverVersion.ifBlank { "Unknown" })
                McpValueRow("Protocol", connectedSnapshot.protocolVersion)
                McpValueRow(
                    "Advertised capabilities",
                    connectedSnapshot.capabilities.sorted().joinToString().ifBlank { "None" },
                )
                McpValueRow(
                    "Available in Misul Agent",
                    if (connectedSnapshot.tools.isEmpty()) "No tool calls" else "Tool calls",
                    showDivider = false,
                )
            }
            if (connectedSnapshot.instructions.isNotBlank()) {
                MisulSectionLabel("Instructions")
                SettingsNote(connectedSnapshot.instructions)
            }
            MisulSectionLabel("Tools")
            if (connectedSnapshot.tools.isEmpty()) SettingsNote("This server exposes no tools.") else {
                if (connectedSnapshot.tools.size > 6) {
                    MisulSearchField(
                        toolQuery,
                        {
                            toolQuery = it
                            if (it.isNotBlank()) showAllTools = true
                        },
                        "Search tools",
                        contentDescription = "Search server tools",
                    )
                    Spacer(Modifier.height(Spacing.xs))
                }
                val matchingTools = connectedSnapshot.tools.filter { tool ->
                    toolQuery.isBlank() ||
                        tool.title.contains(toolQuery, ignoreCase = true) ||
                        tool.name.contains(toolQuery, ignoreCase = true) ||
                        tool.description.contains(toolQuery, ignoreCase = true)
                }
                val visibleTools =
                    if (showAllTools || toolQuery.isNotBlank()) matchingTools else matchingTools.take(8)
                MisulGroup {
                    visibleTools.forEachIndexed { index, tool ->
                        MisulContentRow(showDivider = index != visibleTools.lastIndex) {
                            Column {
                                Text(tool.title.ifBlank { tool.name }, style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
                                if (tool.title.isNotBlank() && tool.title != tool.name) {
                                    Text(
                                        tool.name,
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = PcMono),
                                        color = colors.tertiary,
                                    )
                                }
                                if (tool.description.isNotBlank()) Text(tool.description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (matchingTools.isEmpty()) {
                    SettingsNote("No tools match “${toolQuery.trim()}”.")
                } else if (!showAllTools && toolQuery.isBlank() && connectedSnapshot.tools.size > 8) {
                    Spacer(Modifier.height(Spacing.xs))
                    MisulActionButton("Show all ${connectedSnapshot.tools.size} tools", role = ActionRole.QUIET) {
                        showAllTools = true
                    }
                } else if (showAllTools && toolQuery.isBlank() && connectedSnapshot.tools.size > 8) {
                    Spacer(Modifier.height(Spacing.xs))
                    MisulActionButton("Show fewer tools", role = ActionRole.QUIET) {
                        showAllTools = false
                    }
                }
            }
            if (currentTestResult?.snapshot?.connected == true) {
                val completeInventoryVisible =
                    toolQuery.isBlank() &&
                        (connectedSnapshot.tools.size <= 8 || showAllTools)
                MisulSectionLabel("Review")
                MisulGroup {
                    SettingsToggleRow(
                        label = "I reviewed the reported tools",
                        sub = if (completeInventoryVisible) {
                            if (connectedSnapshot.tools.isEmpty()) {
                                "This server reported that it exposes no tools"
                            } else {
                                "Confirm this exact server inventory before enabling it"
                            }
                        } else {
                            "Show all reported tools and clear the search before confirming"
                        },
                        checked = reviewedCurrentDraft,
                        enabled = completeInventoryVisible,
                        showDivider = false,
                    ) { reviewed ->
                        reviewedDraftRevision =
                            if (reviewed) currentDraftRevision else null
                    }
                }
            }
        }
        if (!isNew) {
            Spacer(Modifier.height(Spacing.l))
            MisulTextAction(
                "Delete server",
                destructive = true,
                enabled = deleteOperation?.running != true,
            ) {
                vm.clearSettingsOperation(deleteOperationKey)
                confirmDelete = true
            }
        }
    }
    if (confirmDelete) {
        ConfirmActionDialog(
            title = "Delete MCP server?",
            message = "This permanently removes $initialName and its encrypted headers. Tools from this server will no longer be available. This cannot be undone.",
            action = "Delete server",
            progressAction = "Deleting…",
            inProgress = deleteOperation?.running == true,
            inlineError = deleteOperation?.error?.let {
                "Could not delete $initialName: $it"
            },
            onDismiss = {
                vm.clearSettingsOperation(deleteOperationKey)
                confirmDelete = false
            },
        ) {
            vm.clearSettingsOperation(deleteOperationKey)
            scope.launch {
                vm.deleteMcpServerAndWait(initialName).fold(
                    onSuccess = {
                        confirmDelete = false
                        withContext(Dispatchers.Main.immediate) { onSaved() }
                    },
                    onFailure = {},
                )
            }
        }
    }
}


@Composable
private fun McpValueRow(label: String, value: String, showDivider: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    MisulContentRow(showDivider = showDivider) {
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

private const val PRESERVED_MCP_HEADER = "••••••••"
private val MCP_SERVER_NAME = Regex("^[a-zA-Z0-9][a-zA-Z0-9 ._-]*$")
private val MCP_HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

private fun headersForEditor(headers: Map<String, String>): String =
    headers.keys.sorted().joinToString("\n") { "$it: $PRESERVED_MCP_HEADER" }

private fun headerRowsFromEditor(text: String): List<Pair<String, String>> =
    text.lineSequence().filter { it.isNotBlank() }.map { line ->
        val separator = line.indexOf(':')
        if (separator < 0) {
            line to ""
        } else {
            line.substring(0, separator).trim() to line.substring(separator + 1).trim()
        }
    }.toList()

private fun headerRowsToEditor(rows: List<Pair<String, String>>): String =
    rows.joinToString("\n") { (name, value) -> "$name: $value" }

private fun addHeaderRow(text: String): String =
    headerRowsToEditor(headerRowsFromEditor(text) + ("" to ""))

private fun updateHeaderRow(
    text: String,
    index: Int,
    name: String? = null,
    value: String? = null,
): String {
    val rows = headerRowsFromEditor(text).toMutableList()
    val current = rows.getOrNull(index) ?: return text
    rows[index] = (name ?: current.first).replace("\n", "") to
        (value ?: current.second).replace("\n", "")
    return headerRowsToEditor(rows)
}

private fun removeHeaderRow(text: String, index: Int): String =
    headerRowsToEditor(headerRowsFromEditor(text).filterIndexed { rowIndex, _ -> rowIndex != index })

private fun parseHeaders(text: String, preserved: Map<String, String> = emptyMap()): Map<String, String> =
    text.lineSequence().mapNotNull { line ->
        val i = line.indexOf(':')
        if (i <= 0) {
            null
        } else {
            val name = line.substring(0, i).trim()
            val entered = line.substring(i + 1).trim()
            name to if (entered == PRESERVED_MCP_HEADER && name in preserved) {
                preserved.getValue(name)
            } else {
                entered
            }
        }
    }.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }.toMap()
