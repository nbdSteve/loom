/*
 * What eating leaves in the research record.
 *
 * `EatingTest` owns the rule - which meal is taken and which of the six refusals stops it -
 * and this owns the join a run's numbers are read from. A refused meal whose reason landed
 * in the prose instead of in `outcome` would be counted by nothing, and the six refusals
 * would collapse into one uncountable "the body refused it": a run could then not say
 * whether its agents starved holding food, were named the wrong slot, or ate to a standstill
 * and wasted the rest.
 *
 * So this files what the rule returns through the real journal into a real append-only file
 * and reads the file back as records. The one line that calls the journal is
 * `NmsEntityBodyPort.apply`, which needs a live server; what is pinned here is what that
 * call writes, from the same `refusedMeal` and `tagOf` the adapter uses.
 */
package dev.loom.body.nms

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventJournal
import dev.loom.body.BodyEventRecord
import dev.loom.body.EAT_REFUSALS
import dev.loom.body.EventTag
import dev.loom.body.FileBodyEventLog
import dev.loom.body.IntentOutcome
import dev.loom.body.describe
import dev.loom.body.refusedMeal
import dev.loom.body.tagOf
import dev.loom.contract.Intent
import dev.loom.contract.LoomJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.readLines

/** The handle the perception issued for the stack, as a decision names it back. */
private const val SLOT_REF = "i7"

/** What a quarter of a shipped reserve is worth, as an accepted meal states it. */
private const val BREAD_TICKS = 9_000L

class EatRecordTest {
    private val agent = AgentId("agent-hungry")
    private var tick = 0L
    private val path = Files.createTempDirectory("loom-eat-record").resolve("body-events.jsonl")
    private val writeFailures = mutableListOf<String>()
    private val log =
        FileBodyEventLog(
            path,
            onWriteFailure = { writeFailures += it.message.orEmpty() },
            onRecordsDropped = { },
        )
    private val journal = BodyEventJournal(log, currentTick = { tick })

    @Test
    @DisplayName("every way a meal is refused is its own countable line, beside the meals that were eaten")
    fun everyRefusalReachesTheRecordNamingItself() {
        for (why in EAT_REFUSALS) {
            file(refusedMeal(why))
        }
        file(IntentOutcome.Accepted("ate one Bread, worth $BREAD_TICKS ticks of reserve"))

        val records = recordsWritten()
        assertEquals(emptyList<String>(), writeFailures, "no record may fail to reach the file")
        assertEquals(
            List(records.size) { BodyEvent.INTENT_APPLIED },
            records.map { it.event },
            "an applied intent is one record, so a refused meal must not invent a second one: $records",
        )
        assertEquals(
            List(records.size) { EventTag.EAT },
            records.map { it.intent },
            "every one of them is countable as a meal before it is countable as a refusal",
        )
        assertEquals(
            EAT_REFUSALS.map(::tagOf) + EventTag.ACCEPTED,
            records.map { it.outcome },
            "the reason has to be a field rather than prose: read from `outcome`, a run whose " +
                "agents ran out of food and one whose agents were named stale slots are different " +
                "findings, and both are different from a run whose agents ate",
        )
        for (record in records) {
            assertEquals("req-1", record.requestId, "a meal joins the perception that offered the stack")
        }
    }

    @Test
    @DisplayName("a refused meal states which stack it was about")
    fun theRecordNamesTheStackTheDecisionAsked() {
        file(refusedMeal(EAT_REFUSALS.first()))

        val record = recordsWritten().single()
        assertTrue(
            record.detail.contains(SLOT_REF),
            "a refusal that does not name the handle joins nothing: '${record.detail}'",
        )
        assertTrue(
            record.detail.contains(describe(EAT_REFUSALS.first())),
            "and the prose is what a person reads: '${record.detail}'",
        )
    }

    @Test
    @DisplayName("every line of the record is one replayable json record")
    fun theFileIsReadableBackAsRecords() {
        file(refusedMeal(EAT_REFUSALS.first()))
        tick += 1
        file(IntentOutcome.Accepted("ate one Bread, worth $BREAD_TICKS ticks of reserve"))

        val lines = closedFileLines()
        assertEquals(2, lines.size, "one refusal and one meal: $lines")
        for (line in lines) {
            assertTrue(line.none { it == '\n' }, "a record must occupy exactly one line")
            assertEquals(line, LoomJson.encodeToString(LoomJson.decodeFromString<BodyEventRecord>(line)))
        }
    }

    /** One applied meal, filed exactly as the port files it. */
    private fun file(outcome: IntentOutcome) {
        val intent = Intent.Eat(SLOT_REF)
        journal.recordAction(
            agent,
            "${describe(intent)} -> ${describe(outcome)}",
            requestId = "req-1",
            intent = tagOf(intent),
            outcome = tagOf(outcome),
        )
        tick += 1
    }

    private fun recordsWritten(): List<BodyEventRecord> =
        closedFileLines().map { LoomJson.decodeFromString<BodyEventRecord>(it) }

    private fun closedFileLines(): List<String> {
        // Closing drains the writer thread, which is what makes the file complete to read.
        log.close()
        return path.readLines()
    }
}
