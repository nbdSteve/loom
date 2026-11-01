package dev.loom.body.player

import dev.loom.contract.BlockFace
import dev.loom.contract.Closing
import dev.loom.contract.Cover
import dev.loom.contract.CoverFace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * R45 zeta at tick 9129 (body:00002178's perception): it stood at (-42.5, 61, -14.5) with its head
 * west and its roof open, each with a closing offered, and placed the sand it held in the roof at
 * 9162, over its own head; the sand fell into its head's cell and it suffocated at 9270.
 */
class R45FallingClosingTest {
    private val position = listOf(-42.5, 61.0, -14.5)

    /** The ground there: solid but for zeta's own two cells, the roof cell and the cell west of its head. */
    private val ground =
        object : BlockGround {
            private val open =
                setOf(listOf(-43, 61, -15), listOf(-43, 62, -15), listOf(-43, 63, -15), listOf(-44, 62, -15))

            override fun standsIn(position: List<Int>): Boolean = position in open

            override fun takesPlacement(position: List<Int>): Boolean = standsIn(position)
        }

    private val cover =
        Cover(
            sealed = false,
            walkOut = false,
            open = listOf(CoverFace.HEAD_WEST, CoverFace.ROOF),
            closings =
                listOf(
                    Closing(CoverFace.HEAD_WEST, "bdav", BlockFace.UP),
                    Closing(CoverFace.ROOF, "bdb3", BlockFace.SOUTH),
                ),
            blockItems = emptyList(),
        )

    @Test
    fun `a falling block in hand closes only a face with something under it`() {
        val sand = withoutFallingClosings(cover, ground, position, heldFalls = true)
        assertEquals(listOf(CoverFace.HEAD_WEST), sand.closings.map { it.face }, "the roof is over zeta's own head")
        assertEquals(cover.open, sand.open, "the face is still open, only not closed with sand")
        assertEquals(cover, withoutFallingClosings(cover, ground, position, heldFalls = false), "sandstone closes both")
    }
}
