package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.BodyPort
import dev.loom.body.EventTag
import dev.loom.body.IntentOutcome
import dev.loom.body.WitnessedPlayer
import dev.loom.contract.Decision
import dev.loom.contract.DecisionImportance
import dev.loom.contract.Intent
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.util.concurrent.CompletableFuture

private val ALPHA = AgentId("agent-alpha")
private val BETA = AgentId("agent-beta")

/** A body whose perceptions the test completes by hand. */
private class DamagedBodies(
    val living: MutableSet<AgentId> = mutableSetOf(ALPHA, BETA),
) : BodyPort {
    val perceived = mutableListOf<Pair<AgentId, Trigger>>()
    val pending = mutableListOf<CompletableFuture<Perception?>>()
    private var sequence = 0

    override fun spawn(agentId: AgentId) = fail("damage never spawns")

    override fun despawn(agentId: AgentId) = fail("damage never despawns")

    override fun apply(
        agentId: AgentId,
        intent: Intent,
        requestId: String?,
    ): CompletableFuture<IntentOutcome> = CompletableFuture.completedFuture(IntentOutcome.Accepted("ok"))

    override fun perceive(
        agentId: AgentId,
        trigger: Trigger,
    ): CompletableFuture<Perception?> {
        perceived += agentId to trigger
        return CompletableFuture<Perception?>().also { pending += it }
    }

    /** Completes the [index]th capture with a perception carrying its own trigger. */
    fun capture(index: Int): String {
        val (agent, trigger) = perceived[index]
        val requestId = "req-${agent.value}-${++sequence}"
        pending[index].complete(perception(requestId = requestId, agentId = agent.value, trigger = trigger))
        return requestId
    }

    override fun tick(tick: Long) = fail("damage never touches the tick path")

    override fun senseThreat(agentId: AgentId) = fail("damage never scans")

    override fun liveBodies(): Set<AgentId> = living.toSet()
}

private class DamageRig(
    mindBridge: ((Perception) -> CompletableFuture<Decision>)? = null,
) {
    val bodies = DamagedBodies()
    val main = FakeMainThread()
    val records = mutableListOf<BodyEventRecord>()
    val log = BodyEventLog { records += it }
    val submitted = mutableListOf<Perception>()
    val dead = mutableSetOf<AgentId>()
    val bridge =
        CognitionBridge { seen ->
            submitted += seen
            mindBridge?.invoke(seen) ?: CompletableFuture.completedFuture(decisionFor(seen))
        }
    val cognition = EmbodiedCognition(bodies, bridge, main, DecisionJournal(log, currentTick = { 0L }))
    val driver = DamageDriver(bodies, cognition, main, { it in dead }, log, currentTick = { 0L })

    fun damage(
        agent: AgentId = ALPHA,
        amount: Double = 6.0,
        tick: Long = 100,
        source: String = "zombie",
        playerDead: Boolean = false,
    ) = driver.witnessed(
        DamageReport(agent, amount, source, tick, WitnessedPlayer("uuid-${agent.value}", agent.value), playerDead),
    )

    fun outcomes(): List<String?> = records.filter { it.event == BodyEvent.DAMAGE_WITNESSED }.map { it.outcome }
}

class DamageDriverTest {
    @Test
    fun `witnessed damage reaches the mind once as a pivotal damaged trigger`() {
        val rig = DamageRig()
        rig.damage()
        assertEquals(listOf(ALPHA to Trigger.Damaged(6, "zombie")), rig.bodies.perceived)
        assertTrue(rig.submitted.isEmpty(), "nothing is submitted before the perception is captured")

        val requestId = rig.bodies.capture(0)
        rig.main.drain()

        val sent = rig.submitted.single()
        assertEquals(Trigger.Damaged(6, "zombie"), sent.trigger)
        assertEquals(DecisionImportance.PIVOTAL, sent.importance)
        val record = rig.records.single { it.event == BodyEvent.DAMAGE_WITNESSED }
        assertEquals(EventTag.DAMAGE_SUBMITTED, record.outcome)
        assertEquals(requestId, record.requestId)
        assertEquals(EventTag.DAMAGED, record.trigger)
        assertEquals(WitnessedPlayer("uuid-agent-alpha", "agent-alpha"), record.witness)
    }

