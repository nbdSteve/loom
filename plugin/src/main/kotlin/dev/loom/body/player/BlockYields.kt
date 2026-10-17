/*
 * The server facts a block observation states beyond where it is: what digging it yields, and
 * whether digging it leaves the body a way out and its drop where a body can collect it.
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
 * of each item it dropped. An item absent from a roll dropped none in it, so its least is zero,
 * and its mean per hundred digs is the rolls' own average, rounded.
 */
fun yieldsOf(rolls: List<Map<String, Long>>): List<BlockYield> =
    rolls
        .flatMap { it.keys }
        .distinct()
        .sorted()
        .mapNotNull { item ->
            val counts = rolls.map { (it[item] ?: 0L).coerceAtMost(MAX_YIELD_COUNT) }
            val most = counts.max()
            val least = counts.min()
            val mean = (counts.sum() * PER_HUNDRED_DIGS + rolls.size / 2) / rolls.size
            if (most < 1) {
                null
            } else {
                BlockYield(
                    item,
                    least,
                    most,
                    mean.coerceIn(least * PER_HUNDRED_DIGS, most * PER_HUNDRED_DIGS),
                )
            }
        }

private const val PER_HUNDRED_DIGS = 100L

private const val MAX_YIELD_COUNT = 64L

/** The block cell directly under a body standing at [position]. */
internal fun supportCellOf(position: List<Double>): List<Int> =
    listOf(floor(position[0]).toInt(), floor(position[1]).toInt() - 1, floor(position[2]).toInt())

/**
 * Whether digging [block] leaves a body at [position] both a way out ([digLeavesExit]) and its
 * drop where a body can collect it ([digLeavesDropReachable]): what a block observation states as
 * `digSafe`.
 */
internal fun digSafeAt(
    ground: BlockGround,
    position: List<Double>,
    block: List<Int>,
): Boolean = digLeavesExit(ground, position, block) && digLeavesDropReachable(ground, position, block)

/**
 * Whether the drop of digging [block] below the feet of a body at [position] lands where a body
 * can step down to collect it. A block whose cell above is open opens a pocket the drop falls
 * into, and the pocket is out of reach when it lies two or more cells below the feet, when its
 * floor is open too so the drop falls further, or when the cell over its mouth is solid so no
 * body can step down into it without striking its head. r19 beta body:00000441 and kappa dug the
 * block diagonally below their feet under the edge of a hill; each drop lay in a one-block pocket
 * under an overhang, and each collect was refused for no route (body:00000453, 00000384). Only
 * the ground's positive answer refuses: a block at or above the feet, the support itself, which
 * [digLeavesExit] judges, and a block whose cell above is solid are not this rule's.
 */
internal fun digLeavesDropReachable(
    ground: BlockGround,
    position: List<Double>,
    block: List<Int>,
): Boolean {
    val feet = floor(position[1]).toInt()
    val (x, y, z) = block
    val mouth = listOf(x, y + 1, z)
    if (y >= feet || block == supportCellOf(position) || !ground.standsIn(mouth)) return true
    val deep = y <= feet - 2
    val bottomless = ground.standsIn(listOf(x, y - 1, z))
    val overhung = !ground.standsIn(listOf(x, y + 2, z))
    return !deep && !bottomless && !overhung
}

/**
 * Whether digging [block] leaves a body standing at [position] a way out.
 *
 * Only the block underfoot can trap a body: dug, it drops the body one cell into a hole whose
 * walls are its four neighbours, and the movement policy neither digs nor towers out. So it is
 * safe only over solid ground, and only where the body can walk out of where it lands
 * ([escapesFrom]). r20's four shaft-diggers each passed a one-level check on every dig, and every
 * one of their 13 flights was `noRoute`. r13's eta dug its support four times and fled from the
 * shaft three times with no route out.
 */
internal fun digLeavesExit(
    ground: BlockGround,
    position: List<Double>,
    block: List<Int>,
): Boolean {
    val support = supportCellOf(position)
    val (x, y, z) = support
    val sides = listOf(listOf(x + 1, y, z), listOf(x - 1, y, z), listOf(x, y, z + 1), listOf(x, y, z - 1))
    return when {
        block != support -> true
        ground.standsIn(listOf(x, y - 1, z)) -> false
        // Where the ground does not report the body's own feet cell open it has no word on the
        // walk out, and the one-level rule stands: a neighbour to step up onto.
        !ground.standsIn(listOf(x, y + 1, z)) -> sides.any { side -> ground.standCell(side) != null }
        else -> escapesFrom(ground, support)
    }
}
