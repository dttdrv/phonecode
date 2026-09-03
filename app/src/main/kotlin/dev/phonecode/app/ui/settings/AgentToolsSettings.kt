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
import androidx.compose.ui.semantics.stateDescription
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
import dev.phonecode.app.ui.components.MisulField
import dev.phonecode.app.ui.components.MisulGroup
import dev.phonecode.app.ui.components.MisulIconButton
import dev.phonecode.app.ui.components.MisulContentRow
import dev.phonecode.app.ui.components.ActionRole
import dev.phonecode.app.ui.components.MisulActionButton
import dev.phonecode.app.ui.components.MisulFilter
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
internal fun AgentToolsPage(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onOpenCategory: (AgentToolAccessFilter) -> Unit,
) {
    val inventory = vm.availableTools()
    val summary = remember(inventory) { agentToolInventorySummary(inventory) }
    SettingsPageShell("Agent tools", onBack) {
        SettingsNote("${summary.total} tools available")
        MisulSectionLabel("Access")
        MisulGroup {
            SettingsNavigationRow(
                label = "Read only",
                value = summary.readOnly.toString(),
                onClick = { onOpenCategory(AgentToolAccessFilter.READ_ONLY) },
            )
            SettingsNavigationRow(
                label = "Requires approval",
                value = summary.needsApproval.toString(),
                onClick = { onOpenCategory(AgentToolAccessFilter.NEEDS_APPROVAL) },
            )
            SettingsNavigationRow(
                label = "Conditional",
                value = summary.contextual.toString(),
                showDivider = false,
                onClick = { onOpenCategory(AgentToolAccessFilter.CONTEXTUAL) },
            )
        }
        SettingsNote("Access follows Files & permissions. Misul asks before tools that can change data.")
        if (summary.remote > 0) SettingsNote("${summary.remote} tools come from connected MCP servers.")
    }
}

@Composable
internal fun AgentToolsCategoryPage(
    vm: ChatViewModel,
    access: AgentToolAccessFilter,
    onBack: () -> Unit,
) {
    var query by rememberSaveable(access) { mutableStateOf("") }
    val inventory = vm.availableTools()
    val tools = remember(inventory, query, access) { filterAgentTools(inventory, query, access) }
    val colors = MaterialTheme.colorScheme
    SettingsPageShell(access.pageTitle(), onBack) {
        SettingsNote(access.explanation())
        if (inventory.size >= 12 || query.isNotBlank()) {
            MisulSearchField(query, { query = it }, "Search tools")
        }
        if (tools.isEmpty()) {
            SettingsNote(if (query.isBlank()) "No tools in this category." else "No tools match “${query.trim()}”.")
        } else {
            tools.groupBy { it.source }.toList()
                .sortedBy { (source, _) -> listOf("Misul Agent", "Skills", "MCP").indexOf(source).let { if (it < 0) Int.MAX_VALUE else it } }
                .forEach { (source, entries) ->
                    MisulSectionLabel(source)
                    MisulGroup {
                        entries.forEachIndexed { index, tool ->
                            MisulContentRow(showDivider = index != entries.lastIndex) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        tool.name,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = PcMono),
                                        color = colors.onBackground,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        tool.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
        }
    }
}

private fun AgentToolAccessFilter.pageTitle() = when (this) {
    AgentToolAccessFilter.ALL -> "All tools"
    AgentToolAccessFilter.READ_ONLY -> "Read-only tools"
    AgentToolAccessFilter.NEEDS_APPROVAL -> "Approval tools"
    AgentToolAccessFilter.CONTEXTUAL -> "Conditional tools"
}

private fun AgentToolAccessFilter.explanation() = when (this) {
    AgentToolAccessFilter.ALL -> "All tools available to Misul Agent."
    AgentToolAccessFilter.READ_ONLY -> "These tools inspect information without changing it."
    AgentToolAccessFilter.NEEDS_APPROVAL -> "Misul asks before these tools can make a change."
    AgentToolAccessFilter.CONTEXTUAL -> "Approval depends on the specific action and its target."
}
