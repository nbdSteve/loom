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

    /**
     * Grass at y 65 everywhere, leaves at y 68 and 69 round the trunk eta had cut from 66 to 69 -
     * its own column open - and the last log at y 70.
     */
    private val ground =
        BlockGround { cell ->
            val trunk = cell[0] == -13 && cell[2] == -6
            val canopy = cell[1] in 68..69 && kotlin.math.abs(cell[0] + 13) <= 2 && kotlin.math.abs(cell[2] + 6) <= 2
            cell[1] >= 66 && !(canopy && !trunk) && cell != listOf(-13, 70, -6)
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
    fun `the cell beside a canopy log stands where the eyes reach it and see it`() {
        // Every stand round the trunk is under the leaves; only the cut trunk's own column sees up
        // to the log, so that is where the walk ends, not on the leaves' top nor under them.
        val feet = listOf(-19.5, 66.0, -3.5)
        val source = listOf(-12.5, 70.5, -5.5)
        val walkable = walkableFrom(ground, listOf(-20, 66, -4), 20)
        val east = exploreOf(ground, feet, listOf(source), walkable = walkable).map { it.second }[2]
        assertEquals(listOf(-13, 66, -6), east)
        val anywhere = exploreOf(ground, feet, listOf(source)).map { it.second }[2]
        assertEquals(70, anywhere[1], "without the walk the nearest stand that sees it is on the leaves")
        assertTrue(digsFrom(ground, east, listOf(-13, 70, -6)))
    }

    @Test
    fun `a log in sight thirteen blocks off is walked to beside it in one walk, where a remembered one is not`() {
        // The path rehearsal: epsilon's log 13 blocks east took a walk past it and one back.
        val level = BlockGround { cell -> cell[1] >= 64 }
        val feet = listOf(13.5, 64.0, -39.5)
        val log = listOf(26.5, 64.5, -39.5)
        val remembered = exploreOf(level, feet, listOf(log)).map { it.second }[2]
        assertEquals(listOf(25, 64, -40), remembered, "a remembered source past the edge keeps the twelve-block cell")
        val sighted = exploreOf(level, feet, sighted = listOf(log)).map { it.second }[2]
        val eyes = listOf(sighted[0] + 0.5, sighted[1] + 1.62, sighted[2] + 0.5)
        assertTrue(blocksApart(eyes, log) <= 4.5, "the eyes at $sighted reach the log")
        assertTrue(sighted[0] < 26 && sighted != remembered, "beside it, not past it: $sighted")
    }
}
