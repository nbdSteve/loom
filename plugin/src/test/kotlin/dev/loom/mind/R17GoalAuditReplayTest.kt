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
import dev.loom.contract.MAX_GOAL_STEPS_WITHOUT_PROGRESS
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

    private fun judged(disposition: GoalDisposition) = GoalChoice(disposition, stepResult = GoalStepResult.NO_PROGRESS)

    private val dig = Intent.Dig("bush")

    /** alpha's three barren digs at body:00000036, 107 and 112, which bring its goal to the bound. */
    private fun stallAlpha(): GoalSummary? {
        review("alpha-36", Scene(), sticks(GoalDisposition.ADOPT, 2), dig)
        review("alpha-107", Scene(), judged(GoalDisposition.CONTINUE), dig)
        review("alpha-112", Scene(), judged(GoalDisposition.CONTINUE), dig)
        return ledger.present(
            AgentId("alpha"),
            "alpha-probe",
            150,
            GoalObservation(emptyMap(), listOf(0.5, 100.0, 0.5)),
        )
            .also { ledger.forget(AgentId("alpha"), "alpha-probe") }
    }

    @Test
    fun `alpha's replace of hold 2 stick with hold 2 stick keeps the stall count`() {
        // alpha body:00000163 cleared its stall bound by replacing a goal with itself.
        assertEquals(MAX_GOAL_STEPS_WITHOUT_PROGRESS, stallAlpha()?.progress?.stepsWithoutProgress)
        val (_, relabelled) = review("alpha-163", Scene(), sticks(GoalDisposition.REPLACE, 2), dig)
        assertEquals(GoalEvidence.REPLACED, relabelled?.outcome)
        val (after, _) = review("alpha-164", Scene(), judged(GoalDisposition.ABANDON), Intent.Idle)
        // The count carried on: the barren dig made it four, still past the bound.
        assertEquals(MAX_GOAL_STEPS_WITHOUT_PROGRESS + 1, after?.progress?.stepsWithoutProgress)
    }

    @Test
    fun `a replace that raises the total starts its own run of steps`() {
        stallAlpha()
        review("alpha-163", Scene(), sticks(GoalDisposition.REPLACE, 3), dig)
        val (after, _) = review("alpha-164", Scene(), judged(GoalDisposition.CONTINUE), dig)
        assertEquals(1L, after?.progress?.stepsWithoutProgress)
    }

    @Test
    fun `theta's abandon one stick short with a bush in sight is refused`() {
        // theta body:00000275 to 333: four of five sticks held, a bush 4.7 blocks off, abandoned.
        val scene = Scene(inventory = mapOf(STICK to 3L))
        review("theta-275", scene, sticks(GoalDisposition.ADOPT, 5), dig)
        val (_, refused) =
            review(
                "theta-333",
                scene.copy(inventory = mapOf(STICK to 4L)),
                judged(GoalDisposition.ABANDON),
                dig,
            )
        assertEquals(GoalEvidence.ABANDONED_ACHIEVABLE, refused?.outcome)
        // Once sight can no longer meet it, it may be abandoned.
        val (_, ended) =
            review(
                "theta-334",
                Scene(inventory = mapOf(STICK to 4L), capacity = emptyMap()),
                judged(GoalDisposition.ABANDON),
                Intent.Idle,
            )
        assertEquals(GoalEvidence.ABANDONED, ended?.outcome)
    }

    @Test
    fun `kappa's completion adopts its next goal in the same answer`() {
        // kappa body:00000142 completed beside idle and waited a full cycle for its next goal.
        review("kappa-79", Scene(), sticks(GoalDisposition.ADOPT, 1), dig)
        val met = Scene(inventory = mapOf(STICK to 2L))
        val (carried, both) = review("kappa-142", met, sticks(GoalDisposition.REPLACE, 4), dig)
        assertEquals(true, carried?.progress?.conditionMet)
        assertEquals(GoalEvidence.COMPLETED_ADOPTED, both?.outcome)
        assertEquals("kappa-79", both?.presentedGoalId)
        assertEquals("kappa-142", both?.goalId)
    }

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
