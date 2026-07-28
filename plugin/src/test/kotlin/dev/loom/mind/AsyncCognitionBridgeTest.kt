/*
 * The bridge's promise, stated as tests: cognition never costs the caller.
 *
 * Each test here pins one of the three properties the bridge exists for -
 * `submit` does not wait, a hung mind is bounded by a deadline, and a saturated
 * bridge refuses rather than queues. They are written against a fake transport a
 * test can stall on purpose, because those are precisely the conditions a real
 * mind will not reproduce on demand.
 */
package dev.loom.mind

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** How long a test waits for something that should happen promptly. */
private val PATIENCE = 5.seconds

/** A deadline short enough that a test can wait out a timeout. */
private val SHORT_DEADLINE = 150.milliseconds

/** How long a duplicate report gets to show up before its absence counts. */
private val REPORT_SETTLE = 100.milliseconds

/** How often to re-read a count that is expected to settle on its own. */
private val SETTLE_POLL = 10.milliseconds

class AsyncCognitionBridgeTest {
    /** The failure the bridge reported, or `null` if it reported none. */
    private fun failureOf(thrown: ExecutionException): CognitionFailure? {
        val unavailable = thrown.cause as? CognitionUnavailableException
        return unavailable?.failure
    }

    /** Waits for [future], failing the test rather than hanging it. */
    private fun <T> await(future: CompletableFuture<T>): T {
        val patience = PATIENCE.inWholeMilliseconds
        return future.get(patience, TimeUnit.MILLISECONDS)
    }

    @Test
    @DisplayName("submit returns before the mind has answered, and the answer arrives later")
    fun submitDoesNotWaitForTheMind() {
        val answering = CountDownLatch(1)
        val bridge =
            AsyncCognitionBridge(
                transport = { p ->
                    // Nothing is answered until the test says so, so a submit that
                    // returned only after cognition could not possibly return here.
                    assertTrue(answering.await(PATIENCE.inWholeMilliseconds, TimeUnit.MILLISECONDS))
                    decisionFor(p)
                },
                timeout = PATIENCE,
                workers = 1,
                queueDepth = 1,
            )
        bridge.use {
            val sent = perception()
            val decided = bridge.submit(sent)
            assertTrue(!decided.isDone, "submit must return before the mind has answered")

            answering.countDown()
            assertEquals(sent.requestId, await(decided).requestId)
        }
    }

    @Test
    @DisplayName("a mind that never answers fails the request at the deadline")
    fun aHungMindIsBoundedByTheDeadline() {
        val released = CountDownLatch(1)
        val bridge =
            AsyncCognitionBridge(
                transport = { p ->
                    released.await()
                    decisionFor(p)
                },
                timeout = SHORT_DEADLINE,
                workers = 1,
                queueDepth = 1,
            )
        try {
            val thrown = assertThrows<ExecutionException> { await(bridge.submit(perception())) }
            assertEquals(CognitionFailure.TimedOut(SHORT_DEADLINE), failureOf(thrown))
        } finally {
            // Freed after the assertion: the point of the test is that the caller
            // did not have to wait for this.
            released.countDown()
            bridge.close()
        }
    }

    @Test
    @DisplayName("the deadline covers queue time, not just the attempt")
    fun theDeadlineCoversTimeSpentWaitingForAWorker() {
        val released = CountDownLatch(1)
        val attempts = AtomicInteger()
        val bridge =
            AsyncCognitionBridge(
                transport = { p ->
                    attempts.incrementAndGet()
                    released.await()
                    decisionFor(p)
                },
                timeout = SHORT_DEADLINE,
                workers = 1,
                queueDepth = 1,
            )
        try {
            val stuck = bridge.submit(perception(requestId = "req-stuck"))
            val queued = bridge.submit(perception(requestId = "req-queued"))

            // The queued request never reaches the transport: its deadline expires
            // while the single worker is still busy with the first one. A timer
            // started per attempt would have let it wait indefinitely.
            assertEquals(
                CognitionFailure.TimedOut(SHORT_DEADLINE),
                failureOf(assertThrows<ExecutionException> { await(queued) }),
            )
            assertEquals(1, attempts.get(), "the queued request must not have been attempted")
            assertTrue(stuck.isCompletedExceptionally, "the in-flight request is on the same clock")
        } finally {
            released.countDown()
            bridge.close()
        }
    }

