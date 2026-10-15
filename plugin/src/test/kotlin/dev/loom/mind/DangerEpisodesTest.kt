package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.contract.Bearing
import dev.loom.contract.DecisionImportance
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DangerEpisodesTest {
    private var tick = 0L
    private val episodes = DangerEpisodes { tick }
    private val alpha = AgentId("agent-alpha")
    private val beta = AgentId("agent-beta")

    private fun sighting(
        requestId: String,
        threat: String = "entity.minecraft.zombie",
    ) = perceptionWith(requestId, Trigger.ThreatSighted(threat, "e1", 6.0, Bearing.NORTH, true, true))

    private fun damage(
        requestId: String,
        source: String = "zombie",
    ) = perceptionWith(requestId, Trigger.Damaged(3, source))

    @Test
    fun `a repeat about the same threat is coalesced while its decision is open`() {
        assertTrue(episodes.admit(alpha, sighting("r1")))
        assertFalse(episodes.admit(alpha, sighting("r2")))
        // Damage from the same kind of creature is the same episode.
        assertFalse(episodes.admit(alpha, damage("r3")))
        // A different threat, and the same threat for another agent, are new information.
        assertTrue(episodes.admit(alpha, sighting("r4", "entity.minecraft.skeleton")))
        assertTrue(episodes.admit(beta, sighting("r5")))
        // Goal reviews are never coalesced.
        assertTrue(episodes.admit(alpha, perceptionWith("r6", Trigger.GoalReview)))
    }

    @Test
    fun `the cooldown after the decision settles is exactly the measured bound`() {
        assertTrue(episodes.admit(alpha, damage("r1")))
        tick = 500
        episodes.settled(alpha, "r1")
        tick = 500 + DANGER_EPISODE_COOLDOWN_TICKS
        assertFalse(episodes.admit(alpha, sighting("r2")), "still cooling down at the bound")
        tick = 501 + DANGER_EPISODE_COOLDOWN_TICKS
        assertTrue(episodes.admit(alpha, sighting("r3")), "a new engagement after the cooldown")
        assertEquals(100L, DANGER_EPISODE_COOLDOWN_TICKS)
    }

    @Test
    fun `an episode stays open until every admitted decision about it settles, and forget drops it`() {
        assertTrue(episodes.admit(alpha, damage("r1")))
        episodes.settled(alpha, "unknown")
        tick = 10_000
        assertFalse(episodes.admit(alpha, damage("r2")), "an unknown settlement closes nothing")
        episodes.forget(alpha)
        assertTrue(episodes.admit(alpha, damage("r3")))
    }

    @Test
    fun `threat and damage keys name the same creature`() {
        val sighted = Trigger.ThreatSighted("entity.minecraft.zombie", "e1", 1.0, Bearing.EAST, true, true)
        assertEquals("zombie", dangerKeyOf(sighted))
        assertEquals("zombie", dangerKeyOf(Trigger.Damaged(2, "zombie")))
        assertEquals("fall", dangerKeyOf(Trigger.Damaged(2, "fall")))
        assertEquals(null, dangerKeyOf(Trigger.GoalReview))
    }
}

private fun perceptionWith(
    requestId: String,
    trigger: Trigger,
): Perception = perception(requestId = requestId, trigger = trigger, importance = DecisionImportance.PIVOTAL)
