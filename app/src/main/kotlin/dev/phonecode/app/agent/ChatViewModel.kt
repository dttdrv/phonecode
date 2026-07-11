package dev.phonecode.app.agent

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.phonecode.agent.AgentConfig
import dev.phonecode.agent.AgentEnvironment
import dev.phonecode.agent.AgentEvent
import dev.phonecode.agent.AgentLoop
import dev.phonecode.agent.AgentMode
import dev.phonecode.agent.MessageSource
import dev.phonecode.agent.PlanExitTool
import dev.phonecode.agent.SkillInfo
import dev.phonecode.agent.TaskTool
import dev.phonecode.agent.TurnSettings
import dev.phonecode.app.auth.CodexAuth
import dev.phonecode.app.auth.GitHubAuth
import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.app.data.AppSettingsStore
import dev.phonecode.app.data.CustomProviderRepository
import dev.phonecode.app.data.FileCatalogCache
import dev.phonecode.app.data.McpSkillRepository
import dev.phonecode.app.data.ModelPrefsStore
import dev.phonecode.app.data.PersistedSession
import dev.phonecode.app.data.Project
import dev.phonecode.app.data.ProjectStore
import dev.phonecode.app.data.SecureKeyStore
import dev.phonecode.app.data.SessionMeta
import dev.phonecode.app.data.SessionStore
import dev.phonecode.app.data.SharedFolder
import dev.phonecode.app.data.SharedFolderStore
import dev.phonecode.app.data.TransferBundle
import dev.phonecode.app.data.toDomain
import dev.phonecode.app.data.toPreset
import dev.phonecode.app.data.toPersisted
import dev.phonecode.provider.catalog.Catalog
import dev.phonecode.provider.catalog.CatalogLoader
import dev.phonecode.provider.domain.ChatMessage
import dev.phonecode.provider.domain.FailureKind
import dev.phonecode.provider.domain.LlmProvider
import dev.phonecode.provider.domain.MessagePart
import dev.phonecode.provider.domain.ReasoningEffort
import dev.phonecode.provider.domain.Role
import dev.phonecode.provider.http.CodexModelInfo
import dev.phonecode.provider.http.CodexModelsClient
import dev.phonecode.provider.http.ProviderFactory
import dev.phonecode.provider.preset.BuiltInPresets
import dev.phonecode.provider.preset.CodexCompatibility
import dev.phonecode.provider.preset.ProviderPreset
import dev.phonecode.provider.preset.WireFormat
import dev.phonecode.tools.Tool
import dev.phonecode.tools.ToolRegistry
import dev.phonecode.tools.UserAnswer
import dev.phonecode.tools.UserQuestion
import dev.phonecode.tools.external.ExternalDirectoryTool
import dev.phonecode.tools.files.defaultFileTools
import dev.phonecode.tools.git.gitTools
import dev.phonecode.tools.interaction.QuestionTool
import dev.phonecode.tools.patch.ApplyPatchTool
import dev.phonecode.tools.mcp.McpConfig
import dev.phonecode.tools.mcp.McpServerConfig
import dev.phonecode.tools.mcp.connectMcpServers
import dev.phonecode.tools.skills.SkillManifest
import dev.phonecode.tools.skills.SkillTool
import dev.phonecode.tools.shared.SharedReadTool
import dev.phonecode.tools.shared.SharedWriteTool
import dev.phonecode.tools.shell.ProcessManager
import dev.phonecode.tools.shell.ProcessTool
import dev.phonecode.tools.shell.ShellTool
import dev.phonecode.tools.todo.TodoItem
import dev.phonecode.tools.todo.TodoStore
import dev.phonecode.tools.todo.todoTools
import dev.phonecode.tools.web.WebFetchTool
import dev.phonecode.tools.web.WebSearchTool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

data class ModelOption(val providerId: String, val modelId: String, val label: String)

enum class ToolStatus { RUNNING, DONE, ERROR }

data class PermissionRequest(val tool: String, val summary: String)

data class QuestionRequest(val questions: List<UserQuestion>)
data class RetryState(val attempt: Int, val message: String)

sealed interface ChatLine {
    data class User(val text: String, val images: List<MessagePart.Image> = emptyList()) : ChatLine
    data class Assistant(val text: String) : ChatLine
    data class Reasoning(val text: String) : ChatLine
    data class ToolActivity(val id: String, val name: String, val status: ToolStatus, val detail: String) : ChatLine
}

data class ChatUiState(
    val lines: List<ChatLine> = emptyList(),
    val streaming: String = "",
    val streamingReasoning: String = "",
    val isRunning: Boolean = false,
    val queued: List<String> = emptyList(), // messages sent while a turn runs, awaiting pickup by the agent
    val models: List<ModelOption> = builtInModels(),
    val selected: ModelOption? = builtInModels().firstOrNull(),
    val agentMode: AgentMode = AgentMode.BUILD,
    val effort: ReasoningEffort = ReasoningEffort.DEFAULT,
    val autoAccept: Boolean = false,
    val pendingPermission: PermissionRequest? = null,
    val pendingQuestion: QuestionRequest? = null,
    val retry: RetryState? = null,
    val todos: List<TodoItem> = emptyList(),
    val mcpServers: Map<String, McpServerConfig> = emptyMap(),
    val mcpToolCount: Int = 0,
    val skills: List<SkillInfo> = emptyList(),
    val sessions: List<SessionMeta> = emptyList(),
    // Bumped whenever `lines` is REWOUND (redo) - the chat list keys its index-cache remembers
    // on this so truncation doesn't leak stale animation/identity state (index keys are otherwise
    // append-only-safe).
    val timelineEpoch: Int = 0,
    val projects: List<Project> = emptyList(),
    val sharedFolders: List<SharedFolder> = emptyList(),
    val favourites: Set<String> = emptySet(),
    val hiddenModels: Set<String> = emptySet(),
    val disabledProviders: Set<String> = emptySet(),
    val usageInput: Long = 0,
    val usageOutput: Long = 0,
    val contextLimit: Long? = null,
    val currentSessionId: String = "",
    val currentProjectId: String? = null,
    val lastCompletedAt: Long? = null,
    val codexConnected: Boolean = false,
    val githubLogin: String? = null,
    val githubAuthCode: String? = null,
    val githubVerifyUri: String? = null,
    val notice: String? = null,
    val error: String? = null,
    val interruptedTurn: Boolean = false,
)

