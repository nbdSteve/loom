/*
 * The durable half of permadeath.
 *
 * `Mortality` makes a death terminal for as long as the server process lives, which is enough
 * for the Villager body: a Villager exists only inside a running Loom, so a restart takes the
 * body with it. A player body is different in the one way that matters. The account outlives
 * the server, the client can reconnect at any time, and the process driving it is started by
 * something outside Loom - so an in-memory death is a death that a restart undoes, and Loom
 * would hand a fresh body to an agent it had already recorded as gone.
 *
 * This file is therefore where the ledger lives: one tombstone per line, appended and synced
 * before the death is treated as final, replayed before the first login is admitted.
 *
 * Two rules shape every decision here, and both are about failing in the safe direction.
 *
 * A tombstone is written *synchronously*, unlike the research event log next to it, which is
 * deliberately asynchronous so a stalled disk cannot stall the tick. The two are answering
 * different questions. The event log is evidence, and evidence arriving a moment late costs
 * nothing; the ledger is an *authorisation input*, and a death that has not reached the disk is
 * a death a restart forgets. It is also written at most once per agent per run, so the cost of
 * being synchronous is one file sync in a whole agent lifetime.
 *
 * And anything unreadable is unsafe rather than empty. A missing file means no agent has died
 * yet, which is the ordinary state of a fresh run. A file that exists and cannot be parsed
 * means Loom does not know who is dead - which is not the same as knowing that nobody is - so
 * it is reported as [LedgerReplay.Unsafe] and every managed login is refused until a human
 * looks. That includes a torn final line from a crash mid-append: the run loses availability
 * for its player bodies and keeps the invariant, which is the correct way round for a platform
 * whose whole subject is permadeath.
 *
 * Unsafe is not the same as unknown, though, and the difference is the whole reason
 * [LedgerReplay] carries its tombstones on both variants. A torn line makes exactly one death
 * unknowable - the one it was in the middle of recording - and says nothing whatever about the
 * deaths recorded on the lines before it, which are complete, synced and Loom's own. Discarding
 * those would leave a dead agent's account admissible by nothing but the absence of a
 * registration this process has not been given yet, which is the failure the whole file exists
 * to prevent. So a replay stops at the first line it cannot read, keeps every tombstone up to
 * it, and reports both: the deaths it can name are enforced, and the line it cannot is what
 * fails closed.
 */
package dev.loom.witness

import dev.loom.contract.LoomJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * One durable record that an agent is dead for good.
 *
 * Carries the whole identity rather than the agent id alone, for the same reason a witness
 * record does: the ledger is the file a human reads when they want to know why an account
 * cannot log in, and an agent id would send them looking through a registration that only ever
 * existed in a previous process's memory.
 *
 * [tick] is the server tick the death was witnessed on. It is kept for the research record
 * rather than for any decision - nothing here compares ticks, because a tick from a previous
 * server process is not on the same clock as this one's.
 */
@Serializable
data class Tombstone(
    /** The agent that died, as [dev.loom.body.AgentId.value]. */
    val agentId: String,
    /** The player uuid the death was witnessed under. */
    val uuid: String,
    /** The username the death was witnessed under. */
    val username: String,
    /** The server tick of the witnessing process, for the record rather than for comparison. */
    val tick: Long,
)

/** What replaying the ledger found. */
sealed interface LedgerReplay {
    /**
     * Every death the ledger could be read for, on either answer.
     *
     * On the interface rather than on one variant because a caller must enforce these deaths
     * whether or not the replay also failed: a tombstone that parsed is a complete, synced
     * statement that an agent is gone, and the line after it being torn does not take that back.
     */
    val tombstones: List<Tombstone>

    /**
     * The ledger was read in full. [tombstones] is every death it holds, possibly none.
     *
     * An empty list is a positive statement - nobody has died - and is what a fresh run gets.
     */
    data class Readable(
        override val tombstones: List<Tombstone>,
    ) : LedgerReplay

    /**
     * The ledger could not be read in full, or held a line that is not a tombstone.
     *
     * [reason] is bounded and quotes no file content, so it is safe to put in a record and in a
     * server log. A malformed line is named by its number and never by its text: a corrupted
     * ledger is exactly the file whose bytes should not be copied into another log.
     *
     * [tombstones] is every death read *before* the line that failed, and is deliberately not
     * empty in the ordinary corruption case: a crash mid-append tears the last line only, so
     * discarding the ones before it would forget deaths the ledger states perfectly well. It is
     * empty when nothing could be read at all, as when the file itself will not open.
     */
    data class Unsafe(
        val reason: String,
        override val tombstones: List<Tombstone> = emptyList(),
    ) : LedgerReplay
}

