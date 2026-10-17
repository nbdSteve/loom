/*
 * The working-goal half of the goal ledger: what a review saw that a step can change, how a
 * step's effect is measured from two such readings, and which fields each goal choice needs.
 *
 * Engine-free and stateless, so every rule is a function of its arguments; the ledger owns the
 * state and calls these.
 *
 * Progress is evidence that advanced the goal's typed success condition or its typed step, and
 * nothing else. The r11 audit found 24 of 28 credited steps were the body merely moving - a
 * shuffle between two bushes, a fall after digging the block underfoot - which the mind itself
 * judged noProgress, so the stall bound never fired. Here a move is credited only as a walk or
 * flight step that arrived somewhere the goal had not been, and the success condition is the
 * held count of the item it names, measured from the agent's own inventory.
 */
package dev.loom.mind

import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalProgress
import dev.loom.contract.GoalSummary
import dev.loom.contract.Intent
import dev.loom.contract.IntentAction
import dev.loom.contract.InventoryChange
import dev.loom.contract.MAX_INVENTORY_CHANGES
import dev.loom.contract.SelfState
import dev.loom.contract.action
import dev.loom.contract.targetRef
import kotlin.math.floor

/** Distinct cells one goal remembers having been reviewed at; the oldest is forgotten past it. */
const val MAX_GOAL_CELLS: Int = 64

/** Steps carried out by moving the body, credited only on arrival somewhere new to the goal. */
private val MOVEMENT_STEPS = setOf(IntentAction.MOVE_TO, IntentAction.FLEE)

/** Steps carried out by acting on the world, credited when the body carried them out. */
private val WORLD_STEPS =
    setOf(
        IntentAction.EAT,
        IntentAction.DIG,
        IntentAction.COLLECT,
        IntentAction.PLACE,
        IntentAction.EQUIP,
        IntentAction.CRAFT,
        IntentAction.ATTACK,
        IntentAction.SLEEP,
    )

/**
 * What a review saw of the agent that a step can change: its inventory by item and count, and
 * its place. Compared between the review that affirmed a goal and the next one.
 */
data class GoalObservation(
    val inventory: Map<String, Long>,
    val position: List<Double>,
) {
    /** The block cell the agent stood in. */
    val cell: List<Int> get() = position.map { floor(it).toInt() }

    companion object {
        fun of(state: SelfState): GoalObservation =
            GoalObservation(
                inventory =
                    state.inventorySummary.groupBy({ it.what }, { it.count }).mapValues {
                            (_, counts) ->
                        counts.sum()
                    },
                position = state.position,
            )
    }
}

/**
 * The body-measured effect of the goal's last step: [before] is what the goal's affirming review
 * saw, [observed] what this review sees, and [visited] every cell the goal has been reviewed at
 * before now. `null` when either reading is missing. [priorCount] is the run of steps that
 * advanced nothing before this one.
 */
internal fun progressOf(
    goal: GoalSummary,
    before: GoalObservation?,
    observed: GoalObservation?,
    visited: Set<List<Int>>,
    priorCount: Long,
): GoalProgress? {
    if (before == null || observed == null) return null
    val item = goal.successItem
    val held = item?.let { observed.inventory[it] } ?: 0
    val heldBefore = item?.let { before.inventory[it] } ?: 0
    val conditionAdvanced = held > heldBefore
    val stepAdvanced = stepAdvanced(goal, observed, visited)
    return GoalProgress(
        conditionMet = item != null && held >= (goal.successCount ?: Long.MAX_VALUE),
        conditionHeld = held,
        inventoryDelta = deltaOf(before.inventory, observed.inventory),
        conditionAdvanced = conditionAdvanced,
        stepAdvanced = stepAdvanced,
        stepsWithoutProgress = if (conditionAdvanced || stepAdvanced) 0 else priorCount + 1,
    )
}

