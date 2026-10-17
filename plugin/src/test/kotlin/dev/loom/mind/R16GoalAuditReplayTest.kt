package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.body.tagOf
import dev.loom.contract.Bearing
import dev.loom.contract.BlockYield
import dev.loom.contract.DropFate
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalStepResult
import dev.loom.contract.GoalSummary
import dev.loom.contract.Intent
import dev.loom.contract.Observation
import dev.loom.contract.ObservationKind
import dev.loom.contract.OwnDrop
import dev.loom.contract.StepEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The independent r16 goal-chain audit, replayed through the real ledger.
 *
 * Each case uses the inventory, intent, success target and what was in sight that one cited
 * agent's records carry in `research/experiments/2026-10-16-openai-10-agent-10m-r16/evidence.jsonl`;
 * the record ids are the audit's. Under r16's rules each passed or cost a review cycle: digs beside
 * the agent's own lying sticks, a stall refusal that blocked the right collect, one-block walks onto
 * terracotta credited as arrivals, and goals sized to every bush's best case.
 */
class R16GoalAuditReplayTest {
    private val ledger = GoalLedger()

    /** What one review saw: held items, the own drops lying by ref, what each ref gives, where. */
    private data class Scene(
        val inventory: Map<String, Long> = emptyMap(),
        val ownDrops: Map<String, String> = emptyMap(),
        val dropped: Set<String> = emptySet(),
        val gives: Map<String, Set<String>> = mapOf("bush" to setOf(STICK)),
        val at: List<Double> = listOf(0.5, 100.0, 0.5),
    )

    private fun review(
        review: String,
        scene: Scene,
        choice: GoalChoice?,
        intent: Intent,
        arrived: Boolean = true,
    ): Pair<GoalSummary?, GoalEvidence?> {
        val agent = AgentId(review.substringBefore('-'))
        val observed =
            GoalObservation(
                scene.inventory,
                scene.at,
                capacity = mapOf(STICK to 8L),
                visible = scene.gives.keys,
                dropped = scene.dropped,
                gives = scene.gives,
                ownDrops = scene.ownDrops,
            )
        val carried = ledger.present(agent, review, review.substringAfter('-').toLong(), observed)
        val decision = decisionFor(perception(requestId = review, agentId = agent.value), intent).copy(goal = choice)
        val evidence = ledger.resolve(agent, decision)
        val applied =
            if (evidence?.outcome in GoalEvidence.REFUSING) requireNotNull(evidence).outcome else EventTag.ACCEPTED
        val walk = intent is Intent.MoveTo
        ledger.stepped(agent, review, tagOf(intent), applied, reached = if (walk) arrived else null)
        return carried to evidence
    }

    private fun sticks(
        disposition: GoalDisposition,
        count: Long,
    ) = goalChoice(disposition, stepResult = GoalStepResult.NO_PROGRESS, success = STICK to count)

    private val dig = Intent.Dig("bush")

    @Test
    fun `alpha's dig beside its own lying sticks is refused and the collect is the step`() {
        // alpha body:00000023 dug a bush that dropped two own sticks; body:00000032 and 00000105
        // dug again while they lay offered as dda.
        review("alpha-23", Scene(), sticks(GoalDisposition.ADOPT, 2), dig)
        val lying = Scene(ownDrops = mapOf("dda" to STICK), dropped = setOf("alpha-23"), gives = GIVES)
        val (_, refused) = review("alpha-32", lying, goalChoice(GoalDisposition.CONTINUE), dig)
        assertEquals(GoalEvidence.OWN_DROP_IGNORED, refused?.outcome)
        assertTrue(GoalEvidence.OWN_DROP_IGNORED in GoalEvidence.REFUSING)
        val (_, collecting) =
            review("alpha-33", lying, goalChoice(GoalDisposition.CONTINUE), Intent.Collect("dda", approach = true))
        assertEquals(GoalEvidence.CONTINUED, collecting?.outcome)
        val (collected, _) =
            review("alpha-34", Scene(inventory = mapOf(STICK to 2L)), goalChoice(GoalDisposition.COMPLETE), Intent.Idle)
        assertEquals(StepEvidence.GAINED, collected?.progress?.stepEvidence)
        assertEquals(true, collected?.progress?.conditionMet)
    }

