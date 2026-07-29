/*
 * What came of trying to hold chunks loaded, and why anything failed.
 *
 * `WorldFootprint` says which chunks a living agent needs held; this says what
 * actually happened when Loom asked for them. The distinction matters because the
 * footprint is the invariant permadeath detection rests on: a living body in a chunk
 * Loom does not hold can be removed with an unload reason, and `departureOf` reads
 * any unexplained removal as a death. A hold that quietly did less than it was asked
 * to therefore has to be visible in the record, not skipped past.
 *
 * The two failures are kept apart on purpose. An unknown world is a Loom-side
 * mistake - a footprint naming a world the server does not have - while a declined
 * ticket is the engine refusing a request Loom made correctly. Collapsing them into
 * one boolean, as an earlier version did, made both invisible and indistinguishable.
 *
 * Lives in the engine-free domain, like `WorldFootprint` and `Mortality`, so the
 * accounting and its rendering are pinned by unit tests rather than discovered on a
 * running server.
 */
package dev.loom.body

/**
 * Why one chunk could not be held loaded.
 *
 * @property wireName short stable token used in the event log's detail text, so a
 *   run can be searched for one kind of failure without matching the other.
 */
enum class ChunkHoldFailure(
    val wireName: String,
) {
    /** No world by that name exists on the server, so no ticket could be asked for. */
    UNKNOWN_WORLD("unknownWorld"),

    /** The world exists and refused the chunk ticket Loom asked it for. */
    TICKET_DECLINED("ticketDeclined"),
}

/** How many failed chunks a rendered summary names before it starts counting them. */
private const val CHUNKS_NAMED = 4

/**
 * The result of one attempt to hold a set of chunks.
 *
 * @param held the chunks that are now held loaded as a result of the attempt.
 * @param failed every chunk that was asked for and not held, with its reason.
 */
data class ChunkHoldOutcome(
    val held: Set<ChunkKey>,
    val failed: Map<ChunkKey, ChunkHoldFailure>,
) {
    /** Whether every chunk asked for is now held - the only outcome the invariant allows. */
    val isComplete: Boolean get() = failed.isEmpty()

    /**
     * The failures as one line, grouped by reason and naming the worst offenders.
     *
     * Rendered here rather than at the call site so the log text is testable, and
     * bounded rather than exhaustive so a wholly unavailable world cannot produce a
     * log line proportional to the footprint.
     */
    fun describeFailures(): String =
        ChunkHoldFailure.entries
            .mapNotNull { failure ->
                val chunks = failed.filterValues { it == failure }.keys.sortedWith(CHUNK_ORDER)
                if (chunks.isEmpty()) null else "${chunks.size} ${failure.wireName} (${name(chunks)})"
            }.joinToString(", ")

    private fun name(chunks: List<ChunkKey>): String {
        val named = chunks.take(CHUNKS_NAMED).joinToString("; ") { "${it.world} ${it.x},${it.z}" }
        val rest = chunks.size - CHUNKS_NAMED
        return if (rest > 0) "$named; +$rest more" else named
    }

    private companion object {
        /** Deterministic order, so the same failure always renders the same text. */
        val CHUNK_ORDER: Comparator<ChunkKey> =
            compareBy({ it.world }, { it.x }, { it.z })
    }
}
