/*
 * When a research run's experiment exposure ends: the moment cognition admission closes, after
 * which nothing new may count toward the run.
 */
package dev.loom.mind

/**
 * Whether the run's experiment exposure has ended, and at which server tick.
 *
 * Ending it is one main-thread step with the record that says so and the stop of every native
 * cognition source, so nothing new is perceived, decided on, applied, damaged, targeted or killed
 * after it, while work already admitted drains to typed terminals: a perception already captured
 * ends as `cognitionUnavailable` and an answer already with the mind as `decisionDiscarded`, both
 * `exposureEnded`, and a movement or interaction the body was already carrying out ends as its
 * own report says. r34's delta and gamma were shot dead by skeletons 78 and 124 ticks after
 * admission closed, in the drain, and both deaths counted. Read from any thread.
 */
class ExposureGate {
    @Volatile
    var endedAt: Long? = null
        private set

    /** Whether exposure has ended. */
    val ended: Boolean get() = endedAt != null

    /** Ends exposure at [tick], answering whether this call was the one that did. Main thread only. */
    fun end(tick: Long): Boolean {
        if (endedAt != null) return false
        endedAt = tick
        return true
    }
}
