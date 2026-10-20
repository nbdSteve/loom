package dev.loom.witness

import java.util.UUID

/**
 * Each audited player's server inventory at its recent ticks, newest last: the [WITNESS_LAG_TICKS]
 * a client may still describe before a sense request, and a whole [SENSE_ROUND_TRIP_TICKS] round
 * trip after it, which a sense read at a craft's settled boundary describes (r29 beta).
 *
 * Engine-free: the adapter samples it once a tick with its own reading. Main thread only.
 */
class InventoryHistory {
    private val byPlayer = mutableMapOf<UUID, ArrayDeque<InventoryAt>>()

    /** Starts keeping [player]'s readings, if they are not kept already. */
    fun watch(player: UUID) {
        byPlayer.getOrPut(player) { ArrayDeque() }
    }

    /** Records every watched player's inventory at [tick]; a player [read] cannot find is forgotten. */
    fun sample(
        tick: Long,
        read: (UUID) -> List<ObservedStack>?,
    ) {
        byPlayer.entries.removeIf { (player, readings) ->
            val inventory = read(player) ?: return@removeIf true
            readings.addLast(InventoryAt(tick, inventory))
            while (readings.size > WITNESS_LAG_TICKS + SENSE_ROUND_TRIP_TICKS) readings.removeFirst()
            false
        }
    }

    /** [player]'s inventory at each of the last [WITNESS_LAG_TICKS] ticks sampled, oldest first. */
    fun lagWindow(player: UUID): List<InventoryAt> = byPlayer[player].orEmpty().takeLast(WITNESS_LAG_TICKS)

    /** [player]'s inventory at each sampled tick after [requestTick], oldest first. */
    fun duringRoundTrip(
        player: UUID,
        requestTick: Long,
    ): List<InventoryAt> =
        byPlayer[player]
            .orEmpty()
            .filter { it.tick > requestTick }
            .takeLast(SENSE_ROUND_TRIP_TICKS)
}
