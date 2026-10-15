/*
 * That a player body's account of itself is checked against the server's, and that the
 * check says which fact was wrong.
 *
 * The properties worth pinning here are all about *distinguishing* things a single
 * boolean would collapse. A body that misreports its hunger and a body that misreports
 * its bread have failed in different ways; a reading that drifted by half a heart over a
 * socket round trip has not failed at all; a slot the server cannot be asked about is
 * neither agreement nor a fault; an account that agreed as far as it went with part of it
 * excluded is a third thing; and a claim from a player no registration names was not
 * compared at all, which is a fourth. Each of those is one test below.
 *
 * Most of them drive the real `ClaimAudit`, not the comparison alone, because what has to
 * hold is that a disagreement reaches the append-only record exactly once with a countable
 * tag - and that agreement reaches it not at all.
 */
package dev.loom.witness

import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventRecord
import dev.loom.body.EventTag
import dev.loom.body.toJsonLine
import dev.loom.bodycontrol.MAX_SLOT_INDEX
import dev.loom.bodycontrol.SensedStack
import dev.loom.bodycontrol.SensedVitals
import dev.loom.contract.LoomJson
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readLines
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A first hotbar slot in the protocol's window numbering. Bukkit calls it 0. */
private const val FIRST_HOTBAR_SLOT = 36

/** A helmet, which the window numbers first of the armour and Bukkit numbers last. */
private const val HELMET_SLOT = 5

/** The offhand, the last slot of the window. */
private const val OFFHAND_SLOT = MAX_SLOT_INDEX

/** A crafting-grid slot: inside the protocol's window, outside what the server addresses. */
private const val CRAFTING_SLOT = 2

/** Bukkit's last `PlayerInventory` index: the offhand. */
private const val LAST_BUKKIT_SLOT = 40

/** A body in perfect health with a full hunger bar, as both sides would read it. */
private val WELL_FED = SensedVitals(health = 20.0, food = 20, saturation = 5.0)

private val WELL_FED_TRUTH = ObservedVitals(health = 20.0, food = 20, saturation = 5.0)

class CorroborationTest {
    private val scout = managed("agent-scout", "loomscout1")
    private val under = WitnessUnderTest(scout)

    private fun crossCheck(
        claim: BodyClaim,
        reading: ServerReading,
    ): Corroboration = under.claims.sawClaim(scout.uuid, scout.username, claim, reading)

    private fun disputes() = under.log.of(BodyEvent.WITNESS_DISPUTED)

    @Test
    fun anHonestAccountAgreesAndIsNotRecordedAtAll() {
        val bread = SensedStack(FIRST_HOTBAR_SLOT, "bread", 3)

        val found =
            crossCheck(
                BodyClaim(WELL_FED, listOf(bread)),
                ServerReading(WELL_FED_TRUTH, listOf(ObservedStack(FIRST_HOTBAR_SLOT, "bread", 3))),
            )

        assertEquals(Corroboration.Agreed, found)
        // Silence is the design rather than an omission: a record per cross-check that found
        // nothing would bury the ones that found something, in the log whose whole purpose is
        // counting them.
        assertTrue(disputes().isEmpty(), "agreement must write nothing: ${disputes()}")
    }

    @Test
    fun eachVitalIsItsOwnFactAndIsNamedOnItsOwn() {
        // Three vitals, three separate faults. Collapsed into one "vitals disagreed" the record
        // could not say whether a client had misread the hunger bar or the health bar, which are
        // different bugs in different parts of a body process.
        val claimed = SensedVitals(health = 20.0, food = 20, saturation = 5.0)
        val truth = ObservedVitals(health = 4.0, food = 2, saturation = 0.0)

        val found = corroborate(claimed, emptyList(), truth, emptyList())

        val dispute = assertIs<Corroboration.Disputed>(found)
        assertEquals(listOf(Vital.HEALTH, Vital.FOOD, Vital.SATURATION), dispute.vitals.map { it.vital })
        assertTrue(dispute.inventory.isEmpty())
        assertEquals(EventTag.DISPUTED_VITALS, tagOf(dispute))
    }

