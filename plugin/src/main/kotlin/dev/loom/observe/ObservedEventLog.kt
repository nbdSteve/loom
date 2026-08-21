/*
 * The seam that lets an operator watch a run without being part of it.
 *
 * A decorator over the real `BodyEventLog`, so the display is fed by the same records
 * the research record is made of - the same values, not a re-derivation of them and not
 * a parse of the file afterwards. That is the whole of "reuse the append-only records as
 * authoritative data": there is no second store to fall out of step with the first,
 * because there is no second store.
 *
 * Two properties make it safe to put in the tick path, and both are load-bearing:
 * the real log is appended *first*, so an observer can never cost the record a line;
 * and anything the observatory throws is contained here, so a bug in a display can never
 * take down a body's tick or a mind's callback.
 */
package dev.loom.observe

import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord

/**
 * Writes every record to [log], then offers it to [offer].
 *
 * @param log the real append-only log. Always written, always first.
 * @param offer the display, as the one thing this seam needs of it - `Observatory::saw`
 *   in the plugin. A capability rather than the class, so the decorator can say plainly
 *   that it does nothing to a record but hand it on, and so this file's containment
 *   guarantee can be tested against an observer that fails on purpose.
 * @param onObserverFailure told about anything the display throws. Wired to the plugin
 *   logger, because a display that has silently stopped updating looks exactly like an
 *   agent that has stopped doing anything.
 */
class ObservedEventLog(
    private val log: BodyEventLog,
    private val offer: (BodyEventRecord) -> Unit,
    private val onObserverFailure: (Throwable) -> Unit,
) : BodyEventLog {
    /**
     * Appends [record], then shows it to whoever is watching.
     *
     * Catches broadly on purpose: this is the error boundary between an operator's
     * display and the run it is watching. Observation is a convenience and the run is
     * the point, so whatever a display manages to throw becomes one reported failure
     * rather than an exception in the middle of a tick.
     */
    @Suppress("TooGenericExceptionCaught")
    override fun append(record: BodyEventRecord) {
        log.append(record)
        try {
            offer(record)
        } catch (failure: RuntimeException) {
            onObserverFailure(failure)
        }
    }
}
