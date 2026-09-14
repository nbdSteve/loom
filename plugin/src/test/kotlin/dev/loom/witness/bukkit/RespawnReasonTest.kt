/*
 * Which of the server's respawns is a death, driven through the adapter's own decision.
 *
 * `PlayerRespawnEvent` is not the after-a-death event its name suggests, and reading it as one cost
 * a living agent its life: `RespawnReason.END_PORTAL` fires for a player stepping into the End's
 * exit portal, because vanilla sends it to its respawn position rather than through an ordinary
 * portal, and the witness then recorded a death, synced a tombstone and kicked the agent for good.
 * That is an irreversible false permadeath, so these tests drive every reason the enum has and
 * assert the whole of what each one does *and does not* leave behind.
 *
 * The reason alone is not the whole rule, though, which is the other half of what these pin: a
 * respawn with no death behind it is still a breach for an agent Loom has *already* tombstoned,
 * because something on the server has put a dead agent back in the world. So each non-death reason
 * is driven twice, once against a living agent and once against a dead one, and the two answers are
 * opposite.
 *
 * They go through `respawnStatement` rather than through a `PlayerRespawnEvent`, because building
 * one needs a `Player` and a `World` and so needs a running server - and this is exactly the part
 * that must be provable without one. The reasons are the real enum values, so a reason Paper adds
 * later fails `respawnCauseOf`'s exhaustive `when` at compile time rather than defaulting into
 * being read as a death.
 */
package dev.loom.witness.bukkit

import dev.loom.body.BodyEvent
import dev.loom.body.EventTag
import dev.loom.witness.DurablePermadeath
import dev.loom.witness.FakeLedger
import dev.loom.witness.LedgerReplay
import dev.loom.witness.LoginVerdict
import dev.loom.witness.ManagedIdentity
import dev.loom.witness.PlayerWitness
import dev.loom.witness.RecordingLog
import dev.loom.witness.RespawnCause
import dev.loom.witness.WitnessAction
import dev.loom.witness.WitnessJournal
import dev.loom.witness.managed
import dev.loom.witness.offlineUuid
import dev.loom.witness.registryOf
import dev.loom.witness.subjectOf
import dev.loom.witness.tombstoneFor
import org.bukkit.event.player.PlayerRespawnEvent.RespawnReason
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** What a dead or revived body is told. Restated here so a change to the wording fails a test. */
private const val DEAD_NOTICE = "This agent has died. Loom is permadeath: it does not come back."

/** A cause string a server would produce. */
private const val KILLED_BY_ZOMBIE = "loomscout1 was slain by Zombie"

class RespawnReasonTest {
    private val scout = managed("agent-scout", "loomscout1")

    @Test
    fun aLiveAgentComingHomeFromTheEndIsNotWitnessedAtAll() {
        val under = WitnessUnderTest(scout)

        val action = respawnStatement(under.witness, RespawnReason.END_PORTAL, scout.uuid, scout.username)

        // A dimension return is continuity for the same player identity. Every
        // one of these being absent is the point: the agent is alive, and any one of them alone
        // would have ended it, because the tombstone is synced before anything can reconsider.
        assertEquals(WitnessAction.LeaveAlone, action, "an agent leaving the End must not be removed")
        assertEquals(emptyList(), under.log.records, "a live respawn is not evidence of anything")
        assertTrue(under.persisted.isEmpty(), "no tombstone may be written for an agent that is alive")
        assertEquals(emptyList(), under.alerts)
        assertEquals(
            LoginVerdict.Admitted(scout),
            under.witness.admitLogin(scout.uuid, scout.username),
            "the agent must still be able to reconnect afterwards",
        )
    }

    @Test
    fun aLivingAgentRespawnedByAPluginIsNotWitnessedEither() {
        val under = WitnessUnderTest(scout)

        val action = respawnStatement(under.witness, RespawnReason.PLUGIN, scout.uuid, scout.username)

        // Something else on the server respawning a *live* player says nothing about whether it died.
        assertEquals(WitnessAction.LeaveAlone, action)
        assertEquals(emptyList(), under.log.records)
        assertTrue(under.persisted.isEmpty())
        assertEquals(emptyList(), under.alerts)
    }

    @Test
    fun aRespawnWithNoDeathBehindItIsStillABreachForAnAgentAlreadyDead() {
        for (reason in RespawnReason.entries.filter { it != RespawnReason.DEATH }) {
            val under = WitnessUnderTest(scout)
            under.witness.sawDeath(scout.uuid, scout.username, KILLED_BY_ZOMBIE)

            val action = respawnStatement(under.witness, reason, scout.uuid, scout.username)

            // Nobody died *here*, but the world is holding a live player for an agent Loom has
            // tombstoned, so the run has to say so - the reason alone was never the whole rule.
            assertEquals(WitnessAction.Remove(subjectOf(scout), DEAD_NOTICE), action, "$reason")
            val revival = under.log.of(BodyEvent.WITNESS_REVIVED).single()
            assertEquals(EventTag.REVIVED_AFTER_RESPAWN, revival.outcome, "$reason")
            assertEquals(1, under.alerts.size, "a breached permadeath must reach an operator: ${under.alerts}")
            // And it is the same one death: the agent was already dead, so nothing is recorded twice.
            assertEquals(1, under.log.of(BodyEvent.DIED).size, "$reason must add no second death")
            assertEquals(1, under.persisted.size, "$reason must add no second tombstone")
        }
    }