    @Test
    fun `kappa at the stall bound replaces its goal and collects in the same answer`() {
        // kappa body:00000077, 086 and 141 dug for nothing, then dropped two own sticks; the
        // continue at body:00000148 that collected them was refused as goalStalled, and kappa
        // collected the same entity a full cycle later at body:00000204.
        review("kappa-77", Scene(), sticks(GoalDisposition.ADOPT, 2), dig)
        review("kappa-86", Scene(), goalChoice(GoalDisposition.CONTINUE), dig)
        review("kappa-141", Scene(), goalChoice(GoalDisposition.CONTINUE), dig)
        val lying = Scene(ownDrops = mapOf("d7" to STICK), dropped = setOf("kappa-141"), gives = GIVES)
        val collect = Intent.Collect("d7", approach = true)
        val (stalled, _) = review("kappa-148", lying, goalChoice(GoalDisposition.CONTINUE), collect)
        assertEquals(MAX_STEPS, stalled?.progress?.stepsWithoutProgress)
        // The continue the r16 mind gave is refused as before; a replace in one answer is not.
        val held = ledger.present(AgentId("kappa"), "kappa-149", 149, null)
        assertEquals(null, held)
        val fresh = GoalLedger()
        replay(fresh, lying)
        val (_, replaced) = reviewIn(fresh, "kappa-148", lying, sticks(GoalDisposition.REPLACE, 2), collect)
        assertEquals(GoalEvidence.REPLACED, replaced?.outcome)
        assertEquals("kappa-77", replaced?.presentedGoalId)
        assertEquals("kappa-148", replaced?.goalId)
        val (gained, completed) =
            reviewIn(
                fresh,
                "kappa-150",
                Scene(inventory = mapOf(STICK to 2L)),
                goalChoice(GoalDisposition.COMPLETE),
                Intent.Idle,
            )
        assertEquals(0L, gained?.progress?.stepsWithoutProgress)
        assertEquals(GoalEvidence.COMPLETED, completed?.outcome)
    }

    /** kappa's three barren digs, replayed into [ledger]. */
    private fun replay(
        ledger: GoalLedger,
        lying: Scene,
    ) {
        reviewIn(ledger, "kappa-77", Scene(), sticks(GoalDisposition.ADOPT, 2), dig)
        reviewIn(ledger, "kappa-86", Scene(), goalChoice(GoalDisposition.CONTINUE), dig)
        reviewIn(ledger, "kappa-141", Scene(), goalChoice(GoalDisposition.CONTINUE), dig)
        assertTrue(lying.ownDrops.isNotEmpty())
    }

    private fun reviewIn(
        other: GoalLedger,
        review: String,
        scene: Scene,
        choice: GoalChoice?,
        intent: Intent,
    ): Pair<GoalSummary?, GoalEvidence?> {
        val agent = AgentId(review.substringBefore('-'))
        val observed =
            GoalObservation(
                scene.inventory,
                scene.at,
                capacity = mapOf(STICK to 8L),
                visible = scene.gives.keys,
                dropped = scene.dropped,
                gives = scene.gives,
                ownDrops = scene.ownDrops,
            )
        val carried = other.present(agent, review, review.substringAfter('-').toLong(), observed)
        val decision = decisionFor(perception(requestId = review, agentId = agent.value), intent).copy(goal = choice)
        val evidence = other.resolve(agent, decision)
        val applied =
            if (evidence?.outcome in GoalEvidence.REFUSING) requireNotNull(evidence).outcome else EventTag.ACCEPTED
        other.stepped(agent, review, tagOf(intent), applied)
        return carried to evidence
    }

