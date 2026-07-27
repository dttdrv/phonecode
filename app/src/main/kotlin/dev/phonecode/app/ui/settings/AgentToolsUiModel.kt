package dev.phonecode.app.ui.settings

import dev.phonecode.app.agent.AgentToolInfo

internal enum class AgentToolAccessFilter {
    ALL,
    READ_ONLY,
    NEEDS_APPROVAL,
    CONTEXTUAL,
}

internal data class AgentToolInventorySummary(
    val total: Int,
    val readOnly: Int,
    val needsApproval: Int,
    val contextual: Int,
    val remote: Int,
)

internal fun agentToolInventorySummary(tools: List<AgentToolInfo>): AgentToolInventorySummary =
    AgentToolInventorySummary(
        total = tools.size,
        readOnly = tools.count { it.access == "Read only" },
        needsApproval = tools.count { it.access.startsWith("Approval") },
        contextual = tools.count { it.access == "Depends on action" },
        remote = tools.count { it.source == "MCP" },
    )

internal fun filterAgentTools(
    tools: List<AgentToolInfo>,
    query: String,
    access: AgentToolAccessFilter,
): List<AgentToolInfo> {
    val normalizedQuery = query.trim()
    return tools.filter { tool ->
        val matchesQuery = normalizedQuery.isEmpty() ||
            tool.name.contains(normalizedQuery, ignoreCase = true) ||
            tool.description.contains(normalizedQuery, ignoreCase = true) ||
            tool.source.contains(normalizedQuery, ignoreCase = true) ||
            tool.access.contains(normalizedQuery, ignoreCase = true)
        val matchesAccess = when (access) {
            AgentToolAccessFilter.ALL -> true
            AgentToolAccessFilter.READ_ONLY -> tool.access == "Read only"
            AgentToolAccessFilter.NEEDS_APPROVAL -> tool.access.startsWith("Approval")
            AgentToolAccessFilter.CONTEXTUAL -> tool.access == "Depends on action"
        }
        matchesQuery && matchesAccess
    }
}
