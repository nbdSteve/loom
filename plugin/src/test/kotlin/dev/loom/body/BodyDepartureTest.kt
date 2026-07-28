/*
 * The rule that decides whether a body leaving the world costs the agent its life.
 *
 * Loom is permadeath, so getting this wrong in either direction is severe: call a
 * journey a death and an agent is annihilated by walking through a portal; call a
 * death a journey and permadeath quietly stops being true. Both directions are
 * pinned here.
 */
package dev.loom.body

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BodyDepartureTest {
    @Test
    fun `death is the only departure that ends the agent`() {
        val terminal = BodyDeparture.entries.filter { it.endsTheAgent }

        assertEquals(listOf(BodyDeparture.DEATH), terminal, "only death may end an agent")
    }

    @Test
    fun `travelling through a portal is continuity, not death`() {
        assertFalse(
            BodyDeparture.DIMENSION_TRAVEL.endsTheAgent,
            "an agent that walks into a portal arrives on the far side alive",
        )
    }

    @Test
    fun `being converted into another creature is continuity, not death`() {
        assertFalse(
            BodyDeparture.CONVERSION.endsTheAgent,
            "being struck by lightning happens to an agent; it does not end one",
        )
    }

    @Test
    fun `stowing a body leaves the agent embodiable`() {
        assertFalse(BodyDeparture.STOWED.endsTheAgent, "Loom putting a body away is not killing an agent")
    }

    @Test
    fun `a death recorded from a terminal departure is never cleared`() {
        val mortality = Mortality()
        val agent = AgentId("agent-ada")

        assertTrue(mortality.recordDeath(agent), "the first death is the one worth signalling")
        assertFalse(mortality.recordDeath(agent), "a death is recorded once")
        assertTrue(mortality.isDead(agent), "death is terminal for the agent identity")
    }
}
