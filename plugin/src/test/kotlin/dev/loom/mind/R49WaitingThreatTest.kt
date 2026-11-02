package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.BodyPort
import dev.loom.body.EventTag
import dev.loom.body.HeldPerceptions
import dev.loom.body.IntentOutcome
import dev.loom.contract.Bearing
import dev.loom.contract.Decision
import dev.loom.contract.Intent
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.util.concurrent.CompletableFuture

/** r49 iota's last warning, which waited behind a nearer threat's answer and was never told. */
class R49WaitingThreatTest {
    private val agent = AgentId("agent-iota")
    private val main = FakeMainThread()

    @Test
    @DisplayName("a threat that waited behind a nearer one's answer is marked never told")
    fun aThreatWaitingBehindANearerOneIsUntold() {
        // r49 iota body:00001696: a skeleton 15.8 off waited behind a zombie flight's open answer, the
        // watch then refused every look as already told, and its arrow killed iota at health 3.8.
        val unsent = mutableListOf<Pair<String, Boolean>>()
        val body =
            object : BodyPort {
                override val held =
                    object : HeldPerceptions {
                        override fun release(
                            agentId: AgentId,
                            requestId: String,
                        ) = Unit

                        override fun unsent(
                            agentId: AgentId,
                            perception: Perception,
                            waited: Boolean,
                        ) {
                            unsent.add(perception.requestId to waited)
                        }
                    }

                override fun spawn(agentId: AgentId) = fail("cognition must not spawn bodies")

                override fun despawn(agentId: AgentId) = fail("cognition must not despawn bodies")

                override fun apply(
                    agentId: AgentId,
                    intent: Intent,
                    requestId: String?,
                ): CompletableFuture<IntentOutcome> = CompletableFuture()

                override fun perceive(
                    agentId: AgentId,
                    trigger: Trigger,
                ): CompletableFuture<Perception?> = CompletableFuture.completedFuture(null)

                override fun senseThreat(agentId: AgentId) = fail("not this test's business")

                override fun tick(tick: Long) = fail("cognition must never drive the tick")

                override fun liveBodies(): Set<AgentId> = fail("cognition does not enumerate bodies")
            }
        val records = mutableListOf<BodyEventRecord>()
        val cognition =
            EmbodiedCognition(
                body,
                mind = { CompletableFuture<Decision>() },
                mainThread = main,
                journal = DecisionJournal(BodyEventLog { records.add(it) }, currentTick = { 0L }),
            )
        val zombie = Trigger.ThreatSighted("entity.minecraft.zombie", "e1", 6.8, Bearing.NORTH, true)
        val skeleton = Trigger.ThreatSighted("entity.minecraft.skeleton", "e2", 15.8, Bearing.WEST, true)
        cognition.submit(agent, perception(requestId = "near", agentId = agent.value, trigger = zombie))
        cognition.submit(agent, perception(requestId = "far", agentId = agent.value, trigger = skeleton))
        cognition.submit(agent, perception(requestId = "again", agentId = agent.value, trigger = zombie))
        main.drain()
        val coalesced = records.filter { it.event == BodyEvent.COGNITION_UNAVAILABLE }
        assertEquals(listOf(EventTag.DANGER_COALESCED, EventTag.DANGER_COALESCED), coalesced.map { it.outcome })
        // The repeat about the zombie being answered was told; the skeleton never was.
        assertEquals(listOf("far" to true, "again" to false), unsent)
    }
}
