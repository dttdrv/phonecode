package dev.phonecode.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import dev.phonecode.app.agent.ChatViewModel
import dev.phonecode.app.R
import dev.phonecode.app.data.Project
import dev.phonecode.app.data.SessionMeta
import dev.phonecode.app.data.ThemeMode
import dev.phonecode.app.ui.chat.ChatScreen
import dev.phonecode.app.ui.onboarding.OnboardingScreen
import dev.phonecode.app.ui.components.PcButton
import dev.phonecode.app.ui.components.PcField
import dev.phonecode.app.ui.components.MorphingMenu
import dev.phonecode.app.ui.components.elasticOverscroll
import dev.phonecode.app.ui.components.pressFeedback
import androidx.compose.material3.ripple
import dev.phonecode.app.ui.settings.SettingsScreen
import dev.phonecode.app.ui.theme.PhoneCodeTheme
import dev.phonecode.app.ui.theme.PhoneEasings
import dev.phonecode.app.ui.theme.PhoneSprings
import dev.phonecode.app.ui.theme.ShapePill
import dev.phonecode.app.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val WHEN = SimpleDateFormat("d MMM", Locale.getDefault())
private enum class DrawerValue { CLOSED, OPEN }

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Root: theme-mode-aware shell with the push-back sidebar drawer (mockup): the main pane shifts,
 * scales, rounds and dims while the drawer slides over it; the drawer hosts search, projects/chats,
 * and the Settings gear.
 */
