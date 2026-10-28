/*
 * How enclosed a body stands, read from the server's ground: the nine faces around the two cells
 * it fills, whether the open space it stands in is sealed, and what one step could enclose it more.
 *
 * This is the shelter a model-chosen shelter goal builds, measured rather than claimed (r36: all ten
 * agents died at night in the open, every one with nothing to hide in and no way to tell a hiding
 * place from open ground). A face is closed where the server gives its cell collision; the body's
 * own two cells are always open, since it stands in them. Nothing here moves a body or chooses a
 * step: it states what the ground already says, and which of the existing one-shot actions - a dig
 * underfoot, a placement against a sensed block - would change it.
 */
package dev.loom.body.player

import dev.loom.body.blocksApart
import dev.loom.contract.BlockFace
import dev.loom.contract.Closing
import dev.loom.contract.Cover
import dev.loom.contract.CoverFace
import kotlin.math.floor

/**
 * The most cells the open space a body stands in may span and still be sealed: a pit, a dug room
 * or a cave pocket fits, and anything open to the world runs past it at once.
 */
internal const val MAX_ENCLOSURE_CELLS = 64

/** How far inside interaction reach a placement's support must lie, so a body off-centre still reaches it. */
private const val COVER_REACH_MARGIN_BLOCKS = 0.1

/** The middle of a cell along one axis, and Mineflayer's eye height above the feet. */
private const val CELL_MIDDLE = 0.5
private const val COVER_EYE_HEIGHT_BLOCKS = 1.62

/** `INTERACTION_REACH_BLOCKS` in `body/src/sensing.js`: eyes to a block's centre. */
private const val COVER_REACH_BLOCKS = 5.1

/** Each cover face and its cell's offset from the feet cell. */
internal val COVER_OFFSETS: List<Pair<CoverFace, List<Int>>> =
    listOf(
        CoverFace.FEET_NORTH to listOf(0, 0, -1),
        CoverFace.FEET_EAST to listOf(1, 0, 0),
        CoverFace.FEET_SOUTH to listOf(0, 0, 1),
        CoverFace.FEET_WEST to listOf(-1, 0, 0),
        CoverFace.HEAD_NORTH to listOf(0, 1, -1),
        CoverFace.HEAD_EAST to listOf(1, 1, 0),
        CoverFace.HEAD_SOUTH to listOf(0, 1, 1),
        CoverFace.HEAD_WEST to listOf(-1, 1, 0),
        CoverFace.ROOF to listOf(0, 2, 0),
    )

/**
 * The neighbours a placement's support may lie in, from the cell it fills, each with the side of
 * the support that faces that cell: below first, as a block is most often set on another.
 */
private val SUPPORT_SIDES: List<Pair<List<Int>, BlockFace>> =
    listOf(
        listOf(0, -1, 0) to BlockFace.UP,
        listOf(0, 0, -1) to BlockFace.SOUTH,
        listOf(1, 0, 0) to BlockFace.WEST,
        listOf(0, 0, 1) to BlockFace.NORTH,
        listOf(-1, 0, 0) to BlockFace.EAST,
        listOf(0, 1, 0) to BlockFace.DOWN,
    )

private val NEIGHBOURS: List<List<Int>> = SUPPORT_SIDES.map { it.first }

internal fun List<Int>.offset(by: List<Int>): List<Int> = indices.map { this[it] + by[it] }

/** A sensed block a placement may name: its ref, its cell, and whether the body saw it. */
internal data class CoverSupport(
    val ref: String,
    val cell: List<Int>,
    val visible: Boolean,
)

/**
 * The cover around a body whose feet stand in [feet]: the faces still open, a placement that would
 * close each open face it can, and whether the space it stands in is sealed. Ordered by how
 * enclosed it is: more faces closed, then more closable from here, which is what a shelter step
 * must raise (r36 replays: a pit's second dig closes four faces, its third makes the roof closable).
 */
internal data class CoverReading(
    val open: List<CoverFace>,
    val closings: List<Closing>,
    val sealed: Boolean,
) : Comparable<CoverReading> {
    val closed: Int get() = COVER_OFFSETS.size - open.size

    val closable: Int get() = closings.size

    override fun compareTo(other: CoverReading): Int =
        compareValuesBy(this, other, { it.sealed }, { it.closed }, { it.closable })
}

/**
 * The cover around feet cell [feet] for a body standing at [standing], whose eyes reach the
 * [supports] a placement may name. A face whose cell the body's own box reaches into has no
 * closing from here, since vanilla places no block where a body stands (r39 iota: 0.227 of a block
 * past its feet cell's north edge, offered feetNorth, and refused by the server).
 */
internal fun coverAt(
    ground: BlockGround,
    feet: List<Int>,
    standing: List<Double>,
    supports: Map<List<Int>, CoverSupport>,
): CoverReading {
    val eyes = eyesAt(standing)
    val own = setOf(feet, feet.offset(listOf(0, 1, 0)))
    val open = COVER_OFFSETS.filter { (_, offset) -> ground.standsIn(feet.offset(offset)) }
    val closings =
        open.mapNotNull { (face, offset) ->
            val cell = feet.offset(offset)
            if (!ground.holdsNothing(cell) || bodyReaches(standing, cell)) return@mapNotNull null
            SUPPORT_SIDES.firstNotNullOfOrNull { (toward, side) ->
                val at = cell.offset(toward)
                supports[at]
                    ?.takeIf { at !in own && it.visible && !ground.standsIn(at) && reaches(eyes, at) }
                    ?.let { Closing(face, it.ref, side) }
            }
        }
    return CoverReading(open.map { it.first }, closings, sealedAt(ground, feet))
}

