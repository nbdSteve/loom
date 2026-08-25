package dev.loom.body

import dev.loom.contract.DayPhase
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorldClockTest {
    @Test
    fun `midday and dusk are different phases`() {
        assertEquals(DayPhase.DAY, WorldClock.phaseOf(6_000))
        assertEquals(DayPhase.DUSK, WorldClock.phaseOf(13_000))
    }

    @Test
    fun `every boundary belongs to the phase it starts`() {
        assertEquals(DayPhase.DAY, WorldClock.phaseOf(0))
        assertEquals(DayPhase.DUSK, WorldClock.phaseOf(WorldClock.DUSK_START))
        assertEquals(DayPhase.NIGHT, WorldClock.phaseOf(WorldClock.NIGHT_START))
        assertEquals(DayPhase.DAWN, WorldClock.phaseOf(WorldClock.DAWN_START))
        // The last tick of the day is still dawn, and the wrap is a fresh day.
        assertEquals(DayPhase.DAWN, WorldClock.phaseOf(WorldClock.TICKS_PER_DAY - 1))
        assertEquals(DayPhase.DAY, WorldClock.phaseOf(WorldClock.TICKS_PER_DAY))
    }

    @Test
    fun `an afternoon counts down toward nightfall`() {
        val earlier = WorldClock.ticksUntilNightfall(6_000)
        val later = WorldClock.ticksUntilNightfall(9_000)
        assertTrue(later < earlier, "nightfall must get closer as the afternoon passes: $later !< $earlier")
        assertEquals(WorldClock.NIGHT_START - 9_000, later)
    }

    @Test
    fun `the countdown is never zero, so the night reads as a night`() {
        // At the very tick night falls, the *next* nightfall is a whole day away. Zero
        // would instead read as a nightfall permanently about to happen, for the whole
        // of the night; the phase is what says the night is already here.
        assertEquals(WorldClock.TICKS_PER_DAY, WorldClock.ticksUntilNightfall(WorldClock.NIGHT_START))
        assertEquals(DayPhase.NIGHT, WorldClock.phaseOf(WorldClock.NIGHT_START))
    }

    @Test
    fun `the clock is total over any day time a world can hold`() {
        // A long-running world's day time is far larger than one day, and `/time set`
        // can put it anywhere. Neither may produce a countdown the contract refuses.
        val times = listOf(-1L, 0L, 1L, 23_999L, 24_000L, 1_000_000L, Long.MAX_VALUE, Long.MIN_VALUE)
        for (dayTime in times) {
            val remaining = WorldClock.ticksUntilNightfall(dayTime)
            assertTrue(remaining in 1..WorldClock.TICKS_PER_DAY, "out of range at $dayTime: $remaining")
            assertTrue(WorldClock.timeOfDay(dayTime) in 0 until WorldClock.TICKS_PER_DAY, "bad time at $dayTime")
        }
    }
}
