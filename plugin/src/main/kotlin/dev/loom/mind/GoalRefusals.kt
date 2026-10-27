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
 * could give, a step that is no step, or a dig or collect that cannot give the goal's item or a
 * direct input of a recipe for it, by [recipes]. Each refuses the decision before its intent
 * reaches the body.
 */
internal fun incoherenceOf(
    presented: GoalSummary?,
    choice: GoalChoice?,
    intent: Intent,
    observed: GoalObservation?,
    recipes: RecipeInputs = RecipeInputs.NONE,
): String? {
    val stepping = choice?.disposition in STEPPING
    return GoalEvidence.SURVIVAL_REQUIRED.takeIf { observed?.survival?.withholds(intent) == true }
        ?: shelterRefusalOf(presented, choice, intent, observed)
        ?: discoveryRefusalOf(presented, choice, intent, observed)
        ?: discoveryAbandonOf(presented, choice, observed)
        ?: reviseItemOf(presented, choice)
        ?: conditionRefusalOf(presented, choice, intent, observed)
        ?: successRefusalOf(presented, choice, observed)
        ?: GoalEvidence.STEP_IDLE.takeIf { stepping && intent.action in PASSIVE }
        ?: GoalEvidence.STEP_UNPRODUCTIVE.takeIf { stepping && !canGive(presented, choice, intent, observed, recipes) }
        ?: GoalEvidence.OWN_DROP_IGNORED.takeIf { ownDropIgnored(presented, choice, intent, observed) }
        ?: reviseUnchangedOf(presented, choice)
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

/** The dispositions that set a new goal's target, as a revise of the held item does not. */
private val NEW_TARGET = setOf(GoalDisposition.ADOPT, GoalDisposition.REPLACE)

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
 * Why the success condition [choice] sets cannot be a goal now, by [observed]: the inventory
 * already holds the count, or the count is more than the inventory plus everything in sight could
 * give. One rule for adopt, revise and replace (r15: a revise keeping theta's held target was
 * refused). A continue keeps a target set when it was obtainable, and is held to a productive step
 * and the stall bound instead. A choice missing a field is refused for that first; this is a
 * question about a whole one.
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
    // A continue keeps a target set when it was obtainable, and is held to a productive step
    // instead: what sight can give advances it, a walk to the edge of sight looks for the rest,
    // and the stall bound ends a goal nothing advances (the path rehearsal: a goal one log short
    // was refused the collect of the agent's own lying log).
    val continuing = disposition == GoalDisposition.CONTINUE
    return when {
        observed == null -> null
        !continuing && held >= count -> GoalEvidence.ALREADY_MET
        !continuing && count > observed.reachOf(item) -> GoalEvidence.SUCCESS_UNOBTAINABLE
        // A new target may not hand over whole what looking further would not (r22); a block that
        // closes a shelter is the shelter's, not a goal of its own.
        disposition in NEW_TARGET && !feeds(choice) && observed.crowdsExploration(item) ->
            GoalEvidence.CROWDS_EXPLORATION
        else -> null
    }
}

/**
 * Whether [intent], a dig, collect, craft or walk, can give the goal's item by what [observed]
 * showed of its target - a walk onto a source of it - or walks to the edge of sight; any other
 * step is judged by its evidence afterwards. r15 dug terracotta, which drops nothing by hand, as
 * the step of four stick goals, and r18 theta walked onto terracotta with four bushes in sight.
 */
private fun canGive(
    presented: GoalSummary?,
    choice: GoalChoice?,
    intent: Intent,
    observed: GoalObservation?,
    recipes: RecipeInputs,
): Boolean {
    val target =
        when (intent) {
            is Intent.Dig -> intent.block
            is Intent.Collect -> intent.item
            is Intent.Craft -> intent.recipe
            is Intent.MoveTo -> intent.onto.takeIf { it !in observed?.explore.orEmpty() }
            else -> null
        }
    val item = targetOf(presented, choice)?.first
    // A handle the perception never described is not judged here; the body refuses it. An equip
    // gives nothing, so it is no step of a goal (r19: two equips stood in as a goal's step).
    val given = target?.let { observed?.gives?.get(it) }
    val toward = item?.let { recipes.inputsOf(it) + it + observed?.horizon.orEmpty().pathOf(it) }.orEmpty()
    val past = observed?.looksPastASource(intent, toward) == true
    return item == null || (intent !is Intent.Equip && (given == null || given.any { it in toward }) && !past)
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
