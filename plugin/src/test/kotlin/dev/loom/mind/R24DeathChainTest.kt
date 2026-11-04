package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.contract.Bearing
import dev.loom.contract.DecisionImportance
import dev.loom.contract.Intent
import dev.loom.contract.Trigger
import dev.loom.replay.CommittedEvidence
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Each of r24's ten deaths, one named case per agent, against today's danger, tiering, call and
 * survival rules. Where r24's evidence names the records, the case reads them exactly; where it
 * records no attacker identity, the case reproduces the retained species, distance, health, tick
 * order and outcome and states that the historical identity is unavailable rather than invent it.
 */
class R24DeathChainTest {
    private val r24 = CommittedEvidence("2026-10-16-openai-10-agent-10m-r24")

    private fun keyOf(record: JsonObject) = record["requestKey"]?.jsonPrimitive?.content

    private fun idOf(record: JsonObject) = requireNotNull(record["recordId"]).jsonPrimitive.content

    private fun perceivedFor(key: String) =
        r24.perception(idOf(r24.all.first { it["event"]?.jsonPrimitive?.content == "perceived" && keyOf(it) == key }))

    /** r24's own perceptions in order through today's reserve under the survival envelope's ceilings. */
    private val admittedUnderEnvelope: Map<String, Boolean> by lazy {
        val dead = mutableSetOf<AgentId>()
        val reserve =
            CallReserve(
                perAgentCeiling = 40,
                globalCeiling = 378,
                pivotalCeiling = 130,
            )
        val endings =
            r24.all
                .filter { it["event"]?.jsonPrimitive?.content in setOf("decided", "cognitionUnavailable") }
                .associate { keyOf(it) to it["outcome"]?.jsonPrimitive?.content }
        val admitted = linkedMapOf<String, Boolean>()
        for (record in r24.all) {
            when (record["event"]?.jsonPrimitive?.content) {
                "died" -> dead += AgentId(requireNotNull(record["agentId"]).jsonPrimitive.content)
                "perceived" -> {
                    val key = keyOf(record)
                    if (key in endings && endings[key] !in setOf("dangerCoalesced", "cognitionCoalesced")) {
                        val seen = r24.perception(idOf(record))
                        admitted[idOf(record)] = reserve.admits(AgentId(seen.agentId), seen)
                    }
                }
            }
        }
        admitted
    }

    private fun sighting(
        id: String,
        threat: String,
        distance: Double,
    ) = perception(
        requestId = id,
        agentId = "agent",
        trigger = Trigger.ThreatSighted("entity.minecraft.$threat", "e1", distance, Bearing.NORTH, true),
    )

    private fun damage(
        id: String,
        source: String,
    ) = perception(requestId = id, agentId = "agent", trigger = Trigger.Damaged(3, source))

    @TestFactory
    fun `each r24 death against today's danger and tiering rules`(): List<DynamicTest> =
        listOf(
            DynamicTest.dynamicTest("kappa: two zombie hits held behind its walk are told once it settles") {
                // body:00001805 and body:00001843, ticks 6660 and 6680: identity unavailable in r24.
                var now = 6640L
                val episodes = DangerEpisodes { now }
                val kappa = AgentId("tribe-kappa")
                assertTrue(episodes.admit(kappa, sighting("walk", "zombie", 6.1)))
                now = 6660
                assertEquals(false, episodes.admit(kappa, damage("hit-1", "zombie")))
                now = 6680
                assertEquals(false, episodes.admit(kappa, damage("hit-2", "zombie")))
                assertEquals(Trigger.Damaged(6, "zombie"), episodes.settled(kappa, "walk", fled = false))
            },
            DynamicTest.dynamicTest(
                "gamma: the creeper beside the spider it fled is a hunter its flight is told to avoid",
            ) {
                val seen = perceivedFor("tribe-gamma:loom18c040d2cc-sense-kn")
                val hunters = seen.surroundings.filter { it.hunts == true }.map { it.what }.toSet()
                assertTrue("entity.minecraft.creeper" in hunters, "$hunters")
                assertTrue(SurvivalOffer.of(seen).hunters.size >= 2)
            },
            DynamicTest.dynamicTest("alpha: a creeper during its zombie flight is news of its own") {
                // body:00002660, tick 7196; the creeper at 2.2 blocks 16 ticks before it blew.
                var now = 7180L
                val episodes = DangerEpisodes { now }
                val alpha = AgentId("tribe-alpha")
                assertTrue(episodes.admit(alpha, sighting("flight", "zombie", 5.0)))
                now = 7240
                assertTrue(episodes.admit(alpha, sighting("creeper", "creeper", 2.2)))
            },
            DynamicTest.dynamicTest("beta: its sightings at health 4 are pivotal, so held only to the ceiling") {
                val seen = r24.perception("body:00002436")
                assertEquals(DecisionImportance.NOTABLE, seen.importance, "r24 tiered it notable")
                assertEquals(
                    DecisionImportance.PIVOTAL,
                    seen.copy(importance = DecisionImportance.ROUTINE).classified().importance,
                )
            },
        )

    @TestFactory
    fun `each r24 death against today's call envelope`(): List<DynamicTest> =
        listOf(
            DynamicTest.dynamicTest("epsilon: its pivotal sighting at health 3.8 is admitted") {
                assertEquals(true, admittedUnderEnvelope["body:00003305"])
            },
            DynamicTest.dynamicTest("theta: its damage at health 1 is admitted") {
                assertEquals(true, admittedUnderEnvelope["body:00003291"])
            },
            DynamicTest.dynamicTest("iota: its damage at health 6 is admitted") {
                assertEquals(true, admittedUnderEnvelope["body:00003287"])
            },
            DynamicTest.dynamicTest("eta: its review at health 4.5 beside a skeleton is pivotal and takes no craft") {
                val key = requireNotNull(keyOf(r24.record("body:00003450")))
                val seen = perceivedFor(key)
                assertEquals(DecisionImportance.PIVOTAL, seen.classified().importance)
                assertTrue(SurvivalOffer.of(seen).withholds(Intent.Craft("r1", 1)))
            },
            DynamicTest.dynamicTest("delta: its three damage calls fit the survival envelope") {
                for (id in listOf("body:00003462", "body:00003470", "body:00003501")) {
                    assertEquals(true, admittedUnderEnvelope[id], id)
                }
            },
            DynamicTest.dynamicTest("zeta: its last call fits the survival envelope") {
                assertEquals(true, admittedUnderEnvelope["body:00003564"])
            },
        )
}
