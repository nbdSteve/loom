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
import dev.loom.mind.CognitionTransport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/** The key the service's refusal frame carries. Not a contract type. */
private const val ERROR_KEY = "error"

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
 * @param readTimeout how long to wait for a response line. This is the limit
 *   that frees a worker thread from a mind that stopped answering, so it should
 *   be no longer than the bridge's own deadline.
 */
class TcpCognitionTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeout: Duration,
    private val readTimeout: Duration,
) : CognitionTransport, AutoCloseable {
    /** One live connection: the socket plus its buffered line reader and writer. */
    private class Channel(
        val socket: Socket,
        val reader: BufferedReader,
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
        return awaitDecision(sent, perception)
    }

    /**
     * Reads the decision answering a request already written to [channel].
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
    ): Decision =
        try {
            val response =
                channel.reader.readLine()
                    ?: throw IOException("the mind closed the connection without answering")
            decode(response, perception)
        } catch (failed: IOException) {
            // The connection is dropped either way: after a read timeout a late
            // response would desynchronize every later request on it, and after a
            // correlation failure the stream is already carrying somebody else's
            // answers.
            discard()
            throw failed
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
                socket.getInputStream().bufferedReader(),
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
