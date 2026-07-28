/*
 * The production append-only body-event sink: newline-delimited JSON on disk.
 *
 * Writes are handed to a single background writer thread so the server main
 * thread never waits on I/O. Ordering is preserved (one queue, one writer), which
 * is what makes the file replayable. The file is only ever appended to.
 */
package dev.loom.body

import java.io.BufferedWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** How long [FileBodyEventLog.close] waits for the writer to drain before giving up. */
private const val DRAIN_TIMEOUT_SECONDS = 5L

/**
 * Upper bound on records awaiting the writer. Sized for a burst well past the
 * ~8k records/second a full body population produces at 20 TPS, so a healthy
 * writer never drops; a stalled disk drops and counts rather than growing memory
 * without limit.
 */
private const val MAX_QUEUED_RECORDS = 100_000

/**
 * Appends body-event records to [path] as newline-delimited JSON.
 *
 * Records are enqueued on the calling (main) thread and written by one daemon
 * writer thread, so a slow disk degrades log latency and never tick cadence. The
 * writer opens the file once and appends to it for its whole lifetime rather than
 * reopening per record. The queue is bounded: [append] never blocks the tick, and
 * a record that finds the queue full is counted via [droppedRecords] rather than
 * enqueued. Any drop is surfaced through [onRecordsDropped] - from the writer once
 * the backlog clears and at [close], never per record on the tick path - so an
 * incomplete file always leaves a runtime sign. The writer is a daemon thread so
 * an unclosed log never blocks JVM shutdown.
 */
class FileBodyEventLog(
    private val path: Path,
    private val onWriteFailure: (IOException) -> Unit,
    private val onRecordsDropped: (Long) -> Unit,
) : BodyEventLog, AutoCloseable {
    private val queue = LinkedBlockingQueue<String>(MAX_QUEUED_RECORDS)
    private val dropped = AtomicLong()
    private val surfaced = AtomicLong()

    @Volatile
    private var running = true

    private val writer =
        Thread({ drainUntilClosed() }, "loom-body-event-log").apply {
            isDaemon = true
            start()
        }

    override fun append(record: BodyEventRecord) {
        // offer never blocks: a closed log or a full queue drops and counts rather
        // than stalling the server tick on I/O.
        if (!running || !queue.offer(record.toJsonLine())) {
            dropped.incrementAndGet()
            // A closed log has no writer left to surface the drop, and appends after
            // close are off the tick path, so it is reported here instead.
            if (!running) {
                surfaceNewDrops()
            }
        }
    }

    /**
     * Records that never reached the file: enqueued after close, dropped because the
     * queue was full, or lost to a failed write. Every record the log did not persist
     * is counted here, so the count bounds how incomplete the file may be.
     */
    fun droppedRecords(): Long = dropped.get()

    override fun close() {
        running = false
        // Unblock the writer's poll immediately rather than waiting out its timeout.
        writer.interrupt()
        writer.join(TimeUnit.SECONDS.toMillis(DRAIN_TIMEOUT_SECONDS))
        surfaceNewDrops()
    }

    private fun drainUntilClosed() {
        var out: BufferedWriter? = null
        try {
            while (running || queue.isNotEmpty()) {
                val line =
                    try {
                        queue.poll(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        // close() interrupts to wake us; keep draining what is queued.
                        continue
                    }
                if (line != null) {
                    out = writeLine(out, line)
                }
                // Surfacing waits for the backlog to clear so a drop episode is
                // reported once rather than once per record while the queue churns.
                if (queue.isEmpty()) {
                    surfaceNewDrops()
                }
            }
        } finally {
            closeQuietly(out)
        }
    }

    /**
     * Reports any drops not yet reported by handing the running total to
     * [onRecordsDropped]. The compare-and-set makes concurrent callers report a
     * total at most once; a count that slips past a losing racer is picked up by
     * the next call, and [close] always makes a final one.
     */
    private fun surfaceNewDrops() {
        val total = dropped.get()
        val reported = surfaced.get()
        if (total > reported && surfaced.compareAndSet(reported, total)) {
            onRecordsDropped(total)
        }
    }

    /**
     * Appends one line to the open writer, opening it on first use, and returns the
     * writer to reuse next time. On an I/O failure the writer is closed and `null`
     * returned so the next line reopens: a failed write must not take the server
     * down, and later records should still land.
     */
    private fun writeLine(
        existing: BufferedWriter?,
        line: String,
    ): BufferedWriter? =
        try {
            val out = existing ?: openWriter()
            out.write(line)
            out.write("\n")
            // Flush once the backlog is clear so an idle log stays current on disk
            // while a burst still batches into few syscalls.
            if (queue.isEmpty()) {
                out.flush()
            }
            out
        } catch (failure: IOException) {
            // This line never reached the file, so it counts as dropped: every metric is
            // meant to derive from the log, and a write failure that stayed invisible to
            // the counter would leave a silent hole in that record.
            dropped.incrementAndGet()
            onWriteFailure(failure)
            closeQuietly(existing)
            null
        }

    private fun openWriter(): BufferedWriter {
        path.parent?.let(Files::createDirectories)
        return Files.newBufferedWriter(
            path,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
        )
    }

    private fun closeQuietly(out: BufferedWriter?) {
        try {
            out?.close()
        } catch (failure: IOException) {
            onWriteFailure(failure)
        }
    }
}
