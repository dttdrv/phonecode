package dev.phonecode.app.agent

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import dev.phonecode.app.data.SharedFolder
import dev.phonecode.app.data.SharedFolderStore
import dev.phonecode.tools.shared.SharedEntry
import dev.phonecode.tools.shared.SharedFileAccess
import dev.phonecode.tools.shared.SharedRoot
import dev.phonecode.tools.shared.sharedPathSegments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

class AndroidSharedFileAccess(
    context: Context,
    private val store: SharedFolderStore,
) : SharedFileAccess {
    private val resolver = context.contentResolver

    override fun roots(): List<SharedRoot> = store.list().map { SharedRoot(it.id, it.name, it.writable) }

    fun linkedFolder(uri: Uri): SharedFolder? = store.list().firstOrNull { it.handle == uri.toString() }

    fun link(uri: Uri): List<SharedFolder> {
        val existing = linkedFolder(uri)
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        var grantedFlags = 0
        try {
            val writable = runCatching {
                resolver.takePersistableUriPermission(uri, read or write)
                grantedFlags = read or write
                true
            }.getOrElse {
                resolver.takePersistableUriPermission(uri, read)
                grantedFlags = read
                false
            }
            val folder = SharedFolder(
                id = existing?.id ?: "folder-${UUID.randomUUID()}",
                name = displayName(uri),
                kind = "android-tree",
                handle = uri.toString(),
                writable = writable,
            )
            return store.add(folder)
        } catch (error: Throwable) {
            // A new persisted URI grant must never outlive a failed metadata write. Existing
            // links keep their grant; releasing it here could revoke access that predated this try.
            if (existing == null && grantedFlags != 0) {
                try {
                    resolver.releasePersistableUriPermission(uri, grantedFlags)
                } catch (cleanupFailure: Throwable) {
                    error.addSuppressed(cleanupFailure)
                }
            }
            throw error
        }
    }

    fun unlink(id: String): List<SharedFolder> {
        val folder = store.list().firstOrNull { it.id == id }
        folder?.let {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                if (it.writable) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
            val uri = Uri.parse(it.handle)
            val persisted = resolver.persistedUriPermissions.any { permission -> permission.uri == uri }
            if (persisted) resolver.releasePersistableUriPermission(uri, flags)
        }
        return store.remove(id)
    }

    override suspend fun list(rootId: String, path: String): List<SharedEntry> = withContext(Dispatchers.IO) {
        val folder = folder(rootId)
        val node = resolve(folder, path)
        require(node.mime == DocumentsContract.Document.MIME_TYPE_DIR) { "$path is not a directory" }
        children(folder, node).map { SharedEntry(it.name, it.mime == DocumentsContract.Document.MIME_TYPE_DIR, it.size) }
            .sortedWith(compareBy({ !it.directory }, { it.name.lowercase() }))
    }

    override suspend fun read(rootId: String, path: String, maxBytes: Int): String = withContext(Dispatchers.IO) {
        val folder = folder(rootId)
        val node = resolve(folder, path)
        require(node.mime != DocumentsContract.Document.MIME_TYPE_DIR) { "$path is a directory" }
        val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        var truncated = false
        resolver.openInputStream(node.uri)?.use { input ->
            val buffer = ByteArray(8192)
            while (out.size() < maxBytes) {
                val remaining = maxBytes - out.size()
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                out.write(buffer, 0, count)
            }
            truncated = input.read() >= 0
        } ?: error("could not open $path")
        out.toString(Charsets.UTF_8.name()) + if (truncated) "\n[Truncated at $maxBytes bytes.]" else ""
    }

    override suspend fun write(rootId: String, path: String, content: String) = withContext(Dispatchers.IO) {
        val folder = writableFolder(rootId)
        val parts = sharedPathSegments(path)
        require(parts.isNotEmpty()) { "path is required" }
        val parent = ensureDirectory(folder, parts.dropLast(1))
        val name = parts.last()
        val existing = child(folder, parent, name)
        require(existing?.mime != DocumentsContract.Document.MIME_TYPE_DIR) { "$path is a directory" }
        val target = existing ?: DocumentsContract.createDocument(
            resolver,
            parent.uri,
            mimeType(name),
            name,
        )?.let { Node(it, name, mimeType(name), null) } ?: error("could not create $path")
        resolver.openOutputStream(target.uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            ?: error("could not write $path")
    }

    override suspend fun mkdir(rootId: String, path: String) = withContext(Dispatchers.IO) {
        val folder = writableFolder(rootId)
        val segments = sharedPathSegments(path)
        require(segments.isNotEmpty()) { "path is required" }
        ensureDirectory(folder, segments)
        Unit
    }

    override suspend fun delete(rootId: String, path: String) = withContext(Dispatchers.IO) {
        val folder = writableFolder(rootId)
        require(sharedPathSegments(path).isNotEmpty()) { "the linked folder root cannot be deleted" }
        val node = resolve(folder, path)
        require(DocumentsContract.deleteDocument(resolver, node.uri)) { "could not delete $path" }
    }

    override suspend fun rename(rootId: String, path: String, newName: String) = withContext(Dispatchers.IO) {
        val folder = writableFolder(rootId)
        require(sharedPathSegments(path).isNotEmpty()) { "the linked folder root cannot be renamed" }
        require(sharedPathSegments(newName).singleOrNull() == newName) { "newName must be a single file name" }
        val node = resolve(folder, path)
        require(DocumentsContract.renameDocument(resolver, node.uri, newName) != null) { "could not rename $path" }
    }

    private fun folder(id: String): SharedFolder =
        store.list().firstOrNull { it.id == id } ?: error("linked folder not found: $id")

    private fun writableFolder(id: String): SharedFolder = folder(id).also {
        require(it.writable) { "${it.name} is read-only" }
    }

    private fun root(folder: SharedFolder): Node {
        val tree = Uri.parse(folder.handle)
        val id = DocumentsContract.getTreeDocumentId(tree)
        return queryNode(tree, DocumentsContract.buildDocumentUriUsingTree(tree, id))
    }

    private fun resolve(folder: SharedFolder, path: String): Node {
        var current = root(folder)
        for (name in sharedPathSegments(path)) {
            require(current.mime == DocumentsContract.Document.MIME_TYPE_DIR) { "$name is not inside a directory" }
            current = child(folder, current, name) ?: error("not found: $path")
        }
        return current
    }

    private fun ensureDirectory(folder: SharedFolder, segments: List<String>): Node {
        var current = root(folder)
        for (name in segments) {
            val existing = child(folder, current, name)
            current = when {
                existing == null -> DocumentsContract.createDocument(
                    resolver,
                    current.uri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    name,
                )?.let { Node(it, name, DocumentsContract.Document.MIME_TYPE_DIR, null) }
                    ?: error("could not create directory $name")
                existing.mime == DocumentsContract.Document.MIME_TYPE_DIR -> existing
                else -> error("$name is not a directory")
            }
        }
        return current
    }

    private fun child(folder: SharedFolder, parent: Node, name: String): Node? =
        children(folder, parent).firstOrNull { it.name == name }

    private fun children(folder: SharedFolder, parent: Node): List<Node> {
        val tree = Uri.parse(folder.handle)
        val parentId = DocumentsContract.getDocumentId(parent.uri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        return resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val result = ArrayList<Node>()
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                result += Node(
                    uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
                    name = cursor.getString(1),
                    mime = cursor.getString(2),
                    size = if (cursor.isNull(3)) null else cursor.getLong(3),
                )
            }
            result
        } ?: error("could not list ${parent.name}")
    }

    private fun queryNode(tree: Uri, uri: Uri): Node {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            require(cursor.moveToFirst()) { "folder is unavailable" }
            Node(
                uri,
                cursor.getString(0),
                cursor.getString(1),
                if (cursor.isNull(2)) null else cursor.getLong(2),
            )
        } ?: error("folder is unavailable: $tree")
    }

    private fun displayName(tree: Uri): String = runCatching { root(SharedFolder("", "", "", tree.toString(), true)).name }
        .getOrDefault(tree.lastPathSegment ?: "Linked folder")

    private fun mimeType(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "text/plain"

    private data class Node(val uri: Uri, val name: String, val mime: String, val size: Long?)
}
