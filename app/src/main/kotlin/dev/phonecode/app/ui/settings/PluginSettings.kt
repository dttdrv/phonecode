package dev.phonecode.app.ui.settings

import dev.phonecode.app.ui.theme.ShapePill
import androidx.compose.ui.semantics.selected
import androidx.compose.material.icons.outlined.Dns
import dev.phonecode.app.ui.components.AppIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.phonecode.app.agent.ChatViewModel
import dev.phonecode.app.ui.components.MisulGroup
import dev.phonecode.app.ui.components.MisulSearchField
import dev.phonecode.app.ui.components.MisulSectionLabel
import dev.phonecode.app.ui.theme.Spacing
import dev.phonecode.tools.mcp.McpServerConfig

internal enum class PluginCategory(val label: String) {
    DOCS("Docs & knowledge"),
    DEVELOPER("Developer"),
    PAYMENTS("Payments"),
}

/** How a catalog plugin authenticates. Only methods PhoneCode can complete today are listed. */
internal sealed interface PluginAuth {
    data object None : PluginAuth

    /** A user-supplied token sent as `Authorization: Bearer <token>`. */
    data class BearerToken(val label: String, val help: String) : PluginAuth
}

/**
 * A remote MCP service with a documented Streamable HTTP endpoint. Every entry here is
 * installable; the real handshake still runs before a plugin can be added.
 */
internal data class PluginCatalogEntry(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val publisher: String,
    val category: PluginCategory,
    val auth: PluginAuth = PluginAuth.None,
)

internal val availablePlugins = listOf(
    PluginCatalogEntry(
        id = "github",
        name = "GitHub",
        description = "Issues, pull requests, code search and repository files",
        url = "https://api.githubcopilot.com/mcp/",
        publisher = "github.com",
        category = PluginCategory.DEVELOPER,
        auth = PluginAuth.BearerToken(
            label = "Personal access token",
            help = "Create a fine-grained token at github.com/settings/tokens with only the repositories and permissions the agent needs.",
        ),
    ),
    PluginCatalogEntry(
        id = "deepwiki",
        name = "DeepWiki",
        description = "Ask questions about any public GitHub repository · No sign-in",
        url = "https://mcp.deepwiki.com/mcp",
        publisher = "deepwiki.com",
        category = PluginCategory.DOCS,
    ),
    PluginCatalogEntry(
        id = "microsoft-learn",
        name = "Microsoft Learn",
        description = "Search official Microsoft and Azure documentation · No sign-in",
        url = "https://learn.microsoft.com/api/mcp",
        publisher = "learn.microsoft.com",
        category = PluginCategory.DOCS,
    ),
    PluginCatalogEntry(
        id = "aws-knowledge",
        name = "AWS Knowledge",
        description = "AWS documentation, API references and regional availability · No sign-in",
        url = "https://knowledge-mcp.global.api.aws",
        publisher = "aws.amazon.com",
        category = PluginCategory.DOCS,
    ),
    PluginCatalogEntry(
        id = "cloudflare-docs",
        name = "Cloudflare Docs",
        description = "Search Cloudflare developer documentation · No sign-in",
        url = "https://docs.mcp.cloudflare.com/mcp",
        publisher = "developers.cloudflare.com",
        category = PluginCategory.DOCS,
    ),
    PluginCatalogEntry(
        id = "cloudflare-agents-docs",
        name = "Cloudflare Agents docs",
        description = "Search official Cloudflare Agents SDK documentation · No sign-in",
        url = "https://agents.cloudflare.com/mcp",
        publisher = "agents.cloudflare.com",
        category = PluginCategory.DOCS,
    ),
    PluginCatalogEntry(
        id = "stripe",
        name = "Stripe",
        description = "Customers, payments, invoices and Stripe docs",
        url = "https://mcp.stripe.com",
        publisher = "stripe.com",
        category = PluginCategory.PAYMENTS,
        auth = PluginAuth.BearerToken(
            label = "Restricted API key",
            help = "Create a restricted key in the Stripe Dashboard under Developers › API keys. Use test mode while trying it out.",
        ),
    ),
)

