/*
 * What a night of being hunted leaves in the research record.
 *
 * `ThreatWatchTest` owns the rule - which sighting fires and which bound refuses - and
 * `BodyEventLogTest` owns the wire format one record is written in. Neither of them puts a
 * refusal the rule produced through the journal that files it, and that is the join a run's
 * numbers are read from: a suppression whose bound landed in the prose instead of in
 * `outcome` would be counted by nothing, and the three bounds would collapse into one
 * uncountable "did not report" for anybody asking why an agent stood still while something
 * hunted it.
 *
 * So this drives the real rule over a scene that trips all three bounds, files what it
 * returns through the real journal into a real append-only file, and reads the file back as
 * records. The scan that calls the two journal methods is `NmsEntityBodyPort.senseThreat`,
 * which needs a live server; what is pinned here is what those two methods write.
 *
 * The clock is moved by hand, so a debounce window and a night are boundaries rather than
 * waits, and the same scene always leaves the same file.
 */
package dev.loom.body.nms

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventRecord
import dev.loom.body.EventTag
import dev.loom.body.FileBodyEventLog
import dev.loom.body.MAX_PIVOTAL_THREAT_CALLS_PER_NIGHT
import dev.loom.body.THREAT_DEBOUNCE_TICKS
import dev.loom.body.ThreatCandidate
import dev.loom.body.ThreatClock
import dev.loom.body.ThreatWatch
import dev.loom.body.tagOf
import dev.loom.contract.Bearing
import dev.loom.contract.LoomJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.readLines

/** The engine's own description of the hunter, which is what the record names. */
private const val ZOMBIE = "entity.minecraft.zombie"

/** Inside [dev.loom.body.PIVOTAL_THREAT_DISTANCE_BLOCKS], so every sighting here is pivotal. */
private const val CLOSE_BLOCKS: Double = 6.0

/** A reserve barely spent, so no reading in the file is mistaken for a starvation. */
private const val RESERVE_LEFT: Double = 0.9

class ThreatRecordTest {
    private val agent = AgentId("agent-hunted")
    private val clock = MovedByHand()
    private val watch = ThreatWatch(clock)
    private val path = Files.createTempDirectory("loom-threat-record").resolve("body-events.jsonl")
    private val writeFailures = mutableListOf<String>()
    private val log =
        FileBodyEventLog(
            path,
            onWriteFailure = { writeFailures += it.message.orEmpty() },
            onRecordsDropped = { },
        )
    private val journal = BodyEventJournal(log, currentTick = { clock.now })

    @Test
    @DisplayName("each bound that refused a sighting is its own countable line, beside the sightings that fired")
    fun everyRefusalReachesTheRecordNamingTheBoundThatRefusedIt() {
        // The first sight of something hunting the agent: reported at once, which is the
        // whole point - a goal review would have been up to a cadence away.
        look(zombie("z1"))
        // Still standing there, inside the window. The clock refuses.
        clock.now += THREAT_DEBOUNCE_TICKS - 1
        look(zombie("z1"))
        // The window has elapsed and nothing about it has changed. The news refuses, and
        // that is a different fact from the clock refusing.
        clock.now += 1
        look(zombie("z1"))
        // Seven more, each a stranger and so each a first sight the debounce cannot refuse:
        // between them and the first they spend the whole of this agent's night.
        repeat(MAX_PIVOTAL_THREAT_CALLS_PER_NIGHT - 1) { met ->
            clock.now += THREAT_DEBOUNCE_TICKS
            look(zombie("n$met"))
        }
        // And one more, on a spent allowance. Loom stops paying, and says so.
        clock.now += THREAT_DEBOUNCE_TICKS
        look(zombie("z9"))

        val records = recordsWritten()
        assertEquals(emptyList<String>(), writeFailures, "no record may fail to reach the file")
        assertEquals(
            setOf(BodyEvent.PERCEIVED, BodyEvent.THREAT_SUPPRESSED),
            records.map { it.event }.toSet(),
            "a scan writes a perception when it earned one and a suppression when it did not, " +
                "and nothing else: $records",
        )

        val refused = records.filter { it.event == BodyEvent.THREAT_SUPPRESSED }
        assertEquals(
            listOf(EventTag.THREAT_DEBOUNCED, EventTag.THREAT_UNCHANGED, EventTag.THREAT_OVER_NIGHTLY_CAP),
            refused.map { it.outcome },
            "the bound that refused has to be a field rather than prose: read from `outcome`, a run " +
                "dominated by the debounce is healthy and one dominated by the nightly cap is a run " +
                "whose budget shaped its own findings",
        )
        assertEquals(
            listOf("z1", "z1", "z9"),
            refused.map { refusalRef(it) },
            "and each names the handle the perception that saw it issued, or a suppression joins " +
                "nothing: $refused",
        )
        assertEquals(0, watch.pivotalCallsRemaining(agent), "the night was spent, which is why the last was refused")

        val reported = records.filter { it.event == BodyEvent.PERCEIVED }
        assertEquals(
            MAX_PIVOTAL_THREAT_CALLS_PER_NIGHT,
            reported.size,
            "the allowance is what bounds the sightings, so exactly the night's worth reached a mind",
        )
        for (perception in reported) {
            assertEquals(
                EventTag.THREAT_SIGHTED,
                perception.trigger,
                "a sighting must be countable apart from the scheduled review that used to be the " +
                    "only thing that woke a mind",
            )
        }
    }

