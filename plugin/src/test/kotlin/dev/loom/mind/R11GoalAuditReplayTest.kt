package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.body.tagOf
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalSummary
import dev.loom.contract.Intent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The independent r11 goal-chain audit, replayed through the real ledger.
 *
 * Each case uses the review positions, inventories, intents and steps that one cited agent's
 * records carry in `research/experiments/2026-10-16-openai-10-agent-10m-r11/evidence.jsonl`; the
 * record ids are the audit's. Under r11's rules every one of them passed as progress or as a
 * coherent choice. Under the typed goal each is refused, measured as no progress, or - for the
 * two honest chains - accepted.
 */
class R11GoalAuditReplayTest {
    private val ledger = GoalLedger()

    /**
     * One goal review, named `agent-record` after the cited agent and its decision record: present,
     * decide [intent] under [choice], and let the body accept it unless the ledger refused it.
     */
    private fun review(
        review: String,
        at: List<Double>,
        inventory: Map<String, Long>,
        choice: GoalChoice?,
        intent: Intent,
    ): Pair<GoalSummary?, GoalEvidence?> {
        val agent = review.substringBefore('-')
        val agentId = AgentId(agent)
        val requestId = review
        val carried =
            ledger.present(
                agentId,
                requestId,
                review.substringAfter('-').toLong(),
                GoalObservation(inventory, at),
            )
        val decision = decisionFor(perception(requestId = requestId, agentId = agent), intent).copy(goal = choice)
        val evidence = ledger.resolve(agentId, decision)
        val applied =
            if (evidence?.outcome in GoalEvidence.REFUSING) {
                requireNotNull(
                    evidence,
                ).outcome
            } else {
                EventTag.ACCEPTED
            }
        ledger.stepped(agentId, requestId, tagOf(intent), applied)
        return carried to evidence
    }

    private fun adopt(intent: Intent) = goalChoice(GoalDisposition.ADOPT, "gather a stick", intent = intent)

    private fun revise(intent: Intent) = goalChoice(GoalDisposition.REVISE, "gather a stick", intent = intent)

    /** A choice whose typed step names [stepped] while the decision's intent is something else. */
    private fun misstep(
        disposition: GoalDisposition,
        stepped: Intent,
    ) = goalChoice(disposition, "gather a stick", intent = stepped)

    @Test
    fun `alpha shuffling between two bushes is no progress and its misnamed step is refused`() {
        val none = emptyMap<String, Long>()
        review("alpha-23", listOf(12.5, 105.0, 0.5), none, adopt(Intent.MoveTo("b5n")), Intent.MoveTo("b5n"))
        val walks =
            listOf(
                "72" to listOf(14.5, 102.0, 2.69),
                "112" to listOf(14.5, 101.0, 3.52),
                "158" to listOf(14.5, 102.0, 2.5),
            )
        val counts =
            walks.map { (record, at) ->
                review("alpha-$record", at, none, revise(Intent.MoveTo("b$record")), Intent.MoveTo("b$record"))
                    .first
                    ?.progress
                    ?.stepsWithoutProgress
            }
        // r11 credited all three: every walk moved a block. Only the first two reach a new cell.
        assertEquals(listOf(0L, 0L, 1L), counts)
        // body:00000233: the step names bh8s, the intent walks to bh90.
        val (back, refused) =
            review(
                "alpha-233",
                listOf(14.5, 101.0, 3.54),
                none,
                misstep(GoalDisposition.REVISE, Intent.MoveTo("bh8s")),
                Intent.MoveTo("bh90"),
            )
        assertEquals(2L, back?.progress?.stepsWithoutProgress)
        assertEquals(GoalEvidence.STEP_MISMATCH, refused?.outcome)
    }