    @Test
    fun `zero, negative and non-finite damage is not damage`() {
        val rig = DamageRig()
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { rig.damage(amount = it) }
        assertTrue(rig.bodies.perceived.isEmpty())
        assertTrue(rig.records.isEmpty())
    }

    @Test
    fun `amount is whole bounded points and source is a stable token`() {
        assertEquals(1L, boundedDamageAmount(0.5))
        assertEquals(6L, boundedDamageAmount(6.0))
        assertEquals(MAX_DAMAGE_AMOUNT, boundedDamageAmount(3.4e38))
        assertNull(boundedDamageAmount(0.0))
        assertEquals("zombie", sanitizedDamageSource("zombie"))
        assertEquals("fire_tick", sanitizedDamageSource("fire_tick"))
        listOf(
            null,
            "",
            "Zombie",
            "minecraft:zombie",
            "/home/op/plugins/Loom",
            "123e4567-e89b-12d3-a456-426614174000",
            "java.lang.IllegalStateException: boom",
            "a".repeat(MAX_DAMAGE_SOURCE_CHARS + 1),
        ).forEach { assertEquals(UNKNOWN_DAMAGE_SOURCE, sanitizedDamageSource(it), "$it") }
    }

    @Test
    fun `one episode coalesces until its boundary and later damage opens a new one`() {
        val rig = DamageRig()
        rig.damage(tick = 100)
        rig.damage(tick = 100)
        rig.damage(tick = 100 + DAMAGE_EPISODE_TICKS - 1)
        rig.damage(tick = 100 + DAMAGE_EPISODE_TICKS, source = "skeleton")
        assertEquals(2, rig.bodies.perceived.size)
        assertEquals(Trigger.Damaged(6, "skeleton"), rig.bodies.perceived[1].second)
        // The first damage is answered before the second is captured; while it was still with
        // the mind, the second would have been coalesced into that answer instead.
        rig.bodies.capture(0)
        rig.main.drain()
        rig.bodies.capture(1)
        rig.main.drain()
        assertEquals(
            listOf(
                EventTag.DAMAGE_COALESCED,
                EventTag.DAMAGE_COALESCED,
                EventTag.DAMAGE_SUBMITTED,
                EventTag.DAMAGE_SUBMITTED,
            ),
            rig.outcomes(),
        )
        assertEquals(2, rig.submitted.size)
    }

    @Test
    fun `agents are isolated and concurrent captures keep their own agent and request`() {
        val rig = DamageRig()
        rig.damage(ALPHA, tick = 100)
        rig.damage(BETA, tick = 100)
        rig.damage(ALPHA, tick = 101)
        assertEquals(listOf(ALPHA, BETA), rig.bodies.perceived.map { it.first })

        val betaRequest = rig.bodies.capture(1)
        val alphaRequest = rig.bodies.capture(0)
        rig.main.drain()

        assertEquals(listOf(BETA.value, ALPHA.value), rig.submitted.map { it.agentId })
        val submitted = rig.records.filter { it.outcome == EventTag.DAMAGE_SUBMITTED }
        assertEquals(
            mapOf(BETA.value to betaRequest, ALPHA.value to alphaRequest),
            submitted.associate { it.agentId to it.requestId },
        )
        val coalesced = rig.records.filter { it.outcome == EventTag.DAMAGE_COALESCED }
        assertEquals(listOf(ALPHA.value), coalesced.map { it.agentId })
    }

    @Test
    fun `a disconnected or not yet ready body is refused without a capture`() {
        val rig = DamageRig()
        rig.bodies.living.remove(ALPHA)
        rig.damage(ALPHA)
        assertTrue(rig.bodies.perceived.isEmpty())
        assertEquals(listOf(EventTag.DAMAGE_BODY_UNAVAILABLE), rig.outcomes())

        rig.bodies.living.add(ALPHA)
        rig.damage(ALPHA, tick = 101)
        assertEquals(1, rig.bodies.perceived.size, "a refusal opens no episode")
    }

