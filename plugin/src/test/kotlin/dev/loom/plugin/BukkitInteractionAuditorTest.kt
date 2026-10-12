package dev.loom.plugin

import dev.loom.bodycontrol.AttackOutcome
import dev.loom.bodycontrol.BODY_CONTROL_PROTOCOL_VERSION
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.CraftOutcome
import dev.loom.bodycontrol.LifecycleState
import dev.loom.bodycontrol.SensedBlock
import dev.loom.bodycontrol.SensedEntity
import dev.loom.bodycontrol.SensedRecipe
import dev.loom.bodycontrol.SensedStack
import dev.loom.bodycontrol.SleepOutcome
import org.bukkit.event.player.PlayerBedEnterEvent
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

private val PLAYER = UUID.nameUUIDFromBytes("auditor-player".toByteArray())
private val TARGET = UUID.nameUUIDFromBytes("auditor-target".toByteArray())
private val BED = BlockPoint(2, 64, 3)

class BukkitInteractionAuditorTest {
    @Test
    fun `an exact claimed server event confirms the interaction`() {
        val audit =
            interactionEventAudit(
                claimed = true,
                eventFound = true,
                exact = true,
                foundDetail = "exact vanilla effect",
                absentDetail = "absent",
            )

        assertTrue(audit.confirmed)
    }

    @Test
    fun `a mismatched event disputes either a success or a refusal`() {
        val claimed =
            interactionEventAudit(
                claimed = true,
                eventFound = true,
                exact = false,
                foundDetail = "different vanilla effect",
                absentDetail = "absent",
            )
        val refused =
            interactionEventAudit(
                claimed = false,
                eventFound = true,
                exact = false,
                foundDetail = "unexpected vanilla effect",
                absentDetail = "absent",
            )

        assertFalse(claimed.confirmed)
        assertFalse(refused.confirmed)
    }

    @Test
    fun `no server event confirms only a body refusal`() {
        val refused =
            interactionEventAudit(
                claimed = false,
                eventFound = false,
                exact = false,
                foundDetail = null,
                absentDetail = "no vanilla effect",
            )
        val claimed =
            interactionEventAudit(
                claimed = true,
                eventFound = false,
                exact = false,
                foundDetail = null,
                absentDetail = "no vanilla effect",
            )

        assertTrue(refused.confirmed)
        assertFalse(claimed.confirmed)
    }

    @Test
    fun `craft evidence distinguishes completion partial application and pre-application refusal`() {
        val request = craftRequest()
        val offered =
            SensedRecipe(
                what = "minecraft:oak_planks",
                resultCount = 4,
                maxCrafts = 2,
                requiresTable = false,
            )
        val first = crafted(100)
        val second = crafted(101)
        val completed =
            craftEventAudit(
                request,
                craftReport(CraftOutcome.CRAFTED, attempted = true, count = 8, crafts = 2),
                offered,
                listOf(first, second),
            )
        val partial =
            craftEventAudit(
                request,
                craftReport(CraftOutcome.TIMED_OUT, attempted = true),
                offered,
                listOf(first),
            )
        val impossibleRefusal =
            craftEventAudit(
                request,
                craftReport(CraftOutcome.CANCELLED, attempted = false),
                offered,
                listOf(first),
            )
        val excessive =
            craftEventAudit(
                request,
                craftReport(CraftOutcome.TIMED_OUT, attempted = true),
                offered,
                listOf(first, second, crafted(102)),
            )

        assertTrue(completed.confirmed)
        assertTrue(partial.confirmed)
        assertFalse(impossibleRefusal.confirmed)
        assertFalse(excessive.confirmed)
    }

    @Test
    fun `combat evidence confirms exact target weapon hit count and death`() {
        val target = offeredTarget()
        val weapon = offeredWeapon()
        val hit = damaged(100)
        val attacked =
            attackEventAudit(
                attackReport(AttackOutcome.ATTACKED, attacks = 1),
                target,
                weapon,
                AttackAuditEvidence(TARGET, listOf(hit), emptyList()),
            )
        val killed =
            attackEventAudit(
                attackReport(AttackOutcome.TARGET_DIED, attacks = 1),
                target,
                weapon,
                AttackAuditEvidence(TARGET, listOf(hit), listOf(killed(101))),
            )

        assertTrue(attacked.confirmed)
        assertTrue(killed.confirmed)
    }