    @Test
    @DisplayName("a full bridge refuses immediately instead of growing its queue")
    fun saturationIsRefusedNotQueued() {
        val released = CountDownLatch(1)
        val started = CountDownLatch(1)
        val bridge =
            AsyncCognitionBridge(
                transport = { p ->
                    started.countDown()
                    released.await()
                    decisionFor(p)
                },
                timeout = PATIENCE,
                workers = 1,
                queueDepth = 1,
            )
        try {
            bridge.submit(perception(requestId = "req-in-flight"))
            assertTrue(started.await(PATIENCE.inWholeMilliseconds, TimeUnit.MILLISECONDS))
            bridge.submit(perception(requestId = "req-queued"))

            // Worker busy, queue slot taken: the third request has nowhere to go and
            // is refused now rather than after the deadline it would otherwise wait
            // out. Saturation must degrade decisions, not grow memory.
            val refused = bridge.submit(perception(requestId = "req-refused"))
            assertTrue(refused.isCompletedExceptionally, "a full bridge must refuse without waiting")
            assertEquals(
                CognitionFailure.Saturated,
                failureOf(assertThrows<ExecutionException> { await(refused) }),
            )
        } finally {
            released.countDown()
            bridge.close()
        }
    }

    @Test
    @DisplayName("a transport that throws fails one request and the bridge keeps serving")
    fun aBrokenMindDegradesOneAgent() {
        val broken = AtomicInteger()
        val bridge =
            AsyncCognitionBridge(
                transport = { p ->
                    if (broken.incrementAndGet() == 1) error("the mind fell over") else decisionFor(p)
                },
                timeout = PATIENCE,
                workers = 1,
                queueDepth = 4,
            )
        bridge.use {
            val thrown = assertThrows<ExecutionException> { await(bridge.submit(perception())) }
            val failure = assertInstanceOf(CognitionFailure.Failed::class.java, failureOf(thrown))
            assertTrue(failure.reason.contains("the mind fell over"), "the cause must survive: ${failure.reason}")

            // The bridge is not poisoned by one failure.
            assertEquals("req-2", await(bridge.submit(perception(requestId = "req-2"))).requestId)
        }
    }

    @Test
    @DisplayName("a decision that arrives after the deadline is discarded, not applied late")
    fun aLateDecisionIsDiscarded() {
        val released = CountDownLatch(1)
        val reported = CompletableFuture<CognitionFailure>()
        val bridge =
            AsyncCognitionBridge(
                transport = { p ->
                    released.await()
                    decisionFor(p)
                },
                timeout = SHORT_DEADLINE,
                workers = 1,
                queueDepth = 1,
                onFailure = { _, failure -> reported.complete(failure) },
            )
        bridge.use {
            val decided = bridge.submit(perception())
            assertThrows<ExecutionException> { await(decided) }

            // The mind now answers, far too late. The future stays failed: acting on
            // a stale perception is worse than not acting.
            released.countDown()
            assertEquals(CognitionFailure.TimedOut(SHORT_DEADLINE), await(reported))
            assertTrue(decided.isCompletedExceptionally, "the late decision must not replace the failure")
        }
    }

    @Test
    @DisplayName("every failure is reported to onFailure exactly once")
    fun failuresAreReportedOnce() {
        val reports = mutableListOf<CognitionFailure>()
        // Counted down by the report itself, because the future is completed *before*
        // onFailure is called. Sampling the list as soon as the caller is freed would
        // race the worker still filing the report, which is a flake, not a test.
        val reported = CountDownLatch(1)
        val bridge =
            AsyncCognitionBridge(
                transport = { error("no") },
                timeout = PATIENCE,
                workers = 1,
                queueDepth = 1,
                onFailure = { _, failure ->
                    synchronized(reports) { reports.add(failure) }
                    reported.countDown()
                },
            )
        bridge.use {
            assertThrows<ExecutionException> { await(bridge.submit(perception())) }
            assertTrue(reported.await(PATIENCE.inWholeMilliseconds, TimeUnit.MILLISECONDS), "nothing was reported")
            // Waited out rather than sampled immediately: the property is "exactly
            // once", so a duplicate report has to be given time to arrive before its
            // absence means anything.
            Thread.sleep(REPORT_SETTLE.inWholeMilliseconds)
            assertEquals(1, synchronized(reports) { reports.size }, "reported: $reports")
        }
    }

