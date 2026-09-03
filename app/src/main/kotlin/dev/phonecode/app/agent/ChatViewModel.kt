package dev.phonecode.app.agent

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.phonecode.app.auth.CodexAuth
import dev.phonecode.app.auth.GitHubAuth
import dev.phonecode.app.auth.githubOAuthClientId
import dev.phonecode.app.BuildConfig
import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.app.data.AppSettings
import dev.phonecode.app.data.AppSettingsStore
import dev.phonecode.app.data.safeAfterRestore
import dev.phonecode.app.data.safeProjectAfterRestore
import dev.phonecode.app.data.CustomProviderRepository
import dev.phonecode.app.data.customProviderSecretName
import dev.phonecode.app.data.FileCatalogCache
import dev.phonecode.app.data.McpSkillRepository
import dev.phonecode.app.data.ManagedSkill
import dev.phonecode.app.data.McpConfigLoad
import dev.phonecode.app.data.ModelPrefsStore
import dev.phonecode.app.data.PersistedSession
import dev.phonecode.app.data.Project
import dev.phonecode.app.data.ProjectStore
import dev.phonecode.app.data.ProvidersConfigLoad
import dev.phonecode.app.data.InvalidProvidersConfigException
import dev.phonecode.app.data.SecureKeyStore
import dev.phonecode.app.data.SessionMeta
import dev.phonecode.app.data.SessionStore
import dev.phonecode.app.data.SharedFolder
import dev.phonecode.app.data.SharedFolderStore
import dev.phonecode.app.data.TransferBundle
import dev.phonecode.app.data.toDomain
import dev.phonecode.app.data.toPreset
import dev.phonecode.app.data.toPersisted
import dev.phonecode.app.data.writeTextAtomically
import dev.phonecode.app.runtime.MisulModel
import dev.phonecode.app.runtime.MisulProvider
import dev.phonecode.app.runtime.MisulRuntimeEvent
import dev.phonecode.app.runtime.MisulRuntimeSpec
import dev.phonecode.app.runtime.toBoundedMisulImportSession
import dev.phonecode.app.runtime.userFacingFailure
import dev.phonecode.provider.catalog.Catalog
import dev.phonecode.provider.catalog.CatalogLoader
import dev.phonecode.provider.domain.ChatMessage
import dev.phonecode.provider.domain.MessagePart
import dev.phonecode.provider.domain.ReasoningEffort
import dev.phonecode.provider.domain.Role
import dev.phonecode.provider.http.CodexModelInfo
import dev.phonecode.provider.http.CodexModelsClient
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
import dev.phonecode.tools.git.openGit
import dev.phonecode.tools.interaction.QuestionTool
import dev.phonecode.tools.patch.ApplyPatchTool
import dev.phonecode.tools.mcp.McpServerSnapshot
import dev.phonecode.tools.mcp.McpServerConfig
import dev.phonecode.tools.mcp.connectMcpServersDetailed
import dev.phonecode.tools.mcp.probeMcpServer
import dev.phonecode.tools.skills.SkillManifest
import dev.phonecode.tools.skills.SkillTool
import dev.phonecode.tools.shared.SharedReadTool
import dev.phonecode.tools.shared.SharedWriteTool
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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

data class ModelOption(
    val providerId: String,
    val modelId: String,
    val label: String,
    val toolCall: Boolean = true,
)

enum class ToolStatus { AWAITING_APPROVAL, RUNNING, DONE, ERROR, STOPPED }
enum class TurnOutcome { STOPPED, FAILED }

data class PermissionRequest(val tool: String, val summary: String)

internal fun permissionCanAutoApprove(tool: String, automaticChanges: Boolean): Boolean {
    if (!automaticChanges) return false
    val normalized = tool.lowercase()
    return normalized != "doom_loop" &&
        normalized != "external_directory" &&
        !normalized.startsWith("external_directory_")
}

data class QuestionRequest(val questions: List<UserQuestion>)
data class RetryState(val attempt: Int, val message: String)
data class AiReportSubmission(val accepted: Boolean, val reference: String? = null, val error: String? = null)
data class SettingsOperation(val running: Boolean = false, val error: String? = null)
data class AgentToolInfo(val name: String, val description: String, val source: String, val access: String)

internal fun providerDeleteOperationKey(id: String) = "provider-delete:$id"
internal fun mcpDeleteOperationKey(name: String) = "mcp-delete:$name"
internal fun skillDeleteOperationKey(id: String) = "skill-delete:$id"

private data class BackupRestore(
    val count: Int,
    val settings: AppSettings,
    val session: PersistedSession,
    val messages: List<ChatMessage>,
    val favourites: Set<String>,
    val hiddenModels: Set<String>,
    val disabledProviders: Set<String>,
    val sessions: List<SessionMeta>,
    val projects: List<Project>,
)

private data class StreamSnapshot(val text: String, val reasoning: String)

private data class RecoveredWorkspace(val source: File, val target: File, val relativePath: String)

sealed interface ChatLine {
    data class User(val text: String, val images: List<MessagePart.Image> = emptyList()) : ChatLine
    data class Assistant(val text: String) : ChatLine
    data class Reasoning(val text: String) : ChatLine
    data class ToolActivity(
        val id: String,
        val name: String,
        val status: ToolStatus,
        val detail: String,
        val input: String = detail,
    ) : ChatLine
}

