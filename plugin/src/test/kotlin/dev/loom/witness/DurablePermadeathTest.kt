/*
 * "Exactly one death, and it stays a death" - the two acceptance criteria this type owns.
 *
 * Exactly-once is tested under contention rather than in sequence, because the sequential case is
 * not the one that fails: a death is witnessed on the server main thread while `PlayerDeathEvent`
 * and a respawn that slipped past a kick can both report the same death, and the claim has to be
 * taken under the same lock as the write for one of them to lose. So the test drives many threads
 * at one identity through a barrier and insists on one `Terminal` and one tombstone.
 *
 * Durability is tested through the real file at least once. A fake ledger can show the type asks
 * for a write; only a second [DurablePermadeath] over the same path can show that a restart still
 * refuses the identity, which is what the criterion actually says.
 *
 * And the replay is tested as an *authorisation input* rather than only as a set of agent ids. A
 * restart leaves the ledger on disk and every registration gone with the process that held it, so
 * "which uuid and which username are tombstoned" is the only question there is anything left to ask
 * - which is why a tombstone that cannot be read back into an identity has a test of its own saying
 * it seals rather than being quietly passed over.
 */
package dev.loom.witness

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import kotlin.io.path.appendText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How many threads race at one death. Well past a server's own two, to make the race likely. */
private const val RACING_THREADS = 16

/** How many faults a seal names one by one. Restated here so a change to the bound fails a test. */
private const val NAMED_SEAL_FAULTS = 4

class DurablePermadeathTest {
    private val scout = managed("agent-scout", "loomscout1")
    private val digger = managed("agent-digger", "loomdigger1")
    private val mason = managed("agent-mason", "loommason1")
    private val tanner = managed("agent-tanner", "loomtanner1")
    private val hauler = managed("agent-hauler", "loomhauler1")

    @Test
    fun aFreshLedgerHoldsNobodyDeadAndSealsNothing() {
        val permadeath = DurablePermadeath(FakeLedger())

        assertFalse(permadeath.isDead(scout.agentId))
        assertNull(permadeath.sealReason, "an empty ledger is a readable ledger, and must admit logins")
    }

    @Test
    fun aPreviousRunsDeathIsAlreadyDeadBeforeAnythingIsAsked() {
        val ledger = FakeLedger(LedgerReplay.Readable(listOf(tombstoneFor(scout, tick = 400))))

        val permadeath = DurablePermadeath(ledger)

        // No call between construction and this question. That ordering is the requirement that
        // tombstones are replayed before login admission begins, stated as a test.
        assertTrue(permadeath.isDead(scout.agentId), "a replayed tombstone must be dead from the start")
        assertFalse(permadeath.isDead(digger.agentId))
        assertNull(permadeath.sealReason)
    }

    @Test
    fun anUnreadableLedgerSealsAtConstructionAndStaysSealed() {
        val permadeath = DurablePermadeath(FakeLedger(LedgerReplay.Unsafe("line 3 is not a tombstone")))

        val reason = assertNotNull(permadeath.sealReason, "an unsafe ledger must seal, not read as empty")
        assertEquals("line 3 is not a tombstone", reason)
        // The seal is what refuses logins; `isDead` is deliberately still false, because the point
        // is that Loom does not know who is dead. A fail-closed answer that leaned on `isDead`
        // would be the silent revival the criterion forbids.
        assertFalse(permadeath.isDead(scout.agentId))
        assertNotNull(permadeath.sealReason, "the seal is one-way and must survive being read")
    }

    @Test
    fun oneIdentityDiesOnceHoweverManyTimesItIsReported() {
        val ledger = FakeLedger()
        val permadeath = DurablePermadeath(ledger)

        assertEquals(DeathRecording.Terminal, permadeath.recordDeath(scout, tick = 100))
        assertEquals(DeathRecording.AlreadyRecorded, permadeath.recordDeath(scout, tick = 140))
        assertEquals(DeathRecording.AlreadyRecorded, permadeath.recordDeath(scout, tick = 180))

        assertEquals(1, ledger.persisted.size, "a second death must write no second tombstone")
        assertTrue(permadeath.isDead(scout.agentId))
    }

