package dev.loom.body.player

import dev.loom.contract.CoverFace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * r39 iota's closing (`sense-u5`, tick 8735): it stood at (3.4995, 68, -29.927), 0.227 of a block
 * past its feet cell's north edge, and was offered feetNorth against the grass under that cell. Its
 * own box reached into the cell, so vanilla refused the placement (real Paper: the destination
 * resent as air, acknowledged 54 ms after the packet), and the body held its one exclusive owner
 * for 330 ticks under skeleton fire until it died. The body now steps to its own cell's centre
 * before such a placement (`body/src/placing.js`), so the cover offers the face from there.
 */
class R39PlacementReplayTest {
    /** Open grass at y 67: everything at y 68 and above is air, everything below solid. */
    private val ground =
        object : BlockGround {
            override fun standsIn(position: List<Int>): Boolean = position[1] >= 68

            override fun takesPlacement(position: List<Int>): Boolean = standsIn(position)
        }

    private val supports: Map<List<Int>, CoverSupport> =
        (1..5).flatMap { x -> (-33..-28).map { z -> listOf(x, 67, z) } }
            .withIndex()
            .associate { (index, cell) -> cell to CoverSupport("b$index", cell, visible = true) }

    private fun closable(
        standing: List<Double>,
        on: BlockGround = ground,
    ): Set<CoverFace> =
        requireNotNull(coverOf(on, standing, supports, listOf("item.minecraft.oak_log")))
            .closings
            .map { it.face }
            .toSet()

    @Test
    fun `a face the body's own box reaches into is closed from its cell's centre, and never over no floor`() {
        val iota = listOf(3.4995153354283053, 68.0, -29.926890663618433)
        assertTrue(bodyReaches(iota, listOf(3, 68, -31)), "its box reached the cell north of its feet")
        // Its feet cell has a floor, so the step to its centre is one the body takes before it places.
        assertTrue(CoverFace.FEET_NORTH in closable(iota))
        assertTrue(CoverFace.FEET_SOUTH in closable(iota), "the far side stays closable")
        // Held up by the edge of the block north of a feet cell with no floor, the step is a fall.
        val ledge =
            object : BlockGround {
                override fun standsIn(position: List<Int>): Boolean =
                    position[1] >= 68 || position == listOf(3, 67, -30)

                override fun takesPlacement(position: List<Int>): Boolean = standsIn(position)
            }
        assertFalse(CoverFace.FEET_NORTH in closable(iota, ledge))
    }

    @Test
    fun `from the centre of its cell the same face closes, and touching a cell's face is not reaching it`() {
        val centred = listOf(3.5, 68.0, -29.5)
        assertFalse(bodyReaches(centred, listOf(3, 68, -31)))
        assertTrue(CoverFace.FEET_NORTH in closable(centred))
        // A box flush with the cell's face, 0.3 from the edge, only touches it.
        assertFalse(bodyReaches(listOf(3.5, 68.0, -29.7), listOf(3, 68, -31)))
        assertEquals(true, bodyReaches(listOf(3.5, 68.0, -29.701), listOf(3, 68, -31)))
    }
}

/**
 * The r39 offline audit: a closing into a cell another body stood in was offered, so its refusal as
 * blocked read as a race with a cell that was never free. The cover now asks the server's ground.
 */
class OccupiedClosingTest {
    private val self = java.util.UUID.randomUUID()
    private val zombie = listOf(3, 68, -29)

    /** Open grass at y 67, with another body the server holds at the cell south of the agent's feet. */
    private val ground =
        object : BlockGround {
            override fun standsIn(position: List<Int>): Boolean = position[1] >= 68

            override fun takesPlacement(position: List<Int>): Boolean = standsIn(position)

            override fun bodyIn(
                position: List<Int>,
                besides: java.util.UUID?,
            ): Boolean = position == zombie && besides == self
        }

    private val supports: Map<List<Int>, CoverSupport> =
        (1..5).flatMap { x -> (-33..-26).map { z -> listOf(x, 67, z) } }
            .withIndex()
            .associate { (index, cell) -> cell to CoverSupport("b$index", cell, visible = true) }

    @Test
    fun `a face another living body stands in is never offered as a closing`() {
        val centred = listOf(3.5, 68.0, -29.5)
        val closable =
            requireNotNull(
                coverOf(SceneGround(ground, centred, self).ground, centred, supports, listOf("item.minecraft.dirt")),
            )
                .closings
                .map { it.face }
                .toSet()
        assertFalse(CoverFace.FEET_SOUTH in closable, "the zombie's cell")
        assertEquals(setOf(CoverFace.FEET_NORTH, CoverFace.FEET_EAST, CoverFace.FEET_WEST), closable)
    }
}

/**
 * The provider-free v36 rehearsal's delta (`body:00000383`): its shelter's two south faces held
 * short grass, which a placed block replaces, and the cover offered no closing into them, so the
 * shelter could not be finished and the agent died in the open.
 */
class ReplaceableClosingTest {
    private val grass = listOf(3, 68, -29)
    private val flower = listOf(4, 68, -30)

    /** Open ground at y 67, short grass south of the agent's feet and a flower east of them. */
    private val ground =
        object : BlockGround {
            override fun standsIn(position: List<Int>): Boolean = position[1] >= 68

            override fun takesPlacement(position: List<Int>): Boolean = standsIn(position) && position != flower
        }

    private val supports: Map<List<Int>, CoverSupport> =
        (1..5).flatMap { x -> (-33..-26).map { z -> listOf(x, 67, z) } }
            .withIndex()
            .associate { (index, cell) -> cell to CoverSupport("b$index", cell, visible = true) }

    @Test
    fun `a closing replaces short grass, and a flower no placement replaces stays open`() {
        val centred = listOf(3.5, 68.0, -29.5)
        val reading = requireNotNull(coverOf(ground, centred, supports, listOf("item.minecraft.dirt")))
        val closable = reading.closings.map { it.face }.toSet()
        assertTrue(ground.takesPlacement(grass))
        assertTrue(CoverFace.FEET_SOUTH in closable, "the short grass")
        assertTrue(CoverFace.FEET_EAST in reading.open)
        assertFalse(CoverFace.FEET_EAST in closable, "the flower")
    }
}

/**
 * The provider-free v36 rehearsal's delta (`body:00000383`): it dug the block under its feet 0.3 off
 * its centre, stayed held up on the edge of the column east of it, and its cover read no walk out of
 * a feet cell with no floor, so no flight was offered from the zombie that killed it.
 */
class HangingCoverTest {
    private val hole = listOf(3, 67, -30)

    /** Open ground at y 67, but for the dug cell under the agent's feet cell. */
    private val ground =
        object : BlockGround {
            override fun standsIn(position: List<Int>): Boolean = position[1] >= 68 || position == hole

            override fun takesPlacement(position: List<Int>): Boolean = standsIn(position)
        }

    @Test
    fun `a body held over its own dug floor walks out from where it comes to rest`() {
        val hanging = listOf(3.8, 68.0, -29.5)
        assertEquals(hole, restingCellOf(ground, listOf(3, 68, -30)))
        val cover = requireNotNull(coverOf(ground, hanging, emptyMap(), listOf("item.minecraft.dirt")))
        assertTrue(cover.walkOut, "from the hole a step up leads out")
        // Its feet cell's own floor intact, it rests where it stands.
        val floored = BlockGround { position -> position[1] >= 68 }
        assertEquals(listOf(3, 68, -30), restingCellOf(floored, listOf(3, 68, -30)))
    }
}
