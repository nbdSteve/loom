// The typed record of what one decision did to its agent's short-term goal.
package dev.loom.body

import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalStepResult
import kotlinx.serialization.Serializable

/**
 * How a decision's goal choice was resolved against the goal its perception carried.
 *
 * Carried on [BodyEvent.DECIDED] so continuity is a join of fields rather than a reading
 * of prose: a second review continued the first one's goal exactly when its
 * [presentedGoalId] equals [goalId] and [outcome] is [CONTINUED], [REVISED] or [COMPLETED].
 *
 * @property outcome one of this type's countable tags.
 * @property disposition what the mind chose, absent when it chose nothing.
 * @property presentedGoalId the goal the answered perception carried, if any.
 * @property presentedRevision that goal's revision as carried.
 * @property goalId the goal held after resolution, if any.
 * @property revision that goal's revision after resolution.
 * @property expiredGoalId a goal that outlived its lifetime and was cleared before this
 *   perception was taken.
 * @property stepResult the mind's verdict on the carried goal's previous step, if it gave one.
 * @property stepsWithoutProgress the body's count, as carried, of affirmed steps in a row that
 *   changed neither the agent's inventory nor its place.
 */
@Serializable
data class GoalEvidence(
    val outcome: String,
    val disposition: GoalDisposition? = null,
    val presentedGoalId: String? = null,
    val presentedRevision: Long? = null,
    val goalId: String? = null,
    val revision: Long? = null,
    val expiredGoalId: String? = null,
    val stepResult: GoalStepResult? = null,
    val stepsWithoutProgress: Long? = null,
    /** Whether the presented goal's typed success condition was met at this review. */
    val conditionMet: Boolean? = null,
    /** Whether the presented goal's success condition advanced since its last affirming review. */
    val conditionAdvanced: Boolean? = null,
    /** Whether the presented goal's typed step was carried out since its last affirming review. */
    val stepAdvanced: Boolean? = null,
) {
    /** The tags [outcome] takes. */
    companion object {
        const val ADOPTED: String = "goalAdopted"
        const val CONTINUED: String = "goalContinued"
        const val REVISED: String = "goalRevised"
        const val COMPLETED: String = "goalCompleted"
        const val ABANDONED: String = "goalAbandoned"

        /** The mind made no goal choice; any held goal stays and keeps ageing. */
        const val UNADDRESSED: String = "goalUnaddressed"

        /** The held goal changed or was cleared after this perception was taken. */
        const val STALE: String = "goalStale"

        /** A continue, revise, complete or abandon answered a perception that carried no goal. */
        const val NOT_HELD: String = "goalNotHeld"

        /** An adopt answered a perception that already carried a goal. */
        const val ALREADY_HELD: String = "goalAlreadyHeld"

        /** An adopt or revise without a statement. */
        const val STATEMENT_MISSING: String = "goalStatementMissing"

        /** An adopt or revise without a success condition. */
        const val SUCCESS_CONDITION_MISSING: String = "goalSuccessConditionMissing"

        /** An adopt, continue or revise without the step its action takes. */
        const val STEP_MISSING: String = "goalStepMissing"

        /** A continue, revise, complete or abandon without a verdict on the previous step. */
        const val STEP_RESULT_MISSING: String = "goalStepResultMissing"

        /**
         * A continue or revise of a goal whose last
         * [dev.loom.contract.MAX_GOAL_STEPS_WITHOUT_PROGRESS] affirmed steps changed neither the
         * agent's inventory nor its place: refused, and the goal ended, so a held goal can never
         * become a no-progress loop, reworded or not.
         */
        const val STALLED: String = "goalStalled"

        /** An adopt or revise without its typed success target, the item and the count. */
        const val SUCCESS_TARGET_MISSING: String = "goalSuccessTargetMissing"

        /** An adopt, continue or revise without the typed action its step takes. */
        const val STEP_ACTION_MISSING: String = "goalStepActionMissing"

        /**
         * A typed step whose action or ref is not the decision's own intent: refused with the
         * intent, before it reaches the body. In r11 four agents' steps named one bush while the
         * intent walked to another, and one named a collect while the intent idled.
         */
        const val STEP_MISMATCH: String = "goalStepMismatch"

        /**
         * A goal review that left a met success condition neither completed nor revised: refused
         * with the intent. In r11 three agents held the stick their goal named and went on.
         */
        const val CONDITION_MET_IGNORED: String = "goalConditionMetIgnored"

        /** A completion of a goal whose success condition the inventory does not show: refused with the intent. */
        const val COMPLETION_UNMET: String = "goalCompletionUnmet"

        /** The outcomes that refuse the decision's intent along with its goal choice. */
        val REFUSING: Set<String> = setOf(STEP_MISMATCH, CONDITION_MET_IGNORED, COMPLETION_UNMET)

        /** A goal choice on a decision that did not answer a goal review. */
        const val NOT_REVIEWED: String = "goalNotReviewed"
    }
}