    @Test
    fun aContinuousVitalMayDriftUpToTheToleranceAndNoFurther() {
        // The server snapshot and later client claim straddle a socket round trip, so health
        // and saturation move for perfectly ordinary reasons. The bound is where the two
        // readings stop being explainable by that; either side of it must answer differently
        // or the tolerance is decoration.
        val atTheBound =
            corroborate(
                SensedVitals(health = 20.0, food = 20, saturation = 5.0),
                emptyList(),
                ObservedVitals(health = 20.0 - VITAL_DRIFT_TOLERANCE, food = 20, saturation = 5.0),
                emptyList(),
            )
        val pastIt =
            corroborate(
                SensedVitals(health = 20.0, food = 20, saturation = 5.0),
                emptyList(),
                ObservedVitals(health = 20.0 - VITAL_DRIFT_TOLERANCE - 0.01, food = 20, saturation = 5.0),
                emptyList(),
            )

        assertEquals(Corroboration.Agreed, atTheBound, "drift within the tolerance is not a fault")
        assertIs<Corroboration.Disputed>(pastIt)
    }

    @Test
    fun aVitalThatMovedDuringTheRoundTripIsCheckedAgainstTheWholeSpan() {
        // A live run at night: the client said 13 while the server read 17 before the sense,
        // because a hit landed inside the round trip; the server read 12 when it was audited.
        val claimed = SensedVitals(health = 13.0, food = 20, saturation = 5.0)
        val before = ObservedVitals(health = 17.0, food = 20, saturation = 5.0)
        val after = ObservedVitals(health = 12.0, food = 20, saturation = 5.0)
        assertIs<Corroboration.Disputed>(corroborate(claimed, emptyList(), before, emptyList()))
        assertEquals(Corroboration.Agreed, corroborate(claimed, emptyList(), before, emptyList(), after))
        // Outside the whole span, plus the tolerance, is still a body contradicting the server.
        val invented = SensedVitals(health = 8.0, food = 20, saturation = 5.0)
        assertIs<Corroboration.Disputed>(corroborate(invented, emptyList(), before, emptyList(), after))
    }

    @Test
    fun saturationFollowsVanillasResendRule() {
        // A live run: both clients still held their starting 5.0 while exhaustion had drained
        // the server to 3.0, because vanilla resends saturation only beside a health or food
        // change or a zero crossing.
        val stale = SensedVitals(health = 20.0, food = 20, saturation = 5.0)
        val drained = ObservedVitals(health = 20.0, food = 20, saturation = 3.0)
        assertEquals(Corroboration.Agreed, corroborate(stale, emptyList(), drained, emptyList(), drained))
        // A peaceful world raised the server to 7.0 with no resend either.
        val raised = ObservedVitals(health = 20.0, food = 20, saturation = 7.0)
        assertEquals(Corroboration.Agreed, corroborate(stale, emptyList(), raised, emptyList(), raised))
        // Zero-ness is what vanilla keeps in step, so disagreeing about it is a contradiction.
        val empty = ObservedVitals(health = 20.0, food = 20, saturation = 0.0)
        assertIs<Corroboration.Disputed>(corroborate(stale, emptyList(), empty, emptyList(), empty))
        val starved = SensedVitals(health = 20.0, food = 20, saturation = 0.0)
        assertIs<Corroboration.Disputed>(corroborate(starved, emptyList(), drained, emptyList(), drained))
    }

    @Test
    fun anItemCountIsExactBecauseNothingAboutItDrifts() {
        // The opposite rule from a vital's, and the reason the two are stated separately: a stack
        // of three does not become a stack of two while a frame is in flight. One off is a body
        // reporting an inventory it does not have.
        val found =
            crossCheck(
                BodyClaim(WELL_FED, listOf(SensedStack(FIRST_HOTBAR_SLOT, "bread", 3))),
                ServerReading(WELL_FED_TRUTH, listOf(ObservedStack(FIRST_HOTBAR_SLOT, "bread", 2))),
            )

        val dispute = assertIs<Corroboration.Disputed>(found)
        val fault = assertIs<InventoryFault.Differs>(dispute.inventory.single())
        assertEquals(FIRST_HOTBAR_SLOT, fault.slot)
        assertEquals(EventTag.DISPUTED_INVENTORY, tagOf(dispute))
        assertEquals(EventTag.DISPUTED_INVENTORY, disputes().single().outcome)
    }

