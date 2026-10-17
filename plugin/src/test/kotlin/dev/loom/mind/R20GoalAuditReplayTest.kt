package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.body.tagOf
import dev.loom.contract.Bearing
import dev.loom.contract.BlockYield
import dev.loom.contract.ExploreTarget
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalStepResult
import dev.loom.contract.Intent
import dev.loom.contract.Observation
import dev.loom.contract.ObservationKind
import dev.loom.contract.Perception
import dev.loom.contract.RecalledSource
import dev.loom.contract.StepEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The independent r20 goal-chain audit, replayed through the real ledger.
 *
 * kappa saw logs only after a flight (perception body:00000761) and theta after another
 * (body:00001067); neither could set a log goal once the tree was out of sight, because a goal may
 * name only an item obtainable now. A source the body recalls now counts, and a walk toward it
 * that brings the tree into sight is credited as revealing it.
 */
class R20GoalAuditReplayTest {
    private val ledger = GoalLedger()
    private val kappa = AgentId("kappa")

    private fun seen(
        requestId: String,
        at: List<Double>,
        surroundings: List<Observation> = emptyList(),
        recalled: List<RecalledSource> = emptyList(),
    ): Perception {
        val base = perception(requestId = requestId, agentId = kappa.value)
        return base.copy(
            selfState = base.selfState.copy(position = at),
            surroundings = surroundings,
            explore = listOf(ExploreTarget("x1", 12.0, Bearing.EAST, 0.0)),
            recalled = recalled,
        )
    }

    @Test
    fun `kappa's tree out of sight is a goal it can set, and the walk that finds it reveals it`() {
        val tree = RecalledSource(LOG, 18.0, Bearing.EAST, 0.0, 300)
        val first = seen("kappa-761", listOf(12.5, 67.0, 0.5), recalled = listOf(tree))
        val observed = GoalObservation.of(first)
        assertEquals(3L, observed.capacity[LOG])
        assertEquals(0L, observed.inSight(LOG), "a recalled source is no source in sight")
        ledger.present(kappa, first.requestId, 761, observed)
        val adopt = GoalChoice(GoalDisposition.ADOPT, successItem = LOG, successCount = 3)
        val toward = decisionFor(first, Intent.MoveTo("x1")).copy(goal = adopt)
        assertEquals(GoalEvidence.ADOPTED, ledger.resolve(kappa, toward)?.outcome)
        ledger.stepped(kappa, first.requestId, tagOf(toward.intent), EventTag.ACCEPTED, reached = true)
        // The walk ends where the tree is in sight: its first log is a dig away.
        val log =
            Observation(
                kind = ObservationKind.BLOCK,
                what = "block.minecraft.oak_log",
                distance = 2.0,
                direction = Bearing.EAST,
                verticalOffset = 0.5,
                visible = true,
                ref = "b1",
                standable = false,
                yields = listOf(BlockYield(LOG, 1, 1, 100)),
                digSafe = true,
            )
        val there = seen("kappa-800", listOf(24.5, 67.0, 0.5), surroundings = listOf(log))
        val carried = ledger.present(kappa, there.requestId, 800, GoalObservation.of(there))
        assertEquals(StepEvidence.REVEALED, carried?.progress?.stepEvidence)
        val dig =
            decisionFor(
                there,
                Intent.Dig("b1"),
            ).copy(goal = GoalChoice(GoalDisposition.CONTINUE, stepResult = GoalStepResult.PROGRESSING))
        assertEquals(GoalEvidence.CONTINUED, ledger.resolve(kappa, dig)?.outcome)
    }

    private companion object {
        const val LOG = "item.minecraft.oak_log"
    }
}
