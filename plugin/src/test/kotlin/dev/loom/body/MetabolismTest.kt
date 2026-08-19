/*
 * The survival drive, driven by hand: decay, restoration, and the boundary that is a
 * permadeath.
 *
 * All of it runs with no server and no clock, because the reserve is spent from ticks
 * that are handed in. That is the property the whole drive rests on - a run replays
 * identically, and a test can stand an agent one tick from the end of its life and
 * look at it.
 */
package dev.loom.body

import dev.loom.contract.ENERGY_EXHAUSTED
import dev.loom.contract.ENERGY_FULL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A short life, so a test can spend a whole one a few ticks at a time. */
private const val LIFESPAN = 100L

private val ADA = AgentId("ada")
private val GRACE = AgentId("grace")

class MetabolismTest {
    private val metabolism = Metabolism(lifespan = LIFESPAN)

    /**
     * The tick this test has run to, so successive [spend] calls read as one run of
     * server ticks rather than as a clock that starts over each time.
     */
    private var tick = 0L

    /** Charges the next [ticks] ticks against [agents], as a run of server ticks would. */
    private fun spend(
        ticks: Long,
        agents: Set<AgentId>,
    ): Set<AgentId> {
        var ranOut = emptySet<AgentId>()
        repeat(ticks.toInt()) {
            tick++
            ranOut = ranOut + metabolism.spendUpTo(tick, agents)
        }
        return ranOut
    }

    /** Charges [ticks] ticks against [agent] alone. */
    private fun spend(
        ticks: Long,
        agent: AgentId,
    ): Set<AgentId> = spend(ticks, setOf(agent))

    @Test
    @DisplayName("an agent nobody has charged yet has a full reserve")
    fun anUnknownAgentIsFull() {
        // No begin() to forget: a body port spawns a body and says nothing to this.
        assertEquals(LIFESPAN, metabolism.reserveOf(ADA))
        assertEquals(ENERGY_FULL, metabolism.energyOf(ADA))
        assertFalse(metabolism.isExhausted(ADA))
    }

    @Test
    @DisplayName("the reserve decays a tick at a time, and the fraction follows it")
    fun theReserveDecays() {
        spend(25, ADA)

        assertEquals(75L, metabolism.reserveOf(ADA))
        assertEquals(0.75, metabolism.energyOf(ADA))
    }

    @Test
    @DisplayName("a reserve is spent by living, not by the server having been up")
    fun theFirstChargeCostsOneTick() {
        // A body spawned into a server that has been running for a week starts with a
        // full life ahead of it, not a spent one.
        metabolism.spendUpTo(1_000_000L, setOf(ADA))

        assertEquals(LIFESPAN - 1, metabolism.reserveOf(ADA))
    }

    @Test
    @DisplayName("a tick that does not advance costs nothing")
    fun aRepeatedTickIsFree() {
        metabolism.spendUpTo(10L, setOf(ADA))
        val afterFirst = metabolism.reserveOf(ADA)

        // Two sweeps within one tick, and a clock that went backwards: neither may
        // shorten a life, or the reserve would stop being a function of elapsed ticks.
        metabolism.spendUpTo(10L, setOf(ADA))
        metabolism.spendUpTo(9L, setOf(ADA))
        // And a tick already paid for stays paid for: a clock that went backwards must not
        // make the ticks after it chargeable a second time.
        metabolism.spendUpTo(10L, setOf(ADA))

        assertEquals(afterFirst, metabolism.reserveOf(ADA))
    }

    @Test
    @DisplayName("only the agents handed in are charged")
    fun anAbsentAgentIsNotCharged() {
        spend(30, ADA)

        // Grace's body is put away, so her reserve is frozen rather than spent. A
        // despawn is not a slow death: the agent is still embodiable, with the reserve
        // it had.
        assertEquals(LIFESPAN, metabolism.reserveOf(GRACE))
    }

    @Test
    @DisplayName("resting refills the reserve completely")
    fun restingRefills() {
        spend(60, ADA)

        assertTrue(metabolism.restore(ADA))

        assertEquals(LIFESPAN, metabolism.reserveOf(ADA))
        assertEquals(ENERGY_FULL, metabolism.energyOf(ADA))
    }

