package dev.loom.mind

import dev.loom.replay.CommittedEvidence
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The r34 audit's cases, replayed through today's code from
 * `research/experiments/2026-10-16-openai-10-agent-10m-r34/evidence.jsonl`.
 */
class R34ReplayTest {
    private val r34 = CommittedEvidence("2026-10-16-openai-10-agent-10m-r34")
    private val replays = DangerReplay(r34)

    /** The order each request was perceived in, by request id. */
    private val perceivedOrder: Map<String, Int> by lazy {
        r34.all
            .filter { it["event"]?.jsonPrimitive?.content == "perceived" }
            .mapIndexed { index, record -> requireNotNull(record["requestId"]).jsonPrimitive.content to index }
            .toMap()
    }

    /** Whether some request in [applied] was applied after one perceived later than it had been. */
    private fun olderAfterNewer(applied: List<String?>): String? {
        var newest = -1
        for (requestId in applied.filterNotNull()) {
            val order = perceivedOrder.getValue(requestId)
            if (order < newest) return requestId
            newest = maxOf(newest, order)
        }
        return null
    }

    @Test
    @DisplayName("beta's older sighting answer is applied while the newer damage answer is coming, and never after it")
    fun anOlderDangerAnswerNeverFollowsANewerOne() {
        // body:00001490..00001546: sighting 111 was answered first and its flight applied; damage 115
        // was answered ten ticks later; 111's retry halted 115's flight and ran in its place.
        val replayed = replays.replay("tribe-beta", 10_330, 10_530)
        val older = "loomeab7eb8acb-sense-111"
        val newer = "loomeab7eb8acb-sense-115"
        assertTrue(older in replayed.applied && newer in replayed.applied, "${replayed.applied}")
        assertTrue(replayed.applied.indexOf(older) < replayed.applied.indexOf(newer), "${replayed.applied}")
        assertEquals(null, olderAfterNewer(replayed.applied), "${replayed.applied}")
    }
}