    @Test
    @DisplayName("closing the bridge fails what was still outstanding rather than abandoning it")
    fun closingAnswersEveryOutstandingRequest() {
        val released = CountDownLatch(1)
        val bridge =
            AsyncCognitionBridge(
                transport = { p ->
                    released.await()
                    decisionFor(p)
                },
                // Long enough that the watchdog cannot be what completes these: the
                // point is that close() itself answers them.
                timeout = PATIENCE,
                workers = 1,
                queueDepth = 2,
            )
        try {
            val inFlight = bridge.submit(perception(requestId = "req-in-flight"))
            val queued = bridge.submit(perception(requestId = "req-queued"))

            // shutdownNow discards the queued work *and* the watchdog tasks that would
            // have timed it out, so without close() answering them a caller on a plain
            // get() would wait forever on a bridge that no longer exists.
            bridge.close()

            for (decided in listOf(inFlight, queued)) {
                val failure = failureOf(assertThrows<ExecutionException> { await(decided) })
                assertInstanceOf(CognitionFailure.Failed::class.java, failure)
            }
        } finally {
            released.countDown()
        }
    }

    @Test
    @DisplayName("a bridge that served its requests does not hold on to them")
    fun completedRequestsAreNotRetained() {
        val bridge =
            AsyncCognitionBridge(
                transport = { p -> decisionFor(p) },
                timeout = PATIENCE,
                workers = 2,
                queueDepth = 8,
            )
        // Tracking outstanding requests must not become a leak: a server submits these
        // continuously for as long as it runs, so an answered request that was never
        // deregistered would grow this list for the lifetime of the process.
        bridge.use {
            repeat(50) { i -> await(bridge.submit(perception(requestId = "req-$i"))) }
            // Polled rather than asserted outright: a future may unblock its waiter
            // before it runs the callback that deregisters the request, so the last
            // one can legitimately still be listed for a moment. A leak does not
            // drain, so waiting cannot make a real one pass.
            val drained = drainsToNothing(bridge)
            assertEquals(0, drained, "answered requests are still being held")
        }
    }

    /** [bridge]'s outstanding count once it settles, or the last count seen. */
    private fun drainsToNothing(bridge: AsyncCognitionBridge): Int {
        val deadline = System.nanoTime() + PATIENCE.inWholeNanoseconds
        var outstanding = bridge.outstanding
        while (outstanding != 0 && System.nanoTime() < deadline) {
            Thread.sleep(SETTLE_POLL.inWholeMilliseconds)
            outstanding = bridge.outstanding
        }
        return outstanding
    }

    @Test
    @DisplayName("the bridge refuses to be built with a shape that cannot bound anything")
    fun degenerateConfigurationsAreRefused() {
        val sane = CognitionTransport { p -> decisionFor(p) }
        for (bad in listOf(0, -1)) {
            assertThrows<IllegalArgumentException> { AsyncCognitionBridge(sane, PATIENCE, bad, 1) }
            assertThrows<IllegalArgumentException> { AsyncCognitionBridge(sane, PATIENCE, 1, bad) }
        }
        assertThrows<IllegalArgumentException> { AsyncCognitionBridge(sane, Duration.ZERO, 1, 1) }
    }

    @Test
    @DisplayName("the perception reaches the transport unchanged")
    fun thePerceptionIsNotRewrittenOnTheWayOut() {
        val seen = CompletableFuture<Any>()
        val bridge =
            AsyncCognitionBridge(
                transport = { p ->
                    seen.complete(p)
                    decisionFor(p)
                },
                timeout = PATIENCE,
                workers = 1,
                queueDepth = 1,
            )
        bridge.use {
            val sent = perception(requestId = "req-verbatim")
            await(bridge.submit(sent))
            assertSame(sent, await(seen), "the bridge must not reshape what the body perceived")
        }
    }
}
