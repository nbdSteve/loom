package dev.loom.body.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Where a body can stand on a sensed block, under the server's collision verdicts. */
class BlockGroundTest {
    /** A ground whose only solid cells are [solid]; everything else, air included, is open. */
    private fun groundOf(vararg solid: List<Int>): BlockGround = BlockGround { position -> position !in solid }

    @Test
    fun `a solid block with two open cells over it is stood on top of`() {
        assertEquals(listOf(6, 107, -2), groundOf(listOf(6, 106, -2)).standCell(listOf(6, 106, -2)))
    }

    @Test
    fun `a covered block has nowhere to stand, as r10's buried terracotta did not`() {
        // The terracotta at (6, 106, -2) had terracotta on top of it at (6, 107, -2).
        val ground = groundOf(listOf(6, 106, -2), listOf(6, 107, -2))
        assertNull(ground.standCell(listOf(6, 106, -2)))
        // A cell open for the feet but closed over the head is no place to stand either.
        assertNull(groundOf(listOf(6, 106, -2), listOf(6, 108, -2)).standCell(listOf(6, 106, -2)))
    }

    @Test
    fun `a plant is stood in when it has ground under it and room over it`() {
        val bush = listOf(1, 64, 2)
        val ground = BlockGround { position -> position != listOf(1, 63, 2) }
        assertEquals(bush, ground.standCell(bush))
        // A plant over air, or under a ceiling, holds nobody.
        assertNull(BlockGround { true }.standCell(bush))
        assertNull(
            BlockGround {
                    position ->
                position != listOf(1, 63, 2) && position != listOf(1, 65, 2)
            }.standCell(bush),
        )
    }

    @Test
    fun `with no server to ask every block is stood on top of`() {
        assertEquals(listOf(4, 64, 2), BlockGround.UNKNOWN.standCell(listOf(4, 63, 2)))
    }
}
