package dev.phonecode.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.phonecode.app.agent.ChatViewModel
import dev.phonecode.app.agent.ChatUiState
import dev.phonecode.app.R
import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.app.data.Project
import dev.phonecode.app.data.SessionMeta
import dev.phonecode.app.data.SkillStatus
import dev.phonecode.app.data.ThemeMode
import dev.phonecode.app.ui.chat.ChatScreen
import dev.phonecode.app.ui.onboarding.ModelSetupScreen
import dev.phonecode.app.ui.onboarding.OnboardingScreen
import dev.phonecode.app.ui.components.PcIconButton
import dev.phonecode.app.ui.components.PcButton
import dev.phonecode.app.ui.components.PcField
import dev.phonecode.app.ui.components.MorphingMenu
import dev.phonecode.app.ui.components.contentVerticalScroll
import dev.phonecode.app.ui.components.pressFeedback
import dev.phonecode.app.ui.components.rememberContentOverscroll
import dev.phonecode.app.ui.components.rememberPredictiveBackMotion
import dev.phonecode.app.ui.components.shortContentVerticalOverscroll
import androidx.compose.material3.ripple
import dev.phonecode.app.ui.settings.SettingsScreen
import dev.phonecode.app.ui.theme.PhoneCodeTheme
import dev.phonecode.app.ui.theme.PhoneEasings
import dev.phonecode.app.ui.theme.PhoneSprings
import dev.phonecode.app.ui.theme.ShapePill
import dev.phonecode.app.ui.theme.Spacing
import dev.phonecode.app.ui.theme.phoneHaze
import dev.phonecode.app.ui.theme.phoneHazeEffect
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private fun formatSessionDate(value: Long) = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(value))
private enum class DrawerValue { CLOSED, OPEN }

