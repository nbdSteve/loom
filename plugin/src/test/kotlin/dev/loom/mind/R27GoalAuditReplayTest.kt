package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.replay.CommittedEvidence
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * The independent r27 audit's cited records, replayed through today's rules from
 * `research/experiments/2026-10-16-openai-10-agent-10m-r27/evidence.jsonl`.
 */
class R27GoalAuditReplayTest {
    private val r27 = CommittedEvidence("2026-10-16-openai-10-agent-10m-r27")

    private fun field(
        record: JsonObject,
        name: String,
    ) = record[name]?.jsonPrimitive?.content

    /**
     * Whether, replaying [agent]'s flights in record order through [FlightHolds], a flight still
     * held the body when the review perception [recordId] was taken. A flight is held from its
     * decision to its own `intentApplied`, as `EmbodiedCognition` holds it.
     */
    private fun heldAt(
        agent: String,
        recordId: String,
    ): Boolean {
        val holds = FlightHolds()
        val id = AgentId(agent)
        val flights = mutableSetOf<String>()
        for (record in r27.all.filter { field(it, "agentId") == agent }) {
            val key = field(record, "requestKey")
            when (field(record, "event")) {
                "perceived" -> if (field(record, "recordId") == recordId) return holds.holds(id)
                "decided" ->
                    if (field(record, "intent") == "flee" && key != null) {
                        flights += key
                        holds.hold(id, key)
                    }
                "intentApplied" -> if (key in flights) holds.release(id, requireNotNull(key))
            }
        }
        error("$recordId is not one of $agent's perceptions")
    }

    @TestFactory
    fun `each review claimed during the agent's own flight is held by the flight still running`(): List<DynamicTest> =
        listOf(
            "tribe-iota" to "body:00001820",
            "tribe-theta" to "body:00001151",
            "tribe-theta" to "body:00004219",
            "tribe-theta" to "body:00004302",
        ).map { (agent, recordId) ->
            DynamicTest.dynamicTest("$agent $recordId") {
                assertTrue(heldAt(agent, recordId), "$recordId was taken while a flight of $agent's still ran")
            }
        }

    @TestFactory
    fun `a re-told damage never names an attacker its own sense did not show`(): List<DynamicTest> =
        listOf("body:00002403", "body:00002399").map { recordId ->
            DynamicTest.dynamicTest(recordId) {
                val told = r27.perception(recordId)
                val trigger = told.trigger as dev.loom.contract.Trigger.Damaged
                val stale = requireNotNull(trigger.attackerRef)
                // In r27 the ref came from the older sense that minted it, and this message lacks it.
                assertTrue(told.surroundings.none { it.ref == stale }, "$stale is not in $recordId")
                // Today the damage is held without it, so the told damage names no attacker it cannot show.
                var now = 0L
                val episodes = DangerEpisodes { now }
                val agent = AgentId(told.agentId)
                val flight = perception(requestId = "flight", agentId = told.agentId, trigger = trigger)
                assertTrue(episodes.admit(agent, flight, "attacker"))
                val repeat = perception(requestId = "repeat", agentId = told.agentId, trigger = trigger)
                assertTrue(!episodes.admit(agent, repeat, "attacker"))
                now += 20
                val held = requireNotNull(episodes.settledDamage(agent, "flight", fled = false))
                assertEquals(null, held.trigger.attackerRef)
                assertEquals("attacker", held.attacker)
            }
        }
}
