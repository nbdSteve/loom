/*
 * Server-authoritative reconciliation of one completed native interaction.
 */
package dev.loom.plugin

import dev.loom.body.player.PlayerIdentity
import dev.loom.body.player.ServerInteractionAudit
import dev.loom.body.player.ServerInteractionClaim
import dev.loom.bodycontrol.AttackOutcome
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.CollectOutcome
import dev.loom.bodycontrol.CraftOutcome
import dev.loom.bodycontrol.DigOutcome
import dev.loom.bodycontrol.EquipOutcome
import dev.loom.bodycontrol.EquipmentDestination
import dev.loom.bodycontrol.PlaceOutcome
import dev.loom.bodycontrol.SensedBlock
import dev.loom.bodycontrol.SensedEntity
import dev.loom.bodycontrol.SensedRecipe
import dev.loom.bodycontrol.SensedStack
import dev.loom.bodycontrol.SleepOutcome
import dev.loom.witness.bukkit.serverReadingOf
import org.bukkit.Server
import org.bukkit.entity.Player

private const val WORLD_POSITION_AXES = 3
private const val QUICK_BAR_START = 36
private const val OFF_HAND_SLOT = 45
private const val HEAD_SLOT = 5
private const val TORSO_SLOT = 6
private const val LEGS_SLOT = 7
private const val FEET_SLOT = 8

