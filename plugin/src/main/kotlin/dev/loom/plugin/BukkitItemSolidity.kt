/*
 * The Bukkit answer to whether an item places as a block nothing passes.
 */
package dev.loom.plugin

import dev.loom.body.player.ItemSolidity
import org.bukkit.Material
import org.bukkit.World

/**
 * Whether each block an item places gives its cell collision, read from the server once by
 * [collides], the rule shelter cover reads placed cells by, for the block's default state. `null`
 * for a name the server does not know as a block, which a shelter reads as no.
 */
class BukkitItemSolidity(
    private val collision: Map<Material, Boolean>,
) : ItemSolidity {
    override fun solid(what: String): Boolean? = Material.matchMaterial(what)?.let(collision::get)

    companion object {
        /**
         * Reads every block material's default state in [world], on the main thread at enable, so
         * [solid] is a lookup any thread may make. A default state's collision does not depend on
         * which cell it is read at, so the world's spawn serves for every one.
         */
        fun readFrom(world: World): BukkitItemSolidity {
            val at = world.spawnLocation
            val collision =
                Material.entries
                    .filter { it.isBlock && !it.isLegacy }
                    .mapNotNull { material ->
                        runCatching { material to collides(material.createBlockData(), at) }.getOrNull()
                    }.toMap()
            return BukkitItemSolidity(collision)
        }
    }
}