    @Test
    fun aNamespacedClaimIsTheSameItemAsTheServersOwnBarePath() {
        // The contract makes an item's namespace optional, and the two sides reach the comparison
        // spelled differently by construction: the server reads `stack.type.key.value()`, which is
        // the path alone, while every shared valid fixture spells a claim `minecraft:bread`.
        // Compared as written, every occupied checkable slot of an honest body was a fault.
        val found =
            crossCheck(
                BodyClaim(WELL_FED, listOf(SensedStack(FIRST_HOTBAR_SLOT, "minecraft:bread", 3))),
                ServerReading(WELL_FED_TRUTH, listOf(ObservedStack(FIRST_HOTBAR_SLOT, "bread", 3))),
            )

        assertEquals(Corroboration.Agreed, found)
        assertTrue(disputes().isEmpty(), "an honest body must not be disputed: ${disputes()}")
    }

    @Test
    fun droppingTheNamespaceDoesNotMakeTwoDifferentItemsAgree() {
        // The other direction, which is what stops the fix above from being a comparison that
        // agrees with anything: only the redundant vanilla namespace is noise, and two different
        // items in one slot are still a body reporting an inventory it does not have.
        val differs =
            crossCheck(
                BodyClaim(WELL_FED, listOf(SensedStack(FIRST_HOTBAR_SLOT, "minecraft:bread", 3))),
                ServerReading(WELL_FED_TRUTH, listOf(ObservedStack(FIRST_HOTBAR_SLOT, "cooked_beef", 3))),
            )

        val dispute = assertIs<Corroboration.Disputed>(differs)
        assertEquals(FIRST_HOTBAR_SLOT, assertIs<InventoryFault.Differs>(dispute.inventory.single()).slot)
        assertEquals(EventTag.DISPUTED_INVENTORY, tagOf(dispute))
        // Two registries can each hold a `bread`, so a namespace that is not vanilla's carries
        // meaning and is not dropped: `loom:bread` is not the server's `bread`.
        val modded =
            corroborate(
                WELL_FED,
                listOf(SensedStack(FIRST_HOTBAR_SLOT, "loom:bread", 3)),
                WELL_FED_TRUTH,
                listOf(ObservedStack(FIRST_HOTBAR_SLOT, "bread", 3)),
            )
        assertIs<InventoryFault.Differs>(assertIs<Corroboration.Disputed>(modded).inventory.single())
    }

    @Test
    fun aStackTheServerDoesNotHoldAndOneTheBodyLeftOutAreBothFaults() {
        // Two-directional coverage, which is what makes the check worth anything: an account that
        // omits the food an agent is carrying is as wrong as one that invents it, and only one of
        // the two would be found by walking the claim.
        val found =
            corroborate(
                WELL_FED,
                listOf(SensedStack(FIRST_HOTBAR_SLOT, "bread", 1)),
                WELL_FED_TRUTH,
                listOf(ObservedStack(HELMET_SLOT, "iron_helmet", 1)),
            )

        val dispute = assertIs<Corroboration.Disputed>(found)
        // Walked in slot order, so one disagreement always reads the same way.
        assertIs<InventoryFault.NotClaimed>(dispute.inventory.first())
        assertIs<InventoryFault.NotHeld>(dispute.inventory.last())
        assertEquals(listOf(HELMET_SLOT, FIRST_HOTBAR_SLOT), dispute.inventory.map { it.slot })
    }

    @Test
    fun bothKindsAtOnceAreOneRecordUnderOneTagThatSaysSo() {
        val found =
            crossCheck(
                BodyClaim(WELL_FED, listOf(SensedStack(OFFHAND_SLOT, "bread", 1))),
                ServerReading(ObservedVitals(health = 2.0, food = 20, saturation = 5.0), emptyList()),
            )

        val dispute = assertIs<Corroboration.Disputed>(found)
        assertEquals(EventTag.DISPUTED_BOTH, tagOf(dispute))
        // One record, not one per fault: a cross-check is one statement about one moment, and a
        // record per fault would let one bad reading dominate any count of them.
        assertEquals(EventTag.DISPUTED_BOTH, disputes().single().outcome)
    }