@Composable
internal fun PluginsPage(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onOpenPlugin: (String) -> Unit,
    onOpenServer: (String) -> Unit,
    onOpenAdvanced: () -> Unit,
) {
    val state by collectSettingsState(vm)
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val installedUrls = state.mcpServers.values.map { it.url.trimEnd('/') }.toSet()
    val directory = availablePlugins.filter { plugin ->
        plugin.url.trimEnd('/') !in installedUrls && plugin.name !in state.mcpServers &&
            (category == null || plugin.category.name == category) &&
            (query.isBlank() || plugin.name.contains(query, ignoreCase = true) ||
                plugin.description.contains(query, ignoreCase = true))
    }
    SettingsPageShell("Plugins", onBack) {
        MisulSearchField(query, { query = it }, "Search plugins")

        if (state.mcpServers.isNotEmpty() && query.isBlank()) {
            MisulSectionLabel("Added")
            MisulGroup {
                state.mcpServers.entries.toList().forEachIndexed { index, (name, server) ->
                    val snapshot = state.mcpSnapshots[name]
                    val catalog = availablePlugins.firstOrNull { it.url.trimEnd('/') == server.url.trimEnd('/') }
                    val (status, healthy) = when {
                        !server.enabled -> "Off" to null
                        name in state.mcpConnecting -> "Connecting…" to null
                        snapshot?.connected == true ->
                            "${snapshot.tools.size} ${if (snapshot.tools.size == 1) "tool" else "tools"}" to true
                        snapshot?.error?.isNotBlank() == true -> "Needs attention" to false
                        else -> "Not connected" to null
                    }
                    PluginRow(
                        name = name,
                        supporting = status,
                        iconKey = catalog?.id.orEmpty(),
                        showDivider = index != state.mcpServers.size - 1,
                        onClick = { onOpenServer(name) },
                        trailing = { StatusDot(healthy) },
                    )
                }
            }
        }

        MisulSectionLabel(if (query.isBlank()) "Discover" else "Results")
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = Spacing.s),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CategoryChip("All", category == null) { category = null }
            PluginCategory.entries.forEach { entry ->
                CategoryChip(entry.label, category == entry.name) { category = entry.name }
            }
        }
        if (directory.isEmpty()) {
            SettingsNote(
                if (query.isNotBlank()) "No plugins match “${query.trim()}”. Add it as a custom MCP server below."
                else "Everything in this category is already added.",
            )
        } else {
            MisulGroup {
                directory.forEachIndexed { index, plugin ->
                    PluginRow(
                        name = plugin.name,
                        supporting = plugin.description,
                        iconKey = plugin.id,
                        showDivider = index != directory.lastIndex,
                        onClick = { onOpenPlugin(plugin.id) },
                        trailing = { AddPill() },
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.l))
        MisulGroup {
            SettingsNavigationRow(
                label = "MCP servers",
                supportingText = "Add a custom server or manage connections",
                icon = Icons.Outlined.Dns,
                showDivider = false,
                onClick = onOpenAdvanced,
            )
        }
        SettingsNote("Every plugin runs a live connection check and shows its tools before it's added.")
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.heightIn(min = Spacing.touchTarget).clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) colors.background else colors.onSurface,
            modifier = Modifier.clip(ShapePill)
                .background(if (selected) colors.onBackground else colors.surfaceContainerLow)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun AddPill() {
    val colors = MaterialTheme.colorScheme
    Text(
        "Add",
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = colors.onSurface,
        modifier = Modifier.clip(ShapePill).background(colors.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** Green when connected, red when it needs attention, hidden when off or pending. */
@Composable
private fun StatusDot(healthy: Boolean?) {
    if (healthy == null) return
    Box(
        Modifier.size(8.dp).clip(ShapePill)
            .background(if (healthy) Color(0xFF34C759) else MaterialTheme.colorScheme.error),
    )
}

@Composable
private fun PluginRow(
    name: String,
    supporting: String,
    iconKey: String,
    showDivider: Boolean,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppIcon(iconKey, 44.dp)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = colors.onSurface)
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            trailing()
        }
        if (showDivider) {
            androidx.compose.material3.HorizontalDivider(
                Modifier.padding(start = 74.dp),
                color = colors.outlineVariant.copy(alpha = 0.52f),
            )
        }
    }
}

/** Hero shown at the top of a catalog plugin's page. */
@Composable
internal fun PluginHero(plugin: PluginCatalogEntry) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(top = Spacing.xs, bottom = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppIcon(plugin.id, 64.dp)
        Column(Modifier.weight(1f)) {
            Text(
                plugin.name,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            Text(plugin.publisher, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
    Text(plugin.description, style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
}

@Composable
internal fun PluginTemplateDestination(
    vm: ChatViewModel,
    id: String,
    onBack: () -> Unit,
) {
    val plugin = availablePlugins.firstOrNull { it.id == id }
    if (plugin == null) {
        SettingsPageShell("Plugin unavailable", onBack) {
            SettingsNote("This plugin is not in the current catalog.")
        }
        return
    }
    val state by collectSettingsState(vm)
    var dirty by rememberSaveable(id) { mutableStateOf(false) }
    DiscardChangesBackHandler(dirty = dirty, onDiscard = onBack) { requestBack ->
        McpServerPage(
            vm = vm,
            initialName = "",
            suggestedName = plugin.name,
            introduction = plugin.description,
            catalogEntry = plugin,
            initial = McpServerConfig(url = plugin.url, enabled = false),
            existingNames = state.mcpServers.keys,
            snapshot = null,
            onBack = requestBack,
            onDirtyChange = { dirty = it },
            onSaved = {
                dirty = false
                onBack()
            },
        )
    }
}
