/*
 * Shared reconciliation for one-effect native interactions.
 */
package dev.loom.plugin

import dev.loom.body.player.ServerInteractionAudit
import dev.loom.bodycontrol.SensedBlock

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

internal fun VanillaInteractionEvent.BedEntered.matches(
    offered: SensedBlock,
    point: BlockPoint,
): Boolean =
    canonicalResource(what) == canonicalResource(offered.what) &&
        (bed == point || otherHalf == point)

internal fun VanillaInteractionEvent.BedLeft.matches(
    offered: SensedBlock,
    point: BlockPoint,
): Boolean =
    canonicalResource(what) == canonicalResource(offered.what) &&
        (bed == point || otherHalf == point)
