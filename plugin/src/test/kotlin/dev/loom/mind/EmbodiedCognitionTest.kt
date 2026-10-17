/*
 * The round trip, and the threading rule it exists to hold.
 *
 * The body here is a recording fake that also asserts *which thread* touched it,
 * because that is the property most easily lost and least visibly broken: a
 * decision applied straight from a bridge worker would pass a test that only
 * checked the outcome, and corrupt world state on a real server.
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
import dev.loom.body.describe
import dev.loom.contract.Bearing
import dev.loom.contract.Decision
import dev.loom.contract.DecisionImportance
import dev.loom.contract.GoalDisposition
import dev.loom.contract.Intent
import dev.loom.contract.Perception
import dev.loom.contract.Provenance
import dev.loom.contract.Trigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.milliseconds

/** The tick a perception is captured on, and the later one its decision lands on. */
private const val PERCEIVE_TICK = 100L
private const val DECIDED_TICK = 140L

/**
 * A body that records what was asked of it, and asserts which thread asked.
 *
 * [perceptions] is given the trigger, because a real body embeds the trigger it
 * was handed into the perception it produces - a double that dropped it would let
 * a classification bug pass unnoticed.
 *
 * It also writes the two event records a real body writes, from nothing but the ids
 * it was handed, so a test can assert that a perception, the decision it caused, and
 * the action that followed all join up. [present] tells it to behave as a body that
 * has gone - died, or travelled through a portal - since its agent perceived.
 */
private class RecordingBody(
    private val perceptions: (Trigger) -> Perception?,
    private val mainThread: () -> Thread?,
    private val present: Boolean = true,
    private val events: BodyEventLog = BodyEventLog { },
    private val currentTick: () -> Long = { 0L },
) : BodyPort {
    val applied = mutableListOf<Intent>()

    override fun spawn(agentId: AgentId) = fail("cognition must not spawn bodies")

    override fun despawn(agentId: AgentId) = fail("cognition must not despawn bodies")

    override fun apply(
        agentId: AgentId,
        intent: Intent,
        requestId: String?,
    ): CompletableFuture<IntentOutcome> {
        assertEquals(
            mainThread(),
            Thread.currentThread(),
            "a decided intent must reach the body on the main thread and nowhere else",
        )
        // A body that is gone records nothing, exactly as the adapter does: recording
        // the loss belongs to the decision that was lost.
        if (!present) return CompletableFuture.completedFuture(IntentOutcome.NoSuchBody)
        applied.add(intent)
        val outcome = IntentOutcome.Accepted("applied $intent")
        record(agentId, BodyEvent.INTENT_APPLIED, "${describe(intent)} -> ${describe(outcome)}", requestId)
        return CompletableFuture.completedFuture(outcome)
    }

    override fun perceive(
        agentId: AgentId,
        trigger: Trigger,
    ): CompletableFuture<Perception?> =
        CompletableFuture.completedFuture(
            perceptions(trigger)?.also {
                events.append(
                    BodyEventRecord(
                        tick = currentTick(),
                        agentId = agentId.value,
                        event = BodyEvent.PERCEIVED,
                        detail = "${it.surroundings.size} observation(s)",
                        requestId = it.requestId,
                        trigger = EventTag.GOAL_REVIEW,
                        perception = it,
                    ),
                )
            },
        )

    override fun senseThreat(agentId: AgentId): CompletableFuture<Perception?> =
        fail("this fake answers a trigger it is handed; a threat scan is the driver's business")

    override fun tick(tick: Long) = fail("cognition must never drive the tick")

    override fun liveBodies(): Set<AgentId> = fail("cognition does not enumerate bodies")

    private fun record(
        agentId: AgentId,
        event: BodyEvent,
        detail: String,
        requestId: String?,
    ) = events.append(BodyEventRecord(currentTick(), agentId.value, event, detail, requestId))
}

class EmbodiedCognitionTest {
    private val agent = AgentId("agent-alpha")
    private val main = FakeMainThread()

    /** The server clock, advanced by hand so a decision can land after its perception. */
    private var tick = PERCEIVE_TICK

