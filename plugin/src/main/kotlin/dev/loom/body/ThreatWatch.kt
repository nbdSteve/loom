/*
 * When a hostile in view is worth waking the mind for.
 *
 * The first survival drive Loom gave an agent was hunger, which is slow: a reserve
 * drains over half an hour and the scheduled goal review is a fine place to notice it.
 * Predation is not slow. An in-world run watched a zombie kill an agent whose every
 * perception still said `goalReview`, because nothing between the world and the mind
 * was allowed to say "this is happening now" - so this is the seam that says it.
 *
 * Two opposite failures bound the design, and both are expensive. Never firing early
 * means an agent dies mid-cadence with a mind that was never asked; firing on every
 * tick a zombie is in view means a frontier model call twenty times a second for as
 * long as the zombie stands there. So a sighting fires at once the first time, and
 * after that only on the cadence or on a change that alters what the agent should do -
 * and the pivotal calls one agent's night can cost are capped outright.
 *
 * Every refusal is recorded rather than merely returned. A run whose agent did nothing
 * about an approaching zombie has to be able to say whether the mind decided that or
 * was never asked, and which bound it was that did not ask - so a suppression carries a
 * stable reason and lands in the append-only log beside the sightings that did fire.
 *
 * Engine-free by rule, like the rest of `dev.loom.body`. The clock is injected and the
 * reachability answer is a function the caller supplies, so every rule here is pinned
 * by a unit test with a fake clock and a counted probe rather than by a server run.
 */
package dev.loom.body

import dev.loom.contract.Bearing
import dev.loom.contract.Trigger
import java.util.UUID

/**
 * The living things in Paper 1.21.1 that hunt a villager on sight.
 *
 * A Loom body is a villager underneath (see `LoomBodyEntity`), and "not a monster" is
 * not safety: these eight all carry a vanilla goal that targets `AbstractVillager`, so
 * they come for an agent without being provoked. They are named by the engine's own
 * entity description id - the same string an [dev.loom.contract.Observation.what]
 * carries - so this table stays engine-free and testable, and the adapter has nothing
 * to classify.
 *
 * `zombie_villager` is here because it inherits Zombie's target goals rather than
 * declaring its own, which is exactly the kind of thing a table of class names would
 * have got wrong. Anything that hunts an agent only once provoked is deliberately
 * absent: this is the set that makes standing still dangerous.
 */
val VILLAGER_HUNTERS: Set<String> =
    setOf(
        "entity.minecraft.zombie",
        "entity.minecraft.drowned",
        "entity.minecraft.zombie_villager",
        "entity.minecraft.evoker",
        "entity.minecraft.illusioner",
        "entity.minecraft.pillager",
        "entity.minecraft.vindicator",
        "entity.minecraft.ravager",
    )

/** Whether [what] - an entity description id - hunts a Loom body unprovoked. */
fun huntsVillagers(what: String): Boolean = what in VILLAGER_HUNTERS

/**
 * How long one threat's sighting holds off the next, in ticks.
 *
 * Five seconds. Long enough that a hostile standing in view does not ask the mind
 * again on every tick - twenty calls a second is the failure mode that makes an
 * event-driven trigger more expensive than the cadence it exists to beat - and short
 * enough that a hostile crossing the whole [PERCEPTION_RADIUS_BLOCKS] toward a body is
 * reported several times before it arrives.
 *
 * It is a floor on repeats and not a wait: the first sight of a threat fires
 * immediately, and the changes that alter what an agent should do re-fire inside the
 * window. Deliberately far shorter than a goal review's cadence, which is the whole
 * point of the seam - a threat folded into the ordinary review is a threat the agent
 * meets up to a cadence late - and `ThreatWatchTest` pins that ordering.
 */
const val THREAT_DEBOUNCE_TICKS: Long = 100

/**
 * How close a hostile has to be before its sighting is worth the frontier model.
 *
 * Eight blocks, deliberately the same distance a body may be *sent* in one navigation
 * request ([PATH_REQUEST_REACH_BLOCKS]): that is the range in which the agent's own
 * options, retreat included, are still real. A threat further off than the body can act
 * over is news rather than a crisis.
 *
 * It lives here rather than beside the routing it drives, because the body is what
 * measures the distance and `dev.loom.mind`'s `importanceOf` is what spends money on
 * it; written down twice, a change to one would silently leave the other tiering the
 * same sighting differently from the watch that reported it.
 */
const val PIVOTAL_THREAT_DISTANCE_BLOCKS: Double = PATH_REQUEST_REACH_BLOCKS