@Composable
fun PhoneCodeApp() {
    val vm: ChatViewModel = viewModel()
    val settingsVm: SettingsViewModel = viewModel()
    val settings by settingsVm.settings.collectAsState()
    val settingsLoaded by settingsVm.loaded.collectAsState()
    // First-run overlay up: hide everything behind it from accessibility so TalkBack can't reach
    // the chat/settings controls under the modal.
    val showOnboarding = settingsLoaded && !settings.onboarded

    val dark = when (settings.mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    PhoneCodeTheme(darkTheme = dark) {
        val colors = MaterialTheme.colorScheme

        // System bar icons follow the APP theme (not just the device theme): dark icons on the
        // white theme, light icons on AMOLED black - this is what makes the bars feel native.
        // TRUE transparency is re-asserted on every theme apply: OEM skins and config changes
        // love resetting bar colors/scrims, which read as "the navbar transparency flag is off"
        // (device feedback). No system scrims anywhere - legibility comes from our blur bands.
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                view.context.findActivity()?.window?.let { window ->
                    val controller = WindowCompat.getInsetsController(window, view)
                    controller.isAppearanceLightStatusBars = !dark
                    controller.isAppearanceLightNavigationBars = !dark
                    // Re-assert transparent bars unconditionally. targetSdk is 34, so on Android 15 (API 35+)
                    // setStatusBarColor is STILL honored and the system paints a backward-compat scrim behind
                    // the bars unless we clear it - the old `SDK_INT < 35` guard disabled exactly the fix those
                    // devices need, leaving a black band under the status bar. Becomes a harmless no-op only if
                    // targetSdk is ever raised to 35 (where edge-to-edge transparency is enforced anyway).
                    @Suppress("DEPRECATION")
                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = android.graphics.Color.TRANSPARENT
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        window.isStatusBarContrastEnforced = false
                        window.isNavigationBarContrastEnforced = false
                    }
                }
            }
        }

        var route by rememberSaveable { mutableStateOf("chat") }
        val focusManager = LocalFocusManager.current
        var settingsInitial by rememberSaveable { mutableStateOf("home") }

        val screenWidth = LocalConfiguration.current.screenWidthDp.dp
        val density = LocalDensity.current
        val drawerWidth = screenWidth * 0.82f
        val drawerWidthPx = with(density) { drawerWidth.toPx() }
        val drawerState = remember {
            AnchoredDraggableState(DrawerValue.CLOSED)
        }
        val drawerAnchors = remember(drawerWidthPx) {
            DraggableAnchors {
                DrawerValue.CLOSED at 0f
                DrawerValue.OPEN at drawerWidthPx
            }
        }
        SideEffect { drawerState.updateAnchors(drawerAnchors) }
        val drawerFling = AnchoredDraggableDefaults.flingBehavior(
            state = drawerState,
            positionalThreshold = { it * 0.35f },
            animationSpec = PhoneSprings.drawer,
        )
        val drawerOffset = drawerState.offset.takeUnless(Float::isNaN) ?: 0f
        val progress = (drawerOffset / drawerWidthPx).coerceIn(0f, 1f)
        val drawerVisible = progress > 0.001f || drawerState.targetValue == DrawerValue.OPEN
        val drawerScope = rememberCoroutineScope()
        val openDrawer: () -> Unit = {
            drawerScope.launch { drawerState.animateTo(DrawerValue.OPEN, PhoneSprings.drawer) }
            Unit
        }
        val closeDrawer: () -> Unit = {
            drawerScope.launch { drawerState.animateTo(DrawerValue.CLOSED, PhoneSprings.drawer) }
            Unit
        }
        var projectPickFromOnboarding by remember { mutableStateOf(false) }
        val projectPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                vm.createProject(uri)
                if (projectPickFromOnboarding) settingsVm.update { it.copy(onboarded = true) }
            }
            projectPickFromOnboarding = false
        }
        LaunchedEffect(drawerState.targetValue) {
            if (drawerState.targetValue == DrawerValue.OPEN) focusManager.clearFocus()
        }

        BackHandler(enabled = drawerVisible) { closeDrawer() }
        BackHandler(enabled = !drawerVisible && route != "chat") { route = "chat" }

        Box(
            Modifier.fillMaxSize().background(colors.background)
                .anchoredDraggable(
                    state = drawerState,
                    orientation = Orientation.Horizontal,
                    enabled = !showOnboarding && route == "chat",
                    flingBehavior = drawerFling,
                ),
        ) {
            // ----- main pane: stays put; the drawer overlays it (Grok/ChatGPT pattern - the old
            // push-back scale read as "disabled", not depth; see revamp-diagnosis.md #8) -----
            Box(
                Modifier.fillMaxSize()
                    .then(if (showOnboarding) Modifier.clearAndSetSemantics {} else Modifier),
            ) {
                // Only HORIZONTAL insets at the root: BOTH vertical edges stay unpadded so the
                // conversation slides under the status bar AND the nav bar, frosting through the
                // blur bands (device feedback: the navbar was solid). Screens own their vertical
                // insets - the chat's bottom overlay and settings pages pad with safeDrawing's
                // bottom (the UNION of ime+navbar, so the keyboard never double-pads).
                Box(
                    Modifier.fillMaxSize().background(colors.background)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        // Drawer open: swallow the IME inset so focusing the sidebar search field
                        // can't push the chat composer (behind the drawer) up with the keyboard.
                        .then(if (drawerVisible) Modifier.consumeWindowInsets(WindowInsets.ime) else Modifier)
                        .graphicsLayer {
                            // Drawer open: the main pane settles back (the push-back depth cue)
                            // while the sidebar overlays it.
                            if (progress > 0f) {
                                val ds = 1f - 0.06f * progress
                                scaleX = ds; scaleY = ds
                            }
                        },
                ) {
                    AnimatedContent(
                        targetState = route,
                        transitionSpec = {
                            val pop = targetState == "chat"
                            (if (pop) {
                                (slideInHorizontally(tween(260, easing = PhoneEasings.iOSStandard)) { -it / 4 }) togetherWith
                                    slideOutHorizontally(tween(220, easing = PhoneEasings.iOSStandard)) { it }
                            } else {
                                (slideInHorizontally(tween(260, easing = PhoneEasings.iOSStandard)) { it }) togetherWith
                                    slideOutHorizontally(tween(220, easing = PhoneEasings.iOSStandard)) { -it / 4 }
                            }).apply { targetContentZIndex = if (pop) -1f else 1f }
                        },
                        label = "route",
                    ) { r ->
                        Box {
                            when (r) {
                                "settings" -> SettingsScreen(vm, settingsVm, onBack = { route = "chat" }, initialPage = settingsInitial)
                                else -> ChatScreen(vm, onOpenDrawer = openDrawer, sendOnEnter = settings.sendOnEnter)
                            }
                        }
                    }
                }
            }

            // ----- dim over the pushed-back main -----
            var collapsedProjects by remember { mutableStateOf(setOf<String>()) }
            if (drawerVisible) {
                Box(
                    Modifier.fillMaxSize(),
                ) {
                    Box(
                        Modifier.fillMaxSize().graphicsLayer { alpha = (0.5f * progress).coerceIn(0f, 1f) }.background(colors.scrim)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { closeDrawer() },
                    )
                    Box(
                        Modifier.fillMaxSize().graphicsLayer { translationX = -drawerWidthPx * (1f - progress) },
                    ) {
                        Sidebar(
                            vm = vm,
                            width = drawerWidth,
                            collapsed = collapsedProjects,
                            onToggleCollapse = { id ->
                                collapsedProjects = if (id in collapsedProjects) collapsedProjects - id else collapsedProjects + id
                            },
                            onOpenChat = { closeDrawer(); route = "chat" },
                            onNewProject = {
                                closeDrawer()
                                projectPicker.launch(null)
                            },
                            onOpenSettings = { closeDrawer(); settingsInitial = "home"; route = "settings" },
                        )
                    }
                }
            }

            // ----- first-run onboarding (covers everything until dismissed) -----
            androidx.compose.animation.AnimatedVisibility(
                visible = showOnboarding,
                enter = androidx.compose.animation.EnterTransition.None,
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(280)),
            ) {
                OnboardingScreen(
                    onConnectModels = {
                        settingsVm.update { it.copy(onboarded = true) }
                        settingsInitial = "providers"; route = "settings"
                    },
                    onConnectGitHub = {
                        settingsVm.update { it.copy(onboarded = true) }
                        settingsInitial = "git"; route = "settings"
                    },
                    onCreateProject = {
                        projectPickFromOnboarding = true
                        projectPicker.launch(null)
                    },
                    onDone = { settingsVm.update { it.copy(onboarded = true) } },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Sidebar(
    vm: ChatViewModel,
    width: androidx.compose.ui.unit.Dp,
    collapsed: Set<String>,
    onToggleCollapse: (String) -> Unit,
    onOpenChat: () -> Unit,
    onNewProject: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val state by vm.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var chatMenu by remember { mutableStateOf<SessionMeta?>(null) }
    var projectMenu by remember { mutableStateOf<Project?>(null) }
    var renameChat by remember { mutableStateOf<SessionMeta?>(null) }
    var renameProject by remember { mutableStateOf<Project?>(null) }
    var archivedOpen by remember { mutableStateOf(false) }

    val matchingProjects = state.projects.filter { project ->
        query.isBlank() || project.name.contains(query, ignoreCase = true) ||
            state.sessions.any { it.projectId == project.id && it.title.contains(query, ignoreCase = true) }
    }
    val filtered = state.sessions.filter { session ->
        query.isBlank() || session.title.contains(query, ignoreCase = true) ||
            state.projects.any { it.id == session.projectId && it.name.contains(query, ignoreCase = true) }
    }
    val pinned = filtered.filter { it.pinned && !it.archived && it.projectId == null }
    val archived = filtered.filter { it.archived }
    val byProject = filtered.filter { !it.archived && it.projectId != null }.groupBy { it.projectId }
    val loose = filtered.filter { !it.pinned && !it.archived && it.projectId == null }

    @Composable
    fun SessionItem(meta: SessionMeta, indent: androidx.compose.ui.unit.Dp) {
        ChatRow(
            meta = meta,
            active = meta.id == state.currentSessionId,
            indent = indent,
            onClick = { vm.switchSession(meta.id); onOpenChat() },
            onMenu = { chatMenu = meta },
            menuExpanded = chatMenu?.id == meta.id,
            onDismissMenu = { chatMenu = null },
        ) {
            ChatOptionsMenu(
                meta = meta,
                projects = state.projects,
                onDismiss = { chatMenu = null },
                onPin = { vm.setSessionPinned(meta.id, !meta.pinned) },
                onRequestRename = { renameChat = meta },
                onMove = { vm.moveSession(meta.id, it) },
                onArchive = { vm.setSessionArchived(meta.id, !meta.archived) },
                onDelete = { vm.deleteSession(meta.id) },
            )
        }
    }

    Column(
        // One tone above the (scrimmed) canvas behind it - the drawer separates by material.
        Modifier.width(width).fillMaxSize().background(colors.surfaceContainerLow).windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_phonecode_mark),
                contentDescription = null,
                tint = colors.onBackground,
                modifier = Modifier.size(24.dp),
            )
            Text("PhoneCode", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = colors.onBackground)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.xs).height(42.dp)
                .clip(ShapePill).background(colors.surfaceContainerHigh),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, null, tint = colors.secondary, modifier = Modifier.padding(start = 13.dp).size(18.dp))
            Box(Modifier.weight(1f).padding(horizontal = 9.dp)) {
                if (query.isEmpty()) Text("Search", style = MaterialTheme.typography.bodySmall, color = colors.secondary)
                BasicTextField(
                    value = query, onValueChange = { query = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onBackground),
                    cursorBrush = SolidColor(colors.primary), singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Box(Modifier.weight(1f).clipToBounds()) {
            Column(Modifier.fillMaxSize().elasticOverscroll()) {
                LazyColumn(Modifier.weight(1f).padding(horizontal = 10.dp), overscrollEffect = null) {
            item {
                Text("Projects", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp))
            }
            if (state.projects.isEmpty() && query.isBlank()) {
                item(key = "projects_empty") {
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onNewProject)
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
                val open = project.id !in collapsed
                item(key = "p_${project.id}") {
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                            .combinedClickable(
                                onClick = { onToggleCollapse(project.id) },
                                onLongClick = { projectMenu = project },
                            )
                            .heightIn(min = Spacing.touchTarget).padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp),
                    ) {
                        val rotation by animateFloatAsState(if (open) 90f else 0f, PhoneSprings.standard, label = "chev")
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.tertiary,
                            modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rotation },
                        )
                        Icon(Icons.Outlined.Folder, null, tint = colors.secondary, modifier = Modifier.size(19.dp))
                        Text(project.name, style = MaterialTheme.typography.titleSmall, color = colors.onBackground, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${byProject[project.id]?.size ?: 0}", style = MaterialTheme.typography.labelMedium, color = colors.tertiary)
                        Box(
                            Modifier.size(34.dp).clip(MaterialTheme.shapes.extraSmall)
                                .clickable { vm.newChat(project.id); onOpenChat() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Add, "New chat in ${project.name}", tint = colors.secondary, modifier = Modifier.size(16.dp))
                        }
                        Box {
                            val menuInteraction = remember { MutableInteractionSource() }
                            Box(
                                Modifier.size(34.dp).pressFeedback(menuInteraction, pressedScale = 0.86f)
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .clickable(interactionSource = menuInteraction, indication = ripple(bounded = false, radius = 18.dp)) { projectMenu = project },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.MoreVert, "Project options", tint = colors.secondary, modifier = Modifier.size(18.dp))
                            }
                            MorphingMenu(
                                expanded = projectMenu?.id == project.id,
                                onDismiss = { projectMenu = null },
                                above = false,
                                alignEnd = true,
                                anchorSize = 34.dp,
                                modifier = Modifier.width(240.dp),
                            ) {
                                ProjectOptionsMenu(
                                    project = project,
                                    onDismiss = { projectMenu = null },
                                    onRequestRename = { renameProject = project },
                                    onDelete = { vm.deleteProject(project.id) },
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
                            .heightIn(min = 40.dp).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val rotation by animateFloatAsState(if (archivedOpen) 90f else 0f, PhoneSprings.standard, label = "arch")
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.tertiary, modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = rotation })
                        Text("Archived", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text("${archived.size}", style = MaterialTheme.typography.labelMedium, color = colors.tertiary)
                    }
                }
                if (archivedOpen) archived.forEach { meta ->
                    item(key = "a_${meta.id}") {
                        SessionItem(meta, 35.dp)
                    }
                }
            }
        }

                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
            val settingsInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(colors.surfaceContainerHigh)
                    .pressFeedback(settingsInteraction, pressedScale = 0.92f)
                    .clickable(interactionSource = settingsInteraction, indication = ripple(), onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Settings, "Settings", tint = colors.onBackground, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val newProjectInteraction = remember { MutableInteractionSource() }
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(colors.surfaceContainerHigh)
                        .pressFeedback(newProjectInteraction, pressedScale = 0.92f)
                        .clickable(interactionSource = newProjectInteraction, indication = ripple(), onClick = onNewProject),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.CreateNewFolder, "New project", tint = colors.onBackground, modifier = Modifier.size(21.dp))
                }
                val newChatInteraction = remember { MutableInteractionSource() }
                Box(
                    Modifier.size(60.dp).clip(CircleShape).background(colors.primary)
                        .pressFeedback(newChatInteraction, pressedScale = 0.92f)
                        .clickable(interactionSource = newChatInteraction, indication = ripple()) { vm.newChat(null); onOpenChat() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Edit, "New chat", tint = colors.onPrimary, modifier = Modifier.size(25.dp))
                }
            }
                }
            }
        }
    }

    renameChat?.let { meta ->
        TextPromptDialog("Rename chat", "Chat title", meta.title, { renameChat = null }) {
            vm.renameSession(meta.id, it); renameChat = null
        }
    }
    renameProject?.let { project ->
        TextPromptDialog("Rename project", "Project name", project.name, { renameProject = null }) {
            vm.renameProject(project.id, it); renameProject = null
        }
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
            .combinedClickable(onClick = onClick, onLongClick = onMenu)
            .heightIn(min = 50.dp).padding(start = indent, end = 2.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Two lines: title + a one-line preview. Selection is a quiet tone pill (grok-design.md).
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
        Text(WHEN.format(Date(meta.updatedAt)), style = MaterialTheme.typography.labelSmall, color = colors.tertiary, modifier = Modifier.padding(start = 8.dp))
        // Three-dot overflow: pin / move / archive / delete (also reachable via long-press).
        val menuInteraction = remember { MutableInteractionSource() }
        Box(
            Modifier.size(32.dp).pressFeedback(menuInteraction, pressedScale = 0.85f).clip(MaterialTheme.shapes.extraSmall)
                .clickable(interactionSource = menuInteraction, indication = ripple(bounded = false, radius = 18.dp), onClick = onMenu),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.MoreVert, "Chat options", tint = colors.secondary, modifier = Modifier.size(18.dp))
            MorphingMenu(
                expanded = menuExpanded,
                onDismiss = onDismissMenu,
                above = false,
                alignEnd = true,
                anchorSize = 32.dp,
                modifier = Modifier.width(280.dp),
                content = menuContent,
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun OptionsCard(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // Material dialog container (publish-plan N2): platform shape/tonal elevation conventions.
    androidx.compose.material3.BasicAlertDialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = colors.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(Spacing.m)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = Spacing.s))
                content()
            }
        }
    }
}