/** Orchestrates the agent loop for the chat UI: builds provider + tools + loop, streams events into UI state. */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    // Workspaces are PER PROJECT: workspaces/<projectId>, with workspaces/default for unsorted
    // chats. Each project is its own folder + git repo; the active one follows the current chat.
    private val workspacesRoot = File(app.filesDir, "workspaces").apply {
        mkdirs()
        // One-time migration: the old single global workspace becomes the default workspace.
        val legacy = File(app.filesDir, "workspace")
        val default = File(this, "default")
        if (legacy.isDirectory && !default.exists()) legacy.renameTo(default)
    }
    @Volatile private var workspace: File = workspaceFor(null)

    // The workspace PINNED for the in-flight turn: tools must keep writing into the directory the
    // turn STARTED in even if the user moves/deletes the project mid-stream (which repoints
    // [workspace]). Set at send() start, cleared when that turn finishes.
    @Volatile private var turnWorkspace: File? = null
    private val keyStore = SecureKeyStore(app)
    // The agent's POSIX userland (busybox shell + applet symlinks + HOME/TMPDIR/PREFIX env) -
    // bootstrapped once per app version, lazily so construction never touches the filesystem.
    private val userland by lazy { EnvironmentBootstrap.ensure(app) }
    private val http = OkHttpClient.Builder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val foregroundLeases = (app as PhoneCodeApplication).foregroundLeases
    private val turnLease = AtomicReference<String?>(null)
    private val authLease = AtomicReference<String?>(null)
    private val githubAuthLease = AtomicReference<String?>(null)
    private val todoStore = TodoStore()
    private val configDir = File(app.filesDir, "config")
    private val repo = McpSkillRepository(configDir)
    private val customProviders = CustomProviderRepository(configDir)
    private val sharedFolderStore = SharedFolderStore(File(app.filesDir, "shared_folders.json"))
    private val sharedFileAccess = AndroidSharedFileAccess(app, sharedFolderStore)
    private val shellProvider: (String) -> List<String> = { workspacePath ->
        userland.ensureLinux()
        userland.shell(workspacePath)
    }
    private val shellEnvironment: () -> Map<String, String> = { userland.shellEnv() }
    private val processManager = ProcessManager(
        shellProvider = shellProvider,
        environmentProvider = shellEnvironment,
        onStarted = { foregroundLeases.acquire("process-$it") },
        onStopped = { foregroundLeases.release("process-$it") },
    )
    private val baseTools: List<Tool> =
        defaultFileTools() + ApplyPatchTool() + ExternalDirectoryTool() + QuestionTool() +
            SharedReadTool(sharedFileAccess) + SharedWriteTool(sharedFileAccess) +
            PlanExitTool { setAgentMode(AgentMode.BUILD) } + todoTools(todoStore) +
            WebFetchTool(http) + WebSearchTool(http) + TaskTool(::runSubagent) + gitTools { gitCredentials() } +
            // Real terminal access: busybox userland over Android's toybox, transparently upgrading to a
            // full Alpine Linux (proot) once its rootfs is set up - sandbox-scoped, permission-gated like
            // every mutating tool. Providers are dynamic: shell()/shellEnv() re-resolve each call so the
            // shell flips from busybox to Linux the moment the background rootfs setup finishes.
            ShellTool(shellProvider, shellEnvironment, processManager) + ProcessTool(processManager)
    @Volatile private var mcpTools: List<Tool> = emptyList()
    @Volatile private var discoveredSkills: List<SkillManifest> = emptyList()
    // Registry is replaced wholesale (not mutated) so send()/runSubagent always read a consistent snapshot.
    @Volatile private var tools = ToolRegistry(baseTools)
    // MUST be initialized before the init block below: the MCP-connect coroutine it launches calls
    // rebuildTools() and can run before a later-declared field's initializer executes (NPE at launch).
    private val toolsLock = Any()
    private val toolContext = AndroidToolContext({ (turnWorkspace ?: workspace).absolutePath }, ::askPermission, ::askUser)
    private val catalogLoader = CatalogLoader(
        http,
        FileCatalogCache(app.cacheDir),
        ttlMillis = CATALOG_REFRESH_TTL_MS,
        bundledFallback = { BUNDLED_CATALOG },
    )
    private val codexModelsClient = CodexModelsClient(http)
    private val codexAuth by lazy { CodexAuth(http, store = keyStore::put, read = keyStore::get) }
    @Volatile private var catalog: dev.phonecode.provider.catalog.Catalog = emptyMap()
    @Volatile private var codexModelMetadata: Map<String, CodexModelInfo> = emptyMap()
    @Volatile private var customPresets: Map<String, ProviderPreset> = emptyMap()
    @Volatile private var customLimits: Map<String, Long> = emptyMap()

    private fun providerFor(id: String): ProviderPreset? {
        val preset = BuiltInPresets.byId(id) ?: customPresets[id] ?: return null
        if (preset.wireFormat != WireFormat.OPENAI_COMPAT) return preset
        return preset.withCatalogApi(catalog[catalogProviderId(id)]?.api)
    }

    /** All providers for Settings: built-ins plus any agent-defined custom providers. */
    fun allProviders(): List<ProviderPreset> = BuiltInPresets.all + customPresets.values.sortedBy { it.displayName }

    /** The selected model's token limits from the models.dev catalog, then the custom config, if known. */
    private fun limitFor(option: ModelOption?): dev.phonecode.provider.catalog.Limit? = option?.let {
        (if (it.providerId == "codex") codexModelMetadata[it.modelId]?.let { model ->
            dev.phonecode.provider.catalog.Limit(
                context = model.contextWindow ?: model.maxContextWindow,
                output = 128_000,
            )
        } else null)
            ?: catalog[catalogProviderId(it.providerId)]?.models?.get(it.modelId)?.limit
            ?: customLimits["${it.providerId}/${it.modelId}"]?.let { c -> dev.phonecode.provider.catalog.Limit(context = c) }
            ?: if (it.providerId == "codex") dev.phonecode.provider.catalog.Limit(context = 372_000, output = 128_000) else null
    }

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val sessionStore = SessionStore(File(app.filesDir, "sessions"))
    private val modelPrefs = ModelPrefsStore(File(app.filesDir, "model_prefs.json"))
    private val projectStore = ProjectStore(File(app.filesDir, "projects.json"))
    private val appSettings = AppSettingsStore(File(app.filesDir, "app_settings.json"))
    @Volatile private var sessionId: String = "session-" + System.currentTimeMillis()
    @Volatile private var currentProjectId: String? = null
    @Volatile private var history: List<ChatMessage> = emptyList()
    @Volatile private var generation = 0
    private var job: Job? = null
    private var modelRefreshJob: Job? = null
    @Volatile private var lastCatalogRefreshAt = 0L
    @Volatile private var lastCodexRefreshAt = 0L
    private var pendingDecision: CompletableDeferred<Boolean>? = null
    private var pendingQuestionDecision: CompletableDeferred<List<UserAnswer>>? = null

    // Messages sent while a turn is running: the agent loop drains them as steering (picked up at its next
    // step, so it can be redirected without stopping) or as a follow-up at the end - nothing is dropped.
    private val pendingMessages = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val queueSource = MessageSource { generateSequence { pendingMessages.poll() }.toList() }

    init {
        refreshSessions()
        // Prewarm the userland bootstrap (symlink install + bundled Alpine rootfs extract) off the main
        // thread, so the Linux env is ready before the agent's first shell call instead of the model
        // hitting a "provisioning" window and improvising.
        viewModelScope.launch(Dispatchers.IO) { userland.ensureLinux() }
        viewModelScope.launch(Dispatchers.IO) {
            val saved = appSettings.load()
            _state.update {
                it.copy(
                    favourites = modelPrefs.favourites(),
                    hiddenModels = modelPrefs.hiddenModels(),
                    disabledProviders = modelPrefs.disabledProviders(),
                    autoAccept = saved.autoAccept,
                    agentMode = runCatching { AgentMode.valueOf(saved.defaultMode) }.getOrDefault(AgentMode.BUILD),
                    codexConnected = keyStore.get("codex.access") != null,
                    githubLogin = keyStore.get("github.login"),
                    currentSessionId = sessionId,
                    sharedFolders = sharedFolderStore.list(),
                )
            }
        }
        reloadProviders()
        // The agent's todo list (a StateFlow) drives the on-screen checklist directly.
        viewModelScope.launch { todoStore.items.collect { todos -> _state.update { it.copy(todos = todos) } } }
        // Restore the most recent conversation SYNCHRONOUSLY, before the UI can send. The old restore ran
        // on a background coroutine and bailed once the user touched the screen, so reopening the app
        // (Android kills it aggressively) and sending right away started a cold session and orphaned the
        // real one - "a new instance with no context" on every relaunch. loadLatest reads a single file.
        // ponytail: one small main-thread read at startup; if a huge transcript ever hitches launch, move
        // the body off-thread and have send() await it.
        runCatching {
            sessionStore.loadLatest()?.let { latest ->
                val interrupted = latest.activeTurn
                sessionId = latest.id
                setActiveProject(latest.projectId)
                history = latest.messages.map { it.toDomain() }.let {
                    if (interrupted) repairInterruptedHistory(it) else it
                }
                _state.update {
                    it.copy(
                        lines = history.toChatLines(),
                        currentSessionId = latest.id,
                        currentProjectId = latest.projectId,
                        error = if (interrupted) TURN_INTERRUPTED_MESSAGE else it.error,
                        interruptedTurn = interrupted,
                    )
                }
                if (interrupted) {
                    sessionStore.save(latest.copy(messages = history.map { it.toPersisted() }, activeTurn = false))
                }
            }
        }
        // Load MCP config + discover skills, then connect remote MCP servers and fold their tools in.
        viewModelScope.launch(Dispatchers.IO) {
            val config = repo.loadMcpConfig()
            repo.seedBundledSkills(app.assets) // built-in skills (e.g. diagrams) on first run; user can edit/delete after
            discoveredSkills = repo.discoverSkills()
            val connected = runCatching { connectMcpServers(config, http) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else emptyList() }
            mcpTools = connected
            rebuildTools()
            _state.update {
                it.copy(
                    mcpServers = config.mcp,
                    mcpToolCount = connected.size,
                    skills = discoveredSkills.map { s -> SkillInfo(s.name, s.description) },
                )
            }
        }
        refreshModels()
    }

    fun refreshModels(forceRefresh: Boolean = false) {
        val now = System.currentTimeMillis()
        val refreshCatalog = forceRefresh || now - lastCatalogRefreshAt >= CATALOG_REFRESH_TTL_MS
        val refreshCodex = !keyStore.get("codex.access").isNullOrBlank() &&
            (forceRefresh || now - lastCodexRefreshAt >= CODEX_REFRESH_TTL_MS)
        if (!refreshCatalog && !refreshCodex) return
        if (modelRefreshJob?.isActive == true) return
        modelRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            if (refreshCatalog) {
                runCatching { catalogLoader.load(forceRefresh) }.getOrNull()?.let {
                    catalog = it.catalog
                    applyModelOptions(catalogToOptions(it.catalog))
                    lastCatalogRefreshAt = System.currentTimeMillis()
                }
            }
            if (refreshCodex) {
                val accessToken = codexAuth.accessToken()
                if (!accessToken.isNullOrBlank()) {
                    val accountId = codexAuth.accountId()
                    val preset = accountId?.let {
                        BuiltInPresets.codex.copy(
                            extraHeaders = BuiltInPresets.codex.extraHeaders + ("chatgpt-account-id" to it),
                        )
                    } ?: BuiltInPresets.codex
                    runCatching {
                        codexModelsClient.fetch(preset, accessToken, CodexCompatibility.CLIENT_VERSION)
                    }.getOrNull()
                        ?.filter { it.visibility == "list" && it.supportedInApi }
                        ?.takeIf { it.isNotEmpty() }
                        ?.let {
                            codexModelMetadata = it.associateBy(CodexModelInfo::slug)
                            applyModelOptions(catalogToOptions(catalog))
                            lastCodexRefreshAt = System.currentTimeMillis()
                        }
                }
            }
        }
    }

    private fun applyModelOptions(options: List<ModelOption>) {
        if (options.isEmpty()) return
        _state.update { state ->
            val builtinKeys = options.map { "${it.providerId}/${it.modelId}" }.toSet()
            val custom = state.models.filter {
                it.providerId in customPresets && "${it.providerId}/${it.modelId}" !in builtinKeys
            }
            val merged = options + custom
            val current = state.selected
            val recentKey = modelPrefs.recents().firstOrNull()
            val resolved = merged.firstOrNull { modelKey(it) == recentKey }
                ?: merged.firstOrNull { it.providerId == current?.providerId && it.modelId == current.modelId }
                ?: current?.providerId?.let { id -> merged.firstOrNull { it.providerId == id } }
                ?: merged.first()
            state.copy(models = merged, selected = resolved, contextLimit = limitFor(resolved)?.context)
        }
    }

    /** Build the picker from the catalog for our presets; fall back to built-ins per provider. */
    private fun catalogToOptions(catalog: Catalog): List<ModelOption> {
        val out = mutableListOf<ModelOption>()
        BuiltInPresets.all.forEach { preset ->
            if (preset.id == "codex") {
                val authenticated = codexModelMetadata.values
                    .sortedWith(compareBy<CodexModelInfo> { it.priority }.thenBy { it.displayName })
                    .map { ModelOption("codex", it.slug, "${preset.displayName} · ${it.displayName}") }
                if (authenticated.isNotEmpty()) {
                    out += authenticated
                    return@forEach
                }
                val live = catalog["openai"]?.models?.values
                    ?.filter { codexEligible(it.id) }
                    ?.sortedByDescending { it.id }
                    ?.map { ModelOption("codex", it.id, "${preset.displayName} · ${it.name}") }
                    .orEmpty()
                out += (live + builtInModels().filter { it.providerId == "codex" }).distinctBy { it.modelId }
                return@forEach
            }
            val info = catalog[catalogProviderId(preset.id)]
            if (info != null && info.models.isNotEmpty()) {
                val live = info.models.values.sortedBy { it.name }.map { model ->
                    ModelOption(preset.id, model.id, "${preset.displayName} · ${model.name}")
                }
                out += (live + builtInModels().filter { it.providerId == preset.id }).distinctBy { it.modelId }
            } else {
                out += builtInModels().filter { it.providerId == preset.id }
            }
        }
        return out
    }

    private fun codexEligible(id: String): Boolean = when (id) {
        in setOf("gpt-5.5", "gpt-5.2", "gpt-5.4", "gpt-5.4-mini") -> true
        in setOf("gpt-5.5-pro") -> false
        else -> Regex("^gpt-(\\d+\\.\\d+)").find(id)?.groupValues?.get(1)?.toDoubleOrNull()?.let { it > 5.4 } ?: false
    }

    private fun modelKey(o: ModelOption) = "${o.providerId}/${o.modelId}"

    /** The workspace folder for [projectId] (null = the default workspace for unsorted chats). */
    private fun workspaceFor(projectId: String?): File =
        File(workspacesRoot, projectId ?: "default").apply { mkdirs() }

    /** Switch the active project: the workspace (files + git repo) follows the current chat's project. */
    private fun setActiveProject(projectId: String?) {
        currentProjectId = projectId
        workspace = workspaceFor(projectId)
    }

    /**
     * "Auto-branch each task" (Settings > Git > Advanced): when enabled, the first turn of a chat
     * moves the workspace onto its own branch so the agent's changes stay isolated from main.
     * Best-effort - a failure (no repo, detached head) must never block the send.
     */
    private fun autoBranchIfEnabled(dir: File, taskSessionId: String = sessionId) {
        if (!appSettings.load().gitAutoBranch) return
        if (!File(dir, ".git").exists()) return
        runCatching {
            org.eclipse.jgit.api.Git.open(dir).use { git ->
                val branch = "task-" + taskSessionId.removePrefix("session-")
                if (git.repository.branch != branch) {
                    git.checkout().setName(branch).setCreateBranch(true).call()
                }
            }
        }
    }

    /** Git HTTPS credentials (username + token) from the keystore, if both are set. */
    private fun gitCredentials(): Pair<String, String>? {
        val user = keyStore.get("git.username")
        val token = keyStore.get("git.token")
        return if (!user.isNullOrBlank() && !token.isNullOrBlank()) user to token else null
    }

    fun selectModel(option: ModelOption) {
        // Effort resets to AUTO on every model switch: one effort silently applied to every
        // model was wrong (round-3 feedback) - thinking adapts per model from the catalog.
        _state.update { it.copy(selected = option, contextLimit = limitFor(option)?.context, effort = ReasoningEffort.DEFAULT) }
        viewModelScope.launch(Dispatchers.IO) { modelPrefs.recordRecent(modelKey(option)) }
    }

    private fun catalogModel(option: ModelOption?) = option?.let {
        catalog[catalogProviderId(it.providerId)]?.models?.get(it.modelId)
    }

    fun reasoningEfforts(option: ModelOption?): List<ReasoningEffort> {
        if (option?.providerId == "codex") {
            codexModelMetadata[option.modelId]?.let { model ->
                val efforts = model.supportedReasoningLevels.mapNotNull { ReasoningEffort.fromWire(it.effort) }
                return if (efforts.isEmpty()) emptyList() else (listOf(ReasoningEffort.DEFAULT) + efforts).distinct()
            }
        }
        val model = catalogModel(option) ?: return if (option == null) emptyList() else listOf(ReasoningEffort.DEFAULT)
        if (!model.reasoning) return emptyList()
        val efforts = model.reasoningOptions
            .firstOrNull { it.type == "effort" }
            ?.values
            .orEmpty()
            .mapNotNull(ReasoningEffort::fromWire)
        return (listOf(ReasoningEffort.DEFAULT) + efforts).distinct()
    }

    fun supportsReasoning(option: ModelOption?): Boolean = reasoningEfforts(option).isNotEmpty()

    fun toggleFavourite(option: ModelOption) {
        viewModelScope.launch(Dispatchers.IO) {
            val favourites = modelPrefs.toggleFavourite(modelKey(option))
            _state.update { it.copy(favourites = favourites) }
        }
    }

    /** Hide/show a model in the picker (Settings > Providers > provider > model toggle). */
    fun toggleModelHidden(option: ModelOption) {
        viewModelScope.launch(Dispatchers.IO) {
            val hidden = modelPrefs.toggleHidden(modelKey(option))
            _state.update { it.copy(hiddenModels = hidden) }
        }
    }

    /** "All on" / "All off" for a provider's models (settings bulk action). */
    fun setAllModelsHidden(options: List<ModelOption>, hidden: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val set = modelPrefs.setHidden(options.map { modelKey(it) }, hidden)
            _state.update { it.copy(hiddenModels = set) }
        }
    }

    /** Turn a whole provider on/off - disabled providers disappear from the picker. */
    fun toggleProviderDisabled(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val disabled = modelPrefs.toggleProviderDisabled(id)
            _state.update { it.copy(disabledProviders = disabled) }
        }
    }

    /** Reload agent-defined custom providers/models from providers.json into the picker + provider map. */
    fun reloadProviders() {
        viewModelScope.launch(Dispatchers.IO) {
            val cfg = customProviders.load()
            customPresets = cfg.provider.mapValues { (id, p) -> p.toPreset(id) }
            customLimits = cfg.provider.flatMap { (pid, p) ->
                p.models.mapNotNull { (mid, m) -> m.context?.let { "$pid/$mid" to it } }
            }.toMap()
            val customOptions = cfg.provider.flatMap { (pid, p) ->
                p.models.map { (mid, m) -> ModelOption(pid, mid, m.name.ifBlank { mid }) }
            }
            if (customOptions.isNotEmpty()) {
                _state.update { s ->
                    val existing = s.models.map { "${it.providerId}/${it.modelId}" }.toSet()
                    s.copy(models = s.models + customOptions.filterNot { "${it.providerId}/${it.modelId}" in existing })
                }
            }
        }
    }
    /** True for user/agent-defined providers (they get a "Remove" action; presets don't). */
    fun isCustomProvider(id: String): Boolean = id in customPresets

    /** Save a user-defined provider from the settings form into providers.json, then fold it in. */
    fun saveCustomProvider(id: String, provider: dev.phonecode.app.data.CustomProvider) {
        viewModelScope.launch(Dispatchers.IO) {
            val cfg = customProviders.load()
            customProviders.save(cfg.copy(provider = cfg.provider + (id to provider)))
            reloadProviders()
        }
    }

    /** Remove a user-defined provider: config entry, preset, and its picker models. */
    fun deleteCustomProvider(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val cfg = customProviders.load()
            customProviders.save(cfg.copy(provider = cfg.provider - id))
            customPresets = customPresets - id
            customLimits = customLimits.filterKeys { !it.startsWith("$id/") }
            _state.update { s -> s.copy(models = s.models.filterNot { it.providerId == id }) }
        }
    }

    fun setAgentMode(mode: AgentMode) = _state.update { it.copy(agentMode = mode) }
    fun setEffort(effort: ReasoningEffort) = _state.update {
        if (effort in reasoningEfforts(it.selected)) it.copy(effort = effort) else it
    }
    fun setAutoAccept(value: Boolean) {
        _state.update { it.copy(autoAccept = value) }
        viewModelScope.launch(Dispatchers.IO) { appSettings.update { it.copy(autoAccept = value) } }
    }

    fun linkSharedFolder(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { sharedFileAccess.link(uri) }
                .onSuccess { folders -> _state.update { it.copy(sharedFolders = folders, notice = "Folder linked") } }
                .onFailure { error -> _state.update { it.copy(error = "Could not link folder: ${error.message}") } }
        }
    }

    fun unlinkSharedFolder(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val folders = sharedFileAccess.unlink(id)
            val projectIds = projectStore.list().filter { it.folderId == id }.map { it.id }.toSet()
            if (projectIds.isNotEmpty()) {
                sessionStore.list().filter { it.projectId in projectIds }.forEach { sessionStore.setProject(it.id, null) }
                projectIds.forEach(projectStore::delete)
            }
            val activeRemoved = currentProjectId in projectIds
            if (activeRemoved) setActiveProject(null)
            _state.update {
                it.copy(
                    sharedFolders = folders,
                    projects = projectStore.list(),
                    sessions = sessionStore.list(),
                    currentProjectId = if (activeRemoved) null else it.currentProjectId,
                    notice = "Folder access removed",
                )
            }
        }
    }

    /**
     * Start a fresh conversation (a new session id); persisted history of the old one is kept on
     * disk. Works mid-stream: the running turn is cancelled first (its partial reply was already
     * committed and persisted to ITS session by cancel()) - a silent no-op read as "the new chat
     * buttons don't work" (device feedback). The new session persists immediately so it shows up
     * under its folder in the drawer right away instead of existing only in memory.
     */
    fun newChat(projectId: String? = currentProjectId) {
        if (_state.value.isRunning) cancel()
        dropIfEmptyPlaceholder()
        generation++
        history = emptyList()
        sessionId = "session-" + System.currentTimeMillis()
        setActiveProject(projectId)
        todoStore.replace(emptyList())
        _state.update {
            it.copy(lines = emptyList(), streaming = "", streamingReasoning = "", usageInput = 0, usageOutput = 0, error = null, interruptedTurn = false, currentSessionId = sessionId, currentProjectId = projectId)
        }
        val id = sessionId
        viewModelScope.launch(Dispatchers.IO) {
            sessionStore.save(PersistedSession(id, "New chat", System.currentTimeMillis(), emptyList(), projectId))
            _state.update { it.copy(sessions = sessionStore.list()) }
        }
    }

    /** Never-used "New chat" placeholders are dropped when navigating away, not collected forever. */
    private fun dropIfEmptyPlaceholder() {
        if (history.isEmpty() && _state.value.lines.isEmpty()) {
            val id = sessionId
            viewModelScope.launch(Dispatchers.IO) {
                if (sessionStore.load(id)?.messages?.isEmpty() == true) sessionStore.delete(id)
                _state.update { it.copy(sessions = sessionStore.list()) }
            }
        }
    }

    /** Load a saved conversation and make it the active session. Works mid-stream (cancels first). */
    fun switchSession(id: String) {
        if (id == sessionId) return
        if (_state.value.isRunning) cancel()
        dropIfEmptyPlaceholder()
        generation++ // bump on the main thread (single-writer for the turn guard), like send/cancel do
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = sessionStore.load(id) ?: return@launch
            val interrupted = loaded.activeTurn
            val restored = loaded.messages.map { it.toDomain() }.let {
                if (interrupted) repairInterruptedHistory(it) else it
            }
            history = restored
            sessionId = loaded.id
            setActiveProject(loaded.projectId)
            todoStore.replace(emptyList())
            _state.update {
                it.copy(
                    lines = restored.toChatLines(),
                    streaming = "",
                    streamingReasoning = "",
                    usageInput = 0,
                    usageOutput = 0,
                    error = if (interrupted) TURN_INTERRUPTED_MESSAGE else null,
                    interruptedTurn = interrupted,
                    currentSessionId = sessionId,
                    currentProjectId = loaded.projectId,
                )
            }
            if (interrupted) {
                sessionStore.save(loaded.copy(messages = restored.map { it.toPersisted() }, activeTurn = false))
            }
        }
    }

    fun deleteSession(id: String) {
        if (id == sessionId) newChat()
        viewModelScope.launch(Dispatchers.IO) {
            sessionStore.delete(id)
            _state.update { it.copy(sessions = sessionStore.list()) }
        }
    }

    private fun refreshSessions() {
        viewModelScope.launch(Dispatchers.IO) { _state.update { it.copy(sessions = sessionStore.list(), projects = projectStore.list()) } }
    }

    fun createProject(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val folders = sharedFileAccess.link(uri)
                val folder = folders.first { it.handle == uri.toString() }
                val project = projectStore.list().firstOrNull { it.folderId == folder.id }
                    ?: projectStore.add("project-" + System.currentTimeMillis(), folder.name, folder.id)
                Triple(folders, projectStore.list(), project)
            }.onSuccess { (folders, projects, project) ->
                withContext(Dispatchers.Main.immediate) {
                    _state.update { it.copy(sharedFolders = folders, projects = projects, notice = "Project linked") }
                    newChat(project.id)
                }
            }.onFailure { error ->
                _state.update { it.copy(error = "Could not create project: ${error.message}") }
            }
        }
    }

    fun renameProject(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            projectStore.rename(id, trimmed)
            _state.update { it.copy(projects = projectStore.list()) }
        }
    }

    /** Delete a project; its chats are detached to "unsorted" rather than removed. */
    fun deleteProject(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val folderId = projectStore.list().firstOrNull { it.id == id }?.folderId
            sessionStore.list().filter { it.projectId == id }.forEach { sessionStore.setProject(it.id, null) }
            projectStore.delete(id)
            val folders = folderId?.let(sharedFileAccess::unlink) ?: sharedFolderStore.list()
            if (currentProjectId == id) {
                setActiveProject(null)
                _state.update { it.copy(projects = projectStore.list(), sharedFolders = folders, sessions = sessionStore.list(), currentProjectId = null) }
            } else {
                _state.update { it.copy(projects = projectStore.list(), sharedFolders = folders, sessions = sessionStore.list()) }
            }
        }
    }

    fun renameSession(id: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            sessionStore.rename(id, trimmed)
            _state.update { it.copy(sessions = sessionStore.list()) }
        }
    }

    fun moveSession(id: String, projectId: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            sessionStore.setProject(id, projectId)
            if (id == sessionId) {
                setActiveProject(projectId)
                _state.update { it.copy(sessions = sessionStore.list(), currentProjectId = projectId) }
            } else {
                _state.update { it.copy(sessions = sessionStore.list()) }
            }
        }
    }

    fun setSessionPinned(id: String, pinned: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            sessionStore.setPinned(id, pinned)
            _state.update { it.copy(sessions = sessionStore.list()) }
        }
    }

    /** Archiving a chat drops it out of the main list; the active chat falls back to a fresh one. */
    fun setSessionArchived(id: String, archived: Boolean) {
        if (archived && id == sessionId) newChat()
        viewModelScope.launch(Dispatchers.IO) {
            sessionStore.setArchived(id, archived)
            _state.update { it.copy(sessions = sessionStore.list()) }
        }
    }

    fun saveMcpServer(name: String, server: McpServerConfig) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val updated = McpConfig(repo.loadMcpConfig().mcp + (trimmed to server))
        repo.saveMcpConfig(updated)
        _state.update { it.copy(mcpServers = updated.mcp) }
        reconnectMcp()
    }

    fun deleteMcpServer(name: String) {
        val updated = McpConfig(repo.loadMcpConfig().mcp - name)
        repo.saveMcpConfig(updated)
        _state.update { it.copy(mcpServers = updated.mcp) }
        reconnectMcp()
    }

    fun setMcpEnabled(name: String, enabled: Boolean) {
        val current = repo.loadMcpConfig().mcp[name] ?: return
        saveMcpServer(name, current.copy(enabled = enabled))
    }

    /** Reconnect every enabled remote MCP server and fold the resulting tools into the registry. */
    fun reconnectMcp() {
        viewModelScope.launch(Dispatchers.IO) {
            val config = repo.loadMcpConfig()
            val connected = runCatching { connectMcpServers(config, http) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else emptyList() }
            mcpTools = connected
            rebuildTools()
            _state.update { it.copy(mcpServers = config.mcp, mcpToolCount = connected.size) }
        }
    }

    /** Re-scan the config dir for SKILL.md files and refresh the skill tool + prompt. */
    fun refreshSkills() {
        viewModelScope.launch(Dispatchers.IO) {
            discoveredSkills = repo.discoverSkills()
            rebuildTools()
            _state.update { it.copy(skills = discoveredSkills.map { s -> SkillInfo(s.name, s.description) }) }
        }
    }

    // Serialized: reconnectMcp/refreshSkills/init all rebuild from background coroutines; without the lock
    // two interleaved read-modify-writes could drop the just-connected MCP tools (a lost update).
    private fun rebuildTools() = synchronized(toolsLock) {
        val skillTool = if (discoveredSkills.isNotEmpty()) listOf(SkillTool(discoveredSkills)) else emptyList()
        tools = ToolRegistry(baseTools + mcpTools + skillTool)
    }
    fun configDirPath(): String = configDir.absolutePath
    fun keyFor(providerId: String): String = keyStore.get(providerId).orEmpty()
    fun setKey(providerId: String, key: String) = keyStore.put(providerId, key.trim())
    /** True when the device Keystore was unavailable and keys are stored UNENCRYPTED (warn on the providers screen). */
    fun secureStorageUnavailable(): Boolean = keyStore.secureStorageUnavailable
    fun clearError() = _state.update { it.copy(error = null, interruptedTurn = false) }

    /** UI-originated user-visible failures (e.g. unreadable attachment) share the error banner. */
    fun surfaceError(message: String) = fail(message)

    fun clearNotice() = _state.update { it.copy(notice = null) }

    // ----- Codex (Sign in with ChatGPT) -----

    private fun beginLease(slot: AtomicReference<String?>, prefix: String): String {
        val id = "$prefix-${UUID.randomUUID()}"
        foregroundLeases.acquire(id)
        slot.getAndSet(id)?.let(foregroundLeases::release)
        return id
    }

    private fun endLease(slot: AtomicReference<String?>, id: String? = slot.get()): Boolean {
        if (id == null) return false
        val endedCurrent = slot.compareAndSet(id, null)
        foregroundLeases.release(id)
        return endedCurrent
    }

    /**
     * Starts the Codex OAuth flow: spins up the loopback listener and returns the authorization URL
     * for the UI to open in the browser. The exchange completes asynchronously; state flips when done.
     */
    fun startCodexSignIn(): String? {
        val lease = beginLease(authLease, "codex-auth")
        return runCatching {
            val url = codexAuth.buildAuthUrl()
            val verifier = requireNotNull(codexAuth.pendingVerifier)
            val expectedState = requireNotNull(codexAuth.pendingState)
            codexAuth.startLoopback(
                expectedState = expectedState,
                onError = { message ->
                    viewModelScope.launch(Dispatchers.IO) {
                        _state.update { it.copy(error = "Codex sign-in failed: $message") }
                        endLease(authLease, lease)
                    }
                },
            ) { code ->
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { codexAuth.exchangeCode(code, verifier) }
                        .onSuccess {
                            _state.update { it.copy(codexConnected = true, notice = "Signed in with ChatGPT - pick a ChatGPT model from the model menu") }
                            refreshModels(forceRefresh = true)
                        }
                        .onFailure { e ->
                            codexAuth.stopLoopback()
                            _state.update { it.copy(error = "Codex sign-in failed: ${e.message}") }
                        }
                    endLease(authLease, lease)
                }
            }
            viewModelScope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(5 * 60_000L)
                if (endLease(authLease, lease)) {
                    codexAuth.stopLoopback()
                }
            }
            url
        }.getOrElse { e ->
            codexAuth.stopLoopback()
            endLease(authLease, lease)
            _state.update { it.copy(error = "Codex sign-in failed: ${e.message}") }
            null
        }
    }

    fun signOutCodex() {
        codexAuth.stopLoopback()
        endLease(authLease)
        codexAuth.signOut() // CodexAuth owns its key names - don't duplicate them here (matches signOutGitHub)
        _state.update { state ->
            val selected = if (state.selected?.providerId == "codex") {
                state.models.firstOrNull {
                    it.providerId != "codex" && it.providerId !in state.disabledProviders && modelKey(it) !in state.hiddenModels
                }
            } else {
                state.selected
            }
            state.copy(codexConnected = false, selected = selected, contextLimit = limitFor(selected)?.context)
        }
    }

    // ----- GitHub (device-flow sign-in: code on screen, no tokens to paste) -----

    private val githubAuth by lazy { GitHubAuth(http, store = keyStore::put, read = keyStore::get) }
    @Volatile private var githubSignInActive = false

    fun startGitHubSignIn() {
        if (githubSignInActive) return
        githubSignInActive = true
        val lease = beginLease(githubAuthLease, "github-auth")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                runCatching {
                    val device = githubAuth.startDeviceFlow()
                    _state.update { it.copy(githubAuthCode = device.userCode, githubVerifyUri = device.verificationUri) }
                    val token = githubAuth.pollForToken(device) { githubSignInActive }
                    val login = githubAuth.fetchLogin(token)
                    _state.update { it.copy(githubLogin = login, githubAuthCode = null, githubVerifyUri = null, notice = "Signed in as @$login") }
                }.onFailure { e ->
                    _state.update {
                        it.copy(
                            githubAuthCode = null,
                            githubVerifyUri = null,
                            error = if (e is GitHubAuth.SignInAbandonedException) null else "GitHub sign-in failed: ${e.message}",
                        )
                    }
                }
            } finally {
                githubSignInActive = false
                endLease(githubAuthLease, lease)
            }
        }
    }

    fun cancelGitHubSignIn() {
        githubSignInActive = false
        endLease(githubAuthLease)
        _state.update { it.copy(githubAuthCode = null, githubVerifyUri = null) }
    }

    fun signOutGitHub() {
        endLease(githubAuthLease)
        githubAuth.signOut()
        _state.update { it.copy(githubLogin = null) }
    }

    // ----- Export / import (Storage Access Framework) -----

    fun exportTo(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    TransferBundle.export(getApplication<Application>().filesDir, out)
                } ?: error("could not open destination")
            }
                .onSuccess { _state.update { it.copy(notice = "Backup exported") } }
                .onFailure { e -> _state.update { it.copy(error = "Export failed: ${e.message}") } }
        }
    }

    fun importFrom(uri: android.net.Uri, onRestored: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    TransferBundle.import(getApplication<Application>().filesDir, input)
                } ?: error("could not open file")
            }
                .onSuccess { count ->
                    refreshSessions()
                    reloadProviders()
                    // The import overwrote model_prefs.json and app_settings.json on disk; the
                    // live state must reflect the RESTORED values, not the pre-import ones
                    // (review finding: otherwise prefs/toggles look broken until a restart).
                    val saved = appSettings.load()
                    _state.update {
                        it.copy(
                            favourites = modelPrefs.favourites(),
                            hiddenModels = modelPrefs.hiddenModels(),
                            disabledProviders = modelPrefs.disabledProviders(),
                            autoAccept = saved.autoAccept,
                            agentMode = runCatching { AgentMode.valueOf(saved.defaultMode) }.getOrDefault(AgentMode.BUILD),
                            notice = "Restored $count file(s)",
                        )
                    }
                    onRestored()
                }
                .onFailure { e -> _state.update { it.copy(error = "Import failed: ${e.message}") } }
        }
    }
    fun resolvePermission(approved: Boolean) { pendingDecision?.complete(approved) }
    fun resolveQuestion(answers: List<UserAnswer>) { pendingQuestionDecision?.complete(answers) }

    private fun connectedProvider(preset: ProviderPreset): LlmProvider? {
        val key = if (preset.id == "codex") codexAuth.accessToken() else keyStore.get(preset.id)
        if (key.isNullOrBlank()) return null
        val resolved = if (preset.id == "codex") {
            codexAuth.accountId()
                ?.let { preset.copy(extraHeaders = preset.extraHeaders + ("chatgpt-account-id" to it)) }
                ?: preset
        } else {
            preset
        }
        return ProviderFactory.create(resolved, key, http)
    }

    private suspend fun askPermission(tool: String, summary: String): Boolean {
        // Authoritative read from the persisted settings file - the same source the settings
        // toggle displays. The in-memory copy diverged on devices that carried an older value
        // (device feedback: "auto-accept on even though it's off in settings").
        if (withContext(Dispatchers.IO) { appSettings.load().autoAccept }) return true
        val deferred = CompletableDeferred<Boolean>()
        pendingDecision = deferred
        _state.update { it.copy(pendingPermission = PermissionRequest(tool, summary)) }
        return try {
            deferred.await()
        } finally {
            // Only clear if still current - a cancel->resend can install a newer deferred before this runs.
            if (pendingDecision === deferred) {
                pendingDecision = null
                _state.update { it.copy(pendingPermission = null) }
            }
        }
    }

    /** Suspend until the user answers the agent's question(s). Cancelling the turn resolves them as unanswered. */
    private suspend fun askUser(questions: List<UserQuestion>): List<UserAnswer> {
        val deferred = CompletableDeferred<List<UserAnswer>>()
        pendingQuestionDecision = deferred
        _state.update { it.copy(pendingQuestion = QuestionRequest(questions)) }
        return try {
            deferred.await()
        } finally {
            if (pendingQuestionDecision === deferred) {
                pendingQuestionDecision = null
                _state.update { it.copy(pendingQuestion = null) }
            }
        }
    }

    /**
     * Runs a [TaskTool] subagent: a fresh child [AgentLoop] on the same provider, with `task` and
     * plan_exit removed (no recursion, no UI-mode side effects) and inheriting the parent's live mode
     * (so a PLAN parent can only spawn a read-only child). Returns the child's accumulated text.
     */
    private suspend fun runSubagent(description: String, prompt: String, subagentType: String): String {
        val selected = _state.value.selected ?: return "no model selected"
        val preset = providerFor(selected.providerId) ?: return "unknown provider: ${selected.providerId}"
        val provider = connectedProvider(preset)
            ?: return if (preset.id == "codex") "ChatGPT sign-in expired" else "no API key configured for ${preset.displayName}"
        val parentMode = _state.value.agentMode // capture so the child can't escalate PLAN->BUILD mid-subtask
        val childConfig = AgentConfig(
            model = selected.modelId,
            mode = parentMode,
            environment = environment(),
            reasoningEffort = _state.value.effort,
            skills = discoveredSkills.map { SkillInfo(it.name, it.description) },
            sessionId = "phonecode-sub",
        )
        val childTools = ToolRegistry(tools.all().filterNot { it.name == "task" || it.planOnly })
        val childLoop = AgentLoop(
            provider, childTools, toolContext, childConfig,
            modeProvider = { parentMode },
        )
        val out = StringBuilder()
        var childError: String? = null
        childLoop.run(emptyList(), prompt).collect { event ->
            when (event) {
                is AgentEvent.TextDelta -> out.append(event.text)
                is AgentEvent.Error -> childError = event.message // surface child failure instead of a blank result
                else -> Unit
            }
        }
        return childError?.let { "subagent error: $it" } ?: out.toString()
    }

    fun send(input: String, images: List<MessagePart.Image> = emptyList()) {
        val text = input.trim()
        if (text.isEmpty() && images.isEmpty()) return
        if (_state.value.isRunning) {
            if (images.isNotEmpty()) return fail("Wait for the current turn before sending a photo.")
            // Queue it for the running turn instead of dropping it; the agent picks it up at its next step.
            pendingMessages.add(text)
            _state.update { it.copy(queued = it.queued + text) }
            return
        }
        val selected = _state.value.selected ?: return fail("Select a model first.")
        val preset = providerFor(selected.providerId) ?: return fail("Unknown provider: ${selected.providerId}")
        // Codex authenticates with the ChatGPT OAuth token (not an API key); gate on being signed in here,
        // then resolve a fresh token off the main thread inside the turn (accessToken() may refresh, i.e. hit
        // the network). Every other provider uses its stored API key directly.
        val isCodex = preset.id == "codex"
        if (keyStore.get(if (isCodex) "codex.access" else selected.providerId).isNullOrBlank()) {
            return fail(if (isCodex) "Sign in with ChatGPT in Settings to use Codex." else "Set an API key for ${preset.displayName} in Settings.")
        }

        _state.update {
            it.copy(
                lines = it.lines + ChatLine.User(text, images),
                streaming = "",
                streamingReasoning = "",
                isRunning = true,
                retry = null,
                error = null,
                interruptedTurn = false,
            )
        }
        // Foreground lease for the whole turn: without it the OS suspends the process shortly
        // after screen-off and the streaming HTTP call dies (device feedback).
        val lease = beginLease(turnLease, "turn")

        val startingHistory = history
        val userParts = buildList {
            if (text.isNotEmpty()) add(MessagePart.Text(text))
            addAll(images)
        }
        val turnSessionId = sessionId
        val turnProjectId = currentProjectId
        val gen = ++generation
        // Pin this turn's workspace so a mid-stream project move/delete can't redirect the agent's
        // file/git tools into a different directory (data-integrity guard).
        val pinnedWorkspace = workspace
        turnWorkspace = pinnedWorkspace
        // Everything below the state update runs off the main thread - the settings read is disk I/O,
        // and tool execution does file I/O; StateFlow updates are thread-safe.
        // The generation guard drops events from a cancelled/superseded turn; one owner clears terminal state.
        // APPLICATION scope, not viewModelScope: the turn must outlive the activity/VM (closing
        // the app or locking the phone killed responses mid-stream - device feedback). The
        // session persists on TurnComplete, so a reopened app restores the finished reply.
        job = (getApplication<Application>() as PhoneCodeApplication).turnScope.launch {
            // Persist the user's message to history + disk right now, so a process kill mid-turn (Android
            // does this) doesn't drop it - history was otherwise only written when the turn completed, so an
            // interrupted first turn restored as a blank chat. loop.run() re-appends it from startingHistory,
            // so it is not duplicated; TurnComplete later overwrites history with the full turn.
            if (gen == generation) {
                val turnHistory = startingHistory + ChatMessage(Role.USER, userParts)
                history = turnHistory
                persist(turnHistory, activeTurn = true, targetSessionId = turnSessionId, targetProjectId = turnProjectId)
            }
            val custom = appSettings.load().customInstructions.trim()
            // Drive the reasoning param off the model's own "thinking" config (from the models.dev catalog -
            // OpenCode's source). Send an effort only to models that actually reason; force DEFAULT otherwise
            // so we never send a control a model rejects.
            val reasons = supportsReasoning(selected)
            val config = AgentConfig(
                model = selected.modelId,
                mode = _state.value.agentMode,
                environment = environment(),
                reasoningEffort = if (reasons) _state.value.effort else ReasoningEffort.DEFAULT,
                skills = discoveredSkills.map { SkillInfo(it.name, it.description) },
                sessionId = turnSessionId,
                projectInstructions = if (custom.isNotEmpty()) listOf(custom) else emptyList(),
            )
            val limit = limitFor(selected) // context/output token limits drive the gauge + compaction
            try {
                val provider = connectedProvider(preset)
                if (provider == null) {
                    sessionStore.setActiveTurn(turnSessionId, false)
                    fail(if (isCodex) "Sign in with ChatGPT again in Settings." else "Set an API key for ${preset.displayName} in Settings.")
                    return@launch
                }
                val loop = AgentLoop(
                    provider, tools, toolContext, config,
                    steering = queueSource, // messages queued mid-turn are picked up at the next step (steer)
                    followUp = queueSource, // ...or run as a follow-up turn if queued right as the turn ends
                    turnSettings = { TurnSettings(config.model, if (reasons) _state.value.effort else ReasoningEffort.DEFAULT, limit?.context, limit?.output) },
                    modeProvider = { _state.value.agentMode }, // live so a plan_exit approval flips PLAN→BUILD mid-run
                )
                if (startingHistory.isEmpty()) autoBranchIfEnabled(pinnedWorkspace, turnSessionId)
                loop.run(startingHistory, userParts).collect { event ->
                    if (gen == generation) reduce(event, turnSessionId, turnProjectId, selected.providerId)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (gen == generation) {
                    _state.update {
                        it.copy(
                            error = "The turn stopped unexpectedly: ${humanizeError(error.message ?: error.javaClass.simpleName)} Review workspace changes before retrying.",
                            interruptedTurn = true,
                        )
                    }
                }
            } finally {
                if (gen == generation) {
                    turnWorkspace = null
                    commitStreaming()
                    _state.update { it.copy(isRunning = false, retry = null, lastCompletedAt = System.currentTimeMillis()) }
                }
                endLease(turnLease, lease)
            }
        }
    }

    /**
     * Re-run the last user message as a fresh turn. The conversation is REWOUND to just before
     * that message first - otherwise the model would see its previous answer in context and the
     * timeline would show the question twice (review finding: redo must regenerate, not re-ask).
     * The cut targets the last HUMAN prompt: tool-RESULT messages also carry Role.USER, and
     * cutting at one of those would leave a dangling tool_use that strict providers reject
     * (verification finding). timelineEpoch tells the chat list its index-key caches are stale.
     */
    fun redo() {
        if (_state.value.isRunning) return
        val lastUser = _state.value.lines.filterIsInstance<ChatLine.User>().lastOrNull() ?: return
        val historyCut = redoCutIndex(history)
        if (historyCut >= 0) history = history.take(historyCut)
        val lineCut = _state.value.lines.indexOfLast { it is ChatLine.User }
        if (lineCut >= 0) _state.update { it.copy(lines = it.lines.take(lineCut), timelineEpoch = it.timelineEpoch + 1) }
        send(lastUser.text, lastUser.images)
    }

    fun cancel() {
        generation++ // invalidate the in-flight turn's events immediately, then clean up here (single owner)
        pendingMessages.clear() // stop means stop: don't let queued messages auto-run after a cancel
        // Cancel the job FIRST so an awaiting tool unwinds via CancellationException (no extra turn/side-effect);
        // completing the deferreds is then only a fallback to resume anything not yet at a cancellation point.
        job?.cancel()
        endLease(turnLease)
        // The cancelled job's finally skips the pin clear (generation moved on) - release it here
        // so no stale workspace pin outlives the turn.
        turnWorkspace = null
        pendingDecision?.complete(false)
        pendingQuestionDecision?.complete(emptyList())
        commitStopped()
        sessionStore.setActiveTurn(sessionId, false)
        _state.update { it.copy(isRunning = false, retry = null, pendingPermission = null, pendingQuestion = null, queued = emptyList(), interruptedTurn = false) }
    }

    /**
     * Flush a cancelled turn's partial reply into BOTH the visible lines and history. The turn never
     * reached TurnComplete, so its streamed text lived only in the streaming buffer - history was left
     * ending on a bare user message, which read as lost context next message (and which Anthropic rejects
     * as two user turns in a row). Writing the partial assistant reply keeps the model's view = the screen.
     */
    private fun commitStopped() {
        val s = _state.value
        commitStreaming() // streaming buffer -> visible lines (unchanged behaviour)
        if (s.streaming.isNotBlank() && history.lastOrNull()?.role == Role.USER) {
            val parts = buildList {
                if (s.streamingReasoning.isNotBlank()) add(MessagePart.Reasoning(s.streamingReasoning))
                add(MessagePart.Text(s.streaming))
            }
            history = history + ChatMessage(Role.ASSISTANT, parts)
            persist()
        }
    }

    private fun reduce(
        event: AgentEvent,
        targetSessionId: String,
        targetProjectId: String?,
        targetProviderId: String,
    ) {
        when (event) {
            is AgentEvent.TextDelta -> _state.update { it.copy(streaming = it.streaming + event.text, retry = null) }
            is AgentEvent.ReasoningDelta -> _state.update { it.copy(streamingReasoning = it.streamingReasoning + event.text, retry = null) }
            is AgentEvent.Retrying -> _state.update {
                it.copy(retry = RetryState(event.attempt, event.message.take(100)))
            }
            is AgentEvent.HistoryCheckpoint -> {
                history = event.messages
                persist(event.messages, activeTurn = true, targetSessionId = targetSessionId, targetProjectId = targetProjectId)
            }
            is AgentEvent.ToolStarted -> {
                commitStreaming()
                _state.update {
                    it.copy(
                        retry = null,
                        lines = it.lines + ChatLine.ToolActivity(
                            event.id, event.name, ToolStatus.RUNNING, summarizeArgs(event.argsJson),
                        ),
                    )
                }
            }
            is AgentEvent.ToolFinished -> _state.update { state ->
                // Update only the most recent RUNNING line with this id (synthetic ids can repeat across turns).
                val index = state.lines.indexOfLast {
                    it is ChatLine.ToolActivity && it.id == event.id && it.status == ToolStatus.RUNNING
                }
                if (index < 0) {
                    state
                } else {
                    val updated = state.lines.toMutableList()
                    updated[index] = (updated[index] as ChatLine.ToolActivity).copy(
                        status = if (event.isError) ToolStatus.ERROR else ToolStatus.DONE,
                        detail = event.output.take(300),
                    )
                    state.copy(lines = updated)
                }
            }
            // Latest turn's tokens = current context occupancy (input already includes history), not a session sum.
            is AgentEvent.Usage -> _state.update { it.copy(usageInput = event.input, usageOutput = event.output, retry = null) }
            is AgentEvent.Compacted -> Unit
            is AgentEvent.UserMessage -> {
                // The agent just folded a queued message into the turn: flush the live reply, drop the
                // message into the timeline in order, and clear it from the pending list.
                commitStreaming()
                _state.update { it.copy(lines = it.lines + ChatLine.User(event.text), queued = it.queued - event.text) }
            }
            is AgentEvent.Error -> {
                // A failed turn that carried its accumulated messages preserves context (and persists it) so
                // the next message continues the conversation instead of starting cold after a connection drop.
                if (event.messages.isNotEmpty()) {
                    history = event.messages
                    commitStreaming()
                    persist(event.messages, targetSessionId = targetSessionId, targetProjectId = targetProjectId)
                } else {
                    commitStreaming()
                    sessionStore.setActiveTurn(targetSessionId, false)
                }
                _state.update { it.copy(error = humanizeError(event, targetProviderId), isRunning = false, retry = null, interruptedTurn = false) }
            }
            is AgentEvent.TurnComplete -> {
                history = event.messages
                commitStreaming()
                persist(event.messages, targetSessionId = targetSessionId, targetProjectId = targetProjectId) // runs on the IO collector thread; survives app restart
                _state.update { it.copy(retry = null, interruptedTurn = false) }
            }
        }
    }

    /** Save the current conversation to disk. Title = first user line; no-op for an empty history. */
    private fun persist(
        snapshot: List<ChatMessage> = history,
        activeTurn: Boolean = false,
        targetSessionId: String = sessionId,
        targetProjectId: String? = currentProjectId,
    ) {
        if (snapshot.isEmpty()) return
        val suggestedTitle = snapshot.firstOrNull { it.role == Role.USER }
            ?.parts?.filterIsInstance<MessagePart.Text>()?.firstOrNull()?.text?.take(40)?.takeIf { it.isNotBlank() }
            ?: "New chat"
        runCatching {
            val stored = sessionStore.load(targetSessionId)
            val title = stored?.title?.takeUnless { it == "New chat" } ?: suggestedTitle
            sessionStore.save(
                PersistedSession(
                    id = targetSessionId,
                    title = title,
                    updatedAt = System.currentTimeMillis(),
                    messages = snapshot.map { it.toPersisted() },
                    projectId = if (stored == null) targetProjectId else stored.projectId,
                    pinned = stored?.pinned ?: false,
                    archived = stored?.archived ?: false,
                    activeTurn = activeTurn,
                ),
            )
            _state.update { it.copy(sessions = sessionStore.list()) }
        }
    }

    /** Rebuild the visible timeline from persisted history, merging each tool result into its tool-call line. */
    private fun List<ChatMessage>.toChatLines(): List<ChatLine> {
        val lines = mutableListOf<ChatLine>()
        for (message in this) {
            if (message.role == Role.USER) {
                val text = message.parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text }
                val images = message.parts.filterIsInstance<MessagePart.Image>()
                if (text.isNotEmpty() || images.isNotEmpty()) lines += ChatLine.User(text, images)
            }
            for (part in message.parts) {
                when (part) {
                    is MessagePart.Text ->
                        if (message.role == Role.ASSISTANT) lines += ChatLine.Assistant(part.text)
                    is MessagePart.Image -> Unit
                    is MessagePart.Reasoning -> lines += ChatLine.Reasoning(part.text)
                    is MessagePart.ToolCall ->
                        lines += ChatLine.ToolActivity(part.id, part.name, ToolStatus.DONE, summarizeArgs(part.argsJson))
                    is MessagePart.ToolResult -> {
                        val index = lines.indexOfLast { it is ChatLine.ToolActivity && it.id == part.callId }
                        if (index >= 0) {
                            lines[index] = (lines[index] as ChatLine.ToolActivity).copy(
                                status = if (part.isError) ToolStatus.ERROR else ToolStatus.DONE,
                                detail = part.content.take(300),
                            )
                        }
                    }
                }
            }
        }
        return lines
    }

    private fun commitStreaming() = _state.update { s ->
        var lines = s.lines
        if (s.streamingReasoning.isNotBlank()) lines = lines + ChatLine.Reasoning(s.streamingReasoning)
        if (s.streaming.isNotBlank()) lines = lines + ChatLine.Assistant(s.streaming)
        if (lines === s.lines) s else s.copy(lines = lines, streaming = "", streamingReasoning = "")
    }

    private fun fail(message: String) = _state.update { it.copy(error = humanizeError(message), interruptedTurn = false) }

    /**
     * Raw transport errors read as developer noise on a phone ("Unable to resolve host...").
     * Map the common classes to plain language; anything unrecognized passes through untouched.
     */
    private fun humanizeError(raw: String): String {
        val lower = raw.lowercase()
        return when {
            "unable to resolve host" in lower || "unknownhost" in lower ->
                "No connection - check your internet and try again."
            "timeout" in lower || "timed out" in lower ->
                "The request timed out - the provider may be slow or your connection unstable."
            "connection" in lower && ("refused" in lower || "reset" in lower || "abort" in lower) ->
                "Connection lost - check your internet and try again."
            "401" in lower || "unauthorized" in lower || "invalid api key" in lower || "invalid x-api-key" in lower ->
                "The provider rejected your API key - check it in Settings > Providers."
            "429" in lower || "rate limit" in lower ->
                "Rate limited by the provider - wait a moment and try again."
            "high-frequency" in lower || "non-compliant requests" in lower ->
                "The provider temporarily blocked this request - wait a few minutes and try again."
            "overloaded" in lower || "529" in lower ->
                "The provider is overloaded right now - try again shortly."
            else -> raw
        }
    }

    private fun humanizeError(error: AgentEvent.Error, providerId: String): String {
        val retry = error.retryAfterMillis?.let { " Try again in ${formatDuration(it)}." }.orEmpty()
        return when (error.kind) {
            FailureKind.AUTH -> if (providerId == "codex") {
                "Your ChatGPT sign-in expired. Sign in again in Settings > Providers."
            } else {
                "The provider rejected your API key. Check it in Settings > Providers."
            }
            FailureKind.RATE_LIMIT -> "The provider is rate limiting requests.$retry"
            FailureKind.QUOTA -> if (providerId == "opencode-go") {
                "OpenCode Go usage limit reached.$retry You can enable Zen balance fallback in the OpenCode console."
            } else {
                "The provider usage limit has been reached.$retry"
            }
            FailureKind.INVALID_REQUEST -> error.message
            FailureKind.SERVER -> "The provider is unavailable right now.$retry"
            FailureKind.NETWORK -> humanizeError(error.message)
            FailureKind.PARSE -> "The provider returned an unreadable response. Try again or switch models."
            FailureKind.UNKNOWN -> humanizeError(error.message)
        }
    }

    private fun environment(): AgentEnvironment {
        val u = userland
        val base = if (u.applets.isNotEmpty()) {
            "busybox ash with ${u.applets.size} applets + Android toybox; " +
                "HOME=${u.env["HOME"]}, TMPDIR=${u.env["TMPDIR"]}, PREFIX=${u.env["PREFIX"]}"
        } else {
            "Android toybox /system/bin/sh (ls, cat, grep, sed, find, ps, tar, ...); " +
                "HOME=${u.env["HOME"]}, TMPDIR=${u.env["TMPDIR"]}"
        }
        // Tell the model a real package manager is reachable, and steer it to install rather than improvise.
        val linux = when {
            u.linuxReady() -> ". A full Alpine Linux is active via proot: install what you need with " +
                "`apk add python3 py3-pip nodejs ...` and use it (cwd is your workspace, so installed tools " +
                "edit the same files as the file tools). Prefer installing the real tool over improvising one " +
                "from busybox (e.g. `python3 -m http.server`, not an `nc` loop)."
            u.linuxAvailable -> ". The bundled Alpine Linux environment is being prepared. The first shell command " +
                "waits for setup, then `apk add python3 py3-pip nodejs npm build-base ...` can install real tools."
            else -> ""
        }
        val projectFolder = _state.value.projects.firstOrNull { it.id == currentProjectId }?.folderId?.let { folderId ->
            _state.value.sharedFolders.firstOrNull { it.id == folderId }
        }
        val projectDetail = projectFolder?.let {
            ". The active Project is linked to the phone folder '${it.name}' through shared_files root='${it.id}'."
        }.orEmpty()
        return AgentEnvironment(
            platform = "Android",
            deviceModel = Build.MODEL ?: "unknown",
            osVersion = "API ${Build.VERSION.SDK_INT}",
            // Match toolContext's workspaceProvider: a pinned turn workspace takes precedence over the live one,
            // so the path the prompt reports is the path tools actually write to.
            workspacePath = (turnWorkspace ?: workspace).absolutePath,
            shellAvailable = true,
            shellDetail = base + linux + projectDetail,
            configPath = File(getApplication<Application>().filesDir, "config").absolutePath,
        )
    }

    private fun summarizeArgs(argsJson: String): String = argsJson.replace("\n", " ").take(120)

    override fun onCleared() {
        // Stop background daemons promptly: the GitHub poll thread checks this flag every ≤500ms,
        // and the Codex loopback listener would otherwise hold port 1455 until its 5-min timeout.
        githubSignInActive = false
        codexAuth.stopLoopback()
        endLease(authLease)
        endLease(githubAuthLease)
        super.onCleared()
    }
}