    @Test
    fun aTombstoneNamesTheWholeIdentityAndTheTickItDiedAt() {
        val ledger = FakeLedger()

        DurablePermadeath(ledger).recordDeath(scout, tick = 1234)

        assertEquals(tombstoneFor(scout, tick = 1234), ledger.persisted.single())
    }

    @Test
    fun deathsOfDifferentAgentsDoNotShadowEachOther() {
        val ledger = FakeLedger()
        val permadeath = DurablePermadeath(ledger)

        permadeath.recordDeath(scout, tick = 100)

        assertTrue(permadeath.isDead(scout.agentId))
        assertFalse(permadeath.isDead(digger.agentId), "one agent's death must not end another's life")
        assertEquals(DeathRecording.Terminal, permadeath.recordDeath(digger, tick = 200))
        assertEquals(2, ledger.persisted.size)
    }

    @Test
    fun aTombstoneThatWouldNotWriteSealsAndTheAgentIsStillDead() {
        val ledger = FakeLedger(writeFailure = "could not append to the death ledger: AccessDeniedException")
        val permadeath = DurablePermadeath(ledger)

        val recording = permadeath.recordDeath(scout, tick = 100)

        val unpersisted = assertIs<DeathRecording.Unpersisted>(recording)
        assertEquals("could not append to the death ledger: AccessDeniedException", unpersisted.reason)
        // Both halves matter. The agent is dead for this process, because it is - and the seal is
        // what stops a restart from quietly admitting it again, since the tombstone is not there.
        assertTrue(permadeath.isDead(scout.agentId))
        assertEquals(unpersisted.reason, permadeath.sealReason)
        assertTrue(ledger.persisted.isEmpty())
    }

    @Test
    fun theSealStatesEveryFaultItFoundRatherThanOne() {
        val permadeath =
            DurablePermadeath(
                FakeLedger(
                    found = LedgerReplay.Unsafe("line 1 is not a tombstone"),
                    writeFailure = "could not append to the death ledger: IOException",
                ),
            )

        permadeath.recordDeath(scout, tick = 100)

        // Two independent faults, and each is a different thing for an operator to go and look at:
        // a file that will not read back and a disk that will not take a write. Naming one hid the
        // other in the alert and in the record that carry the seal, so both are stated, oldest
        // first - the earliest is usually the cause and the later one its consequence.
        val reason = assertNotNull(permadeath.sealReason)
        assertTrue(reason.contains("line 1 is not a tombstone"), reason)
        assertTrue(reason.contains("could not append to the death ledger"), reason)
        assertTrue(
            reason.indexOf("line 1") < reason.indexOf("could not append"),
            "the fault found first must be stated first: $reason",
        )
    }

    @Test
    fun aFaultThatRecursIsStatedOnceRatherThanSpendingTheSealsBudget() {
        val permadeath =
            DurablePermadeath(
                FakeLedger(
                    found = LedgerReplay.Unsafe("line 1 is not a tombstone"),
                    writeFailure = "could not append to the death ledger: IOException",
                ),
            )

        // One disk fault, met by every agent that dies on it. The reason string is identical each
        // time, so counting occurrences rather than faults spent three of the four slots the seal
        // has - and most of the bounded detail a record carries - restating one thing to look at.
        for (identity in listOf(scout, digger, mason, tanner)) {
            permadeath.recordDeath(identity, tick = 100)
        }

        val reason = assertNotNull(permadeath.sealReason)
        val write = "could not append to the death ledger"
        assertEquals(
            reason.indexOf(write),
            reason.lastIndexOf(write),
            "a recurring fault is one thing to go and look at, so it is stated once: $reason",
        )
        assertTrue(reason.contains("line 1 is not a tombstone"), "and the other fault still fits: $reason")
        assertFalse(reason.contains("more"), "with nothing left over to count: $reason")
        assertTrue(reason.length <= MAX_WITNESS_DETAIL_CHARS, "and it still fits a record's detail: $reason")
    }