@Composable
private fun MenuActionRow(label: String, icon: ImageVector, destructive: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .pressFeedback(interaction, pressedScale = 0.97f)
            .clickable(interactionSource = interaction, indication = ripple(), onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(icon, null, tint = if (destructive) colors.error else colors.secondary, modifier = Modifier.size(19.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = if (destructive) colors.error else colors.onBackground,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChatOptionsMenu(
    meta: SessionMeta,
    projects: List<Project>,
    onDismiss: () -> Unit,
    onPin: () -> Unit,
    onRequestRename: () -> Unit,
    onMove: (String?) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var mode by remember { mutableStateOf("menu") }
    Column(Modifier.fillMaxWidth().padding(6.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 42.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mode == "move") {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    "Back",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = 180f }.clickable { mode = "menu" },
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                if (mode == "move") "Move to" else meta.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (mode == "move") {
            MenuActionRow("Unsorted", Icons.Outlined.Inbox) { onMove(null); onDismiss() }
            projects.forEach { p -> MenuActionRow(p.name, Icons.Outlined.Folder) { onMove(p.id); onDismiss() } }
        } else {
            MenuActionRow(if (meta.pinned) "Unpin" else "Pin", Icons.Outlined.PushPin) { onPin(); onDismiss() }
            MenuActionRow("Rename", Icons.Outlined.Edit) { onDismiss(); onRequestRename() }
            MenuActionRow("Move to…", Icons.Outlined.Folder) { mode = "move" }
            MenuActionRow(if (meta.archived) "Unarchive" else "Archive", Icons.Outlined.Archive) { onArchive(); onDismiss() }
            MenuActionRow("Delete", Icons.Outlined.DeleteOutline, destructive = true) { onDelete(); onDismiss() }
        }
    }
}

@Composable
private fun ProjectOptionsMenu(project: Project, onDismiss: () -> Unit, onRequestRename: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(6.dp)) {
        Text(
            project.name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
        )
        MenuActionRow("Rename", Icons.Outlined.Edit) { onDismiss(); onRequestRename() }
        MenuActionRow("Delete project", Icons.Outlined.DeleteOutline, destructive = true) { onDelete(); onDismiss() }
    }
}

@Composable
private fun TextPromptDialog(title: String, placeholder: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    OptionsCard(title, onDismiss) {
        PcField(value, { value = it }, placeholder)
        Spacer(Modifier.height(Spacing.s))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Box(Modifier.weight(1f)) { PcButton("Cancel", filled = false, onClick = onDismiss) }
            Box(Modifier.weight(1f)) { PcButton("Save") { if (value.isNotBlank()) onConfirm(value.trim()) } }
        }
    }
}
