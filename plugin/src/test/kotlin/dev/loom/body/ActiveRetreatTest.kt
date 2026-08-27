package dev.loom.body

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A retreat that lasts, without a world.
 *
 * The engine half of a hop is two calls and needs a live `Level`; what is pinned here is
 * the whole of what a tick may *ask* of an active retreat. Every case is measured against
 * the in-world death that asked for it: a body given a good target walked it in about a
 * second, arrived, and stood still for four seconds while a zombie closed ten blocks and
 * killed it, and the one continuation that was attempted arrived while knockback had the
 * body off the ground and was refused by a ground navigator that will not search mid-air.
 */
class ActiveRetreatTest {
    private val agent = AgentId("runner")
    private val zombie: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val other: UUID = UUID.fromString("00000000-0000-4000-8000-000000000002")

    /** A retreat already running against [zombie], routed on tick 0. */
    private fun retreating(): ActiveRetreats =
        ActiveRetreats().apply {
            assertTrue(attempted(agent, RetreatAttempt.Routed(zombie, "retreating to 1,2,3"), tick = 0))
        }

    private fun ActiveRetreats.stepAt(
        tick: Long,
        standing: ThreatStanding = ThreatStanding.IN_RANGE,
        routeRunning: Boolean = false,
        grounded: Boolean = true,
    ) = next(agent, tick, standing, routeRunning, grounded)

    @Test
    fun `a route that has ended is continued while the same threat is still in range`() {
        // The defect this whole file exists for. Nothing acted on a route ending, so one
        // Flee was one hop and the agent stood still until its next goal review.
        assertEquals(
            RetreatStep.Route(zombie),
            retreating().stepAt(tick = RETREAT_RETRY_CADENCE_TICKS),
        )
    }

    @Test
    fun `a body still walking its route is asked for nothing`() {
        // A live route is already the retreat. Asking would spend a path-search token out
        // of the per-tick bucket to replace a good route with a shorter one.
        assertEquals(
            RetreatStep.Hold,
            retreating().stepAt(tick = 500, routeRunning = true),
        )
    }

    @Test
    fun `continuations are bounded by their own cadence, not tried every tick`() {
        val retreats = retreating()

        for (tick in 1 until RETREAT_RETRY_CADENCE_TICKS) {
            assertEquals(RetreatStep.Hold, retreats.stepAt(tick), "tick $tick is inside the cadence")
        }
        assertEquals(RetreatStep.Route(zombie), retreats.stepAt(RETREAT_RETRY_CADENCE_TICKS))
    }

    @Test
    fun `a cornered agent does not sample the ground every tick`() {
        // The cost being bounded is *asking*: a `chooseRetreat` sample and a path search.
        // A retreat whose continuation is refused for the ground it is standing on would
        // otherwise pay both twenty times a second for as long as it was hunted.
        assertTrue(RETREAT_RETRY_CADENCE_TICKS > 1, "asking every tick is what the cadence exists to stop")
        // And bounded above as well: the cadence is how long a hop that arrived early leaves
        // the body standing still with something hunting it, so half a second is the ceiling.
        assertTrue(
            RETREAT_RETRY_CADENCE_TICKS < TICKS_PER_SECOND,
            "a body waiting a whole second between hops is a body standing still",
        )
    }

    @Test
    fun `an airborne attempt keeps the retreat and waits for the ground`() {
        // The second live defect. A body being hit is off the ground, a ground navigator
        // will not search there, and the flee was refused outright - one of a night's eight
        // pivotal model calls spent on nothing, with nothing to retry it.
        val retreats = ActiveRetreats()

        assertTrue(retreats.attempted(agent, RetreatAttempt.Grounding(zombie), tick = 0))
        assertEquals(zombie, retreats.threatOf(agent), "the retreat is retained, not refused")
        // Still in the air at the cadence: held, and nothing is recorded a second time.
        assertEquals(RetreatStep.Hold, retreats.stepAt(RETREAT_RETRY_CADENCE_TICKS, grounded = false))
        // Back on its feet: routed, with no further cognition spent.
        assertEquals(RetreatStep.Route(zombie), retreats.stepAt(RETREAT_RETRY_CADENCE_TICKS))
    }

