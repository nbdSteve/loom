/*
 * The Bukkit answer to whether an item can be eaten.
 */
package dev.loom.plugin

import dev.loom.body.player.ItemEdibility
import org.bukkit.Material

/** Paper's own `Material.isEdible` for the item a body names; `null` for a name it does not know. */
object BukkitItemEdibility : ItemEdibility {
    override fun edible(what: String): Boolean? = Material.matchMaterial(what)?.isEdible
}
