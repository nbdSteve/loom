/*
 * The server facts a walk named by a block needs: whether a body stands in that block, and
 * whether a body can stand at it at all.
 */
package dev.loom.body.player

import java.util.UUID

/**
 * The server's ground, read on the main thread at exact sensed positions.
 *
 * [standsIn] is true where the server gives a block no collision, such as a dead bush, short
 * grass or air. A walk onto such a block ends in its own cell, and a walk onto any other ends in
 * the cell above it. [standCell] is where that is, or `null` when no body can stand there: a
 * block whose face is in sight can still be covered, and r10 sent a body to the solid cell on top
 * of a buried terracotta, which the ground correctly refused. These are facts about the blocks,
 * not a choice of destination.
 *
 * A cell past the server's world border is never one a body stands in: the border is a wall the
 * server enforces, and the v29 plains rehearsal offered walks past it to agents standing against
 * it (see [columnInsideBorder]).
 */
fun interface BlockGround {
    fun standsIn(position: List<Int>): Boolean

    /**
     * Whether the cell at [position] holds water, which a body passes but cannot stand in dry and
     * which floods any cell opened beside it (r27 epsilon). `false` where the ground cannot say.
     */
    fun holdsWater(position: List<Int>): Boolean = false

    /**
     * Whether the cell at [position] holds nothing at all, so a held block placed into it lands
     * there: air, never a plant a body walks through. `false` where the ground cannot say.
     */
    fun holdsNothing(position: List<Int>): Boolean = false

    /** Whether a body can break the block at [position] at all: not bedrock or a barrier. */
    fun breakable(position: List<Int>): Boolean = true

    /**
     * Whether the server holds a living body other than the player [besides] whose box reaches into
     * the cell at [position], where vanilla places no block. `false` where the ground cannot say.
     */
    fun bodyIn(
        position: List<Int>,
        besides: UUID?,
    ): Boolean = false

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
 * [this] with each cell's verdict read from the server once: a scene's walk search and lines of
 * sight read the same cells many times over, and every read is a main-thread world lookup.
 * Short-lived by design - one sense's scene - so a block that changes later is read anew.
 */
internal fun BlockGround.cached(): BlockGround {
    val source = this
    if (source === BlockGround.UNKNOWN) return source
    val open = HashMap<List<Int>, Boolean>()
    val water = HashMap<List<Int>, Boolean>()
    val empty = HashMap<List<Int>, Boolean>()
    val stands = HashMap<List<Int>, List<Int>?>()
    return object : BlockGround {
        override fun standsIn(position: List<Int>): Boolean = open.getOrPut(position) { source.standsIn(position) }

        override fun holdsWater(position: List<Int>): Boolean = water.getOrPut(position) { source.holdsWater(position) }

        override fun holdsNothing(position: List<Int>): Boolean =
            empty.getOrPut(position) { source.holdsNothing(position) }

        override fun breakable(position: List<Int>): Boolean = source.breakable(position)

        override fun bodyIn(
            position: List<Int>,
            besides: UUID?,
        ): Boolean = source.bodyIn(position, besides)

        // The source's own answer, which a ground may state its own way, read once a cell.
        override fun standCell(position: List<Int>): List<Int>? =
            if (position in stands) stands[position] else source.standCell(position).also { stands[position] = it }
    }
}

/**
 * [this] ground as the player [self] reads it: a living body in a cell is another's, since the
 * player's own box is where its own sense reported it, not where the server last heard of it.
 */
internal fun BlockGround.besides(self: UUID): BlockGround {
    val source = this
    if (source === BlockGround.UNKNOWN) return source
    return object : BlockGround {
        override fun standsIn(position: List<Int>): Boolean = source.standsIn(position)

        override fun holdsWater(position: List<Int>): Boolean = source.holdsWater(position)

        override fun holdsNothing(position: List<Int>): Boolean = source.holdsNothing(position)

        override fun breakable(position: List<Int>): Boolean = source.breakable(position)

        override fun bodyIn(
            position: List<Int>,
            besides: UUID?,
        ): Boolean = source.bodyIn(position, besides ?: self)

        override fun standCell(position: List<Int>): List<Int>? = source.standCell(position)
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
    }?.takeIf { cell ->
        // A cell holding water is no place to stand: a body starting dry treats water as a wall,
        // so a walk into one could never start (r27 epsilon's walks onto drowned sand).
        !ground.holdsWater(cell) && !ground.holdsWater(cell.offsetY(1))
    }

/** Half a player's width: a body's box reaches this far either side of where it stands. */
const val PLAYER_HALF_WIDTH: Double = 0.3

/**
 * Whether a body standing in block column ([x], [z]) lies wholly inside a world border centred
 * on ([centreX], [centreZ]) and [diameter] blocks across - the rule the body process searches
 * routes under (`body/src/border.js`).
 */
fun columnInsideBorder(
    centreX: Double,
    centreZ: Double,
    diameter: Double,
    x: Int,
    z: Int,
): Boolean {
    val half = diameter / HALVES

    fun inside(
        cell: Int,
        centre: Double,
    ): Boolean {
        val middle = cell + CELL_CENTRE
        return middle - PLAYER_HALF_WIDTH >= centre - half && middle + PLAYER_HALF_WIDTH <= centre + half
    }
    return inside(x, centreX) && inside(z, centreZ)
}

private const val HALVES = 2.0

private const val CELL_CENTRE = 0.5

private fun List<Int>.offsetY(by: Int): List<Int> = listOf(this[0], this[1] + by, this[2])
