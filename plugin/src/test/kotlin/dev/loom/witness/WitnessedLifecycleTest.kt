/*
 * What the server's own account of a player means once it is already in.
 *
 * The login is the one moment something can claim to be a managed body without being one, and it has
 * a suite of its own next door. These are the four questions that come after it - an arrival, a
 * departure, a death and a respawn - where the connection is resolved by uuid alone and the answers
 * are about *this* agent rather than about who is connecting.
 *
 * Two properties run through all four and are what most of these are for. Every record names the
 * connection the server actually saw rather than the registration Loom resolved it to, because those
 * two fields are the join to a server log line and to a `playerdata` file and after a login the two
 * readings can differ; and a breach - any of these four reaching a dead agent - is recorded, alerted
 * and removed whether or not a registration happens to exist, since after a restart none does.
 */
package dev.loom.witness

import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventRecord
import dev.loom.body.EventTag
import dev.loom.body.WitnessedPlayer
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The world a body joins in these tests. */
private const val WORLD = "world"

/** A cause string a server would produce. */
private const val KILLED_BY_ZOMBIE = "loomscout1 was slain by Zombie"

/** What a dead or revived body is told. Restated here so a change to the wording fails a test. */
private const val DEAD_NOTICE = "This agent has died. Loom is permadeath: it does not come back."

class WitnessedLifecycleTest {
    private val scout = managed("agent-scout", "loomscout1")
    private val digger = managed("agent-digger", "loomdigger1")

    @Test
    fun aJoinIsRecordedAgainstTheAgentAndNamesThePlayerAndTheWorld() {
        val under = WitnessUnderTest(scout)
        under.tick = 640

        assertEquals(WitnessAction.LeaveAlone, under.witness.sawJoin(scout.uuid, scout.username, WORLD))

        val record = under.log.of(BodyEvent.WITNESS_JOINED).single()
        assertEquals("agent-scout", record.agentId)
        assertEquals(640, record.tick)
        assertTrue(record.detail.contains(WORLD), record.detail)
        // The uuid and the username are what tie a record to a server log and to vanilla playerdata,
        // and neither is recoverable from the agent id afterwards.
        assertEquals(scout.uuid.toString(), record.witness?.uuid)
        assertEquals(scout.username, record.witness?.username)
        assertNull(record.outcome, "an ordinary join has no refusal to report")
    }

    @Test
    fun anOrdinaryQuitIsContinuityRatherThanADeath() {
        val under = WitnessUnderTest(scout)
        under.witness.sawJoin(scout.uuid, scout.username, WORLD)

        under.witness.sawQuit(scout.uuid, scout.username)

        val record = under.log.of(BodyEvent.WITNESS_QUIT).single()
        assertEquals("agent-scout", record.agentId)
        assertTrue(under.persisted.isEmpty(), "a disconnect must write no tombstone")
        assertFalse(under.permadeath.isDead(scout.agentId))
        // The proof that it was continuity: the same identity gets back in.
        assertEquals(LoginVerdict.Admitted(scout), under.witness.admitLogin(scout.uuid, scout.username))
        assertEquals(emptyList(), under.log.of(BodyEvent.DIED))
    }

    @Test
    fun oneDeathProducesExactlyOneAuthoritativeDeathRecordHoweverOftenItIsReported() {
        val under = WitnessUnderTest(scout)

        val first = under.witness.sawDeath(scout.uuid, scout.username, KILLED_BY_ZOMBIE)
        val second = under.witness.sawDeath(scout.uuid, scout.username, KILLED_BY_ZOMBIE)

        // Removal is asked for both times: a player still in the world after a recorded death must
        // not be left there just because the record already existed.
        assertEquals(WitnessAction.Remove(subjectOf(scout), DEAD_NOTICE), first)
        assertEquals(WitnessAction.Remove(subjectOf(scout), DEAD_NOTICE), second)
        val died = under.log.of(BodyEvent.DIED).single()
        assertTrue(died.detail.contains(KILLED_BY_ZOMBIE), died.detail)
        assertTrue(died.detail.contains("Permadeath"), "a death record must say that it is terminal: ${died.detail}")
        assertEquals(scout.uuid.toString(), died.witness?.uuid)
        assertEquals(1, under.persisted.size)
        assertEquals(emptyList(), under.alerts, "an ordinary death is not an operator alarm")
    }

