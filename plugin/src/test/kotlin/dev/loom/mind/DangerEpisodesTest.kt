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
        // A different threat as near, and the same threat for another agent, are new information.
        assertTrue(episodes.admit(alpha, sighting("r4", "entity.minecraft.skeleton")))
        assertTrue(episodes.admit(beta, sighting("r5")))
        // Goal reviews are never coalesced.
        assertTrue(episodes.admit(alpha, perceptionWith("r6", Trigger.GoalReview)))
    }

    @Test
    fun `a further threat waits while a nearer one is being answered`() {
        // r20 eta body:00001074 fled a zombie 6.8 off; body:00001100, a flight from a skeleton
        // 23.1 off, superseded it.
        val near =
            perceptionWith("r1", Trigger.ThreatSighted("entity.minecraft.zombie", "e1", 6.8, Bearing.NORTH, true))
        val far =
            perceptionWith("r2", Trigger.ThreatSighted("entity.minecraft.skeleton", "e2", 15.0, Bearing.EAST, true))
        assertTrue(episodes.admit(alpha, near))
        assertFalse(episodes.admit(alpha, far))
        // Damage is nearest of all, and is sent.
        assertTrue(episodes.admit(alpha, damage("r3", "spider")))
        episodes.settled(alpha, "r1")
        episodes.settled(alpha, "r3")
        assertTrue(episodes.admit(alpha, far.copy(requestId = "r4")))
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
    fun `an answer that is no flight ends the episode at once, so the next sighting is asked about`() {
        // r20 kappa body:00000777 answered a skeleton 2.4 off with a one-block walk; its next
        // sighting (body:00000806) was coalesced into the cooldown, and it died beside it.
        assertTrue(episodes.admit(alpha, sighting("r1", "entity.minecraft.skeleton")))
        tick = 10
        episodes.settled(alpha, "r1", fled = false)
        tick = 11
        assertTrue(episodes.admit(alpha, sighting("r2", "entity.minecraft.skeleton")))
        // A flight still cools down.
        episodes.settled(alpha, "r2", fled = true)
        tick = 12
        assertFalse(episodes.admit(alpha, sighting("r3", "entity.minecraft.skeleton")))
    }

    @Test
    fun `damage after a flight settled is asked about, and reopens the episode`() {
        // v29 rehearsal eta fled a spider safely, was bitten 58 ticks later inside the cooldown,
        // and died with the bite never asked about.
        assertTrue(episodes.admit(alpha, damage("r1", "spider")))
        tick = 6412
        episodes.settled(alpha, "r1", fled = true)
        tick = 6470
        assertFalse(episodes.admit(alpha, sighting("r2", "entity.minecraft.spider")), "a sighting still cools down")
        assertTrue(episodes.admit(alpha, damage("r3", "spider")))
        // The reopened episode coalesces the next bite while its decision is open.
        assertFalse(episodes.admit(alpha, damage("r4", "spider")))
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
