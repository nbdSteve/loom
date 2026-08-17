/*
 * The transport that reaches the real Rust cognition service.
 *
 * Wire format: one JSON object per line, in both directions. A request line is a
 * contract `Perception`; a response line is a contract `Decision`, or a
 * `{"error":...}` refusal when the mind could not answer at all. Encoding and
 * decoding go through `LoomJson` - the same configuration the conformance tests
 * pin - so the bytes on this socket are the bytes both runtimes agree on.
 *
 * It lives in its own adapter package for the same reason the body's NMS code
 * does: `dev.loom.mind` is the port, and a transport is one way of satisfying it.
 * A different mind deployment - a subprocess, a queue, an in-process model - is a
 * sibling package and one changed wiring line.
 *
 * Blocking is correct here. `CognitionTransport.ask` is only ever called on a
 * bridge worker thread, and a blocking socket with its own timeouts is the
 * simplest thing that bounds its own waiting, which the port requires: the
 * bridge's watchdog frees the caller of a hung mind but cannot free the thread
 * stuck in a read.
 *
 * That bound is a **wall-clock budget over the read side of a whole `ask`**, both
 * reads of its one retry included, and it is framed here by hand rather than left
 * to `BufferedReader.readLine`. `Socket.soTimeout` bounds one read syscall, not one
 * line: `readLine` loops until it sees a newline and every byte that arrives
 * restarts the clock, so a peer dripping one byte just under the timeout holds a
 * worker forever while the line buffer grows without limit. Measured at 40s inside
 * a 2s read timeout. The response line is therefore accumulated against
 * [MAX_RESPONSE_LINE_BYTES] and that shared deadline, exactly the way the mind's
 * own `read_frame` bounds request lines.
 *
 * What the deadline does *not* cut short is named here on purpose, because a bound
 * believed to be wider than it is would be worse than no bound at all:
 *
 * - The connect legs are charged to the budget - it is armed before the first
 *   connect - but they are bounded by `connectTimeout` rather than narrowed onto
 *   what is left of it. `CognitionSettings` is what keeps both connects and both
 *   reads fitting inside the bridge deadline.
 * - The write leg has no clock at all, because Java exposes no `SO_SNDTIMEO` to
 *   narrow it onto. It is bounded instead by the perception: `PerceptionBound` caps
 *   a snapshot at a handful of observations, roughly 1-2 KB, which fits a send
 *   buffer without blocking.
 *
 * Bounding either of those by the clock would take a non-blocking channel, and is
 * separate work.
 *
 * Delivery is at-most-once, and the seam is the write. A failure before the
 * perception is on the wire is retried once, because nothing can have been
 * decided yet; a failure after it is reported, because the mind may already have
 * spent a model call on it and a duplicate inference is worse than a lost
 * decision. `CognitionSettings.readTimeout` budgets both attempts inside the
 * bridge deadline.
 */
package dev.loom.mind.tcp

import dev.loom.contract.Decision
import dev.loom.contract.LoomJson
import dev.loom.contract.Perception
import dev.loom.mind.ATTEMPTS_PER_ASK
import dev.loom.mind.CognitionTransport
import dev.loom.mind.MIN_SOCKET_TIMEOUT_MILLIS
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.TimeSource

/** The key the service's refusal frame carries. Not a contract type. */
private const val ERROR_KEY = "error"

/** The byte that ends a frame in both directions. */
private const val NEWLINE = '\n'.code

/**
 * Longest response line this transport will accumulate, in bytes.
 *
 * Mirrors the mind's own `MAX_REQUEST_LINE_BYTES`, deliberately to the byte: the
 * two runtimes bound each other's lines by the same number, so neither side can
 * make the other hold memory without limit. A decision is small - one intent and
 * a provenance stamp - so this is generous by a wide margin.
 *
 * Internal rather than private so the test asserting the bound reads the same
 * number the transport enforces, instead of restating it and drifting.
 */
internal const val MAX_RESPONSE_LINE_BYTES = 256 * 1024

