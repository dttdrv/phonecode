package dev.phonecode.agent

/**
 * Device/workspace facts injected into the system prompt so the model adapts to
 * running on an Android phone. Supplied by :app with real values. Keep these
 * session-stable (not per-turn) so the cached system prefix stays byte-stable.
 */
data class AgentEnvironment(
    val platform: String = "Android",
    val deviceModel: String = "unknown",
    val osVersion: String = "unknown",
    val workspacePath: String = "/",
    val shellAvailable: Boolean = false,
    /** One line describing the shell toolkit + key paths (HOME/TMPDIR/PREFIX), shown verbatim. */
    val shellDetail: String = "",
    /** Where MCP-server and skill config live, so the agent can self-manage them. */
    val configPath: String = "",
)
