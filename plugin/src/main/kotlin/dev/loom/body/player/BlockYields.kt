/*
 * The two server facts a block observation states beyond where it is: what digging it yields,
 * and whether digging it leaves the body a way out.
 */
package dev.loom.body.player

import dev.loom.contract.BlockYield
import kotlin.math.floor

/**
 * What digging the block at a position with what a player holds can drop, as item names
 * (`item.minecraft.stick`) with the range of each the server's own random loot rules give, read
 * on the main thread.
 *
 * Empty when the dig drops nothing, which is the evidence that bare-hand terracotta, or a dead
 * bush without shears, can never yield the item a goal names (the r13 audit: alpha, epsilon,
 * eta and iota spent half the run on such goals). Never air: a roll that drops nothing is no
 * item (r14: every dead bush listed `item.minecraft.air`).
 */
fun interface BlockYields {
    fun of(
        position: List<Int>,
        player: PlayerIdentity,
    ): List<BlockYield>

    companion object {
        /** No server to ask: nothing is known to drop. */
        val NONE: BlockYields = BlockYields { _, _ -> emptyList() }
    }
}

/**
 * The yields a set of sampled loot rolls shows, one per item: [rolls] holds, per roll, how many
 * of each item it dropped. An item absent from a roll dropped none in it, so its least is zero.
 */
fun yieldsOf(rolls: List<Map<String, Long>>): List<BlockYield> =
    rolls
        .flatMap { it.keys }
        .distinct()
        .sorted()
        .mapNotNull { item ->
            val counts = rolls.map { it[item] ?: 0L }
            val most = counts.max()
            if (most < 1) null else BlockYield(item, counts.min(), most.coerceAtMost(MAX_YIELD_COUNT))
        }

private const val MAX_YIELD_COUNT = 64L

/** The block cell directly under a body standing at [position]. */
internal fun supportCellOf(position: List<Double>): List<Int> =
    listOf(floor(position[0]).toInt(), floor(position[1]).toInt() - 1, floor(position[2]).toInt())

/**
 * Whether digging [block] leaves a body standing at [position] a way out.
 *
 * Only the block underfoot can trap a body: dug, it drops the body one cell into a hole whose
 * walls are its four neighbours, and the movement policy neither digs nor towers out. So it is
 * safe only over solid ground, with a neighbour at the support's level the body can then stand
 * on or walk into. r13's eta dug its support four times and fled from the shaft three times with
 * no route out.
 */
internal fun digLeavesExit(
    ground: BlockGround,
    position: List<Double>,
    block: List<Int>,
): Boolean {
    val support = supportCellOf(position)
    val (x, y, z) = support
    val sides = listOf(listOf(x + 1, y, z), listOf(x - 1, y, z), listOf(x, y, z + 1), listOf(x, y, z - 1))
    return block != support ||
        !ground.standsIn(listOf(x, y - 1, z)) &&
        sides.any { side ->
            ground.standCell(side) != null || ground.standCell(listOf(side[0], side[1] - 1, side[2])) == side
        }
}