    @Test
    fun aClaimAboutTheCraftingWindowIsOutsideTheComparisonRatherThanAgreedWith() {
        // Bukkit's `PlayerInventory` does not address the five crafting-window slots, so the
        // witness genuinely cannot check one. Counted and stated rather than passed over, because
        // "did not look" reading as "agreed" is exactly the gap a body could hide a stack in.
        val found =
            corroborate(
                WELL_FED,
                listOf(SensedStack(CRAFTING_SLOT, "bread", 9), SensedStack(FIRST_HOTBAR_SLOT, "bread", 1)),
                WELL_FED_TRUTH,
                emptyList(),
            )

        val dispute = assertIs<Corroboration.Disputed>(found)
        assertEquals(1, dispute.outsideComparison)
        // The uncheckable slot is not among the faults; only the hotbar stack is.
        assertEquals(listOf(FIRST_HOTBAR_SLOT), dispute.inventory.map { it.slot })
        assertContains(detailOf(dispute), "1 claimed stacks outside the comparison")
    }

    @Test
    fun anAccountWhollyInsideTheCraftingWindowIsPartlyCheckedRatherThanAgreedWith() {
        // The same rule as the test above, on the path where nothing disagreed. Every stack this
        // body claims sits where the server cannot be asked, so the comparison looked at none of
        // its inventory - and `Agreed` said the server had vouched for all of it, which is a body
        // being told its own numbers are corroborated by putting a stack exactly there.
        val found =
            crossCheck(
                BodyClaim(WELL_FED, (0 until FIRST_CORROBORABLE_SLOT).map { SensedStack(it, "bread", 9) }),
                ServerReading(WELL_FED_TRUTH, emptyList()),
            )

        val partly = assertIs<Corroboration.PartlyChecked>(found)
        assertEquals(FIRST_CORROBORABLE_SLOT, partly.outsideComparison)
        // Still not a dispute, so still nothing recorded: nothing here says the body was wrong.
        assertTrue(under.log.records.isEmpty(), "an unchecked claim is not a disagreement")
    }

    @Test
    fun anAgreeingAccountCarryingOneUncheckableStackIsPartlyCheckedToo() {
        // The mixed case, which is where the false `Agreed` was actually reachable in an ordinary
        // run: a body's hotbar agrees exactly and one stack sits in the crafting grid. Carving the
        // narrow case out and answering this one `Agreed` would keep the whole gap open, since a
        // body needs only one honest stack to reach it.
        val found =
            crossCheck(
                BodyClaim(
                    WELL_FED,
                    listOf(SensedStack(CRAFTING_SLOT, "bread", 9), SensedStack(FIRST_HOTBAR_SLOT, "bread", 1)),
                ),
                ServerReading(WELL_FED_TRUTH, listOf(ObservedStack(FIRST_HOTBAR_SLOT, "bread", 1))),
            )

        assertEquals(Corroboration.PartlyChecked(1), found)
        assertTrue(under.log.records.isEmpty(), "an unchecked claim is not a disagreement")
    }

    @Test
    fun theOutsideCountSurvivesTheBoundTheJournalWritesTheDetailUnder() {
        // A dispute naming most of an inventory overruns `MAX_WITNESS_DETAIL_CHARS`, and the
        // journal cuts the tail. Written after the faults, the outside count is exactly what gets
        // cut - so the record of a partly checked disagreement read as a wholly checked one, which
        // is the reading the exclusion exists to prevent.
        val crafting = (0 until FIRST_CORROBORABLE_SLOT).map { SensedStack(it, "bread", 9) }
        val invented = (FIRST_HOTBAR_SLOT..OFFHAND_SLOT).map { SensedStack(it, "cooked_beef", 64) }
        val dispute =
            assertIs<Corroboration.Disputed>(
                corroborate(WELL_FED, crafting + invented, WELL_FED_TRUTH, emptyList()),
            )

        val written = boundedDetail(detailOf(dispute))

        // Genuinely truncated, or the assertions below would hold for the wrong reason.
        assertTrue(written.endsWith("..."), written)
        assertTrue(written.length <= MAX_WITNESS_DETAIL_CHARS, written)
        assertContains(written, "${invented.size} disagreed")
        assertContains(written, "$FIRST_CORROBORABLE_SLOT claimed stacks outside the comparison")
    }

