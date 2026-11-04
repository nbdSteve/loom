package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.body.tagOf
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalReason
import dev.loom.contract.GoalSummary
import dev.loom.contract.Intent
import dev.loom.contract.IntentAction
import dev.loom.contract.StepEvidence
import dev.loom.contract.targetRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The independent r11 goal-chain audit, replayed through the real ledger.
 *
 * Each case uses the review positions, inventories, intents and steps that one cited agent's
 * records carry in `research/experiments/2026-10-16-openai-10-agent-10m-r11/evidence.jsonl`; the
 * record ids are the audit's. Under r11's rules every one of them passed as progress or as a
 * coherent choice. Under the typed goal each is refused, measured as no progress, or - for the
 * two honest chains - accepted. From routine-v23 a step is typed from its decision's own intent,
 * so the misnamed steps the audit cited can no longer be stated at all: each case shows the step
 * the ledger holds is the intent's own. From routine-v36 a goal one step meets is no objective, so
 * the r11 goal of one stick is replayed at two, the fewest an objective may ask, and every abandon
 * states the reason its evidence supports.
 */
class R11GoalAuditReplayTest {
    private val ledger = GoalLedger()

    /**
     * One goal review, named `agent-record` after the cited agent and its decision record: present,
     * decide [intent] under [choice], and let the body accept it unless the ledger refused it.
     */
    private fun review(
        review: String,
        scene: Scene,
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
                // Sticks were obtainable throughout r11 and r13: dead bushes yielding them were in
                // sight. The step's own target is in sight, as every cited one was.
                GoalObservation(
                    scene.inventory,
                    scene.at,
                    capacity = scene.capacity,
                    visible = setOfNotNull(intent.targetRef),
                    dropped = scene.dropped,
                    // The cited walks were to bushes, sources of the goal's sticks.
                    gives = listOfNotNull(intent.targetRef).associateWith { setOf(STICK) },
                ),
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
        val walk = intent is Intent.MoveTo || intent is Intent.Flee
        ledger.stepped(agentId, requestId, tagOf(intent), applied, reached = if (walk) scene.arrived else null)
        return carried to evidence
    }

    private fun adopt() = goalChoice(GoalDisposition.ADOPT)

    /**
     * r11's revises restated the held total, which from routine-v28 is refused as
     * `goalReviseUnchanged`; the cases about what a step showed take each as the continue it was.
     */
    private fun onward() = goalChoice(GoalDisposition.CONTINUE)

    /** The step the ledger holds for [agent] after its last review: its action and ref. */
    private fun heldStep(agent: String): Pair<IntentAction?, String?> {
        val held = ledger.present(AgentId(agent), "$agent-probe", 1, null)
        ledger.forget(AgentId(agent), "$agent-probe")
        return held?.stepAction to held?.stepRef
    }

    @Test
    fun `alpha shuffling between two bushes is no progress and its step is its intent's own`() {
        val none = emptyMap<String, Long>()
        review("alpha-23", Scene(listOf(12.5, 105.0, 0.5), none), adopt(), Intent.MoveTo("b5n"))
        val walks =
            listOf(
                "72" to listOf(14.5, 102.0, 2.69),
                "112" to listOf(14.5, 101.0, 3.52),
                "158" to listOf(14.5, 102.0, 2.5),
            )
        val counts =
            walks.map { (record, at) ->
                review("alpha-$record", Scene(at, none), onward(), Intent.MoveTo("b$record"))
                    .first
                    ?.progress
                    ?.stepsWithoutProgress
            }
        // r11 credited all three: every walk moved a block. Only the first two reach a new cell.
        assertEquals(listOf(0L, 0L, 1L), counts)
        // body:00000233: the step named bh8s while the intent walked to bh90. The step is now the
        // intent's own, so the goal holds the walk to bh90.
        val (back, revised) =
            review("alpha-233", Scene(listOf(14.5, 101.0, 3.54), none), onward(), Intent.MoveTo("bh90"))
        assertEquals(2L, back?.progress?.stepsWithoutProgress)
        assertEquals(GoalEvidence.CONTINUED, revised?.outcome)
        assertEquals(IntentAction.MOVE_TO to "bh90", heldStep("alpha"))
    }

