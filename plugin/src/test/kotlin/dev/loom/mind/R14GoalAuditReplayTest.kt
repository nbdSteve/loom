package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.body.tagOf
import dev.loom.contract.AIR_ITEM
import dev.loom.contract.BlockYield
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalSummary
import dev.loom.contract.Intent
import dev.loom.contract.IntentAction
import dev.loom.contract.LoomJson
import dev.loom.contract.StepEvidence
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The independent r14 goal-chain audit, replayed through the real ledger and contract.
 *
 * Each case uses the inventory, intent, success item and what was in sight that one cited
 * agent's records carry in `research/experiments/2026-10-16-openai-10-agent-10m-r14/evidence.jsonl`;
 * the record ids are the audit's. Under r14's rules each passed: three completions of goals met
 * when they were adopted, a credit for another agent's drop, a step naming what its intent did
 * not do, a goal raised with nothing in sight to give it, and air listed as what a bush drops.
 */
class R14GoalAuditReplayTest {
    private val ledger = GoalLedger()

    /** What one review saw beyond the agent's place: held items, what sight could give, own drops. */
    private data class Scene(
        val inventory: Map<String, Long> = emptyMap(),
        val capacity: Map<String, Long> = emptyMap(),
        val dropped: Set<String> = emptySet(),
    )

    private fun review(
        review: String,
        scene: Scene,
        choice: GoalChoice?,
        intent: Intent,
        outcome: String = EventTag.ACCEPTED,
    ): Pair<GoalSummary?, GoalEvidence?> {
        val agent = AgentId(review.substringBefore('-'))
        val observed =
            GoalObservation(
                scene.inventory,
                listOf(0.5, 100.0, 0.5),
                capacity = scene.capacity,
                dropped = scene.dropped,
            )
        val carried = ledger.present(agent, review, review.substringAfter('-').toLong(), observed)
        val decision = decisionFor(perception(requestId = review, agentId = agent.value), intent).copy(goal = choice)
        val evidence = ledger.resolve(agent, decision)
        val applied = if (evidence?.outcome in GoalEvidence.REFUSING) requireNotNull(evidence).outcome else outcome
        ledger.stepped(agent, review, tagOf(intent), applied)
        return carried to evidence
    }

    private fun sticks(
        disposition: GoalDisposition,
        count: Long,
    ) = goalChoice(disposition, success = STICK to count)

    @Test
    fun `three goals met when they were adopted are refused, not completed`() {
        // theta body:00000174, delta body:00000187 and eta body:00000197: each held two sticks
        // and adopted a goal of one, then "completed" it at the next review.
        val holding = Scene(inventory = mapOf(STICK to 2L), capacity = mapOf(STICK to 2L))
        for ((review, intent) in listOf(
            "theta-174" to Intent.Dig("bakm"),
            "delta-187" to Intent.MoveTo("be00"),
            "eta-197" to Intent.Idle,
        )) {
            val (_, refused) = review(review, holding, sticks(GoalDisposition.ADOPT, 1), intent)
            assertEquals(GoalEvidence.ALREADY_MET, refused?.outcome, review)
        }
        // eta's idle step is refused on its own too, for a goal not yet met.
        val (_, idled) =
            review(
                "eta-198",
                Scene(capacity = mapOf(STICK to 2L)),
                sticks(GoalDisposition.ADOPT, 1),
                Intent.Idle,
            )
        assertEquals(GoalEvidence.STEP_IDLE, idled?.outcome)
    }

    @Test
    fun `a condition is the typed item and count, and words claiming another are no part of a choice`() {
        // delta body:00000187: the words said a log, the typed item was the stick it already held.
        val stated =
            """{"disposition":"adopt","statement":"gather wood","successCondition":"1 log",""" +
                """"successItem":"item.minecraft.stick","successCount":1,"step":"walk"}"""
        assertThrows(SerializationException::class.java) { LoomJson.decodeFromString(GoalChoice.serializer(), stated) }
    }

