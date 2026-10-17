/*
 * The cadence and the stagger, pinned with a fake clock and no server.
 *
 * These are the two properties that decide what autonomy costs. A cadence that
 * quietly drifted short, or a stagger that let a population arrive as one burst,
 * would both look perfectly fine on a small demo server and only show up as
 * a bill - or a stalled tick - at twenty agents. So they are asserted here, where
 * time is a variable a test can move.
 */
package dev.loom.mind

import dev.loom.body.AgentId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** A cadence and a slice count whose arithmetic is easy to state in a test. */
private const val CADENCE = 120L
private const val SLICES = 6

class GoalReviewScheduleTest {
    private val schedule = GoalReviewSchedule(CADENCE, SLICES)

    /** A fake clock, moved by the test rather than by the passage of real time. */
    private var now = 0L

    private fun agents(count: Int): Set<AgentId> = (1..count).map { AgentId("agent-$it") }.toSet()

    /** Claims for every slice of one cadence, as the driver's timer would. */
    private fun sweepACadence(living: Set<AgentId>): List<List<AgentId>> =
        (1..SLICES).map {
            now += schedule.sliceTicks
            schedule.claim(living, now)
        }

    @Test
    @DisplayName("an agent that has never reviewed is due at once")
    fun aNewAgentThinksImmediately() {
        // What keeps a demo watchable: a body that has just been spawned acts on the
        // next sweep rather than a cadence later.
        assertEquals(listOf(AgentId("agent-1")), schedule.claim(agents(1), now))
    }

    @Test
    @DisplayName("an agent is not reviewed again until the cadence has elapsed")
    fun theCadenceHolds() {
        val living = agents(1)
        schedule.claim(living, now)

        // Every slice of the cadence but the last: due exactly once, not once a sweep.
        repeat(SLICES - 1) {
            now += schedule.sliceTicks
            assertEquals(emptyList<AgentId>(), schedule.claim(living, now))
        }

        now += schedule.sliceTicks
        assertEquals(living.toList(), schedule.claim(living, now))
    }

    @Test
    @DisplayName("a population is spread across the cadence rather than reviewed in one sweep")
    fun agentsAreStaggered() {
        val living = agents(SLICES)

        val sweeps = sweepACadence(living)

        assertTrue(sweeps.all { it.size <= 1 }, "one agent per slice at this population, got ${sweeps.map { it.size }}")
        // Spread, but nobody dropped: every agent still gets its review inside one
        // cadence, which is what makes the cadence a bound rather than an aspiration.
        assertEquals(living, sweeps.flatten().toSet())
    }

    @Test
    @DisplayName("a population larger than the slice count still fits inside one cadence")
    fun aBigPopulationStillFitsTheCadence() {
        val living = agents(SLICES * 2 + 1)

        val sweeps = sweepACadence(living)

        // Two per slice would cover exactly twice the slice count; the odd agent is
        // what forces the share to round up rather than starve someone.
        assertTrue(sweeps.all { it.size <= 3 }, "the per-slice share must stay bounded, got ${sweeps.map { it.size }}")
        assertEquals(living, sweeps.flatten().toSet())
    }

    @Test
    @DisplayName("reviews rotate through the population instead of favouring one agent")
    fun reviewsRotate() {
        val living = agents(SLICES)

        val first = sweepACadence(living).flatten()
        val second = sweepACadence(living).flatten()

        assertEquals(living, first.toSet())
        // Every agent again, in the same order: whoever went first in one cadence is
        // also the one who has waited longest when the next comes round.
        assertEquals(first, second)
    }

    @Test
    @DisplayName("an agent squeezed out of a full slice is served ahead of one that merely came due")
    fun nobodyIsStarvedByAFullSlice() {
        // Two slices, so five agents share three slots a slice and someone is left
        // over - the only case where the order actually decides who waits.
        val schedule = GoalReviewSchedule(CADENCE, slicesPerCadence = 2)
        val early = AgentId("agent-1")
        schedule.claim(setOf(early), 60L)

        val living = agents(5)
        val served = schedule.claim(living, 120L)
        val squeezedOut = living - served.toSet() - setOf(early)

        assertEquals(3, served.size, "the slice's share, not the whole due set")
        // A cadence after its own review, `early` is due - but the agent that never
        // got a slot has been waiting since it was embodied, so it goes first.
        assertEquals(squeezedOut.toList() + early, schedule.claim(living, 180L))
    }

