/*
 * Where the mind is, and how long the body will wait for it.
 *
 * Its own type, in the domain rather than in the wiring, so the defaults are one
 * readable thing a test can pin instead of literals scattered through plugin
 * setup. The bridge's own preconditions cover positivity; what is checked here is
 * the relationship between the two timeouts, which no single component can see.
 */
package dev.loom.mind

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How the body reaches the mind.
 *
 * @param host where the cognition service listens. Loopback by default: the mind
 *   trusts whatever sends it a perception, so reaching it across a network should
 *   take saying so.
 * @param port the service's port.
 * @param timeout how long a request may take before the caller's future is
 *   failed. Generous relative to a tick because it is not on the tick path at
 *   all - the point of the bridge is that this number cannot cost the server
 *   anything.
 * @param connectTimeout how long to wait for the connection itself. Short: a mind
 *   that is not listening should be known immediately, not at the request
 *   deadline.
 * @param workers how many cognition requests may be in flight at once.
 * @param queueDepth how many may wait for a worker before requests are refused.
 */
data class CognitionSettings(
    val host: String = "127.0.0.1",
    val port: Int = 8642,
    val timeout: Duration = 5.seconds,
    val connectTimeout: Duration = 500.milliseconds,
    val workers: Int = 4,
    val queueDepth: Int = 64,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..MAX_PORT) { "port must be in 1..$MAX_PORT, was $port" }
        require(connectTimeout.isPositive()) { "connectTimeout must be positive, was $connectTimeout" }
        require(connectTimeout < timeout) {
            "connectTimeout ($connectTimeout) must be shorter than timeout ($timeout), or a mind that is " +
                "not listening would consume the whole request deadline before the connection is even refused"
        }
        // A socket expresses both timeouts in whole milliseconds, where zero means
        // "wait forever". Anything that rounds down to zero would therefore invert
        // the property this class exists to guarantee - an unbounded wait, from
        // settings that read as the tightest possible bound - so the floor is
        // checked here rather than discovered as a hung worker.
        require(connectTimeout.inWholeMilliseconds >= MIN_TIMEOUT_MILLIS) {
            "connectTimeout ($connectTimeout) must be at least ${MIN_TIMEOUT_MILLIS}ms, or it narrows to a " +
                "socket timeout of zero, which means no connect timeout at all"
        }
        require(readTimeout.inWholeMilliseconds >= MIN_TIMEOUT_MILLIS) {
            "timeout ($timeout) is too short: its read timeout ($readTimeout) narrows to a socket timeout of " +
                "zero, which means an unbounded read - the opposite of bounding the wait"
        }
    }

    /**
     * How long a transport may block reading one response.
     *
     * Tied to [timeout] rather than configured separately, and deliberately
     * shorter: the bridge's watchdog frees the *caller* of a hung mind but cannot
     * free the worker thread parked in the read, so the read must give up first or
     * a stalled mind would slowly consume every worker.
     *
     * Budgeted for a whole [CognitionTransport.ask] rather than for one read,
     * because a transport may attempt a connection and a read twice: an `ask` that
     * spends two connect timeouts and two full reads must still finish inside the
     * bridge deadline, or a worker stays busy after its caller has already been
     * failed and later requests are refused as saturated while capacity actually
     * existed.
     */
    val readTimeout: Duration get() = (timeout - (connectTimeout * ATTEMPTS_PER_ASK)) / ATTEMPTS_PER_ASK

    private companion object {
        const val MAX_PORT = 65_535

        /**
         * Shortest timeout that survives narrowing to a socket's whole-millisecond
         * field, where zero means "no timeout".
         */
        const val MIN_TIMEOUT_MILLIS = 1

        /**
         * How many connect-and-read attempts one `ask` may make.
         *
         * Two, because the transport retries once on a connection that broke before
         * its request was written. Raising the retry count means raising this, or
         * the read budget silently stops covering an `ask`.
         */
        const val ATTEMPTS_PER_ASK = 2
    }
}
