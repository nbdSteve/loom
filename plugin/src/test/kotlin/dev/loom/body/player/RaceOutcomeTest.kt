package dev.loom.body.player

import dev.loom.body.EventTag
import dev.loom.body.IntentOutcome
import dev.loom.bodycontrol.AttackOutcome
import dev.loom.bodycontrol.BODY_CONTROL_PROTOCOL_VERSION
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.LifecycleState
import dev.loom.bodycontrol.MoveOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** r22's two endings a target's own movement decided, filed as what they were. */
class RaceOutcomeTest {
    private fun attack(
        outcome: AttackOutcome,
        attacks: Int,
    ) = BodyResponse.AttackReport(
        BODY_CONTROL_PROTOCOL_VERSION,
        "attack-qb",
        "tribe-delta",
        LifecycleState.READY,
        outcome,
        from = listOf(10.5, 64.0, 10.5),
        position = listOf(10.5, 64.0, 10.5),
        travelled = 0.0,
        elapsedMs = 650,
        attacks = attacks,
    )

    @Test
    fun `out of range after a landed strike is the target escaping, and before any is out of range`() {
        // r22 delta body:00002116: the first strike landed and the skeleton was then 5.9 off.
        val escaped = attackOutcome(attack(AttackOutcome.OUT_OF_RANGE, attacks = 1)) as IntentOutcome.Rejected
        assertEquals(EventTag.ATTACK_TARGET_ESCAPED, escaped.tag)
        val never = attackOutcome(attack(AttackOutcome.OUT_OF_RANGE, attacks = 0)) as IntentOutcome.Rejected
        assertEquals(EventTag.ATTACK_OUT_OF_RANGE, never.tag)
    }

    @Test
    fun `a flight that arrived with the threat still close is short, and one that got clear is safe`() {
        // r22: eight "safe" flights ended under 12 blocks from a threat that kept pace.
        assertEquals(EventTag.RETREAT_SHORT, retreatTag(MoveOutcome.ARRIVED, separation = 9.1, distance = 24))
        assertEquals(EventTag.RETREAT_SAFE, retreatTag(MoveOutcome.ARRIVED, separation = 24.3, distance = 24))
        assertEquals(EventTag.RETREAT_SAFE, retreatTag(MoveOutcome.ARRIVED))
        assertEquals(EventTag.RETREAT_FAILED, retreatTag(MoveOutcome.STALLED, separation = 9.5, distance = 24))
    }
}
