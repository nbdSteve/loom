/*
 * Every acceptance criterion of the witness, driven through the five questions a server asks it.
 *
 * The tests are written against the verdicts rather than against the Bukkit adapter, which is the
 * whole reason the judgement is engine-free: a login race, a ledger that cannot be read, and a
 * respawn that should be impossible are all reachable here and none of them is reachable on a
 * server without either luck or a broken disk.
 *
 * Three of them are worth naming, because they are the ones a reading of the code alone would not
 * settle. An unrelated player is exercised on all five paths at once, since "never recorded and
 * never denied" is a claim about every path and not about the login. The concurrent case runs a
 * death and a crowd of logins through one barrier, and asserts the *pairing* of verdicts to records
 * rather than only the verdicts, because a refusal recorded twice and a refusal recorded not at all
 * both read as "denied" from the caller's side. And the thread test asserts which thread each record
 * was appended on, which is the only way to show the witness records inline rather than deferring to
 * somewhere a `PlayerQuitEvent` has already invalidated.
 */
package dev.loom.witness

import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventRecord
import dev.loom.body.EventTag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import kotlin.io.path.appendText
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How many logins race one death. Well past a server's real concurrency, to make the race likely. */
private const val RACING_LOGINS = 16

/** The world a body joins in these tests. */
private const val WORLD = "world"

/** A cause string a server would produce. */
private const val KILLED_BY_ZOMBIE = "loomscout1 was slain by Zombie"

/** How many faults a seal names one by one. Restated here so a change to the bound fails a test. */
private const val NAMED_SEAL_FAULTS = 4

class PlayerWitnessTest {
    private val scout = managed("agent-scout", "loomscout1")
    private val digger = managed("agent-digger", "loomdigger1")

    /** An agent no ledger in these tests ever names, for reaching the seal rather than a death. */
    private val mason = managed("agent-mason", "loommason1")

    @Test
    fun anUnrelatedPlayerIsNeitherRecordedNorDenied() {
        val under = WitnessUnderTest(scout)
        val name = "somebodyelse"
        val stranger = offlineUuid(name)

        assertEquals(LoginVerdict.Unmanaged, under.witness.admitLogin(stranger, name))
        assertEquals(WitnessAction.LeaveAlone, under.witness.sawJoin(stranger, name, WORLD))
        under.witness.sawQuit(stranger, name)
        assertEquals(WitnessAction.LeaveAlone, under.witness.sawDeath(stranger, name, "$name drowned"))
        assertEquals(
            WitnessAction.LeaveAlone,
            under.witness.sawRespawn(stranger, name, RespawnCause.AFTER_DEATH),
        )

        assertEquals(emptyList(), under.log.records, "Loom must have no opinion at all about a stranger")
        assertEquals(emptyList(), under.alerts)
        assertTrue(under.persisted.isEmpty(), "a stranger's death is not Loom's to record")
    }

    @Test
    fun aStrangerIsStillNotDeniedWhenLoomCannotReadItsOwnDeathRecords() {
        val under = WitnessUnderTest(scout, ledger = FakeLedger(LedgerReplay.Unsafe("line 1 is not a tombstone")))

        // Failing closed is a rule about Loom's own agents. Applied to everybody it would make an
        // unreadable Loom file into a server-wide outage, which is a far worse failure than the one
        // it protects against.
        assertEquals(LoginVerdict.Unmanaged, under.witness.admitLogin(offlineUuid("human"), "human"))
        assertEquals(emptyList(), under.log.records)
    }

    @Test
    fun aManagedLoginIsAdmittedAndTheLoginItselfIsNotEvidence() {
        val under = WitnessUnderTest(scout)

        assertEquals(LoginVerdict.Admitted(scout), under.witness.admitLogin(scout.uuid, scout.username))

        // A login that succeeds is not a fact about the run; the join that follows it is, and the
        // join is what the server has actually done. Recording both would double every arrival.
        assertEquals(emptyList(), under.log.records)
    }