    /** A body that always perceives [perception], whatever the trigger. */
    private fun bodyWith(perception: Perception?) = RecordingBody({ perception }, main::drainingThread)

    /** A body that perceives its trigger, as a real one does. */
    private fun perceivingBody(): RecordingBody {
        val perceive = { trigger: Trigger -> perception(agentId = agent.value, trigger = trigger) }
        return RecordingBody(perceive, main::drainingThread)
    }

    /** A body whose own records land in [records], alongside the journal's. */
    private fun recordingBodyInto(
        records: MutableList<BodyEventRecord>,
        present: Boolean = true,
    ) = RecordingBody(
        { perceptionNearBush(agentId = agent.value) },
        main::drainingThread,
        present,
        BodyEventLog { records.add(it) },
        currentTick = { tick },
    )

    /** A real journal writing into [records], on the same clock as the body. */
    private fun journalInto(records: MutableList<BodyEventRecord>) =
        DecisionJournal(BodyEventLog { records.add(it) }, currentTick = { tick })

    /** The typed failure a handler was given, or a test failure if it was not one. */
    private fun typedFailure(thrown: Throwable): CognitionFailure =
        (thrown as? CognitionUnavailableException)?.failure ?: fail("expected a typed cognition failure, got $thrown")

    @Test
    @DisplayName("a shutdown ends every chain still waiting for the mind, and a late answer is ignored")
    fun aShutdownEndsEveryWaitingChain() {
        val records = mutableListOf<BodyEventRecord>()
        val body = perceivingBody()
        val pending = CompletableFuture<Decision>()
        val cognition =
            EmbodiedCognition(body, mind = { pending }, mainThread = main, journal = journalInto(records))
        val waiting = perception(requestId = "review-1", agentId = agent.value)
        cognition.submit(agent, waiting)
        cognition.shutDown()
        val ended = records.single { it.event == BodyEvent.COGNITION_UNAVAILABLE }
        assertEquals("review-1", ended.requestId)
        assertEquals(EventTag.COGNITION_SHUTDOWN, ended.outcome)
        pending.complete(decisionFor(waiting, Intent.Idle))
        main.drain()
        assertTrue(body.applied.isEmpty(), "an answer after the shutdown reaches no body")
        assertEquals(1, records.size, "and records nothing more")
        cognition.submit(agent, perception(requestId = "review-2", agentId = agent.value))
        main.drain()
        assertEquals(1, records.size, "nothing new is sent after the shutdown")
    }

    @Test
    @DisplayName("a repeat about the threat an agent is already answering is coalesced, not sent")
    fun aDangerRepeatIsCoalescedIntoTheActiveEpisode() {
        val records = mutableListOf<BodyEventRecord>()
        val asked = mutableListOf<String>()
        val decisions = mutableMapOf<String, CompletableFuture<Decision>>()
        val cognition =
            EmbodiedCognition(
                perceivingBody(),
                mind = { p ->
                    asked.add(p.requestId)
                    CompletableFuture<Decision>().also { decisions[p.requestId] = it }
                },
                mainThread = main,
                journal = journalInto(records),
            )
        val zombie = Trigger.ThreatSighted("entity.minecraft.zombie", "e1", 6.0, Bearing.NORTH, true, true)
        val first = perception(requestId = "danger-1", agentId = agent.value, trigger = zombie)
        val repeat = perception(requestId = "danger-2", agentId = agent.value, trigger = Trigger.Damaged(3, "zombie"))
        cognition.submit(agent, first)
        cognition.submit(agent, repeat)
        main.drain()
        assertEquals(listOf("danger-1"), asked, "the mind is asked once about one threat")
        val coalesced = records.single { it.event == BodyEvent.COGNITION_UNAVAILABLE }
        assertEquals("danger-2", coalesced.requestId)
        assertEquals(EventTag.DANGER_COALESCED, coalesced.outcome)

        // Once the decision is applied, the cooldown holds, then a new engagement is asked.
        decisions.getValue("danger-1").complete(decisionFor(first, Intent.Flee("e1")))
        main.drain()
        tick += DANGER_EPISODE_COOLDOWN_TICKS
        cognition.submit(agent, perception(requestId = "danger-3", agentId = agent.value, trigger = zombie))
        tick += 1
        cognition.submit(agent, perception(requestId = "danger-4", agentId = agent.value, trigger = zombie))
        main.drain()
        assertEquals(listOf("danger-1", "danger-4"), asked)
    }

