package dev.phonecode.app.data

import dev.phonecode.provider.domain.ChatMessage
import dev.phonecode.provider.domain.MessagePart
import dev.phonecode.provider.domain.Role
import dev.phonecode.tools.todo.TodoItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/**
 * On-device chat persistence. The provider message model is deliberately not @Serializable, so we map it
 * to a small serializable DTO and store each session as a JSON file under [dir]. Pure file IO + kotlinx
 * serialization - no Room/DB, no Android APIs - so it is fully unit-testable on the JVM and stays private
 * to the device (no backend). Sessions survive process death; the catalog is rebuilt by scanning [dir].
 */
@Serializable
enum class PersistedRole { USER, ASSISTANT }

@Serializable
sealed interface PersistedPart {
    @Serializable @SerialName("text") data class Text(val text: String) : PersistedPart
    @Serializable @SerialName("image") data class Image(val mimeType: String, val data: String) : PersistedPart
    @Serializable @SerialName("tool_call") data class ToolCall(val id: String, val name: String, val argsJson: String) : PersistedPart
    @Serializable @SerialName("tool_result") data class ToolResult(val callId: String, val content: String, val isError: Boolean = false) : PersistedPart
    @Serializable @SerialName("reasoning") data class Reasoning(val text: String) : PersistedPart
}

@Serializable
data class PersistedMessage(val role: PersistedRole, val parts: List<PersistedPart>)

@Serializable
data class PersistedSession(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<PersistedMessage>,
    val projectId: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val activeTurn: Boolean = false,
    val todos: List<TodoItem> = emptyList(),
    val branchInitialized: Boolean = false,
)

/** Lightweight catalog row for the sessions list (one-line preview, no full message bodies). */
data class SessionMeta(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val projectId: String? = null,
    val preview: String = "",
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val branchInitialized: Boolean = false,
)

/** Collapse basic markdown to plain text so drawer previews read clean (no **, *, `, #, >, lists, links). */
private fun stripMarkdown(s: String): String =
    s.replace(Regex("```[\\s\\S]*?```"), " ")                              // fenced code blocks
        .replace(Regex("`([^`]*)`"), "$1")                                // inline code
        .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")               // links / images -> text
        .replace(Regex("^\\s{0,3}#{1,6}\\s+", RegexOption.MULTILINE), "") // headings
        .replace(Regex("^\\s{0,3}>\\s?", RegexOption.MULTILINE), "")      // blockquotes
        .replace(Regex("^\\s*([-*+]|\\d+\\.)\\s+", RegexOption.MULTILINE), "") // list markers
        .replace(Regex("\\*\\*|__|[*_~]"), "")                            // bold / italic / strike markers
        .replace(Regex("\\s+"), " ")                                      // collapse whitespace incl. newlines
        .trim()

/** One-line preview for the drawer: the last message's first text part, stripped of markdown and trimmed. */
private fun previewOf(s: PersistedSession): String =
    s.messages.asReversed().firstNotNullOfOrNull { message ->
        message.parts.firstNotNullOfOrNull { (it as? PersistedPart.Text)?.text }
    }
        ?.let { stripMarkdown(it.take(500)).take(80) } ?: "" // take() first so the regex never runs over a multi-MB body

fun ChatMessage.toPersisted(): PersistedMessage =
    PersistedMessage(if (role == Role.USER) PersistedRole.USER else PersistedRole.ASSISTANT, parts.map { it.toPersisted() })

private fun MessagePart.toPersisted(): PersistedPart = when (this) {
    is MessagePart.Text -> PersistedPart.Text(text)
    is MessagePart.Image -> PersistedPart.Image(mimeType, data)
    is MessagePart.ToolCall -> PersistedPart.ToolCall(id, name, argsJson)
    is MessagePart.ToolResult -> PersistedPart.ToolResult(callId, content, isError)
    is MessagePart.Reasoning -> PersistedPart.Reasoning(text)
}

