/*
 * TEMPORARY reproduction of the gap PR B closes. Delete once ThreatDriverTest covers it.
 *
 * Claim under test: a hostile standing two blocks from an agent, in plain sight, gets a
 * pivotal cognition request before the agent's ordinary goal review comes round. Today
 * the only thing that wakes a mind is `AutonomousDriver`, whose whole cadence is a goal
 * review, so nothing in the loop can say "this is happening now".
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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The zombie standing next to the agent in every case here. */
private val ZOMBIE =
    Observation(
        what = "entity.minecraft.zombie",
        distance = 2.0,
        direction = Bearing.NORTH,
        verticalOffset = 0.0,
        visible = true,
        ref = "e1",
    )

/** A body whose every perception shows one zombie two blocks away, in the open. */
private class HuntedBody(private val agent: AgentId) : BodyPort {
    override fun spawn(
        agentId: AgentId,
        at: BodyLocation,
    ): BodySpawnResult = fail("not part of this reproduction")

    override fun despawn(agentId: AgentId): Boolean = fail("not part of this reproduction")

    override fun apply(
        agentId: AgentId,
        intent: Intent,
        requestId: String?,
    ): IntentOutcome = IntentOutcome.Accepted("applied $intent")

    override fun perceive(
        agentId: AgentId,
        trigger: Trigger,
    ): Perception =
        perception(requestId = "req-1", agentId = agentId.value, trigger = trigger)
            .copy(surroundings = listOf(ZOMBIE))

    override fun senseThreat(agentId: AgentId): Perception =
        perception(requestId = "req-threat", agentId = agentId.value, trigger = sightingOf(ZOMBIE))
            .copy(surroundings = listOf(ZOMBIE))

    override fun tick(tick: Long) = fail("cognition is never driven from the tick")

    override fun liveBodies(): Set<AgentId> = setOf(agent)
}

class PivotalThreatReproductionTest {
    private val agent = AgentId("agent-hunted")
    private val main = FakeMainThread()
    private var now: Duration = Duration.ZERO
    private val asked = mutableListOf<Perception>()

    @Test
    @DisplayName("a zombie two blocks away in plain sight earns a pivotal request within a second")
    fun aCloseHostileInterruptsOrdinaryGoalReview() {
        val bodies = HuntedBody(agent)
        val mind =
            EmbodiedCognition(
                bodies = bodies,
                mind = { perception ->
                    asked += perception
                    CompletableFuture.completedFuture(decisionFor(perception))
                },
                mainThread = main,
                journal = silentJournal(),
            )
        // A conservative review cadence, as a real run uses: the whole point is that a
        // threat must not wait for it.
        val driver = AutonomousDriver(bodies, mind, GoalReviewSchedule(cadence = 2.minutes), now = { now })

        // One second of the world going by, swept as often as anything sweeps.
        repeat(20) {
            now += 1.seconds / 20
            driver.sweep()
            main.drain()
        }

        val pivotal = asked.filter { it.importance == DecisionImportance.PIVOTAL }
        assertEquals(
            1,
            pivotal.size,
            "a hostile two blocks away in sight must earn exactly one pivotal request in the first " +
                "second, not wait up to a whole goal-review cadence; what was asked instead: " +
                asked.map { "${it.importance}/${it.trigger}" },
        )
    }
}
