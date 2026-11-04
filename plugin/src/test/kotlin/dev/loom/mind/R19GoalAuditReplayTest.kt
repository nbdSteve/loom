package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.body.tagOf
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalReason
import dev.loom.contract.GoalStepResult
import dev.loom.contract.GoalSummary
import dev.loom.contract.Intent
import dev.loom.contract.StepEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The independent r19 goal-chain audit, replayed through the real ledger and schedule.
 *
 * Each case uses the inventory, intent, success target and what was in sight that one cited
 * agent's records carry in `research/experiments/2026-10-16-openai-10-agent-10m-r19/evidence.jsonl`;
 * the record ids are the audit's. Under r19's rules each passed: leaves counted as a stick a dig,
 * gains of another item credited as the goal's, completions beside a filler equip, revises that
 * restated their total, and a goal that never built on an earlier one.
 */
class R19GoalAuditReplayTest {
    /** Planks are made from logs and sticks from planks, as the server's recipes say. */
    private val recipes =
        RecipeInputs { item ->
            when (item) {
                STICK -> setOf(PLANKS)
                PLANKS -> setOf(LOG)
                else -> emptySet()
            }
        }
    private val ledger = GoalLedger(recipes = recipes)

    private data class Scene(
        val inventory: Map<String, Long> = emptyMap(),
        val capacity: Map<String, Long> = mapOf(STICK to 4L),
        val gives: Map<String, Set<String>> = mapOf("bush" to setOf(STICK)),
        val ownDrops: Map<String, String> = emptyMap(),
    )

    private fun review(
        review: String,
        scene: Scene,
        choice: GoalChoice?,
        intent: Intent,
    ): Pair<GoalSummary?, GoalEvidence?> {
        val agent = AgentId(review.substringBefore('-'))
        val observed =
            GoalObservation(
                scene.inventory,
                listOf(0.5, 64.0, 0.5),
                capacity = scene.capacity,
                visible = scene.gives.keys,
                gives = scene.gives,
                ownDrops = scene.ownDrops,
                inReach = scene.gives.keys,
                // Every source the scene names lies in reach: a dig of it is a step offered now.
                stepSources = scene.gives,
            )
        val carried = ledger.present(agent, review, review.substringAfter('-').toLong(), observed)
        val decision = decisionFor(perception(requestId = review, agentId = agent.value), intent).copy(goal = choice)
        val evidence = ledger.resolve(agent, decision)
        val applied =
            if (evidence?.outcome in GoalEvidence.REFUSING) requireNotNull(evidence).outcome else EventTag.ACCEPTED
        ledger.stepped(agent, review, tagOf(intent), applied, null)
        return carried to evidence
    }

    private fun target(
        disposition: GoalDisposition,
        item: String,
        count: Long,
    ) = GoalChoice(
        disposition,
        successItem = item,
        successCount = count,
        stepResult = GoalStepResult.NO_PROGRESS.takeIf { disposition != GoalDisposition.ADOPT },
    )

    private fun judged(disposition: GoalDisposition) = GoalChoice(disposition, stepResult = GoalStepResult.NO_PROGRESS)

    @Test
    fun `delta's stick and apple and iota's sapling are no gain of the goal's item`() {
        // delta body:00000452 and 472 were credited `gained` for a stick and an apple toward a
        // sapling goal; iota body:00000285 for a sapling toward a stick goal.
        val leaf = mapOf("leaf" to setOf(SAPLING, STICK))
        review(
            "delta-430",
            Scene(capacity = mapOf(SAPLING to 2L), gives = leaf),
            target(GoalDisposition.ADOPT, SAPLING, 2),
            Intent.Dig("leaf"),
        )
        val stick = Scene(inventory = mapOf(STICK to 1L), capacity = mapOf(SAPLING to 2L), gives = leaf)
        val (carried, _) = review("delta-452", stick, judged(GoalDisposition.CONTINUE), Intent.Dig("leaf"))
        assertEquals(StepEvidence.NONE, carried?.progress?.stepEvidence)
        assertEquals(1L, carried?.progress?.stepsWithoutProgress)
        review("iota-270", Scene(gives = leaf), target(GoalDisposition.ADOPT, STICK, 3), Intent.Dig("leaf"))
        val sapling = Scene(inventory = mapOf(SAPLING to 1L), gives = leaf)
        val (iota, _) = review("iota-285", sapling, judged(GoalDisposition.CONTINUE), Intent.Dig("leaf"))
        assertEquals(StepEvidence.NONE, iota?.progress?.stepEvidence)
    }

