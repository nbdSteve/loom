/*
 * The Bukkit answer to whether an item places as a solid block.
 */
package dev.loom.plugin

import dev.loom.body.player.ItemSolidity
import org.bukkit.Material

/**
 * Paper's own `Material.isBlock` and `Material.isSolid` for the item a body names: a block a
 * creature cannot pass, which is what closes a shelter. `null` for a name it does not know.
 */
object BukkitItemSolidity : ItemSolidity {
    override fun solid(what: String): Boolean? = Material.matchMaterial(what)?.let { it.isBlock && it.isSolid }
}