    @Test
    fun aDeadIdentityIsDeniedWhenItsProcessReconnects() {
        val under = WitnessUnderTest(scout)
        under.witness.sawDeath(scout.uuid, scout.username, KILLED_BY_ZOMBIE)

        val verdict = under.witness.admitLogin(scout.uuid, scout.username)

        val denied = assertIs<LoginVerdict.Denied>(verdict)
        assertEquals(EventTag.LOGIN_DEAD_IDENTITY, denied.tag)
        assertTrue(denied.notice.contains("permadeath"), denied.notice)
        val record = under.log.of(BodyEvent.WITNESS_DENIED).single()
        assertEquals(EventTag.LOGIN_DEAD_IDENTITY, record.outcome)
        assertEquals("agent-scout", record.agentId)
    }

    @Test
    fun aDeadIdentityIsStillDeniedAfterAPluginOrServerRestart() {
        val path = Files.createTempDirectory("loom-witness").resolve("player-deaths.jsonl")
        val before = WitnessUnderTest(scout, ledger = FileDeathLedger(path))
        before.witness.sawDeath(scout.uuid, scout.username, KILLED_BY_ZOMBIE)

        // A whole new witness over the same file: new registry, new journal, new permadeath. The
        // only thing carried over is the ledger on disk, which is the point.
        val after = WitnessUnderTest(scout, ledger = FileDeathLedger(path))

        val denied = assertIs<LoginVerdict.Denied>(after.witness.admitLogin(scout.uuid, scout.username))
        assertEquals(EventTag.LOGIN_DEAD_IDENTITY, denied.tag)
        assertNull(after.permadeath.sealReason, "a well-formed ledger must not seal the run that reads it")
    }

    @Test
    fun aDeadIdentityIsDeniedWithNothingRegisteredAtAll() {
        // The gap a registry-keyed admission leaves: a registration lives in one process's memory,
        // and the body process driving the account is started by something Loom does not own, so it
        // can reconnect before - or without - anything registering the identity again.
        val under = WitnessUnderTest(ledger = FakeLedger(LedgerReplay.Readable(listOf(tombstoneFor(scout, 400)))))

        val denied = assertIs<LoginVerdict.Denied>(under.witness.admitLogin(scout.uuid, scout.username))

        assertEquals(EventTag.LOGIN_DEAD_IDENTITY, denied.tag)
        assertEquals(0, under.witness.registry.size, "the refusal must not have needed a registration")
        val record = under.log.of(BodyEvent.WITNESS_DENIED).single()
        assertEquals("agent-scout", record.agentId, "the refusal is filed under the agent the tombstone names")
        assertEquals(EventTag.LOGIN_DEAD_IDENTITY, record.outcome)
        assertEquals(scout.uuid.toString(), record.witness?.uuid)
        assertEquals(scout.username, record.witness?.username)
    }

    @Test
    fun anUnregisteredDeadIdentityIsStillDeniedAfterARestart() {
        val path = Files.createTempDirectory("loom-witness").resolve("player-deaths.jsonl")
        val before = WitnessUnderTest(scout, ledger = FileDeathLedger(path))
        before.witness.sawDeath(scout.uuid, scout.username, KILLED_BY_ZOMBIE)

        // The restart as it actually happens: the file survives and the registry does not, because
        // nothing in Loom registers a player and the cutover that will runs after the plugin enables.
        val after = WitnessUnderTest(ledger = FileDeathLedger(path))

        val denied = assertIs<LoginVerdict.Denied>(after.witness.admitLogin(scout.uuid, scout.username))
        assertEquals(EventTag.LOGIN_DEAD_IDENTITY, denied.tag)
        assertEquals("agent-scout", after.log.of(BodyEvent.WITNESS_DENIED).single().agentId)
        assertNull(after.permadeath.sealReason, "a well-formed ledger must not seal the run that reads it")
    }

    @Test
    fun aTombstonedIdentityIsDeniedWhenEitherHalfOfItConnects() {
        val under = WitnessUnderTest(ledger = FakeLedger(LedgerReplay.Readable(listOf(tombstoneFor(scout, 400)))))

        // A dead agent's account reconnecting under a fresh name, and a fresh account reconnecting
        // under a dead agent's name. Both are the same account trying to get back in, and refusing
        // only the pair would let either through.
        val underNewName = assertIs<LoginVerdict.Denied>(under.witness.admitLogin(scout.uuid, "loomscout9"))
        val fromNewAccount =
            assertIs<LoginVerdict.Denied>(under.witness.admitLogin(UUID.randomUUID(), scout.username))

        assertEquals(EventTag.LOGIN_DEAD_IDENTITY, underNewName.tag)
        assertEquals(EventTag.LOGIN_DEAD_IDENTITY, fromNewAccount.tag)
        assertEquals(2, under.log.of(BodyEvent.WITNESS_DENIED).size)
    }

