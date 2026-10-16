/*
 * Goal continuity across the whole round trip: a goal chosen at one review reaches the
 * next review's perception with its step and the body's result, and the record proves it.
 */
package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.BodyPort
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.body.IntentOutcome
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalStep
import dev.loom.contract.Intent
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.util.concurrent.CompletableFuture

/** A body that numbers its perceptions and answers every intent with [outcome]. */
private class NumberingBody(
    private val outcome: IntentOutcome,
) : BodyPort {
    private var next = 0

    override fun spawn(agentId: AgentId) = fail("not under test")

    override fun despawn(agentId: AgentId) = fail("not under test")

    override fun apply(
        agentId: AgentId,
        intent: Intent,
        requestId: String?,
    ): CompletableFuture<IntentOutcome> = CompletableFuture.completedFuture(outcome)

    override fun perceive(
        agentId: AgentId,
        trigger: Trigger,
    ): CompletableFuture<Perception?> =
        CompletableFuture.completedFuture(
            perception(requestId = "r${++next}", agentId = agentId.value, trigger = trigger),
        )

    override fun senseThreat(agentId: AgentId): CompletableFuture<Perception?> = fail("not under test")

    override fun tick(tick: Long) = fail("not under test")

    override fun liveBodies(): Set<AgentId> = fail("not under test")
}

class GoalContinuityTest {
    private val agent = AgentId("agent-alpha")
    private val main = FakeMainThread()
    private val records = mutableListOf<BodyEventRecord>()
    private val seen = mutableListOf<Perception>()

    private fun cognition(
        outcome: IntentOutcome,
        choices: List<GoalChoice?>,
    ): EmbodiedCognition {
        val answers = choices.iterator()
        return EmbodiedCognition(
            NumberingBody(outcome),
            mind = { p ->
                seen.add(p)
                CompletableFuture.completedFuture(decisionFor(p, Intent.Idle).copy(goal = answers.next()))
            },
            mainThread = main,
            journal = DecisionJournal(BodyEventLog { records.add(it) }, currentTick = { 0L }),
        )
    }

    private fun decidedGoals(): List<GoalEvidence?> = records.filter { it.event == BodyEvent.DECIDED }.map { it.goal }

    @Test
    fun aSecondReviewReceivesTheFirstReviewsGoalStepAndResultAndCanContinueIt() {
        val mind =
            cognition(
                IntentOutcome.Accepted("standing still"),
                listOf(goalChoice(GoalDisposition.ADOPT, "find shelter"), goalChoice(GoalDisposition.CONTINUE)),
            )
        mind.consider(agent, Trigger.GoalReview)
        main.drain()
        mind.consider(agent, Trigger.GoalReview)
        main.drain()

        assertNull(seen[0].goal)
        val carried = seen[1].goal
        assertEquals("r1", carried?.goalId)
        assertEquals("find shelter", carried?.statement)
        assertEquals(GoalStep("r1", EventTag.IDLE, EventTag.ACCEPTED), carried?.lastStep)

        val (first, second) = decidedGoals()
        assertEquals(GoalEvidence.ADOPTED, first?.outcome)
        assertEquals(GoalEvidence.CONTINUED, second?.outcome)
        assertEquals(second?.presentedGoalId, second?.goalId)
    }

    @Test
    fun aRejectedActionReachesTheNextReviewAsTheBodysRefusal() {
        val mind =
            cognition(
                IntentOutcome.Rejected("busy", EventTag.CONTROL_NOT_READY),
                listOf(goalChoice(GoalDisposition.ADOPT, "find shelter"), goalChoice(GoalDisposition.ABANDON)),
            )
        repeat(2) {
            mind.consider(agent, Trigger.GoalReview)
            main.drain()
        }
        assertEquals(EventTag.CONTROL_NOT_READY, seen[1].goal?.lastStep?.outcome)
        assertEquals(GoalEvidence.ABANDONED, decidedGoals()[1]?.outcome)
    }

    @Test
    fun aGoalIsNeverCarriedIntoAnotherTrigger() {
        val mind =
            cognition(
                IntentOutcome.Accepted("ok"),
                listOf(goalChoice(GoalDisposition.ADOPT, "find shelter"), null),
            )
        mind.consider(agent, Trigger.GoalReview)
        main.drain()
        mind.consider(agent, Trigger.Damaged(source = "zombie", amount = 2))
        main.drain()
        assertNull(seen[1].goal)
        assertNull(decidedGoals()[1])
    }

    @Test
    fun aDecisionWithoutAGoalChoiceKeepsTheExistingRecordShape() {
        val mind = cognition(IntentOutcome.Accepted("ok"), listOf(null))
        mind.consider(agent, Trigger.GoalReview)
        main.drain()
        assertEquals(GoalEvidence(GoalEvidence.UNADDRESSED), decidedGoals().single())
    }
}