    @Test
    fun aCrowdedSealStillNamesTheLineThatWouldNotReadAndSaysWhatItLeftOut() {
        // More distinct tombstone faults than a seal will ever name, plus the crash-torn line that
        // ended the last run. The torn line is the gravest of the set - a partly readable tombstone
        // is still indexed by whichever half read back and still refuses that connection, while a
        // torn one is a death Loom cannot name at all - so it is the fault an elision must not drop.
        val mangled =
            listOf(scout, digger, mason, tanner, hauler).map {
                tombstoneFor(it, tick = 100).copy(uuid = "not-a-uuid")
            }
        val permadeath =
            DurablePermadeath(
                FakeLedger(
                    found =
                        LedgerReplay.Unsafe(
                            reason = "death ledger line 6 is not a tombstone: JsonDecodingException",
                            tombstones = mangled,
                        ),
                ),
            )

        val logged = assertNotNull(permadeath.sealReason)
        val recorded = assertNotNull(permadeath.sealReasonWithin(MAX_WITNESS_DETAIL_CHARS))

        // Whichever bound shortened it, the torn line survives and the cut falls on a whole fault
        // Loom can then count - not on the end of the line, which took the "and N more" with it and
        // left an operator no sign that anything had been left out.
        for (reason in listOf(logged, recorded)) {
            assertTrue(reason.contains("death ledger line 6"), "the torn line must survive: $reason")
            assertTrue(reason.contains("tombstone 1"), "beside as many tombstone faults as fit: $reason")
            assertTrue(reason.endsWith("more"), "and what it left out must be stated: $reason")
        }
        // The character bound is the record's alone. A server log line has none, so the seal names
        // every fault it may there - and that is the one place a fault the record elided is still
        // recoverable from, which sharing one string between the two readers took away.
        assertTrue(recorded.length <= MAX_WITNESS_DETAIL_CHARS, "within a record's own bound: $recorded")
        assertTrue(logged.length > MAX_WITNESS_DETAIL_CHARS, "the log is not held to it: $logged")
        assertTrue(
            logged.contains("tombstone ${NAMED_SEAL_FAULTS - 1}"),
            "the log names every fault the count bound allows: $logged",
        )
        assertFalse(
            recorded.contains("tombstone ${NAMED_SEAL_FAULTS - 1}"),
            "which is strictly more than the record carries: $recorded",
        )
    }

    @Test
    fun aSealNamesTombstonesAndFileLinesInUnitsThatCannotBeConfused() {
        // Both numbering schemes at once: the ledger counts *file lines* and the replay counts
        // *tombstones*, and they diverge as soon as a blank line is tolerated, so a reason that did
        // not name its own unit would read as an index into the file it is not.
        val permadeath =
            DurablePermadeath(
                FakeLedger(
                    found =
                        LedgerReplay.Unsafe(
                            reason = "death ledger line 4 is not a tombstone: SerializationException",
                            tombstones = listOf(tombstoneFor(scout, tick = 100).copy(uuid = "not-a-uuid")),
                        ),
                ),
            )

        val reason = assertNotNull(permadeath.sealReason)
        assertTrue(reason.contains("tombstone 1 of the death ledger"), reason)
        assertTrue(reason.contains("death ledger line 4"), reason)
    }

    @Test
    fun oneDeathReportedFromEveryThreadAtOnceIsStillOneDeath() {
        val ledger = FakeLedger()
        val permadeath = DurablePermadeath(ledger)

        val recordings = inTandem(RACING_THREADS, name = "death") { permadeath.recordDeath(scout, tick = 100) }

        assertEquals(
            1,
            recordings.count { it == DeathRecording.Terminal },
            "exactly one caller may be told the death was the first: $recordings",
        )
        assertEquals(
            RACING_THREADS - 1,
            recordings.count { it == DeathRecording.AlreadyRecorded },
            "every other caller must be told it was already recorded: $recordings",
        )
        assertEquals(1, ledger.persisted.size, "one life, one tombstone")
    }

