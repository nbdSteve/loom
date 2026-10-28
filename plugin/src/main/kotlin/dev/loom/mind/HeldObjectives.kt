/*
 * The held-objective half of the goal refusals (routine-v36): an objective is held under one
 * identity across every step that advances it, asks more than one step could give, and ends only
 * for a stated reason its evidence supports.
 *
 * Engine-free and stateless, like `GoalRefusals.kt`, which consults it. r39 met every structural
 * bar and missed held time by a wide margin with goals that never stayed held: alpha and kappa
 * replaced a met dirt goal by the same item one higher at every step, so each advance landed on a
 * new goal that had advanced nothing yet; gamma abandoned three shelter goals while taking their
 * own offered step, the last of them its seal; zeta's every advance coincided with a new goal.
 */
package dev.loom.mind

import dev.loom.body.GoalEvidence
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalReason
import dev.loom.contract.GoalSummary
import dev.loom.contract.MAX_GOAL_STEPS_WITHOUT_PROGRESS

/**
 * How many more than it holds and its own drops of the item lying an item objective must ask, at
 * least: a goal one step could meet is no objective. `MIN_OBJECTIVE_ADVANCE` in the mind's `offered.rs`.
 */
const val MIN_OBJECTIVE_ADVANCE: Long = 2

/** The dispositions that set an item objective's success condition. */
private val SETTING_OBJECTIVE = setOf(GoalDisposition.ADOPT, GoalDisposition.REVISE, GoalDisposition.REPLACE)

/** The dispositions that end the held goal and must say why. */
private val ENDING_WITH_REASON = setOf(GoalDisposition.REPLACE, GoalDisposition.ABANDON)

/**
 * Why [choice] breaks the held-objective rules beside the goal [presented] carried and what
 * [observed] saw, or `null`: an item objective one step could meet, a replace that names the held
 * goal's own item, and a replace or abandon whose reason is missing or unsupported.
 */
internal fun objectiveRefusalOf(
    presented: GoalSummary?,
    choice: GoalChoice?,
    observed: GoalObservation?,
): String? =
    trivialOf(presented, choice, observed)
        ?: GoalEvidence.REPLACE_SAME_ITEM.takeIf { replacesWithItself(presented, choice) }
        ?: reasonRefusalOf(presented, choice, observed)

/**
 * An item objective [choice] sets that asks fewer than [GoalObservation.fewestObjective] by what
 * [observed] saw: one step could meet it.
 */
private fun trivialOf(
    presented: GoalSummary?,
    choice: GoalChoice?,
    observed: GoalObservation?,
): String? {
    val (item, count) = objectiveSetBy(presented, choice) ?: return null
    return GoalEvidence.TRIVIAL.takeIf { observed != null && count < observed.fewestObjective(item) }
}

/**
 * The item and count of the objective [choice] sets, or `null` for one that sets none: no item
 * target, a missing field refused on its own, a resource subgoal, or a revise to the total
 * [presented] already asks, which is refused as unchanged instead.
 */
private fun objectiveSetBy(
    presented: GoalSummary?,
    choice: GoalChoice?,
): Pair<String, Long>? {
    val setting = choice != null && choice.disposition in SETTING_OBJECTIVE && missingFieldOf(choice) == null
    val target = choice?.successItem?.let { item -> choice.successCount?.let { item to it } }
    val unchanged =
        choice?.disposition == GoalDisposition.REVISE && target ==
            presented?.successItem?.let { item ->
                presented.successCount?.let { item to it }
            }
    return target.takeIf { setting && !unchanged && choice?.let(::feeds) != true }
}

/** Whether [choice] replaces the held item goal [presented] by a goal for its own item. */
private fun replacesWithItself(
    presented: GoalSummary?,
    choice: GoalChoice?,
): Boolean =
    choice?.disposition == GoalDisposition.REPLACE && presented?.successItem != null &&
        choice.successItem == presented.successItem

/** Why a replace or an abandon of [presented] does not stand on its stated reason, or `null`. */
private fun reasonRefusalOf(
    presented: GoalSummary?,
    choice: GoalChoice?,
    observed: GoalObservation?,
): String? {
    val reason = choice?.reason
    return when {
        presented == null || choice == null || choice.disposition !in ENDING_WITH_REASON -> null
        reason == null -> GoalEvidence.REASON_MISSING
        !supports(reason, presented, choice, observed) -> GoalEvidence.REASON_UNSUPPORTED
        else -> null
    }
}

/**
 * Whether [reason] is true of [presented] by the evidence its review measured and [observed] saw:
 * completed for a met condition, stale at the stall bound, impossible for a goal nothing held or
 * in reach could meet now, unsafe for a shelter goal replacing another while one is offered, and
 * superseded for a replace by a different objective of an unmet goal.
 */
private fun supports(
    reason: GoalReason,
    presented: GoalSummary,
    choice: GoalChoice,
    observed: GoalObservation?,
): Boolean {
    val met = presented.progress?.conditionMet == true
    val replacing = choice.disposition == GoalDisposition.REPLACE
    return when (reason) {
        GoalReason.COMPLETED -> met
        GoalReason.STALE -> (presented.progress?.stepsWithoutProgress ?: 0) >= MAX_GOAL_STEPS_WITHOUT_PROGRESS
        GoalReason.IMPOSSIBLE -> !met && unreachable(presented, observed)
        GoalReason.UNSAFE -> replacing && shelters(choice) && !presented.shelter && observed?.cover?.offered == true
        GoalReason.SUPERSEDED -> replacing && !met && !sameObjective(presented, choice)
    }
}

/**
 * Whether nothing held or in reach could meet [presented] now, by [observed]: a shelter no step can
 * close, a discovery with nowhere left to look, or an item goal asking more than is held plus what
 * sight could give, or one no step offered now advances - the converse of the rule an abandon of an
 * achievable goal is refused by.
 */
private fun unreachable(
    presented: GoalSummary,
    observed: GoalObservation?,
): Boolean {
    if (observed == null) return false
    val item = presented.successItem
    val count = presented.successCount
    return when {
        presented.shelter -> observed.cover?.let { !it.rank.sealed && !it.stepped } == true
        presented.discovery -> observed.explore.isEmpty()
        item != null && count != null ->
            count > (observed.inventory[item] ?: 0) + (observed.capacity[item] ?: 0) || observed.steplessFor(item)
        else -> false
    }
}

/** Whether [choice] sets the same objective [presented] held: its item, or its kind of goal. */
private fun sameObjective(
    presented: GoalSummary,
    choice: GoalChoice,
): Boolean =
    when {
        presented.shelter -> shelters(choice)
        presented.discovery -> discovers(choice)
        else -> choice.successItem == presented.successItem
    }
