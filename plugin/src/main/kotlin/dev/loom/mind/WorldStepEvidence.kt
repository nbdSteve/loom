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
 * The most of each item a goal tracks it has held once one step settled, from [was] before it: a
 * new most where [now] holds more. A craft of the goal's own step, making [crafting] past the most of
 * it held, lowers the most of an input of what it made that fell across it, since that input went
 * toward the goal for good (the r39 round 5 and 6 offline audits: a table goal's second planks, and a
 * pickaxe goal's planks remade after its sticks): to what is held, unless an item sharing a recipe
 * cycle with it is held, which may hold the rest of it, and then only by what fell; and not at all
 * beside a rise of such an item, which may hold what fell. Any other spend keeps the most, so a craft
 * back to a level held before is no gain: a reversible pair, such as a chain's ingot and its nuggets,
 * or an ingot and its block; an input another decision spent beside a craft that did not use it (the
 * r39 round 7 offline audit: an ingot an event crafted into nuggets during a pickaxe goal's sticks
 * craft, then made back, was a gain); and an input of something only made back to as much as held,
 * which may have been placed and broken into what it was made from (snow layers into snowballs).
 */
internal fun mostHeldAfter(
    was: Map<String, Long>,
    before: GoalObservation?,
    now: GoalObservation?,
    crafting: Set<String>,
    recipes: RecipeInputs,
): Map<String, Long> {
    val held = { item: String -> now?.inventory?.get(item) ?: 0 }
    val heldBefore = { item: String -> before?.inventory?.get(item) ?: 0 }
    val made = crafting.filter { held(it) > maxOf(heldBefore(it), was[it] ?: 0) }
    return was.mapValues { (item, most) ->
        val fell = heldBefore(item) - held(item)
        if (fell <= 0 || made.none { item in recipes.inputsOf(it) }) return@mapValues maxOf(most, held(item))
        val partners = now?.inventory?.keys.orEmpty().filter { held(it) > 0 && sharesCycle(item, it, recipes) }
        when {
            partners.any { held(it) > heldBefore(it) } -> most
            partners.isNotEmpty() -> maxOf(held(item), most - fell)
            else -> held(item)
        }
    }
}

/** The most of [item] and each of its [inputs] a step starts from: what [kept] remembered, or [observed] holds. */
internal fun mostHeldFrom(
    item: String?,
    inputs: Set<String>,
    kept: Map<String, Long>?,
    observed: GoalObservation?,
): Map<String, Long> =
    (setOfNotNull(item) + inputs).associateWith { maxOf(kept?.get(it) ?: 0, observed?.inventory?.get(it) ?: 0) }

/**
 * Whether [item] and [other] can each be crafted from the other by some chain of [recipes], so what
 * went into one is held in the other.
 */
private fun sharesCycle(
    item: String,
    other: String,
    recipes: RecipeInputs,
): Boolean = other in craftedFrom(item, recipes) && item in craftedFrom(other, recipes)

/**
 * Every item [item] can be crafted from by some chain of [recipes], each read once, so the search is
 * bounded by the registry's size. Vanilla's reversible pairs are all one or two crafts deep, but the
 * registry is reread after a datapack reload, and a longer cycle cut short would read each craft of
 * a lap as a conversion for good and credit it again every lap.
 */
private fun craftedFrom(
    item: String,
    recipes: RecipeInputs,
): Set<String> {
    val sources = mutableSetOf<String>()
    val unread = ArrayDeque(listOf(item))
    while (unread.isNotEmpty()) {
        for (source in recipes.inputsOf(unread.removeFirst())) {
            if (sources.add(source)) unread.addLast(source)
        }
    }
    return sources
}