/**
 * The rewind point for redo: the last HUMAN prompt - a Role.USER message carrying Text. Tool
 * RESULTS also ride Role.USER (loop convention), and cutting at one of those would orphan the
 * preceding tool_use. Pure function so the shape is unit-testable.
 */
internal fun redoCutIndex(history: List<ChatMessage>): Int =
    history.indexOfLast { m -> m.role == Role.USER && m.parts.any { it is MessagePart.Text } }

internal fun repairInterruptedHistory(history: List<ChatMessage>): List<ChatMessage> {
    val unresolved = linkedMapOf<String, MessagePart.ToolCall>()
    history.forEach { message ->
        message.parts.forEach { part ->
            when (part) {
                is MessagePart.ToolCall -> unresolved[part.id] = part
                is MessagePart.ToolResult -> unresolved.remove(part.callId)
                else -> Unit
            }
        }
    }
    if (unresolved.isEmpty()) return history
    return history + ChatMessage(
        Role.USER,
        unresolved.values.map {
            MessagePart.ToolResult(
                callId = it.id,
                content = "Interrupted before PhoneCode recorded the result. Review workspace changes before retrying.",
                isError = true,
            )
        },
    )
}

private const val TURN_INTERRUPTED_MESSAGE =
    "The previous turn stopped unexpectedly. Review any file changes before retrying."