    @Test
    fun `combat evidence rejects wrong weapon collateral death and uncorrelated success`() {
        val target = offeredTarget()
        val weapon = offeredWeapon()
        val hit = damaged(100)
        val wrongWeapon =
            attackEventAudit(
                attackReport(AttackOutcome.ATTACKED, attacks = 1),
                target,
                weapon,
                AttackAuditEvidence(
                    TARGET,
                    listOf(hit.copy(weapon = "minecraft:wooden_sword")),
                    emptyList(),
                ),
            )
        val collateral =
            attackEventAudit(
                attackReport(AttackOutcome.ATTACKED, attacks = 1),
                target,
                weapon,
                AttackAuditEvidence(
                    TARGET,
                    listOf(hit.copy(target = UUID.randomUUID())),
                    emptyList(),
                ),
            )
        val wrongDeath =
            attackEventAudit(
                attackReport(AttackOutcome.TARGET_DIED, attacks = 1),
                target,
                weapon,
                AttackAuditEvidence(
                    TARGET,
                    listOf(hit),
                    listOf(killed(101).copy(target = UUID.randomUUID())),
                ),
            )
        val uncorrelatedSuccess =
            attackEventAudit(
                attackReport(AttackOutcome.ATTACKED, attacks = 1),
                target,
                weapon,
                AttackAuditEvidence(null, emptyList(), emptyList()),
            )

        assertFalse(wrongWeapon.confirmed)
        assertFalse(collateral.confirmed)
        assertFalse(wrongDeath.confirmed)
        assertFalse(uncorrelatedSuccess.confirmed)
    }

    @Test
    fun `sleep evidence requires the exact successful enter and matching leave`() {
        val offered = offeredBed()
        val entered = bedEntered()
        val left = bedLeft()
        val woke =
            sleepEventAudit(
                sleepReport(SleepOutcome.WOKE, entered = true),
                offered,
                SleepAuditEvidence(listOf(entered), listOf(left)),
            )
        val missingLeave =
            sleepEventAudit(
                sleepReport(SleepOutcome.WOKE, entered = true),
                offered,
                SleepAuditEvidence(listOf(entered), emptyList()),
            )

        assertTrue(woke.confirmed)
        assertFalse(missingLeave.confirmed)
    }

    @Test
    fun `sleep evidence accepts either verified half of the exact same bed`() {
        val offered = offeredBed()
        val canonicalHalf = BED.copy(x = BED.x + 1)
        val entered =
            bedEntered().copy(
                bed = canonicalHalf,
                otherHalf = BED,
            )
        val left =
            bedLeft().copy(
                bed = canonicalHalf,
                otherHalf = BED,
            )

        val woke =
            sleepEventAudit(
                sleepReport(SleepOutcome.WOKE, entered = true),
                offered,
                SleepAuditEvidence(listOf(entered), listOf(left)),
            )
        val wrongMaterial =
            sleepEventAudit(
                sleepReport(SleepOutcome.WOKE, entered = true),
                offered,
                SleepAuditEvidence(
                    listOf(entered.copy(what = "minecraft:blue_bed")),
                    listOf(left.copy(what = "minecraft:blue_bed")),
                ),
            )

        assertTrue(woke.confirmed)
        assertFalse(wrongMaterial.confirmed)
    }

    @Test
    fun `sleep evidence rejects contradicted refusal and a different bed`() {
        val offered = offeredBed()
        val entered = bedEntered()
        val left = bedLeft()
        val refusal =
            sleepEventAudit(
                sleepReport(SleepOutcome.NOT_NIGHT, entered = false),
                offered,
                SleepAuditEvidence(
                    listOf(entered.copy(result = PlayerBedEnterEvent.BedEnterResult.NOT_POSSIBLE_NOW)),
                    emptyList(),
                ),
            )
        val contradictedRefusal =
            sleepEventAudit(
                sleepReport(SleepOutcome.NOT_NIGHT, entered = false),
                offered,
                SleepAuditEvidence(listOf(entered), emptyList()),
            )
        val wrongBed =
            sleepEventAudit(
                sleepReport(SleepOutcome.WOKE, entered = true),
                offered,
                SleepAuditEvidence(
                    listOf(entered.copy(bed = BED.copy(x = BED.x + 1))),
                    listOf(left.copy(bed = BED.copy(x = BED.x + 1))),
                ),
            )

        assertTrue(refusal.confirmed)
        assertFalse(contradictedRefusal.confirmed)
        assertFalse(wrongBed.confirmed)
    }
}

