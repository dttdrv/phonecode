package dev.phonecode.tools.files

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * Resolves [path] under [workspacePath], rejecting anything that escapes the workspace root.
 * Confinement assumes a trusted local filesystem (single-user device): it canonicalizes symlinks
 * and '..' at resolve time, but does not defend against a symlink swapped in after resolution.
 */
internal fun resolveInWorkspace(workspacePath: String, path: String): File {
    val root = File(workspacePath).canonicalFile
    val raw = File(path)
    val target = if (raw.isAbsolute) raw else File(root, path)
    val canonical = target.canonicalFile
    require(canonical == root || canonical.path.startsWith(root.path + File.separator)) {
        "path escapes the workspace: $path"
    }
    return canonical
}

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun objectSchema(properties: Map<String, JsonObject>, required: List<String>): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject { properties.forEach { (k, v) -> put(k, v) } })
    put("required", buildJsonArray { required.forEach { add(it) } })
    put("additionalProperties", false)
}

internal fun arraySchema(items: JsonObject, description: String): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", items)
}

internal fun strSchema(description: String): JsonObject =
    buildJsonObject { put("type", "string"); put("description", description) }

internal fun intSchema(description: String): JsonObject =
    buildJsonObject { put("type", "integer"); put("description", description) }
