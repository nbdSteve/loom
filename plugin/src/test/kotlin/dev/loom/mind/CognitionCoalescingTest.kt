/*
 * A provider that answers slowly, an agent that keeps noticing things, and a review it is owed.
 *
 * The seventh live run failed when every agent's per-agent outstanding-request budget filled
 * while the mind was slow to answer: each further event was refused as agentBudgetExhausted,
 * and a scheduled review claimed into a full budget would have been lost. Here the mind holds
 * every answer until the test releases it, through the real per-agent budget, schedule and
 * driver.
 */
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

private const val CADENCE = 1_200L
private const val SLICES = 4

/** One agent's body, minting a fresh request id for every perception. */
private class OneBody(
    private val agent: AgentId,
) : BodyPort {
    private var minted = 0
    val applied = mutableListOf<String>()

    override fun spawn(agentId: AgentId) = fail("not under test")

    override fun despawn(agentId: AgentId) = fail("not under test")

    override fun apply(
        agentId: AgentId,
        intent: Intent,
        requestId: String?,
    ): CompletableFuture<IntentOutcome> {
        applied += requireNotNull(requestId)
        return CompletableFuture.completedFuture(IntentOutcome.Accepted("applied"))
    }

    override fun perceive(
        agentId: AgentId,
        trigger: Trigger,
    ): CompletableFuture<Perception?> {
        minted += 1
        return CompletableFuture.completedFuture(perception("req-$minted", agent.value, trigger))
    }

    override fun tick(tick: Long) = fail("not under test")

    override fun senseThreat(agentId: AgentId): CompletableFuture<Perception?> = fail("not under test")

    override fun liveBodies(): Set<AgentId> = setOf(agent)
}

/** A mind that answers nothing until the test says so. */
private class SlowMind : CognitionBridge {
    val held = linkedMapOf<String, Pair<Perception, CompletableFuture<Decision>>>()

    override fun submit(perception: Perception): CompletableFuture<Decision> =
        CompletableFuture<Decision>().also { held[perception.requestId] = perception to it }

    fun answer(requestId: String) {
        val (perception, future) = requireNotNull(held.remove(requestId)) { "nothing held for $requestId" }
        future.complete(decisionFor(perception))
    }
}

class CognitionCoalescingTest {
    @Test
    @DisplayName("while the mind is slow, redundant events coalesce and the owed review is served once it fits")
    fun redundantEventsCoalesceAndTheOwedReviewIsServed() {
        val agent = AgentId("agent-alpha")
        val body = OneBody(agent)
        val mind = SlowMind()
        val refusals = mutableListOf<CognitionFailure>()
        val budget = PerAgentCognitionBridge(mind, maxOutstandingPerAgent = 2) { _, failure -> refusals += failure }
        val records = mutableListOf<BodyEventRecord>()
        val main = FakeMainThread()
        var now = 0L
        val cognition =
            EmbodiedCognition(body, budget, main, DecisionJournal(BodyEventLog(records::add)) { now })
        val driver = AutonomousDriver(body, cognition, GoalReviewSchedule(CADENCE, SLICES)) { now }

        // The first scheduled review goes to the mind, and the mind sits on it.
        driver.sweep()
        main.drain()
        assertEquals(listOf(Trigger.GoalReview), mind.held.values.map { it.first.trigger })
        val firstReview = mind.held.keys.single()

        // Five events while it waits, as a damaged agent under several mobs produces them.
        val events =
            listOf<Trigger>(
                Trigger.Damaged(amount = 3L, source = "minecraft:zombie"),
                Trigger.Damaged(amount = 2L, source = "minecraft:skeleton"),
                Trigger.Damaged(amount = 4L, source = "minecraft:spider"),
                Trigger.Spoken(from = "tribe-beta", text = "over here"),
                Trigger.Spoken(from = "tribe-gamma", text = "run"),
            )
        for (trigger in events) cognition.consider(agent, trigger)
        main.drain()

        // One event joins the review in flight; the other four fold into what is coming.
        assertEquals(2, mind.held.size)
        assertEquals(emptyList<CognitionFailure>(), refusals, "the per-agent budget must never refuse")
        val coalesced = records.filter { it.event == BodyEvent.COGNITION_UNAVAILABLE }
        assertEquals(4, coalesced.size)
        assertTrue(coalesced.all { it.outcome == EventTag.COGNITION_COALESCED })

        // A whole cadence later the next review is owed, but the first is still unanswered:
        // it is deferred rather than claimed into the budget and refused.
        now += CADENCE
        driver.sweep()
        main.drain()
        assertEquals(2, mind.held.size)
        assertEquals(1, mind.held.values.count { it.first.trigger == Trigger.GoalReview })

        // The slow answers land - the review's, and the danger event's, while which no review is
        // claimed (r23: five reviews claimed beside a danger answer were refused as not ready);
        // at the very next slice the owed review is served.
        mind.answer(firstReview)
        main.drain()
        assertTrue(!cognition.canReview(agent), "a danger event is still with the mind")
        mind.held.keys.toList().forEach(mind::answer)
        main.drain()
        assertTrue(cognition.canReview(agent))
        now += GoalReviewSchedule(CADENCE, SLICES).sliceTicks
        driver.sweep()
        main.drain()
        val reviews = mind.held.values.filter { it.first.trigger == Trigger.GoalReview }
        assertEquals(1, reviews.size, "the owed review reached the mind")
        assertTrue(reviews.single().first.requestId != firstReview)
        mind.held.keys.toList().forEach(mind::answer)
        main.drain()
        assertEquals(emptyList<CognitionFailure>(), refusals)
        assertEquals(3, body.applied.size, "two reviews and one event were decided and applied")
        assertTrue(records.none { it.outcome == "agentBudgetExhausted" })
    }

    @Test
    @DisplayName("an answer that arrives after its agent died is discarded and never recorded as decided")
    fun anAnswerAfterDeathIsDiscardedNotDecided() {
        // r21 gamma: a damaged perception at tick 6683 was answered 2.3 s later, after the
        // death, and the decided record written for it stopped the run.
        val agent = AgentId("agent-alpha")
        val body = OneBody(agent)
        val mind = SlowMind()
        val records = mutableListOf<BodyEventRecord>()
        val main = FakeMainThread()
        var dead = false
        val cognition =
            EmbodiedCognition(
                body,
                mind,
                main,
                DecisionJournal(BodyEventLog(records::add)) { 6683L },
                CognitionCallbacks(isDead = { it == agent && dead }),
            )
        cognition.consider(agent, Trigger.Damaged(amount = 4L, source = "minecraft:zombie_villager"))
        main.drain()
        val asked = mind.held.keys.single()
        dead = true
        mind.answer(asked)
        main.drain()
        val chain = records.filter { it.requestId == asked }.map { it.event }
        assertEquals(listOf(BodyEvent.DECISION_DISCARDED), chain, "no decided record, only the discard")
        assertEquals(emptyList<String>(), body.applied, "nothing is asked of a dead body")
    }
}
