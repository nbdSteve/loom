/*
 * What an accepted world step - a dig, collect, craft or placement - showed of the held goal: the
 * goal's own item past the most it has held, a direct input of a recipe for it, or the server's
 * drop attributed to the step's own request.
 */
package dev.loom.mind

import dev.loom.contract.GoalSummary
import dev.loom.contract.IntentAction
import dev.loom.contract.StepEvidence

/**
 * What showed an accepted world step - a dig, collect, craft or placement - was carried out. Only
 * the goal's own item, or a direct input of a recipe for it, is evidence (r19: a sapling dug for a
 * stick goal, and a stick and an apple for a sapling goal, reset the stall count). More than the
 * goal has held is a gain; an input made by spending the goal's own item is not (the round 4
 * offline audit: ingots crafted to a block, and back, were credited both ways). A craft only turns
 * what is held into something else, so an input it makes is a gain only past the most of it the
 * goal has held, while a dig or collect brings one in from the world (the r39 round 5 offline
 * audit: a chain goal's ingot crafted to nuggets, and back, was credited both ways).
 */
internal fun worldEvidenceOf(
    goal: GoalSummary,
    before: GoalObservation,
    observed: GoalObservation,
    step: StepRecord,
): StepEvidence {
    val item = goal.successItem
    val held = item?.let { observed.inventory[it] } ?: 0
    val heldBefore = item?.let { before.inventory[it] } ?: 0
    return when {
        item != null && held > maxOf(heldBefore, step.mostHeld[item] ?: 0) -> StepEvidence.GAINED
        held >= heldBefore && gainedInput(goal, before, observed, step) -> StepEvidence.GAINED_INPUT
        droppedToward(goal, observed, setOfNotNull(item) + step.inputs) -> StepEvidence.DROPPED
        else -> StepEvidence.NONE
    }
}

/** Whether [observed] holds more of an input than [before] did and, after a craft, than [goal] ever has. */
private fun gainedInput(
    goal: GoalSummary,
    before: GoalObservation,
    observed: GoalObservation,
    step: StepRecord,
): Boolean {
    val crafted = goal.stepAction == IntentAction.CRAFT
    return step.inputs.any { input ->
        val most = if (crafted) step.mostHeld[input] ?: 0 else 0
        (observed.inventory[input] ?: 0) > maxOf(before.inventory[input] ?: 0, most)
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

/**
 * The most of each of [items] a goal has held once one step settled, from [was] before it: a new
 * most where [now] holds more. Only a craft of the goal's own item lowers one, to what is held,
 * for an input it used, since that input went into the goal (r39 round 5 offline audit: a goal's
 * second planks craft after its table craft). Any other spend keeps the most, so a craft back to
 * a level held before is no gain either way (a reversible pair, such as an ingot and its nuggets
 * for a chain).
 */
internal fun mostHeldAfter(
    items: Set<String>,
    was: Map<String, Long>,
    before: GoalObservation?,
    now: GoalObservation?,
    madeGoal: Boolean,
): Map<String, Long> =
    items.associateWith { item ->
        val held = now?.inventory?.get(item) ?: 0
        val spent = held < (before?.inventory?.get(item) ?: 0)
        if (madeGoal && spent) held else maxOf(was[item] ?: 0, held)
    }
