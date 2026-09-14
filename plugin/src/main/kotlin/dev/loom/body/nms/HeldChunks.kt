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

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventJournal
import dev.loom.body.BodyLocation
import dev.loom.body.ChunkHoldFailure
import dev.loom.body.ChunkHoldOutcome
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
 * @param onHoldFailure called with a one-line summary whenever a chunk a living agent
 *   needs could not be held. The event log alone is not enough for this one: it is a
 *   broken invariant rather than an observation, and a run's operator should see it
 *   without going looking for it.
 */
internal class HeldChunks(
    private val server: Server,
    private val owner: Plugin,
    private val footprint: WorldFootprint,
    private val journal: BodyEventJournal,
    private val onHoldFailure: (String) -> Unit,
) {
    private val held = mutableSetOf<ChunkKey>()

    /**
     * Brings the held set in line with where the living agents at [locations] now are,
     * recording any change it made and any chunk it failed to hold.
     *
     * A still world does no work at all: the common case is that no agent has changed
     * chunk since the last call, the difference is empty, and nothing is logged.
     *
     * Unlike the spawn path, this cannot refuse: the bodies are already live and
     * already there, so a failure here *is* the invariant being broken rather than a
     * request to turn down. It is therefore reported both ways - a dedicated
     * [BodyEvent.WORLD_HOLD_FAILED] record so the run's own log says the footprint was
     * incomplete, and [onHoldFailure] so it surfaces while the run is going. Silently
     * dropping the failed chunks, as an earlier version did, left an agent standing in
     * an unheld chunk that an unload could remove - which the departure classification
     * would then read as a permanent death.
     */
    fun follow(locations: Map<AgentId, BodyLocation>) {
        val change = footprint.change(held, locations.values)
        if (change.isEmpty) {
            return
        }
        val outcome = acquire(change.toHold)
        for (chunk in change.toRelease) {
            release(chunk)
            held.remove(chunk)
        }
        journal.recordForWorld(
            BodyEvent.WORLD_LOADED,
            "held ${outcome.held.size}, released ${change.toRelease.size}, " +
                "now ${held.size} chunk(s) for ${locations.size} agent(s)",
        )
        if (!outcome.isComplete) {
            reportIncompleteFollow(outcome, locations)
        }
    }

    /**
     * Records and surfaces a follow that did not get every chunk it needed.
     *
     * The agents standing *in* a failed chunk are named first, because they are the
     * ones the invariant is actually broken for: a missing margin chunk costs an agent
     * its room to walk, while a missing chunk under an agent's feet is the case that
     * can end with a false permadeath.
     */
    private fun reportIncompleteFollow(
        outcome: ChunkHoldOutcome,
        locations: Map<AgentId, BodyLocation>,
    ) {
        val unheldAgents =
            locations
                .filterValues { footprint.chunkAt(it) in outcome.failed }
                .keys
                .map { it.value }
                .sorted()
        val standing =
            if (unheldAgents.isEmpty()) {
                "no agent is standing in one"
            } else {
                "${unheldAgents.size} agent(s) standing in one: ${unheldAgents.joinToString(",")}"
            }
        val detail = "could not hold ${outcome.failed.size} chunk(s): ${outcome.describeFailures()}; $standing"
        journal.recordForWorld(BodyEvent.WORLD_HOLD_FAILED, detail)
        onHoldFailure(detail)
    }

    /**
     * Holds the world around a body about to be spawned at [location], reporting what
     * it managed to hold and why anything failed.
     *
     * Called from the spawn path before a body is registered as live and therefore
     * reapable, so a living body never sits in a chunk Loom does not hold - the
     * invariant permadeath detection rests on. It only adds chunks; [follow]
     * reconciles the full set, including any release, once the body is live. A caller
     * that gets an outcome that is not [ChunkHoldOutcome.isComplete] should refuse the
     * spawn rather than register a body that is live but unheld, and the outcome says
     * which failure to blame.
     */
    fun holdAround(location: BodyLocation): ChunkHoldOutcome {
        val needed = footprint.cover(listOf(location))
        val outcome = acquire(needed - held)
        if (outcome.held.isNotEmpty()) {
            journal.recordForWorld(
                BodyEvent.WORLD_LOADED,
                "held ${outcome.held.size} around a spawn, now ${held.size} chunk(s)",
            )
        }
        return outcome
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

    /**
     * Holds each chunk in [chunks], adding the ones the world accepts to [held].
     *
     * Reports both halves of the attempt, rather than a count of successes, because
     * the caller cannot tell an incomplete hold from a complete one without knowing
     * what failed - and the two ways it can fail need telling apart.
     */
    private fun acquire(chunks: Set<ChunkKey>): ChunkHoldOutcome {
        val added = mutableSetOf<ChunkKey>()
        val failed = mutableMapOf<ChunkKey, ChunkHoldFailure>()
        for (chunk in chunks) {
            val failure = hold(chunk)
            if (failure == null) {
                held.add(chunk)
                added.add(chunk)
            } else {
                failed[chunk] = failure
            }
        }
        return ChunkHoldOutcome(held = added, failed = failed)
    }

    /**
     * Asks the world to hold [chunk], returning null on success or why it failed.
     *
     * The two failures are distinct facts about very different problems: a world Loom
     * named that the server does not have, versus a world that declined a ticket Loom
     * asked for correctly. They were once both a bare `false`, which made every hold
     * failure look the same and none of them look like anything.
     *
     * Bukkit also returns false for a ticket this plugin already holds, which is why
     * only chunks outside [held] are ever asked for: a duplicate request would
     * otherwise be reported as a decline for a chunk that is in fact held.
     */
    private fun hold(chunk: ChunkKey): ChunkHoldFailure? {
        val world = server.getWorld(chunk.world) ?: return ChunkHoldFailure.UNKNOWN_WORLD
        return if (world.addPluginChunkTicket(chunk.x, chunk.z, owner)) {
            null
        } else {
            ChunkHoldFailure.TICKET_DECLINED
        }
    }

    private fun release(chunk: ChunkKey) {
        server.getWorld(chunk.world)?.removePluginChunkTicket(chunk.x, chunk.z, owner)
    }
}
