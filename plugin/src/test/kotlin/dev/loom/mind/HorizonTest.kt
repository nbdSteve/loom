package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.contract.Bearing
import dev.loom.contract.BlockYield
import dev.loom.contract.CraftingOption
import dev.loom.contract.ExploreTarget
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalReason
import dev.loom.contract.GoalStepResult
import dev.loom.contract.HorizonGoal
import dev.loom.contract.HorizonGrounding
import dev.loom.contract.Intent
import dev.loom.contract.InventoryStack
import dev.loom.contract.MAX_HORIZON_GOALS
import dev.loom.contract.Observation
import dev.loom.contract.ObservationKind
import dev.loom.contract.Perception
import dev.loom.contract.RecalledSource
import dev.loom.contract.Trigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * r22: every goal was dirt from underfoot or a single seed, because a success count could never
 * pass what sight handed over whole, and no agent looked for wood.
 */
class HorizonTest {
    private val recipes =
        object : RecipeInputs {
            override fun inputsOf(item: String): Set<String> =
                mapOf(PLANKS to setOf(LOG), STICK to setOf(PLANKS))[item].orEmpty()

            override fun madeFrom(input: String): List<String> =
                mapOf(LOG to listOf(PLANKS), PLANKS to listOf(STICK, TABLE))[input].orEmpty()
        }

    private fun block(
        ref: String,
        what: String,
        item: String,
        distance: Double,
        rise: Double,
    ) = Observation(
        kind = ObservationKind.BLOCK,
        what = "block.minecraft.$what",
        distance = distance,
        direction = Bearing.NORTH,
        verticalOffset = rise,
        visible = true,
        ref = ref,
        standable = true,
        yields = listOf(BlockYield(item, 1, 1, 100)),
        digSafe = true,
    )

    private val underfoot = block("g1", "grass_block", DIRT, 0.5, -0.5)
    private val tree = block("b1", "oak_log", LOG, 3.0, 0.5)
    private val edge = ExploreTarget("x1", 12.0, Bearing.NORTH, 0.0)

    private fun scene(
        vararg seen: Observation,
        explore: Boolean = true,
        recalled: List<RecalledSource> = emptyList(),
        held: List<InventoryStack> = emptyList(),
    ): Perception {
        val base = perception(requestId = "r1", trigger = Trigger.GoalReview)
        return base.copy(
            surroundings = seen.toList(),
            explore = if (explore) listOf(edge) else emptyList(),
            recalled = recalled,
            selfState = base.selfState.copy(inventorySummary = held),
        )
    }

    @Test
    fun `the footing grounds no horizon, a source in sight reaches past itself only onto the frontier`() {
        assertEquals(setOf(DIRT), footingOf(scene(underfoot, tree)))
        val horizon = horizonOf(scene(underfoot, tree), recipes).associateBy { it.what }
        assertTrue(DIRT !in horizon, "dirt underfoot grounds nothing")
        assertEquals(HorizonGoal(LOG, HorizonGrounding.SIGHT, null, 1 + HORIZON_SPAN, true), horizon[LOG])
        // A typed recipe path from the log, two steps deep and capped.
        assertEquals(HorizonGoal(PLANKS, HorizonGrounding.RECIPE, LOG, HORIZON_SPAN, false), horizon[PLANKS])
        assertEquals(PLANKS, horizon[STICK]?.via)
        assertEquals(PLANKS, horizon[TABLE]?.via)
        assertEquals(listOf(PLANKS, LOG), horizon.pathOf(STICK))
        // With nowhere further to look, a source in sight is no horizon of its own.
        assertTrue(horizonOf(scene(underfoot, tree, explore = false), recipes).none { it.what == LOG })
        // A remembered tree grounds one just as well.
        val recalled = RecalledSource(LOG, 18.0, Bearing.NORTH, 0.0, 300)
        assertEquals(
            HorizonGrounding.RECALLED,
            horizonOf(scene(underfoot, recalled = listOf(recalled)), recipes).single { it.what == LOG }.grounding,
        )
    }

