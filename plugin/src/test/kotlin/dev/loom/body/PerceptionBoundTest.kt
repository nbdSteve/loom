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
        assertEquals(listOf(1.0, 11.99, 12.0), kept.shown.map { it.thing })
        // Out of range is not an omission: the count bound is what a reader is warned
        // about, and counting the far-away things would make every open scene look cut.
        assertEquals(0, kept.omitted)
    }

    @Test
    fun theNearestObservationsComeFirst() {
        val kept = bound.nearest(sequenceOf(9.0, 2.0, 5.0)) { it }
        assertEquals(listOf(2.0, 5.0, 9.0), kept.shown.map { it.thing })
    }

    @Test
    fun theDistanceIsMeasuredOncePerCandidateAndCarried() {
        // Measuring is real work - the engine's own reading is a three-dimensional square
        // root - and it was being taken three times per candidate: to filter, to sort, and
        // again to describe. Once is also the *correct* number rather than merely the cheap
        // one: everything in a live world is moving, so three readings of one entity can
        // disagree, and an observation could report a distance the bound did not admit it
        // for. Named things here so a repeated reading is visible as a repeated name.
        val asked = mutableListOf<String>()
        val kept =
            bound.nearest(sequenceOf("near" to 3.0, "far" to 40.0, "mid" to 7.0)) {
                asked += it.first
                it.second
            }

        assertEquals(listOf("near", "far", "mid"), asked)
        // And the reading it was kept for is the reading it carries out, so whatever
        // describes it has no reason to take another.
        assertEquals(listOf("near" to 3.0, "mid" to 7.0), kept.shown.map { it.thing })
        assertEquals(listOf(3.0, 7.0), kept.shown.map { it.blocksAway })
    }

    @Test
    fun equallyDistantThingsKeepTheOrderTheyArrivedIn() {
        // The same scene has to render the same prompt, so the sort may not shuffle a tie:
        // two entities exactly as far away must come out in candidate order, whichever of
        // them the count bound then drops.
        val tied = sequenceOf("first" to 5.0, "second" to 5.0, "third" to 5.0, "fourth" to 5.0)
        val kept = bound.nearest(tied) { it.second }

        assertEquals(listOf("first", "second", "third"), kept.shown.map { it.thing.first })
        assertEquals(1, kept.omitted)
    }

    @Test
    fun aCrowdedSceneStaysBounded() {
        // Every candidate is in range, so only the count limit can hold the
        // snapshot down - and it must, or the mind's prompt grows without limit.
        val crowd = (1..500).asSequence().map { it % 12 + 0.5 }
        val kept = bound.nearest(crowd) { it }
        assertEquals(bound.maxObservations, kept.shown.size)
        assertTrue(kept.shown.all { it.blocksAway <= bound.radiusBlocks })
        // And it says how much it held down. A trimmed scene recorded as a bare count
        // reads afterwards as a quiet one, which is the reading a survival run must not
        // be given.
        assertEquals(500 - bound.maxObservations, kept.omitted)
    }

    @Test
    fun theBodysOwnBoundIsTheOneNamedSource() {
        // The one way to build what a body perceives through, because the radius has a
        // second reader: `holdRadiusChunksFor` derives the loaded-world hold from the same
        // constant, and a literal copy in the adapter is a copy the hold can fall behind.
        assertEquals(PERCEPTION_RADIUS_BLOCKS, bodyPerceptionBound().radiusBlocks)
        assertEquals(PERCEPTION_MAX_OBSERVATIONS, bodyPerceptionBound().maxObservations)
    }

    @Test
    fun anUnboundedSnapshotCannotBeConfigured() {
        assertFailsWith<IllegalArgumentException> { PerceptionBound(0.0, 16) }
        assertFailsWith<IllegalArgumentException> { PerceptionBound(-1.0, 16) }
        assertFailsWith<IllegalArgumentException> { PerceptionBound(12.0, 0) }
        assertFailsWith<IllegalArgumentException> { PerceptionBound(12.0, -4) }
    }
}
