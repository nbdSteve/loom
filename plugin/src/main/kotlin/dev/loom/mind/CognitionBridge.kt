/*
 * The outbound cognition port: the one channel between the body and the mind.
 *
 * Exactly two contract types cross it - a `Perception` goes out, a `Decision`
 * comes back - which is the perception boundary expressed as a type signature.
 * The mind is handed a bounded snapshot and can only name an intent; it gets no
 * world handle, no body, and no callback into the server.
 *
 * The port is asynchronous by construction rather than by convention: `submit`
 * returns a future, so there is no shape of this API in which a caller on the
 * server main thread ends up waiting for a model. Whatever runs cognition -
 * an in-process fake, a socket to the Rust service, something else later - is a
 * `CognitionTransport` behind this port, and swapping it changes one wiring line.
 */
package dev.loom.mind

import dev.loom.contract.Decision
import dev.loom.contract.Perception
import java.util.concurrent.CompletableFuture

/**
 * Submits perceptions for cognition and hands back decisions.
 *
 * Implementations must be safe to call from the server main thread, and [submit]
 * must return promptly whatever the mind is doing - a slow or dead mind may cost
 * one agent its decision and must never cost the server its tick.
 */
fun interface CognitionBridge {
    /**
     * Sends [perception] to the mind and returns a future for its decision.
     *
     * Returns immediately. The future completes exceptionally if the mind fails,
     * refuses, or overruns the bridge's deadline; callers must handle that as an
     * ordinary outcome for one agent rather than an error condition for the
     * server.
     *
     * The future completes on a background thread. Anything that touches world
     * state must be marshalled onto the main thread first - see [MainThread].
     */
    fun submit(perception: Perception): CompletableFuture<Decision>

    /**
     * How many requests one agent may have outstanding through this bridge before a further one
     * is refused: unbounded unless a bridge in the chain bounds it ([PerAgentCognitionBridge]).
     */
    val outstandingPerAgent: Int get() = Int.MAX_VALUE
}

/**
 * Runs one cognition request to completion, blocking the calling thread.
 *
 * This is the seam a bridge wraps: it is written as an ordinary blocking call
 * because it always runs on a bridge-owned background thread, never on the main
 * thread. Keeping it blocking is what lets the same bridge be driven by an
 * in-process fake and by a real socket without either side knowing.
 *
 * Implementations must be safe to call from several threads at once, and must
 * bound their own waiting: the bridge's watchdog frees the *caller* of a hung
 * mind, but only the transport can free the thread that is actually blocked in a
 * read.
 */
fun interface CognitionTransport {
    /**
     * Asks the mind about [perception] and returns its decision.
     *
     * @throws Exception if the mind could not be reached, refused the request,
     *   or answered with something that is not a decision. The bridge turns that
     *   into a failed future for one agent and keeps serving.
     */
    fun ask(perception: Perception): Decision
}

/**
 * Runs a task on the server main thread.
 *
 * The single sanctioned path from a background thread back to world state. It
 * exists as a port so the marshalling behavior is testable without a server: in
 * production it is `Bukkit`'s scheduler, in tests a queue a test drains itself.
 *
 * Implementations must only ever *schedule*: running the task inline on the
 * calling thread would defeat the entire point.
 */
fun interface MainThread {
    /** Schedules [task] to run on the main thread. Must not run it inline. */
    fun execute(task: () -> Unit)
}
