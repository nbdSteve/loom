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
import dev.loom.body.BodyLocation
import dev.loom.body.BodyPort
import dev.loom.body.BodySpawnResult
import dev.loom.body.IntentOutcome
import dev.loom.contract.DecisionImportance
import dev.loom.contract.Intent
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val PATIENCE = 5.seconds

/**
 * A `MainThread` a test drains by hand, standing in for the server's scheduler.
 *
 * Tasks are queued and never run inline, exactly like `runTask`, so a test can
 * assert both that a decision waited for the main thread and that it ran on it.
 */
private class FakeMainThread : MainThread {
    private val queue = ConcurrentLinkedQueue<() -> Unit>()

    /** The thread that drains the queue: whatever last called [drain]. */
    @Volatile
    var drainingThread: Thread? = null
        private set

    val pending: Int get() = queue.size

    override fun execute(task: () -> Unit) {
        queue.add(task)
    }

    /** Runs everything queued so far, as one "tick". Returns how many ran. */
    fun drain(): Int {
        drainingThread = Thread.currentThread()
        var ran = 0
        while (true) {
            val task = queue.poll() ?: return ran
            task()
            ran++
        }
    }

    /** Drains until [expected] tasks have run, failing rather than hanging. */
    fun drainUntil(expected: Int) {
        val deadline = System.nanoTime() + PATIENCE.inWholeNanoseconds
        var ran = 0
        while (ran < expected && System.nanoTime() < deadline) {
            ran += drain()
            if (ran < expected) Thread.sleep(1)
        }
        assertEquals(expected, ran, "expected $expected main-thread task(s)")
    }
}

/**
 * A body that records what was asked of it, and asserts which thread asked.
 *
 * [perceptions] is given the trigger, because a real body embeds the trigger it
 * was handed into the perception it produces - a double that dropped it would let
 * a classification bug pass unnoticed.
 */
private class RecordingBody(
    private val perceptions: (Trigger) -> Perception?,
    private val mainThread: () -> Thread?,
) : BodyPort {
    val applied = mutableListOf<Intent>()

    override fun spawn(
        agentId: AgentId,
        at: BodyLocation,
    ): BodySpawnResult = fail("cognition must not spawn bodies")

    override fun despawn(agentId: AgentId): Boolean = fail("cognition must not despawn bodies")

    override fun apply(
        agentId: AgentId,
        intent: Intent,
    ): IntentOutcome {
        assertEquals(
            mainThread(),
            Thread.currentThread(),
            "a decided intent must reach the body on the main thread and nowhere else",
        )
        applied.add(intent)
        return IntentOutcome.Accepted("applied $intent")
    }

    override fun perceive(
        agentId: AgentId,
        trigger: Trigger,
    ): Perception? = perceptions(trigger)

    override fun tick(tick: Long) = fail("cognition must never drive the tick")

    override fun liveBodies(): Set<AgentId> = fail("cognition does not enumerate bodies")
}

class EmbodiedCognitionTest {
    private val agent = AgentId("agent-alpha")
    private val main = FakeMainThread()

    /** A body that always perceives [perception], whatever the trigger. */
    private fun bodyWith(perception: Perception?) = RecordingBody({ perception }, main::drainingThread)

    /** A body that perceives its trigger, as a real one does. */
    private fun perceivingBody(): RecordingBody {
        val perceive = { trigger: Trigger -> perception(agentId = agent.value, trigger = trigger) }
        return RecordingBody(perceive, main::drainingThread)
    }

    /** The typed failure a handler was given, or a test failure if it was not one. */
    private fun typedFailure(thrown: Throwable): CognitionFailure =
        (thrown as? CognitionUnavailableException)?.failure ?: fail("expected a typed cognition failure, got $thrown")

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
                onApplied = { applied.add(it) },
                onUnavailable = { _, failure -> fail("unexpected failure: $failure") },
            )

        val sent = cognition.consider(agent, Trigger.GoalReview)

        assertEquals(captured.requestId, sent?.requestId)
        // Even with a mind that answered instantly and inline, the body is untouched
        // until the main thread runs: applying a decision from inside whatever
        // triggered it is how re-entrant world mutation gets in.
        assertTrue(body.applied.isEmpty(), "the body must not be touched before the main thread runs")
        assertEquals(1, main.pending)

        main.drain()

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
                transport = { p -> decisionFor(p, Intent.MoveTo(listOf(1L, 2L, 3L))) },
                timeout = PATIENCE,
                workers = 1,
                queueDepth = 1,
            )
        bridge.use {
            EmbodiedCognition(body, bridge, main).submit(agent, captured)
            // Drained from this thread, which is therefore "the main thread" as far
            // as the body is concerned; RecordingBody fails if apply arrives on the
            // bridge worker instead.
            main.drainUntil(1)
        }
        assertEquals(listOf<Intent>(Intent.MoveTo(listOf(1L, 2L, 3L))), body.applied)
    }

    @Test
    @DisplayName("an agent with no body is not sent to the mind at all")
    fun aBodylessAgentDoesNotCostADecision() {
        val cognition =
            EmbodiedCognition(
                bodyWith(null),
                mind = { fail("a bodyless agent must never reach the mind: there is nothing to act with") },
                mainThread = main,
            )

        assertNull(cognition.consider(agent, Trigger.GoalReview))
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
                onApplied = { fail("no decision was produced, so nothing may be applied") },
                onUnavailable = { _, failure -> failures.add(failure) },
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
            onApplied = { fail("there was no decision to apply") },
            onUnavailable = { _, failure -> failures.add(failure) },
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
            )

        cognition.consider(agent, Trigger.Damaged(amount = 3L, source = "zombie"))

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
            val cognition = EmbodiedCognition(body, bridge, main)
            repeat(5) { i -> cognition.submit(agent, perception(requestId = "req-$i", agentId = agent.value)) }
            main.drainUntil(5)
        }
        assertEquals(5, body.applied.size)
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
