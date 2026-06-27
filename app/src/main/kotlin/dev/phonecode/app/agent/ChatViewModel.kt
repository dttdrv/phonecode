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
import dev.phonecode.app.data.TransferBundle
import dev.phonecode.app.data.toDomain
import dev.phonecode.app.data.toPreset
import dev.phonecode.app.data.toPersisted
import dev.phonecode.provider.catalog.Catalog
import dev.phonecode.provider.catalog.CatalogLoader
import dev.phonecode.provider.domain.ChatMessage
import dev.phonecode.provider.domain.MessagePart
import dev.phonecode.provider.domain.ReasoningEffort
import dev.phonecode.provider.domain.Role
import dev.phonecode.provider.http.ProviderFactory
import dev.phonecode.provider.preset.BuiltInPresets
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
import java.util.concurrent.TimeUnit

data class ModelOption(val providerId: String, val modelId: String, val label: String)

enum class ToolStatus { RUNNING, DONE, ERROR }

data class PermissionRequest(val tool: String, val summary: String)

data class QuestionRequest(val questions: List<UserQuestion>)

sealed interface ChatLine {
    data class User(val text: String) : ChatLine
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
        .readTimeout(180, TimeUnit.SECONDS)
        .build()
    private val todoStore = TodoStore()
    private val configDir = File(app.filesDir, "config")
    private val repo = McpSkillRepository(configDir)
    private val customProviders = CustomProviderRepository(configDir)
    private val baseTools: List<Tool> =
        defaultFileTools() + ApplyPatchTool() + ExternalDirectoryTool() + QuestionTool() +
            PlanExitTool { setAgentMode(AgentMode.BUILD) } + todoTools(todoStore) +
            WebFetchTool(http) + WebSearchTool(http) + TaskTool(::runSubagent) + gitTools { gitCredentials() } +
            // Real terminal access: busybox userland over Android's toybox, transparently upgrading to a
            // full Alpine Linux (proot) once its rootfs is set up - sandbox-scoped, permission-gated like
            // every mutating tool. Providers are dynamic: shell()/shellEnv() re-resolve each call so the
            // shell flips from busybox to Linux the moment the background rootfs setup finishes.
            dev.phonecode.tools.shell.ShellTool({ userland.shell() }, { userland.shellEnv() })
    @Volatile private var mcpTools: List<Tool> = emptyList()
    @Volatile private var discoveredSkills: List<SkillManifest> = emptyList()
    // Registry is replaced wholesale (not mutated) so send()/runSubagent always read a consistent snapshot.
    @Volatile private var tools = ToolRegistry(baseTools)
    // MUST be initialized before the init block below: the MCP-connect coroutine it launches calls
    // rebuildTools() and can run before a later-declared field's initializer executes (NPE at launch).
    private val toolsLock = Any()
    private val toolContext = AndroidToolContext({ (turnWorkspace ?: workspace).absolutePath }, ::askPermission, ::askUser)
    private val catalogLoader = CatalogLoader(http, FileCatalogCache(app.cacheDir), bundledFallback = { BUNDLED_CATALOG })
    @Volatile private var catalog: dev.phonecode.provider.catalog.Catalog = emptyMap()
    @Volatile private var customPresets: Map<String, ProviderPreset> = emptyMap()
    @Volatile private var customLimits: Map<String, Long> = emptyMap()

    /** Resolve a provider id against the built-ins, then any agent-defined custom providers. */
    private fun providerFor(id: String): ProviderPreset? = BuiltInPresets.byId(id) ?: customPresets[id]

    /** All providers for Settings: built-ins plus any agent-defined custom providers. */
    fun allProviders(): List<ProviderPreset> = BuiltInPresets.all + customPresets.values.sortedBy { it.displayName }

