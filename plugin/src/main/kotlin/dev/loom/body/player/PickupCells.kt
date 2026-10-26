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

/** The server's reading of the ground under [claim] when the body found it no route; `null` otherwise. */
internal fun terrainOf(
    ground: BlockGround,
    claim: ServerInteractionClaim.Collect?,
): TerrainEvidence? =
    claim
        ?.takeIf { it.report.outcome == CollectOutcome.NO_ROUTE }
        ?.let { TerrainEvidence(pickupCellsOf(ground, it.offered.position).size) }