data class ChatUiState(
    val lines: List<ChatLine> = emptyList(),
    val streaming: String = "",
    val streamingReasoning: String = "",
    val isRunning: Boolean = false,
    val sessionLoading: Boolean = false,
    val queued: List<String> = emptyList(), // messages sent while a turn runs, awaiting pickup by the agent
    val models: List<ModelOption> = builtInModels(BuildConfig.CODEX_OAUTH_ENABLED),
    val selected: ModelOption? = builtInModels(BuildConfig.CODEX_OAUTH_ENABLED).firstOrNull(),
    val effort: ReasoningEffort = ReasoningEffort.DEFAULT,
    val autoAccept: Boolean = false,
    val pendingPermission: PermissionRequest? = null,
    val pendingQuestion: QuestionRequest? = null,
    val retry: RetryState? = null,
    val todos: List<TodoItem> = emptyList(),
    val mcpServers: Map<String, McpServerConfig> = emptyMap(),
    val mcpInventoryLoaded: Boolean = false,
    val mcpSnapshots: Map<String, McpServerSnapshot> = emptyMap(),
    val mcpToolCount: Int = 0,
    val mcpConnecting: Set<String> = emptySet(),
    val mcpConfigError: String? = null,
    val mcpOperationError: String? = null,
    val providerConfigError: String? = null,
    val skills: List<ManagedSkill> = emptyList(),
    val skillInventoryLoaded: Boolean = false,
    val sessions: List<SessionMeta> = emptyList(),
    // Bumped whenever a timeline is replaced or rewound. The chat list treats a new epoch as a
    // restored baseline so reused indices cannot inherit per-row UI state from prior history.
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
    val codexOAuthAvailable: Boolean = BuildConfig.CODEX_OAUTH_ENABLED,
    val codexConnected: Boolean = false,
    val githubLogin: String? = null,
    val githubAuthCode: String? = null,
    val githubVerifyUri: String? = null,
    val notice: String? = null,
    val error: String? = null,
    val interruptedTurn: Boolean = false,
    val turnOutcome: TurnOutcome? = null,
    val draftPhotos: Map<String, List<MessagePart.Image>> = emptyMap(),
    val reportSubmitting: Boolean = false,
    val reportSubmission: AiReportSubmission? = null,
    val settingsOperations: Map<String, SettingsOperation> = emptyMap(),
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
    private val gitCredentialLock = Any()
    private val http = OkHttpClient.Builder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val reportHttp = reportHttpClient(http)
    private val foregroundLeases = (app as PhoneCodeApplication).foregroundLeases
    private val shellBackend by lazy { (app as PhoneCodeApplication).shellBackend }
    private val misulRuntime by lazy { (app as PhoneCodeApplication).misulRuntimeController }
    private val turnLease = AtomicReference<String?>(null)
    private val todoStore = TodoStore()
    private val configDir = File(app.filesDir, "config")
    private val repo = McpSkillRepository(configDir, keyStore)
    private val customProviders = CustomProviderRepository(configDir)
    private val sharedFolderStore = SharedFolderStore(File(app.filesDir, "shared_folders.json"))
    private val sharedFileAccess = AndroidSharedFileAccess(app, sharedFolderStore)
    private val baseTools: List<Tool> by lazy {
        defaultFileTools() + ApplyPatchTool() + ExternalDirectoryTool() + QuestionTool() +
            SharedReadTool(sharedFileAccess) + SharedWriteTool(sharedFileAccess) +
            todoTools(todoStore) +
            WebFetchTool(http) + WebSearchTool(http) + gitTools { gitCredentials() } +
            ShellTool(shellBackend) + ProcessTool(shellBackend) +
            ExtensionConfigReadTool(repo) { workspace } + ExtensionConfigWriteTool(repo) { workspace }
    }
    @Volatile private var mcpTools: List<Tool> = emptyList()
    @Volatile private var discoveredSkills: List<SkillManifest> = emptyList()
    private val tools by lazy { ToolRegistry(baseTools) }
    // MUST be initialized before the init block below: the MCP-connect coroutine it launches calls
    // rebuildTools() and can run before a later-declared field's initializer executes (NPE at launch).
    private val toolsLock = Any()
    private val catalogLoader = CatalogLoader(
        http,
        FileCatalogCache(app.cacheDir),
        ttlMillis = CATALOG_REFRESH_TTL_MS,
        bundledFallback = { BUNDLED_CATALOG },
    )
    private val codexModelsClient = CodexModelsClient(http)
    private val codexAuth by lazy {
        CodexAuth(
            http,
            store = keyStore::put,
            read = keyStore::get,
            enabled = BuildConfig.CODEX_OAUTH_ENABLED,
        )
    }
    @Volatile private var catalog: dev.phonecode.provider.catalog.Catalog = emptyMap()
    @Volatile private var codexModelMetadata: Map<String, CodexModelInfo> = emptyMap()
    @Volatile private var customPresets: Map<String, ProviderPreset> = emptyMap()
    @Volatile private var customLimits: Map<String, Long> = emptyMap()

    private fun providerFor(id: String): ProviderPreset? {
        if (!providerAllowed(id, BuildConfig.CODEX_OAUTH_ENABLED)) return null
        val preset = BuiltInPresets.byId(id) ?: customPresets[id] ?: return null
        if (preset.wireFormat != WireFormat.OPENAI_COMPAT) return null
        return preset.withCatalogApi(catalog[catalogProviderId(id)]?.api)
    }

    /** All providers for Settings: built-ins plus any agent-defined custom providers. */
    fun allProviders(): List<ProviderPreset> =
        BuiltInPresets.all + customPresets.values.sortedBy { it.displayName }

    /** Whether the native Misul runtime can execute this provider in the current alpha. */
    fun providerAvailableInMisul(providerId: String): Boolean =
        providerAllowed(providerId, BuildConfig.CODEX_OAUTH_ENABLED) &&
            allProviders().firstOrNull { it.id == providerId }?.wireFormat == WireFormat.OPENAI_COMPAT

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

    private val appSettings = AppSettingsStore(File(app.filesDir, "app_settings.json"))
    private val startupSettings = appSettings.load()
    private val _state = MutableStateFlow(
        ChatUiState(
            sessionLoading = true,
            autoAccept = startupSettings.autoAccept,
        ),
    )
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val sessionStore = SessionStore(File(app.filesDir, "sessions"))
    private val modelPrefs = ModelPrefsStore(File(app.filesDir, "model_prefs.json"))
    private val projectStore = ProjectStore(File(app.filesDir, "projects.json"))
    @Volatile private var sessionId: String = newSessionId()
    @Volatile private var currentProjectId: String? = null
    @Volatile private var history: List<ChatMessage> = emptyList()
    private val streamBufferLock = Any()
    private val streamingTextBuffer = StringBuilder()
    private val streamingReasoningBuffer = StringBuilder()
    private var lastStreamFlushAt = 0L
    @Volatile private var generation = 0
    private val sessionWriteOrder = AtomicLong()
    private val autoAcceptWriteOrder = AtomicLong()
    private var job: Job? = null
    private var sessionSwitchJob: Job? = null
    @Volatile private var loadingSessionId: String? = null
    @Volatile private var sessionSelection = 0
    private var modelRefreshJob: Job? = null
    private var mcpReconnectJob: Job? = null
    private val runtimeReloadMutex = Mutex()
    private val mcpReloadMutex = Mutex()
    private val metadataMutationMutex = Mutex()
    private val autoAcceptMutationLock = Any()
    @Volatile private var lastMcpFingerprint: String? = null
    @Volatile private var lastSkillsFingerprint: String? = null
    @Volatile private var lastProvidersFingerprint: String? = null
    private val configHotReload = ConfigHotReloadObserver(
        scope = viewModelScope,
        directories = { repo.watchedDirectories(workspace) },
        onChange = { refreshRuntimeConfiguration() },
        // Robolectric's FileObserver shadow allocates one Linux inotify instance per watched
        // directory and does not reliably release application-scoped observers between tests.
        enabled = Build.FINGERPRINT != "robolectric",
    )
    @Volatile private var lastCatalogRefreshAt = 0L
    @Volatile private var lastCodexRefreshAt = 0L
    private val pendingNativeApprovalId = AtomicReference<String?>(null)
    private var pendingQuestionDecision: CompletableDeferred<List<UserAnswer>>? = null

    // Messages sent while a turn is running: the agent loop drains them as steering (picked up at its next
    // step, so it can be redirected without stopping) or as a follow-up at the end - nothing is dropped.
    private val pendingMessages = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val queueStateLock = Any()

    init {
        foregroundLeases.registerStopHandler("processes") { shellBackend.stopAll() }
        foregroundLeases.registerStopHandler("turn") { cancel() }
        viewModelScope.launch(Dispatchers.IO) {
            delay(STARTUP_DEFER_MILLIS)
            configDir.mkdirs()
            runCatching {
                repo.seedBundledSkills(app.assets)
                refreshSkillsNow()
            }.onFailure { failure ->
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                // A restored skill route must not wait forever when the one-time seed fails.
                _state.update {
                    it.copy(
                        skillInventoryLoaded = true,
                        error = failure.message ?: "Skills could not be loaded",
                    )
                }
            }
            _state.update { it.copy(sessions = sessionStore.list(), projects = projectStore.list()) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            delay(STARTUP_DEFER_MILLIS)
            shellBackend.status(workspace.absolutePath)
        }
        viewModelScope.launch(Dispatchers.IO) {
            _state.update {
                it.copy(
                    favourites = modelPrefs.favourites(),
                    hiddenModels = modelPrefs.hiddenModels(),
                    disabledProviders = modelPrefs.disabledProviders(),
                    autoAccept = startupSettings.autoAccept,
                    codexConnected = BuildConfig.CODEX_OAUTH_ENABLED && keyStore.get("codex.access") != null,
                    githubLogin = keyStore.get("github.login"),
                    currentSessionId = sessionId,
                    sharedFolders = sharedFolderStore.list(),
                )
            }
        }
        reloadProviders()
        // The agent's todo list (a StateFlow) drives the on-screen checklist directly.
        viewModelScope.launch { todoStore.items.collect { todos -> _state.update { it.copy(todos = todos) } } }
        viewModelScope.launch(Dispatchers.IO) {
            val latest = runCatching {
                startupSettings.activeSessionId?.let(sessionStore::load) ?: sessionStore.loadLatest()
            }.getOrNull()
            if (latest == null) {
                _state.update { it.copy(sessionLoading = false, currentSessionId = sessionId) }
            } else {
                val interrupted = latest.activeTurn
                val restored = latest.messages.map { it.toDomain() }.let {
                    if (interrupted) repairInterruptedHistory(it) else it
                }
                val lines = restored.toChatLines()
                withContext(Dispatchers.Main.immediate) {
                    sessionId = latest.id
                    val activeProjectId = setActiveProject(latest.projectId)
                    history = restored
                    todoStore.replace(latest.todos)
                    _state.update {
                        it.copy(
                            lines = lines,
                            timelineEpoch = it.timelineEpoch + 1,
                            currentSessionId = latest.id,
                            currentProjectId = activeProjectId,
                            error = if (interrupted) TURN_INTERRUPTED_MESSAGE else it.error,
                            interruptedTurn = interrupted,
                            turnOutcome = latest.turnOutcome?.let { saved ->
                                runCatching { TurnOutcome.valueOf(saved) }.getOrNull()
                            },
                            queued = latest.queuedMessages,
                            sessionLoading = false,
                        )
                    }
                }
                if (interrupted) {
                    sessionStore.save(
                        latest.copy(
                            messages = restored.map { it.toPersisted() },
                            activeTurn = false,
                        ),
                    )
                }
                appSettings.update { it.copy(activeSessionId = latest.id) }
            }
        }
        // Load MCP config + discover skills, then connect remote MCP servers and fold their tools in.
        viewModelScope.launch(Dispatchers.IO) {
            delay(STARTUP_DEFER_MILLIS)
            reconnectMcpNow(force = true)
            configHotReload.restart()
        }
        viewModelScope.launch {
            delay(STARTUP_DEFER_MILLIS)
            refreshModels()
        }
    }

    fun refreshModels(forceRefresh: Boolean = false) {
        val now = System.currentTimeMillis()
        val refreshCatalog = forceRefresh || now - lastCatalogRefreshAt >= CATALOG_REFRESH_TTL_MS
        val refreshCodex = BuildConfig.CODEX_OAUTH_ENABLED && !keyStore.get("codex.access").isNullOrBlank() &&
            (forceRefresh || now - lastCodexRefreshAt >= CODEX_REFRESH_TTL_MS)
        if (!refreshCatalog && !refreshCodex) return
        if (modelRefreshJob?.isActive == true) {
            if (!forceRefresh) return
            modelRefreshJob?.cancel()
        }
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
                        ?.let(::visibleCodexModels)
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
        val supportedOptions = agentCapableModels(options)
        if (supportedOptions.isEmpty()) return
        _state.update { state ->
            val builtinKeys = supportedOptions.map { "${it.providerId}/${it.modelId}" }.toSet()
            val custom = state.models.filter {
                it.toolCall && it.providerId in customPresets && "${it.providerId}/${it.modelId}" !in builtinKeys
            }
            val merged = supportedOptions + custom
            val current = state.selected
            val recentKey = modelPrefs.recents().firstOrNull()
            val resolved = merged.firstOrNull { modelKey(it) == recentKey && providerConfigured(it.providerId) }
                ?: merged.firstOrNull { it.providerId == current?.providerId && it.modelId == current.modelId && providerConfigured(it.providerId) }
                ?: merged.firstOrNull { providerConfigured(it.providerId) }
                ?: merged.firstOrNull { modelKey(it) == recentKey }
                ?: merged.firstOrNull { it.providerId == current?.providerId && it.modelId == current.modelId }
                ?: current?.providerId?.let { id -> merged.firstOrNull { it.providerId == id } }
                ?: merged.first()
            state.copy(models = merged, selected = resolved, contextLimit = limitFor(resolved)?.context)
        }
    }

    /** Build the picker from the catalog for our presets; fall back to built-ins per provider. */
    private fun catalogToOptions(catalog: Catalog): List<ModelOption> {
        val out = mutableListOf<ModelOption>()
        BuiltInPresets.all.filter { it.wireFormat == WireFormat.OPENAI_COMPAT }.forEach { preset ->
            if (preset.id == "codex") {
                val authenticated = codexModelMetadata.values
                    .sortedWith(compareBy<CodexModelInfo> { it.priority }.thenBy { it.displayName })
                    .map { ModelOption("codex", it.slug, "${preset.displayName} · ${it.displayName}") }
                if (authenticated.isNotEmpty()) {
                    out += authenticated
                    return@forEach
                }
                val catalogModels = catalog["openai"]?.models.orEmpty()
                val live = catalogModels.values
                    .filter { codexEligible(it.id) && it.toolCall }
                    .sortedByDescending { it.id }
                    .map { ModelOption("codex", it.id, "${preset.displayName} · ${it.name}") }
                val fallback = builtInFallbackModels(
                    builtInModels(BuildConfig.CODEX_OAUTH_ENABLED).filter { it.providerId == "codex" },
                    catalogModels.keys,
                )
                out += (live + fallback).distinctBy { it.modelId }
                return@forEach
            }
            val info = catalog[catalogProviderId(preset.id)]
            if (info != null && info.models.isNotEmpty()) {
                val live = info.models.values
                    .filter { it.toolCall }
                    .sortedBy { it.name }
                    .map { model ->
                        ModelOption(preset.id, model.id, "${preset.displayName} · ${model.name}", toolCall = true)
                    }
                val fallback = builtInFallbackModels(
                    builtInModels(BuildConfig.CODEX_OAUTH_ENABLED).filter { it.providerId == preset.id },
                    info.models.keys,
                )
                out += (live + fallback).distinctBy { it.modelId }
            } else {
                out += builtInModels(BuildConfig.CODEX_OAUTH_ENABLED).filter { it.providerId == preset.id }
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

    private fun workspacePathFor(projectId: String?): File {
        val root = workspacesRoot.canonicalFile.apply { mkdirs() }
        val directory = File(root, projectId ?: "default").canonicalFile
        require(directory.parentFile == root) { "Project workspace is outside the workspace root" }
        return directory
    }

    private fun workspaceFor(projectId: String?): File = workspacePathFor(projectId).apply { mkdirs() }

    private suspend fun recoverProjectWorkspace(project: Project): RecoveredWorkspace? {
        val source = workspacePathFor(project.id)
        shellBackend.stopWorkspace(source.absolutePath)
        if (!source.isDirectory || source.list().isNullOrEmpty()) {
            source.delete()
            return null
        }
        val defaultWorkspace = workspaceFor(null)
        val recoveredRoot = File(defaultWorkspace, "Recovered projects").apply { mkdirs() }
        val safeName = project.name.map { char ->
            if (char.isLetterOrDigit() || char == ' ' || char == '-' || char == '_' || char == '.') char else '_'
        }.joinToString("").trim().take(60).ifBlank { "Project" }
        val baseName = "$safeName (${project.id.takeLast(8)})"
        var target = File(recoveredRoot, baseName)
        var suffix = 2
        while (target.exists()) target = File(recoveredRoot, "$baseName $suffix").also { suffix++ }
        runCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(source.toPath(), target.toPath())
        }
        return RecoveredWorkspace(source, target, target.relativeTo(defaultWorkspace).path)
    }

    private fun restoreProjectWorkspace(recovered: RecoveredWorkspace) {
        if (!recovered.target.exists()) return
        recovered.source.parentFile?.mkdirs()
        check(!recovered.source.exists() || recovered.source.delete()) { "Project workspace recovery destination is not empty" }
        runCatching {
            Files.move(recovered.target.toPath(), recovered.source.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(recovered.target.toPath(), recovered.source.toPath())
        }
    }

    /** Switch the active project: the workspace (files + git repo) follows the current chat's project. */
    private fun setActiveProject(projectId: String?): String? {
        val safeProjectId = projectId?.takeIf(PROJECT_ID::matches)
        currentProjectId = safeProjectId
        workspace = workspaceFor(safeProjectId)
        lastSkillsFingerprint = null
        configHotReload.restart()
        refreshSkills()
        return safeProjectId
    }

    /**
     * "Auto-branch each task" (Settings > Git > Advanced): when enabled, the first turn of a chat
     * moves the workspace onto its own branch so the agent's changes stay isolated from main.
     * Best-effort - a failure (no repo, detached head) must never block the send.
     */
    private fun autoBranchIfEnabled(dir: File, taskSessionId: String = sessionId): Boolean {
        if (!appSettings.load().gitAutoBranch) return false
        if (!File(dir, ".git").exists()) return false
        return runCatching {
            openGit(dir).use { git ->
                val branch = "task-" + taskSessionId.removePrefix("session-")
                if (git.repository.branch != branch) {
                    git.checkout().setName(branch).setCreateBranch(git.repository.findRef(branch) == null).call()
                }
                true
            }
        }.getOrDefault(false)
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

    fun reloadProviders() {
        viewModelScope.launch(Dispatchers.IO) { reloadProvidersNow() }
    }

    private fun reloadProvidersNow(): Result<Unit> {
        val fingerprint = runCatching { customProviders.fingerprint() }.getOrDefault("unreadable")
        var warning: String? = null
        val cfg = when (val loaded = customProviders.loadState()) {
            is ProvidersConfigLoad.Ready -> loaded.config.also { warning = loaded.warning }
            is ProvidersConfigLoad.Invalid -> {
                lastProvidersFingerprint = fingerprint
                _state.update { it.copy(providerConfigError = loaded.message) }
                return Result.failure(InvalidProvidersConfigException(loaded.message))
            }
        }
        return runCatching {
            cfg.provider.keys.forEach { id ->
                val scoped = customProviderSecretName(id)
                val legacy = keyStore.get(id)
                if (keyStore.get(scoped).isNullOrBlank() && !legacy.isNullOrBlank()) {
                    keyStore.put(scoped, legacy)
                    keyStore.put(id, "")
                }
            }
            customPresets = cfg.provider.mapValues { (id, provider) -> provider.toPreset(id) }
            customLimits = cfg.provider.flatMap { (providerId, provider) ->
                provider.models.mapNotNull { (modelId, model) -> model.context?.let { "$providerId/$modelId" to it } }
            }.toMap()
            val customOptions = cfg.provider.flatMap { (providerId, provider) ->
                if (customPresets[providerId]?.wireFormat != WireFormat.OPENAI_COMPAT) return@flatMap emptyList()
                provider.models.map { (modelId, model) -> ModelOption(providerId, modelId, model.name.ifBlank { modelId }) }
            }
            applyModelOptions(catalogToOptions(catalog) + customOptions)
            lastProvidersFingerprint = fingerprint
            _state.update { it.copy(providerConfigError = warning) }
        }.onFailure { error ->
            _state.update { it.copy(providerConfigError = error.message ?: "Provider configuration could not be loaded") }
        }
    }
    /** True for user/agent-defined providers (they get a "Remove" action; presets don't). */
    fun isCustomProvider(id: String): Boolean = id in customPresets

    suspend fun saveCustomProvider(id: String, provider: dev.phonecode.app.data.CustomProvider): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cfg = customProviders.load()
                customProviders.save(cfg.copy(provider = cfg.provider + (id to provider)))
                reloadProvidersNow().getOrThrow()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "Custom provider could not be saved") }
            }
        }

    /** Remove a user-defined provider: config entry, preset, and its picker models. */
    fun deleteCustomProvider(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            deleteCustomProviderAndWait(id).onFailure { error ->
                _state.update { it.copy(error = error.message ?: "Custom provider could not be removed") }
            }
        }
    }

    suspend fun deleteCustomProviderAndWait(id: String): Result<Unit> =
        runSettingsOperation(providerDeleteOperationKey(id)) {
        runCatching {
            val cfg = customProviders.load()
            val previousSecrets = mapOf(
                customProviderSecretName(id) to keyStore.get(customProviderSecretName(id)).orEmpty(),
                id to keyStore.get(id).orEmpty(),
            )
            var configurationChanged = false
            try {
                customProviders.save(cfg.copy(provider = cfg.provider - id))
                configurationChanged = true
                keyStore.putAll(previousSecrets.keys.associateWith { "" })
                reloadProvidersNow().getOrThrow()
            } catch (failure: Throwable) {
                // The JSON file and encrypted preferences cannot share one filesystem transaction,
                // so compensate every committed step before reporting a retryable failure.
                if (configurationChanged) {
                    runCatching { customProviders.save(cfg) }
                        .onFailure(failure::addSuppressed)
                }
                runCatching { keyStore.putAll(previousSecrets) }
                    .onFailure(failure::addSuppressed)
                runCatching { reloadProvidersNow().getOrThrow() }
                    .onFailure(failure::addSuppressed)
                throw failure
            }
        }
    }

    fun clearSettingsOperation(key: String) {
        _state.update { it.copy(settingsOperations = it.settingsOperations - key) }
    }

    private suspend fun runSettingsOperation(
        key: String,
        operation: suspend () -> Result<Unit>,
    ): Result<Unit> {
        val owned = viewModelScope.async(Dispatchers.IO) {
            _state.update {
                it.copy(settingsOperations = it.settingsOperations + (key to SettingsOperation(running = true)))
            }
            runCatching { operation() }
                .fold(onSuccess = { it }, onFailure = { Result.failure(it) })
                .also { result ->
                _state.update {
                    it.copy(
                        settingsOperations = it.settingsOperations + (
                            key to SettingsOperation(
                                running = false,
                                error = result.exceptionOrNull()?.message,
                            )
                        ),
                    )
                }
            }
        }
        return owned.await()
    }

    fun setEffort(effort: ReasoningEffort) = _state.update {
        if (effort in reasoningEfforts(it.selected)) it.copy(effort = effort) else it
    }
    fun setAutoAccept(value: Boolean) {
        val writeOrder = autoAcceptWriteOrder.incrementAndGet()
        if (!value) {
            // Revocation is a safety boundary. Persist the tiny settings file before returning to the
            // main loop, so the UI cannot show "Ask before each change" while disk still grants authority.
            _state.update { state -> state.copy(autoAccept = false) }
            val failure = synchronized(autoAcceptMutationLock) {
                runCatching { appSettings.update { it.copy(autoAccept = false) } }.exceptionOrNull()
            }
            if (failure != null) {
                _state.update { state ->
                    state.copy(
                        autoAccept = false,
                        error = "Ask-before-change could not be saved; approval remains required for this session.",
                    )
                }
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            synchronized(autoAcceptMutationLock) {
                // A newer tap superseded this write before it acquired the lock. Skipping it keeps the
                // persisted setting in the same order as the visible setting.
                if (autoAcceptWriteOrder.get() != writeOrder) return@synchronized
                runCatching { appSettings.update { it.copy(autoAccept = true) } }
                    .onSuccess {
                        if (autoAcceptWriteOrder.get() == writeOrder) {
                            _state.update { state -> state.copy(autoAccept = true) }
                        }
                    }
                    .onFailure {
                        if (autoAcceptWriteOrder.get() == writeOrder) {
                            _state.update { state ->
                                state.copy(
                                    autoAccept = false,
                                    error = "Automatic approval could not be enabled; approval is still required.",
                                )
                            }
                        }
                    }
            }
        }
    }

    fun linkSharedFolder(uri: android.net.Uri) {
        if (_state.value.sessionLoading) return fail("Wait for the current data operation to finish.")
        viewModelScope.launch(Dispatchers.IO) {
            metadataMutationMutex.withLock {
                runCatching { sharedFileAccess.link(uri) }
                    .onSuccess { folders -> _state.update { it.copy(sharedFolders = folders, notice = "Folder linked") } }
                    .onFailure { error -> _state.update { it.copy(error = "Could not link folder: ${error.message}") } }
            }
        }
    }

    fun unlinkSharedFolder(id: String) {
        if (_state.value.sessionLoading) return fail("Wait for the current data operation to finish.")
        val affected = _state.value.projects.filter { it.folderId == id }
        if (_state.value.isRunning && affected.any { it.id == currentProjectId }) {
            return fail("Stop the current agent before removing this folder.")
        }
        _state.update { it.copy(sessionLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            metadataMutationMutex.withLock {
            val originalProjects = projectStore.list()
            val projects = originalProjects.filter { it.folderId == id }
            val projectIds = projects.mapTo(mutableSetOf()) { it.id }
            val originalSessions = sessionStore.list().filter { it.projectId in projectIds }
                .mapNotNull { sessionStore.load(it.id) }
            val recovered = mutableListOf<RecoveredWorkspace>()
            val originalActiveProject = currentProjectId
            val activeRemoved = originalActiveProject in projectIds
            try {
                sessionStore.list().filter { it.projectId in projectIds }.forEach { sessionStore.setProject(it.id, null) }
                projectIds.forEach(projectStore::delete)
                projects.mapNotNullTo(recovered) { recoverProjectWorkspace(it) }
                if (activeRemoved) setActiveProject(null)
                val folders = sharedFileAccess.unlink(id)
                _state.update {
                    it.copy(
                        sharedFolders = folders,
                        projects = projectStore.list(),
                        sessions = sessionStore.list(),
                        currentProjectId = if (activeRemoved) null else it.currentProjectId,
                        notice = if (recovered.isEmpty()) "Folder access removed" else
                            "Folder access removed. Workspace files moved to ${recovered.joinToString { item -> item.relativePath }}.",
                    )
                }
            } catch (error: Throwable) {
                val rollbackFailed = recovered.asReversed().mapNotNull { runCatching { restoreProjectWorkspace(it) }.exceptionOrNull() }
                    .toMutableList()
                runCatching { projectStore.replace(originalProjects) }.exceptionOrNull()?.let(rollbackFailed::add)
                originalSessions.forEach { session ->
                    runCatching { sessionStore.save(session) }.exceptionOrNull()?.let(rollbackFailed::add)
                }
                if (activeRemoved) {
                    runCatching { setActiveProject(originalActiveProject) }.exceptionOrNull()?.let(rollbackFailed::add)
                }
                if (error is kotlinx.coroutines.CancellationException) throw error
                _state.update {
                    it.copy(
                        projects = projectStore.list(),
                        sessions = sessionStore.list(),
                        error = "Could not remove folder access: ${error.message}" +
                            if (rollbackFailed.isEmpty()) "" else ". Some changes could not be restored.",
                    )
                }
            } finally {
                _state.update { state -> state.copy(sessionLoading = false) }
            }
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
        if (_state.value.isRunning) return fail("Stop the current agent before starting another chat.")
        if (_state.value.sessionLoading) return fail("Wait for the current data operation to finish.")
        sessionSwitchJob?.cancel()
        loadingSessionId = null
        sessionSelection++
        pendingMessages.clear()
        dropIfEmptyPlaceholder()
        generation++
        history = emptyList()
        resetStreamingBuffers()
        sessionId = newSessionId()
        val activeProjectId = setActiveProject(projectId)
        todoStore.replace(emptyList())
        sessionStore.create(
            PersistedSession(
                id = sessionId,
                title = "New chat",
                updatedAt = System.currentTimeMillis(),
                messages = emptyList(),
                projectId = activeProjectId,
            ),
        )
        appSettings.update { it.copy(activeSessionId = sessionId) }
        _state.update {
            it.copy(
                lines = emptyList(),
                streaming = "",
                streamingReasoning = "",
                usageInput = 0,
                usageOutput = 0,
                error = null,
                interruptedTurn = false,
                turnOutcome = null,
                sessionLoading = false,
                currentSessionId = sessionId,
                currentProjectId = activeProjectId,
                queued = emptyList(),
                sessions = sessionStore.list(),
            )
        }
    }

    /** Never-used "New chat" placeholders are dropped when navigating away, not collected forever. */
    private fun dropIfEmptyPlaceholder() {
        if (history.isEmpty() && _state.value.lines.isEmpty()) {
            val id = sessionId
            viewModelScope.launch(Dispatchers.IO) {
                metadataMutationMutex.withLock {
                    if (sessionStore.load(id)?.messages?.isEmpty() == true) sessionStore.delete(id)
                    _state.update { it.copy(sessions = sessionStore.list()) }
                }
            }
        }
    }

    /** Load a saved conversation and make it the active session. Works mid-stream (cancels first). */
    fun switchSession(id: String) {
        if (id == sessionId) {
            if (sessionSwitchJob?.isActive == true) {
                sessionSwitchJob?.cancel()
                sessionSelection++
                loadingSessionId = null
                _state.update { it.copy(sessionLoading = false) }
            }
            return
        }
        if (_state.value.isRunning) return fail("Stop the current agent before opening another chat.")
        if (_state.value.sessionLoading) return fail("Wait for the current data operation to finish.")
        sessionSwitchJob?.cancel()
        pendingMessages.clear()
        dropIfEmptyPlaceholder()
        generation++
        val selection = ++sessionSelection
        loadingSessionId = id
        _state.update { it.copy(sessionLoading = true, error = null) }
        sessionSwitchJob = viewModelScope.launch(Dispatchers.IO) {
            val loaded = sessionStore.load(id)
            if (loaded == null) {
                withContext(Dispatchers.Main.immediate) {
                    if (selection == sessionSelection) {
                        loadingSessionId = null
                        _state.update { it.copy(sessionLoading = false, error = "This chat is no longer available.") }
                    }
                }
                return@launch
            }
            val interrupted = loaded.activeTurn
            val restored = loaded.messages.map { it.toDomain() }.let {
                if (interrupted) repairInterruptedHistory(it) else it
            }
            val committed = withContext(Dispatchers.Main.immediate) {
                if (selection != sessionSelection) return@withContext false to null
                loadingSessionId = null
                history = restored
                resetStreamingBuffers()
                sessionId = loaded.id
                val activeProjectId = setActiveProject(loaded.projectId)
                todoStore.replace(loaded.todos)
                appSettings.update { it.copy(activeSessionId = loaded.id) }
                _state.update {
                    it.copy(
                        lines = restored.toChatLines(),
                        timelineEpoch = it.timelineEpoch + 1,
                        streaming = "",
                        streamingReasoning = "",
                        usageInput = 0,
                        usageOutput = 0,
                        error = if (interrupted) TURN_INTERRUPTED_MESSAGE else null,
                        interruptedTurn = interrupted,
                        turnOutcome = loaded.turnOutcome?.let { saved ->
                            runCatching { TurnOutcome.valueOf(saved) }.getOrNull()
                        },
                        sessionLoading = false,
                        currentSessionId = sessionId,
                        currentProjectId = activeProjectId,
                        queued = loaded.queuedMessages,
                    )
                }
                true to activeProjectId
            }
            if (committed.first && (interrupted || loaded.projectId != committed.second)) {
                sessionStore.save(
                    loaded.copy(
                        messages = restored.map { it.toPersisted() },
                        activeTurn = false,
                        projectId = committed.second,
                    ),
                )
            }
        }
    }

    fun deleteSession(id: String) {
        if (id == sessionId && _state.value.isRunning) return fail("Stop the current agent before deleting this chat.")
        if (_state.value.sessionLoading && loadingSessionId != id) return fail("Wait for the current data operation to finish.")
        if (loadingSessionId == id) {
            sessionSwitchJob?.cancel()
            sessionSelection++
            loadingSessionId = null
            _state.update { it.copy(sessionLoading = false) }
        }
        if (id == sessionId) newChat()
        viewModelScope.launch(Dispatchers.IO) {
            metadataMutationMutex.withLock {
                sessionStore.delete(id)
                _state.update { it.copy(sessions = sessionStore.list()) }
            }
        }
    }

    fun createProject(uri: android.net.Uri) {
        if (_state.value.sessionLoading) return fail("Wait for the current data operation to finish.")
        viewModelScope.launch(Dispatchers.IO) {
            metadataMutationMutex.withLock {
            val previouslyLinked = sharedFileAccess.linkedFolder(uri)
            var newlyLinkedFolderId: String? = null
            var newlyCreatedProjectId: String? = null
            runCatching {
                val folders = sharedFileAccess.link(uri)
                val folder = folders.first { it.handle == uri.toString() }
                if (previouslyLinked == null) newlyLinkedFolderId = folder.id
                val project = projectStore.list().firstOrNull { it.folderId == folder.id } ?: run {
                    projectStore.add("project-" + System.currentTimeMillis(), folder.name, folder.id)
                        .also { newlyCreatedProjectId = it.id }
                }
                Triple(folders, projectStore.list(), project)
            }.onSuccess { (folders, projects, project) ->
                withContext(Dispatchers.Main.immediate) {
                    _state.update { it.copy(sharedFolders = folders, projects = projects, notice = "Project linked") }
                    newChat(project.id)
                }
            }.onFailure { error ->
                val cleanupFailures = mutableListOf<Throwable>()
                newlyCreatedProjectId?.let { projectId ->
                    runCatching { projectStore.delete(projectId) }
                        .exceptionOrNull()
                        ?.let(cleanupFailures::add)
                }
                // If project deletion failed, keep its folder metadata and grant intact so the
                // orphan remains visible and usable instead of becoming a broken hidden record.
                if (cleanupFailures.isEmpty()) {
                    newlyLinkedFolderId?.let { folderId ->
                        runCatching { sharedFileAccess.unlink(folderId) }
                            .exceptionOrNull()
                            ?.let(cleanupFailures::add)
                    }
                }
                cleanupFailures.forEach(error::addSuppressed)
                _state.update {
                    it.copy(
                        sharedFolders = sharedFolderStore.list(),
                        projects = projectStore.list(),
                        error = "Could not create project: ${error.message}" +
                            if (cleanupFailures.isEmpty()) "" else
                                ". Cleanup was incomplete; review linked folders before retrying.",
                    )
                }
            }
            }
        }
    }

    fun renameProject(id: String, name: String) {
        if (_state.value.sessionLoading) return fail("Wait for the current data operation to finish.")
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            metadataMutationMutex.withLock {
                projectStore.rename(id, trimmed)
                _state.update { it.copy(projects = projectStore.list()) }
            }
        }
    }

    /** Delete a project; its chats are detached to "unsorted" rather than removed. */
    fun deleteProject(id: String) {
        if (currentProjectId == id && _state.value.isRunning) return fail("Stop the current agent before deleting this project.")
        if (_state.value.sessionLoading) return fail("Wait for the current data operation to finish.")
        _state.update { it.copy(sessionLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            metadataMutationMutex.withLock {
            val originalProjects = projectStore.list()
            val project = originalProjects.firstOrNull { it.id == id }
            val originalSessions = sessionStore.list().filter { it.projectId == id }
                .mapNotNull { sessionStore.load(it.id) }
            var recovered: RecoveredWorkspace? = null
            val activeRemoved = currentProjectId == id
            try {
                project ?: return@launch
                sessionStore.list().filter { it.projectId == id }.forEach { sessionStore.setProject(it.id, null) }
                projectStore.delete(id)
                recovered = recoverProjectWorkspace(project)
                if (activeRemoved) setActiveProject(null)
                val sharedElsewhere = project.folderId != null && originalProjects.any {
                    it.id != id && it.folderId == project.folderId
                }
                val folders = if (sharedElsewhere) sharedFolderStore.list()
                else project.folderId?.let(sharedFileAccess::unlink) ?: sharedFolderStore.list()
                _state.update {
                    it.copy(
                        projects = projectStore.list(),
                        sharedFolders = folders,
                        sessions = sessionStore.list(),
                        currentProjectId = if (activeRemoved) null else it.currentProjectId,
                        notice = recovered?.let { item -> "Project removed. Chats moved to Unsorted; workspace files moved to ${item.relativePath}." }
                            ?: "Project removed. Chats moved to Unsorted.",
                    )
                }
            } catch (error: Throwable) {
                val rollbackFailed = mutableListOf<Throwable>()
                recovered?.let { runCatching { restoreProjectWorkspace(it) }.exceptionOrNull()?.let(rollbackFailed::add) }
                runCatching { projectStore.replace(originalProjects) }.exceptionOrNull()?.let(rollbackFailed::add)
                originalSessions.forEach { session ->
                    runCatching { sessionStore.save(session) }.exceptionOrNull()?.let(rollbackFailed::add)
                }
                if (activeRemoved) {
                    runCatching { setActiveProject(id) }.exceptionOrNull()?.let(rollbackFailed::add)
                }
                if (error is kotlinx.coroutines.CancellationException) throw error
                _state.update {
                    it.copy(
                        projects = projectStore.list(),
                        sessions = sessionStore.list(),
                        error = "Could not remove project: ${error.message}" +
                            if (rollbackFailed.isEmpty()) "" else ". Some changes could not be restored.",
                    )
                }
            } finally {
                _state.update { state -> state.copy(sessionLoading = false) }
            }
            }
        }
    }

    fun renameSession(id: String, title: String) {
        if (_state.value.sessionLoading) return fail("Wait for the current data operation to finish.")
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            metadataMutationMutex.withLock {
                sessionStore.rename(id, trimmed)
                _state.update { it.copy(sessions = sessionStore.list()) }
            }
        }
    }

    fun moveSession(id: String, projectId: String?) {
        if (id == sessionId && _state.value.isRunning) return fail("Stop the current agent before moving this chat.")
        if (_state.value.sessionLoading) return fail("Wait for the current data operation to finish.")
        val safeProjectId = projectId?.takeIf(PROJECT_ID::matches)
        viewModelScope.launch(Dispatchers.IO) {
            metadataMutationMutex.withLock {
            sessionStore.setProject(id, safeProjectId)
            if (id == sessionId) {
                setActiveProject(safeProjectId)
                _state.update { it.copy(sessions = sessionStore.list(), currentProjectId = safeProjectId) }
            } else {
                _state.update { it.copy(sessions = sessionStore.list()) }
            }
            }
        }
    }

    fun setSessionPinned(id: String, pinned: Boolean) {
        if (_state.value.sessionLoading) return fail("Wait for the current data operation to finish.")
        viewModelScope.launch(Dispatchers.IO) {
            metadataMutationMutex.withLock {
                sessionStore.setPinned(id, pinned)
                _state.update { it.copy(sessions = sessionStore.list()) }
            }
        }
    }

    /** Archiving a chat drops it out of the main list; the active chat falls back to a fresh one. */
    fun setSessionArchived(id: String, archived: Boolean) {
        if (archived && id == sessionId && _state.value.isRunning) return fail("Stop the current agent before archiving this chat.")
        if (_state.value.sessionLoading) return fail("Wait for the current data operation to finish.")
        if (archived && id == sessionId) newChat()
        viewModelScope.launch(Dispatchers.IO) {
            metadataMutationMutex.withLock {
                sessionStore.setArchived(id, archived)
                _state.update { it.copy(sessions = sessionStore.list()) }
            }
        }
    }

    suspend fun saveMcpServerAndWait(
        name: String,
        server: McpServerConfig,
        expectedServer: McpServerConfig? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Server name is required"))
        val original = trimmed.takeIf { it in _state.value.mcpServers }
        repo.upsertMcpServer(original, trimmed, server, expectedServer).fold(
            onSuccess = { updated ->
                _state.update {
                    it.copy(
                        mcpServers = updated.mcp,
                        mcpConfigError = null,
                        mcpOperationError = null,
                    )
                }
                reconnectMcpNow(force = true)
                Result.success(Unit)
            },
            onFailure = { failure ->
                _state.update {
                    it.copy(
                        mcpOperationError = failure.message ?: "MCP configuration could not be saved",
                    )
                }
                Result.failure(failure)
            },
        )
    }

    fun deleteMcpServer(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            deleteMcpServerAndWait(name).onFailure { failure ->
                _state.update { it.copy(error = failure.message ?: "MCP server could not be deleted") }
            }
        }
    }

    suspend fun deleteMcpServerAndWait(name: String): Result<Unit> =
        runSettingsOperation(mcpDeleteOperationKey(name)) {
            repo.removeMcpServer(name).fold(
                onSuccess = { updated ->
                    _state.update {
                        it.copy(
                            mcpServers = updated.mcp,
                            mcpConfigError = null,
                            mcpOperationError = null,
                        )
                    }
                    reconnectMcp()
                    Result.success(Unit)
                },
                onFailure = { failure -> Result.failure(failure) },
            )
        }

    fun setMcpEnabled(name: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            setMcpEnabledAndWait(name, enabled).onFailure { failure ->
                _state.update { it.copy(error = failure.message ?: "MCP server could not be updated") }
            }
        }
    }

    suspend fun setMcpEnabledAndWait(name: String, enabled: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            repo.setMcpEnabled(name, enabled).fold(
                onSuccess = { updated ->
                    _state.update {
                        it.copy(
                            mcpServers = updated.mcp,
                            mcpConfigError = null,
                            mcpOperationError = null,
                        )
                    }
                    reconnectMcpNow(force = true)
                    Result.success(Unit)
                },
                onFailure = { failure -> Result.failure(failure) },
            )
        }

    /** Reconnect every enabled remote MCP server and fold the resulting tools into the registry. */
    fun reconnectMcp() {
        mcpReconnectJob?.cancel()
        mcpReconnectJob = viewModelScope.launch(Dispatchers.IO) {
            reconnectMcpAndWait().onFailure { failure ->
                if (failure !is kotlinx.coroutines.CancellationException) {
                    _state.update {
                        it.copy(mcpOperationError = failure.message ?: "MCP servers could not be reconnected")
                    }
                }
            }
        }
    }

    suspend fun reconnectMcpAndWait(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { reconnectMcpNow(force = true) }
    }

    private suspend fun reconnectMcpNow(force: Boolean = false) = mcpReloadMutex.withLock {
        val fingerprint = runCatching { repo.runtimeFingerprint(workspace).mcp }.getOrElse { failure ->
            _state.update {
                it.copy(
                    mcpInventoryLoaded = true,
                    mcpConnecting = emptySet(),
                    mcpConfigError = failure.message ?: "MCP configuration could not be read",
                    mcpOperationError = null,
                )
            }
            return@withLock
        }
        if (!force && fingerprint == lastMcpFingerprint) return@withLock
        val loaded = runCatching { repo.loadMcpConfigState() }.getOrElse { failure ->
            _state.update {
                it.copy(
                    mcpInventoryLoaded = true,
                    mcpConnecting = emptySet(),
                    mcpConfigError = failure.message ?: "MCP configuration could not be read",
                    mcpOperationError = null,
                )
            }
            return@withLock
        }
        if (loaded is McpConfigLoad.Invalid) {
            mcpTools = emptyList()
            rebuildTools()
            lastMcpFingerprint = fingerprint
            _state.update {
                it.copy(
                    mcpInventoryLoaded = true,
                    mcpConnecting = emptySet(),
                    mcpSnapshots = emptyMap(),
                    mcpToolCount = 0,
                    mcpConfigError = loaded.message,
                    mcpOperationError = null,
                )
            }
            return@withLock
        }
        val config = (loaded as McpConfigLoad.Ready).config
        _state.update {
            it.copy(
                mcpServers = config.mcp,
                mcpInventoryLoaded = true,
                mcpConnecting = config.mcp.filterValues { server -> server.enabled }.keys,
                mcpConfigError = null,
                mcpOperationError = null,
            )
        }
        val connected = runCatching {
            connectMcpServersDetailed(config, http, baseTools.mapTo(mutableSetOf()) { it.name })
        }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            dev.phonecode.tools.mcp.McpConnectionResult(emptyList(), emptyMap())
        }
        kotlinx.coroutines.yield()
        mcpTools = connected.tools
        rebuildTools()
        lastMcpFingerprint = fingerprint
        _state.update {
            it.copy(
                mcpServers = config.mcp,
                mcpInventoryLoaded = true,
                mcpSnapshots = connected.snapshots,
                mcpToolCount = connected.tools.size,
                mcpConnecting = emptySet(),
                mcpConfigError = null,
                mcpOperationError = null,
            )
        }
    }

    suspend fun testMcpServer(name: String, server: McpServerConfig): McpServerSnapshot =
        probeMcpServer(name.ifBlank { "MCP server" }, server, http)

    /** Re-scan the config dir for SKILL.md files and refresh the skill tool + prompt. */
    fun refreshSkills() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshSkillsNow()
        }
    }

    fun setSkillEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            setSkillEnabledAndWait(id, enabled).onFailure { failure ->
                _state.update { it.copy(error = failure.message ?: "Skill could not be updated") }
            }
        }
    }

    suspend fun setSkillEnabledAndWait(id: String, enabled: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            repo.setSkillEnabled(id, enabled, workspace).fold(
                onSuccess = {
                    refreshSkillsNow()
                    Result.success(Unit)
                },
                onFailure = { failure -> Result.failure(failure) },
            )
        }

    fun deleteSkill(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            deleteSkillAndWait(id).onFailure { failure ->
                _state.update { it.copy(error = failure.message ?: "Skill could not be deleted") }
            }
        }
    }

    suspend fun deleteSkillAndWait(id: String): Result<Unit> =
        runSettingsOperation(skillDeleteOperationKey(id)) {
            repo.deleteSkill(id, workspace).fold(
                onSuccess = {
                    refreshSkillsNow()
                    Result.success(Unit)
                },
                onFailure = { failure -> Result.failure(failure) },
            )
        }

    suspend fun readSkill(id: String): Result<String> = withContext(Dispatchers.IO) {
        repo.readSkill(id, workspace)
    }

    suspend fun saveSkillAndWait(
        id: String?,
        scope: dev.phonecode.app.data.SkillScope,
        name: String,
        content: String,
        expectedContent: String? = null,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            val result = if (id == null) {
                repo.writeSkillFile(scope, name.trim(), content = content, projectDir = workspace)
            } else {
                repo.writeSkill(id, content, workspace, expectedContent)
            }
            if (result.isSuccess) refreshSkillsNow()
            result
        }

    private fun refreshSkillsNow() {
        runCatching {
            repo.scanSkills(workspace) to repo.runtimeFingerprint(workspace).skills
        }.fold(
            onSuccess = { (inventory, fingerprint) ->
                _state.update { it.copy(skills = inventory.items, skillInventoryLoaded = true) }
                runCatching {
                    discoveredSkills = inventory.active
                    rebuildTools()
                    lastSkillsFingerprint = fingerprint
                }.onFailure { failure ->
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    _state.update {
                        it.copy(error = failure.message ?: "Skills could not be loaded")
                    }
                }
            },
            onFailure = { failure ->
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                _state.update {
                    it.copy(
                        skillInventoryLoaded = true,
                        error = failure.message ?: "Skills could not be loaded",
                    )
                }
            },
        )
    }

    private suspend fun refreshRuntimeConfiguration() = runtimeReloadMutex.withLock {
        withContext(Dispatchers.IO) {
            val fingerprint = repo.runtimeFingerprint(workspace)
            if (fingerprint.skills != lastSkillsFingerprint) refreshSkillsNow()
            if (fingerprint.mcp != lastMcpFingerprint) reconnectMcpNow()
            val providersFingerprint = runCatching { customProviders.fingerprint() }.getOrDefault("unreadable")
            if (providersFingerprint != lastProvidersFingerprint) reloadProvidersNow()
        }
    }

    // Serialized: reconnectMcp/refreshSkills/init all rebuild from background coroutines; without the lock
    // two interleaved read-modify-writes could drop the just-connected MCP tools (a lost update).
    private fun rebuildTools() = synchronized(toolsLock) {
        val skillTool = if (discoveredSkills.isNotEmpty()) listOf(SkillTool(discoveredSkills)) else emptyList()
        tools.replace(baseTools + mcpTools + skillTool)
    }

    private fun mcpInstructions(): List<String> = _state.value.mcpSnapshots.mapNotNull { (name, snapshot) ->
        snapshot.instructions.trim().takeIf { snapshot.connected && it.isNotEmpty() }
            ?.take(512)?.let { "$name:\n$it" }
    }

    fun configDirPath(): String = configDir.absolutePath
    fun availableTools(): List<AgentToolInfo> {
        val remoteNames = mcpTools.mapTo(mutableSetOf()) { it.name }
        return tools.all().sortedBy { it.name }.map { tool ->
            AgentToolInfo(
                name = tool.name,
                description = tool.description,
                source = when {
                    tool.name in remoteNames -> "MCP"
                    tool.name == "skill" -> "Skills"
                    else -> "Misul Agent"
                },
                access = when {
                    tool.name == "external_directory" ||
                        tool.name.startsWith("external_directory_") -> "Approval every time"
                    tool.mutating -> "Approval required"
                    tool.name == "process" || tool.name == "git_branch" -> "Depends on action"
                    else -> "Read only"
                },
            )
        }
    }
    private fun providerSecretName(providerId: String): String =
        if (providerId in customPresets) customProviderSecretName(providerId) else providerId

    fun keyFor(providerId: String): String = keyStore.get(providerSecretName(providerId)).orEmpty()
    fun setKey(providerId: String, key: String): Boolean {
        val trimmed = key.trim()
        return runCatching {
            keyStore.put(providerSecretName(providerId), trimmed)
            keyFor(providerId) == trimmed
        }.getOrDefault(false)
    }

    fun setKeys(values: Map<String, String>): Boolean = runCatching {
        val stored = values.map { (providerId, value) ->
            providerSecretName(providerId) to value.trim()
        }.toMap()
        synchronized(gitCredentialLock) {
            keyStore.putAll(stored)
            stored.all { (name, value) -> keyStore.get(name).orEmpty() == value }
        }
    }.getOrDefault(false)
    fun configureProviderKey(providerId: String, key: String): Boolean {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return false
        if (!setKey(providerId, trimmed)) return false
        return activateProvider(providerId)
    }
    fun activateProvider(providerId: String): Boolean {
        val option = configuredModelForProviderActivation(
            models = _state.value.models,
            providerId = providerId,
            hiddenModels = _state.value.hiddenModels,
        ) ?: return false
        if (providerId in _state.value.disabledProviders) toggleProviderDisabled(providerId)
        selectModel(option)
        return true
    }
    fun providerConfigured(providerId: String): Boolean =
        providerAllowed(providerId, BuildConfig.CODEX_OAUTH_ENABLED) &&
            !keyStore.get(if (providerId == "codex") "codex.access" else providerSecretName(providerId)).isNullOrBlank()
    fun hasConfiguredProvider(): Boolean =
        configuredModelForActivation(_state.value.models, _state.value.selected, ::providerConfigured) != null
    fun activateConfiguredModel(): Boolean {
        val configured = configuredModelForActivation(_state.value.models, _state.value.selected, ::providerConfigured)
            ?: return false
        if (configured != _state.value.selected) selectModel(configured)
        return true
    }
    /** True when the device Keystore was unavailable and keys are stored UNENCRYPTED (warn on the providers screen). */
    fun secureStorageUnavailable(): Boolean = keyStore.secureStorageUnavailable
    fun clearError() = _state.update { it.copy(error = null, interruptedTurn = false) }

    /** UI-originated user-visible failures (e.g. unreadable attachment) share the error banner. */
    fun surfaceError(message: String) = fail(message)

    fun clearNotice() = _state.update { it.copy(notice = null) }

    fun clearQueuedMessages() {
        synchronized(queueStateLock) {
            pendingMessages.clear()
            _state.update { it.copy(queued = emptyList()) }
            checkpointQueuedMessages(emptyList(), activeTurn = false, turnOutcome = _state.value.turnOutcome)
        }
    }

    private fun checkpointQueuedMessages(
        queuedMessages: List<String>,
        activeTurn: Boolean,
        turnOutcome: TurnOutcome?,
        expectedGeneration: Int? = null,
    ) {
        if (expectedGeneration != null && expectedGeneration != generation) return
        val snapshot = history
        if (snapshot.isEmpty()) return
        val targetSessionId = sessionId
        val targetProjectId = currentProjectId
        val targetTodos = todoStore.snapshot()
        val writeOrder = sessionWriteOrder.incrementAndGet()
        (getApplication<Application>() as PhoneCodeApplication).turnScope.launch(Dispatchers.IO) {
            persist(
                snapshot = snapshot,
                activeTurn = activeTurn,
                targetSessionId = targetSessionId,
                targetProjectId = targetProjectId,
                targetTodos = targetTodos,
                writeOrder = writeOrder,
                expectedGeneration = expectedGeneration,
                turnOutcome = turnOutcome,
                queuedMessages = queuedMessages,
            )
        }
    }

    fun setDraftPhotos(composerKey: String, photos: List<MessagePart.Image>) {
        _state.update { state ->
            state.copy(
                draftPhotos = if (photos.isEmpty()) {
                    state.draftPhotos - composerKey
                } else {
                    state.draftPhotos + (composerKey to photos)
                },
            )
        }
    }

    fun submitAiReport(category: String, note: String) {
        if (_state.value.reportSubmitting) return
        _state.update { it.copy(reportSubmitting = true, reportSubmission = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val submission = performAiReport(category, note)
            _state.update { it.copy(reportSubmitting = false, reportSubmission = submission) }
        }
    }

    fun clearAiReportSubmission() {
        if (_state.value.reportSubmitting) return
        _state.update { it.copy(reportSubmission = null) }
    }

    private fun performAiReport(category: String, note: String): AiReportSubmission {
        val body = aiReportPayload(
            category = category,
            note = note,
            appVersion = getApplication<Application>().packageManager
                .getPackageInfo(getApplication<Application>().packageName, 0)
                .versionName ?: "unknown",
        ).toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://dttdrv.xyz/api/phonecode/report")
            .post(body)
            .build()
        return runCatching {
            reportHttp.newCall(request).execute().use { response ->
                when (response.code) {
                    202 -> AiReportSubmission(
                        accepted = true,
                        reference = runCatching {
                            Json.parseToJsonElement(response.body?.string().orEmpty())
                                .jsonObject["id"]?.jsonPrimitive?.contentOrNull
                        }.getOrNull(),
                    )
                    429 -> AiReportSubmission(false, error = "Too many reports were sent from this network. Try again later.")
                    else -> AiReportSubmission(false, error = "Reporting is temporarily unavailable. Try again later.")
                }
            }
        }.getOrElse {
            AiReportSubmission(false, error = "Reporting is temporarily unavailable. Check your connection and try again.")
        }
    }

    // ----- Codex (Sign in with ChatGPT) -----

    private fun beginLease(slot: AtomicReference<String?>, prefix: String): String {
        val id = "$prefix-${UUID.randomUUID()}"
        runCatching { foregroundLeases.acquire(id) }.onFailure { error ->
            _state.update {
                it.copy(notice = "Android could not keep this work active in the background: ${error.message ?: "service unavailable"}")
            }
        }
        slot.getAndSet(id)?.let(foregroundLeases::release)
        return id
    }

    private fun endLease(slot: AtomicReference<String?>, id: String? = slot.get()): Boolean {
        if (id == null) return false
        val endedCurrent = slot.compareAndSet(id, null)
        if (endedCurrent) foregroundLeases.release(id)
        return endedCurrent
    }

    /**
     * Starts the Codex OAuth flow: spins up the loopback listener and returns the authorization URL
     * for the UI to open in the browser. The exchange completes asynchronously; state flips when done.
     */
    fun startCodexSignIn(): String? {
        if (!BuildConfig.CODEX_OAUTH_ENABLED) return null
        return runCatching {
            val url = codexAuth.buildAuthUrl()
            val verifier = requireNotNull(codexAuth.pendingVerifier)
            val expectedState = requireNotNull(codexAuth.pendingState)
            codexAuth.startLoopback(
                expectedState = expectedState,
                onError = { message ->
                    viewModelScope.launch(Dispatchers.IO) {
                        _state.update { it.copy(error = "Codex sign-in failed: $message") }
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
                }
            }
            viewModelScope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(5 * 60_000L)
                codexAuth.stopLoopback()
            }
            url
        }.getOrElse { e ->
            codexAuth.stopLoopback()
            _state.update { it.copy(error = "Codex sign-in failed: ${e.message}") }
            null
        }
    }

    fun signOutCodex() {
        codexAuth.stopLoopback()
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

    private val githubAuth by lazy {
        GitHubAuth(
            http,
            store = keyStore::putAll,
            read = keyStore::get,
            credentialLock = gitCredentialLock,
            clientId = githubOAuthClientId(BuildConfig.GITHUB_OAUTH_CLIENT_ID, BuildConfig.DEBUG),
        )
    }
    private enum class GitHubSignInStatus { ACTIVE, CANCELED, COMMITTED }

    private class GitHubSignInAttempt {
        private val status = AtomicReference(GitHubSignInStatus.ACTIVE)
        fun canContinue(): Boolean = status.get() != GitHubSignInStatus.CANCELED
        fun cancel(): Boolean = status.compareAndSet(GitHubSignInStatus.ACTIVE, GitHubSignInStatus.CANCELED)
        fun commit(): Boolean = status.compareAndSet(GitHubSignInStatus.ACTIVE, GitHubSignInStatus.COMMITTED)
    }

    private val githubSignInAttempt = AtomicReference<GitHubSignInAttempt?>(null)

    fun startGitHubSignIn() {
        val attempt = GitHubSignInAttempt()
        if (!githubSignInAttempt.compareAndSet(null, attempt)) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val device = githubAuth.startDeviceFlow()
                if (!attempt.canContinue()) throw GitHubAuth.SignInAbandonedException()
                _state.update { it.copy(githubAuthCode = device.userCode, githubVerifyUri = device.verificationUri) }
                val token = githubAuth.pollForToken(device, attempt::canContinue)
                githubAuth.finishSignIn(token, attempt::canContinue) { login ->
                    if (!attempt.commit() || !githubSignInAttempt.compareAndSet(attempt, null)) {
                        throw GitHubAuth.SignInAbandonedException()
                    }
                    _state.update {
                        it.copy(
                            githubLogin = login,
                            githubAuthCode = null,
                            githubVerifyUri = null,
                            notice = "Signed in as @$login",
                        )
                    }
                }
            }.onFailure { error ->
                attempt.cancel()
                if (githubSignInAttempt.compareAndSet(attempt, null)) {
                    _state.update {
                        it.copy(
                            githubAuthCode = null,
                            githubVerifyUri = null,
                            error = if (error is GitHubAuth.SignInAbandonedException) {
                                null
                            } else {
                                "GitHub sign-in failed: ${error.message}"
                            },
                        )
                    }
                }
            }
        }
    }

    fun cancelGitHubSignIn() {
        val attempt = githubSignInAttempt.get() ?: return
        if (!attempt.cancel()) return
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
        if (_state.value.isRunning) return fail("Stop the current agent before importing a backup.")
        if (_state.value.sessionLoading) return fail("Wait for the current data operation to finish.")
        sessionSwitchJob?.cancel()
        val selection = ++sessionSelection
        pendingMessages.clear()
        _state.update { it.copy(sessionLoading = true, error = null, queued = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val restoreWriteBoundary = sessionWriteOrder.incrementAndGet()
                lateinit var normalizedRestore: BackupRestore
                val count = sessionStore.reconcileExternalRestore(restoreWriteBoundary) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        TransferBundle.import(getApplication<Application>().filesDir, input) {
                            // Keep normalization inside the durable import transaction. Any failed
                            // project/session/settings write restores the complete previous tree.
                            val linkedFolderIds = sharedFolderStore.list().map { it.id }.toSet()
                            val importedProjects = projectStore.list()
                            val restoredProjects = importedProjects.safeAfterRestore(linkedFolderIds)
                            if (restoredProjects != importedProjects) projectStore.replace(restoredProjects)
                            val restoredProjectIds = restoredProjects.map { it.id }.toSet()
                            sessionStore.list().forEach { meta ->
                                val safeProject = meta.projectId.safeProjectAfterRestore(restoredProjectIds)
                                if (safeProject != meta.projectId) {
                                    sessionStore.load(meta.id)?.let { session ->
                                        sessionStore.save(session.copy(projectId = safeProject))
                                    }
                                }
                            }
                            val saved = appSettings.load().safeAfterRestore()
                            val loaded = saved.activeSessionId?.let(sessionStore::load) ?: sessionStore.loadLatest()
                            val restored = loaded ?: PersistedSession(
                                newSessionId(),
                                "New chat",
                                System.currentTimeMillis(),
                                emptyList(),
                            )
                            val safeProjectId = restored.projectId?.takeIf(PROJECT_ID::matches)
                            val repaired = restored.messages.map { it.toDomain() }.let {
                                if (restored.activeTurn) repairInterruptedHistory(it) else it
                            }
                            val normalized = restored.copy(
                                messages = repaired.map { it.toPersisted() },
                                projectId = safeProjectId,
                                activeTurn = false,
                            )
                            if (loaded == null) {
                                sessionStore.create(normalized)
                            } else if (normalized != restored) {
                                sessionStore.save(normalized)
                            }
                            val normalizedSettings = saved.copy(activeSessionId = normalized.id)
                            appSettings.save(normalizedSettings)
                            normalizedRestore = BackupRestore(
                                0,
                                normalizedSettings,
                                normalized,
                                repaired,
                                modelPrefs.favourites(),
                                modelPrefs.hiddenModels(),
                                modelPrefs.disabledProviders(),
                                sessionStore.list(),
                                restoredProjects,
                            )
                        }
                    } ?: error("could not open file")
                }
                normalizedRestore.copy(count = count)
            }
            result.fold(
                onSuccess = { restored ->
                    withContext(Dispatchers.Main.immediate) {
                        if (selection != sessionSelection) return@withContext
                        history = restored.messages
                        sessionId = restored.session.id
                        val activeProjectId = setActiveProject(restored.session.projectId)
                        todoStore.replace(restored.session.todos)
                        _state.update {
                            it.copy(
                                favourites = restored.favourites,
                                hiddenModels = restored.hiddenModels,
                                disabledProviders = restored.disabledProviders,
                                autoAccept = restored.settings.autoAccept,
                                lines = restored.messages.toChatLines(),
                                timelineEpoch = it.timelineEpoch + 1,
                                currentSessionId = restored.session.id,
                                currentProjectId = activeProjectId,
                                sessions = restored.sessions,
                                projects = restored.projects,
                                sessionLoading = false,
                                notice = "Restored ${restored.count} file(s)",
                            )
                        }
                        reloadProviders()
                        onRestored()
                    }
                },
                onFailure = { error ->
                    withContext(Dispatchers.Main.immediate) {
                        if (selection == sessionSelection) {
                            _state.update { it.copy(sessionLoading = false, error = "Import failed: ${error.message}") }
                        }
                    }
                },
            )
        }
    }
    fun resolvePermission(approved: Boolean) {
        val approvalId = pendingNativeApprovalId.getAndSet(null) ?: return
        _state.update { current ->
            current.copy(
                pendingPermission = null,
                lines = current.lines.map { line ->
                    if (line is ChatLine.ToolActivity && line.id == approvalId && line.status == ToolStatus.AWAITING_APPROVAL) {
                        line.copy(
                            status = if (approved) ToolStatus.RUNNING else ToolStatus.STOPPED,
                            detail = if (approved) "Approved" else "Denied",
                        )
                    } else {
                        line
                    }
                },
            )
        }
        (getApplication<Application>() as PhoneCodeApplication).turnScope.launch {
            try {
                misulRuntime.respondToApproval(approvalId, approved)
            } catch (_: Throwable) {
                runCatching { misulRuntime.abort() }
                fail("Could not send the approval decision to Misul.")
            }
        }
    }
    fun resolveQuestion(answers: List<UserAnswer>) { pendingQuestionDecision?.complete(answers) }

    private fun misulRuntimeSpec(
        preset: ProviderPreset,
        selected: ModelOption,
        pinnedWorkspace: File,
        credential: String,
        projectId: String?,
    ): MisulRuntimeSpec {
        require(preset.wireFormat == WireFormat.OPENAI_COMPAT) { "${preset.displayName} is not available in this alpha" }
        val limits = limitFor(selected)
        val contextWindow = (limits?.context ?: 128_000L).coerceAtLeast(1)
        val outputLimit = (limits?.output ?: minOf(32_000L, contextWindow)).coerceIn(1, contextWindow)
        val reasoning = when (_state.value.effort) {
            ReasoningEffort.DEFAULT, ReasoningEffort.NONE -> null
            ReasoningEffort.ULTRA, ReasoningEffort.MAX -> "max"
            else -> _state.value.effort.wireValue
        }?.takeIf { supportsReasoning(selected) }
        val instructions = loadProjectInstructions(pinnedWorkspace, appSettings.load().customInstructions)
        val systemPrompt = buildString {
            append("You are Misul Agent, a coding agent running directly on the user's Android phone. ")
            append("Work in the provided workspace, use tools when they improve correctness, preserve user data, and report results plainly.")
            if (instructions.isNotEmpty()) append("\n\n").append(instructions.joinToString("\n\n"))
        }
        return MisulRuntimeSpec(
            workspaceRoot = pinnedWorkspace,
            stateRoot = misulStateRoot(getApplication<Application>().filesDir, projectId),
            systemPrompt = systemPrompt,
            model = MisulModel(
                id = selected.modelId,
                name = selected.label.substringAfter(" · ", selected.modelId),
                provider = selected.providerId,
                contextWindow = contextWindow,
                outputLimit = outputLimit,
                reasoning = reasoning,
                toolCall = selected.toolCall,
            ),
            provider = MisulProvider(
                id = selected.providerId,
                endpoint = preset.baseUrl,
                credential = credential,
                dialect = when (selected.providerId) {
                    "openrouter" -> "openrouter_chat"
                    "deepseek" -> "deepseek_chat"
                    else -> "openai_chat"
                },
                headers = preset.extraHeaders,
            ),
            allowMutatingTools = _state.value.autoAccept,
        )
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

    fun send(input: String, images: List<MessagePart.Image> = emptyList()): Boolean {
        val text = input.trim()
        if (text.isEmpty() && images.isEmpty()) return false
        if (images.isNotEmpty()) {
            fail("Photos are not available through the Misul runtime in this alpha yet.")
            return false
        }
        if (_state.value.sessionLoading) {
            fail("Wait for this chat to finish opening.")
            return false
        }
        if (_state.value.isRunning) {
            if (images.isNotEmpty()) {
                fail("Wait for the current turn before sending a photo.")
                return false
            }
            return synchronized(queueStateLock) {
                if (!_state.value.isRunning) return@synchronized false
                // Queue it for the running turn instead of dropping it; the agent picks it up at its next step.
                pendingMessages.add(text)
                _state.update { it.copy(queued = it.queued + text) }
                checkpointQueuedMessages(_state.value.queued, activeTurn = true, turnOutcome = null)
                true
            }
        }
        if (_state.value.queued.isNotEmpty()) {
            fail("Restore or clear the unsent follow-ups before sending another message.")
            return false
        }
        val selected = _state.value.selected ?: run {
            fail("Select a model first.")
            return false
        }
        val preset = providerFor(selected.providerId) ?: run {
            fail("Unknown provider: ${selected.providerId}")
            return false
        }
        // Codex authenticates with the ChatGPT OAuth token (not an API key); gate on being signed in here,
        // then resolve a fresh token off the main thread inside the turn (accessToken() may refresh, i.e. hit
        // the network). Every other provider uses its stored API key directly.
        val isCodex = preset.id == "codex"
        if (keyStore.get(if (isCodex) "codex.access" else providerSecretName(selected.providerId)).isNullOrBlank()) {
            fail(if (isCodex) "Sign in with ChatGPT in Settings to use Codex." else "Set an API key for ${preset.displayName} in Settings.")
            return false
        }

        resetStreamingBuffers()
        _state.update {
            it.copy(
                lines = it.lines + ChatLine.User(text, images),
                streaming = "",
                streamingReasoning = "",
                isRunning = true,
                retry = null,
                error = null,
                interruptedTurn = false,
                turnOutcome = null,
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
                persist(
                    turnHistory,
                    activeTurn = true,
                    targetSessionId = turnSessionId,
                    targetProjectId = turnProjectId,
                    expectedGeneration = gen,
                )
            }
            try {
                val credential = keyStore.get(providerSecretName(selected.providerId))
                if (credential.isNullOrBlank()) {
                    sessionStore.setActiveTurn(turnSessionId, false, sessionWriteOrder.incrementAndGet())
                    fail("Set an API key for ${preset.displayName} in Settings.")
                    return@launch
                }
                if (sessionStore.list().none { it.id == turnSessionId && it.branchInitialized } &&
                    autoBranchIfEnabled(pinnedWorkspace, turnSessionId)
                ) {
                    sessionStore.setBranchInitialized(turnSessionId)
                }
                val runtimeSpec = misulRuntimeSpec(preset, selected, pinnedWorkspace, credential, turnProjectId)
                importActiveSession(runtimeSpec, turnSessionId, startingHistory)
                var promptText = text
                while (gen == generation) {
                    val completeText = StringBuilder()
                    val completeReasoning = StringBuilder()
                    val settlement = misulRuntime.prompt(
                        spec = runtimeSpec,
                        sessionId = turnSessionId,
                        prompt = promptText,
                    ) { event ->
                        if (gen != generation) return@prompt
                        when (event) {
                            is MisulRuntimeEvent.Text -> {
                                completeText.append(event.delta)
                                appendStreaming(text = event.delta, expectedGeneration = gen)
                            }
                            is MisulRuntimeEvent.Reasoning -> {
                                completeReasoning.append(event.delta)
                                appendStreaming(reasoning = event.delta, expectedGeneration = gen)
                            }
                            is MisulRuntimeEvent.ProtocolError -> throw IllegalStateException(event.message)
                            is MisulRuntimeEvent.ToolStarted -> {
                                commitStreaming()
                                _state.update { current ->
                                    current.copy(lines = current.lines + ChatLine.ToolActivity(
                                        id = event.id,
                                        name = event.name,
                                        status = ToolStatus.RUNNING,
                                        detail = summarizeArgs(event.input),
                                        input = boundedToolInput(event.input),
                                    ))
                                }
                            }
                            is MisulRuntimeEvent.ToolFinished -> _state.update { current ->
                                val index = current.lines.indexOfLast {
                                    it is ChatLine.ToolActivity && it.id == event.id && it.status == ToolStatus.RUNNING
                                }
                                if (index < 0) current else current.copy(lines = current.lines.toMutableList().also { lines ->
                                    lines[index] = (lines[index] as ChatLine.ToolActivity).copy(
                                        status = if (event.isError) ToolStatus.ERROR else ToolStatus.DONE,
                                        detail = if (event.isError) "Tool failed" else "Completed",
                                    )
                                })
                            }
                            is MisulRuntimeEvent.ApprovalRequested -> {
                                pendingNativeApprovalId.set(event.id)
                                _state.update { current ->
                                    current.copy(
                                        pendingPermission = PermissionRequest(event.name, summarizeArgs(event.input)),
                                        lines = current.lines.map { line ->
                                            if (line is ChatLine.ToolActivity && line.id == event.id) {
                                                line.copy(status = ToolStatus.AWAITING_APPROVAL)
                                            } else {
                                                line
                                            }
                                        },
                                    )
                                }
                            }
                            is MisulRuntimeEvent.Started, is MisulRuntimeEvent.Ended -> Unit
                        }
                    }
                    if (gen != generation) break

                    commitStreaming()
                    val assistantParts = buildList {
                        completeReasoning.toString().takeIf(String::isNotBlank)?.let { add(MessagePart.Reasoning(it)) }
                        settlement.content.ifBlank { completeText.toString() }
                            .takeIf(String::isNotBlank)?.let { add(MessagePart.Text(it)) }
                    }
                    if (assistantParts.isNotEmpty()) history = history + ChatMessage(Role.ASSISTANT, assistantParts)

                    val failed = settlement.status != "completed"
                    if (failed) {
                        persist(
                            history,
                            targetSessionId = turnSessionId,
                            targetProjectId = turnProjectId,
                            expectedGeneration = gen,
                            turnOutcome = TurnOutcome.FAILED,
                            queuedMessages = _state.value.queued,
                        )
                        _state.update {
                            it.copy(
                                isRunning = false,
                                interruptedTurn = false,
                                turnOutcome = TurnOutcome.FAILED,
                                error = settlement.userFacingFailure(),
                            )
                        }
                        break
                    }

                    val followUp = synchronized(queueStateLock) {
                        pendingMessages.poll()?.also {
                            _state.update { current -> current.copy(queued = current.queued.drop(1)) }
                        }
                    }
                    if (followUp == null) {
                        persist(
                            history,
                            targetSessionId = turnSessionId,
                            targetProjectId = turnProjectId,
                            expectedGeneration = gen,
                        )
                        _state.update {
                            it.copy(isRunning = false, interruptedTurn = false, turnOutcome = null, error = null)
                        }
                        break
                    }

                    history = history + ChatMessage(Role.USER, listOf(MessagePart.Text(followUp)))
                    _state.update { it.copy(lines = it.lines + ChatLine.User(followUp)) }
                    persist(
                        history,
                        activeTurn = true,
                        targetSessionId = turnSessionId,
                        targetProjectId = turnProjectId,
                        expectedGeneration = gen,
                        queuedMessages = _state.value.queued,
                    )
                    resetStreamingBuffers()
                    promptText = followUp
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (gen == generation) {
                    commitStopped(turnOutcome = TurnOutcome.FAILED)
                    _state.update {
                        it.copy(
                            error = "The turn stopped unexpectedly: ${humanizeError(error.message ?: error.javaClass.simpleName)} Review workspace changes before retrying.",
                            interruptedTurn = false,
                            turnOutcome = TurnOutcome.FAILED,
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
        return true
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
        if (_state.value.queued.isNotEmpty()) {
            fail("Restore or clear the unsent follow-ups before retrying this turn.")
            return
        }
        val lastUser = _state.value.lines.filterIsInstance<ChatLine.User>().lastOrNull() ?: return
        val previousHistory = history
        val previousLines = _state.value.lines
        val previousEpoch = _state.value.timelineEpoch
        val historyCut = redoCutIndex(history)
        if (historyCut >= 0) history = history.take(historyCut)
        val lineCut = _state.value.lines.indexOfLast { it is ChatLine.User }
        if (lineCut >= 0) _state.update { it.copy(lines = it.lines.take(lineCut), timelineEpoch = it.timelineEpoch + 1) }
        if (!send(lastUser.text, lastUser.images)) {
            history = previousHistory
            _state.update { it.copy(lines = previousLines, timelineEpoch = previousEpoch) }
        }
    }

    fun cancel() {
        val (stoppedWriteOrder, stoppedQueued) = synchronized(queueStateLock) {
            generation++ // invalidate the in-flight turn's events immediately, then clean up here (single owner)
            val writeOrder = sessionWriteOrder.incrementAndGet()
            val queued = _state.value.queued
            pendingMessages.clear() // stop means stop: don't let queued messages auto-run after a cancel
            _state.update {
                it.copy(
                    lines = it.lines.map { line ->
                        if (line is ChatLine.ToolActivity && line.status == ToolStatus.AWAITING_APPROVAL) {
                            line.copy(
                                status = ToolStatus.STOPPED,
                                detail = STOPPED_BEFORE_APPROVAL_MESSAGE,
                            )
                        } else {
                            line
                        }
                    },
                    isRunning = false,
                    retry = null,
                    pendingPermission = null,
                    pendingQuestion = null,
                    interruptedTurn = false,
                    turnOutcome = TurnOutcome.STOPPED,
                )
            }
            writeOrder to queued
        }
        (getApplication<Application>() as PhoneCodeApplication).turnScope.launch {
            runCatching { misulRuntime.abort() }
        }
        job?.cancel()
        val stoppedLease = turnLease.getAndSet(null)
        // The cancelled job's finally skips the pin clear (generation moved on) - release it here
        // so no stale workspace pin outlives the turn.
        turnWorkspace = null
        pendingNativeApprovalId.set(null)
        pendingQuestionDecision?.complete(emptyList())
        commitStopped(persistChanges = false)
        val stoppedSessionId = sessionId
        val stoppedProjectId = currentProjectId
        val stoppedHistory = history
        val stoppedTodos = todoStore.snapshot()
        (getApplication<Application>() as PhoneCodeApplication).turnScope.launch {
            try {
                if (stoppedHistory.isEmpty()) {
                    sessionStore.setActiveTurn(stoppedSessionId, false, stoppedWriteOrder)
                } else {
                    persist(
                        stoppedHistory,
                        targetSessionId = stoppedSessionId,
                        targetProjectId = stoppedProjectId,
                        targetTodos = stoppedTodos,
                        writeOrder = stoppedWriteOrder,
                        turnOutcome = TurnOutcome.STOPPED,
                        queuedMessages = stoppedQueued,
                    )
                }
            } finally {
                stoppedLease?.let(foregroundLeases::release)
            }
        }
    }

    /**
     * Flush a cancelled turn's partial reply into BOTH the visible lines and history. The turn never
     * reached TurnComplete, so its streamed text lived only in the streaming buffer - history was left
     * ending on a bare user message, which read as lost context next message (and which Anthropic rejects
     * as two user turns in a row). Writing the partial assistant reply keeps the model's view = the screen.
     */
    private fun commitStopped(
        persistChanges: Boolean = true,
        turnOutcome: TurnOutcome? = _state.value.turnOutcome,
    ) {
        val streamed = commitStreaming()
        val stoppedApprovalCallIds = _state.value.lines
            .filterIsInstance<ChatLine.ToolActivity>()
            .filter { it.status == ToolStatus.STOPPED && it.detail == STOPPED_BEFORE_APPROVAL_MESSAGE }
            .mapTo(mutableSetOf()) { it.id }
        val parts = buildList {
            if (streamed.reasoning.isNotBlank()) add(MessagePart.Reasoning(streamed.reasoning))
            if (streamed.text.isNotBlank()) add(MessagePart.Text(streamed.text))
        }
        history = repairInterruptedHistory(history, stoppedApprovalCallIds).let { repaired ->
            if (parts.isEmpty()) repaired else repaired + ChatMessage(Role.ASSISTANT, parts)
        }
        if (history.isNotEmpty() && persistChanges) {
            persist(turnOutcome = turnOutcome)
        } else if (persistChanges) {
            sessionStore.setActiveTurn(sessionId, false, sessionWriteOrder.incrementAndGet())
        }
    }

    /** Save the current conversation to disk. Title = first user line; no-op for an empty history. */
    private fun persist(
        snapshot: List<ChatMessage> = history,
        activeTurn: Boolean = false,
        targetSessionId: String = sessionId,
        targetProjectId: String? = currentProjectId,
        targetTodos: List<TodoItem> = todoStore.snapshot(),
        expectedGeneration: Int? = null,
        writeOrder: Long = sessionWriteOrder.incrementAndGet(),
        turnOutcome: TurnOutcome? = _state.value.turnOutcome,
        queuedMessages: List<String> = _state.value.queued,
    ) {
        if (snapshot.isEmpty()) return
        if (expectedGeneration != null && expectedGeneration != generation) return
        val suggestedTitle = snapshot.firstOrNull { it.role == Role.USER }
            ?.parts?.filterIsInstance<MessagePart.Text>()?.firstOrNull()?.text?.take(40)?.takeIf { it.isNotBlank() }
            ?: "New chat"
        runCatching {
            if (expectedGeneration != null && expectedGeneration != generation) return@runCatching
            sessionStore.checkpoint(
                PersistedSession(
                    id = targetSessionId,
                    title = suggestedTitle,
                    updatedAt = System.currentTimeMillis(),
                    messages = snapshot.map { it.toPersisted() },
                    projectId = targetProjectId,
                    activeTurn = activeTurn,
                    todos = targetTodos,
                    turnOutcome = turnOutcome?.name,
                    queuedMessages = queuedMessages,
                ),
                writeOrder,
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
                        lines += ChatLine.ToolActivity(
                            part.id,
                            part.name,
                            ToolStatus.DONE,
                            summarizeArgs(part.argsJson),
                            boundedToolInput(part.argsJson),
                        )
                    is MessagePart.ToolResult -> {
                        val index = lines.indexOfLast { it is ChatLine.ToolActivity && it.id == part.callId }
                        if (index >= 0) {
                            lines[index] = (lines[index] as ChatLine.ToolActivity).copy(
                                status = when {
                                    !part.isError -> ToolStatus.DONE
                                    part.content == USER_STOPPED_BEFORE_APPROVAL_RESULT -> ToolStatus.STOPPED
                                    else -> ToolStatus.ERROR
                                },
                                detail = if (part.content == USER_STOPPED_BEFORE_APPROVAL_RESULT) {
                                    STOPPED_BEFORE_APPROVAL_MESSAGE
                                } else {
                                    part.content
                                },
                            )
                        }
                    }
                }
            }
        }
        return lines
    }

    private fun appendStreaming(text: String = "", reasoning: String = "", expectedGeneration: Int) {
        val snapshot = synchronized(streamBufferLock) {
            if (expectedGeneration != generation) return@synchronized null
            streamingTextBuffer.append(text)
            streamingReasoningBuffer.append(reasoning)
            val now = System.nanoTime()
            if (lastStreamFlushAt != 0L && now - lastStreamFlushAt < STREAM_UI_INTERVAL_NANOS) {
                null
            } else {
                lastStreamFlushAt = now
                StreamSnapshot(streamingTextBuffer.toString(), streamingReasoningBuffer.toString())
            }
        }
        snapshot?.let { current ->
            _state.update { it.copy(streaming = current.text, streamingReasoning = current.reasoning, retry = null) }
        }
    }

    private fun resetStreamingBuffers() = synchronized(streamBufferLock) {
        streamingTextBuffer.setLength(0)
        streamingReasoningBuffer.setLength(0)
        lastStreamFlushAt = 0L
    }

    private fun commitStreaming(): StreamSnapshot {
        val snapshot = synchronized(streamBufferLock) {
            StreamSnapshot(streamingTextBuffer.toString(), streamingReasoningBuffer.toString()).also {
                streamingTextBuffer.setLength(0)
                streamingReasoningBuffer.setLength(0)
                lastStreamFlushAt = 0L
            }
        }
        _state.update { state ->
            var lines = state.lines
            if (snapshot.reasoning.isNotBlank()) lines = lines + ChatLine.Reasoning(snapshot.reasoning)
            if (snapshot.text.isNotBlank()) lines = lines + ChatLine.Assistant(snapshot.text)
            if (lines === state.lines && state.streaming.isEmpty() && state.streamingReasoning.isEmpty()) {
                state
            } else {
                state.copy(lines = lines, streaming = "", streamingReasoning = "")
            }
        }
        return snapshot
    }

    private suspend fun importActiveSession(
        spec: MisulRuntimeSpec,
        activeSessionId: String,
        activeHistoryBeforePrompt: List<ChatMessage>,
    ) {
        val session = sessionStore.load(activeSessionId) ?: return
        val active = session.copy(messages = activeHistoryBeforePrompt.map { it.toPersisted() })
        misulRuntime.importSessions(
            spec,
            listOf(
                active.toBoundedMisulImportSession(
                    provider = spec.provider.id,
                    model = spec.model.id,
                    api = spec.provider.dialect,
                ),
            ),
        )
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

    private fun summarizeArgs(argsJson: String): String = argsJson.replace("\n", " ").take(120)

    private fun boundedToolInput(argsJson: String): String = if (argsJson.length <= MAX_TOOL_INPUT_CHARS) {
        argsJson
    } else {
        argsJson.take(MAX_TOOL_INPUT_CHARS / 2) +
            "\n[Input truncated; showing beginning and end.]\n" +
            argsJson.takeLast(MAX_TOOL_INPUT_CHARS / 2)
    }

    override fun onCleared() {
        // Stop background daemons promptly: the GitHub poll thread checks this attempt every ≤500ms,
        // and the Codex loopback listener would otherwise hold port 1455 until its 5-min timeout.
        githubSignInAttempt.getAndSet(null)?.cancel()
        configHotReload.close()
        codexAuth.stopLoopback()
        foregroundLeases.unregisterStopHandler("turn")
        foregroundLeases.unregisterStopHandler("processes")
        shellBackend.stopAll()
    }
}

internal fun reportHttpClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
    .callTimeout(20, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

internal fun aiReportPayload(
    category: String,
    note: String,
    appVersion: String,
): String {
    require(category in setOf("hate", "harassment", "sexual", "violence", "self_harm", "illegal", "privacy", "other"))
    return buildJsonObject {
        put("version", 1)
        put("category", category)
        put("appVersion", appVersion)
        put("platform", "android")
        note.trim().takeIf { it.isNotEmpty() }?.let { put("note", it.take(1000)) }
    }.toString()
}

/**
 * The rewind point for redo: the last HUMAN prompt - a Role.USER message carrying Text. Tool
 * RESULTS also ride Role.USER (loop convention), and cutting at one of those would orphan the
 * preceding tool_use. Pure function so the shape is unit-testable.
 */
internal fun redoCutIndex(history: List<ChatMessage>): Int =
    history.indexOfLast { m -> m.role == Role.USER && m.parts.any { it is MessagePart.Text } }

internal fun repairInterruptedHistory(
    history: List<ChatMessage>,
    stoppedApprovalCallIds: Set<String> = emptySet(),
): List<ChatMessage> {
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
                content = if (it.id in stoppedApprovalCallIds) {
                    USER_STOPPED_BEFORE_APPROVAL_RESULT
                } else {
                    "Interrupted before Misul Agent recorded the result. Review workspace changes before retrying."
                },
                isError = true,
            )
        },
    )
}

private const val TURN_INTERRUPTED_MESSAGE =
    "The previous turn stopped unexpectedly. Review any file changes before retrying."
private const val STOPPED_BEFORE_APPROVAL_MESSAGE = "Stopped before approval."
private const val USER_STOPPED_BEFORE_APPROVAL_RESULT = "[phonecode:user-stopped-before-approval]"

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

internal fun visibleCodexModels(models: List<CodexModelInfo>): List<CodexModelInfo> =
    models.filter { it.visibility == "list" }

internal fun agentCapableModels(models: List<ModelOption>): List<ModelOption> =
    models.filter { it.toolCall }

internal fun builtInFallbackModels(models: List<ModelOption>, catalogModelIds: Set<String>): List<ModelOption> =
    models.filter { it.modelId !in catalogModelIds }

internal fun configuredModelForActivation(
    models: List<ModelOption>,
    current: ModelOption?,
    providerConfigured: (String) -> Boolean,
): ModelOption? = current?.takeIf { it.toolCall && providerConfigured(it.providerId) }
    ?: models.firstOrNull { it.toolCall && providerConfigured(it.providerId) }

internal fun configuredModelForProviderActivation(
    models: List<ModelOption>,
    providerId: String,
    hiddenModels: Set<String>,
): ModelOption? = models.firstOrNull {
    it.providerId == providerId && it.toolCall && "${it.providerId}/${it.modelId}" !in hiddenModels
}

private fun newSessionId(): String = "session-${UUID.randomUUID()}"

internal fun providerAllowed(providerId: String, codexOAuthEnabled: Boolean): Boolean =
    providerId != "anthropic" && providerId != "codex"

fun builtInModels(codexOAuthEnabled: Boolean = BuildConfig.CODEX_OAUTH_ENABLED): List<ModelOption> = listOf(
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
).filter { providerAllowed(it.providerId, codexOAuthEnabled) }

private const val CATALOG_REFRESH_TTL_MS = 6L * 60 * 60 * 1000
private const val CODEX_REFRESH_TTL_MS = 5L * 60 * 1000
private val STARTUP_DEFER_MILLIS = if (Build.FINGERPRINT == "robolectric") 0L else 1_500L
private const val MAX_TOOL_INPUT_CHARS = 64_000
private const val STREAM_UI_INTERVAL_NANOS = 50_000_000L
internal val PROJECT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")

internal fun misulStateRoot(filesDir: File, projectId: String?): File {
    require(projectId == null || PROJECT_ID.matches(projectId)) { "Invalid project id" }
    val root = File(filesDir, "misul/state").canonicalFile
    val parent = if (projectId == null) root else File(root, "projects").canonicalFile
    require(projectId == null || parent.parentFile == root) { "Misul project state root was redirected" }
    val scoped = File(parent, projectId ?: "unassigned").canonicalFile
    require(scoped.parentFile == parent) { "Misul state must remain inside its project scope" }
    return scoped
}
private const val BUNDLED_CATALOG = """
{
  "openai":{"id":"openai","name":"OpenAI","models":{"gpt-5.6":{"id":"gpt-5.6","name":"GPT-5.6","reasoning":true,"reasoning_options":[{"type":"effort","values":["none","low","medium","high","xhigh","max"]}],"tool_call":true,"attachment":true,"limit":{"context":1050000,"output":128000}},"gpt-5.6-sol":{"id":"gpt-5.6-sol","name":"GPT-5.6 Sol","reasoning":true,"reasoning_options":[{"type":"effort","values":["none","low","medium","high","xhigh","max"]}],"tool_call":true,"attachment":true,"limit":{"context":1050000,"output":128000}},"gpt-5.6-terra":{"id":"gpt-5.6-terra","name":"GPT-5.6 Terra","reasoning":true,"reasoning_options":[{"type":"effort","values":["none","low","medium","high","xhigh","max"]}],"tool_call":true,"attachment":true,"limit":{"context":1050000,"output":128000}},"gpt-5.6-luna":{"id":"gpt-5.6-luna","name":"GPT-5.6 Luna","reasoning":true,"reasoning_options":[{"type":"effort","values":["none","low","medium","high","xhigh","max"]}],"tool_call":true,"attachment":true,"limit":{"context":1050000,"output":128000}},"gpt-5.5":{"id":"gpt-5.5","name":"GPT-5.5"},"o3":{"id":"o3","name":"o3"}}},
  "anthropic":{"id":"anthropic","name":"Anthropic","models":{"claude-opus-4-8":{"id":"claude-opus-4-8","name":"Claude Opus 4.8"},"claude-sonnet-4-6":{"id":"claude-sonnet-4-6","name":"Claude Sonnet 4.6"},"claude-haiku-4-5":{"id":"claude-haiku-4-5","name":"Claude Haiku 4.5"}}},
  "openrouter":{"id":"openrouter","name":"OpenRouter","models":{"anthropic/claude-opus-4-8":{"id":"anthropic/claude-opus-4-8","name":"Claude Opus 4.8"}}},
  "opencode":{"id":"opencode","name":"OpenCode Zen","models":{"nemotron-3-ultra-free":{"id":"nemotron-3-ultra-free","name":"Nemotron 3 Ultra Free"}}},
  "opencode-go":{"id":"opencode-go","name":"OpenCode Go","api":"https://opencode.ai/zen/go/v1","models":{"deepseek-v4-flash":{"id":"deepseek-v4-flash","name":"DeepSeek V4 Flash","reasoning":true,"reasoning_options":[{"type":"effort","values":["high","max"]}],"tool_call":true,"attachment":false,"limit":{"context":1000000,"output":384000}},"mimo-v2.5":{"id":"mimo-v2.5","name":"MiMo V2.5","reasoning":true,"tool_call":true,"attachment":true,"limit":{"context":1000000,"output":128000}}}}
}
"""
