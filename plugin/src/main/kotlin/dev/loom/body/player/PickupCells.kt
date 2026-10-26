/*
 * The cells a body could stand in and pick a dropped item up from, by the server's own ground:
 * the rule `pickupCells` in `body/src/collecting.js` states, read where the client cannot be the
 * judge of it.
 */
package dev.loom.body.player

import dev.loom.body.TerrainEvidence
import dev.loom.bodycontrol.CollectOutcome
import kotlin.math.abs
import kotlin.math.floor

/** Vanilla's player box, 0.6 wide and 1.8 tall, grown by 1 and 0.5 to pick an item up. */
private const val PICKUP_HALF_WIDTH_BLOCKS = 0.3
private const val PICKUP_PLAYER_HEIGHT_BLOCKS = 1.8
private const val PICKUP_GROWTH_HORIZONTAL_BLOCKS = 1.0
private const val PICKUP_GROWTH_VERTICAL_BLOCKS = 0.5

/** A dropped item's box: 0.25 wide and 0.25 tall, centred on its position horizontally. */
private const val PICKUP_ITEM_HALF_WIDTH_BLOCKS = 0.125
private const val PICKUP_ITEM_HEIGHT_BLOCKS = 0.25

/** How far inside vanilla's reach the body's final approach aims (`PICKUP_MARGIN`). */
private const val PICKUP_MARGIN_BLOCKS = 0.1

/** The cells below an item's own a body's grown box can still reach it from, and above. */
private const val PICKUP_CELLS_BELOW = 2
private const val PICKUP_CELLS_ABOVE = 1

/**
 * Every cell a body stands in - solid ground under its feet, its feet and head cells open, by
 * [ground] - from a collision-safe point of which vanilla's grown pickup box overlaps the box of
 * an item lying at [item]. None means no body can collect the item at all, which is the ground's
 * answer verified rather than a body's claim of it (r34: delta's, kappa's and theta's collects of
 * one log lodged in a one-high gap under a canopy were refused `noRoute`, and nothing on the
 * server said whether any route could exist).
 */
internal fun pickupCellsOf(
    ground: BlockGround,
    item: List<Double>,
): List<List<Int>> {
    val (x, y, z) = item.map { floor(it).toInt() }
    return (x - 1..x + 1).flatMap { cx ->
        (y - PICKUP_CELLS_BELOW..y + PICKUP_CELLS_ABOVE).flatMap { cy ->
            (z - 1..z + 1).mapNotNull { cz ->
                listOf(cx, cy, cz).takeIf { cell ->
                    reachesItem(cell, item) && ground.standCell(listOf(cx, cy - 1, cz)) == cell
                }
            }
        }
    }
}

/** Whether a body standing in [cell] at its collision-safe point nearest [item] would pick it up. */
private fun reachesItem(
    cell: List<Int>,
    item: List<Double>,
): Boolean {
    val (cx, cy, cz) = cell
    val (ix, iy, iz) = item
    val inY =
        cy - PICKUP_GROWTH_VERTICAL_BLOCKS < iy + PICKUP_ITEM_HEIGHT_BLOCKS &&
            iy < cy + PICKUP_PLAYER_HEIGHT_BLOCKS + PICKUP_GROWTH_VERTICAL_BLOCKS
    val reach =
        PICKUP_HALF_WIDTH_BLOCKS + PICKUP_GROWTH_HORIZONTAL_BLOCKS + PICKUP_ITEM_HALF_WIDTH_BLOCKS -
            PICKUP_MARGIN_BLOCKS
    val safe = { value: Double, low: Int ->
        value.coerceIn(low + PICKUP_HALF_WIDTH_BLOCKS, low + 1 - PICKUP_HALF_WIDTH_BLOCKS)
    }
    return inY && abs(safe(ix, cx) - ix) < reach && abs(safe(iz, cz) - iz) < reach
}

/** How far from its start, along each horizontal axis, the server's walk search reads: a route's reach and a step. */
private const val WALK_SEARCH_RADIUS_BLOCKS = 34

/** How far above and below its start the walk search reads. */
private const val WALK_SEARCH_RISE_BLOCKS = 8

/** The deepest drop a walk takes, as the body's own movement policy allows (`maxDropDown`). */
private const val WALK_MAX_DROP_BLOCKS = 3

/** The most cells the walk search visits before it gives no answer at all. */
private const val WALK_MAX_CELLS = 40_000