    @Test
    @DisplayName("a perception becomes a decision and the decision becomes an action on the body")
    fun onePerceptionMakesOneAction() {
        val captured = perception(agentId = agent.value)
        val body = bodyWith(captured)
        val applied = mutableListOf<AppliedDecision>()
        val cognition =
            EmbodiedCognition(
                body,
                mind = { p -> CompletableFuture.completedFuture(decisionFor(p, Intent.Say("hello"))) },
                mainThread = main,
                journal = silentJournal(),
                callbacks =
                    CognitionCallbacks(
                        onApplied = { applied.add(it) },
                        onUnavailable = { _, failure -> fail("unexpected failure: $failure") },
                    ),
            )

        val sent = cognition.consider(agent, Trigger.GoalReview)

        // Even with a mind that answered instantly and inline, the body is untouched
        // until the main thread runs: applying a decision from inside whatever
        // triggered it is how re-entrant world mutation gets in.
        assertTrue(body.applied.isEmpty(), "the body must not be touched before the main thread runs")
        assertEquals(1, main.pending)

        main.drain()

        assertEquals(captured.requestId, sent.toCompletableFuture().join()?.requestId)
        assertEquals(listOf<Intent>(Intent.Say("hello")), body.applied)
        assertEquals(IntentOutcome.Accepted("applied ${Intent.Say("hello")}"), applied.single().outcome)
        // The provenance the mind stamped survives to the applied record, which is
        // what makes the chain a research record rather than just an action.
        assertEquals("test-model", applied.single().decision.provenance.model)
    }

    @Test
    @DisplayName("a decision produced on another thread still reaches the body on the main thread")
    fun anOffThreadDecisionIsMarshalledBack() {
        val captured = perception(agentId = agent.value)
        val body = bodyWith(captured)
        val bridge =
            AsyncCognitionBridge(
                transport = { p -> decisionFor(p, Intent.MoveTo("b1")) },
                timeout = PATIENCE,
                workers = 1,
                queueDepth = 1,
            )
        bridge.use {
            EmbodiedCognition(body, bridge, main, silentJournal()).submit(agent, captured)
            // Drained from this thread, which is therefore "the main thread" as far
            // as the body is concerned; RecordingBody fails if apply arrives on the
            // bridge worker instead.
            main.drainUntil(2)
        }
        assertEquals(listOf<Intent>(Intent.MoveTo("b1")), body.applied)
    }

    @Test
    @DisplayName("an agent with no body is not sent to the mind at all")
    fun aBodylessAgentDoesNotCostADecision() {
        val cognition =
            EmbodiedCognition(
                bodyWith(null),
                mind = { fail("a bodyless agent must never reach the mind: there is nothing to act with") },
                mainThread = main,
                journal = silentJournal(),
            )

        val considered = cognition.consider(agent, Trigger.GoalReview)
        main.drain()
        assertNull(considered.toCompletableFuture().join())
        assertEquals(0, main.pending, "nothing to decide means nothing scheduled")
    }

    @Test
    @DisplayName("a mind that does not answer is reported on the main thread, and the body is untouched")
    fun aLostDecisionIsVisible() {
        val captured = perception(agentId = agent.value)
        val body = bodyWith(captured)
        val failures = mutableListOf<Throwable>()
        val bridge =
            AsyncCognitionBridge(
                transport = { error("the mind is down") },
                timeout = 100.milliseconds,
                workers = 1,
                queueDepth = 1,
            )
        bridge.use {
            EmbodiedCognition(
                body,
                bridge,
                main,
                silentJournal(),
                CognitionCallbacks(
                    onApplied = { fail("no decision was produced, so nothing may be applied") },
                    onUnavailable = { _, failure -> failures.add(failure) },
                ),
            ).submit(agent, captured)
            main.drainUntil(1)
        }
        assertTrue(body.applied.isEmpty(), "a failed decision must leave the body alone")
        assertTrue(
            typedFailure(failures.single()) is CognitionFailure.Failed,
            "the typed cause must survive to the handler",
        )
    }