/** Cross-checks one body result against Bukkit events or final server inventory. */
internal class BukkitInteractionAuditor(
    private val server: Server,
    private val events: BukkitInteractionEvents?,
) {
    fun audit(
        identity: PlayerIdentity,
        claim: ServerInteractionClaim,
    ): ServerInteractionAudit =
        when (claim) {
            is ServerInteractionClaim.Dig -> auditDig(identity, claim)
            is ServerInteractionClaim.Collect -> auditCollect(identity, claim)
            is ServerInteractionClaim.Place -> auditPlace(identity, claim)
            is ServerInteractionClaim.Equip -> auditEquip(identity, claim)
            is ServerInteractionClaim.Craft -> auditCraft(identity, claim)
            is ServerInteractionClaim.Attack -> auditAttack(identity, claim)
            is ServerInteractionClaim.Sleep -> auditSleep(identity, claim)
        }

    private fun auditDig(
        identity: PlayerIdentity,
        claim: ServerInteractionClaim.Dig,
    ): ServerInteractionAudit {
        val event =
            events?.evidence?.takeBreak(
                identity.uuid,
                claim.startedTick,
                claim.offered.position.point(),
            )
        val exact =
            event != null &&
                canonicalResource(event.what) == canonicalResource(claim.offered.what)
        val claimed = claim.report.outcome == DigOutcome.DUG
        // Two finishes crossed in the server: this body saw the block go after its own finish, and
        // the server's break names another managed player. The server's attribution stands.
        val other =
            if (claimed && event == null) {
                events?.evidence?.brokenByAnother(identity.uuid, claim.startedTick, claim.offered.position.point())
            } else {
                null
            }
        if (other != null && canonicalResource(other.what) == canonicalResource(claim.offered.what)) {
            return ServerInteractionAudit(
                confirmed = true,
                detail =
                    "blockBreak ${other.what} at ${other.block.words()} on tick ${other.tick} " +
                        "by another managed player",
                brokenByOther = true,
            )
        }
        return interactionEventAudit(
            claimed = claimed,
            eventFound = event != null,
            exact = exact,
            foundDetail =
                event?.let {
                    "blockBreak ${it.what} at ${it.block.words()} on tick ${it.tick}"
                },
            absentDetail = "no matching blockBreak event",
        )
    }

    private fun auditCollect(
        identity: PlayerIdentity,
        claim: ServerInteractionClaim.Collect,
    ): ServerInteractionAudit {
        val subject = claim.itemSubject
        if (subject == null) {
            return ServerInteractionAudit(
                confirmed = claim.report.outcome != CollectOutcome.COLLECTED,
                detail = "dropped item was not correlated to a server entity",
            )
        }
        val event =
            events?.evidence?.takeCollection(
                identity.uuid,
                claim.startedTick,
                subject,
            )
        val exact =
            event != null &&
                canonicalResource(claim.offered.what) == canonicalResource(event.what) &&
                claim.offered.count == event.stackCount &&
                claim.report.what?.let {
                    canonicalResource(it) == canonicalResource(event.what)
                } == true &&
                claim.report.count == event.count
        return interactionEventAudit(
            claimed = claim.report.outcome == CollectOutcome.COLLECTED,
            eventFound = event != null,
            exact = exact,
            foundDetail =
                event?.let {
                    "entityPickupItem ${it.itemEntity} ${it.count}/${it.stackCount} ${it.what} on tick ${it.tick}"
                },
            absentDetail = "no entityPickupItem event for $subject",
        )
    }

    private fun auditPlace(
        identity: PlayerIdentity,
        claim: ServerInteractionClaim.Place,
    ): ServerInteractionAudit {
        val expectedPosition =
            destinationOf(claim.offeredBlock.position.point(), claim.request)
        val event =
            events?.evidence?.takePlacement(
                identity.uuid,
                claim.startedTick,
                expectedPosition,
            )
        val exact =
            event != null &&
                claim.report.position?.point() == expectedPosition &&
                claim.report.what?.let {
                    canonicalResource(it) == canonicalResource(event.what)
                } == true
        return interactionEventAudit(
            claimed = claim.report.outcome == PlaceOutcome.PLACED,
            eventFound = event != null,
            exact = exact,
            foundDetail =
                event?.let {
                    "blockPlace ${it.what} at ${it.block.words()} on tick ${it.tick}"
                },
            absentDetail = "no matching blockPlace event",
        )
    }

    private fun auditEquip(
        identity: PlayerIdentity,
        claim: ServerInteractionClaim.Equip,
    ): ServerInteractionAudit {
        val player =
            server.getPlayer(identity.uuid)
                ?: return ServerInteractionAudit(
                    confirmed = false,
                    detail = "player absent before equipment could be checked",
                )
        val slot = equipmentSlot(player, claim.request.destination)
        val held = serverReadingOf(player).inventory.firstOrNull { it.slot == slot }
        val effectFound =
            held != null &&
                canonicalResource(held.what) == canonicalResource(claim.offeredStack.what) &&
                held.count == claim.offeredStack.count
        val exact =
            effectFound &&
                claim.report.toSlot == slot &&
                claim.report.what?.let {
                    canonicalResource(it) == canonicalResource(requireNotNull(held).what)
                } == true &&
                claim.report.count == held?.count
        val claimed = claim.report.outcome == EquipOutcome.EQUIPPED
        return ServerInteractionAudit(
            confirmed = if (claimed) exact else !effectFound,
            detail =
                if (held == null) {
                    "server slot $slot is empty"
                } else {
                    "server slot $slot holds ${held.count} ${held.what}"
                },
        )
    }

    private fun auditCraft(
        identity: PlayerIdentity,
        claim: ServerInteractionClaim.Craft,
    ): ServerInteractionAudit {
        val crafted =
            events?.evidence?.takeCrafts(
                identity.uuid,
                claim.startedTick,
            ).orEmpty()
        return craftEventAudit(claim.request, claim.report, claim.offeredRecipe, crafted)
    }

    private fun auditAttack(
        identity: PlayerIdentity,
        claim: ServerInteractionClaim.Attack,
    ): ServerInteractionAudit {
        val target = claim.targetSubject
        val damage = events?.evidence?.takeDamage(identity.uuid, claim.startedTick).orEmpty()
        val killed = events?.evidence?.takeKills(identity.uuid, claim.startedTick).orEmpty()
        return attackEventAudit(
            claim.report,
            claim.offeredTarget,
            claim.offeredWeapon,
            AttackAuditEvidence(target, damage, killed),
        )
    }

    private fun auditSleep(
        identity: PlayerIdentity,
        claim: ServerInteractionClaim.Sleep,
    ): ServerInteractionAudit {
        val entered = events?.evidence?.takeBedEntries(identity.uuid, claim.startedTick).orEmpty()
        val left = events?.evidence?.takeBedLeaves(identity.uuid, claim.startedTick).orEmpty()
        return sleepEventAudit(
            claim.report,
            claim.offeredBed,
            SleepAuditEvidence(entered, left),
        )
    }
}

