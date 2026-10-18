/*
 * Horizon goals: the goal items a review may set a total for beyond what sight alone guarantees.
 *
 * r22 had no goal that sight did not hand over whole: every adoption was dirt from underfoot or a
 * single seed, because a success count could never pass what the visible blocks would give, and
 * no agent looked for wood. A horizon goal may ask more, but only when something grounds it - a
 * source seen now or remembered, a typed recipe made from such a source alone, or the cells at
 * the edge of sight beyond which more of a grounded source may lie - and never further than
 * [HORIZON_SPAN] past what that grounding gives. Nothing the agent's own footing guarantees, the
 * dirt and seeds of the blocks under and beside its feet, grounds one; such an item is a goal at
 * all while exploration is offered only when a horizon goal is made from it.
 *
 * Engine-free and stateless: the same perception the mind is shown decides it, and the ledger
 * judges every answer against the same list.
 */
package dev.loom.mind

import dev.loom.contract.HorizonGoal
import dev.loom.contract.HorizonGrounding
import dev.loom.contract.MAX_HORIZON_GOALS
import dev.loom.contract.MAX_SUCCESS_COUNT
import dev.loom.contract.ObservationKind
import dev.loom.contract.Perception
import kotlin.math.sqrt

/** How far past what its grounding gives a horizon goal's total may reach. */
const val HORIZON_SPAN: Long = 8

/** How far, horizontally, a block is the agent's footing: its own column and the ones beside it. */
const val FOOTING_REACH_BLOCKS: Double = 1.6

/** The vertical band of the footing: the support layer and the feet layer, centre to centre. */
private const val FOOTING_BAND_BLOCKS = 0.6

/** How many recipe results one grounded input offers, the fewest slots first. */
private const val RECIPES_PER_INPUT = 3

/** How many results of a recipe-grounded item offer a second step of a typed path. */
private const val SECOND_STEP_RECIPES = 2

/** The items the blocks under and beside the agent's feet give: its footing, guaranteed. */
fun footingOf(perception: Perception): Set<String> =
    perception.surroundings
        .filter { it.kind == ObservationKind.BLOCK && it.visible }
        .filter { seen ->
            val squared = seen.distance * seen.distance - seen.verticalOffset * seen.verticalOffset
            val horizontal = sqrt(squared.coerceAtLeast(0.0))
            horizontal <= FOOTING_REACH_BLOCKS && kotlin.math.abs(seen.verticalOffset) <= FOOTING_BAND_BLOCKS
        }.flatMap { seen -> seen.yields.orEmpty().filter { it.most >= 1 }.map { it.what } }
        .toSet()

/** The horizon goals [perception] may set, by [recipes]: at most [MAX_HORIZON_GOALS], recipe paths first. */
fun horizonOf(
    perception: Perception,
    recipes: RecipeInputs,
): List<HorizonGoal> {
    val observed = GoalObservation.of(perception)
    val footing = footingOf(perception)
    val held = { item: String -> observed.inventory[item] ?: 0 }
    val frontier = perception.explore.isNotEmpty()
    val sources = observed.capacity.keys.filter { it !in footing }.sorted()
    val chosen = linkedMapOf<String, HorizonGoal>()

    fun add(goal: HorizonGoal) {
        if (goal.what !in chosen && held(goal.what) < goal.most) chosen[goal.what] = goal
    }

    fun recipe(
        item: String,
        via: String,
    ) = HorizonGoal(
        item,
        HorizonGrounding.RECIPE,
        via,
        (held(item) + HORIZON_SPAN).coerceAtMost(MAX_SUCCESS_COUNT),
        false,
    )
    // A typed path from anything grounded, the footing included: dirt is a goal only when it feeds one.
    val firstSteps =
        (sources + footing.sorted()).flatMap { input ->
            recipes
                .madeFrom(input)
                .take(RECIPES_PER_INPUT)
                .filter { it !in footing }
                .map { recipe(it, input) }
        }
    firstSteps.forEach(::add)
    if (frontier) {
        for (item in sources) {
            val grounding = if (observed.inSight(item) > 0) HorizonGrounding.SIGHT else HorizonGrounding.RECALLED
            val most = (held(item) + (observed.capacity[item] ?: 0) + HORIZON_SPAN).coerceAtMost(MAX_SUCCESS_COUNT)
            add(HorizonGoal(item, grounding, null, most, true))
        }
    }
    for (step in firstSteps) {
        recipes.madeFrom(
            step.what,
        ).take(SECOND_STEP_RECIPES).filter { it !in footing }.forEach { add(recipe(it, step.what)) }
    }
    return chosen.values.take(MAX_HORIZON_GOALS)
}

/** The items [item]'s horizon recipe path is made from, nearest first: its `via`, then that one's. */
fun Map<String, HorizonGoal>.pathOf(item: String): List<String> =
    generateSequence(this[item]?.via) { this[it]?.via }.take(2).toList()