    @Test
    fun aDeadIdentityRefusalNamesWhatActuallyConnected() {
        val under = WitnessUnderTest(ledger = FakeLedger(LedgerReplay.Readable(listOf(tombstoneFor(scout, 400)))))
        val strangeName = "loomscout9"
        val strangeUuid = UUID.randomUUID()

        under.witness.admitLogin(scout.uuid, strangeName)
        under.witness.admitLogin(strangeUuid, scout.username)

        // A tombstone is matched on either half, so what connected need not be what it names. Both
        // are stated, and which field carries which is the load-bearing part: the `witness` fields
        // are the *connection*, because they are the join to a server log line and to a playerdata
        // file, so filling them from the tombstone named a player that never presented itself.
        val (underNewName, fromNewAccount) = under.log.of(BodyEvent.WITNESS_DENIED)
        assertEquals(scout.uuid.toString(), underNewName.witness?.uuid)
        assertEquals(strangeName, underNewName.witness?.username, "the name nobody offered must not be recorded")
        assertEquals(strangeUuid.toString(), fromNewAccount.witness?.uuid, "nor a dead agent's uuid for another")
        assertEquals(scout.username, fromNewAccount.witness?.username)
        // And the tombstone's own identity is kept, in the detail, which is what shows an operator
        // that only one half of the recorded identity matched.
        assertTrue(underNewName.detail.contains(strangeName), underNewName.detail)
        assertTrue(underNewName.detail.contains(scout.uuid.toString()), underNewName.detail)
        assertTrue(underNewName.detail.contains(scout.username), underNewName.detail)
        assertTrue(fromNewAccount.detail.contains(strangeUuid.toString()), fromNewAccount.detail)
        assertTrue(fromNewAccount.detail.contains(scout.username), fromNewAccount.detail)
        assertTrue(fromNewAccount.detail.contains(scout.uuid.toString()), fromNewAccount.detail)
        // Bounded like every other detail, so a long name cannot push the statement off the end.
        for (record in listOf(underNewName, fromNewAccount)) {
            assertTrue(record.detail.length <= MAX_WITNESS_DETAIL_CHARS, record.detail)
            assertEquals(EventTag.LOGIN_DEAD_IDENTITY, record.outcome)
            assertEquals("agent-scout", record.agentId)
        }
    }

    @Test
    fun anAgentWhoseTombstoneOnlyPartlyReadsIsStillDeniedWithNothingRegistered() {
        // The one remaining way a tombstoned agent walked back in: a hand-edited uuid meant neither
        // index held the line, so the agent's own username - still in the file - matched nothing, and
        // the seal never reached it because the seal is only asked about a matched registration.
        val mangled = tombstoneFor(scout, 400).copy(uuid = "not-a-uuid")
        val under = WitnessUnderTest(ledger = FakeLedger(LedgerReplay.Readable(listOf(mangled))))

        val denied = assertIs<LoginVerdict.Denied>(under.witness.admitLogin(scout.uuid, scout.username))

        assertEquals(EventTag.LOGIN_DEAD_IDENTITY, denied.tag, "the file plainly names this agent as dead")
        assertEquals(scout.agentId, denied.agentId)
        assertTrue(denied.notice.contains("permadeath"), denied.notice)
        val record = under.log.of(BodyEvent.WITNESS_DENIED).single()
        assertEquals("agent-scout", record.agentId, "filed under the one field that did parse")
        // The record names only what actually connected. There is no stored identity to put in the
        // witness fields, and inventing a uuid would file a player Loom made up against a real agent.
        assertEquals(scout.uuid.toString(), record.witness?.uuid)
        assertEquals(scout.username, record.witness?.username)
        assertTrue(record.detail.contains(scout.uuid.toString()), record.detail)
        assertTrue(record.detail.contains(scout.username), record.detail)
        assertFalse(record.detail.contains("not-a-uuid"), "the unreadable field must not be quoted: ${record.detail}")
        assertTrue(record.detail.length <= MAX_WITNESS_DETAIL_CHARS, record.detail)
        assertNotNull(under.permadeath.sealReason, "the part that could not be read must still fail closed")
    }

