package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.contract.Decision
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalStep
import dev.loom.contract.Trigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GoalLedgerTest {
    private val alpha = AgentId("agent-alpha")
    private val beta = AgentId("agent-beta")
    private val ledger = GoalLedger()

    private fun answer(
        agentId: AgentId,
        requestId: String,
        disposition: GoalDisposition?,
        statement: String? = null,
    ): Decision =
        decisionFor(perception(requestId = requestId, agentId = agentId.value))
            .copy(goal = disposition?.let { GoalChoice(it, statement) })

    private fun review(
        agentId: AgentId,
        requestId: String,
        tick: Long,
        disposition: GoalDisposition?,
        statement: String? = null,
    ): Pair<GoalEvidence?, Boolean> {
        val carried = ledger.present(agentId, requestId, tick)
        return ledger.resolve(agentId, answer(agentId, requestId, disposition, statement)) to (carried != null)
    }

    @Test
    fun anAdoptedGoalIsCarriedIntoTheNextReviewWithItsStepAndResult() {
        assertNull(ledger.present(alpha, "r1", 100))
        val adopted = ledger.resolve(alpha, answer(alpha, "r1", GoalDisposition.ADOPT, "gather wood"))
        assertEquals(GoalEvidence(GoalEvidence.ADOPTED, GoalDisposition.ADOPT, goalId = "r1", revision = 0), adopted)
        ledger.stepped(alpha, "r1", EventTag.DIG, EventTag.ACCEPTED)

        val carried = ledger.present(alpha, "r2", 2_500)
        assertEquals("r1", carried?.goalId)
        assertEquals("gather wood", carried?.statement)
        assertEquals(100L, carried?.adoptedTick)
        assertEquals(GoalStep("r1", EventTag.DIG, EventTag.ACCEPTED), carried?.lastStep)

        val continued = ledger.resolve(alpha, answer(alpha, "r2", GoalDisposition.CONTINUE))
        assertEquals(GoalEvidence.CONTINUED, continued?.outcome)
        assertEquals("r1", continued?.presentedGoalId)
        assertEquals("r1", continued?.goalId)
    }

    @Test
    fun aRefusedStepIsCarriedAsTheBodyReportedIt() {
        review(alpha, "r1", 100, GoalDisposition.ADOPT, "gather wood")
        ledger.stepped(alpha, "r1", EventTag.DIG, EventTag.CONTROL_NOT_READY)
        assertEquals(EventTag.CONTROL_NOT_READY, ledger.present(alpha, "r2", 200)?.lastStep?.outcome)
    }

    @Test
    fun aStepFromAnotherDecisionDoesNotOverwriteTheGoalsOwnStep() {
        review(alpha, "r1", 100, GoalDisposition.ADOPT, "gather wood")
        ledger.stepped(alpha, "r1", EventTag.DIG, EventTag.ACCEPTED)
        ledger.stepped(alpha, "flee-1", EventTag.FLEE, EventTag.ACCEPTED)
        assertEquals("r1", ledger.present(alpha, "r2", 200)?.lastStep?.requestId)
    }

    @Test
    fun reviseKeepsTheIdAndCountsTheRevision() {
        review(alpha, "r1", 100, GoalDisposition.ADOPT, "gather wood")
        val (revised, _) = review(alpha, "r2", 200, GoalDisposition.REVISE, "gather stone")
        assertEquals(GoalEvidence.REVISED, revised?.outcome)
        val carried = ledger.present(alpha, "r3", 300)
        assertEquals("r1", carried?.goalId)
        assertEquals(1L, carried?.revision)
        assertEquals("gather stone", carried?.statement)
    }

    @Test
    fun completeAndAbandonEndTheGoal() {
        for ((disposition, tag) in listOf(
            GoalDisposition.COMPLETE to GoalEvidence.COMPLETED,
            GoalDisposition.ABANDON to GoalEvidence.ABANDONED,
        )) {
            review(alpha, "a-$tag", 100, GoalDisposition.ADOPT, "gather wood")
            val (ended, carried) = review(alpha, "b-$tag", 200, disposition)
            assertEquals(true, carried)
            assertEquals(tag, ended?.outcome)
            assertNull(ended?.goalId)
            assertNull(ledger.present(alpha, "c-$tag", 300))
            ledger.forget(alpha, "c-$tag")
        }
    }

    @Test
    fun idleWithoutAChoiceLeavesTheGoalHeldButAgeing() {
        review(alpha, "r1", 100, GoalDisposition.ADOPT, "gather wood")
        val (unaddressed, _) = review(alpha, "r2", 100 + GOAL_LIFETIME_TICKS, null)
        assertEquals(GoalEvidence.UNADDRESSED, unaddressed?.outcome)
        assertEquals("r1", unaddressed?.goalId)

        // Not re-affirmed, so the review after the lifetime finds it expired.
        assertNull(ledger.present(alpha, "r3", 101 + GOAL_LIFETIME_TICKS))
        val expired = ledger.resolve(alpha, answer(alpha, "r3", GoalDisposition.CONTINUE))
        assertEquals(GoalEvidence.NOT_HELD, expired?.outcome)
        assertEquals("r1", expired?.expiredGoalId)
    }

    @Test
    fun aRefusedChoiceChangesNothing() {
        assertEquals(GoalEvidence.NOT_HELD, review(alpha, "r1", 100, GoalDisposition.CONTINUE).first?.outcome)
        assertEquals(GoalEvidence.STATEMENT_MISSING, review(alpha, "r2", 100, GoalDisposition.ADOPT).first?.outcome)
        review(alpha, "r3", 100, GoalDisposition.ADOPT, "gather wood")
        assertEquals(
            GoalEvidence.ALREADY_HELD,
            review(alpha, "r4", 200, GoalDisposition.ADOPT, "hunt").first?.outcome,
        )
        assertEquals(GoalEvidence.STATEMENT_MISSING, review(alpha, "r5", 200, GoalDisposition.REVISE).first?.outcome)
        assertEquals("gather wood", ledger.present(alpha, "r6", 300)?.statement)
    }

    @Test
    fun goalsAreIsolatedByAgentAndAnAnswerCannotReachAnotherAgentsReview() {
        review(alpha, "r1", 100, GoalDisposition.ADOPT, "gather wood")
        assertNull(ledger.present(beta, "r1", 100))
        // The same request id under another agent resolves against that agent alone.
        val beta1 = ledger.resolve(beta, answer(beta, "r1", GoalDisposition.CONTINUE))
        assertEquals(GoalEvidence.NOT_HELD, beta1?.outcome)
        assertEquals("r1", ledger.present(alpha, "r2", 200)?.goalId)
    }

    @Test
    fun concurrentReviewsResolveOnlyAgainstTheGoalTheirPerceptionCarried() {
        ledger.present(alpha, "r1", 100)
        ledger.present(alpha, "r2", 100)
        assertEquals(
            GoalEvidence.ADOPTED,
            ledger.resolve(alpha, answer(alpha, "r1", GoalDisposition.ADOPT, "a"))?.outcome,
        )
        val second = ledger.resolve(alpha, answer(alpha, "r2", GoalDisposition.ADOPT, "b"))
        assertEquals(GoalEvidence.STALE, second?.outcome)
        assertEquals("r1", second?.goalId)

        ledger.present(alpha, "r3", 200)
        ledger.present(alpha, "r4", 200)
        assertEquals(
            GoalEvidence.REVISED,
            ledger.resolve(alpha, answer(alpha, "r3", GoalDisposition.REVISE, "c"))?.outcome,
        )
        assertEquals(GoalEvidence.STALE, ledger.resolve(alpha, answer(alpha, "r4", GoalDisposition.COMPLETE))?.outcome)
        assertEquals("c", ledger.present(alpha, "r5", 300)?.statement)
    }

    @Test
    fun lifecycleRecordsClearTheGoalAndEveryAnswerInFlight() {
        val written = mutableListOf<BodyEventRecord>()
        val log = ledger.watching(BodyEventLog { written.add(it) })
        for (event in listOf(BodyEvent.BODY_DISCONNECTED, BodyEvent.SPAWNED, BodyEvent.DESPAWNED, BodyEvent.DIED)) {
            review(alpha, "a-$event", 100, GoalDisposition.ADOPT, "gather wood")
            review(beta, "a-$event", 100, GoalDisposition.ADOPT, "keep watch")
            ledger.present(alpha, "b-$event", 200)
            log.append(BodyEventRecord(150, alpha.value, event, "lifecycle"))

            // The answer in flight across the boundary is stale, even one that adopts.
            val crossed = ledger.resolve(alpha, answer(alpha, "b-$event", GoalDisposition.ADOPT, "x"))
            assertEquals(GoalEvidence.NOT_REVIEWED, crossed?.outcome)
            assertNull(ledger.present(alpha, "c-$event", 300))
            ledger.forget(alpha, "c-$event")
            // The peer keeps its own goal; a disconnect never moves one between agents.
            assertEquals("keep watch", ledger.present(beta, "c-$event", 300)?.statement)
            ledger.resolve(beta, answer(beta, "c-$event", GoalDisposition.ABANDON))
        }
        assertEquals(4, written.size)
    }

    @Test
    fun aClearedAgentCannotResolveAnOldBasisAfterReentering() {
        ledger.present(alpha, "r1", 100)
        ledger.clear(alpha)
        ledger.present(alpha, "r2", 200)
        assertEquals(
            GoalEvidence.NOT_REVIEWED,
            ledger.resolve(alpha, answer(alpha, "r1", GoalDisposition.ADOPT, "a"))?.outcome,
        )
        assertNull(ledger.present(alpha, "r3", 300))
    }

    @Test
    fun stateIsBoundedInAgentsAndPendingReviews() {
        repeat(MAX_GOAL_AGENTS + 4) { ledger.present(AgentId("agent-$it"), "r", 100) }
        assertEquals(MAX_GOAL_AGENTS, ledger.trackedAgents())
        assertNull(ledger.present(alpha, "beyond", 100))
        ledger.clear(AgentId("agent-0"))

        repeat(MAX_PENDING_GOAL_REVIEWS + 1) { ledger.present(alpha, "p$it", 100) }
        val evicted = ledger.resolve(alpha, answer(alpha, "p0", GoalDisposition.ADOPT, "a"))
        assertEquals(GoalEvidence.NOT_REVIEWED, evicted?.outcome)
        val kept = ledger.resolve(alpha, answer(alpha, "p1", GoalDisposition.ADOPT, "a"))
        assertEquals(GoalEvidence.ADOPTED, kept?.outcome)
    }

    @Test
    fun aDecisionThatAnsweredNoGoalReviewChangesNothing() {
        review(alpha, "r1", 100, GoalDisposition.ADOPT, "gather wood")
        val threat = decisionFor(perception(requestId = "t1", trigger = Trigger.GoalReview))
        assertNull(ledger.resolve(alpha, threat))
        val choosing = ledger.resolve(alpha, threat.copy(goal = GoalChoice(GoalDisposition.ABANDON)))
        assertEquals(GoalEvidence.NOT_REVIEWED, choosing?.outcome)
        assertEquals("r1", ledger.present(alpha, "r2", 200)?.goalId)
    }
}
