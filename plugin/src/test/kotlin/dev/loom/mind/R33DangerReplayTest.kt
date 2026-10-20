package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.BodyPort
import dev.loom.body.EventTag
import dev.loom.body.IntentOutcome
import dev.loom.contract.Decision
import dev.loom.contract.Intent
import dev.loom.contract.LoomJson
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import dev.loom.replay.CommittedEvidence
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.fail
import java.util.concurrent.CompletableFuture

/**
 * The r33 audit's danger chains, replayed through today's cognition from
 * `research/experiments/2026-10-16-openai-10-agent-10m-r33/evidence.jsonl`: each agent's own
 * perceptions are submitted and its own recorded answers arrive in the order r33 recorded them.
 */
class R33DangerReplayTest {
    private val r33 = CommittedEvidence("2026-10-16-openai-10-agent-10m-r33")

    private fun field(
        record: JsonObject,
        name: String,
    ) = record[name]?.jsonPrimitive?.content

    /** A danger subject for [perception]: the evidence keeps no attacker uuid, so its kind. */
    private fun kindOf(perception: Perception): String? =
        when (val trigger = perception.trigger) {
            is Trigger.Damaged -> trigger.source.substringAfterLast('.')
            is Trigger.ThreatSighted -> trigger.threat.substringAfterLast('.')
            else -> null
        }

    /** Every request r33 sent to the mind. */
    private val sentKeys: Set<String?> by lazy {
        r33.all.filter { field(it, "event") == "cognitionForwarded" }.map { field(it, "requestKey") }.toSet()
    }

    private fun brief(record: BodyEventRecord) =
        "${record.tick} ${record.event} ${record.requestId?.substringAfterLast('-')} ${record.outcome}"

    private data class Replayed(
        val applied: List<String?>,
        val records: List<BodyEventRecord>,
    )

    /** [agent]'s chain from [from] to [until]: perceptions submitted, answers given back in r33's order. */
    private fun replay(
        agent: String,
        from: Long,
        until: Long,
    ): Replayed {
        val main = FakeMainThread()
        var now = from
        val records = mutableListOf<BodyEventRecord>()
        val applied = mutableListOf<String?>()
        val decisions = mutableMapOf<String, CompletableFuture<Decision>>()
        val body =
            object : BodyPort {
                override fun spawn(agentId: AgentId) = fail("not this replay's business")

                override fun despawn(agentId: AgentId) = fail("not this replay's business")

                override fun apply(
                    agentId: AgentId,
                    intent: Intent,
                    requestId: String?,
                ): CompletableFuture<IntentOutcome> {
                    applied += requestId
                    return CompletableFuture.completedFuture(IntentOutcome.Accepted("ok"))
                }

                override fun perceive(
                    agentId: AgentId,
                    trigger: Trigger,
                ): CompletableFuture<Perception?> = CompletableFuture.completedFuture(null)

                override fun senseThreat(agentId: AgentId) = fail("not this replay's business")

                override fun tick(tick: Long) = fail("cognition must never drive the tick")

                override fun liveBodies(): Set<AgentId> = setOf(AgentId(agent))
            }
        val cognition =
            EmbodiedCognition(
                body,
                mind =
                    PerAgentCognitionBridge(
                        { p -> CompletableFuture<Decision>().also { decisions[p.requestId] = it } },
                        // As r33 ran: LOOM_COGNITION_PENDING_PER_AGENT=2.
                        2,
                    ),
                mainThread = main,
                journal = DecisionJournal(BodyEventLog { records.add(it) }, currentTick = { now }),
            )
        val window =
            r33.all.filter {
                field(it, "agentId") == agent && (it["tick"]?.jsonPrimitive?.longOrNull ?: -1) in from..until
            }
        for (record in window) {
            now = requireNotNull(record["tick"]?.jsonPrimitive?.longOrNull)
            when (field(record, "event")) {
                "perceived" -> {
                    val perception = r33.perception(requireNotNull(field(record, "recordId")))
                    // The evidence keeps no creature uuid: a perception r33 sent is its own threat, and one
                    // r33 coalesced shares its kind's episode.
                    val subject =
                        if (field(record, "requestKey") in sentKeys) {
                            perception.requestId
                        } else {
                            kindOf(
                                perception,
                            )
                        }
                    cognition.submit(AgentId(agent), perception, dangerSubject = subject)
                }
                "decided", "decisionDiscarded" -> {
                    val decision =
                        LoomJson.decodeFromJsonElement(
                            Decision.serializer(),
                            requireNotNull(record["decision"]),
                        )
                    decisions[decision.requestId]?.complete(decision)
                }
            }
            main.drain()
        }
        return Replayed(applied, records)
    }

