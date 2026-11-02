/*
 * The Bukkit answer to where a managed player stands and what is in its main hand.
 */
package dev.loom.plugin

import dev.loom.body.player.PlayerIdentity
import dev.loom.body.player.ServerPlayerView
import org.bukkit.Server
import org.bukkit.entity.Player

/** The protocol window slot of the first hotbar slot. */
private const val HOTBAR_WINDOW_START = 36

/** Paper's own location and held hotbar slot for the online, living player of an identity. */
class BukkitServerPlayerView(
    private val server: Server,
) : ServerPlayerView {
    override fun feetOf(identity: PlayerIdentity): List<Double>? =
        playerOf(identity)?.location?.let { listOf(it.x, it.y, it.z) }

    override fun mainHandSlotOf(identity: PlayerIdentity): Int? =
        playerOf(identity)?.inventory?.heldItemSlot?.let { HOTBAR_WINDOW_START + it }

    private fun playerOf(identity: PlayerIdentity): Player? =
        server.getPlayer(identity.uuid)?.takeIf { it.isOnline && !it.isDead }
}
