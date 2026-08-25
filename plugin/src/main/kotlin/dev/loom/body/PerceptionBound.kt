/*
 * The bound on what a body may perceive.
 *
 * This lives in the domain rather than in the adapter because it is a rule about
 * the perception boundary, not about any engine: whatever flavor gives an agent a
 * body, the mind sees a snapshot bounded the same way. Keeping it here also makes
 * it testable without a server.
 *
 * It exists as its own type because the bound has been wrong before: an engine
 * bounding-box helper takes full extents rather than radii, so a box built from a
 * radius silently covered half the intended area. A named, tested bound makes that
 * class of mistake visible.
 */
package dev.loom.body

/**
 * How far from a body an observation may be, in blocks.
 *
 * Stated here rather than in the adapter that captures a perception, because it is not
 * only that adapter's business: a perception reads the world around the body, so the
 * area Loom holds loaded has to cover it, and [holdRadiusChunksFor] derives the hold
 * from this number for exactly that reason. Written down twice, the two would drift and
 * the drift would be silent - an unloaded chunk yields no entities rather than an error,
 * so a body would simply stop perceiving whatever stood in it.
 *
 * Deliberately wider than the distance a body can be *sent* in one request
 * ([PATH_REQUEST_REACH_BLOCKS], 8): seeing further than it can walk in one go is what
 * lets an agent choose a direction and then travel it in stages, and a mind that could
 * only see as far as it could walk would have nothing to aim at.
 */
const val PERCEPTION_RADIUS_BLOCKS: Double = 24.0

/**
 * How many observations one perception may carry.
 *
 * What keeps a prompt bounded whatever the radius sees. The mind's own cap is looser
 * (`MAX_OBSERVATIONS` in `prompt.rs`), on purpose: each side bounds what it pays for.
 */
const val PERCEPTION_MAX_OBSERVATIONS: Int = 16

/**
 * The bound every Loom body perceives through.
 *
 * The one way to build the body's [PerceptionBound], for the same reason
 * [navigableFootprint] is the one way to build its [WorldFootprint]: a second literal
 * copy of either number is a copy that can fall behind.
 */
fun bodyPerceptionBound(): PerceptionBound =
    PerceptionBound(
        radiusBlocks = PERCEPTION_RADIUS_BLOCKS,
        maxObservations = PERCEPTION_MAX_OBSERVATIONS,
    )

/**
 * Limits a perception snapshot to what is near and to how much of it is reported.
 *
 * @param radiusBlocks how far from the body an observation may be, in blocks.
 * @param maxObservations cap on reported observations, so a crowded scene cannot
 *   produce an unbounded snapshot (and, downstream, an unbounded prompt).
 */
data class PerceptionBound(
    val radiusBlocks: Double,
    val maxObservations: Int,
) {
    init {
        require(radiusBlocks > 0) { "radiusBlocks must be positive, was $radiusBlocks" }
        require(maxObservations > 0) { "maxObservations must be positive, was $maxObservations" }
    }

    /**
     * The full width of a bounding box that reaches [radiusBlocks] in every
     * direction.
     *
     * Engine bounding-box helpers are specified in total extents, so a radius must
     * be doubled before it becomes a box. The box is a coarse pre-filter only: its
     * corners lie further away than [radiusBlocks], which is why [nearest] still
     * applies the distance test.
     */
    val boxExtent: Double get() = radiusBlocks * 2

    /**
     * Reduces [candidates] to the bounded set the mind may see: those within
     * [radiusBlocks], nearest first, at most [maxObservations] of them - and says how
     * many the count bound left out.
     *
     * The omission is returned rather than merely implied because the bound is the only
     * place that can still count it: once the list is cut, nothing downstream can tell a
     * quiet scene from a crowded one that was trimmed, and a survival run reading the
     * record afterwards would take the trimmed scene for the quiet one.
     *
     * [distance] is asked exactly once per candidate and the reading is carried out in
     * [Measured], so eligibility, order and whatever is described from the survivors are
     * all the same number. Measuring is real work - the engine's own reading is a
     * three-dimensional square root - and it was being taken three times per candidate:
     * once to filter, once to sort, once to describe. Carrying it also means the three
     * cannot disagree, which a moving world makes possible and not merely wasteful.
     */
    fun <T> nearest(
        candidates: Sequence<T>,
        distance: (T) -> Double,
    ): Bounded<Measured<T>> {
        val within =
            candidates
                .map { Measured(it, distance(it)) }
                .filter { it.blocksAway <= radiusBlocks }
                // Stable, so equally distant things keep the order the candidates arrived
                // in: the same scene has to render the same prompt.
                .sortedBy { it.blocksAway }
                .toList()
        return Bounded(
            shown = within.take(maxObservations),
            omitted = (within.size - maxObservations).coerceAtLeast(0),
        )
    }

    /**
     * One candidate, and how far away it was measured to be.
     *
     * @param thing the candidate itself.
     * @param blocksAway what [nearest]'s own distance function answered for it, in blocks.
     */
    data class Measured<T>(
        val thing: T,
        val blocksAway: Double,
    )

    /**
     * The bounded set, and what the count bound left out of it.
     *
     * @param shown the candidates within [radiusBlocks], nearest first, at most
     *   [maxObservations] of them.
     * @param omitted how many further candidates were within [radiusBlocks] and were not
     *   reported, always zero or more.
     */
    data class Bounded<T>(
        val shown: List<T>,
        val omitted: Int,
    )
}
