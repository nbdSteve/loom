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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The independent r17 goal-chain audit, replayed through the real ledger and schedule.
 *
 * Each case uses the inventory, intent, success target and what was in sight that one cited
 * agent's records carry in `research/experiments/2026-10-16-openai-10-agent-10m-r17/evidence.jsonl`;
 * the record ids are the audit's. Under r17's rules each passed: a replace that only relabelled a
 * stalled goal, an abandon of a goal one stick short with a bush in sight, every completion idling
 * for a full cycle, a step text naming terracotta while its intent dug a bush, and a drop left
 * lying with no review owed until another agent took it.
 */
class R17GoalAuditReplayTest {
    private val ledger = GoalLedger()

    private data class Scene(
        val inventory: Map<String, Long> = emptyMap(),
        val capacity: Map<String, Long> = mapOf(STICK to 4L),
    )

    private fun review(
        review: String,
        scene: Scene,
        choice: GoalChoice?,
        intent: Intent,
    ): Pair<GoalSummary?, GoalEvidence?> {
        val agent = AgentId(review.substringBefore('-'))
        val observed =
            GoalObservation(
                scene.inventory,
                listOf(0.5, 100.0, 0.5),
                capacity = scene.capacity,
                visible = setOf("bush"),
                gives = mapOf("bush" to setOf(STICK)),
                stepSources = mapOf("bush" to setOf(STICK)),
            )
        val carried = ledger.present(agent, review, review.substringAfter('-').toLong(), observed)
        val decision = decisionFor(perception(requestId = review, agentId = agent.value), intent).copy(goal = choice)
        val evidence = ledger.resolve(agent, decision)
        val applied =
            if (evidence?.outcome in GoalEvidence.REFUSING) requireNotNull(evidence).outcome else EventTag.ACCEPTED
        ledger.stepped(agent, review, tagOf(intent), applied)
        return carried to evidence
    }

    private fun sticks(
        disposition: GoalDisposition,
        count: Long,
    ) = GoalChoice(
        disposition,
        successItem = STICK,
        successCount = count,
        stepResult = GoalStepResult.NO_PROGRESS.takeIf { disposition != GoalDisposition.ADOPT },
    )

    @Test
    fun `epsilon's step is worded from its intent, not from its text`() {
        // epsilon body:00000177 said it dug terracotta for sticks while its intent dug a bush.
        val claimed = sticks(GoalDisposition.ADOPT, 5).copy(step = "dig the terracotta, which drops sticks")
        review("epsilon-177", Scene(capacity = mapOf(STICK to 8L)), claimed, Intent.Dig("bush"))
        val held = ledger.present(AgentId("epsilon"), "epsilon-244", 244, null)
        assertEquals("dig bush", held?.step)
    }

    @Test
    fun `beta's drop left lying at its follow-up owes one more review`() {
        // beta body:00000041/043: the dig at its one follow-up dropped two sticks at tick 410, no
        // review came until 2665, and gamma took them at 843.
        val schedule = GoalReviewSchedule()
        val beta = AgentId("beta")
        schedule.claim(setOf(beta), 0)
        schedule.settled(beta, 300, owed = false)
        assertEquals(listOf(beta), schedule.claimFollowUps(setOf(beta), 300 + FOLLOW_UP_DELAY_TICKS))
        // The follow-up's dig left its drop lying: one more review is owed for the collect.
        schedule.settled(beta, 410, owed = true)
        assertEquals(listOf(beta), schedule.claimFollowUps(setOf(beta), 410 + FOLLOW_UP_DELAY_TICKS))
        // And no more than that before the next scheduled review.
        schedule.settled(beta, 500, owed = true)
        assertEquals(emptyList<AgentId>(), schedule.claimFollowUps(setOf(beta), 500 + FOLLOW_UP_DELAY_TICKS))
        // An ordinary settlement after the first follow-up owes nothing.
        val gamma = AgentId("gamma")
        schedule.claim(setOf(gamma), 0)
        schedule.settled(gamma, 300, owed = false)
        schedule.claimFollowUps(setOf(gamma), 300 + FOLLOW_UP_DELAY_TICKS)
        schedule.settled(gamma, 410, owed = false)
        assertEquals(emptyList<AgentId>(), schedule.claimFollowUps(setOf(gamma), 410 + FOLLOW_UP_DELAY_TICKS))
    }

    private companion object {
        const val STICK = "item.minecraft.stick"
    }
}
