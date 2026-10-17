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
import dev.loom.contract.StepEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The independent r18 goal-chain audit, replayed through the real ledger and schedule.
 *
 * Each case uses the inventory, intent, success target and what was in sight that one cited
 * agent's records carry in `research/experiments/2026-10-16-openai-10-agent-10m-r18/evidence.jsonl`;
 * the record ids are the audit's. Under r18's rules each passed or cost the agent: a stall that
 * fired on a dig that had just dropped two sticks, goals abandoned to look further, a revise that
 * changed the goal's item, a cut sight forced recorded as the mind's own, and a third drop in one
 * review left lying with no review owed until another agent took it.
 */
class R18GoalAuditReplayTest {
    private val ledger = GoalLedger()

    private data class Scene(
        val inventory: Map<String, Long> = emptyMap(),
        val capacity: Map<String, Long> = mapOf(STICK to 2L),
        val gives: Map<String, Set<String>> = mapOf("bush" to setOf(STICK), "terra" to emptySet()),
        val dropped: Set<String> = emptySet(),
        val ownDrops: Map<String, String> = emptyMap(),
        val explore: Set<String> = emptySet(),
        val at: List<Double> = listOf(0.5, 64.0, 0.5),
    )

    private fun review(
        review: String,
        scene: Scene,
        choice: GoalChoice?,
        intent: Intent,
        reached: Boolean? = null,
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
                explore = scene.explore,
                inReach = scene.gives.keys,
            )
        val carried = ledger.present(agent, review, review.substringAfter('-').toLong(), observed)
        val decision = decisionFor(perception(requestId = review, agentId = agent.value), intent).copy(goal = choice)
        val evidence = ledger.resolve(agent, decision)
        val applied =
            if (evidence?.outcome in GoalEvidence.REFUSING) requireNotNull(evidence).outcome else EventTag.ACCEPTED
        ledger.stepped(agent, review, tagOf(intent), applied, reached)
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

    @Test
    fun `beta's dig that dropped two sticks is no stall while they lie, so its collect is a continue`() {
        // beta body:00000194 and 246 advanced nothing; body:00000272's dig dropped two own sticks,
        // and body:00000282 found the goal at the bound and replaced it at one more stick.
        review("beta-183", Scene(), sticks(GoalDisposition.ADOPT, 1), dig)
        review("beta-194", Scene(), judged(GoalDisposition.CONTINUE), dig)
        review("beta-272", Scene(), sticks(GoalDisposition.REVISE, 2), dig)
        val lying = Scene(dropped = setOf("beta-272"), ownDrops = mapOf("dn" to STICK), gives = GIVES)
        val collect = Intent.Collect("dn", approach = true)
        val (carried, collecting) = review("beta-282", lying, judged(GoalDisposition.CONTINUE), collect)
        assertEquals(StepEvidence.DROPPED, carried?.progress?.stepEvidence)
        assertEquals(MAX_GOAL_STEPS_WITHOUT_PROGRESS - 1, carried?.progress?.stepsWithoutProgress)
        assertEquals(GoalEvidence.CONTINUED, collecting?.outcome)
        // A dig whose drop is no longer lying in reach is a step that advanced nothing.
        val held = Scene(inventory = mapOf(STICK to 2L), capacity = mapOf(STICK to 2L))
        val taken = held.copy(dropped = setOf("beta-284"))
        val (collected, _) = review("beta-284", held, sticks(GoalDisposition.REVISE, 3), dig)
        assertEquals(StepEvidence.GAINED, collected?.progress?.stepEvidence)
        val (after, _) = review("beta-290", taken, judged(GoalDisposition.CONTINUE), dig)
        assertEquals(StepEvidence.DROPPED, after?.progress?.stepEvidence)
        assertEquals(1L, after?.progress?.stepsWithoutProgress)
    }

    @Test
    fun `gamma's goal sight could not meet is continued by a walk to look, credited when it reveals a bush`() {
        // gamma body:00000069 abandoned its stick goal to walk to the edge of sight, x1.
        review("gamma-59", Scene(), sticks(GoalDisposition.ADOPT, 1), dig)
        val bare = Scene(capacity = emptyMap(), gives = emptyMap(), explore = setOf("x1"))
        val walk = Intent.MoveTo("x1")
        val (_, looking) = review("gamma-69", bare, judged(GoalDisposition.CONTINUE), walk, reached = true)
        assertEquals(GoalEvidence.CONTINUED, looking?.outcome)
        // The walk arrived where a bush was in sight: revealed, and progress.
        val (found, _) =
            review(
                "gamma-143",
                Scene(at = listOf(12.5, 64.0, 0.5)),
                judged(GoalDisposition.CONTINUE),
                dig,
            )
        assertEquals(StepEvidence.REVEALED, found?.progress?.stepEvidence)
        assertEquals(0L, found?.progress?.stepsWithoutProgress)
        // A walk that reveals nothing is a step that advanced nothing.
        review("delta-480", Scene(), sticks(GoalDisposition.ADOPT, 1), dig)
        review("delta-485", bare, judged(GoalDisposition.CONTINUE), walk, reached = true)
        val (empty, _) =
            review(
                "delta-500",
                bare.copy(at = listOf(12.5, 64.0, 0.5)),
                judged(GoalDisposition.ABANDON),
                walk,
            )
        assertEquals(StepEvidence.NONE, empty?.progress?.stepEvidence)
        assertEquals(2L, empty?.progress?.stepsWithoutProgress)
    }

