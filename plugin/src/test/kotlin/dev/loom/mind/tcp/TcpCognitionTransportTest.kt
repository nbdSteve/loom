/*
 * The wire, tested against a real socket.
 *
 * The server here is a fake, but nothing else is: a real `ServerSocket`, real line
 * framing, and - the point of the test - the *canonical fixture bytes* as the
 * response. The Rust conformance test pins those same fixtures byte-for-byte, so a
 * transport that decodes them is a transport that can decode the real service, and
 * the two runtimes meet in the middle at the fixture rather than at a mock.
 */
package dev.loom.mind.tcp

import dev.loom.contract.Decision
import dev.loom.contract.DecisionImportance
import dev.loom.contract.Intent
import dev.loom.contract.LoomJson
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import dev.loom.mind.AsyncCognitionBridge
import dev.loom.mind.decisionFor
import dev.loom.mind.perception
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

private val PATIENCE = 5.seconds

/** How often to re-check something another thread is expected to record shortly. */
private const val POLL_MILLIS = 5L

/**
 * A port with nothing listening on it.
 *
 * Bound and released rather than picked at random, so the number is one the OS was
 * willing to hand out and the test is not racing whatever happens to run locally.
 */
private fun unusedPort(): Int = ServerSocket(0).use { it.localPort }

/**
 * A line-oriented stand-in for the cognition service.
 *
 * [answer] is given each request line and returns the response line, or `null` to
 * close the connection without answering.
 */
private class FakeMind(
    private val answer: (String) -> String?,
) : AutoCloseable {
    private val server = ServerSocket(0)
    private val threads = CopyOnWriteArrayList<Thread>()
    val requests = CopyOnWriteArrayList<String>()

    /** Connections the transport dropped while this fake was mid-answer. */
    val hangUps = CopyOnWriteArrayList<String>()

    val port: Int get() = server.localPort

    /**
     * How many requests have arrived, once at least [expected] have or [within] elapses.
     *
     * A response served from bytes the transport had already read does not wait on this
     * fake, so a test can reach its assertion before the serving thread has recorded the
     * request. Returns the count either way, so a genuine shortfall still fails with the
     * number it saw rather than as a timeout.
     */
    fun requestsWithin(
        within: Duration,
        expected: Int = 2,
    ): Int {
        val giveUpAt = TimeSource.Monotonic.markNow() + within
        while (requests.size < expected && giveUpAt.hasNotPassedNow()) {
            Thread.sleep(POLL_MILLIS)
        }
        return requests.size
    }

    init {
        accept()
    }

    private fun accept() {
        val acceptor =
            Thread {
                while (!server.isClosed) {
                    val client =
                        try {
                            server.accept()
                        } catch (closed: IOException) {
                            check(server.isClosed) { "accept failed while open: $closed" }
                            return@Thread
                        }
                    serve(client)
                }
            }
        acceptor.isDaemon = true
        threads.add(acceptor)
        acceptor.start()
    }

    private fun serve(client: Socket) {
        val worker =
            Thread {
                client.use {
                    val reader = client.getInputStream().bufferedReader()
                    val writer = client.getOutputStream().bufferedWriter()
                    while (true) {
                        val line = reader.readLine() ?: return@use
                        requests.add(line)
                        val response = answer(line) ?: return@use
                        try {
                            writer.write(response)
                            writer.write("\n")
                            writer.flush()
                        } catch (gone: IOException) {
                            // The transport hung up mid-answer, which for the tests about
                            // abandoning a channel is the behavior under test rather than a
                            // fault. Recorded rather than thrown, so a test that did not
                            // expect it can still say what happened.
                            hangUps.add("$gone")
                            return@use
                        }
                    }
                }
            }
        worker.isDaemon = true
        threads.add(worker)
        worker.start()
    }

    override fun close() {
        server.close()
        threads.forEach { it.join(PATIENCE.inWholeMilliseconds) }
    }
}