    @Test
    fun `beta's return walks reach the stall bound and its misnamed step is refused`() {
        val none = emptyMap<String, Long>()
        val positions =
            listOf(
                "28" to listOf(10.5, 99.0, 7.5),
                "76" to listOf(12.5, 99.0, 6.48),
                "116" to listOf(10.5, 100.0, 5.49),
                "162" to listOf(12.5, 99.0, 6.5),
                "237" to listOf(10.5, 100.0, 5.49),
                "999" to listOf(12.5, 99.0, 6.5),
            )
        val outcomes =
            positions.mapIndexed { index, (record, at) ->
                val walk = Intent.MoveTo("b$record")
                val choice = if (index == 0) adopt(walk) else revise(walk)
                review("beta-$record", at, none, choice, walk).second?.outcome
            }
        // Three returns in a row advance nothing, so the revise at the third is the stall.
        assertEquals(GoalEvidence.STALLED, outcomes.last())
        // body:00000237: the step names biaq5, the intent walks to bib5.
        val fresh = GoalLedger()
        val betaId = AgentId("beta")
        fresh.present(betaId, "a", 1, GoalObservation(emptyMap(), listOf(10.5, 100.0, 5.49)))
        val decision =
            decisionFor(perception(requestId = "a", agentId = "beta"), Intent.MoveTo("bib5"))
                .copy(goal = misstep(GoalDisposition.ADOPT, Intent.MoveTo("biaq5")))
        assertEquals(GoalEvidence.STEP_MISMATCH, fresh.resolve(betaId, decision)?.outcome)
    }

    @Test
    fun `delta holding its stick must complete, and a step naming a collect cannot idle`() {
        val at = listOf(-5.5, 110.0, 8.5)
        review("delta-33", listOf(-3.5, 110.0, 11.5), emptyMap(), adopt(Intent.MoveTo("bkv")), Intent.MoveTo("bkv"))
        review("delta-80", at, emptyMap(), revise(Intent.Dig("b500")), Intent.Dig("b500"))
        // body:00000119: the stick is held; delta revised to the same target and dug on.
        val (met, ignored) =
            review(
                "delta-120",
                at,
                mapOf(STICK to 1L),
                revise(Intent.Dig("b9hd")),
                Intent.Dig("b9hd"),
            )
        assertEquals(true, met?.progress?.conditionMet)
        assertEquals(1L, met?.progress?.conditionHeld)
        assertEquals(GoalEvidence.CONDITION_MET_IGNORED, ignored?.outcome)
        // body:00000241: the step says collect the dropped stick d5r; the intent is idle.
        val mismatch = goalChoice(GoalDisposition.CONTINUE, intent = Intent.Collect("d5r", approach = true))
        val fresh = GoalLedger()
        val deltaId = AgentId("delta")
        fresh.present(deltaId, "a", 1, GoalObservation(emptyMap(), at))
        fresh.resolve(
            deltaId,
            decisionFor(perception(requestId = "a", agentId = "delta")).copy(goal = adopt(Intent.Idle)),
        )
        fresh.present(deltaId, "b", 2, GoalObservation(emptyMap(), at))
        val idle = decisionFor(perception(requestId = "b", agentId = "delta"), Intent.Idle).copy(goal = mismatch)
        assertEquals(GoalEvidence.STEP_MISMATCH, fresh.resolve(deltaId, idle)?.outcome)
        // The completion it owed is accepted.
        val (_, completed) =
            review("delta-166", at, mapOf(STICK to 1L), goalChoice(GoalDisposition.COMPLETE), Intent.Idle)
        assertEquals(GoalEvidence.COMPLETED, completed?.outcome)
    }