fun PersistedMessage.toDomain(): ChatMessage =
    ChatMessage(if (role == PersistedRole.USER) Role.USER else Role.ASSISTANT, parts.map { it.toDomain() })

private fun PersistedPart.toDomain(): MessagePart = when (this) {
    is PersistedPart.Text -> MessagePart.Text(text)
    is PersistedPart.Image -> MessagePart.Image(mimeType, data)
    is PersistedPart.ToolCall -> MessagePart.ToolCall(id, name, argsJson)
    is PersistedPart.ToolResult -> MessagePart.ToolResult(callId, content, isError)
    is PersistedPart.Reasoning -> MessagePart.Reasoning(text)
}

class SessionStore(private val dir: File) {
    private val json = storeJson
    private val writeOrders = mutableMapOf<String, Long>()

    // In-memory catalog (id -> meta) so the hot list() - rebuilt after every turn and every mutation -
    // never re-parses every session file's full message bodies. Authoritative for this process; the
    // on-device store has a single writer. ponytail: single-process cache; if a second writer ever
    // appears, drop it and re-scan. All access serializes on LOCK, which also closes the read-modify-write
    // races in the setX/rename helpers (concurrent load+save no longer lose each other's updates).
    private var metaCache: MutableMap<String, SessionMeta>? = null

    init {
        dir.mkdirs()
    }

    private inline fun <T> locked(block: () -> T): T = synchronized(LOCK, block)

    private fun fileFor(id: String): File {
        require(SAFE_ID.matches(id)) { "Invalid session id" }
        return File(dir, "$id.json")
    }

    private fun deletionKey(id: String): String = fileFor(id).absolutePath

    private fun metaOf(s: PersistedSession): SessionMeta =
        SessionMeta(s.id, s.title, s.updatedAt, s.projectId, previewOf(s), s.pinned, s.archived, s.branchInitialized)

    /** Lazily scan the dir once into [metaCache] (the only full-parse pass); later calls reuse it. */
    private fun cache(): MutableMap<String, SessionMeta> = metaCache ?: run {
        val map = LinkedHashMap<String, SessionMeta>()
        (dir.listFiles { f -> f.isFile && f.extension == "json" } ?: emptyArray()).forEach { file ->
            runCatching { json.decodeFromString(PersistedSession.serializer(), file.readText()) }.getOrNull()
                ?.takeIf { SAFE_ID.matches(it.id) && file.nameWithoutExtension == it.id }
                ?.let { map[it.id] = metaOf(it) }
        }
        metaCache = map
        map
    }

    fun save(session: PersistedSession): Unit = locked {
        require(SAFE_ID.matches(session.id)) { "Invalid session id" }
        dir.mkdirs()
        fileFor(session.id).writeTextAtomically(json.encodeToString(PersistedSession.serializer(), session))
        cache()[session.id] = metaOf(session)
        deletionTombstones.remove(deletionKey(session.id))
    }

    fun checkpoint(session: PersistedSession, writeOrder: Long? = null): PersistedSession = locked {
        if (!acceptsWrite(session.id, writeOrder)) return@locked load(session.id) ?: session
        val current = cache()[session.id]
        val updated = current?.let {
            session.copy(
                title = it.title.takeUnless { title -> title == "New chat" } ?: session.title,
                projectId = it.projectId,
                pinned = it.pinned,
                archived = it.archived,
                branchInitialized = it.branchInitialized,
            )
        } ?: session
        save(updated)
        updated
    }

    fun create(session: PersistedSession): Boolean = locked {
        if (fileFor(session.id).exists()) return@locked false
        save(session)
        true
    }

    fun load(id: String): PersistedSession? = locked {
        if (!SAFE_ID.matches(id)) return@locked null
        fileFor(id).takeIf { it.exists() }?.let { file ->
            runCatching { json.decodeFromString(PersistedSession.serializer(), file.readText()) }.getOrNull()
                ?.takeIf { it.id == id }
        }
    }