    @Test
    fun readingWhileDeathsAreBeingRecordedNeitherFailsNorLosesOne() {
        val ledger = FakeLedger()
        val permadeath = DurablePermadeath(ledger)
        val dying = (0 until RACING_THREADS).map { managed("agent-$it", "loomagent%02d".format(it)) }

        // Half the threads record a death, half ask about one, all at the same instant. `Mortality`
        // holds a plain HashSet, so an unlocked read during a write is a data race whose worst
        // outcome is a dead agent reading as alive - which is the whole failure this type prevents.
        inTandem(RACING_THREADS * 2, name = "mixed") { index ->
            if (index < RACING_THREADS) {
                permadeath.recordDeath(dying[index], tick = index.toLong())
            } else {
                permadeath.isDead(dying[index - RACING_THREADS].agentId)
            }
        }

        assertEquals(RACING_THREADS, ledger.persisted.size)
        for (identity in dying) {
            assertTrue(permadeath.isDead(identity.agentId), "${identity.agentId} died and must read as dead")
        }
    }

    @Test
    fun aDeathRecordedBeforeARestartIsStillDeadAfterIt() {
        val path = Files.createTempDirectory("loom-permadeath").resolve("player-deaths.jsonl")

        assertEquals(DeathRecording.Terminal, DurablePermadeath(FileDeathLedger(path)).recordDeath(scout, tick = 100))

        // A new object over the same file: this is the plugin reload and the server restart, which
        // is the case a permadeath rule held only in memory would silently fail.
        val afterRestart = DurablePermadeath(FileDeathLedger(path))

        assertTrue(afterRestart.isDead(scout.agentId), "a tombstone on disk must outlive the process that wrote it")
        assertNull(afterRestart.sealReason)
        assertEquals(DeathRecording.AlreadyRecorded, afterRestart.recordDeath(scout, tick = 900))
    }

    @Test
    fun aCorruptedLedgerSealsARestartRatherThanAdmittingTheDead() {
        val path = Files.createTempDirectory("loom-permadeath").resolve("player-deaths.jsonl")
        DurablePermadeath(FileDeathLedger(path)).recordDeath(scout, tick = 100)
        Files.write(path, "the tombstones were here".toByteArray())

        val afterRestart = DurablePermadeath(FileDeathLedger(path))

        assertNotNull(
            afterRestart.sealReason,
            "a ledger whose contents were lost must refuse everybody rather than believe nobody died",
        )
    }

    @Test
    fun aReplayedTombstoneIsFoundByEitherHalfOfTheIdentityItNames() {
        val permadeath = DurablePermadeath(FakeLedger(LedgerReplay.Readable(listOf(tombstoneFor(scout, tick = 400)))))

        // Nothing is registered here, and nothing can be: this is the state a restart leaves, with
        // the ledger on disk and every registration gone with the process that held it.
        assertEquals(DeadMatch.Whole(scout), permadeath.deadIdentityFor(scout.uuid, scout.username))
        assertEquals(DeadMatch.Whole(scout), permadeath.deadIdentityFor(scout.uuid, "someothername"))
        assertEquals(DeadMatch.Whole(scout), permadeath.deadIdentityFor(UUID.randomUUID(), scout.username))
        val shouted = scout.username.uppercase(Locale.ROOT)
        assertEquals(DeadMatch.Whole(scout), permadeath.deadIdentityFor(scout.uuid, shouted))
        assertNull(
            permadeath.deadIdentityFor(digger.uuid, digger.username),
            "an identity with no tombstone must be found by neither half",
        )
    }

