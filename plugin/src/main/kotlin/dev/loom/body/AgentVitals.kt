/*
 * A survival reading an agent has: a level, the capacity it is measured against, and
 * what the level is doing.
 *
 * The same shape records the player's vanilla food level and health, so evidence,
 * step functions, and the observer can operate on either reading.
 */
package dev.loom.body

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

/** A vital is reported as a fraction of capacity; this is the whole of it. */
private const val WHOLE = 1.0

/** Vitals are rendered to a tenth of a unit, wherever a person rather than a metric reads them. */
private const val TENTHS = 10.0

/**
 * How far a vital must move before the change is worth a durable record.
 *
 * Vitals are sampled repeatedly, so without a step every changing player would
 * write a stream of records and drown the research
 * record it is supposed to feed. Half a heart is the smallest change a viewer can
 * see, which makes it the smallest change worth recording.
 */
const val ENERGY_RECORD_STEP: Double = 1.0

/**
 * A vital's level and the capacity it is measured against.
 *
 * Both halves travel together because neither means anything alone: "16" is only
 * legible next to "of 20".
 */
@Serializable
data class AgentVitals(
    /**
     * How much of the vital is left, in the same units as [capacity]. Never negative.
     *
     * On the wire it is `level`, not `energy`, because this shape reports either vital:
     * a health reading read back as `health.energy` would be exactly the name-level
     * aliasing of food and health that the two-vitals split exists to prevent. What
     * vital a reading is of is the field it rides in on the record.
     */
    @SerialName("level")
    val energy: Double,
    /** The reading's capacity. Always positive. */
    val capacity: Double,
) {
    init {
        require(capacity > 0.0) { "capacity must be positive, was $capacity" }
        require(energy >= 0.0) { "energy cannot be negative, was $energy" }
    }

    /** The level as a fraction of capacity, clamped so a gauge never overruns. */
    val fraction: Double get() = (energy / capacity).coerceIn(0.0, WHOLE)
}

/**
 * Renders [vitals] for a record's detail, as a reading of the vital called [vital].
 *
 * The reading is in the record's own `vitals` or `health` field, where anything counting
 * it will look; this is the human-readable half, for whoever is reading a run back by eye.
 * The vital is named by the caller because [AgentVitals] is the shape a reading comes in
 * and not one particular vital.
 */
fun describe(
    vitals: AgentVitals,
    vital: String,
): String = "$vital ${amountOf(vitals.energy)} of ${amountOf(vitals.capacity)}"

/**
 * A vital's amount as a person reads it: to a tenth, and with no trailing `.0`.
 *
 * One rendering for the record's human half and for the operator display both, so the
 * two cannot drift apart. A tenth because that is the smallest change a viewer can act
 * on - a Minecraft half-heart is half a unit.
 */
fun amountOf(value: Double): String {
    val rounded = (value * TENTHS).roundToInt() / TENTHS
    return if (rounded == rounded.toInt().toDouble()) rounded.toInt().toString() else rounded.toString()
}

/** What a vital has been doing since it was last looked at. */
enum class EnergyTrend {
    /** The vital is rising. */
    RECOVERING,

    /** The vital has not moved. */
    STEADY,

    /** The vital is falling. */
    DECLINING,
}

/**
 * The trend from [previous] level to [current].
 *
 * A first reading has no trend, so `null` previous is [EnergyTrend.STEADY] rather
 * than an invented decline: an agent that has only just been looked at is not
 * dying, and a gauge that said so would make every spawn look like an emergency.
 */
fun trendOf(
    previous: Double?,
    current: Double,
): EnergyTrend =
    when {
        previous == null || current == previous -> EnergyTrend.STEADY
        current > previous -> EnergyTrend.RECOVERING
        else -> EnergyTrend.DECLINING
    }

/**
 * Decides when a vital has moved enough to deserve a record.
 *
 * Engine-free and stateful by design: it remembers the last reading it let through
 * per agent, so what reaches the log is a step function rather than a per-tick
 * stream. The last *recorded* reading is what it compares against, not the last
 * sampled one, so a slow drift still eventually crosses the step instead of being
 * lost to a series of individually-negligible changes.
 *
 * @param step how far the level must move to be worth recording.
 */
class EnergyWatch(
    private val step: Double = ENERGY_RECORD_STEP,
) {
    init {
        require(step > 0.0) { "step must be positive, was $step" }
    }

    private val recorded = mutableMapOf<AgentId, Double>()

    /**
     * Returns [vitals] when they are worth recording for [agentId], else `null`.
     *
     * An agent's first sample always counts: a run needs to know where an agent
     * started before any change to it means anything.
     */
    fun sample(
        agentId: AgentId,
        vitals: AgentVitals,
    ): AgentVitals? {
        val last = recorded[agentId]
        val worthRecording = last == null || abs(vitals.energy - last) >= step
        if (!worthRecording) {
            return null
        }
        noted(agentId, vitals)
        return vitals
    }

    /**
     * Takes [vitals] as already recorded for [agentId], without recording them again.
     *
     * For the readings some other record carried anyway - a spawn's, most of all. Without
     * it an agent's first tick would restate the energy its spawn record had just stated,
     * and every run's log would open with a duplicate.
     */
    fun noted(
        agentId: AgentId,
        vitals: AgentVitals,
    ) {
        recorded[agentId] = vitals.energy
    }

    /**
     * Forgets [agentId], so a later body for it starts from a first sample again.
     *
     * Called when a body leaves the world. Without it the map would grow for the
     * life of the server, and - worse for the record - an agent's first sample in a
     * new body would be compared against a reading from a body it no longer has.
     */
    fun forget(agentId: AgentId) {
        recorded.remove(agentId)
    }
}
