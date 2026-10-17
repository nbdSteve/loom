package dev.loom.mind

import dev.loom.body.BodyEventLog
import dev.loom.contract.Bearing
import dev.loom.contract.BlockYield
import dev.loom.contract.DayPhase
import dev.loom.contract.Decision
import dev.loom.contract.DecisionImportance
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalStepResult
import dev.loom.contract.Intent
import dev.loom.contract.Observation
import dev.loom.contract.ObservationKind
import dev.loom.contract.Perception
import dev.loom.contract.Provenance
import dev.loom.contract.SelfState
import dev.loom.contract.Trigger
import dev.loom.contract.WorldState
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
 * [food] defaults to a full food bar so a test that is not about survival
 * is never accidentally about it.
 */
fun perception(
    requestId: String = "req-1",
    agentId: String = "agent-alpha",
    trigger: Trigger = Trigger.GoalReview,
    importance: DecisionImportance = DecisionImportance.ROUTINE,
    food: Int = 20,
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
                food = food,
                saturation = 0.0,
                position = listOf(1.0, 64.0, 2.0),
                inventorySummary = emptyList(),
                craftingOptions = emptyList(),
                craftingOptionsOmitted = 0,
                ownDrops = emptyList(),
                world = WorldState(dayPhase = DayPhase.DAY, isDay = true, ticksUntilNightfall = 6_000),
            ),
        surroundings = emptyList(),
        surroundingsOmitted = 0,
    )

/** [perception] with a dead bush in sight that yields sticks, which makes a stick goal obtainable. */
fun perceptionNearBush(
    requestId: String = "req-1",
    agentId: String = "agent-alpha",
    trigger: Trigger = Trigger.GoalReview,
): Perception =
    perception(
        requestId = requestId,
        agentId = agentId,
        trigger = trigger,
    ).copy(surroundings = listOf(STICK_BUSH))

/**
 * A dead bush in sight that digging with a bare hand yields sticks from: what makes the sticks
 * the goal helpers name obtainable, as the r11 and r13 scenes' bushes did.
 */
val STICK_BUSH: Observation =
    Observation(
        kind = ObservationKind.BLOCK,
        what = "block.minecraft.dead_bush",
        distance = 2.0,
        direction = Bearing.NORTH,
        verticalOffset = 0.0,
        visible = true,
        ref = "bush",
        standable = true,
        yields = listOf(BlockYield("item.minecraft.stick", 0, 2)),
        digSafe = true,
    )

/**
 * The sighting trigger a body would put on a perception that found [seen].
 *
 * Derived from the observation rather than given its own coordinates, because the whole
 * point of a sighting is that the trigger and the observation describe one creature at one
 * moment: a helper that let the two drift would let a test pass on a scene no body could
 * produce. [reachable] defaults to `false` on purpose - it is the value that must never on
 * its own make a threat cheaper, so a test that does not mention it is testing the case
 * that matters.
 */
fun sightingOf(
    seen: Observation,
    reachable: Boolean = false,
): Trigger.ThreatSighted =
    Trigger.ThreatSighted(
        threat = seen.what,
        ref = seen.ref ?: error("a threat is named by its handle, so the observation must carry one"),
        distance = seen.distance,
        direction = seen.direction,
        visible = seen.visible,
        reachable = reachable,
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

/**
 * A goal choice carrying every field [disposition] requires: its typed success target to set a
 * goal, the step an affirming decision takes, and a verdict on the previous step for every
 * disposition that answers a carried goal. The step's type is the decision's own intent, and the
 * goal's wording its success target, which the ledger reads for itself.
 */
fun goalChoice(
    disposition: GoalDisposition,
    stepResult: GoalStepResult = GoalStepResult.PROGRESSING,
    success: Pair<String, Long> = "item.minecraft.stick" to 1L,
): GoalChoice {
    val worded = disposition == GoalDisposition.ADOPT || disposition == GoalDisposition.REVISE
    val stepped = worded || disposition == GoalDisposition.CONTINUE
    return GoalChoice(
        disposition = disposition,
        successItem = if (worded) success.first else null,
        successCount = if (worded) success.second else null,
        step = if (stepped) "the next concrete step" else null,
        stepResult = if (disposition == GoalDisposition.ADOPT) null else stepResult,
    )
}
