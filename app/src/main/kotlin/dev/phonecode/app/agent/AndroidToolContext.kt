package dev.phonecode.app.agent

import dev.phonecode.tools.ToolContext
import dev.phonecode.tools.UserAnswer
import dev.phonecode.tools.UserQuestion

/**
 * The agent's execution context on Android: a workspace directory plus two suspend channels the
 * ViewModel surfaces as UI - a permission gate ([requestPermission]) and a question prompt ([askUser]).
 * Both block until the user responds (or the turn is cancelled, which resolves them as denied / unanswered).
 *
 * The workspace is a provider, not a constant: workspaces are per-project, so the path follows the
 * active chat's project and every tool call reads the current one.
 */
class AndroidToolContext(
    private val workspaceProvider: () -> String,
    private val asker: suspend (tool: String, summary: String) -> Boolean,
    private val questioner: suspend (questions: List<UserQuestion>) -> List<UserAnswer>,
) : ToolContext {
    override val workspacePath: String get() = workspaceProvider()

    override suspend fun requestPermission(tool: String, summary: String): Boolean = asker(tool, summary)

    override suspend fun askUser(questions: List<UserQuestion>): List<UserAnswer> = questioner(questions)
}
