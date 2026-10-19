package dev.loom.mind

import dev.loom.contract.Intent
import dev.loom.replay.CommittedEvidence
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The independent r24 audit's cited records, replayed through today's ledger, danger episodes and
 * survival rules from `research/experiments/2026-10-16-openai-10-agent-10m-r24/evidence.jsonl`.
 */
class R24GoalAuditReplayTest {
    private val r24 = CommittedEvidence("2026-10-16-openai-10-agent-10m-r24")

    private fun keyOf(record: JsonObject) = record["requestKey"]?.jsonPrimitive?.content

    private fun recordFor(
        event: String,
        key: String,
    ): JsonObject = r24.all.first { it["event"]?.jsonPrimitive?.content == event && keyOf(it) == key }

    private fun idOf(record: JsonObject) = requireNotNull(record["recordId"]).jsonPrimitive.content

    @Test
    fun `theta's walk past thirteen-percent grass at body 78 is a step the ledger now takes`() {
        val key = "tribe-theta:loom9bc6579ca4-sense-3g"
        val decided = recordFor("decided", key)
        val seen = r24.perception(idOf(recordFor("perceived", key))).copy(horizon = r24.shownHorizon(idOf(decided)))
        val held = requireNotNull(r24.shownGoal(idOf(decided)))
        val observed = GoalObservation.of(seen)
        assertTrue(
            observed.productive.values.none { "item.minecraft.wheat_seeds" in it },
            "grass gives a seed one dig in eight: ${observed.productive}",
        )
        val walk = Intent.MoveTo("x1")
        val continuing =
            dev.loom.contract.GoalChoice(
                dev.loom.contract.GoalDisposition.CONTINUE,
                stepResult = dev.loom.contract.GoalStepResult.NO_PROGRESS,
            )
        assertNull(incoherenceOf(held, continuing, walk, observed))
    }

    @Test
    fun `eta's review at health 4_5 beside a skeleton at body 3450 is pivotal and takes only a survival response`() {
        val decided = r24.record("body:00003450")
        val key = requireNotNull(keyOf(decided))
        val seen = r24.perception(idOf(recordFor("perceived", key)))
        assertEquals(dev.loom.contract.DecisionImportance.PIVOTAL, seen.classified().importance)
        assertTrue(SurvivalOffer.of(seen).withholds(Intent.Craft("r1", 1)), "its craft is withheld")
    }

    @Test
    fun `gamma's damage at health 0_7 at body 2000 is told once the answer it waited behind settles`() {
        // The real coalescing outcome, and today's: held, then delivered on settlement.
        assertEquals("dangerCoalesced", r24.record("body:00002000")["outcome"]?.jsonPrimitive?.content)
        var now = 0L
        val episodes = DangerEpisodes { now }
        val gamma = dev.loom.body.AgentId("tribe-gamma")
        val flight =
            perception(
                requestId = "flight",
                agentId = gamma.value,
                trigger = dev.loom.contract.Trigger.Damaged(3, "spider"),
            )
        assertTrue(episodes.admit(gamma, flight, "spider-1"))
        val bite =
            perception(
                requestId = "bite",
                agentId = gamma.value,
                trigger = dev.loom.contract.Trigger.Damaged(2, "spider"),
            )
        assertEquals(false, episodes.admit(gamma, bite, "spider-1"))
        now += 40
        assertEquals(dev.loom.contract.Trigger.Damaged(2, "spider"), episodes.settled(gamma, "flight"))
        // And a second spider is news of its own, not the first one's episode.
        assertTrue(episodes.admit(gamma, bite.copy(requestId = "other"), "spider-2"))
    }
}