    @Test
    fun `epsilon and zeta cannot carry a met condition on by continuing or rewording it`() {
        val epsilon = listOf(-10.5, 112.0, 7.5)
        review("epsilon-38", listOf(-9.5, 111.0, 7.5), emptyMap(), adopt(Intent.MoveTo("bpe")), Intent.MoveTo("bpe"))
        review("epsilon-84", epsilon, emptyMap(), revise(Intent.Dig("b56r")), Intent.Dig("b56r"))
        // body:00000121: the stick is held and epsilon continued.
        val (_, continued) =
            review(
                "epsilon-122",
                epsilon,
                mapOf(STICK to 1L),
                goalChoice(GoalDisposition.CONTINUE, intent = Intent.Dig("b9ob")),
                Intent.Dig("b9ob"),
            )
        assertEquals(GoalEvidence.CONDITION_MET_IGNORED, continued?.outcome)

        val zeta = listOf(-12.5, 111.0, 1.5)
        review("zeta-68", listOf(-11.5, 110.0, 0.5), emptyMap(), adopt(Intent.MoveTo("b1tl")), Intent.MoveTo("b1tl"))
        review("zeta-108", zeta, emptyMap(), revise(Intent.Dig("b6c0")), Intent.Dig("b6c0"))
        // body:00000147: the stick is held and zeta revised to "obtain sticks" again.
        val (_, reworded) =
            review(
                "zeta-148",
                zeta,
                mapOf(STICK to 1L),
                revise(Intent.Dig("basn")),
                Intent.Dig("basn"),
            )
        assertEquals(GoalEvidence.CONDITION_MET_IGNORED, reworded?.outcome)
        // A revise to a target not yet held is a new objective, and stands.
        val (_, raised) =
            review(
                "zeta-211",
                zeta,
                mapOf(STICK to 1L),
                goalChoice(
                    GoalDisposition.REVISE,
                    "gather four sticks",
                    intent = Intent.Dig("bfbd"),
                    success = STICK to 4L,
                ),
                Intent.Dig("bfbd"),
            )
        assertEquals(GoalEvidence.REVISED, raised?.outcome)
    }

    @Test
    fun `eta's fall after digging the block underfoot credits the dig, not the fall`() {
        val none = emptyMap<String, Long>()
        review("eta-43", listOf(-9.5, 110.0, -6.5), none, adopt(Intent.Idle), Intent.Idle)
        // A fall of one block under an idle step is no progress.
        val (fell, _) =
            review(
                "eta-88",
                listOf(-9.5, 109.0, -6.5),
                none,
                revise(Intent.Dig("b5eo")),
                Intent.Dig("b5eo"),
            )
        assertEquals(false, fell?.progress?.stepAdvanced)
        assertEquals(1L, fell?.progress?.stepsWithoutProgress)
        // body:00000127: the dig of b5eo was carried out; that, not the fall, is the step's evidence.
        val (dug, _) = review("eta-128", listOf(-10.5, 109.0, -6.5), none, revise(Intent.Idle), Intent.Idle)
        assertEquals(true, dug?.progress?.stepAdvanced)
        assertEquals(false, dug?.progress?.conditionAdvanced)
    }

    @Test
    fun `gamma's misnamed bush is refused and its honest abandon stands`() {
        val start = listOf(4.5, 102.0, 11.5)
        // body:00000048: the step names b140, the intent walks to b142.
        val (_, refused) =
            review(
                "gamma-48",
                start,
                emptyMap(),
                misstep(GoalDisposition.ADOPT, Intent.MoveTo("b140")),
                Intent.MoveTo("b142"),
            )
        assertEquals(GoalEvidence.STEP_MISMATCH, refused?.outcome)
        review("gamma-90", start, emptyMap(), adopt(Intent.MoveTo("b5k3")), Intent.MoveTo("b5k3"))
        // body:00000252: nothing left to dig and nothing dropped, so gamma abandons.
        val (_, abandoned) =
            review(
                "gamma-252",
                listOf(5.5, 102.0, 11.5),
                emptyMap(),
                goalChoice(GoalDisposition.ABANDON),
                Intent.Idle,
            )
        assertEquals(GoalEvidence.ABANDONED, abandoned?.outcome)
    }