private fun ChatUiState.shellSnapshot(): ChatUiState = copy(
    lines = emptyList(),
    streaming = "",
    streamingReasoning = "",
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
    val application = LocalContext.current.applicationContext as PhoneCodeApplication
    val vm = application.chatViewModel
    val settingsVm: SettingsViewModel = viewModel()
    val settings by settingsVm.settings.collectAsState()
    val settingsLoaded by settingsVm.loaded.collectAsState()
    val chatState by remember(vm) {
        vm.state.map(ChatUiState::shellSnapshot).distinctUntilChanged()
    }.collectAsState(initial = ChatUiState().shellSnapshot())
    // First-run overlay up: hide everything behind it from accessibility so TalkBack can't reach
    // the chat/settings controls under the modal.
    val needsOnboarding = settingsLoaded && !settings.onboarded

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

        val navController = rememberNavController()
        val navEntry by navController.currentBackStackEntryAsState()
        val route = navEntry?.destination?.route ?: "chat"
        val showOnboarding = needsOnboarding && route != "onboarding-settings" && route != "model-setup"
        val focusManager = LocalFocusManager.current
        var settingsInitial by rememberSaveable { mutableStateOf("home") }
        var onboardingStep by rememberSaveable { mutableIntStateOf(0) }

        val density = LocalDensity.current
        val windowInfo = LocalWindowInfo.current
        val screenWidth = with(density) { windowInfo.containerSize.width.toDp() }
        // Keep phone proportions while avoiding a giant sheet on tablets and unfolded devices.
        val drawerWidth = minOf(screenWidth * 0.82f, 400.dp)
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
        // Keep composition out of the continuous drag path. This boolean changes only when the
        // drawer crosses the visible boundary; offset/progress are read later by graphicsLayer.
        var drawerVisible by remember { mutableStateOf(false) }
        LaunchedEffect(drawerState) {
            snapshotFlow { (drawerState.offset.takeUnless(Float::isNaN) ?: 0f) > 0.5f }
                .collect { visible -> drawerVisible = visible }
        }
        val drawerScope = rememberCoroutineScope()
        val openDrawer: () -> Unit = {
            drawerScope.launch { drawerState.animateTo(DrawerValue.OPEN, PhoneSprings.drawer) }
            Unit
        }
        val closeDrawer: () -> Unit = {
            drawerScope.launch { drawerState.animateTo(DrawerValue.CLOSED, PhoneSprings.drawer) }
            Unit
        }
        val navigateFromDrawer: (String) -> Unit = { destination ->
            closeDrawer()
            navController.navigate(destination) { launchSingleTop = true }
        }
        val projectPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                vm.createProject(uri)
            }
        }
        val openProjectPickerFromDrawer: () -> Unit = {
            closeDrawer()
            projectPicker.launch(null)
            Unit
        }
        LaunchedEffect(drawerState.targetValue) {
            if (drawerState.targetValue == DrawerValue.OPEN) focusManager.clearFocus()
        }

        val drawerBackMotion = rememberPredictiveBackMotion(enabled = drawerVisible) {
            drawerState.animateTo(DrawerValue.CLOSED, snap())
        }

        Box(
            Modifier.fillMaxSize().background(colors.background)
                .anchoredDraggable(
                    state = drawerState,
                    orientation = Orientation.Horizontal,
                    enabled = settingsLoaded && !showOnboarding && route == "chat",
                    flingBehavior = drawerFling,
                ),
        ) {
            // ----- main pane: stays put; the drawer overlays it (Grok/ChatGPT pattern - the old
            // push-back scale read as "disabled", not depth; see revamp-diagnosis.md #8) -----
            Box(
                Modifier.fillMaxSize()
                    .then(if (!settingsLoaded || showOnboarding || drawerVisible) Modifier.clearAndSetSemantics {} else Modifier),
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
                            val drawerOffset = drawerState.offset.takeUnless(Float::isNaN) ?: 0f
                            val drawerProgress = (drawerOffset / drawerWidthPx).coerceIn(0f, 1f)
                            val progress = drawerProgress * (1f - drawerBackMotion.progress.value)
                            if (progress > 0f) {
                                val ds = 1f - 0.06f * progress
                                scaleX = ds; scaleY = ds
                            }
                        },
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = "chat",
                        modifier = Modifier.fillMaxSize(),
                        enterTransition = {
                            slideInHorizontally(tween(240, easing = PhoneEasings.easeInOut)) { it }
                        },
                        exitTransition = { androidx.compose.animation.ExitTransition.None },
                        popEnterTransition = { androidx.compose.animation.EnterTransition.None },
                        popExitTransition = {
                            slideOutHorizontally(tween(180, easing = PhoneEasings.easeInOut)) { it }
                        },
                    ) {
                        composable("chat") {
                            ChatScreen(
                                vm = vm,
                                onOpenDrawer = openDrawer,
                                onOpenModelSetup = {
                                    navController.navigate("model-setup") { launchSingleTop = true }
                                },
                                onOpenProviderSetup = { providerId ->
                                    settingsInitial = "provider:$providerId"
                                    navController.navigate("settings") { launchSingleTop = true }
                                },
                                sendOnEnter = settings.sendOnEnter,
                            )
                        }
                        composable("settings") {
                            SettingsScreen(vm, settingsVm, onBack = { navController.popBackStack() }, initialPage = settingsInitial)
                        }
                        composable("skills") {
                            SettingsScreen(vm, settingsVm, onBack = { navController.popBackStack() }, initialPage = "skills")
                        }
                        composable("mcp") {
                            SettingsScreen(vm, settingsVm, onBack = { navController.popBackStack() }, initialPage = "mcp")
                        }
                        composable("model-setup") {
                            ModelSetupScreen(
                                vm = vm,
                                onBack = { navController.popBackStack() },
                                onConfigured = { navController.popBackStack() },
                            )
                        }
                        composable(
                            route = "onboarding-settings",
                            enterTransition = { androidx.compose.animation.EnterTransition.None },
                        ) {
                            SettingsScreen(vm, settingsVm, onBack = { navController.popBackStack() }, initialPage = settingsInitial)
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
                        Modifier.fillMaxSize().graphicsLayer {
                            val drawerOffset = drawerState.offset.takeUnless(Float::isNaN) ?: 0f
                            val drawerProgress = (drawerOffset / drawerWidthPx).coerceIn(0f, 1f)
                            val progress = drawerProgress * (1f - drawerBackMotion.progress.value)
                            alpha = (0.5f * progress).coerceIn(0f, 1f)
                        }.background(colors.scrim)
                            .semantics { contentDescription = "Close navigation drawer" }
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { closeDrawer() },
                    )
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            val drawerOffset = drawerState.offset.takeUnless(Float::isNaN) ?: 0f
                            val drawerProgress = (drawerOffset / drawerWidthPx).coerceIn(0f, 1f)
                            val progress = drawerProgress * (1f - drawerBackMotion.progress.value)
                            translationX = -drawerWidthPx * (1f - progress)
                        },
                    ) {
                        Sidebar(
                            vm = vm,
                            width = drawerWidth,
                            collapsed = collapsedProjects,
                            onToggleCollapse = { id ->
                                collapsedProjects = if (id in collapsedProjects) collapsedProjects - id else collapsedProjects + id
                            },
                            onOpenChat = closeDrawer,
                            onNewProject = openProjectPickerFromDrawer,
                            onOpenSettings = { settingsInitial = "home"; navigateFromDrawer("settings") },
                            onOpenSkills = { navigateFromDrawer("skills") },
                            onOpenMcp = { navigateFromDrawer("mcp") },
                        )
                    }
                }
            }

            // ----- first-run onboarding (covers everything until dismissed) -----
            androidx.compose.animation.AnimatedVisibility(
                visible = showOnboarding,
                enter = androidx.compose.animation.EnterTransition.None,
                exit = slideOutHorizontally(tween(220, easing = PhoneEasings.easeOut)) { -it / 4 } +
                    fadeOut(tween(160, easing = PhoneEasings.easeOut)),
            ) {
                OnboardingScreen(
                    step = onboardingStep,
                    onStepChange = { onboardingStep = it },
                    onConnectModels = {
                        navController.navigate("model-setup") { launchSingleTop = true }
                    },
                    onConnectGitHub = {
                        settingsInitial = "git"
                        navController.navigate("onboarding-settings") { launchSingleTop = true }
                    },
                    onCreateProject = {
                        projectPicker.launch(null)
                    },
                    modelReady = vm.hasConfiguredProvider(),
                    githubReady = chatState.githubLogin != null,
                    projectReady = chatState.projects.any { project ->
                        project.folderId != null && chatState.sharedFolders.any { it.id == project.folderId }
                    },
                    onDone = {
                        if (vm.activateConfiguredModel()) settingsVm.update { it.copy(onboarded = true) }
                    },
                    onSkip = {
                        settingsVm.update { it.copy(onboarded = true) }
                    },
                )
            }
            // Settings load asynchronously. Keep the real navigation tree covered and inert until
            // we know whether this is a first launch, avoiding a one-frame flash of the chat.
            if (!settingsLoaded) {
                Box(
                    Modifier.fillMaxSize().background(colors.background)
                        .clearAndSetSemantics { contentDescription = "Loading PhoneCode" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_phonecode_mark),
                        contentDescription = null,
                        tint = colors.onBackground,
                        modifier = Modifier.size(48.dp),
                    )
                }
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
    onOpenSkills: () -> Unit,
    onOpenMcp: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val state by remember(vm) {
        vm.state.map(ChatUiState::shellSnapshot).distinctUntilChanged()
    }.collectAsState(initial = ChatUiState().shellSnapshot())
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
    val hazeState = remember { HazeState() }
    val hazeStyle = phoneHaze()
    val listScrolled by remember { derivedStateOf { listState.canScrollBackward } }
    val listCanScroll by remember { derivedStateOf { listState.canScrollBackward || listState.canScrollForward } }
    val blurChrome = listCanScroll && !searchExpanded
    val listOverscroll = rememberContentOverscroll()

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
    val byProject = remember(filtered) { filtered.filter { !it.archived && it.projectId != null }.groupBy { it.projectId } }
    val loose = remember(filtered) { filtered.filter { !it.pinned && !it.archived && it.projectId == null } }

    @Composable
    fun SessionItem(meta: SessionMeta, indent: androidx.compose.ui.unit.Dp) {
        ChatRow(
            meta = meta,
            active = meta.id == state.currentSessionId,
            running = meta.id == state.currentSessionId && state.isRunning,
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
                onDelete = { deleteChat = meta },
            )
        }
    }

    Box(
        Modifier.width(width).fillMaxSize().background(colors.background)
            .semantics { paneTitle = "Navigation drawer" }
            .windowInsetsPadding(WindowInsets.systemBars).clipToBounds(),
    ) {
        Box(
            Modifier.fillMaxSize().shortContentVerticalOverscroll(
                enabled = !listCanScroll,
                effect = listOverscroll,
            ).background(colors.background),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
                    .then(if (blurChrome) Modifier.hazeSource(hazeState) else Modifier)
                    .padding(horizontal = 10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 112.dp, bottom = 132.dp),
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
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onNewProject)
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
                val open = project.id !in collapsed
                item(key = "p_${project.id}") {
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
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
                        PcIconButton(
                            icon = Icons.Filled.Add,
                            contentDescription = "New chat in ${project.name}",
                            tint = colors.secondary,
                        ) { vm.newChat(project.id); onOpenChat() }
                        Box {
                            PcIconButton(Icons.Filled.MoreVert, "Project options", tint = colors.secondary) { projectMenu = project }
                            MorphingMenu(
                                expanded = projectMenu?.id == project.id,
                                onDismiss = { projectMenu = null },
                                above = false,
                                alignEnd = true,
                                anchorSize = 48.dp,
                                modifier = Modifier.width(240.dp),
                            ) {
                                ProjectOptionsMenu(
                                    project = project,
                                    onDismiss = { projectMenu = null },
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
                            .semantics { stateDescription = if (archivedOpen) "Expanded" else "Collapsed" }
                            .heightIn(min = Spacing.touchTarget).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
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
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .shadow(2.dp, RectangleShape, clip = false)
                .then(if (blurChrome) Modifier.phoneHazeEffect(hazeState, hazeStyle) else Modifier)
                .background(if (blurChrome) colors.background.copy(alpha = 0.35f) else colors.background)
                .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            val settingsInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(colors.surfaceContainerHigh)
                    .pressFeedback(settingsInteraction, pressedScale = 0.96f)
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
                        .pressFeedback(newProjectInteraction, pressedScale = 0.96f)
                        .clickable(interactionSource = newProjectInteraction, indication = ripple(), onClick = onNewProject),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.CreateNewFolder, "New project", tint = colors.onBackground, modifier = Modifier.size(21.dp))
                }
                val newChatInteraction = remember { MutableInteractionSource() }
                Box(
                    Modifier.size(60.dp).clip(CircleShape).background(colors.primary)
                        .pressFeedback(newChatInteraction, pressedScale = 0.96f)
                        .clickable(interactionSource = newChatInteraction, indication = ripple()) { vm.newChat(null); onOpenChat() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Edit, "New chat", tint = colors.onPrimary, modifier = Modifier.size(25.dp))
                }
            }
        }

        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .shadow(if (listScrolled) 2.dp else 0.dp, RectangleShape, clip = false)
                .then(if (blurChrome) Modifier.phoneHazeEffect(hazeState, hazeStyle) else Modifier)
                .background(if (blurChrome) colors.background.copy(alpha = 0.35f) else colors.background),
        ) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(start = 18.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_phonecode_mark),
                    contentDescription = null,
                    tint = colors.onBackground,
                    modifier = Modifier.size(24.dp),
                )
                Box(Modifier.weight(1f).height(40.dp), contentAlignment = Alignment.CenterStart) {
                    SidebarTitleSearch(searchExpanded, query, { query = it }, searchFocus)
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
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SidebarDestination(
                    icon = Icons.Outlined.AutoAwesome,
                    label = "Skills",
                    value = state.skills.count { it.status == SkillStatus.ACTIVE }.toString(),
                    onClick = onOpenSkills,
                    modifier = Modifier.weight(1f),
                )
                SidebarDestination(
                    icon = Icons.Outlined.Extension,
                    label = "MCP",
                    value = state.mcpServers.size.toString(),
                    onClick = onOpenMcp,
                    modifier = Modifier.weight(1f),
                )
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
    deleteChat?.let { meta ->
        ConfirmDeleteDialog(
            title = "Delete chat?",
            detail = "${meta.title} will be removed from this device.",
            onDismiss = { deleteChat = null },
        ) {
            vm.deleteSession(meta.id)
            deleteChat = null
        }
    }
    deleteProject?.let { project ->
        ConfirmDeleteDialog(
            title = "Delete project?",
            detail = "The project link will be removed and its chats moved to Unsorted. Workspace files stay under Recovered projects. The linked phone folder is not deleted.",
            onDismiss = { deleteProject = null },
        ) {
            vm.deleteProject(project.id)
            deleteProject = null
        }
    }
}

