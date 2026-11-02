package dev.loom.body.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** r24: alpha and iota walked back to exploration cells they had already looked from. */
class VisitedGroundTest {
    @Test
    fun `ground stood on is remembered for a cadence, then offered again`() {
        var now = 0L
        val visited = VisitedGround { now }
        visited.stoodAt(listOf(4.5, 68.0, -3.5))
        assertTrue(visited.near(listOf(6, 68, -2)))
        assertFalse(visited.near(listOf(16, 68, -3)))
        now += 2401
        assertFalse(visited.near(listOf(6, 68, -2)))
    }

    @Test
    fun `the cell back where an agent stood is withheld while another bearing is new`() {
        var now = 0L
        val visited = VisitedGround { now }
        val level = BlockGround { cell -> cell[1] >= 64 }
        val start = listOf(0.5, 64.0, 0.5)
        val east = listOf(12.5, 64.0, 0.5)
        visited.stoodAt(start)
        val cells = exploreOf(level, east)
        val fresh = cells.filterNot { (_, cell) -> visited.near(cell) }
        assertEquals(cells.size - 1, fresh.size, "only the cell back at the start is withheld")
        now += 1
        assertTrue(cells.any { (_, cell) -> visited.near(cell) })
    }
}
