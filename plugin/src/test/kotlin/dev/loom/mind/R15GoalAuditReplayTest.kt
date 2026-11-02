package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.body.tagOf
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalRefusal
import dev.loom.contract.GoalSummary
import dev.loom.contract.Intent
import dev.loom.contract.ObservationKind
import dev.loom.contract.StepEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `iota's own drops, never collected, reach the stall bound instead of resetting it`() {
        // iota body:00000138, 144, 197, 203: three digs the server saw drop a stick for iota, none
        // of them ever held. Each was credited and reset the count, so the bound could not fire.
        review("iota-138", Scene(), sticks(GoalDisposition.ADOPT, 4), dig)
        val reviews =
            listOf("iota-144" to "iota-138", "iota-197" to "iota-144", "iota-203" to "iota-197").map { (review, dug) ->
                val (carried, resolved) =
                    review(review, Scene(dropped = setOf(dug)), goalChoice(GoalDisposition.CONTINUE), dig)
                assertEquals(StepEvidence.DROPPED, carried?.progress?.stepEvidence, review)
                carried?.progress?.stepsWithoutProgress to resolved?.outcome
            }
        assertEquals(
            listOf(
                1L to GoalEvidence.CONTINUED,
                2L to GoalEvidence.CONTINUED,
                3L to GoalEvidence.STALLED,
            ),
            reviews,
        )
    }

    @Test
    fun `a stalled answer is refused with its intent and the agent is told why`() {
        // beta body:00000225: the revise at the bound was stalled, and its dig still ran.
        assertTrue(GoalEvidence.STALLED in GoalEvidence.REFUSING)
        review("beta-28", Scene(), sticks(GoalDisposition.ADOPT, 2), dig)
        for (review in listOf("beta-41", "beta-110")) {
            review(review, Scene(), goalChoice(GoalDisposition.CONTINUE), dig)
        }
        val (_, stalled) = review("beta-225", Scene(), sticks(GoalDisposition.REVISE, 2), dig)
        assertEquals(GoalEvidence.STALLED, stalled?.outcome)
        assertEquals(GoalEvidence.STALLED, ledger.takeRefusal(AgentId("beta"))?.outcome)
    }

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
    fun `a decision that acts while ignoring the goal it holds is refused`() {
        // kappa body:00000253: a null goal choice beside a dig, which was never measured.
        review("kappa-248", Scene(inventory = mapOf(STICK to 4L)), sticks(GoalDisposition.ADOPT, 6), dig)
        val (_, refused) = review("kappa-253", Scene(inventory = mapOf(STICK to 4L)), null, dig)
        assertEquals(GoalEvidence.CHOICE_MISSING, refused?.outcome)
        assertTrue(GoalEvidence.CHOICE_MISSING in GoalEvidence.REFUSING)
    }

    @Test
    fun `a dig or collect that cannot give the goal's item is no step toward it`() {
        // gamma body:00000069 and iota body:00000079 dug terracotta, which drops nothing by hand,
        // as the steps of stick goals.
        val terracotta = Scene(gives = mapOf("bush" to setOf(STICK), "b19a" to emptySet(), "d1" to setOf(SEEDS)))
        val (_, unproductive) = review("gamma-69", terracotta, sticks(GoalDisposition.ADOPT, 2), Intent.Dig("b19a"))
        assertEquals(GoalEvidence.STEP_UNPRODUCTIVE, unproductive?.outcome)
        val (_, seeds) = review("gamma-70", terracotta, sticks(GoalDisposition.ADOPT, 2), Intent.Collect("d1", true))
        assertEquals(GoalEvidence.STEP_UNPRODUCTIVE, seeds?.outcome)
        val (_, bush) = review("gamma-71", terracotta, sticks(GoalDisposition.ADOPT, 2), dig)
        assertEquals(GoalEvidence.ADOPTED, bush?.outcome)
    }

    @Test
    fun `a revise is held to obtainability, the refusal is told with its numbers, and a continue steps on`() {
        // theta body:00000263: held 3 of 8 sticks with sight able to give 4; the revise keeping the
        // held target was refused while a continue of it would have passed.
        val scene = Scene(inventory = mapOf(STICK to 3L), capacity = mapOf(STICK to 4L))
        review(
            "theta-155",
            Scene(inventory = mapOf(STICK to 2L), capacity = mapOf(STICK to 8L)),
            sticks(GoalDisposition.ADOPT, 8),
            dig,
        )
        val (_, revised) = review("theta-263", scene, sticks(GoalDisposition.REVISE, 8), dig)
        assertEquals(GoalEvidence.SUCCESS_UNOBTAINABLE, revised?.outcome)
        assertEquals(
            GoalRefusal("theta-263", GoalEvidence.SUCCESS_UNOBTAINABLE, STICK, asked = 8, held = 3, capacity = 4),
            ledger.takeRefusal(AgentId("theta")),
        )
        // Told once.
        assertNull(ledger.takeRefusal(AgentId("theta")))
        // From routine-v27 a continue keeps the target it set and is held to a productive step and
        // the stall bound instead (the path rehearsal: a goal one log short was refused the
        // collect of the agent's own lying log), so the dig toward it stands.
        val (_, continued) = review("theta-295", scene, goalChoice(GoalDisposition.CONTINUE), dig)
        assertEquals(GoalEvidence.CONTINUED, continued?.outcome)
        // A revise to a total sight can still give stands.
        val (_, lowered) = review("theta-296", scene, sticks(GoalDisposition.REVISE, 7), dig)
        assertEquals(GoalEvidence.REVISED, lowered?.outcome)
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