    @Test
    fun theSealOverAPartlyReadableTombstoneDoesNotDenyWhatItJustCompared() {
        val mangled = tombstoneFor(scout, 400).copy(uuid = "not-a-uuid")
        val under =
            WitnessUnderTest(
                digger,
                ledger = FakeLedger(LedgerReplay.Readable(listOf(mangled))),
            )
        val stranger = "somebodyelse"

        // The same tombstone that seals the run is the one whose username IS compared and refused,
        // so the operator's only explanation of the seal must not say Loom can compare nothing.
        val denied = assertIs<LoginVerdict.Denied>(under.witness.admitLogin(scout.uuid, scout.username))
        assertEquals(EventTag.LOGIN_DEAD_IDENTITY, denied.tag)
        for (said in under.alerts + under.log.of(BodyEvent.WITNESS_DENIED).map(BodyEventRecord::detail)) {
            assertFalse(
                said.contains("does not name an identity"),
                "the seal must not claim Loom cannot compare a login it just refused one against: $said",
            )
        }
        val reason = assertNotNull(under.permadeath.sealReason)
        assertTrue(reason.contains("tombstone 1"), "the seal still names the line by number: $reason")
        assertFalse(reason.contains("not-a-uuid"), "and still quotes no file content: $reason")

        // And the fail-closed rules it states are unchanged: a registered agent the ledger says
        // nothing about is refused, while a stranger is admitted and recorded nowhere at all.
        val sealed = assertIs<LoginVerdict.Denied>(under.witness.admitLogin(digger.uuid, digger.username))
        assertEquals(EventTag.LOGIN_LEDGER_UNSAFE, sealed.tag)
        val before = under.log.records.size
        assertEquals(LoginVerdict.Unmanaged, under.witness.admitLogin(offlineUuid(stranger), stranger))
        assertEquals(before, under.log.records.size, "a stranger is recorded nowhere, sealed ledger or not")
    }

    @Test
    fun aPartlyReadableTombstoneRefusesTheNameHoweverItIsCapitalised() {
        val mangled = tombstoneFor(scout, 400).copy(uuid = "not-a-uuid")
        val under = WitnessUnderTest(ledger = FakeLedger(LedgerReplay.Readable(listOf(mangled))))

        // The game treats a username as case-insensitive at the door, so a body process reconnecting
        // with different capitalisation is the same account and must meet the same refusal.
        val denied =
            assertIs<LoginVerdict.Denied>(
                under.witness.admitLogin(UUID.randomUUID(), scout.username.uppercase(Locale.ROOT)),
            )

        assertEquals(EventTag.LOGIN_DEAD_IDENTITY, denied.tag)
        assertEquals(scout.agentId, denied.agentId)
    }