    @Test
    fun `an already dead agent or dead player is refused, breach or not`() {
        val rig = DamageRig()
        rig.damage(ALPHA, playerDead = true)
        rig.dead += BETA
        rig.damage(BETA)
        assertTrue(rig.bodies.perceived.isEmpty())
        assertEquals(listOf(EventTag.DAMAGE_AGENT_DEAD, EventTag.DAMAGE_AGENT_DEAD), rig.outcomes())
    }

    @Test
    fun `lethal damage loses to the death witnessed before its perception returns`() {
        val rig = DamageRig()
        rig.damage(ALPHA, amount = 40.0)
        rig.dead += ALPHA
        rig.damage(ALPHA, amount = 4.0, tick = 101)
        rig.bodies.capture(0)
        rig.main.drain()
        assertTrue(rig.submitted.isEmpty(), "a dead agent never reaches the mind")
        assertEquals(listOf(EventTag.DAMAGE_AGENT_DEAD, EventTag.DAMAGE_DIED_BEFORE_COGNITION), rig.outcomes())
        assertTrue(rig.records.none { it.event == BodyEvent.DECIDED })
    }

    @Test
    fun `a perception the body could not capture is classified`() {
        val rig = DamageRig()
        rig.damage(ALPHA)
        rig.damage(BETA)
        rig.bodies.pending[0].complete(null)
        rig.bodies.pending[1].completeExceptionally(IllegalStateException("control lost"))
        rig.main.drain()
        assertTrue(rig.submitted.isEmpty())
        assertEquals(listOf(EventTag.DAMAGE_NOT_PERCEIVED, EventTag.DAMAGE_NOT_PERCEIVED), rig.outcomes())
    }

    @Test
    fun `shutdown refuses new damage and drops in-flight captures`() {
        val rig = DamageRig()
        rig.damage(ALPHA)
        rig.driver.close()
        rig.damage(BETA)
        rig.bodies.capture(0)
        rig.main.drain()
        assertEquals(1, rig.bodies.perceived.size)
        assertTrue(rig.submitted.isEmpty())
        assertEquals(listOf(EventTag.DAMAGE_SHUTDOWN, EventTag.DAMAGE_SHUTDOWN), rig.outcomes())
    }

    @Test
    fun `damage while the agent's event is still with the mind is coalesced before its budget`() {
        val stalled = CompletableFuture<Decision>()
        val rig = DamageRig()
        val fair = PerAgentCognitionBridge(delegate = { stalled }, maxOutstandingPerAgent = 1)
        val cognition = EmbodiedCognition(rig.bodies, fair, rig.main, DecisionJournal(rig.log, currentTick = { 0L }))
        val driver = DamageDriver(rig.bodies, cognition, rig.main, { false }, rig.log, currentTick = { 0L })
        val witness = WitnessedPlayer("uuid", "alpha")
        driver.witnessed(DamageReport(ALPHA, 6.0, "zombie", 100, witness))
        // A different attacker, so the danger episode for the zombie does not coalesce it: the
        // agent's one event already with the mind does, before the per-agent budget is spent.
        driver.witnessed(DamageReport(ALPHA, 6.0, "skeleton", 100 + DAMAGE_EPISODE_TICKS, witness))
        driver.witnessed(DamageReport(BETA, 6.0, "zombie", 100 + DAMAGE_EPISODE_TICKS, witness))
        rig.bodies.capture(0)
        val refused = rig.bodies.capture(1)
        rig.bodies.capture(2)
        rig.main.drain()
        rig.main.drain()

        val unavailable = rig.records.single { it.event == BodyEvent.COGNITION_UNAVAILABLE }
        assertEquals(refused, unavailable.requestId)
        assertEquals(EventTag.COGNITION_COALESCED, unavailable.outcome)
        assertEquals(1, fair.outstandingFor(ALPHA.value))
        assertEquals(1, fair.outstandingFor(BETA.value), "one agent's budget does not refuse another")
    }
}