    @Test
    @DisplayName("a future that completes with neither decision nor cause is reported, not taken as a no-op")
    fun anEmptyCompletionIsAFailure() {
        val body = bodyWith(perception(agentId = agent.value))
        val failures = mutableListOf<Throwable>()
        EmbodiedCognition(
            body,
            // A transport-shaped lie: success, with nothing in it.
            mind = { CompletableFuture.completedFuture(null) },
            mainThread = main,
            journal = silentJournal(),
            callbacks =
                CognitionCallbacks(
                    onApplied = { fail("there was no decision to apply") },
                    onUnavailable = { _, failure -> failures.add(failure) },
                ),
        ).submit(agent, perception(agentId = agent.value))

        main.drain()

        assertTrue(body.applied.isEmpty())
        assertEquals(
            CognitionFailure.Failed("the mind completed without a decision"),
            typedFailure(failures.single()),
        )
    }

    @Test
    @DisplayName("the perception sent to the mind carries the tier its trigger earned")
    fun considerClassifiesBeforeSending() {
        // The body reports the routine default; what reaches the mind is the
        // classified tier, because that is what the decision's cost is set from.
        val seen = mutableListOf<Perception>()
        val cognition =
            EmbodiedCognition(
                perceivingBody(),
                mind = { p ->
                    seen.add(p)
                    CompletableFuture.completedFuture(decisionFor(p))
                },
                mainThread = main,
                journal = silentJournal(),
            )

        cognition.consider(agent, Trigger.Damaged(amount = 3L, source = "zombie"))
        main.drain()

        assertEquals(DecisionImportance.PIVOTAL, seen.single().importance)
    }

    @Test
    @DisplayName("several decisions landing together each get their own main-thread task")
    fun noDecisionIsLostWhenSeveralLandAtOnce() {
        val body = perceivingBody()
        val bridge =
            AsyncCognitionBridge(
                transport = { p -> decisionFor(p) },
                timeout = PATIENCE,
                workers = 2,
                queueDepth = 8,
            )
        bridge.use {
            val cognition = EmbodiedCognition(body, bridge, main, silentJournal())
            // Five agents, because one agent has at most one review in flight at a time.
            repeat(
                5,
            ) { i -> cognition.submit(AgentId("agent-$i"), perception(requestId = "req-$i", agentId = "agent-$i")) }
            main.drainUntil(10)
        }
        assertEquals(5, body.applied.size)
    }

    @Test
    @DisplayName("a perception, the decision it caused and the action taken join up in the record")
    fun theRecordIsOneChainPerRoundTrip() {
        val records = mutableListOf<BodyEventRecord>()
        val body = recordingBodyInto(records)
        val cognition =
            EmbodiedCognition(
                body,
                mind = { p -> CompletableFuture.completedFuture(decisionFor(p, Intent.Say("hello"))) },
                mainThread = main,
                journal = journalInto(records),
            )

        cognition.consider(agent, Trigger.GoalReview)
        // The mind took time, as a real one does: the decision lands on a later tick
        // than the perception that prompted it.
        tick = DECIDED_TICK
        main.drain()

        assertEquals(
            listOf(BodyEvent.PERCEIVED, BodyEvent.DECIDED, BodyEvent.INTENT_APPLIED),
            records.map { it.event },
            "the record must read perception -> decision -> action, in that order",
        )
        assertEquals(
            listOf("req-1", "req-1", "req-1"),
            records.map { it.requestId },
            "all three records must join by request id: several agents can think in one tick",
        )
        assertEquals(listOf(agent.value), records.map { it.agentId }.distinct())
        assertEquals(listOf(PERCEIVE_TICK, DECIDED_TICK, DECIDED_TICK), records.map { it.tick })
        assertEquals(
            Provenance(tier = DecisionImportance.ROUTINE, model = "test-model", promptVersion = "test-v1"),
            records[1].provenance,
            "the decision record carries the stamp the mind put on it, whole",
        )
        assertEquals(records[0].perception?.requestId, records[1].decision?.requestId)
        assertEquals(Intent.Say("hello"), records[1].decision?.intent)
        // A perception and an action claim no model: only the decision was answered by one.
        assertNull(records[0].provenance)
        assertNull(records[2].provenance)
    }

