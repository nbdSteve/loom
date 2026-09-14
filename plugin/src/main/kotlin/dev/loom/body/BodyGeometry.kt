/*
 * Geometry shared by every body flavor's semantic perception translation.
 */
package dev.loom.body

import dev.loom.contract.Bearing
import kotlin.math.abs
import kotlin.math.sqrt

private const val WORLD_POSITION_COMPONENTS = 3

/**
 * The nearest horizontal axis for a delta in Minecraft coordinates.
 *
 * Positive z is south. A tie belongs to the z axis, matching the engine's
 * nearest-direction order, and a zero delta is north rather than an exception.
 */
fun horizontalBearing(
    deltaX: Double,
    deltaZ: Double,
): Bearing =
    if (abs(deltaZ) >= abs(deltaX)) {
        if (deltaZ > 0.0) Bearing.SOUTH else Bearing.NORTH
    } else if (deltaX > 0.0) {
        Bearing.EAST
    } else {
        Bearing.WEST
    }

/** How far apart two body-control world positions are in three-dimensional space. */
fun blocksApart(
    from: List<Double>,
    to: List<Double>,
): Double {
    require(
        from.size == WORLD_POSITION_COMPONENTS &&
            to.size == WORLD_POSITION_COMPONENTS,
    ) {
        "world positions have exactly three components"
    }
    val dx = to[0] - from[0]
    val dy = to[1] - from[1]
    val dz = to[2] - from[2]
    return sqrt(dx * dx + dy * dy + dz * dz)
}
