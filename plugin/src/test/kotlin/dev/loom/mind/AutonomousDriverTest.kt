/*
 * The loop that makes an agent autonomous, driven with no server and no mind.
 *
 * What is asserted here is the whole claim of the driver: an agent thinks and acts
 * with nobody asking it to, only living agents are driven, they are driven a few at
 * a time rather than all at once, and the sweep itself never waits for cognition.
 * The body double fails the test if the driver so much as touches the tick - the
 * per-tick path is actuation only, and a driver that crept onto it would be a
 * cognition call every tick per agent.
 */
package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.BodyLocation
import dev.loom.body.BodyPort
import dev.loom.body.BodySpawnResult
import dev.loom.body.IntentOutcome
import dev.loom.contract.Decision
import dev.loom.contract.DecisionImportance
import dev.loom.contract.Intent
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private val CADENCE = 1.minutes
private const val SLICES = 4

/**
 * A body port that reports a chosen population, and records what was asked of it.
 *
 * [embodied] is deliberately separate from [living]: a port lists the bodies it has
 * and then perceives one, and an agent can drop out between the two.
 */
private class FakeBodies(
    private val living: Set<AgentId>,
    private val embodied: Set<AgentId> = living,
) : BodyPort {
    val perceived = mutableListOf<AgentId>()
    val applied = mutableListOf<AgentId>()

    override fun spawn(
        agentId: AgentId,
        at: BodyLocation,
    ): BodySpawnResult = fail("the driver must not spawn bodies")

    override fun despawn(agentId: AgentId): Boolean = fail("the driver must not despawn bodies")

    override fun apply(
        agentId: AgentId,
        intent: Intent,
        requestId: String?,
    ): IntentOutcome {
        applied.add(agentId)
        return IntentOutcome.Accepted("applied $intent")
    }

    override fun perceive(
        agentId: AgentId,
        trigger: Trigger,
    ): Perception? {
        perceived.add(agentId)
        return if (agentId in embodied) {
            perception(requestId = "req-${agentId.value}", agentId = agentId.value, trigger = trigger)
        } else {
            null
        }
    }

    override fun tick(tick: Long) = fail("the autonomous driver must never touch the per-tick path")

    override fun liveBodies(): Set<AgentId> = living
}

class AutonomousDriverTest {
    private val main = FakeMainThread()
    private var now: Duration = Duration.ZERO
    private val asked = mutableListOf<Perception>()

    private fun agents(count: Int): Set<AgentId> = (1..count).map { AgentId("agent-$it") }.toSet()

    /** A driver over [bodies] whose mind answers every perception with a move. */
    private fun driverFor(
        bodies: BodyPort,
        mind: CognitionBridge = CognitionBridge(::answer),
    ): AutonomousDriver {
        val cognition =
            EmbodiedCognition(bodies, mind, main, silentJournal(), onUnavailable = { _, e -> fail("unexpected: $e") })
        return AutonomousDriver(bodies, cognition, GoalReviewSchedule(CADENCE, SLICES)) { now }
    }

    /** A cooperative mind: records what it was asked and answers at once. */
    private fun answer(perception: Perception): CompletableFuture<Decision> {
        asked.add(perception)
        return CompletableFuture.completedFuture(decisionFor(perception, Intent.MoveTo(listOf(1L, 64L, 2L))))
    }

    @Test
    @DisplayName("an agent perceives, thinks and acts with nobody asking it to")
    fun anAgentActsOnItsOwn() {
        val agent = AgentId("agent-1")
        val bodies = FakeBodies(setOf(agent))

        driverFor(bodies).sweep()

        // The perception the agent sent itself: a goal review, at the routine tier
        // that a scheduled review is priced at.
        assertEquals(listOf(agent), bodies.perceived)
        assertEquals(Trigger.GoalReview, asked.single().trigger)
        assertEquals(DecisionImportance.ROUTINE, asked.single().importance)
        // And the decision reaches the body, on the main thread, as an action.
        assertTrue(bodies.applied.isEmpty(), "nothing may touch the body before the main thread runs")
        main.drain()
        assertEquals(listOf(agent), bodies.applied)
    }

    @Test
    @DisplayName("an agent with no body is never sent to the mind")
    fun aBodylessAgentIsNotDriven() {
        val agent = AgentId("agent-1")
        // Listed as living but with nothing to perceive with: the state an agent is
        // in for the moment between losing its body and being reaped.
        val bodies = FakeBodies(living = setOf(agent), embodied = emptySet())

        driverFor(bodies) { fail("a bodyless agent must never reach the mind") }.sweep()

        assertEquals(0, main.pending, "nothing was decided, so nothing may be scheduled")
    }

    @Test
    @DisplayName("an agent that is not among the living is never driven, however many cadences pass")
    fun aDeadAgentIsNotDriven() {
        val alive = AgentId("agent-1")
        val gone = AgentId("agent-2")
        // `gone` would perceive perfectly well if it were asked. It is not among the
        // living, and that alone must be enough: Loom is permadeath, so a driver that
        // enumerated anything wider than the live bodies would keep paying for agents
        // that are never coming back.
        val bodies = FakeBodies(living = setOf(alive), embodied = setOf(alive, gone))
        val driver = driverFor(bodies)

        repeat(SLICES) {
            now += CADENCE
            driver.sweep()
        }

        assertEquals(setOf(alive), bodies.perceived.toSet())
    }

    @Test
    @DisplayName("a population is driven a slice at a time, never all in one sweep")
    fun agentsDoNotBurst() {
        val bodies = FakeBodies(agents(20))
        val driver = driverFor(bodies)

        driver.sweep()

        assertEquals(5, asked.size, "20 agents over $SLICES slices is 5 a sweep, not 20")
    }

    @Test
    @DisplayName("the sweep does not wait for the mind")
    fun theSweepNeverBlocks() {
        val agent = AgentId("agent-1")
        val bodies = FakeBodies(setOf(agent))
        // A mind that never answers. The sweep returning at all is the property: a
        // driver that waited on cognition would hang the server tick here.
        driverFor(bodies) { CompletableFuture() }.sweep()

        assertEquals(listOf(agent), bodies.perceived)
        assertTrue(bodies.applied.isEmpty())
        assertEquals(0, main.pending, "an unanswered request schedules nothing")
    }
}
