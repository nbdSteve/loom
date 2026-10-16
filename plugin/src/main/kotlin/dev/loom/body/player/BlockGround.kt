/*
 * The one server fact a walk named by a block needs: whether a body stands in that block.
 */
package dev.loom.body.player

/**
 * Whether a body stands *in* the block at a position rather than on top of it.
 *
 * True where the server gives that block no collision, such as a dead bush or short grass:
 * the cell above one is open air, so the place a walk onto it can end is its own cell. Read on
 * the server main thread at the exact sensed position. It is a fact about the block, not a
 * choice of destination, and false wherever the server cannot say.
 */
fun interface BlockGround {
    fun standsIn(position: List<Int>): Boolean

    companion object {
        /** No server to ask: every block is one a body stands on. */
        val UNKNOWN: BlockGround = BlockGround { false }
    }
}
