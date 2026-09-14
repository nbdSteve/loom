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
}
