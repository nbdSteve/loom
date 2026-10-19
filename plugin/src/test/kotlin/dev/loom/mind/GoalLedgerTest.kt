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
import dev.loom.contract.StepEvidence
import dev.loom.contract.Trigger
import dev.loom.contract.targetRef
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
        count: Long = 1L,
        intent: Intent = DIG,
    ): Decision =
        decisionFor(perception(requestId = requestId, agentId = agentId.value), intent)
            .copy(goal = disposition?.let { goalChoice(it, success = STICK to count) })

    private fun review(
        agentId: AgentId,
        requestId: String,
        tick: Long,
        disposition: GoalDisposition?,
        count: Long = 1L,
    ): Pair<GoalEvidence?, Boolean> {
        val carried = ledger.present(agentId, requestId, tick)
        return ledger.resolve(agentId, answer(agentId, requestId, disposition, count)) to (carried != null)
    }

    @Test
    fun anAdoptedGoalIsCarriedIntoTheNextReviewWithItsStepAndResult() {
        assertNull(ledger.present(alpha, "r1", 100))
        val adopted = ledger.resolve(alpha, answer(alpha, "r1", GoalDisposition.ADOPT))
        assertEquals(GoalEvidence(GoalEvidence.ADOPTED, GoalDisposition.ADOPT, goalId = "r1", revision = 0), adopted)
        ledger.stepped(alpha, "r1", EventTag.DIG, EventTag.ACCEPTED)

        val carried = ledger.present(alpha, "r2", 2_500)
        assertEquals("r1", carried?.goalId)
        assertEquals(statementOf(STICK, 1L), carried?.statement)
        assertEquals(100L, carried?.adoptedTick)
        assertEquals(GoalStep("r1", EventTag.DIG, EventTag.ACCEPTED), carried?.lastStep)

        val continued = ledger.resolve(alpha, answer(alpha, "r2", GoalDisposition.CONTINUE))
        assertEquals(GoalEvidence.CONTINUED, continued?.outcome)
        assertEquals("r1", continued?.presentedGoalId)
        assertEquals("r1", continued?.goalId)
    }

    @Test
    fun aRefusedStepIsCarriedAsTheBodyReportedIt() {
        review(alpha, "r1", 100, GoalDisposition.ADOPT)
        ledger.stepped(alpha, "r1", EventTag.DIG, EventTag.CONTROL_NOT_READY)
        assertEquals(EventTag.CONTROL_NOT_READY, ledger.present(alpha, "r2", 200)?.lastStep?.outcome)
    }

    @Test
    fun aStepFromAnotherDecisionDoesNotOverwriteTheGoalsOwnStep() {
        review(alpha, "r1", 100, GoalDisposition.ADOPT)
        ledger.stepped(alpha, "r1", EventTag.DIG, EventTag.ACCEPTED)
        ledger.stepped(alpha, "flee-1", EventTag.FLEE, EventTag.ACCEPTED)
        assertEquals("r1", ledger.present(alpha, "r2", 200)?.lastStep?.requestId)
    }

    @Test
    fun reviseKeepsTheIdAndCountsTheRevision() {
        review(alpha, "r1", 100, GoalDisposition.ADOPT)
        val (revised, _) = review(alpha, "r2", 200, GoalDisposition.REVISE, 2L)
        assertEquals(GoalEvidence.REVISED, revised?.outcome)
        val carried = ledger.present(alpha, "r3", 300)
        assertEquals("r1", carried?.goalId)
        assertEquals(1L, carried?.revision)
        assertEquals(statementOf(STICK, 2L), carried?.statement)
    }

    @Test
    fun abandonEndsTheGoal() {
        review(alpha, "a", 100, GoalDisposition.ADOPT)
        val (ended, carried) = review(alpha, "b", 200, GoalDisposition.ABANDON)
        assertEquals(true, carried)
        assertEquals(GoalEvidence.ABANDONED, ended?.outcome)
        assertNull(ended?.goalId)
        assertNull(ledger.present(alpha, "c", 300))
    }

    @Test
    fun completeEndsTheGoalOnlyWhenItsSuccessConditionIsMet() {
        affirm("a", 100, seen(0.0), goalChoice(GoalDisposition.ADOPT))
        // Without the stick a completion is a claim the inventory does not back, refused.
        assertEquals(
            GoalEvidence.COMPLETION_UNMET,
            affirm("b", 200, seen(0.0), goalChoice(GoalDisposition.COMPLETE)).second?.outcome,
        )
        // A met goal beside a source of a next one is replaced, not completed beside a filler (r19).
        val (met, beside) = affirm("c", 300, seen(0.0, STICK to 1L), goalChoice(GoalDisposition.COMPLETE))
        assertEquals(true, met?.progress?.conditionMet)
        assertEquals(GoalEvidence.COMPLETED_WITHOUT_NEXT, beside?.outcome)
        val bare = seen(0.0, STICK to 1L).copy(capacity = emptyMap())
        val (_, completed) = affirm("d", 400, bare, goalChoice(GoalDisposition.COMPLETE))
        assertEquals(GoalEvidence.COMPLETED, completed?.outcome)
        assertNull(ledger.present(alpha, "e", 500, bare))
    }

    @Test
    fun aFlightFromAHunterStepsAsideFromTheHeldGoal() {
        // r20 kappa body:00000839: a review beside a zombie and a skeleton could only dig. A
        // flight with no goal choice defers the goal, which stays held.
        review(alpha, "r1", 100, GoalDisposition.ADOPT)
        ledger.present(alpha, "r2", 200)
        val fled = ledger.resolve(alpha, answer(alpha, "r2", null, intent = Intent.Flee("e9")))
        assertEquals(GoalEvidence.DEFERRED, fled?.outcome)
        assertEquals("r1", fled?.goalId)
        assertNull(ledger.takeRefusal(alpha))
    }

    @Test
    fun aFlightStepsAsideFromAMetGoalToo() {
        // The v32 rehearsal: gamma's review beside a zombie at 4.5 blocks, its dye goal met, was
        // offered a flight alone with no goal choice, and its flight was refused as met-ignored.
        affirm("a", 100, seen(0.0), goalChoice(GoalDisposition.ADOPT))
        val (met, fled) = affirm("b", 200, seen(0.0, STICK to 1L), null, Intent.Flee("e9"))
        assertEquals(true, met?.progress?.conditionMet)
        assertEquals(GoalEvidence.DEFERRED, fled?.outcome)
        assertNull(ledger.takeRefusal(alpha))
        // An action that is no survival response still answers the met goal.
        val (_, dug) = affirm("c", 300, seen(0.0, STICK to 1L), null)
        assertEquals(GoalEvidence.CONDITION_MET_IGNORED, dug?.outcome)
    }

    @Test
    fun idleWithoutAChoiceLeavesTheGoalHeldButAgeing() {
        review(alpha, "r1", 100, GoalDisposition.ADOPT)
        // An agent that acts at a review must say what it does with the goal it holds (r15 kappa).
        ledger.present(alpha, "r2", 100 + GOAL_LIFETIME_TICKS)
        val acting = ledger.resolve(alpha, answer(alpha, "r2", null))
        assertEquals(GoalEvidence.CHOICE_MISSING, acting?.outcome)
        assertEquals(GoalEvidence.CHOICE_MISSING, ledger.takeRefusal(alpha)?.outcome)
        ledger.present(alpha, "r2", 100 + GOAL_LIFETIME_TICKS)
        val unaddressed = ledger.resolve(alpha, answer(alpha, "r2", null, intent = Intent.Idle))
        assertEquals(GoalEvidence.UNADDRESSED, unaddressed?.outcome)
        assertEquals("r1", unaddressed?.goalId)
        assertNull(ledger.takeRefusal(alpha))

        // Not re-affirmed, so the review after the lifetime finds it expired.
        assertNull(ledger.present(alpha, "r3", 101 + GOAL_LIFETIME_TICKS))
        val expired = ledger.resolve(alpha, answer(alpha, "r3", GoalDisposition.CONTINUE))
        assertEquals(GoalEvidence.NOT_HELD, expired?.outcome)
        assertEquals("r1", expired?.expiredGoalId)
    }

    @Test
    fun aRefusedChoiceChangesNothing() {
        assertEquals(GoalEvidence.NOT_HELD, review(alpha, "r1", 100, GoalDisposition.CONTINUE).first?.outcome)
        ledger.present(alpha, "r2", 100)
        val untargeted =
            answer(
                alpha,
                "r2",
                GoalDisposition.ADOPT,
            ).let { it.copy(goal = it.goal?.copy(successItem = null)) }
        assertEquals(GoalEvidence.SUCCESS_TARGET_MISSING, ledger.resolve(alpha, untargeted)?.outcome)
        review(alpha, "r3", 100, GoalDisposition.ADOPT)
        assertEquals(
            GoalEvidence.ALREADY_HELD,
            review(alpha, "r4", 200, GoalDisposition.ADOPT).first?.outcome,
        )
        ledger.present(alpha, "r5", 200)
        val uncounted =
            answer(
                alpha,
                "r5",
                GoalDisposition.REVISE,
            ).let { it.copy(goal = it.goal?.copy(successCount = null)) }
        assertEquals(GoalEvidence.SUCCESS_TARGET_MISSING, ledger.resolve(alpha, uncounted)?.outcome)
        assertEquals(statementOf(STICK, 1L), ledger.present(alpha, "r6", 300)?.statement)
    }

    @Test
    fun goalsAreIsolatedByAgentAndAnAnswerCannotReachAnotherAgentsReview() {
        review(alpha, "r1", 100, GoalDisposition.ADOPT)
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
            ledger.resolve(alpha, answer(alpha, "r1", GoalDisposition.ADOPT))?.outcome,
        )
        val second = ledger.resolve(alpha, answer(alpha, "r2", GoalDisposition.ADOPT))
        assertEquals(GoalEvidence.STALE, second?.outcome)
        assertEquals("r1", second?.goalId)

        ledger.present(alpha, "r3", 200)
        ledger.present(alpha, "r4", 200)
        assertEquals(
            GoalEvidence.REVISED,
            ledger.resolve(alpha, answer(alpha, "r3", GoalDisposition.REVISE, 3L))?.outcome,
        )
        assertEquals(GoalEvidence.STALE, ledger.resolve(alpha, answer(alpha, "r4", GoalDisposition.COMPLETE))?.outcome)
        assertEquals(statementOf(STICK, 3L), ledger.present(alpha, "r5", 300)?.statement)
    }

    @Test
    fun lifecycleRecordsClearTheGoalAndEveryAnswerInFlight() {
        val written = mutableListOf<BodyEventRecord>()
        val log = ledger.watching(BodyEventLog { written.add(it) })
        for (event in listOf(BodyEvent.BODY_DISCONNECTED, BodyEvent.SPAWNED, BodyEvent.DESPAWNED, BodyEvent.DIED)) {
            review(alpha, "a-$event", 100, GoalDisposition.ADOPT)
            review(beta, "a-$event", 100, GoalDisposition.ADOPT, 4L)
            ledger.present(alpha, "b-$event", 200)
            log.append(BodyEventRecord(150, alpha.value, event, "lifecycle"))

            // The answer in flight across the boundary is stale, even one that adopts.
            val crossed = ledger.resolve(alpha, answer(alpha, "b-$event", GoalDisposition.ADOPT))
            assertEquals(GoalEvidence.NOT_REVIEWED, crossed?.outcome)
            assertNull(ledger.present(alpha, "c-$event", 300))
            ledger.forget(alpha, "c-$event")
            // The peer keeps its own goal; a disconnect never moves one between agents.
            assertEquals(statementOf(STICK, 4L), ledger.present(beta, "c-$event", 300)?.statement)
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
            ledger.resolve(alpha, answer(alpha, "r1", GoalDisposition.ADOPT))?.outcome,
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
        val evicted = ledger.resolve(alpha, answer(alpha, "p0", GoalDisposition.ADOPT))
        assertEquals(GoalEvidence.NOT_REVIEWED, evicted?.outcome)
        val kept = ledger.resolve(alpha, answer(alpha, "p1", GoalDisposition.ADOPT))
        assertEquals(GoalEvidence.ADOPTED, kept?.outcome)
    }

    @Test
    fun aDecisionThatAnsweredNoGoalReviewChangesNothing() {
        review(alpha, "r1", 100, GoalDisposition.ADOPT)
        val threat = decisionFor(perception(requestId = "t1", trigger = Trigger.GoalReview))
        assertNull(ledger.resolve(alpha, threat))
        val choosing = ledger.resolve(alpha, threat.copy(goal = goalChoice(GoalDisposition.ABANDON)))
        assertEquals(GoalEvidence.NOT_REVIEWED, choosing?.outcome)
        assertEquals("r1", ledger.present(alpha, "r2", 200)?.goalId)
    }

    private fun seen(
        x: Double,
        vararg stacks: Pair<String, Long>,
    ): GoalObservation =
        // A bush in sight could give up to eight more sticks, as the r11 and r13 scenes' bushes did.
        GoalObservation(mapOf(*stacks), listOf(x, 64.0, 0.0), capacity = mapOf(STICK to 8L))

    private fun affirm(
        requestId: String,
        tick: Long,
        observed: GoalObservation,
        choice: GoalChoice?,
        intent: Intent = DIG,
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
    ): Pair<GoalSummary?, GoalEvidence?> = stepWith(requestId, observed, goalChoice(disposition), intent, outcome)

    private fun revisedTo(count: Long) = goalChoice(GoalDisposition.REVISE, success = STICK to count)

    /** [step] under an exact [choice], as a revise to a named total needs. */
    private fun stepWith(
        requestId: String,
        observed: GoalObservation,
        choice: GoalChoice,
        intent: Intent,
        outcome: String = EventTag.ACCEPTED,
    ): Pair<GoalSummary?, GoalEvidence?> {
        val tick = requestId.drop(1).toLong() * 100
        // The step's own target is in sight, and a walk the body accepted arrived.
        // Each walked-to target is a bush, a source of the goal's sticks, as the r11 scenes' were.
        val seen =
            observed.copy(
                visible = observed.visible + listOfNotNull(intent.targetRef),
                gives = observed.gives + listOfNotNull(intent.targetRef).associateWith { setOf(STICK) },
            )
        val result = affirm(requestId, tick, seen, choice, intent)
        val walk = intent is Intent.MoveTo || intent is Intent.Flee
        ledger.stepped(
            alpha,
            requestId,
            tagOf(intent),
            outcome,
            reached = if (walk) outcome == EventTag.ACCEPTED else null,
        )
        return result
    }

    @Test
    fun aWorkingGoalNeedsItsTypedConditionStepAndVerdict() {
        val adoptBare = GoalChoice(GoalDisposition.ADOPT, step = "walk to the tree")
        assertEquals(GoalEvidence.SUCCESS_TARGET_MISSING, affirm("r1", 100, seen(0.0), adoptBare).second?.outcome)
        // From routine-v26 the step is the intent's own, worded by the body: no choice states it,
        // and one that does is not believed (r17 epsilon worded terracotta while it dug a bush).
        val adoptNoStep = adoptBare.copy(successItem = STICK, successCount = 2, step = null)
        assertEquals(GoalEvidence.ADOPTED, affirm("r2", 100, seen(0.0), adoptNoStep).second?.outcome)
        assertEquals("dig bush", ledger.present(alpha, "r2b", 150, seen(0.0))?.step)
        ledger.forget(alpha, "r2b")
        ledger.clear(alpha)
        affirm("r3", 100, seen(0.0), goalChoice(GoalDisposition.ADOPT))
        val unjudged = goalChoice(GoalDisposition.CONTINUE).copy(stepResult = null)
        assertEquals(GoalEvidence.STEP_RESULT_MISSING, affirm("r4", 200, seen(0.0), unjudged).second?.outcome)
        val carried = ledger.present(alpha, "r5", 300, seen(0.0))
        assertEquals("item.minecraft.stick", carried?.successItem)
        assertEquals(1L, carried?.successCount)
    }

    @Test
    fun theStepIsTypedFromTheDecisionsOwnIntentNeverClaimed() {
        // r14 gamma: a step naming a dropped stick while the intent walked the other way. A step is
        // the intent's own, so it cannot name anything else.
        affirm("r1", 100, seen(0.0), goalChoice(GoalDisposition.ADOPT), Intent.Dig("b1"))
        val adopted = ledger.present(alpha, "r2", 200, seen(0.0))
        assertEquals(IntentAction.DIG, adopted?.stepAction)
        assertEquals("b1", adopted?.stepRef)
        affirm("r2", 200, seen(0.0), goalChoice(GoalDisposition.CONTINUE), Intent.Collect("d4a", approach = true))
        val continued = ledger.present(alpha, "r3", 300, seen(0.0))
        assertEquals(IntentAction.COLLECT, continued?.stepAction)
        assertEquals("d4a", continued?.stepRef)
    }

    @Test
    fun aGoalPursuedByIdlingIsRefusedWithItsIntent() {
        // r14 eta adopted a goal with an idle step: waiting is no step.
        val idling = affirm("r1", 100, seen(0.0), goalChoice(GoalDisposition.ADOPT), Intent.Idle)
        assertEquals(GoalEvidence.STEP_IDLE, idling.second?.outcome)
        assertNull(ledger.present(alpha, "r2", 200, seen(0.0)))
        affirm("r2", 200, seen(0.0), goalChoice(GoalDisposition.ADOPT))
        for ((request, disposition) in listOf("r3" to GoalDisposition.CONTINUE, "r4" to GoalDisposition.REVISE)) {
            val resting = affirm(request, 300, seen(0.0), goalChoice(disposition), Intent.Rest)
            assertEquals(GoalEvidence.STEP_IDLE, resting.second?.outcome, request)
        }
        // A goal sight can still meet is pursued, not abandoned (r17 theta body:00000333).
        val achievable = affirm("r5", 400, seen(0.0), goalChoice(GoalDisposition.ABANDON), Intent.Idle)
        assertEquals(GoalEvidence.ABANDONED_ACHIEVABLE, achievable.second?.outcome)
        // Ending a goal sight can no longer meet takes no step, so it may idle.
        val barren = GoalObservation(emptyMap(), listOf(0.0, 64.0, 0.0))
        val ended = affirm("r6", 500, barren, goalChoice(GoalDisposition.ABANDON), Intent.Idle)
        assertEquals(GoalEvidence.ABANDONED, ended.second?.outcome)
    }

    @Test
    fun aGoalAlreadyMetOrBeyondWhatIsInSightIsRefused() {
        // r14 theta, delta and eta set stick goals they already held, then "completed" them.
        val holding = seen(0.0, STICK to 2L)
        val met = affirm("r1", 100, holding, goalChoice(GoalDisposition.ADOPT, success = STICK to 2L))
        assertEquals(GoalEvidence.ALREADY_MET, met.second?.outcome)
        // r14 kappa: a stick goal with no bush or drop in sight to give one more.
        val barren = GoalObservation(mapOf(STICK to 1L), listOf(0.0, 64.0, 0.0))
        val unreachable = affirm("r2", 100, barren, goalChoice(GoalDisposition.ADOPT, success = STICK to 2L))
        assertEquals(GoalEvidence.SUCCESS_UNOBTAINABLE, unreachable.second?.outcome)
        // What the inventory holds and what is in sight could give, together, bound the count.
        val bush = barren.copy(capacity = mapOf(STICK to 2L))
        val beyond = affirm("r3", 100, bush, goalChoice(GoalDisposition.ADOPT, success = STICK to 4L))
        assertEquals(GoalEvidence.SUCCESS_UNOBTAINABLE, beyond.second?.outcome)
        val within = affirm("r4", 100, bush, goalChoice(GoalDisposition.ADOPT, success = STICK to 3L))
        assertEquals(GoalEvidence.ADOPTED, within.second?.outcome)
        // A revise to a count already held is no new goal either.
        ledger.stepped(alpha, "r4", EventTag.DIG, EventTag.ACCEPTED)
        val reworded = affirm("r5", 200, bush, revisedTo(1L))
        assertEquals(GoalEvidence.ALREADY_MET, reworded.second?.outcome)
    }

    @Test
    fun theConditionIsTheHeldCountOfItsItemAndTheDeltaIsEveryItemThatChanged() {
        affirm(
            "r1",
            100,
            seen(0.0, DIRT to 3L),
            goalChoice(GoalDisposition.ADOPT, success = STICK to 2L),
        )
        val (half, _) = affirm("r2", 200, seen(0.0, DIRT to 2L, STICK to 1L), goalChoice(GoalDisposition.CONTINUE))
        assertEquals(
            GoalProgress(
                conditionMet = false,
                conditionHeld = 1,
                inventoryDelta = listOf(InventoryChange(DIRT, -1), InventoryChange(STICK, 1)),
                conditionAdvanced = true,
                stepAdvanced = false,
                stepEvidence = StepEvidence.NONE,
                stepsWithoutProgress = 0,
            ),
            half?.progress,
        )
        val (full, _) =
            affirm(
                "r3",
                300,
                seen(0.0, DIRT to 2L, STICK to 2L),
                goalChoice(GoalDisposition.REVISE),
            )
        assertEquals(true, full?.progress?.conditionMet)
        assertEquals(listOf(InventoryChange(STICK, 1)), full?.progress?.inventoryDelta)
    }

    @Test
    fun aMetConditionMustBeCompletedOrRevisedAtTheNextReview() {
        affirm("r1", 100, seen(0.0), goalChoice(GoalDisposition.ADOPT))
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
            affirm("r5", 300, holding, revisedTo(3L))
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
    fun aWorldStepAdvancesOnlyOnWhatItGainedOrWhatTheServerSawItsOwnDigDrop() {
        step("r1", seen(0.0), GoalDisposition.ADOPT, Intent.Dig("b1"))
        // The dig gave dirt, which is not the stick the goal names nor made into one: no evidence
        // (r19: a sapling dug for a stick goal reset the stall count).
        val (other, _) = step("r2", seen(0.0, DIRT to 1L), GoalDisposition.CONTINUE, Intent.Dig("b2"))
        assertEquals(StepEvidence.NONE, other?.progress?.stepEvidence)
        assertEquals(false, other?.progress?.stepAdvanced)
        // The server attributed a drop to r2's own dig, which another agent then took: evidence.
        val ownDrop = seen(0.0, DIRT to 1L).copy(dropped = setOf("r2"))
        val (dropped, _) = step("r3", ownDrop, GoalDisposition.CONTINUE, Intent.Dig("b3"))
        assertEquals(StepEvidence.DROPPED, dropped?.progress?.stepEvidence)
        // A drop the server attributed to another request is not r3's: no evidence (r14 delta was
        // credited for the stick beta's dig dropped).
        val (barren, _) = step("r4", ownDrop, GoalDisposition.CONTINUE, Intent.Dig("b4"))
        assertEquals(StepEvidence.NONE, barren?.progress?.stepEvidence)
        assertEquals(false, barren?.progress?.stepAdvanced)
    }

    @Test
    fun stepsThatAdvanceNothingEndTheGoalAtTheBoundEvenWhenRevised() {
        step("r1", seen(0.0), GoalDisposition.ADOPT, Intent.Dig("b1"))
        step("r2", seen(0.0), GoalDisposition.CONTINUE, Intent.Dig("b2"))
        stepWith("r3", seen(0.0), revisedTo(2L), Intent.Dig("b3"))
        val (atBound, refused) =
            stepWith("r4", seen(0.0), revisedTo(3L), Intent.Dig("b4"))
        assertEquals(MAX_GOAL_STEPS_WITHOUT_PROGRESS, atBound?.progress?.stepsWithoutProgress)
        assertEquals(GoalEvidence.STALLED, refused?.outcome)
        assertNull(ledger.present(alpha, "r5", 500, seen(0.0)))
    }

    @Test
    fun aReviseToTheHeldTotalChangesNothingAndIsRefusedWithItsIntent() {
        // r19: 28 of 31 revises restated the total they were shown, and read as goal activity.
        stepWith("r1", seen(0.0), goalChoice(GoalDisposition.ADOPT, success = STICK to 2L), Intent.Dig("b1"))
        val (_, restated) =
            stepWith(
                "r2",
                seen(0.0),
                revisedTo(2L),
                Intent.Dig("b2"),
            )
        assertEquals(GoalEvidence.REVISE_UNCHANGED, restated?.outcome)
        assertEquals(GoalEvidence.REVISE_UNCHANGED, ledger.takeRefusal(alpha)?.outcome)
        val (_, changed) =
            stepWith(
                "r3",
                seen(0.0),
                revisedTo(3L),
                Intent.Dig("b3"),
            )
        assertEquals(GoalEvidence.REVISED, changed?.outcome)
    }

    private companion object {
        const val STICK = "item.minecraft.stick"
        const val DIRT = "item.minecraft.dirt"
        val DIG: Intent = Intent.Dig("bush")
    }
}