    @Test
    @DisplayName("every line of the record is one replayable json record")
    fun theFileIsReadableBackAsRecords() {
        look(zombie("z1"))
        clock.now += 1
        look(zombie("z1"))

        // The file is the research record, so a line has to come back out as the record that
        // went in - fields and all - rather than as text to be re-parsed by whoever counts it.
        val lines = closedFileLines()
        assertEquals(2, lines.size, "one sighting and one refusal: $lines")
        for (line in lines) {
            assertTrue(line.none { it == '\n' }, "a record must occupy exactly one line")
            assertEquals(line, LoomJson.encodeToString(LoomJson.decodeFromString<BodyEventRecord>(line)))
        }
    }

    /**
     * One look around, filed exactly as a scan files it: every refusal, then any sighting.
     *
     * The perception record is the ordinary one any perception gets, because a sighting
     * recorded some other way would make a run's perceptions uncountable.
     */
    private fun look(vararg inView: ThreatCandidate) {
        val outcome = watch.consider(agent, inView.toList()) { true }
        for (suppression in outcome.suppressed) {
            journal.recordSuppressedThreat(agent, suppression)
        }
        outcome.sighted?.let { sighting ->
            journal.recordPerception(
                agent,
                "${inView.size} observation(s)",
                requestId = "req-${clock.now}",
                trigger = tagOf(sighting),
                vitals = reserveVitals(RESERVE_LEFT),
            )
        }
    }

    /** The handle a suppression named, as the record carries it. */
    private fun refusalRef(record: BodyEventRecord): String =
        record.detail.substringAfter("in view as ", missingDelimiterValue = "")
            .substringBefore(",")

    private fun recordsWritten(): List<BodyEventRecord> =
        closedFileLines().map { LoomJson.decodeFromString<BodyEventRecord>(it) }

    private fun closedFileLines(): List<String> {
        // Closing drains the writer thread, which is what makes the file complete to read.
        log.close()
        return path.readLines()
    }
}

/** The tick and the day, both moved by this file rather than by a world. */
private class MovedByHand(
    var now: Long = 0L,
    var day: Long = 0L,
) : ThreatClock {
    override fun tick(): Long = now

    override fun dayOrdinal(): Long = day
}

/**
 * One hostile in view, close enough that its sighting is worth the frontier tier.
 *
 * The subject is derived from [ref] rather than random, because two threats are told apart
 * by that field alone and a failure here has to be reproducible from the scene it failed in.
 */
private fun zombie(ref: String): ThreatCandidate =
    ThreatCandidate(
        subject = UUID.nameUUIDFromBytes(ref.toByteArray()),
        what = ZOMBIE,
        ref = ref,
        distance = CLOSE_BLOCKS,
        direction = Bearing.NORTH,
        visible = true,
    )
