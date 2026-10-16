/*
 * The server facts a walk named by a block needs: whether a body stands in that block, and
 * whether a body can stand at it at all.
 */
package dev.loom.body.player

/**
 * The server's ground, read on the main thread at exact sensed positions.
 *
 * [standsIn] is true where the server gives a block no collision, such as a dead bush, short
 * grass or air. A walk onto such a block ends in its own cell, and a walk onto any other ends in
 * the cell above it. [standCell] is where that is, or `null` when no body can stand there: a
 * block whose face is in sight can still be covered, and r10 sent a body to the solid cell on top
 * of a buried terracotta, which the ground correctly refused. These are facts about the blocks,
 * not a choice of destination.
 */
fun interface BlockGround {
    fun standsIn(position: List<Int>): Boolean

    /** Where a body stands on the block at [position], or `null` when no body can. */
    fun standCell(position: List<Int>): List<Int>? = standCellOn(this, position)

    companion object {
        /** No server to ask: every block is one a body stands on top of. */
        val UNKNOWN: BlockGround =
            object : BlockGround {
                override fun standsIn(position: List<Int>): Boolean = false

                override fun standCell(position: List<Int>): List<Int> = position.offsetY(1)
            }
    }
}

/**
 * Where a body stands on the block at [position] under [ground]'s collision verdicts.
 *
 * In the block's own cell when it has no collision, with solid ground below and an open cell
 * above for the head; otherwise in the cell above, when that cell and the one over it are open.
 */
internal fun standCellOn(
    ground: BlockGround,
    position: List<Int>,
): List<Int>? =
    if (ground.standsIn(position)) {
        position.takeIf { !ground.standsIn(position.offsetY(-1)) && ground.standsIn(position.offsetY(1)) }
    } else {
        position.offsetY(1).takeIf { ground.standsIn(it) && ground.standsIn(it.offsetY(1)) }
    }

private fun List<Int>.offsetY(by: Int): List<Int> = listOf(this[0], this[1] + by, this[2])
