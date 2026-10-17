// The typed record of what one decision did to its agent's short-term goal.
package dev.loom.body

import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalStepResult
import dev.loom.contract.StepEvidence
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
    /** What showed it, from the presented goal's progress. */
    val stepEvidence: StepEvidence? = null,
    /** How many of the presented goal's item the agent held at this review. */
    val targetHeld: Long? = null,
    /**
     * How many more of it what was in sight could give at this review, so a cut to a lower total
     * reads as forced by sight or chosen.
     */
    val targetCapacity: Long? = null,
) {
    /** The tags [outcome] takes. */
    companion object {
        const val ADOPTED: String = "goalAdopted"
        const val CONTINUED: String = "goalContinued"
        const val REVISED: String = "goalRevised"
        const val COMPLETED: String = "goalCompleted"
        const val ABANDONED: String = "goalAbandoned"

        /** The held goal was abandoned and a new one adopted in the same answer, under its id. */
        const val REPLACED: String = "goalReplaced"

        /** The held goal's met condition was completed and a new goal adopted in the same answer. */
        const val COMPLETED_ADOPTED: String = "goalCompletedAdopted"

        /** The mind made no goal choice; any held goal stays and keeps ageing. */
        const val UNADDRESSED: String = "goalUnaddressed"

        /** The held goal changed or was cleared after this perception was taken. */
        const val STALE: String = "goalStale"

        /** A continue, revise, complete or abandon answered a perception that carried no goal. */
        const val NOT_HELD: String = "goalNotHeld"

        /** An adopt answered a perception that already carried a goal. */
        const val ALREADY_HELD: String = "goalAlreadyHeld"

        /** A continue, revise, complete or abandon without a verdict on the previous step. */
        const val STEP_RESULT_MISSING: String = "goalStepResultMissing"

        /**
         * A continue or revise of a goal whose last
         * [dev.loom.contract.MAX_GOAL_STEPS_WITHOUT_PROGRESS] affirmed steps advanced nothing:
         * refused with the intent, and the goal ended, so a held goal can never become a
         * no-progress loop, reworded or not, and the answer that reached the bound does not act
         * (r15: beta's stalled dig ran with no goal and fed another agent's).
         */
        const val STALLED: String = "goalStalled"

        /** An adopt or revise without its typed success target, the item and the count. */
        const val SUCCESS_TARGET_MISSING: String = "goalSuccessTargetMissing"

        /**
         * A goal review that left a met success condition neither completed nor revised: refused
         * with the intent. In r11 three agents held the stick their goal named and went on.
         */
        const val CONDITION_MET_IGNORED: String = "goalConditionMetIgnored"

        /** A completion of a goal whose success condition the inventory does not show: refused with the intent. */
        const val COMPLETION_UNMET: String = "goalCompletionUnmet"

        /**
         * An adopt, continue or revise whose success count is more than the agent holds plus
         * everything in sight could give it - a reachable drop, craftable, or the most a visible
         * block it may dig with what it holds can drop: refused with the intent. In r13 five
         * agents chased items nothing in sight could give for half the run, and in r14 kappa
         * raised a stick goal with no bush or drop in sight.
         */
        const val SUCCESS_UNOBTAINABLE: String = "goalSuccessUnobtainable"

        /**
         * An adopt or revise whose success count the inventory already holds: refused with the
         * intent, since a goal met when it is set is no goal. In r14 theta, delta and eta
         * "completed" three such goals.
         */
        const val ALREADY_MET: String = "goalAlreadyMet"

        /**
         * An adopt, continue or revise beside an idle or rest intent, which takes no step: refused
         * with the intent. A goal's step is its decision's own intent, so waiting is no step (r14
         * eta adopted a goal it already met with an idle step).
         */
        const val STEP_IDLE: String = "goalStepIdle"

        /**
         * A decision that acts at a goal review while a goal is held, without saying what it does
         * with that goal: refused with the intent, since its step would be measured against a
         * goal it ignored (r15 kappa).
         */
        const val CHOICE_MISSING: String = "goalChoiceMissing"

        /**
         * An adopt, continue, revise or replace whose dig, collect, craft or walk cannot give the
         * goal's item, by what the perception showed of its target: refused with the intent (r15:
         * terracotta, which drops nothing by hand, was dug as the step of four stick goals).
         */
        const val STEP_UNPRODUCTIVE: String = "goalStepUnproductive"

        /**
         * A continue or revise whose intent is not a collect while the agent's own drop of the goal's
         * item lies in reach: refused with the intent, since a drop left lying is one another agent
         * can take (r16: in 11 of 21 such reviews the agent dug again or idled beside its stick).
         */
        const val OWN_DROP_IGNORED: String = "goalOwnDropIgnored"

        /**
         * An abandon of a goal that what the agent held and saw could still meet, neither met nor
         * stalled: refused with its intent (r17 theta abandoned a goal one stick short with a bush
         * in sight).
         */
        const val ABANDONED_ACHIEVABLE: String = "goalAbandonedAchievable"

        /**
         * A revise that names an item other than the held goal's, which is a new goal rather than
         * a new total: refused with its intent (r18 zeta revised a scute goal into sticks).
         */
        const val REVISE_ITEM_CHANGED: String = "goalReviseItemChanged"

        /** The outcomes that refuse the decision's intent along with its goal choice. */
        val REFUSING: Set<String> =
            setOf(
                CONDITION_MET_IGNORED,
                COMPLETION_UNMET,
                SUCCESS_UNOBTAINABLE,
                ALREADY_MET,
                STEP_IDLE,
                STALLED,
                CHOICE_MISSING,
                STEP_UNPRODUCTIVE,
                OWN_DROP_IGNORED,
                ABANDONED_ACHIEVABLE,
                REVISE_ITEM_CHANGED,
            )

        /** The outcomes that settle a decision with nothing its next review needs to be told. */
        val TOLD_NOTHING: Set<String> =
            setOf(
                ADOPTED,
                CONTINUED,
                REVISED,
                REPLACED,
                COMPLETED,
                COMPLETED_ADOPTED,
                ABANDONED,
                UNADDRESSED,
                STALE,
                NOT_REVIEWED,
            )

        /** A goal choice on a decision that did not answer a goal review. */
        const val NOT_REVIEWED: String = "goalNotReviewed"
    }
}
