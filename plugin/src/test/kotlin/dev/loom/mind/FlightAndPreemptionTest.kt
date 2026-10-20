package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.BodyPort
import dev.loom.body.EventTag
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

/** Flight holds and danger preemption, against r27's iota, theta and alpha. */
class FlightAndPreemptionTest {
    private val agent = AgentId("agent-alpha")
    private val main = FakeMainThread()

    private fun journalInto(records: MutableList<BodyEventRecord>) =
        DecisionJournal(BodyEventLog { records.add(it) }, currentTick = { 0L })

    @Test
    @DisplayName("an older flight's completion does not release a newer flight's hold on the body")
    fun anOlderFlightDoesNotReleaseANewerOne() {
        // r27 iota sense-mv (body:00001820): flight m3 completed while flight mc ran, the one-entry
        // hold was cleared, and a review claimed mid-flight had its dig refused out of reach.
        val flights = mutableMapOf<String, CompletableFuture<IntentOutcome>>()
        val body =
            object : BodyPort {
                override fun spawn(agentId: AgentId) = fail("cognition must not spawn bodies")

                override fun despawn(agentId: AgentId) = fail("cognition must not despawn bodies")

                override fun apply(
                    agentId: AgentId,
                    intent: Intent,
                    requestId: String?,
                ): CompletableFuture<IntentOutcome> =
                    CompletableFuture<IntentOutcome>().also {
                        flights[requireNotNull(requestId)] = it
                    }

                override fun perceive(
                    agentId: AgentId,
                    trigger: Trigger,
                ): CompletableFuture<Perception?> = CompletableFuture.completedFuture(null)

                override fun senseThreat(agentId: AgentId) = fail("not this test's business")

                override fun tick(tick: Long) = fail("cognition must never drive the tick")

                override fun liveBodies(): Set<AgentId> = fail("cognition does not enumerate bodies")
            }
        val decisions = mutableMapOf<String, CompletableFuture<Decision>>()
        val cognition =
            EmbodiedCognition(
                body,
                mind = { p -> CompletableFuture<Decision>().also { decisions[p.requestId] = it } },
                mainThread = main,
                journal = journalInto(mutableListOf()),
            )
        for ((id, ref) in listOf("m3" to "e1", "mc" to "e2")) {
            val zombie = Trigger.ThreatSighted("entity.minecraft.zombie", ref, 3.0, Bearing.NORTH, true)
            val sighted = perception(requestId = id, agentId = agent.value, trigger = zombie)
            cognition.submit(agent, sighted, dangerSubject = "zombie-$ref")
            main.drain()
            decisions.getValue(id).complete(decisionFor(sighted, Intent.Flee(ref)))
            main.drain()
        }
        flights.getValue("m3").complete(IntentOutcome.Accepted("safe"))
        main.drain()
        assertEquals(false, cognition.canReview(agent), "flight mc still holds the body")
        flights.getValue("mc").complete(IntentOutcome.Accepted("safe"))
        main.drain()
        assertEquals(true, cognition.canReview(agent))
    }