    @Test
    fun `a log gained toward a planks goal is a recipe input, and the planks goal chains to it`() {
        // r19: no goal built on another goal's item. A log goal met, then planks from it.
        val tree = Scene(capacity = mapOf(LOG to 2L), gives = mapOf("log" to setOf(LOG)))
        review("alpha-100", tree, target(GoalDisposition.ADOPT, LOG, 2), Intent.Dig("log"))
        val held = Scene(inventory = mapOf(LOG to 2L), capacity = mapOf(PLANKS to 8L), gives = tree.gives)
        val (met, replaced) =
            review(
                "alpha-140",
                held,
                target(GoalDisposition.REPLACE, PLANKS, 4).copy(reason = GoalReason.COMPLETED),
                Intent.Craft("planks", 1),
            )
        assertEquals(true, met?.progress?.conditionMet)
        assertEquals(GoalEvidence.COMPLETED_ADOPTED, replaced?.outcome)
        assertEquals("alpha-100", replaced?.chainedFrom)
        // A planks goal whose next step digs a log gains an input of its recipe: typed, credited.
        review(
            "beta-100",
            tree.copy(capacity = mapOf(PLANKS to 4L)),
            target(GoalDisposition.ADOPT, PLANKS, 4),
            Intent.Dig("log"),
        )
        val logged = Scene(inventory = mapOf(LOG to 1L), capacity = mapOf(PLANKS to 4L), gives = tree.gives)
        val (input, _) = review("beta-140", logged, judged(GoalDisposition.CONTINUE), Intent.Dig("log"))
        assertEquals(StepEvidence.GAINED_INPUT, input?.progress?.stepEvidence)
        assertEquals(0L, input?.progress?.stepsWithoutProgress)
    }

    @Test
    fun `delta's own stick lying when it replaced its goal owes a review from the reserve`() {
        // delta body:00000254 replaced a stick goal with its own stick lying, and no review came.
        // From routine-v36 more of the same item is a revise, which records the drop the same way.
        val lying = Scene(ownDrops = mapOf("d1" to STICK), gives = mapOf("d1" to setOf(STICK), "bush" to setOf(STICK)))
        review("delta-200", Scene(), target(GoalDisposition.ADOPT, STICK, 2), Intent.Dig("bush"))
        val (_, revised) =
            review("delta-254", lying, target(GoalDisposition.REVISE, STICK, 3), Intent.Collect("d1", approach = true))
        assertEquals(GoalEvidence.REVISED, revised?.outcome)
        assertEquals(true, revised?.ownGoalDropLying)
        // The schedule keeps the last earned follow-up for an owed review.
        val schedule = GoalReviewSchedule()
        val delta = AgentId("delta")
        schedule.claim(setOf(delta), 0)
        schedule.settled(delta, 100, owed = false)
        assertEquals(listOf(delta), schedule.claimFollowUps(setOf(delta), 100 + FOLLOW_UP_DELAY_TICKS))
        schedule.settled(delta, 200, owed = false)
        assertEquals(emptyList<AgentId>(), schedule.claimFollowUps(setOf(delta), 200 + FOLLOW_UP_DELAY_TICKS))
        schedule.settled(delta, 300, owed = true)
        assertEquals(listOf(delta), schedule.claimFollowUps(setOf(delta), 300 + FOLLOW_UP_DELAY_TICKS))
    }

    @Test
    fun `a schedule earning one follow-up a review keeps it for an owed review`() {
        // r20: 50 reviews and 100 follow-ups planned against a ceiling of 150 left nothing for
        // 49 danger calls; a night scene's plan earns one follow-up a review.
        val schedule = GoalReviewSchedule(followUpsPerReview = 1)
        val delta = AgentId("delta")
        schedule.claim(setOf(delta), 0)
        schedule.settled(delta, 100, owed = false)
        assertEquals(emptyList<AgentId>(), schedule.claimFollowUps(setOf(delta), 100 + FOLLOW_UP_DELAY_TICKS))
        schedule.settled(delta, 200, owed = true)
        assertEquals(listOf(delta), schedule.claimFollowUps(setOf(delta), 200 + FOLLOW_UP_DELAY_TICKS))
        schedule.settled(delta, 300, owed = true)
        assertEquals(emptyList<AgentId>(), schedule.claimFollowUps(setOf(delta), 300 + FOLLOW_UP_DELAY_TICKS))
    }

    private companion object {
        const val STICK = "item.minecraft.stick"
        const val LOG = "item.minecraft.oak_log"
        const val PLANKS = "item.minecraft.oak_planks"
        const val SAPLING = "item.minecraft.oak_sapling"
        const val DIRT = "item.minecraft.dirt"
    }
}