    @Test
    fun `iota returning to its first cell is not credited as a walk`() {
        val none = emptyMap<String, Long>()
        review("iota-53", listOf(4.5, 111.0, -10.5), none, adopt(Intent.MoveTo("b13w")), Intent.MoveTo("b13w"))
        review("iota-96", listOf(3.5, 111.0, -10.5), none, revise(Intent.MoveTo("b537")), Intent.MoveTo("b537"))
        review("iota-136", listOf(4.5, 111.0, -9.5), none, revise(Intent.MoveTo("bde7")), Intent.MoveTo("bde7"))
        // body:00000255: back at the cell of body:00000096.
        val (returned, _) =
            review("iota-255", listOf(3.5, 111.0, -10.5), none, revise(Intent.MoveTo("bhgq")), Intent.MoveTo("bhgq"))
        assertEquals(false, returned?.progress?.stepAdvanced)
        assertEquals(1L, returned?.progress?.stepsWithoutProgress)
    }

    @Test
    fun `kappa's dig, collect and complete is the coherent chain, and a movement condition is no goal`() {
        val at = listOf(10.5, 108.0, -6.5)
        review("kappa-58", at, emptyMap(), adopt(Intent.Dig("b1gh")), Intent.Dig("b1gh"))
        val (dug, _) =
            review(
                "kappa-100",
                at,
                emptyMap(),
                revise(Intent.Collect("d3b", approach = true)),
                Intent.Collect("d3b", approach = true),
            )
        assertEquals(true, dug?.progress?.stepAdvanced)
        // body:00000139: the collected stick is held, and body:00000140 completes.
        val (met, completed) =
            review(
                "kappa-140",
                listOf(13.3, 107.0, -6.5),
                mapOf(STICK to 1L),
                goalChoice(GoalDisposition.COMPLETE),
                Intent.Idle,
            )
        assertEquals(true, met?.progress?.conditionAdvanced)
        assertEquals(true, met?.progress?.conditionMet)
        assertEquals(GoalEvidence.COMPLETED, completed?.outcome)
        // body:00000193: "my position changes to stand near bewu" types as no held item at all.
        val positional =
            goalChoice(GoalDisposition.ADOPT, "stand near bewu", intent = Intent.MoveTo("bewu"))
                .copy(successItem = null, successCount = null)
        val (_, refused) =
            review(
                "kappa-193",
                listOf(13.3, 107.0, -6.5),
                mapOf(STICK to 1L),
                positional,
                Intent.MoveTo("bewu"),
            )
        assertEquals(GoalEvidence.SUCCESS_TARGET_MISSING, refused?.outcome)
    }

    @Test
    fun `theta's misnamed bushes are refused and five walks without a dig reach the bound`() {
        // body:00000063 and body:00000144: steps name b1mo and balc, intents walk to b1mq and balh.
        for ((stepped, walked) in listOf("b1mo" to "b1mq", "balc" to "balh")) {
            val fresh = GoalLedger()
            val thetaId = AgentId("theta")
            fresh.present(thetaId, "a", 1, GoalObservation(emptyMap(), listOf(-3.5, 108.0, -10.5)))
            val decision =
                decisionFor(perception(requestId = "a", agentId = "theta"), Intent.MoveTo(walked))
                    .copy(goal = misstep(GoalDisposition.ADOPT, Intent.MoveTo(stepped)))
            assertEquals(GoalEvidence.STEP_MISMATCH, fresh.resolve(thetaId, decision)?.outcome, stepped)
        }
        val none = emptyMap<String, Long>()
        val cells =
            listOf(
                listOf(-3.5, 108.0, -10.5),
                listOf(-4.5, 109.0, -10.5),
                listOf(-4.5, 109.0, -9.67),
                listOf(-3.69, 109.0, -5.59),
                listOf(-3.53, 109.0, -5.5),
                listOf(-4.5, 109.0, -10.5),
                listOf(-3.69, 109.0, -5.59),
            )
        val outcomes =
            cells.mapIndexed { index, at ->
                val walk = Intent.MoveTo("b$index")
                review(
                    "theta-${index + 1}",
                    at,
                    none,
                    if (index == 0) adopt(walk) else revise(walk),
                    walk,
                ).second?.outcome
            }
        assertEquals(GoalEvidence.STALLED, outcomes.last())
        assertNull(ledger.present(AgentId("theta"), "theta-end", 99, GoalObservation(none, cells.last())))
    }

    private companion object {
        const val STICK = "item.minecraft.stick"
    }
}
