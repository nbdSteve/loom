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
 * A loot table can be random - a dead bush drops nought to two sticks, leaves a stick about one
 * break in fifty - so the roll is sampled [SAMPLES] times and each item is reported with the
 * fewest and most of it any roll dropped and the mean a hundred rolls drop. The mean is what a
 * goal may count on: r19 sized seven stick goals to leaves read at the middle of their range, and
 * thirty-three digs gave one stick. Sampling once per block state and tool keeps that many rolls
 * off every perception: the same state broken with the same tool rolls the same table, so its
 * reading is kept, at most [MAX_CACHED] of them, the oldest forgotten first.
 */
class BukkitBlockYields(
    private val server: Server,
) : BlockYields {
    private val sampled =
        object : LinkedHashMap<String, List<BlockYield>>(MAX_CACHED, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<BlockYield>>?): Boolean =
                size > MAX_CACHED
        }

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
        val key = "${block.blockData.asString}|${tool.type.key}|${tool.enchantments}"
        return sampled.getOrPut(key) { yieldsOf((1..SAMPLES).map { rollOf(block.getDrops(tool, online)) }) }
    }

    private companion object {
        const val SAMPLES = 2000
        const val MAX_CACHED = 256
        const val LOAD_FACTOR = 0.75f
    }
}

/** One loot roll as item name to count, leaving out every air or empty stack. */
internal fun rollOf(drops: Collection<ItemStack>): Map<String, Long> =
    drops
        .filter { !it.type.isAir && it.amount > 0 }
        .groupBy({ "item.${it.type.key.namespace}.${it.type.key.key.replace('/', '.')}" }, { it.amount.toLong() })
        .mapValues { (_, counts) -> counts.sum() }
