package dev.phonecode.app.ui.settings

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.phonecode.app.ui.navigation.MisulNavigationMotion
import dev.phonecode.app.agent.ChatViewModel
import dev.phonecode.app.ui.SettingsViewModel
import dev.phonecode.tools.mcp.McpServerConfig

@Composable
internal fun SettingsNavigation(
    vm: ChatViewModel,
    settingsVm: SettingsViewModel,
    onExit: () -> Unit,
    startRoute: SettingsRoute = SettingsRoute.Home,
    motionEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    fun popOrExit() {
        if (!navController.popBackStack()) onExit()
    }

    NavHost(
        navController = navController,
        startDestination = startRoute,
        modifier = modifier,
        enterTransition = { if (motionEnabled) MisulNavigationMotion.forwardEnter() else EnterTransition.None },
        exitTransition = { if (motionEnabled) MisulNavigationMotion.forwardExit() else ExitTransition.None },
        popEnterTransition = { if (motionEnabled) MisulNavigationMotion.backEnter() else EnterTransition.None },
        popExitTransition = { if (motionEnabled) MisulNavigationMotion.backExit() else ExitTransition.None },
    ) {
        settingsRouteGraph { route ->
            when (route) {
                SettingsRoute.Home -> HomePage(
                    vm = vm,
                    settingsVm = settingsVm,
                    onBack = ::popOrExit,
                    onOpen = navController::navigate,
                )

                SettingsRoute.AgentTools -> AgentToolsPage(vm, ::popOrExit)

                SettingsRoute.Files -> FilesPage(
                    vm = vm,
                    onBack = ::popOrExit,
                )

                SettingsRoute.Appearance -> AppearancePage(settingsVm, ::popOrExit)

                SettingsRoute.Personalization -> PersonalPage(settingsVm, ::popOrExit)

                SettingsRoute.Providers -> ProvidersPage(
                    vm = vm,
                    onOpenProvider = { navController.navigate(SettingsRoute.Provider(it)) },
                    onBack = ::popOrExit,
                )

                is SettingsRoute.Provider -> ProviderDetailPage(
                    vm = vm,
                    providerId = route.id,
                    onBack = ::popOrExit,
                )

                SettingsRoute.Mcp -> McpPage(
                    vm = vm,
                    onBack = ::popOrExit,
                    onOpenServer = { navController.navigate(SettingsRoute.McpServer(it)) },
                )

                is SettingsRoute.McpServer -> McpServerDestination(
                    vm = vm,
                    id = route.id,
                    onBack = ::popOrExit,
                )

                SettingsRoute.Skills -> SkillsPage(
                    vm = vm,
                    onBack = ::popOrExit,
                    onOpenSkill = { navController.navigate(SettingsRoute.Skill(it)) },
                    onNewSkill = { navController.navigate(SettingsRoute.NewSkill) },
                )

                is SettingsRoute.Skill -> SkillDestination(
                    vm = vm,
                    id = route.id,
                    onBack = ::popOrExit,
                    onEdit = { navController.navigate(SettingsRoute.EditSkill(route.id)) },
                )

                SettingsRoute.NewSkill -> SkillEditorDestination(
                    vm = vm,
                    skillId = null,
                    skill = null,
                    isNew = true,
                    onBack = ::popOrExit,
                )

                is SettingsRoute.EditSkill -> SkillEditorDestination(
                    vm = vm,
                    skillId = route.id,
                    skill = collectSettingsState(vm).value.skills.firstOrNull { it.id == route.id },
                    isNew = false,
                    onBack = ::popOrExit,
                )

                SettingsRoute.Git -> GitPage(vm, settingsVm, ::popOrExit)

                SettingsRoute.Data -> ExportPage(
                    vm = vm,
                    settingsVm = settingsVm,
                    onBack = ::popOrExit,
                )

                SettingsRoute.About -> AboutPage(
                    vm = vm,
                    onOpenDoc = { page ->
                        val route = SettingsRoute.fromLegacyPage(page)
                        navController.navigate(route)
                    },
                    onBack = ::popOrExit,
                )

                is SettingsRoute.Document -> DocPage(
                    title = when (route.name) {
                        "terms" -> "Terms of Service"
                        "privacy" -> "Privacy Policy"
                        "licenses" -> "Open-source notices"
                        else -> route.name
                    },
                    assetName = "${route.name}.md",
                    onBack = ::popOrExit,
                )
            }
        }
    }

}

