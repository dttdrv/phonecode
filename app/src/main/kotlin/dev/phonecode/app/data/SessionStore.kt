package dev.phonecode.app.data

import dev.phonecode.provider.domain.ChatMessage
import dev.phonecode.provider.domain.MessagePart
import dev.phonecode.provider.domain.Role
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
)

/** Lightweight catalog row for the sessions list (no message bodies). */
data class SessionMeta(val id: String, val title: String, val updatedAt: Long, val projectId: String? = null)

fun ChatMessage.toPersisted(): PersistedMessage =
    PersistedMessage(if (role == Role.USER) PersistedRole.USER else PersistedRole.ASSISTANT, parts.map { it.toPersisted() })

private fun MessagePart.toPersisted(): PersistedPart = when (this) {
    is MessagePart.Text -> PersistedPart.Text(text)
    is MessagePart.ToolCall -> PersistedPart.ToolCall(id, name, argsJson)
    is MessagePart.ToolResult -> PersistedPart.ToolResult(callId, content, isError)
    is MessagePart.Reasoning -> PersistedPart.Reasoning(text)
}

fun PersistedMessage.toDomain(): ChatMessage =
    ChatMessage(if (role == PersistedRole.USER) Role.USER else Role.ASSISTANT, parts.map { it.toDomain() })

private fun PersistedPart.toDomain(): MessagePart = when (this) {
    is PersistedPart.Text -> MessagePart.Text(text)
    is PersistedPart.ToolCall -> MessagePart.ToolCall(id, name, argsJson)
    is PersistedPart.ToolResult -> MessagePart.ToolResult(callId, content, isError)
    is PersistedPart.Reasoning -> MessagePart.Reasoning(text)
}

class SessionStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        dir.mkdirs()
    }

    // Filenames are derived from a sanitized id; the app generates collision-free ids (e.g. "session-<epochMs>").
    private fun fileFor(id: String): File = File(dir, id.replace(UNSAFE, "_") + ".json")

    fun save(session: PersistedSession) {
        dir.mkdirs()
        fileFor(session.id).writeText(json.encodeToString(PersistedSession.serializer(), session))
    }

    fun load(id: String): PersistedSession? =
        fileFor(id).takeIf { it.exists() }?.let { file ->
            runCatching { json.decodeFromString(PersistedSession.serializer(), file.readText()) }.getOrNull()
        }

    /** All sessions, newest first, skipping any unreadable/corrupt file. */
    fun list(): List<SessionMeta> =
        (dir.listFiles { f -> f.isFile && f.extension == "json" } ?: emptyArray())
            .mapNotNull { file ->
                runCatching { json.decodeFromString(PersistedSession.serializer(), file.readText()) }.getOrNull()
                    ?.let { SessionMeta(it.id, it.title, it.updatedAt, it.projectId) }
            }
            .sortedByDescending { it.updatedAt }

    fun delete(id: String) {
        fileFor(id).delete()
    }

    /** Rename a stored session in place (no-op if it doesn't exist). */
    fun rename(id: String, title: String) {
        load(id)?.let { save(it.copy(title = title)) }
    }

    /** Reassign a stored session to a project (null = unsorted). */
    fun setProject(id: String, projectId: String?) {
        load(id)?.let { save(it.copy(projectId = projectId)) }
    }

    private companion object {
        val UNSAFE = Regex("[^a-zA-Z0-9_-]")
    }
}