/**
 * A mind that takes a perception and then drips bytes forever, never a newline.
 *
 * The shape of the fault a socket timeout cannot catch: every byte restarts a
 * per-read clock, so a `readLine` waits as long as the peer keeps typing. Written
 * as its own fake rather than as a [FakeMind] answer because the point is what it
 * does *instead of* producing a line.
 */
private class DrippingMind(
    private val interval: Duration,
) : AutoCloseable {
    private val server = ServerSocket(0)
    private val threads = CopyOnWriteArrayList<Thread>()

    /** Bytes the fake actually managed to send, so the test can prove it dripped. */
    @Volatile
    var dripped = 0
        private set

    @Volatile
    private var stopped = false

    val port: Int get() = server.localPort

    init {
        val acceptor =
            Thread {
                val client =
                    try {
                        server.accept()
                    } catch (closed: IOException) {
                        check(server.isClosed) { "accept failed while open: $closed" }
                        return@Thread
                    }
                drip(client)
            }
        acceptor.isDaemon = true
        threads.add(acceptor)
        acceptor.start()
    }

    private fun drip(client: Socket) {
        val worker =
            Thread {
                client.use {
                    // Take the perception first: the drip has to happen where the
                    // transport is committed and waiting, past the at-most-once seam.
                    client.getInputStream().bufferedReader().readLine() ?: return@use
                    val out = client.getOutputStream()
                    // Runs until the test stops it or the transport hangs up, which is
                    // what makes the abandon the client's own doing and not the fake's.
                    while (!stopped) {
                        try {
                            out.write('x'.code)
                            out.flush()
                        } catch (expected: IOException) {
                            // The transport abandoned the channel: the thing under test.
                            return@use
                        }
                        dripped++
                        Thread.sleep(interval.inWholeMilliseconds)
                    }
                }
            }
        worker.isDaemon = true
        threads.add(worker)
        worker.start()
    }

    override fun close() {
        stopped = true
        server.close()
        threads.forEach { it.join(PATIENCE.inWholeMilliseconds) }
    }
}

class TcpCognitionTransportTest {
    private val contractDir = Paths.get(System.getProperty("user.dir"), "..", "contract").toRealPath()

    private var closeables = mutableListOf<AutoCloseable>()

    @AfterEach
    fun tearDown() {
        closeables.asReversed().forEach { it.close() }
        closeables.clear()
    }

    private fun <T : AutoCloseable> managed(closeable: T): T = closeable.also { closeables.add(it) }

    /** A transport pointed at [mind], with timeouts short enough for a test. */
    private fun transportTo(mind: FakeMind): TcpCognitionTransport =
        managed(
            TcpCognitionTransport(
                host = "127.0.0.1",
                port = mind.port,
                connectTimeout = PATIENCE,
                readTimeout = 500.milliseconds,
            ),
        )

    /** The canonical bytes of a shared fixture, exactly as both runtimes emit them. */
    private fun fixture(name: String): String = contractDir.resolve("fixtures/valid/$name").readText()

    /**
     * A perception asking for exactly the decision in [fixture].
     *
     * The transport correlates a response with its request by id, so a test that
     * answers with a canonical fixture has to ask for that fixture's request. Taken
     * from the fixture itself rather than restated, so a renamed fixture id cannot
     * leave a test asserting against a request nobody made.
     */
    private fun asking(fixture: String): Perception {
        val answered = LoomJson.decodeFromString<Decision>(fixture)
        return perception(requestId = answered.requestId)
    }

    @Test
    @DisplayName("a perception goes out as contract JSON and a canonical decision fixture comes back decoded")
    fun oneRoundTripOverARealSocket() {
        val canonical = fixture("decision-say.json")
        val mind = managed(FakeMind { canonical })
        val sent = perception(requestId = "req-spoken-1", agentId = "agent-alpha")

        val decision = transportTo(mind).ask(sent)

        // What went out is the contract encoding of the perception, one line, nothing
        // added: the mind's whole view of the world.
        assertEquals(LoomJson.encodeToString(sent), mind.requests.single())
        assertEquals(Intent.Say("Hello, player-steve."), decision.intent)
        assertEquals(DecisionImportance.NOTABLE, decision.provenance.tier)
        assertEquals("req-spoken-1", decision.requestId)
    }

