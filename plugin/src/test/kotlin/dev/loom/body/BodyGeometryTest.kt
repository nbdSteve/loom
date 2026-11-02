package dev.loom.body

import dev.loom.contract.Bearing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class BodyGeometryTest {
    @Test
    @DisplayName("a thing in the agent's own block column is here, and anything else has a compass point")
    fun ownColumnIsHere() {
        // The block a body stands on: its centre is straight below the feet.
        assertEquals(Bearing.HERE, horizontalBearing(0.0, 0.0))
        assertEquals(Bearing.HERE, horizontalBearing(0.49, -0.49))
        // The neighbouring block's centre is a whole block away on one axis.
        assertEquals(Bearing.NORTH, horizontalBearing(0.0, -1.0))
        assertEquals(Bearing.SOUTH, horizontalBearing(0.0, 1.0))
        assertEquals(Bearing.EAST, horizontalBearing(1.0, 0.0))
        assertEquals(Bearing.WEST, horizontalBearing(-1.0, 0.0))
        // Half a block out on either axis is outside the column, and a tie is the z axis.
        assertEquals(Bearing.NORTH, horizontalBearing(0.0, -0.5))
        assertEquals(Bearing.EAST, horizontalBearing(0.5, 0.0))
        assertEquals(Bearing.SOUTH, horizontalBearing(0.7, 0.7))
    }
}
