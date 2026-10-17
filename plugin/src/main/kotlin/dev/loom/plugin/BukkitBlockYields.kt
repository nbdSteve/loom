/*
 * The Bukkit answer to what digging a block drops.
 */
package dev.loom.plugin

import dev.loom.body.player.BlockYields
import dev.loom.body.player.PlayerIdentity
import dev.loom.body.player.yieldsOf
import dev.loom.contract.BlockYield
import org.bukkit.Server
import org.bukkit.inventory.ItemStack

/**
 * Paper's own loot roll for the block at a position, broken with the player's main-hand item.
 *
 * A loot table can be random - a dead bush drops nought to two sticks - so the roll is sampled
 * [SAMPLES] times and each item is reported with the fewest and most of it any roll dropped. An
 * item that can drop at all is then reported with near certainty (a dead bush's two sticks come
 * one roll in three, so 32 rolls miss them about once in half a million), one that never can
 * (bare-hand terracotta) never is, and an empty or air stack is no drop at all.
 */
class BukkitBlockYields(
    private val server: Server,
) : BlockYields {
    override fun of(
        position: List<Int>,
        player: PlayerIdentity,
    ): List<BlockYield> {
        val (x, y, z) = position
        val world = server.worlds.firstOrNull()
        val online = server.getPlayer(player.uuid)
        if (world == null || online == null) return emptyList()
        val block = world.getBlockAt(x, y, z)
        val tool = online.inventory.itemInMainHand
        return yieldsOf((1..SAMPLES).map { rollOf(block.getDrops(tool, online)) })
    }

    private companion object {
        const val SAMPLES = 32
    }
}

/** One loot roll as item name to count, leaving out every air or empty stack. */
internal fun rollOf(drops: Collection<ItemStack>): Map<String, Long> =
    drops
        .filter { !it.type.isAir && it.amount > 0 }
        .groupBy({ "item.${it.type.key.namespace}.${it.type.key.key.replace('/', '.')}" }, { it.amount.toLong() })
        .mapValues { (_, counts) -> counts.sum() }
