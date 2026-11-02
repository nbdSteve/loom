package dev.loom.body.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Water in the server's ground, against r27 epsilon's drowning: it dug the sand under its feet with
 * water at its feet, the pit filled to above its head, and every walk out ended in water.
 */
class WaterGroundTest {
    /** Solid below y 62 and at [solid], water at [water], air elsewhere. */
    private fun ground(
        water: Set<List<Int>>,
        solid: Set<List<Int>> = emptySet(),
    ) = object : BlockGround {
        override fun standsIn(position: List<Int>): Boolean =
            position !in solid && (position[1] >= 62 || position in water)

        override fun holdsWater(position: List<Int>): Boolean = position in water
    }

    // Epsilon at tick 5634 (body:00000916): feet in the water at (-43, 62, -13), on sand.
    private val feet = listOf(-42.54, 62.0, -12.5)
    private val support = listOf(-43, 61, -13)

    @Test
    fun `digging the support under water floods the body's own cell`() {
        val flooded = ground(water = setOf(listOf(-43, 62, -13), listOf(-44, 62, -13)))
        assertFalse(digLeavesBodyDry(flooded, feet, support), "epsilon's batd, dug at 5690")
        assertFalse(digSafeAt(flooded, feet, support))
    }

    @Test
    fun `a dry dig, or one whose water cannot reach the body, stays safe`() {
        assertTrue(digLeavesBodyDry(ground(water = emptySet()), feet, support))
        // Water beside a block three columns off floods only that block.
        val far = ground(water = setOf(listOf(-47, 61, -13)))
        assertTrue(digLeavesBodyDry(far, feet, listOf(-46, 61, -13)))
        // A feet-level block beside the body that water lies beside floods the body's cell.
        val beside = ground(water = setOf(listOf(-45, 62, -13)), solid = setOf(listOf(-44, 62, -13)))
        assertFalse(digLeavesBodyDry(beside, feet, listOf(-44, 62, -13)))
    }

    @Test
    fun `a cell holding water is no place to stand`() {
        // The sand west of epsilon at 6100 (bbet) was offered standable, its cell above under water.
        val drowned = ground(water = setOf(listOf(-44, 62, -13)))
        assertEquals(null, drowned.standCell(listOf(-44, 61, -13)))
        assertEquals(listOf(-44, 62, -13), ground(water = emptySet()).standCell(listOf(-44, 61, -13)))
    }
}