    @Test
    fun aDeathRecordedInThisRunIsFoundByTheIdentityItWasWitnessedUnder() {
        val permadeath = DurablePermadeath(FakeLedger())

        permadeath.recordDeath(scout, tick = 100)

        // The same answer whichever path put the tombstone there, so an admission cannot depend on
        // whether the death happened in this run or a previous one.
        assertEquals(DeadMatch.Whole(scout), permadeath.deadIdentityFor(scout.uuid, scout.username))
        assertNull(permadeath.deadIdentityFor(digger.uuid, digger.username))
    }

    @Test
    fun aDeathWhoseTombstoneDidNotLandIsStillFoundByItsIdentity() {
        val permadeath = DurablePermadeath(FakeLedger(writeFailure = "could not append: IOException"))

        permadeath.recordDeath(scout, tick = 100)

        // The agent is dead in this process whatever the disk did, so its account must be refused
        // from here on. What the failed write costs is the seal, not this answer.
        assertEquals(DeadMatch.Whole(scout), permadeath.deadIdentityFor(scout.uuid, scout.username))
    }

    @Test
    fun aTombstoneLoomCannotReadBackSealsRatherThanBeingPassedOver() {
        // Three ways a line can parse as JSON and still name nothing Loom can compare a login
        // against: a uuid that is not one, a username no client could present, and an agent id
        // `AgentId` itself refuses. Each is a file edited or corrupted since Loom wrote it.
        val unusable =
            listOf(
                tombstoneFor(scout, tick = 100).copy(uuid = "not-a-uuid"),
                tombstoneFor(scout, tick = 100).copy(username = "no"),
                tombstoneFor(scout, tick = 100).copy(agentId = ""),
            )

        for (tombstone in unusable) {
            val permadeath = DurablePermadeath(FakeLedger(LedgerReplay.Readable(listOf(tombstone))))

            val reason =
                assertNotNull(
                    permadeath.sealReason,
                    "$tombstone cannot be compared against a login, so it must seal rather than be skipped",
                )
            assertTrue(reason.contains("tombstone 1"), "the seal must name which tombstone: $reason")
            assertFalse(
                reason.contains("not-a-uuid"),
                "a corrupted ledger's own bytes must not be quoted into a reason: $reason",
            )
        }
    }

    @Test
    fun eachHalfOfAnUnusableTombstoneIsIndexedOnItsOwn() {
        // The two fields fail independently, and each one that reads is a way a connection can still
        // be matched. A tombstone is only unmatchable when both are gone.
        val mangledUuid = DurablePermadeath(ledgerOf(tombstoneFor(scout, tick = 100).copy(uuid = "not-a-uuid")))
        val mangledName = DurablePermadeath(ledgerOf(tombstoneFor(scout, tick = 100).copy(username = "no")))
        val bothGone =
            DurablePermadeath(
                ledgerOf(tombstoneFor(scout, tick = 100).copy(uuid = "not-a-uuid", username = "no")),
            )
        val expected = DeadMatch.AgentOnly(scout.agentId)

        // A readable username still refuses the account that presents it.
        assertEquals(expected, mangledUuid.deadIdentityFor(UUID.randomUUID(), scout.username))
        // A readable uuid still refuses the account it names, whatever name that account offers.
        assertEquals(expected, mangledName.deadIdentityFor(scout.uuid, "loomsomebody"))
        // A username no client could present is not indexed, because no connection could match it.
        assertNull(mangledUuid.deadIdentityFor(UUID.randomUUID(), "no"))
        // With neither half readable there is nothing left to match, and the seal is all there is.
        assertNull(bothGone.deadIdentityFor(scout.uuid, scout.username))
        for (permadeath in listOf(mangledUuid, mangledName, bothGone)) {
            assertTrue(permadeath.isDead(scout.agentId), "the agent is named by every one of these")
            assertNotNull(permadeath.sealReason, "the part that could not be read must still fail closed")
        }
    }