    @Test
    fun `a spent search budget keeps the retreat and waits for a token`() {
        // Loom grants a fixed number of path searches per tick across the whole server, so a
        // tick with more agents deciding than the budget serves refuses the excess. Ended
        // there, the flight would be lost to Loom's own bookkeeping while the target stayed
        // reachable, and the agent would stand still with a hostile on it until its next goal
        // review - the death this state machine exists to prevent, arriving a different way.
        val retreats = ActiveRetreats()

        assertTrue(retreats.attempted(agent, RetreatAttempt.PendingBudget(zombie), tick = 0))
        assertEquals(zombie, retreats.threatOf(agent), "the retreat is retained, not refused")
        // Not asked again any faster than a routed hop is: a budget that is out this tick is
        // most likely out for the agents behind it too, and a retry per tick would be a search
        // per tick from every one of them.
        assertEquals(RetreatStep.Hold, retreats.stepAt(RETREAT_RETRY_CADENCE_TICKS - 1))
        assertEquals(RetreatStep.Route(zombie), retreats.stepAt(RETREAT_RETRY_CADENCE_TICKS))
    }

    @Test
    fun `a spent budget is an acceptance and says so apart from a failure`() {
        // The two questions a run has to be able to answer separately: were its agents
        // cornered, or merely over budget. One tag each, and the flight is still a flight.
        assertTrue(outcomeOf(RetreatAttempt.PendingBudget(zombie)) is IntentOutcome.Accepted)
        assertEquals(
            EventTag.RETREAT_PENDING_BUDGET,
            tagOf(RetreatAttempt.PendingBudget(zombie), continued = false),
        )
        assertNotEquals(
            EventTag.RETREAT_FAILED,
            tagOf(RetreatAttempt.PendingBudget(zombie), continued = true),
            "waiting for a token is not the ground refusing",
        )
        assertNotEquals(
            EventTag.RETREAT_PENDING_GROUND,
            tagOf(RetreatAttempt.PendingBudget(zombie), continued = false),
            "Loom's own budget and a body in mid-air are different facts about the run",
        )
        assertTrue(describe(RetreatAttempt.PendingBudget(zombie), continued = false).isNotBlank())
    }

    @Test
    fun `getting out of range ends the retreat as safety rather than as failure`() {
        val retreats = retreating()

        assertEquals(
            RetreatStep.Ended(RetreatEnd.SAFE),
            retreats.stepAt(RETREAT_RETRY_CADENCE_TICKS, ThreatStanding.OUT_OF_RANGE),
        )
        // Ended once: the state is gone, so the transition cannot be journaled twice.
        assertNull(retreats.threatOf(agent))
        assertEquals(RetreatStep.Hold, retreats.stepAt(500))
    }

    @Test
    fun `a threat that is dead, despawned or in another dimension ends the retreat`() {
        val retreats = retreating()

        assertEquals(
            RetreatStep.Ended(RetreatEnd.THREAT_GONE),
            retreats.stepAt(RETREAT_RETRY_CADENCE_TICKS, ThreatStanding.GONE),
        )
        assertNull(retreats.threatOf(agent))
    }

    @Test
    fun `the threat is checked before the route, so a resolved threat stops a running route`() {
        // An agent that has got away has nothing to run from, and a retreat that waited for
        // its current route to end first would keep running from something that is not there.
        assertEquals(
            RetreatStep.Ended(RetreatEnd.THREAT_GONE),
            retreating().stepAt(1, ThreatStanding.GONE, routeRunning = true),
        )
    }