    @Test
    @DisplayName("an agent that leaves the world is forgotten rather than tracked forever")
    fun departedAgentsArePruned() {
        val living = agents(3)
        sweepACadence(living)
        assertEquals(3, schedule.tracked)

        now += schedule.sliceTicks
        schedule.claim(emptySet(), now)

        assertEquals(0, schedule.tracked, "a server runs for a long time; departed agents must not accumulate")
    }

    @Test
    @DisplayName("the sweep period is the cadence divided into slices")
    fun theSliceIsDerivedFromTheCadence() {
        assertEquals(20L, schedule.sliceTicks)
    }

    @Test
    @DisplayName("a cadence or slice count that would break the stagger is refused")
    fun nonsenseIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { GoalReviewSchedule(0L, SLICES) }
        assertThrows(IllegalArgumentException::class.java) { GoalReviewSchedule(CADENCE, 0) }
        assertThrows(IllegalArgumentException::class.java) { GoalReviewSchedule(5L, SLICES) }
    }

    @Test
    @DisplayName("the shipped default keeps a twenty-agent population inside its budget")
    fun theDefaultCadenceIsConservative() {
        // Pins the posture, not the number: whatever the default becomes, twenty
        // agents must not cost more than a call every few seconds.
        val perAgent = DEFAULT_GOAL_REVIEW_CADENCE_TICKS / 20
        assertTrue(perAgent >= 100L, "a 20-agent population would think every $perAgent ticks")
    }

    @Test
    @DisplayName("a settled goal step owes one follow-up review soon after, once per scheduled review")
    fun aSettledStepIsFollowedUpOncePerScheduledReview() {
        val agent = AgentId("agent-1")
        val living = setOf(agent)
        // Never reviewed: nothing to follow up.
        schedule.settled(agent, now)
        assertEquals(emptyList<AgentId>(), schedule.claimFollowUps(living, now + FOLLOW_UP_DELAY_TICKS))
        assertEquals(listOf(agent), schedule.claim(living, now))
        schedule.settled(agent, now + 5)
        // Not before the drop of a dig can land and be picked up.
        assertEquals(emptyList<AgentId>(), schedule.claimFollowUps(living, now + 5 + FOLLOW_UP_DELAY_TICKS - 1))
        assertEquals(listOf(agent), schedule.claimFollowUps(living, now + 5 + FOLLOW_UP_DELAY_TICKS))
        // The follow-up's own step settling owes nothing more until the next scheduled review.
        schedule.settled(agent, now + 60)
        assertEquals(emptyList<AgentId>(), schedule.claimFollowUps(living, now + CADENCE - 1))
        // And the follow-up never moved the cadence.
        assertEquals(emptyList<AgentId>(), schedule.claim(living, now + CADENCE - 1))
        assertEquals(listOf(agent), schedule.claim(living, now + CADENCE))
        schedule.settled(agent, now + CADENCE)
        assertEquals(listOf(agent), schedule.claimFollowUps(living, now + CADENCE + FOLLOW_UP_DELAY_TICKS))
    }

    @Test
    @DisplayName("an owed follow-up waits for a mind that is busy, and is dropped with its agent")
    fun anOwedFollowUpWaitsAndIsForgotten() {
        val agent = AgentId("agent-1")
        schedule.claim(setOf(agent), 0)
        schedule.settled(agent, 0)
        assertEquals(emptyList<AgentId>(), schedule.claimFollowUps(setOf(agent), 100) { false })
        assertEquals(listOf(agent), schedule.claimFollowUps(setOf(agent), 101))
        val other = AgentId("agent-2")
        schedule.claim(setOf(agent, other), CADENCE)
        schedule.settled(other, CADENCE)
        assertEquals(emptyList<AgentId>(), schedule.claimFollowUps(setOf(agent), CADENCE + 100))
        assertEquals(emptyList<AgentId>(), schedule.claimFollowUps(setOf(agent, other), CADENCE + 100))
    }
}
