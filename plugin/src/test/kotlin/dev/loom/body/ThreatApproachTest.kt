package dev.loom.body

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** When a flight is worth a sprint, against r33's sprinting flights and starved healing. */
class ThreatApproachTest {
    private val skeleton = UUID.fromString("00000000-0000-4000-8000-000000000041")

    @Test
    fun `a distant threat that is not closing is fled at a walk`() {
        // r33: every flight sprinted, from skeletons fifteen blocks off as from spiders at three.
        val approach = ThreatApproach()
        approach.record(skeleton, 100, 15.2)
        assertFalse(approach.sprints(skeleton, 140, 15.0))
        assertFalse(approach.sprints(skeleton, 140, 15.0), "asking again changes nothing")
    }

    @Test
    fun `a near threat, or one closing fast, is fled at a sprint`() {
        val approach = ThreatApproach()
        assertTrue(approach.sprints(skeleton, 100, SPRINT_NEAR_BLOCKS), "near enough")
        approach.record(skeleton, 100, 16.0)
        // Six blocks in twenty ticks: 0.3 a tick, a charge.
        assertTrue(approach.sprints(skeleton, 120, 10.0))
        // Two blocks in twenty ticks: 0.1 a tick, a walk.
        assertFalse(approach.sprints(skeleton, 120, 14.0))
        // A sample past the window says nothing about the pace now.
        assertFalse(approach.sprints(skeleton, 100 + CLOSING_WINDOW_TICKS + 1, 10.0))
    }

    @Test
    fun `each sprint choice states the reason that decided it`() {
        // r34: delta's walking flight from a skeleton 15.6 blocks off stalled, and no record said
        // it had walked, nor why.
        val approach = ThreatApproach()
        assertEquals(SprintChoice(true, SprintReason.NEAR), approach.choice(skeleton, 100, SPRINT_NEAR_BLOCKS))
        approach.record(skeleton, 100, 16.0)
        assertEquals(SprintChoice(true, SprintReason.CLOSING), approach.choice(skeleton, 120, 10.0))
        assertEquals(SprintChoice(false, SprintReason.DISTANT), approach.choice(skeleton, 120, 15.6))
    }
}