    @Test
    fun aLedgerCorruptedTwoWaysNamesBothCorruptionsToTheOperator() {
        val path = Files.createTempDirectory("loom-witness").resolve("player-deaths.jsonl")
        val before = WitnessUnderTest(scout, digger, ledger = FileDeathLedger(path))
        before.witness.sawDeath(scout.uuid, scout.username, KILLED_BY_ZOMBIE)
        before.witness.sawDeath(digger.uuid, digger.username, "loomdigger1 fell out of the world")
        // Two independent faults in one file: digger's uuid hand-edited, and a third line the crash
        // that ended the last run tore off mid-append.
        val mangled = path.readLines().map { it.replace(digger.uuid.toString(), "not-a-uuid") }
        path.writeText(mangled.joinToString("\n", postfix = "\n"))
        path.appendText("""{"agentId":"agent-third","uuid":""")

        // Registered so the seal itself can be reached: a login the ledger says nothing about is the
        // only one that reports the seal, since a tombstoned one is reported as the death instead.
        val after = WitnessUnderTest(mason, ledger = FileDeathLedger(path))

        // Each is a different thing for an operator to go and look at, so both are stated - naming
        // only the one found first left the other invisible in the alert and in the record alike.
        val alert = after.alerts.single()
        assertTrue(alert.contains("tombstone 2 of the death ledger"), alert)
        assertTrue(alert.contains("line 3"), alert)
        // And both survive into the durable record, which is where an operator reads them back after
        // the fact. A fixed notice repeated ahead of them spent 114 of the 192 characters a detail
        // has and cut the second fault off entirely, saying nothing the record's own tag did not.
        val sealed = assertIs<LoginVerdict.Denied>(after.witness.admitLogin(mason.uuid, mason.username))
        assertEquals(EventTag.LOGIN_LEDGER_UNSAFE, sealed.tag)
        val detail = after.log.of(BodyEvent.WITNESS_DENIED).last().detail
        assertTrue(detail.contains("tombstone 2 of the death ledger"), detail)
        assertTrue(detail.contains("line 3"), detail)
        assertTrue(detail.length <= MAX_WITNESS_DETAIL_CHARS, detail)

        // And every readable death is still enforced while the seal still holds, with a stranger
        // still admitted and recorded nowhere.
        assertNotNull(after.permadeath.sealReason)
        for (identity in listOf(scout, digger)) {
            val denied = assertIs<LoginVerdict.Denied>(after.witness.admitLogin(identity.uuid, identity.username))
            assertEquals(EventTag.LOGIN_DEAD_IDENTITY, denied.tag, "${identity.agentId} is named by the ledger")
        }
        val recorded = after.log.records.size
        assertEquals(LoginVerdict.Unmanaged, after.witness.admitLogin(offlineUuid("somebodyelse"), "somebodyelse"))
        assertEquals(recorded, after.log.records.size, "a stranger is recorded nowhere, sealed ledger or not")
    }

    @Test
    fun aCrowdedSealStillNamesTheTornLineInTheRecordItRefusesWith() {
        // The record is where an operator reads the faults back after the fact, and the torn line is
        // the one that says a death cannot be named at all. Named after the tombstone faults it was
        // the entry the elision dropped, and the elision itself was then cut off the end of a detail
        // that had overflowed - so the record said neither which line nor that anything was missing.
        val mangled =
            (1..5).map { tombstoneFor(managed("agent-lost$it", "loomlost$it"), 400).copy(uuid = "not-a-uuid") }
        val under =
            WitnessUnderTest(
                mason,
                ledger =
                    FakeLedger(
                        LedgerReplay.Unsafe(
                            reason = "death ledger line 6 is not a tombstone: JsonDecodingException",
                            tombstones = mangled,
                        ),
                    ),
            )

        val sealed = assertIs<LoginVerdict.Denied>(under.witness.admitLogin(mason.uuid, mason.username))

        assertEquals(EventTag.LOGIN_LEDGER_UNSAFE, sealed.tag)
        val detail = under.log.of(BodyEvent.WITNESS_DENIED).single().detail
        assertTrue(detail.contains("death ledger line 6"), detail)
        assertTrue(detail.endsWith("more"), "what the seal left out must reach the record too: $detail")
        assertTrue(detail.length <= MAX_WITNESS_DETAIL_CHARS, detail)

        // The record's 192-character budget is the record's alone. A server log line has none, so the
        // alert names every fault the count bound allows - and it is the one place a fault the record
        // had to elide can still be recovered from, which sharing one string took away.
        val alert = under.alerts.single()
        assertTrue(alert.contains("death ledger line 6"), alert)
        for (ordinal in 1 until NAMED_SEAL_FAULTS) {
            assertTrue(alert.contains("tombstone $ordinal"), "the alert names every fault it may: $alert")
        }
        assertTrue(
            alert.count { it == ';' } > detail.count { it == ';' },
            "the alert must name more faults than the bounded record does: $alert",
        )
    }