    @Test
    @DisplayName("resting one agent leaves every other agent's reserve alone")
    fun restingIsPerAgent() {
        spend(40, setOf(ADA, GRACE))

        metabolism.restore(ADA)

        assertEquals(LIFESPAN - 40, metabolism.reserveOf(GRACE))
    }

    @Test
    @DisplayName("an agent is exhausted on the tick its reserve runs out, and not before")
    fun theBoundaryIsExact() {
        // One tick short of the end: still alive, and this is the tick a body has to
        // survive for the boundary to be the boundary rather than an off-by-one.
        assertTrue(spend(LIFESPAN - 1, ADA).isEmpty(), "an agent with reserve left has not run out")
        assertFalse(metabolism.isExhausted(ADA))
        assertEquals(1L, metabolism.reserveOf(ADA))

        val ranOut = metabolism.spendUpTo(LIFESPAN, setOf(ADA))

        assertEquals(setOf(ADA), ranOut)
        assertTrue(metabolism.isExhausted(ADA))
        assertEquals(ENERGY_EXHAUSTED, metabolism.energyOf(ADA))
    }

    @Test
    @DisplayName("a reserve overshot by a long gap between charges stops at exhaustion")
    fun theReserveNeverGoesNegative() {
        spend(1, ADA)

        // Ticks the server skipped, or a sweep that stopped and started again much later:
        // the whole gap is charged at once, and the reserve is spent, not owed.
        tick = LIFESPAN * 3
        val ranOut = metabolism.spendUpTo(tick, setOf(ADA))

        assertEquals(setOf(ADA), ranOut)
        assertEquals(0L, metabolism.reserveOf(ADA))
        assertEquals(ENERGY_EXHAUSTED, metabolism.energyOf(ADA))
    }

    @Test
    @DisplayName("running out is reported exactly once, however long the caller keeps asking")
    fun exhaustionIsReportedOnce() {
        spend(LIFESPAN, ADA)

        // A caller that kept charging an ended agent - through a bug, or through a
        // population set it built a tick too early - must not be told to end it twice:
        // the death it would record is terminal.
        for (tick in LIFESPAN + 1..LIFESPAN + 10) {
            assertTrue(
                metabolism.spendUpTo(tick, setOf(ADA)).isEmpty(),
                "an agent that has already run out must not be reported again at tick $tick",
            )
        }
    }

    @Test
    @DisplayName("a spent reserve cannot be rested back")
    fun exhaustionIsTerminal() {
        spend(LIFESPAN, ADA)

        // Exhaustion is a permadeath. An agent that could rest its way out of one would
        // not be permadead, so refusing is the type's job and not the caller's.
        assertFalse(metabolism.restore(ADA))

        assertEquals(0L, metabolism.reserveOf(ADA))
        assertTrue(metabolism.isExhausted(ADA))
    }

    @Test
    @DisplayName("one agent running out does not touch another")
    fun exhaustionIsPerAgent() {
        spend(LIFESPAN / 2, GRACE)
        metabolism.restore(GRACE)
        spend(LIFESPAN, ADA)

        assertTrue(metabolism.isExhausted(ADA))
        assertFalse(metabolism.isExhausted(GRACE))
    }

    @Test
    @DisplayName("a lifespan that is not a positive number of ticks is refused")
    fun aLifespanMustBePositive() {
        // Zero would exhaust every agent on its first tick, which is a configuration
        // mistake and must not look like a rule.
        assertThrows<IllegalArgumentException> { Metabolism(lifespan = 0L) }
        assertThrows<IllegalArgumentException> { Metabolism(lifespan = -1L) }
    }

    @Test
    @DisplayName("the shipped default is a development-safe length of life")
    fun theDefaultLifespanIsSafe() {
        // Pinned as a relationship rather than as a number: whatever the default is, an
        // agent must not be able to starve inside a demo someone is watching.
        val tenMinutesOfTicks = 10 * 60 * 20L
        assertTrue(
            DEFAULT_ENERGY_LIFESPAN_TICKS >= tenMinutesOfTicks,
            "the default reserve must outlast ten minutes of unattended running",
        )
    }
}
