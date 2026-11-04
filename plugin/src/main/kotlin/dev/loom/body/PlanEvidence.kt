// The typed record of what one decision did to its agent's plan.
package dev.loom.body

import kotlinx.serialization.Serializable

/**
 * How a decision's plan update was applied: [update] is its tag, [applied] whether it changed the
 * plan as asked, [reason] the stated reason for any change of a level, [done] the step or subgoal it
 * marked done, and the plan's objective, current subgoal and current step after it.
 */
@Serializable
data class PlanEvidence(
    val update: String,
    val applied: Boolean,
    val reason: String? = null,
    val done: String? = null,
    val objective: String? = null,
    val subgoal: String? = null,
    val step: String? = null,
)
