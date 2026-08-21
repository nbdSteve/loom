/*
 * What a watcher knows, and the only way it comes to know it.
 *
 * A view of the append-only record and nothing else. Every field here was stated
 * outright by some record, [advanced] is the one function that can change any of them,
 * and it takes a record and returns a new state - so "the operator display is derived
 * from the research record" is a property of the types rather than a claim in a comment.
 *
 * Nothing in this file can reach a body, a mind, or a world. That is what makes
 * attaching an observer incapable of changing an agent's energy, timing, decisions, or
 * routing: there is no call it could make to do so.
 */
package dev.loom.observe

import dev.loom.body.AgentVitals
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventRecord
import dev.loom.body.EnergyTrend
import dev.loom.body.EventTag
import dev.loom.body.trendOf
import dev.loom.contract.Provenance

/**
 * How long a moving agent keeps being called moving after its last step.
 *
 * A walking body records a step every tick, so silence means it has arrived. Without
 * this the display would call an agent "moving" until its next goal review - up to a
 * whole cadence of standing perfectly still under a label saying otherwise.
 */
const val MOTION_IDLE_TICKS: Long = 20L

/** The activities that a body has to keep taking steps to still be in. */
private val IN_MOTION = setOf(AgentActivity.MOVING, AgentActivity.FLEEING)

/**
 * Which mind answered one cognition cycle, and which cycle that was.
 *
 * The two travel together because a stamp only means anything about the request it
 * answered. Held apart, the stamp of a finished cycle can be shown beside the trigger of
 * the next one, which reads as "this trigger was answered by this model" about a cycle
 * nothing has answered yet - and the two would differ the day a tier other than routine
 * is reached, since the router gives each tier its own model and prompt version.
 */
data class DecisionStamp(
    /** The perception this stamp answered, as the record's own request id. */
    val requestId: String?,
    /** The mind, tier, and prompt version that answered it. */
    val provenance: Provenance,
)

/**
 * Everything a watcher currently knows about one agent.
 *
 * Small on purpose. It is not a second store of agent state: it holds no history, it is
 * discarded when the watch ends, and nothing in Loom outside this package reads it.
 */
data class WatchState(
    /** The agent being watched. */
    val agentId: String,
    /** The last energy-reserve reading recorded for it, or `null` if none has landed yet. */
    val vitals: AgentVitals? = null,
    /** The last health reading recorded for it, or `null` if none has landed yet. */
    val health: AgentVitals? = null,
    /** What the reserve did between the last two readings. */
    val trend: EnergyTrend = EnergyTrend.STEADY,
    /** What the agent is visibly doing. */
    val activity: AgentActivity = AgentActivity.UNSEEN,
    /** Which event prompted the last cognition, as a wire tag. */
    val trigger: String? = null,
    /** The last decision's stamp, with the cycle it belongs to. */
    val stamp: DecisionStamp? = null,
    /** Whether the records so far leave this agent with no body in the world. */
    val hasNoBody: Boolean = false,
    /** The tick of the last step the body took, for [decayed]. */
    val lastMotionTick: Long? = null,
) {
    /**
     * True once this agent has died.
     *
     * Derived from the activity rather than stored beside it, so the two cannot
     * disagree. Loom is permadeath: a state that reaches [AgentActivity.DEAD] never
     * leaves it, which is why the display freezes on a tombstone rather than clearing.
     */
    val isFinal: Boolean get() = activity == AgentActivity.DEAD

    /**
     * True once nothing is spending this agent's reserve any more.
     *
     * A reserve is charged to a body on the tick - `Metabolism.spendUpTo` is handed the
     * live bodies and nothing else - so an agent with no body has a reserve that is frozen
     * rather than falling, and no further reading of it can arrive. Its own predicate
     * rather than a widening of [isFinal], because a bodiless agent is not final: it can be
     * embodied again, while a dead one never is.
     *
     * Read from [hasNoBody] and not from [activity], because the activity moves on while
     * the body stays gone: a request still in flight when the body was put away fails a
     * moment later, which is an `UNANSWERED` agent that is nonetheless spending nothing.
     */
    val isReserveFrozen: Boolean get() = isFinal || hasNoBody

    /**
     * Which mind answered, for a display that has no business with request ids.
     *
     * Derived rather than stored beside [stamp] for the same reason [isFinal] is: a stamp
     * that could be read without the cycle it belongs to is a stamp that can outlive it.
     */
    val provenance: Provenance? get() = stamp?.provenance
}

