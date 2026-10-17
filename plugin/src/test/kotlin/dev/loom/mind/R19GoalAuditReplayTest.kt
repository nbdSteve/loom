package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.body.tagOf
import dev.loom.contract.Bearing
import dev.loom.contract.BlockYield
import dev.loom.contract.EquipmentDestination
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalStepResult
import dev.loom.contract.GoalSummary
import dev.loom.contract.Intent
import dev.loom.contract.Observation
import dev.loom.contract.ObservationKind
import dev.loom.contract.StepEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    private fun block(
        ref: String,
        what: String,
        vararg yields: BlockYield,
    ) = Observation(
        kind = ObservationKind.BLOCK,
        what = what,
        distance = 2.0,
        direction = Bearing.NORTH,
        verticalOffset = 0.0,
        visible = true,
        ref = ref,
        standable = true,
        yields = yields.toList(),
        digSafe = true,
    )

    @Test
    fun `epsilon's eleven leaves are counted at their sampled mean, which makes no stick goal`() {
        // epsilon body:00000166 replaced a five-stick goal at capacity 10: eleven leaves in sight
        // with sticks nought to two each counted one apiece. 33 r19 leaf digs dropped one stick.
        val leaves = (1..11).map { block("l$it", "block.minecraft.birch_leaves", BlockYield(STICK, 0, 2, 3)) }
        val logs = (1..3).map { block("o$it", "block.minecraft.oak_log", BlockYield(LOG, 1, 1, 100)) }
        val seen = perception(requestId = "epsilon-166", agentId = "epsilon").copy(surroundings = leaves + logs)
        val observed = GoalObservation.of(seen)
        assertNull(observed.capacity[STICK], "33 hundredths of a stick is no stick: ${observed.capacity}")
        assertEquals(3L, observed.capacity[LOG])
        val epsilon = AgentId("epsilon")
        ledger.present(epsilon, "epsilon-166", 166, observed)
        val leaf = decisionFor(perception(requestId = "epsilon-166", agentId = "epsilon"), Intent.Dig("l1"))
        assertEquals(
            GoalEvidence.SUCCESS_UNOBTAINABLE,
            ledger.resolve(epsilon, leaf.copy(goal = target(GoalDisposition.ADOPT, STICK, 5)))?.outcome,
        )
        ledger.present(epsilon, "epsilon-167", 167, observed)
        val log = decisionFor(perception(requestId = "epsilon-167", agentId = "epsilon"), Intent.Dig("o1"))
        assertEquals(
            GoalEvidence.ADOPTED,
            ledger.resolve(epsilon, log.copy(goal = target(GoalDisposition.ADOPT, LOG, 3)))?.outcome,
        )
    }

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
        review("alpha-100", tree, target(GoalDisposition.ADOPT, LOG, 1), Intent.Dig("log"))
        val held = Scene(inventory = mapOf(LOG to 1L), capacity = mapOf(LOG to 1L, PLANKS to 4L), gives = tree.gives)
        val (met, replaced) =
            review("alpha-140", held, target(GoalDisposition.REPLACE, PLANKS, 4), Intent.Craft("planks", 1))
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
    fun `eta and zeta completing beside a filler equip with a source in sight is refused`() {
        // eta body:00000268 met one plank with four and equipped; zeta body:00000513 met twelve
        // dirt and equipped, with ten more in sight.
        val tree = mapOf("log" to setOf(LOG), "planks" to setOf(PLANKS))
        review(
            "eta-230",
            Scene(capacity = mapOf(PLANKS to 4L), gives = tree),
            target(GoalDisposition.ADOPT, PLANKS, 1),
            Intent.Craft("planks", 1),
        )
        val met = Scene(inventory = mapOf(PLANKS to 4L), capacity = mapOf(LOG to 2L), gives = tree)
        val equip = Intent.Equip("i30", EquipmentDestination.HAND)
        val (_, filler) =
            review("eta-268", met, GoalChoice(GoalDisposition.COMPLETE, stepResult = GoalStepResult.DONE), equip)
        assertEquals(GoalEvidence.COMPLETED_WITHOUT_NEXT, filler?.outcome)
        // An equip is no step of a goal either.
        review(
            "zeta-400",
            Scene(capacity = mapOf(DIRT to 22L), gives = mapOf("g" to setOf(DIRT))),
            target(GoalDisposition.ADOPT, DIRT, 12),
            Intent.Dig("g"),
        )
        val (_, equipped) =
            review(
                "zeta-450",
                Scene(capacity = mapOf(DIRT to 22L), gives = mapOf("g" to setOf(DIRT))),
                judged(GoalDisposition.CONTINUE),
                equip,
            )
        assertEquals(GoalEvidence.STEP_UNPRODUCTIVE, equipped?.outcome)
        // With nothing in sight a plain completion stands.
        val bare = Scene(inventory = mapOf(PLANKS to 4L), capacity = emptyMap(), gives = emptyMap())
        val (_, completed) =
            review("eta-269", bare, GoalChoice(GoalDisposition.COMPLETE, stepResult = GoalStepResult.DONE), Intent.Idle)
        assertEquals(GoalEvidence.COMPLETED, completed?.outcome)
    }

    @Test
    fun `alpha's revise that restated four sticks is refused, a changed total stands`() {
        // alpha body:00000237 and 00000328 revised four sticks to four sticks.
        review("alpha-200", Scene(), target(GoalDisposition.ADOPT, STICK, 4), Intent.Dig("bush"))
        val (_, restated) = review("alpha-237", Scene(), target(GoalDisposition.REVISE, STICK, 4), Intent.Dig("bush"))
        assertEquals(GoalEvidence.REVISE_UNCHANGED, restated?.outcome)
        val (_, cut) = review("alpha-328", Scene(), target(GoalDisposition.REVISE, STICK, 3), Intent.Dig("bush"))
        assertEquals(GoalEvidence.REVISED, cut?.outcome)
    }

    @Test
    fun `delta's own stick lying when it replaced its goal owes a review from the reserve`() {
        // delta body:00000254 replaced a stick goal with its own stick lying, and no review came.
        val lying = Scene(ownDrops = mapOf("d1" to STICK), gives = mapOf("d1" to setOf(STICK), "bush" to setOf(STICK)))
        review("delta-200", Scene(), target(GoalDisposition.ADOPT, STICK, 2), Intent.Dig("bush"))
        val (_, replaced) =
            review("delta-254", lying, target(GoalDisposition.REPLACE, STICK, 3), Intent.Collect("d1", approach = true))
        assertEquals(true, replaced?.ownGoalDropLying)
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

    private companion object {
        const val STICK = "item.minecraft.stick"
        const val LOG = "item.minecraft.oak_log"
        const val PLANKS = "item.minecraft.oak_planks"
        const val SAPLING = "item.minecraft.oak_sapling"
        const val DIRT = "item.minecraft.dirt"
    }
}
