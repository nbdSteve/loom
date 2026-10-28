package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.GoalEvidence
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalReason
import dev.loom.contract.GoalStepResult
import dev.loom.contract.Intent
import dev.loom.contract.Perception
import dev.loom.replay.CommittedEvidence
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The independent r23 audit, replayed through the real ledger and danger episodes.
 *
 * Every case reads the exact perception a cited record carries in
 * `research/experiments/2026-10-16-openai-10-agent-10m-r23/evidence.jsonl`, with the horizon the
 * matching `decided` record says its mind was shown, and asks today's rules what they make of it.
 */
class R23GoalAuditReplayTest {
    private val r23 = CommittedEvidence.R23

    /** Planks from logs and sticks from planks, the server registry's sole-input recipes the cases cross. */
    private val recipes =
        object : RecipeInputs {
            override fun inputsOf(item: String): Set<String> =
                mapOf(PLANKS to setOf(LOG), STICK to setOf(PLANKS))[item].orEmpty()

            override fun madeFrom(input: String): List<String> =
                mapOf(LOG to listOf(PLANKS), PLANKS to listOf(STICK))[input].orEmpty()
        }

    /** The perception [perceived] carries, with the horizon [decided] says its mind was shown. */
    private fun shown(
        perceived: String,
        decided: String,
    ): Perception = r23.perception(perceived).copy(horizon = r23.shownHorizon(decided))

    private fun GoalLedger.answer(
        perception: Perception,
        intent: Intent,
        choice: GoalChoice?,
    ): GoalEvidence? {
        val agent = AgentId(perception.agentId)
        present(agent, perception.requestId, perception.capturedTick, GoalObservation.of(perception))
        return resolve(agent, decisionFor(perception, intent).copy(goal = choice))
    }

    @Test
    fun `epsilon's empty horizon at body 36 sets a discovery goal walked to the edge of sight`() {
        // r23: 17 of 70 reviews could set no goal; epsilon walked to x1 with none (body:00000038).
        val seen = shown("body:00000036", "body:00000038")
        assertTrue(seen.horizon.isEmpty(), "the mind was shown no horizon")
        val observed = GoalObservation.of(seen)
        assertTrue(observed.discoveryOffered)
        val adopted =
            GoalLedger(
                recipes = recipes,
            ).answer(seen, Intent.MoveTo("x1"), GoalChoice(GoalDisposition.ADOPT, discover = true))
        assertEquals(GoalEvidence.ADOPTED, adopted?.outcome)
        assertEquals(true, adopted?.discovery)
    }

    @Test
    fun `epsilon's walk past two logs in dig reach at body 88 is no step of its log goal`() {
        // body:00000089 adopted one oak log and walked to x2 with b57m and b57r 3.4 and 3.2 off;
        // replayed at the two logs in reach, since from routine-v36 one dig's log is no objective.
        val seen = shown("body:00000088", "body:00000089")
        val adopt = GoalChoice(GoalDisposition.ADOPT, successItem = LOG, successCount = 2)
        val ledger = GoalLedger(recipes = recipes)
        assertEquals(GoalEvidence.STEP_UNPRODUCTIVE, ledger.answer(seen, Intent.MoveTo("x2"), adopt)?.outcome)
        assertEquals(GoalEvidence.ADOPTED, ledger.answer(seen, Intent.Dig("b57r"), adopt)?.outcome)
    }

    @Test
    fun `eta's eight planks from its own logs at body 1532 are a switch its held goal may make`() {
        // body:00001542 abandoned its unmet sapling goal to craft planks from its two logs, with no
        // goal held after, because only a stalled goal could be replaced.
        val seen = shown("body:00001532", "body:00001542")
        val held = requireNotNull(r23.shownGoal("body:00001542"))
        assertEquals(SAPLING, held.successItem)
        assertTrue(seen.selfState.inventorySummary.any { it.what == LOG && it.count == 2L })
        val craft = Intent.Craft(seen.selfState.craftingOptions.single { it.what == PLANKS }.ref, 2)
        val switch =
            GoalChoice(
                GoalDisposition.REPLACE,
                successItem = PLANKS,
                successCount = 8,
                stepResult = GoalStepResult.NO_PROGRESS,
                reason = GoalReason.SUPERSEDED,
            )
        assertEquals(null, incoherenceOf(held, switch, craft, GoalObservation.of(seen), recipes))
        // Ending the unmet sapling goal says why: the switch is a different objective, chosen.
        assertEquals(
            GoalEvidence.REASON_MISSING,
            incoherenceOf(held, switch.copy(reason = null), craft, GoalObservation.of(seen), recipes),
        )
    }

    @Test
    fun `gamma's skeleton beside it at body 1869 is asked about, not held behind a zombie being fled`() {
        // r23 coalesced it, 6.4 blocks off, behind the zombie e2v6 episode opened at body:00001830.
        var now = 0L
        val episodes = DangerEpisodes { now }
        val gamma = AgentId("tribe-gamma")
        val admitted = mutableMapOf<String, Boolean>()
        for (record in r23.all.filter { it.agentOf() == "tribe-gamma" && it.tickOf() in GAMMA_NIGHT }) {
            now = record.tickOf()
            when (record.eventOf()) {
                "perceived" -> {
                    val perception = r23.perception(record.idOf())
                    if (dangerKeyOf(perception.trigger) != null) {
                        admitted[record.idOf()] = episodes.admit(gamma, perception)
                    }
                }
                "decided" ->
                    episodes.settled(
                        gamma,
                        requireNotNull(record["requestId"]).jsonPrimitive.content,
                        fled = record.intentOf() == "flee",
                    )
            }
        }
        assertEquals(true, admitted["body:00001869"], "a skeleton 6.4 blocks off")
        // Damage from the zombie already being fled is the same episode, but at 8.2 health it is at
        // or below LOW_HEALTH, so it is asked about rather than held (r27: low-health damage held
        // behind an older answer killed iota).
        assertEquals(true, admitted["body:00001831"])
    }

    private fun JsonObject.idOf(): String = requireNotNull(this["recordId"]).jsonPrimitive.content

    private fun JsonObject.agentOf(): String? = this["agentId"]?.jsonPrimitive?.content

    private fun JsonObject.eventOf(): String = requireNotNull(this["event"]).jsonPrimitive.content

    private fun JsonObject.tickOf(): Long = this["tick"]?.jsonPrimitive?.content?.toLong() ?: -1

    private fun JsonObject.intentOf(): String? = this["intent"]?.jsonPrimitive?.content

    private companion object {
        const val LOG = "item.minecraft.oak_log"
        const val PLANKS = "item.minecraft.oak_planks"
        const val STICK = "item.minecraft.stick"
        const val SAPLING = "item.minecraft.oak_sapling"

        /** From gamma's flight from a zombie at body:00001780 to its death at body:00001885. */
        val GAMMA_NIGHT = 8543L..8880L
    }
}
