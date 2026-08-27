/*
 * The survival drive on the autonomous path, end to end, with no server and no mind.
 *
 * The parts under test are the real ones: `Metabolism` spends the reserve, `Mortality`
 * says who has ended, `GoalReviewSchedule` decides whose turn it is to think,
 * `AutonomousDriver` and `EmbodiedCognition` carry a perception to a mind and the
 * decision back to a body. Only the entity is a double, because an entity is the one
 * part that needs a world. What it stands in for is `NmsEntityBodyPort` and the seams
 * it shares with it are deliberately thin: charge the reserve on the tick, end whoever
 * ran out, report the reserve in the ordinary perception, refill it on a rest.
 *
 * Time is ticks throughout, and the clock the schedule reads is derived from the tick
 * count, so a whole life - spent down, rested, spent again, starved - runs in
 * milliseconds and runs the same way every time.
 */
package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.BodyLocation
import dev.loom.body.BodyPort
import dev.loom.body.BodySpawnResult
import dev.loom.body.IntentOutcome
import dev.loom.body.Metabolism
import dev.loom.body.Mortality
import dev.loom.contract.ENERGY_EXHAUSTED
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A whole life in 400 ticks, so several of them fit in a test. */
private const val LIFESPAN_TICKS = 400L

/** One server tick, at the twenty a second a server runs at. */
private val TICK = 50.milliseconds

/** A goal review every hundred ticks, swept in slices of twenty. */
private val SURVIVAL_CADENCE = 5.seconds
private const val SURVIVAL_SLICES = 5

/**
 * The reserve at or below which the mind here decides to rest.
 *
 * The same rule the shipped stand-in mind applies, and the same number:
 * `RESTING_ENERGY` in `cognition/crates/cognition/src/model.rs`, where it is pinned by
 * that side's tests. Restated rather than shared because it is the mind's policy and
 * this side must not depend on it - what this test asserts is that a mind *can* keep an
 * agent alive with nothing but the reserve it is told about.
 */
private const val RESTS_AT = 0.35

private val HOME = BodyLocation("world", 0.0, 64.0, 0.0)

/**
 * A body port with the real survival drive behind it and a set of ids for bodies.
 *
 * The double is the world, not the rule: the reserve, its arithmetic, and the record of
 * who has ended are the production types. What is faked is everything an entity would
 * do - standing, walking, being rendered - none of which the drive depends on.
 */
private class SurvivingBodies(
    private val events: MutableList<BodyEventRecord>,
    lifespan: Long = LIFESPAN_TICKS,
) : BodyPort {
    private val metabolism = Metabolism(lifespan)
    private val mortality = Mortality()
    private val bodies = mutableSetOf<AgentId>()

    /** The ticks a perception was taken on, and the intents applied, with their ticks. */
    val perceivedAt = mutableListOf<Long>()
    val applied = mutableListOf<Pair<Long, Intent>>()

    /** The server tick, as the last call to [tick] left it. */
    var tick = 0L
        private set

    override fun spawn(
        agentId: AgentId,
        at: BodyLocation,
    ): BodySpawnResult =
        if (mortality.isDead(agentId)) {
            BodySpawnResult.Rejected(agentId, "this agent has died, and Loom agents do not return")
        } else {
            bodies.add(agentId)
            BodySpawnResult.Spawned(agentId, at)
        }

    override fun despawn(agentId: AgentId): Boolean = bodies.remove(agentId)

    override fun apply(
        agentId: AgentId,
        intent: Intent,
        requestId: String?,
    ): IntentOutcome {
        if (agentId !in bodies) return IntentOutcome.NoSuchBody
        // The shape the adapter has: resting refills the reserve, and a reserve that has
        // already run out refuses instead of refilling.
        val spent = intent is Intent.Rest && !metabolism.restore(agentId)
        if (!spent) applied.add(tick to intent)
        return if (spent) {
            IntentOutcome.Rejected("the energy reserve is spent; this agent has ended")
        } else {
            IntentOutcome.Accepted("applied $intent")
        }
    }

    override fun perceive(
        agentId: AgentId,
        trigger: Trigger,
    ): Perception? {
        if (agentId !in bodies) return null
        perceivedAt.add(tick)
        return perception(
            requestId = "req-$tick",
            agentId = agentId.value,
            trigger = trigger,
            energy = metabolism.energyOf(agentId),
        )
    }

    override fun senseThreat(agentId: AgentId): Perception? = null

    override fun tick(tick: Long) {
        this.tick = tick
        for (agent in metabolism.spendUpTo(tick, bodies.toSet())) {
            // Ending the agent is one step: out of the live population, and into the
            // record, exactly once - which is what makes it a permadeath rather than a
            // body that happens to be missing.
            bodies.remove(agent)
            if (mortality.recordDeath(agent)) {
                events.add(BodyEventRecord(tick, agent.value, BodyEvent.DIED, "spent its energy reserve"))
            }
        }
    }

    override fun liveBodies(): Set<AgentId> = bodies.toSet()
}

class AutonomousSurvivalTest {
    private val agent = AgentId("agent-1")
    private val main = FakeMainThread()

    /** The run's record: every decision, and the death, in the order they happened. */
    private val events = mutableListOf<BodyEventRecord>()
    private val bodies = SurvivingBodies(events)

    /** Every perception the mind was given, so a test can read what the agent knew. */
    private val asked = mutableListOf<Perception>()