/**
 * The state after [record].
 *
 * Readings, tags, and stamps are carried forward when a record does not restate them -
 * an agent's model does not stop being known because the next record is a step - but
 * they are never invented: a watcher that has been told nothing shows nothing. The one
 * thing that is actively forgotten is the model stamp of a cycle that is over, whether it
 * ended in an answer or in silence; see [stampAfter].
 */
fun advanced(
    state: WatchState,
    record: BodyEventRecord,
): WatchState =
    state.copy(
        vitals = record.vitals ?: state.vitals,
        health = record.health ?: state.health,
        trend = trendAfter(state, record.vitals),
        activity = if (state.isFinal) state.activity else activityAfter(state, record),
        trigger = record.trigger ?: state.trigger,
        stamp = stampAfter(state, record),
        hasNoBody = bodilessAfter(state, record),
        lastMotionTick = if (isMotion(record)) record.tick else state.lastMotionTick,
    )

/**
 * The state after [tick] has passed with no further records.
 *
 * The one change to a watcher's knowledge that time alone can cause. Everything else
 * waits for a record, because everything else is something that happened.
 */
fun decayed(
    state: WatchState,
    tick: Long,
): WatchState {
    val since = state.lastMotionTick
    val hasSettled = state.activity in IN_MOTION && since != null && tick - since >= MOTION_IDLE_TICKS
    return if (hasSettled) state.copy(activity = AgentActivity.EMBODIED) else state
}

/**
 * The trend after [reading], which is news only when the number actually moved.
 *
 * A reading identical to the one already held is a restatement rather than a second
 * reading, and two records state the same reserve on one tick by design: `energyChanged`
 * and `healthChanged` each carry both vitals, so a wound landing on the tick the reserve
 * steps writes that reserve twice. Read as a fresh sample, the repeat answered "steady" and
 * took the trend word off a live, still-draining agent - and the absence of that word is
 * the signal that a reserve has just been rested or has nobody spending it.
 */
private fun trendAfter(
    state: WatchState,
    reading: AgentVitals?,
): EnergyTrend {
    val previous = state.vitals?.energy
    return if (reading == null || reading.energy == previous) state.trend else trendOf(previous, reading.energy)
}

/**
 * The stamp to show after [record]: the one it carries, the one already known, or none.
 *
 * Carried forward like every other reading, except where carrying it would answer a
 * question nothing has answered. A stamp belongs to one cycle, so it is dropped by any
 * record that ends that cycle without an answer or starts another one - see
 * [forgetsTheStamp]. Held past either, it sits beside the *current* trigger reading "this
 * trigger was answered by this model", which is the one thing that must never be invented.
 */
private fun stampAfter(
    state: WatchState,
    record: BodyEventRecord,
): DecisionStamp? {
    record.provenance?.let { return DecisionStamp(record.requestId, it) }
    return if (forgetsTheStamp(state, record)) null else state.stamp
}

/**
 * Whether [record] leaves the last stamp with nothing to say.
 *
 * Two records do. A cognition failure states outright that this cycle has no model, and a
 * perception that names a *different* request has started a cycle no model has answered
 * yet - which is the whole of the wait an operator watches during a silent mind, and which
 * would otherwise show the previous cycle's model for all of it. A perception restating the
 * request the stamp came from is the same cycle, so it keeps it.
 */
private fun forgetsTheStamp(
    state: WatchState,
    record: BodyEventRecord,
): Boolean =
    when (record.event) {
        BodyEvent.COGNITION_UNAVAILABLE -> true
        BodyEvent.PERCEIVED -> record.requestId != state.stamp?.requestId
        else -> false
    }

/**
 * Whether the agent has no body in the world once [record] has landed.
 *
 * Held as a fact of its own rather than read back off the activity, because the activity is
 * about what the agent is *doing* and moves on while the body stays gone: a request that was
 * in flight when the body was put away fails a moment later and sets `UNANSWERED`, which
 * would put the trend word back on a reserve nothing is spending, permanently, since no
 * further record about a bodiless agent can arrive to correct it.
 *
 * Four records speak about a body and each states rather than carries: a spawn gives one, a
 * despawn puts one away, a refusal that is not `alreadyEmbodied` happens to an agent that has
 * none, and a conversion that took the body leaves none. A death leaves none either, though
 * [WatchState.isFinal] is what a tombstone is read from. Everything else says nothing about
 * it, and is grouped below rather than defaulted so a new body event has to answer this
 * question as well as the one [activityAfter] asks.
 */
