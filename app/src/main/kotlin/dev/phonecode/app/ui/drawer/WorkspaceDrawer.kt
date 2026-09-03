package dev.phonecode.app.ui.drawer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.phonecode.app.R
import dev.phonecode.app.data.Project
import dev.phonecode.app.data.SessionMeta
import dev.phonecode.app.ui.components.MisulIconButton
import dev.phonecode.app.ui.components.StretchSyncedScrollChrome
import dev.phonecode.app.ui.components.pressFeedback
import dev.phonecode.app.ui.components.rememberContentOverscroll
import dev.phonecode.app.ui.components.shortContentVerticalOverscroll
import dev.phonecode.app.ui.theme.LocalMisulAccent
import dev.phonecode.app.ui.theme.PhoneEasings
import dev.phonecode.app.ui.theme.PhoneSprings
import dev.phonecode.app.ui.theme.ShapePill
import dev.phonecode.app.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatSessionDate(value: Long) =
    SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(value))

data class WorkspaceDrawerState(
    val projects: List<Project>,
    val sessions: List<SessionMeta>,
    val currentSessionId: String,
    val isRunning: Boolean,
    val activeSkillCount: Int,
    val mcpServerCount: Int,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WorkspaceDrawer(
    state: WorkspaceDrawerState,
    width: Dp,
    collapsed: Set<String>,
    onToggleProjectCollapse: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onNewChat: (String?) -> Unit,
    onCreateProject: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenMcp: () -> Unit,
    onSetSessionPinned: (String, Boolean) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onMoveSession: (String, String?) -> Unit,
    onSetSessionArchived: (String, Boolean) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameProject: (String, String) -> Unit,
    onDeleteProject: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val accent = LocalMisulAccent.current
    var query by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var chatMenu by remember { mutableStateOf<SessionMeta?>(null) }
    var projectMenu by remember { mutableStateOf<Project?>(null) }
    var renameChat by remember { mutableStateOf<SessionMeta?>(null) }
    var renameProject by remember { mutableStateOf<Project?>(null) }
    var deleteChat by remember { mutableStateOf<SessionMeta?>(null) }
    var deleteProject by remember { mutableStateOf<Project?>(null) }
    var archivedOpen by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val listScrolled by remember { derivedStateOf { listState.canScrollBackward } }
    val hasMoreBelow by remember { derivedStateOf { listState.canScrollForward } }
    val listCanScroll by remember { derivedStateOf { listState.canScrollBackward || listState.canScrollForward } }
    val blurChrome = listScrolled || hasMoreBelow
    val listOverscroll = rememberContentOverscroll()
    val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    fun closeSearch() {
        query = ""
        searchExpanded = false
        focusManager.clearFocus()
        keyboard?.hide()
    }

    BackHandler(enabled = searchExpanded) { closeSearch() }
    LaunchedEffect(searchExpanded) {
        if (searchExpanded) {
            searchFocus.requestFocus()
            keyboard?.show()
        }
    }

    val matchingProjects = remember(state.projects, state.sessions, query) {
        state.projects.filter { project ->
            query.isBlank() || project.name.contains(query, ignoreCase = true) ||
                state.sessions.any { it.projectId == project.id && it.title.contains(query, ignoreCase = true) }
        }
    }
    val filtered = remember(state.projects, state.sessions, query) {
        state.sessions.filter { session ->
            query.isBlank() || session.title.contains(query, ignoreCase = true) ||
                state.projects.any { it.id == session.projectId && it.name.contains(query, ignoreCase = true) }
        }
    }
    val pinned = remember(filtered) { filtered.filter { it.pinned && !it.archived && it.projectId == null } }
    val archived = remember(filtered) { filtered.filter { it.archived } }
    val archivedVisible = archivedOpen || query.isNotBlank()
    val byProject = remember(filtered) { filtered.filter { !it.archived && it.projectId != null }.groupBy { it.projectId } }
    val loose = remember(filtered) { filtered.filter { !it.pinned && !it.archived && it.projectId == null } }

    @Composable
    fun SessionItem(meta: SessionMeta, indent: androidx.compose.ui.unit.Dp) {
        ChatRow(
            meta = meta,
            active = meta.id == state.currentSessionId,
            running = meta.id == state.currentSessionId && state.isRunning,
            indent = indent,
            onClick = { onOpenSession(meta.id) },
            onMenu = { chatMenu = meta },
            menuExpanded = chatMenu?.id == meta.id,
            onDismissMenu = { chatMenu = null },
        ) {
            ChatOptionsMenu(
                meta = meta,
                projects = state.projects,
                onDismiss = { chatMenu = null },
                onPin = { onSetSessionPinned(meta.id, !meta.pinned) },
                onRequestRename = { renameChat = meta },
                onMove = { onMoveSession(meta.id, it) },
                onArchive = { onSetSessionArchived(meta.id, !meta.archived) },
                onDelete = { deleteChat = meta },
                lifecycleMutationsEnabled = meta.id != state.currentSessionId || !state.isRunning,
            )
        }
    }

    Box(
        Modifier.width(width).fillMaxSize().background(colors.background)
            .testTag("workspace-drawer")
            .semantics { paneTitle = "Navigation drawer" }
            .clipToBounds(),
    ) {
        StretchSyncedScrollChrome(
            modifier = Modifier.fillMaxSize(),
            showTop = blurChrome && listScrolled,
            showBottom = blurChrome && hasMoreBelow,
            topHeight = statusInset + 56.dp,
            bottomHeight = navigationInset + 132.dp,
        ) { _ ->
            Box(
                Modifier.fillMaxSize().shortContentVerticalOverscroll(
                    enabled = !listCanScroll,
                    effect = listOverscroll,
                ).background(colors.background),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        top = statusInset + 64.dp,
                        bottom = navigationInset + 132.dp,
                    ),
                    overscrollEffect = listOverscroll.takeIf { listCanScroll },
                    userScrollEnabled = listCanScroll,
                ) {
            item {
                Text("Projects", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp))
            }
            if (query.isNotBlank() && matchingProjects.isEmpty() && filtered.isEmpty()) {
                item(key = "search_empty") {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("No results for \"${query.take(40)}\"", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        TextButton(onClick = ::closeSearch, modifier = Modifier.heightIn(min = Spacing.touchTarget)) { Text("Clear search") }
                    }
                }
            }
            if (state.projects.isEmpty() && query.isBlank()) {
                item(key = "projects_empty") {
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onCreateProject)
                            .heightIn(min = Spacing.touchTarget)
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Outlined.Folder, null, tint = colors.secondary, modifier = Modifier.size(19.dp))
                        Text("Link a folder", style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
                    }
                }
            }
            matchingProjects.forEach { project ->
                // Search is a temporary reveal layer: matching chats remain reachable without
                // changing the user's saved project-collapse choice.
                val open = query.isNotBlank() || project.id !in collapsed
                item(key = "p_${project.id}") {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget).padding(end = 2.dp)
                            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val rotation by animateFloatAsState(if (open) 90f else 0f, PhoneSprings.standard, label = "chev")
                        MisulIconButton(
                            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = if (open) "Collapse ${project.name}" else "Expand ${project.name}",
                            onClick = { onToggleProjectCollapse(project.id) },
                            modifier = Modifier.graphicsLayer { rotationZ = rotation },
                        )
                        Row(
                            Modifier.weight(1f).clip(MaterialTheme.shapes.small)
                                .combinedClickable(
                                    onClick = { onToggleProjectCollapse(project.id) },
                                    onLongClick = { projectMenu = project },
                                )
                                .heightIn(min = Spacing.touchTarget).padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(11.dp),
                        ) {
                            Icon(Icons.Outlined.Folder, null, tint = colors.secondary, modifier = Modifier.size(21.dp))
                            Text(project.name, style = MaterialTheme.typography.titleSmall, color = colors.onBackground, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${byProject[project.id]?.size ?: 0}", style = MaterialTheme.typography.labelMedium, color = colors.tertiary)
                        }
                        Box {
                            MisulIconButton(Icons.Filled.MoreVert, "Project options", onClick = { projectMenu = project })
                            WorkspaceDrawerMenu(
                                expanded = projectMenu?.id == project.id,
                                onDismiss = { projectMenu = null },
                                modifier = Modifier.width(240.dp),
                            ) {
                                ProjectOptionsMenu(
                                    project = project,
                                    onDismiss = { projectMenu = null },
                                    onNewChat = {
                                        onNewChat(project.id)
                                    },
                                    onRequestRename = { renameProject = project },
                                    onDelete = { deleteProject = project },
                                )
                            }
                        }
                    }
                }
                if (open) {
                    val chats = byProject[project.id].orEmpty()
                    if (chats.isEmpty()) item(key = "pe_${project.id}") {
                        Text("No chats", style = MaterialTheme.typography.labelMedium, color = colors.tertiary, modifier = Modifier.padding(start = 40.dp, bottom = 8.dp, top = 2.dp))
                    }
                    chats.forEach { meta ->
                        item(key = "c_${meta.id}") {
                            SessionItem(meta, 40.dp)
                        }
                    }
                }
            }
            if (pinned.isNotEmpty()) {
                item(key = "h_pinned") { SectionHeader("Pinned") }
                pinned.forEach { meta ->
                    item(key = "pin_${meta.id}") {
                        SessionItem(meta, 12.dp)
                    }
                }
            }
            timeBuckets(loose).forEach { (label, chats) ->
                item(key = "h_$label") {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp))
                }
                chats.forEach { meta ->
                    item(key = "u_${meta.id}") {
                        SessionItem(meta, 12.dp)
                    }
                }
            }
            if (archived.isNotEmpty()) {
                item(key = "h_archived") {
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { archivedOpen = !archivedOpen }
                            .semantics { stateDescription = if (archivedVisible) "Expanded" else "Collapsed" }
                            .heightIn(min = Spacing.touchTarget).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val rotation by animateFloatAsState(if (archivedVisible) 90f else 0f, PhoneSprings.standard, label = "arch")
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.tertiary, modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = rotation })
                        Text("Archived", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text("${archived.size}", style = MaterialTheme.typography.labelMedium, color = colors.tertiary)
                    }
                }
                if (archivedVisible) archived.forEach { meta ->
                    item(key = "a_${meta.id}") {
                        SessionItem(meta, 35.dp)
                    }
                }
            }
            item(key = "h_capabilities") { SectionHeader("Capabilities") }
            item(key = "skills") {
                DrawerDestination(
                    icon = Icons.Outlined.AutoAwesome,
                    label = "Skills",
                    value = state.activeSkillCount.toString(),
                    onClick = onOpenSkills,
                )
            }
            item(key = "mcp") {
                DrawerDestination(
                    icon = Icons.Outlined.Extension,
                    label = "MCP",
                    value = state.mcpServerCount.toString(),
                    onClick = onOpenMcp,
                )
            }
                }
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MisulIconButton(Icons.Outlined.Settings, "Settings", onClick = onOpenSettings)
            Spacer(Modifier.weight(1f))
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(colors.surfaceContainerHigh))
                MisulIconButton(Icons.Outlined.CreateNewFolder, "New project", onClick = onCreateProject)
            }
            MisulIconButton(Icons.Outlined.Edit, "New chat", onClick = { onNewChat(null) }, filled = true)
        }

        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .height(statusInset + 56.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = statusInset).height(56.dp)
                    .padding(start = 18.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_misul_mark),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(24.dp),
                )
                Box(Modifier.weight(1f).height(40.dp), contentAlignment = Alignment.CenterStart) {
                    DrawerTitleSearch(searchExpanded, query, { query = it }, searchFocus)
                }
                val searchInteraction = remember { MutableInteractionSource() }
                Box(
                    Modifier.size(48.dp).pressFeedback(searchInteraction, pressedScale = 0.96f)
                        .clip(CircleShape)
                        .clickable(interactionSource = searchInteraction, indication = ripple()) {
                            if (searchExpanded) closeSearch() else searchExpanded = true
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (searchExpanded) Icons.Filled.Close else Icons.Outlined.Search,
                        if (searchExpanded) "Close search" else "Search chats and projects",
                        tint = colors.onBackground,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
        }
    }

    renameChat?.let { meta ->
        DrawerRenameDialog("Rename chat", "Chat title", meta.title, { renameChat = null }) {
            onRenameSession(meta.id, it); renameChat = null
        }
    }
    renameProject?.let { project ->
        DrawerRenameDialog("Rename project", "Project name", project.name, { renameProject = null }) {
            onRenameProject(project.id, it); renameProject = null
        }
    }
    deleteChat?.let { meta ->
        ConfirmDrawerDeleteDialog(
            title = "Delete chat?",
            detail = "${meta.title} will be permanently removed from this device. This cannot be undone.",
            onDismiss = { deleteChat = null },
        ) {
            onDeleteSession(meta.id)
            deleteChat = null
        }
    }
    deleteProject?.let { project ->
        ConfirmDrawerDeleteDialog(
            title = "Delete project?",
            detail = "The project link will be removed and its chats moved to Unsorted. Workspace files stay under Recovered projects. The linked phone folder is not deleted.",
            onDismiss = { deleteProject = null },
        ) {
            onDeleteProject(project.id)
            deleteProject = null
        }
    }
}