    @Test
    fun nothingButRefusedLoginsFollowsATerminalDeath() {
        val under = WitnessUnderTest(scout)
        under.witness.sawJoin(scout.uuid, scout.username, WORLD)
        under.witness.sawDeath(scout.uuid, scout.username, KILLED_BY_ZOMBIE)

        // Everything a dead body's process still does: the kick arrives as an ordinary quit, and it
        // then reconnects on a loop for as long as it is running.
        under.witness.sawQuit(scout.uuid, scout.username)
        under.witness.admitLogin(scout.uuid, scout.username)
        under.witness.admitLogin(scout.uuid, scout.username)

        val chain = under.log.forAgent("agent-scout").map(BodyEventRecord::event)
        assertEquals(
            listOf(BodyEvent.WITNESS_JOINED, BodyEvent.DIED, BodyEvent.WITNESS_DENIED, BodyEvent.WITNESS_DENIED),
            chain,
            "a terminal death must be the last thing in an agent's chain apart from refused logins",
        )
    }

    @Test
    fun aJoinAfterATerminalDeathIsABreachRatherThanAnArrival() {
        val under = WitnessUnderTest(scout)
        under.witness.sawDeath(scout.uuid, scout.username, KILLED_BY_ZOMBIE)
        // A name the registration does not hold, because after a login the lookup is by uuid alone:
        // a renamed account against a stale registration is enough to make the two readings differ,
        // and driving it with the registration's own name would prove nothing about which was used.
        val presentedName = "loomscout9"

        val action = under.witness.sawJoin(scout.uuid, presentedName, WORLD)

        val turnedBack = subjectOf(scout.agentId, scout.uuid, presentedName)
        assertEquals(WitnessAction.Remove(turnedBack, DEAD_NOTICE), action)
        val record = under.log.of(BodyEvent.WITNESS_REVIVED).single()
        assertEquals(EventTag.REVIVED_AFTER_LOGIN, record.outcome)
        assertEquals("agent-scout", record.agentId, "filed under the agent Loom resolved")
        assertEquals(scout.uuid.toString(), record.witness?.uuid)
        assertEquals(presentedName, record.witness?.username, "and naming what actually connected")
        assertEquals(emptyList(), under.log.of(BodyEvent.WITNESS_JOINED), "a revival is not an ordinary join")
        assertEquals(1, under.alerts.size, "a breached permadeath must reach an operator: ${under.alerts}")
        assertEquals(1, under.log.of(BodyEvent.DIED).size, "the breach must not add a second death")
    }

    @Test
    fun everyRecordOfARegisteredAgentNamesTheConnectionRatherThanTheRegistration() {
        val presentedName = "loomscout9"
        // A `Matched` login agrees on both halves only up to case, so the login path needs a name
        // that differs by case rather than one the registry does not hold at all.
        val shoutedName = scout.username.uppercase(Locale.ROOT)

        // One rule over the ordinary paths as well as the breaches: `witness` is the join to a server
        // log line and to a playerdata file, so it is what the server saw. Filing it from the
        // registration recorded a username nobody offered, which lands that join on the wrong row.
        val joined = WitnessUnderTest(scout)
        joined.witness.sawJoin(scout.uuid, presentedName, WORLD)
        val quit = WitnessUnderTest(scout)
        quit.witness.sawQuit(scout.uuid, presentedName)
        val died = WitnessUnderTest(scout)
        died.witness.sawDeath(scout.uuid, presentedName, KILLED_BY_ZOMBIE)
        val respawned = WitnessUnderTest(scout)
        respawned.witness.sawRespawn(scout.uuid, presentedName, RespawnCause.AFTER_DEATH)
        val refused = WitnessUnderTest(scout, ledger = FakeLedger(LedgerReplay.Unsafe("line 1 is not a tombstone")))
        refused.witness.admitLogin(scout.uuid, shoutedName)

        val recorded =
            listOf(
                joined.log.of(BodyEvent.WITNESS_JOINED).single() to presentedName,
                quit.log.of(BodyEvent.WITNESS_QUIT).single() to presentedName,
                died.log.of(BodyEvent.DIED).single() to presentedName,
                respawned.log.of(BodyEvent.WITNESS_REVIVED).single() to presentedName,
                refused.log.of(BodyEvent.WITNESS_DENIED).single() to shoutedName,
            )
        for ((record, connected) in recorded) {
            assertEquals("agent-scout", record.agentId, "${record.event} is filed under the agent Loom resolved")
            assertEquals(scout.uuid.toString(), record.witness?.uuid, "${record.event}")
            assertEquals(connected, record.witness?.username, "${record.event} must name what connected")
        }
        // And the tombstone is unchanged: it is an authorisation input matched against every later
        // login, so it names the identity Loom registered rather than one connection's own name.
        assertEquals(scout.username, died.persisted.single().username)
        assertEquals(scout.uuid.toString(), died.persisted.single().uuid)
    }