/**
 * How many pivotal threat sightings one agent's night may pay for.
 *
 * A hard ceiling rather than a rate: a night with many hostiles about is exactly when
 * the debounce is least effective, because each new one is a first sight and fires at
 * once. Eight is enough that an agent meeting a group is asked about the first several
 * of them and cheap enough that a bad night cannot become a bill nobody chose.
 *
 * Per agent rather than server-wide, which is the opposite choice from
 * [NavigationPolicy]'s and made for the opposite reason. A path search is a cost to the
 * *server*, so a hundred agents deciding at once must share one budget or the tick
 * suffers. A model call is a cost to the *run*, and an agent that has spent nothing
 * tonight has done nothing to deserve being refused because its neighbour was busy
 * being hunted - which, with one shared ceiling, is exactly what would happen in the
 * scene the trigger exists for. The consequence is deliberate and worth stating: the
 * whole population's ceiling grows with its head count, so an operator sizing a run's
 * inference bill multiplies this by the agents, not by the server.
 *
 * Reached, the sighting is suppressed rather than downgraded to a cheaper tier. A
 * pivotal moment answered by a routine model would look in the record like a decision
 * the agent made, when what actually happened is that Loom stopped paying - and
 * [ThreatSuppressionReason.OVER_NIGHTLY_CAP] says so in its own record.
 */
const val MAX_PIVOTAL_THREAT_CALLS_PER_NIGHT: Int = 8

/** Why a sighting the body saw did not reach the mind. */
enum class ThreatSuppressionReason(
    /** The stable tag the append-only log counts by. */
    val tag: String,
) {
    /**
     * The threat was reported recently and nothing worth re-asking about has changed.
     *
     * The ordinary case, and the one that makes the seam affordable.
     */
    DEBOUNCED(EventTag.THREAT_DEBOUNCED),

    /**
     * The cadence elapsed and the threat is in exactly the state it was reported in.
     *
     * Kept apart from [DEBOUNCED] because it is a different fact: the bound that
     * refused was not the clock but the news. A standing zombie behind the same wall at
     * the same distance is something the mind has already been told, and re-telling it
     * every cadence for as long as it stands there would spend a run's budget on the
     * absence of events. The scheduled goal review still reaches the agent meanwhile.
     */
    UNCHANGED(EventTag.THREAT_UNCHANGED),

    /** This agent's pivotal threat budget for the night is spent. */
    OVER_NIGHTLY_CAP(EventTag.THREAT_OVER_NIGHTLY_CAP),
}

/** One hostile in view, as the threat rule needs it. */
data class ThreatCandidate(
    /** Which creature it is, so the watch can tell one zombie from another. */
    val subject: UUID,
    /** The engine's own description of it, and what [huntsVillagers] is asked about. */
    val what: String,
    /** The handle this perception issued for it, minted by [EntityRefs]. */
    val ref: String,
    /** How far away it is, in blocks, as the perception bound measured it. */
    val distance: Double,
    /** The horizontal bearing to it. */
    val direction: Bearing,
    /** Whether the body has a clear line to it. Not a field of view: a line. */
    val visible: Boolean,
)

/** A sighting that did not reach the mind, and why. */
data class ThreatSuppression(
    /** The threat that was not reported. */
    val what: String,
    /** The handle it carried, so the suppression joins the perception that saw it. */
    val ref: String,
    /** Which bound refused. */
    val reason: ThreatSuppressionReason,
)

/**
 * What one tick's look around amounted to.
 *
 * At most one sighting, because a decision answers one threat: combining several into
 * one retreat is deliberately not in this design. The rest of what was examined is in
 * [suppressed], so the record says what the body saw and not only what it reported.
 */
data class ThreatOutcome(
    /** The trigger to send, or `null` if nothing in view earned one. */
    val sighted: Trigger.ThreatSighted? = null,
    /** Every candidate the watch examined and refused. */
    val suppressed: List<ThreatSuppression> = emptyList(),
)

/** The two clock readings the threat rule needs, injected so tests can drive them. */
interface ThreatClock {
    /** The server tick now. */
    fun tick(): Long

    /**
     * Which of the world's days it is: a number that changes once per day and never
     * mid-night, which is what makes [MAX_PIVOTAL_THREAT_CALLS_PER_NIGHT] reset at a
     * boundary rather than after an elapsed interval an agent's own history could
     * shift. Derived from the level's own day time, so `/time set` moves it too.
     */
    fun dayOrdinal(): Long
}

/**
 * What was last reported about one threat, and when.
 *
 * Everything here is a fact the watch compares against to decide whether a fresh look
 * is news. `insidePivotal` is stored rather than recomputed from `distance` so the
 * crossing is a comparison of two answers to the same question, not of a distance
 * against a threshold that could move between ticks.
 */
private data class ThreatMemory(
    val firedTick: Long,
    val visible: Boolean,
    val reachable: Boolean,
    val insidePivotal: Boolean,
)

