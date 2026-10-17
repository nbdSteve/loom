package dev.loom.mind

// What makes a revise a revise: it keeps the held goal's item and changes its total, and of a
// met goal it names a total not yet held.

import dev.loom.body.GoalEvidence
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalSummary

/**
 * A revise that names an item other than the held goal's: a new goal, which is a replace, not a
 * revise (r18 zeta body:00000569 revised a scute goal into sticks under the same goal).
 */
internal fun reviseItemOf(
    presented: GoalSummary?,
    choice: GoalChoice?,
): String? =
    GoalEvidence.REVISE_ITEM_CHANGED.takeIf {
        revising(presented, choice) && choice?.successItem != presented?.successItem
    }

/**
 * A revise that names the held goal's own item and total, which changes nothing and would read as
 * goal activity (r19: 28 of 31 revises restated the total they were shown).
 */
internal fun reviseUnchangedOf(
    presented: GoalSummary?,
    choice: GoalChoice?,
): String? =
    GoalEvidence.REVISE_UNCHANGED.takeIf {
        revising(presented, choice) &&
            choice?.successItem == presented?.successItem &&
            choice?.successCount == presented?.successCount
    }

private fun revising(
    presented: GoalSummary?,
    choice: GoalChoice?,
): Boolean =
    choice?.disposition == GoalDisposition.REVISE && presented?.successItem != null && choice.successItem != null

/**
 * Whether [choice] revises a met goal to a target the inventory does not already hold: a new
 * objective rather than the old one reworded, which in r11 kept a held stick "uncollected".
 */
internal fun revisedPast(
    choice: GoalChoice?,
    observed: GoalObservation?,
): Boolean {
    val item = choice?.successItem.takeIf { choice?.disposition == GoalDisposition.REVISE }
    val count = choice?.successCount
    return item != null && count != null && (observed?.inventory?.get(item) ?: 0) < count
}