    private var now: Duration = Duration.ZERO

    /**
     * Runs the world for [ticks] ticks with [mind] answering, and nobody intervening.
     *
     * The body is ticked every tick and the driver swept every slice, at the rate the
     * schedule itself dictates. The mind answers instantly here, so a decision is
     * applied on the tick it was asked for rather than a few ticks later; what that
     * costs is nothing the drive can see, since the reserve is spent by the tick either
     * way.
     */
    private fun live(
        ticks: Long,
        mind: CognitionBridge,
    ) {
        val schedule = GoalReviewSchedule(SURVIVAL_CADENCE, SURVIVAL_SLICES)
        val cognition =
            EmbodiedCognition(
                bodies,
                mind,
                main,
                DecisionJournal(BodyEventLog { events.add(it) }, currentTick = { bodies.tick }),
                onUnavailable = { _, failure -> fail("the mind here always answers: $failure") },
            )
        val driver = AutonomousDriver(bodies, cognition, schedule) { now }
        val sweepEvery = (schedule.slice / TICK).toLong()
        for (tick in 1..ticks) {
            bodies.tick(tick)
            now = TICK * tick.toDouble()
            if (tick % sweepEvery == 0L) driver.sweep()
            main.drain()
        }
    }

    /** A mind that rests an agent whose reserve is low, and otherwise leaves it be. */
    private fun aMindThatRests(): CognitionBridge =
        CognitionBridge { perception ->
            asked.add(perception)
            val intent = if (perception.selfState.energy <= RESTS_AT) Intent.Rest else Intent.Idle
            CompletableFuture.completedFuture(decisionFor(perception, intent))
        }

    /** A mind that never rests an agent, whatever it is told about the reserve. */
    private fun aMindThatNeverRests(): CognitionBridge =
        CognitionBridge { perception ->
            asked.add(perception)
            CompletableFuture.completedFuture(decisionFor(perception, Intent.Idle))
        }

    private fun rests(): Int = bodies.applied.count { (_, intent) -> intent == Intent.Rest }

    @Test
    @DisplayName("an agent nobody attends to keeps itself alive, for many times the length of one reserve")
    fun anAgentSurvivesOnItsOwn() {
        bodies.spawn(agent, HOME)

        // No command, no operator, no help: the agent's own goal reviews are the only
        // thing that happens to it for five lifespans.
        live(LIFESPAN_TICKS * 5, aMindThatRests())

        assertEquals(setOf(agent), bodies.liveBodies(), "an agent that rested when it needed to must still be here")
        assertTrue(events.none { it.event == BodyEvent.DIED }, "nothing may have died: ${events.map { it.detail }}")
        // It was not merely lucky: it read a reserve worth resting on, several times,
        // and each reading came from the body it was standing in.
        assertTrue(asked.minOf { it.selfState.energy } <= RESTS_AT, "the mind must have seen a low reserve")
        assertTrue(rests() >= 4, "five lifespans of unattended running must have taken several rests, took ${rests()}")
    }

    @Test
    @DisplayName("an agent whose mind never rests it starves, once, and is never driven again")
    fun anAgentThatIsNeverRestedStarves() {
        bodies.spawn(agent, HOME)

        // Twice as long as a reserve lasts, so a run that failed to end the agent would
        // have every chance to show it.
        live(LIFESPAN_TICKS * 2, aMindThatNeverRests())

        assertTrue(bodies.liveBodies().isEmpty(), "an agent that ran out of reserve is not among the living")
        val deaths = events.filter { it.event == BodyEvent.DIED }
        assertEquals(1, deaths.size, "one agent, one death, however long the run goes on afterwards")
        assertEquals(agent.value, deaths.single().agentId)
        assertEquals(LIFESPAN_TICKS, deaths.single().tick, "the reserve ran out on the tick it was spent, not later")
        // The death is the last thing in this agent's record. Nothing perceived for it,
        // nothing decided for it, and nothing acted for it after that tick.
        assertEquals(BodyEvent.DIED, events.last().event)
        assertTrue(bodies.perceivedAt.all { it < LIFESPAN_TICKS }, "a dead agent must not perceive")
        assertTrue(bodies.applied.all { (tick, _) -> tick < LIFESPAN_TICKS }, "a dead agent must not act")
        assertTrue(asked.none { it.selfState.energy == ENERGY_EXHAUSTED }, "a spent agent must not reach the mind")
        // And it stays gone: exhaustion is a permadeath, so the id is refused a body.
        assertTrue(
            bodies.spawn(agent, HOME) is BodySpawnResult.Rejected,
            "an agent that starved must never be embodied again",
        )
    }

    @Test
    @DisplayName("what the mind decides on is the reserve the body had at that tick")
    fun theReserveCrossesToTheMindThroughTheOrdinaryPerception() {
        bodies.spawn(agent, HOME)

        // Half a reserve: long enough for several reviews, short enough that no rest has
        // happened yet, so what is left is exactly what has not been spent.
        live(LIFESPAN_TICKS / 2, aMindThatRests())

        assertEquals(0, rests(), "this half of a life is before the reserve gets low")
        assertEquals(
            bodies.perceivedAt.map { (LIFESPAN_TICKS - it).toDouble() / LIFESPAN_TICKS },
            asked.map { it.selfState.energy },
            "each review saw the reserve as it stood on the tick it was taken",
        )
    }
}