    @TestFactory
    @DisplayName("a ready flight is applied, never discarded, while the answer that superseded it is still coming")
    fun readyFlightsAreApplied(): List<DynamicTest> =
        listOf(
            // gamma's three preempted flights, and its livelock from 8197 to its death at 8346.
            Triple("tribe-gamma", 8_190L..8_346L, listOf("sy", "t4", "t8")),
            // kappa's sighting answer, preempted one tick before it could apply.
            Triple("tribe-kappa", 8_900L..8_966L, listOf("vw")),
            // iota's sighting answer, preempted by its damage.
            Triple("tribe-iota", 9_580L..9_636L, listOf("y6")),
            // delta's sighting answer, preempted.
            Triple("tribe-delta", 8_240L..8_313L, listOf("ts")),
        ).map { (agent, ticks, cited) ->
            DynamicTest.dynamicTest(agent) {
                val replayed = replay(agent, ticks.first, ticks.last)
                val prefix =
                    r33.all.first { field(it, "agentId") == agent && field(it, "requestId") != null }
                        .let { requireNotNull(field(it, "requestId")).substringBeforeLast('-') }
                val held =
                    replayed.records
                        .filter { it.outcome == EventTag.DANGER_COALESCED }
                        .map { it.requestId }
                for (suffix in cited) {
                    val requestId = "$prefix-$suffix"
                    // Applied when ready, or - under today's bounded preemption - never sent but held.
                    assertTrue(
                        requestId in replayed.applied || requestId in held,
                        "$requestId was applied or held: ${replayed.applied}; ${replayed.records.map(::brief)}",
                    )
                }
                assertTrue(replayed.applied.isNotEmpty(), "$agent flew in the window r33 applied nothing of")
                val preempted = replayed.records.filter { it.outcome == EventTag.DANGER_PREEMPTED }.map { it.requestId }
                assertTrue(cited.none { "$prefix-$it" in preempted }, "none of $cited was discarded: $preempted")
                assertEquals(
                    0,
                    replayed.records.count {
                        it.event == BodyEvent.COGNITION_UNAVAILABLE && it.outcome == "agentBudgetExhausted"
                    },
                    "no request reached a full bridge",
                )
            }
        }

    @org.junit.jupiter.api.Test
    @DisplayName("beta's 19-damage creeper blast is urgent, though the creeper's own sighting was being answered")
    fun aLargeHitFromTheSameAttackerIsUrgent() {
        // body:00003640: coalesced behind sighting yq of the same creeper; beta died 40 ticks later.
        val blast =
            r33.perception(
                requireNotNull(
                    r33.all.first {
                        field(it, "recordId") == "body:00003640"
                    }.let(::perceivedFor),
                ),
            )
        val damage = blast.trigger as Trigger.Damaged
        assertTrue(damage.amount >= LARGE_DAMAGE, "${damage.amount} at ${blast.selfState.health} health")
        assertTrue(urgentDamage(blast))
        // Ordinary hits stay coalesced behind their own attacker's answer.
        val ordinary = blast.copy(trigger = damage.copy(amount = 4), selfState = blast.selfState.copy(health = 18.0))
        assertTrue(!urgentDamage(ordinary))
    }

    /** The `perceived` record of the request [record] ends. */
    private fun perceivedFor(record: JsonObject): String =
        requireNotNull(
            r33.all.first {
                field(it, "event") == "perceived" && field(it, "requestKey") == field(record, "requestKey")
            }
                .let { field(it, "recordId") },
        )
}