    @Test
    @DisplayName("a decision pursuing its goal by idling is refused before the body sees it")
    fun aDecisionIncoherentWithItsGoalNeverReachesTheBody() {
        val records = mutableListOf<BodyEventRecord>()
        val body = recordingBodyInto(records)
        val applied = mutableListOf<AppliedDecision>()
        // r14 eta, body:00000197: a goal adopted with an idle step.
        val adopting = goalChoice(GoalDisposition.ADOPT)
        val cognition =
            EmbodiedCognition(
                body,
                mind = { p -> CompletableFuture.completedFuture(decisionFor(p, Intent.Idle).copy(goal = adopting)) },
                mainThread = main,
                journal = journalInto(records),
                callbacks = CognitionCallbacks(onApplied = { applied.add(it) }),
            )

        cognition.consider(agent, Trigger.GoalReview)
        main.drain()

        assertTrue(body.applied.isEmpty(), "a refused decision must not reach the body")
        assertEquals(listOf(BodyEvent.PERCEIVED, BodyEvent.DECIDED, BodyEvent.INTENT_APPLIED), records.map { it.event })
        assertEquals(GoalEvidence.STEP_IDLE, records[1].goal?.outcome)
        assertEquals(GoalEvidence.STEP_IDLE, records[2].outcome)
        assertEquals("req-1", records[2].requestId)
        assertEquals(GoalEvidence.STEP_IDLE, (applied.single().outcome as IntentOutcome.Rejected).tag)
    }

    @Test
    @DisplayName("only an accepted step of the held goal settles, once, into a follow-up review")
    fun anAcceptedGoalStepIsTheOneSettlement() {
        val settled = mutableListOf<Pair<AgentId, Long>>()
        val answers =
            ArrayDeque(
                listOf(
                    // A goal adopted with a dig: its step settles.
                    {
                            p: Perception ->
                        decisionFor(p, Intent.Dig("bush")).copy(goal = goalChoice(GoalDisposition.ADOPT))
                    },
                    // A walk with no goal choice: no step of the goal, nothing settles.
                    { p: Perception -> decisionFor(p, Intent.MoveTo("bush")) },
                ),
            )
        var request = 0
        val body =
            RecordingBody(
                { perceptionNearBush(requestId = "req-${++request}", agentId = agent.value) },
                main::drainingThread,
                true,
                BodyEventLog { },
                currentTick = { tick },
            )
        val cognition =
            EmbodiedCognition(
                body,
                mind = { p -> CompletableFuture.completedFuture(answers.removeFirst()(p)) },
                mainThread = main,
                journal = journalInto(mutableListOf()),
                callbacks = CognitionCallbacks(onStepSettled = { who, at, _ -> settled.add(who to at) }),
            )

        cognition.consider(agent, Trigger.GoalReview)
        main.drain()
        cognition.consider(agent, Trigger.StepSettled)
        main.drain()

        assertEquals(listOf(agent to tick), settled)
    }

