package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalProgress
import dev.loom.contract.GoalReason
import dev.loom.contract.GoalStepResult
import dev.loom.contract.GoalSummary
import dev.loom.contract.Intent
import dev.loom.contract.MAX_GOAL_STEPS_WITHOUT_PROGRESS
import dev.loom.contract.StepEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The held-objective rules of routine-v36 (`HeldObjectives.kt`), against the r39 cases that set
 * them: alpha and kappa replaced a met dirt goal by the same item one higher at every step, so no
 * advance ever landed on a goal that had advanced before; gamma abandoned shelter goals while
 * taking their own steps; and goals one dig met were set and completed at the next review.
 */
class HeldObjectivesTest {
    private fun held(
        count: Long,
        have: Long,
        stalled: Long = 0,
        item: String = DIRT,
    ) = GoalSummary(
        goalId = "g1",
        statement = statementOf(item, count),
        revision = 0,
        adoptedTick = 0,
        successItem = item,
        successCount = count,
        progress =
            GoalProgress(
                conditionMet = have >= count,
                conditionHeld = have,
                inventoryDelta = emptyList(),
                conditionAdvanced = false,
                stepAdvanced = false,
                stepEvidence = StepEvidence.NONE,
                stepsWithoutProgress = stalled,
            ),
    )

    private fun seen(
        have: Long,
        capacity: Long,
        lying: Long = 0,
    ) = GoalObservation(
        inventory = mapOf(DIRT to have),
        position = listOf(0.5, 64.0, 0.5),
        capacity = mapOf(DIRT to capacity, LOG to 4L),
        ownLying = if (lying > 0) mapOf(DIRT to lying) else emptyMap(),
        // Dirt to dig and a log beside it: a step toward either is offered now.
        stepSources = mapOf("b1" to setOf(DIRT), "b2" to setOf(LOG)),
    )

    private fun choice(
        disposition: GoalDisposition,
        item: String? = DIRT,
        count: Long? = null,
        reason: GoalReason? = null,
    ) = GoalChoice(
        disposition,
        successItem = item.takeIf { count != null },
        successCount = count,
        stepResult = GoalStepResult.PROGRESSING.takeIf { disposition != GoalDisposition.ADOPT },
        reason = reason,
    )

    @Test
    fun `a goal one step could meet is no objective, and two past what is held and lying is`() {
        // r39 alpha 10106: "hold 10 dirt" while holding nine, met by the next dig.
        val observed = seen(have = 9, capacity = 6)
        assertEquals(
            GoalEvidence.TRIVIAL,
            objectiveRefusalOf(null, choice(GoalDisposition.ADOPT, count = 10), observed),
        )
        assertNull(objectiveRefusalOf(null, choice(GoalDisposition.ADOPT, count = 11), observed))
        // The agent's own drop lying is its for one collect, so it counts as held.
        val lying = seen(have = 9, capacity = 6, lying = 1)
        assertEquals(GoalEvidence.TRIVIAL, objectiveRefusalOf(null, choice(GoalDisposition.ADOPT, count = 11), lying))
        assertNull(objectiveRefusalOf(null, choice(GoalDisposition.ADOPT, count = 12), lying))
        // A revise down to one step's worth is as trivial; one restating the held total is not, since
        // it sets nothing and is refused as unchanged.
        val presented = held(count = 14, have = 9)
        val down = choice(GoalDisposition.REVISE, count = 10)
        assertEquals(GoalEvidence.TRIVIAL, objectiveRefusalOf(presented, down, observed))
        assertNull(objectiveRefusalOf(presented, choice(GoalDisposition.REVISE, count = 14), observed))
    }

    @Test
    fun `a goal one craft, one dig at its most or one collect of a stack meets is no objective`() {
        // The r39 offline audit: alpha body:00000106 set "hold 2 oak_planks" holding a log, and the
        // craft paired with it made four; a dig of a bush at its most or a stack picked up is the same.
        val planks = "item.minecraft.oak_planks"
        val crafted = seen(have = 0, capacity = 6).copy(oneStep = mapOf(planks to 4L))
        assertEquals(5L, crafted.fewestObjective(planks))
        val adopt = { item: String, count: Long -> choice(GoalDisposition.ADOPT, item = item, count = count) }
        assertEquals(GoalEvidence.TRIVIAL, objectiveRefusalOf(null, adopt(planks, 4), crafted))
        assertNull(objectiveRefusalOf(null, adopt(planks, 5), crafted))
        // One step at its most counts past what is held, and the lying-drop floor still holds beside it.
        val dug = seen(have = 3, capacity = 6, lying = 2).copy(oneStep = mapOf(DIRT to 2L))
        assertEquals(3L + 2 + MIN_OBJECTIVE_ADVANCE, dug.fewestObjective(DIRT))
        val stack = seen(have = 3, capacity = 6).copy(oneStep = mapOf(DIRT to 5L))
        assertEquals(9L, stack.fewestObjective(DIRT))
        assertEquals(GoalEvidence.TRIVIAL, objectiveRefusalOf(null, adopt(DIRT, 8), stack))
    }

