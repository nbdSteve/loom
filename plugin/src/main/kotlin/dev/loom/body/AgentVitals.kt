/*
 * A survival reading an agent has: a level, the capacity it is measured against, and
 * what the level is doing.
 *
 * In the domain rather than in a body flavor because a reading is a rule of the world
 * Loom is running, not a property of one way of having a body: every flavor reports the
 * same shape, so a run's survival curves stay comparable across flavors.
 *
 * WHAT THIS MEASURES. It is the shape a vital is reported in, not one particular vital.
 * Loom now has two: the energy reserve, which living spends and only resting refills, and
 * the body's health. The reserve is what a survival curve is drawn from and what an
 * unattended agent starves on; health is the other way a body can end, beaten down on a
 * full reserve. Both are read as this same [AgentVitals], so the log, the step function,
 * and the operator display work on either without having to know which they were handed.
 */
package dev.loom.body

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

/** Energy is reported as a fraction of capacity; this is the whole of it. */
private const val WHOLE = 1.0

/** Vitals are rendered to a tenth of a unit, wherever a person rather than a metric reads them. */
private const val TENTHS = 10.0

/**
 * How far energy must move before the change is worth a durable record.
 *
 * Energy is a continuous quantity sampled every tick, so without a step every
 * regenerating agent would write twenty records a second and drown the research
 * record it is supposed to feed. Half a heart is the smallest change a viewer can
 * see, which makes it the smallest change worth recording.
 */
const val ENERGY_RECORD_STEP: Double = 1.0

/**
 * An agent's energy, and the capacity it is measured against.
 *
 * Both halves travel together because neither means anything alone: "16" is only
 * legible next to "of 20", and a flavor whose capacity differs would otherwise make
 * two runs' numbers silently incomparable.
 */
@Serializable
data class AgentVitals(
    /**
     * How much of the vital is left, in the same units as [capacity]. Never negative.
     *
     * On the wire it is `level`, not `energy`, because this shape reports either vital:
     * a health reading read back as `health.energy` would be exactly the name-level
     * aliasing of the reserve and the body's health that the two-vitals split exists to
     * prevent. What vital a reading is of is the field it rides in on the record.
     */
    @SerialName("level")
    val energy: Double,
    /** The most energy this body can hold. Always positive. */
    val capacity: Double,
) {
    init {
        require(capacity > 0.0) { "capacity must be positive, was $capacity" }
        require(energy >= 0.0) { "energy cannot be negative, was $energy" }
    }

    /** Energy as a fraction of capacity, clamped so a gauge never overruns. */
    val fraction: Double get() = (energy / capacity).coerceIn(0.0, WHOLE)

    /** True once the agent has nothing left. In Loom that is death, and terminal. */
    val isExhausted: Boolean get() = energy <= 0.0
}

/**
 * Renders [vitals] for a record's detail, as a reading of the vital called [vital].
 *
 * The reading is in the record's own `vitals` or `health` field, where anything counting
 * it will look; this is the human-readable half, for whoever is reading a run back by eye.
 * The vital is named by the caller because [AgentVitals] is the shape a reading comes in
 * and not one particular vital: a line reading `energy 12 of 20` on a health record would
 * be the aliasing the two-vitals split exists to prevent.
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
 * on - a Minecraft half-heart is half a unit - and because a reserve counted in ticks
 * and reported as a percentage does not divide: an unrounded one reads
 * `98.76666666666667` in a line written for an eye.
 */
fun amountOf(value: Double): String {
    val rounded = (value * TENTHS).roundToInt() / TENTHS
    return if (rounded == rounded.toInt().toDouble()) rounded.toInt().toString() else rounded.toString()
}

/** What an agent's energy has been doing since it was last looked at. */
enum class EnergyTrend {
    /** Energy is coming back: the agent is recovering. */
    RECOVERING,

    /** Energy has not moved. */
    STEADY,

    /** Energy is being spent or taken: the agent is in trouble. */
    DECLINING,
}

/**
 * The trend from [previous] energy to [current].
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
 * Decides when an agent's energy has moved enough to deserve a record.
 *
 * Engine-free and stateful by design: it remembers the last reading it let through
 * per agent, so what reaches the log is a step function rather than a per-tick
 * stream. The last *recorded* reading is what it compares against, not the last
 * sampled one, so a slow drift still eventually crosses the step instead of being
 * lost to a series of individually-negligible changes.
 *
 * @param step how far energy must move to be worth recording.
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
     * started before any change to it means anything. Exhaustion always counts too,
     * whatever the step, because that reading is the agent's death.
     */
    fun sample(
        agentId: AgentId,
        vitals: AgentVitals,
    ): AgentVitals? {
        val last = recorded[agentId]
        val worthRecording = last == null || vitals.isExhausted || abs(vitals.energy - last) >= step
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
