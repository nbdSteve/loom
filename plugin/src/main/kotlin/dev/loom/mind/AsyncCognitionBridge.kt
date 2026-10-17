/*
 * The async bridge: the only place in Loom where a thread waits for a mind.
 *
 * Three properties make it safe to call from the server main thread, and all
 * three are the point of the class rather than implementation detail:
 *
 *  1. `submit` never runs cognition. It hands the perception to a background
 *     pool and returns, so the main thread's cost is an enqueue.
 *  2. Every request has a deadline. A watchdog fails the future when the mind
 *     overruns it, so a caller is freed even from a mind that never answers.
 *  3. Back pressure is explicit. The pool and its queue are bounded; a request
 *     that does not fit fails immediately rather than growing a queue the
 *     server has no way to shed. A saturated mind must degrade decisions, not
 *     memory.
 *
 * The watchdog frees the *caller*, not the worker: a thread blocked in a socket
 * read stays blocked until its own read timeout fires. That is why
 * `CognitionTransport` is required to bound its own waiting - the two limits
 * work together, and neither alone is enough.
 */
package dev.loom.mind

import dev.loom.contract.Decision
import dev.loom.contract.Perception
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/** Thread-name prefix for the cognition workers, so a thread dump names them. */
private const val WORKER_THREAD_PREFIX = "loom-cognition-"

/** Thread name for the single watchdog thread. */
private const val WATCHDOG_THREAD_NAME = "loom-cognition-watchdog"

/** How long a worker pool thread may sit idle before it is reclaimed. */
private const val WORKER_KEEPALIVE_SECONDS = 60L

/** Why a request outstanding at [AsyncCognitionBridge.close] failed. */
private const val CLOSED_REASON = "the cognition bridge closed before the mind answered"

/**
 * Why a cognition request failed.
 *
 * A typed cause rather than a bare message, because these three failures call
 * for different responses from an operator: a timeout suggests a slow mind,
 * saturation an undersized bridge or an overloaded one, and a transport failure
 * a mind that is broken or unreachable.
 */
sealed interface CognitionFailure {
    /**
     * The short stable name the append-only record counts this failure by.
     *
     * Declared on the interface rather than derived by a `when` somewhere else, so a
     * fourth kind of failure cannot be added without naming itself - an unnamed one
     * would land in the log as a value no analysis knows to look for. It is also the
     * *only* part of a failure any operator display may show: the reason text belongs
     * to the log, where its length and its contents are nobody's problem.
     */
    val tag: String

    /** The mind did not answer within the bridge's deadline. */
    data class TimedOut(
        val after: Duration,
    ) : CognitionFailure {
        override val tag: String get() = "timedOut"
    }

    /** Every worker and queue slot was busy, so the request was not attempted. */
    data object Saturated : CognitionFailure {
        override val tag: String get() = "saturated"
    }

    /** This agent already owns every model-call slot budgeted to it. */
    data class AgentBudgetExhausted(
        val limit: Int,
    ) : CognitionFailure {
        override val tag: String get() = "agentBudgetExhausted"
    }

    /**
     * The research harness refused the request at one of its call or spend ceilings, before any
     * mind was asked (r20: the 151st call was recorded as `failed`, and only the harness's own
     * record named the ceiling).
     */
    data object BudgetRefused : CognitionFailure {
        override val tag: String get() = "budgetRefused"
    }

    /** The mind was reached but failed, refused, or answered unusably. */
    data class Failed(
        val reason: String,
    ) : CognitionFailure {
        override val tag: String get() = "failed"
    }
}

/**
 * The typed failure behind a thrown cognition error, when there is one.
 *
 * A `CompletableFuture` hands a caller a `Throwable`, and only this side knows which
 * throwables carry a classification. Anything else - a bug on a worker thread, say -
 * has no tag of its own and is reported as [CognitionFailure.Failed] would be, since
 * from the agent's point of view the mind failed either way.
 */
fun failureOf(throwable: Throwable): CognitionFailure =
    (throwable as? CognitionUnavailableException)?.failure
        ?: CognitionFailure.Failed(throwable.toString())

/**
 * Thrown into a failed cognition future. Carries the typed [failure].
 *
 * Named for what it is - the mind did not answer - so a caller reading a stack
 * trace is not left guessing which side broke.
 */
class CognitionUnavailableException(
    val failure: CognitionFailure,
    message: String,
) : RuntimeException(message)

/**
 * Runs cognition off the main thread, with a deadline and bounded back pressure.
 *
 * @param transport what actually asks the mind. Blocking by design; it always
 *   runs on a worker thread here.
 * @param timeout how long a request may take before the caller's future is
 *   failed.
 * @param workers how many requests may be in flight at once.
 * @param queueDepth how many may wait for a worker before further requests are
 *   refused outright.
 * @param onFailure called with every failure, for logging. Called on a
 *   background thread, so it must not touch world state.
 */