    @Test
    fun anAccountOfAnUnregisteredPlayerIsNotComparedAndIsNotAgreedWithEither() {
        // The same rule every statement after a login follows: an unregistered uuid is not Loom's
        // to have an opinion about, so a stranger's inventory produces no record.
        val stranger = managed("agent-nobody", "somebodyelse")

        val found =
            under.claims.sawClaim(
                stranger.uuid,
                stranger.username,
                BodyClaim(WELL_FED, emptyList()),
                ServerReading(ObservedVitals(health = 1.0, food = 0, saturation = 0.0), emptyList()),
            )

        // But not silence answered as agreement: this claim contradicts the server on all three
        // vitals, and `Agreed` would have said the server vouched for numbers nothing compared.
        // A caller gating on agreement then trusts a body's own account of itself, which at
        // cutover is reached by a managed body whose registration has not landed rather than by a
        // stranger.
        assertEquals(Corroboration.NotManaged, found)
        assertTrue(under.log.records.isEmpty(), "nothing may be recorded about a stranger")
    }

    @Test
    fun aRegisteredBodyIsStillComparedRatherThanPassedOver() {
        // The other half of the same rule: `NotManaged` must be about the registration and not
        // about the claim, so the identical claim from the registered body is in fact compared -
        // and, being wrong on every vital, disputed.
        val found =
            crossCheck(
                BodyClaim(WELL_FED, emptyList()),
                ServerReading(ObservedVitals(health = 1.0, food = 0, saturation = 0.0), emptyList()),
            )

        val dispute = assertIs<Corroboration.Disputed>(found)
        assertEquals(listOf(Vital.HEALTH, Vital.FOOD, Vital.SATURATION), dispute.vitals.map { it.vital })
        assertEquals(1, disputes().size, "one dispute, filed under the registered agent")
        assertEquals(scout.agentId.value, disputes().single().agentId)
    }

    @Test
    fun aServerReadingIsNotForcedThroughTheProtocolsOwnBounds() {
        // A plugin can set a food level of thirty. A witness whose reading of the truth threw
        // would have stopped witnessing precisely where the world had become unusual, so the
        // observed half is unbounded and the disagreement is simply reported.
        val found =
            corroborate(
                WELL_FED,
                emptyList(),
                ObservedVitals(health = 40.0, food = 30, saturation = 25.0),
                emptyList(),
            )

        val dispute = assertIs<Corroboration.Disputed>(found)
        // Saturation is compared only by whether it is zero, which is all vanilla keeps in step.
        assertEquals(listOf(Vital.HEALTH, Vital.FOOD), dispute.vitals.map { it.vital })
    }

    @Test
    fun theDisputeDetailNamesBothReadingsAndStaysOnOneBoundedLine() {
        val dispute =
            assertIs<Corroboration.Disputed>(
                corroborate(
                    WELL_FED,
                    (FIRST_HOTBAR_SLOT..OFFHAND_SLOT).map { SensedStack(it, "cooked_beef", 64) },
                    ObservedVitals(health = 1.0, food = 0, saturation = 0.0),
                    emptyList(),
                ),
            )

        val detail = detailOf(dispute)

        // Which of the two readings is wrong is not a question the comparison can answer, so both
        // are stated; a detail carrying only the server's would read as a reading rather than as a
        // disagreement.
        assertContains(detail, "health claimed 20.00, server reads 1.00")
        assertContains(detail, "server holds nothing")
        // The count comes first, so a detail the journal has to trim still says how much was cut.
        assertTrue(detail.startsWith("13 disagreed: "), detail)
        assertTrue(boundedDetail(detail).length <= MAX_WITNESS_DETAIL_CHARS, boundedDetail(detail))
    }

