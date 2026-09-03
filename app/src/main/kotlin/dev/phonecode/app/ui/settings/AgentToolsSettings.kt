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
internal fun AgentToolsPage(vm: ChatViewModel, onBack: () -> Unit) {
    val state by collectSettingsState(vm)
    var query by rememberSaveable { mutableStateOf("") }
    var accessFilter by rememberSaveable { mutableStateOf(AgentToolAccessFilter.ALL) }
    // Recompute from the current registry: tool identities can change while MCP/skill counts stay
    // constant (for example, reconnecting a different server with the same number of tools).
    val inventory = vm.availableTools()
    val summary = remember(inventory) { agentToolInventorySummary(inventory) }
    val tools = remember(inventory, query, accessFilter) {
        filterAgentTools(inventory, query, accessFilter)
    }
    val colors = MaterialTheme.colorScheme
    SettingsPageShell("Agent tools", onBack) {
        MisulSectionLabel("Available capabilities")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                summary.total.toString(),
                style = MaterialTheme.typography.headlineMedium,
                color = colors.onBackground,
            )
            Text(
                " tools",
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            if (summary.remote > 0) {
                AgentToolBadge("${summary.remote} connected", emphasized = true)
            }
        }
        Text(
            "${summary.readOnly} read only · ${summary.needsApproval} require approval · ${summary.contextual} depend on the action",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        SettingsNote("Changes follow your approval setting in Files & permissions.")
        MisulSearchField(query, { query = it }, "Search tools")
        AgentToolFilters(accessFilter) { accessFilter = it }
        if (inventory.isEmpty()) {
            SettingsNote("No tools are available yet. Connect an MCP server or add a skill to extend Misul Agent.")
        } else if (tools.isEmpty()) {
            val message = if (query.isNotBlank()) {
                "No tools match “${query.trim()}”. Clear the search or choose another access filter."
            } else {
                "No ${accessFilter.emptyLabel()} tools are available. Choose All to see the full inventory."
            }
            SettingsNote(message)
            Spacer(Modifier.height(Spacing.xs))
            MisulActionButton(
                if (query.isNotBlank()) "Clear search" else "Show all tools",
                onClick = {
                    query = ""
                    accessFilter = AgentToolAccessFilter.ALL
                },
                role = ActionRole.QUIET,
            )
        } else {
            tools.groupBy { it.source }.toList()
                .sortedBy { (source, _) -> listOf("Misul Agent", "Skills", "MCP").indexOf(source).let { if (it < 0) Int.MAX_VALUE else it } }
                .forEach { (source, entries) ->
                MisulSectionLabel("$source · ${entries.size}")
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
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            AgentToolBadge(tool.access, emphasized = tool.access == "Read only")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentToolFilters(
    selected: AgentToolAccessFilter,
    onSelect: (AgentToolAccessFilter) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = Spacing.s).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AgentToolAccessFilter.entries.forEach { filter ->
            MisulFilter(
                label = filter.shortLabel(),
                selected = selected == filter,
                onClick = { onSelect(filter) },
            )
        }
    }
}

@Composable
private fun AgentToolBadge(text: String, emphasized: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.clip(MaterialTheme.shapes.large)
            .background(if (emphasized) colors.secondaryContainer else colors.surfaceContainerHighest)
            .semantics { stateDescription = text }
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (emphasized) colors.onSecondaryContainer else colors.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private fun AgentToolAccessFilter.shortLabel() = when (this) {
    AgentToolAccessFilter.ALL -> "All"
    AgentToolAccessFilter.READ_ONLY -> "Read only"
    AgentToolAccessFilter.NEEDS_APPROVAL -> "Approval"
    AgentToolAccessFilter.CONTEXTUAL -> "Conditional"
}

private fun AgentToolAccessFilter.emptyLabel() = when (this) {
    AgentToolAccessFilter.ALL -> "matching"
    AgentToolAccessFilter.READ_ONLY -> "read-only"
    AgentToolAccessFilter.NEEDS_APPROVAL -> "approval-gated"
    AgentToolAccessFilter.CONTEXTUAL -> "conditional"
}