internal fun formatDuration(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0) + 999) / 1_000
    val hours = seconds / 3_600
    val minutes = seconds % 3_600 / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
    }
}

internal fun catalogProviderId(id: String): String = when (id) {
    "opencode-zen" -> "opencode"
    "codex" -> "openai"
    else -> id
}

fun builtInModels(): List<ModelOption> = listOf(
    ModelOption("anthropic", "claude-opus-4-8", "Claude Opus 4.8"),
    ModelOption("anthropic", "claude-sonnet-4-6", "Claude Sonnet 4.6"),
    ModelOption("anthropic", "claude-haiku-4-5", "Claude Haiku 4.5"),
    ModelOption("openai", "gpt-5.6", "GPT-5.6"),
    ModelOption("openai", "gpt-5.5", "GPT-5.5"),
    ModelOption("openai", "o3", "o3"),
    ModelOption("openrouter", "anthropic/claude-opus-4-8", "OpenRouter · Claude Opus 4.8"),
    ModelOption("opencode-zen", "nemotron-3-ultra-free", "Zen · Nemotron 3 Ultra (Free)"),
    ModelOption("opencode-go", "deepseek-v4-flash", "Go · DeepSeek V4 Flash"),
    ModelOption("opencode-go", "mimo-v2.5", "Go · MiMo V2.5"),
    ModelOption("google", "gemini-2.5-pro", "Gemini 2.5 Pro"),
    ModelOption("google", "gemini-2.0-flash", "Gemini 2.0 Flash"),
    ModelOption("xai", "grok-2-latest", "Grok 2"),
    ModelOption("deepseek", "deepseek-chat", "DeepSeek Chat"),
    ModelOption("deepseek", "deepseek-reasoner", "DeepSeek Reasoner"),
    ModelOption("mistral", "mistral-large-latest", "Mistral Large"),
    ModelOption("codex", "gpt-5.6-sol", "ChatGPT · GPT-5.6 Sol"),
    ModelOption("codex", "gpt-5.6-terra", "ChatGPT · GPT-5.6 Terra"),
    ModelOption("codex", "gpt-5.6-luna", "ChatGPT · GPT-5.6 Luna"),
    ModelOption("codex", "gpt-5.5", "ChatGPT · GPT-5.5"),
    ModelOption("codex", "gpt-5.4", "ChatGPT · GPT-5.4"),
    ModelOption("codex", "gpt-5.4-mini", "ChatGPT · GPT-5.4 Mini"),
    ModelOption("codex", "gpt-5.2", "ChatGPT · GPT-5.2"),
)