/** Whether a body's eyes at [eyes] reach the centre of the block at [cell]. */
private fun reaches(
    eyes: List<Double>,
    cell: List<Int>,
): Boolean = blocksApart(eyes, cell.map { it + CELL_MIDDLE }) <= COVER_REACH_BLOCKS - COVER_REACH_MARGIN_BLOCKS

/** The eyes of a body whose feet stand at [position]. */
internal fun eyesAt(position: List<Double>): List<Double> =
    listOf(position[0], position[1] + COVER_EYE_HEIGHT_BLOCKS, position[2])

/**
 * Whether the open space a body with feet in [feet] stands in is sealed: searched from its two cells
 * through every cell the server gives no collision, it ends within [MAX_ENCLOSURE_CELLS], so nothing
 * outside can walk, climb or swim in. Never where the ground gives those two cells collision.
 */
internal fun sealedAt(
    ground: BlockGround,
    feet: List<Int>,
): Boolean {
    val start = listOf(feet, feet.offset(listOf(0, 1, 0)))
    val seen = start.toMutableSet()
    val frontier = ArrayDeque(start)
    // Ground that gives the body's own cells collision is not ground a body stands in: no shelter.
    var bounded = start.all(ground::standsIn)
    while (bounded && frontier.isNotEmpty()) {
        val cell = frontier.removeFirst()
        for (next in NEIGHBOURS.map(cell::offset).filter { it !in seen && ground.standsIn(it) }) {
            seen += next
            frontier.addLast(next)
        }
        bounded = seen.size <= MAX_ENCLOSURE_CELLS
    }
    return bounded
}

/** How many of the nine faces around a body standing in [cell] the server gives collision. */
internal fun closedFacesAt(
    ground: BlockGround,
    cell: List<Int>,
): Int = COVER_OFFSETS.count { (_, offset) -> !ground.standsIn(cell.offset(offset)) }

/**
 * The ref of the block under the feet of a body at [position] when digging it deepens its cover:
 * the body drops one cell onto solid ground it can break no further than, no water floods in, and
 * the cell it lands in is more enclosed than [current] by the [CoverReading] order. `null` otherwise.
 */
internal fun descendOf(
    ground: BlockGround,
    position: List<Double>,
    supports: Map<List<Int>, CoverSupport>,
    current: CoverReading,
): String? {
    val under = supportCellOf(position)
    val support = supports[under]?.takeIf { it.visible && descentSafe(ground, position) } ?: return null
    val landing = listOf(position[0], under[1].toDouble(), position[2])
    val after = coverAt(ground.opening(under), under, landing, supports - setOf(under))
    return support.ref.takeIf { after > current }
}

/**
 * Whether digging the block under the feet of a body at [position] drops it one cell, safely: the
 * block can be broken, the ground below it has collision for the body to land on, and no water
 * floods the cells it lands in. The rule a descent is offered by and held to when it is applied.
 */
internal fun descentSafe(
    ground: BlockGround,
    position: List<Double>,
): Boolean {
    val under = supportCellOf(position)
    val lands = !ground.standsIn(under.offset(listOf(0, -1, 0)))
    return !ground.standsIn(under) && ground.breakable(under) && lands && digLeavesBodyDry(ground, position, under)
}

/** [this] ground with the block at [cell] dug open. */
internal fun BlockGround.opening(cell: List<Int>): BlockGround {
    val source = this
    return object : BlockGround {
        override fun standsIn(position: List<Int>): Boolean = position == cell || source.standsIn(position)

        override fun holdsWater(position: List<Int>): Boolean = position != cell && source.holdsWater(position)

        override fun holdsNothing(position: List<Int>): Boolean = position == cell || source.holdsNothing(position)

        override fun breakable(position: List<Int>): Boolean = source.breakable(position)
    }
}

/**
 * The cover a body at [position] reads: [supports] are the blocks this perception offers by cell,
 * and [blockItems] the solid-block items sight could give it now. `null` where there is no server
 * ground to read.
 */
internal fun coverOf(
    ground: BlockGround,
    position: List<Double>,
    supports: Map<List<Int>, CoverSupport>,
    blockItems: List<String>,
): Cover? {
    if (ground === BlockGround.UNKNOWN) return null
    val feet = position.map { floor(it).toInt() }
    val reading = coverAt(ground, feet, position, supports)
    return Cover(
        sealed = reading.sealed,
        walkOut = escapesFrom(ground, feet),
        open = reading.open,
        closings = reading.closings,
        descend = descendOf(ground, position, supports, reading),
        blockItems = blockItems.distinct().take(dev.loom.contract.MAX_COVER_BLOCK_ITEMS),
    )
}
