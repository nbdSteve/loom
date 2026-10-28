/*
 * The one collision rule Loom reads the server's blocks by.
 */
package dev.loom.plugin

import org.bukkit.Location
import org.bukkit.block.data.BlockData

/**
 * Whether [data] gives the cell at [at] collision: vanilla's own collision shape for that state
 * there is not empty, the query Paper's `Block.isPassable` answers the negation of.
 *
 * Shelter cover reads placed cells by it, and item solidity reads the block an item places by it,
 * so whether a stack can close a face and whether a placed block closes it cannot drift apart
 * (r39: Paper's `Material.isSolid` called an oak pressure plate solid, which nothing collides
 * with, and theta was offered it as a closer).
 */
internal fun collides(
    data: BlockData,
    at: Location,
): Boolean = data.getCollisionShape(at).boundingBoxes.isNotEmpty()