    @Test
    @DisplayName("every valid decision fixture decodes off the wire")
    fun everyDecisionShapeDecodes() {
        // The four intent shapes the contract allows, taken from the shared fixtures
        // rather than restated here, so a contract change fails this test too.
        val fixtures =
            listOf("decision-say.json", "decision-move-to.json", "decision-flee.json", "decision-idle.json")
        for (name in fixtures) {
            val canonical = fixture(name)
            val mind = managed(FakeMind { canonical })
            val decision = transportTo(mind).ask(asking(canonical))
            assertEquals(
                LoomJson.decodeFromString<Decision>(canonical),
                decision,
                "fixture $name",
            )
        }
    }

    @Test
    @DisplayName("a refusal frame surfaces the mind's own message, not a parse error")
    fun aRefusalIsReportedAsARefusal() {
        val mind = managed(FakeMind { """{"error":"perception was not valid JSON"}""" })

        val thrown = assertThrows<IOException> { transportTo(mind).ask(perception()) }

        assertTrue(
            thrown.message?.contains("perception was not valid JSON") == true,
            "the mind's own words must survive: ${thrown.message}",
        )
    }

    @Test
    @DisplayName("a mind that never answers frees the worker at the read timeout")
    fun aSilentMindIsBoundedByTheReadTimeout() {
        val held = CountDownLatch(1)
        val mind =
            managed(
                FakeMind {
                    // Answers only after the test is done waiting; the transport must
                    // not be waiting by then.
                    held.await(PATIENCE.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                    null
                },
            )
        try {
            // InterruptedIOException because a socket read timeout is one; what matters
            // is that the perception was sent once. Past the write the mind may already
            // be spending a model call on it, so waiting again is not an option.
            assertThrows<InterruptedIOException> { transportTo(mind).ask(perception()) }
            assertEquals(1, mind.requests.size, "a read timeout must not be retried")
        } finally {
            held.countDown()
        }
    }

    @Test
    @DisplayName("a mind dripping bytes with no newline is abandoned inside the ask budget")
    fun aDrippingMindCannotOutlastTheBudget() {
        // The fault a socket timeout cannot catch: every byte restarts the per-read
        // clock, so `readLine` waits as long as the peer keeps typing. Measured at 40s
        // inside a 2s read timeout before the whole-`ask` deadline existed.
        val readTimeout = 300.milliseconds
        val mind = managed(DrippingMind(interval = readTimeout / 3))
        val transport =
            managed(
                TcpCognitionTransport(
                    host = "127.0.0.1",
                    port = mind.port,
                    connectTimeout = PATIENCE,
                    readTimeout = readTimeout,
                ),
            )

        val started = TimeSource.Monotonic.markNow()
        // Preemptively, because the regression this pins is an *unbounded* wait: without
        // the whole-`ask` deadline the drip holds this thread until the fake stops, which
        // it never does, and a plain assertion would hang the suite instead of failing it.
        assertTimeoutPreemptively(PATIENCE.toJavaDuration()) {
            assertThrows<InterruptedIOException> { transport.ask(perception()) }
        }
        val blocked = started.elapsedNow()

        // The budget is the whole `ask`: two attempts' worth of read timeout. Generous
        // slack for a loaded CI box, but far below the unbounded wait this pins against.
        val budget = readTimeout * 2
        assertTrue(
            blocked < budget * 4,
            "a drip held the worker for $blocked against a budget of $budget",
        )
        // And it really was dripping - a fake that failed to send anything would make
        // this an ordinary silent-mind test and prove nothing about the drip.
        assertTrue(mind.dripped > 1, "the fake mind sent ${mind.dripped} bytes; it must actually drip")
    }

    @Test
    @DisplayName("a response line that never ends is refused rather than accumulated without limit")
    fun anEndlessResponseLineIsCapped() {
        // The memory half of the same fault. The mind bounds request lines at 256 KiB
        // as bytes arrive; this side bounds response lines by the same number, so
        // neither runtime can make the other hold memory without limit.
        val mind = managed(FakeMind { "x".repeat(MAX_RESPONSE_LINE_BYTES * 2) })
        // Its own transport, with a budget wide enough that the clock cannot get there
        // first: reaching the cap is itself a byte-at-a-time loop, and against the short
        // readTimeout the other tests use, a slow runner would trip the deadline and
        // fail this test's own not-a-timeout assertion. The cap is byte-counted, so a
        // wide deadline does not weaken what is being pinned - it just leaves the cap
        // the only bound that can fire.
        val transport =
            managed(
                TcpCognitionTransport(
                    host = "127.0.0.1",
                    port = mind.port,
                    connectTimeout = PATIENCE,
                    readTimeout = PATIENCE,
                ),
            )

        // No newline arrives within the cap, so the read is abandoned on the cap rather
        // than on the clock: this must not be a timeout in disguise.
        val thrown = assertThrows<IOException> { transport.ask(perception()) }

        assertFalse(
            thrown is InterruptedIOException,
            "the cap must stop this, not the deadline: ${thrown.message}",
        )
        assertTrue(
            thrown.message?.contains("$MAX_RESPONSE_LINE_BYTES") == true,
            "the diagnosis should name the cap it hit: ${thrown.message} (fake hang-ups: ${mind.hangUps})",
        )
    }

    @Test
    @DisplayName("a second response arriving in the same packet is answered from the bytes already read")
    fun bytesPastTheNewlineAreKeptForTheNextResponse() {
        val canonical = fixture("decision-idle.json")
        val asked = asking(canonical)
        var served = 0
        val mind =
            managed(
                FakeMind {
                    served++
                    // Two frames in one flush, and then nothing: the reads are chunk-wise,
                    // so the second decision arrives while the first is being framed.
                    if (served == 1) "$canonical\n$canonical" else null
                },
            )
        val transport = transportTo(mind)

        assertEquals(Intent.Idle, transport.ask(asked).intent)
        // The only place this answer can come from is the bytes read past the first
        // newline. Drop them instead of carrying them and the connection is a frame out
        // of step from here on - which correlation catches, but every response after it
        // is already the wrong one.
        assertEquals(Intent.Idle, transport.ask(asked).intent)
        // Waited for rather than asserted outright: answering the second `ask` from carried
        // bytes does not wait on the mind at all, so this thread can arrive before the
        // fake's own thread has recorded the request it is already serving.
        assertEquals(
            2,
            mind.requestsWithin(PATIENCE),
            "both perceptions went out on the one connection",
        )
    }

    @Test
    @DisplayName("a request the mind never answers is not sent a second time")
    fun aRequestIsNeverDeliveredTwice() {
        // The mind takes the perception and hangs up. It may well have started a model
        // call on it, so this one decision is lost rather than paid for twice: delivery
        // is at-most-once and the write is the seam.
        val mind = managed(FakeMind { null })

        assertThrows<IOException> { transportTo(mind).ask(perception()) }

        assertEquals(1, mind.requests.size, "the perception must reach the mind at most once")
    }

    @Test
    @DisplayName("a connection the peer dropped costs one decision, and the next request reconnects")
    fun aDroppedConnectionCostsOneDecision() {
        val canonical = fixture("decision-idle.json")
        val asked = asking(canonical)
        var served = 0
        val mind =
            managed(
                FakeMind {
                    served++
                    // The second exchange is taken and then dropped unanswered - what a
                    // service restart mid-request looks like from this side.
                    if (served == 2) null else canonical
                },
            )
        val transport = transportTo(mind)

        assertEquals(Intent.Idle, transport.ask(asked).intent)
        // The perception went out and the answer never came. That is past the seam, so
        // it is reported rather than re-sent: this one decision is the price of never
        // paying for the same inference twice.
        assertThrows<IOException> { transport.ask(asked) }
        // The dead connection is dropped, so the agent's next perception gets through
        // on a fresh one - one lost decision, not a body that has stopped thinking.
        assertEquals(Intent.Idle, transport.ask(asked).intent)
        assertEquals(3, served, "the lost exchange was not re-sent; the third is a new request")
    }

    @Test
    @DisplayName("a request that never got written is retried once, then reported")
    fun aFailureBeforeTheWriteIsRetriedOnce() {
        // Nothing is listening, so both attempts fail at connect - before the seam,
        // where a retry cannot duplicate a model call. The suppressed cause is the
        // evidence the retry happened: only the second failure carries the first.
        val transport =
            managed(
                TcpCognitionTransport(
                    host = "127.0.0.1",
                    port = unusedPort(),
                    connectTimeout = 500.milliseconds,
                    readTimeout = 500.milliseconds,
                ),
            )

        val thrown = assertThrows<IOException> { transport.ask(perception()) }

        assertEquals(
            1,
            thrown.suppressed.size,
            "a pre-write failure must be retried once, carrying the first failure for the operator",
        )
    }

    @Test
    @DisplayName("an answer to somebody else's request is refused, not handed to this caller")
    fun aMisaddressedDecisionIsRefused() {
        // Line order is the only thing correlating a request with its response, so a
        // stream that gets out of step would otherwise hand one agent's decided intent
        // to another agent's body - silently, and looking nothing like a bug.
        val mind = managed(FakeMind { fixture("decision-say.json") })

        val thrown = assertThrows<IOException> { transportTo(mind).ask(perception(requestId = "req-mine")) }

        assertTrue(
            thrown.message?.contains("req-mine") == true && thrown.message?.contains("req-spoken-1") == true,
            "both request ids belong in the diagnosis: ${thrown.message}",
        )
    }

    @Test
    @DisplayName("a closed transport refuses to open new connections")
    fun closingStopsTheTransport() {
        val mind = managed(FakeMind { fixture("decision-idle.json") })
        val transport = transportTo(mind)
        transport.close()

        assertThrows<IOException> { transport.ask(perception()) }
    }

    @Test
    @DisplayName("concurrent callers do not read each other's decisions")
    fun eachThreadGetsItsOwnConnection() {
        // Request and response are correlated by line order on a connection, so a
        // shared socket would let two threads swap answers. Each request here asks
        // for a decision naming its own agent, and every answer must match.
        val mind =
            managed(
                FakeMind { request ->
                    val asked = LoomJson.decodeFromString<Perception>(request)
                    LoomJson.encodeToString(decisionFor(asked))
                },
            )
        val transport = transportTo(mind)
        val start = CountDownLatch(1)
        val mismatches = CopyOnWriteArrayList<String>()
        val threads =
            (1..4).map { i ->
                Thread {
                    start.await()
                    repeat(10) { round ->
                        val sent = perception(requestId = "req-$i-$round", agentId = "agent-$i")
                        val got = transport.ask(sent)
                        if (got.requestId != sent.requestId) mismatches.add("${sent.requestId} got ${got.requestId}")
                    }
                }.apply { isDaemon = true }
            }
        threads.forEach(Thread::start)
        start.countDown()
        threads.forEach { it.join(PATIENCE.inWholeMilliseconds) }

        assertTrue(mismatches.isEmpty(), "answers went to the wrong caller: $mismatches")
    }

    @Test
    @DisplayName("the transport is what the bridge drives, end to end")
    fun theBridgeDrivesTheRealTransport() {
        // The whole path in one test, with only the mind faked: bridge, worker pool,
        // socket, contract bytes, decoded decision.
        val canonical = fixture("decision-move-to.json")
        val mind = managed(FakeMind { canonical })
        val bridge =
            managed(
                AsyncCognitionBridge(
                    transport = transportTo(mind),
                    timeout = PATIENCE,
                    workers = 2,
                    queueDepth = 4,
                ),
            )

        val decision =
            bridge.submit(asking(canonical).copy(trigger = Trigger.BlockSighted("diamond_ore")))
                .get(PATIENCE.inWholeMilliseconds, TimeUnit.MILLISECONDS)

        assertEquals(Intent.MoveTo(listOf(128L, 11L, -64L)), decision.intent)
        assertEquals(DecisionImportance.ROUTINE, decision.provenance.tier)
    }
}