class AsyncCognitionBridge(
    private val transport: CognitionTransport,
    private val timeout: Duration,
    workers: Int,
    queueDepth: Int,
    private val onFailure: (Perception, CognitionFailure) -> Unit = { _, _ -> },
) : CognitionBridge, AutoCloseable {
    init {
        require(workers > 0) { "workers must be positive, was $workers" }
        require(queueDepth > 0) { "queueDepth must be positive, was $queueDepth" }
        require(timeout.isPositive()) { "timeout must be positive, was $timeout" }
    }

    private val threadCount = AtomicLong()

    /** One outstanding request, kept so [close] can answer it. */
    private class Pending(
        val perception: Perception,
        val decided: CompletableFuture<Decision>,
    )

    // Every request that has neither completed nor failed yet. Concurrent because
    // workers, the watchdog, and close() all remove from it.
    private val pendingRequests = ConcurrentLinkedQueue<Pending>()

    /**
     * How many requests are outstanding right now.
     *
     * Exists for the test that pins the tracking [submit] adds against a leak: a
     * server submits continuously for as long as it runs, so a request that was
     * answered but never deregistered would grow this without bound. Nothing in
     * production reads it.
     */
    internal val outstanding: Int get() = pendingRequests.size

    // Fixed size with a bounded queue: the pool cannot grow past `workers`, and
    // work cannot pile up past `queueDepth`. Both are daemon threads, so a mind
    // that never answers can never keep the JVM from shutting down.
    private val pool =
        ThreadPoolExecutor(
            workers,
            workers,
            WORKER_KEEPALIVE_SECONDS,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(queueDepth),
        ) { runnable -> daemon(runnable, WORKER_THREAD_PREFIX + threadCount.incrementAndGet()) }

    // removeOnCancelPolicy because the common case is a cancelled deadline: a
    // request answered in milliseconds would otherwise leave its timer in the delay
    // queue for the rest of the timeout, so the queue would hold roughly (submit
    // rate x timeout) dead entries on the one watchdog thread.
    private val watchdog: ScheduledExecutorService =
        ScheduledThreadPoolExecutor(1) { runnable -> daemon(runnable, WATCHDOG_THREAD_NAME) }
            .apply { removeOnCancelPolicy = true }

    override fun submit(perception: Perception): CompletableFuture<Decision> {
        val decided = CompletableFuture<Decision>()
        // Tracked so close() can fail whatever is still outstanding. Without this a
        // request sitting in the queue at shutdown would be discarded along with its
        // watchdog task, and its future would never complete at all - a caller using
        // get() with no timeout would wait forever on a bridge that no longer exists.
        val pending = Pending(perception, decided)
        pendingRequests.add(pending)
        decided.whenComplete { _, _ -> pendingRequests.remove(pending) }

        // Armed before the work is queued: a request that waits in the queue
        // behind a stalled worker is on the clock too, which is the case a
        // per-attempt timer would miss entirely.
        val deadline =
            watchdog.schedule(
                { fail(decided, perception, CognitionFailure.TimedOut(timeout)) },
                timeout.inWholeMilliseconds,
                TimeUnit.MILLISECONDS,
            )
        decided.whenComplete { _, _ -> deadline.cancel(false) }

        try {
            pool.execute { run(perception, decided) }
        } catch (refused: RejectedExecutionException) {
            // Bounded queue full, or the bridge is closing. Either way this
            // request is not happening, and saying so now beats waiting out the
            // deadline to learn it.
            fail(decided, perception, CognitionFailure.Saturated, refused)
        }
        return decided
    }

    /**
     * Asks the mind on a worker thread and completes [decided].
     *
     * Catches broadly on purpose: this is the error boundary between one agent's
     * cognition and the rest of the server. A transport may throw anything at
     * all, and whatever it throws must become one failed decision rather than an
     * uncaught exception on a pool thread.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun run(
        perception: Perception,
        decided: CompletableFuture<Decision>,
    ) {
        // The deadline may already have fired while this sat in the queue; if so,
        // there is nobody left to answer.
        if (decided.isDone) {
            return
        }
        try {
            val decision = transport.ask(perception)
            if (!decided.complete(decision)) {
                // The watchdog won the race. The decision is discarded rather than
                // applied late: the body has moved on, and acting on a stale
                // perception is worse than not acting.
                onFailure(perception, CognitionFailure.TimedOut(timeout))
            }
        } catch (failure: Throwable) {
            fail(decided, perception, failureOf(failure), failure)
        }
    }

    /** Fails [decided] with [failure], reporting it exactly once. */
    private fun fail(
        decided: CompletableFuture<Decision>,
        perception: Perception,
        failure: CognitionFailure,
        cause: Throwable? = null,
    ) {
        val thrown = CognitionUnavailableException(failure, describe(perception, failure))
        cause?.let(thrown::initCause)
        if (decided.completeExceptionally(thrown)) {
            onFailure(perception, failure)
        }
    }

    private fun describe(
        perception: Perception,
        failure: CognitionFailure,
    ): String =
        "cognition for agent ${perception.agentId} (request ${perception.requestId}) " +
            when (failure) {
                is CognitionFailure.TimedOut -> "did not answer within ${failure.after}"
                CognitionFailure.Saturated -> "was refused: every worker and queue slot is busy"
                is CognitionFailure.AgentBudgetExhausted ->
                    "was refused: this agent already has ${failure.limit} request(s) outstanding"
                CognitionFailure.BudgetRefused -> "was refused at the research harness's call or spend ceiling"
                is CognitionFailure.Failed -> "failed: ${failure.reason}"
            }

    /**
     * Stops accepting requests and releases the threads.
     *
     * Requests already in flight are abandoned rather than awaited: this runs on
     * plugin disable, and a mind that is hanging is exactly the case where
     * waiting would hold the server shutdown open.
     *
     * Abandoned, but not left hanging. `shutdownNow` discards the queued work and
     * the watchdog tasks that would have timed it out, so every outstanding request
     * is failed here instead - `submit` promises a future that completes one way or
     * another, and a bridge that stops existing is a failure like any other.
     */
    override fun close() {
        pool.shutdownNow()
        watchdog.shutdownNow()
        while (true) {
            val pending = pendingRequests.poll() ?: return
            fail(pending.decided, pending.perception, CognitionFailure.Failed(CLOSED_REASON))
        }
    }

    private fun daemon(
        runnable: Runnable,
        name: String,
    ): Thread =
        Thread(runnable, name).apply {
            isDaemon = true
        }
}
