/*
 * The Bukkit answer to whether a body stands in a block.
 */
package dev.loom.plugin

import dev.loom.body.player.BlockGround
import dev.loom.body.player.columnInsideBorder
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.block.Block
import org.bukkit.block.data.Waterlogged
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import java.util.UUID

/**
 * Paper's own collision verdict for the block at a position in the overworld, by [collides], with
 * every cell past the world border closed, because the server lets no body stand there.
 */
class BukkitBlockGround(
    private val server: Server,
) : BlockGround {
    override fun standsIn(position: List<Int>): Boolean =
        insideBorder(position)?.let { !collides(it.blockData, it.location) } == true

    override fun holdsNothing(position: List<Int>): Boolean = insideBorder(position)?.type?.isAir == true

    override fun breakable(position: List<Int>): Boolean = (blockAt(position)?.type?.hardness ?: -1f) >= 0f

    override fun holdsWater(position: List<Int>): Boolean {
        val block = blockAt(position) ?: return false
        return block.type == Material.WATER || block.type == Material.BUBBLE_COLUMN ||
            (block.blockData as? Waterlogged)?.isWaterlogged == true
    }

    /**
     * Vanilla's own obstruction test for a placement, `Level.isUnobstructed`: a living body other
     * than [besides] whose box overlaps the cell, a spectator excepted.
     */
    override fun bodyIn(
        position: List<Int>,
        besides: UUID?,
    ): Boolean {
        val (x, y, z) = position
        val world = server.worlds.firstOrNull() ?: return false
        val cell = BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0)
        return world
            .getNearbyEntities(cell) { entity ->
                entity is LivingEntity && !entity.isDead && entity.uniqueId != besides &&
                    (entity as? Player)?.gameMode != GameMode.SPECTATOR
            }.isNotEmpty()
    }

    private fun blockAt(position: List<Int>): Block? {
        val (x, y, z) = position
        return server.worlds.firstOrNull()?.getBlockAt(x, y, z)
    }

    /** The block at [position], or `null` past the world border, where the server lets nothing be. */
    private fun insideBorder(position: List<Int>): Block? {
        val (x, y, z) = position
        val world = server.worlds.firstOrNull() ?: return null
        val border = world.worldBorder
        val centre = border.center
        return if (columnInsideBorder(centre.x, centre.z, border.size, x, z)) world.getBlockAt(x, y, z) else null
    }
}