    @Test
    fun `gamma's step is its walk's own and its honest abandon stands`() {
        val start = listOf(4.5, 102.0, 11.5)
        // body:00000048: the step named b140 while the intent walked to b142; the goal now holds b142.
        review("gamma-48", Scene(start, emptyMap()), adopt(), Intent.MoveTo("b142"))
        assertEquals(IntentAction.MOVE_TO to "b142", heldStep("gamma"))
        review(
            "gamma-90",
            Scene(start, emptyMap()),
            goalChoice(GoalDisposition.ABANDON, reason = GoalReason.IMPOSSIBLE),
            Intent.Idle,
        )
        review("gamma-91", Scene(start, emptyMap()), adopt(), Intent.MoveTo("b5k3"))
        // body:00000252: nothing left to dig and nothing dropped, so gamma abandons.
        val (_, abandoned) =
            review(
                "gamma-252",
                Scene(listOf(5.5, 102.0, 11.5), emptyMap(), capacity = emptyMap()),
                goalChoice(GoalDisposition.ABANDON, reason = GoalReason.IMPOSSIBLE),
                Intent.Idle,
            )
        assertEquals(GoalEvidence.ABANDONED, abandoned?.outcome)
    }

    @Test
    fun `iota returning to its first cell is not credited as a walk`() {
        val none = emptyMap<String, Long>()
        review("iota-53", Scene(listOf(4.5, 111.0, -10.5), none), adopt(), Intent.MoveTo("b13w"))
        review("iota-96", Scene(listOf(3.5, 111.0, -10.5), none), onward(), Intent.MoveTo("b537"))
        review("iota-136", Scene(listOf(4.5, 111.0, -9.5), none), onward(), Intent.MoveTo("bde7"))
        // body:00000255: back at the cell of body:00000096.
        val (returned, _) =
            review(
                "iota-255",
                Scene(listOf(3.5, 111.0, -10.5), none),
                onward(),
                Intent.MoveTo("bhgq"),
            )
        assertEquals(false, returned?.progress?.stepAdvanced)
        assertEquals(1L, returned?.progress?.stepsWithoutProgress)
    }

    @Test
    fun `kappa's dig, collect and complete is the coherent chain, and a movement condition is no goal`() {
        val at = listOf(10.5, 108.0, -6.5)
        review("kappa-58", Scene(at, emptyMap()), adopt(), Intent.Dig("b1gh"))
        val (dug, _) =
            review(
                "kappa-100",
                // The server attributed the stick d3b to kappa-58's own dig of b1gh.
                Scene(at, emptyMap(), dropped = setOf("kappa-58")),
                onward(),
                Intent.Collect("d3b", approach = true),
            )
        assertEquals(StepEvidence.DROPPED, dug?.progress?.stepEvidence)
        // body:00000139: the collected stick is held, and body:00000140 completes.
        val (met, completed) =
            review(
                "kappa-140",
                Scene(listOf(13.3, 107.0, -6.5), mapOf(STICK to 2L), capacity = emptyMap()),
                goalChoice(GoalDisposition.COMPLETE),
                Intent.Idle,
            )
        assertEquals(true, met?.progress?.conditionAdvanced)
        assertEquals(true, met?.progress?.conditionMet)
        assertEquals(GoalEvidence.COMPLETED, completed?.outcome)
        // body:00000193: "my position changes to stand near bewu" types as no held item at all.
        val positional =
            goalChoice(GoalDisposition.ADOPT)
                .copy(successItem = null, successCount = null)
        val (_, refused) =
            review(
                "kappa-193",
                Scene(listOf(13.3, 107.0, -6.5), mapOf(STICK to 2L)),
                positional,
                Intent.MoveTo("bewu"),
            )
        assertEquals(GoalEvidence.SUCCESS_TARGET_MISSING, refused?.outcome)
    }

    @Test
    fun `r12 theta, standing in the bush its step named, may dig it`() {
        val at = listOf(-3.5, 108.0, -10.5)
        // body:00000062 of r12 named the bush b1mo underfoot while walking to b1mq.
        // The coherent decision the v21 perception points it at: dig the bush where it stands.
        val (_, adopted) = review("theta-63", Scene(at, emptyMap()), adopt(), Intent.Dig("b1mo"))
        assertEquals(GoalEvidence.ADOPTED, adopted?.outcome)
        val (dug, _) =
            review("theta-64", Scene(at, mapOf(STICK to 2L)), goalChoice(GoalDisposition.COMPLETE), Intent.Idle)
        assertEquals(true, dug?.progress?.stepAdvanced)
        assertEquals(true, dug?.progress?.conditionMet)
    }

    /**
     * What one review saw: where the agent stood, what it held, how many more of each item what was
     * in sight could give (the r11 scenes' bushes could give sticks), the requests whose dig the
     * server saw drop something, and whether a walk arrived.
     */
    private data class Scene(
        val at: List<Double>,
        val inventory: Map<String, Long> = emptyMap(),
        val capacity: Map<String, Long> = mapOf(STICK to 8L),
        val dropped: Set<String> = emptySet(),
        val arrived: Boolean = true,
    )

    private companion object {
        const val STICK = "item.minecraft.stick"
    }
}
