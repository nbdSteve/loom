/*
 * What an accepted world step - a dig, collect, craft or placement - showed of the held goal: the
 * goal's own item past the most it has held, a direct input of a recipe for it, or the server's
 * drop attributed to the step's own request.
 */
package dev.loom.mind

import dev.loom.contract.GoalSummary
import dev.loom.contract.StepEvidence

/**
 * What showed an accepted world step - a dig, collect, craft or placement - was carried out. Only
 * the goal's own item, or a direct input of a recipe for it, is evidence (r19: a sapling dug for a
 * stick goal, and a stick and an apple for a sapling goal, reset the stall count). More than the
 * goal has held is a gain; an input made by spending the goal's own item is not (the round 4
 * offline audit: ingots crafted to a block, and back, were credited both ways).
 */
internal fun worldEvidenceOf(
    goal: GoalSummary,
    before: GoalObservation,
    observed: GoalObservation,
    step: StepRecord,
): StepEvidence {
    val rose = { item: String -> (observed.inventory[item] ?: 0) > (before.inventory[item] ?: 0) }
    val item = goal.successItem
    val held = item?.let { observed.inventory[it] } ?: 0
    val heldBefore = item?.let { before.inventory[it] } ?: 0
    return when {
        item != null && held > maxOf(heldBefore, step.mostHeld) -> StepEvidence.GAINED
        held >= heldBefore && step.inputs.any(rose) -> StepEvidence.GAINED_INPUT
        droppedToward(goal, observed, setOfNotNull(item) + step.inputs) -> StepEvidence.DROPPED
        else -> StepEvidence.NONE
    }
}

/**
 * Whether the server attributed a drop to [goal]'s last step's own request, of an item [toward]
 * names, or of an item it did not tell.
 */
private fun droppedToward(
    goal: GoalSummary,
    observed: GoalObservation,
    toward: Set<String>,
): Boolean {
    val request = goal.lastStep?.requestId ?: return false
    val told = observed.droppedItems[request]
    return request in observed.dropped && (told == null || told.any { it in toward })
}