private fun bodilessAfter(
    state: WatchState,
    record: BodyEventRecord,
): Boolean =
    when (record.event) {
        BodyEvent.SPAWNED -> false
        BodyEvent.DESPAWNED -> true
        BodyEvent.DIED -> true
        BodyEvent.SPAWN_REJECTED -> record.outcome != EventTag.ALREADY_EMBODIED
        BodyEvent.CONVERTED -> record.outcome == EventTag.BODY_TAKEN
        BodyEvent.PERCEIVED,
        BodyEvent.DECIDED,
        BodyEvent.INTENT_APPLIED,
        BodyEvent.DECISION_DISCARDED,
        BodyEvent.COGNITION_UNAVAILABLE,
        BodyEvent.MOVED,
        BodyEvent.ENERGY_CHANGED,
        BodyEvent.HEALTH_CHANGED,
        BodyEvent.TRAVELLED,
        BodyEvent.WORLD_LOADED,
        BodyEvent.WORLD_HOLD_FAILED,
        -> state.hasNoBody
    }

/**
 * Whether [record] is a body taking a step.
 *
 * A recorded step is one, and so is the moment a movement intent was accepted - which
 * is what starts the clock, so an intent whose first step never arrives decays like any
 * other stalled movement instead of showing as motion forever.
 */
private fun isMotion(record: BodyEventRecord): Boolean =
    when (record.event) {
        BodyEvent.MOVED -> true
        BodyEvent.INTENT_APPLIED ->
            record.outcome == EventTag.ACCEPTED && record.intent in setOf(EventTag.MOVE_TO, EventTag.FLEE)
        else -> false
    }

/**
 * The activity [record] puts the agent in, or the one it was already in.
 *
 * Total over [BodyEvent] with no default branch, so a new body event has to say what an
 * operator should now believe the agent is doing - including "nothing new", which several
 * events honestly mean.
 */
@Suppress("CyclomaticComplexMethod")
private fun activityAfter(
    state: WatchState,
    record: BodyEventRecord,
): AgentActivity =
    when (record.event) {
        BodyEvent.SPAWNED -> AgentActivity.EMBODIED
        BodyEvent.SPAWN_REJECTED -> refusedActivity(state, record.outcome)
        BodyEvent.PERCEIVED -> AgentActivity.THINKING
        BodyEvent.INTENT_APPLIED -> activityOf(record.intent, record.outcome)
        BodyEvent.COGNITION_UNAVAILABLE -> AgentActivity.UNANSWERED
        BodyEvent.DESPAWNED -> AgentActivity.BODILESS
        BodyEvent.DIED -> AgentActivity.DEAD
        BodyEvent.TRAVELLED -> AgentActivity.TRAVELLING
        // A decision is not yet an action: what the agent is doing changes when the body
        // answers it, one record later, and saying otherwise would show every refused
        // intent as briefly carried out.
        BodyEvent.DECIDED -> state.activity
        BodyEvent.CONVERTED -> convertedActivity(state, record.outcome)
        // Something happened *to* the agent without changing what it is doing.
        BodyEvent.MOVED,
        BodyEvent.ENERGY_CHANGED,
        BodyEvent.HEALTH_CHANGED,
        BodyEvent.DECISION_DISCARDED,
        -> state.activity
        // World-level, and true of every agent at once rather than of this one.
        BodyEvent.WORLD_LOADED,
        BodyEvent.WORLD_HOLD_FAILED,
        -> state.activity
    }

/**
 * What a refused spawn leaves the agent in.
 *
 * Most refusals happen to an agent that has no body, and saying so is most of the point
 * of the record. One is the opposite: a spawn refused because the agent *already* has a
 * body says the body is there, so an operator who typed the command twice must not be
 * told that the live body in front of them is gone. Read from the countable outcome the
 * refusal carries and never from its prose, which this package may not look at.
 */
private fun refusedActivity(
    state: WatchState,
    outcome: String?,
): AgentActivity = if (outcome == EventTag.ALREADY_EMBODIED) state.activity else AgentActivity.BODILESS

/**
 * What a conversion leaves the agent in.
 *
 * One event name, two opposite facts. Loom refuses a conversion, so the ordinary case is
 * a body that never changed and an agent still doing whatever it was. A conversion that
 * got past the refusal took the body with it and nothing re-embodies the agent, so the
 * display has to stop claiming it is standing anywhere.
 */
private fun convertedActivity(
    state: WatchState,
    outcome: String?,
): AgentActivity = if (outcome == EventTag.BODY_TAKEN) AgentActivity.BODILESS else state.activity
