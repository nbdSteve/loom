/*
 * The discovery goal: what a review sets when nothing it sees, remembers or holds grounds an item
 * goal and cells at the edge of sight are offered (r23: 17 of 70 reviews could set no goal at all,
 * and looked further with nothing to measure the looking by).
 *
 * A discovery goal names no item. Its success condition is that sight shows a useful resource the
 * agent's footing does not give - a source of an item some horizon goal is grounded in or made
 * from - and its only step is a walk to a cell at the edge of sight. A walk that brings such a
 * source into a sight that showed none is typed as revealed; a met discovery goal is never
 * completed bare but replaced by an item goal for what it revealed, and like any goal it ends at
 * the stall bound. Engine-free and stateless, like `WorkingGoal.kt`.
 */
package dev.loom.mind

import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalProgress
import dev.loom.contract.GoalSummary
import dev.loom.contract.Intent
import dev.loom.contract.StepEvidence

/** Whether [choice] sets a discovery goal. */
internal fun discovers(choice: GoalChoice?): Boolean = choice?.discover == true

/**
 * Why a discovery choice, or a choice about a held discovery goal, cannot stand beside [intent],
 * or `null`: a discovery set while something grounds an item goal or nowhere is offered to look,
 * or over a held discovery goal; a revise or plain completion of one, which has no item to revise
 * and must become an item goal once met; and a step of one that is not a walk to the edge of sight.
 */
internal fun discoveryRefusalOf(
    presented: GoalSummary?,
    choice: GoalChoice?,
    intent: Intent,
    observed: GoalObservation?,
): String? {
    val held = presented?.discovery == true
    val disposition = choice?.disposition
    val setting = discovers(choice)
    val stepping = setting || (held && disposition == GoalDisposition.CONTINUE)
    return when {
        setting && (held || !offeredTo(choice, observed)) -> GoalEvidence.DISCOVERY_NOT_OFFERED
        held && disposition == GoalDisposition.REVISE -> GoalEvidence.REVISE_ITEM_CHANGED
        held && disposition == GoalDisposition.COMPLETE -> GoalEvidence.COMPLETED_WITHOUT_NEXT
        stepping && !walksToLook(intent, observed) && presented?.progress?.conditionMet != true ->
            GoalEvidence.STEP_UNPRODUCTIVE
        else -> null
    }
}

/** An abandon of a held discovery goal that can still be stepped, or `null`. */
internal fun discoveryAbandonOf(
    presented: GoalSummary?,
    choice: GoalChoice?,
    observed: GoalObservation?,
): String? =
    GoalEvidence.ABANDONED_ACHIEVABLE.takeIf {
        presented?.discovery == true && choice?.disposition == GoalDisposition.ABANDON && looksOn(presented, observed)
    }

/**
 * Whether a held discovery goal can still be stepped: under its stall bound and with somewhere to
 * look (r24: six of nine discovery goals were abandoned after one or two walks).
 */
private fun looksOn(
    presented: GoalSummary?,
    observed: GoalObservation?,
): Boolean =
    (presented?.progress?.stepsWithoutProgress ?: 0) < dev.loom.contract.MAX_GOAL_STEPS_WITHOUT_PROGRESS &&
        presented?.progress?.conditionMet != true && observed?.explore?.isNotEmpty() == true

/** Whether [observed] offers the discovery goal [choice] sets, with no success item. */
private fun offeredTo(
    choice: GoalChoice?,
    observed: GoalObservation?,
): Boolean = observed?.discoveryOffered == true && choice?.successItem == null

/** Whether [intent] walks to a cell at the edge of sight [observed] offered. */
private fun walksToLook(
    intent: Intent,
    observed: GoalObservation?,
): Boolean = intent is Intent.MoveTo && intent.onto in observed?.explore.orEmpty()

/** The discovery goal a decision [requestId] sets, its perception taken at [capturedTick] and its step [intent]. */
internal fun discoveryGoalOf(
    requestId: String,
    capturedTick: Long,
    intent: Intent,
): GoalSummary =
    GoalSummary(
        goalId = requestId,
        statement = DISCOVERY_STATEMENT,
        revision = 0,
        adoptedTick = capturedTick,
        step = stepOf(intent),
        stepAction = dev.loom.contract.IntentAction.MOVE_TO,
        stepRef = (intent as? Intent.MoveTo)?.onto,
        discovery = true,
    )

/**
 * The body-measured effect of a discovery goal's last step: met when sight shows a useful resource
 * no footing gives, and revealed when an accepted walk that reached a cell at the edge of sight
 * brought one into a sight that showed none. A walk that revealed nothing advances nothing.
 */
internal fun discoveryProgressOf(
    goal: GoalSummary,
    before: GoalObservation,
    observed: GoalObservation,
    step: StepRecord,
    priorCount: Long,
): GoalProgress {
    val met = observed.discoveryMet
    val advanced = met && !before.discoveryMet
    val accepted = goal.lastStep?.outcome == EventTag.ACCEPTED
    // The walk's own reveal: sight shows the resource at the review straight after it settled.
    val revealed =
        advanced && accepted && step.reached == true && step.explored && step.fresh &&
            observed.revealed.isNotEmpty()
    return GoalProgress(
        conditionMet = met,
        conditionHeld = 0,
        inventoryDelta = deltaOf(before.inventory, observed.inventory),
        conditionAdvanced = advanced,
        stepAdvanced = revealed,
        stepEvidence = if (revealed) StepEvidence.REVEALED else StepEvidence.NONE,
        stepsWithoutProgress = if (advanced) 0 else priorCount + 1,
    )
}