@Composable
private fun DrawerTitleSearch(
    expanded: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
        AnimatedVisibility(
            visible = !expanded,
            enter = fadeIn(tween(150, easing = PhoneEasings.easeOut)),
            exit = fadeOut(tween(100, easing = PhoneEasings.easeOut)),
        ) {
            Text(
                "Misul Agent",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onBackground,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandHorizontally(expandFrom = Alignment.End, animationSpec = tween(220, easing = PhoneEasings.easeInOut)) +
                fadeIn(tween(160, easing = PhoneEasings.easeOut)),
            exit = shrinkHorizontally(shrinkTowards = Alignment.End, animationSpec = tween(170, easing = PhoneEasings.easeInOut)) +
                fadeOut(tween(110, easing = PhoneEasings.easeOut)),
        ) {
            Row(
                Modifier.fillMaxSize().clip(ShapePill).background(colors.surfaceContainerHigh)
                    .padding(start = 12.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, tint = colors.secondary, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f).padding(start = 8.dp)) {
                    if (query.isEmpty()) Text("Search", style = MaterialTheme.typography.bodySmall, color = colors.secondary)
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onBackground),
                        cursorBrush = SolidColor(colors.primary),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                            .semantics { contentDescription = "Search chats and projects" },
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerDestination(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget).clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.onBackground, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelMedium, color = colors.tertiary)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.tertiary, modifier = Modifier.size(18.dp))
    }
}

