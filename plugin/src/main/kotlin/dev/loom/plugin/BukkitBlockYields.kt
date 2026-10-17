/*
 * The Bukkit answer to what digging a block drops.
 */
package dev.loom.plugin

import dev.loom.body.player.BlockYields
import dev.loom.body.player.PlayerIdentity
import org.bukkit.Server

/**
 * Paper's own loot roll for the block at a position, broken with the player's main-hand item.
 *
 * A loot table can be random - a dead bush drops nought to two sticks - so the roll is sampled
 * [SAMPLES] times and the union of what it ever dropped is the answer; an item that drops at
 * all is then reported with near certainty, and one that never can (bare-hand terracotta) never is.
 */
class BukkitBlockYields(
    private val server: Server,
) : BlockYields {
    override fun of(
        position: List<Int>,
        player: PlayerIdentity,
    ): List<String> {
        val (x, y, z) = position
        val world = server.worlds.firstOrNull()
        val online = server.getPlayer(player.uuid)
        if (world == null || online == null) return emptyList()
        val block = world.getBlockAt(x, y, z)
        val tool = online.inventory.itemInMainHand
        return (1..SAMPLES)
            .flatMap { block.getDrops(tool, online) }
            .map { "item.${it.type.key.namespace}.${it.type.key.key.replace('/', '.')}" }
            .distinct()
            .sorted()
    }

    private companion object {
        const val SAMPLES = 16
    }
}
