package dev.loom.body.player

import dev.loom.contract.CoverFace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * r39 iota's closing (`sense-u5`, tick 8735): it stood at (3.4995, 68, -29.927), 0.227 of a block
 * past its feet cell's north edge, and was offered feetNorth against the grass under that cell. Its
 * own box reached into the cell, so vanilla refused the placement (real Paper: the destination
 * resent as air, acknowledged 54 ms after the packet), and the body held its one exclusive owner
 * for 330 ticks under skeleton fire until it died.
 */
class R39PlacementReplayTest {
    /** Open grass at y 67: everything at y 68 and above is air, everything below solid. */
    private val ground =
        object : BlockGround {
            override fun standsIn(position: List<Int>): Boolean = position[1] >= 68

            override fun holdsNothing(position: List<Int>): Boolean = standsIn(position)
        }

    private val supports: Map<List<Int>, CoverSupport> =
        (1..5).flatMap { x -> (-33..-28).map { z -> listOf(x, 67, z) } }
            .withIndex()
            .associate { (index, cell) -> cell to CoverSupport("b$index", cell, visible = true) }

    private fun closable(standing: List<Double>): Set<CoverFace> =
        requireNotNull(coverOf(ground, standing, supports, listOf("item.minecraft.oak_log")))
            .closings
            .map { it.face }
            .toSet()

    @Test
    fun `a face the body's own box reaches into is never offered as a closing`() {
        val iota = listOf(3.4995153354283053, 68.0, -29.926890663618433)
        assertTrue(bodyReaches(iota, listOf(3, 68, -31)), "its box reached the cell north of its feet")
        assertFalse(CoverFace.FEET_NORTH in closable(iota))
        assertFalse(CoverFace.HEAD_NORTH in closable(iota), "the head cell above it is reached as well")
        assertTrue(CoverFace.FEET_SOUTH in closable(iota), "the far side stays closable")
    }

    @Test
    fun `from the centre of its cell the same face closes, and touching a cell's face is not reaching it`() {
        val centred = listOf(3.5, 68.0, -29.5)
        assertFalse(bodyReaches(centred, listOf(3, 68, -31)))
        assertTrue(CoverFace.FEET_NORTH in closable(centred))
        // A box flush with the cell's face, 0.3 from the edge, only touches it.
        assertFalse(bodyReaches(listOf(3.5, 68.0, -29.7), listOf(3, 68, -31)))
        assertEquals(true, bodyReaches(listOf(3.5, 68.0, -29.701), listOf(3, 68, -31)))
    }
}