/** What appending a tombstone did. */
sealed interface LedgerWrite {
    /** The tombstone is on the disk and synced. The death now outlives this process. */
    data object Persisted : LedgerWrite

    /** The tombstone did not reach the disk. [reason] is bounded and quotes no content. */
    data class Failed(
        val reason: String,
    ) : LedgerWrite
}

/**
 * The durable death state, as the two operations permadeath needs of it.
 *
 * An interface so a test can drive the fail-closed paths - an unreadable ledger, a write that
 * does not land - without needing a filesystem that can be made to fail on demand. There is
 * deliberately no way to remove a tombstone, in the same spirit as `Mortality` offering no way
 * to un-record a death: a revive path that does not exist cannot be called by mistake.
 */
interface DeathLedger {
    /**
     * Reads every tombstone it can. Called once, before any login is admitted.
     *
     * An implementation must report the tombstones it did read even when it also has to answer
     * [LedgerReplay.Unsafe]; see that variant for why a partial read is not an empty one.
     */
    fun replay(): LedgerReplay

    /** Appends [tombstone] and does not return until it is durable, or has failed. */
    fun record(tombstone: Tombstone): LedgerWrite
}

/**
 * The ledger as a newline-delimited JSON file.
 *
 * Same shape as the research event log beside it - one JSON object per line, append-only,
 * never rewritten - so an operator reads both with the same tools. The file is opened for each
 * append rather than held open, which costs a syscall on an event that happens once per agent
 * lifetime and means a Loom that is not enabled holds no handle on it.
 */
class FileDeathLedger(
    private val path: Path,
) : DeathLedger {
    override fun replay(): LedgerReplay =
        try {
            // A missing file is the ordinary state of a run in which nobody has died yet. It is
            // the one absence that is safe to read as "no deaths", because Loom is the only
            // thing that writes here and it writes before it treats a death as final.
            if (!Files.exists(path)) {
                LedgerReplay.Readable(emptyList())
            } else {
                replayOf(Files.readAllLines(path, StandardCharsets.UTF_8))
            }
        } catch (failure: IOException) {
            // Nothing was read, so there is nothing to enforce: the whole file is what failed
            // rather than one line in it.
            LedgerReplay.Unsafe("could not read the death ledger: ${failure.javaClass.simpleName}")
        }

    override fun record(tombstone: Tombstone): LedgerWrite =
        try {
            path.parent?.let(Files::createDirectories)
            val line = LoomJson.encodeToString(tombstone) + "\n"
            // SYNC rather than a plain append: the caller is about to treat the agent as dead
            // for good, and a tombstone still in a page cache is a death a power cut undoes.
            Files.newOutputStream(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                StandardOpenOption.SYNC,
            ).use { out -> out.write(line.toByteArray(StandardCharsets.UTF_8)) }
            LedgerWrite.Persisted
        } catch (failure: IOException) {
            LedgerWrite.Failed("could not append to the death ledger: ${failure.javaClass.simpleName}")
        }

    /**
     * Every tombstone up to the first line that is not one, and which line that was.
     *
     * Accumulating and stopping is what makes a partial read partial rather than lost. It stops
     * rather than skipping on, because the lines after a torn one are not known to be in step
     * with anything: a half-written line means the process died mid-append, and what follows it in
     * the file is whatever was there before. Everything up to that point is a whole line Loom
     * itself wrote and synced.
     */
    private fun replayOf(lines: List<String>): LedgerReplay {
        val found = mutableListOf<Tombstone>()
        for ((index, line) in lines.withIndex()) {
            if (line.isBlank()) {
                continue
            }
            val tombstone =
                try {
                    LoomJson.decodeFromString<Tombstone>(line)
                } catch (failure: SerializationException) {
                    // Named by line number and failure type, never by content: this is the one
                    // file whose bytes are known to be untrustworthy, so copying them into
                    // another log or into a record is the last thing to do with them.
                    return LedgerReplay.Unsafe(
                        "death ledger line ${index + 1} is not a tombstone: " +
                            failure.javaClass.simpleName,
                        found.toList(),
                    )
                }
            found += tombstone
        }
        return LedgerReplay.Readable(found)
    }
}
