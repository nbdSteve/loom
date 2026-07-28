package dev.loom.body

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WorldFootprintTest {
    private val footprint = WorldFootprint(radiusChunks = 1)

    private fun at(
        x: Double,
        z: Double,
        world: String = "arena",
    ) = BodyLocation(world, x, 64.0, z)

    @Test
    @DisplayName("a radius below one chunk is refused")
    fun radiusMustLeaveRoomToWalk() {
        assertThrows<IllegalArgumentException> { WorldFootprint(radiusChunks = 0) }
    }

    @Test
    @DisplayName("one agent covers its own chunk and the ring around it")
    fun oneAgentCoversItsSurroundings() {
        val covered = footprint.cover(listOf(at(8.0, 8.0)))

        // Radius 1 is a 3x3 of chunks centred on the agent.
        assertEquals(9, covered.size)
        assertTrue(covered.contains(ChunkKey("arena", 0, 0)))
        assertTrue(covered.contains(ChunkKey("arena", -1, -1)))
        assertTrue(covered.contains(ChunkKey("arena", 1, 1)))
    }

    @Test
    @DisplayName("clustered agents share one coalesced area rather than stacking bubbles")
    fun clusteredAgentsShareOneArea() {
        val alone = footprint.cover(listOf(at(8.0, 8.0)))
        val crowd =
            footprint.cover(
                listOf(at(1.0, 1.0), at(8.0, 8.0), at(15.0, 15.0), at(4.0, 12.0), at(12.0, 4.0)),
            )

        // Five agents in one chunk must cost what one agent costs: the whole point of
        // coalescing is that cost follows the footprint, not the population.
        assertEquals(alone, crowd)
    }

    @Test
    @DisplayName("a chunk needed by several agents is held exactly once")
    fun overlapIsHeldOnce() {
        // Neighbouring agents whose rings overlap: the union must not double-count.
        val covered = footprint.cover(listOf(at(8.0, 8.0), at(24.0, 8.0)))

        // Two 3x3 areas sharing a 3x2 overlap: 9 + 9 - 6 distinct chunks.
        assertEquals(12, covered.size)
        assertEquals(covered.size, covered.toSet().size)
    }

    @Test
    @DisplayName("agents that split off each keep their own area loaded")
    fun everySplitGroupStaysLoaded() {
        val home = at(8.0, 8.0)
        val north = at(8.0, 1000.0)

        val covered = footprint.cover(listOf(home, north))

        // Both parties must sit in a held region - no living agent in an unticking void.
        assertTrue(covered.containsAll(footprint.cover(listOf(home))))
        assertTrue(covered.containsAll(footprint.cover(listOf(north))))
        assertEquals(18, covered.size)
    }

    @Test
    @DisplayName("agents in different worlds do not share chunks")
    fun worldsAreDistinct() {
        val covered = footprint.cover(listOf(at(8.0, 8.0, "arena"), at(8.0, 8.0, "other")))

        assertEquals(18, covered.size)
    }

    @Test
    @DisplayName("no living agents means nothing is kept loaded")
    fun noAgentsNoFootprint() {
        assertTrue(footprint.cover(emptyList()).isEmpty())
    }

    @Test
    @DisplayName("negative coordinates map to the chunk they are actually in")
    fun negativeCoordinatesFloor() {
        // Truncating instead of flooring would put block -1 in chunk 0 and quietly
        // leave the agent's real chunk unloaded.
        val covered = footprint.cover(listOf(at(-1.0, -1.0)))

        assertTrue(covered.contains(ChunkKey("arena", -1, -1)))
        assertTrue(covered.contains(ChunkKey("arena", -2, -2)))
        assertFalse(covered.contains(ChunkKey("arena", 1, 1)))
    }

    @Test
    @DisplayName("a still world needs no work")
    fun stillWorldIsNoWork() {
        val locations = listOf(at(8.0, 8.0))
        val held = footprint.cover(locations)

        val change = footprint.change(held, locations)

        assertTrue(change.isEmpty)
    }

    @Test
    @DisplayName("a moving agent holds what it walks into and releases what it leaves")
    fun movementHoldsAndReleases() {
        val held = footprint.cover(listOf(at(8.0, 8.0)))

        val change = footprint.change(held, listOf(at(8.0, 1000.0)))

        assertEquals(9, change.toHold.size)
        assertEquals(9, change.toRelease.size)
        assertTrue(change.toRelease.contains(ChunkKey("arena", 0, 0)))
        assertFalse(change.toHold.contains(ChunkKey("arena", 0, 0)))
    }

    @Test
    @DisplayName("one agent leaving a shared area does not unload the ground the others stand on")
    fun sharedGroundSurvivesOneAgentLeaving() {
        val stay = at(8.0, 8.0)
        val held = footprint.cover(listOf(stay, at(8.0, 24.0)))

        val change = footprint.change(held, listOf(stay, at(8.0, 1000.0)))

        // The agent that stayed keeps its whole area: nothing it needs is released.
        assertTrue(change.toRelease.intersect(footprint.cover(listOf(stay))).isEmpty())
    }

    @Test
    @DisplayName("when the last agent is gone every chunk is released")
    fun lastAgentReleasesEverything() {
        val held = footprint.cover(listOf(at(8.0, 8.0)))

        val change = footprint.change(held, emptyList())

        assertEquals(held, change.toRelease)
        assertTrue(change.toHold.isEmpty())
    }
}
