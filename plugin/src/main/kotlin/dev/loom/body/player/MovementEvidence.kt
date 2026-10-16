/*
 * How a walk or flight is answered and recorded: its intent verdict, the typed tag a route
 * refusal is counted under, and the outcome its `moved` and `retreating` records carry.
 */
package dev.loom.body.player

import dev.loom.body.EventTag
import dev.loom.body.IntentOutcome
import dev.loom.bodycontrol.MoveOutcome

internal fun movementOutcome(
    outcome: MoveOutcome,
    movement: String,
): IntentOutcome =
    when (outcome) {
        MoveOutcome.ARRIVED -> IntentOutcome.Accepted("$movement arrived")
        MoveOutcome.CANCELLED -> IntentOutcome.Accepted("$movement was cancelled after moving")
        MoveOutcome.STALLED -> IntentOutcome.Accepted("$movement stalled after starting")
        MoveOutcome.OVER_TRAVELLED -> IntentOutcome.Accepted("$movement reached its travel bound")
        MoveOutcome.TIMED_OUT -> IntentOutcome.Accepted("$movement reached its time bound")
        MoveOutcome.OUT_OF_RANGE, MoveOutcome.NO_ROUTE, MoveOutcome.SEARCH_OVER_BUDGET ->
            routeRefusal(outcome, movement)
        MoveOutcome.SENSE_FORGOTTEN ->
            IntentOutcome.Rejected(
                "the body no longer remembers the sense that named that threat",
                EventTag.RETREAT_THREAT_FORGOTTEN,
            )
        MoveOutcome.THREAT_UNNAMED ->
            IntentOutcome.Rejected(
                "that sense named no such threat",
                EventTag.RETREAT_THREAT_UNNAMED,
            )
        MoveOutcome.THREAT_GONE ->
            IntentOutcome.Rejected(
                "the named threat is gone",
                EventTag.RETREAT_THREAT_GONE,
            )
    }

/** A movement the ground or the body's own bounds refused before it found a route. */
private fun routeRefusal(
    outcome: MoveOutcome,
    movement: String,
): IntentOutcome.Rejected {
    val walk = movement == WALK
    return when (outcome) {
        MoveOutcome.OUT_OF_RANGE ->
            IntentOutcome.Rejected(
                "$movement target is outside the body reach",
                if (walk) EventTag.MOVE_OUT_OF_RANGE else EventTag.RETREAT_OUT_OF_RANGE,
            )
        MoveOutcome.NO_ROUTE ->
            IntentOutcome.Rejected(
                "the ground offered no route for this $movement",
                if (walk) EventTag.MOVE_NO_ROUTE else EventTag.RETREAT_NO_ROUTE,
            )
        else ->
            IntentOutcome.Rejected(
                "the body search budget ended before a route was known",
                if (walk) EventTag.MOVE_SEARCH_OVER_BUDGET else EventTag.RETREAT_SEARCH_OVER_BUDGET,
            )
    }
}

/** One movement report's ending, as its `moved` record states it. */
internal data class MovementEnding(
    val outcome: MoveOutcome,
    val from: List<Double>,
    val position: List<Double>,
    val travelled: Double,
)

/** The movement name a walk's outcome is described with; anything else is a flight. */
internal const val WALK = "walk"

/** How a movement that moved the body ended, as its `moved` record's outcome. */
internal fun movementTag(outcome: MoveOutcome): String =
    when (outcome) {
        MoveOutcome.ARRIVED -> EventTag.MOVEMENT_ARRIVED
        MoveOutcome.STALLED -> EventTag.MOVEMENT_STALLED
        MoveOutcome.OVER_TRAVELLED -> EventTag.MOVEMENT_OVER_TRAVELLED
        MoveOutcome.TIMED_OUT -> EventTag.MOVEMENT_TIMED_OUT
        MoveOutcome.CANCELLED -> EventTag.MOVEMENT_CANCELLED
        MoveOutcome.OUT_OF_RANGE,
        MoveOutcome.NO_ROUTE,
        MoveOutcome.SEARCH_OVER_BUDGET,
        MoveOutcome.SENSE_FORGOTTEN,
        MoveOutcome.THREAT_UNNAMED,
        MoveOutcome.THREAT_GONE,
        -> EventTag.MOVEMENT_REFUSED
    }

internal fun retreatTag(outcome: MoveOutcome): String =
    when (outcome) {
        MoveOutcome.ARRIVED -> EventTag.RETREAT_SAFE
        MoveOutcome.CANCELLED -> EventTag.RETREAT_SUPERSEDED
        MoveOutcome.SENSE_FORGOTTEN -> EventTag.RETREAT_THREAT_FORGOTTEN
        MoveOutcome.THREAT_UNNAMED -> EventTag.RETREAT_THREAT_UNNAMED
        MoveOutcome.THREAT_GONE -> EventTag.RETREAT_THREAT_GONE
        else -> EventTag.RETREAT_FAILED
    }
