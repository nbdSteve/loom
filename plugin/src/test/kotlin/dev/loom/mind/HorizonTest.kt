package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.contract.Bearing
import dev.loom.contract.BlockYield
import dev.loom.contract.ExploreTarget
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalReason
import dev.loom.contract.GoalStepResult
import dev.loom.contract.HorizonGoal
import dev.loom.contract.HorizonGrounding
import dev.loom.contract.Intent
import dev.loom.contract.InventoryStack
import dev.loom.contract.Observation
import dev.loom.contract.ObservationKind
import dev.loom.contract.Perception
import dev.loom.contract.RecalledSource
import dev.loom.contract.StepEvidence
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
    fun `a horizon goal is set past sight, stepped through its recipe path, and dirt may not crowd out looking`() {
        val ledger = GoalLedger(recipes = recipes)
        val agent = AgentId("agent-theta")
        val seen = scene(underfoot, tree).let { it.copy(horizon = horizonOf(it, recipes)) }

        fun answer(
            perception: Perception,
            intent: Intent,
            choice: GoalChoice,
        ): String? {
            ledger.present(agent, perception.requestId, perception.capturedTick, GoalObservation.of(perception))
            return ledger.resolve(agent, decisionFor(perception, intent).copy(goal = choice))?.outcome
        }
        // Dirt sight hands over whole, beside a cell to look from: refused.
        assertEquals(
            GoalEvidence.CROWDS_EXPLORATION,
            answer(seen, Intent.Dig("g1"), GoalChoice(GoalDisposition.ADOPT, successItem = DIRT, successCount = 1)),
        )
        // Nine logs, one in sight and the rest beyond the edge of sight: grounded, and capped.
        assertEquals(
            GoalEvidence.SUCCESS_UNOBTAINABLE,
            answer(seen, Intent.Dig("b1"), GoalChoice(GoalDisposition.ADOPT, successItem = LOG, successCount = 10)),
        )
        // Four sticks, stepped now by digging the log their planks are made from.
        assertEquals(
            GoalEvidence.ADOPTED,
            answer(seen, Intent.Dig("b1"), GoalChoice(GoalDisposition.ADOPT, successItem = STICK, successCount = 4)),
        )
        ledger.stepped(agent, seen.requestId, "dig", EventTag.ACCEPTED, null)
        // The log arrived: a gained input of the sticks' recipe path, not nothing.
        val after =
            scene(underfoot, tree, held = listOf(InventoryStack("i1", LOG, 1)))
                .let { it.copy(requestId = "r2", capturedTick = 200, horizon = horizonOf(it, recipes)) }
        val carried = ledger.present(agent, after.requestId, after.capturedTick, GoalObservation.of(after))
        assertEquals(StepEvidence.GAINED_INPUT, carried?.progress?.stepEvidence)
        assertEquals(0L, carried?.progress?.stepsWithoutProgress)
        // And a walk to look further is a step of it too.
        // A walk to look further while the log is still in reach is no step of it (r23: epsilon
        // walked away from two logs in dig reach).
        val looking = GoalChoice(GoalDisposition.CONTINUE, stepResult = GoalStepResult.PROGRESSING)
        val look = decisionFor(after, Intent.MoveTo("x1")).copy(goal = looking)
        assertEquals(GoalEvidence.STEP_UNPRODUCTIVE, ledger.resolve(agent, look)?.outcome)
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

    private companion object {
        const val DIRT = "item.minecraft.dirt"
        const val LOG = "item.minecraft.oak_log"
        const val PLANKS = "item.minecraft.oak_planks"
        const val STICK = "item.minecraft.stick"
        const val TABLE = "item.minecraft.crafting_table"
    }
}
