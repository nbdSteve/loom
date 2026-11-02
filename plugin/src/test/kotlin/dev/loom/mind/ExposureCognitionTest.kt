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
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.util.concurrent.CompletableFuture

/** What cognition does once a research run's exposure has ended, against r34's drain. */
class ExposureCognitionTest {
    private val agent = AgentId("agent-alpha")
    private val main = FakeMainThread()
    private val records = mutableListOf<BodyEventRecord>()
    private val applied = mutableListOf<String?>()
    private val asked = mutableListOf<String>()
    private val decisions = mutableMapOf<String, CompletableFuture<Decision>>()
    private val settled = mutableListOf<Long>()
    private val exposure = ExposureGate()

    private val body =
        object : BodyPort {
            override fun spawn(agentId: AgentId) = fail("cognition must not spawn bodies")

            override fun despawn(agentId: AgentId) = fail("cognition must not despawn bodies")

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

            override fun senseThreat(agentId: AgentId) = fail("not this test's business")

            override fun tick(tick: Long) = fail("cognition must never drive the tick")

            override fun liveBodies(): Set<AgentId> = setOf(agent)
        }

    private val cognition =
        EmbodiedCognition(
            body,
            mind = { p ->
                asked += p.requestId
                CompletableFuture<Decision>().also { decisions[p.requestId] = it }
            },
            mainThread = main,
            journal = DecisionJournal(BodyEventLog { records.add(it) }, currentTick = { 12_063L }),
            callbacks =
                CognitionCallbacks(
                    onStepSettled =
                        object : StepSettlement {
                            override fun settled(
                                agentId: AgentId,
                                tick: Long,
                                owed: Boolean,
                            ) {
                                settled += tick
                            }
                        },
                    exposure = exposure,
                ),
        )

    @Test
    @DisplayName("an answer already with the mind when exposure ends is discarded, typed, and never applied")
    fun anAdmittedAnswerDrainsToATypedTerminal() {
        // r34: two calls were still with the mind when admission closed at tick 12063.
        val review = perception(requestId = "16k", agentId = agent.value)
        cognition.submit(agent, review)
        main.drain()
        assertEquals(listOf("16k"), asked)
        assertTrue(exposure.end(12_063))
        decisions.getValue("16k").complete(decisionFor(review, Intent.Idle))
        main.drain()

        assertTrue(applied.isEmpty(), "nothing is applied after the end: $applied")
        val discarded = records.single { it.event == BodyEvent.DECISION_DISCARDED }
        assertEquals(EventTag.EXPOSURE_ENDED, discarded.outcome)
        assertEquals("16k", discarded.requestId)
        assertTrue(records.none { it.event == BodyEvent.DECIDED })
    }

    @Test
    @DisplayName("a perception taken after exposure ends is never sent and ends its chain typed")
    fun nothingNewIsSentAfterTheEnd() {
        assertTrue(exposure.end(12_063))
        assertTrue(!exposure.end(12_064), "ending is once")
        cognition.submit(
            agent,
            perception(requestId = "16o", agentId = agent.value, trigger = Trigger.Damaged(5, "skeleton")),
        )
        main.drain()

        assertTrue(asked.isEmpty(), "no mind was asked: $asked")
        val unavailable = records.single { it.event == BodyEvent.COGNITION_UNAVAILABLE }
        assertEquals(EventTag.EXPOSURE_ENDED, unavailable.outcome)
        assertTrue(settled.isEmpty())
    }

    @Test
    @DisplayName("a step settled after exposure ends owes no follow-up review")
    fun nothingIsOwedAfterTheEnd() {
        val review = perception(requestId = "15s", agentId = agent.value)
        cognition.submit(agent, review)
        main.drain()
        decisions.getValue("15s").complete(decisionFor(review, Intent.Idle))
        main.drain()
        val before = settled.size
        exposure.end(12_063)
        val later = perception(requestId = "15t", agentId = agent.value)
        cognition.submit(agent, later)
        main.drain()
        assertEquals(before, settled.size, "nothing new was owed after the end")
        assertEquals(listOf("15s"), asked)
    }
}
