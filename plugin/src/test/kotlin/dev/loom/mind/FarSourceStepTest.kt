package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalStepResult
import dev.loom.contract.GoalSummary
import dev.loom.contract.Intent
import dev.loom.contract.StepEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A goal grounded in a source sight shows beyond dig reach is stepped by the walk to the cell
 * beside it, and that walk is evidence when it brings the source within a dig's reach (r34: zeta's
 * logs were in view and out of reach, and no walk toward them could ever be credited).
 */
class FarSourceStepTest {
    private val ledger = GoalLedger()
    private val zeta = AgentId("tribe-zeta")

    private fun review(
        requestId: String,
        observed: GoalObservation,
        choice: GoalChoice,
        intent: Intent,
    ): Pair<GoalSummary?, GoalEvidence?> {
        val carried = ledger.present(zeta, requestId, requestId.substringAfter('-').toLong(), observed)
        val decision = decisionFor(perception(requestId = requestId, agentId = zeta.value), intent).copy(goal = choice)
        val evidence = ledger.resolve(zeta, decision)
        ledger.stepped(zeta, requestId, "moveTo", EventTag.ACCEPTED, reached = true)
        return carried to evidence
    }

    private fun scene(
        at: List<Double>,
        inReach: Boolean,
    ) = GoalObservation(
        inventory = emptyMap(),
        position = at,
        capacity = mapOf(LOG to 1L),
        visible = setOf("log"),
        gives = mapOf("log" to setOf(LOG)),
        explore = setOf("x1", "x2"),
        inReach = if (inReach) setOf("log") else emptySet(),
        horizon = mapOf(LOG to logHorizon),
        productive = if (inReach) mapOf("log" to setOf(LOG)) else emptyMap(),
    )

    private val logHorizon =
        dev.loom.contract.HorizonGoal(LOG, dev.loom.contract.HorizonGrounding.SIGHT, most = 9, frontier = true)

    private val adopt = GoalChoice(GoalDisposition.ADOPT, successItem = LOG, successCount = 1)
    private val continuing = GoalChoice(GoalDisposition.CONTINUE, stepResult = GoalStepResult.PROGRESSING)

    @Test
    fun `a walk beside a log out of reach that brings it within a dig's reach arrived`() {
        val far = scene(listOf(0.5, 66.0, 0.5), inReach = false)
        assertEquals(GoalEvidence.ADOPTED, review("zeta-10", far, adopt, Intent.MoveTo("x1")).second?.outcome)
        val beside = scene(listOf(6.5, 66.0, 0.5), inReach = true)
        val (carried, _) = review("zeta-20", beside, continuing, Intent.Dig("log"))
        assertEquals(StepEvidence.ARRIVED, carried?.progress?.stepEvidence)
        assertEquals(0L, carried?.progress?.stepsWithoutProgress)
    }

    @Test
    fun `a walk that leaves the log out of reach is no evidence`() {
        val far = scene(listOf(0.5, 66.0, 0.5), inReach = false)
        review("zeta-10", far, adopt, Intent.MoveTo("x2"))
        val still = scene(listOf(-11.5, 66.0, 0.5), inReach = false)
        val (carried, _) = review("zeta-20", still, continuing, Intent.MoveTo("x1"))
        assertEquals(StepEvidence.NONE, carried?.progress?.stepEvidence)
        assertEquals(1L, carried?.progress?.stepsWithoutProgress)
    }

    private companion object {
        const val LOG = "item.minecraft.oak_log"
    }
}
