package dev.loom.body

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The perception boundary's arithmetic.
 *
 * The extent test is not a tautology: it pins the radius-to-extent conversion that
 * an engine bounding-box helper requires. Getting it wrong halves what a body can
 * perceive while every other test still passes, which is exactly what happened
 * before this type existed.
 */
class PerceptionBoundTest {
    private val bound = PerceptionBound(radiusBlocks = 12.0, maxObservations = 3)

    @Test
    fun aBoxBuiltFromTheBoundReachesTheFullRadiusInEveryDirection() {
        // A box of this total width, centered on the body, spans -12..+12.
        assertEquals(24.0, bound.boxExtent)
        assertEquals(bound.radiusBlocks, bound.boxExtent / 2)
    }

    @Test
    fun observationsBeyondTheRadiusAreNotReported() {
        val kept = bound.nearest(sequenceOf(1.0, 11.99, 12.0, 12.01, 40.0)) { it }
        assertEquals(listOf(1.0, 11.99, 12.0), kept)
    }

    @Test
    fun theNearestObservationsComeFirst() {
        val kept = bound.nearest(sequenceOf(9.0, 2.0, 5.0)) { it }
        assertEquals(listOf(2.0, 5.0, 9.0), kept)
    }

    @Test
    fun aCrowdedSceneStaysBounded() {
        // Every candidate is in range, so only the count limit can hold the
        // snapshot down - and it must, or the mind's prompt grows without limit.
        val crowd = (1..500).asSequence().map { it % 12 + 0.5 }
        val kept = bound.nearest(crowd) { it }
        assertEquals(bound.maxObservations, kept.size)
        assertTrue(kept.all { it <= bound.radiusBlocks })
    }

    @Test
    fun anUnboundedSnapshotCannotBeConfigured() {
        assertFailsWith<IllegalArgumentException> { PerceptionBound(0.0, 16) }
        assertFailsWith<IllegalArgumentException> { PerceptionBound(-1.0, 16) }
        assertFailsWith<IllegalArgumentException> { PerceptionBound(12.0, 0) }
        assertFailsWith<IllegalArgumentException> { PerceptionBound(12.0, -4) }
    }
}
