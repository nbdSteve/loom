/*
 * The survival number, and the step function that keeps it out of the log's way.
 *
 * Two things are pinned here because both are silent when wrong. A trend invented for a
 * first reading would make every spawn look like an emergency, and a step that let every
 * sample through would bury a run's own record under twenty lines a second per agent.
 */
package dev.loom.body

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A full body of the flavor Loom runs today: twenty units, all of them there. */
private val FULL = AgentVitals(energy = 20.0, capacity = 20.0)

class AgentVitalsTest {
    private val agent = AgentId("agent-ada")

    @Test
    @DisplayName("a reading cannot claim a capacity of nothing or a negative energy")
    fun readingsAreGuarded() {
        assertThrows<IllegalArgumentException> { AgentVitals(energy = 1.0, capacity = 0.0) }
        assertThrows<IllegalArgumentException> { AgentVitals(energy = -1.0, capacity = 20.0) }
    }

    @Test
    @DisplayName("the fraction is clamped, so a gauge can never overrun or run backwards")
    fun theFractionStaysInsideAGauge() {
        assertEquals(0.5, AgentVitals(energy = 10.0, capacity = 20.0).fraction)
        // Absorption and regeneration overshoot in Minecraft; a bar that went past its
        // own end would render as a bar that had wrapped around to empty.
        assertEquals(1.0, AgentVitals(energy = 30.0, capacity = 20.0).fraction)
        assertEquals(0.0, AgentVitals(energy = 0.0, capacity = 20.0).fraction)
    }

    @Test
    @DisplayName("exhaustion is nothing left, and nothing less than that")
    fun exhaustionIsTerminal() {
        assertTrue(AgentVitals(energy = 0.0, capacity = 20.0).isExhausted)
        assertFalse(AgentVitals(energy = 0.5, capacity = 20.0).isExhausted, "half a heart is still alive")
    }

    @Test
    @DisplayName("a first reading has no trend: a newly-watched agent is not a dying one")
    fun aFirstReadingIsSteady() {
        assertEquals(EnergyTrend.STEADY, trendOf(previous = null, current = 1.0))
        assertEquals(EnergyTrend.STEADY, trendOf(previous = 20.0, current = 20.0))
        assertEquals(EnergyTrend.RECOVERING, trendOf(previous = 16.0, current = 17.0))
        assertEquals(EnergyTrend.DECLINING, trendOf(previous = 17.0, current = 16.0))
    }

    @Test
    @DisplayName("the human-readable half names the vital and both numbers, since none means anything alone")
    fun describeNamesTheVitalAndTheCapacity() {
        assertEquals("energy 16 of 20", describe(AgentVitals(energy = 16.0, capacity = 20.0), "energy"))
        // The same shape carries either vital, so the caller names which one it read: a
        // health record whose detail said "energy" would be exactly the aliasing of the two
        // that the separate reserve and health fields exist to prevent.
        assertEquals("health 12 of 20", describe(AgentVitals(energy = 12.0, capacity = 20.0), "health"))
    }

    @Test
    @DisplayName("a reserve that does not divide is still readable by eye")
    fun anAmountIsRoundedForAReader() {
        // A reserve is counted in ticks and reported as a percentage, so most readings
        // repeat: this one is what an in-world run wrote into a record's detail as
        // `98.76666666666667`. The exact number stays in the record's own vitals field,
        // where anything counting it looks.
        assertEquals("98.8", amountOf(98.76666666666667))
        assertEquals("100", amountOf(100.0))
        assertEquals("16.5", amountOf(16.5))
    }

    @Test
    @DisplayName("a first sample always counts: a run needs to know where an agent started")
    fun theFirstSampleIsAlwaysRecorded() {
        val watch = EnergyWatch()

        assertEquals(FULL, watch.sample(agent, FULL))
        assertNull(watch.sample(agent, FULL), "an unchanged reading is not news")
    }

    @Test
    @DisplayName("a change smaller than the step is not recorded, and a drift across it is")
    fun theStepIsAStepFunctionAndNotAFilter() {
        val watch = EnergyWatch(step = 1.0)
        watch.sample(agent, FULL)

        assertNull(watch.sample(agent, AgentVitals(energy = 19.5, capacity = 20.0)))
        // Compared against the last *recorded* reading rather than the last sampled one,
        // so a slow decline still crosses the step instead of vanishing into a series of
        // individually-negligible changes.
        assertEquals(
            AgentVitals(energy = 19.0, capacity = 20.0),
            watch.sample(agent, AgentVitals(energy = 19.0, capacity = 20.0)),
        )
    }

    @Test
    @DisplayName("exhaustion is recorded whatever the step, because that reading is the death")
    fun exhaustionOutranksTheStep() {
        val watch = EnergyWatch(step = 100.0)
        watch.sample(agent, FULL)

        assertNotNull(watch.sample(agent, AgentVitals(energy = 0.0, capacity = 20.0)))
    }

    @Test
    @DisplayName("a reading some other record carried is not restated a tick later")
    fun anAlreadyRecordedReadingIsNotDuplicated() {
        val watch = EnergyWatch()
        // What a spawn does: the spawn record carries the reading, so the first tick
        // must not open the log with the same number again.
        watch.noted(agent, FULL)

        assertNull(watch.sample(agent, FULL))
    }

    @Test
    @DisplayName("forgetting an agent makes its next body's first reading a first reading again")
    fun forgettingResetsTheComparison() {
        val watch = EnergyWatch()
        watch.sample(agent, AgentVitals(energy = 3.0, capacity = 20.0))

        watch.forget(agent)

        // Not merely bookkeeping: a reading compared against one from a body the agent
        // no longer has would report a trend that never happened.
        assertEquals(
            AgentVitals(energy = 3.0, capacity = 20.0),
            watch.sample(agent, AgentVitals(energy = 3.0, capacity = 20.0)),
        )
    }

    @Test
    @DisplayName("a step of nothing is refused, since it would record every sample")
    fun theStepMustBePositive() {
        assertThrows<IllegalArgumentException> { EnergyWatch(step = 0.0) }
    }
}