internal fun craftEventAudit(
    request: BodyRequest.Craft,
    report: BodyResponse.CraftReport,
    offered: SensedRecipe,
    crafted: List<VanillaInteractionEvent.Crafted>,
): ServerInteractionAudit {
    val exactApplications =
        crafted.all {
            canonicalResource(it.what) == canonicalResource(offered.what) &&
                it.count == offered.resultCount
        }
    val exactCompletion =
        crafted.size == report.crafts &&
            crafted.sumOf { it.count } == report.count &&
            exactApplications
    // A refused craft is confirmed only by a partial effect: one the server saw complete every
    // application of is a false failure, and disputed (r19 theta body:00000310 and epsilon 352
    // reported libraryFailure for crafts the server finished, and were confirmed).
    val confirmed =
        when {
            report.outcome == CraftOutcome.CRAFTED -> exactCompletion
            !report.attempted -> crafted.isEmpty()
            else -> crafted.size < request.crafts && exactApplications
        }
    return ServerInteractionAudit(
        confirmed = confirmed,
        detail =
            crafted
                .takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "craftItem ", separator = ", ") {
                    "${it.count} ${it.what} on tick ${it.tick}"
                }
                ?: "no matching craftItem event",
        effected = crafted.size == request.crafts && exactApplications,
    )
}

internal fun attackEventAudit(
    report: BodyResponse.AttackReport,
    offeredTarget: SensedEntity,
    offeredWeapon: SensedStack,
    evidence: AttackAuditEvidence,
): ServerInteractionAudit {
    val targetSubject = evidence.targetSubject
    if (targetSubject == null) {
        return ServerInteractionAudit(
            confirmed =
                report.attacks == 0 &&
                    evidence.damage.isEmpty() &&
                    evidence.killed.isEmpty() &&
                    report.outcome !in
                    setOf(AttackOutcome.ATTACKED, AttackOutcome.TARGET_DIED),
            detail = "target was not correlated to a server entity",
        )
    }
    val exactDamage =
        evidence.damage.all {
            damageMatches(it, targetSubject, offeredTarget, offeredWeapon)
        }
    val exactKill =
        evidence.killed.all {
            killMatches(it, targetSubject, offeredTarget)
        }
    val confirmed = attackConfirmed(report, evidence, exactDamage, exactKill)
    val detail =
        buildString {
            append("server damage events ")
            append(evidence.damage.size)
            append('/')
            append(report.attacks)
            if (evidence.damage.isNotEmpty()) {
                append(", total damage ")
                append(evidence.damage.sumOf { it.damage })
            }
            append(", target death ")
            append(evidence.killed.size)
        }
    return ServerInteractionAudit(confirmed, detail)
}

internal data class AttackAuditEvidence(
    val targetSubject: java.util.UUID?,
    val damage: List<VanillaInteractionEvent.Damaged>,
    val killed: List<VanillaInteractionEvent.Killed>,
)

private fun damageMatches(
    damage: VanillaInteractionEvent.Damaged,
    targetSubject: java.util.UUID,
    offeredTarget: SensedEntity,
    offeredWeapon: SensedStack,
): Boolean =
    listOf(
        damage.target == targetSubject,
        canonicalResource(damage.targetWhat) == canonicalResource(offeredTarget.what),
        canonicalResource(damage.weapon) == canonicalResource(offeredWeapon.what),
        damage.damage >= 0.0,
    ).all { it }

private fun killMatches(
    killed: VanillaInteractionEvent.Killed,
    targetSubject: java.util.UUID,
    offeredTarget: SensedEntity,
): Boolean =
    listOf(
        killed.target == targetSubject,
        canonicalResource(killed.targetWhat) == canonicalResource(offeredTarget.what),
    ).all { it }