/**
 * How much of a response is taken from the socket per read.
 *
 * A decision is a few hundred bytes, so this is one read per response in every
 * ordinary case, and the newline is then found by scanning bytes already in hand.
 * Asking the socket for one byte at a time would be correct too, but not cheap: on a
 * socket stream `read` and `available` alike reach the descriptor, so a per-byte loop
 * costs a syscall per byte - hundreds for one decision, and hundreds of thousands to
 * reach [MAX_RESPONSE_LINE_BYTES].
 */
private const val RESPONSE_CHUNK_BYTES = 8 * 1024

/**
 * Reads newline-terminated frames off one connection, a chunk at a time.
 *
 * The mind's own `read_frame` is the model: take what has arrived, scan the bytes
 * already in hand for the newline, and accumulate against a cap rather than calling
 * something that loops until a newline on its own terms. That is what keeps both of
 * the transport's bounds real, and it is why this does its own buffering instead of
 * wrapping the stream in a reader.
 *
 * **Bytes past a newline are kept, never dropped.** They are the beginning of the
 * *next* response on this connection, and line order is the only thing correlating a
 * request with its response, so swallowing them would desynchronize every later
 * exchange on the channel - silently, and looking nothing like a bug.
 */
private class FrameReader(
    private val source: InputStream,
) : AutoCloseable {
    private val chunk = ByteArray(RESPONSE_CHUNK_BYTES)

    /** The window of [chunk] that has arrived and not yet been handed out. */
    private var at = 0
    private var filled = 0

    /**
     * The next frame, newline stripped.
     *
     * [beforeBlocking] is called with the bytes accumulated so far, immediately
     * before every read that could actually block - carried bytes are already in
     * hand, so they cost nothing and bound nothing. It is where the caller narrows
     * its own deadline onto the socket, and where it gives up if that is spent.
     *
     * [refuseAt] many bytes is the longest frame this will accumulate; a longer one
     * throws [tooLong] rather than being buffered first.
     */
    fun readFrame(
        refuseAt: Int,
        beforeBlocking: (Int) -> Unit,
        tooLong: (Int) -> IOException,
    ): String {
        val line = ByteArrayOutputStream()
        while (true) {
            if (at == filled) {
                beforeBlocking(line.size())
                val read = source.read(chunk, 0, chunk.size)
                if (read < 0) throw IOException("the mind closed the connection without answering")
                at = 0
                filled = read
            }
            val newlineAt = newlineInHand()
            val end = if (newlineAt < 0) filled else newlineAt
            // Checked before the bytes are kept, so an endless line is abandoned
            // rather than accumulated and measured afterwards.
            if (line.size() + (end - at) > refuseAt) throw tooLong(refuseAt)
            line.write(chunk, at, end - at)
            at = if (newlineAt < 0) filled else newlineAt + 1
            if (newlineAt >= 0) return line.toString(StandardCharsets.UTF_8)
        }
    }

    /** Where the frame ends within the bytes in hand, or -1 if it does not. */
    private fun newlineInHand(): Int {
        for (index in at until filled) {
            if (chunk[index].toInt() == NEWLINE) return index
        }
        return -1
    }

    override fun close() {
        source.close()
    }
}

/**
 * The wall-clock budget for one `ask`, armed when the `ask` begins.
 *
 * A budget rather than a per-read timeout because the thing that has to be
 * bounded is the *call*, not a syscall inside it: a peer that keeps sending bytes
 * restarts a socket read timeout indefinitely, and only elapsed time since the
 * `ask` started catches that.
 */
private class Deadline(
    private val budget: Duration,
) {
    private val armed = TimeSource.Monotonic.markNow()

    /** What is left of the budget; zero or negative once it is spent. */
    fun remaining(): Duration = budget - armed.elapsedNow()
}

/**
 * The mind's refusal frame: it was reached, but produced no decision.
 *
 * Deliberately not a contract type. A mind that could not answer has not decided
 * anything, and turning a refusal into some `Intent` would put a fiction in the
 * decision record.
 */
@Serializable
private data class WireError(
    @SerialName(ERROR_KEY) val error: String,
)

