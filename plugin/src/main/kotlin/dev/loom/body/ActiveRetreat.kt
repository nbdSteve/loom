/*
 * A retreat that lasts longer than one hop.
 *
 * `Retreat.kt` answers "is there anywhere worth running to, and where": one decision, one
 * bounded hop, done. This file answers the question an in-world death asked next. A body
 * given a good retreat target walked it in about a second, arrived, and stood still until
 * its next goal review while the zombie it had just gained two blocks on closed ten and
 * killed it. The hop was correct; treating the hop as the whole of running away was not.
 *
 * So one accepted `Flee` becomes a state: an *active retreat* against one named threat,
 * which keeps asking for another bounded hop each time the last route ends, and ends only
 * when the threat is gone, the agent is out of its reach, something else supersedes it, or
 * the ground genuinely offers nothing.
 *
 * Engine-free on purpose, like every other policy in this package: what a tick may ask of
 * a retreat is decided here, where a unit test can read it without a server, and the
 * adapter below the port supplies the three engine facts it needs and makes the two engine
 * calls a hop is made of. Nothing here widens a bound - every hop is the same
 * `chooseRetreat` sample, the same `PATH_REQUEST_REACH_BLOCKS`, the same per-tick search
 * token, the same hazard prices - and nothing here reaches cognition: a continuation costs
 * a path search and never a model call.
 */
package dev.loom.body

import java.util.UUID

/**
 * How long an active retreat waits before asking the ground again, in ticks.
 *
 * Half a second. A continuation costs a `chooseRetreat` sample and a path search out of
 * the per-tick bucket every walk pays into, so a cornered agent - or one that keeps being
 * knocked off the ground - must not be able to spend one of those every tick for as long
 * as something is hunting it. Short enough that the gap it re-opens is measured in a
 * fraction of a block of the threat's approach rather than in the four seconds of standing
 * still that killed an agent in world.
 *
 * It bounds the *retry*, not the route: a body walking a live route is not asked for
 * anything at all, so on open ground this cadence never fires.
 */
const val RETREAT_RETRY_CADENCE_TICKS: Long = 10L

/**
 * Where the threat an active retreat is against stands now.
 *
 * Three facts rather than a distance, because the three end the retreat differently and a
 * run has to be able to tell them apart: [GONE] is the threat dying, despawning or
 * crossing into another dimension, [OUT_OF_RANGE] is the agent having actually got away,
 * and only [IN_RANGE] is worth another hop. The range is the perception radius, because
 * that is the whole of what a body can honestly say anything about.
 */
enum class ThreatStanding {
    /** Within `PERCEPTION_RADIUS_BLOCKS` of the body, in the same world, and alive. */
    IN_RANGE,

    /** Still there, and further off than the body can perceive. The agent got away. */
    OUT_OF_RANGE,

    /** Dead, despawned, or in another dimension: there is nothing left to run from. */
    GONE,
}

/** Why an active retreat ended, in words a record can be counted by. */
enum class RetreatEnd {
    /** The threat died, despawned, or left the world the body is in. */
    THREAT_GONE,

    /** The agent is beyond the threat's reach as far as the body can perceive. */
    SAFE,

    /** A later intent that was not a flight took the body over. */
    SUPERSEDED,

    /**
     * The ground refused, with the body on it and nothing in the way of an answer.
     *
     * Kept distinct from the three above because it is the one ending that is a *failure*
     * rather than a resolution: the agent is still in range of something hunting it and
     * has been left standing. A retreat that silently retried a genuine refusal for ever
     * would hide exactly that, which is why a grounded refusal clears the state rather
     * than deferring it. The refusal itself rides the record beside this.
     */
    FAILED,
}

/** What the active retreat asks of the tick that is running. */
sealed interface RetreatStep {
    /**
     * Nothing to do: no active retreat, or a route still running, or the cadence not yet
     * elapsed, or the body not yet back on the ground.
     *
     * The common case by a long way, and it costs nothing: this is what keeps a durative
     * retreat from being a search per tick.
     */
    data object Hold : RetreatStep

    /** Ask the ground for another bounded hop away from [threat]. */
    data class Route(
        val threat: UUID,
    ) : RetreatStep

    /** The retreat is over, because of [why]. Journal it once and forget it. */
    data class Ended(
        val why: RetreatEnd,
    ) : RetreatStep
}