    @Test
    fun aQuitBeforeADeathIsStillContinuityWhateverNameItPresented() {
        val under = WitnessUnderTest(scout)

        under.witness.sawQuit(scout.uuid, "loomscout9")

        // Carrying the presented name must not turn a quit into news: the agent is alive, no
        // tombstone is written, and the same identity gets back in.
        assertEquals(1, under.log.of(BodyEvent.WITNESS_QUIT).size)
        assertTrue(under.persisted.isEmpty(), "a disconnect must write no tombstone")
        assertFalse(under.permadeath.isDead(scout.agentId))
        assertEquals(LoginVerdict.Admitted(scout), under.witness.admitLogin(scout.uuid, scout.username))
    }

    @Test
    fun aJoinAfterATerminalDeathIsABreachWithNothingRegisteredEither() {
        // Exactly the restart the ledger is synced to survive: the file names scout as dead and
        // nothing has registered it again. A failsafe keyed on the registry alone said nothing at
        // all here - no revival, no alert, no removal - about a dead agent's account something on
        // the server had let back in past the refusal.
        val under = WitnessUnderTest(ledger = FakeLedger(LedgerReplay.Readable(listOf(tombstoneFor(scout, 400)))))
        val presentedName = "loomscout9"

        val action = under.witness.sawJoin(scout.uuid, presentedName, WORLD)

        val turnedBack = WitnessSubject(scout.agentId, WitnessedPlayer(scout.uuid.toString(), presentedName))
        assertEquals(WitnessAction.Remove(turnedBack, DEAD_NOTICE), action)
        val record = under.log.of(BodyEvent.WITNESS_REVIVED).single()
        assertEquals(EventTag.REVIVED_AFTER_LOGIN, record.outcome)
        assertEquals("agent-scout", record.agentId, "filed under the agent the tombstone names")
        assertEquals(scout.uuid.toString(), record.witness?.uuid)
        assertEquals(presentedName, record.witness?.username, "and naming what actually connected")
        assertEquals(1, under.alerts.size, "a breached permadeath must reach an operator: ${under.alerts}")
        assertEquals(emptyList(), under.log.of(BodyEvent.WITNESS_JOINED), "a revival is not an ordinary join")
        // The death is already recorded and already durable, so the breach adds neither again.
        assertEquals(emptyList(), under.log.of(BodyEvent.DIED))
        assertTrue(under.persisted.isEmpty())
    }

    @Test
    fun aRespawnAfterATerminalDeathIsABreachWithNothingRegisteredEither() {
        for (cause in RespawnCause.entries) {
            val ledger = FakeLedger(LedgerReplay.Readable(listOf(tombstoneFor(scout, 400))))
            val under = WitnessUnderTest(ledger = ledger)

            val action = under.witness.sawRespawn(scout.uuid, scout.username, cause)

            val turnedBack = WitnessSubject(scout.agentId, WitnessedPlayer(scout.uuid.toString(), scout.username))
            assertEquals(WitnessAction.Remove(turnedBack, DEAD_NOTICE), action, "$cause")
            val record = under.log.of(BodyEvent.WITNESS_REVIVED).single()
            assertEquals(EventTag.REVIVED_AFTER_RESPAWN, record.outcome, "$cause")
            assertEquals("agent-scout", record.agentId, "$cause")
            assertEquals(scout.uuid.toString(), record.witness?.uuid, "$cause")
            assertEquals(1, under.alerts.size, "$cause: ${under.alerts}")
            assertEquals(emptyList(), under.log.of(BodyEvent.DIED), "$cause must add no second death")
            assertTrue(under.persisted.isEmpty(), "$cause must add no second tombstone")
        }
    }

