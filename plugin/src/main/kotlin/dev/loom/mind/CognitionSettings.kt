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
 * How many connect-and-read attempts one `ask` may make.
 *
 * Two, because the transport retries once on a connection that broke before its
 * request was written. [CognitionSettings.readTimeout] divides the bridge deadline
 * by this, and `TcpCognitionTransport` multiplies its read timeout back up by it to
 * get the budget for one `ask`, so raising the retry count means raising this and
 * nothing else.
 *
 * Internal rather than private so the transport spending the budget reads the same
 * number the budget was derived from, instead of restating it and drifting: a
 * transport attempting three times against a budget reserved for two would park a
 * worker past the point its caller had already been failed.
 */
internal const val ATTEMPTS_PER_ASK = 2

/**
 * Shortest timeout that survives narrowing to a socket's whole-millisecond field.
 *
 * A socket reads a timeout of zero as "no timeout at all", so a duration that rounds
 * down to zero must be treated as spent rather than passed on: it would otherwise
 * invert the property the timeouts exist for, turning the tightest-looking
 * configuration into the only unbounded one.
 *
 * Internal rather than private to either side because it is one fact about the
 * socket field, checked twice: [CognitionSettings] refuses a configured timeout
 * below it, and `TcpCognitionTransport` treats a remaining budget below it as spent.
 * Held in one place so raising the floor cannot leave the transport still passing a
 * value the socket treats as infinite.
 */
internal const val MIN_SOCKET_TIMEOUT_MILLIS = 1L
private const val DEFAULT_PER_AGENT_PENDING = 4
private const val MAX_PER_AGENT_PENDING = 64

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
 *   anything. Sized against a real model rather than against a stub: the mind
 *   waits up to 12s for one provider call (`LOOM_OPENAI_TIMEOUT_MS`) and then
 *   answers with a bounded, actionable "the provider did not answer", so the
 *   body has to still be waiting for that answer to be worth anything. 30s
 *   leaves [readTimeout] at 14.5s, which is the middle number that ordering
 *   needs. Shortening this without shortening the mind's own timeout puts the
 *   body's socket first and turns every provider stall into the far less useful
 *   "the mind did not reply".
 * @param connectTimeout how long to wait for the connection itself. Short: a mind
 *   that is not listening should be known immediately, not at the request
 *   deadline.
 * @param workers how many cognition requests may be in flight at once.
 * @param queueDepth how many may wait for a worker before requests are refused.
 */
data class CognitionSettings(
    val host: String = "127.0.0.1",
    val port: Int = 8642,
    val timeout: Duration = 30.seconds,
    val connectTimeout: Duration = 500.milliseconds,
    val workers: Int = 4,
    val queueDepth: Int = 64,
    val perAgentPending: Int = DEFAULT_PER_AGENT_PENDING,
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
        require(connectTimeout.inWholeMilliseconds >= MIN_SOCKET_TIMEOUT_MILLIS) {
            "connectTimeout ($connectTimeout) must be at least ${MIN_SOCKET_TIMEOUT_MILLIS}ms, or it narrows " +
                "to a socket timeout of zero, which means no connect timeout at all"
        }
        require(readTimeout.inWholeMilliseconds >= MIN_SOCKET_TIMEOUT_MILLIS) {
            "timeout ($timeout) is too short: its read timeout ($readTimeout) narrows to a socket timeout of " +
                "zero, which means an unbounded read - the opposite of bounding the wait"
        }
        require(perAgentPending in 1..MAX_PER_AGENT_PENDING) {
            "perAgentPending must be in 1..$MAX_PER_AGENT_PENDING, was $perAgentPending"
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
     *
     * This is a budget, not a socket setting. A socket timeout bounds one read
     * syscall, so a mind dripping bytes without a newline would restart it forever;
     * `TcpCognitionTransport` therefore spends [ATTEMPTS_PER_ASK] of this number as a
     * wall-clock deadline over the *read* side of a whole `ask`, both reads of its one
     * retry included. The connect legs are still bounded by [connectTimeout] rather
     * than by that deadline, which is why the arithmetic here has to reserve room for
     * them. Read that transport before changing it - the number here is only a bound
     * because that side enforces it as one.
     */
    val readTimeout: Duration get() = (timeout - (connectTimeout * ATTEMPTS_PER_ASK)) / ATTEMPTS_PER_ASK

    companion object {
        private const val MAX_PORT = 65_535

        private fun configuredPerAgentPending(environment: Map<String, String>): Int {
            val raw = environment["LOOM_COGNITION_PENDING_PER_AGENT"]
            val parsed = raw?.toIntOrNull()
            require(raw == null || parsed != null) {
                "LOOM_COGNITION_PENDING_PER_AGENT must be an integer"
            }
            val value = parsed ?: DEFAULT_PER_AGENT_PENDING
            require(value in 1..MAX_PER_AGENT_PENDING) {
                "LOOM_COGNITION_PENDING_PER_AGENT must be in 1..$MAX_PER_AGENT_PENDING"
            }
            return value
        }

        /** Reads the one per-agent resource budget owned by the JVM bridge. */
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): CognitionSettings =
            CognitionSettings(perAgentPending = configuredPerAgentPending(environment))
    }
}
