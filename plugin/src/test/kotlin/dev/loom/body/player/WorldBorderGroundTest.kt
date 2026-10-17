package dev.loom.body.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The v29 plains rehearsal's world border: 128 blocks centred on the origin. theta stood at
 * (27.3, 68, -63.7) with its box touching the north face, and its perceptions offered an
 * edge-of-sight walk 11.8 blocks north, past the border, where no body can stand.
 */
class WorldBorderGroundTest {
    /** Open plains at ground level 67 out to the edge of the world, closed past the border. */
    private val plains =
        BlockGround { (x, y, z) -> y >= 68 && columnInsideBorder(0.0, 0.0, 128.0, x, z) }

    @Test
    fun aColumnIsInsideOnlyWhenABodyStandingInItIsWhollyInside() {
        assertTrue(columnInsideBorder(0.0, 0.0, 128.0, 27, -64))
        assertFalse(columnInsideBorder(0.0, 0.0, 128.0, 27, -65))
        assertTrue(columnInsideBorder(0.0, 0.0, 128.0, 63, 63))
        assertFalse(columnInsideBorder(0.0, 0.0, 128.0, 64, 0))
        // An off-grid centre closes a column a body's box would cross.
        assertFalse(columnInsideBorder(0.5, 0.0, 128.0, -64, 0))
        assertEquals(0.3, PLAYER_HALF_WIDTH)
    }

    @Test
    fun noWalkIsOfferedOntoGroundPastTheBorder() {
        assertEquals(listOf(27, 68, -64), plains.standCell(listOf(27, 67, -64)))
        assertNull(plains.standCell(listOf(27, 67, -65)), "the block past the border holds no body")
        val explored = exploreOf(plains, listOf(27.3, 68.0, -63.7))
        assertTrue(explored.isNotEmpty())
        assertTrue(explored.all { (_, cell) -> cell[2] >= -64 }, explored.toString())
        assertTrue(explored.none { (target, _) -> target.direction.name.startsWith("NORTH") }, explored.toString())
    }
}