    @Test
    fun `a later intent that is not a flight supersedes the retreat exactly once`() {
        val retreats = retreating()

        assertTrue(retreats.superseded(agent), "the intent that took a retreat over says so")
        assertFalse(retreats.superseded(agent), "and every later intent says nothing")
        assertEquals(RetreatStep.Hold, retreats.stepAt(500))
    }

    @Test
    fun `a later flight replaces the threat rather than adding one`() {
        val retreats = retreating()

        assertTrue(retreats.attempted(agent, RetreatAttempt.Routed(other, "retreating to 4,5,6"), tick = 40))
        assertEquals(other, retreats.threatOf(agent), "an agent has one body and runs from one thing")
        // And the new attempt resets the cadence: it is an attempt like any other.
        assertEquals(RetreatStep.Hold, retreats.stepAt(40 + RETREAT_RETRY_CADENCE_TICKS - 1))
        assertEquals(RetreatStep.Route(other), retreats.stepAt(40 + RETREAT_RETRY_CADENCE_TICKS))
    }

    @Test
    fun `a body that has ended takes its retreat with it`() {
        val retreats = retreating()

        retreats.forget(agent)

        assertNull(retreats.threatOf(agent))
        assertEquals(RetreatStep.Hold, retreats.stepAt(500), "no successor body continues a dead agent's flight")
    }

    @Test
    fun `a grounded refusal ends the retreat instead of retrying it for ever`() {
        // Every one of the five refusals is a fact about the run rather than something to
        // retry, and the body has already been stopped by the time one is answered. A
        // retreat that stayed active would be an endless search against ground that has
        // already said no.
        for (attempt in everyFailure) {
            val retreats = retreating()

            assertFalse(retreats.attempted(agent, attempt, tick = 40), "$attempt")

            assertNull(retreats.threatOf(agent), "$attempt")
            assertEquals(RetreatStep.Hold, retreats.stepAt(500), "$attempt")
        }
    }

    @Test
    fun `a route the movement policy would not take is a failure and not a threat refusal`() {
        // A target past the reach and a tick whose search budget is spent are answers about
        // a *route*; the five refusals are answers about a *threat*. Both end the retreat,
        // and both are already-stopped bodies, but a run that could not tell them apart
        // could not say whether its agents were cornered or merely over budget.
        val overBudget = RetreatAttempt.Unrouted("the tick has spent its path search budget")

        assertEquals(IntentOutcome.Rejected(overBudget.reason), outcomeOf(overBudget))
        assertEquals(EventTag.RETREAT_FAILED, tagOf(overBudget, continued = true))
        assertFalse(retreating().attempted(agent, overBudget, tick = 40))
    }

    @Test
    fun `every transition is countable, and starting is countable apart from continuing`() {
        // The tag is what a run counts; the sentence is what a person reads. An ending that
        // gained one without the other would be a transition nothing could total up.
        val tags = RetreatEnd.entries.map(::tagOf)

        assertEquals(tags.size, tags.distinct().size, "two endings must never count as one")
        assertTrue(tags.none { it.isBlank() })
        val routed = RetreatAttempt.Routed(zombie, "retreating to 1,2,3")
        assertEquals(EventTag.RETREAT_STARTED, tagOf(routed, continued = false))
        assertEquals(EventTag.RETREAT_CONTINUED, tagOf(routed, continued = true))
        // Waiting for the ground is its own count, because it is the one transition that
        // resolves itself rather than being a failure or a resolution.
        assertEquals(EventTag.RETREAT_PENDING_GROUND, tagOf(RetreatAttempt.Grounding(zombie), continued = false))
        for (attempt in everyFailure) {
            assertEquals(tagOf(attempt, continued = false), tagOf(attempt, continued = true), "$attempt")
            assertTrue(describe(attempt, continued = false).isNotBlank(), "$attempt")
        }
    }