/** Whether the goal's typed step was carried out, by the body's own outcome for it. */
private fun stepAdvanced(
    goal: GoalSummary,
    observed: GoalObservation,
    visited: Set<List<Int>>,
): Boolean =
    goal.lastStep?.outcome == EventTag.ACCEPTED &&
        when (goal.stepAction) {
            in MOVEMENT_STEPS -> observed.cell !in visited
            in WORLD_STEPS -> true
            else -> false
        }

/** Every item whose held count changed, in name order, bounded to one per inventory slot. */
private fun deltaOf(
    before: Map<String, Long>,
    after: Map<String, Long>,
): List<InventoryChange> =
    (before.keys + after.keys)
        .sorted()
        .mapNotNull { what ->
            val change = (after[what] ?: 0) - (before[what] ?: 0)
            if (change == 0L) null else InventoryChange(what, change)
        }.take(MAX_INVENTORY_CHANGES)

/** The first field [choice]'s disposition requires that it did not give, or `null`. */
internal fun missingFieldOf(choice: GoalChoice): String? {
    val adopting = choice.disposition == GoalDisposition.ADOPT
    val worded = adopting || choice.disposition == GoalDisposition.REVISE
    val stepped = worded || choice.disposition == GoalDisposition.CONTINUE
    return (if (worded) missingWordingOf(choice) else null)
        ?: (if (stepped) missingStepOf(choice) else null)
        ?: GoalEvidence.STEP_RESULT_MISSING.takeIf { !adopting && choice.stepResult == null }
}

/** The first field wording a goal needs that [choice] did not give, or `null`. */
private fun missingWordingOf(choice: GoalChoice): String? =
    when {
        choice.statement == null -> GoalEvidence.STATEMENT_MISSING
        choice.successCondition == null -> GoalEvidence.SUCCESS_CONDITION_MISSING
        choice.successItem == null || choice.successCount == null -> GoalEvidence.SUCCESS_TARGET_MISSING
        else -> null
    }

/** The first field a step needs that [choice] did not give, or `null`. */
private fun missingStepOf(choice: GoalChoice): String? =
    when {
        choice.step == null -> GoalEvidence.STEP_MISSING
        choice.stepAction == null -> GoalEvidence.STEP_ACTION_MISSING
        else -> null
    }

/**
 * Why [choice] cannot stand beside [intent] and the goal [presented] carried, or `null`: a step
 * typed as one action and ref while the intent takes another, a met success condition neither
 * completed nor revised to a target [observed]'s inventory does not already meet, or a completion
 * the inventory does not show. Each refuses the decision before its intent reaches the body.
 */
internal fun incoherenceOf(
    presented: GoalSummary?,
    choice: GoalChoice?,
    intent: Intent,
    observed: GoalObservation?,
): String? {
    val met = presented?.progress?.conditionMet == true
    val disposition = choice?.disposition
    val stepped = disposition in setOf(GoalDisposition.ADOPT, GoalDisposition.CONTINUE, GoalDisposition.REVISE)
    return when {
        met && disposition != GoalDisposition.COMPLETE && !revisedPast(choice, observed) ->
            GoalEvidence.CONDITION_MET_IGNORED
        disposition == GoalDisposition.COMPLETE && !met -> GoalEvidence.COMPLETION_UNMET
        stepped && choice?.stepAction != null &&
            (choice.stepAction != intent.action || choice.stepRef != intent.targetRef) ->
            GoalEvidence.STEP_MISMATCH
        else -> null
    }
}

/**
 * Whether [choice] revises a met goal to a target the inventory does not already hold: a new
 * objective rather than the old one reworded, which in r11 kept a held stick "uncollected".
 */
private fun revisedPast(
    choice: GoalChoice?,
    observed: GoalObservation?,
): Boolean {
    val item = choice?.successItem.takeIf { choice?.disposition == GoalDisposition.REVISE }
    val count = choice?.successCount
    return item != null && count != null && (observed?.inventory?.get(item) ?: 0) < count
}