/** Recency buckets for the loose chat list: Today / Yesterday / Previous 7 days / Earlier. */
private fun timeBuckets(sessions: List<SessionMeta>): List<Pair<String, List<SessionMeta>>> {
    val now = System.currentTimeMillis()
    val day = 86_400_000L
    val labels = listOf("Today", "Yesterday", "Previous 7 days", "Earlier")
    fun idx(t: Long): Int = when {
        now - t < day -> 0
        now - t < 2 * day -> 1
        now - t < 7 * day -> 2
        else -> 3
    }
    return labels.indices.mapNotNull { i ->
        val chats = sessions.filter { idx(it.updatedAt) == i }
        if (chats.isEmpty()) null else labels[i] to chats
    }
}

/** A drawer list-section label (Pinned / Today / ...) in the shared quiet-caption style. */
@Composable
private fun SectionHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    meta: SessionMeta,
    active: Boolean,
    running: Boolean,
    indent: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    menuContent: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(MaterialTheme.shapes.medium)
            .background(if (active) colors.surfaceContainerHigh else androidx.compose.ui.graphics.Color.Transparent)
            .semantics { selected = active }
            .heightIn(min = 50.dp).padding(start = indent, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Two lines: title + a one-line preview. Selection is a quiet tone pill (grok-design.md).
        Row(
            Modifier.weight(1f).clip(MaterialTheme.shapes.small)
                .combinedClickable(onClick = onClick, onLongClick = onMenu)
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    meta.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onBackground,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (meta.preview.isNotEmpty()) {
                    Text(
                        meta.preview,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.tertiary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            Text(
                if (running) "Running" else formatSessionDate(meta.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = if (running) LocalMisulAccent.current else colors.tertiary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        // Three-dot overflow: pin / move / archive / delete (also reachable via long-press).
        Box(
            contentAlignment = Alignment.Center,
        ) {
            MisulIconButton(Icons.Filled.MoreVert, "Chat options", onClick = onMenu)
            WorkspaceDrawerMenu(
                expanded = menuExpanded,
                onDismiss = onDismissMenu,
                modifier = Modifier.width(280.dp),
                content = menuContent,
            )
        }
    }
}
