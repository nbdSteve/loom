/*
 * Which parts of the world Loom keeps loaded, and why it must.
 *
 * Loom has no human players. Nothing in a vanilla server keeps a region ticking
 * unless somebody is standing in it, so a Loom world left to itself freezes
 * wherever no agent is being watched - and, worse, a body in an unloaded region is
 * removed from the world without dying. Loom therefore loads the world around its
 * own agents: the loaded world is the union of an area around every living agent,
 * wherever each one happens to be.
 *
 * That union is what makes permadeath detectable. Because a living agent is always
 * inside a region Loom holds loaded, a body that has been removed can only have
 * died, so `Mortality` needs no unload-versus-death disambiguation. Anything that
 * weakens the footprint silently reintroduces false deaths.
 *
 * The arithmetic lives here, in the engine-free domain, for the same reason
 * `WalkGait`, `PerceptionBound`, and `Mortality` do: it is a Loom rule rather than
 * an engine detail, and it is the kind of set arithmetic that should be pinned by
 * unit tests rather than discovered on a running server.
 *
 * Two properties are deliberate and load-bearing:
 *
 * - The area is **coalesced**, not per-agent. Agents standing together contribute
 *   one shared set of chunks, each held exactly once, so a crowd costs what its
 *   spatial footprint costs rather than what its population costs. Stacked
 *   per-agent bubbles would undo the cheap-per-body property the whole server-side
 *   body architecture was chosen for.
 * - Every living agent is accounted for. Agents that split off keep their own
 *   surroundings loaded, so a party exploring north and a party staying home both
 *   sit in ticking regions. No living agent is ever left in an unticking void.
 */
package dev.loom.body

/**
 * A chunk of the world, identified the way engines index chunks.
 *
 * Owned data, so a footprint can be computed and tested without a world handle.
 */
data class ChunkKey(
    val world: String,
    val x: Int,
    val z: Int,
) {
    init {
        require(world.isNotBlank()) { "world must not be blank" }
    }
}

/**
 * What changed between two footprints.
 *
 * Expressed as a difference rather than as the whole set so a caller holds and
 * releases only what actually changed, which is what keeps the per-tick cost
 * proportional to agent movement rather than to the size of the loaded world.
 */
data class FootprintChange(
    /** Chunks now needed that were not held before. */
    val toHold: Set<ChunkKey>,
    /** Chunks that were held and are no longer needed by any living agent. */
    val toRelease: Set<ChunkKey>,
) {
    /** Whether anything needs doing. A still world produces no work. */
    val isEmpty: Boolean get() = toHold.isEmpty() && toRelease.isEmpty()
}

/**
 * Computes the set of chunks Loom keeps loaded around its living agents.
 *
 * @param radiusChunks how far beyond an agent's own chunk to keep loaded, in
 *   chunks. A radius of 1 keeps the agent's chunk and its eight neighbours, which
 *   is the smallest area that leaves an agent room to walk without stepping into an
 *   unloaded chunk before the footprint is recomputed.
 */
class WorldFootprint(
    val radiusChunks: Int,
) {
    init {
        require(radiusChunks >= 1) { "radiusChunks must be at least 1, was $radiusChunks" }
    }

    /**
     * The coalesced union of the areas around [locations].
     *
     * Overlapping areas collapse into one set: a chunk needed by several agents
     * appears once, so the result is the shape the agents occupy rather than a tally
     * of the agents themselves. Pass the locations of living agents only - a dead
     * agent has no body to keep loaded.
     */
    fun cover(locations: Collection<BodyLocation>): Set<ChunkKey> {
        val covered = mutableSetOf<ChunkKey>()
        for (location in locations) {
            val center = chunkAt(location)
            val centerX = center.x
            val centerZ = center.z
            for (x in centerX - radiusChunks..centerX + radiusChunks) {
                for (z in centerZ - radiusChunks..centerZ + radiusChunks) {
                    covered.add(ChunkKey(location.world, x, z))
                }
            }
        }
        return covered
    }

    /**
     * The one chunk [location] itself is in - the middle of its area.
     *
     * Called out from [cover] because the middle is not just one chunk among the
     * nine: a body standing in an unheld margin chunk has lost the room it needs to
     * walk, but a body standing in an unheld *middle* chunk is already in the state
     * the footprint exists to prevent, and can be unloaded out of the world.
     */
    fun chunkAt(location: BodyLocation): ChunkKey = ChunkKey(location.world, chunkOf(location.x), chunkOf(location.z))

    /**
     * The work needed to move from [held] to the cover of [locations].
     *
     * A chunk stays held when any living agent still needs it, so one agent walking
     * out of a shared area does not unload the ground the others are standing on.
     */
    fun change(
        held: Set<ChunkKey>,
        locations: Collection<BodyLocation>,
    ): FootprintChange {
        val needed = cover(locations)
        return FootprintChange(
            toHold = needed - held,
            toRelease = held - needed,
        )
    }

    /**
     * The chunk coordinate containing a block coordinate.
     *
     * Chunks are 16 blocks wide, and the division must floor rather than truncate so
     * that negative coordinates map to the chunk they are actually in: block -1 is in
     * chunk -1, not chunk 0.
     */
    private fun chunkOf(blockCoordinate: Double): Int = Math.floorDiv(Math.floor(blockCoordinate).toInt(), CHUNK_BLOCKS)

    private companion object {
        /** Width of a chunk in blocks. */
        const val CHUNK_BLOCKS = 16
    }
}
