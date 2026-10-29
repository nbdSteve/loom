/*
 * What a shelter step's cover is credited against: the most enclosed the goal has been where the
 * agent stands, and whether something other than the goal's own step carried it there.
 */
package dev.loom.mind

import dev.loom.body.EventTag
import dev.loom.contract.GoalSummary
import dev.loom.contract.IntentAction

/**
 * The reference a shelter step measured at [now] must pass: the most enclosed the goal has been at
 * the cell the agent now stands in, by [bestByCell], and for a step that moved it the cover it
 * left, [from].
 */
internal fun shelterReferenceOf(
    bestByCell: Map<List<Int>, CoverRank>,
    from: GoalObservation?,
    now: GoalObservation?,
): CoverRank? {
    val cell = now?.cell ?: return null
    val left = from?.cover?.rank?.takeIf { from.cell != cell }
    return listOfNotNull(bestByCell[cell], left).maxOrNull()
}

/**
 * Whether [now] stands somewhere other than [from], the goal's affirming review, without [goal]'s
 * own accepted step having moved it there: a flight, a fall or a push carried it. Only a walk and
 * the descent [from]'s cover named move the agent; any other dig leaves it where it stood (the r39
 * offline audit: a side-face dig, then a flight into an overhang, was credited as the shelter's).
 */
internal fun displaced(
    goal: GoalSummary,
    from: GoalObservation?,
    now: GoalObservation?,
): Boolean {
    val moved = from != null && now != null && from.cell != now.cell
    val descended = goal.stepAction == IntentAction.DIG && goal.stepRef != null && goal.stepRef == from?.cover?.descend
    val ownMove = goal.lastStep?.outcome == EventTag.ACCEPTED && (goal.stepAction == IntentAction.MOVE_TO || descended)
    return moved && !ownMove
}
