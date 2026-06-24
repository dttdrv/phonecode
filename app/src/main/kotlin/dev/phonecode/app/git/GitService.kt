package dev.phonecode.app.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import java.io.File

data class GitCommit(val hash: String, val author: String, val message: String)

data class GitStatus(
    val branch: String,
    val staged: List<String>,
    val unstaged: List<String>,
    val untracked: List<String>,
) {
    val isClean: Boolean get() = staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty()
    val hasStaged: Boolean get() = staged.isNotEmpty()
    val changeCount: Int get() = staged.size + unstaged.size + untracked.size
}

/** JGit wrapper for the user-facing git UI. All ops run on IO and fail soft (null/empty), never crash. */
class GitService(val workspace: File) {

    fun isRepo(): Boolean = File(workspace, ".git").exists()

    private suspend fun <T> withGit(block: (Git) -> T): T? = withContext(Dispatchers.IO) {
        runCatching { Git.open(workspace).use(block) }.getOrNull()
    }

    suspend fun status(): GitStatus? = withGit { git ->
        val s = git.status().call()
        GitStatus(
            branch = git.repository.branch ?: "(detached)",
            staged = (s.added + s.changed + s.removed).sorted(),
            unstaged = (s.modified + s.missing).sorted(),
            untracked = s.untracked.sorted(),
        )
    }

    suspend fun log(max: Int = 30): List<GitCommit> = withGit { git ->
        runCatching {
            git.log().setMaxCount(max).call().map { GitCommit(it.name.take(8), it.authorIdent.name, it.shortMessage) }
        }.getOrDefault(emptyList())
    } ?: emptyList()

    suspend fun branches(): List<String> = withGit { git ->
        git.branchList().call().map { it.name.removePrefix("refs/heads/") }
    } ?: emptyList()

    suspend fun init() {
        withContext(Dispatchers.IO) { runCatching { Git.init().setDirectory(workspace).call().use {} } }
    }

    suspend fun stageAll() {
        withGit { git ->
            git.add().addFilepattern(".").call()
            git.add().setUpdate(true).addFilepattern(".").call()
        }
    }

    suspend fun commit(message: String): Boolean =
        withGit { git ->
            // No empty snapshots: a clean tree means there is nothing to record (review #7), and
            // the author matches the agent's git tools so the repo has ONE PhoneCode identity.
            if (git.status().call().isClean) return@withGit false
            git.commit().setMessage(message).setAuthor("PhoneCode", "agent@phonecode.dev").call()
            true
        } ?: false

    suspend fun checkout(branch: String) { withGit { it.checkout().setName(branch).call() } }

    suspend fun createBranch(name: String) { withGit { it.branchCreate().setName(name).call() } }
}
