package dev.loom.body

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.readLines
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        val names =
            BodyEvent.entries.map { event ->
                BodyEventRecord(0, "a", event, "").toJsonLine()
            }
        assertTrue(names.any { it.contains(""""event":"spawned"""") })
        assertTrue(names.any { it.contains(""""event":"spawnRejected"""") })
        assertTrue(names.any { it.contains(""""event":"intentApplied"""") })
        assertTrue(names.any { it.contains(""""event":"moved"""") })
        assertTrue(names.any { it.contains(""""event":"perceived"""") })
        assertTrue(names.any { it.contains(""""event":"despawned"""") })
        assertTrue(names.any { it.contains(""""event":"died"""") })
        assertTrue(names.any { it.contains(""""event":"travelled"""") })
        assertTrue(names.any { it.contains(""""event":"converted"""") })
        assertTrue(names.any { it.contains(""""event":"worldLoaded"""") })
        assertTrue(names.any { it.contains(""""event":"worldHoldFailed"""") })
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
