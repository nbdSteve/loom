/*
 * The condition half of the goal refusals: whether a decision answers the held goal's success
 * condition, met or not - completing, replacing or revising past a met one, abandoning only one
 * sight cannot meet, and stepping aside only for a survival response.
 *
 * Engine-free and stateless, like `GoalRefusals.kt`, which consults it.
 */
package dev.loom.mind

import dev.loom.body.GoalEvidence
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalSummary
import dev.loom.contract.Intent
import dev.loom.contract.MAX_GOAL_STEPS_WITHOUT_PROGRESS
import dev.loom.contract.MAX_SUCCESS_COUNT
import dev.loom.contract.action

/**
 * Why [choice] leaves the goal [presented] carried unanswered, or `null`: a met condition neither
 * completed nor revised past, a completion of an unmet one, or a decision that acts with no goal
 * choice at all while a goal is held (r15 kappa dug beside a null choice, never measured).
 */
internal fun conditionRefusalOf(
    presented: GoalSummary?,
    choice: GoalChoice?,
    intent: Intent,
    observed: GoalObservation?,
): String? {
    val met = presented?.progress?.conditionMet == true
    val disposition = choice?.disposition
    // A survival response steps aside from the goal, met or not, which stays held (r20 kappa;
    // the v32 rehearsal's gamma fled a zombie at 4.5 blocks beside a met goal).
    val steppedAside = choice == null && observed.defers(intent)
    return when {
        met && !steppedAside && !movesOnFrom(choice, observed) -> GoalEvidence.CONDITION_MET_IGNORED
        disposition == GoalDisposition.ABANDON && achievable(presented, observed) ->
            GoalEvidence.ABANDONED_ACHIEVABLE
        disposition == GoalDisposition.COMPLETE -> completionRefusalOf(met, observed, presented)
        presented != null && choice == null && intent.action !in PASSIVE && !steppedAside ->
            GoalEvidence.CHOICE_MISSING
        else -> null
    }
}

/**
 * Whether [choice] answers a met goal: a completion, a replace that completes it and adopts the
 * next in one answer (r17: every one of eleven completes idled for a full review cycle), or a
 * revise past what [observed] already holds.
 */
private fun movesOnFrom(
    choice: GoalChoice?,
    observed: GoalObservation?,
): Boolean =
    choice?.disposition == GoalDisposition.COMPLETE ||
        choice?.disposition == GoalDisposition.REPLACE ||
        revisedPast(choice, observed)

/**
 * Why a plain completion is refused, or `null`: an unmet condition, or a met one with a next goal
 * in sight, which a replace adopts in the same answer rather than beside a filler step (r19:
 * eight plain completes, two beside an equip, six leaving their agent goal-less for a cadence), or
 * with a discovery goal offered in its place, as the schema offers a met item goal one.
 */
private fun completionRefusalOf(
    met: Boolean,
    observed: GoalObservation?,
    presented: GoalSummary?,
): String? {
    // A sealed shelter is completed to be stayed in, and a met subgoal resumes the shelter it fed:
    // neither leaves its agent without what comes next.
    val leadsOn = presented?.shelter == true || presented?.feeds != null
    return when {
        !met -> GoalEvidence.COMPLETION_UNMET
        !leadsOn && observed != null && (nextGoalOffered(observed) || observed.discoveryOffered) ->
            GoalEvidence.COMPLETED_WITHOUT_NEXT
        else -> null
    }
}

/**
 * Whether a replace or a revise could set a next objective, as the schema offers one in place of a
 * plain completion: an item of sight or the horizon reaching as many as
 * [GoalObservation.fewestObjective] asks, with a step offered toward it now, and not crowding out
 * looking further (the r39 offline audit: the schema withheld a plain completion beside a horizon
 * goal with a step, which the ledger accepted).
 */
private fun nextGoalOffered(observed: GoalObservation): Boolean =
    (observed.capacity.keys + observed.horizon.keys).any { item ->
        val reach = minOf(observed.reachOf(item), MAX_SUCCESS_COUNT)
        !observed.crowdsExploration(item) && !observed.steplessFor(item) && reach >= observed.fewestObjective(item)
    }

/**
 * Whether the goal [presented] carries could still be met from what [observed] holds and sees, by
 * a step offered now, and is neither met nor at the stall bound: a goal to pursue, not to abandon
 * (r17 theta body:00000333 abandoned a goal one stick short with a bush in sight, and fled a peer).
 */
private fun achievable(
    presented: GoalSummary?,
    observed: GoalObservation?,
): Boolean {
    val item = presented?.successItem
    val count = presented?.successCount
    val progress = presented?.progress
    val atBound = (progress?.stepsWithoutProgress ?: 0) >= MAX_GOAL_STEPS_WITHOUT_PROGRESS
    // What is held and sight could give, or the horizon's most: a goal asking past sight may still be met.
    val reach = item?.let { observed?.reachOf(it) }
    return observed != null && count != null && reach != null && progress?.conditionMet != true && !atBound &&
        count <= reach && item?.let(observed::steplessFor) != true
}