/**
 * What one attempt to route a retreat came to, whether it was the first or a continuation.
 *
 * Its own type rather than an [IntentOutcome], because the state machine needs to know
 * which of them keeps the retreat alive and an outcome only says whether the body acted.
 * [outcomeOf] turns one into the other for the caller that has to answer the port.
 */
sealed interface RetreatAttempt {
    /** A route away from [threat] is on the navigator; [effect] is what it will do. */
    data class Routed(
        val threat: UUID,
        val effect: String,
    ) : RetreatAttempt

    /**
     * The body was off the ground, so nothing was asked of the navigator.
     *
     * Not a refusal, and this is the distinction the whole variant exists for. A ground
     * navigator will not search while its body is airborne, so `createPath` answers a
     * perfectly reachable target with no route at all - and a body being hit is airborne.
     * An in-world run spent one of a night's eight pivotal model calls on a flight that
     * was refused for that reason and never retried, while the agent was being killed.
     *
     * So the retreat is *retained* rather than refused: the prior route is stopped, the
     * agent is told its flight was accepted and is waiting for the ground, and the next
     * attempt comes from [RETREAT_RETRY_CADENCE_TICKS] rather than from another decision.
     */
    data class Grounding(
        val threat: UUID,
    ) : RetreatAttempt

    /**
     * The attempt is refused, for [refusal], and the retreat is over.
     *
     * Every one of the five refusals is a fact about the run rather than something to
     * retry: a handle that named nothing, a threat that is not there, ground that offered
     * nowhere, and ground that offered only spots no further away.
     */
    data class Refused(
        val refusal: RetreatRefusal,
    ) : RetreatAttempt

    /**
     * The tick's path-search budget was already spent, so no search was made.
     *
     * Retained for the same reason [Grounding] is, and the reason is worth stating because
     * the two look nothing alike from the outside: neither is an answer about the threat or
     * about the ground. Loom grants a fixed number of searches per tick across the whole
     * server, shared between mind-driven walks and every flight, so a tick with more agents
     * deciding than the budget serves refuses the excess - and the target they named is
     * unchanged and still reachable on the very next tick.
     *
     * Ending the flight there is the death this file exists to prevent, arriving through
     * Loom's own bookkeeping rather than through the world: the agent would stand still with
     * a hostile on it until its next goal review, up to a whole cadence later. So the prior
     * route is stopped like any refusal, the agent is told its flight was accepted, and the
     * next attempt comes from [RETREAT_RETRY_CADENCE_TICKS].
     */
    data class PendingBudget(
        val threat: UUID,
    ) : RetreatAttempt

    /**
     * A target was chosen and the routing seam would not take it, for [reason].
     *
     * The refusals that are not about the threat and are not transient either: a target past
     * the reach, an engine that offered no route. Kept apart from
     * [Refused] because those five are answers about a *threat* and these are answers about
     * a *route*, and it ends the retreat for the same reason they do - the body has already
     * been stopped, and retrying a bound that refused is how a cornered agent would search
     * for ever.
     */
    data class Unrouted(
        val reason: String,
    ) : RetreatAttempt
}

/**
 * How the port answers an [attempt].
 *
 * [RetreatAttempt.Grounding] is deliberately an acceptance: the decision *was* carried
 * out, and what it is waiting for is the ground rather than the mind. Reported as a
 * rejection it would read afterwards as a flight that never happened, which is what the
 * in-world record said about an agent that was in fact fleeing.
 */
fun outcomeOf(attempt: RetreatAttempt): IntentOutcome =
    when (attempt) {
        is RetreatAttempt.Routed -> IntentOutcome.Accepted(attempt.effect)
        is RetreatAttempt.Grounding ->
            IntentOutcome.Accepted(
                "retreating as soon as it is back on the ground; " +
                    "a ground route cannot be searched for mid-air",
            )
        is RetreatAttempt.PendingBudget ->
            IntentOutcome.Accepted(
                "retreating as soon as there is a path search to spend on it; " +
                    "this tick's search budget was already gone",
            )
        is RetreatAttempt.Refused -> IntentOutcome.Rejected(describe(attempt.refusal))
        is RetreatAttempt.Unrouted -> IntentOutcome.Rejected(attempt.reason)
    }

/** What an ended retreat says in a record. */
fun describe(end: RetreatEnd): String =
    when (end) {
        RetreatEnd.THREAT_GONE -> "the threat is gone; no longer retreating"
        RetreatEnd.SAFE -> "out of range of the threat; no longer retreating"
        RetreatEnd.SUPERSEDED -> "a later intent replaced the retreat"
        RetreatEnd.FAILED -> "the retreat could not be continued"
    }