    @Test
    fun `the most one offered step gives is read from the perception the mind was shown`() {
        val bush =
            dev.loom.contract.Observation(
                kind = dev.loom.contract.ObservationKind.BLOCK,
                what = "block.minecraft.dead_bush",
                distance = 2.0,
                direction = dev.loom.contract.Bearing.NORTH,
                verticalOffset = 0.0,
                visible = true,
                ref = "b1",
                standable = true,
                yields = listOf(dev.loom.contract.BlockYield(STICK, 0, 2, 100)),
                digSafe = true,
            )
        val far = bush.copy(ref = "b2", distance = 9.0)
        val stack =
            dev.loom.contract.Observation(
                kind = dev.loom.contract.ObservationKind.DROPPED_ITEM,
                what = STICK,
                distance = 6.0,
                direction = dev.loom.contract.Bearing.EAST,
                verticalOffset = 0.0,
                visible = true,
                ref = "d1",
                count = 3,
            )
        val sticks = dev.loom.contract.CraftingOption("r1", STICK, 4, 2, false)
        val seen =
            perception().let {
                it.copy(
                    surroundings = listOf(bush, far, stack),
                    selfState = it.selfState.copy(craftingOptions = listOf(sticks)),
                )
            }
        // A dig in reach gives two at most, the stack three, and one craft of both applications eight;
        // a bush out of reach is a walk first, so no one step.
        assertEquals(mapOf(STICK to 8L), GoalObservation.of(seen).oneStep)
        assertEquals(
            mapOf(STICK to 3L),
            GoalObservation.of(seen.copy(selfState = seen.selfState.copy(craftingOptions = emptyList()))).oneStep,
        )
    }

    @Test
    fun `more of the held goal's own item is a revise, never a replace`() {
        // r39 alpha 10106 to 10574 and kappa 9511 to 10218: every met dirt goal replaced by one more.
        val met = held(count = 10, have = 10)
        val observed = seen(have = 10, capacity = 6)
        val same = choice(GoalDisposition.REPLACE, count = 12, reason = GoalReason.COMPLETED)
        assertEquals(GoalEvidence.REPLACE_SAME_ITEM, objectiveRefusalOf(met, same, observed))
        assertNull(objectiveRefusalOf(met, choice(GoalDisposition.REVISE, count = 12), observed))
        val other = choice(GoalDisposition.REPLACE, item = LOG, count = 2, reason = GoalReason.COMPLETED)
        assertNull(objectiveRefusalOf(met, other, observed))
    }

    @Test
    fun `a goal no step offered now advances may be abandoned as impossible, as the mind offers it`() {
        // The r39 offline audit: "hold 4 oak_log" with a recalled log out of sight and nowhere to
        // look further was offered only its abandon as impossible, which the ledger refused.
        val unmet = held(count = 4, have = 1, item = LOG)
        val abandon = choice(GoalDisposition.ABANDON, item = null, reason = GoalReason.IMPOSSIBLE)
        val recalled = seen(have = 0, capacity = 0).copy(inventory = mapOf(LOG to 1L), stepSources = emptyMap())
        assertEquals(true, recalled.steplessFor(LOG))
        assertNull(objectiveRefusalOf(unmet, abandon, recalled))
        // A cell at the edge of sight, a craft of it, or a dig of a source of it is a step.
        val looking = recalled.copy(explore = setOf("x1"))
        assertEquals(GoalEvidence.REASON_UNSUPPORTED, objectiveRefusalOf(unmet, abandon, looking))
        assertEquals(false, recalled.copy(craftable = setOf(LOG)).steplessFor(LOG))
        assertEquals(false, recalled.copy(stepSources = mapOf("b2" to setOf(LOG))).steplessFor(LOG))
    }

