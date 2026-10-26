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

    @Test
    @DisplayName("alpha's 14-damage creeper hit is shown at the 6 health the server left it, not the 20 its body read")
    fun aDamagedPerceptionShowsThePostHitHealth() {
        // body:00001342: 14 from a creeper at tick 10209, its perception taken the same tick showed 20.
        val recorded = r34.perception("body:00001342")
        assertEquals(20.0, recorded.selfState.health)
        val main = FakeMainThread()
        val submitted = mutableListOf<dev.loom.contract.Perception>()
        val alpha = dev.loom.body.AgentId("tribe-alpha")
        val body =
            object : dev.loom.body.BodyPort {
                override fun spawn(agentId: dev.loom.body.AgentId) =
                    org.junit.jupiter.api.fail("not this replay's business")

                override fun despawn(agentId: dev.loom.body.AgentId) =
                    org.junit.jupiter.api.fail(
                        "not this replay's business",
                    )

                override fun apply(
                    agentId: dev.loom.body.AgentId,
                    intent: dev.loom.contract.Intent,
                    requestId: String?,
                ) = java.util.concurrent.CompletableFuture.completedFuture<dev.loom.body.IntentOutcome>(
                    dev.loom.body.IntentOutcome.Accepted("ok"),
                )

                // The body's own sense, as r34 recorded it, carrying the trigger the driver asked about.
                override fun perceive(
                    agentId: dev.loom.body.AgentId,
                    trigger: dev.loom.contract.Trigger,
                ) = java.util.concurrent.CompletableFuture.completedFuture<dev.loom.contract.Perception?>(
                    recorded.copy(trigger = trigger),
                )

                override fun senseThreat(agentId: dev.loom.body.AgentId) =
                    org.junit.jupiter.api.fail(
                        "not this replay's business",
                    )

                override fun tick(tick: Long) = org.junit.jupiter.api.fail("cognition must never drive the tick")

                override fun liveBodies() = setOf(alpha)
            }
        val cognition =
            EmbodiedCognition(
                body,
                mind = { seen ->
                    submitted += seen
                    java.util.concurrent.CompletableFuture.completedFuture(decisionFor(seen))
                },
                mainThread = main,
                journal = silentJournal(),
            )
        val driver =
            DamageDriver(body, cognition, main, { false }, dev.loom.body.BodyEventLog { }, currentTick = { 10_209L })
        driver.witnessed(
            DamageReport(
                alpha,
                14.0,
                "creeper",
                10_209,
                dev.loom.body.WitnessedPlayer("65effc8b-efcc-3b44-83c1-8157de341f2e", "loom_aa88da42d7"),
                // Alpha's last server health before the hit, as its healthChanged record at tick 178 stated.
                healthBefore = 20.0,
                healthAfter = 6.0,
            ),
        )
        main.drain()

        val sent = submitted.single()
        assertEquals(6.0, sent.selfState.health)
        val damaged = sent.trigger as dev.loom.contract.Trigger.Damaged
        assertEquals(20.0, damaged.healthBefore)
        assertEquals(6.0, damaged.healthAfter)
        assertEquals(dev.loom.contract.DecisionImportance.PIVOTAL, sent.importance)
    }

    /** Logs to planks, and planks to buttons, plates and wood: the sole-input recipes zeta's horizon crossed. */
    private val woodRecipes =
        object : RecipeInputs {
            override fun inputsOf(item: String): Set<String> =
                mapOf(
                    "item.minecraft.oak_planks" to setOf(OAK_LOG),
                    "item.minecraft.oak_wood" to setOf(OAK_LOG),
                    OAK_BUTTON to setOf("item.minecraft.oak_planks"),
                    "item.minecraft.oak_pressure_plate" to setOf("item.minecraft.oak_planks"),
                )[item].orEmpty()
        }

    private fun recordOf(
        event: String,
        requestKey: String,
    ): kotlinx.serialization.json.JsonObject =
        r34.all.first {
            it["event"]?.jsonPrimitive?.content == event && it["requestKey"]?.jsonPrimitive?.content == requestKey
        }

    @Test
    @DisplayName("zeta's stalled oak log goal replaced by oak buttons keeps its three steps that advanced nothing")
    fun aStalledGoalsProductKeepsItsStallCount() {
        // body:00000222: oak log three steps without progress, replaced by two oak buttons, which need
        // the same unreached log; r34 started the button goal at none and it was abandoned two walks on.
        val zeta = dev.loom.body.AgentId("tribe-zeta")
        val ledger = GoalLedger(recipes = woodRecipes)
        val outcomes = mutableListOf<String?>()
        var presentedAtAbandon: dev.loom.contract.GoalSummary? = null
        for (sense in listOf("3h", "48", "4z", "bq", "ch", "jw")) {
            val key = "tribe-zeta:loom3d8263b30e-sense-$sense"
            val perceived = recordOf("perceived", key)
            val decided = recordOf("decided", key)
            val decidedId = requireNotNull(decided["recordId"]).jsonPrimitive.content
            val seen =
                r34.perception(requireNotNull(perceived["recordId"]).jsonPrimitive.content)
                    .copy(horizon = r34.shownHorizon(decidedId))
            val presented = ledger.present(zeta, seen.requestId, seen.capturedTick, GoalObservation.of(seen))
            if (sense == "jw") presentedAtAbandon = presented
            val decision =
                dev.loom.contract.LoomJson.decodeFromJsonElement(
                    dev.loom.contract.Decision.serializer(),
                    requireNotNull(decided["decision"]),
                )
            outcomes += ledger.resolve(zeta, decision)?.outcome
            val applied = recordOf("intentApplied", key)
            val outcome = requireNotNull(applied["outcome"]).jsonPrimitive.content
            ledger.stepped(zeta, seen.requestId, "moveTo", outcome, reached = true)
        }
        assertEquals(
            listOf(
                dev.loom.body.GoalEvidence.ADOPTED,
                dev.loom.body.GoalEvidence.COMPLETED_ADOPTED,
                dev.loom.body.GoalEvidence.REVISED,
                dev.loom.body.GoalEvidence.REVISED,
                dev.loom.body.GoalEvidence.REPLACED,
                dev.loom.body.GoalEvidence.ABANDONED,
            ),
            outcomes,
        )
        val buttons = requireNotNull(presentedAtAbandon)
        assertEquals(OAK_BUTTON, buttons.successItem)
        // The three the log goal carried, and the walk the button goal took.
        assertEquals(4L, buttons.progress?.stepsWithoutProgress)
    }

    private companion object {
        const val OAK_LOG = "item.minecraft.oak_log"
        const val OAK_BUTTON = "item.minecraft.oak_button"
    }
}
