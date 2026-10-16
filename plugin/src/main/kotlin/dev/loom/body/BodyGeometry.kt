/*
 * Geometry shared by every body flavor's semantic perception translation.
 */
package dev.loom.body

import dev.loom.contract.Bearing
import kotlin.math.abs
import kotlin.math.sqrt

private const val WORLD_POSITION_COMPONENTS = 3

/** Within this far on both horizontal axes, a thing is in the agent's own block column. */
private const val OWN_COLUMN_BLOCKS = 0.5

/**
 * The nearest horizontal axis for a delta in Minecraft coordinates.
 *
 * Positive z is south. A tie belongs to the z axis, matching the engine's nearest-direction
 * order. A delta inside the agent's own block column on both axes is [Bearing.HERE]: the
 * block the agent stands on is straight below it, and naming a compass point for it - the
 * zero delta once read as north - told a mind that block lay beside it, so a live run's
 * walks went to the block each body was already standing on.
 */
fun horizontalBearing(
    deltaX: Double,
    deltaZ: Double,
): Bearing =
    if (abs(deltaX) < OWN_COLUMN_BLOCKS && abs(deltaZ) < OWN_COLUMN_BLOCKS) {
        Bearing.HERE
    } else if (abs(deltaZ) >= abs(deltaX)) {
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
