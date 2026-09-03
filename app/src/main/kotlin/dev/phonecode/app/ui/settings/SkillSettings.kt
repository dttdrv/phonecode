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
import dev.phonecode.app.ui.components.MisulField
import dev.phonecode.app.ui.components.MisulGroup
import dev.phonecode.app.ui.components.MisulIconButton
import dev.phonecode.app.ui.components.ActionRole
import dev.phonecode.app.ui.components.MisulActionButton
import dev.phonecode.app.ui.components.MisulContentRow
import dev.phonecode.app.ui.components.MisulSectionLabel
import dev.phonecode.app.ui.components.MisulFilter
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
internal fun SkillsPage(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onOpenSkill: (String) -> Unit,
    onNewSkill: () -> Unit,
) {
    val state by collectSettingsState(vm)
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(SkillFilter.ALL) }
    val colors = MaterialTheme.colorScheme
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
    SettingsPageShell(
        "Skills",
        onBack,
        action = {
            MisulIconButton(
                Icons.Filled.Add,
                "New skill",
                onClick = onNewSkill,
                filled = true,
            )
        },
    ) {
        val active = state.skills.count { it.status == SkillStatus.ACTIVE }
        val issues = state.skills.count { it.status == SkillStatus.INVALID }
        if (state.skills.isEmpty()) {
            MisulSectionLabel("Your skills")
            Text(
                "No skills yet",
                style = MaterialTheme.typography.titleMedium,
                color = colors.onBackground,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = Spacing.xs),
            )
            SettingsNote("Create one to give the agent a reusable workflow or set of instructions.")
            Spacer(Modifier.height(Spacing.s))
            MisulActionButton(
                "Create skill",
                onClick = onNewSkill,
                role = ActionRole.PRIMARY,
                icon = Icons.Filled.Add,
            )
        } else {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Text(
                    "$active active · ${state.skills.size} discovered",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (issues > 0) {
                    Text(
                        "$issues skill${if (issues == 1) "" else "s"} ${if (issues == 1) "needs" else "need"} attention",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                    )
                }
            }
            if (state.skills.size >= 6 || query.isNotBlank()) {
                Spacer(Modifier.height(Spacing.s))
                MisulSearchField(query, { query = it }, "Search skills")
            }
            if (state.skills.size > 1) {
                SkillFilters(filter) { filter = it }
            }
            MisulSectionLabel(filter.label())
            when {
                filtered.isNotEmpty() -> MisulGroup {
                    filtered.forEachIndexed { index, skill ->
                        MisulContentRow(onClick = {
                            onOpenSkill(skill.id)
                        }, showDivider = index != filtered.lastIndex) {
                            Column(Modifier.weight(1f)) {
                                Text(skill.name, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
                                Text(
                                    "${skill.scope.label()} · ${skill.status.label()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (skill.status == SkillStatus.INVALID) colors.error else colors.onSurfaceVariant,
                                )
                                skill.issue?.takeIf { it.isNotBlank() }?.let { issue ->
                                    SettingsErrorText(
                                        issue,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                                skill.manifest?.description?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                null,
                                tint = colors.tertiary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                query.isNotBlank() -> {
                    SettingsNote("No skills match “${query.trim()}”.")
                    Spacer(Modifier.height(Spacing.xs))
                    MisulActionButton("Clear search", onClick = { query = "" }, role = ActionRole.QUIET)
                }
                filter == SkillFilter.ISSUES -> {
                    SettingsNote("No skills need attention.")
                    Spacer(Modifier.height(Spacing.xs))
                    MisulActionButton("Show all skills", onClick = { filter = SkillFilter.ALL }, role = ActionRole.QUIET)
                }
                filter == SkillFilter.ACTIVE -> {
                    SettingsNote("No active skills.")
                    Spacer(Modifier.height(Spacing.xs))
                    MisulActionButton("Show all skills", onClick = { filter = SkillFilter.ALL }, role = ActionRole.QUIET)
                }
                else -> {
                    SettingsNote("No inactive or overridden skills.")
                    Spacer(Modifier.height(Spacing.xs))
                    MisulActionButton("Show all skills", onClick = { filter = SkillFilter.ALL }, role = ActionRole.QUIET)
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            SettingsNote("Skill files reload automatically. The agent can create and edit global or project skills with your permission.")
        }
    }
}

@Composable
internal fun SkillDetailPage(vm: ChatViewModel, skill: ManagedSkill, onEdit: () -> Unit, onBack: () -> Unit) {
    val state by collectSettingsState(vm)
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val manifest = skill.manifest
    var confirmDelete by rememberSaveable(skill.id) { mutableStateOf(false) }
    var toggling by remember(skill.id) { mutableStateOf(false) }
    var toggleError by remember(skill.id) { mutableStateOf<String?>(null) }
    val deleteOperationKey = skillDeleteOperationKey(skill.id)
    val deleteOperation = state.settingsOperations[deleteOperationKey]
    SettingsPageShell(
        skill.name,
        onBack = onBack,
        action = {
            MisulIconButton(
                Icons.Outlined.Edit,
                "Edit skill",
                enabled = !toggling && deleteOperation?.running != true,
                onClick = onEdit,
            )
        },
    ) {
        SettingsNote("${skill.scope.label()} · ${skill.status.label()}")
        skill.issue?.let { SettingsErrorText(it) }
        manifest?.description?.takeIf { it.isNotBlank() }?.let { SettingsNote(it) }
        if (manifest != null && skill.status != SkillStatus.SHADOWED && skill.status != SkillStatus.INVALID) {
            MisulGroup {
                SettingsToggleRow(
                    "Enabled",
                    sub = "Applies immediately to the current agent session",
                    checked = skill.status != SkillStatus.DISABLED,
                    enabled = !toggling && deleteOperation?.running != true,
                    showDivider = false,
                ) { target ->
                    vm.clearError()
                    toggleError = null
                    toggling = true
                    scope.launch {
                        vm.setSkillEnabledAndWait(skill.id, target).onFailure { failure ->
                            toggleError = "Could not update ${skill.name}: ${
                                failure.message ?: "storage update failed"
                            }"
                        }
                        toggling = false
                    }
                }
            }
            when {
                toggling -> SettingsNote("Updating…", announce = true)
                toggleError != null -> SettingsErrorText(requireNotNull(toggleError))
            }
        } else if (skill.status == SkillStatus.SHADOWED) {
            SettingsNote("Another skill with this name takes precedence. Disable or edit the active copy to use this one.")
        }
        manifest?.body?.takeIf { it.isNotBlank() }?.let { instructions ->
            MisulSectionLabel("Instructions")
            Box(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                    .background(colors.surface).padding(Spacing.m),
            ) {
                MarkdownBlocks(instructions)
            }
            SettingsNote("The agent can edit this skill with permission. Changes reload into this session automatically.")
        }
        MisulSectionLabel("Details")
        MisulGroup {
            if (!manifest?.compatibility.isNullOrBlank()) MisulContentRow(
                showDivider = !manifest.license.isNullOrBlank() || skill.location.isNotBlank(),
            ) {
                Column {
                    Text("Compatibility", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    Text(manifest.compatibility, style = MaterialTheme.typography.bodyMedium, color = colors.onBackground, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (!manifest?.license.isNullOrBlank()) MisulContentRow(
                showDivider = skill.location.isNotBlank(),
            ) {
                Text("License", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Text(manifest.license, style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
            }
            if (skill.location.isNotBlank()) MisulContentRow(showDivider = false) {
                Column {
                    Text("Location", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    Text(skill.location, style = MaterialTheme.typography.bodySmall, color = colors.onBackground, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        MisulSectionLabel("Danger zone")
        MisulGroup {
            MisulContentRow(
                showDivider = false,
                onClick = if (!toggling && deleteOperation?.running != true) {
                    {
                        vm.clearSettingsOperation(deleteOperationKey)
                        confirmDelete = true
                    }
                } else {
                    null
                },
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Delete skill", style = MaterialTheme.typography.bodyLarge, color = colors.error)
                    Text(
                        "Permanently remove this skill",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.error, modifier = Modifier.size(18.dp))
            }
        }
    }
    if (confirmDelete) {
        ConfirmActionDialog(
            title = "Delete skill?",
            message = "This permanently removes ${skill.name}. There is no built-in restore or undo.",
            action = "Delete skill",
            progressAction = "Deleting…",
            inProgress = deleteOperation?.running == true,
            inlineError = deleteOperation?.error?.let {
                "Could not delete ${skill.name}: $it"
            },
            onDismiss = {
                vm.clearSettingsOperation(deleteOperationKey)
                confirmDelete = false
            },
        ) {
            vm.clearError()
            vm.clearSettingsOperation(deleteOperationKey)
            scope.launch {
                vm.deleteSkillAndWait(skill.id).fold(
                    onSuccess = {
                        confirmDelete = false
                        withContext(Dispatchers.Main.immediate) { onBack() }
                    },
                    onFailure = {},
                )
            }
        }
    }
}

@Composable
internal fun SkillEditorPage(
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
    val initialDescription = skill?.manifest?.description.orEmpty()
    val initialInstructions = skill?.manifest?.body.orEmpty()
    val initialContent = remember(editorKey) {
        if (isNew) newSkillTemplate(initialName) else ""
    }
    val clipboard = LocalClipboardManager.current
    var name by rememberSaveable(editorKey) { mutableStateOf(initialName) }
    var skillScope by rememberSaveable(editorKey) { mutableStateOf(skill?.scope ?: SkillScope.GLOBAL) }
    var description by rememberSaveable(editorKey) { mutableStateOf(initialDescription) }
    var instructions by rememberSaveable(editorKey) { mutableStateOf(initialInstructions) }
    var advancedSource by rememberSaveable(editorKey) { mutableStateOf(false) }
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
                    val parsed = parseSkillMarkdown(latest)
                    baseline = latest
                    baselineRevision = latestRevision
                    content = latest
                    if (parsed != null) {
                        name = parsed.name
                        description = parsed.description
                        instructions = parsed.body
                    }
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

    fun updateStructured(
        nextName: String = name,
        nextDescription: String = description,
        nextInstructions: String = instructions,
    ) {
        name = nextName
        description = nextDescription
        instructions = nextInstructions
        content = structuredSkillMarkdown(content, nextName, nextDescription, nextInstructions)
        error = null
    }

    SettingsPageShell(if (isNew) "New skill" else "Edit $name", onBack) {
        if (isNew) {
            MisulSectionLabel("Identity")
            SettingsFieldLabel("Skill name")
            MisulField(
                name,
                { value ->
                    val next = value.lowercase().replace(Regex("[^a-z0-9-]"), "")
                    updateStructured(nextName = next)
                },
                "my-skill",
                contentDescription = "Skill name",
            )
            MisulGroup(Modifier.selectableGroup()) {
                SettingsSelectionRow("Global", skillScope == SkillScope.GLOBAL) { skillScope = SkillScope.GLOBAL }
                SettingsSelectionRow(
                    "Current project",
                    skillScope == SkillScope.PROJECT,
                    showDivider = false,
                ) { skillScope = SkillScope.PROJECT }
            }
        } else {
            SettingsNote("${skillScope.label()} · changes reload into the current session")
        }
        if (unavailable) {
            SettingsErrorText("This skill was removed or renamed. Your draft is preserved here.")
            if (content.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.xs))
                MisulActionButton(
                    "Copy draft",
                    onClick = { clipboard.setText(AnnotatedString(content)) },
                    role = ActionRole.QUIET,
                )
            }
        } else if (conflict) {
            SettingsErrorText("This skill changed elsewhere. Your draft is preserved; reopen the editor to load the latest file.")
        }
        if (advancedSource) {
            MisulSectionLabel("SKILL.md source")
            MisulField(
                content,
                { content = it; error = null },
                if (loading) "Loading…" else "Complete SKILL.md source",
                singleLine = false,
                minLines = 14,
                contentDescription = "Skill source",
            )
            SettingsNote("Advanced source includes frontmatter and instructions. Keep the name aligned with the skill folder.")
        } else {
            MisulSectionLabel("When to use")
            SettingsFieldLabel("When should the agent use this skill?")
            MisulField(
                description,
                { updateStructured(nextDescription = it) },
                "Describe the tasks or situations that should activate this skill",
                singleLine = false,
                minLines = 2,
                contentDescription = "When to use this skill",
            )
            MisulSectionLabel("Instructions")
            MisulField(
                instructions,
                { updateStructured(nextInstructions = it) },
                if (loading) "Loading…" else "Give the agent clear, actionable steps",
                singleLine = false,
                minLines = 10,
                contentDescription = "Skill instructions",
            )
        }
        MisulSectionLabel("Advanced")
        MisulGroup {
            SettingsToggleRow(
                "Advanced source",
                "Edit the complete SKILL.md file",
                checked = advancedSource,
                showDivider = false,
            ) { target ->
                if (target) {
                    advancedSource = true
                    error = null
                } else {
                    val parsed = parseSkillMarkdown(content)
                    if (parsed == null || parsed.name != name) {
                        error = "Fix the SKILL.md source before returning to the guided editor."
                    } else {
                        description = parsed.description
                        instructions = parsed.body
                        advancedSource = false
                        error = null
                    }
                }
            }
        }
        error?.let { SettingsErrorText(it, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(Spacing.s))
        val parsedDraft = parseSkillMarkdown(content)
        val draftIsValid = if (advancedSource) {
            parsedDraft != null &&
                parsedDraft.name == name &&
                parsedDraft.description.isNotBlank() &&
                parsedDraft.body.isNotBlank()
        } else {
            name.isNotBlank() && description.isNotBlank() && instructions.isNotBlank()
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            MisulActionButton(
                label = "Save",
                role = ActionRole.PRIMARY,
                loading = saving,
                enabled = !loading && !saving && !unavailable && !conflict && baselineReady && draftIsValid,
                onClick = {
                    val parsed = parseSkillMarkdown(content)
                    if (parsed == null || parsed.name != name || parsed.description.isBlank() || parsed.body.isBlank()) {
                        error = "Add a valid name, when-to-use description, and instructions before saving."
                        return@MisulActionButton
                    }
                    scope.launch {
                        saving = true
                        vm.saveSkillAndWait(skillId, skillScope, name, content, baseline.takeUnless { isNew }).fold(
                            onSuccess = { withContext(Dispatchers.Main.immediate) { onSaved() } },
                            onFailure = { error = it.message ?: "Skill could not be saved" },
                        )
                        saving = false
                    }
                },
            )
        }
    }
}

internal fun structuredSkillMarkdown(
    source: String,
    name: String,
    description: String,
    instructions: String,
): String {
    val normalized = source.replace("\r\n", "\n").trimStart()
    val frontmatter = Regex("^---\\s*\\n(.*?)\\n---", RegexOption.DOT_MATCHES_ALL)
        .find(normalized)
        ?.groupValues
        ?.get(1)
        ?.lines()
        ?.toMutableList()
        ?: mutableListOf("license: Apache-2.0")

    fun replaceOrAdd(key: String, value: String) {
        val index = frontmatter.indexOfFirst { it.matches(Regex("^${Regex.escape(key)}:\\s*.*$")) }
        val line = "$key: ${value.replace('\n', ' ').trim()}"
        if (index >= 0) frontmatter[index] = line else frontmatter.add(0, line)
    }

    replaceOrAdd("name", name)
    replaceOrAdd("description", description)
    return buildString {
        append("---\n")
        append(frontmatter.joinToString("\n"))
        append("\n---\n\n")
        append(instructions.trim())
    }
}

private fun newSkillTemplate(name: String): String =
    structuredSkillMarkdown(
        source = "---\nlicense: Apache-2.0\n---",
        name = name,
        description = "",
        instructions = "",
    )

private const val NEW_SKILL_ID = "__phonecode_new_skill__"

internal enum class SkillFilter { ALL, ACTIVE, OFF, ISSUES }

@Composable
private fun SkillFilters(selected: SkillFilter, onSelect: (SkillFilter) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = Spacing.s).selectableGroup()) {
        val wrap = maxWidth < 360.dp || LocalDensity.current.fontScale >= 1.3f
        if (wrap) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                maxItemsInEachRow = 2,
            ) {
                SkillFilter.entries.forEach { filter ->
                    SkillFilterButton(
                        filter = filter,
                        active = filter == selected,
                        onClick = { onSelect(filter) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SkillFilter.entries.forEach { filter ->
                    SkillFilterButton(
                        filter = filter,
                        active = filter == selected,
                        onClick = { onSelect(filter) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SkillFilterButton(
    filter: SkillFilter,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MisulFilter(
        label = filter.shortLabel(),
        selected = active,
        onClick = onClick,
        modifier = modifier,
    )
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
    SkillFilter.OFF -> "Inactive skills"
    SkillFilter.ISSUES -> "Needs attention"
}

private fun SkillFilter.shortLabel() = when (this) {
    SkillFilter.ALL -> "All"
    SkillFilter.ACTIVE -> "Active"
    SkillFilter.OFF -> "Inactive"
    SkillFilter.ISSUES -> "Issues"
}
