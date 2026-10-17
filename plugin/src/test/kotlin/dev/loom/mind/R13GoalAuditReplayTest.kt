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
    ) = goalChoice(disposition, "gather sticks", success = success)

    @Test
    fun `alpha's four barren terracotta digs advance nothing and stall the goal`() {
        val at = listOf(14.5, 102.0, 2.5)
        // A stick goal, which is obtainable, so the chain itself is what is judged.
        review("alpha-23", at, Seen(), goal(GoalDisposition.ADOPT), Intent.Dig("b72"))
        val outcomes =
            listOf("110", "232", "452", "500").map { record ->
                review(
                    "alpha-$record",
                    at,
                    Seen(),
                    goal(GoalDisposition.REVISE),
                    Intent.Dig("b$record"),
                )
            }
        // Each dig broke its block and yielded nothing: no evidence, so the count climbs.
        assertEquals(listOf(1L, 2L, 3L), outcomes.take(3).map { it.first?.progress?.stepsWithoutProgress })
        assertEquals(listOf(StepEvidence.NONE), outcomes.take(3).map { it.first?.progress?.stepEvidence }.distinct())
        // body:00000452 and the review after: at the bound the revise is the stall.
        assertEquals(GoalEvidence.STALLED, outcomes[2].second?.outcome)
    }

    @Test
    fun `goals naming items no tool in reach can yield are refused`() {
        // alpha body:00000023: terracotta x16 with a bare hand, which drops nothing.
        val (_, terracotta) =
            review(
                "alpha-23",
                listOf(12.5, 105.0, 0.5),
                Seen(capacity = emptyMap()),
                goal(GoalDisposition.ADOPT, "item.minecraft.terracotta" to 16L),
                Intent.Dig("b1"),
            )
        assertEquals(GoalEvidence.SUCCESS_UNOBTAINABLE, terracotta?.outcome)
        // beta: an oak log, with no log in sight anywhere in the run.
        val (_, log) =
            review(
                "beta-28",
                listOf(10.5, 99.0, 7.5),
                Seen(),
                goal(GoalDisposition.ADOPT, "item.minecraft.oak_log" to 1L),
                Intent.MoveTo("bcf"),
            )
        assertEquals(GoalEvidence.SUCCESS_UNOBTAINABLE, log?.outcome)
        // iota: a dead bush item, which needs shears; the bush in sight yields only sticks.
        val (_, bush) =
            review(
                "iota-53",
                listOf(4.5, 111.0, -10.5),
                Seen(),
                goal(GoalDisposition.ADOPT, "item.minecraft.dead_bush" to 1L),
                Intent.MoveTo("b13w"),
            )
        assertEquals(GoalEvidence.SUCCESS_UNOBTAINABLE, bush?.outcome)
        // The stick those same bushes yield is a goal worth holding.
        val (_, stick) =
            review(
                "iota-54",
                listOf(4.5, 111.0, -10.5),
                Seen(),
                goal(GoalDisposition.ADOPT),
                Intent.MoveTo("b13w"),
            )
        assertEquals(GoalEvidence.ADOPTED, stick?.outcome)
    }

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