    @Test
    @DisplayName("a decision for a body that has gone is recorded, not silently dropped")
    fun aDiscardedDecisionIsStillRecorded() {
        val records = mutableListOf<BodyEventRecord>()
        // A body that has died or crossed a portal since perceiving.
        val body = recordingBodyInto(records, present = false)
        val applied = mutableListOf<AppliedDecision>()
        EmbodiedCognition(
            body,
            mind = { p -> CompletableFuture.completedFuture(decisionFor(p)) },
            mainThread = main,
            journal = journalInto(records),
            callbacks =
                CognitionCallbacks(
                    onApplied = { applied.add(it) },
                ),
        ).submit(agent, perception(agentId = agent.value))

        main.drain()

        assertEquals(IntentOutcome.NoSuchBody, applied.single().outcome)
        assertEquals(
            listOf(BodyEvent.DECIDED, BodyEvent.DECISION_DISCARDED),
            records.map { it.event },
            "a decision that could not be applied was still made and still billed",
        )
        val discarded = records.last()
        assertEquals("req-1", discarded.requestId)
        assertEquals("test-model", discarded.provenance?.model)
    }

    @Test
    @DisplayName("a mind that never answered is recorded as an unanswered cycle, tagged and joined up")
    fun anUnansweredPerceptionIsRecorded() {
        val records = mutableListOf<BodyEventRecord>()
        val body =
            RecordingBody(
                { trigger -> perception(agentId = agent.value, trigger = trigger) },
                main::drainingThread,
                events = BodyEventLog { records.add(it) },
                currentTick = { tick },
            )
        // The failure is handed over already typed rather than produced by a real
        // deadline: what the bridge does when a mind overruns is its own test, and what
        // this one is about is the record the loss leaves behind.
        val timedOut =
            CognitionUnavailableException(
                CognitionFailure.TimedOut(PATIENCE),
                "the mind did not answer within $PATIENCE",
            )
        val cognition =
            EmbodiedCognition(
                body,
                mind = { CompletableFuture.failedFuture(timedOut) },
                mainThread = main,
                journal = journalInto(records),
                callbacks =
                    CognitionCallbacks(
                        onApplied = { fail("nothing was decided, so nothing may be applied") },
                    ),
            )

        cognition.consider(agent, Trigger.Damaged(amount = 3L, source = "zombie"))
        tick = DECIDED_TICK
        main.drain()

        assertEquals(
            listOf(BodyEvent.PERCEIVED, BodyEvent.COGNITION_UNAVAILABLE),
            records.map { it.event },
            "a cycle the mind never answered still happened, and belongs on the agent's chain",
        )
        val unanswered = records.last()
        // These two fields are the whole of what a display may show about a lost
        // decision: what prompted the cycle, and the countable name of what stopped it.
        // The failure's own text stays in the detail, where its length is nobody's
        // problem, and never becomes a line on somebody's screen.
        assertEquals(EventTag.DAMAGED, unanswered.trigger)
        assertEquals("timedOut", unanswered.outcome)
        assertEquals("req-1", unanswered.requestId, "the loss must join to the perception that caused it")
        assertEquals("req-1", unanswered.perception?.requestId)
        assertNull(unanswered.provenance, "no model answered, so no record of this may name one")
        assertNull(unanswered.intent, "an unanswered perception produced no action to name")
        assertTrue(body.applied.isEmpty(), "a lost decision must leave the body alone")
    }

    @Test
    @DisplayName("a decision is recorded against the agent Loom asked about, not the one the answer names")
    fun theRecordKeepsLoomsOwnAgentId() {
        val records = mutableListOf<BodyEventRecord>()
        EmbodiedCognition(
            bodyWith(perception(agentId = agent.value)),
            // A mind that answers about somebody else. The body it acts on is chosen by
            // Loom, so the record must be filed the same way or the two disagree.
            mind = { p -> CompletableFuture.completedFuture(decisionFor(p).copy(agentId = "agent-elsewhere")) },
            mainThread = main,
            journal = journalInto(records),
        ).submit(agent, perception(agentId = agent.value))

        main.drain()

        assertEquals(agent.value, records.single().agentId)
    }

    @Test
    @DisplayName("the fake scheduler models runTask: queued, never inline")
    fun theFakeSchedulerNeverRunsInline() {
        // Guards the test double itself: if it ran tasks inline, every threading
        // assertion above would pass vacuously.
        var ran = false
        main.execute { ran = true }
        assertTrue(!ran, "a MainThread must schedule, never run inline")
        main.drain()
        assertTrue(ran)
    }
}