    @Test
    fun `a step is the intent's own, so gamma's walk is held as the walk it took`() {
        // gamma body:00000164: the step named the dropped stick d4a while the intent walked to b9ur.
        val claimed =
            """{"disposition":"adopt","statement":"collect","successItem":"item.minecraft.stick",""" +
                """"successCount":2,"step":"walk","stepAction":"moveTo","stepRef":"d4a"}"""
        assertThrows(SerializationException::class.java) { LoomJson.decodeFromString(GoalChoice.serializer(), claimed) }
        val dropInSight = Scene(inventory = mapOf(STICK to 1L), capacity = mapOf(STICK to 1L))
        review("gamma-164", dropInSight, sticks(GoalDisposition.ADOPT, 2), Intent.MoveTo("b9ur"))
        val (held, _) = review("gamma-200", dropInSight, null, Intent.Collect("d7o", approach = true))
        assertEquals(IntentAction.MOVE_TO, held?.stepAction)
        assertEquals("b9ur", held?.stepRef)
    }

    @Test
    fun `a dig is credited for its own server drop and never for another agent's`() {
        // delta body:00000033 dug bkv, which dropped nothing; body:00000107 saw beta's two sticks
        // (d6, from beta's dig at body:00000105) 17.8 blocks off and was credited as dropped.
        review("delta-33", Scene(capacity = mapOf(STICK to 2L)), sticks(GoalDisposition.ADOPT, 1), Intent.Dig("bkv"))
        val (others, _) =
            review(
                "delta-107",
                Scene(capacity = mapOf(STICK to 2L), dropped = setOf("beta-103")),
                // delta's revise restated its total, which routine-v28 refuses: a continue.
                goalChoice(GoalDisposition.CONTINUE),
                Intent.Collect("d6", approach = true),
            )
        assertEquals(StepEvidence.NONE, others?.progress?.stepEvidence)
        assertEquals(1L, others?.progress?.stepsWithoutProgress)
        // Its collect of them is its own gain, and honest.
        val (collected, _) =
            review(
                "delta-154",
                Scene(inventory = mapOf(STICK to 2L)),
                goalChoice(GoalDisposition.COMPLETE),
                Intent.Idle,
            )
        assertEquals(StepEvidence.GAINED, collected?.progress?.stepEvidence)
        // beta body:00000103 dug b4v5, and the server dropped two sticks for it that delta took
        // before beta's next review at body:00000150; told of its own drop, beta's dig yielded.
        review("beta-28", Scene(capacity = mapOf(STICK to 2L)), sticks(GoalDisposition.ADOPT, 1), Intent.Dig("bdr"))
        review(
            "beta-103",
            Scene(capacity = mapOf(STICK to 2L)),
            goalChoice(GoalDisposition.CONTINUE),
            Intent.Dig("b4v5"),
        )
        val (yielded, _) =
            review("beta-150", Scene(dropped = setOf("beta-103")), goalChoice(GoalDisposition.ABANDON), Intent.Idle)
        assertEquals(StepEvidence.DROPPED, yielded?.progress?.stepEvidence)
        assertEquals(true, yielded?.progress?.stepAdvanced)
        // From routine-v24 a drop not yet held is no progress: it counts toward the stall bound
        // (r15 iota was credited five drops and never held a stick).
        assertEquals(2L, yielded?.progress?.stepsWithoutProgress)
    }

    @Test
    fun `a goal raised past what sight could give is refused`() {
        // kappa body:00000208 held one stick with a bush in sight and set two; at body:00000243 no
        // bush or drop was in sight and it revised to two again.
        review(
            "kappa-208",
            Scene(inventory = mapOf(STICK to 1L), capacity = mapOf(STICK to 2L)),
            sticks(GoalDisposition.ADOPT, 2),
            Intent.Dig("bcyg"),
        )
        val (_, refused) =
            review(
                "kappa-243",
                Scene(inventory = mapOf(STICK to 1L)),
                sticks(GoalDisposition.REVISE, 2),
                Intent.MoveTo("bgao"),
            )
        assertEquals(GoalEvidence.SUCCESS_UNOBTAINABLE, refused?.outcome)
    }

    @Test
    fun `air is no yield, so a bush can never list it`() {
        // All 110 dead-bush observations of r14 listed item.minecraft.air beside the stick.
        assertThrows(IllegalArgumentException::class.java) { BlockYield(AIR_ITEM, 0, 1, 50) }
        assertEquals(BlockYield(STICK, 0, 2, 100), BlockYield(STICK, 0, 2, 100))
    }

    private companion object {
        const val STICK = "item.minecraft.stick"
    }
}