    private fun ledgerOf(vararg tombstones: Tombstone) = FakeLedger(LedgerReplay.Readable(tombstones.toList()))

    @Test
    fun oneUnusableTombstoneDoesNotHideTheDeathsAroundIt() {
        val permadeath =
            DurablePermadeath(
                FakeLedger(
                    LedgerReplay.Readable(
                        listOf(
                            tombstoneFor(scout, tick = 100),
                            tombstoneFor(digger, tick = 200).copy(uuid = "not-a-uuid"),
                        ),
                    ),
                ),
            )

        // The replay carries on, so the tombstones it could read still count - and the seal is what
        // covers the part of the one it could not.
        assertTrue(permadeath.isDead(scout.agentId))
        assertEquals(DeadMatch.Whole(scout), permadeath.deadIdentityFor(scout.uuid, scout.username))
        // The mangled tombstone still says plainly which agent died, so the death is taken from the
        // half that reads. Discarding the whole line made `isDead` answer false for an agent the
        // file names, and a registered login for it was then refused as a ledger Loom could not
        // read rather than as the death it is.
        assertTrue(
            permadeath.isDead(digger.agentId),
            "a tombstone whose uuid was mangled still names the agent that died",
        )
        // And the half a connection can be matched against is indexed too, which is the whole of
        // what keeps that agent out: the seal is only consulted for a connection a registration has
        // already matched, so after the restart it exists to survive it refuses nobody, and digger's
        // own username - still sitting in the file - was matching nothing.
        assertEquals(
            DeadMatch.AgentOnly(digger.agentId),
            permadeath.deadIdentityFor(digger.uuid, digger.username),
            "a mangled uuid must not cost the agent's username its refusal",
        )
        assertEquals(
            DeadMatch.AgentOnly(digger.agentId),
            permadeath.deadIdentityFor(digger.uuid, digger.username.uppercase(Locale.ROOT)),
            "the death index reads a name the way the game does, without regard to case",
        )
        assertNotNull(permadeath.sealReason)
    }

    @Test
    fun everyDeathAnUnsafeReplayCouldReadIsStillEnforced() {
        val torn = "death ledger line 3 is not a tombstone: SerializationException"
        val permadeath =
            DurablePermadeath(
                FakeLedger(
                    LedgerReplay.Unsafe(
                        reason = torn,
                        tombstones = listOf(tombstoneFor(scout, tick = 100), tombstoneFor(digger, tick = 200)),
                    ),
                ),
            )

        // Both halves happen. A torn final line makes only the death it was recording unknowable,
        // so the deaths before it are enforced by name with no registration present - and the seal
        // covers the line that could not be read on top of that.
        for (identity in listOf(scout, digger)) {
            assertTrue(permadeath.isDead(identity.agentId), "${identity.agentId} is named by a whole tombstone")
            assertEquals(DeadMatch.Whole(identity), permadeath.deadIdentityFor(identity.uuid, identity.username))
        }
        assertEquals(torn, permadeath.sealReason, "the line that could not be read must still fail closed")
    }

    @Test
    fun aTornFinalLineOnTheRealFileStillDeniesTheDeathsBeforeIt() {
        val path = Files.createTempDirectory("loom-permadeath").resolve("player-deaths.jsonl")
        val ledger = FileDeathLedger(path)
        DurablePermadeath(ledger).recordDeath(scout, tick = 100)
        // A crash mid-append: the line Loom was writing when the process died.
        path.appendText("""{"agentId":"agent-digger","uuid":""")

        val afterRestart = DurablePermadeath(FileDeathLedger(path))

        // Through the real file rather than a fake, because the torn line is the corruption a real
        // run produces and the all-or-nothing read that used to discard scout lived in the file
        // ledger rather than in this type.
        assertTrue(afterRestart.isDead(scout.agentId))
        assertEquals(DeadMatch.Whole(scout), afterRestart.deadIdentityFor(scout.uuid, scout.username))
        assertNotNull(afterRestart.sealReason)
    }
}
