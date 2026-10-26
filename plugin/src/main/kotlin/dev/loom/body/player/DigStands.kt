/*
 * Where a body could stand to dig a block it sees from afar: a cell it stands in, by the server's
 * ground, whose eyes reach the block and see it past every block between.
 */
package dev.loom.body.player

import dev.loom.body.blocksApart
import kotlin.math.floor

/** `INTERACTION_REACH_BLOCKS` in `body/src/sensing.js`: eyes to a block's centre. */
private const val DIG_REACH_BLOCKS = 5.1

/** Mineflayer's player eye height, from which dig reach is measured. */
private const val DIG_EYE_HEIGHT_BLOCKS = 1.62

/** How far inside dig reach a stand keeps the block, so a body arriving off-centre still reaches it. */
private const val DIG_REACH_MARGIN_BLOCKS = 0.3

/** How far, along each horizontal axis, a stand may lie from the block. */
private const val DIG_STAND_SPAN = 4

/** How far below the block, and above it, a stand's feet may be. */
private const val DIG_STAND_BELOW = 5
private const val DIG_STAND_ABOVE = 1

/** The most walkable stands one block's check reads, nearest the block first. */
private const val MAX_DIG_STANDS = 24

/** The step a line of sight is sampled at, in blocks. */
private const val SIGHT_STEP_BLOCKS = 0.2

/** A block's middle, along one axis, and how far inside a face a line of sight may end. */
private const val AIM_MIDDLE = 0.5
private const val AIM_FACE_INSET = 0.02

/** Where on a block a line of sight may end: its centre, and just inside each face's centre. */
private val AIMS: List<List<Double>> =
    listOf(listOf(AIM_MIDDLE, AIM_MIDDLE, AIM_MIDDLE)) +
        (0..2).flatMap { axis ->
            listOf(AIM_FACE_INSET, 1 - AIM_FACE_INSET).map { at ->
                (0..2).map { other -> if (other == axis) at else AIM_MIDDLE }
            }
        }

/**
 * Whether a body standing in [stand] reaches [block] from its eyes and sees it: every cell a line
 * from the eyes to some aim on the block crosses, short of the block, is one the server gives no
 * collision, as leaves are not (r35: epsilon and zeta walked to a canopy log seen through a gap,
 * whose trunk eta and alpha had dug, and stood under leaves that hid it).
 */
internal fun digsFrom(
    ground: BlockGround,
    stand: List<Int>,
    block: List<Int>,
): Boolean {
    val eyes = listOf(stand[0] + AIM_MIDDLE, stand[1] + DIG_EYE_HEIGHT_BLOCKS, stand[2] + AIM_MIDDLE)
    val centre = block.map { it + AIM_MIDDLE }
    if (blocksApart(eyes, centre) > DIG_REACH_BLOCKS - DIG_REACH_MARGIN_BLOCKS) return false
    return AIMS.any { aim -> clearLine(ground, eyes, block.indices.map { block[it] + aim[it] }, block) }
}

/** Whether nothing with collision stands between [from] and [to], short of [target]'s own cell. */
private fun clearLine(
    ground: BlockGround,
    from: List<Double>,
    to: List<Double>,
    target: List<Int>,
): Boolean {
    val steps = (blocksApart(from, to) / SIGHT_STEP_BLOCKS).toInt().coerceAtLeast(1)
    return (1 until steps).all { step ->
        val cell = from.indices.map { axis -> floor(from[axis] + (to[axis] - from[axis]) * step / steps).toInt() }
        cell == target || ground.standsIn(cell)
    }
}

/**
 * Whether some cell in [walkable] - where a body can walk to - digs [block] ([digsFrom]): a block
 * sight shows beyond dig reach that no such stand digs, as a canopy log whose only stands are on
 * the leaves above it, is no source a walk could reach.
 */
internal fun diggableFrom(
    ground: BlockGround,
    block: List<Int>,
    walkable: Set<List<Int>>,
): Boolean {
    val (x, y, z) = block
    return (x - DIG_STAND_SPAN..x + DIG_STAND_SPAN)
        .flatMap { cx -> (z - DIG_STAND_SPAN..z + DIG_STAND_SPAN).map { cz -> cx to cz } }
        .flatMap { (cx, cz) -> (y + DIG_STAND_ABOVE downTo y - DIG_STAND_BELOW).map { cy -> listOf(cx, cy, cz) } }
        .filter { it in walkable }
        .sortedBy { blocksApart(it.map(Int::toDouble), block.map(Int::toDouble)) }
        .take(MAX_DIG_STANDS)
        .any { cell -> digsFrom(ground, cell, block) }
}

/** How far a walk to a source sight shows is searched: the sense's reach and a stand beside it. */
private const val SIGHT_WALK_RADIUS_BLOCKS = 20

/**
 * One sense's reads of the ground: each cell's verdict taken from the server once, and where a
 * body standing at [feet] can walk to, searched only when a far source asks - a search on every
 * threat scan stalled the server's tick (the v34 path rehearsal).
 */
internal class SceneGround(
    source: BlockGround,
    feet: List<Double>,
) {
    val ground: BlockGround = source.cached()

    val walkable: Set<List<Int>> by lazy(LazyThreadSafetyMode.NONE) {
        walkableFrom(ground, feet.map { floor(it).toInt() }, SIGHT_WALK_RADIUS_BLOCKS)
    }
}
