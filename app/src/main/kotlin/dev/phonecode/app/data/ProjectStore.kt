package dev.phonecode.app.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class Project(val id: String, val name: String, val folderId: String? = null)

/** Folder grants are device-local and are intentionally absent from backups. */
fun List<Project>.safeAfterRestore(linkedFolderIds: Set<String>): List<Project> =
    map { project -> project.copy(folderId = project.folderId?.takeIf(linkedFolderIds::contains)) }

/** Chats whose project was not restored remain reachable under Unsorted. */
fun String?.safeProjectAfterRestore(projectIds: Set<String>): String? = this?.takeIf(projectIds::contains)

/** Persists the user's chat projects in a single JSON file. Sessions reference a project by id. */
class ProjectStore(private val file: File) {
    private val json = storeJson
    private val serializer = ListSerializer(Project.serializer())

    // add/rename/delete are read-modify-write over one shared file, so they serialize on a process-wide
    // lock (the same pattern as the other stores); without it concurrent writers lose each other's updates.
    private inline fun <T> locked(block: () -> T): T = synchronized(LOCK, block)

    fun list(): List<Project> = locked {
        if (file.exists()) runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())
        else emptyList()
    }

    private fun save(projects: List<Project>) {
        file.parentFile?.mkdirs()
        file.writeTextAtomically(json.encodeToString(serializer, projects))
    }

    fun add(id: String, name: String, folderId: String? = null): Project = locked {
        val project = Project(id, name, folderId)
        save(list() + project)
        project
    }

    fun rename(id: String, name: String): Unit = locked { save(list().map { if (it.id == id) it.copy(name = name) else it }) }

    fun delete(id: String): Unit = locked { save(list().filterNot { it.id == id }) }

    fun replace(projects: List<Project>): Unit = locked { save(projects) }

    private companion object {
        val LOCK = Any()
    }
}