    @Test
    fun everyBukkitSlotMapsToItsOwnWindowSlotAndAllOfThemAreCheckable() {
        // The two numberings genuinely differ - Bukkit puts the hotbar first where the window puts
        // it second to last, and Bukkit's armour runs boots-upward against the window's
        // helmet-downward - so a mistake here would report every armour piece as being in the wrong
        // slot, which reads as a body that lied rather than as arithmetic.
        val mapped = (0..LAST_BUKKIT_SLOT).mapNotNull(::windowSlotOfBukkitSlot)

        assertEquals(mapped.size, mapped.distinct().size, "two Bukkit slots may not share a window slot")
        assertTrue(mapped.all(::isCorroborableSlot), "every addressable slot must be checkable: $mapped")
        assertEquals(FIRST_HOTBAR_SLOT, windowSlotOfBukkitSlot(0), "Bukkit's first hotbar slot")
        assertEquals(HELMET_SLOT, windowSlotOfBukkitSlot(39), "Bukkit numbers the helmet last")
        assertEquals(8, windowSlotOfBukkitSlot(36), "and the boots first")
        assertEquals(OFFHAND_SLOT, windowSlotOfBukkitSlot(40))
        assertEquals(null, windowSlotOfBukkitSlot(41), "nothing beyond the offhand is addressable")
    }

    @Test
    fun aDisagreementReachesTheAppendOnlyFileAsOneReplayableLineAndAgreementReachesItNotAtAll() {
        // The other cases here read the records out of a list, which proves what the audit
        // decided but not what an operator or an analysis actually gets: the record has to
        // survive into the append-only file as one line that decodes back to itself. Both
        // outcomes are driven through the same real file, because "agreement is recorded
        // nowhere" is a statement about that file and not about a fake of it.
        val directory = Files.createTempDirectory("loom-witness-dispute")
        val file = directory.resolve("body-events.jsonl")
        try {
            val audit =
                ClaimAudit(
                    registryOf(scout),
                    WitnessJournal { record ->
                        Files.writeString(
                            file,
                            record.toJsonLine() + "\n",
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND,
                        )
                    },
                    currentTick = { 4_242 },
                )

            audit.sawClaim(
                scout.uuid,
                scout.username,
                BodyClaim(WELL_FED, listOf(SensedStack(FIRST_HOTBAR_SLOT, "bread", 3))),
                ServerReading(WELL_FED_TRUTH, listOf(ObservedStack(FIRST_HOTBAR_SLOT, "bread", 3))),
            )
            audit.sawClaim(
                scout.uuid,
                scout.username,
                BodyClaim(WELL_FED, listOf(SensedStack(FIRST_HOTBAR_SLOT, "bread", 3))),
                ServerReading(
                    ObservedVitals(health = 6.0, food = 3, saturation = 0.0),
                    listOf(ObservedStack(FIRST_HOTBAR_SLOT, "bread", 1)),
                ),
            )

            val lines = file.readLines()
            assertEquals(1, lines.size, "the agreeing claim must add no line: $lines")

            val written = LoomJson.decodeFromString<BodyEventRecord>(lines.single())
            assertEquals(BodyEvent.WITNESS_DISPUTED, written.event)
            assertEquals(EventTag.DISPUTED_BOTH, written.outcome)
            assertEquals(scout.agentId.value, written.agentId)
            assertEquals(scout.uuid.toString(), written.witness?.uuid)
            assertEquals(scout.username, written.witness?.username)
            assertEquals(4_242, written.tick)
            // The two readings the operator has to be able to tell apart, in the record they
            // are read back out of rather than in the object they were rendered from.
            assertContains(written.detail, "health claimed 20.00, server reads 6.00")
            assertContains(written.detail, "slot $FIRST_HOTBAR_SLOT")
            assertEquals(lines.single(), written.toJsonLine(), "a record must replay as itself")
        } finally {
            file.deleteIfExists()
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun aCraftingSlotIsTheOnlyThingOutsideTheComparison() {
        // Stated as the whole of the exclusion rather than as an example, so widening it later has
        // to be deliberate: the excluded set is exactly the five slots the server cannot reach.
        val excluded = (0..OFFHAND_SLOT).filterNot(::isCorroborableSlot)

        assertEquals((0 until FIRST_CORROBORABLE_SLOT).toList(), excluded)
    }
}