    @Test
    @DisplayName(
        "damage from a new attacker is sent at once, and the older answer it supersedes never reaches the body",
    )
    fun damageFromANewAttackerSupersedesTheOlderAnswer() {
        // r27 alpha body:00002606: a zombie's hit was coalesced behind the creeper answer the mind
        // was still deciding, and alpha died.
        val applied = mutableListOf<String?>()
        val body =
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

                override fun liveBodies(): Set<AgentId> = fail("cognition does not enumerate bodies")
            }
        val records = mutableListOf<BodyEventRecord>()
        val decisions = mutableMapOf<String, CompletableFuture<Decision>>()
        val cognition =
            EmbodiedCognition(
                body,
                mind = { p -> CompletableFuture<Decision>().also { decisions[p.requestId] = it } },
                mainThread = main,
                journal = journalInto(records),
            )
        val creeper = perception(requestId = "creeper", agentId = agent.value, trigger = Trigger.Damaged(7, "creeper"))
        cognition.submit(agent, creeper, dangerSubject = "creeper-1")
        main.drain()
        val zombie = perception(requestId = "zombie", agentId = agent.value, trigger = Trigger.Damaged(3, "zombie"))
        cognition.submit(agent, zombie, dangerSubject = "zombie-1")
        main.drain()
        assertEquals(setOf("creeper", "zombie"), decisions.keys, "the zombie's damage was sent, not coalesced")
        decisions.getValue("creeper").complete(decisionFor(creeper, Intent.Flee("e1")))
        decisions.getValue("zombie").complete(decisionFor(zombie, Intent.Flee("e2")))
        main.drain()
        assertEquals(listOf<String?>("zombie"), applied)
        val discarded = records.filter { it.event == dev.loom.body.BodyEvent.DECISION_DISCARDED }
        assertEquals(listOf("creeper"), discarded.map { it.requestId })
        // Typed, so the run guard reads it as a preemption and never as a body that has gone.
        assertEquals(listOf<String?>(dev.loom.body.EventTag.DANGER_PREEMPTED), discarded.map { it.outcome })
    }

    @Test
    @DisplayName(
        "a preempting damage holds the agent's pending bound, and further damage is held and told after it",
    )
    fun preemptionHoldsThePendingBound() {
        // r30 gamma (tick 6588): damage at 6548 preempted its sighting answer, damage at 6568
        // preempted again, and the damage at 6588 found both pending slots full.
        val applied = mutableListOf<String?>()
        val told = mutableListOf<Trigger>()
        val body =
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
                ): CompletableFuture<Perception?> {
                    told += trigger
                    return CompletableFuture.completedFuture(null)
                }

                override fun senseThreat(agentId: AgentId) = fail("not this test's business")

                override fun tick(tick: Long) = fail("cognition must never drive the tick")

                override fun liveBodies(): Set<AgentId> = fail("cognition does not enumerate bodies")
            }
        val records = mutableListOf<BodyEventRecord>()
        val decisions = mutableMapOf<String, CompletableFuture<Decision>>()
        val cognition =
            EmbodiedCognition(
                body,
                // Bounded the way the production chain is: LOOM_COGNITION_PENDING_PER_AGENT=2.
                mind =
                    PerAgentCognitionBridge(
                        { p -> CompletableFuture<Decision>().also { decisions[p.requestId] = it } },
                        2,
                    ),
                mainThread = main,
                journal = journalInto(records),
            )
        val low = { p: Perception -> p.copy(selfState = p.selfState.copy(health = 4.0)) }
        val sighting =
            perception(
                requestId = "sighting",
                agentId = agent.value,
                trigger = Trigger.ThreatSighted("entity.minecraft.zombie", "e1", 6.0, Bearing.NORTH, true),
            )
        cognition.submit(agent, sighting, dangerSubject = "zombie-1")
        main.drain()
        val first = low(perception(requestId = "d6548", agentId = agent.value, trigger = Trigger.Damaged(3, "zombie")))
        cognition.submit(agent, first, dangerSubject = "zombie-1")
        main.drain()
        assertEquals(setOf("sighting", "d6548"), decisions.keys, "the low-health damage preempted")
        for (tick in listOf("d6568", "d6588")) {
            val later = low(perception(requestId = tick, agentId = agent.value, trigger = Trigger.Damaged(2, "zombie")))
            cognition.submit(agent, later, dangerSubject = "zombie-1")
            main.drain()
        }
        assertEquals(setOf("sighting", "d6548"), decisions.keys, "no third request: both slots are taken")
        val held = records.filter { it.event == dev.loom.body.BodyEvent.COGNITION_UNAVAILABLE }
        assertEquals(listOf("d6568", "d6588"), held.map { it.requestId })
        assertEquals(listOf<String?>(EventTag.DANGER_COALESCED, EventTag.DANGER_COALESCED), held.map { it.outcome })
        assertEquals(0, records.count { it.outcome == "agentBudgetExhausted" }, "no request reached a full bridge")
        decisions.getValue("sighting").complete(decisionFor(sighting, Intent.Flee("e1")))
        decisions.getValue("d6548").complete(decisionFor(first, Intent.Flee("e1")))
        main.drain()
        assertEquals(listOf<String?>("d6548"), applied, "the preempting answer governs the body")
        // The two held hits are told as one damage once no answer of the agent's is still open.
        assertEquals(listOf<Trigger>(Trigger.Damaged(4, "zombie")), told)
    }
}
