package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.body.tagOf
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalStepResult
import dev.loom.contract.GoalSummary
import dev.loom.contract.Intent
import dev.loom.contract.StepEvidence
import org.junit.jupiter.api.Assertions.assertEquals
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
        val inReach: Set<String> = emptySet(),
        val capacity: Map<String, Long> = mapOf(STICK to 8L),
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
                capacity = scene.capacity,
                visible = scene.gives.keys,
                dropped = scene.dropped,
                gives = scene.gives,
                ownDrops = scene.ownDrops,
                inReach = scene.inReach,
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
    fun `kappa's dig that dropped two sticks is no stall while they lie, and the collect is the step`() {
        // kappa body:00000077, 086 and 141 dug, the last dropping two own sticks; under r16's rules
        // that third dig reached the stall bound and the continue that collected them at
        // body:00000148 was refused as goalStalled. A drop still lying in reach is no stall (r18:
        // all three stalls fired on such a dig), so the collect is taken.
        review("kappa-77", Scene(), sticks(GoalDisposition.ADOPT, 2), dig)
        review("kappa-86", Scene(), goalChoice(GoalDisposition.CONTINUE), dig)
        review("kappa-141", Scene(), goalChoice(GoalDisposition.CONTINUE), dig)
        val lying = Scene(ownDrops = mapOf("d7" to STICK), dropped = setOf("kappa-141"), gives = GIVES)
        val collect = Intent.Collect("d7", approach = true)
        val (pending, collecting) = review("kappa-148", lying, goalChoice(GoalDisposition.CONTINUE), collect)
        assertEquals(StepEvidence.DROPPED, pending?.progress?.stepEvidence)
        assertEquals(MAX_STEPS - 1, pending?.progress?.stepsWithoutProgress)
        assertEquals(GoalEvidence.CONTINUED, collecting?.outcome)
        val (gained, completed) =
            review(
                "kappa-150",
                // From routine-v28 a plain completion stands only with no next goal in sight.
                Scene(inventory = mapOf(STICK to 2L), capacity = emptyMap()),
                goalChoice(GoalDisposition.COMPLETE),
                Intent.Idle,
            )
        assertEquals(0L, gained?.progress?.stepsWithoutProgress)
        assertEquals(GoalEvidence.COMPLETED, completed?.outcome)
    }

    private companion object {
        const val STICK = "item.minecraft.stick"
        val MAX_STEPS = dev.loom.contract.MAX_GOAL_STEPS_WITHOUT_PROGRESS
        val GIVES = mapOf("bush" to setOf(STICK), "dda" to setOf(STICK), "d7" to setOf(STICK))
    }
}