private const val CATALOG_REFRESH_TTL_MS = 6L * 60 * 60 * 1000
private const val CODEX_REFRESH_TTL_MS = 5L * 60 * 1000
private const val BUNDLED_CATALOG = """
{
  "openai":{"id":"openai","name":"OpenAI","models":{"gpt-5.6":{"id":"gpt-5.6","name":"GPT-5.6","reasoning":true,"reasoning_options":[{"type":"effort","values":["none","low","medium","high","xhigh","max"]}],"tool_call":true,"attachment":true,"limit":{"context":1050000,"output":128000}},"gpt-5.6-sol":{"id":"gpt-5.6-sol","name":"GPT-5.6 Sol","reasoning":true,"reasoning_options":[{"type":"effort","values":["none","low","medium","high","xhigh","max"]}],"tool_call":true,"attachment":true,"limit":{"context":1050000,"output":128000}},"gpt-5.6-terra":{"id":"gpt-5.6-terra","name":"GPT-5.6 Terra","reasoning":true,"reasoning_options":[{"type":"effort","values":["none","low","medium","high","xhigh","max"]}],"tool_call":true,"attachment":true,"limit":{"context":1050000,"output":128000}},"gpt-5.6-luna":{"id":"gpt-5.6-luna","name":"GPT-5.6 Luna","reasoning":true,"reasoning_options":[{"type":"effort","values":["none","low","medium","high","xhigh","max"]}],"tool_call":true,"attachment":true,"limit":{"context":1050000,"output":128000}},"gpt-5.5":{"id":"gpt-5.5","name":"GPT-5.5"},"o3":{"id":"o3","name":"o3"}}},
  "anthropic":{"id":"anthropic","name":"Anthropic","models":{"claude-opus-4-8":{"id":"claude-opus-4-8","name":"Claude Opus 4.8"},"claude-sonnet-4-6":{"id":"claude-sonnet-4-6","name":"Claude Sonnet 4.6"},"claude-haiku-4-5":{"id":"claude-haiku-4-5","name":"Claude Haiku 4.5"}}},
  "openrouter":{"id":"openrouter","name":"OpenRouter","models":{"anthropic/claude-opus-4-8":{"id":"anthropic/claude-opus-4-8","name":"Claude Opus 4.8"}}},
  "opencode":{"id":"opencode","name":"OpenCode Zen","models":{"nemotron-3-ultra-free":{"id":"nemotron-3-ultra-free","name":"Nemotron 3 Ultra Free"}}},
  "opencode-go":{"id":"opencode-go","name":"OpenCode Go","api":"https://opencode.ai/zen/go/v1","models":{"deepseek-v4-flash":{"id":"deepseek-v4-flash","name":"DeepSeek V4 Flash","reasoning":true,"reasoning_options":[{"type":"effort","values":["high","max"]}],"tool_call":true,"attachment":false,"limit":{"context":1000000,"output":384000}},"mimo-v2.5":{"id":"mimo-v2.5","name":"MiMo V2.5","reasoning":true,"tool_call":true,"attachment":true,"limit":{"context":1000000,"output":128000}}}}
}
"""
