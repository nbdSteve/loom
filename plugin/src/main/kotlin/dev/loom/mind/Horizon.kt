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
import dev.loom.contract.reviewsGoal
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

/** A held goal's item and the two steps of its recipe path, which the horizon keeps. */
private const val PATH_ITEMS = 3

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

/**
 * The horizon goals [perception] may set, by [recipes]: at most [MAX_HORIZON_GOALS], the [held] goal's
 * own item and its recipe path first, then recipe paths.
 */
fun horizonOf(
    perception: Perception,
    recipes: RecipeInputs,
    held: String? = null,
): List<HorizonGoal> = HorizonBuilder(perception, recipes, held).build()

/** [this] as a goal review holding a goal of [held] is shown it, with its horizon; any other perception as it is. */
fun Perception.withHorizon(
    recipes: RecipeInputs,
    held: String? = null,
): Perception = if (trigger.reviewsGoal) copy(horizon = horizonOf(this, recipes, held)) else this

/** One perception's horizon, assembled in its order of preference. */
private class HorizonBuilder(
    private val perception: Perception,
    private val recipes: RecipeInputs,
    private val held: String?,
) {
    private val observed = GoalObservation.of(perception)
    private val footing = footingOf(perception)

    // What the world gives, not what a craft makes: a craft-only item is grounded by its recipe
    // and labelled so (r23: sticks a craft would make were labelled seen, with more beyond the edge).
    private val sources =
        GoalObservation
            .of(perception.copy(selfState = perception.selfState.copy(craftingOptions = emptyList())))
            .capacity
            .keys
            .filter { it !in footing }
            .sorted()
    private val chosen = linkedMapOf<String, HorizonGoal>()

    private fun held(item: String): Long = observed.inventory[item] ?: 0

    private fun add(goal: HorizonGoal) {
        if (goal.what !in chosen && goal.what !in footing && held(goal.what) < goal.most) chosen[goal.what] = goal
    }

    private fun recipe(
        item: String,
        via: String,
        most: Long = held(item) + HORIZON_SPAN,
    ) = HorizonGoal(item, HorizonGrounding.RECIPE, via, most.coerceAtMost(MAX_SUCCESS_COUNT), false)

    fun build(): List<HorizonGoal> {
        craftableNow().forEach(::add)
        // A typed path from anything grounded - held, given, or the footing: dirt is a goal only
        // when it feeds one.
        val inputs = (sources + footing.sorted() + observed.inventory.keys.sorted()).distinct()
        val firstSteps =
            inputs.flatMap { input -> recipes.madeFrom(input).take(RECIPES_PER_INPUT).map { recipe(it, input) } }
        firstSteps.forEach(::add)
        if (perception.explore.isNotEmpty()) sources.forEach { add(frontierOf(it)) }
        for (step in firstSteps) {
            recipes.madeFrom(step.what).take(SECOND_STEP_RECIPES).forEach { add(recipe(it, step.what)) }
        }
        held?.let { heldPathOf(it, inputs) }
        // The held goal's own item and its path are never cut: a horizon full of other items would
        // read the held goal as one nothing reaches, and offer it impossible beside a source that
        // meets it (the round 3 offline audit).
        val kept = held?.let { generateSequence(it) { item -> chosen[item]?.via }.take(PATH_ITEMS).toSet() }.orEmpty()
        val (first, rest) = chosen.values.partition { it.what in kept }
        return (first + rest).take(MAX_HORIZON_GOALS)
    }

    /**
     * The held goal [item]'s grounding when the cuts above passed it over: a recipe from a grounded
     * input, or from a first step made from one, by every recipe rather than the fewest-slot few.
     */
    private fun heldPathOf(
        item: String,
        inputs: List<String>,
    ) {
        val direct = inputs.firstOrNull { item in recipes.madeFrom(it) }
        val viaStep =
            inputs.firstNotNullOfOrNull { input ->
                recipes.madeFrom(input).firstOrNull { item in recipes.madeFrom(it) }?.let { input to it }
            }
        when {
            item in chosen -> Unit
            direct != null -> add(recipe(item, direct))
            viaStep != null -> {
                add(recipe(viaStep.second, viaStep.first))
                add(recipe(item, viaStep.second))
            }
        }
    }

    /**
     * The crafts the agent can make now, each made from an input it holds, reaching [HORIZON_SPAN]
     * past what the craft gives: an objective asks more than one step could give, and one craft of
     * every application is one step (the r39 offline audit), so a goal of a craftable item reaches
     * past it or is no goal at all.
     */
    private fun craftableNow(): List<HorizonGoal> =
        perception.selfState.craftingOptions.mapNotNull { option ->
            recipes.inputsOf(option.what).sorted().firstOrNull { held(it) > 0 }?.let { via ->
                val crafted = held(option.what) + option.resultCount * option.maxCrafts
                recipe(option.what, via, crafted + HORIZON_SPAN)
            }
        }

    /** A source's goal, reaching past what it gives onto the frontier. */
    private fun frontierOf(item: String): HorizonGoal {
        val grounding = if (observed.inSight(item) > 0) HorizonGrounding.SIGHT else HorizonGrounding.RECALLED
        val most = (held(item) + (observed.capacity[item] ?: 0) + HORIZON_SPAN).coerceAtMost(MAX_SUCCESS_COUNT)
        return HorizonGoal(item, grounding, null, most, true)
    }
}

/** The items [item]'s horizon recipe path is made from, nearest first: its `via`, then that one's. */
fun Map<String, HorizonGoal>.pathOf(item: String): List<String> =
    generateSequence(this[item]?.via) { this[it]?.via }.take(2).toList()
