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

    @org.junit.jupiter.api.Test
    fun `delta's dropped oak log, an input of its oak-wood goal, is owed the one pickup`() {
        // r27 delta sense-bo (body:00000235): its dig dropped an oak log for its oak-wood goal; no
        // pickup and no review followed, and a peer took the log 1400 ticks later (body:00000461).
        val interaction = r27.record("body:00000235")
        assertTrue(field(interaction, "summary").orEmpty().contains("dropped 1 item.minecraft.oak_log"))
        val goal =
            dev.loom.contract.GoalSummary(
                goalId = "loom3b4ef3e082-sense-bo",
                statement = "hold 1 oak wood",
                revision = 0,
                adoptedTick = 3472,
                successItem = "item.minecraft.oak_wood",
                successCount = 1,
            )
        val recipes =
            RecipeInputs { if (it == "item.minecraft.oak_wood") setOf("item.minecraft.oak_log") else emptySet() }
        val dug = dev.loom.body.IntentOutcome.Accepted("dug", dropped = setOf("item.minecraft.oak_log"))
        assertEquals("item.minecraft.oak_log", lyingStepItem(null, goal, dug, recipes))
        // Its own item first, and nothing for a drop that is neither.
        val both =
            dev.loom.body.IntentOutcome.Accepted(
                "dug",
                dropped = setOf("item.minecraft.oak_log", "item.minecraft.oak_wood"),
            )
        assertEquals("item.minecraft.oak_wood", lyingStepItem(null, goal, both, recipes))
        val other = dev.loom.body.IntentOutcome.Accepted("dug", dropped = setOf("item.minecraft.dirt"))
        assertEquals(null, lyingStepItem(null, goal, other, recipes))
    }

    /** A stand-in danger subject for [perception]: the evidence keeps no attacker uuid, so its kind. */
    private fun kindOf(perception: dev.loom.contract.Perception): String =
        when (val trigger = perception.trigger) {
            is dev.loom.contract.Trigger.Damaged -> trigger.source.substringAfterLast('.')
            is dev.loom.contract.Trigger.ThreatSighted -> trigger.threat.substringAfterLast('.')
            else -> "none"
        }

    @TestFactory
    fun `damage from a new attacker or at low health preempts the older answer it waited behind`(): List<DynamicTest> =
        listOf(
            // alpha: zombie hits at 8.1 and 5.1 health behind a creeper answer and a zombie sighting.
            "body:00002601" to "body:00002604",
            "body:00002619" to "body:00002637",
            // zeta: a spider hit at 16.7 health behind a zombie sighting, a different attacker.
            "body:00003823" to "body:00003826",
        ).map { (olderId, damageId) ->
            DynamicTest.dynamicTest(damageId) {
                val older = r27.perception(olderId)
                val damage = r27.perception(damageId)
                val agent = AgentId(damage.agentId)
                val preemption = DangerPreemption()
                preemption.sent(older, kindOf(older))
                val inFlight = mapOf(older.requestId to (agent to older))
                val verdict = preemption.preempts(agent, damage, kindOf(damage), inFlight)
                val preempts = verdict == DangerPreemption.Verdict.PREEMPT
                assertTrue(preempts, "$damageId preempts $olderId")
                val settled = preemption.settled(older.requestId)
                assertTrue(settled != DangerPreemption.Supersession.NONE, "the older answer arrives superseded")
            }
        }

    @TestFactory
    fun `iota's damage at 4_5 and 2_5 health is asked about, not coalesced`(): List<DynamicTest> =
        listOf("body:00002047" to "body:00002076", "body:00002076" to "body:00002106").map { (olderId, damageId) ->
            DynamicTest.dynamicTest(damageId) {
                val older = r27.perception(olderId)
                val damage = r27.perception(damageId)
                assertTrue(damage.selfState.health <= LOW_HEALTH)
                val episodes = DangerEpisodes { 0L }
                val agent = AgentId(damage.agentId)
                assertTrue(episodes.admit(agent, older, "spider"))
                assertTrue(episodes.admit(agent, damage, "spider"), "$damageId at ${damage.selfState.health}")
            }
        }
}
