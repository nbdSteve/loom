package dev.loom.body

import dev.loom.contract.DecisionImportance
import dev.loom.contract.LoomJson
import dev.loom.contract.Provenance
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.readLines
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wire name every event is written under.
 *
 * Kept as a map so the test below can check it against the enum itself: a variant
 * that reached the log without being named here would be a record no analysis of a
 * run knows to look for.
 */
private val WIRE_NAMES =
    mapOf(
        BodyEvent.SPAWNED to "spawned",
        BodyEvent.SPAWN_REJECTED to "spawnRejected",
        BodyEvent.INTENT_APPLIED to "intentApplied",
        BodyEvent.MOVED to "moved",
        BodyEvent.WORLD_INTERACTION to "worldInteraction",
        BodyEvent.PERCEIVED to "perceived",
        BodyEvent.THREAT_SUPPRESSED to "threatSuppressed",
        BodyEvent.RETREATING to "retreating",
        BodyEvent.DECIDED to "decided",
        BodyEvent.DECISION_DISCARDED to "decisionDiscarded",
        BodyEvent.COGNITION_UNAVAILABLE to "cognitionUnavailable",
        BodyEvent.ENERGY_CHANGED to "energyChanged",
        BodyEvent.HEALTH_CHANGED to "healthChanged",
        BodyEvent.BODY_DISCONNECTED to "bodyDisconnected",
        BodyEvent.DESPAWNED to "despawned",
        BodyEvent.DIED to "died",
        BodyEvent.WITNESS_JOINED to "witnessJoined",
        BodyEvent.WITNESS_QUIT to "witnessQuit",
        BodyEvent.WITNESS_DENIED to "witnessDenied",
        BodyEvent.WITNESS_REVIVED to "witnessRevived",
        BodyEvent.WITNESS_DISPUTED to "witnessDisputed",
    )

/**
 * The append-only log is where every body metric comes from, so its two
 * load-bearing properties are tested: each record is one replayable JSON line, and
 * the file is only ever appended to.
 */
class BodyEventLogTest {
    @Test
    fun `a record renders as one self-describing json line`() {
        val line =
            BodyEventRecord(
                tick = 1234,
                agentId = "agent-ada",
                event = BodyEvent.SPAWNED,
                detail = "at world 10.00,64.00,-3.00",
            ).toJsonLine()

        assertTrue(line.none { it == '\n' }, "a record must occupy exactly one line")
        assertEquals(
            """{"tick":1234,"agentId":"agent-ada","event":"spawned","detail":"at world 10.00,64.00,-3.00"}""",
            line,
        )
    }

    @Test
    fun `every event has a stable wire name so old logs stay readable`() {
        assertEquals(
            BodyEvent.entries.toSet(),
            WIRE_NAMES.keys,
            "a new body event must be given its wire name here before it can reach a log",
        )
        for ((event, name) in WIRE_NAMES) {
            // Also pins that a record claims nothing it has not got: the fields a
            // perception-less event has no value for are absent, not null.
            assertEquals(
                """{"tick":0,"agentId":"a","event":"$name","detail":""}""",
                BodyEventRecord(0, "a", event, "").toJsonLine(),
            )
        }
    }

    @Test
    fun `a decision record carries the perception it answers and the provenance the mind stamped`() {
        val record =
            BodyEventRecord(
                tick = 7,
                agentId = "agent-ada",
                event = BodyEvent.DECIDED,
                detail = "idle: nothing worth doing",
                requestId = "req-1",
                provenance =
                    Provenance(
                        tier = DecisionImportance.NOTABLE,
                        model = "test-model",
                        promptVersion = "test-v1",
                    ),
            )

        val line = record.toJsonLine()

        assertTrue(line.none { it == '\n' }, "a record must occupy exactly one line")
        assertEquals(
            """{"tick":7,"agentId":"agent-ada","event":"decided","detail":"idle: nothing worth doing",""" +
                """"requestId":"req-1","provenance":{"tier":"NOTABLE","model":"test-model",""" +
                """"promptVersion":"test-v1"}}""",
            line,
        )
        // Which model decided, and reacting to which perception, must come back out of
        // the line as data: a run that had to parse rendered text for its provenance
        // would not have a research record at all.
        assertEquals(record, LoomJson.decodeFromString<BodyEventRecord>(line))
    }