/**
 * Decides which hostiles in view are worth waking a mind for, and holds the bounds.
 *
 * One instance per server and keyed by agent inside, because both things it remembers
 * are per agent: what each one has already been told about each threat it can see, and
 * how much of its own nightly allowance it has spent. See
 * [MAX_PIVOTAL_THREAT_CALLS_PER_NIGHT] for why the ceiling is not shared.
 *
 * @param clock the tick and day readings, injected.
 * @param debounceTicks how long one threat's sighting holds off the next.
 * @param pivotalDistanceBlocks how close a threat must be to be worth the frontier tier.
 * @param maxPivotalCallsPerNight the nightly ceiling on one agent's pivotal sightings.
 */
class ThreatWatch(
    private val clock: ThreatClock,
    private val debounceTicks: Long = THREAT_DEBOUNCE_TICKS,
    private val pivotalDistanceBlocks: Double = PIVOTAL_THREAT_DISTANCE_BLOCKS,
    private val maxPivotalCallsPerNight: Int = MAX_PIVOTAL_THREAT_CALLS_PER_NIGHT,
) {
    init {
        require(debounceTicks > 0) { "debounceTicks must be positive, got $debounceTicks" }
        require(maxPivotalCallsPerNight > 0) {
            "maxPivotalCallsPerNight must be positive, got $maxPivotalCallsPerNight"
        }
    }

    /** What each agent was last told about each threat it could see. */
    private val remembered = mutableMapOf<AgentId, MutableMap<UUID, ThreatMemory>>()

    /**
     * The day [pivotalCalls] belongs to, so the reset is a boundary and not a timer.
     *
     * One reading for every agent, because it is the world's day rather than anyone's
     * own: the allowance every agent gets is restored by the same dawn.
     */
    private var budgetDay: Long = Long.MIN_VALUE

    /** Pivotal sightings each agent has paid for on [budgetDay]. */
    private val pivotalCalls = mutableMapOf<AgentId, Int>()

    /**
     * How many pivotal sightings [agent] can still afford tonight. Exposed for the record.
     *
     * Reading it rolls the day first, so a caller writing the figure into a record cannot
     * state a count from a night that has already ended.
     */
    fun pivotalCallsRemaining(agent: AgentId): Int {
        rollBudgetToToday()
        return maxPivotalCallsPerNight - spentBy(agent)
    }

    /**
     * Looks at what [agent] can see and says what, if anything, to tell its mind.
     *
     * [candidates] must be nearest-first - the perception bound already orders them that
     * way - because the nearest threat is the one a retreat is about, and the walk stops
     * at the first one that earns a sighting.
     *
     * [reachable] answers whether the engine's own bounded ground search finds a route to
     * a candidate. It is a function rather than a field because it costs a real path
     * search: the watch calls it only for a candidate that has already passed the clock
     * and the change tests, so a threat standing in view for a minute costs one search
     * per [debounceTicks] rather than one per tick, and a suppressed sighting costs none.
     * The caller must answer it through the bounded navigation seam so that spend stays
     * inside the budget that seam keeps.
     *
     * Threats no longer in [candidates] are forgotten, so a body walking away from one
     * does not carry it for the rest of its life - and one that comes back into view is
     * a first sight again, which is right: it re-entered the scene.
     */
    fun consider(
        agent: AgentId,
        candidates: List<ThreatCandidate>,
        reachable: (ThreatCandidate) -> Boolean,
    ): ThreatOutcome {
        val hunters = candidates.filter { huntsVillagers(it.what) }
        val memories = remembered.getOrPut(agent) { mutableMapOf() }
        memories.keys.retainAll(hunters.map { it.subject }.toSet())

        val refused = mutableListOf<ThreatSuppression>()
        for (candidate in hunters) {
            when (val verdict = examine(agent, candidate, memories[candidate.subject], reachable)) {
                is Examination.Refused -> refused += ThreatSuppression(candidate.what, candidate.ref, verdict.reason)
                is Examination.Report -> {
                    memories[candidate.subject] = verdict.memory
                    if (verdict.pivotal) {
                        pivotalCalls[agent] = spentBy(agent) + 1
                    }
                    return ThreatOutcome(sighted = verdict.trigger, suppressed = refused)
                }
            }
        }
        return ThreatOutcome(suppressed = refused)
    }

    /**
     * Drops what is held about the scene [agent] was standing in.
     *
     * For an agent whose body has gone - it has died, which in Loom is for good, or it
     * has been put away. What it deliberately does *not* drop is tonight's spend: that is
     * a fact about the night rather than about the body, so a body put away and spawned
     * again does not come back with a fresh allowance. The spend needs no clearing of its
     * own, because [rollBudgetToToday] discards every agent's count at the day boundary.
     */
    fun forget(agent: AgentId) {
        remembered.remove(agent)
    }

    /** What one candidate amounted to: a sighting to report, or a bound that refused it. */
    private sealed interface Examination {
        data class Report(
            val trigger: Trigger.ThreatSighted,
            val memory: ThreatMemory,
            val pivotal: Boolean,
        ) : Examination

        data class Refused(val reason: ThreatSuppressionReason) : Examination
    }

    private fun examine(
        agent: AgentId,
        candidate: ThreatCandidate,
        last: ThreatMemory?,
        reachable: (ThreatCandidate) -> Boolean,
    ): Examination {
        val now = clock.tick()
        val insidePivotal = candidate.distance <= pivotalDistanceBlocks
        // The free tests first: none of these reads the world.
        if (last != null && !mayLookAgain(last, candidate, insidePivotal, now)) {
            return Examination.Refused(ThreatSuppressionReason.DEBOUNCED)
        }
        // Only now is a path search worth paying for.
        return verdictOn(
            agent,
            candidate,
            last,
            ThreatMemory(
                firedTick = now,
                visible = candidate.visible,
                reachable = reachable(candidate),
                insidePivotal = insidePivotal,
            ),
        )
    }

    /**
     * What to do about a threat whose fresh state is now known.
     *
     * Split from the clock test above because the two ask different questions: that one
     * is about whether looking again is worth a search, and this one about what the look
     * found. It is also where a change in reachability becomes visible, which is why the
     * state comparison happens after the search rather than before it.
     */
    private fun verdictOn(
        agent: AgentId,
        candidate: ThreatCandidate,
        last: ThreatMemory?,
        fresh: ThreatMemory,
    ): Examination {
        // Exactly the conjunction `dev.loom.mind`'s `importanceOf` prices as pivotal, and
        // for the same reason it leaves `reachable` out: the engine refuses a route to a
        // perfectly reachable threat whenever the body is off the ground, so a body being
        // hit would be charged for the cheap tier while in the worst danger it will ever
        // be in. The two conjunctions have to stay the same or the budget stops counting
        // what it pays for.
        val pivotal = fresh.visible && fresh.insidePivotal
        return when {
            // A null `last` never matches, so a first sight always passes here.
            last?.copy(firedTick = fresh.firedTick) == fresh ->
                Examination.Refused(ThreatSuppressionReason.UNCHANGED)
            pivotal && pivotalCallsRemaining(agent) <= 0 ->
                Examination.Refused(ThreatSuppressionReason.OVER_NIGHTLY_CAP)
            else ->
                Examination.Report(
                    trigger =
                        Trigger.ThreatSighted(
                            threat = candidate.what,
                            ref = candidate.ref,
                            distance = candidate.distance,
                            direction = candidate.direction,
                            visible = fresh.visible,
                            reachable = fresh.reachable,
                        ),
                    memory = fresh,
                    pivotal = pivotal,
                )
        }
    }

    /**
     * Whether a threat already reported may be looked at again yet.
     *
     * True once the cadence has elapsed, and true inside it for the two changes that
     * alter what an agent should do without costing anything to notice: a hostile that
     * has stepped out from behind a wall, or into it, and one that has crossed inside
     * the distance its arrival is imminent at. A change in reachability is the third
     * such change and is caught by the state comparison after the search, which is the
     * earliest anything can know it - a route is not a thing that can be checked for
     * free, and probing every tick to find out would be the cost this bound exists to
     * avoid.
     */
    private fun mayLookAgain(
        last: ThreatMemory,
        candidate: ThreatCandidate,
        insidePivotal: Boolean,
        now: Long,
    ): Boolean =
        now - last.firedTick >= debounceTicks ||
            candidate.visible != last.visible ||
            (insidePivotal && !last.insidePivotal)

    /** How many pivotal sightings [agent] has paid for on the day the budget is on. */
    private fun spentBy(agent: AgentId): Int = pivotalCalls[agent] ?: 0

    /**
     * Moves the nightly budget to today if the world's day has turned over.
     *
     * A boundary rather than an elapsed interval: a budget that reset a fixed time after
     * it was first spent would give an agent that met its first zombie at dusk a
     * different allowance from one that met its first at midnight.
     *
     * Every agent's count goes at once, which is both the reset and the only bound on
     * what this map holds: an entry exists for exactly the agents that have spent
     * something since the last dawn, so an agent that has ended costs nothing past it.
     */
    private fun rollBudgetToToday() {
        val today = clock.dayOrdinal()
        if (today != budgetDay) {
            budgetDay = today
            pivotalCalls.clear()
        }
    }
}
