package dev.phonecode.app.ui.settings

import dev.phonecode.app.agent.AgentToolInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentToolsUiModelTest {
    private val tools = listOf(
        AgentToolInfo("read_file", "Read a workspace file", "PhoneCode", "Read only"),
        AgentToolInfo("write_file", "Change a workspace file", "PhoneCode", "Approval required"),
        AgentToolInfo("process", "Run a command", "PhoneCode", "Depends on action"),
        AgentToolInfo("remote_search", "Search a connected service", "MCP", "Approval required"),
    )

    @Test
    fun summarySeparatesAccessAndRemoteCounts() {
        assertEquals(
            AgentToolInventorySummary(
                total = 4,
                readOnly = 1,
                needsApproval = 2,
                contextual = 1,
                remote = 1,
            ),
            agentToolInventorySummary(tools),
        )
    }

    @Test
    fun filterMatchesMetadataAndAccessWithoutChangingInventoryOrder() {
        assertEquals(
            listOf("write_file", "remote_search"),
            filterAgentTools(tools, "", AgentToolAccessFilter.NEEDS_APPROVAL).map { it.name },
        )
        assertEquals(
            listOf("remote_search"),
            filterAgentTools(tools, "connected", AgentToolAccessFilter.ALL).map { it.name },
        )
        assertEquals(
            listOf("read_file"),
            filterAgentTools(tools, "read only", AgentToolAccessFilter.READ_ONLY).map { it.name },
        )
    }
}