    @Test
    fun `only the ground refusing counts as a flight that failed to get away`() {
        // RETREAT_FAILED means the agent is still in range of something hunting it and has
        // been left standing, and it is the count a run reads for predation. A ref that had
        // gone stale and a threat that had already burnt to death were both filed under it,
        // so a display told an operator the agent "could not run from a threat, and is still
        // in range of it" about a zombie that was dead - and every such decision was totalled
        // into the predation figure.
        assertEquals(
            EventTag.RETREAT_THREAT_GONE,
            tagOf(RetreatAttempt.Refused(RetreatRefusal.ThreatGone), continued = false),
            "a threat that has gone is the same fact however the retreat reached it",
        )
        assertEquals(
            tagOf(RetreatEnd.THREAT_GONE),
            tagOf(RetreatAttempt.Refused(RetreatRefusal.ThreatGone), continued = false),
            "the first hop and the continuation must not tag one fact two ways",
        )
        for (refusal in listOf(RetreatRefusal.ThreatForgotten, RetreatRefusal.ThreatUnnamed)) {
            assertNotEquals(
                EventTag.RETREAT_FAILED,
                tagOf(RetreatAttempt.Refused(refusal), continued = false),
                "$refusal is an answer about a handle, not about being cornered",
            )
        }
        // And the two that really are the ground refusing keep the count they are for, along
        // with a chosen target the routing seam would not take.
        for (attempt in groundRefusals) {
            assertEquals(EventTag.RETREAT_FAILED, tagOf(attempt, continued = false), "$attempt")
        }
    }

    @Test
    fun `waiting for the ground is an acceptance and a refusal is not`() {
        // Reported as a rejection, a flight that is waiting for the ground would read
        // afterwards as a flight that never happened - which is exactly what the in-world
        // record said about an agent that was in fact fleeing.
        assertTrue(outcomeOf(RetreatAttempt.Grounding(zombie)) is IntentOutcome.Accepted)
        assertEquals(
            IntentOutcome.Accepted("retreating to 1,2,3"),
            outcomeOf(RetreatAttempt.Routed(zombie, "retreating to 1,2,3")),
        )
        for (refusal in everyRefusal) {
            assertEquals(
                IntentOutcome.Rejected(describe(refusal)),
                outcomeOf(RetreatAttempt.Refused(refusal)),
                "$refusal",
            )
        }
    }

    @Test
    fun `every ending says something different that a record can carry`() {
        // Total over the enum by construction, and distinct because the four are opposite
        // facts about the run: an agent that got away, one whose threat died, one that was
        // given something else to do, and one that was left standing in range.
        val said = RetreatEnd.entries.map(::describe)

        assertEquals(said.size, said.distinct().size, "two endings must never read the same")
        assertTrue(said.none { it.isBlank() }, "an ending with nothing to say is one nothing can count")
    }

    private val everyRefusal =
        listOf(
            RetreatRefusal.ThreatForgotten,
            RetreatRefusal.ThreatUnnamed,
            RetreatRefusal.ThreatGone,
            RetreatRefusal.NoRetreat,
            RetreatRefusal.NoMeaningfulRetreat,
        )

    /** Every attempt that ends a retreat: the five about a threat, and one about a route. */
    private val everyFailure: List<RetreatAttempt> =
        everyRefusal.map { RetreatAttempt.Refused(it) } +
            RetreatAttempt.Unrouted("the engine offered no route to 1,2,3")

    /**
     * The attempts that leave the agent in range of a live threat and standing still.
     *
     * The two refusals that are the ground's own answer, plus the routing seam's - the
     * only three `RETREAT_FAILED` may count, since that tag is what a run reads for
     * predation.
     */
    private val groundRefusals: List<RetreatAttempt> =
        listOf(
            RetreatAttempt.Refused(RetreatRefusal.NoRetreat),
            RetreatAttempt.Refused(RetreatRefusal.NoMeaningfulRetreat),
            RetreatAttempt.Unrouted("the engine offered no route to 1,2,3"),
        )
}

/** Server ticks in one second, which is what makes a cadence readable as a delay. */
private const val TICKS_PER_SECOND: Int = 20