    /** The selected model's token limits from the models.dev catalog, then the custom config, if known. */
    private fun limitFor(option: ModelOption?): dev.phonecode.provider.catalog.Limit? = option?.let {
        catalog[it.providerId]?.models?.get(it.modelId)?.limit
            ?: customLimits["${it.providerId}/${it.modelId}"]?.let { c -> dev.phonecode.provider.catalog.Limit(context = c) }
            // Codex model ids ARE OpenAI ids (catalog lists them under "openai"); reuse their real limits so
            // compaction fires correctly, falling back to the GPT-5 window if the catalog hasn't loaded.
            ?: if (it.providerId == "codex") {
                catalog["openai"]?.models?.get(it.modelId)?.limit
                    ?: dev.phonecode.provider.catalog.Limit(context = 400_000, output = 128_000)
            } else {
                null
            }
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
                sessionId = latest.id
                setActiveProject(latest.projectId)
                history = latest.messages.map { it.toDomain() }
                _state.update { it.copy(lines = history.toChatLines(), currentSessionId = latest.id, currentProjectId = latest.projectId) }
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
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { catalogLoader.load() }.getOrNull() ?: return@launch
            catalog = result.catalog
            val options = catalogToOptions(result.catalog)
            if (options.isNotEmpty()) {
                _state.update { s ->
                    // The catalog only covers built-in presets, so preserve any custom-provider models
                    // reloadProviders() already folded in - otherwise this reducer clobbers them (and can
                    // silently re-select a built-in if a custom model was active). Order-independent: if the
                    // catalog wins the race, reloadProviders re-appends later; if it loses, we keep them here.
                    val builtinKeys = options.map { "${it.providerId}/${it.modelId}" }.toSet()
                    val custom = s.models.filter {
                        it.providerId in customPresets && "${it.providerId}/${it.modelId}" !in builtinKeys
                    }
                    val merged = options + custom
                    val current = s.selected
                    // Remember the user's model across restarts: prefer the last-picked model (persisted in
                    // recents) over the hardcoded default, then any selection already made this session.
                    val recentKey = modelPrefs.recents().firstOrNull()
                    val resolved = merged.firstOrNull { modelKey(it) == recentKey }
                        ?: merged.firstOrNull { it.providerId == current?.providerId && it.modelId == current?.modelId }
                        ?: merged.first()
                    s.copy(models = merged, selected = resolved, contextLimit = limitFor(resolved)?.context)
                }
            }
        }
    }

    /** Build the picker from the catalog for our presets; fall back to built-ins per provider. */
    private fun catalogToOptions(catalog: Catalog): List<ModelOption> {
        val out = mutableListOf<ModelOption>()
        BuiltInPresets.all.forEach { preset ->
            if (preset.id == "codex") {
                // Codex has no catalog provider of its own; "Sign in with ChatGPT" exposes OpenAI's current
                // GPT-5 family. Source it live from the openai catalog so the list tracks new releases
                // (GPT-5.5, the latest Codex models, whatever ships next) instead of a hardcoded, stale set.
                val live = catalog["openai"]?.models?.values
                    ?.filter { it.id.startsWith("gpt-5") }
                    ?.sortedByDescending { it.id }
                    ?.map { ModelOption("codex", it.id, "${preset.displayName} · ${it.name}") }
                    ?.takeIf { it.isNotEmpty() }
                out += live ?: builtInModels().filter { it.providerId == "codex" }
                return@forEach
            }
            val key = catalog.keys.firstOrNull { it == preset.id }
                ?: if (preset.id == "opencode-zen") catalog.keys.firstOrNull { it == "opencode" } else null
            val info = key?.let { catalog[it] }
            if (info != null && info.models.isNotEmpty()) {
                info.models.values.sortedBy { it.name }.forEach { model ->
                    out += ModelOption(preset.id, model.id, "${preset.displayName} · ${model.name}")
                }
            } else {
                out += builtInModels().filter { it.providerId == preset.id }
            }
        }
        return out
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
    private fun autoBranchIfEnabled(dir: File) {
        if (!appSettings.load().gitAutoBranch) return
        if (!File(dir, ".git").exists()) return
        runCatching {
            org.eclipse.jgit.api.Git.open(dir).use { git ->
                val branch = "task-" + sessionId.removePrefix("session-")
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

    /** Whether the model exposes reasoning controls, per the models.dev catalog. */
    fun supportsReasoning(option: ModelOption?): Boolean =
        option?.let { catalog[it.providerId]?.models?.get(it.modelId)?.reasoning } ?: true

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
    fun setEffort(effort: ReasoningEffort) = _state.update { it.copy(effort = effort) }
    fun setAutoAccept(value: Boolean) {
        _state.update { it.copy(autoAccept = value) }
        viewModelScope.launch(Dispatchers.IO) { appSettings.update { it.copy(autoAccept = value) } }
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
            it.copy(lines = emptyList(), streaming = "", streamingReasoning = "", usageInput = 0, usageOutput = 0, error = null, currentSessionId = sessionId, currentProjectId = projectId)
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
            val restored = loaded.messages.map { it.toDomain() }
            history = restored
            sessionId = loaded.id
            setActiveProject(loaded.projectId)
            todoStore.replace(emptyList())
            _state.update {
                it.copy(lines = restored.toChatLines(), streaming = "", streamingReasoning = "", usageInput = 0, usageOutput = 0, error = null, currentSessionId = sessionId, currentProjectId = loaded.projectId)
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

    fun createProject(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            projectStore.add("project-" + System.currentTimeMillis(), trimmed)
            _state.update { it.copy(projects = projectStore.list()) }
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
            sessionStore.list().filter { it.projectId == id }.forEach { sessionStore.setProject(it.id, null) }
            projectStore.delete(id)
            if (currentProjectId == id) {
                setActiveProject(null)
                _state.update { it.copy(projects = projectStore.list(), sessions = sessionStore.list(), currentProjectId = null) }
            } else {
                _state.update { it.copy(projects = projectStore.list(), sessions = sessionStore.list()) }
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
    fun keysStoredInPlaintext(): Boolean = keyStore.usingPlaintextFallback
    fun clearError() = _state.update { it.copy(error = null) }

    /** UI-originated user-visible failures (e.g. unreadable attachment) share the error banner. */
    fun surfaceError(message: String) = fail(message)

    fun clearNotice() = _state.update { it.copy(notice = null) }

    // ----- Codex (Sign in with ChatGPT) -----

    private val codexAuth by lazy { CodexAuth(http, store = keyStore::put, read = keyStore::get) }

    /**
     * Starts the Codex OAuth flow: spins up the loopback listener and returns the authorization URL
     * for the UI to open in the browser. The exchange completes asynchronously; state flips when done.
     */
    fun startCodexSignIn(): String? = runCatching {
        val url = codexAuth.buildAuthUrl()
        val verifier = codexAuth.pendingVerifier ?: return@runCatching null
        val expectedState = codexAuth.pendingState ?: return@runCatching null
        // State validation happens inside the listener; only a matching callback reaches this lambda.
        codexAuth.startLoopback(expectedState) { code ->
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { codexAuth.exchangeCode(code, verifier) }
                    .onSuccess { _state.update { it.copy(codexConnected = true, notice = "Signed in with ChatGPT - pick a ChatGPT model from the model menu") } }
                    .onFailure { e ->
                        codexAuth.stopLoopback()
                        _state.update { it.copy(error = "Codex sign-in failed: ${e.message}") }
                    }
            }
        }
        // Abandonment guard: stop listening after 5 minutes if the browser flow never completed.
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(5 * 60_000L)
            if (!_state.value.codexConnected) codexAuth.stopLoopback()
        }
        url
    }.getOrElse { e ->
        codexAuth.stopLoopback()
        _state.update { it.copy(error = "Codex sign-in failed: ${e.message}") }
        null
    }

    fun signOutCodex() {
        codexAuth.signOut() // CodexAuth owns its key names - don't duplicate them here (matches signOutGitHub)
        _state.update { it.copy(codexConnected = false) }
    }

    // ----- GitHub (device-flow sign-in: code on screen, no tokens to paste) -----

    private val githubAuth by lazy { GitHubAuth(http, store = keyStore::put, read = keyStore::get) }
    @Volatile private var githubSignInActive = false

    fun startGitHubSignIn() {
        if (githubSignInActive) return
        githubSignInActive = true
        viewModelScope.launch(Dispatchers.IO) {
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
                        // Local abandonment (cancel button) is silent; a genuine GitHub denial is shown.
                        error = if (e is GitHubAuth.SignInAbandonedException) null else "GitHub sign-in failed: ${e.message}",
                    )
                }
            }
            githubSignInActive = false
        }
    }

    fun cancelGitHubSignIn() {
        githubSignInActive = false
        _state.update { it.copy(githubAuthCode = null, githubVerifyUri = null) }
    }

    fun signOutGitHub() {
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
        val key = keyStore.get(selected.providerId)
        if (key.isNullOrBlank()) return "no API key configured for ${preset.displayName}"
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
            ProviderFactory.create(preset, key, http), childTools, toolContext, childConfig,
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

    fun send(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        if (_state.value.isRunning) {
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
        val isCodex = preset.wireFormat == WireFormat.OPENAI_RESPONSES
        val key = if (isCodex) keyStore.get("codex.access") else keyStore.get(selected.providerId)
        if (key.isNullOrBlank()) {
            return fail(if (isCodex) "Sign in with ChatGPT in Settings to use Codex." else "Set an API key for ${preset.displayName} in Settings.")
        }

        _state.update {
            it.copy(
                lines = it.lines + ChatLine.User(text),
                streaming = "",
                streamingReasoning = "",
                isRunning = true,
                error = null,
            )
        }
        // Foreground lease for the whole turn: without it the OS suspends the process shortly
        // after screen-off and the streaming HTTP call dies (device feedback).
        TurnService.start(getApplication())

        val startingHistory = history
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
                history = startingHistory + ChatMessage(Role.USER, listOf(MessagePart.Text(text)))
                persist()
            }
            val custom = appSettings.load().customInstructions.trim()
            val config = AgentConfig(
                model = selected.modelId,
                mode = _state.value.agentMode,
                environment = environment(),
                reasoningEffort = _state.value.effort,
                skills = discoveredSkills.map { SkillInfo(it.name, it.description) },
                sessionId = sessionId,
                projectInstructions = if (custom.isNotEmpty()) listOf(custom) else emptyList(),
            )
            val limit = limitFor(selected) // context/output token limits drive the gauge + compaction
            try {
                val provider = if (isCodex) {
                    val token = codexAuth.accessToken() // refreshes if near expiry (off the main thread)
                    if (token.isNullOrBlank()) { fail("Sign in with ChatGPT again in Settings."); return@launch }
                    // The per-user account id can't live in the static preset; attach it for this turn.
                    val withAccount = codexAuth.accountId()
                        ?.let { preset.copy(extraHeaders = preset.extraHeaders + ("chatgpt-account-id" to it)) } ?: preset
                    ProviderFactory.create(withAccount, token, http)
                } else {
                    ProviderFactory.create(preset, key, http)
                }
                val loop = AgentLoop(
                    provider, tools, toolContext, config,
                    steering = queueSource, // messages queued mid-turn are picked up at the next step (steer)
                    followUp = queueSource, // ...or run as a follow-up turn if queued right as the turn ends
                    turnSettings = { TurnSettings(config.model, _state.value.effort, limit?.context, limit?.output) },
                    modeProvider = { _state.value.agentMode }, // live so a plan_exit approval flips PLAN→BUILD mid-run
                )
                if (startingHistory.isEmpty()) autoBranchIfEnabled(pinnedWorkspace)
                loop.run(startingHistory, text).collect { event -> if (gen == generation) reduce(event) }
            } finally {
                if (gen == generation) {
                    turnWorkspace = null
                    commitStreaming()
                    _state.update { it.copy(isRunning = false, lastCompletedAt = System.currentTimeMillis()) }
                    TurnService.stop(getApplication())
                }
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
        send(lastUser.text)
    }

    fun cancel() {
        generation++ // invalidate the in-flight turn's events immediately, then clean up here (single owner)
        pendingMessages.clear() // stop means stop: don't let queued messages auto-run after a cancel
        // Cancel the job FIRST so an awaiting tool unwinds via CancellationException (no extra turn/side-effect);
        // completing the deferreds is then only a fallback to resume anything not yet at a cancellation point.
        job?.cancel()
        // The cancelled job's finally skips the pin clear (generation moved on) - release it here
        // so no stale workspace pin outlives the turn.
        turnWorkspace = null
        pendingDecision?.complete(false)
        pendingQuestionDecision?.complete(emptyList())
        commitStopped()
        _state.update { it.copy(isRunning = false, pendingPermission = null, pendingQuestion = null, queued = emptyList()) }
        TurnService.stop(getApplication())
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

    private fun reduce(event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta -> _state.update { it.copy(streaming = it.streaming + event.text) }
            is AgentEvent.ReasoningDelta -> _state.update { it.copy(streamingReasoning = it.streamingReasoning + event.text) }
            is AgentEvent.ToolStarted -> {
                commitStreaming()
                _state.update {
                    it.copy(
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
            is AgentEvent.Usage -> _state.update { it.copy(usageInput = event.input, usageOutput = event.output) }
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
                    persist()
                } else {
                    commitStreaming()
                }
                _state.update { it.copy(error = humanizeError(event.message), isRunning = false) }
            }
            is AgentEvent.TurnComplete -> {
                history = event.messages
                commitStreaming()
                persist() // runs on the IO collector thread; survives app restart
            }
        }
    }

    /** Save the current conversation to disk. Title = first user line; no-op for an empty history. */
    private fun persist() {
        val snapshot = history
        if (snapshot.isEmpty()) return
        val title = snapshot.firstOrNull { it.role == Role.USER }
            ?.parts?.filterIsInstance<MessagePart.Text>()?.firstOrNull()?.text?.take(40)?.takeIf { it.isNotBlank() }
            ?: "New chat"
        runCatching {
            sessionStore.save(PersistedSession(sessionId, title, System.currentTimeMillis(), snapshot.map { it.toPersisted() }, currentProjectId))
            _state.update { it.copy(sessions = sessionStore.list()) }
        }
    }

    /** Rebuild the visible timeline from persisted history, merging each tool result into its tool-call line. */
    private fun List<ChatMessage>.toChatLines(): List<ChatLine> {
        val lines = mutableListOf<ChatLine>()
        for (message in this) for (part in message.parts) {
            when (part) {
                is MessagePart.Text ->
                    lines += if (message.role == Role.USER) ChatLine.User(part.text) else ChatLine.Assistant(part.text)
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
        return lines
    }

    private fun commitStreaming() = _state.update { s ->
        var lines = s.lines
        if (s.streamingReasoning.isNotBlank()) lines = lines + ChatLine.Reasoning(s.streamingReasoning)
        if (s.streaming.isNotBlank()) lines = lines + ChatLine.Assistant(s.streaming)
        if (lines === s.lines) s else s.copy(lines = lines, streaming = "", streamingReasoning = "")
    }

    private fun fail(message: String) = _state.update { it.copy(error = humanizeError(message)) }

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
            "overloaded" in lower || "529" in lower ->
                "The provider is overloaded right now - try again shortly."
            else -> raw
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
            u.linuxAvailable -> ". A full Alpine Linux (proot) is provisioning in the background. Run any shell " +
                "command once to trigger setup, then retry `apk add python3 py3-pip nodejs ...` to install real " +
                "tools - do not settle for a busybox workaround when the proper tool can be installed."
            else -> ""
        }
        return AgentEnvironment(
            platform = "Android",
            deviceModel = Build.MODEL ?: "unknown",
            osVersion = "API ${Build.VERSION.SDK_INT}",
            // Match toolContext's workspaceProvider: a pinned turn workspace takes precedence over the live one,
            // so the path the prompt reports is the path tools actually write to.
            workspacePath = (turnWorkspace ?: workspace).absolutePath,
            shellAvailable = true,
            shellDetail = base + linux,
            configPath = File(getApplication<Application>().filesDir, "config").absolutePath,
        )
    }

    private fun summarizeArgs(argsJson: String): String = argsJson.replace("\n", " ").take(120)

    override fun onCleared() {
        // Stop background daemons promptly: the GitHub poll thread checks this flag every ≤500ms,
        // and the Codex loopback listener would otherwise hold port 1455 until its 5-min timeout.
        githubSignInActive = false
        codexAuth.stopLoopback()
        TurnService.stop(getApplication())
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

fun builtInModels(): List<ModelOption> = listOf(
    ModelOption("anthropic", "claude-opus-4-8", "Claude Opus 4.8"),
    ModelOption("anthropic", "claude-sonnet-4-6", "Claude Sonnet 4.6"),
    ModelOption("anthropic", "claude-haiku-4-5", "Claude Haiku 4.5"),
    ModelOption("openai", "gpt-5.5", "GPT-5.5"),
    ModelOption("openai", "o3", "o3"),
    ModelOption("openrouter", "anthropic/claude-opus-4-8", "OpenRouter · Claude Opus 4.8"),
    ModelOption("opencode-zen", "opencode/nemotron-3-ultra-free", "Zen · Nemotron 3 Ultra (Free)"),
    ModelOption("opencode-go", "opencode/go-fast", "Go · Fast"),
    ModelOption("google", "gemini-2.5-pro", "Gemini 2.5 Pro"),
    ModelOption("google", "gemini-2.0-flash", "Gemini 2.0 Flash"),
    ModelOption("xai", "grok-2-latest", "Grok 2"),
    ModelOption("deepseek", "deepseek-chat", "DeepSeek Chat"),
    ModelOption("deepseek", "deepseek-reasoner", "DeepSeek Reasoner"),
    ModelOption("mistral", "mistral-large-latest", "Mistral Large"),
    // ChatGPT plan via "Sign in with ChatGPT" (Codex). Offline fallback only - the real list is sourced
    // live from the catalog's GPT-5 family (see catalogToOptions), so it stays current.
    ModelOption("codex", "gpt-5.5", "ChatGPT · GPT-5.5"),
    ModelOption("codex", "gpt-5.3-codex", "ChatGPT · GPT-5.3 Codex"),
)

private const val BUNDLED_CATALOG = """
{
  "openai":{"id":"openai","name":"OpenAI","models":{"gpt-5.5":{"id":"gpt-5.5","name":"GPT-5.5"},"o3":{"id":"o3","name":"o3"}}},
  "anthropic":{"id":"anthropic","name":"Anthropic","models":{"claude-opus-4-8":{"id":"claude-opus-4-8","name":"Claude Opus 4.8"},"claude-sonnet-4-6":{"id":"claude-sonnet-4-6","name":"Claude Sonnet 4.6"},"claude-haiku-4-5":{"id":"claude-haiku-4-5","name":"Claude Haiku 4.5"}}},
  "openrouter":{"id":"openrouter","name":"OpenRouter","models":{"anthropic/claude-opus-4-8":{"id":"anthropic/claude-opus-4-8","name":"Claude Opus 4.8"}}},
  "opencode":{"id":"opencode","name":"OpenCode Zen","models":{"opencode/nemotron-3-ultra-free":{"id":"opencode/nemotron-3-ultra-free","name":"Nemotron 3 Ultra (Free)"}}}
}
"""

