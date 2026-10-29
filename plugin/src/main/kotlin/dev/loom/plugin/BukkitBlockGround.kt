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
import org.bukkit.block.data.type.Snow
import org.bukkit.entity.EnderCrystal
import org.bukkit.entity.Entity
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.TNTPrimed
import org.bukkit.entity.Vehicle
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

    /**
     * What vanilla's `BlockState.canBeReplaced` lets a placed block replace (Paper's
     * `isReplaceable`): air, short grass, ferns, water, a vine. Snow is replaced by any item but
     * snow only as a single layer, which the placement context decides and the context-free test
     * does not.
     */
    override fun takesPlacement(position: List<Int>): Boolean {
        val block = insideBorder(position) ?: return false
        val snow = block.blockData as? Snow
        return block.isReplaceable && (snow == null || snow.layers == 1)
    }

    override fun breakable(position: List<Int>): Boolean = (blockAt(position)?.type?.hardness ?: -1f) >= 0f

    override fun holdsWater(position: List<Int>): Boolean {
        val block = blockAt(position) ?: return false
        return block.type == Material.WATER || block.type == Material.BUBBLE_COLUMN ||
            (block.blockData as? Waterlogged)?.isWaterlogged == true
    }

    /**
     * Vanilla's own obstruction test for a placement, `Level.isUnobstructed`: an entity that blocks
     * building, other than [besides], whose box overlaps the cell - a living body, a spectator
     * excepted, or a vehicle, a falling block, primed TNT or an end crystal, every entity vanilla
     * marks `blocksBuilding` - until the server removes it. A body in its death animation still
     * blocks, as vanilla reads it, though Bukkit already calls it dead (the round 3 offline audit),
     * so presence is Paper's `isInWorld`, never `isDead`.
     */
    override fun bodyIn(
        position: List<Int>,
        besides: UUID?,
    ): Boolean {
        val (x, y, z) = position
        val world = server.worlds.firstOrNull() ?: return false
        val cell = BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0)
        return world.getNearbyEntities(cell) { it.uniqueId != besides && blocksBuilding(it) }.isNotEmpty()
    }

    private fun blocksBuilding(entity: Entity): Boolean =
        when (entity) {
            is LivingEntity -> entity.isInWorld && (entity as? Player)?.gameMode != GameMode.SPECTATOR
            is Vehicle, is FallingBlock, is TNTPrimed, is EnderCrystal -> entity.isInWorld
            else -> false
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
