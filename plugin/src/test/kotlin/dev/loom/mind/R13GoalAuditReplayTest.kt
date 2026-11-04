package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.body.tagOf
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalSummary
import dev.loom.contract.Intent
import dev.loom.contract.StepEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The independent r13 goal-chain audit, replayed through the real ledger.
 *
 * Each case uses the positions, inventories, intents, success items and what was in sight that
 * one cited agent's records carry in
 * `research/experiments/2026-10-16-openai-10-agent-10m-r13/evidence.jsonl`; the record ids are
 * the audit's. Under r13's rules each passed as progress or as a goal worth holding.
 */
class R13GoalAuditReplayTest {
    private val ledger = GoalLedger()

    /** What one review saw, beyond the agent's place. */
    private data class Seen(
        val inventory: Map<String, Long> = emptyMap(),
        val capacity: Map<String, Long> = mapOf(STICK to 8L),
        val visible: Set<String> = emptySet(),
        val arrived: Boolean = true,
    )

    private fun review(
        review: String,
        at: List<Double>,
        seen: Seen,
        choice: GoalChoice?,
        intent: Intent,
    ): Pair<GoalSummary?, GoalEvidence?> {
        val agent = AgentId(review.substringBefore('-'))
        val observed =
            GoalObservation(
                seen.inventory,
                at,
                capacity = seen.capacity,
                visible = seen.visible,
                // Every block in sight here is a bush, a source of the goal's sticks.
                gives = seen.visible.associateWith { setOf(STICK) },
            )
        val carried = ledger.present(agent, review, review.substringAfter('-').toLong(), observed)
        val decision = decisionFor(perception(requestId = review, agentId = agent.value), intent).copy(goal = choice)
        val evidence = ledger.resolve(agent, decision)
        val refused = evidence?.outcome in GoalEvidence.REFUSING
        val walk = intent is Intent.MoveTo || intent is Intent.Flee
        ledger.stepped(
            agent,
            review,
            tagOf(intent),
            if (refused) requireNotNull(evidence).outcome else EventTag.ACCEPTED,
            reached = if (walk) seen.arrived else null,
        )
        return carried to evidence
    }

    private fun goal(
        disposition: GoalDisposition,
        success: Pair<String, Long> = STICK to 4L,
    ) = goalChoice(disposition, success = success)

    @Test
    fun `a block name is no item, and the contract refuses it before the ledger sees it`() {
        // eta body:00000043 and epsilon body:00000084 named block.minecraft.terracotta and
        // block.minecraft.dead_bush, which no inventory stack can ever be.
        for (name in listOf("block.minecraft.terracotta", "block.minecraft.dead_bush")) {
            assertThrows(IllegalArgumentException::class.java) {
                goal(GoalDisposition.ADOPT, name to 1L)
            }
        }
    }

    @Test
    fun `a walk counts only when it reached the exact target its step named in sight`() {
        val none = Seen()
        // gamma body:00000128, 284 and 495: short walks with no bush or drop in view.
        review(
            "gamma-128",
            listOf(4.5, 102.0, 11.5),
            none,
            goal(GoalDisposition.ADOPT),
            Intent.MoveTo("beip"),
        )
        val (unseen, _) =
            review(
                "gamma-284",
                listOf(5.5, 102.0, 11.5),
                none,
                goal(GoalDisposition.REVISE),
                Intent.MoveTo("bf1"),
            )
        assertEquals(StepEvidence.NONE, unseen?.progress?.stepEvidence)
        // A walk to a target in sight that the body reported not reaching is no arrival either.
        val inSight = Seen(visible = setOf("bf2"), arrived = false)
        review(
            "gamma-285",
            listOf(5.5, 102.0, 11.5),
            inSight,
            goal(GoalDisposition.REVISE),
            Intent.MoveTo("bf2"),
        )
        val (short, _) =
            review(
                "gamma-495",
                listOf(6.5, 102.0, 11.5),
                none,
                goal(GoalDisposition.REVISE),
                Intent.MoveTo("bf3"),
            )
        assertEquals(StepEvidence.NONE, short?.progress?.stepEvidence)
        // delta body:00000118: a search walk to no bush in view, under a goal of three sticks.
        review(
            "delta-80",
            listOf(-5.5, 110.0, 8.5),
            none,
            goal(GoalDisposition.ADOPT),
            Intent.MoveTo("b9"),
        )
        val (search, _) =
            review(
                "delta-118",
                listOf(-7.5, 110.0, 8.5),
                none,
                goal(GoalDisposition.REVISE),
                Intent.MoveTo("b10"),
            )
        assertEquals(StepEvidence.NONE, search?.progress?.stepEvidence)
    }

    @Test
    fun `a walk to a bush in sight that arrived somewhere new is evidence`() {
        val bush = Seen(visible = setOf("bush"))
        review(
            "kappa-58",
            listOf(10.5, 108.0, -6.5),
            bush,
            goal(GoalDisposition.ADOPT),
            Intent.MoveTo("bush"),
        )
        val (reached, _) =
            review(
                "kappa-99",
                listOf(12.5, 108.0, -6.5),
                Seen(),
                goal(GoalDisposition.REVISE),
                Intent.Idle,
            )
        assertEquals(StepEvidence.ARRIVED, reached?.progress?.stepEvidence)
    }

    private companion object {
        const val STICK = "item.minecraft.stick"
    }
}