/** The four level steps a walk can take from a cell. */
private val WALK_STEPS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

/**
 * The server's reading of the ground under [claim] when the body found it no route, `null`
 * otherwise: the cells that would pick its item up and, when there are any, whether a walk from
 * where the body stood reaches one of them over the server's ground.
 */
internal fun terrainOf(
    ground: BlockGround,
    claim: ServerInteractionClaim.Collect?,
): TerrainEvidence? =
    claim
        ?.takeIf { it.report.outcome == CollectOutcome.NO_ROUTE }
        ?.let { refused ->
            val cells = pickupCellsOf(ground, refused.offered.position)
            val start = refused.report.from.map { floor(it).toInt() }
            val walkable = cells.takeIf { it.isNotEmpty() }?.let { walkReaches(ground, start, it.toSet()) }
            TerrainEvidence(cells.size, walkable)
        }

/**
 * Whether a body standing in [start] reaches one of [targets] by level steps, one-block step-ups
 * with head room and drops of at most [WALK_MAX_DROP_BLOCKS], over cells it stands in by [ground]
 * - the moves the body's own movement policy makes, with no dig, place or parkour - or `null` when
 * the search outgrew [WALK_MAX_CELLS]. A route the server finds where the body found none makes
 * its `noRoute` the body's claim; none found makes it the ground's answer, verified.
 */
internal fun walkReaches(
    ground: BlockGround,
    start: List<Int>,
    targets: Set<List<Int>>,
): Boolean? {
    val open = { cell: List<Int> -> ground.standsIn(cell) }
    val inside = { cell: List<Int> ->
        abs(cell[0] - start[0]) <= WALK_SEARCH_RADIUS_BLOCKS &&
            abs(cell[2] - start[2]) <= WALK_SEARCH_RADIUS_BLOCKS &&
            abs(cell[1] - start[1]) <= WALK_SEARCH_RISE_BLOCKS
    }
    val seen = hashSetOf(start)
    val queue = ArrayDeque(listOf(start))
    var answer: Boolean? = false
    while (queue.isNotEmpty() && answer == false) {
        val at = queue.removeFirst()
        when {
            at in targets -> answer = true
            seen.size > WALK_MAX_CELLS -> answer = null
            else ->
                stepsFrom(at, open)
                    .filter { inside(it) && ground.standCell(listOf(it[0], it[1] - 1, it[2])) == it && seen.add(it) }
                    .forEach(queue::addLast)
        }
    }
    return answer
}

/**
 * Every cell a body standing in [start] can walk to over [ground] within [radius] blocks along each
 * horizontal axis, by the moves [walkReaches] makes, at most [WALK_MAX_CELLS] of them, [start] first.
 */
internal fun walkableFrom(
    ground: BlockGround,
    start: List<Int>,
    radius: Int,
): Set<List<Int>> {
    val open = { cell: List<Int> -> ground.standsIn(cell) }
    val seen = linkedSetOf(start)
    val queue = ArrayDeque(listOf(start))
    while (queue.isNotEmpty() && seen.size <= WALK_MAX_CELLS) {
        stepsFrom(queue.removeFirst(), open)
            .filter { abs(it[0] - start[0]) <= radius && abs(it[2] - start[2]) <= radius }
            .filter { abs(it[1] - start[1]) <= WALK_SEARCH_RISE_BLOCKS }
            .filter { ground.standCell(listOf(it[0], it[1] - 1, it[2])) == it && seen.add(it) }
            .forEach(queue::addLast)
    }
    return seen
}

/** The cells one step from [at]: level or dropping into the column beside it, or one block up it. */
private fun stepsFrom(
    at: List<Int>,
    open: (List<Int>) -> Boolean,
): List<List<Int>> =
    WALK_STEPS.mapNotNull { (dx, dz) ->
        val (x, y, z) = listOf(at[0] + dx, at[1], at[2] + dz)
        when {
            open(listOf(x, y, z)) && open(listOf(x, y + 1, z)) ->
                (y downTo y - WALK_MAX_DROP_BLOCKS)
                    .firstOrNull { !open(listOf(x, it - 1, z)) }
                    ?.let { listOf(x, it, z) }
            open(listOf(x, y + 1, z)) && open(listOf(x, y + 2, z)) && open(listOf(at[0], at[1] + 2, at[2])) ->
                listOf(x, y + 1, z)
            else -> null
        }
    }
