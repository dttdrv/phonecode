package dev.phonecode.tools.git

import dev.phonecode.tools.Tool
import dev.phonecode.tools.ToolContext
import dev.phonecode.tools.ToolResult
import dev.phonecode.tools.files.objectSchema
import dev.phonecode.tools.files.str
import dev.phonecode.tools.files.strSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.Status
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.FileTreeIterator
import java.io.ByteArrayOutputStream
import java.io.File

/** Native git via JGit, confined to the workspace. push/pull use [credentials] = (username, token) over HTTPS. */
fun gitTools(credentials: suspend () -> Pair<String, String>?): List<Tool> = listOf(
    GitInitTool(), GitStatusTool(), GitDiffTool(), GitAddTool(), GitCommitTool(),
    GitLogTool(), GitBranchTool(), GitCheckoutTool(), GitPushTool(credentials), GitPullTool(credentials),
)

private val NO_PARAMS = objectSchema(emptyMap(), emptyList())

/** Open the workspace repo and run [block]; a friendly error if it isn't a repo. */
private suspend fun <T> withRepo(context: ToolContext, name: String, block: (Git) -> T): ToolResult where T : Any =
    withContext(Dispatchers.IO) {
        runCatching { Git.open(File(context.workspacePath)).use { ToolResult(block(it).toString()) } }
            .getOrElse { ToolResult("$name: ${it.message ?: "not a git repository (try git_init)"}", isError = true) }
    }

private fun Status.render(): String {
    val sb = StringBuilder()
    val staged = added.map { "A  $it" } + changed.map { "M  $it" } + removed.map { "D  $it" }
    val unstaged = modified.map { " M $it" } + missing.map { " D $it" }
    if (staged.isNotEmpty()) sb.append("Staged:\n").append(staged.joinToString("\n") { "  $it" }).append("\n")
    if (unstaged.isNotEmpty()) sb.append("Unstaged:\n").append(unstaged.joinToString("\n") { "  $it" }).append("\n")
    if (untracked.isNotEmpty()) sb.append("Untracked:\n").append(untracked.joinToString("\n") { "  ?? $it" }).append("\n")
    return sb.toString().trimEnd().ifEmpty { "working tree clean" }
}

class GitInitTool : Tool {
    override val name = "git_init"
    override val description = "Initialize a new git repository in the workspace."
    override val mutating = true
    override val promptSnippet = "initialize a git repository in the workspace"
    override val parameters = NO_PARAMS
    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        runCatching { Git.init().setDirectory(File(context.workspacePath)).call().use {} ; ToolResult("git: initialized repository") }
            .getOrElse { ToolResult("git_init: ${it.message}", isError = true) }
    }
}

class GitStatusTool : Tool {
    override val name = "git_status"
    override val description = "Show the working-tree status (staged, unstaged, and untracked files)."
    override val promptSnippet = "show git status"
    override val parameters = NO_PARAMS
    override suspend fun execute(args: JsonObject, context: ToolContext) = withRepo(context, name) { it.status().call().render() }
}

class GitDiffTool : Tool {
    override val name = "git_diff"
    override val description = "Show the diff. Defaults to unstaged (working tree vs index); pass staged=true for staged changes."
    override val promptSnippet = "show the git diff (unstaged, or staged)"
    override val parameters = objectSchema(mapOf("staged" to strSchema("true to show staged changes (default false)")), emptyList())
    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val staged = args.str("staged")?.equals("true", ignoreCase = true) == true
        return withRepo(context, name) { git ->
            val repo = git.repository
            val out = ByteArrayOutputStream()
            DiffFormatter(out).use { df ->
                df.setRepository(repo)
                if (staged) {
                    val head = repo.resolve("HEAD^{tree}")
                    val oldTree = if (head != null) {
                        org.eclipse.jgit.treewalk.CanonicalTreeParser().apply { reset(repo.newObjectReader(), head) }
                    } else {
                        org.eclipse.jgit.treewalk.EmptyTreeIterator()
                    }
                    df.format(oldTree, DirCacheIterator(repo.readDirCache()))
                } else {
                    df.format(DirCacheIterator(repo.readDirCache()), FileTreeIterator(repo))
                }
            }
            out.toString(Charsets.UTF_8).take(20_000).ifEmpty { "(no changes)" }
        }
    }
}

class GitAddTool : Tool {
    override val name = "git_add"
    override val description = "Stage files for commit. Pass a path glob, or omit to stage everything ('.')."
    override val mutating = true
    override val promptSnippet = "stage files for commit (git add)"
    override val parameters = objectSchema(mapOf("path" to strSchema("File path or glob to stage (default: all)")), emptyList())
    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val pattern = args.str("path")?.takeIf { it.isNotBlank() } ?: "."
        return withRepo(context, name) { git ->
            git.add().addFilepattern(pattern).call()
            git.add().setUpdate(true).addFilepattern(pattern).call() // also stage deletions
            "staged: $pattern"
        }
    }
}

