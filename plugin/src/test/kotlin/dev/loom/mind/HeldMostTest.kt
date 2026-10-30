package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalProgress
import dev.loom.contract.Intent
import dev.loom.contract.StepEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The most of its item, and of each input, an item goal has held (`Held.mostHeld`): a craft back to
 * a level held before is no gain, and gathering back what something else spent is no stall.
 */
class HeldMostTest {
    private val alpha = AgentId("agent-alpha")

    @Test
    fun aReversiblePairOfInputsCraftedBackAndForthIsNoGain() {
        // The r39 round 5 offline audit: a chain is made from nuggets and an ingot, which craft into
        // each other, so a chain goal crafting one into the other and back was credited gainedInput
        // every step and never stalled.
        val chain = "item.minecraft.chain"
        val nugget = "item.minecraft.iron_nugget"
        val ingot = "item.minecraft.iron_ingot"
        val pair =
            object : RecipeInputs {
                override fun inputsOf(item: String): Set<String> =
                    if (item == chain) setOf(nugget, ingot) else emptySet()
            }
        val ledger = GoalLedger(recipes = pair)

        fun at(
            requestId: String,
            vararg stacks: Pair<String, Long>,
        ): GoalProgress? {
            val observed =
                GoalObservation(
                    mapOf(*stacks),
                    listOf(0.0, 64.0, 0.0),
                    capacity = mapOf(chain to 8L),
                    craftable = setOf(chain, nugget, ingot),
                    gives = mapOf("c1" to setOf(chain, nugget, ingot)),
                    productive = mapOf("c1" to setOf(chain, nugget, ingot)),
                )
            val carried = ledger.present(alpha, requestId, requestId.drop(1).toLong() * 100, observed)
            val choice =
                if (carried == null) {
                    goalChoice(
                        GoalDisposition.ADOPT,
                        success = chain to 6,
                    )
                } else {
                    goalChoice(GoalDisposition.CONTINUE)
                }
            val decision = decisionFor(perception(requestId = requestId, agentId = alpha.value), Intent.Craft("c1", 1))
            val outcome = ledger.resolve(alpha, decision.copy(goal = choice))?.outcome
            assertTrue(outcome in setOf(GoalEvidence.ADOPTED, GoalEvidence.CONTINUED), outcome)
            ledger.stepped(alpha, requestId, EventTag.CRAFT, EventTag.ACCEPTED)
            return carried?.progress
        }
        at("r1", ingot to 3L)
        // An ingot made into nuggets the goal never held is a gain.
        assertEquals(StepEvidence.GAINED_INPUT, at("r2", ingot to 2L, nugget to 9L)?.stepEvidence)
        // Made back, and into nuggets again: no more of either than the goal has held.
        val back = at("r3", ingot to 3L)
        assertEquals(StepEvidence.NONE, back?.stepEvidence)
        assertEquals(1L, back?.stepsWithoutProgress)
        val again = at("r4", ingot to 2L, nugget to 9L)
        assertEquals(StepEvidence.NONE, again?.stepEvidence)
        assertEquals(2L, again?.stepsWithoutProgress)
        // A chain made from them is progress, and what it used is the goal's: the next nuggets are a gain.
        val made = at("r5", chain to 1L, ingot to 1L, nugget to 7L)
        assertEquals(StepEvidence.GAINED, made?.stepEvidence)
        assertEquals(0L, made?.stepsWithoutProgress)
        assertEquals(StepEvidence.GAINED_INPUT, at("r6", chain to 1L, nugget to 16L)?.stepEvidence)
    }

    @Test
    fun aGoalItemSomethingElseSpentIsGatheredBackWithoutStalling() {
        // The r39 round 5 offline audit: logs another decision spent, gathered back one a step, each
        // counted a step without progress, so the goal stalled before it passed the most it had held.
        val log = "item.minecraft.oak_log"
        val ledger = GoalLedger()

        fun at(
            requestId: String,
            logs: Long,
        ): Pair<GoalProgress?, GoalEvidence?> {
            val observed =
                GoalObservation(
                    mapOf(log to logs),
                    listOf(0.0, 64.0, 0.0),
                    capacity = mapOf(log to 4L),
                    gives = mapOf("b1" to setOf(log)),
                    productive = mapOf("b1" to setOf(log)),
                )
            val carried = ledger.present(alpha, requestId, requestId.drop(1).toLong() * 100, observed)
            val choice =
                if (carried == null) {
                    goalChoice(
                        GoalDisposition.ADOPT,
                        success = log to 8,
                    )
                } else {
                    goalChoice(GoalDisposition.CONTINUE)
                }
            val decision = decisionFor(perception(requestId = requestId, agentId = alpha.value), Intent.Dig("b1"))
            val resolved = ledger.resolve(alpha, decision.copy(goal = choice))
            ledger.stepped(alpha, requestId, EventTag.DIG, EventTag.ACCEPTED)
            return carried?.progress to resolved
        }
        assertEquals(GoalEvidence.ADOPTED, at("r1", 5).second?.outcome)
        // Something else spent four while the dig gave one.
        assertEquals(1L, at("r2", 2).first?.stepsWithoutProgress)
        for ((requestId, logs) in listOf("r3" to 3L, "r4" to 4L, "r5" to 5L)) {
            val (progress, resolved) = at(requestId, logs)
            assertEquals(false, progress?.conditionAdvanced, requestId)
            assertEquals(1L, progress?.stepsWithoutProgress, requestId)
            assertEquals(GoalEvidence.CONTINUED, resolved?.outcome, requestId)
        }
        val past = at("r6", 6).first
        assertEquals(true, past?.conditionAdvanced)
        assertEquals(StepEvidence.GAINED, past?.stepEvidence)
        assertEquals(0L, past?.stepsWithoutProgress)
    }
}
