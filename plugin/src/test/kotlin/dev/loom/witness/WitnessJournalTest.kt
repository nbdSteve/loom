/*
 * That a witness record is bounded and countable, whatever the world puts in it.
 *
 * The interesting input here is not Loom's own prose but the one string in these records that
 * neither Loom nor a model wrote: a vanilla death message names the killer and the weapon, and a
 * weapon's name comes off an anvil, so it can be any length and hold any character a client can
 * type. Every test below drives a real journal with the sort of text that reaches it in world,
 * rather than asserting the helper's arithmetic, because what has to hold is that no record on the
 * disk can be over-long or span two lines - not that one function trims.
 */
package dev.loom.witness

import dev.loom.body.BodyEvent
import dev.loom.body.WitnessedPlayer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A tick a statement is made at. Arbitrary; nothing here compares two. */
private const val TICK = 4_200L

class WitnessJournalTest {
    private val scout = managed("agent-scout", "loomscout1")
    private val log = RecordingLog()
    private val journal = WitnessJournal(log)

    @Test
    fun everyRecordNamesTheAgentTheUuidAndTheUsername() {
        journal.joined(subjectOf(scout), "world", TICK)

        // The three names are what tie a record to the rest of the evidence: the agent id to
        // Loom's own chain, and the uuid and username to the server log and to vanilla
        // playerdata. None of the other two is recoverable from the agent id afterwards.
        val record = log.of(BodyEvent.WITNESS_JOINED).single()
        assertEquals("agent-scout", record.agentId)
        assertEquals(scout.uuid.toString(), record.witness?.uuid)
        assertEquals("loomscout1", record.witness?.username)
        assertEquals(TICK, record.tick)
        assertNull(record.outcome, "an ordinary arrival has no tag to carry")
    }

    @Test
    fun aRefusalAndARevivalCarryTheirReasonAsACountableTag() {
        journal.denied(subjectOf(scout), "loginDeadIdentity", "it had already died", TICK)
        journal.revived(subjectOf(scout), "revivedAfterRespawn", "it respawned anyway", TICK)

        // Both events cover several opposite facts under one name, so anything counting them
        // reads the tag. Left in the prose, the count would be a text search over a detail
        // whose whole purpose is to be human-readable and therefore free to be reworded.
        assertEquals("loginDeadIdentity", log.of(BodyEvent.WITNESS_DENIED).single().outcome)
        assertEquals("revivedAfterRespawn", log.of(BodyEvent.WITNESS_REVIVED).single().outcome)
    }

    @Test
    fun aRefusalNamesTheAgentItIsFiledUnderAndThePlayerItTurnedAway() {
        // The one statement whose agent and whose player need not be halves of one registration: a
        // dead agent's account is refused whichever name it presents, and for a tombstone that only
        // partly read back there is no stored identity to name at all.
        val subject = WitnessSubject(scout.agentId, WitnessedPlayer(offlineUuid("loomscout9").toString(), "loomscout9"))

        journal.denied(subject, "loginDeadIdentity", "somebody else's account was refused", TICK)

        val record = log.of(BodyEvent.WITNESS_DENIED).single()
        assertEquals("agent-scout", record.agentId, "filed under the agent the ledger names")
        assertEquals("loomscout9", record.witness?.username, "and naming what actually connected")
        assertEquals(offlineUuid("loomscout9").toString(), record.witness?.uuid)
    }

    @Test
    fun aDeathRecordSaysThatItIsTerminal() {
        journal.died(subjectOf(scout), "loomscout1 was slain by Zombie", TICK)

        // Filed as Loom's own `died`, so a player body's death and a Villager's are one number.
        val record = log.of(BodyEvent.DIED).single()
        assertTrue(record.detail.contains("was slain by Zombie"), record.detail)
        assertTrue(record.detail.contains("Permadeath"), record.detail)
    }

    @Test
    fun aRenamedWeaponCannotWriteAnUnboundedRecord() {
        // An anvil will take a name of any length, and the server puts it in the death message
        // verbatim. Unbounded, one death would put a paragraph on a line of the research record.
        val weapon = "the".repeat(MAX_WITNESS_DETAIL_CHARS)

        journal.died(subjectOf(scout), "loomscout1 was slain by Zombie using $weapon", TICK)

        val detail = log.of(BodyEvent.DIED).single().detail
        assertEquals(MAX_WITNESS_DETAIL_CHARS, detail.length)
        assertTrue(detail.endsWith("..."), "a cut must be visible rather than silent: $detail")
        // The front of the message is what says who died and to what, so it is what survives.
        assertTrue(detail.startsWith("loomscout1 was slain by Zombie"), detail)
    }

    @Test
    fun aRenamedWeaponCannotWriteASecondLineEither() {
        // The record is newline-delimited JSON, so a detail that spanned two lines would be two
        // records to anything reading the file - one of them malformed. The formatting sign goes
        // the same way: a coloured death message is colour codes to a reader of the raw file.
        journal.died(subjectOf(scout), "loomscout1 was slain by\nZombie\tusing §cthe §rknife", TICK)

        val detail = log.of(BodyEvent.DIED).single().detail
        assertEquals(
            "loomscout1 was slain by Zombie using cthe rknife. Permadeath: this agent will not return.",
            detail,
        )
        assertFalse(detail.contains("\n"), detail)
    }
}
