package dev.loom.body.player

import dev.loom.contract.Bearing
import dev.loom.contract.BlockYield
import dev.loom.contract.ObservationKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** A body's memory of sources seen recently, replayed on r20's plains (kappa saw logs only after a flight). */
class SourceRecallTest {
    private var tick = 0L
    private val recall = SourceRecall { tick }

    private fun log(
        index: Int,
        position: List<Double>,
    ) = SceneOffer(
        kind = ObservationKind.BLOCK,
        sourceIndex = index,
        what = "block.minecraft.oak_log",
        position = position,
        visible = true,
        standable = false,
        yields = listOf(BlockYield(LOG, 1, 1, 100)),
        digSafe = true,
    )

    @Test
    fun `a tree seen once is recalled out of sight, with every log it showed`() {
        val tree =
            listOf(log(0, listOf(30.5, 67.5, 0.5)), log(1, listOf(30.5, 68.5, 0.5)), log(2, listOf(30.5, 69.5, 0.5)))
        // In sight: nothing to recall.
        assertEquals(emptyList(), recall.recalled(listOf(26.5, 67.0, 0.5), tree))
        tick = 600
        // Walked back west out of sight: the tree is recalled east, all three logs counted.
        val recalled = recall.recalled(listOf(12.5, 67.0, 0.5), emptyList()).single()
        assertEquals(LOG, recalled.what)
        assertEquals(Bearing.EAST, recalled.direction)
        assertEquals(300L, recalled.perHundred)
        assertEquals(18.0, recalled.distance, 0.01)
    }

    @Test
    fun `a recalled source is forgotten after a cadence or beyond its radius`() {
        recall.recalled(listOf(26.5, 67.0, 0.5), listOf(log(0, listOf(30.5, 67.5, 0.5))))
        tick = RECALL_TICKS
        assertEquals(1, recall.recalled(listOf(12.5, 67.0, 0.5), emptyList()).size)
        assertEquals(emptyList(), recall.recalled(listOf(-30.5, 67.0, 0.5), emptyList()), "beyond the radius")
        tick = RECALL_TICKS + 1
        assertEquals(emptyList(), recall.recalled(listOf(12.5, 67.0, 0.5), emptyList()), "after a cadence")
    }

    @Test
    fun `a source whose place is in reach and shows nothing is gone, and is not told`() {
        recall.recalled(listOf(26.5, 67.0, 0.5), listOf(log(0, listOf(28.5, 67.5, 0.5))))
        tick = 20
        // The log was dug: standing beside its place, the body sees no log there.
        assertEquals(emptyList(), recall.recalled(listOf(27.5, 67.0, 0.5), emptyList()))
        tick = 40
        assertEquals(emptyList(), recall.recalled(listOf(12.5, 67.0, 0.5), emptyList()), "and stays forgotten")
    }

    @Test
    fun `a remembered source nearer than the edge of sight is walked to beside it, not past it`() {
        // r23 delta body:00001508: a log remembered 4.7 blocks east was walked past twelve blocks
        // east, then twelve back west.
        val open = BlockGround { (_, y, _) -> y >= 64 }
        val plain = exploreOf(open, listOf(0.5, 64.0, 0.5)).associate { (target, cell) -> target.direction to cell }
        assertEquals(listOf(12, 64, 0), plain[Bearing.EAST])
        val toward = exploreOf(open, listOf(0.5, 64.0, 0.5), listOf(listOf(5.5, 64.5, 0.5)))
        val east = toward.map { it.second }.filter { it[0] > 0 && it[2] == 0 }
        assertEquals(listOf(listOf(3, 64, 0)), east, "beside the log, nearest the agent")
        assertEquals(8, toward.size)
    }

    private companion object {
        const val LOG = "item.minecraft.oak_log"
    }
}
