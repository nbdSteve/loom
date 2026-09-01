/*
 * The durable half of permadeath, against a real filesystem.
 *
 * Two acceptance criteria live here. A dead identity has to still be dead after a restart, which is
 * tested by throwing the ledger object away and replaying the file with a new one - the only way to
 * show that the durability is the file's and not the object's. And a malformed or unreadable ledger
 * must not silently revive anybody, which is tested by corrupting the file in each of the three
 * ways a real one gets corrupted: a line that is not JSON, a line that is JSON but not a tombstone,
 * and a final line torn off by a crash mid-append.
 *
 * What an unsafe replay still *carries* is checked as carefully as the verdict itself. A torn line
 * makes exactly one death unknowable - the one it was in the middle of recording - and says nothing
 * about the whole, synced lines before it, so those come back with the refusal. Discarding them
 * would leave a dead agent's account admissible by nothing but the absence of a registration the
 * new process has not been given yet, which is what the file being synchronous exists to prevent.
 *
 * The reason string is checked as carefully as the verdict. A corrupted ledger is the one file in
 * Loom whose bytes are known to be untrustworthy, so a reason that quoted them would copy them into
 * the server log and into a record - which is why the reason names a line number and a failure type
 * and nothing else.
 */
package dev.loom.witness

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A stand-in for a killer named on an anvil, used to prove no file content reaches a reason. */
private const val CORRUPTION_MARKER = "unquotable-ledger-bytes"

class DeathLedgerTest {
    private val scout = Tombstone("agent-scout", offlineUuid("loomscout1").toString(), "loomscout1", 120)
    private val digger = Tombstone("agent-digger", offlineUuid("loomdigger1").toString(), "loomdigger1", 480)

    @Test
    fun aMissingLedgerReadsAsNoDeathsRatherThanAsUnsafe() {
        val path = tempDir().resolve("nested").resolve("player-deaths.jsonl")

        val replay = FileDeathLedger(path).replay()

        // The ordinary state of a fresh run, and the one absence that is safe to read positively:
        // Loom is the only writer here and it writes before it treats a death as final.
        assertEquals(LedgerReplay.Readable(emptyList()), replay)
        assertFalse(Files.exists(path), "replaying must not create the file")
    }

    @Test
    fun aDeathOutlivesTheObjectThatRecordedIt() {
        val path = tempDir().resolve("nested").resolve("player-deaths.jsonl")

        assertEquals(LedgerWrite.Persisted, FileDeathLedger(path).record(scout))

        // A different object over the same path: this is the restart, and it is the only shape of
        // test that can tell durability on the disk from durability in a field.
        val replay = FileDeathLedger(path).replay()

        assertEquals(LedgerReplay.Readable(listOf(scout)), replay)
    }