/**
 * Speaks to a cognition service over TCP, one connection per calling thread.
 *
 * A connection is kept open across requests - the service answers many lines per
 * connection - and reconnected on the next request if it breaks. Per-thread
 * rather than shared, because request and response are correlated by line order
 * on a connection: two threads interleaving writes on one socket could read each
 * other's answers. The bridge bounds how many threads exist, so this bounds the
 * connections too.
 *
 * @param host where the service listens.
 * @param port the service's port.
 * @param connectTimeout how long to wait for the connection itself.
 * @param readTimeout how long to wait for a response line, per attempt. This is
 *   the limit that frees a worker thread from a mind that stopped answering, so it
 *   should be no longer than the bridge's own deadline. One `ask` gets
 *   [ATTEMPTS_PER_ASK] of these as the wall-clock budget its reads spend - the same
 *   constant `CognitionSettings.readTimeout` divides the bridge deadline by, so the
 *   budget spent here is the budget reserved there.
 */
class TcpCognitionTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeout: Duration,
    private val readTimeout: Duration,
) : CognitionTransport, AutoCloseable {
    /** One live connection: the socket plus its frame reader and line writer. */
    private class Channel(
        val socket: Socket,
        val reader: FrameReader,
        val writer: BufferedWriter,
    ) : AutoCloseable {
        override fun close() {
            // Closing the socket is what unblocks a thread parked in a read, so it
            // is done even if the streams themselves fail to close.
            runCatching { reader.close() }
            runCatching { writer.close() }
            runCatching { socket.close() }
        }
    }

    private val channels = ThreadLocal<Channel?>()

    // Every channel ever handed out, so close() can reach the ones belonging to
    // other threads. A ThreadLocal alone would leave them open until their
    // threads died, which on plugin disable is exactly the wrong time to wait.
    private val openLock = ReentrantLock()
    private val open = mutableSetOf<Channel>()
    private var closed = false

    override fun ask(perception: Perception): Decision {
        val request = LoomJson.encodeToString(perception)
        // Armed before the first connect, so a slow connect, a retry, and both reads
        // all spend the same budget. Per-attempt timers are what let a drip outlast
        // every configured bound.
        val deadline = Deadline(readTimeout * ATTEMPTS_PER_ASK)
        val sent =
            try {
                send(channel(), request)
            } catch (broken: IOException) {
                // Only a failure at or before the write is retried. A connection that
                // was refused, or reaped by the OS while idle, breaks here and failing
                // an agent's decision over it would be needless - and a request that
                // did not get written is one the mind cannot have decided, so sending
                // it again cannot duplicate a model call. Not a general recovery loop:
                // a second failure goes to the bridge.
                discard()
                retry(request, broken)
            }
        return awaitDecision(sent, perception, deadline)
    }

    /**
     * Reads the decision answering a request already written to [channel], within
     * what is left of [deadline].
     *
     * Deliberately outside every retry path. **Delivery is at-most-once:** once the
     * mind has the perception it may already have spent a model call on it, so a
     * failure from here on is reported rather than retried. Re-sending would risk a
     * second inference - and a second bill - for one perception, and a decision
     * nobody is waiting for is worth less than not paying twice.
     */
    private fun awaitDecision(
        channel: Channel,
        perception: Perception,
        deadline: Deadline,
    ): Decision =
        try {
            decode(readLine(channel, deadline), perception)
        } catch (failed: IOException) {
            // The connection is dropped either way: after a read timeout a late
            // response would desynchronize every later request on it, and after a
            // correlation failure the stream is already carrying somebody else's
            // answers.
            discard()
            throw failed
        }

    /**
     * Reads one response line, bounded both by what is left of [deadline] and by
     * [MAX_RESPONSE_LINE_BYTES].
     *
     * Both bounds are supplied to the framing rather than trusted to it: the socket
     * timeout is re-narrowed to the *remaining* budget immediately before every read
     * that could block, so bytes arriving cannot extend the call, and the cap is
     * enforced as the bytes arrive rather than after a line is complete.
     */
    private fun readLine(
        channel: Channel,
        deadline: Deadline,
    ): String =
        channel.reader.readFrame(
            refuseAt = MAX_RESPONSE_LINE_BYTES,
            beforeBlocking = { soFar -> channel.socket.soTimeout = remainingSocketTimeout(deadline, soFar) },
            tooLong = { cap ->
                IOException(
                    "the mind's response line reached $cap bytes with no newline: " +
                        "a response that long is not a decision this transport will hold",
                )
            },
        )

    /**
     * What is left of [deadline] as a socket timeout, or a failure if it is spent.
     *
     * [soFar] is only for the diagnosis: an operator seeing this needs to know the
     * mind was sending *something*, because a drip and a silence are the same
     * timeout otherwise and they are different faults.
     */
    private fun remainingSocketTimeout(
        deadline: Deadline,
        soFar: Int,
    ): Int {
        val left = deadline.remaining().inWholeMilliseconds
        // Below the floor there is no timeout left to ask for - and a zero would mean
        // "wait forever", which is the opposite of giving up.
        if (left < MIN_SOCKET_TIMEOUT_MILLIS) {
            throw InterruptedIOException(
                "the mind did not complete a response line within ${readTimeout * ATTEMPTS_PER_ASK}: " +
                    "$soFar bytes arrived with no newline",
            )
        }
        return left.toInt()
    }

    /**
     * Retries [request] once on a fresh connection after [broken].
     *
     * A second failure carries the first as a suppressed cause: "the retry failed
     * too" and "the connection was already dead" are different diagnoses, and an
     * operator needs both to tell a restarted mind from an unreachable one.
     */
    private fun retry(
        request: String,
        broken: IOException,
    ): Channel =
        try {
            send(channel(), request)
        } catch (again: IOException) {
            again.addSuppressed(broken)
            throw again
        }

    /** Writes [request] to [channel], returning it once the bytes are away. */
    private fun send(
        channel: Channel,
        request: String,
    ): Channel {
        channel.writer.write(request)
        channel.writer.write("\n")
        channel.writer.flush()
        return channel
    }

    /**
     * Parses a response line into the [Decision] answering [asked].
     *
     * A refusal frame is recognized before decoding as a decision so its message
     * survives into the failure the bridge reports; without that the operator
     * would only see a parse error about a field the mind never claimed to send.
     *
     * The request id is then checked against [asked], because line order is the
     * only thing correlating a request with its response and a mismatch is
     * otherwise silent: the bridge would hand one agent's decided intent to
     * another agent's body. There is no known way to reach that today, which is
     * the point of checking - the failure it prevents would not look like a bug.
     */
    private fun decode(
        response: String,
        asked: Perception,
    ): Decision {
        val element = LoomJson.parseToJsonElement(response)
        val refusal = element.jsonObject[ERROR_KEY]
        if (refusal != null) {
            throw IOException("the mind refused: ${LoomJson.decodeFromJsonElement<WireError>(element).error}")
        }
        val decision = LoomJson.decodeFromJsonElement<Decision>(element)
        if (decision.requestId != asked.requestId) {
            throw IOException(
                "the mind answered request ${decision.requestId} on the connection asking " +
                    "${asked.requestId}: the responses on this connection are out of step with its requests",
            )
        }
        return decision
    }

    /** This thread's connection, opening one if it has none. */
    private fun channel(): Channel {
        channels.get()?.let { return it }
        val socket = Socket()
        // Nagle off: these are small request/response frames and a body is waiting.
        socket.tcpNoDelay = true
        socket.soTimeout = readTimeout.inWholeMilliseconds.toInt()
        socket.connect(InetSocketAddress(host, port), connectTimeout.inWholeMilliseconds.toInt())
        val opened =
            Channel(
                socket,
                FrameReader(socket.getInputStream()),
                socket.getOutputStream().bufferedWriter(),
            )
        openLock.withLock {
            if (closed) {
                // Raced with close(). Better to fail this request than to leak a
                // connection nothing will ever close.
                opened.close()
                throw IOException("the cognition transport is closed")
            }
            open.add(opened)
        }
        channels.set(opened)
        return opened
    }

    /** Drops this thread's connection so the next request opens a fresh one. */
    private fun discard() {
        val channel = channels.get() ?: return
        channels.remove()
        openLock.withLock { open.remove(channel) }
        channel.close()
    }

    /**
     * Closes every connection this transport has opened, on any thread.
     *
     * Closing the sockets is what releases a worker parked in a read, so this is
     * what makes plugin disable prompt rather than dependent on a timeout.
     */
    override fun close() {
        val toClose =
            openLock.withLock {
                closed = true
                open.toList().also { open.clear() }
            }
        toClose.forEach(Channel::close)
        channels.remove()
    }
}