    @Test
    fun aStrangerIsStillAdmittedAgainstAPartlyReadableTombstone() {
        val mangled = tombstoneFor(scout, 400).copy(uuid = "not-a-uuid")
        val under = WitnessUnderTest(ledger = FakeLedger(LedgerReplay.Readable(listOf(mangled))))
        val stranger = "somebodyelse"

        // Indexing the halves that read must not widen what Loom has an opinion about: a connection
        // matching neither a registration nor any death index is nobody's business but its own.
        assertEquals(LoginVerdict.Unmanaged, under.witness.admitLogin(offlineUuid(stranger), stranger))
        assertEquals(emptyList(), under.log.records)
    }

    @Test
    fun aDeathATornLedgerCouldStillBeReadForIsDeniedWithNothingRegistered() {
        val path = Files.createTempDirectory("loom-witness").resolve("player-deaths.jsonl")
        val before = WitnessUnderTest(scout, digger, ledger = FileDeathLedger(path))
        before.witness.sawDeath(scout.uuid, scout.username, KILLED_BY_ZOMBIE)
        before.witness.sawDeath(digger.uuid, digger.username, "loomdigger1 fell out of the world")
        // The crash the ledger is synced against: the line Loom was writing when the process died.
        path.appendText("""{"agentId":"agent-third","uuid":""")

        val after = WitnessUnderTest(ledger = FileDeathLedger(path))

        // The seal alone only refuses identities a registration names, and after a restart there are
        // none - so discarding the whole ledger over its last line let both of these walk back in.
        for (identity in listOf(scout, digger)) {
            val denied = assertIs<LoginVerdict.Denied>(after.witness.admitLogin(identity.uuid, identity.username))
            assertEquals(EventTag.LOGIN_DEAD_IDENTITY, denied.tag, "${identity.agentId} is named by a whole line")
        }
        assertEquals(
            listOf("agent-scout", "agent-digger"),
            after.log.of(BodyEvent.WITNESS_DENIED).map(BodyEventRecord::agentId),
        )
        // And the line that could not be read still fails closed, and still says so to an operator.
        assertNotNull(after.permadeath.sealReason)
        assertTrue(after.alerts.single().contains("line 3"), after.alerts.single())
    }

    @Test
    fun aStrangerIsStillAdmittedAndRecordedNowhereAgainstATornLedger() {
        val path = Files.createTempDirectory("loom-witness").resolve("player-deaths.jsonl")
        val before = WitnessUnderTest(scout, ledger = FileDeathLedger(path))
        before.witness.sawDeath(scout.uuid, scout.username, KILLED_BY_ZOMBIE)
        path.appendText("""{"agentId":"agent-third","uuid":""")

        val after = WitnessUnderTest(ledger = FileDeathLedger(path))
        val stranger = "somebodyelse"

        // Failing closed is a rule about Loom's own agents, and enforcing the deaths a torn ledger
        // does name must not turn it into one about everybody: an unrelated player is still admitted
        // with nothing recorded, not even a refusal.
        assertEquals(LoginVerdict.Unmanaged, after.witness.admitLogin(offlineUuid(stranger), stranger))
        assertEquals(emptyList(), after.log.records)
    }

    @Test
    fun aStrangerIsAdmittedAndRecordedNowhereEvenWithTombstonesInTheLedger() {
        val under =
            WitnessUnderTest(
                digger,
                ledger = FakeLedger(LedgerReplay.Readable(listOf(tombstoneFor(scout, 400)))),
            )
        val stranger = "somebodyelse"

        // Consulting the ledger before the registry must not widen what Loom has an opinion about.
        // A connection matching neither a registration nor a tombstone is nobody's business but its
        // own, and a refusal recorded here would make Loom a liability on any server it is on.
        assertEquals(
            LoginVerdict.Unmanaged,
            under.witness.admitLogin(offlineUuid(stranger), stranger),
        )
        assertEquals(emptyList(), under.log.records)
        assertEquals(LoginVerdict.Admitted(digger), under.witness.admitLogin(digger.uuid, digger.username))
        assertEquals(emptyList(), under.log.records, "an agent the ledger says nothing about is admitted in silence")
    }

