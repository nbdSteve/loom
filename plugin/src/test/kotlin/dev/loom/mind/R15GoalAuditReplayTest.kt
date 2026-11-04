package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.body.tagOf
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalSummary
import dev.loom.contract.Intent
import dev.loom.contract.ObservationKind
import dev.loom.contract.StepEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The independent r15 goal-chain audit, replayed through the real ledger.
 *
 * Each case uses the inventory, intent, success target and what was in sight that one cited
 * agent's records carry in `research/experiments/2026-10-16-openai-10-agent-10m-r15/evidence.jsonl`;
 * the record ids are the audit's. Under r15's rules each passed: drops credited as progress until
 * the stall bound could never fire, one step measured and credited again at every review that took
 * no new one, a dig beside a null choice, digs of terracotta as the steps of stick goals, a
 * stalled answer that still dug, a continue the obtainability rule never reached, and refusals the
 * mind was never told of.
 */
class R15GoalAuditReplayTest {
    private val ledger = GoalLedger()

    /** What one review saw: held items, what sight could give, own drops, and what each ref gives. */
    private data class Scene(
        val inventory: Map<String, Long> = emptyMap(),
        val capacity: Map<String, Long> = mapOf(STICK to 8L),
        val dropped: Set<String> = emptySet(),
        val gives: Map<String, Set<String>> = mapOf("bush" to setOf(STICK)),
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
                listOf(0.5, 100.0, 0.5),
                capacity = scene.capacity,
                dropped = scene.dropped,
                gives = scene.gives,
            )
        val carried = ledger.present(agent, review, review.substringAfter('-').toLong(), observed)
        val decision = decisionFor(perception(requestId = review, agentId = agent.value), intent).copy(goal = choice)
        val evidence = ledger.resolve(agent, decision)
        val applied =
            if (evidence?.outcome in GoalEvidence.REFUSING) requireNotNull(evidence).outcome else EventTag.ACCEPTED
        ledger.stepped(agent, review, tagOf(intent), applied)
        return carried to evidence
    }

    private fun sticks(
        disposition: GoalDisposition,
        count: Long,
    ) = goalChoice(disposition, success = STICK to count)

    private val dig = Intent.Dig("bush")

    @Test
    fun `a step is measured once, so a review that took no new step neither re-credits nor recounts it`() {
        // kappa body:00000248 dug and dropped a stick; body:00000253 answered with no choice, and
        // body:00000291 credited body:00000248's drop a second time. Its goal of five is replayed at
        // six, two past the four it held (routine-v36).
        review("kappa-248", Scene(inventory = mapOf(STICK to 4L)), sticks(GoalDisposition.ADOPT, 6), dig)
        val scene = Scene(inventory = mapOf(STICK to 4L), dropped = setOf("kappa-248"))
        val (first, measured) = review("kappa-253", scene, null, Intent.Idle)
        assertEquals(StepEvidence.DROPPED, first?.progress?.stepEvidence)
        assertEquals(StepEvidence.DROPPED, measured?.stepEvidence)
        assertEquals(1L, measured?.stepsWithoutProgress)
        val (again, remeasured) = review("kappa-291", scene, goalChoice(GoalDisposition.CONTINUE), dig)
        // The presented progress is the one measurement, and the record states no second one.
        assertEquals(1L, again?.progress?.stepsWithoutProgress)
        assertNull(remeasured?.stepEvidence)
        assertNull(remeasured?.stepAdvanced)
        assertNull(remeasured?.stepsWithoutProgress)
        assertEquals(GoalEvidence.CONTINUED, remeasured?.outcome)
    }

    @Test
    fun `a goal's wording is its typed total, so the two cannot disagree`() {
        // epsilon body:00000184 revised to three while its statement still said eight sticks;
        // replayed at four, two past the two it held, since routine-v36 asks no less.
        review(
            "epsilon-120",
            Scene(inventory = mapOf(STICK to 1L), capacity = mapOf(STICK to 8L)),
            sticks(GoalDisposition.ADOPT, 8),
            dig,
        )
        review("epsilon-184", Scene(inventory = mapOf(STICK to 2L)), sticks(GoalDisposition.REVISE, 4), dig)
        val held = ledger.present(AgentId("epsilon"), "epsilon-231", 231, null)
        assertEquals("hold 4 item.minecraft.stick", held?.statement)
    }

    @Test
    fun `capacity counts a reachable drop out of sight and never one the body found no route to`() {
        // r15 beta's stick over the trench at body:00000030 stood in alpha's and gamma's capacity
        // after five noRoute refusals; iota's own drop body:00000201 lay out of sight, uncounted.
        val drop = { ref: String, distance: Double, visible: Boolean, refused: Boolean? ->
            STICK_BUSH.copy(
                kind = ObservationKind.DROPPED_ITEM,
                what = STICK,
                ref = ref,
                distance = distance,
                visible = visible,
                count = 1,
                standable = null,
                yields = null,
                digSafe = null,
                routeRefused = refused,
            )
        }
        val seen =
            perception().copy(
                surroundings =
                    listOf(
                        drop("d1", 5.4, false, null),
                        drop("d2", 2.8, true, true),
                        drop("d3", 40.0, true, null),
                    ),
            )
        val observed = GoalObservation.of(seen)
        assertEquals(mapOf(STICK to 1L), observed.capacity)
        assertEquals(setOf(STICK), observed.gives["d1"])
    }

    private companion object {
        const val STICK = "item.minecraft.stick"
        const val SEEDS = "item.minecraft.wheat_seeds"
    }
}
