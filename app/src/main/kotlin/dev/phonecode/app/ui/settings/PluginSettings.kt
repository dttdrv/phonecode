package dev.phonecode.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.phonecode.app.agent.ChatViewModel
import dev.phonecode.app.ui.components.MisulGroup
import dev.phonecode.app.ui.components.MisulSectionLabel
import dev.phonecode.app.ui.components.MisulStatusRow
import dev.phonecode.tools.mcp.McpServerConfig

private const val CLOUDFLARE_DOCS_ID = "cloudflare-agents-docs"
private const val CLOUDFLARE_DOCS_NAME = "Cloudflare Agents docs"
private const val CLOUDFLARE_DOCS_URL = "https://agents.cloudflare.com/mcp"

/** Curated entries must have a verified native connection path before they become installable. */
internal data class PluginCatalogEntry(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
)

internal val availablePlugins = listOf(
    PluginCatalogEntry(
        id = CLOUDFLARE_DOCS_ID,
        name = CLOUDFLARE_DOCS_NAME,
        description = "Search official Cloudflare Agents SDK documentation · No sign-in",
        url = CLOUDFLARE_DOCS_URL,
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
    val discoverable = availablePlugins.filterNot { it.name in state.mcpServers }
    SettingsPageShell("Plugins", onBack) {
        SettingsNote("Connect services to give the agent more tools. Review each connection's reported tools before enabling it.")

        if (discoverable.isNotEmpty()) {
            MisulSectionLabel("Explore")
            MisulGroup {
                discoverable.forEachIndexed { index, plugin ->
                    SettingsNavigationRow(
                        label = plugin.name,
                        supportingText = plugin.description,
                        showDivider = index != discoverable.lastIndex,
                        onClick = { onOpenPlugin(plugin.id) },
                    )
                }
            }
        }

        if (state.mcpServers.isNotEmpty()) {
            MisulSectionLabel("Your plugins")
            MisulGroup {
                state.mcpServers.entries.toList().forEachIndexed { index, (name, server) ->
                    val snapshot = state.mcpSnapshots[name]
                    val status = when {
                        !server.enabled -> "Off"
                        name in state.mcpConnecting -> "Connecting"
                        snapshot?.connected == true -> "Connected · ${snapshot.tools.size} ${if (snapshot.tools.size == 1) "tool" else "tools"}"
                        snapshot?.error?.isNotBlank() == true -> "Needs attention"
                        else -> "Not connected"
                    }
                    SettingsNavigationRow(
                        label = name,
                        supportingText = status,
                        showDivider = index != state.mcpServers.size - 1,
                        onClick = { onOpenServer(name) },
                    )
                }
            }
        }

        MisulSectionLabel("Planned")
        MisulGroup {
            MisulStatusRow(
                label = "Cloudflare account",
                supportingText = "Account actions need scoped access and sign-in",
            )
            MisulStatusRow(
                label = "Vercel",
                supportingText = "Requires supported OAuth client access",
            )
            MisulStatusRow(
                label = "Gmail",
                supportingText = "Requires Google sign-in and reviewed mail scopes",
                showDivider = false,
            )
        }

        MisulSectionLabel("Advanced")
        MisulGroup {
            SettingsNavigationRow(
                label = "MCP servers",
                supportingText = "Add a custom server or manage connection details",
                showDivider = false,
                onClick = onOpenAdvanced,
            )
        }
    }
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
