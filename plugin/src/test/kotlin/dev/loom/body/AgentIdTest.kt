package dev.loom.body

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AgentIdTest {
    @Test
    @DisplayName("a blank id is refused")
    fun blankIsRefused() {
        assertThrows<IllegalArgumentException> { AgentId(" ") }
    }

    @Test
    @DisplayName("an ordinary id is accepted and prints as its value")
    fun ordinaryIdIsAccepted() {
        assertEquals("ada", AgentId("ada").toString())
    }

    @Test
    @DisplayName("the reserved world id can never be an agent")
    fun reservedWorldIdIsRefused() {
        // Structural, not conventional: no agent may be created whose event-log
        // records are indistinguishable from the world's own footprint records.
        assertThrows<IllegalArgumentException> { AgentId(WORLD_AGENT_ID) }
    }
}