/**
 * The countable tag an ended retreat is filed under.
 *
 * Beside [describe] rather than in `EventTag.kt` because the two answer the same question
 * for the same reader - what a run can count, and what a person reads - and an ending that
 * gained a tag without a sentence, or the reverse, is how a transition becomes uncountable.
 */
fun tagOf(end: RetreatEnd): String =
    when (end) {
        RetreatEnd.THREAT_GONE -> EventTag.RETREAT_THREAT_GONE
        RetreatEnd.SAFE -> EventTag.RETREAT_SAFE
        RetreatEnd.SUPERSEDED -> EventTag.RETREAT_SUPERSEDED
        RetreatEnd.FAILED -> EventTag.RETREAT_FAILED
    }

/**
 * The countable tag one attempt is filed under, given whether it [continued] a retreat.
 *
 * Starting and continuing are the same three engine answers and different facts about the
 * run: a run whose flights are all first hops never chained, and one that is all
 * continuations is being hunted. Both are the same event, so only the tag separates them.
 *
 * A refusal is deferred to [tagOf] over [RetreatRefusal] rather than answered here, because
 * the five refusals are not one fact: only two of them are the ground refusing an agent that
 * is still in range of something, and [EventTag.RETREAT_FAILED] is the count that says so.
 * [RetreatAttempt.Unrouted] is such a failure - the body is still where it was, with the
 * threat still on it - so it keeps that tag.
 */
fun tagOf(
    attempt: RetreatAttempt,
    continued: Boolean,
): String =
    when (attempt) {
        is RetreatAttempt.Routed -> if (continued) EventTag.RETREAT_CONTINUED else EventTag.RETREAT_STARTED
        is RetreatAttempt.Grounding -> EventTag.RETREAT_PENDING_GROUND
        is RetreatAttempt.PendingBudget -> EventTag.RETREAT_PENDING_BUDGET
        is RetreatAttempt.Refused -> tagOf(attempt.refusal)
        is RetreatAttempt.Unrouted -> EventTag.RETREAT_FAILED
    }

/** What one attempt says in a record, given whether it [continued] a retreat. */
fun describe(
    attempt: RetreatAttempt,
    continued: Boolean,
): String {
    val verb = if (continued) "still running" else "running"
    return when (attempt) {
        is RetreatAttempt.Routed -> "$verb: ${attempt.effect}"
        is RetreatAttempt.Grounding -> "$verb as soon as it is back on the ground"
        is RetreatAttempt.PendingBudget -> "$verb as soon as a path search can be spared"
        is RetreatAttempt.Refused -> "could not run: ${describe(attempt.refusal)}"
        is RetreatAttempt.Unrouted -> "could not run: ${attempt.reason}"
    }
}

/**
 * The active retreats, one per agent at most.
 *
 * Held for the agent rather than for its body, like everything else per-agent in this
 * package: a body can be replaced by a dimension transit, and a retreat is the agent's.
 * Not thread-safe, and it does not need to be - every caller is on the server main thread,
 * as [BodyPort] requires.
 *
 * The identity stored is the threat's own [UUID] and never the request-scoped ref the mind
 * named it by. A handle means something only inside the request that minted it, so a
 * retreat that kept one would be asking, a hundred ticks later, about a window that has
 * closed: the resolution happens once, at the decision, and what survives it is the
 * creature.
 */
class ActiveRetreats {
    private val active = mutableMapOf<AgentId, Retreat>()

    /**
     * Records what [attempt] did for [agentId] on [tick], and answers whether a retreat is
     * now running.
     *
     * The one way the state changes on an attempt, used by the first hop and by every
     * continuation alike, so a continuation cannot accidentally be exempt from a rule the
     * first hop obeys. A [RetreatAttempt.Refused] clears the retreat rather than deferring
     * it: see [RetreatEnd.FAILED].
     *
     * A later flight simply overwrites the earlier one, which is what "a later Flee
     * replaces the active threat" means - an agent has one body and can only run from one
     * thing at a time, and the newest decision is the one made on the newest view of the
     * world.
     */
    fun attempted(
        agentId: AgentId,
        attempt: RetreatAttempt,
        tick: Long,
    ): Boolean {
        val threat = threatKept(attempt)
        if (threat == null) {
            active.remove(agentId)
            return false
        }
        active[agentId] = Retreat(threat, tick)
        return true
    }