private fun attackConfirmed(
    report: BodyResponse.AttackReport,
    evidence: AttackAuditEvidence,
    exactDamage: Boolean,
    exactKill: Boolean,
): Boolean =
    when (report.outcome) {
        AttackOutcome.ATTACKED ->
            listOf(
                evidence.killed.isEmpty(),
                evidence.damage.size == report.attacks,
                evidence.damage.isNotEmpty(),
                exactDamage,
                exactKill,
            ).all { it }
        AttackOutcome.TARGET_DIED ->
            listOf(
                evidence.killed.size == 1,
                evidence.damage.isNotEmpty(),
                evidence.damage.size <= report.attacks,
                exactDamage,
                exactKill,
            ).all { it }
        else ->
            listOf(
                evidence.killed.isEmpty(),
                evidence.damage.size <= report.attacks,
                exactDamage,
                exactKill,
            ).all { it }
    }

internal fun sleepEventAudit(
    report: BodyResponse.SleepReport,
    offeredBed: SensedBlock,
    evidence: SleepAuditEvidence,
): ServerInteractionAudit {
    val bed = offeredBed.position.point()
    val exactEntry = evidence.entered.all { it.matches(offeredBed, bed) }
    val exactLeave = evidence.left.all { it.matches(offeredBed, bed) }
    val boundedEvents = evidence.entered.size <= 1 && evidence.left.size <= 1
    val serverEntered =
        evidence.entered.singleOrNull()?.result ==
            org.bukkit.event.player.PlayerBedEnterEvent.BedEnterResult.OK
    val confirmed =
        exactEntry &&
            exactLeave &&
            boundedEvents &&
            report.entered == serverEntered &&
            when (report.outcome) {
                SleepOutcome.WOKE -> serverEntered && evidence.left.size == 1
                SleepOutcome.TIMED_OUT,
                SleepOutcome.CANCELLED,
                SleepOutcome.BODY_DIED,
                SleepOutcome.DISCONNECTED,
                SleepOutcome.LIBRARY_FAILURE,
                -> !serverEntered || evidence.left.size == 1
                else -> !serverEntered
            }
    return ServerInteractionAudit(
        confirmed = confirmed,
        detail =
            "bedEnter ${
                evidence.entered.joinToString { it.result.name.lowercase() }.ifEmpty { "absent" }
            }; bedLeave ${
                evidence.left.joinToString { it.tick.toString() }.ifEmpty { "absent" }
            }",
    )
}

internal data class SleepAuditEvidence(
    val entered: List<VanillaInteractionEvent.BedEntered>,
    val left: List<VanillaInteractionEvent.BedLeft>,
)

private fun List<Int>.point(): BlockPoint {
    require(size == WORLD_POSITION_AXES)
    return BlockPoint(this[0], this[1], this[2])
}

private fun BlockPoint.words(): String = "$x,$y,$z"

private fun equipmentSlot(
    player: Player,
    destination: EquipmentDestination,
): Int =
    when (destination) {
        EquipmentDestination.HAND -> QUICK_BAR_START + player.inventory.heldItemSlot
        EquipmentDestination.OFF_HAND -> OFF_HAND_SLOT
        EquipmentDestination.HEAD -> HEAD_SLOT
        EquipmentDestination.TORSO -> TORSO_SLOT
        EquipmentDestination.LEGS -> LEGS_SLOT
        EquipmentDestination.FEET -> FEET_SLOT
    }

private fun destinationOf(
    support: BlockPoint,
    request: BodyRequest.Place,
): BlockPoint {
    val delta =
        when (request.face) {
            dev.loom.bodycontrol.BlockFace.DOWN -> BlockPoint(0, -1, 0)
            dev.loom.bodycontrol.BlockFace.UP -> BlockPoint(0, 1, 0)
            dev.loom.bodycontrol.BlockFace.NORTH -> BlockPoint(0, 0, -1)
            dev.loom.bodycontrol.BlockFace.SOUTH -> BlockPoint(0, 0, 1)
            dev.loom.bodycontrol.BlockFace.WEST -> BlockPoint(-1, 0, 0)
            dev.loom.bodycontrol.BlockFace.EAST -> BlockPoint(1, 0, 0)
        }
    return BlockPoint(support.x + delta.x, support.y + delta.y, support.z + delta.z)
}
