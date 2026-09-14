/*
 * Two vitals through one watch: which reading reaches the log, and under which name.
 *
 * This file owns the routing and nothing else. `AgentVitalsTest` owns one `EnergyWatch`
 * in isolation and `BodyEventLogTest` owns the wire format, so neither of them ever puts
 * two readings through a single `VitalsWatch` - and that is exactly where a wrong label
 * would be silent, because both events are spelled correctly either way round.
 *
 * The readings here are on the two different scales the port really uses, a reserve out of
 * a hundred and health out of twenty, so a record handed the wrong vital cannot pass by
 * looking plausible.
 *
 * Engine-free, like the concern it tests: a fake log and a tick this file sets, no server.
 */
package dev.loom.body.nms

import dev.loom.body.AgentId
import dev.loom.body.AgentVitals
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventJournal
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.VitalsWatch
import dev.loom.body.reserveVitals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** A body of the flavor Loom runs today, unhurt: twenty hit points of twenty. */
private val UNHURT = AgentVitals(energy = 20.0, capacity = 20.0)

/** A full reserve, on the percentage scale the port records it on. */
private val FULL_RESERVE = reserveVitals(1.0)

class VitalsWatchTest {
    private val agent = AgentId("agent-ada")
    private val records = mutableListOf<BodyEventRecord>()
    private var tick = 0L
    private val watch =
        VitalsWatch(BodyEventJournal(BodyEventLog { records.add(it) }, currentTick = { tick }))

    /** The one record this agent's vitals produced, which the test expects there to be one of. */
    private fun theOnlyRecord(): BodyEventRecord {
        assertEquals(1, records.size, "$records")
        return records.first()
    }

    @Test
    @DisplayName("each vital is recorded under its own name, carrying both readings the right way round")
    fun bothReadingsRideEveryRecordInTheirOwnFields() {
        watch.noted(agent, FULL_RESERVE, UNHURT)
        val spent = reserveVitals(0.97)
        val hurt = AgentVitals(energy = 16.0, capacity = 20.0)

        watch.recordChanges(agent, spent, hurt)

        // Which vital moved is the event's name; what the other stood at is what makes a
        // record readable alone. Feeding either record the wrong reading is a run that
        // cannot say what ended an agent, and the two scales are what catch it.
        assertEquals(listOf(BodyEvent.ENERGY_CHANGED, BodyEvent.HEALTH_CHANGED), records.map { it.event })
        for (record in records) {
            assertEquals(spent, record.vitals, "${record.event} must carry the reserve in vitals")
            assertEquals(hurt, record.health, "${record.event} must carry the body's health in health")
        }
        // The human-readable half names the vital it was rendered from, so a swap shows up
        // here as well as in the fields.
        assertEquals("energy 97 of 100", records.first().detail)
        assertEquals("health 16 of 20", records.last().detail)
    }

    @Test
    @DisplayName("a wound is recorded on the tick it is read, on a reserve that has barely moved")
    fun aWoundDoesNotWaitForTheReserveToStep() {
        watch.noted(agent, FULL_RESERVE, UNHURT)
        tick = 1L

        // The in-world case the two watches exist for: the reserve drains on a half-hour
        // ramp, so a tenth of a percent of it is nothing, while health drops a whole point
        // in one hit. Sharing one step function let the wound wait for the reserve's next
        // step, which a run measured at up to eighteen seconds.
        watch.recordChanges(agent, reserveVitals(0.999), AgentVitals(energy = 19.0, capacity = 20.0))

        val wound = theOnlyRecord()
        assertEquals(BodyEvent.HEALTH_CHANGED, wound.event)
        assertEquals(AgentVitals(energy = 19.0, capacity = 20.0), wound.health)
        assertEquals(reserveVitals(0.999), wound.vitals, "the reserve rides along at what it actually was")
        assertEquals(1L, wound.tick, "a wound reported a tick later than it was read is a wound off the screen")
    }

    @Test
    @DisplayName("a perception states the reserve only, and does not swallow a wound with it")
    fun aReserveOnlyReadingLeavesTheHealthWatchAlone() {
        watch.noted(agent, FULL_RESERVE, UNHURT)
        val spent = reserveVitals(0.99)
        // What a perception does: its record carries the reserve it handed the mind and no
        // health reading at all. Taking an unstated health reading as recorded here is how
        // the wound below would be dropped instead of reported on the next tick.
        watch.noted(agent, spent)

        watch.recordChanges(agent, spent, AgentVitals(energy = 12.0, capacity = 20.0))

        val wound = theOnlyRecord()
        assertEquals(BodyEvent.HEALTH_CHANGED, wound.event, "the wound must still reach the log")
        assertEquals(AgentVitals(energy = 12.0, capacity = 20.0), wound.health)
        // And the reserve the perception already stated is not restated a tick later.
        assertEquals(spent, wound.vitals)
    }

    @Test
    @DisplayName("forgetting an agent forgets both vitals, so a new body's readings are first readings")
    fun forgettingIsAlwaysBothVitalsAtOnce() {
        watch.noted(agent, FULL_RESERVE, UNHURT)

        watch.forget(agent)

        // An agent left in one watch and not the other would have that vital compared
        // against a reading from a body it no longer has, so both unchanged readings are
        // first readings again and both are recorded.
        watch.recordChanges(agent, FULL_RESERVE, UNHURT)

        assertEquals(listOf(BodyEvent.ENERGY_CHANGED, BodyEvent.HEALTH_CHANGED), records.map { it.event })
    }

    @Test
    @DisplayName("a tick that moved neither vital far enough writes nothing at all")
    fun anUnchangedTickIsSilent() {
        watch.noted(agent, FULL_RESERVE, UNHURT)

        watch.recordChanges(agent, reserveVitals(0.999), UNHURT)

        // Twenty ticks a second per agent: the step function is what keeps the research
        // record readable, and it has to hold for both vitals at once.
        assertEquals(emptyList<BodyEventRecord>(), records)
    }
}
