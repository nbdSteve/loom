/*
 * The refusal half of the goal ledger: which goal choices cannot stand beside their decision's
 * intent and the goal its perception carried, and what a refusal tells the agent's next review.
 *
 * Engine-free and stateless, like `WorkingGoal.kt`, whose readings these rules judge. Each
 * refuses the decision before its intent reaches the body.
 */
package dev.loom.mind

import dev.loom.body.GoalEvidence
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalRefusal
import dev.loom.contract.GoalSummary
import dev.loom.contract.Intent
import dev.loom.contract.IntentAction
import dev.loom.contract.action

/** The intents that take no step, which a goal cannot be pursued by. */
internal val PASSIVE = setOf(IntentAction.IDLE, IntentAction.REST)

/** The dispositions that take the decision's intent as the goal's step. */
internal val STEPPING =
    setOf(GoalDisposition.ADOPT, GoalDisposition.CONTINUE, GoalDisposition.REVISE, GoalDisposition.REPLACE)

/**
 * Why [choice] cannot stand beside [intent] and the goal [presented] carried, or `null`: a met
 * success condition neither completed nor revised to a target [observed]'s inventory does not
 * already meet, a completion the inventory does not show, a held goal left unanswered by a
 * decision that acts, a success condition already met or more than what is held and in sight
 * could give, a step that is no step, or a dig or collect that cannot give the goal's item. Each
 * refuses the decision before its intent reaches the body.
 */
internal fun incoherenceOf(
    presented: GoalSummary?,
    choice: GoalChoice?,
    intent: Intent,
    observed: GoalObservation?,
): String? {
    val stepping = choice?.disposition in STEPPING
    return conditionRefusalOf(presented, choice, intent, observed)
        ?: successRefusalOf(presented, choice, observed)
        ?: GoalEvidence.STEP_IDLE.takeIf { stepping && intent.action in PASSIVE }
        ?: GoalEvidence.STEP_UNPRODUCTIVE.takeIf { stepping && !canGive(presented, choice, intent, observed) }
        ?: GoalEvidence.OWN_DROP_IGNORED.takeIf { ownDropIgnored(presented, choice, intent, observed) }
}

/** The dispositions that keep pursuing the held goal's item, rather than set a new goal. */
private val PURSUING = setOf(GoalDisposition.CONTINUE, GoalDisposition.REVISE)

/**
 * Whether [choice] keeps pursuing the held goal while its item lies in reach as the agent's own
 * drop and [intent] is not a collect: a drop left lying is one another agent can take (r16: in 11
 * of 21 such reviews the agent dug again or idled, and delta collected the lost sticks).
 */
private fun ownDropIgnored(
    presented: GoalSummary?,
    choice: GoalChoice?,
    intent: Intent,
    observed: GoalObservation?,
): Boolean {
    val item = targetOf(presented, choice)?.first ?: return false
    val lying = observed?.ownDrops?.containsValue(item) == true
    return choice?.disposition in PURSUING && presented != null && lying && intent !is Intent.Collect
}

/**
 * Why [choice] leaves the goal [presented] carried unanswered, or `null`: a met condition neither
 * completed nor revised past, a completion of an unmet one, or a decision that acts with no goal
 * choice at all while a goal is held (r15 kappa dug beside a null choice, never measured).
 */
private fun conditionRefusalOf(
    presented: GoalSummary?,
    choice: GoalChoice?,
    intent: Intent,
    observed: GoalObservation?,
): String? {
    val met = presented?.progress?.conditionMet == true
    val disposition = choice?.disposition
    return when {
        met && disposition != GoalDisposition.COMPLETE && !revisedPast(choice, observed) ->
            GoalEvidence.CONDITION_MET_IGNORED
        disposition == GoalDisposition.COMPLETE && !met -> GoalEvidence.COMPLETION_UNMET
        presented != null && choice == null && intent.action !in PASSIVE -> GoalEvidence.CHOICE_MISSING
        else -> null
    }
}

/** The dispositions that set a success condition of their own. */
private val SETTING = setOf(GoalDisposition.ADOPT, GoalDisposition.REVISE, GoalDisposition.REPLACE)

/** The success condition [choice] sets, or for a continue the one [presented] carries. */
private fun targetOf(
    presented: GoalSummary?,
    choice: GoalChoice?,
): Pair<String, Long>? {
    val setting = choice?.disposition in SETTING
    val item = if (setting) choice?.successItem else presented?.successItem
    val count = if (setting) choice?.successCount else presented?.successCount
    return if (item != null && count != null) item to count else null
}

/**
 * Why the success condition [choice] sets, or the held one a continue keeps, cannot be a goal
 * now, by [observed]: the inventory already holds a count being set, or the count is more than
 * the inventory plus everything in sight could give. One rule for adopt, continue and revise
 * (r15: a revise keeping theta's held target was refused where a continue of it would have
 * passed). A choice missing a field is refused for that first; this is a question about a whole one.
 */
private fun successRefusalOf(
    presented: GoalSummary?,
    choice: GoalChoice?,
    observed: GoalObservation?,
): String? {
    val disposition = choice?.disposition
    val judged = disposition in STEPPING && choice?.let(::missingFieldOf) == null
    val (item, count) = targetOf(presented, choice)?.takeIf { judged } ?: return null
    val held = observed?.inventory?.get(item) ?: 0
    return when {
        observed == null -> null
        disposition != GoalDisposition.CONTINUE && held >= count -> GoalEvidence.ALREADY_MET
        count > held + (observed.capacity[item] ?: 0) -> GoalEvidence.SUCCESS_UNOBTAINABLE
        else -> null
    }
}

/**
 * Whether [intent], a dig or collect, can give the goal's item by what [observed] showed of its
 * target; any other step is judged by its evidence afterwards. r15 dug terracotta, which drops
 * nothing by hand, as the step of four stick goals.
 */
private fun canGive(
    presented: GoalSummary?,
    choice: GoalChoice?,
    intent: Intent,
    observed: GoalObservation?,
): Boolean {
    val target =
        when (intent) {
            is Intent.Dig -> intent.block
            is Intent.Collect -> intent.item
            else -> null
        }
    val item = targetOf(presented, choice)?.first
    // A handle the perception never described is not judged here; the body refuses it.
    val given = target?.let { observed?.gives?.get(it) }
    return item == null || given == null || item in given
}

/**
 * What [outcome] refused, worded for the agent's next review: the success condition it judged,
 * with what was held and what sight could give, when the decision set or kept one.
 */
internal fun refusalOf(
    requestId: String,
    outcome: String,
    presented: GoalSummary?,
    choice: GoalChoice?,
    observed: GoalObservation?,
): GoalRefusal {
    val target = targetOf(presented, choice)
    val item = target?.first
    return GoalRefusal(
        requestId = requestId,
        outcome = outcome,
        successItem = item,
        asked = target?.second,
        held = item?.let { observed?.inventory?.get(it) ?: 0 },
        capacity = item?.let { observed?.capacity?.get(it) ?: 0 },
    )
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
