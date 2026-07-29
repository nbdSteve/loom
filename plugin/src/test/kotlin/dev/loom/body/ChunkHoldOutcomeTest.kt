package dev.loom.body

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A hold that did less than it was asked to has to be legible, because it is the one
 * condition that makes a run's permadeath metrics untrustworthy: a living body in an
 * unheld chunk can be removed by an unload, which the departure classification reads
 * as a death. So the outcome must say it is incomplete, and its rendering must keep
 * the two failure kinds apart and stay bounded.
 */
class ChunkHoldOutcomeTest {
    private fun chunk(
        x: Int,
        z: Int,
        world: String = "arena",
    ) = ChunkKey(world, x, z)

    @Test
    @DisplayName("a hold that got everything it asked for is complete")
    fun everythingHeldIsComplete() {
        val outcome = ChunkHoldOutcome(held = setOf(chunk(0, 0), chunk(0, 1)), failed = emptyMap())

        assertTrue(outcome.isComplete)
        assertEquals("", outcome.describeFailures())
    }

    @Test
    @DisplayName("a hold that missed even one chunk is not complete, however many it got")
    fun oneMissedChunkBreaksTheInvariant() {
        val outcome =
            ChunkHoldOutcome(
                held = (0..7).map { chunk(it, 0) }.toSet(),
                failed = mapOf(chunk(8, 0) to ChunkHoldFailure.TICKET_DECLINED),
            )

        assertFalse(outcome.isComplete, "eight of nine chunks held is still a body in an unheld chunk")
    }

    @Test
    @DisplayName("an unknown world and a declined ticket are reported as different failures")
    fun theTwoFailuresStayApart() {
        val outcome =
            ChunkHoldOutcome(
                held = emptySet(),
                failed =
                    mapOf(
                        chunk(0, 0) to ChunkHoldFailure.UNKNOWN_WORLD,
                        chunk(1, 0) to ChunkHoldFailure.TICKET_DECLINED,
                    ),
            )

        val described = outcome.describeFailures()

        // Collapsing these into one boolean is the bug this type exists to prevent:
        // a footprint naming a world the server lacks is a Loom mistake, while a
        // declined ticket is the engine refusing a correct request.
        assertTrue(described.contains("1 unknownWorld"), described)
        assertTrue(described.contains("1 ticketDeclined"), described)
    }

    @Test
    @DisplayName("a wholly unavailable world renders a bounded line, not one chunk per failure")
    fun theSummaryStaysBounded() {
        val failed = (0..199).associate { chunk(it, 0) to ChunkHoldFailure.UNKNOWN_WORLD }
        val described = ChunkHoldOutcome(held = emptySet(), failed = failed).describeFailures()

        assertTrue(described.contains("200 unknownWorld"), described)
        assertTrue(described.contains("+196 more"), described)
        assertTrue(described.length < 200, "the summary must not grow with the footprint: $described")
    }

    @Test
    @DisplayName("the same failure always renders the same text")
    fun theRenderingIsDeterministic() {
        val failed =
            mapOf(
                chunk(1, 1, "nether") to ChunkHoldFailure.TICKET_DECLINED,
                chunk(0, 0) to ChunkHoldFailure.TICKET_DECLINED,
                chunk(0, -1) to ChunkHoldFailure.TICKET_DECLINED,
            )
        val shuffled = mapOf(*failed.entries.reversed().map { it.key to it.value }.toTypedArray())

        assertEquals(
            ChunkHoldOutcome(emptySet(), failed).describeFailures(),
            ChunkHoldOutcome(emptySet(), shuffled).describeFailures(),
        )
        assertEquals(
            "3 ticketDeclined (arena 0,-1; arena 0,0; nether 1,1)",
            ChunkHoldOutcome(emptySet(), failed).describeFailures(),
        )
    }

    @Test
    @DisplayName("the chunk an agent is standing in is identifiable, so an unheld one can be named")
    fun theChunkUnderAnAgentIsIdentifiable() {
        val footprint = WorldFootprint(radiusChunks = 1)

        // Negative coordinates must floor: block -1 is in chunk -1, not chunk 0. An
        // off-by-one here would name the wrong agent as standing in an unheld chunk.
        assertEquals(chunk(-1, -1), footprint.chunkAt(BodyLocation("arena", -1.0, 64.0, -1.0)))
        val standingAt = BodyLocation("arena", 8.0, 64.0, 8.0)
        assertEquals(chunk(0, 0), footprint.chunkAt(standingAt))
        assertTrue(footprint.chunkAt(standingAt) in footprint.cover(listOf(standingAt)))
    }
}
