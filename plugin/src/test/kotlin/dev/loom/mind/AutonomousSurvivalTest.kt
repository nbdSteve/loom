/*
 * The survival drive on the autonomous path, end to end, with no server and no mind.
 *
 * The parts under test are the real ones: `Metabolism` spends the reserve, `Mortality`
 * says who has ended, `GoalReviewSchedule` decides whose turn it is to think,
 * `AutonomousDriver` and `EmbodiedCognition` carry a perception to a mind and the
 * decision back to a body. Only the entity is a double, because an entity is the one
 * part that needs a world. What it stands in for is `NmsEntityBodyPort` and the seams
 * it shares with it are deliberately thin: charge the reserve on the tick, end whoever
 * ran out, report the reserve in the ordinary perception.
 *
 * Nothing here refills a reserve, because nothing an agent can decide today does. Food
 * is the only source of recovery, and resting is a deliberate hold rather than a meal -
 * so a mind that answers every low reserve with a rest starves its agent on exactly the
 * tick a mind that answered with idle would have.
 *
 * Time is ticks throughout, and the clock the schedule reads is derived from the tick
 * count, so a whole life - spent down, waited out, starved - runs in milliseconds and
 * runs the same way every time.
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
        // The shape the adapter has: every intent is applied on its own terms, and none
        // of them touches the reserve. A rest stops the body and buys no time.
        applied.add(tick to intent)
        return IntentOutcome.Accepted("applied $intent")
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
        bodies: SurvivingBodies = this.bodies,
        events: MutableList<BodyEventRecord> = this.events,
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

    /** How many times [world]'s body was actually rested, as its own record of applied intents shows. */
    private fun rests(world: SurvivingBodies = bodies): Int =
        world.applied.count { (_, intent) -> intent == Intent.Rest }

    /**
     * Runs a fresh world to two whole reserves with [mind] answering, and answers with
     * the world it left behind.
     *
     * A world of its own rather than this test's, so two minds can be compared without
     * either inheriting the other's reserve - and returned rather than reduced to a death
     * tick, because what a mind actually got the body to *do* is half of what a comparison
     * of two minds is worth.
     */
    private fun aLifeUnder(
        events: MutableList<BodyEventRecord>,
        mind: CognitionBridge,
    ): SurvivingBodies {
        val world = SurvivingBodies(events)
        world.spawn(agent, HOME)
        live(LIFESPAN_TICKS * 2, mind, world, events)
        return world
    }

    /** The tick the one agent in [events] starved on. */
    private fun starvedAt(events: List<BodyEventRecord>): Long = events.single { it.event == BodyEvent.DIED }.tick

    @Test
    @DisplayName("an agent that only rests starves on the same tick as one that only idles")
    fun restingBuysNoTime() {
        val restingEvents = mutableListOf<BodyEventRecord>()
        val idlingEvents = mutableListOf<BodyEventRecord>()

        // Two runs of the same length, differing only in what the mind decides. Resting
        // is a deliberate wait and food is the only recovery, so the two agents must end
        // at the identical tick - a rest that bought even one tick would show up here.
        val resting = aLifeUnder(restingEvents, aMindThatRests())
        val idling = aLifeUnder(idlingEvents, aMindThatNeverRests())

        assertEquals(starvedAt(idlingEvents), starvedAt(restingEvents), "a rest must not buy an agent a single tick")
        assertEquals(LIFESPAN_TICKS, starvedAt(restingEvents), "the reserve is spent by living, at one tick a tick")
        // And the two runs really did differ, which is the whole of what makes their equal
        // ends evidence: one agent was rested by its mind, repeatedly, and the other never
        // was. Comparing two runs that both only idled would pass this test for ever.
        assertTrue(rests(resting) > 0, "the resting mind must have answered rest, and the body applied it")
        assertEquals(0, rests(idling), "the run it is compared against must never have rested")
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
