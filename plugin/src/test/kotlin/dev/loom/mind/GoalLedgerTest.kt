package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.body.tagOf
import dev.loom.contract.Decision
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalProgress
import dev.loom.contract.GoalStep
import dev.loom.contract.GoalSummary
import dev.loom.contract.Intent
import dev.loom.contract.IntentAction
import dev.loom.contract.InventoryChange
import dev.loom.contract.MAX_GOAL_STEPS_WITHOUT_PROGRESS
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
            .copy(goal = disposition?.let { goalChoice(it, statement) })

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
    fun abandonEndsTheGoal() {
        review(alpha, "a", 100, GoalDisposition.ADOPT, "gather wood")
        val (ended, carried) = review(alpha, "b", 200, GoalDisposition.ABANDON)
        assertEquals(true, carried)
        assertEquals(GoalEvidence.ABANDONED, ended?.outcome)
        assertNull(ended?.goalId)
        assertNull(ledger.present(alpha, "c", 300))
    }

    @Test
    fun completeEndsTheGoalOnlyWhenItsSuccessConditionIsMet() {
        affirm("a", 100, seen(0.0), goalChoice(GoalDisposition.ADOPT, "gather a stick"))
        // Without the stick a completion is a claim the inventory does not back, refused.
        assertEquals(
            GoalEvidence.COMPLETION_UNMET,
            affirm("b", 200, seen(0.0), goalChoice(GoalDisposition.COMPLETE)).second?.outcome,
        )
        val (met, completed) = affirm("c", 300, seen(0.0, STICK to 1L), goalChoice(GoalDisposition.COMPLETE))
        assertEquals(true, met?.progress?.conditionMet)
        assertEquals(GoalEvidence.COMPLETED, completed?.outcome)
        assertNull(ledger.present(alpha, "d", 400, seen(0.0, STICK to 1L)))
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
        val choosing = ledger.resolve(alpha, threat.copy(goal = goalChoice(GoalDisposition.ABANDON)))
        assertEquals(GoalEvidence.NOT_REVIEWED, choosing?.outcome)
        assertEquals("r1", ledger.present(alpha, "r2", 200)?.goalId)
    }

    private fun seen(
        x: Double,
        vararg stacks: Pair<String, Long>,
    ): GoalObservation = GoalObservation(mapOf(*stacks), listOf(x, 64.0, 0.0))

    private fun affirm(
        requestId: String,
        tick: Long,
        observed: GoalObservation,
        choice: GoalChoice?,
        intent: Intent = Intent.Idle,
    ): Pair<GoalSummary?, GoalEvidence?> {
        val carried = ledger.present(alpha, requestId, tick, observed)
        val decision = decisionFor(perception(requestId = requestId, agentId = alpha.value), intent).copy(goal = choice)
        return carried to ledger.resolve(alpha, decision)
    }

    /**
     * One affirmed step whose intent is [intent], which the body then answered with [outcome]. The
     * review's tick is its request number times a hundred.
     */
    private fun step(
        requestId: String,
        observed: GoalObservation,
        disposition: GoalDisposition,
        intent: Intent,
        outcome: String = EventTag.ACCEPTED,
    ): Pair<GoalSummary?, GoalEvidence?> {
        val tick = requestId.drop(1).toLong() * 100
        val result =
            affirm(requestId, tick, observed, goalChoice(disposition, "gather a stick", intent = intent), intent)
        ledger.stepped(alpha, requestId, tagOf(intent), outcome)
        return result
    }

    @Test
    fun aWorkingGoalNeedsItsTypedConditionStepAndVerdict() {
        val adoptBare = GoalChoice(GoalDisposition.ADOPT, statement = "gather wood", step = "walk to the tree")
        assertEquals(GoalEvidence.SUCCESS_CONDITION_MISSING, affirm("r1", 100, seen(0.0), adoptBare).second?.outcome)
        val untyped = adoptBare.copy(successCondition = "8 logs")
        assertEquals(GoalEvidence.SUCCESS_TARGET_MISSING, affirm("r2", 100, seen(0.0), untyped).second?.outcome)
        val adoptNoStep = untyped.copy(successItem = "item.minecraft.oak_log", successCount = 8, step = null)
        assertEquals(GoalEvidence.STEP_MISSING, affirm("r3", 100, seen(0.0), adoptNoStep).second?.outcome)
        val adoptNoAction = adoptNoStep.copy(step = "walk to the tree")
        assertEquals(GoalEvidence.STEP_ACTION_MISSING, affirm("r4", 100, seen(0.0), adoptNoAction).second?.outcome)
        affirm("r5", 100, seen(0.0), goalChoice(GoalDisposition.ADOPT, "gather wood"))
        val unjudged = goalChoice(GoalDisposition.CONTINUE).copy(stepResult = null)
        assertEquals(GoalEvidence.STEP_RESULT_MISSING, affirm("r6", 200, seen(0.0), unjudged).second?.outcome)
        val carried = ledger.present(alpha, "r7", 300, seen(0.0))
        assertEquals("item.minecraft.stick", carried?.successItem)
        assertEquals(1L, carried?.successCount)
        assertEquals(IntentAction.IDLE, carried?.stepAction)
        assertNull(carried?.stepRef)
    }

    @Test
    fun aStepTypedAsAnotherActionOrRefIsRefusedWithItsIntent() {
        affirm("r1", 100, seen(0.0), goalChoice(GoalDisposition.ADOPT, "gather a stick"))
        val walk = Intent.MoveTo("bh90")
        // The step names bh8s; the intent walks to bh90.
        val misnamed = goalChoice(GoalDisposition.CONTINUE, intent = Intent.MoveTo("bh8s"))
        assertEquals(GoalEvidence.STEP_MISMATCH, affirm("r2", 200, seen(0.0), misnamed, walk).second?.outcome)
        // The step names a collect; the intent idles.
        val collecting = goalChoice(GoalDisposition.CONTINUE, intent = Intent.Collect("d5r", approach = true))
        assertEquals(GoalEvidence.STEP_MISMATCH, affirm("r3", 300, seen(0.0), collecting).second?.outcome)
        // The goal is untouched by either.
        assertEquals(IntentAction.IDLE, ledger.present(alpha, "r4", 400, seen(0.0))?.stepAction)
    }

    @Test
    fun theConditionIsTheHeldCountOfItsItemAndTheDeltaIsEveryItemThatChanged() {
        affirm(
            "r1",
            100,
            seen(0.0, DIRT to 3L),
            goalChoice(GoalDisposition.ADOPT, "gather sticks", success = STICK to 2L),
        )
        val (half, _) = affirm("r2", 200, seen(0.0, DIRT to 2L, STICK to 1L), goalChoice(GoalDisposition.CONTINUE))
        assertEquals(
            GoalProgress(
                conditionMet = false,
                conditionHeld = 1,
                inventoryDelta = listOf(InventoryChange(DIRT, -1), InventoryChange(STICK, 1)),
                conditionAdvanced = true,
                stepAdvanced = false,
                stepsWithoutProgress = 0,
            ),
            half?.progress,
        )
        val (full, _) =
            affirm(
                "r3",
                300,
                seen(0.0, DIRT to 2L, STICK to 2L),
                goalChoice(GoalDisposition.REVISE, "more"),
            )
        assertEquals(true, full?.progress?.conditionMet)
        assertEquals(listOf(InventoryChange(STICK, 1)), full?.progress?.inventoryDelta)
    }

    @Test
    fun aMetConditionMustBeCompletedOrRevisedAtTheNextReview() {
        affirm("r1", 100, seen(0.0), goalChoice(GoalDisposition.ADOPT, "gather a stick"))
        val holding = seen(0.0, STICK to 1L)
        for ((request, choice) in listOf(
            "r2" to goalChoice(GoalDisposition.CONTINUE),
            "r3" to goalChoice(GoalDisposition.ABANDON),
            "r4" to null,
        )) {
            assertEquals(
                GoalEvidence.CONDITION_MET_IGNORED,
                affirm(request, 200, holding, choice).second?.outcome,
                request,
            )
        }
        assertEquals(
            GoalEvidence.REVISED,
            affirm("r5", 300, holding, goalChoice(GoalDisposition.REVISE, "gather three sticks", success = STICK to 3L))
                .second?.outcome,
        )
    }

    @Test
    fun aWalkAdvancesItsStepOnlyByArrivingWhereTheGoalHasNotBeen() {
        step("r1", seen(0.0), GoalDisposition.ADOPT, Intent.MoveTo("b1"))
        val (arrived, _) = step("r2", seen(3.0), GoalDisposition.CONTINUE, Intent.MoveTo("b2"))
        assertEquals(true, arrived?.progress?.stepAdvanced)
        assertEquals(0L, arrived?.progress?.stepsWithoutProgress)
        // Back to the cell the goal was adopted at: no arrival anywhere new.
        val (returned, _) = step("r3", seen(0.0), GoalDisposition.CONTINUE, Intent.MoveTo("b1"))
        assertEquals(false, returned?.progress?.stepAdvanced)
        assertEquals(1L, returned?.progress?.stepsWithoutProgress)
        // And back to the cell the first walk reached: still nowhere new.
        val (shuffled, _) =
            step("r4", seen(3.0), GoalDisposition.CONTINUE, Intent.MoveTo("b3"), EventTag.MOVE_NO_ROUTE)
        assertEquals(false, shuffled?.progress?.stepAdvanced)
        assertEquals(2L, shuffled?.progress?.stepsWithoutProgress)
        // A refused walk advances nothing, even where the body ends up somewhere new.
        val refused = ledger.present(alpha, "r5", 500, seen(9.0))
        assertEquals(false, refused?.progress?.stepAdvanced)
        assertEquals(3L, refused?.progress?.stepsWithoutProgress)
    }

    @Test
    fun aWorldStepAdvancesWhenTheBodyCarriedItOutAndIdlingNeverDoes() {
        step("r1", seen(0.0), GoalDisposition.ADOPT, Intent.Dig("b1"))
        val (dug, _) = step("r2", seen(0.0), GoalDisposition.CONTINUE, Intent.Idle)
        assertEquals(true, dug?.progress?.stepAdvanced)
        val (idled, _) = step("r3", seen(0.0), GoalDisposition.CONTINUE, Intent.Dig("b2"), EventTag.DIG_TIMED_OUT)
        assertEquals(false, idled?.progress?.stepAdvanced)
        assertEquals(false, ledger.present(alpha, "r4", 400, seen(0.0))?.progress?.stepAdvanced)
    }

    @Test
    fun stepsThatAdvanceNothingEndTheGoalAtTheBoundEvenWhenRevised() {
        step("r1", seen(0.0), GoalDisposition.ADOPT, Intent.Idle)
        step("r2", seen(0.0), GoalDisposition.CONTINUE, Intent.Idle)
        step("r3", seen(0.0), GoalDisposition.REVISE, Intent.Idle)
        val (atBound, refused) = step("r4", seen(0.0), GoalDisposition.REVISE, Intent.Idle)
        assertEquals(MAX_GOAL_STEPS_WITHOUT_PROGRESS, atBound?.progress?.stepsWithoutProgress)
        assertEquals(GoalEvidence.STALLED, refused?.outcome)
        assertNull(ledger.present(alpha, "r5", 500, seen(0.0)))
    }

    private companion object {
        const val STICK = "item.minecraft.stick"
        const val DIRT = "item.minecraft.dirt"
    }
}
