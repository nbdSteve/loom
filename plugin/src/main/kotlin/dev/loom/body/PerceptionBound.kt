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
     * [radiusBlocks], nearest first, at most [maxObservations] of them.
     */
    fun <T> nearest(
        candidates: Sequence<T>,
        distance: (T) -> Double,
    ): List<T> =
        candidates
            .filter { distance(it) <= radiusBlocks }
            .sortedBy(distance)
            .take(maxObservations)
            .toList()
}
