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

    @Test
    fun `every damage call and pivotal sighting r24 refused to keep calls for reviews is admitted today`() {
        // The run's own perceptions, in order, through today's reserve under its bounds; what the
        // run coalesced never reached the reserve and is passed over.
        val dead = mutableSetOf<dev.loom.body.AgentId>()
        val reserve =
            CallReserve(
                perAgentCeiling = 24,
                scheduledPerAgent = 5,
                globalCeiling = 150,
                agents = 10,
                isDead = { it in dead },
            )
        val endings =
            r24.all
                .filter { it["event"]?.jsonPrimitive?.content in setOf("decided", "cognitionUnavailable") }
                .associate { keyOf(it) to it["outcome"]?.jsonPrimitive?.content }
        val admitted = mutableMapOf<String, Boolean>()
        for (record in r24.all) {
            when (record["event"]?.jsonPrimitive?.content) {
                "died" -> dead += dev.loom.body.AgentId(requireNotNull(record["agentId"]).jsonPrimitive.content)
                "perceived" -> {
                    val key = keyOf(record)
                    val sent = key in endings && endings[key] !in setOf("dangerCoalesced", "cognitionCoalesced")
                    if (sent) {
                        val seen = r24.perception(idOf(record))
                        admitted[idOf(record)] = reserve.admits(dev.loom.body.AgentId(seen.agentId), seen)
                    }
                }
            }
        }
        // iota's damage at health 6, theta's at health 1 and epsilon's pivotal sighting at 3.8 were
        // refused to keep calls for reviews; today each is admitted.
        for (id in listOf("body:00003287", "body:00003291", "body:00003305")) assertEquals(true, admitted[id], id)
        // delta's three and zeta's last come after the run's own 150-call ceiling is spent, which
        // no reserve can lift: they are refused by the ceiling itself, not kept for reviews.
        for (id in listOf("body:00003462", "body:00003470", "body:00003501", "body:00003564")) {
            val before = admitted.entries.takeWhile { it.key != id }.count { it.value }
            assertEquals(false, admitted[id], id)
            assertEquals(150, before, id)
        }
    }
}