    @Test
    fun `zeta's revise of a scute goal into sticks is refused, a replace is the way`() {
        // zeta body:00000543 adopted one armadillo scute; body:00000569 revised it to four sticks.
        val scute = "item.minecraft.armadillo_scute"
        val sight =
            Scene(
                capacity = mapOf(scute to 1L, STICK to 4L),
                gives = mapOf("bush" to setOf(STICK), "drop" to setOf(scute)),
            )
        review(
            "zeta-543",
            sight,
            GoalChoice(GoalDisposition.ADOPT, successItem = scute, successCount = 1),
            Intent.Collect("drop", true),
        )
        val (_, refused) = review("zeta-569", sight, sticks(GoalDisposition.REVISE, 4), dig)
        assertEquals(GoalEvidence.REVISE_ITEM_CHANGED, refused?.outcome)
        val (_, replaced) = review("zeta-570", sight, sticks(GoalDisposition.REPLACE, 4), dig)
        assertEquals(GoalEvidence.REPLACED, replaced?.outcome)
    }

    @Test
    fun `delta's cut from three to two states what sight could give, so the gate reads it as forced`() {
        // delta body:00000053 revised three sticks to two: it held none and sight could give one.
        review("delta-39", Scene(capacity = mapOf(STICK to 4L)), sticks(GoalDisposition.ADOPT, 3), dig)
        val (_, cut) = review("delta-53", Scene(capacity = mapOf(STICK to 1L)), sticks(GoalDisposition.REVISE, 1), dig)
        assertEquals(GoalEvidence.REVISED, cut?.outcome)
        assertEquals(0L, cut?.targetHeld)
        assertEquals(1L, cut?.targetCapacity)
    }

    @Test
    fun `epsilon's dig of terracotta for a stick goal is refused, and theta's walk onto it`() {
        // epsilon body:00000046 adopted a stick goal and dug terracotta; theta body:00000240 walked
        // onto terracotta with four bushes in sight.
        val (_, dug) = review("epsilon-46", Scene(), sticks(GoalDisposition.ADOPT, 1), Intent.Dig("terra"))
        assertEquals(GoalEvidence.STEP_UNPRODUCTIVE, dug?.outcome)
        review("theta-162", Scene(), sticks(GoalDisposition.ADOPT, 2), dig)
        val (_, walked) = review("theta-240", Scene(), judged(GoalDisposition.CONTINUE), Intent.MoveTo("terra"))
        assertEquals(GoalEvidence.STEP_UNPRODUCTIVE, walked?.outcome)
    }

    @Test
    fun `delta's third drop in one review is owed a follow-up from the run's allowance`() {
        // delta body:00000062 was its scheduled review's second follow-up; its dig dropped a stick,
        // no third review came, and epsilon took it (body:00000121 taken, 128).
        val schedule = GoalReviewSchedule()
        val delta = AgentId("delta")
        schedule.claim(setOf(delta), 0)
        schedule.claim(setOf(delta), DEFAULT_GOAL_REVIEW_CADENCE_TICKS)
        // Two scheduled reviews have earned four follow-ups; three owed ones in a row are served.
        var tick = DEFAULT_GOAL_REVIEW_CADENCE_TICKS
        repeat(3) {
            tick += 100
            schedule.settled(delta, tick, owed = true)
            assertEquals(listOf(delta), schedule.claimFollowUps(setOf(delta), tick + FOLLOW_UP_DELAY_TICKS), "owed $it")
        }
        // An ordinary step past the first in this review owes nothing.
        schedule.settled(delta, tick + 200, owed = false)
        assertEquals(emptyList<AgentId>(), schedule.claimFollowUps(setOf(delta), tick + 200 + FOLLOW_UP_DELAY_TICKS))
        // The fourth earned follow-up is spent by an owed step; nothing is left after it.
        schedule.settled(delta, tick + 300, owed = true)
        assertEquals(listOf(delta), schedule.claimFollowUps(setOf(delta), tick + 300 + FOLLOW_UP_DELAY_TICKS))
        schedule.settled(delta, tick + 400, owed = true)
        assertEquals(emptyList<AgentId>(), schedule.claimFollowUps(setOf(delta), tick + 400 + FOLLOW_UP_DELAY_TICKS))
    }

    private companion object {
        const val STICK = "item.minecraft.stick"
        val GIVES = mapOf("bush" to setOf(STICK), "dn" to setOf(STICK))
    }
}
