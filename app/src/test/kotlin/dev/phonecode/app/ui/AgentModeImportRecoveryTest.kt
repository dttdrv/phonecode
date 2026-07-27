package dev.phonecode.app.ui

import dev.phonecode.agent.AgentMode
import dev.phonecode.app.agent.restoredAgentMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentModeImportRecoveryTest {

    @Test
    fun importedPlanSessionOverridesBuildDefault() {
        assertEquals(
            AgentMode.PLAN,
            restoredAgentMode(
                persistedMode = AgentMode.PLAN.name,
                interrupted = false,
                fallback = AgentMode.BUILD,
            ),
        )
    }

    @Test
    fun interruptedImportedBuildSessionFailsClosedToPlan() {
        assertEquals(
            AgentMode.PLAN,
            restoredAgentMode(
                persistedMode = AgentMode.BUILD.name,
                interrupted = true,
                fallback = AgentMode.BUILD,
            ),
        )
    }

    @Test
    fun invalidLegacyModeUsesSafeProvidedFallback() {
        assertEquals(
            AgentMode.PLAN,
            restoredAgentMode(
                persistedMode = "UNKNOWN",
                interrupted = false,
                fallback = AgentMode.PLAN,
            ),
        )
    }
}
