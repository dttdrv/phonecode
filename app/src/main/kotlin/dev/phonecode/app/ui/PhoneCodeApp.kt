package dev.phonecode.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.layout.onSizeChanged
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
import dev.phonecode.app.agent.ChatUiState
import dev.phonecode.app.R
import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.app.data.SkillStatus
import dev.phonecode.app.data.ThemeMode
import dev.phonecode.app.ui.chat.ChatScreen
import dev.phonecode.app.ui.drawer.WorkspaceDrawer
import dev.phonecode.app.ui.drawer.WorkspaceDrawerState
import dev.phonecode.app.ui.onboarding.ModelSetupScreen
import dev.phonecode.app.ui.onboarding.OnboardingScreen
import dev.phonecode.app.ui.components.StretchSyncedScrollChrome
import dev.phonecode.app.ui.components.contentVerticalScroll
import dev.phonecode.app.ui.components.pressFeedback
import dev.phonecode.app.ui.components.rememberContentOverscroll
import dev.phonecode.app.ui.components.shortContentVerticalOverscroll
import dev.phonecode.app.ui.navigation.MisulNavigationMotion
import androidx.compose.material3.ripple
import dev.phonecode.app.ui.settings.SettingsScreen
import dev.phonecode.app.ui.theme.LocalMisulAccent
import dev.phonecode.app.ui.theme.PhoneCodeTheme
import dev.phonecode.app.ui.theme.PhoneEasings
import dev.phonecode.app.ui.theme.PhoneSprings
import dev.phonecode.app.ui.theme.ShapePill
import dev.phonecode.app.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal fun resolvedDrawerWidthPx(layoutWidthPx: Int, windowWidthPx: Int, density: Float): Float {
    val availableWidth = layoutWidthPx.takeIf { it > 0 }
        ?: windowWidthPx.takeIf { it > 0 }
        ?: (400f * density).toInt()
    return minOf(availableWidth * 0.82f, 400f * density).coerceAtLeast(1f)
}

private fun formatSessionDate(value: Long) = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(value))
internal fun renameSaveEnabled(initial: String, value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.isNotEmpty() && trimmed != initial.trim()
}

internal enum class AppNavigationMotion { HIERARCHY, MODAL }

internal fun navigationMotionFor(route: String): AppNavigationMotion =
    if (route == "model-setup") AppNavigationMotion.MODAL else AppNavigationMotion.HIERARCHY

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
        val accent = LocalMisulAccent.current

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
        var drawerContainerWidthPx by remember { mutableIntStateOf(0) }
        val resolvedDrawerWidthPx = resolvedDrawerWidthPx(
            layoutWidthPx = drawerContainerWidthPx,
            windowWidthPx = windowInfo.containerSize.width,
            density = density.density,
        )
        // Keep phone proportions while avoiding a giant sheet on tablets and unfolded devices.
        val drawerWidthPx = resolvedDrawerWidthPx
        val drawerWidth = with(density) { drawerWidthPx.toDp() }
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
        var drawerBackGestureInProgress by remember { mutableStateOf(false) }
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
            drawerScope.launch {
                focusManager.clearFocus()
                drawerState.animateTo(DrawerValue.CLOSED, PhoneSprings.drawer)
                navController.navigate(destination) { launchSingleTop = true }
            }
            Unit
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

        PredictiveBackHandler(enabled = drawerVisible || drawerBackGestureInProgress) { events ->
            val startOffset = drawerState.offset.takeUnless(Float::isNaN) ?: 0f
            var lastOffset = startOffset
            drawerBackGestureInProgress = true
            try {
                events.collect { event ->
                    val nextOffset = (startOffset * (1f - event.progress)).coerceIn(0f, drawerWidthPx)
                    drawerState.dispatchRawDelta(nextOffset - lastOffset)
                    lastOffset = nextOffset
                }
                // Commit the exact anchored offset driven by gesture progress. This must not replay
                // a second close animation after the system has already completed the gesture.
                drawerState.snapTo(DrawerValue.CLOSED)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    drawerState.animateTo(DrawerValue.OPEN, PhoneSprings.drawer)
                }
                throw cancelled
            } finally {
                drawerBackGestureInProgress = false
            }
        }

        Box(
            Modifier.fillMaxSize().background(colors.background)
                .onSizeChanged { size -> drawerContainerWidthPx = size.width }
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
                            val progress = drawerProgress
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
                        enterTransition = { MisulNavigationMotion.forwardEnter() },
                        exitTransition = { MisulNavigationMotion.forwardExit() },
                        popEnterTransition = { MisulNavigationMotion.backEnter() },
                        popExitTransition = { MisulNavigationMotion.backExit() },
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
                        composable(
                            route = "model-setup",
                            enterTransition = {
                                if (needsOnboarding) {
                                    androidx.compose.animation.EnterTransition.None
                                } else {
                                    slideInVertically(tween(260, easing = PhoneEasings.easeOut)) { it } +
                                        fadeIn(tween(160, easing = PhoneEasings.easeOut))
                                }
                            },
                            popExitTransition = {
                                slideOutVertically(tween(200, easing = PhoneEasings.easeOut)) { it } +
                                    fadeOut(tween(120, easing = PhoneEasings.easeOut))
                            },
                        ) {
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
                            val progress = drawerProgress
                            alpha = (0.5f * progress).coerceIn(0f, 1f)
                        }.background(colors.scrim)
                            .semantics { contentDescription = "Close navigation drawer" }
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { closeDrawer() },
                    )
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            val drawerOffset = drawerState.offset.takeUnless(Float::isNaN) ?: 0f
                            val drawerProgress = (drawerOffset / drawerWidthPx).coerceIn(0f, 1f)
                            val progress = drawerProgress
                            translationX = -drawerWidthPx * (1f - progress)
                        },
                    ) {
                        WorkspaceDrawer(
                            state = WorkspaceDrawerState(
                                projects = chatState.projects,
                                sessions = chatState.sessions,
                                currentSessionId = chatState.currentSessionId,
                                isRunning = chatState.isRunning,
                                activeSkillCount = chatState.skills.count { it.status == SkillStatus.ACTIVE },
                                mcpServerCount = chatState.mcpServers.size,
                            ),
                            width = drawerWidth,
                            collapsed = collapsedProjects,
                            onToggleProjectCollapse = { id ->
                                collapsedProjects = if (id in collapsedProjects) collapsedProjects - id else collapsedProjects + id
                            },
                            onOpenSession = { id -> vm.switchSession(id); closeDrawer() },
                            onNewChat = { projectId -> vm.newChat(projectId); closeDrawer() },
                            onCreateProject = openProjectPickerFromDrawer,
                            onOpenSettings = { settingsInitial = "home"; navigateFromDrawer("settings") },
                            onOpenSkills = { navigateFromDrawer("skills") },
                            onOpenMcp = { navigateFromDrawer("mcp") },
                            onSetSessionPinned = vm::setSessionPinned,
                            onRenameSession = vm::renameSession,
                            onMoveSession = vm::moveSession,
                            onSetSessionArchived = vm::setSessionArchived,
                            onDeleteSession = vm::deleteSession,
                            onRenameProject = vm::renameProject,
                            onDeleteProject = vm::deleteProject,
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
                    errorMessage = chatState.error,
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
                        .clearAndSetSemantics { contentDescription = "Loading Misul Agent" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_misul_mark),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
        }
    }
}