    @Test
    fun aTombstonedIdentityReadsAsDeadRatherThanAsASealedLedgerEvenWithNothingRegistered() {
        val under =
            WitnessUnderTest(
                digger,
                ledger =
                    FakeLedger(
                        found = LedgerReplay.Readable(listOf(tombstoneFor(scout, 400))),
                        writeFailure = "could not append to the death ledger: IOException",
                    ),
            )
        // Somebody else's lost tombstone seals the run, which would refuse every managed login.
        under.witness.sawDeath(digger.uuid, digger.username, "loomdigger1 fell out of the world")

        val denied = assertIs<LoginVerdict.Denied>(under.witness.admitLogin(scout.uuid, scout.username))

        // The seal would have turned this connection away too, and would have said the wrong thing
        // about it. "This agent died" is what an operator needs to read; "Loom cannot tell" hides it.
        assertEquals(EventTag.LOGIN_DEAD_IDENTITY, denied.tag)
        assertEquals(
            EventTag.LOGIN_DEAD_IDENTITY,
            under.log.of(BodyEvent.WITNESS_DENIED).single().outcome,
        )
    }

    @Test
    fun anAgentThatDiedUnderAnotherAccountIsStillRefusedUnderItsNewOne() {
        // The one death the tombstone's own uuid and username cannot catch: the same agent
        // re-registered against a different account after it died. The agent id is what died.
        val reborn = ManagedIdentity(scout.agentId, "loomscout9", offlineUuid("loomscout9"))
        val under =
            WitnessUnderTest(
                reborn,
                ledger = FakeLedger(LedgerReplay.Readable(listOf(tombstoneFor(scout, 400)))),
            )

        val denied = assertIs<LoginVerdict.Denied>(under.witness.admitLogin(reborn.uuid, reborn.username))

        assertEquals(EventTag.LOGIN_DEAD_IDENTITY, denied.tag)
        assertEquals("agent-scout", under.log.of(BodyEvent.WITNESS_DENIED).single().agentId)
    }

    @Test
    fun aSealedLedgerRefusesAManagedLoginAndSaysSoBeforeItIsAsked() {
        val under = WitnessUnderTest(scout, ledger = FakeLedger(LedgerReplay.Unsafe("line 3 is not a tombstone")))

        // The alert is raised at construction, so an operator learns why nothing can connect rather
        // than waiting for a login to fail.
        assertEquals(1, under.alerts.size, "${under.alerts}")
        assertTrue(under.alerts.single().contains("line 3 is not a tombstone"), under.alerts.single())

        val denied = assertIs<LoginVerdict.Denied>(under.witness.admitLogin(scout.uuid, scout.username))

        assertEquals(EventTag.LOGIN_LEDGER_UNSAFE, denied.tag)
        val record = under.log.of(BodyEvent.WITNESS_DENIED).single()
        assertEquals(EventTag.LOGIN_LEDGER_UNSAFE, record.outcome)
        assertTrue(record.detail.contains("line 3"), "the record must carry the reason: ${record.detail}")
    }

    @Test
    fun aKnownDeathIsReportedRatherThanTheSealWhenBothWouldRefuse() {
        val under =
            WitnessUnderTest(
                scout,
                ledger =
                    FakeLedger(
                        found = LedgerReplay.Unsafe("line 3 is not a tombstone"),
                        writeFailure = "could not append to the death ledger: IOException",
                    ),
            )
        under.witness.sawDeath(scout.uuid, scout.username, KILLED_BY_ZOMBIE)

        val denied = assertIs<LoginVerdict.Denied>(under.witness.admitLogin(scout.uuid, scout.username))

        // Both refuse, so the caller cannot tell them apart - but an operator reading the record
        // has to, and "this agent died" is the informative one. "Loom cannot tell" would hide it.
        assertEquals(EventTag.LOGIN_DEAD_IDENTITY, denied.tag)
    }

