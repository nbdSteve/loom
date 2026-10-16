/*
 * The Bukkit answer to whether a body stands in a block.
 */
package dev.loom.plugin

import dev.loom.body.player.BlockGround
import org.bukkit.Server

/** Paper's own collision verdict for the block at a position in the overworld. */
class BukkitBlockGround(
    private val server: Server,
) : BlockGround {
    override fun standsIn(position: List<Int>): Boolean {
        val (x, y, z) = position
        return server.worlds.firstOrNull()?.getBlockAt(x, y, z)?.isPassable == true
    }
}