    @Test
    fun aRevivalReadsTheSameWhicheverRespawnBroughtItAbout() {
        val afterDeath = WitnessUnderTest(scout)
        val withoutDeath = WitnessUnderTest(scout)
        withoutDeath.witness.sawDeath(scout.uuid, scout.username, KILLED_BY_ZOMBIE)

        respawnStatement(afterDeath.witness, RespawnReason.DEATH, scout.uuid, scout.username)
        respawnStatement(withoutDeath.witness, RespawnReason.END_PORTAL, scout.uuid, scout.username)

        // A breach is a breach: anything counting them reads the event and the tag, so the two paths
        // must be indistinguishable in kind however differently the prose describes what happened.
        val fromDeath = afterDeath.log.of(BodyEvent.WITNESS_REVIVED).single()
        val fromPortal = withoutDeath.log.of(BodyEvent.WITNESS_REVIVED).single()
        assertEquals(fromDeath.event, fromPortal.event)
        assertEquals(fromDeath.outcome, fromPortal.outcome)
        assertEquals(fromDeath.agentId, fromPortal.agentId)
        assertEquals(fromDeath.witness, fromPortal.witness)
    }

    @Test
    fun aRespawnAfterADeathIsStillTheBreachTheFailsafeWasWrittenFor() {
        val under = WitnessUnderTest(scout)

        val action = respawnStatement(under.witness, RespawnReason.DEATH, scout.uuid, scout.username)

        // The case the handler exists for: the removal on death did not beat the client's respawn.
        // The event cannot be cancelled, so by now the world holds a live player for a dead agent.
        assertEquals(WitnessAction.Remove(subjectOf(scout), DEAD_NOTICE), action)
        assertEquals(1, under.log.of(BodyEvent.DIED).size)
        assertEquals(1, under.persisted.size, "a witnessed death must leave the identity durably dead")
        assertEquals(
            EventTag.REVIVED_AFTER_RESPAWN,
            under.log.of(BodyEvent.WITNESS_REVIVED).single().outcome,
        )
        assertEquals(1, under.alerts.size, "a breached permadeath must reach an operator: ${under.alerts}")
    }

    @Test
    fun onlyADeathIsTranslatedAsOne() {
        // Stated over every value the enum has rather than over the two named above, so a reason
        // added by a Minecraft version bump has to be answered here as well as in `respawnCauseOf`.
        val afterDeath = RespawnReason.entries.filter { respawnCauseOf(it) == RespawnCause.AFTER_DEATH }

        assertEquals(listOf(RespawnReason.DEATH), afterDeath)
    }

    @Test
    fun anUnrelatedPlayerRespawningIsNotWitnessedWhateverTheReason() {
        val under = WitnessUnderTest(scout)
        val name = "somebodyelse"
        val stranger = offlineUuid(name)
        // Dead as far as the ledger goes, so even the breach path has an agent it could have named.
        under.witness.sawDeath(scout.uuid, scout.username, KILLED_BY_ZOMBIE)
        val recordsAboutScout = under.log.records.size

        for (reason in RespawnReason.entries) {
            assertEquals(
                WitnessAction.LeaveAlone,
                respawnStatement(under.witness, reason, stranger, name),
                "$reason",
            )
        }

        // Resolved as unmanaged first, on this path as on every other: a stranger's respawn is
        // nobody's business but its own, whatever Loom knows about the agents it does manage.
        assertEquals(recordsAboutScout, under.log.records.size, "Loom has no opinion about a stranger's respawn")
        assertEquals(1, under.persisted.size, "a stranger must leave no tombstone of its own")
    }

    @Test
    fun aRespawnOfADeadAgentIsStillABreachWithNothingRegistered() {
        // The restart the ledger is synced to survive, reached through the adapter's own translation:
        // the file names scout as dead, nothing has registered it, and something on the server has
        // put its player back in the world without anything dying.
        val under =
            WitnessUnderTest(found = LedgerReplay.Readable(listOf(tombstoneFor(scout, tick = 400))))

        val action = respawnStatement(under.witness, RespawnReason.END_PORTAL, scout.uuid, scout.username)

        assertIs<WitnessAction.Remove>(action)
        val revival = under.log.of(BodyEvent.WITNESS_REVIVED).single()
        assertEquals(EventTag.REVIVED_AFTER_RESPAWN, revival.outcome)
        assertEquals("agent-scout", revival.agentId)
        assertEquals(scout.username, revival.witness?.username, "naming what actually connected")
        assertEquals(1, under.alerts.size, "a breached permadeath must reach an operator: ${under.alerts}")
        // The tombstone already is the death, so the breach adds neither a record nor a second line.
        assertEquals(emptyList(), under.log.of(BodyEvent.DIED))
        assertTrue(under.persisted.isEmpty())
    }

    @Test
    fun aDeathRespawnForAnAgentAlreadyRecordedDeadAddsNoSecondDeath() {
        val under = WitnessUnderTest(scout)
        under.witness.sawDeath(scout.uuid, scout.username, KILLED_BY_ZOMBIE)

        val action = respawnStatement(under.witness, RespawnReason.DEATH, scout.uuid, scout.username)

        assertIs<WitnessAction.Remove>(action)
        assertEquals(1, under.log.of(BodyEvent.DIED).size, "one life, one death record")
        assertEquals(1, under.persisted.size)
    }
}

/** One witness and everything it wrote, over the identities it manages. */
private class WitnessUnderTest(
    vararg managedIdentities: ManagedIdentity,
    found: LedgerReplay = LedgerReplay.Readable(emptyList()),
) {
    val log = RecordingLog()
    val alerts = CopyOnWriteArrayList<String>()
    private val ledger = FakeLedger(found)

    val witness =
        PlayerWitness(
            registryOf(*managedIdentities),
            DurablePermadeath(ledger),
            WitnessJournal(log),
            currentTick = { 100 },
            alert = alerts::add,
        )

    /** The tombstones that landed. */
    val persisted get() = ledger.persisted
}
