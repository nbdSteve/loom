/*
 * Whether a body that digs out its own support can still walk out of where it lands.
 */
package dev.loom.body.player

import kotlin.math.abs

/** How far sideways a walk out of a dug hole must reach to count as out of it. */
internal const val ESCAPE_RADIUS_BLOCKS = 3

/** How far above its landing a walk out of a dug hole must climb to count as out of it. */
internal const val ESCAPE_RISE_BLOCKS = 2

/**
 * Whether [block] lies where the walk out of [feet] can pass: within [ESCAPE_RADIUS_BLOCKS]
 * sideways, and no higher than the cell above the feet. A block further off is no step of it.
 */
internal fun nearWalkOut(
    feet: List<Int>,
    block: List<Int>,
): Boolean =
    maxOf(abs(block[0] - feet[0]), abs(block[2] - feet[2])) <= ESCAPE_RADIUS_BLOCKS &&
        block[1] <= feet[1] + 1 && block[1] >= feet[1] - ESCAPE_DROP_BLOCKS - 1

/** The most a body drops in one step of the walk out, as the movement policy's own drop bound. */
private const val ESCAPE_DROP_BLOCKS = 3

/** The most cells the walk out is searched over: the box the radius and rise bound. */
private const val ESCAPE_MAX_CELLS = 512

/**
 * Whether a body in [landing], with the block at [opened] dug open if one is named, can
 * walk to a cell [ESCAPE_RADIUS_BLOCKS] sideways or [ESCAPE_RISE_BLOCKS] up by steps a body
 * makes: level, up one with head room to jump, or down at most [ESCAPE_DROP_BLOCKS].
 *
 * A one-level check cannot see a deeper trap: in r20 four agents dug shafts two wide and up to
 * four deep, each dig passing because the next column, or that column's floor, offered a step,
 * and every one of their 13 flights was `noRoute`. Bounded to [ESCAPE_MAX_CELLS] cells, and
 * asked only of the block underfoot, once a perception.
 */
internal fun escapesFrom(
    ground: BlockGround,
    landing: List<Int>,
    opened: List<Int>? = null,
): Boolean {
    val walk = EscapeWalk(ground, landing, opened)
    if (!walk.standable(landing)) return false
    val seen = mutableSetOf(landing)
    val frontier = ArrayDeque(listOf(landing))
    var out = false
    while (!out && frontier.isNotEmpty() && seen.size < ESCAPE_MAX_CELLS) {
        val cell = frontier.removeFirst()
        out = walk.isOut(cell)
        walk.stepsFrom(cell).filter(seen::add).forEach(frontier::addLast)
    }
    return out
}

/** The ground as a body walking out of [landing] meets it, with [landing] itself dug open. */
private class EscapeWalk(
    private val ground: BlockGround,
    private val landing: List<Int>,
    private val opened: List<Int>?,
) {
    fun open(cell: List<Int>): Boolean = cell == opened || ground.standsIn(cell)

    fun standable(cell: List<Int>): Boolean = open(cell) && open(cell.up(1)) && !open(cell.up(-1))

    fun isOut(cell: List<Int>): Boolean {
        val away = maxOf(abs(cell[0] - landing[0]), abs(cell[2] - landing[2]))
        return away >= ESCAPE_RADIUS_BLOCKS || cell[1] - landing[1] >= ESCAPE_RISE_BLOCKS
    }

    /** The cells one step from [cell]: level, up one with head room to jump, or a drop. */
    fun stepsFrom(cell: List<Int>): List<List<Int>> =
        listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).flatMap { (dx, dz) ->
            val beside = listOf(cell[0] + dx, cell[1], cell[2] + dz)
            listOfNotNull(
                beside.takeIf(::standable),
                beside.up(1).takeIf { open(cell.up(2)) && standable(it) },
                dropFrom(beside),
            )
        }

    private fun dropFrom(beside: List<Int>): List<Int>? =
        if (open(beside) && open(beside.up(1))) {
            (1..ESCAPE_DROP_BLOCKS).map { beside.up(-it) }.takeWhile(::open).firstOrNull(::standable)
        } else {
            null
        }
}

private fun List<Int>.up(blocks: Int): List<Int> = listOf(this[0], this[1] + blocks, this[2])
