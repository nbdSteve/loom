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
        ledger.settledAt(agent, "r1", 250)
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
    fun `a reveal is the walk's own, seen straight after it settled, and a recalled source meets the goal`() {
        // r24 eta 9u was credited for a peer's drop 2300 ticks after its walk ended.
        val ledger = GoalLedger(recipes = recipes)
        val bare = scene("r1", 100, 0.0, underfoot)
        assertEquals(GoalEvidence.ADOPTED, ledger.answer(bare, Intent.MoveTo("x1"), discover)?.outcome)
        ledger.stepped(agent, "r1", "moveTo", EventTag.ACCEPTED, reached = true)
        ledger.settledAt(agent, "r1", 200)
        val late = scene("r2", 200 + REVEAL_WINDOW_TICKS + 1, 12.0, underfoot, tree)
        val shown = ledger.present(agent, late.requestId, late.capturedTick, GoalObservation.of(late))
        assertEquals(true, shown?.progress?.conditionMet)
        assertEquals(StepEvidence.NONE, shown?.progress?.stepEvidence, "no credit for what a later review saw")
        // r24 zeta: a log remembered out of sight meets the discovery, which may then become a log goal.
        val remembered =
            dev.loom.contract.HorizonGoal(LOG, dev.loom.contract.HorizonGrounding.RECALLED, null, 9, true)
        val recalling = GoalObservation.of(bare).copy(horizon = mapOf(LOG to remembered))
        assertTrue(recalling.discoveryMet)
        // And a held discovery with somewhere to look is never abandoned before its stall bound.
        val other = GoalLedger(recipes = recipes)
        assertEquals(GoalEvidence.ADOPTED, other.answer(bare, Intent.MoveTo("x1"), discover)?.outcome)
        other.stepped(agent, "r1", "moveTo", EventTag.ACCEPTED, reached = true)
        val again = scene("r3", 300, 12.0, underfoot)
        val abandon = GoalChoice(GoalDisposition.ABANDON, stepResult = GoalStepResult.NO_PROGRESS)
        assertEquals(GoalEvidence.ABANDONED_ACHIEVABLE, other.answer(again, Intent.MoveTo("x1"), abandon)?.outcome)
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

    private fun hunter(distance: Double) =
        Observation(
            kind = ObservationKind.ENTITY,
            what = "entity.minecraft.zombie",
            distance = distance,
            direction = Bearing.WEST,
            verticalOffset = 0.0,
            visible = true,
            ref = "e1",
            hunts = true,
        )

    private val bread = dev.loom.contract.InventoryStack("i1", "item.minecraft.bread", 2)

    private fun Perception.fed(food: Int): Perception =
        copy(selfState = selfState.copy(food = food, inventorySummary = listOf(bread)))

    @Test
    fun `a survival response defers a mandatory or held discovery goal and is never credited to it`() {
        val ledger = GoalLedger(recipes = recipes)
        // Nothing grounded, a zombie close: the flight defers the discovery the review owed.
        val hunted = scene("r1", 100, 0.0, underfoot, hunter(4.0)).fed(20)
        assertEquals(GoalEvidence.DEFERRED, ledger.answer(hunted, Intent.Flee("e1"), null)?.outcome)
        assertEquals(
            GoalEvidence.DEFERRED,
            ledger.answer(hunted.copy(requestId = "r1b"), Intent.Attack("e1", "i1", 2, false), null)?.outcome,
        )
        // A fed meal is no survival response: beside the zombie it is withheld, and away from one it
        // leaves the discovery unaddressed. A hungry meal is one.
        assertEquals(
            GoalEvidence.SURVIVAL_REQUIRED,
            ledger.answer(hunted.copy(requestId = "r1c"), Intent.Eat("i1"), null)?.outcome,
        )
        val fedAlone = scene("r1e", 100, 0.0, underfoot).fed(20)
        assertEquals(GoalEvidence.UNADDRESSED, ledger.answer(fedAlone, Intent.Eat("i1"), null)?.outcome)
        val hungry = scene("r1d", 100, 0.0, underfoot).fed(HUNGRY_FOOD_LEVEL.toInt())
        assertEquals(GoalEvidence.DEFERRED, ledger.answer(hungry, Intent.Eat("i1"), null)?.outcome)
        // Held, it is deferred the same way, and nothing else steps aside from it.
        val bare = scene("r2", 200, 0.0, underfoot)
        assertEquals(GoalEvidence.ADOPTED, ledger.answer(bare, Intent.MoveTo("x1"), discover)?.outcome)
        ledger.stepped(agent, "r2", "moveTo", EventTag.ACCEPTED, reached = true)
        val walked = scene("r3", 400, 12.0, underfoot, hunter(4.0)).fed(HUNGRY_FOOD_LEVEL.toInt())
        val stalledBefore =
            ledger.present(
                agent,
                "probe",
                400,
                GoalObservation.of(walked),
            )?.progress?.stepsWithoutProgress
        ledger.forget(agent, "probe")
        assertEquals(GoalEvidence.SURVIVAL_REQUIRED, ledger.answer(walked, Intent.Dig("g1"), null)?.outcome)
        assertEquals(
            GoalEvidence.DEFERRED,
            ledger.answer(walked.copy(requestId = "r3b"), Intent.Flee("e1"), null)?.outcome,
        )
        ledger.stepped(agent, "r3b", "flee", EventTag.ACCEPTED, reached = true)
        // The flight ended beside a tree: the discovery is met, but the flight is no reveal of it.
        val fled = scene("r4", 600, 30.0, underfoot, tree)
        val shown = ledger.present(agent, fled.requestId, fled.capturedTick, GoalObservation.of(fled))
        assertEquals(true, shown?.progress?.conditionMet)
        assertEquals(StepEvidence.NONE, shown?.progress?.stepEvidence)
        assertEquals(stalledBefore, shown?.progress?.stepsWithoutProgress)
        assertEquals("r2", shown?.goalId)
    }

    private companion object {
        const val DIRT = "item.minecraft.dirt"
        const val LOG = "item.minecraft.oak_log"
        const val PLANKS = "item.minecraft.oak_planks"

        /** The adopting walk and two more that reveal nothing reach the stall bound at the third. */
        const val MAX_DISCOVERY_WALKS = 3
    }
}
