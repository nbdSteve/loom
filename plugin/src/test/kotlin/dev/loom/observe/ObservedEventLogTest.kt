/*
 * The seam, tested for the two ways a display could damage the run it is watching.
 *
 * Both are ordering or containment properties rather than behaviour, and both are silent
 * when broken: a research record short one line reads as an event that never happened,
 * and an exception raised from a display inside a body tick reads as a body that stopped
 * working. So the order is asserted as an order, and an observer that fails on purpose is
 * driven through the seam to prove the run survives it.
 */
package dev.loom.observe

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ObservedEventLogTest {
    /** What the real log took, and in which order relative to the display. */
    private val order = mutableListOf<String>()
    private val written = mutableListOf<BodyEventRecord>()
    private val failures = mutableListOf<Throwable>()

    private val log =
        BodyEventLog { record ->
            order.add("logged ${record.event}")
            written.add(record)
        }

    @Test
    @DisplayName("every record the run produces still reaches the real log, unchanged")
    fun theRecordIsTheSameRecord() {
        val observed = ObservedEventLog(log, {}, failures::add)
        val original = record(BodyEvent.DIED, detail = "killed by a zombie")

        observed.append(original)

        // Identity, not equality: the display is handed the record the log took, and a
        // seam that copied or rebuilt it could quietly change what a run recorded.
        assertSame(original, written.single())
    }

    @Test
    @DisplayName("the research record is written before any display is offered the line")
    fun theLogIsWrittenFirst() {
        val watching = Observatory()
        val observed = ObservedEventLog(log, watching::saw, failures::add)
        watching.watch(AgentId(WATCHED), OrderedSink(order))

        observed.append(record(BodyEvent.SPAWNED))
        watching.refresh(0L)

        // An observer must never be able to cost the record a line, so the durable write
        // happens before anything a display can fail at.
        assertEquals(listOf("logged SPAWNED", "noted", "shown"), order)
    }

    @Test
    @DisplayName("a display that throws is reported, and the run carries on regardless")
    fun aBrokenDisplayCannotBreakARun() {
        val observed =
            ObservedEventLog(
                log,
                { throw IllegalStateException("display bug") },
                failures::add,
            )

        observed.append(record(BodyEvent.MOVED))
        observed.append(record(BodyEvent.DIED))

        // Both records are in the log: observation is a convenience and the run is the
        // point, so a display bug becomes a reported failure rather than an exception in
        // the middle of a body tick or a cognition callback.
        assertEquals(listOf(BodyEvent.MOVED, BodyEvent.DIED), written.map { it.event })
        assertEquals(2, failures.size)
        assertTrue(failures.all { it is IllegalStateException }, "$failures")
    }
}

/** A sink that records only *when* it was touched, into a log shared with the event log. */
private class OrderedSink(
    private val order: MutableList<String>,
) : ObserverSink {
    override val observer = OPERATOR
    override val isPresent = true

    override fun show(overlay: Overlay) {
        order.add("shown")
    }

    override fun note(note: Note) {
        order.add("noted")
    }

    override fun release() = Unit
}
