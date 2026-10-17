/*
 * The dropped items one agent's body has already found no route to.
 */
package dev.loom.body.player

import java.util.UUID

/** How long a dropped item the body found no route to stays refused: vanilla's own item lifetime. */
internal const val ROUTE_REFUSAL_TICKS: Long = 6000

/** Most refused items one agent remembers; the oldest is forgotten past it. */
internal const val MAX_ROUTE_REFUSALS: Int = 16

/**
 * Server item entities a collect by this agent's body was refused as `noRoute` for, by the
 * server's uuid, so a later perception can say so and neither the offer nor a goal's capacity
 * counts on them. In r15 one stick hanging over a trench drew five refused collects from two
 * agents and stood in two agents' goal capacity for its whole life. Bounded in count and server
 * ticks; main thread only.
 */
internal class RouteRefusals(
    private val currentTick: () -> Long,
) {
    private val refused = LinkedHashMap<UUID, Long>()

    /** Remembers that the body found no route to [item]. */
    fun refused(item: UUID) {
        refused.remove(item)
        refused[item] = currentTick()
        while (refused.size > MAX_ROUTE_REFUSALS) refused.remove(refused.keys.first())
    }

    /** Whether the body found no route to [item] within [ROUTE_REFUSAL_TICKS]. */
    operator fun contains(item: UUID): Boolean {
        val now = currentTick()
        refused.entries.removeIf { now - it.value > ROUTE_REFUSAL_TICKS }
        return item in refused
    }
}
