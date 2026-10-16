/*
 * The working-goal half of the goal ledger: what a review saw that a step can change, how a
 * step's effect is measured from two such readings, and which fields each goal choice needs.
 *
 * Engine-free and stateless, so every rule is a function of its arguments; the ledger owns the
 * state and calls these.
 */
package dev.loom.mind

import dev.loom.body.GoalEvidence
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalProgress
import dev.loom.contract.SelfState
import kotlin.math.sqrt

/** Moving at least this far between two reviews counts as having moved. */
private const val MOVED_BLOCKS = 1.0

/**
 * What a review saw of the agent that a step can change: its inventory by item and count, and
 * its place. Compared between the review that affirmed a goal and the next one.
 */
data class GoalObservation(
    val inventory: Map<String, Long>,
    val position: List<Double>,
) {
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
 * The body-measured effect of a step: [before] is what the goal's affirming review saw and
 * [observed] what this review sees. `null` when either reading is missing. [priorCount] is the
 * run of steps that changed nothing before this one.
 */
internal fun progressOf(
    before: GoalObservation?,
    observed: GoalObservation?,
    priorCount: Long,
): GoalProgress? {
    if (before == null || observed == null) return null
    val inventoryChanged = before.inventory != observed.inventory
    val moved = distance(before.position, observed.position) >= MOVED_BLOCKS
    val count = if (inventoryChanged || moved) 0 else priorCount + 1
    return GoalProgress(inventoryChanged, moved, count)
}

/** The first field [choice]'s disposition requires that it did not give, or `null`. */
internal fun missingFieldOf(choice: GoalChoice): String? {
    val adopting = choice.disposition == GoalDisposition.ADOPT
    val worded = adopting || choice.disposition == GoalDisposition.REVISE
    val stepped = worded || choice.disposition == GoalDisposition.CONTINUE
    return when {
        worded && choice.statement == null -> GoalEvidence.STATEMENT_MISSING
        worded && choice.successCondition == null -> GoalEvidence.SUCCESS_CONDITION_MISSING
        stepped && choice.step == null -> GoalEvidence.STEP_MISSING
        !adopting && choice.stepResult == null -> GoalEvidence.STEP_RESULT_MISSING
        else -> null
    }
}

private fun distance(
    from: List<Double>,
    to: List<Double>,
): Double = sqrt(from.zip(to).sumOf { (a, b) -> (a - b) * (a - b) })