    @Test
    fun halfAManagedIdentityIsRefusedAndTheRecordNamesWhatActuallyConnected() {
        val under = WitnessUnderTest(scout)
        val impostorUuid = UUID.randomUUID()
        val impostorName = "loomscout9"

        // Both directions of the mismatch, because each is where one of the two fields differs: a
        // registered name presented from a foreign account, and a registered account presenting a
        // name nobody registered.
        val byName = assertIs<LoginVerdict.Denied>(under.witness.admitLogin(impostorUuid, scout.username))
        val byUuid = assertIs<LoginVerdict.Denied>(under.witness.admitLogin(scout.uuid, impostorName))

        assertEquals(EventTag.LOGIN_IDENTITY_MISMATCH, byName.tag)
        assertEquals(EventTag.LOGIN_IDENTITY_MISMATCH, byUuid.tag)
        val (fromForeignAccount, underForeignName) = under.log.of(BodyEvent.WITNESS_DENIED)
        // The `witness` fields are the connection, on this refusal as on every other: they are the
        // join to a server log line and to a playerdata file, so a uuid or a name that never
        // presented itself lands that join on the wrong row.
        assertEquals(impostorUuid.toString(), fromForeignAccount.witness?.uuid)
        assertEquals(scout.username, fromForeignAccount.witness?.username)
        assertEquals(scout.uuid.toString(), underForeignName.witness?.uuid)
        assertEquals(impostorName, underForeignName.witness?.username, "a name nobody offered must not be recorded")
        for (record in listOf(fromForeignAccount, underForeignName)) {
            assertEquals("agent-scout", record.agentId, "the refusal is filed against the agent that was claimed")
            // And the registration it claimed is kept, in the detail, which is what shows an
            // operator which half of that registration the connection actually matched.
            assertTrue(record.detail.contains(scout.username), record.detail)
            assertTrue(record.detail.contains(scout.uuid.toString()), record.detail)
            assertTrue(record.detail.length <= MAX_WITNESS_DETAIL_CHARS, record.detail)
        }
        assertTrue(fromForeignAccount.detail.contains(impostorUuid.toString()), fromForeignAccount.detail)
        assertTrue(underForeignName.detail.contains(impostorName), underForeignName.detail)
        assertFalse(under.permadeath.isDead(scout.agentId), "a refused impostor must not end the agent it claimed")
    }

    @Test
    fun loginsRacingADeathAreAllAnsweredAndEveryRefusalIsRecordedExactlyOnce() {
        val under = WitnessUnderTest(scout)

        val answers =
            inTandem(RACING_LOGINS + 1, name = "login") { index ->
                if (index == 0) {
                    under.witness.sawDeath(scout.uuid, scout.username, KILLED_BY_ZOMBIE)
                } else {
                    under.witness.admitLogin(scout.uuid, scout.username)
                }
            }

        val verdicts = answers.drop(1).map { assertIs<LoginVerdict>(it) }
        // Either answer is correct: a login that reached the witness before the death was legitimate
        // then. What must not happen is a third outcome - a crash, a lost verdict, or a refusal for
        // the wrong reason - and after the dust settles the identity must be refused for good.
        for (verdict in verdicts) {
            when (verdict) {
                is LoginVerdict.Admitted -> assertEquals(scout, verdict.identity)
                is LoginVerdict.Denied -> assertEquals(EventTag.LOGIN_DEAD_IDENTITY, verdict.tag)
                LoginVerdict.Unmanaged -> error("a registered identity must never read as unmanaged")
            }
        }
        assertEquals(
            verdicts.count { it is LoginVerdict.Denied },
            under.log.of(BodyEvent.WITNESS_DENIED).size,
            "one record per refusal: a refusal recorded twice or not at all reads the same to the caller",
        )
        assertEquals(1, under.log.of(BodyEvent.DIED).size, "the death must still be recorded exactly once")
        assertEquals(1, under.persisted.size)
        val settled = assertIs<LoginVerdict.Denied>(under.witness.admitLogin(scout.uuid, scout.username))
        assertEquals(EventTag.LOGIN_DEAD_IDENTITY, settled.tag)
    }

    @Test
    fun oneAgentsDeathDoesNotRefuseAnother() {
        val under = WitnessUnderTest(scout, digger)
        under.witness.sawDeath(scout.uuid, scout.username, KILLED_BY_ZOMBIE)

        assertEquals(LoginVerdict.Admitted(digger), under.witness.admitLogin(digger.uuid, digger.username))
        assertEquals(WitnessAction.LeaveAlone, under.witness.sawJoin(digger.uuid, digger.username, WORLD))
        assertEquals(1, under.log.forAgent("agent-digger").size)
        assertEquals(BodyEvent.WITNESS_JOINED, under.log.forAgent("agent-digger").single().event)
    }
}
