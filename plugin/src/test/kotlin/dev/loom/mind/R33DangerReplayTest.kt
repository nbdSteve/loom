package dev.loom.mind

import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventRecord
import dev.loom.body.EventTag
import dev.loom.contract.Trigger
import dev.loom.replay.CommittedEvidence
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * The r33 audit's danger chains, replayed through today's cognition from
 * `research/experiments/2026-10-16-openai-10-agent-10m-r33/evidence.jsonl`: each agent's own
 * perceptions are submitted and its own recorded answers arrive in the order r33 recorded them.
 */
class R33DangerReplayTest {
    private val r33 = CommittedEvidence("2026-10-16-openai-10-agent-10m-r33")

    private val replays = DangerReplay(r33)

    private fun field(
        record: JsonObject,
        name: String,
    ) = record[name]?.jsonPrimitive?.content

    private fun brief(record: BodyEventRecord) =
        "${record.tick} ${record.event} ${record.requestId?.substringAfterLast('-')} ${record.outcome}"

    private fun replay(
        agent: String,
        from: Long,
        until: Long,
    ) = replays.replay(agent, from, until)

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