@Composable
private fun SidebarTitleSearch(
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
                "PhoneCode",
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
private fun SidebarDestination(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.heightIn(min = 48.dp).clip(MaterialTheme.shapes.large)
            .background(colors.surfaceContainerHigh.copy(alpha = 0.72f)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.onBackground, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelMedium, color = colors.tertiary)
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
        Text(if (running) "Running" else formatSessionDate(meta.updatedAt), style = MaterialTheme.typography.labelSmall, color = if (running) colors.primary else colors.tertiary, modifier = Modifier.padding(start = 8.dp))
        // Three-dot overflow: pin / move / archive / delete (also reachable via long-press).
        Box(
            contentAlignment = Alignment.Center,
        ) {
            PcIconButton(Icons.Filled.MoreVert, "Chat options", tint = colors.secondary, onClick = onMenu)
            MorphingMenu(
                expanded = menuExpanded,
                onDismiss = onDismissMenu,
                above = false,
                alignEnd = true,
                anchorSize = 48.dp,
                modifier = Modifier.width(280.dp),
                content = menuContent,
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun OptionsCard(
    title: String,
    onDismiss: () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val maxHeight = with(density) {
        (LocalWindowInfo.current.containerSize.height.toDp() - 32.dp)
            .coerceAtLeast(Spacing.touchTarget * 3f)
    }
    val dialogScroll = rememberScrollState()
    // Material dialog container (publish-plan N2): platform shape/tonal elevation conventions.
    androidx.compose.material3.BasicAlertDialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight)
                .windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom)),
            shape = MaterialTheme.shapes.extraLarge,
            color = colors.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(Spacing.m)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onBackground,
                    modifier = Modifier.padding(bottom = Spacing.s),
                )
                Column(
                    Modifier.fillMaxWidth().weight(1f, fill = false)
                        .contentVerticalScroll(dialogScroll),
                    content = body,
                )
                Spacer(Modifier.height(Spacing.m))
                Column(Modifier.fillMaxWidth(), content = actions)
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
    val density = LocalDensity.current
    val maxMenuHeight = with(density) {
        (LocalWindowInfo.current.containerSize.height.toDp() - 16.dp)
            .coerceAtLeast(Spacing.touchTarget * 2f)
    }
    Column(Modifier.fillMaxWidth().heightIn(max = maxMenuHeight).padding(6.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 42.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mode == "move") {
                PcIconButton(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    "Back",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.graphicsLayer { rotationZ = 180f },
                ) { mode = "menu" }
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
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                item(key = "unsorted") {
                    MenuActionRow("Unsorted", Icons.Outlined.Inbox) { onMove(null); onDismiss() }
                }
                items(projects, key = { "project:${it.id}" }) { project ->
                    MenuActionRow(project.name, Icons.Outlined.Folder) {
                        onMove(project.id)
                        onDismiss()
                    }
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                MenuActionRow(if (meta.pinned) "Unpin" else "Pin", Icons.Outlined.PushPin) { onPin(); onDismiss() }
                MenuActionRow("Rename", Icons.Outlined.Edit) { onDismiss(); onRequestRename() }
                MenuActionRow("Move to…", Icons.Outlined.Folder) { mode = "move" }
                MenuActionRow(if (meta.archived) "Unarchive" else "Archive", Icons.Outlined.Archive) { onArchive(); onDismiss() }
                MenuActionRow("Delete", Icons.Outlined.DeleteOutline, destructive = true) { onDelete(); onDismiss() }
            }
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
private fun ConfirmDeleteDialog(
    title: String,
    detail: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    OptionsCard(
        title = title,
        onDismiss = onDismiss,
        body = {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Box(Modifier.weight(1f)) { PcButton("Cancel", filled = false, onClick = onDismiss) }
            Box(Modifier.weight(1f)) { PcButton("Delete", destructive = true, onClick = onConfirm) }
        }
    }
}

@Composable
private fun TextPromptDialog(title: String, placeholder: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    OptionsCard(
        title = title,
        onDismiss = onDismiss,
        body = {
            PcField(value, { value = it }, placeholder)
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Box(Modifier.weight(1f)) { PcButton("Cancel", filled = false, onClick = onDismiss) }
            Box(Modifier.weight(1f)) { PcButton("Save") { if (value.isNotBlank()) onConfirm(value.trim()) } }
        }
    }
}