    @Test
    fun `a replace or an abandon states why, and only a reason its evidence supports stands`() {
        val observed = seen(have = 3, capacity = 6)
        val unmet = held(count = 6, have = 3)
        val met = held(count = 3, have = 3)
        val stalled = held(count = 6, have = 3, stalled = MAX_GOAL_STEPS_WITHOUT_PROGRESS)
        val replace = { reason: GoalReason? -> choice(GoalDisposition.REPLACE, item = LOG, count = 2, reason = reason) }
        val abandon = { reason: GoalReason? -> choice(GoalDisposition.ABANDON, item = null, reason = reason) }
        assertEquals(GoalEvidence.REASON_MISSING, objectiveRefusalOf(unmet, replace(null), observed))
        assertEquals(GoalEvidence.REASON_MISSING, objectiveRefusalOf(unmet, abandon(null), observed))
        val cases =
            listOf(
                // A met goal is completed; an unmet one is not.
                Triple(met, replace(GoalReason.COMPLETED), true),
                Triple(unmet, replace(GoalReason.COMPLETED), false),
                // A different objective chosen deliberately supersedes an unmet goal, never a met one.
                Triple(unmet, replace(GoalReason.SUPERSEDED), true),
                Triple(met, replace(GoalReason.SUPERSEDED), false),
                // Stale only at the stall bound.
                Triple(unmet, replace(GoalReason.STALE), false),
                Triple(stalled, replace(GoalReason.STALE), true),
                Triple(stalled, abandon(GoalReason.STALE), true),
                // Impossible only once what is held and sight could give no longer reach it, or no
                // step offered now advances it.
                Triple(unmet, abandon(GoalReason.IMPOSSIBLE), false),
                Triple(held(count = 12, have = 3), abandon(GoalReason.IMPOSSIBLE), true),
                // Unsafe and superseded name what replaces the goal, so no abandon states them.
                Triple(unmet, abandon(GoalReason.UNSAFE), false),
                Triple(unmet, abandon(GoalReason.SUPERSEDED), false),
                // Unsafe is a shelter set in place of another goal, and an item goal is no shelter.
                Triple(unmet, replace(GoalReason.UNSAFE), false),
            )
        for ((presented, ending, supported) in cases) {
            val refusal = objectiveRefusalOf(presented, ending, observed)
            val expected = if (supported) null else GoalEvidence.REASON_UNSUPPORTED
            assertEquals(expected, refusal, "${ending.disposition} ${ending.reason} of ${presented.statement}")
        }
    }

    @Test
    fun `a shelter set in place of an item goal as night nears is unsafe, and only while one is offered`() {
        val unmet = held(count = 6, have = 3)
        val cover =
            CoverObservation(CoverRank(sealed = false, closed = 0, closable = 0), walkOut = true, descend = "b1")
        val dusk = seen(have = 3, capacity = 6).copy(cover = cover.copy(nightNear = true))
        val shelter = GoalChoice(GoalDisposition.REPLACE, shelter = true, stepResult = GoalStepResult.PROGRESSING)
        assertNull(objectiveRefusalOf(unmet, shelter.copy(reason = GoalReason.UNSAFE), dusk))
        val noon = seen(have = 3, capacity = 6).copy(cover = cover)
        assertEquals(
            GoalEvidence.REASON_UNSUPPORTED,
            objectiveRefusalOf(unmet, shelter.copy(reason = GoalReason.UNSAFE), noon),
        )
    }

    @Test
    fun `a met goal revised for more keeps its identity and the advances it made`() {
        val ledger = GoalLedger()
        val agent = AgentId("agent-kappa")

        fun review(
            requestId: String,
            have: Long,
            choice: GoalChoice,
        ): Pair<GoalSummary?, GoalEvidence?> {
            val observed = seen(have = have, capacity = 8)
            val carried = ledger.present(agent, requestId, requestId.drop(1).toLong() * 100, observed)
            val decision = decisionFor(perception(requestId = requestId, agentId = agent.value), Intent.Dig("b1"))
            val evidence = ledger.resolve(agent, decision.copy(goal = choice))
            ledger.stepped(agent, requestId, EventTag.DIG, EventTag.ACCEPTED)
            return carried to evidence
        }
        assertEquals(GoalEvidence.ADOPTED, review("r1", 0, choice(GoalDisposition.ADOPT, count = 2)).second?.outcome)
        review("r2", 1, choice(GoalDisposition.CONTINUE))
        val (met, revised) = review("r3", 2, choice(GoalDisposition.REVISE, count = 4))
        assertEquals(true, met?.progress?.conditionMet)
        assertEquals(GoalEvidence.REVISED, revised?.outcome)
        assertEquals("r1", revised?.goalId)
        val (carried, _) = review("r4", 3, choice(GoalDisposition.CONTINUE))
        assertEquals("r1", carried?.goalId)
        assertEquals(1L, carried?.revision)
        assertEquals(true, carried?.progress?.conditionAdvanced)
    }

    private companion object {
        const val DIRT = "item.minecraft.dirt"
        const val LOG = "item.minecraft.oak_log"
        const val STICK = "item.minecraft.stick"
    }
}