private fun craftRequest(): BodyRequest.Craft =
    BodyRequest.Craft(
        protocol = BODY_CONTROL_PROTOCOL_VERSION,
        requestId = "craft-request",
        agentId = "agent-auditor",
        senseId = "sense-craft",
        recipe = 0,
        crafts = 2,
        timeoutMs = 10_000,
    )

private fun craftReport(
    outcome: CraftOutcome,
    attempted: Boolean,
    count: Int? = null,
    crafts: Int? = null,
): BodyResponse.CraftReport =
    BodyResponse.CraftReport(
        protocol = BODY_CONTROL_PROTOCOL_VERSION,
        requestId = "craft-response",
        agentId = "agent-auditor",
        state = LifecycleState.READY,
        outcome = outcome,
        elapsedMs = 20,
        attempted = attempted,
        what = count?.let { "minecraft:oak_planks" },
        count = count,
        crafts = crafts,
    )

private fun crafted(tick: Long): VanillaInteractionEvent.Crafted =
    VanillaInteractionEvent.Crafted(
        player = PLAYER,
        tick = tick,
        what = "minecraft:oak_planks",
        count = 4,
    )

private fun attackReport(
    outcome: AttackOutcome,
    attacks: Int,
): BodyResponse.AttackReport =
    BodyResponse.AttackReport(
        protocol = BODY_CONTROL_PROTOCOL_VERSION,
        requestId = "attack-response",
        agentId = "agent-auditor",
        state = LifecycleState.READY,
        outcome = outcome,
        from = listOf(1.5, 64.0, 2.5),
        position = listOf(1.5, 64.0, 2.5),
        travelled = 0.0,
        elapsedMs = 20,
        attacks = attacks,
    )

private fun damaged(tick: Long): VanillaInteractionEvent.Damaged =
    VanillaInteractionEvent.Damaged(
        player = PLAYER,
        tick = tick,
        target = TARGET,
        targetWhat = "minecraft:zombie",
        weapon = "minecraft:iron_sword",
        damage = 6.0,
    )

private fun offeredTarget(): SensedEntity =
    SensedEntity(
        what = "minecraft:zombie",
        position = listOf(3.5, 64.0, 3.5),
        visible = true,
    )

private fun offeredWeapon(): SensedStack = SensedStack(slot = 36, what = "minecraft:iron_sword", count = 1)

private fun killed(tick: Long): VanillaInteractionEvent.Killed =
    VanillaInteractionEvent.Killed(
        player = PLAYER,
        tick = tick,
        target = TARGET,
        targetWhat = "minecraft:zombie",
    )

private fun offeredBed(): SensedBlock =
    SensedBlock(
        what = "minecraft:red_bed",
        position = listOf(BED.x, BED.y, BED.z),
        visible = true,
    )

private fun bedEntered(): VanillaInteractionEvent.BedEntered =
    VanillaInteractionEvent.BedEntered(
        player = PLAYER,
        tick = 100,
        bed = BED,
        otherHalf = null,
        what = "minecraft:red_bed",
        result = PlayerBedEnterEvent.BedEnterResult.OK,
    )

private fun bedLeft(): VanillaInteractionEvent.BedLeft =
    VanillaInteractionEvent.BedLeft(
        player = PLAYER,
        tick = 101,
        bed = BED,
        otherHalf = null,
        what = "minecraft:red_bed",
    )

private fun sleepReport(
    outcome: SleepOutcome,
    entered: Boolean,
): BodyResponse.SleepReport =
    BodyResponse.SleepReport(
        protocol = BODY_CONTROL_PROTOCOL_VERSION,
        requestId = "sleep-response",
        agentId = "agent-auditor",
        state = LifecycleState.READY,
        outcome = outcome,
        elapsedMs = 20,
        entered = entered,
    )
