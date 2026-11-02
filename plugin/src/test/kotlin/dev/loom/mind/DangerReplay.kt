package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.BodyPort
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
import org.junit.jupiter.api.fail
import java.util.concurrent.CompletableFuture

/**
 * One agent's danger chain from a committed run, replayed through today's cognition: each of its own
 * perceptions is submitted and its own recorded answers arrive in the order the run recorded them.
 * The body accepts every intent at once and records the order it was asked in, and every halt.
 */
internal class DangerReplay(
    private val evidence: CommittedEvidence,
    /** As the run ran: `LOOM_COGNITION_PENDING_PER_AGENT`. */
    private val pendingPerAgent: Int = 2,
) {
    /** What one replay asked the body for, in order, and what it journalled. */
    data class Replayed(
        val applied: List<String?>,
        val halts: Int,
        val records: List<BodyEventRecord>,
    )

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

    /** Every request the run sent to the mind. */
    private val sentKeys: Set<String?> by lazy {
        evidence.all.filter { field(it, "event") == "cognitionForwarded" }.map { field(it, "requestKey") }.toSet()
    }

    /** [agent]'s chain from [from] to [until]. */
    fun replay(
        agent: String,
        from: Long,
        until: Long,
    ): Replayed {
        val main = FakeMainThread()
        var now = from
        val records = mutableListOf<BodyEventRecord>()
        val applied = mutableListOf<String?>()
        var halted = 0
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

                override val halts =
                    dev.loom.body.BodyHalt { _, _ ->
                        halted += 1
                        CompletableFuture.completedFuture(true)
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
                        pendingPerAgent,
                    ),
                mainThread = main,
                journal = DecisionJournal(BodyEventLog { records.add(it) }, currentTick = { now }),
            )
        val window =
            evidence.all.filter {
                field(it, "agentId") == agent && (it["tick"]?.jsonPrimitive?.longOrNull ?: -1) in from..until
            }
        for (record in window) {
            now = requireNotNull(record["tick"]?.jsonPrimitive?.longOrNull)
            when (field(record, "event")) {
                "perceived" -> {
                    val perception = evidence.perception(requireNotNull(field(record, "recordId")))
                    // The evidence keeps no creature uuid: a perception the run sent is its own threat,
                    // and one it coalesced shares its kind's episode.
                    val sent = field(record, "requestKey") in sentKeys
                    val subject = if (sent) perception.requestId else kindOf(perception)
                    cognition.submit(AgentId(agent), perception, dangerSubject = subject)
                }
                "decided", "decisionDiscarded" -> {
                    val decision =
                        LoomJson.decodeFromJsonElement(Decision.serializer(), requireNotNull(record["decision"]))
                    decisions[decision.requestId]?.complete(decision)
                }
            }
            main.drain()
        }
        return Replayed(applied, halted, records)
    }
}