    @Test
    fun `a craft-only item is labelled by its recipe, and a switch into it from the held goal's item is a chain`() {
        // r23: sticks a craft would make were labelled seen, with more beyond the edge of sight.
        val planks = InventoryStack("i1", PLANKS, 2)
        val sticks = dev.loom.contract.CraftingOption("r1", STICK, 4, 1, false)
        val craft =
            scene(underfoot, held = listOf(planks)).let {
                it.copy(selfState = it.selfState.copy(craftingOptions = listOf(sticks)))
            }
        val horizon = horizonOf(craft, recipes).associateBy { it.what }
        // It reaches past the four its one craft makes, since one craft is one step (the r39 offline audit).
        assertEquals(HorizonGoal(STICK, HorizonGrounding.RECIPE, PLANKS, 4 + HORIZON_SPAN, false), horizon[STICK])
        // r23 eta: a log goal holding logs switches into planks before its stall bound, and the
        // planks goal is chained to it.
        val ledger = GoalLedger(recipes = recipes)
        val agent = AgentId("agent-eta")
        val first = scene(underfoot, tree).let { it.copy(horizon = horizonOf(it, recipes)) }
        ledger.present(agent, first.requestId, first.capturedTick, GoalObservation.of(first))
        val adopt = GoalChoice(GoalDisposition.ADOPT, successItem = LOG, successCount = 4)
        assertEquals(
            GoalEvidence.ADOPTED,
            ledger.resolve(agent, decisionFor(first, Intent.Dig("b1")).copy(goal = adopt))?.outcome,
        )
        ledger.stepped(agent, first.requestId, "dig", EventTag.ACCEPTED, null)
        val holding =
            scene(underfoot, tree, held = listOf(InventoryStack("i2", LOG, 2)))
                .let { it.copy(requestId = "r2", capturedTick = 200, horizon = horizonOf(it, recipes)) }
        ledger.present(agent, holding.requestId, holding.capturedTick, GoalObservation.of(holding))
        val switch =
            GoalChoice(
                GoalDisposition.REPLACE,
                successItem = PLANKS,
                successCount = 8,
                stepResult = GoalStepResult.PROGRESSING,
                reason = GoalReason.SUPERSEDED,
            )
        val replaced = ledger.resolve(agent, decisionFor(holding, Intent.Dig("b1")).copy(goal = switch))
        assertEquals(GoalEvidence.REPLACED, replaced?.outcome)
        assertEquals(first.requestId, replaced?.chainedFrom)
    }

    @Test
    fun `a held goal's own item and its path are never cut from a full horizon`() {
        // The round 3 offline audit: eight crafts made from the planks held filled the horizon, so
        // "hold 9 oak_planks" beside a dig-safe log read as one nothing reaches, and impossible was
        // offered and accepted.
        val made = (1..MAX_HORIZON_GOALS).map { "item.minecraft.made_$it" }
        val full =
            object : RecipeInputs {
                override fun inputsOf(item: String): Set<String> =
                    if (item in made) setOf(PLANKS) else recipes.inputsOf(item)

                override fun madeFrom(input: String): List<String> = if (input == LOG) listOf(PLANKS) else emptyList()
            }
        val base = scene(underfoot, tree, held = listOf(InventoryStack("i1", PLANKS, 4)))
        val seen =
            base.copy(
                selfState =
                    base.selfState.copy(
                        craftingOptions =
                            made.mapIndexed { index, item -> CraftingOption("r$index", item, 1, 1, false) },
                    ),
            )
        val unheld = horizonOf(seen, full)
        assertEquals(MAX_HORIZON_GOALS, unheld.size)
        assertTrue(unheld.none { it.what == PLANKS }, unheld.toString())
        val kept = horizonOf(seen, full, held = PLANKS)
        assertEquals(MAX_HORIZON_GOALS, kept.size)
        assertEquals(HorizonGoal(PLANKS, HorizonGrounding.RECIPE, LOG, 12, false), kept.first())
        val observed = GoalObservation.of(seen.copy(horizon = kept))
        assertTrue(observed.reachOf(PLANKS) >= 9)
        assertTrue(!observed.steplessFor(PLANKS), "the log in reach is its step")
    }

    @Test
    fun `a held goal's horizon over a registry-sized recipe map is a few map reads, never a stall`() {
        // The path rehearsal's main thread stopped at tick 1310 for 509 s, which the host's own power
        // log showed was a clamshell sleep. This bounds what the held goal's path search costs, by
        // the reads it makes rather than a clock: 1290 results over 600 sole inputs, the size of the
        // 1.21.1 registry, with a goal nothing grounds, so every input and every first step is searched.
        val items = (0 until 600).map { "item.minecraft.input_$it" }
        val results = (0 until 1290).map { "item.minecraft.result_$it" }
        val made =
            items.withIndex().associate { (index, item) ->
                item to (0 until 3).map { results[(index * 3 + it) % results.size] }
            }
        var reads = 0
        val wide =
            object : RecipeInputs {
                override fun inputsOf(item: String): Set<String> = emptySet()

                override fun madeFrom(input: String): List<String> {
                    reads += 1
                    return made[input].orEmpty()
                }
            }
        val held = items.take(36).mapIndexed { index, item -> InventoryStack("i$index", item, 1) }
        val seen = scene(underfoot, tree, held = held)
        horizonOf(seen, wide, held = "item.minecraft.unreachable")
        // Each grounded input is read once for its first steps and twice by the held path (as a direct
        // input, then for its first steps), and each of its three results once for a second step and
        // once by the held path: 1 + 3 + 2 + 3.
        val inputs = held.size + 1
        assertTrue(reads <= inputs * HELD_READS_PER_INPUT, "$reads recipe reads for $inputs grounded inputs")
    }

    private companion object {
        /** The most recipe reads one grounded input with three results costs a horizon with a held goal. */
        const val HELD_READS_PER_INPUT = 9
        const val DIRT = "item.minecraft.dirt"
        const val LOG = "item.minecraft.oak_log"
        const val PLANKS = "item.minecraft.oak_planks"
        const val STICK = "item.minecraft.stick"
        const val TABLE = "item.minecraft.crafting_table"
    }
}
