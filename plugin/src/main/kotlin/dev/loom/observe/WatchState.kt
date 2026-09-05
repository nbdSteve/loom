/*
 * What a watcher knows, and the only way it comes to know it.
 *
 * A view of the append-only record and nothing else. Every field here was stated
 * outright by some record, [advanced] is the one function that can change any of them,
 * and it takes a record and returns a new state - so "the operator display is derived
 * from the research record" is a property of the types rather than a claim in a comment.
 *
 * The one reading with rules of its own is the model stamp, which belongs to a single
 * cognition cycle and is therefore the only thing here that is dropped or refused rather
 * than carried forward. Those rules live in `DecisionStamp.kt`, and so does the question
 * they turn on - which cycle the standing line is about. A cognition failure's trigger and
 * activity are refused by the same answer, because a failure names the request it was
 * about and a superseded one is not news about the cycle on screen.
 *
 * The four events whose *name* does not settle what they mean are answered in
 * `TaggedActivity.kt`, which owns the rule that such a fact is read from the record's countable
 * outcome tag and never from its prose.
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
    /**
     * The newest cognition cycle the records have started, as its request id.
     *
     * Held so [stampAfter] can tell an answer to *this* cycle from an answer to a
     * superseded one. Several agents can think in one tick and one call can be slower than
     * the next, so decisions arrive in whatever order the minds answer in, and the newest
     * perception is the only cycle the standing line is about.
     */
    val cycle: String? = null,
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
 * ended in an answer or in silence; see [stampAfter]. A cognition failure naming a cycle
 * the line has stopped asking about is the one record that restates a tag and is not
 * believed: it is news about neither the trigger, the stamp, nor the activity, because it
 * speaks about a question a later perception has already replaced - see [answersTheCycle].
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
        trigger = triggerAfter(state, record),
        stamp = stampAfter(state, record),
        cycle = cycleAfter(state, record),
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
 * The trigger to show after [record], carried forward when it states none.
 *
 * Two records state one, and only one of them can be about a cycle the line has stopped
 * asking about: a perception *starts* the cycle it names, while a cognition failure names the
 * request it was already about. So a failure that does not [answersTheCycle] is left where it
 * is, exactly as its stamp is - it would otherwise relabel the standing line with a
 * superseded cycle's trigger, beside the model and the activity of a cycle that was answered
 * and applied, with nothing until the next perception to correct it.
 */
private fun triggerAfter(
    state: WatchState,
    record: BodyEventRecord,
): String? {
    val superseded = record.event == BodyEvent.COGNITION_UNAVAILABLE && !answersTheCycle(state, record)
    val stated = if (superseded) null else record.trigger
    return stated ?: state.trigger
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
 *
 * A player body speaks about the same fact in its own vocabulary: a managed player that joined
 * is a body in the world, one that quit has none, and one that is in the world again after a
 * terminal death has none either - because it is *dead*, and a revival record is itself proof of
 * that death. The live player the world is holding is the breach rather than the body, and the
 * `ALARM` note is what says so; a reading that called that a body would have to call the agent
 * alive too, and a permadeath is not something the display gets to soften.
 *
 * A refused login is the one witness record that speaks about a body only sometimes, and only the
 * countable tag says when. A refusal because the agent *died* leaves no body by definition. Every
 * other refusal is a fact about a connection attempt and not about the agent - a second body
 * process, or something that took a live agent's username, refused while that agent is standing in
 * the world - so what was already known stands. Read as bodilessness it labelled a connected agent
 * `no body` and froze its reserve trend, with no per-tick record from a player body able to correct
 * it; `refusedLoginActivity` states the other half of the same rule.
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
        BodyEvent.WITNESS_JOINED -> false
        BodyEvent.WITNESS_QUIT -> true
        BodyEvent.WITNESS_DENIED -> record.outcome == EventTag.LOGIN_DEAD_IDENTITY || state.hasNoBody
        BodyEvent.WITNESS_REVIVED -> true
        BodyEvent.PERCEIVED,
        BodyEvent.DECIDED,
        BodyEvent.INTENT_APPLIED,
        BodyEvent.DECISION_DISCARDED,
        BodyEvent.COGNITION_UNAVAILABLE,
        BodyEvent.MOVED,
        BodyEvent.ENERGY_CHANGED,
        BodyEvent.HEALTH_CHANGED,
        BodyEvent.TRAVELLED,
        BodyEvent.RETREATING,
        BodyEvent.WORLD_LOADED,
        BodyEvent.WORLD_HOLD_FAILED,
        // A body that looked around is a body: the record says nothing either way about
        // whether the agent still has one, so what was already known stands.
        BodyEvent.THREAT_SUPPRESSED,
        // A body whose account of itself is wrong is still a body. What is in doubt is the
        // readings, not the embodiment - and the readings are exactly what a display would
        // freeze if this were read as bodilessness.
        BodyEvent.WITNESS_DISPUTED,
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
        BodyEvent.COGNITION_UNAVAILABLE -> unansweredActivity(state, record)
        BodyEvent.DESPAWNED -> AgentActivity.BODILESS
        BodyEvent.DIED -> AgentActivity.DEAD
        BodyEvent.TRAVELLED -> AgentActivity.TRAVELLING
        // A decision is not yet an action: what the agent is doing changes when the body
        // answers it, one record later, and saying otherwise would show every refused
        // intent as briefly carried out.
        BodyEvent.DECIDED -> state.activity
        BodyEvent.CONVERTED -> convertedActivity(state, record.outcome)
        BodyEvent.WITNESS_JOINED -> AgentActivity.EMBODIED
        BodyEvent.WITNESS_QUIT -> AgentActivity.BODILESS
        BodyEvent.WITNESS_DENIED -> refusedLoginActivity(state, record.outcome)
        // A revival record is itself proof of the permadeath: every path that writes one has
        // already established the death - a respawn from a death records it first, a non-death
        // respawn revives only an agent the durable state already calls dead, and a join revives
        // only a dead identity. So this may state the death without having seen the `died` record,
        // which is what a watch attached *after* the fact needs, exactly as `loginDeadIdentity`
        // already does. The note is what states the breach; see `ObserverView.noteFor`.
        BodyEvent.WITNESS_REVIVED -> AgentActivity.DEAD
        // Something happened *to* the agent without changing what it is doing.
        BodyEvent.MOVED,
        BodyEvent.ENERGY_CHANGED,
        BodyEvent.HEALTH_CHANGED,
        BodyEvent.DECISION_DISCARDED,
        // A flight's own activity is set by the applied record that accepted it, one record
        // earlier, and every transition after that is about the *state* of the flight
        // rather than about a new thing the body was told to do. The note says which.
        BodyEvent.RETREATING,
        -> state.activity
        // World-level, and true of every agent at once rather than of this one.
        BodyEvent.WORLD_LOADED,
        BodyEvent.WORLD_HOLD_FAILED,
        -> state.activity
        // Nothing was asked of the body and no mind was woken, so the agent is doing
        // exactly what it was already doing. Saying otherwise would show a suppression as
        // a state change, which is the opposite of what it records.
        BodyEvent.THREAT_SUPPRESSED,
        -> state.activity
        // A cross-check is something Loom did about the evidence rather than something the
        // agent did, so the activity is unchanged and the note carries the caveat.
        BodyEvent.WITNESS_DISPUTED,
        -> state.activity
    }
