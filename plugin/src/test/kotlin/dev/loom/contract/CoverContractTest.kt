package dev.loom.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** The cover's own rules the schema cannot state, held by the JVM binding as the Rust one holds them. */
class CoverContractTest {
    private val roof = Closing(CoverFace.ROOF, "b2", BlockFace.WEST)
    private val cover =
        Cover(
            sealed = false,
            walkOut = false,
            open = listOf(CoverFace.ROOF, CoverFace.FEET_NORTH),
            closings = listOf(roof),
            descend = "b1",
            blockItems = listOf("item.minecraft.dirt"),
        )

    @Test
    fun `a cover names each face once and closes only open ones`() {
        assertEquals(listOf(roof), cover.closings)
        assertThrows(IllegalArgumentException::class.java) { cover.copy(open = listOf(CoverFace.ROOF, CoverFace.ROOF)) }
        assertThrows(IllegalArgumentException::class.java) { cover.copy(open = listOf(CoverFace.FEET_NORTH)) }
        assertThrows(IllegalArgumentException::class.java) {
            cover.copy(closings = listOf(roof, Closing(CoverFace.ROOF, "b3", BlockFace.EAST)))
        }
        assertThrows(IllegalArgumentException::class.java) { cover.copy(blockItems = listOf("block.minecraft.dirt")) }
        assertThrows(IllegalArgumentException::class.java) { cover.copy(blockItems = listOf(AIR_ITEM)) }
        assertThrows(IllegalArgumentException::class.java) { cover.copy(descend = "b123456789") }
        assertThrows(IllegalArgumentException::class.java) { CoverProgress(closed = 10, closable = 0, sealed = false) }
    }

    @Test
    fun `natural cover is told only for a block a body can stand at`() {
        val block =
            Observation(
                kind = ObservationKind.BLOCK,
                what = "block.minecraft.stone",
                distance = 2.0,
                direction = Bearing.EAST,
                verticalOffset = 0.0,
                visible = true,
                ref = "b1",
                standable = true,
                yields = emptyList(),
                digSafe = true,
                cover = 9,
            )
        assertEquals(9L, block.cover)
        assertThrows(IllegalArgumentException::class.java) { block.copy(standable = false) }
        assertThrows(IllegalArgumentException::class.java) { block.copy(cover = 10) }
    }

    @Test
    fun `shelter and feeds are present only as true`() {
        assertThrows(IllegalArgumentException::class.java) { GoalChoice(GoalDisposition.ADOPT, shelter = false) }
        assertThrows(IllegalArgumentException::class.java) { GoalChoice(GoalDisposition.ADOPT, feeds = false) }
    }
}
