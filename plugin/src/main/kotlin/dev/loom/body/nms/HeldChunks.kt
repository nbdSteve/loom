/*
 * Keeps the world under Loom's living agents loaded and ticking.
 *
 * Loom has no human players, so nothing else holds a region open: without this the
 * world stops ticking wherever no agent is being watched, and a body in an unloaded
 * region is removed without dying. The policy - which chunks the agents' footprint
 * covers - is domain arithmetic in `WorldFootprint`; this class is only the engine
 * half that makes the holds real, which is why it lives in the adapter package.
 *
 * The mechanism is Bukkit's plugin chunk ticket. It adds a region ticket at chunk
 * level 31, which is `ENTITY_TICKING_LEVEL`, so a held chunk ticks its entities -
 * exactly the guarantee permadeath detection rests on. Tickets are held per chunk
 * regardless of how many agents want that chunk, which is what makes a crowd cost
 * its footprint rather than its population, and they outlive a single tick, so they
 * must be released when the plugin stops or the chunks stay pinned.
 */
package dev.loom.body.nms

import dev.loom.body.BodyEvent
import dev.loom.body.BodyLocation
import dev.loom.body.ChunkKey
import dev.loom.body.WorldFootprint
import org.bukkit.Server
import org.bukkit.plugin.Plugin

/**
 * The chunks Loom currently holds loaded, and the work to keep them current.
 *
 * @param server used to resolve a world by name when a ticket is added or removed.
 * @param owner the plugin the chunk tickets belong to; Bukkit tracks them per plugin
 *   so they can all be dropped when it disables.
 * @param footprint the domain rule for which chunks living agents need.
 * @param journal where a change to the held set is recorded, so the loaded world is
 *   replayable from the same log as every other body event.
 */
internal class HeldChunks(
    private val server: Server,
    private val owner: Plugin,
    private val footprint: WorldFootprint,
    private val journal: BodyEventJournal,
) {
    private val held = mutableSetOf<ChunkKey>()

    /**
     * Brings the held set in line with where the living agents now are, recording any
     * change it made.
     *
     * A still world does no work at all: the common case is that no agent has changed
     * chunk since the last call, the difference is empty, and nothing is logged.
     */
    fun follow(locations: Collection<BodyLocation>) {
        val change = footprint.change(held, locations)
        if (change.isEmpty) {
            return
        }
        val acquired = acquire(change.toHold)
        for (chunk in change.toRelease) {
            release(chunk)
            held.remove(chunk)
        }
        journal.recordForWorld(
            BodyEvent.WORLD_LOADED,
            "held $acquired, released ${change.toRelease.size}, " +
                "now ${held.size} chunk(s) for ${locations.size} agent(s)",
        )
    }

    /**
     * Holds the world around a body about to be spawned at [location], reporting
     * whether all of that body's chunks are now held.
     *
     * Called from the spawn path before a body is registered as live and therefore
     * reapable, so a living body never sits in a chunk Loom does not hold - the
     * invariant permadeath detection rests on. It only adds chunks; [follow]
     * reconciles the full set, including any release, once the body is live. A
     * caller that gets `false` should refuse the spawn rather than register a body
     * that is live but unheld.
     */
    fun holdAround(location: BodyLocation): Boolean {
        val needed = footprint.cover(listOf(location))
        val acquired = acquire(needed - held)
        if (acquired > 0) {
            journal.recordForWorld(
                BodyEvent.WORLD_LOADED,
                "held $acquired around a spawn, now ${held.size} chunk(s)",
            )
        }
        return needed.all { it in held }
    }

    /**
     * Releases every held chunk and lets the world go.
     *
     * Called when the plugin disables: a plugin chunk ticket outlives the tick that
     * added it, so without this the world would stay pinned loaded after Loom stops.
     */
    fun releaseAll() {
        if (held.isEmpty()) {
            return
        }
        val released = held.size
        for (chunk in held.toList()) {
            release(chunk)
        }
        held.clear()
        journal.recordForWorld(BodyEvent.WORLD_LOADED, "released all $released chunk(s) on shutdown")
    }

    /** Holds each chunk in [chunks], adding the ones the world accepts to [held]. */
    private fun acquire(chunks: Set<ChunkKey>): Int {
        var added = 0
        for (chunk in chunks) {
            if (hold(chunk)) {
                held.add(chunk)
                added++
            }
        }
        return added
    }

    private fun hold(chunk: ChunkKey): Boolean {
        val world = server.getWorld(chunk.world) ?: return false
        return world.addPluginChunkTicket(chunk.x, chunk.z, owner)
    }

    private fun release(chunk: ChunkKey) {
        server.getWorld(chunk.world)?.removePluginChunkTicket(chunk.x, chunk.z, owner)
    }
}
