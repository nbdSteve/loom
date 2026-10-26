/*
 * The dropped items one agent's body has already found no way to: no route, or a route its walk
 * stalled on from where it stood.
 */
package dev.loom.body.player

import java.util.UUID
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** How long a dropped item the body found no route to stays refused: vanilla's own item lifetime. */
internal const val ROUTE_REFUSAL_TICKS: Long = 6000

/** Most refused items one agent remembers; the oldest is forgotten past it. */
internal const val MAX_ROUTE_REFUSALS: Int = 16

/** How far around a stalled approach's two cells the ground it was walked over is read, in blocks. */
private const val STALL_MARGIN_BLOCKS = 1

/** How far below and above the lower and higher of those cells it is read: a step down, a head over a jump. */
private const val STALL_BELOW_BLOCKS = 1
private const val STALL_ABOVE_BLOCKS = 2

/** The widest ground a stall's fingerprint reads along either horizontal axis; a wider one is no stall memory. */
private const val MAX_STALL_SPAN_BLOCKS = 40

/**
 * Server item entities a collect by this agent's body found no way to, by the server's uuid, so a
 * later perception can say so and neither the offer nor a goal's capacity counts on them.
 *
 * Two kinds, kept apart because they are opposite facts about how long they hold. A `noRoute` is
 * the ground's answer about the item: in r15 one stick hanging over a trench drew five refused
 * collects from two agents and stood in two agents' goal capacity for its whole life, so it holds
 * for [ROUTE_REFUSAL_TICKS]. A stall is the body's walk failing from one place over one piece of
 * ground, so it holds only while the body stands in the same cell, the item lies in the same cell,
 * and the ground between them is the same: r34 delta was offered the same log three times from the
 * same stuck cell and stalled each time, while a step to another cell, or a block dug between them,
 * is a different approach. Bounded in count and server ticks; main thread only.
 */
internal class RouteRefusals(
    private val currentTick: () -> Long,
    private val ground: BlockGround = BlockGround.UNKNOWN,
) {
    /** Where a stall was walked from and to, and the ground between them when it stalled. */
    private data class Stall(
        val from: List<Int>,
        val target: List<Int>,
        val terrain: Int,
    )

    private data class Refusal(
        val tick: Long,
        val stall: Stall?,
    )

    private val refused = LinkedHashMap<UUID, Refusal>()

    /** Remembers that the body found no route to [item]. */
    fun refused(item: UUID) = remember(item, Refusal(currentTick(), stall = null))

    /**
     * Remembers that the body's approach to [item], lying at [target], stalled from [from]: it is
     * not offered again while the body stands in that cell, the item lies in its cell, and the
     * ground between them reads the same.
     */
    fun stalled(
        item: UUID,
        from: List<Double>,
        target: List<Double>,
    ) {
        val start = cellOf(from)
        val end = cellOf(target)
        val terrain = terrainBetween(start, end) ?: return
        remember(item, Refusal(currentTick(), Stall(start, end, terrain)))
    }

    /** Whether the body found no route to [item] within [ROUTE_REFUSAL_TICKS], whatever the place. */
    operator fun contains(item: UUID): Boolean {
        expire()
        return refused[item]?.stall == null && item in refused
    }

    /**
     * Whether [item], lying at [target], is no item to offer a body standing at [from]: no cell the
     * server's ground has a body stand in would pick it up ([pickupCellsOf]), refused as no route,
     * or stalled on from this same cell, to this same cell, over this same ground.
     */
    fun blocks(
        item: UUID,
        from: List<Double>,
        target: List<Double>,
    ): Boolean {
        expire()
        val refusal = refused[item]
        val stall = refusal?.stall
        val stalledHere =
            stall != null && stall.from == cellOf(from) && stall.target == cellOf(target) &&
                terrainBetween(stall.from, stall.target) == stall.terrain
        return pickupCellsOf(ground, target).isEmpty() || (refusal != null && (stall == null || stalledHere))
    }

    private fun remember(
        item: UUID,
        refusal: Refusal,
    ) {
        refused.remove(item)
        refused[item] = refusal
        while (refused.size > MAX_ROUTE_REFUSALS) refused.remove(refused.keys.first())
    }

    private fun expire() {
        val now = currentTick()
        refused.entries.removeIf { now - it.value.tick > ROUTE_REFUSAL_TICKS }
    }

    /**
     * Which cells of the ground around and between [start] and [end] a body passes, as one number:
     * a dug block, a placed one, or a door opened anywhere a route between them could use changes
     * it. `null` for ground too wide to read, which is then no stall memory at all.
     */
    private fun terrainBetween(
        start: List<Int>,
        end: List<Int>,
    ): Int? {
        val low = (0..2).map { min(start[it], end[it]) }
        val high = (0..2).map { max(start[it], end[it]) }
        if (high[0] - low[0] > MAX_STALL_SPAN_BLOCKS || high[2] - low[2] > MAX_STALL_SPAN_BLOCKS) return null
        val cells =
            (low[0] - STALL_MARGIN_BLOCKS..high[0] + STALL_MARGIN_BLOCKS).flatMap { x ->
                (low[1] - STALL_BELOW_BLOCKS..high[1] + STALL_ABOVE_BLOCKS).flatMap { y ->
                    (low[2] - STALL_MARGIN_BLOCKS..high[2] + STALL_MARGIN_BLOCKS).map { z -> listOf(x, y, z) }
                }
            }
        return cells.fold(1) { hash, cell -> HASH_PRIME * hash + passageOf(cell) }
    }

    /** One cell as the stall's fingerprint reads it: whether a body passes it, and whether it holds water. */
    private fun passageOf(cell: List<Int>): Int =
        (if (ground.standsIn(cell)) PASSABLE else 0) + if (ground.holdsWater(cell)) WATER else 0
}

private const val HASH_PRIME = 31
private const val PASSABLE = 1
private const val WATER = 2

/** The block cell [position] lies in. */
private fun cellOf(position: List<Double>): List<Int> = position.map { floor(it).toInt() }

/**
 * Remembers what one collect's claim says about reaching its item: a `noRoute` for as long as the
 * item lives, and a stall for as long as the body, the item and the ground between them stay put.
 */
internal fun RouteRefusals.remember(claim: ServerInteractionClaim.Collect) {
    val item = claim.itemSubject ?: return
    when (claim.report.outcome) {
        dev.loom.bodycontrol.CollectOutcome.NO_ROUTE -> refused(item)
        dev.loom.bodycontrol.CollectOutcome.STALLED -> stalled(item, claim.report.from, claim.offered.position)
        else -> Unit
    }
}