    @Test
    fun `the countable fields survive the line as fields rather than as prose`() {
        val record =
            BodyEventRecord(
                tick = 9,
                agentId = "agent-ada",
                event = BodyEvent.INTENT_APPLIED,
                detail = "walk to 10,64,-3 -> accepted",
                requestId = "req-2",
                trigger = EventTag.GOAL_REVIEW,
                intent = EventTag.MOVE_TO,
                outcome = EventTag.ACCEPTED,
                // Deliberately different readings: food and health are separate facts,
                // so a change that fed one of them from
                // the other - the aliasing this record exists to keep apart - fails here
                // rather than shipping a gauge that reports the wrong death.
                vitals = AgentVitals(energy = 16.8, capacity = 20.0),
                health = AgentVitals(energy = 16.0, capacity = 20.0),
            )

        val line = record.toJsonLine()

        // Every metric a run states - what triggered a decision, what the mind chose,
        // what the body did with it, how much food was left, and whether the
        // body was hurt - has to be countable without reading the detail text beside it.
        //
        // Each reading is `level`, not `energy`: which vital a reading is of is the field
        // it rides in, and an analysis reaching a health reading through `health.energy`
        // would be the same aliasing of the two vitals one field further down.
        assertEquals(
            """{"tick":9,"agentId":"agent-ada","event":"intentApplied","detail":"walk to 10,64,-3 -> accepted",""" +
                """"requestId":"req-2","trigger":"goalReview","intent":"moveTo","outcome":"accepted",""" +
                """"vitals":{"level":16.8,"capacity":20.0},"health":{"level":16.0,"capacity":20.0}}""",
            line,
        )
        assertEquals(record, LoomJson.decodeFromString<BodyEventRecord>(line))
    }

    @Test
    fun `the file log appends every record in order and never rewrites`() {
        val dir = Files.createTempDirectory("loom-body-log")
        val path = dir.resolve("nested").resolve("body-events.jsonl")
        val failures = mutableListOf<String>()

        val log =
            FileBodyEventLog(
                path,
                onWriteFailure = { failures += it.message.orEmpty() },
                onRecordsDropped = { },
            )
        log.use {
            repeat(20) { index ->
                it.append(BodyEventRecord(index.toLong(), "agent-$index", BodyEvent.MOVED, "step $index"))
            }
        }

        val lines = path.readLines()
        assertEquals(emptyList(), failures, "no write should have failed")
        assertEquals(20, lines.size)
        lines.forEachIndexed { index, line ->
            assertTrue(line.contains(""""tick":$index"""), "line $index out of order: $line")
        }
    }

    @Test
    fun `records enqueued after close are counted rather than silently lost`() {
        val dir = Files.createTempDirectory("loom-body-log-closed")
        val log = FileBodyEventLog(dir.resolve("events.jsonl"), onWriteFailure = { }, onRecordsDropped = { })
        log.append(BodyEventRecord(1, "agent", BodyEvent.SPAWNED, "before close"))
        log.close()

        log.append(BodyEventRecord(2, "agent", BodyEvent.MOVED, "after close"))
        assertEquals(1, log.droppedRecords())
    }

    @Test
    fun `a non-zero drop count is surfaced rather than left in the counter`() {
        val dir = Files.createTempDirectory("loom-body-log-dropped")
        val surfaced = mutableListOf<Long>()
        val log =
            FileBodyEventLog(
                dir.resolve("events.jsonl"),
                onWriteFailure = { },
                onRecordsDropped = { surfaced += it },
            )
        log.close()

        log.append(BodyEventRecord(1, "agent", BodyEvent.SPAWNED, "after close"))
        log.append(BodyEventRecord(2, "agent", BodyEvent.MOVED, "after close"))

        assertEquals(2, log.droppedRecords())
        assertEquals(2, surfaced.last(), "the surfaced count must match the records that never reached the file")
    }

    @Test
    fun `a clean run surfaces no drops`() {
        val dir = Files.createTempDirectory("loom-body-log-clean")
        val surfaced = mutableListOf<Long>()
        val log =
            FileBodyEventLog(
                dir.resolve("events.jsonl"),
                onWriteFailure = { },
                onRecordsDropped = { surfaced += it },
            )
        log.use {
            it.append(BodyEventRecord(1, "agent", BodyEvent.SPAWNED, "at world"))
        }

        assertEquals(emptyList(), surfaced, "a log that persisted every record must not report drops")
    }
}
