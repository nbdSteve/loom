/*
 * The cells at the edge of sight a perception offers to walk to, and the waypoint beside a
 * remembered source nearer than that edge.
 */
package dev.loom.body.player

import dev.loom.body.blocksApart
import dev.loom.body.horizontalBearing
import dev.loom.contract.ExploreTarget

/** A cell's centre, from its corner. */
private const val CELL_CENTRE = 0.5

/** How far along each compass axis an exploration cell lies: past the ~5 blocks a body sees. */
internal const val EXPLORE_REACH_BLOCKS = 12

/** The same along each axis of a diagonal bearing: about twelve blocks off in all. */
private const val EXPLORE_DIAGONAL_BLOCKS = 8

/** How far above and below the feet an exploration cell's ground is looked for. */
private const val EXPLORE_RISE_BLOCKS = 3
private const val EXPLORE_FALL_BLOCKS = 4

/** The eight compass steps, in the order their refs are minted: north first, clockwise. */
private val COMPASS =
    listOf(0 to -1, 1 to -1, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1)

/** The cells to look further from, a remembered source's beside it rather than past it. */
internal fun PlayerBodyContext.exploreFor(
    raw: RawSense,
    selected: List<SceneOffer>,
): List<Pair<ExploreTarget, List<Int>>> {
    val remembered = recall.recalled(raw.report.position, selected).mapNotNull(recall::positionOf)
    return exploreOf(authority.ground, raw.report.position, remembered)
}

/**
 * The cells at the edge of sight an agent at [position] can walk to, one per compass bearing:
 * the highest cell the server's [ground] says a body can stand in, [EXPLORE_REACH_BLOCKS] off
 * along that bearing, and within a few blocks of the feet. They state only where a body can
 * stand, never what lies there, so an agent that sees nothing worth doing can look further
 * (r17: eta idled three reviews with a dead bush 5.4 blocks off, just past what its body saw).
 */
internal fun exploreOf(
    ground: BlockGround,
    position: List<Double>,
    remembered: List<List<Double>> = emptyList(),
): List<Pair<ExploreTarget, List<Int>>> {
    val feet = position.map { kotlin.math.floor(it).toInt() }
    val cells =
        COMPASS.map { (dx, dz) ->
            // A diagonal step is shortened so every cell lies about as far off.
            val along = if (dx != 0 && dz != 0) EXPLORE_DIAGONAL_BLOCKS else EXPLORE_REACH_BLOCKS
            standableNear(ground, feet[0] + dx * along, feet[1], feet[2] + dz * along)
        }.toMutableList()
    // A remembered source nearer than the edge of sight is walked to beside it, not past it: its
    // bearing's cell becomes the standable cell nearest the agent within reach of the source (r23:
    // twelve-block cells overshot logs remembered 4.7 to 8 blocks off, and agents walked back and forth).
    for (source in remembered) {
        val dx = source[0] - position[0]
        val dz = source[2] - position[2]
        if (kotlin.math.hypot(dx, dz) >= EXPLORE_REACH_BLOCKS) continue
        val bearing = COMPASS.indices.minBy { index -> angleBetween(COMPASS[index], dx, dz) }
        cells[bearing] = besideSource(ground, source, position) ?: cells[bearing]
    }
    return cells.filterNotNull().mapIndexed { index, cell ->
        val centre = listOf(cell[0] + CELL_CENTRE, cell[1].toDouble(), cell[2] + CELL_CENTRE)
        ExploreTarget(
            ref = "x${index + 1}",
            distance = blocksApart(position, centre),
            direction = horizontalBearing(centre[0] - position[0], centre[2] - position[2]),
            verticalOffset = centre[1] - position[1],
        ) to cell
    }
}

/** The highest cell a body stands in at column ([x], [z]), within a few blocks of [feetY]. */
private fun standableNear(
    ground: BlockGround,
    x: Int,
    feetY: Int,
    z: Int,
): List<Int>? =
    (feetY + EXPLORE_RISE_BLOCKS downTo feetY - EXPLORE_FALL_BLOCKS)
        .firstNotNullOfOrNull { y -> ground.standCell(listOf(x, y - 1, z))?.takeIf { it[1] == y } }

/** How far, in radians, compass [step] turns from the bearing ([dx], [dz]). */
private fun angleBetween(
    step: Pair<Int, Int>,
    dx: Double,
    dz: Double,
): Double {
    val turn =
        kotlin.math.abs(
            kotlin.math.atan2(dz, dx) - kotlin.math.atan2(step.second.toDouble(), step.first.toDouble()),
        )
    return minOf(turn, 2 * kotlin.math.PI - turn)
}

/** The standable cell beside [source], out of its own column, nearest the agent at [position]. */
private fun besideSource(
    ground: BlockGround,
    source: List<Double>,
    position: List<Double>,
): List<Int>? {
    val column = source.map { kotlin.math.floor(it).toInt() }
    return (-WAYPOINT_REACH_BLOCKS..WAYPOINT_REACH_BLOCKS)
        .flatMap { dx -> (-WAYPOINT_REACH_BLOCKS..WAYPOINT_REACH_BLOCKS).map { dz -> dx to dz } }
        .filter { (dx, dz) -> dx != 0 || dz != 0 }
        .mapNotNull { (dx, dz) -> standableNear(ground, column[0] + dx, column[1], column[2] + dz) }
        .minByOrNull {
                cell ->
            blocksApart(
                position,
                listOf(cell[0] + CELL_CENTRE, cell[1].toDouble(), cell[2] + CELL_CENTRE),
            )
        }
}

/** How far from a remembered source its waypoint may stand, along each axis. */
private const val WAYPOINT_REACH_BLOCKS = 2
