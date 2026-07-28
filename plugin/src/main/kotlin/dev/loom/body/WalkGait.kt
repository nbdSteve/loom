/*
 * The cheap Loom-owned movement model, as pure domain arithmetic.
 *
 * This is deliberately NOT pathfinding. A measured comparison of body movement
 * models found that a straight cheap per-tick step toward a waypoint costs a
 * couple of microseconds per body and stays flat into the hundreds, while vanilla
 * goal selectors driving synchronous A* spiked to half the tick budget at a
 * hundred bodies. So the body owns a cheap step and nothing more; real navigation,
 * when it lands, must be computed off the tick and fed in as waypoints.
 *
 * Keeping the arithmetic here rather than inside the adapter means the movement
 * model is unit-testable without a server and is shared by any future body
 * flavor: an adapter's only job is to apply the resulting step.
 */
package dev.loom.body

import kotlin.math.atan2
import kotlin.math.sqrt

/** Full circle in degrees, for converting a heading to Minecraft's yaw. */
private const val DEGREES_PER_TURN = 360.0

/** Quarter turn in degrees; Minecraft yaw 0 faces +Z, so headings are offset. */
private const val YAW_OFFSET_DEGREES = 90.0

/**
 * A single tick of cheap movement toward a waypoint.
 *
 * [stepX]/[stepZ] are the horizontal displacement to apply this tick; [yaw] is
 * the heading the body should face, in Minecraft's degrees convention.
 */
data class WalkStep(
    val stepX: Double,
    val stepZ: Double,
    val yaw: Float,
)

/**
 * Computes cheap per-tick steps toward a waypoint at a fixed speed.
 *
 * @param blocksPerTick horizontal distance covered per tick. A vanilla walking
 *   pace is roughly 0.2 blocks/tick.
 * @param arrivalRadius horizontal distance at which the waypoint counts as
 *   reached, which stops the body jittering across its target.
 */
class WalkGait(
    private val blocksPerTick: Double,
    private val arrivalRadius: Double,
) {
    init {
        require(blocksPerTick > 0.0) { "blocksPerTick must be positive, got $blocksPerTick" }
        require(arrivalRadius > 0.0) { "arrivalRadius must be positive, got $arrivalRadius" }
    }

    /**
     * The step to take from ([fromX], [fromZ]) toward ([toX], [toZ]), or `null`
     * if the waypoint has been reached and the body should stand still.
     *
     * Never overshoots: the final step is clamped to land on the waypoint.
     */
    fun step(
        fromX: Double,
        fromZ: Double,
        toX: Double,
        toZ: Double,
    ): WalkStep? {
        val dx = toX - fromX
        val dz = toZ - fromZ
        val distance = sqrt(dx * dx + dz * dz)
        if (distance <= arrivalRadius) {
            return null
        }
        val travel = minOf(blocksPerTick, distance)
        val scale = travel / distance
        return WalkStep(
            stepX = dx * scale,
            stepZ = dz * scale,
            yaw = headingYaw(dx, dz),
        )
    }

    private fun headingYaw(
        dx: Double,
        dz: Double,
    ): Float {
        val degrees = Math.toDegrees(atan2(dz, dx)) - YAW_OFFSET_DEGREES
        // Normalize into [0, 360) so the value is stable regardless of heading.
        val normalized = ((degrees % DEGREES_PER_TURN) + DEGREES_PER_TURN) % DEGREES_PER_TURN
        return normalized.toFloat()
    }
}
