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
    fun `the lower block of a two-high wall stays diggable, its drop in a one-high nook`() {
        // r28 plains alpha dug dirt beside its feet with dirt above it; the body collects such a
        // drop by a final step inside its own cell, so the dig is not refused.
        val lower = listOf(8, 74, -7)
        val wall = groundOf(*(floor + listOf(lower, listOf(8, 75, -7))).toTypedArray())
        assertTrue(digLeavesDropReachable(wall, feet, lower))
        assertTrue(digSafeAt(wall, feet, lower))
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

/** Whether digging the block underfoot leaves a way back up, replayed on r20's shafts. */
class DigLeavesExitTest {
    /** A ground whose only open cells are [open]; everything else is solid. */
    private fun openAt(vararg open: List<Int>): BlockGround = BlockGround { position -> position in open }

    private val feet = listOf(-9.5, 63.0, 7.5)
    private val support = listOf(-10, 62, 7)

    @Test
    fun `the support of a body at the bottom of a two-wide shaft is no longer dug`() {
        // r20 epsilon at (-10, 62, 7), 4 deep, its shaft's other column (-11, 62..66, 7) open:
        // the next column at the new depth was the "way out", and the shaft became a trap.
        val shaft = (63..66).flatMap { y -> listOf(listOf(-10, y, 7), listOf(-11, y, 7)) }.toTypedArray()
        val walls = openAt(*shaft)
        assertFalse(digLeavesExit(walls, listOf(-9.5, 63.0, 7.5), listOf(-10, 62, 7)))
    }

    @Test
    fun `the block under the stair out of a dug hole is not dug`() {
        // The v29 plains rehearsal, epsilon: in its own hole at (-10, 64, 7), floor 2 below the
        // surface at 66, the hole's other column (-11, 65, 7) on solid (-11, 64, 7) was the stair
        // out. It dug that block at tick 2987, and its flights were refused noRoute after it.
        val surface = BlockGround { position -> position[1] >= 66 }
        val hole =
            BlockGround { position ->
                surface.standsIn(position) || position in
                    listOf(listOf(-10, 64, 7), listOf(-10, 65, 7), listOf(-11, 65, 7))
            }
        val at = listOf(-9.5, 64.0, 7.5)
        assertFalse(digLeavesExit(hole, at, listOf(-11, 64, 7)), "the stair's own footing")
        // A block that is no part of the walk out is dug as before, and so is the wall beside.
        assertTrue(digLeavesExit(hole, at, listOf(-9, 65, 7)))
        assertTrue(digLeavesExit(hole, at, listOf(-20, 64, 7)))
    }

    @Test
    fun `a support beside open ground at the body's own level is still dug`() {
        // A flat field: a neighbour of the support is solid with two open cells over it, so the
        // body steps back up out of its one-deep hole.
        val field = BlockGround { position -> position[1] >= 63 }
        assertTrue(digLeavesExit(field, feet, support))
        // A one-deep hole in that field: dug again, its floor would land the body two deep with
        // no step up out of it, so it is not dug.
        val hole = BlockGround { position -> position[1] >= 63 || position == listOf(-10, 62, 7) }
        assertFalse(digLeavesExit(hole, listOf(-9.5, 62.0, 7.5), listOf(-10, 61, 7)))
        // The same hole beside a step: a dug block next to it at the hole's floor lets the body
        // climb out one level at a time.
        val stepped =
            BlockGround { position ->
                position[1] >= 63 || position == listOf(-10, 62, 7) || position == listOf(-9, 62, 7)
            }
        assertTrue(digLeavesExit(stepped, listOf(-9.5, 62.0, 7.5), listOf(-10, 61, 7)))
        // Two deep and boxed in on every side but one column: no way out.
        val well = openAt(*(61..66).map { y -> listOf(-10, y, 7) }.toTypedArray())
        assertFalse(digLeavesExit(well, listOf(-9.5, 61.0, 7.5), listOf(-10, 60, 7)))
    }
}
