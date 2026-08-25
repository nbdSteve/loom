/*
 * The world clock, as the mind is told it.
 *
 * The engine owns the number - a level's day time, in ticks - and this owns what it
 * means. It lives in the domain rather than in the adapter for the same reason
 * `PerceptionBound` does: the boundaries between day, dusk, night and dawn are a rule
 * about what an agent is told, not about any engine, and a rule with boundaries in it
 * is a rule worth testing without a server.
 *
 * The phase boundaries are vanilla's own: dusk begins at 12000, full night at 13800,
 * sunrise at 22200, and the day wraps at 24000. They are named here rather than
 * inlined because two of them are also what nightfall is measured against, and a
 * boundary spelled twice is a boundary that can be spelled wrong once.
 */
package dev.loom.body

import dev.loom.contract.DayPhase

/**
 * Reads a level's day time as the phase and countdown the contract carries.
 *
 * Every function here is total over the whole of `Long`: the day time comes off a
 * level Loom does not control, a `/time set` can put it anywhere, and a world that has
 * run for long enough has a day time far larger than one day.
 */
object WorldClock {
    /** Ticks in one full world day. */
    const val TICKS_PER_DAY: Long = 24_000

    /** The day time the sun starts to set at. */
    const val DUSK_START: Long = 12_000

    /**
     * The day time full night begins at, and therefore what "nightfall" means.
     *
     * Dusk is the warning and this is the event, which is why the countdown is measured
     * to here rather than to [DUSK_START]: an agent told it had no time left the moment
     * the light started to change would be told the day was over while it could still
     * see.
     */
    const val NIGHT_START: Long = 13_800

    /** The day time the sun starts to rise at. */
    const val DAWN_START: Long = 22_200

    /** Where in its day the world is, in `0 until TICKS_PER_DAY`. */
    fun timeOfDay(dayTime: Long): Long = dayTime.mod(TICKS_PER_DAY)

    /** Which part of the day [dayTime] falls in. */
    fun phaseOf(dayTime: Long): DayPhase =
        when (timeOfDay(dayTime)) {
            in 0 until DUSK_START -> DayPhase.DAY
            in DUSK_START until NIGHT_START -> DayPhase.DUSK
            in NIGHT_START until DAWN_START -> DayPhase.NIGHT
            else -> DayPhase.DAWN
        }

    /**
     * Ticks from [dayTime] until the next [NIGHT_START], wrapping the day.
     *
     * Never zero and never negative, so the contract's own bound holds: at the very
     * tick night falls the answer is a whole day, which with a phase of `night` beside
     * it reads as "it is night, and the next one is a day away". A zero would instead
     * read as a nightfall that is permanently imminent for the whole of the night.
     */
    fun ticksUntilNightfall(dayTime: Long): Long {
        val remaining = (NIGHT_START - timeOfDay(dayTime)).mod(TICKS_PER_DAY)
        return if (remaining == 0L) TICKS_PER_DAY else remaining
    }
}
