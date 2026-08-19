package dev.loom.mind

import dev.loom.body.BodyEventLog
import dev.loom.contract.Decision
import dev.loom.contract.DecisionImportance
import dev.loom.contract.ENERGY_FULL
import dev.loom.contract.Intent
import dev.loom.contract.Perception
import dev.loom.contract.Provenance
import dev.loom.contract.SelfState
import dev.loom.contract.Trigger
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.seconds

/** How long a test waits on a background thread before failing rather than hanging. */
val PATIENCE = 5.seconds

/**
 * A `MainThread` a test drains by hand, standing in for the server's scheduler.
 *
 * Tasks are queued and never run inline, exactly like `runTask`, so a test can
 * assert both that a decision waited for the main thread and that it ran on it.
 */
class FakeMainThread : MainThread {
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
 * A journal whose records go nowhere, for the tests that are not about the record.
 *
 * Spelled out at every call site rather than defaulted in the class: a decision that
 * reaches a body without reaching the record is the bug this whole seam exists to
 * prevent, so forgetting the journal must not compile.
 */
fun silentJournal(): DecisionJournal = DecisionJournal(BodyEventLog { }, currentTick = { 0L })

/**
 * A perception with everything a test does not care about already filled in.
 *
 * [energy] defaults to a full reserve so a test that is not about the survival drive
 * is never accidentally about it.
 */
fun perception(
    requestId: String = "req-1",
    agentId: String = "agent-alpha",
    trigger: Trigger = Trigger.GoalReview,
    importance: DecisionImportance = DecisionImportance.ROUTINE,
    energy: Double = ENERGY_FULL,
): Perception =
    Perception(
        requestId = requestId,
        agentId = agentId,
        trigger = trigger,
        importance = importance,
        capturedTick = 100L,
        selfState =
            SelfState(
                health = 20.0,
                energy = energy,
                position = listOf(1.0, 64.0, 2.0),
                inventorySummary = emptyList(),
            ),
        surroundings = emptyList(),
    )

/** The decision a cooperative fake mind answers [perception] with. */
fun decisionFor(
    perception: Perception,
    intent: Intent = Intent.Idle,
): Decision =
    Decision(
        requestId = perception.requestId,
        agentId = perception.agentId,
        intent = intent,
        reasoning = "test",
        provenance =
            Provenance(
                tier = perception.importance,
                model = "test-model",
                promptVersion = "test-v1",
            ),
    )
