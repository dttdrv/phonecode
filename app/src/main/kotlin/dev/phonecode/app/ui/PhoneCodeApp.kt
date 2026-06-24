package dev.phonecode.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import dev.phonecode.app.agent.ChatViewModel
import dev.phonecode.app.data.Project
import dev.phonecode.app.data.SessionMeta
import dev.phonecode.app.data.ThemeMode
import dev.phonecode.app.git.GitViewModel
import dev.phonecode.app.ui.chat.ChatScreen
import dev.phonecode.app.ui.onboarding.OnboardingScreen
import dev.phonecode.app.ui.components.PcButton
import dev.phonecode.app.ui.components.PcField
import dev.phonecode.app.ui.settings.SettingsScreen
import dev.phonecode.app.ui.theme.PhoneCodeTheme
import dev.phonecode.app.ui.theme.PhoneSprings
import dev.phonecode.app.ui.theme.PhoneTweens
import dev.phonecode.app.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val WHEN = SimpleDateFormat("d MMM", Locale.getDefault())

/** Unwrap ContextWrappers (themes, Compose test hosts) to the owning Activity, if any. */
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
    val gitVm: GitViewModel = viewModel()
    val settingsVm: SettingsViewModel = viewModel()
    val settings by settingsVm.settings.collectAsState()

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
                    if (android.os.Build.VERSION.SDK_INT < 35) {
                        // Deprecated-on-35 (where edge-to-edge transparency is enforced anyway).
                        @Suppress("DEPRECATION")
                        window.statusBarColor = android.graphics.Color.TRANSPARENT
                        @Suppress("DEPRECATION")
                        window.navigationBarColor = android.graphics.Color.TRANSPARENT
                    }
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        window.isStatusBarContrastEnforced = false
                        window.isNavigationBarContrastEnforced = false
                    }
                }
            }
        }

        var route by rememberSaveable { mutableStateOf("chat") }
        var drawerOpen by rememberSaveable { mutableStateOf(false) }
        // Onboarding deep-links open settings on a specific sub-page; normal entry resets to home.
        var settingsInitial by rememberSaveable { mutableStateOf("home") }

        // The git UI follows the active chat's project workspace.
        val chatState by vm.state.collectAsState()
        LaunchedEffect(chatState.currentProjectId) { gitVm.setWorkspace(vm.activeWorkspace()) }

        val progress by animateFloatAsState(if (drawerOpen) 1f else 0f, PhoneSprings.drawer, label = "drawer")
        val screenWidth = LocalConfiguration.current.screenWidthDp.dp
        val density = LocalDensity.current
        val drawerWidth = screenWidth * 0.82f

        BackHandler(enabled = drawerOpen) { drawerOpen = false }
        // Predictive back for the settings route: the page follows the gesture and, on commit,
        // FINISHES its slide before the route flips - the old reset-then-reanimate handoff
        // visibly snapped (round-4: "the animation [is] kind of too cheap-like"). The transform
        // is keyed to the outgoing route inside AnimatedContent so it holds through the exit.
        var backingOutRoute by remember { mutableStateOf<String?>(null) }
        val backAnim = remember { androidx.compose.animation.core.Animatable(0f) }
        val backScope = androidx.compose.runtime.rememberCoroutineScope()
        androidx.activity.compose.PredictiveBackHandler(enabled = !drawerOpen && route != "chat") { events ->
            backingOutRoute = route
            try {
                events.collect { backAnim.snapTo(it.progress) }
                backAnim.animateTo(1f, androidx.compose.animation.core.tween(150))
                route = "chat"
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                // Settle back on a scope that survives the cancelled gesture coroutine.
                backScope.launch {
                    runCatching { backAnim.animateTo(0f, PhoneSprings.quick) }
                    backingOutRoute = null
                }
            }
        }
        LaunchedEffect(route) {
            if (backingOutRoute != null && route == "chat") {
                kotlinx.coroutines.delay(320)
                backAnim.snapTo(0f)
                backingOutRoute = null
            }
        }

        Box(Modifier.fillMaxSize().background(colors.background)) {
            // ----- main pane: stays put; the drawer overlays it (Grok/ChatGPT pattern - the old
            // push-back scale read as "disabled", not depth; see revamp-diagnosis.md #8) -----
            // Edge-swipe right opens the sidebar. INITIAL pass: child scrollables would otherwise
            // consume the events first and the gesture never fired on device (round-3 feedback).
            // Only gestures that BEGIN in the left 28dp strip are claimed.
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        if (down.position.x > 28.dp.toPx()) return@awaitEachGesture
                        var acc = 0f
                        while (true) {
                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            val drag = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!drag.pressed) break
                            acc += drag.positionChange().x
                            if (acc > 40.dp.toPx()) {
                                drawerOpen = true
                                drag.consume()
                                break
                            }
                        }
                    }
                },
            ) {
                // Only HORIZONTAL insets at the root: BOTH vertical edges stay unpadded so the
                // conversation slides under the status bar AND the nav bar, frosting through the
                // blur bands (device feedback: the navbar was solid). Screens own their vertical
                // insets - the chat's bottom overlay and settings pages pad with safeDrawing's
                // bottom (the UNION of ime+navbar, so the keyboard never double-pads).
                Box(
                    Modifier.fillMaxSize().background(colors.background)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
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
                            if (targetState == "chat") {
                                (slideInHorizontally(PhoneSprings.standardSpec()) { -it / 4 } + fadeIn(PhoneTweens.popEnter)) togetherWith
                                    (slideOutHorizontally(PhoneSprings.standardSpec()) { it / 4 } + fadeOut(PhoneTweens.popExit))
                            } else {
                                (slideInHorizontally(PhoneSprings.standardSpec()) { it / 4 } + fadeIn(PhoneTweens.popEnter)) togetherWith
                                    (slideOutHorizontally(PhoneSprings.standardSpec()) { -it / 4 } + fadeOut(PhoneTweens.popExit))
                            }
                        },
                        label = "route",
                    ) { r ->
                        Box(
                            Modifier.graphicsLayer {
                                // Predictive back: only the route being backed out carries the
                                // gesture transform, held through its exit so nothing snaps.
                                if (r == backingOutRoute) {
                                    val t = backAnim.value
                                    translationX = t * size.width * 0.4f
                                    alpha = 1f - t
                                    val s = 1f - 0.04f * t
                                    scaleX = s; scaleY = s
                                }
                            },
                        ) {
                            when (r) {
                                "settings" -> SettingsScreen(vm, gitVm, settingsVm, onBack = { route = "chat" }, initialPage = settingsInitial)
                                else -> ChatScreen(vm, gitVm, onOpenDrawer = { drawerOpen = true }, sendOnEnter = settings.sendOnEnter)
                            }
                        }
                    }
                }
            }

            // ----- dim over the pushed-back main -----
            if (progress > 0.01f) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer { alpha = (0.5f * progress).coerceIn(0f, 1f) }.background(colors.scrim)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { drawerOpen = false },
                )
            }

            // ----- drawer over the dim (composed only while visible/animating) -----
            // Collapse state lives HERE (not in Sidebar): the drawer subtree leaves composition
            // when closed, so any state inside it would reset on every open.
            var collapsedProjects by remember { mutableStateOf(setOf<String>()) }
            if (drawerOpen || progress > 0.001f) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        translationX = with(density) { (-drawerWidth).toPx() } * (1f - progress)
                    },
                ) {
                    Sidebar(
                        vm = vm,
                        width = drawerWidth,
                        collapsed = collapsedProjects,
                        onToggleCollapse = { id ->
                            collapsedProjects = if (id in collapsedProjects) collapsedProjects - id else collapsedProjects + id
                        },
                        onOpenChat = { drawerOpen = false; route = "chat" },
                        onOpenSettings = { drawerOpen = false; settingsInitial = "home"; route = "settings" },
                    )
                }
            }

            // ----- first-run onboarding (covers everything until dismissed) -----
            val settingsLoaded by settingsVm.loaded.collectAsState()
            androidx.compose.animation.AnimatedVisibility(
                visible = settingsLoaded && !settings.onboarded,
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
    onOpenSettings: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val state by vm.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var newProject by remember { mutableStateOf(false) }
    var chatMenu by remember { mutableStateOf<SessionMeta?>(null) }
    var projectMenu by remember { mutableStateOf<Project?>(null) }

    val byProject = state.sessions
        .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
        .groupBy { it.projectId }

    Column(
        // One tone above the (scrimmed) canvas behind it - the drawer separates by material.
        Modifier.width(width).fillMaxSize().background(colors.surfaceContainerLow).windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // search
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.xs).height(42.dp)
                .clip(MaterialTheme.shapes.small).background(colors.surfaceContainerHigh),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, tint = colors.secondary, modifier = Modifier.padding(start = 13.dp).size(18.dp))
            Box(Modifier.weight(1f).padding(horizontal = 9.dp)) {
                if (query.isEmpty()) Text("Search chats", style = MaterialTheme.typography.bodySmall, color = colors.secondary)
                BasicTextField(
                    value = query, onValueChange = { query = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onBackground),
                    cursorBrush = SolidColor(colors.primary), singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        LazyColumn(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Projects", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Box(Modifier.size(28.dp).clip(MaterialTheme.shapes.extraSmall).clickable { newProject = true }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Add, "New project", tint = colors.secondary, modifier = Modifier.size(17.dp))
                    }
                }
            }
            state.projects.forEach { project ->
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
                        Icon(Icons.Filled.Folder, null, tint = colors.secondary, modifier = Modifier.size(19.dp))
                        Text(project.name, style = MaterialTheme.typography.titleSmall, color = colors.onBackground, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${byProject[project.id]?.size ?: 0}", style = MaterialTheme.typography.labelMedium, color = colors.tertiary)
                        // New chat IN this project (mockup parity).
                        Box(
                            Modifier.size(34.dp).clip(MaterialTheme.shapes.extraSmall)
                                .clickable { vm.newChat(project.id); onOpenChat() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Add, "New chat in ${project.name}", tint = colors.secondary, modifier = Modifier.size(16.dp))
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
                            ChatRow(meta, active = meta.id == state.currentSessionId, indent = 40.dp,
                                onClick = { vm.switchSession(meta.id); onOpenChat() }, onLongClick = { chatMenu = meta })
                        }
                    }
                }
            }
            // A "Chats" header only earns its place when projects exist above it; otherwise the
            // list IS the chats and a second caps label just reads as wireframe noise.
            if (state.projects.isNotEmpty() && byProject[null].orEmpty().isNotEmpty()) {
                item { Text("Chats", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp)) }
            }
            byProject[null].orEmpty().forEach { meta ->
                item(key = "u_${meta.id}") {
                    ChatRow(meta, active = meta.id == state.currentSessionId, indent = 12.dp,
                        onClick = { vm.switchSession(meta.id); onOpenChat() }, onLongClick = { chatMenu = meta })
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp).clip(MaterialTheme.shapes.small)
                        .clickable { vm.newChat(null); onOpenChat() }.heightIn(min = Spacing.touchTarget).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.Add, null, tint = colors.secondary, modifier = Modifier.size(17.dp))
                    Text("New chat", style = MaterialTheme.typography.bodySmall, color = colors.secondary)
                }
            }
        }

        // Pinned bottom action, separated by a hairline so it reads as the drawer's footer rather
        // than a stray list item (round-3: "out of place and does not fit anything"). The row
        // itself speaks the app-wide menu language: bodyLarge label, quiet outlined icon, 48dp.
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant))
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp).fillMaxWidth()
                .clip(MaterialTheme.shapes.small).clickable(onClick = onOpenSettings)
                .heightIn(min = 48.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Settings, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Text("Settings", style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
        }
    }

    if (newProject) {
        TextPromptDialog(title = "New project", placeholder = "Project name", initial = "", onDismiss = { newProject = false }) {
            vm.createProject(it); newProject = false
        }
    }
    chatMenu?.let { meta ->
        ChatOptionsDialog(
            meta = meta,
            projects = state.projects,
            onDismiss = { chatMenu = null },
            onRename = { vm.renameSession(meta.id, it); chatMenu = null },
            onMove = { vm.moveSession(meta.id, it); chatMenu = null },
            onDelete = { vm.deleteSession(meta.id); chatMenu = null },
        )
    }
    projectMenu?.let { project ->
        ProjectOptionsDialog(
            project = project,
            onDismiss = { projectMenu = null },
            onRename = { vm.renameProject(project.id, it); projectMenu = null },
            onDelete = { vm.deleteProject(project.id); projectMenu = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(meta: SessionMeta, active: Boolean, indent: androidx.compose.ui.unit.Dp, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            .background(if (active) colors.surfaceContainerHigh else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 40.dp).padding(start = indent, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Selection = a quiet tone pill; tone is the highlight, no extra marks (grok-design.md).
        Text(
            meta.title,
            style = MaterialTheme.typography.bodySmall,
            color = if (active) colors.onBackground else colors.secondary,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(WHEN.format(Date(meta.updatedAt)), style = MaterialTheme.typography.labelSmall, color = colors.tertiary)
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
private fun OptionRow(text: String, destructive: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).heightIn(min = Spacing.touchTarget).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (destructive) colors.error else colors.onBackground)
    }
}

@Composable
private fun ChatOptionsDialog(
    meta: SessionMeta,
    projects: List<Project>,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onMove: (String?) -> Unit,
    onDelete: () -> Unit,
) {
    var mode by remember { mutableStateOf("menu") }
    when (mode) {
        "rename" -> TextPromptDialog("Rename chat", "Chat title", meta.title, onDismiss) { onRename(it) }
        "move" -> OptionsCard("Move to", onDismiss) {
            OptionRow("Unsorted") { onMove(null) }
            projects.forEach { p -> OptionRow(p.name) { onMove(p.id) } }
        }
        else -> OptionsCard(meta.title, onDismiss) {
            OptionRow("Rename") { mode = "rename" }
            OptionRow("Move to...") { mode = "move" }
            OptionRow("Delete", destructive = true, onClick = onDelete)
        }
    }
}

@Composable
private fun ProjectOptionsDialog(project: Project, onDismiss: () -> Unit, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var mode by remember { mutableStateOf("menu") }
    when (mode) {
        "rename" -> TextPromptDialog("Rename project", "Project name", project.name, onDismiss) { onRename(it) }
        else -> OptionsCard(project.name, onDismiss) {
            OptionRow("Rename") { mode = "rename" }
            OptionRow("Delete (chats become unsorted)", destructive = true, onClick = onDelete)
        }
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