    @Test
    fun `delta's one-block walks onto terracotta are no arrival and reset nothing`() {
        // delta body:00000178 walked 0.8 block onto terracotta and body:00000184 credited the
        // arrival and reset the stall count; it walked 0.8 block back.
        val terracotta = mapOf("b00" to emptySet<String>(), "b01" to emptySet(), "bush" to setOf(STICK))
        val start = Scene(inventory = mapOf(STICK to 2L), gives = terracotta, at = listOf(8.97, 100.0, 7.01))
        review("delta-178", start, sticks(GoalDisposition.ADOPT, 3), Intent.MoveTo("b00"))
        val (walked, _) =
            review(
                "delta-184",
                start.copy(at = listOf(9.8, 100.0, 7.01)),
                goalChoice(GoalDisposition.CONTINUE),
                Intent.MoveTo("b01"),
            )
        assertEquals(StepEvidence.NONE, walked?.progress?.stepEvidence)
        assertEquals(1L, walked?.progress?.stepsWithoutProgress)
        // A walk onto the bush that drops the goal's sticks, somewhere new, is the arrival.
        val (back, _) =
            review(
                "delta-238",
                start.copy(at = listOf(8.97, 100.0, 7.01)),
                goalChoice(GoalDisposition.CONTINUE),
                Intent.MoveTo("bush"),
            )
        assertEquals(StepEvidence.NONE, back?.progress?.stepEvidence)
        val (bush, _) =
            review(
                "delta-239",
                start.copy(at = listOf(12.5, 100.0, 3.5)),
                goalChoice(GoalDisposition.CONTINUE),
                Intent.Idle,
            )
        assertEquals(StepEvidence.ARRIVED, bush?.progress?.stepEvidence)
    }

    @Test
    fun `a goal sized to a bush's best case is beyond what sight can be counted on to give`() {
        // beta body:00000228: three sticks held and one bush of nought to two in sight; a goal of
        // five needed two maximum rolls and failed.
        val bush =
            Observation(
                kind = ObservationKind.BLOCK,
                what = "block.minecraft.dead_bush",
                distance = 2.0,
                direction = Bearing.NORTH,
                verticalOffset = 0.0,
                visible = true,
                ref = "b1",
                standable = true,
                yields = listOf(BlockYield(STICK, 0, 2)),
                digSafe = true,
            )
        val seen =
            perception().let {
                it.copy(
                    surroundings = listOf(bush),
                    selfState =
                        it.selfState.copy(
                            inventorySummary = listOf(dev.loom.contract.InventoryStack("i1", STICK, 3)),
                            ownDrops =
                                listOf(
                                    OwnDrop("beta-1", STICK, 1, DropFate.TAKEN),
                                ),
                        ),
                )
            }
        val observed = GoalObservation.of(seen)
        assertEquals(mapOf(STICK to 1L), observed.capacity)
        val beta = AgentId("beta")
        ledger.present(beta, "beta-228", 228, observed)
        val decision = decisionFor(perception(requestId = "beta-228", agentId = "beta"), Intent.Dig("b1"))
        val five = ledger.resolve(beta, decision.copy(goal = sticks(GoalDisposition.ADOPT, 5)))
        assertEquals(GoalEvidence.SUCCESS_UNOBTAINABLE, five?.outcome)
        ledger.present(beta, "beta-229", 229, observed)
        val four =
            ledger.resolve(
                beta,
                decision.copy(requestId = "beta-229", goal = sticks(GoalDisposition.ADOPT, 4)),
            )
        assertEquals(GoalEvidence.ADOPTED, four?.outcome)
    }

    private companion object {
        const val STICK = "item.minecraft.stick"
        val MAX_STEPS = dev.loom.contract.MAX_GOAL_STEPS_WITHOUT_PROGRESS
        val GIVES = mapOf("bush" to setOf(STICK), "dda" to setOf(STICK), "d7" to setOf(STICK))
    }
}
