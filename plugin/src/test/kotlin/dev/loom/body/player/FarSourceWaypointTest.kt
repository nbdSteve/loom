package dev.loom.body.player

import dev.loom.body.blocksApart
import dev.loom.contract.BlockYield
import dev.loom.contract.ObservationKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * r34 zeta: the last oak log of a tree eta had cut stood four blocks up at (-13, 70, -6), and the
 * cell zeta was offered beside it left the log out of a dig's reach. A source sight shows beyond
 * dig reach is now walked to by a cell beside it whose eyes reach it.
 */
class FarSourceWaypointTest {
    private val log = listOf(BlockYield("item.minecraft.oak_log", 1, 1, 100))
    private val sapling = listOf(BlockYield("item.minecraft.oak_sapling", 0, 1, 5))

    private fun block(
        index: Int,
        cell: List<Int>,
        yields: List<BlockYield>,
    ) = SceneOffer(
        kind = ObservationKind.BLOCK,
        sourceIndex = index,
        what = "block.minecraft.oak_log",
        position = cell.map { it + 0.5 },
        visible = true,
        yields = yields,
        digSafe = true,
    )

    /** Grass at y 65 everywhere, leaves at y 68 and 69 round the cut trunk, the last log at y 70. */
    private val ground =
        BlockGround { cell ->
            val canopy = cell[1] in 68..69 && kotlin.math.abs(cell[0] + 13) <= 2 && kotlin.math.abs(cell[2] + 6) <= 2
            cell[1] >= 66 && !canopy && cell != listOf(-13, 70, -6)
        }

    @Test
    fun `a log in sight beyond dig reach is a far source, and none is while one in reach gives a log`() {
        val feet = listOf(-19.5, 66.0, -3.5)
        val far = block(1, listOf(-13, 70, -6), log)
        val leaves = block(2, listOf(-18, 66, -4), sapling)
        assertEquals(listOf(far.position), farSourcesOf(feet, listOf(far, leaves)))
        val near = block(3, listOf(-18, 66, -3), log)
        assertEquals(emptyList<List<Double>>(), farSourcesOf(feet, listOf(far, near)))
    }

    @Test
    fun `the cell beside a canopy log stands where the eyes reach it`() {
        val feet = listOf(-19.5, 66.0, -3.5)
        val source = listOf(-12.5, 70.5, -5.5)
        val east = exploreOf(ground, feet, listOf(source)).map { it.second }[2]
        val eyes = listOf(east[0] + 0.5, east[1] + 1.62, east[2] + 0.5)
        assertTrue(blocksApart(eyes, source) <= 4.5, "the eyes at $east reach the log")
        assertEquals(66, east[1])
    }
}