    /** What [attempt] leaves a retreat against, or `null` if it leaves none. */
    private fun threatKept(attempt: RetreatAttempt): UUID? =
        when (attempt) {
            is RetreatAttempt.Routed -> attempt.threat
            is RetreatAttempt.Grounding -> attempt.threat
            is RetreatAttempt.PendingBudget -> attempt.threat
            // Both of these have already stopped the body, and both are answers rather than
            // conditions: retrying either would be a search per cadence against ground that
            // has said no. See RetreatEnd.FAILED.
            is RetreatAttempt.Refused -> null
            is RetreatAttempt.Unrouted -> null
        }

    /**
     * Ends [agentId]'s retreat because something else is driving the body now, and answers
     * whether there was one to end.
     *
     * The answer is what keeps the record honest: a supersession is journaled once, on the
     * intent that actually took a retreat over, and every other intent an agent is given
     * says nothing about retreating.
     */
    fun superseded(agentId: AgentId): Boolean = active.remove(agentId) != null

    /**
     * Forgets [agentId] entirely, for a body that has gone and an agent that has ended.
     *
     * Called on despawn and on death, because per-agent state must not outlive the agent it
     * is about, and a put-away body's retreat must not be continued by its successor.
     */
    fun forget(agentId: AgentId) {
        active.remove(agentId)
    }

    /** The creature [agentId] is running from, or `null` if it is not retreating. */
    fun threatOf(agentId: AgentId): UUID? = active[agentId]?.threat

    /**
     * What [agentId]'s retreat asks of [tick], given where the threat stands and what the
     * body is doing.
     *
     * The order of the tests is the design. The threat's own standing comes first, so an
     * agent that has got away or whose threat has died stops retreating on the tick that
     * becomes true rather than at the end of a route it no longer needs. A live route comes
     * next, because a body already walking away needs nothing and asking would spend a
     * search token on a route it would replace with a worse one. Then the cadence, which is
     * what bounds a cornered agent. And the ground last, because it is the one condition
     * that is nobody's fault and resolves itself: a body mid-knockback is asked again at
     * the next cadence rather than being refused.
     *
     * @param routeRunning whether the navigator still has a route to walk.
     * @param grounded whether the body is on the ground, and so whether a ground navigator
     *   could search at all.
     */
    fun next(
        agentId: AgentId,
        tick: Long,
        standing: ThreatStanding,
        routeRunning: Boolean,
        grounded: Boolean,
    ): RetreatStep {
        val retreat = active[agentId] ?: return RetreatStep.Hold
        // Removed as it is read, so an ending is answered - and journaled - exactly once.
        val ending = endingFor(standing)?.also { active.remove(agentId) }
        return when {
            ending != null -> RetreatStep.Ended(ending)
            worthAskingAgain(retreat, tick, routeRunning, grounded) -> RetreatStep.Route(retreat.threat)
            else -> RetreatStep.Hold
        }
    }

    /** What [standing] ends a retreat as, or `null` if it does not end one. */
    private fun endingFor(standing: ThreatStanding): RetreatEnd? =
        when (standing) {
            ThreatStanding.GONE -> RetreatEnd.THREAT_GONE
            ThreatStanding.OUT_OF_RANGE -> RetreatEnd.SAFE
            ThreatStanding.IN_RANGE -> null
        }

    /**
     * Whether [retreat] should ask the ground for another hop on [tick].
     *
     * All three conditions are cost. A body still walking needs nothing and asking would
     * spend a search token replacing a good route with a shorter one; the cadence is what
     * bounds a cornered agent; and a body off the ground cannot be searched for at all, so
     * asking would buy a certain refusal.
     *
     * The airborne case records nothing as well as asking nothing: the attempt that put
     * this retreat into its waiting state already said so once, and a record per cadence
     * would be the same sentence twenty times a second for as long as something kept
     * knocking the body about.
     */
    private fun worthAskingAgain(
        retreat: Retreat,
        tick: Long,
        routeRunning: Boolean,
        grounded: Boolean,
    ): Boolean = !routeRunning && grounded && tick - retreat.attemptedAt >= RETREAT_RETRY_CADENCE_TICKS

    /**
     * One agent's retreat: what it is running from, and when it last asked the ground.
     *
     * [attemptedAt] is the last *attempt* rather than the last route, so a hop that was
     * held back for the ground is bounded by the same cadence as one that was refused a
     * target - the cost being bounded is asking, and both of those asked.
     */
    private data class Retreat(
        val threat: UUID,
        val attemptedAt: Long,
    )
}
