/*
 * Which mind answered which cognition cycle, and the one reading that is forgotten.
 *
 * Its own file beside `WatchState.kt` because it is the exception to that file's rule.
 * Every other thing a watcher knows is carried forward until a record restates it - an
 * agent does not stop having a reserve, a body, or an activity because the next record is
 * a step. A model stamp does: it is an answer to one question, and the question expires.
 * So this is the only part of a watcher's knowledge with rules about *dropping* a reading
 * and about *refusing* one, and both are answerable only by knowing which cycle the
 * standing line is currently about.
 *
 * Two failures live here and neither is visible from the type. A stamp held past the end
 * of its cycle sits beside the *current* trigger reading "this trigger was answered by
 * this model", about a cycle nothing has answered yet. A stamp taken from a cycle that is
 * already over replaces a correct answer with a superseded one, which is worse: it is
 * wrong at the moment it is shown and no later record restates it.
 *
 * Which cycle the line is about - `answersTheCycle` - is the one question `WatchState.kt`
 * asks of this file. A cognition failure names the request it was about, so a failure from
 * a superseded cycle is no more news about the standing line's trigger or activity than
 * about its stamp, and two files answering that question differently is how one of them
 * would go on reporting `mind did not answer` about a cycle that was answered.
 *
 * Like `WatchState.kt`, nothing here can reach a body, a mind, or a world.
 */
package dev.loom.observe

import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventRecord
import dev.loom.contract.Provenance

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
 * The stamp to show after [record]: the one it carries, the one already known, or none.
 *
 * Carried forward like every other reading, except where carrying it would answer a
 * question nothing has answered. A stamp belongs to one cycle, so it is dropped by any
 * record that ends that cycle without an answer or starts another one - see
 * [forgetsTheStamp].
 *
 * For the same reason a stamp is only *taken* from a record that answers the cycle the line
 * is about - see [answersTheCycle]. Decisions arrive in whatever order the minds answer in,
 * so an older cycle answered late would otherwise reattach its model to the newer cycle and
 * stay there: an in-world run had a boss bar credit a goal review to the model of a review
 * that had already been superseded, and never correct itself.
 */
internal fun stampAfter(
    state: WatchState,
    record: BodyEventRecord,
): DecisionStamp? {
    record.provenance?.let { provenance ->
        return if (answersTheCycle(state, record)) DecisionStamp(record.requestId, provenance) else state.stamp
    }
    return if (forgetsTheStamp(state, record)) null else state.stamp
}

/**
 * The cycle the standing line is about once [record] has landed.
 *
 * A perception starts one, and nothing else does: a decision, an action and a failure all
 * belong to a cycle some perception already started, and are the reason the cycle has to be
 * remembered rather than read off whichever record arrived last.
 */
internal fun cycleAfter(
    state: WatchState,
    record: BodyEventRecord,
): String? = if (record.event == BodyEvent.PERCEIVED) record.requestId else state.cycle

/**
 * Whether [record] speaks about the cycle the display is showing.
 *
 * Asked of the records that belong to a cycle some perception already started: a decision
 * carrying a stamp, and a failure saying there is none. A watcher that has seen no
 * perception yet has no cycle to contradict - it may have attached between one and its
 * answer - so such a record is taken at its word. Once a perception has named a cycle, a
 * record from any other one speaks about a question the line has stopped asking, and is left
 * where it is rather than shown as this cycle's.
 *
 * The cycle-less case therefore fails open, and the stamp it settles on can be a superseded
 * cycle's until a perception names a cycle: with several answers still in flight, each
 * arriving `decided` overwrites the last, so a slower older one wins. Nothing here can order
 * them - a request id says nothing about when its perception was taken, and a `decided`
 * record's tick is when the answer landed, which is the arrival order that is already wrong -
 * and preferring the first stamp seen would only invert which arrival loses.
 *
 * Shared with `WatchState.kt`, which asks it of a cognition failure for the trigger and the
 * activity as well: a superseded cycle's failure carries the older request's own trigger,
 * and would otherwise relabel a cycle that a model did answer.
 */
internal fun answersTheCycle(
    state: WatchState,
    record: BodyEventRecord,
): Boolean = state.cycle == null || record.requestId == state.cycle

/**
 * Whether [record] leaves the last stamp with nothing to say.
 *
 * Two records do. A cognition failure states outright that *its own* cycle has no model, so
 * it drops the stamp only when that is the cycle the line is about - see [answersTheCycle].
 * Two cycles overlap for one agent whenever a `/loombody think` lands during a goal review
 * still in flight, and the older call then times out after the newer one has been answered
 * and applied: dropping the stamp there took the model that did answer off the line, with no
 * later record to restate it until the next perception.
 *
 * The other is a perception that names a *different* request, which has started a cycle no
 * model has answered yet - the whole of the wait an operator watches during a silent mind,
 * which would otherwise show the previous cycle's model for all of it. A perception restating
 * the request the stamp came from is the same cycle, so it keeps it.
 */
private fun forgetsTheStamp(
    state: WatchState,
    record: BodyEventRecord,
): Boolean =
    when (record.event) {
        BodyEvent.COGNITION_UNAVAILABLE -> answersTheCycle(state, record)
        BodyEvent.PERCEIVED -> record.requestId != state.stamp?.requestId
        else -> false
    }
