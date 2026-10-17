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

    private companion object {
        const val LOG = "item.minecraft.oak_log"
    }
}
