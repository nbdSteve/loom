/*
 * When Loom's native cognition sources begin: the goal-review schedule, the threat scan and
 * the damage witness.
 *
 * By default they begin as the plugin enables, which is what an operator running a world
 * wants. A research run instead needs its initial conditions - clock, positions and empty
 * inventories - applied before any agent perceives anything, and the fleet starts agents one
 * pair at a time, so the first agent's first scheduled review could otherwise arrive while
 * later agents were still starting. `held` keeps every native source off until the operator
 * says `/loombody begin`, once, after the conditions are in place.
 */
package dev.loom.plugin

import dev.loom.mind.DEFAULT_FOLLOW_UPS_PER_REVIEW
import dev.loom.mind.DEFAULT_GOAL_REVIEW_CADENCE_TICKS
import dev.loom.mind.GoalReviewSchedule
import dev.loom.mind.MAX_FOLLOW_UPS_PER_REVIEW

/** The environment setting that selects when native cognition begins. */
const val NATIVE_COGNITION_ENV: String = "LOOM_NATIVE_COGNITION"

/** The environment setting that bounds how many follow-ups each scheduled review earns. */
const val FOLLOW_UPS_ENV: String = "LOOM_GOAL_FOLLOW_UPS_PER_REVIEW"

/** The environment setting that makes the review schedule periodic: one review per agent every this many ticks. */
const val REVIEW_CADENCE_ENV: String = "LOOM_GOAL_REVIEW_CADENCE_TICKS"

/** A positive tick count up to [DEFAULT_GOAL_REVIEW_CADENCE_TICKS] from [value], or `null` when unset. */
fun reviewCadenceOf(value: String?): Long? {
    if (value == null) return null
    val ticks = value.trim().toLongOrNull()
    require(ticks != null && ticks in 1..DEFAULT_GOAL_REVIEW_CADENCE_TICKS) {
        "$REVIEW_CADENCE_ENV must be a tick count in 1..$DEFAULT_GOAL_REVIEW_CADENCE_TICKS, was \"$value\""
    }
    return ticks
}

/** The schedule the environment asks for: periodic at [REVIEW_CADENCE_ENV] ticks when set. */
fun reviewScheduleOf(environment: (String) -> String?): GoalReviewSchedule {
    val followUps = followUpsPerReviewOf(environment(FOLLOW_UPS_ENV))
    val cadence =
        reviewCadenceOf(environment(REVIEW_CADENCE_ENV))
            ?: return GoalReviewSchedule(followUpsPerReview = followUps)
    return GoalReviewSchedule(
        cadenceTicks = cadence,
        slicesPerCadence = minOf(cadence, PERIODIC_SLICES).toInt(),
        followUpsPerReview = followUps,
        periodic = true,
    )
}

private const val PERIODIC_SLICES = 24L

/**
 * The follow-ups each scheduled review earns, from [value]: absent is the schedule's own
 * [DEFAULT_FOLLOW_UPS_PER_REVIEW], and anything that is not a count up to [MAX_FOLLOW_UPS_PER_REVIEW] stops the plugin
 * rather than running a plan nobody configured.
 */
fun followUpsPerReviewOf(value: String?): Int {
    if (value == null) return DEFAULT_FOLLOW_UPS_PER_REVIEW
    val count = value.trim().toIntOrNull()
    require(count != null && count in 0..MAX_FOLLOW_UPS_PER_REVIEW) {
        "$FOLLOW_UPS_ENV must be a count in 0..$MAX_FOLLOW_UPS_PER_REVIEW, was \"$value\""
    }
    return count
}

/**
 * A one-shot latch over the native cognition sources.
 *
 * @param held whether [start] waits for [begin] rather than running from [enable].
 * @param witnessBegin runs once, just before [start], only when a held run begins: the server's
 *   reading of every agent at the tick its initial conditions must hold (`InitialConditionsWitness`).
 * @param start starts every native source. Runs exactly once, on the server main thread.
 */
class NativeCognitionStart(
    private val held: Boolean,
    private val witnessBegin: () -> Unit = {},
    private val start: () -> Unit,
) {
    private var started = false

    /** Starts the native sources now unless they are held for [begin]. */
    fun enable() {
        if (!held) begin()
    }

    /** Starts the native sources, answering whether this call was the one that did. */
    fun begin(): Boolean {
        if (started) return false
        started = true
        if (held) witnessBegin()
        start()
        return true
    }

    companion object {
        /** A latch held or not by [NATIVE_COGNITION_ENV] in [environment]; see [heldBy]. */
        fun fromEnvironment(
            environment: Map<String, String> = System.getenv(),
            witnessBegin: () -> Unit = {},
            start: () -> Unit,
        ): NativeCognitionStart = NativeCognitionStart(heldBy(environment[NATIVE_COGNITION_ENV]), witnessBegin, start)

        /**
         * Whether [value] holds native cognition until `/loombody begin`.
         *
         * Unset is `immediate`. Anything else that is not one of the two words stops the
         * plugin, because a misspelt `held` that silently started at once would let a
         * research run's first perceptions predate its initial conditions.
         */
        fun heldBy(value: String?): Boolean =
            when (value?.trim()) {
                null, "", "immediate" -> false
                "held" -> true
                else -> throw IllegalArgumentException("$NATIVE_COGNITION_ENV must be immediate or held")
            }
    }
}
