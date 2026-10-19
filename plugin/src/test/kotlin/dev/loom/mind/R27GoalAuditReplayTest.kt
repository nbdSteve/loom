package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.replay.CommittedEvidence
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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
}
