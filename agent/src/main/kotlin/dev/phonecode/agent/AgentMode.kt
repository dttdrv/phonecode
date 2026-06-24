package dev.phonecode.agent

/**
 * Agent operating mode. BUILD has the full tool set; PLAN is read-only (mutating
 * tools are withheld) for safe exploration. OpenCode's "baldur" is a user-defined
 * agent, out of MVP scope.
 */
enum class AgentMode { BUILD, PLAN }
