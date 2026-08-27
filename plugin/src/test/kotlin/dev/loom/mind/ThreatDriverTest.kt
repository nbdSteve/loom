/*
 * What the vigilance loop owes an agent that something is hunting.
 *
 * Three claims, and they are the whole of what this coordinator is for: a close visible
 * hostile reaches a mind at the top tier without waiting for a goal review, the perception
 * that reaches it is the *same* one the sighting was found in, and an agent that is no
 * longer among the living is never looked at.
 *
 * No server and no engine: the driver knows a body port and a cognition seam, which is
 * exactly what makes the loop provable here.
 */
package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.BodyLocation
import dev.loom.body.BodyPort
import dev.loom.body.BodySpawnResult
import dev.loom.body.IntentOutcome
import dev.loom.contract.Bearing
import dev.loom.contract.DecisionImportance
import dev.loom.contract.Intent
import dev.loom.contract.Observation
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.util.concurrent.CompletableFuture

/** A zombie two blocks away, in the open: the case that must reach a mind at once. */
private val CLOSE_ZOMBIE =
    Observation(
        what = "entity.minecraft.zombie",
        distance = 2.0,
        direction = Bearing.NORTH,
        verticalOffset = 0.0,
        visible = true,
        ref = "e1",
    )

/** A zombie the whole perception radius away: in view, and not yet the moment. */
private val DISTANT_ZOMBIE = CLOSE_ZOMBIE.copy(distance = 20.0, ref = "e2")

/**
 * A body port that answers a scan with whatever the test put in front of each agent.
 *
 * [scans] counts the looks per agent, which is how "an agent that is gone is not looked
 * at" is stated as an observation rather than as an absence of one.
 */
private class ScannedBodies(
    private val living: Set<AgentId>,
    private val inView: Map<AgentId, Observation> = emptyMap(),
) : BodyPort {
    val scans = mutableListOf<AgentId>()

    override fun spawn(
        agentId: AgentId,
        at: BodyLocation,
    ): BodySpawnResult = fail("vigilance never spawns a body")

    override fun despawn(agentId: AgentId): Boolean = fail("vigilance never puts a body away")

    override fun apply(
        agentId: AgentId,
        intent: Intent,
        requestId: String?,
    ): IntentOutcome = IntentOutcome.Accepted("applied $intent")

    override fun perceive(
        agentId: AgentId,
        trigger: Trigger,
    ): Perception =
        fail(
            "a sighting must be submitted as the port captured it: re-perceiving would mint a " +
                "second request, and the trigger's ref only means anything in its own",
        )

    override fun tick(tick: Long) = fail("vigilance must never touch the per-tick path")

    override fun senseThreat(agentId: AgentId): Perception? {
        scans += agentId
        val seen = inView[agentId] ?: return null
        return perception(
            requestId = "req-${agentId.value}",
            agentId = agentId.value,
            trigger = sightingOf(seen),
        ).copy(surroundings = listOf(seen))
    }

    override fun liveBodies(): Set<AgentId> = living
}

class ThreatDriverTest {
    private val hunted = AgentId("agent-hunted")
    private val quiet = AgentId("agent-quiet")
    private val main = FakeMainThread()
    private val asked = mutableListOf<Perception>()

    private fun mindOf(bodies: BodyPort): EmbodiedCognition =
        EmbodiedCognition(
            bodies = bodies,
            mind = { perception ->
                asked += perception
                CompletableFuture.completedFuture(decisionFor(perception))
            },
            mainThread = main,
            journal = silentJournal(),
        )

    @Test
    @DisplayName("a close visible hostile earns one pivotal request from a single sweep")
    fun aCloseHostileIsSubmittedAtOnce() {
        val bodies = ScannedBodies(living = setOf(hunted), inView = mapOf(hunted to CLOSE_ZOMBIE))

        ThreatDriver(bodies, mindOf(bodies)).sweep()
        main.drain()

        assertEquals(1, asked.size, "one sighting is one request; what was asked: ${asked.map { it.trigger }}")
        val sent = asked.single()
        assertEquals(
            DecisionImportance.PIVOTAL,
            sent.importance,
            "a hostile two blocks away in plain sight is the moment that can end the agent, " +
                "so it must be classified on the way past rather than left at the body's default",
        )
    }

    @Test
    @DisplayName("the perception submitted is the one the sighting was found in")
    fun theSightingKeepsItsOwnRequest() {
        val bodies = ScannedBodies(living = setOf(hunted), inView = mapOf(hunted to CLOSE_ZOMBIE))

        ThreatDriver(bodies, mindOf(bodies)).sweep()
        main.drain()

        val sent = asked.single()
        val sighting = sent.trigger as Trigger.ThreatSighted
        // The ref the trigger names has to be a ref the perception carries, because a ref
        // only means something inside the request that minted it - and `perceive` in this
        // fake fails outright, so nothing could have re-captured it.
        assertEquals("req-${hunted.value}", sent.requestId, "the port's own request must be the one sent")
        assertTrue(
            sent.surroundings.any { it.ref == sighting.ref },
            "the threat the trigger names must appear in the same perception's observations, " +
                "or the flight it prompts names a handle the body cannot resolve",
        )
    }

    @Test
    @DisplayName("a hostile in view but far off is notable rather than pivotal")
    fun aDistantHostileIsNotTheTopTier() {
        val bodies = ScannedBodies(living = setOf(hunted), inView = mapOf(hunted to DISTANT_ZOMBIE))

        ThreatDriver(bodies, mindOf(bodies)).sweep()
        main.drain()

        assertEquals(
            DecisionImportance.NOTABLE,
            asked.single().importance,
            "something that hunts this agent's kind is never ordinary, but a threat across the " +
                "perception radius is not yet worth a permadeath budget's frontier model",
        )
    }

    @Test
    @DisplayName("an agent whose body has gone is neither scanned nor submitted for")
    fun aRemovedAgentIsNeverLookedAt() {
        // The threat is standing next to `quiet`, which is deliberately not living: an
        // agent that has died or been put away must not be reachable through this loop at
        // all, or a permadeath run would keep spending on agents it has already ended.
        val bodies = ScannedBodies(living = setOf(hunted), inView = mapOf(quiet to CLOSE_ZOMBIE))

        ThreatDriver(bodies, mindOf(bodies)).sweep()
        main.drain()

        assertEquals(listOf(hunted), bodies.scans, "only the living may be looked at")
        assertEquals(emptyList<Perception>(), asked, "a scan that earned nothing wakes no mind")
    }

    @Test
    @DisplayName("nothing in view costs no cognition, however often the loop sweeps")
    fun aQuietSceneWakesNoMind() {
        val bodies = ScannedBodies(living = setOf(hunted, quiet))
        val driver = ThreatDriver(bodies, mindOf(bodies))

        repeat(SWEEPS_PER_SECOND) {
            driver.sweep()
            main.drain()
        }

        assertEquals(
            SWEEPS_PER_SECOND * 2,
            bodies.scans.size,
            "every living agent is looked at on every sweep - the bound on a scan is the cadence",
        )
        assertEquals(emptyList<Perception>(), asked, "looking is free of the mind; only a sighting is not")
    }
}

/** Enough sweeps to show that repetition alone never reaches a mind. */
private const val SWEEPS_PER_SECOND = 5
