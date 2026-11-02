/*
 * Which decisions may have carried the agent away from where a held step left it: the rule a
 * shelter's cover credit is withheld by once another decision overtakes the step.
 */
package dev.loom.mind

import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence

/**
 * The actions that may carry the body elsewhere: a walk, a flight, an approach to collect or strike,
 * a dig of the block under the feet, and a bed entered.
 */
internal val MOVING_ACTIONS =
    setOf(EventTag.MOVE_TO, EventTag.FLEE, EventTag.COLLECT, EventTag.ATTACK, EventTag.DIG, EventTag.SLEEP)

/** The endings of a decision the body never began: refused before it, stale, or behind other work. */
private val NEVER_MOVED =
    GoalEvidence.REFUSING + setOf(EventTag.SURVIVAL_REQUIRED, EventTag.RETREAT_STALE, EventTag.CONTROL_NOT_READY)

/** The endings of a dig after which the block under the feet may be gone: broken, by the body or another. */
private val DIG_REMOVED = setOf(EventTag.ACCEPTED, EventTag.DIG_BLOCK_GONE, EventTag.DIG_CONTEST_GONE)

/** The endings of a bed request after which the body may have lain in the bed and left it. */
private val SLEEP_ENTERED =
    setOf(
        EventTag.ACCEPTED,
        EventTag.SLEEP_TIMED_OUT,
        EventTag.SLEEP_CANCELLED,
        EventTag.SLEEP_BODY_DIED,
        EventTag.SLEEP_DISCONNECTED,
        EventTag.SLEEP_LIBRARY_FAILURE,
    )

/**
 * Whether a decision of [action] that ended [outcome] may have carried the body elsewhere: a walk,
 * flight or approach the body began, a dig whose block went, and a bed the body may have entered.
 * A dig its newer step halted moved nobody (the r39 round 5 offline audit: the goal's own earlier
 * dig, ended `digCancelled`, overtook the newer step).
 */
internal fun moves(
    action: String,
    outcome: String,
): Boolean =
    when (action) {
        EventTag.DIG -> outcome in DIG_REMOVED
        EventTag.SLEEP -> outcome in SLEEP_ENTERED
        else -> action in MOVING_ACTIONS && outcome !in NEVER_MOVED
    }

/** The most running decisions one held step tracks as overtaking it; one more counts as having moved. */
internal const val MAX_OVERTAKING = 16
