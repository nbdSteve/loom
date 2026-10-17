/*
 * The sources of items a body saw recently and does not see now.
 */
package dev.loom.body.player

import dev.loom.body.blocksApart
import dev.loom.body.horizontalBearing
import dev.loom.contract.MAX_PER_HUNDRED
import dev.loom.contract.MAX_RECALLED_SOURCES
import dev.loom.contract.RecalledSource

/** How long a source the body saw stays recalled, in server ticks: one review cadence. */
internal const val RECALL_TICKS: Long = 2400

/** How far from the body a recalled source may lie and still be told, in blocks. */
internal const val RECALL_RADIUS_BLOCKS: Double = 48.0

/** The most items one body remembers sources of. */
private const val MAX_REMEMBERED_ITEMS = 32

/**
 * One body's memory of where it last saw a source of each item, so a goal for an item out of
 * sight can be set and walked toward (r20: no agent without a log in sight could set a log goal;
 * kappa and theta saw logs only after flights, and forgot them at the next perception).
 *
 * Fed by every scene's dig-safe blocks in sight, keyed by item, keeping for each the latest
 * sighting: where the nearest such block stood and the summed expected yield of all of them, as
 * a tree is several logs; told back only for items no block in the current sight gives, within
 * [RECALL_RADIUS_BLOCKS] and [RECALL_TICKS]. Main thread only.
 */
internal class SourceRecall(
    private val currentTick: () -> Long,
) {
    private data class Seen(
        val position: List<Double>,
        val perHundred: Long,
        val tick: Long,
    )

    private val seen = LinkedHashMap<String, Seen>()

    /** Remembers the sources [offers] show, and answers what to tell beyond them from [position]. */
    fun recalled(
        position: List<Double>,
        offers: List<SceneOffer>,
    ): List<RecalledSource> {
        val now = currentTick()
        val sighted =
            offers
                .filter { it.visible && it.digSafe == true }
                .flatMap { offer -> offer.yields.orEmpty().filter { it.perHundred >= 1 }.map { offer to it } }
                .groupBy({ (_, given) -> given.what }, { (offer, given) -> offer to given.perHundred })
        for ((item, sources) in sighted) {
            val nearest = sources.minBy { (offer, _) -> blocksApart(position, offer.position) }.first
            seen.remove(item)
            seen[item] = Seen(nearest.position, sources.sumOf { it.second }.coerceAtMost(MAX_PER_HUNDRED), now)
        }
        val inSight = sighted.keys
        seen.entries.removeIf { (_, source) -> now - source.tick > RECALL_TICKS }
        while (seen.size > MAX_REMEMBERED_ITEMS) seen.remove(seen.keys.first())
        return seen
            .filterKeys { it !in inSight }
            .map { (item, source) -> item to source }
            .filter { (_, source) -> blocksApart(position, source.position) <= RECALL_RADIUS_BLOCKS }
            .sortedBy { (_, source) -> blocksApart(position, source.position) }
            .take(MAX_RECALLED_SOURCES)
            .map { (item, source) ->
                RecalledSource(
                    what = item,
                    distance = blocksApart(position, source.position),
                    direction = horizontalBearing(source.position[0] - position[0], source.position[2] - position[2]),
                    verticalOffset = source.position[1] - position[1],
                    perHundred = source.perHundred,
                )
            }
    }
}
