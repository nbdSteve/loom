/*
 * The cells at the edge of sight a perception offers to walk to, and the waypoint beside a
 * remembered source, or a source in sight beyond dig reach, nearer than that edge.
 */
package dev.loom.body.player

import dev.loom.body.blocksApart
import dev.loom.body.horizontalBearing
import dev.loom.contract.ExploreTarget
import dev.loom.contract.ObservationKind

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

/** `INTERACTION_REACH_BLOCKS` in `body/src/sensing.js`: eyes to a block's centre. */
private const val SOURCE_DIG_REACH_BLOCKS = 5.1

/** Mineflayer's player eye height, from which dig reach is measured. */
private const val SOURCE_EYE_HEIGHT_BLOCKS = 1.62

/** How far inside dig reach a waypoint keeps its source, so a body arriving off-centre still reaches it. */
private const val WAYPOINT_REACH_MARGIN_BLOCKS = 0.6

/**
 * The cells to look further from, a remembered source's, or one sight shows beyond dig reach,
 * beside it rather than past it.
 */
internal fun PlayerBodyContext.exploreFor(
    raw: RawSense,
    selected: List<SceneOffer>,
): List<Pair<ExploreTarget, List<Int>>> {
    val remembered = recall.recalled(raw.report.position, selected).mapNotNull(recall::positionOf)
    val sighted = farSourcesOf(raw.report.position, selected)
    val cells = exploreOf(authority.ground, raw.report.position, remembered, sighted)
    // Ground already stood on is offered again only when every other bearing has been (r24: alpha
    // and iota walked back to cells they had already looked from).
    val fresh = cells.filterNot { (_, cell) -> visited.near(cell) }.ifEmpty { cells }
    visited.stoodAt(raw.report.position)
    return fresh
}

/**
 * Where the sources [offers] show in sight beyond dig reach of a body at [position] stand: for
 * each item no dig-safe block in reach gives, its nearest such source. The body looks that far
 * only for the kinds a goal review seeks (`SOUGHT_SOURCE_KINDS`), so a walk to the cell beside
 * one reaches a source sight already grounds (r34: zeta's logs were walked past, never to).
 */
internal fun farSourcesOf(
    position: List<Double>,
    offers: List<SceneOffer>,
): List<List<Double>> {
    val eyes = listOf(position[0], position[1] + SOURCE_EYE_HEIGHT_BLOCKS, position[2])
    val sources = offers.filter { it.kind == ObservationKind.BLOCK && it.visible && it.digSafe == true }
    val (near, far) = sources.partition { blocksApart(eyes, it.position) <= SOURCE_DIG_REACH_BLOCKS }
    val inReach = near.flatMap { offer -> offer.yields.orEmpty().filter { it.perHundred >= 1 }.map { it.what } }.toSet()
    return far
        .flatMap { offer ->
            offer.yields.orEmpty().filter { it.perHundred >= 1 && it.what !in inReach }.map { it.what to offer }
        }.groupBy({ it.first }, { it.second })
        .toSortedMap()
        .values
        .map { found -> found.minBy { blocksApart(eyes, it.position) }.position }
        .distinct()
}

/** Where one agent's body has recently stood, so a walk to look further goes somewhere new. */
internal class VisitedGround(
    private val currentTick: () -> Long,
) {
    private val stood = ArrayDeque<Pair<List<Double>, Long>>()

    @Synchronized
    fun stoodAt(position: List<Double>) {
        stood.addLast(position to currentTick())
        while (stood.size > MAX_VISITED_PLACES) stood.removeFirst()
    }

    /** Whether [cell] lies within [VISITED_REACH_BLOCKS] of a place stood at in the last [VISITED_TICKS]. */
    @Synchronized
    fun near(cell: List<Int>): Boolean {
        val now = currentTick()
        return stood.any { (at, tick) ->
            now - tick <= VISITED_TICKS &&
                kotlin.math.hypot(cell[0] + CELL_CENTRE - at[0], cell[2] + CELL_CENTRE - at[2]) <= VISITED_REACH_BLOCKS
        }
    }
}

/** Places stood at that one agent remembers. */
private const val MAX_VISITED_PLACES = 64

