/*
 * Server-authoritative reconciliation of one completed native interaction.
 */
package dev.loom.plugin

import dev.loom.body.player.PlayerIdentity
import dev.loom.body.player.ServerInteractionAudit
import dev.loom.body.player.ServerInteractionClaim
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.CollectOutcome
import dev.loom.bodycontrol.DigOutcome
import dev.loom.bodycontrol.EquipOutcome
import dev.loom.bodycontrol.EquipmentDestination
import dev.loom.bodycontrol.PlaceOutcome
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
        }

    private fun auditDig(
        identity: PlayerIdentity,
        claim: ServerInteractionClaim.Dig,
    ): ServerInteractionAudit {
        val event =
            events?.takeBreak(
                identity.uuid,
                claim.startedTick,
                claim.offered.position.point(),
            )
        val exact =
            event != null &&
                canonicalResource(event.what) == canonicalResource(claim.offered.what)
        return interactionEventAudit(
            claimed = claim.report.outcome == DigOutcome.DUG,
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
            events?.takeCollection(
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
            events?.takePlacement(
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
}

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

internal fun interactionEventAudit(
    claimed: Boolean,
    eventFound: Boolean,
    exact: Boolean,
    foundDetail: String?,
    absentDetail: String,
): ServerInteractionAudit =
    ServerInteractionAudit(
        confirmed = if (claimed) exact else !eventFound,
        detail = foundDetail ?: absentDetail,
    )