    /** All sessions, newest first, served from the in-memory catalog (no per-call file parse). */
    fun list(): List<SessionMeta> = locked { cache().values.sortedByDescending { it.updatedAt } }

    fun invalidateCatalog(): Unit = locked { metaCache = null }

    fun <T> reconcileExternalRestore(writeOrderBoundary: Long, restore: () -> T): T = locked {
        metaCache = null
        val result = try {
            restore()
        } finally {
            // The callback replaces session files and may read them while normalizing. Neither a
            // pre-import cache nor a cache built before rollback may cross this boundary.
            metaCache = null
        }
        cache().keys.forEach { id ->
            val key = deletionKey(id)
            deletionTombstones.remove(key)
            externalRestoreWriteFloors[key] = maxOf(externalRestoreWriteFloors[key] ?: Long.MIN_VALUE, writeOrderBoundary)
        }
        result
    }

    /**
     * The most recently updated session, or null. Reads a single file (the newest by mtime), so it is
     * cheap enough to call synchronously at startup - the restore must land before the UI can send, or a
     * quick first message after the app is killed starts cold and orphans the real conversation.
     */
    fun loadLatest(): PersistedSession? = locked {
        (dir.listFiles { f -> f.isFile && f.extension == "json" } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .asSequence()
            .mapNotNull { file ->
                runCatching { json.decodeFromString(PersistedSession.serializer(), file.readText()) }.getOrNull()
                    ?.takeIf { it.id == file.nameWithoutExtension }
            }
            .firstOrNull { SAFE_ID.matches(it.id) }
    }

    fun delete(id: String): Unit = locked {
        if (!SAFE_ID.matches(id)) return@locked
        fileFor(id).delete()
        cache().remove(id)
        writeOrders.remove(id)
        deletionTombstones += deletionKey(id)
        while (deletionTombstones.size > MAX_DELETION_TOMBSTONES) {
            deletionTombstones.remove(deletionTombstones.first())
        }
    }

    /** Rename a stored session in place (no-op if it doesn't exist). */
    fun rename(id: String, title: String): Unit = locked {
        load(id)?.let { save(it.copy(title = title)) }
    }

    /** Reassign a stored session to a project (null = unsorted). */
    fun setProject(id: String, projectId: String?): Unit = locked {
        load(id)?.let { save(it.copy(projectId = projectId)) }
    }

    /** Pin/unpin a stored session (pinned chats float to the top of the drawer). */
    fun setPinned(id: String, pinned: Boolean): Unit = locked {
        load(id)?.let { save(it.copy(pinned = pinned)) }
    }

    /** Archive/unarchive a stored session (archived chats drop out of the main list). */
    fun setArchived(id: String, archived: Boolean): Unit = locked {
        load(id)?.let { save(it.copy(archived = archived)) }
    }

    fun setActiveTurn(id: String, active: Boolean, writeOrder: Long? = null): Unit = locked {
        if (!acceptsWrite(id, writeOrder)) return@locked
        load(id)?.let { save(it.copy(activeTurn = active)) }
    }

    fun setBranchInitialized(id: String): Unit = locked {
        load(id)?.let { save(it.copy(branchInitialized = true)) }
    }

    private fun acceptsWrite(id: String, writeOrder: Long?): Boolean {
        if (!SAFE_ID.matches(id)) return false
        val key = deletionKey(id)
        if (key in deletionTombstones) return false
        externalRestoreWriteFloors[key]?.let { floor ->
            if (writeOrder == null || writeOrder <= floor) return false
        }
        if (writeOrder == null) return true
        if (writeOrder < (writeOrders[id] ?: Long.MIN_VALUE)) return false
        writeOrders[id] = writeOrder
        return true
    }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
        val LOCK = Any()
        val deletionTombstones = LinkedHashSet<String>()
        val externalRestoreWriteFloors = mutableMapOf<String, Long>()
        const val MAX_DELETION_TOMBSTONES = 1_024
    }
}
