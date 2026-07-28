package dev.loom.body

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cheap movement model is the body's actuation budget, so its arithmetic is
 * tested without a server. These assert the three properties actuation depends on:
 * it converges, it never overshoots, and it faces where it walks.
 */
class WalkGaitTest {
    private val gait = WalkGait(blocksPerTick = 0.2, arrivalRadius = 0.35)

    @Test
    fun `steps toward the waypoint at the configured pace`() {
        val step = assertNotNull(gait.step(fromX = 0.0, fromZ = 0.0, toX = 10.0, toZ = 0.0))
        assertEquals(0.2, step.stepX, 1e-9)
        assertEquals(0.0, step.stepZ, 1e-9)
    }

    @Test
    fun `reports arrival instead of jittering across the waypoint`() {
        assertNull(gait.step(fromX = 5.0, fromZ = 5.0, toX = 5.1, toZ = 5.1))
    }

    @Test
    fun `never overshoots the waypoint on the final step`() {
        // Closer than one full step, but outside the arrival radius.
        val step = assertNotNull(gait.step(fromX = 0.0, fromZ = 0.0, toX = 0.4, toZ = 0.0))
        assertTrue(step.stepX <= 0.4 + 1e-9, "step ${step.stepX} overshot a 0.4 block gap")
    }

    @Test
    fun `walking converges on the waypoint in a bounded number of ticks`() {
        var x = 0.0
        var z = 0.0
        var ticks = 0
        val maxTicks = 500
        while (ticks < maxTicks) {
            val step = gait.step(x, z, toX = 12.0, toZ = 9.0) ?: break
            x += step.stepX
            z += step.stepZ
            ticks++
        }
        assertTrue(ticks < maxTicks, "did not converge within $maxTicks ticks")
        assertTrue(hypot(12.0 - x, 9.0 - z) <= 0.35, "stopped ${hypot(12.0 - x, 9.0 - z)} blocks away")
    }

    @Test
    fun `faces the direction of travel using Minecraft yaw where zero is plus Z`() {
        val towardPositiveZ = assertNotNull(gait.step(0.0, 0.0, toX = 0.0, toZ = 10.0))
        assertEquals(0.0f, towardPositiveZ.yaw, 1e-3f)

        val towardNegativeX = assertNotNull(gait.step(0.0, 0.0, toX = -10.0, toZ = 0.0))
        assertEquals(90.0f, towardNegativeX.yaw, 1e-3f)

        val towardPositiveX = assertNotNull(gait.step(0.0, 0.0, toX = 10.0, toZ = 0.0))
        assertEquals(270.0f, towardPositiveX.yaw, 1e-3f)
    }

    @Test
    fun `yaw is always normalized into a single turn`() {
        for (angle in 0 until 360 step 7) {
            val radians = Math.toRadians(angle.toDouble())
            val step = assertNotNull(gait.step(0.0, 0.0, toX = 10 * Math.cos(radians), toZ = 10 * Math.sin(radians)))
            assertTrue(step.yaw >= 0.0f && step.yaw < 360.0f, "yaw ${step.yaw} outside [0, 360)")
        }
    }

    @Test
    fun `diagonal steps cover the configured distance, not more per axis`() {
        val step = assertNotNull(gait.step(0.0, 0.0, toX = 10.0, toZ = 10.0))
        assertEquals(0.2, hypot(step.stepX, step.stepZ), 1e-9)
        assertTrue(abs(step.stepX - step.stepZ) < 1e-9, "a 45 degree heading should split evenly")
    }

    @Test
    fun `rejects a non-positive pace or arrival radius`() {
        val paceFailure = runCatching { WalkGait(blocksPerTick = 0.0, arrivalRadius = 0.3) }
        assertTrue(paceFailure.exceptionOrNull() is IllegalArgumentException)

        val radiusFailure = runCatching { WalkGait(blocksPerTick = 0.2, arrivalRadius = -1.0) }
        assertTrue(radiusFailure.exceptionOrNull() is IllegalArgumentException)
    }
}
