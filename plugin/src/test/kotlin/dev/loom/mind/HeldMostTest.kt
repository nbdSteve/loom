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
        // A chain is made from both; nine nuggets make an ingot, and an ingot nine nuggets.
        val pair =
            object : RecipeInputs {
                override fun inputsOf(item: String): Set<String> =
                    when (item) {
                        chain -> setOf(nugget, ingot)
                        ingot -> setOf(nugget)
                        nugget -> setOf(ingot)
                        else -> emptySet()
                    }
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
    fun anInputMadeAgainAfterAnotherInputUsedItIsAGain() {
        // The r39 round 6 offline audit: planks made, then spent on sticks, then made again from
        // logs were held to the most planks the goal had held, so a pickaxe goal's third craft
        // counted as a step without progress though the planks could never be made back from sticks.
        val pickaxe = "item.minecraft.wooden_pickaxe"
        val planks = "item.minecraft.oak_planks"
        val stick = "item.minecraft.stick"
        val log = "item.minecraft.oak_log"
        val recipes =
            object : RecipeInputs {
                override fun inputsOf(item: String): Set<String> =
                    when (item) {
                        pickaxe -> setOf(planks, stick)
                        stick -> setOf(planks)
                        planks -> setOf(log)
                        else -> emptySet()
                    }
            }
        val ledger = GoalLedger(recipes = recipes)
        val crafts = mapOf("c1" to setOf(planks), "c2" to setOf(stick), "c3" to setOf(pickaxe))

        fun at(
            requestId: String,
            recipe: String,
            vararg stacks: Pair<String, Long>,
        ): GoalProgress? {
            val observed =
                GoalObservation(
                    mapOf(*stacks),
                    listOf(0.0, 64.0, 0.0),
                    capacity = mapOf(pickaxe to 2L),
                    craftable = setOf(pickaxe, planks, stick),
                    gives = crafts,
                    productive = crafts,
                )
            val carried = ledger.present(alpha, requestId, requestId.drop(1).toLong() * 100, observed)
            val choice =
                if (carried == null) {
                    goalChoice(
                        GoalDisposition.ADOPT,
                        success = pickaxe to 2,
                    )
                } else {
                    goalChoice(GoalDisposition.CONTINUE)
                }
            val decision =
                decisionFor(perception(requestId = requestId, agentId = alpha.value), Intent.Craft(recipe, 1))
            val outcome = ledger.resolve(alpha, decision.copy(goal = choice))?.outcome
            assertTrue(outcome in setOf(GoalEvidence.ADOPTED, GoalEvidence.CONTINUED), outcome)
            ledger.stepped(alpha, requestId, EventTag.CRAFT, EventTag.ACCEPTED)
            return carried?.progress
        }
        at("r1", "c1", log to 3L)
        assertEquals(StepEvidence.GAINED_INPUT, at("r2", "c2", planks to 8L, log to 1L)?.stepEvidence)
        assertEquals(StepEvidence.GAINED_INPUT, at("r3", "c1", planks to 4L, stick to 8L, log to 1L)?.stepEvidence)
        // Sticks cannot be made back into planks, so planks made again from the last log are a gain.
        val again = at("r4", "c3", planks to 8L, stick to 8L)
        assertEquals(StepEvidence.GAINED_INPUT, again?.stepEvidence)
        assertEquals(0L, again?.stepsWithoutProgress)
    }

    @Test
    fun inputsCraftedRoundALongerCycleAreNoGainTheSecondTimeRound() {
        // Vanilla's every reversible pair is one or two crafts deep, but the registry is reread after
        // a datapack reload: four inputs each crafted from the one before, the first from the last,
        // were each judged used up for good by a two-craft search, so the lap repeated every credit.
        val goal = "item.minecraft.lodestone"
        val ring = listOf("coal", "flint", "clay_ball", "bone").map { "item.minecraft.$it" }
        val recipes =
            object : RecipeInputs {
                override fun inputsOf(item: String): Set<String> =
                    when (item) {
                        goal -> ring.toSet()
                        in ring -> setOf(ring[(ring.indexOf(item) + ring.size - 1) % ring.size])
                        else -> emptySet()
                    }
            }
        val ledger = GoalLedger(recipes = recipes)
        val crafts = ring.withIndex().associate { (i, item) -> "c$i" to setOf(item) }

        fun at(
            requestId: String,
            held: String,
        ): GoalProgress? {
            val observed =
                GoalObservation(
                    mapOf(held to 1L),
                    listOf(0.0, 64.0, 0.0),
                    capacity = mapOf(goal to 2L),
                    craftable = ring.toSet() + goal,
                    gives = crafts,
                    productive = crafts,
                )
            val carried = ledger.present(alpha, requestId, requestId.drop(1).toLong() * 100, observed)
            val choice =
                if (carried == null) {
                    goalChoice(GoalDisposition.ADOPT, success = goal to 2)
                } else {
                    goalChoice(GoalDisposition.CONTINUE)
                }
            val next = "c${(ring.indexOf(held) + 1) % ring.size}"
            val decision = decisionFor(perception(requestId = requestId, agentId = alpha.value), Intent.Craft(next, 1))
            val outcome = ledger.resolve(alpha, decision.copy(goal = choice))?.outcome
            assertTrue(outcome in setOf(GoalEvidence.ADOPTED, GoalEvidence.CONTINUED), outcome)
            ledger.stepped(alpha, requestId, EventTag.CRAFT, EventTag.ACCEPTED)
            return carried?.progress
        }
        at("r1", ring[0])
        // Once round: each input is one the goal never held.
        for ((i, item) in ring.drop(1).withIndex()) {
            assertEquals(StepEvidence.GAINED_INPUT, at("r${i + 2}", item)?.stepEvidence)
        }
        // Round again: no more of any than the goal has held, so the lap stalls.
        val back = at("r5", ring[0])
        assertEquals(StepEvidence.NONE, back?.stepEvidence)
        assertEquals(1L, back?.stepsWithoutProgress)
        val again = at("r6", ring[1])
        assertEquals(StepEvidence.NONE, again?.stepEvidence)
        assertEquals(2L, again?.stepsWithoutProgress)
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