    @Test
    fun everyTombstoneIsOneAppendedLineAndNothingIsRewritten() {
        val path = tempDir().resolve("player-deaths.jsonl")
        val ledger = FileDeathLedger(path)

        ledger.record(scout)
        ledger.record(digger)

        val lines = path.readLines()
        assertEquals(2, lines.size, "one line per death, appended")
        assertTrue(lines[0].contains(""""agentId":"agent-scout""""), lines[0])
        assertTrue(lines[1].contains(""""agentId":"agent-digger""""), lines[1])
        assertEquals(LedgerReplay.Readable(listOf(scout, digger)), FileDeathLedger(path).replay())
    }

    @Test
    fun aTombstoneCarriesTheWholeIdentityBackOutOfTheFile() {
        val path = tempDir().resolve("player-deaths.jsonl")
        FileDeathLedger(path).record(scout)

        val read = assertIs<LedgerReplay.Readable>(FileDeathLedger(path).replay()).tombstones.single()

        // All four fields, because the ledger is what a human reads to learn why an account cannot
        // log in - an agent id alone would send them looking through a dead process's memory.
        assertEquals(scout.agentId, read.agentId)
        assertEquals(scout.uuid, read.uuid)
        assertEquals(scout.username, read.username)
        assertEquals(scout.tick, read.tick)
    }

    @Test
    fun aBlankLineIsNotADeathAndIsNotAFailure() {
        val path = tempDir().resolve("player-deaths.jsonl")
        FileDeathLedger(path).record(scout)
        path.appendText("\n   \n")

        assertEquals(LedgerReplay.Readable(listOf(scout)), FileDeathLedger(path).replay())
    }

    @Test
    fun aLineThatIsNotATombstoneMakesTheReplayUnsafeAndKeepsTheDeathsBeforeIt() {
        // Three ways a ledger actually goes wrong: not JSON at all, JSON that is not a tombstone,
        // and a final line a crash tore off mid-append. All three mean Loom does not know who is
        // dead, which is not the same as knowing that nobody is.
        val corruptions =
            listOf(
                "$CORRUPTION_MARKER not json at all",
                """{"agentId":"$CORRUPTION_MARKER"}""",
                """{"agentId":"$CORRUPTION_MARKER","uuid":""",
            )

        for (corruption in corruptions) {
            val path = tempDir().resolve("player-deaths.jsonl")
            val ledger = FileDeathLedger(path)
            ledger.record(scout)
            path.appendText(corruption)

            val replay = ledger.replay()

            val unsafe = assertIs<LedgerReplay.Unsafe>(replay, "'$corruption' must not read as a clean ledger")
            // The death that *is* readable comes back with the refusal. A torn line makes only the
            // death it was in the middle of recording unknowable; the line before it is whole,
            // synced and Loom's own, and dropping it would leave that agent's account admissible
            // by nothing but the absence of a registration this process has not been given yet.
            assertEquals(listOf(scout), unsafe.tombstones, "'$corruption' must not discard the deaths before it")
            assertTrue(unsafe.reason.contains("line 2"), "the reason must name the line: ${unsafe.reason}")
            assertFalse(
                unsafe.reason.contains(CORRUPTION_MARKER),
                "the reason must quote no file content: ${unsafe.reason}",
            )
        }
    }

    @Test
    fun aTornLineStopsTheReplayRatherThanBeingSteppedOver() {
        val path = tempDir().resolve("player-deaths.jsonl")
        val ledger = FileDeathLedger(path)
        ledger.record(scout)
        path.appendText("$CORRUPTION_MARKER not json at all\n")
        ledger.record(digger)

        val unsafe = assertIs<LedgerReplay.Unsafe>(ledger.replay())

        // Everything up to the bad line is a whole line Loom wrote and synced. What follows one is
        // not known to be in step with anything - a half-written line means the process died
        // mid-append, and whatever is after it in the file is whatever was there before - so the
        // replay stops, and the seal is what covers the remainder.
        assertEquals(listOf(scout), unsafe.tombstones)
        assertTrue(unsafe.reason.contains("line 2"), unsafe.reason)
    }

    @Test
    fun aLedgerNothingCouldBeReadFromCarriesNoDeaths() {
        val path = tempDir().resolve("player-deaths.jsonl")
        path.writeText("$CORRUPTION_MARKER not json at all\n")

        val unsafe = assertIs<LedgerReplay.Unsafe>(FileDeathLedger(path).replay())

        // An empty list here is honest rather than reassuring: nothing was read, so there is
        // nothing to enforce, and the seal is the whole of Loom's answer.
        assertEquals(emptyList(), unsafe.tombstones)
        assertTrue(unsafe.reason.contains("line 1"), unsafe.reason)
    }

    @Test
    fun aLedgerThatCannotBeReadIsUnsafeRatherThanEmpty() {
        // A directory where the file should be: something else owns that name, so Loom cannot read
        // its own death state. Chosen over an unreadable file because it fails for any user,
        // including a CI container running as root.
        val path = tempDir().resolve("player-deaths.jsonl")
        path.createDirectories()

        val replay = FileDeathLedger(path).replay()

        val unsafe = assertIs<LedgerReplay.Unsafe>(replay)
        assertTrue(unsafe.reason.contains("could not read"), unsafe.reason)
    }

    @Test
    fun aWriteThatCannotLandIsReportedRatherThanSwallowed() {
        // The parent of the ledger is a regular file, so the directory it needs cannot be made.
        val dir = tempDir()
        val blocker = dir.resolve("blocker")
        blocker.writeText("not a directory")
        val path = blocker.resolve("player-deaths.jsonl")

        val write = FileDeathLedger(path).record(scout)

        val failed = assertIs<LedgerWrite.Failed>(write, "a tombstone that did not land must say so")
        assertTrue(failed.reason.contains("could not append"), failed.reason)
        assertFalse(failed.reason.contains(scout.username), "a reason must not carry the identity: ${failed.reason}")
        assertEquals("not a directory", blocker.readText(), "a failed write must not have touched anything")
    }

    private fun tempDir(): Path = Files.createTempDirectory("loom-death-ledger")
}