class GitCommitTool : Tool {
    override val name = "git_commit"
    override val description = "Commit the staged changes with a message."
    override val mutating = true
    override val promptSnippet = "commit staged changes with a message"
    override val parameters = objectSchema(
        mapOf(
            "message" to strSchema("The commit message"),
            "author" to strSchema("Author name (optional)"),
            "email" to strSchema("Author email (optional)"),
        ),
        required = listOf("message"),
    )
    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val message = args.str("message") ?: return ToolResult("git_commit: missing 'message'", isError = true)
        val author = args.str("author")?.takeIf { it.isNotBlank() } ?: "PhoneCode"
        val email = args.str("email")?.takeIf { it.isNotBlank() } ?: "agent@phonecode.dev"
        return withRepo(context, name) { git ->
            val commit = git.commit().setMessage(message).setAuthor(author, email).call()
            "committed ${commit.name.take(8)}: ${commit.shortMessage}"
        }
    }
}

class GitLogTool : Tool {
    override val name = "git_log"
    override val description = "Show recent commits (hash, author, message)."
    override val promptSnippet = "show recent git commits"
    override val parameters = objectSchema(mapOf("count" to strSchema("How many commits (default 15)")), emptyList())
    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val count = args.str("count")?.toIntOrNull()?.coerceIn(1, 100) ?: 15
        return withRepo(context, name) { git ->
            git.log().setMaxCount(count).call().joinToString("\n") { c ->
                "${c.name.take(8)}  ${c.authorIdent.name}  ${c.shortMessage}"
            }.ifEmpty { "(no commits yet)" }
        }
    }
}

class GitBranchTool : Tool {
    override val name = "git_branch"
    override val description = "List branches, or create a new branch with name=<branch>."
    override val mutating = true
    override val promptSnippet = "list or create git branches"
    override val parameters = objectSchema(mapOf("name" to strSchema("New branch name to create (omit to just list)")), emptyList())
    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val create = args.str("name")?.takeIf { it.isNotBlank() }
        return withRepo(context, name) { git ->
            if (create != null) {
                git.branchCreate().setName(create).call()
                "created branch $create"
            } else {
                val current = git.repository.branch
                git.branchList().call().joinToString("\n") { ref ->
                    val short = ref.name.removePrefix("refs/heads/")
                    if (short == current) "* $short" else "  $short"
                }
            }
        }
    }
}

class GitCheckoutTool : Tool {
    override val name = "git_checkout"
    override val description = "Switch to an existing branch (or create+switch with create=true)."
    override val mutating = true
    override val promptSnippet = "switch git branches (checkout)"
    override val parameters = objectSchema(
        mapOf("name" to strSchema("Branch to switch to"), "create" to strSchema("true to create the branch first")),
        required = listOf("name"),
    )
    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val branch = args.str("name") ?: return ToolResult("git_checkout: missing 'name'", isError = true)
        val create = args.str("create")?.equals("true", ignoreCase = true) == true
        return withRepo(context, name) { git ->
            git.checkout().setName(branch).setCreateBranch(create).call()
            "switched to $branch"
        }
    }
}

class GitPushTool(private val credentials: suspend () -> Pair<String, String>?) : Tool {
    override val name = "git_push"
    override val description = "Push the current branch to the remote (origin) over HTTPS, using the configured git credentials."
    override val mutating = true
    override val promptSnippet = "push commits to the remote"
    override val parameters = NO_PARAMS
    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val creds = credentials() ?: return ToolResult("git_push: no git credentials set (add a username + token in Settings)", isError = true)
        return withRepo(context, name) { git ->
            git.push().setCredentialsProvider(UsernamePasswordCredentialsProvider(creds.first, creds.second)).call()
            "pushed to origin"
        }
    }
}

class GitPullTool(private val credentials: suspend () -> Pair<String, String>?) : Tool {
    override val name = "git_pull"
    override val description = "Pull from the remote (origin) over HTTPS, using the configured git credentials."
    override val mutating = true
    override val promptSnippet = "pull from the remote"
    override val parameters = NO_PARAMS
    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val creds = credentials()
        return withRepo(context, name) { git ->
            val pull = git.pull()
            if (creds != null) pull.setCredentialsProvider(UsernamePasswordCredentialsProvider(creds.first, creds.second))
            val result = pull.call()
            if (result.isSuccessful) "pulled from origin" else "pull completed with conflicts"
        }
    }
}
