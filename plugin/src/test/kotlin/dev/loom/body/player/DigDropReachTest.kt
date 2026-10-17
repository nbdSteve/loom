package dev.loom.body.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether a dig below the feet leaves its drop where a body can step down to collect it, replayed
 * on r19 kappa's own ground: feet at (8.46, 74, -7.5) on the stone at (8, 73, -8), and the block
 * it dug beside its support at (8, 73, -7), with the grass it later dug at (8, 75, -7) over it.
 */
class DigDropReachTest {
    private val feet = listOf(8.46, 74.0, -7.5)
    private val beside = listOf(8, 73, -7)

    /** A ground whose only solid cells are [solid]; everything else is open. */
    private fun groundOf(vararg solid: List<Int>): BlockGround = BlockGround { position -> position !in solid }

    private val floor = listOf(listOf(8, 72, -7), listOf(8, 73, -8), beside)

    @Test
    fun `kappa's dig beside its support under the grass overhang strands its drop`() {
        // body:00000384: the drop lay in the pocket, a quarter block below the pickup box from
        // beside it, and no body fits under the grass to step down into it.
        val overhung = groundOf(*(floor + listOf(listOf(8, 75, -7))).toTypedArray())
        assertFalse(digLeavesDropReachable(overhung, feet, beside))
        assertFalse(digSafeAt(overhung, feet, beside))
    }

    @Test
    fun `the same pocket open to the sky is stepped down into, so the dig is safe`() {
        // body/test/native-collect-terminal.test.js collects from exactly this pocket.
        val open = groundOf(*floor.toTypedArray())
        assertTrue(digLeavesDropReachable(open, feet, beside))
        assertTrue(digSafeAt(open, feet, beside))
    }

    @Test
    fun `a pocket two below the feet or over open ground strands its drop`() {
        // The dug block at (8, 72, -7) under an already open (8, 73, -7): the drop lies two below.
        val deep = groundOf(listOf(8, 71, -7), listOf(8, 72, -7), listOf(8, 73, -8))
        assertFalse(digLeavesDropReachable(deep, feet, listOf(8, 72, -7)))
        // Nothing under the dug block: the drop falls on past it.
        val bottomless = groundOf(listOf(8, 73, -8), beside)
        assertFalse(digLeavesDropReachable(bottomless, feet, beside))
    }

    @Test
    fun `only the ground's positive answer refuses`() {
        // A block at the feet, the support itself, and a block with a solid cell over it are not
        // this rule's, and with no server to ask nothing is refused.
        val ground = groundOf(*(floor + listOf(listOf(8, 75, -7))).toTypedArray())
        assertTrue(digLeavesDropReachable(ground, feet, listOf(8, 74, -7)))
        assertTrue(digLeavesDropReachable(ground, feet, listOf(8, 73, -8)))
        assertTrue(digLeavesDropReachable(groundOf(*(floor + listOf(listOf(8, 74, -7))).toTypedArray()), feet, beside))
        assertTrue(digLeavesDropReachable(BlockGround.UNKNOWN, feet, beside))
    }
}
