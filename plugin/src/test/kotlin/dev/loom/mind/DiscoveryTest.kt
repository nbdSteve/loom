package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.contract.Bearing
import dev.loom.contract.BlockYield
import dev.loom.contract.ExploreTarget
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalStepResult
import dev.loom.contract.Intent
import dev.loom.contract.Observation
import dev.loom.contract.ObservationKind
import dev.loom.contract.Perception
import dev.loom.contract.StepEvidence
import dev.loom.contract.Trigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * r23: 17 of 70 reviews could set no goal at all, because nothing they saw, remembered or held
 * grounded one, and the walks they took to look further were measured by nothing.
 */
class DiscoveryTest {
    private val recipes =
        object : RecipeInputs {
            override fun inputsOf(item: String): Set<String> = mapOf(PLANKS to setOf(LOG))[item].orEmpty()

            override fun madeFrom(input: String): List<String> = mapOf(LOG to listOf(PLANKS))[input].orEmpty()
        }
    private val agent = AgentId("agent-kappa")
    private val discover = GoalChoice(GoalDisposition.ADOPT, discover = true)

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

    private fun scene(
        requestId: String,
        tick: Long,
        x: Double,
        vararg seen: Observation,
        explore: Boolean = true,
    ): Perception {
        val base = perception(requestId = requestId, trigger = Trigger.GoalReview)
        return base
            .copy(
                capturedTick = tick,
                surroundings = seen.toList(),
                explore = if (explore) listOf(ExploreTarget("x1", 12.0, Bearing.EAST, 0.0)) else emptyList(),
                selfState = base.selfState.copy(position = listOf(x, 64.0, 0.0)),
            ).withHorizon(recipes)
    }

    private fun GoalLedger.answer(
        perception: Perception,
        intent: Intent,
        choice: GoalChoice?,
    ): GoalEvidence? {
        present(agent, perception.requestId, perception.capturedTick, GoalObservation.of(perception))
        return resolve(agent, decisionFor(perception, intent).copy(goal = choice))
    }

    @Test
    fun `a discovery goal is set with nothing grounded, walked to the frontier and revealed into a log goal`() {
        val ledger = GoalLedger(recipes = recipes)
        val bare = scene("r1", 100, 0.0, underfoot)
        assertTrue(bare.horizon.isEmpty(), "dirt underfoot grounds nothing")
        // Its step is a walk to the edge of sight, nothing else.
        assertEquals(GoalEvidence.STEP_UNPRODUCTIVE, ledger.answer(bare, Intent.Dig("g1"), discover)?.outcome)
        val adopted = ledger.answer(bare, Intent.MoveTo("x1"), discover)
        assertEquals(GoalEvidence.ADOPTED, adopted?.outcome)
        assertEquals(true, adopted?.discovery)
        ledger.stepped(agent, "r1", "moveTo", EventTag.ACCEPTED, reached = true)
        // The walk revealed a tree: the condition is met, and the step typed as revealed.
        val after = scene("r2", 300, 12.0, underfoot, tree)
        val shown = ledger.present(agent, after.requestId, after.capturedTick, GoalObservation.of(after))
        assertEquals(DISCOVERY_STATEMENT, shown?.statement)
        assertEquals(true, shown?.progress?.conditionMet)
        assertEquals(StepEvidence.REVEALED, shown?.progress?.stepEvidence)
        assertEquals(0L, shown?.progress?.stepsWithoutProgress)
        // Never completed bare, nor continued: it becomes a goal for what it revealed.
        val done = GoalChoice(GoalDisposition.COMPLETE, stepResult = GoalStepResult.DONE)
        assertEquals(
            GoalEvidence.COMPLETED_WITHOUT_NEXT,
            ledger.resolve(agent, decisionFor(after, Intent.Dig("b1")).copy(goal = done))?.outcome,
        )
        val logs =
            GoalChoice(GoalDisposition.REPLACE, successItem = LOG, successCount = 4, stepResult = GoalStepResult.DONE)
        val replaced = ledger.answer(after, Intent.Dig("b1"), logs)
        assertEquals(GoalEvidence.COMPLETED_ADOPTED, replaced?.outcome)
        assertEquals(true, replaced?.presentedDiscovery)
        assertEquals("r1", replaced?.discoveredFrom)
        assertNull(replaced?.discovery)
    }

    @Test
    fun `a discovery goal is refused where an item goal is grounded, and stalls on walks that reveal nothing`() {
        val ledger = GoalLedger(recipes = recipes)
        val wooded = scene("r1", 100, 0.0, underfoot, tree)
        assertEquals(GoalEvidence.DISCOVERY_NOT_OFFERED, ledger.answer(wooded, Intent.MoveTo("x1"), discover)?.outcome)
        val blind = scene("r2", 100, 0.0, underfoot, explore = false)
        assertEquals(GoalEvidence.DISCOVERY_NOT_OFFERED, ledger.answer(blind, Intent.Idle, discover)?.outcome)
        val bare = scene("r3", 100, 0.0, underfoot)
        assertEquals(GoalEvidence.ADOPTED, ledger.answer(bare, Intent.MoveTo("x1"), discover)?.outcome)
        val walking = GoalChoice(GoalDisposition.CONTINUE, stepResult = GoalStepResult.NO_PROGRESS)
        var previous = "r3"
        for (step in 1..MAX_DISCOVERY_WALKS) {
            ledger.stepped(agent, previous, "moveTo", EventTag.ACCEPTED, reached = true)
            val next = scene("w$step", 100L + step * 200, 12.0 * step, underfoot)
            val outcome = ledger.answer(next, Intent.MoveTo("x1"), walking)?.outcome
            val expected = if (step < MAX_DISCOVERY_WALKS) GoalEvidence.CONTINUED else GoalEvidence.STALLED
            assertEquals(expected, outcome, "walk $step")
            previous = next.requestId
        }
    }

    private companion object {
        const val DIRT = "item.minecraft.dirt"
        const val LOG = "item.minecraft.oak_log"
        const val PLANKS = "item.minecraft.oak_planks"

        /** The adopting walk and two more that reveal nothing reach the stall bound at the third. */
        const val MAX_DISCOVERY_WALKS = 3
    }
}
