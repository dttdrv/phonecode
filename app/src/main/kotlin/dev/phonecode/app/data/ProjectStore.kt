package dev.phonecode.app.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class Project(val id: String, val name: String)

/** Persists the user's chat projects in a single JSON file. Sessions reference a project by id. */
class ProjectStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(Project.serializer())

    fun list(): List<Project> =
        if (file.exists()) runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())
        else emptyList()

    private fun save(projects: List<Project>) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(serializer, projects))
    }

    fun add(id: String, name: String): Project {
        val project = Project(id, name)
        save(list() + project)
        return project
    }

    fun rename(id: String, name: String) = save(list().map { if (it.id == id) it.copy(name = name) else it })

    fun delete(id: String) = save(list().filterNot { it.id == id })
}