    @Test
    fun aStrangerJoiningOrRespawningIsRecordedNowhereEvenWithTombstonesInTheLedger() {
        val under = WitnessUnderTest(ledger = FakeLedger(LedgerReplay.Readable(listOf(tombstoneFor(scout, 400)))))
        val name = "somebodyelse"
        val stranger = offlineUuid(name)

        // Consulting the ledger after a login must not widen what Loom has an opinion about either:
        // a uuid with neither a registration nor a tombstone is nobody's business but its own.
        assertEquals(WitnessAction.LeaveAlone, under.witness.sawJoin(stranger, name, WORLD))
        for (cause in RespawnCause.entries) {
            assertEquals(WitnessAction.LeaveAlone, under.witness.sawRespawn(stranger, name, cause), "$cause")
        }
        under.witness.sawQuit(stranger, name)

        assertEquals(emptyList(), under.log.records)
        assertEquals(emptyList(), under.alerts)
    }

    @Test
    fun aRespawnRecordsTheDeathItImpliesAndTakesThePlayerBackOut() {
        val under = WitnessUnderTest(scout)

        // No death was witnessed: the respawn is the only evidence Loom has that the player died,
        // which is the case where `PlayerDeathEvent` itself is what went missing.
        val action = under.witness.sawRespawn(scout.uuid, scout.username, RespawnCause.AFTER_DEATH)

        assertEquals(WitnessAction.Remove(subjectOf(scout), DEAD_NOTICE), action)
        val died = under.log.of(BodyEvent.DIED).single()
        assertTrue(died.detail.contains("did not witness"), died.detail)
        assertEquals(1, under.persisted.size, "a respawn must leave the identity durably dead")
        assertEquals(EventTag.REVIVED_AFTER_RESPAWN, under.log.of(BodyEvent.WITNESS_REVIVED).single().outcome)
        assertEquals(1, under.alerts.size, "${under.alerts}")
    }

    @Test
    fun aRespawnAfterAWitnessedDeathAddsNoSecondDeathRecord() {
        val under = WitnessUnderTest(scout)
        under.witness.sawDeath(scout.uuid, scout.username, KILLED_BY_ZOMBIE)

        under.witness.sawRespawn(scout.uuid, scout.username, RespawnCause.AFTER_DEATH)

        val died = under.log.of(BodyEvent.DIED).single()
        assertTrue(died.detail.contains(KILLED_BY_ZOMBIE), "the witnessed cause must survive: ${died.detail}")
        assertEquals(1, under.persisted.size)
        assertEquals(EventTag.REVIVED_AFTER_RESPAWN, under.log.of(BodyEvent.WITNESS_REVIVED).single().outcome)
    }

    @Test
    fun aDeathWhoseTombstoneDidNotLandIsStillRecordedAndRaisedWithTheOperator() {
        val under =
            WitnessUnderTest(scout, digger, ledger = FakeLedger(writeFailure = "could not append: IOException"))

        under.witness.sawDeath(scout.uuid, scout.username, KILLED_BY_ZOMBIE)

        // The record is the run's evidence and must not be lost because the ledger was. What the
        // failed write costs is durability, which is why the run is now sealed.
        assertEquals(1, under.log.of(BodyEvent.DIED).size)
        assertEquals(1, under.alerts.size, "${under.alerts}")
        assertTrue(under.alerts.single().contains("agent-scout"), under.alerts.single())
        val denied = assertIs<LoginVerdict.Denied>(under.witness.admitLogin(digger.uuid, digger.username))
        assertEquals(EventTag.LOGIN_LEDGER_UNSAFE, denied.tag, "a lost tombstone must stop every agent, not just one")
    }

    @Test
    fun everyRecordIsWrittenOnTheThreadTheServerCalledOn() {
        val under = WitnessUnderTest(scout)

        inTandem(1, name = "server-main") {
            under.witness.sawJoin(scout.uuid, scout.username, WORLD)
            under.witness.sawDeath(scout.uuid, scout.username, KILLED_BY_ZOMBIE)
            under.witness.admitLogin(scout.uuid, scout.username)
        }

        // Nothing is queued for a later tick or handed to a pool. A record written elsewhere would
        // be a record whose ordering against the death it qualifies is not guaranteed, and the
        // `Player` the caller was holding may not exist by then.
        assertEquals(3, under.log.threads.size)
        assertEquals(setOf("server-main-0"), under.log.threads.toSet())
    }
}
