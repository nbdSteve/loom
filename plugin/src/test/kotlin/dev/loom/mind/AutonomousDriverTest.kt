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
import dev.loom.body.BodyPort
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

private const val CADENCE = 1_200L
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

    override fun spawn(agentId: AgentId) = fail("the driver must not spawn bodies")

    override fun despawn(agentId: AgentId) = fail("the driver must not despawn bodies")

    override fun apply(
        agentId: AgentId,
        intent: Intent,
        requestId: String?,
    ): CompletableFuture<IntentOutcome> {
        applied.add(agentId)
        return CompletableFuture.completedFuture(IntentOutcome.Accepted("applied $intent"))
    }

    override fun perceive(
        agentId: AgentId,
        trigger: Trigger,
    ): CompletableFuture<Perception?> {
        perceived.add(agentId)
        return CompletableFuture.completedFuture(
            if (agentId in embodied) {
                perception(requestId = "req-${agentId.value}", agentId = agentId.value, trigger = trigger)
            } else {
                null
            },
        )
    }

    override fun tick(tick: Long) = fail("the autonomous driver must never touch the per-tick path")

    override fun senseThreat(agentId: AgentId): CompletableFuture<Perception?> =
        fail("a goal review must never look for threats: that is the vigilance loop's own sweep")

    override fun liveBodies(): Set<AgentId> = living
}

class AutonomousDriverTest {
    private val main = FakeMainThread()
    private var now = 0L
    private val asked = mutableListOf<Perception>()

    private fun agents(count: Int): Set<AgentId> = (1..count).map { AgentId("agent-$it") }.toSet()

    private val schedule = GoalReviewSchedule(CADENCE, SLICES)

    /** A driver over [bodies] whose mind answers every perception with a move. */
    private fun driverFor(
        bodies: BodyPort,
        mind: CognitionBridge = CognitionBridge(::answer),
    ): AutonomousDriver {
        val cognition =
            EmbodiedCognition(
                bodies,
                mind,
                main,
                silentJournal(),
                CognitionCallbacks(
                    onUnavailable = { _, failure -> fail("unexpected: $failure") },
                ),
            )
        return AutonomousDriver(bodies, cognition, schedule) { now }
    }

    /** A cooperative mind: records what it was asked and answers at once. */
    private fun answer(perception: Perception): CompletableFuture<Decision> {
        asked.add(perception)
        return CompletableFuture.completedFuture(decisionFor(perception, Intent.MoveTo("b1")))
    }

    @Test
    @DisplayName("an agent perceives, thinks and acts with nobody asking it to")
    fun anAgentActsOnItsOwn() {
        val agent = AgentId("agent-1")
        val bodies = FakeBodies(setOf(agent))

        driverFor(bodies).sweep()

        assertEquals(1, main.pending, "body I/O completion must return through the main thread")
        main.drain()

        // The perception the agent sent itself: a goal review, at the routine tier
        // that a scheduled review is priced at.
        assertEquals(listOf(agent), bodies.perceived)
        assertEquals(Trigger.GoalReview, asked.single().trigger)
        assertEquals(DecisionImportance.ROUTINE, asked.single().importance)
        // And the decision reaches the body, on the main thread, as an action.
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

        main.drain()
        assertEquals(0, main.pending, "nothing was decided, so nothing else may be scheduled")
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
        main.drain()

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
        assertEquals(1, main.pending, "the completed body read still returns through the main thread")
        main.drain()
        assertEquals(0, main.pending, "an unanswered mind schedules no action")
    }

    @Test
    @DisplayName("a settled goal step is followed up as its own trigger, and never doubled with a review")
    fun aSettledStepIsReviewedAsStepSettled() {
        val agent = AgentId("agent-1")
        val bodies = FakeBodies(setOf(agent))
        val driver = driverFor(bodies)
        driver.sweep()
        main.drain()
        schedule.settled(agent, now, owed = false)
        now += FOLLOW_UP_DELAY_TICKS
        driver.sweep()
        main.drain()
        assertEquals(listOf(Trigger.GoalReview, Trigger.StepSettled), asked.map { it.trigger })
        assertEquals(DecisionImportance.ROUTINE, asked.last().importance)
        // The follow-up's own step owes nothing more: the next review is the scheduled one.
        schedule.settled(agent, now, owed = false)
        now = CADENCE
        driver.sweep()
        main.drain()
        assertEquals(listOf(Trigger.GoalReview, Trigger.StepSettled, Trigger.GoalReview), asked.map { it.trigger })
    }
}