@Composable
private fun McpServerDestination(
    vm: ChatViewModel,
    id: String,
    onBack: () -> Unit,
) {
    val state by collectSettingsState(vm)
    val existing = state.mcpServers[id]

    // Session restoration is independent from MCP discovery. Never turn a
    // restored existing-server route into a draft while its inventory is unknown.
    if (id.isNotEmpty() && !state.mcpInventoryLoaded) {
        SettingsInventoryLoadingPage("MCP server", "Loading MCP servers…", onBack)
        return
    }
    if (id.isNotEmpty() && existing == null) {
        LaunchedEffect(id, state.mcpServers) { onBack() }
        return
    }

    var dirty by rememberSaveable(id) { androidx.compose.runtime.mutableStateOf(false) }
    DiscardChangesBackHandler(
        dirty = dirty,
        onDiscard = onBack,
    ) { requestBack ->
        McpServerPage(
            vm = vm,
            initialName = id,
            initial = existing ?: McpServerConfig(enabled = false),
            existingNames = state.mcpServers.keys,
            snapshot = state.mcpSnapshots[id],
            onBack = requestBack,
            onDirtyChange = { dirty = it },
            onSaved = {
                dirty = false
                onBack()
            },
        )
    }
}

@Composable
private fun SkillDestination(
    vm: ChatViewModel,
    id: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    val state by collectSettingsState(vm)
    if (!state.skillInventoryLoaded) {
        SettingsInventoryLoadingPage("Skill", "Loading skills…", onBack)
        return
    }
    val skill = state.skills.firstOrNull { it.id == id }
    if (skill == null) {
        LaunchedEffect(id) { onBack() }
        return
    }
    SkillDetailPage(vm = vm, skill = skill, onEdit = onEdit, onBack = onBack)
}

@Composable
private fun SkillEditorDestination(
    vm: ChatViewModel,
    skillId: String?,
    skill: dev.phonecode.app.data.ManagedSkill?,
    isNew: Boolean,
    onBack: () -> Unit,
) {
    var dirty by rememberSaveable(skillId, isNew) { androidx.compose.runtime.mutableStateOf(false) }
    DiscardChangesBackHandler(
        dirty = dirty,
        message = "This skill has unsaved changes.",
        onDiscard = onBack,
    ) { requestBack ->
        SkillEditorPage(
            vm = vm,
            skillId = skillId,
            skill = skill,
            isNew = isNew,
            onDirtyChange = { dirty = it },
            onBack = requestBack,
            onSaved = {
                dirty = false
                onBack()
            },
        )
    }
}

/** The single typed Settings graph, shared by the UI and route/back-stack tests. */
internal fun NavGraphBuilder.settingsRouteGraph(
    destination: @Composable (SettingsRoute) -> Unit,
) {
    composable<SettingsRoute.Home> { destination(SettingsRoute.Home) }
    composable<SettingsRoute.AgentTools> { destination(SettingsRoute.AgentTools) }
    composable<SettingsRoute.Files> { destination(SettingsRoute.Files) }
    composable<SettingsRoute.Appearance> { destination(SettingsRoute.Appearance) }
    composable<SettingsRoute.Personalization> { destination(SettingsRoute.Personalization) }
    composable<SettingsRoute.Providers> { destination(SettingsRoute.Providers) }
    composable<SettingsRoute.Provider> { entry -> destination(entry.toRoute<SettingsRoute.Provider>()) }
    composable<SettingsRoute.Mcp> { destination(SettingsRoute.Mcp) }
    composable<SettingsRoute.McpServer> { entry -> destination(entry.toRoute<SettingsRoute.McpServer>()) }
    composable<SettingsRoute.Skills> { destination(SettingsRoute.Skills) }
    composable<SettingsRoute.Skill> { entry -> destination(entry.toRoute<SettingsRoute.Skill>()) }
    composable<SettingsRoute.NewSkill> { destination(SettingsRoute.NewSkill) }
    composable<SettingsRoute.EditSkill> { entry -> destination(entry.toRoute<SettingsRoute.EditSkill>()) }
    composable<SettingsRoute.Git> { destination(SettingsRoute.Git) }
    composable<SettingsRoute.Data> { destination(SettingsRoute.Data) }
    composable<SettingsRoute.About> { destination(SettingsRoute.About) }
    composable<SettingsRoute.Document> { entry -> destination(entry.toRoute<SettingsRoute.Document>()) }
}

@Composable
internal fun DiscardChangesBackHandler(
    dirty: Boolean,
    message: String = "This server has unsaved changes.",
    onDiscard: () -> Unit,
    content: @Composable (onBack: () -> Unit) -> Unit,
) {
    var showDiscardDialog by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val requestBack = {
        if (dirty) showDiscardDialog = true else onDiscard()
    }
    PredictiveBackHandler(enabled = dirty && !showDiscardDialog) { events ->
        events.collect { }
        showDiscardDialog = true
    }
    content(requestBack)
    if (showDiscardDialog) {
        ConfirmDiscardDialog(
            message = message,
            onDiscard = {
                showDiscardDialog = false
                onDiscard()
            },
            onKeepEditing = { showDiscardDialog = false },
        )
    }
}
