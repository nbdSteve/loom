package dev.loom.body

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class MortalityTest {
    @Test
    @DisplayName("an agent that has not died is not dead")
    fun livingAgentIsNotDead() {
        assertFalse(Mortality().isDead(AgentId("ada")))
    }

    @Test
    @DisplayName("a recorded death is permanent")
    fun deathIsPermanent() {
        val mortality = Mortality()
        mortality.recordDeath(AgentId("ada"))

        // Asked repeatedly, because a body port checks this on every spawn attempt
        // for the rest of the process: permadeath must not decay into a one-shot flag.
        assertTrue(mortality.isDead(AgentId("ada")))
        assertTrue(mortality.isDead(AgentId("ada")))
    }

    @Test
    @DisplayName("one agent's death does not touch another agent")
    fun deathIsPerAgent() {
        val mortality = Mortality()
        mortality.recordDeath(AgentId("ada"))

        assertFalse(mortality.isDead(AgentId("grace")))
    }

    @Test
    @DisplayName("recording a death reports whether it was the first time")
    fun firstDeathIsDistinguishable() {
        val mortality = Mortality()

        // The terminal death signal is owed to the mind exactly once, so the caller
        // needs to tell a first death from a repeat.
        assertTrue(mortality.recordDeath(AgentId("ada")))
        assertFalse(mortality.recordDeath(AgentId("ada")))
    }
}
