package dev.phonecode.app.agent

import dev.phonecode.agent.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModeAuthorityTest {

    @Test
    fun buildSupersededDuringWriteIsRepairedToTheNewerPlanAuthority() {
        var durableMode = AgentMode.PLAN
        var authoritativeMode = AgentMode.BUILD

        val result = persistAgentModeWithLatestAuthority(
            requestedMode = AgentMode.BUILD,
            previousMode = AgentMode.PLAN,
            persist = { mode ->
                durableMode = mode
                if (mode == AgentMode.BUILD) authoritativeMode = AgentMode.PLAN
                true
            },
            authoritativeMode = { authoritativeMode },
        )

        assertFalse(result.current)
        assertTrue(result.durable)
        assertEquals(AgentMode.PLAN, durableMode)
    }

    @Test
    fun failedNewerPlanRepairRollsBackToPlanBeforeReturning() {
        var durableMode = AgentMode.PLAN
        var authoritativeMode = AgentMode.BUILD
        var planWriteAttempts = 0

        val result = persistAgentModeWithLatestAuthority(
            requestedMode = AgentMode.BUILD,
            previousMode = AgentMode.PLAN,
            persist = { mode ->
                when (mode) {
                    AgentMode.BUILD -> {
                        durableMode = mode
                        authoritativeMode = AgentMode.PLAN
                        true
                    }
                    AgentMode.PLAN -> {
                        planWriteAttempts++
                        if (planWriteAttempts == 1) {
                            false
                        } else {
                            durableMode = mode
                            true
                        }
                    }
                }
            },
            authoritativeMode = { authoritativeMode },
        )

        assertFalse(result.current)
        assertTrue(result.durable)
        assertEquals(2, planWriteAttempts)
        assertEquals(
            "A process restart after the superseded request completes must restore Plan",
            AgentMode.PLAN,
            durableMode,
        )
    }
}