/** How near a place stood at an exploration cell counts as already seen: within sensing reach. */
private const val VISITED_REACH_BLOCKS = 5.0

/** How long a place stood at is remembered: a review cadence. */
private const val VISITED_TICKS = 2400L

/**
 * The cells at the edge of sight an agent at [position] can walk to, one per compass bearing:
 * the highest cell the server's [ground] says a body can stand in, [EXPLORE_REACH_BLOCKS] off
 * along that bearing, and within a few blocks of the feet; a bearing toward a [remembered] source
 * nearer than that, or a [sighted] one within [SIGHTED_WAYPOINT_BLOCKS], gets the cell beside it.
 * They state only where a body can stand, never what lies there, so an agent that sees nothing
 * worth doing can look further (r17: eta idled three reviews with a dead bush 5.4 blocks off, just
 * past what its body saw).
 */
internal fun exploreOf(
    ground: BlockGround,
    position: List<Double>,
    remembered: List<List<Double>> = emptyList(),
    sighted: List<List<Double>> = emptyList(),
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
    // A source in sight is walked to beside it as far as a goal review sees, so one walk reaches
    // it (the path rehearsal: epsilon's log 13 blocks off took a walk past it and one back).
    val sources =
        remembered.map { it to EXPLORE_REACH_BLOCKS.toDouble() } + sighted.map { it to SIGHTED_WAYPOINT_BLOCKS }
    for ((source, reach) in sources) {
        val dx = source[0] - position[0]
        val dz = source[2] - position[2]
        if (kotlin.math.hypot(dx, dz) >= reach) continue
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

/**
 * The standable cell beside [source], out of its own column, nearest the agent at [position]:
 * one whose eyes reach the source if any does, as a log high in a tree needs (r34: zeta's
 * waypoint stood under a canopy log four blocks up, out of a dig's reach).
 */
private fun besideSource(
    ground: BlockGround,
    source: List<Double>,
    position: List<Double>,
): List<Int>? {
    val column = source.map { kotlin.math.floor(it).toInt() }
    val centre = { cell: List<Int> -> listOf(cell[0] + CELL_CENTRE, cell[1].toDouble(), cell[2] + CELL_CENTRE) }
    val reaches = { cell: List<Int> ->
        val eyes = centre(cell).let { listOf(it[0], it[1] + SOURCE_EYE_HEIGHT_BLOCKS, it[2]) }
        blocksApart(eyes, source) <= SOURCE_DIG_REACH_BLOCKS - WAYPOINT_REACH_MARGIN_BLOCKS
    }
    val columns =
        (-WAYPOINT_REACH_BLOCKS..WAYPOINT_REACH_BLOCKS)
            .flatMap { dx -> (-WAYPOINT_REACH_BLOCKS..WAYPOINT_REACH_BLOCKS).map { dz -> dx to dz } }
            .filter { (dx, dz) -> dx != 0 || dz != 0 }
    // Every cell of the band a body stands in, not only each column's highest: the highest beside
    // a canopy log is the leaves' top, where no walk from the ground arrives.
    val reaching =
        columns
            .flatMap { (dx, dz) -> standableAll(ground, column[0] + dx, column[1], column[2] + dz) }
            .filter(reaches)
    val beside = columns.mapNotNull { (dx, dz) -> standableNear(ground, column[0] + dx, column[1], column[2] + dz) }
    return reaching.ifEmpty { beside }.minByOrNull { blocksApart(position, centre(it)) }
}

/** Every cell a body stands in at column ([x], [z]), within a few blocks of [feetY], highest first. */
private fun standableAll(
    ground: BlockGround,
    x: Int,
    feetY: Int,
    z: Int,
): List<List<Int>> =
    (feetY + EXPLORE_RISE_BLOCKS downTo feetY - EXPLORE_FALL_BLOCKS)
        .mapNotNull { y -> ground.standCell(listOf(x, y - 1, z))?.takeIf { it[1] == y } }

/** How far off a source in sight is walked to beside it: as far as a goal review's sense looks. */
private const val SIGHTED_WAYPOINT_BLOCKS = 16.0

/** How far from a remembered source its waypoint may stand, along each axis. */
private const val WAYPOINT_REACH_BLOCKS = 2
