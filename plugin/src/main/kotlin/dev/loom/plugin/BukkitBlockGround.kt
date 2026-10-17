/*
 * The Bukkit answer to whether a body stands in a block.
 */
package dev.loom.plugin

import dev.loom.body.player.BlockGround
import dev.loom.body.player.columnInsideBorder
import org.bukkit.Server

/**
 * Paper's own collision verdict for the block at a position in the overworld, with every cell
 * past the world border closed, because the server lets no body stand there.
 */
class BukkitBlockGround(
    private val server: Server,
) : BlockGround {
    override fun standsIn(position: List<Int>): Boolean {
        val (x, y, z) = position
        val world = server.worlds.firstOrNull() ?: return false
        val border = world.worldBorder
        val centre = border.center
        return columnInsideBorder(centre.x, centre.z, border.size, x, z) && world.getBlockAt(x, y, z).isPassable
    }
}
