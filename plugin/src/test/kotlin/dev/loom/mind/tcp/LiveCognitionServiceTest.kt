/*
 * The two runtimes, actually talking.
 *
 * Every other test on this side proves the JVM half against a fake mind. This one
 * proves the whole thing: the real Rust `loom-cognition` service, reached over a
 * real socket, answering with a decision this side decodes and routes by tier. It
 * is the only test that can catch a disagreement the shared fixtures do not - a
 * framing difference, a tag that only one serializer emits.
 *
 * Opt-in, because it needs a second process. Point `LOOM_COGNITION_ADDR` at a
 * running service and it runs; otherwise it is skipped, so the default suite stays
 * hermetic:
 *
 *   cd cognition && cargo run --bin loom-cognition
 *   cd plugin && LOOM_COGNITION_ADDR=127.0.0.1:8642 ./gradlew test
 */
package dev.loom.mind.tcp

import dev.loom.contract.DecisionImportance
import dev.loom.contract.Intent
import dev.loom.contract.Trigger
import dev.loom.mind.AsyncCognitionBridge
import dev.loom.mind.CognitionSettings
import dev.loom.mind.classified
import dev.loom.mind.perception
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/** Environment variable naming a live service as `host:port`. */
private const val ADDR_ENV = "LOOM_COGNITION_ADDR"

class LiveCognitionServiceTest {
    /** Settings for the live service, or `null` if none was named. */
    private fun liveSettings(): CognitionSettings? {
        val addr = System.getenv(ADDR_ENV) ?: return null
        val host = addr.substringBeforeLast(':', "")
        val port = addr.substringAfterLast(':', "").toIntOrNull()
        require(host.isNotBlank() && port != null) { "$ADDR_ENV must be host:port, was '$addr'" }
        return CognitionSettings(host = host, port = port)
    }

    @Test
    @DisplayName("the real service answers every trigger with a decision routed to its tier")
    fun theRealMindDecidesAndStampsItsProvenance() {
        val settings = liveSettings()
        assumeTrue(settings != null, "set $ADDR_ENV to run against a live cognition service")
        requireNotNull(settings)

        val transport =
            TcpCognitionTransport(settings.host, settings.port, settings.connectTimeout, settings.readTimeout)
        val bridge =
            AsyncCognitionBridge(transport, settings.timeout, settings.workers, settings.queueDepth)

        // One perception per trigger shape, each classified the way the body would
        // classify it, so what is exercised is the real tiering and not a literal.
        val triggers =
            listOf(
                Trigger.GoalReview,
                Trigger.BlockSighted("diamond_ore"),
                Trigger.Spoken(from = "player-steve", text = "want to trade?"),
                Trigger.Damaged(amount = 4L, source = "zombie"),
            )
        val modelsByTier = mutableMapOf<DecisionImportance, MutableSet<String>>()

        transport.use {
            bridge.use {
                for (trigger in triggers) {
                    val captured =
                        perception(requestId = "req-live-${trigger::class.simpleName}", trigger = trigger)
                    val sent = captured.classified()
                    val decision =
                        bridge.submit(sent)
                            .get(settings.timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)

                    assertEquals(sent.requestId, decision.requestId, "the mind must answer the request it was asked")
                    assertEquals(sent.agentId, decision.agentId)
                    assertEquals(sent.importance, decision.provenance.tier, "trigger $trigger")
                    assertFalse(decision.provenance.model.isBlank(), "every decision must name its model")
                    assertFalse(decision.provenance.promptVersion.isBlank(), "and its prompt version")
                    assertFalse(decision.reasoning.isBlank(), "and carry reasoning for the research record")
                    // A named intent, never a refusal dressed up as one.
                    assertNotEquals(null, decision.intent as Intent?)

                    modelsByTier.getOrPut(sent.importance) { mutableSetOf() }.add(decision.provenance.model)
                }
            }
        }

        // Collected first, asserted after, so the check does not depend on the order
        // the triggers happened to be sent in. The rule being pinned is the expensive
        // one to get wrong: whatever model answers a pivotal decision must not be the
        // model answering routine traffic.
        val frontier = modelsByTier[DecisionImportance.PIVOTAL].orEmpty()
        for ((tier, models) in modelsByTier.filterKeys { it != DecisionImportance.PIVOTAL }) {
            assertEquals(
                emptySet<String>(),
                models intersect frontier,
                "a $tier decision reached the frontier model",
            )
        }
    }
}
