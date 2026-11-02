/*
 * How near one agent's named threats stand and how fast they close, from its own senses, which is
 * what a flight's sprint is worth spending food on.
 */
package dev.loom.body

import java.util.UUID

/** A threat this near, in blocks, is fled at a sprint whatever its pace. */
const val SPRINT_NEAR_BLOCKS: Double = 8.0

/**
 * A threat closing at least this fast, in blocks per tick, is fled at a sprint wherever it stands:
 * three blocks a second, faster than a walking zombie and as fast as a spider's charge.
 */
const val SPRINT_CLOSING_BLOCKS_PER_TICK: Double = 0.15

/** Why a flight sprints or walks, as its `retreating` record states it (r34: the choice was in no record). */
object SprintReason {
    /** Within [SPRINT_NEAR_BLOCKS]: sprinted whatever its pace. */
    const val NEAR: String = "threatNear"

    /** Closing at [SPRINT_CLOSING_BLOCKS_PER_TICK] or more: sprinted wherever it stands. */
    const val CLOSING: String = "threatClosing"

    /** The sense that named it states no position for it: sprinted, as nothing says it is far. */
    const val UNLOCATED: String = "threatUnlocated"

    /** Neither near nor closing: walked, keeping the food a sprint would spend. */
    const val DISTANT: String = "threatDistant"
}

/** Whether one flight sprints, and the [SprintReason] that decided it. */
data class SprintChoice(
    val sprint: Boolean,
    val reason: String,
)

/** How far back a sample of a threat's distance still says how fast it is closing, in ticks. */
const val CLOSING_WINDOW_TICKS: Long = 100

/** The most samples kept per threat. */
private const val SAMPLES_PER_THREAT = 4

/**
 * Each named threat's recent distance from one agent, by server identity, newest last.
 *
 * Every flight sprinted in r33, and every agent's food fell below the vanilla healing threshold
 * of 18 with no saturation left: the agents spent hundreds of ticks at 0.8 to 4.8 health that a
 * walking escape from distant threats would have regenerated. A flight now sprints only from a
 * threat within [SPRINT_NEAR_BLOCKS], or one closing at [SPRINT_CLOSING_BLOCKS_PER_TICK] or more.
 */
class ThreatApproach {
    private val samples = mutableMapOf<UUID, ArrayDeque<Pair<Long, Double>>>()

    /** Records that [subject] stood [distance] blocks off at [tick]. */
    fun record(
        subject: UUID,
        tick: Long,
        distance: Double,
    ) {
        samples.entries.removeIf { (_, kept) -> kept.last().first < tick - CLOSING_WINDOW_TICKS }
        val kept = samples.getOrPut(subject) { ArrayDeque() }
        if (kept.lastOrNull()?.first == tick) kept.removeLast()
        kept.addLast(tick to distance)
        while (kept.size > SAMPLES_PER_THREAT) kept.removeFirst()
    }

    /** Whether a flight from [subject], [distance] blocks off at [tick], is worth a sprint. */
    fun sprints(
        subject: UUID,
        tick: Long,
        distance: Double,
    ): Boolean = choice(subject, tick, distance).sprint

    /** Whether a flight from [subject], [distance] blocks off at [tick], sprints, and why. */
    fun choice(
        subject: UUID,
        tick: Long,
        distance: Double,
    ): SprintChoice {
        val earlier =
            samples[subject].orEmpty().lastOrNull {
                    (at, _) ->
                at < tick && at >= tick - CLOSING_WINDOW_TICKS
            }
        val rate = earlier?.let { (at, was) -> (was - distance) / (tick - at) }
        val closing = rate != null && rate >= SPRINT_CLOSING_BLOCKS_PER_TICK
        return when {
            distance <= SPRINT_NEAR_BLOCKS -> SprintChoice(true, SprintReason.NEAR)
            closing -> SprintChoice(true, SprintReason.CLOSING)
            else -> SprintChoice(false, SprintReason.DISTANT)
        }
    }
}
