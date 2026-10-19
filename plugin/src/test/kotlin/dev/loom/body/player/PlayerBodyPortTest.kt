/*
 * The player adapter as one coordinator boundary.
 *
 * Node's mechanics and the server witness have their own suites. Here both are
 * represented by their narrow ports so one test layer can pin the ordering and
 * translation between them: identity registration, readiness, request-scoped
 * control sessions, evidence order, disconnect/restart, terminal death and full
 * cleanup.
 */
package dev.loom.body.player

import dev.loom.body.AgentId
import dev.loom.body.BODY_SENSE_MAX_OFFERS_PER_KIND
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.BodyLifecycle
import dev.loom.body.BodySpawnResult
import dev.loom.body.EventTag
import dev.loom.body.IntentOutcome
import dev.loom.body.PERCEPTION_MAX_OBSERVATIONS
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.CollectOutcome
import dev.loom.bodycontrol.DigOutcome
import dev.loom.bodycontrol.EatOutcome
import dev.loom.bodycontrol.EquipOutcome
import dev.loom.bodycontrol.ErrorCode
import dev.loom.bodycontrol.LifecycleState
import dev.loom.bodycontrol.MoveOutcome
import dev.loom.bodycontrol.PlaceOutcome
import dev.loom.bodycontrol.SayOutcome
import dev.loom.bodycontrol.SensedBlock
import dev.loom.bodycontrol.SensedEntity
import dev.loom.bodycontrol.SensedGround
import dev.loom.bodycontrol.SensedItem
import dev.loom.bodycontrol.SensedRecipe
import dev.loom.bodycontrol.SensedStack
import dev.loom.bodycontrol.SensedTime
import dev.loom.bodycontrol.SensedVitals
import dev.loom.contract.Bearing
import dev.loom.contract.BlockFace
import dev.loom.contract.BlockYield
import dev.loom.contract.DropFate
import dev.loom.contract.EquipmentDestination
import dev.loom.contract.Intent
import dev.loom.contract.ObservationKind
import dev.loom.contract.OwnDrop
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import dev.loom.mind.CognitionBridge
import dev.loom.mind.CognitionCallbacks
import dev.loom.mind.DecisionJournal
import dev.loom.mind.EmbodiedCognition
import dev.loom.mind.MainThread
import dev.loom.mind.decisionFor
import dev.loom.witness.WitnessJournal
import dev.loom.witness.subjectOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val PORT_TEST_TIMEOUT: Duration = Duration.ofSeconds(3)
private val SENSE_POSITION = listOf(1.5, 64.0, 2.5)

/** Bounded one-shot speech through the same fake fleet harness. */
class PlayerSpeechPortTest {
    @TempDir
    lateinit var temporary: Path

    private val harnesses = mutableListOf<PortHarness>()
    private val tick = AtomicLong(500)

    @AfterEach
    fun cleanUp() {
        harnesses.forEach { runCatching(it.port::close) }
    }

    private fun harness(
        agent: AgentId,
        options: HarnessOptions = HarnessOptions(),
    ): PortHarness = buildPortHarness(temporary, tick, agent, options).also(harnesses::add)

    @Test
    fun `a say sends exactly one bounded chat request with the exact text`() {
        val agent = AgentId("agent-speaker")
        val harness = harness(agent)
        harness.port.spawn(agent).await()
        val perception = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())

        val said = harness.port.apply(agent, Intent.Say("h\u00e9llo river"), perception.requestId).await()

        assertEquals(IntentOutcome.Accepted("said"), said)
        val says = harness.connector.requests.filterIsInstance<BodyRequest.Say>()
        assertEquals(listOf("h\u00e9llo river"), says.map { it.text })
        assertEquals(agent.value, says.single().agentId)
    }

    @Test
    fun `a full sense memory refuses a perception on the record, and a released one frees it`() {
        // r20: perceptions coalesced or lost held the body's four senses, the damage that killed
        // three agents went unperceived, and beta's review at capacity left no record at all.
        val agent = AgentId("agent-crowded")
        val harness = harness(agent)
        harness.port.spawn(agent).await()
        val held = (1..4).map { requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await()) }
        assertEquals(null, harness.port.perceive(agent, Trigger.GoalReview).await())
        val refused = harness.records.single { it.event == BodyEvent.PERCEPTION_REFUSED }
        assertEquals(EventTag.SENSE_AT_CAPACITY, refused.outcome)
        assertEquals(agent.value, refused.agentId)
        harness.port.held.release(agent, held.first().requestId)
        assertTrue(harness.port.perceive(agent, Trigger.GoalReview).await() != null)
    }

    @Test
    fun `an unsendable line is refused before any chat request`() {
        val agent = AgentId("agent-mute")
        val harness = harness(agent)
        harness.port.spawn(agent).await()
        val lines =
            listOf("", "   ", "a".repeat(129), "hi\u0007", "\u00a7cred", "\u001b[31m", "a\u202eb", "/kill", "a  b")
        for (line in lines) {
            assertEquals(
                IntentOutcome.Rejected("the line is not sendable chat", EventTag.SAY_TEXT_REFUSED),
                harness.port.apply(agent, Intent.Say(line)).await(),
            )
        }
        assertTrue(harness.connector.requests.none { it is BodyRequest.Say })
    }

    @Test
    fun `a full speech window is a typed refusal`() {
        val agent = AgentId("agent-chatty")
        val harness =
            harness(
                agent = agent,
                options =
                    HarnessOptions(intercept = { request ->
                        (request as? BodyRequest.Say)?.let { fakeSay(it, SayOutcome.RATE_LIMITED) }
                    }),
            )
        harness.port.spawn(agent).await()

        assertEquals(
            IntentOutcome.Rejected("the body's speech window was full", EventTag.SAY_RATE_LIMITED),
            harness.port.apply(agent, Intent.Say("again")).await(),
        )
    }

    @Test
    fun `one say is outstanding per agent and the bound is released on every ending`() {
        val agent = AgentId("agent-waiting")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var fail = false
        val harness =
            harness(
                agent = agent,
                options =
                    HarnessOptions(intercept = { request ->
                        if (request is BodyRequest.Say) {
                            entered.countDown()
                            release.await(PORT_TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                            if (fail) throw BodyControlFailure.Unavailable()
                        }
                        null
                    }),
            )
        harness.port.spawn(agent).await()

        val first = harness.port.apply(agent, Intent.Say("first"))
        assertTrue(entered.await(PORT_TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
        assertEquals(
            IntentOutcome.Rejected("this agent is already speaking", EventTag.SAY_OUTSTANDING),
            harness.port.apply(agent, Intent.Say("second")).await(),
        )
        fail = true
        release.countDown()
        assertEquals(
            IntentOutcome.Rejected("the body-control request was unavailable", EventTag.CONTROL_UNAVAILABLE),
            first.await(),
        )
        fail = false
        assertEquals(IntentOutcome.Accepted("said"), harness.port.apply(agent, Intent.Say("third")).await())
        assertEquals(
            listOf("first", "third"),
            harness.connector.requests.filterIsInstance<BodyRequest.Say>().map { it.text },
        )
    }
}

private class FakeBodyProcess(
    override val identity: PlayerIdentity,
    override val endpoint: BodyControlEndpoint,
) : SupervisedBodyProcess {
    private val alive = AtomicBoolean(true)
    private val exited = CompletableFuture<Int>()

    override val exitCode: CompletionStage<Int> = exited

    override fun isAlive(): Boolean = alive.get()

    fun stop(code: Int = 0) {
        if (alive.compareAndSet(true, false)) {
            exited.complete(code)
        }
    }
}

private class FakeBodySupervisor(
    private val endpoint: BodyControlEndpoint,
    private val beforeStart: (PlayerIdentity) -> Unit = {},
) : BodyProcessSupervisor {
    val starts = CopyOnWriteArrayList<PlayerIdentity>()
    val stopped = CopyOnWriteArrayList<FakeBodyProcess>()
    val processes = CopyOnWriteArrayList<FakeBodyProcess>()
    var stopFailure: Throwable? = null
    var healthy = true
    var healthChecks = 0
    var closed = false

    override fun start(identity: PlayerIdentity): CompletionStage<SupervisedBodyProcess> {
        beforeStart(identity)
        starts += identity
        return FakeBodyProcess(identity, endpoint).also(processes::add).let {
            CompletableFuture.completedFuture(it)
        }
    }

    override fun stop(process: SupervisedBodyProcess): CompletionStage<Unit> {
        val fake = process as FakeBodyProcess
        stopFailure?.let { return CompletableFuture.failedFuture(it) }
        stopped += fake
        fake.stop()
        return CompletableFuture.completedFuture(Unit)
    }

    override fun health(process: SupervisedBodyProcess): CompletionStage<Boolean> {
        healthChecks += 1
        return CompletableFuture.completedFuture(healthy && process.isAlive())
    }

    override fun close() {
        if (closed) return
        closed = true
        processes.forEach { process ->
            if (process.isAlive()) {
                stopped += process
                process.stop()
            }
        }
    }
}

/**
 * The fake server's ground: open everywhere it was not told otherwise, so a sensed block is stood
 * on top of unless it is listed as passable, and stood at nowhere if it is listed as buried.
 */
private class FakeGround(
    private val server: FakePlayerServerState,
) : BlockGround {
    override fun standsIn(position: List<Int>): Boolean = position in server.passable

    override fun standCell(position: List<Int>): List<Int>? =
        when {
            position in server.buried -> null
            standsIn(position) -> position
            else -> listOf(position[0], position[1] + 1, position[2])
        }
}

private class FakePlayerServerState : PlayerServerState {
    @Volatile
    var connected = true

    var correlateEntities = true
    val dead = mutableSetOf<AgentId>()
    val senseAudits = CopyOnWriteArrayList<Pair<PlayerIdentity, BodyResponse.SenseReport>>()
    val senseAuditVersions = CopyOnWriteArrayList<Long>()
    val moveAudits = CopyOnWriteArrayList<Pair<PlayerIdentity, BodyRequest>>()
    val interactionAudits = CopyOnWriteArrayList<Pair<PlayerIdentity, BodyRequest>>()
    var senseAuditVersion: Long = 0
    val passable = CopyOnWriteArrayList<List<Int>>()

    /** Sensed blocks the fake server says no body can stand at. */
    val buried = CopyOnWriteArrayList<List<Int>>()

    override fun connected(identity: PlayerIdentity): Boolean = connected

    override fun isDead(agentId: AgentId): Boolean = synchronized(dead) { agentId in dead }

    override fun dayOrdinal(): Long = 2

    override fun subjectsOf(
        identity: PlayerIdentity,
        entities: List<SensedEntity>,
    ): List<UUID?> =
        entities.mapIndexed { index, entity ->
            if (correlateEntities) {
                UUID.nameUUIDFromBytes(
                    "$index:${entity.what}".toByteArray(StandardCharsets.UTF_8),
                )
            } else {
                null
            }
        }

    override fun itemSubjectsOf(
        identity: PlayerIdentity,
        items: List<SensedItem>,
    ): List<UUID?> =
        items.mapIndexed { index, item ->
            UUID.nameUUIDFromBytes(
                "item:$index:${item.what}:${item.count}".toByteArray(StandardCharsets.UTF_8),
            )
        }

    override fun captureSenseAudit(identity: PlayerIdentity): SenseAuditSnapshot =
        FakeSenseAuditSnapshot(identity, senseAuditVersion)

    override fun auditSense(
        identity: PlayerIdentity,
        snapshot: SenseAuditSnapshot?,
        report: BodyResponse.SenseReport,
    ) {
        val captured = snapshot as FakeSenseAuditSnapshot
        assertEquals(identity, captured.identity)
        senseAuditVersions += captured.version
        senseAudits += identity to report
    }

    override fun auditMovement(
        identity: PlayerIdentity,
        request: BodyRequest.MoveTo,
        report: BodyResponse.MoveReport,
    ) {
        moveAudits += identity to request
    }

    override fun auditMovement(
        identity: PlayerIdentity,
        request: BodyRequest.Flee,
        report: BodyResponse.FleeReport,
    ) {
        moveAudits += identity to request
    }

    override fun auditInteraction(
        identity: PlayerIdentity,
        claim: ServerInteractionClaim,
    ): ServerInteractionAudit {
        interactionAudits += identity to claim.controlRequest
        val detail =
            when (claim) {
                is ServerInteractionClaim.Dig ->
                    "blockBreak ${claim.offered.what} at ${claim.offered.position}"
                is ServerInteractionClaim.Collect ->
                    "entityPickupItem ${claim.itemSubject} ${claim.offered.what}"
                is ServerInteractionClaim.Place ->
                    "blockPlace ${claim.offeredStack.what} against ${claim.offeredBlock.position}"
                is ServerInteractionClaim.Equip ->
                    "server slot ${claim.report.toSlot} holds ${claim.offeredStack.what}"
                is ServerInteractionClaim.Craft ->
                    "craftItem ${claim.report.count} ${claim.offeredRecipe.what}"
                is ServerInteractionClaim.Attack ->
                    "entityDamage ${claim.targetSubject} ${claim.offeredTarget.what}"
                is ServerInteractionClaim.Sleep ->
                    "bed ${claim.offeredBed.position} entered ${claim.report.entered}"
            }
        return ServerInteractionAudit(true, detail, brokenByOther = claim is ServerInteractionClaim.Dig && digsLost)
    }

    /** Whether the fake server credits every dug block to another managed player. */
    @Volatile
    var digsLost = false
}

private data class FakeSenseAuditSnapshot(
    val identity: PlayerIdentity,
    val version: Long,
) : SenseAuditSnapshot

private class FakeControlConnector(
    private val intercept: (BodyRequest) -> BodyResponse? = { null },
) : BodyControlConnector {
    val connections = CopyOnWriteArrayList<FakeControlConnection>()

    override fun connect(
        endpoint: BodyControlEndpoint,
        identity: PlayerIdentity,
    ): BodyControlConnection = FakeControlConnection(identity, intercept).also(connections::add)

    val requests: List<BodyRequest>
        get() = connections.flatMap { it.requests }
}

private class FakeControlConnection(
    override val identity: PlayerIdentity,
    private val intercept: (BodyRequest) -> BodyResponse?,
) : BodyControlConnection {
    val requests = CopyOnWriteArrayList<BodyRequest>()
    private val closed = AtomicBoolean()

    override fun request(
        request: BodyRequest,
        timeout: Duration,
    ): BodyResponse {
        check(!closed.get()) { "a closed control session was reused" }
        requests += request
        intercept(request)?.let { return it }
        return fakeResponse(request, requests.size)
    }

    override fun close() {
        closed.set(true)
    }

    fun isClosed(): Boolean = closed.get()
}

private data class PortHarness(
    val port: PlayerBodyPort,
    val supervisor: FakeBodySupervisor,
    val connector: FakeControlConnector,
    val server: FakePlayerServerState,
    val registered: CopyOnWriteArrayList<PlayerIdentity>,
    val records: CopyOnWriteArrayList<BodyEventRecord>,
    val io: ExecutorService,
    val scheduler: ScheduledExecutorService,
)

private data class FleetPortHarness(
    val port: PlayerBodyPort,
    val supervisors: Map<AgentId, FakeBodySupervisor>,
    val connectors: Map<AgentId, FakeControlConnector>,
    val server: FakePlayerServerState,
    val registered: CopyOnWriteArrayList<PlayerIdentity>,
    val records: CopyOnWriteArrayList<BodyEventRecord>,
    val io: List<ExecutorService>,
    val schedulers: List<ScheduledExecutorService>,
)

private data class HarnessOptions(
    val registered: CopyOnWriteArrayList<PlayerIdentity> = CopyOnWriteArrayList(),
    val server: FakePlayerServerState = FakePlayerServerState(),
    val beforeStart: (PlayerIdentity) -> Unit = {},
    val intercept: (BodyRequest) -> BodyResponse? = { null },
    val healthCheckIntervalTicks: Long = 100,
    val healthFailureLimit: Int = 3,
    val yields: BlockYields = BlockYields.NONE,
    val ownDrops: OwnDrops = OwnDrops.NONE,
)

private class StartupGate {
    private val pending =
        linkedMapOf<AgentId, Pair<FakeBodyProcess, CompletableFuture<SupervisedBodyProcess>>>()
    val started = mutableListOf<AgentId>()
    var active = 0
        private set
    var maximumActive = 0
        private set

    @Synchronized
    fun start(
        identity: PlayerIdentity,
        endpoint: BodyControlEndpoint,
    ): CompletionStage<SupervisedBodyProcess> {
        val process = FakeBodyProcess(identity, endpoint)
        val future = CompletableFuture<SupervisedBodyProcess>()
        pending[identity.agentId] = process to future
        started += identity.agentId
        active += 1
        maximumActive = maxOf(maximumActive, active)
        return future
    }

    @Synchronized
    fun release(agentId: AgentId) {
        val (process, future) = requireNotNull(pending.remove(agentId))
        active -= 1
        future.complete(process)
    }
}

private class GatedBodySupervisor(
    private val endpoint: BodyControlEndpoint,
    private val gate: StartupGate,
) : BodyProcessSupervisor {
    private val processes = CopyOnWriteArrayList<FakeBodyProcess>()

    override fun start(identity: PlayerIdentity): CompletionStage<SupervisedBodyProcess> =
        gate.start(identity, endpoint).whenComplete { process, _ ->
            (process as? FakeBodyProcess)?.let(processes::add)
        }

    override fun stop(process: SupervisedBodyProcess): CompletionStage<Unit> {
        (process as FakeBodyProcess).stop()
        return CompletableFuture.completedFuture(Unit)
    }

    override fun close() {
        processes.forEach { it.stop() }
    }
}

class PlayerBodyPortTest {
    @TempDir
    lateinit var temporary: Path

    private val harnesses = mutableListOf<PortHarness>()
    private val fleetHarnesses = mutableListOf<FleetPortHarness>()
    private val extraPorts = mutableListOf<PlayerBodyPort>()
    private val tick = AtomicLong(100)

    @AfterEach
    fun cleanUp() {
        harnesses.forEach { runCatching(it.port::close) }
        fleetHarnesses.forEach { runCatching(it.port::close) }
        extraPorts.forEach { runCatching(it::close) }
    }

    @Test
    fun `spawn registers identity before launch and completes only after server readiness`() {
        val agent = AgentId("agent-ready")
        val registered = CopyOnWriteArrayList<PlayerIdentity>()
        val server = FakePlayerServerState().apply { connected = false }
        val harness =
            harness(
                agent = agent,
                options =
                    HarnessOptions(
                        registered = registered,
                        server = server,
                        beforeStart = { identity ->
                            assertEquals(listOf(identity), registered)
                        },
                    ),
            )

        val starting = harness.port.spawn(agent).toCompletableFuture()

        assertFalse(starting.isDone)
        assertEquals(BodyLifecycle.STARTING, harness.port.inspect(agent)?.state)
        server.connected = true

        assertEquals(BodySpawnResult.Spawned(agent), starting.await())
        assertEquals(BodyLifecycle.READY, harness.port.inspect(agent)?.state)
        assertEquals(setOf(agent), harness.port.liveBodies())
        assertEquals(playerIdentityOf(agent), registered.single())
        assertEquals(listOf(BodyEvent.SPAWNED), harness.records.map { it.event })
    }

    @Test
    fun `perception movement and eating retain one request identity end to end`() {
        val agent = AgentId("agent-round-trip")
        val harness = harness(agent)
        harness.port.spawn(agent).await()

        val movementPerception =
            requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        harness.port.held.shown(agent, movementPerception)
        assertEquals(18.0, movementPerception.selfState.health)
        assertEquals(6, movementPerception.selfState.food)
        assertEquals(1.5, movementPerception.selfState.saturation)
        assertEquals(
            "entity.minecraft.zombie",
            movementPerception.surroundings.single { it.kind == ObservationKind.ENTITY }.what,
        )
        assertEquals("item.minecraft.bread", movementPerception.selfState.inventorySummary.single().what)
        val perceptionAudit =
            requireNotNull(
                harness.records
                    .single {
                        it.event == BodyEvent.PERCEIVED &&
                            it.requestId == movementPerception.requestId
                    }.perceptionAudit,
            )
        assertEquals(6_000, perceptionAudit.worldClock.timeOfDay)
        assertEquals(2, perceptionAudit.worldClock.day)
        assertEquals(1, perceptionAudit.entities.rawBodyListCount)
        assertEquals(1, perceptionAudit.entities.jvmSelectedCount)
        assertEquals(0, perceptionAudit.entities.jvmCutOmittedCount)
        assertEquals(1, perceptionAudit.blocks.rawBodyListCount)
        assertEquals(0, perceptionAudit.droppedItems.rawBodyListCount)

        val moveOutcome =
            harness.port
                .apply(
                    agent,
                    walkOnto(movementPerception),
                    movementPerception.requestId,
                ).await()
        assertTrue(moveOutcome is IntentOutcome.Accepted)

        val eatingPerception =
            requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        harness.port.held.shown(agent, eatingPerception)
        val stack = eatingPerception.selfState.inventorySummary.single()
        val eatOutcome =
            harness.port
                .apply(agent, Intent.Eat(stack.ref), eatingPerception.requestId)
                .await()
        assertEquals(IntentOutcome.Accepted("ate minecraft:bread"), eatOutcome)

        assertRoundTripEvidence(
            harness,
            agent,
            movementPerception.requestId,
            eatingPerception.requestId,
        )
    }

    @Test
    fun `sense audit uses the server snapshot from before the control round trip`() {
        val agent = AgentId("agent-sense-audit")
        val server = FakePlayerServerState().apply { senseAuditVersion = 7 }
        val harness =
            harness(
                agent,
                HarnessOptions(
                    server = server,
                    intercept = { request ->
                        (request as? BodyRequest.Sense)?.let {
                            server.senseAuditVersion = 8
                            interactionSense(it)
                        }
                    },
                ),
            )
        harness.port.spawn(agent).await()

        assertTrue(harness.port.perceive(agent, Trigger.GoalReview).await() != null)

        assertEquals(listOf(7L), server.senseAuditVersions)
        assertEquals(8L, server.senseAuditVersion)
    }

    @Test
    fun `a process crash is a diagnosable disconnect and the same identity can restart`() {
        val agent = AgentId("agent-restart")
        val harness = harness(agent)
        harness.port.spawn(agent).await()
        val first = harness.supervisor.processes.single()

        first.stop(code = 1)

        assertEquals(BodyLifecycle.DISCONNECTED, harness.port.inspect(agent)?.state)
        assertFalse(agent in harness.server.dead)
        assertTrue(harness.port.liveBodies().isEmpty())

        assertEquals(BodySpawnResult.Spawned(agent), harness.port.spawn(agent).await())
        assertEquals(2, harness.supervisor.starts.size)
        assertEquals(
            harness.supervisor.starts.first(),
            harness.supervisor.starts.last(),
            "a restart must preserve the exact player identity",
        )
        val disconnected = harness.records.single { it.event == BodyEvent.BODY_DISCONNECTED }
        assertEquals(agent.value, disconnected.agentId)
        assertEquals(EventTag.BODY_PROCESS_EXITED, disconnected.outcome)
    }

    @Test
    fun `bounded health failures disconnect only after the configured consecutive limit`() {
        val agent = AgentId("agent-health")
        val harness =
            harness(
                agent = agent,
                options =
                    HarnessOptions(
                        healthCheckIntervalTicks = 1,
                        healthFailureLimit = 2,
                    ),
            )
        harness.port.spawn(agent).await()
        harness.supervisor.healthy = false

        harness.port.tick(tick.incrementAndGet())

        assertEquals(BodyLifecycle.READY, harness.port.inspect(agent)?.state)
        assertEquals(1, harness.supervisor.healthChecks)

        harness.port.tick(tick.incrementAndGet())

        assertEquals(BodyLifecycle.DISCONNECTED, harness.port.inspect(agent)?.state)
        assertEquals(2, harness.supervisor.healthChecks)
        assertEquals(1, harness.supervisor.stopped.size)
        val disconnected = harness.records.single { it.event == BodyEvent.BODY_DISCONNECTED }
        assertEquals(EventTag.BODY_HEALTH_FAILED, disconnected.outcome)
    }

    @Test
    fun `configured fleet startup never exceeds its concurrency bound`() {
        val agents = listOf("charlie", "alpha", "bravo").map(::AgentId)
        val gate = StartupGate()
        val server = FakePlayerServerState()
        val registered = CopyOnWriteArrayList<PlayerIdentity>()
        val records = CopyOnWriteArrayList<BodyEventRecord>()
        val resources = mutableListOf<Pair<ExecutorService, ScheduledExecutorService>>()
        val infrastructures =
            agents.sortedBy { it.value }.mapIndexed { index, agent ->
                val identity = playerIdentityOf(agent)
                val endpoint = BodyControlEndpoint("127.0.0.1", 26_000 + index)
                val io = Executors.newFixedThreadPool(2)
                val scheduler = Executors.newSingleThreadScheduledExecutor()
                resources += io to scheduler
                PlayerBodyInfrastructure(
                    identity = identity,
                    settings =
                        playerSettings(
                            identity,
                            endpoint,
                            ioRoot = temporary.resolve("gated"),
                        ),
                    supervisor = GatedBodySupervisor(endpoint, gate),
                    connector = FakeControlConnector(),
                    requestIds = BodyControlRequestIds(scope = identity.username),
                    io = io,
                    scheduler = scheduler,
                )
            }
        val port =
            PlayerBodyPort(
                infrastructures = infrastructures,
                authority =
                    PlayerBodyAuthority(
                        main = { task -> task.run() },
                        registerIdentity = registered::add,
                        serverState = server,
                        eventLog = BodyEventLog(records::add),
                        currentTick = tick::get,
                    ),
                startupConcurrency = 2,
            ).also(extraPorts::add)

        val startup = port.configuredStartup

        assertEquals(listOf(AgentId("alpha"), AgentId("bravo")), gate.started)
        assertEquals(2, gate.maximumActive)
        assertFalse(startup.toCompletableFuture().isDone)

        gate.release(AgentId("alpha"))
        awaitPortCondition { gate.started.size == 3 }
        gate.release(AgentId("bravo"))
        gate.release(AgentId("charlie"))

        assertEquals(3, startup.await().ready)
        assertEquals(2, gate.maximumActive)
        assertEquals(3, registered.size)
        resources.forEach { (io, scheduler) ->
            assertFalse(io.isShutdown)
            assertFalse(scheduler.isShutdown)
        }
    }

    @Test
    @Suppress("LongMethod")
    fun `three-agent disposable proof isolates decisions refs crashes death and evidence`() {
        val agents = listOf("proof-alpha", "proof-beta", "proof-gamma").map(::AgentId)
        val harness = fleetHarness(agents)
        val startup = harness.port.configuredStartup.await()
        assertEquals(3, startup.ready)
        assertEquals(3, harness.port.liveBodies().size)

        val applied = CountDownLatch(3)
        val cognition =
            EmbodiedCognition(
                bodies = harness.port,
                mind =
                    CognitionBridge { perception ->
                        val intent =
                            when (perception.agentId) {
                                // A zombie three blocks off is immediate danger: only a survival
                                // response is taken beside it, and the flight is the proof's action.
                                "proof-alpha" ->
                                    Intent.Flee(perception.surroundings.first { it.kind == ObservationKind.ENTITY }.ref)
                                "proof-beta" ->
                                    Intent.Eat(
                                        perception.selfState.inventorySummary.single().ref,
                                    )
                                else -> Intent.Idle
                            }
                        CompletableFuture.completedFuture(decisionFor(perception, intent))
                    },
                mainThread = MainThread { task -> task() },
                journal = DecisionJournal(BodyEventLog(harness.records::add), tick::get),
                callbacks = CognitionCallbacks(onApplied = { applied.countDown() }),
            )

        val alphaPerception =
            requireNotNull(cognition.consider(agents[0], Trigger.GoalReview).await())
        val alphaStack = alphaPerception.selfState.inventorySummary.single()
        val crossAgentSpend =
            harness.port
                .apply(
                    agents[1],
                    Intent.Eat(alphaStack.ref),
                    alphaPerception.requestId,
                ).await()
        assertTrue(crossAgentSpend is IntentOutcome.Rejected)

        cognition.consider(agents[1], Trigger.GoalReview).await()
        cognition.consider(agents[2], Trigger.GoalReview).await()
        assertTrue(applied.await(PORT_TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))

        val perceivedIds =
            harness.records
                .filter { it.event == BodyEvent.PERCEIVED }
                .mapNotNull(BodyEventRecord::requestId)
        assertEquals(perceivedIds.size, perceivedIds.distinct().size)
        assertAgentChain(
            harness.records,
            agents[0],
            setOf(BodyEvent.PERCEIVED, BodyEvent.DECIDED, BodyEvent.INTENT_APPLIED, BodyEvent.RETREATING),
        )
        assertAgentChain(
            harness.records,
            agents[1],
            setOf(
                BodyEvent.PERCEIVED,
                BodyEvent.DECIDED,
                BodyEvent.INTENT_APPLIED,
                BodyEvent.ENERGY_CHANGED,
            ),
        )
        assertAgentChain(
            harness.records,
            agents[2],
            setOf(BodyEvent.PERCEIVED, BodyEvent.DECIDED, BodyEvent.INTENT_APPLIED),
        )

        harness.supervisors.getValue(agents[0]).processes.single().stop(code = 17)
        assertEquals(BodyLifecycle.DISCONNECTED, harness.port.inspect(agents[0])?.state)
        assertEquals(BodyLifecycle.READY, harness.port.inspect(agents[1])?.state)
        assertEquals(BodyLifecycle.READY, harness.port.inspect(agents[2])?.state)

        val betaIdentity = playerIdentityOf(agents[1])
        WitnessJournal(BodyEventLog(harness.records::add)).died(
            subjectOf(agents[1], betaIdentity.uuid, betaIdentity.username),
            "proof-beta was killed by the disposable harness",
            tick.incrementAndGet(),
        )
        synchronized(harness.server.dead) {
            harness.server.dead += agents[1]
        }
        harness.port.tick(tick.incrementAndGet())

        assertEquals(BodyLifecycle.DEAD, harness.port.inspect(agents[1])?.state)
        assertEquals(BodyLifecycle.READY, harness.port.inspect(agents[2])?.state)
        assertTrue(harness.port.despawn(agents[2]).await())
        assertEquals(BodyLifecycle.DISCONNECTED, harness.port.inspect(agents[2])?.state)

        val death = harness.records.single { it.event == BodyEvent.DIED }
        assertEquals(agents[1].value, death.agentId)
        assertEquals(betaIdentity.username, death.witness?.username)
        assertEquals(betaIdentity.uuid.toString(), death.witness?.uuid)
        assertEquals(
            setOf(agents[0].value, agents[1].value, agents[2].value),
            harness.records.map(BodyEventRecord::agentId).toSet(),
        )
        harness.connectors.forEach { (agent, connector) ->
            assertTrue(
                connector.requests.all { it.agentId == agent.value },
                "$agent received another agent's control request",
            )
        }
    }

    @Test
    fun `a busy movement refusal leaves the healthy player process ready`() {
        var refuseMovement = true
        val agent = AgentId("agent-busy")
        val harness =
            harness(
                agent = agent,
                options =
                    HarnessOptions(
                        intercept = { request ->
                            if (refuseMovement && request is BodyRequest.MoveTo) {
                                throw BodyControlFailure.Refused(ErrorCode.NOT_READY)
                            }
                            null
                        },
                    ),
            )
        harness.port.spawn(agent).await()
        val perception = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())

        val refused =
            harness.port
                .apply(
                    agent,
                    walkOnto(perception),
                    perception.requestId,
                ).await()

        assertEquals(
            IntentOutcome.Rejected(
                "the player body was not ready for another operation",
                EventTag.CONTROL_NOT_READY,
            ),
            refused,
        )
        assertEquals(BodyLifecycle.READY, harness.port.inspect(agent)?.state)
        assertTrue(harness.supervisor.processes.single().isAlive())
        assertTrue(harness.supervisor.stopped.isEmpty())

        refuseMovement = false
        val again = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        assertTrue(
            harness.port
                .apply(agent, walkOnto(again), again.requestId)
                .await() is IntentOutcome.Accepted,
        )
    }

    @Test
    fun `repeated senses of one server subject spend the existing threat debounce`() {
        val agent = AgentId("agent-threat")
        val harness = harness(agent)
        harness.port.spawn(agent).await()

        val first = harness.port.senseThreat(agent).await()
        val repeated = harness.port.senseThreat(agent).await()

        assertTrue(first?.trigger is Trigger.ThreatSighted)
        assertEquals(null, repeated)
        assertTrue(
            harness.records.any {
                it.event == BodyEvent.THREAT_SUPPRESSED &&
                    it.outcome == EventTag.THREAT_DEBOUNCED
            },
        )
    }

    @Test
    fun `server death is terminal and no process outcome can manufacture or undo it`() {
        val agent = AgentId("agent-terminal")
        val harness = harness(agent)
        harness.port.spawn(agent).await()
        synchronized(harness.server.dead) {
            harness.server.dead += agent
        }

        harness.port.tick(tick.incrementAndGet())

        assertEquals(BodyLifecycle.DEAD, harness.port.inspect(agent)?.state)
        assertEquals(1, harness.supervisor.stopped.size)
        val retry = harness.port.spawn(agent).await()
        assertTrue(retry is BodySpawnResult.Rejected)
        assertEquals(
            EventTag.HAS_DIED,
            harness.records.last { it.event == BodyEvent.SPAWN_REJECTED }.outcome,
        )
        assertEquals(1, harness.supervisor.starts.size)
    }

    @Test
    fun `an unconfigured identity is refused without touching a configured process`() {
        val configured = AgentId("agent-one")
        val harness = harness(configured)
        harness.port.spawn(configured).await()

        val second = harness.port.spawn(AgentId("agent-two")).await()

        assertTrue(second is BodySpawnResult.Rejected)
        assertEquals(1, harness.supervisor.starts.size)
        assertEquals(
            EventTag.BODY_START_FAILED,
            harness.records.last { it.event == BodyEvent.SPAWN_REJECTED }.outcome,
        )
    }

    @Test
    fun `a failed process cleanup is surfaced and is never recorded as a despawn`() {
        val agent = AgentId("agent-stop-failure")
        val harness = harness(agent)
        harness.port.spawn(agent).await()
        val failure = BodySupervisorFailure(BodySupervisorFailureKind.CONTROL_UNAVAILABLE)
        harness.supervisor.stopFailure = failure

        val thrown =
            assertThrows<ExecutionException> {
                harness.port.despawn(agent).await()
            }

        assertEquals(failure, thrown.cause)
        assertEquals(BodyLifecycle.FAILED, harness.port.inspect(agent)?.state)
        assertEquals("player process cleanup failed", harness.port.inspect(agent)?.detail)
        assertTrue(harness.supervisor.processes.single().isAlive())
        assertFalse(harness.records.any { it.event == BodyEvent.DESPAWNED })
    }

    @Test
    fun `plugin shutdown closes held sense sessions workers scheduler and child process`() {
        val agent = AgentId("agent-cleanup")
        val harness = harness(agent)
        harness.port.spawn(agent).await()
        harness.port.perceive(agent, Trigger.GoalReview).await()
        assertTrue(harness.connector.connections.any { !it.isClosed() })

        harness.port.close()

        assertTrue(harness.connector.connections.all(FakeControlConnection::isClosed))
        assertTrue(harness.supervisor.closed)
        assertTrue(harness.supervisor.processes.none(FakeBodyProcess::isAlive))
        assertTrue(harness.io.isShutdown)
        assertTrue(harness.scheduler.isShutdown)
        val despawned = harness.records.single { it.event == BodyEvent.DESPAWNED }
        assertEquals(agent.value, despawned.agentId)
    }

    private fun harness(
        agent: AgentId,
        options: HarnessOptions = HarnessOptions(),
    ): PortHarness = buildPortHarness(temporary, tick, agent, options).also(harnesses::add)

    private fun fleetHarness(agents: List<AgentId>): FleetPortHarness {
        val server = FakePlayerServerState()
        val registered = CopyOnWriteArrayList<PlayerIdentity>()
        val records = CopyOnWriteArrayList<BodyEventRecord>()
        val supervisors = linkedMapOf<AgentId, FakeBodySupervisor>()
        val connectors = linkedMapOf<AgentId, FakeControlConnector>()
        val io = mutableListOf<ExecutorService>()
        val schedulers = mutableListOf<ScheduledExecutorService>()
        val infrastructures =
            agents.sortedBy { it.value }.mapIndexed { index, agent ->
                val identity = playerIdentityOf(agent)
                val endpoint = BodyControlEndpoint("127.0.0.1", 27_000 + index)
                val supervisor = FakeBodySupervisor(endpoint)
                val connector = FakeControlConnector()
                val worker = Executors.newFixedThreadPool(2)
                val scheduler = Executors.newSingleThreadScheduledExecutor()
                supervisors[agent] = supervisor
                connectors[agent] = connector
                io += worker
                schedulers += scheduler
                PlayerBodyInfrastructure(
                    identity = identity,
                    settings =
                        playerSettings(
                            identity,
                            endpoint,
                            ioRoot = temporary.resolve("fleet"),
                        ),
                    supervisor = supervisor,
                    connector = connector,
                    requestIds = BodyControlRequestIds(scope = identity.username),
                    io = worker,
                    scheduler = scheduler,
                )
            }
        val port =
            PlayerBodyPort(
                infrastructures = infrastructures,
                authority =
                    PlayerBodyAuthority(
                        main = { task -> task.run() },
                        registerIdentity = registered::add,
                        serverState = server,
                        eventLog = BodyEventLog(records::add),
                        currentTick = tick::get,
                    ),
                startupConcurrency = 2,
            )
        return FleetPortHarness(
            port,
            supervisors,
            connectors,
            server,
            registered,
            records,
            io,
            schedulers,
        ).also(fleetHarnesses::add)
    }

    private fun playerSettings(
        identity: PlayerIdentity,
        endpoint: BodyControlEndpoint,
        ioRoot: Path,
    ): PlayerBodySettings =
        PlayerBodySettings(
            bodyDirectory = temporary.toAbsolutePath(),
            processDirectory = ioRoot.resolve(identity.username).toAbsolutePath(),
            controlEndpoint = endpoint,
            connectTimeout = Duration.ofMillis(100),
            requestTimeout = Duration.ofMillis(100),
            movementTimeout = Duration.ofMillis(200),
            startupTimeout = Duration.ofSeconds(1),
            shutdownTimeout = Duration.ofMillis(200),
        )
}

class PlayerSceneSelectionIntegrationTest {
    @TempDir
    lateinit var temporary: Path

    private val harnesses = mutableListOf<PortHarness>()
    private val tick = AtomicLong(400)

    @AfterEach
    fun cleanUp() {
        harnesses.forEach { runCatching(it.port::close) }
    }

    @Test
    fun `crowded perception keeps category refs omissions and audit arithmetic consistent`() {
        val agent = AgentId("agent-category-fair")
        val harness = crowdedHarness(agent)
        harness.port.spawn(agent).await()

        val first = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val entity = first.surroundings.first { it.kind == ObservationKind.ENTITY }
        val item = first.surroundings.first { it.kind == ObservationKind.DROPPED_ITEM }
        // The record is written as the mind is shown the perception, as cognition shows it.
        harness.port.held.shown(agent, first)
        assertCrowdedAudit(harness, first.requestId)
        assertEquals(PERCEPTION_MAX_OBSERVATIONS, first.surroundings.size)
        for (kind in ObservationKind.entries) {
            assertEquals(
                BODY_SENSE_MAX_OFFERS_PER_KIND,
                first.surroundings.count { it.kind == kind },
            )
        }
        assertEquals(PERCEPTION_MAX_OBSERVATIONS, first.surroundings.map { it.ref }.distinct().size)
        assertEquals(9, first.surroundingsOmitted)
        val senseRequest = harness.connector.requests.filterIsInstance<BodyRequest.Sense>().first()
        assertEquals(BODY_SENSE_MAX_OFFERS_PER_KIND, senseRequest.limit)

        val second = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val wrongRequest =
            harness.port
                .apply(agent, Intent.Collect(item.ref, approach = false), second.requestId)
                .await() as IntentOutcome.Rejected
        assertEquals(EventTag.COLLECT_ITEM_UNNAMED, wrongRequest.tag)
        assertEquals(
            IntentOutcome.Accepted("collected 1 minecraft:apple"),
            harness.port
                .apply(agent, Intent.Collect(item.ref, approach = false), first.requestId)
                .await(),
        )

        val collect = harness.connector.requests.filterIsInstance<BodyRequest.Collect>().single()
        assertEquals(first.requestId, collect.senseId)
        assertEquals(0, collect.item)
        assertTrue(entity.ref.startsWith("e"))
        assertTrue(item.ref.startsWith("d"))
    }

    @Test
    fun `threat scanning sees a hostile behind forty-eight closer blocks`() {
        val agent = AgentId("agent-crowded-threat")
        val harness = crowdedHarness(agent)
        harness.port.spawn(agent).await()

        val perception = requireNotNull(harness.port.senseThreat(agent).await())
        val trigger = perception.trigger as Trigger.ThreatSighted
        val entity = perception.surroundings.single { it.ref == trigger.ref }

        assertEquals("entity.minecraft.zombie", trigger.threat)
        assertEquals(entity.ref, trigger.ref)
        // The player body makes no route search toward a threat, so it states none (r20: a
        // constant false read as no route to flee).
        assertEquals(null, trigger.reachable)
        // The hunter is marked as one, so a goal review may flee from it (r20 kappa).
        assertEquals(true, entity.hunts)
        assertEquals(
            BODY_SENSE_MAX_OFFERS_PER_KIND,
            perception.surroundings.count { it.kind == ObservationKind.BLOCK },
        )
        assertEquals(
            BODY_SENSE_MAX_OFFERS_PER_KIND,
            perception.surroundings.count { it.kind == ObservationKind.ENTITY },
        )
        assertEquals(
            BODY_SENSE_MAX_OFFERS_PER_KIND,
            perception.surroundings.count { it.kind == ObservationKind.DROPPED_ITEM },
        )
        assertTrue(
            harness.port
                .apply(agent, Intent.Flee(trigger.ref), perception.requestId)
                .await() is IntentOutcome.Accepted,
        )
        val flee = harness.connector.requests.filterIsInstance<BodyRequest.Flee>().single()
        assertEquals(perception.requestId, flee.senseId)
        assertEquals(0, flee.entity)
        // Every other hunter of the answer is named for the flight to end clear of, at most 16
        // (r24: gamma's flight from a spider ended 0.6 blocks from a creeper).
        assertEquals((1..dev.loom.bodycontrol.MAX_FLIGHT_AVOID).toList(), flee.avoid)
        // The retreat record states the hunters it was told to avoid and how near it ended to any.
        awaitPortCondition { harness.records.any { it.event == BodyEvent.RETREATING } }
        val retreat = requireNotNull(harness.records.last { it.event == BodyEvent.RETREATING }.retreat)
        assertEquals(dev.loom.bodycontrol.MAX_FLIGHT_AVOID, retreat.avoided)
        assertTrue(retreat.leastSeparation != null)
    }

    @Test
    @Suppress("LongMethod")
    fun `dense translation mints every ref builds every hostile candidate and stays tick bounded`() {
        val agent = AgentId("agent-dense-translation")
        val identity = playerIdentityOf(agent)
        val endpoint = BodyControlEndpoint("127.0.0.1", 25_586)
        val io = Executors.newSingleThreadExecutor()
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val server = FakePlayerServerState()
        try {
            val context =
                PlayerBodyContext(
                    infrastructure =
                        PlayerBodyInfrastructure(
                            identity = identity,
                            settings =
                                PlayerBodySettings(
                                    bodyDirectory = temporary.toAbsolutePath(),
                                    processDirectory = temporary.resolve(identity.username).toAbsolutePath(),
                                    controlEndpoint = endpoint,
                                    connectTimeout = Duration.ofMillis(100),
                                    requestTimeout = Duration.ofMillis(100),
                                    movementTimeout = Duration.ofMillis(200),
                                    startupTimeout = Duration.ofSeconds(1),
                                    shutdownTimeout = Duration.ofMillis(200),
                                ),
                            supervisor = FakeBodySupervisor(endpoint),
                            connector = FakeControlConnector(),
                            requestIds = BodyControlRequestIds(scope = identity.username),
                            io = io,
                            scheduler = scheduler,
                        ),
                    authority =
                        PlayerBodyAuthority(
                            main = { task -> task.run() },
                            registerIdentity = {},
                            serverState = server,
                            eventLog = BodyEventLog {},
                            currentTick = tick::get,
                        ),
                )
            val translator = PlayerSceneTranslator(context)

            fun raw(requestId: String): RawSense {
                val request =
                    BodyRequest.Sense(
                        protocol = dev.loom.bodycontrol.BODY_CONTROL_PROTOCOL_VERSION,
                        requestId = requestId,
                        agentId = agent.value,
                        radius = 24,
                        limit = BODY_SENSE_MAX_OFFERS_PER_KIND,
                    )
                return RawSense(
                    identity = identity,
                    capturedTick = tick.get(),
                    auditSnapshot = server.captureSenseAudit(identity),
                    connection = FakeControlConnection(identity) { null },
                    report = crowdedSense(request),
                )
            }

            repeat(5) { translator.sceneOf(raw("warm-$it")) }
            var scene = translator.sceneOf(raw("dense-initial"))
            val samples = 20
            var slowestNanos = 0L
            val started = System.nanoTime()
            repeat(samples) { sample ->
                val sampleStarted = System.nanoTime()
                scene = translator.sceneOf(raw("dense-$sample"))
                slowestNanos = maxOf(slowestNanos, System.nanoTime() - sampleStarted)
            }
            val elapsedNanos = System.nanoTime() - started

            assertTrue(
                slowestNanos < Duration.ofMillis(50).toNanos(),
                "the slowest dense translation and ref-minting pass took " +
                    "${slowestNanos / 1_000_000.0} ms and occupied a whole server tick",
            )
            assertTrue(
                elapsedNanos < Duration.ofMillis(250).toNanos(),
                "$samples dense translations and ref-minting passes took " +
                    "${elapsedNanos / 1_000_000.0} ms",
            )
            assertEquals(PERCEPTION_MAX_OBSERVATIONS, scene.surroundings.size)
            assertEquals(BODY_SENSE_MAX_OFFERS_PER_KIND, scene.creatureBindings.size)
            assertEquals(BODY_SENSE_MAX_OFFERS_PER_KIND, scene.blockBindings.size)
            assertEquals(BODY_SENSE_MAX_OFFERS_PER_KIND, scene.droppedItemBindings.size)
            assertEquals(BODY_SENSE_MAX_OFFERS_PER_KIND, scene.candidates.size)
            assertTrue(scene.candidates.all { it.what == "entity.minecraft.zombie" })
            assertEquals(
                scene.surroundings
                    .filter { it.kind == ObservationKind.ENTITY }
                    .map { it.ref },
                scene.candidates.map { it.ref },
            )
            assertEquals(
                PERCEPTION_MAX_OBSERVATIONS,
                scene.surroundings.map { it.ref }.distinct().size,
            )
        } finally {
            io.shutdownNow()
            scheduler.shutdownNow()
        }
    }

    private fun crowdedHarness(agent: AgentId): PortHarness =
        buildPortHarness(
            temporary,
            tick,
            agent,
            HarnessOptions(
                intercept = { request ->
                    (request as? BodyRequest.Sense)?.let(::crowdedSense)
                },
            ),
        ).also(harnesses::add)

    private fun assertCrowdedAudit(
        harness: PortHarness,
        requestId: String,
    ) {
        val audit =
            requireNotNull(
                harness.records
                    .single {
                        it.event == BodyEvent.PERCEIVED &&
                            it.requestId == requestId
                    }.perceptionAudit,
            )
        assertEquals(48, audit.entities.rawBodyListCount)
        assertEquals(2, audit.entities.bodyReportedOmittedCount)
        assertEquals(48, audit.entities.jvmSelectedCount)
        assertEquals(0, audit.entities.jvmCutOmittedCount)
        assertEquals(48, audit.blocks.rawBodyListCount)
        assertEquals(3, audit.blocks.bodyReportedOmittedCount)
        assertEquals(48, audit.blocks.jvmSelectedCount)
        assertEquals(0, audit.blocks.jvmCutOmittedCount)
        assertEquals(48, audit.droppedItems.rawBodyListCount)
        assertEquals(4, audit.droppedItems.bodyReportedOmittedCount)
        assertEquals(48, audit.droppedItems.jvmSelectedCount)
        assertEquals(0, audit.droppedItems.jvmCutOmittedCount)
    }
}

class PlayerInteractionPortTest {
    @TempDir
    lateinit var temporary: Path

    private val harnesses = mutableListOf<PortHarness>()
    private val tick = AtomicLong(500)

    @AfterEach
    fun cleanUp() {
        harnesses.forEach { runCatching(it.port::close) }
    }

    @Test
    fun `native interactions retain typed refs control sessions server audits and evidence`() {
        val agent = AgentId("agent-interactions")
        val harness = interactionHarness(agent)
        harness.port.spawn(agent).await()

        val rounds = InteractionPortProof.perform(harness, agent)
        val requests = InteractionPortProof.requests(harness, rounds)
        InteractionPortProof.evidence(harness, agent, rounds, requests)
    }

    @Test
    fun `world refs fail closed across namespaces requests and settled decisions`() {
        val agent = AgentId("agent-refusal")
        val harness =
            harness(
                agent,
                HarnessOptions(
                    intercept = { request ->
                        (request as? BodyRequest.Sense)?.let(::interactionSense)
                    },
                ),
            )
        harness.port.spawn(agent).await()

        InteractionPortProof.rejectsWrongKind(harness, agent)
        InteractionPortProof.rejectsSurvivalWrongKinds(harness, agent)
        InteractionPortProof.rejectsExcessCraftQuantity(harness, agent)
        InteractionPortProof.rejectsSpentRef(harness, agent)
        InteractionPortProof.rejectsSpentRecipe(harness, agent)
        InteractionPortProof.rejectsWrongRequest(harness, agent)
        assertEquals(1, harness.connector.requests.count { it is BodyRequest.Dig })
        assertEquals(1, harness.connector.requests.count { it is BodyRequest.Craft })
    }

    @Test
    fun `combat refuses a client target the server did not corroborate`() {
        val agent = AgentId("agent-uncorroborated")
        val harness =
            harness(
                agent,
                HarnessOptions(
                    intercept = { request ->
                        (request as? BodyRequest.Sense)?.let(::interactionSense)
                    },
                ),
            )
        harness.server.correlateEntities = false
        harness.port.spawn(agent).await()

        val perception = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val target =
            perception.surroundings.single {
                it.what == "entity.minecraft.zombie"
            }
        val weapon =
            perception.selfState.inventorySummary.single {
                it.what == "item.minecraft.iron_sword"
            }
        val outcome =
            harness.port
                .apply(
                    agent,
                    Intent.Attack(target.ref, weapon.ref, attacks = 1, approach = false),
                    perception.requestId,
                ).await() as IntentOutcome.Rejected

        assertEquals(EventTag.ATTACK_TARGET_UNCORROBORATED, outcome.tag)
        assertTrue(harness.connector.requests.none { it is BodyRequest.Attack })
    }

    private fun interactionHarness(agent: AgentId): PortHarness =
        harness(
            agent,
            HarnessOptions(
                intercept = { request ->
                    when (request) {
                        is BodyRequest.Sense -> interactionSense(request)
                        is BodyRequest.Equip ->
                            BodyResponse.EquipReport(
                                protocol = request.protocol,
                                requestId = request.requestId,
                                agentId = request.agentId,
                                state = LifecycleState.READY,
                                outcome = EquipOutcome.EQUIPPED,
                                elapsedMs = 20,
                                what = "minecraft:iron_helmet",
                                count = 1,
                                toSlot = 5,
                            )
                        else -> null
                    }
                },
            ),
        )

    private fun harness(
        agent: AgentId,
        options: HarnessOptions,
    ): PortHarness = buildPortHarness(temporary, tick, agent, options).also(harnesses::add)
}

private data class InteractionRounds(
    val dig: String,
    val collect: String,
    val place: String,
    val equip: String,
    val craft: String,
    val attack: String,
    val sleep: String,
) {
    val all: List<String> = listOf(dig, collect, place, equip, craft, attack, sleep)
}

private data class InteractionRequests(
    val dig: BodyRequest.Dig,
    val collect: BodyRequest.Collect,
    val place: BodyRequest.Place,
    val equip: BodyRequest.Equip,
    val craft: BodyRequest.Craft,
    val attack: BodyRequest.Attack,
    val sleep: BodyRequest.Sleep,
) {
    val all: List<BodyRequest> = listOf(dig, collect, place, equip, craft, attack, sleep)
}

private object InteractionPortProof {
    fun perform(
        harness: PortHarness,
        agent: AgentId,
    ): InteractionRounds =
        InteractionRounds(
            dig = dig(harness, agent),
            collect = collect(harness, agent),
            place = place(harness, agent),
            equip = equip(harness, agent),
            craft = craft(harness, agent),
            attack = attack(harness, agent),
            sleep = sleep(harness, agent),
        )

    fun requests(
        harness: PortHarness,
        rounds: InteractionRounds,
    ): InteractionRequests {
        val requests =
            InteractionRequests(
                harness.connector.requests.filterIsInstance<BodyRequest.Dig>().single(),
                harness.connector.requests.filterIsInstance<BodyRequest.Collect>().single(),
                harness.connector.requests.filterIsInstance<BodyRequest.Place>().single(),
                harness.connector.requests.filterIsInstance<BodyRequest.Equip>().single(),
                harness.connector.requests.filterIsInstance<BodyRequest.Craft>().single(),
                harness.connector.requests.filterIsInstance<BodyRequest.Attack>().single(),
                harness.connector.requests.filterIsInstance<BodyRequest.Sleep>().single(),
            )
        assertEquals(rounds.dig, requests.dig.senseId)
        assertEquals(0, requests.dig.block)
        assertEquals(rounds.collect, requests.collect.senseId)
        assertEquals(0, requests.collect.item)
        assertTrue(requests.collect.approach)
        assertEquals(rounds.place, requests.place.senseId)
        assertEquals(0, requests.place.block)
        assertEquals(36, requests.place.slot)
        assertEquals(dev.loom.bodycontrol.BlockFace.UP, requests.place.face)
        assertEquals(rounds.equip, requests.equip.senseId)
        assertEquals(38, requests.equip.slot)
        assertEquals(
            dev.loom.bodycontrol.EquipmentDestination.HEAD,
            requests.equip.destination,
        )
        assertEquals(rounds.craft, requests.craft.senseId)
        assertEquals(0, requests.craft.recipe)
        assertEquals(2, requests.craft.crafts)
        assertEquals(10_000, requests.craft.timeoutMs)
        assertEquals(rounds.attack, requests.attack.senseId)
        assertEquals(0, requests.attack.entity)
        assertEquals(39, requests.attack.slot)
        assertEquals(2, requests.attack.attacks)
        assertTrue(requests.attack.approach)
        assertEquals(7.5, requests.attack.maxPursuitDistance)
        assertEquals(4.5, requests.attack.minHealth)
        assertFalse(requests.attack.sprint)
        assertEquals(rounds.sleep, requests.sleep.senseId)
        assertEquals(1, requests.sleep.block)
        assertEquals(20_000, requests.sleep.timeoutMs)
        rounds.all.zip(requests.all).forEach { (senseId, request) ->
            assertRequestUsesSenseConnection(harness, senseId, request)
        }
        assertEquals(requests.all, harness.server.interactionAudits.map { it.second })
        return requests
    }

    fun evidence(
        harness: PortHarness,
        agent: AgentId,
        rounds: InteractionRounds,
        requests: InteractionRequests,
    ) {
        val records = harness.records.filter { it.event == BodyEvent.WORLD_INTERACTION }
        assertEquals(7, records.size)
        assertEquals(rounds.all, records.map(BodyEventRecord::requestId))
        records.zip(requests.all).forEach { (record, request) ->
            assertEquals(agent.value, record.agentId)
            assertEquals(playerIdentityOf(agent).uuid.toString(), record.witness?.uuid)
            assertEquals(playerIdentityOf(agent).username, record.witness?.username)
            assertEquals(EventTag.SERVER_EFFECT_CONFIRMED, record.outcome)
            assertTrue(record.detail.contains("sense ${record.requestId}"))
            assertTrue(record.detail.contains("control ${request.requestId}"))
            assertTrue(record.detail.contains("vanilla "))
            // An approaching interaction states where the body went against its target.
            val approaches = request is BodyRequest.Collect || request is BodyRequest.Attack
            assertEquals(
                approaches,
                Regex("; from \\S+ to \\S+ travelled \\S+; target at \\S+;").containsMatchIn(record.detail),
            )
            assertTrue(
                harness.records.indexOfFirst {
                    it.event == BodyEvent.INTENT_APPLIED &&
                        it.requestId == record.requestId
                } < harness.records.indexOf(record),
                "the intent verdict must precede the server-effect evidence",
            )
        }
    }

    fun rejectsWrongKind(
        harness: PortHarness,
        agent: AgentId,
    ) {
        val perception = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val itemRef =
            perception.surroundings.single {
                it.kind == ObservationKind.DROPPED_ITEM
            }.ref
        val outcome =
            harness.port
                .apply(agent, Intent.Dig(itemRef), perception.requestId)
                .await() as IntentOutcome.Rejected
        assertEquals(EventTag.DIG_BLOCK_UNNAMED, outcome.tag)
    }

    fun rejectsSpentRef(
        harness: PortHarness,
        agent: AgentId,
    ) {
        val perception = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val block =
            perception.surroundings.single {
                it.what == "block.minecraft.stone"
            }.ref
        assertTrue(
            harness.port
                .apply(agent, Intent.Dig(block), perception.requestId)
                .await() is IntentOutcome.Accepted,
        )
        val outcome =
            harness.port
                .apply(agent, Intent.Dig(block), perception.requestId)
                .await() as IntentOutcome.Rejected
        assertEquals(EventTag.DIG_SENSE_FORGOTTEN, outcome.tag)
    }

    fun rejectsSurvivalWrongKinds(
        harness: PortHarness,
        agent: AgentId,
    ) {
        val craftPerception =
            requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val weapon =
            craftPerception.selfState.inventorySummary.single {
                it.what == "item.minecraft.iron_sword"
            }
        val craft =
            harness.port
                .apply(agent, Intent.Craft(weapon.ref, 1), craftPerception.requestId)
                .await() as IntentOutcome.Rejected
        assertEquals(EventTag.CRAFT_RECIPE_UNNAMED, craft.tag)

        val sleepPerception =
            requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val target =
            sleepPerception.surroundings.single {
                it.what == "entity.minecraft.zombie"
            }
        val sleep =
            harness.port
                .apply(agent, Intent.Sleep(target.ref), sleepPerception.requestId)
                .await() as IntentOutcome.Rejected
        assertEquals(EventTag.SLEEP_BED_UNNAMED, sleep.tag)

        val attackPerception =
            requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val bed =
            attackPerception.surroundings.single {
                it.what == "block.minecraft.red_bed"
            }
        val attackWeapon =
            attackPerception.selfState.inventorySummary.single {
                it.what == "item.minecraft.iron_sword"
            }
        val attack =
            harness.port
                .apply(
                    agent,
                    Intent.Attack(bed.ref, attackWeapon.ref, attacks = 1, approach = false),
                    attackPerception.requestId,
                ).await() as IntentOutcome.Rejected
        assertEquals(EventTag.ATTACK_TARGET_UNNAMED, attack.tag)
    }

    fun rejectsExcessCraftQuantity(
        harness: PortHarness,
        agent: AgentId,
    ) {
        val perception = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val recipe = perception.selfState.craftingOptions.single()
        val outcome =
            harness.port
                .apply(agent, Intent.Craft(recipe.ref, 3), perception.requestId)
                .await() as IntentOutcome.Rejected
        assertEquals(EventTag.CRAFT_QUANTITY_EXCEEDED, outcome.tag)
    }

    fun rejectsSpentRecipe(
        harness: PortHarness,
        agent: AgentId,
    ) {
        val perception = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val recipe = perception.selfState.craftingOptions.single()
        assertTrue(
            harness.port
                .apply(agent, Intent.Craft(recipe.ref, 1), perception.requestId)
                .await() is IntentOutcome.Accepted,
        )
        val outcome =
            harness.port
                .apply(agent, Intent.Craft(recipe.ref, 1), perception.requestId)
                .await() as IntentOutcome.Rejected
        assertEquals(EventTag.CRAFT_SENSE_FORGOTTEN, outcome.tag)
    }

    fun rejectsWrongRequest(
        harness: PortHarness,
        agent: AgentId,
    ) {
        val old = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val block =
            old.surroundings.single {
                it.what == "block.minecraft.stone"
            }.ref
        val current = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val outcome =
            harness.port
                .apply(agent, Intent.Dig(block), current.requestId)
                .await() as IntentOutcome.Rejected
        assertEquals(EventTag.DIG_BLOCK_UNNAMED, outcome.tag)
    }

    private fun dig(
        harness: PortHarness,
        agent: AgentId,
    ): String {
        val perception = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val block =
            perception.surroundings.single {
                it.what == "block.minecraft.stone"
            }
        assertEquals(
            IntentOutcome.Accepted("dug the offered block"),
            harness.port.apply(agent, Intent.Dig(block.ref), perception.requestId).await(),
        )
        return perception.requestId
    }

    private fun collect(
        harness: PortHarness,
        agent: AgentId,
    ): String {
        val perception = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val item =
            perception.surroundings.single {
                it.kind == ObservationKind.DROPPED_ITEM
            }
        assertEquals(
            IntentOutcome.Accepted("collected 1 minecraft:apple"),
            harness.port
                .apply(agent, Intent.Collect(item.ref, approach = true), perception.requestId)
                .await(),
        )
        return perception.requestId
    }

    private fun place(
        harness: PortHarness,
        agent: AgentId,
    ): String {
        val perception = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val support =
            perception.surroundings.single {
                it.what == "block.minecraft.stone"
            }
        val dirt =
            perception.selfState.inventorySummary.single {
                it.what == "item.minecraft.dirt"
            }
        assertEquals(
            IntentOutcome.Accepted("placed minecraft:dirt at 2,64,2"),
            harness.port
                .apply(
                    agent,
                    Intent.Place(support.ref, dirt.ref, BlockFace.UP),
                    perception.requestId,
                ).await(),
        )
        return perception.requestId
    }

    private fun equip(
        harness: PortHarness,
        agent: AgentId,
    ): String {
        val perception = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val helmet =
            perception.selfState.inventorySummary.single {
                it.what == "item.minecraft.iron_helmet"
            }
        assertEquals(
            IntentOutcome.Accepted("equipped 1 minecraft:iron_helmet in slot 5"),
            harness.port
                .apply(
                    agent,
                    Intent.Equip(helmet.ref, EquipmentDestination.HEAD),
                    perception.requestId,
                ).await(),
        )
        return perception.requestId
    }

    private fun craft(
        harness: PortHarness,
        agent: AgentId,
    ): String {
        val perception = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val recipe = perception.selfState.craftingOptions.single()
        assertEquals(
            IntentOutcome.Accepted("crafted 8 minecraft:oak_planks in 2 application(s)"),
            harness.port
                .apply(agent, Intent.Craft(recipe.ref, crafts = 2), perception.requestId)
                .await(),
        )
        return perception.requestId
    }

    private fun attack(
        harness: PortHarness,
        agent: AgentId,
    ): String {
        val perception = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val target =
            perception.surroundings.single {
                it.what == "entity.minecraft.zombie"
            }
        val weapon =
            perception.selfState.inventorySummary.single {
                it.what == "item.minecraft.iron_sword"
            }
        assertEquals(
            IntentOutcome.Accepted("completed 2 bounded attack(s)"),
            harness.port
                .apply(
                    agent,
                    Intent.Attack(target.ref, weapon.ref, attacks = 2, approach = true),
                    perception.requestId,
                ).await(),
        )
        return perception.requestId
    }

    private fun sleep(
        harness: PortHarness,
        agent: AgentId,
    ): String {
        val perception = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val bed =
            perception.surroundings.single {
                it.what == "block.minecraft.red_bed"
            }
        assertEquals(
            IntentOutcome.Accepted("slept until vanilla woke the player"),
            harness.port
                .apply(agent, Intent.Sleep(bed.ref), perception.requestId)
                .await(),
        )
        return perception.requestId
    }
}

private fun buildPortHarness(
    temporary: Path,
    tick: AtomicLong,
    agent: AgentId,
    options: HarnessOptions,
): PortHarness {
    val endpoint = BodyControlEndpoint("127.0.0.1", 25_585)
    val supervisor = FakeBodySupervisor(endpoint, options.beforeStart)
    val connector = FakeControlConnector(options.intercept)
    val records = CopyOnWriteArrayList<BodyEventRecord>()
    val io = Executors.newFixedThreadPool(2)
    val scheduler = Executors.newSingleThreadScheduledExecutor()
    val identity = playerIdentityOf(agent)
    val port =
        PlayerBodyPort(
            infrastructures =
                listOf(
                    PlayerBodyInfrastructure(
                        identity = identity,
                        settings =
                            PlayerBodySettings(
                                bodyDirectory = temporary.toAbsolutePath(),
                                processDirectory = temporary.resolve(identity.username).toAbsolutePath(),
                                controlEndpoint = endpoint,
                                connectTimeout = Duration.ofMillis(100),
                                requestTimeout = Duration.ofMillis(100),
                                movementTimeout = Duration.ofMillis(200),
                                startupTimeout = Duration.ofSeconds(1),
                                shutdownTimeout = Duration.ofMillis(200),
                                healthCheckIntervalTicks = options.healthCheckIntervalTicks,
                                healthFailureLimit = options.healthFailureLimit,
                            ),
                        supervisor = supervisor,
                        connector = connector,
                        requestIds = BodyControlRequestIds(scope = identity.username),
                        io = io,
                        scheduler = scheduler,
                    ),
                ),
            authority =
                PlayerBodyAuthority(
                    main = { task -> task.run() },
                    registerIdentity = options.registered::add,
                    serverState = options.server,
                    eventLog = BodyEventLog(records::add),
                    currentTick = tick::get,
                    ground = FakeGround(options.server),
                    yields = options.yields,
                    ownDrops = options.ownDrops,
                ),
            startupConcurrency = 1,
        )
    return PortHarness(
        port = port,
        supervisor = supervisor,
        connector = connector,
        server = options.server,
        registered = options.registered,
        records = records,
        io = io,
        scheduler = scheduler,
    )
}

private fun assertAgentChain(
    records: List<BodyEventRecord>,
    agentId: AgentId,
    expected: Set<BodyEvent>,
) {
    val chain = records.filter { it.agentId == agentId.value }
    assertTrue(chain.all { it.agentId == agentId.value })
    assertTrue(chain.map(BodyEventRecord::event).containsAll(expected), "$agentId chain was $chain")
}

private fun awaitPortCondition(condition: () -> Boolean) {
    val deadline = System.nanoTime() + PORT_TEST_TIMEOUT.toNanos()
    while (!condition()) {
        check(System.nanoTime() < deadline) { "condition did not become true before the test deadline" }
        Thread.sleep(5)
    }
}

private fun fakeResponse(
    request: BodyRequest,
    handled: Int,
): BodyResponse =
    when (request) {
        is BodyRequest.Sense -> fakeSense(request)
        is BodyRequest.MoveTo -> fakeMove(request)
        is BodyRequest.Flee -> fakeFlee(request)
        is BodyRequest.Eat -> fakeEat(request)
        is BodyRequest.Status -> fakeStatus(request, handled)
        is BodyRequest.Halt -> fakeHalt(request)
        is BodyRequest.Say -> fakeSay(request)
        else -> fakeInteractionResponse(request)
    }

private fun fakeInteractionResponse(request: BodyRequest): BodyResponse =
    when (request) {
        is BodyRequest.Dig -> fakeDig(request)
        is BodyRequest.Collect -> fakeCollect(request)
        is BodyRequest.Place -> fakePlace(request)
        is BodyRequest.Equip -> fakeEquip(request)
        is BodyRequest.Craft -> fakeCraft(request)
        is BodyRequest.Attack -> fakeAttack(request)
        is BodyRequest.Sleep -> fakeSleep(request)
        is BodyRequest.Handshake,
        is BodyRequest.Shutdown,
        is BodyRequest.Sense,
        is BodyRequest.MoveTo,
        is BodyRequest.Flee,
        is BodyRequest.Eat,
        is BodyRequest.Status,
        is BodyRequest.Halt,
        is BodyRequest.Say,
        -> error("the fake connector already represents a handshaken gameplay connection")
    }

private fun fakeSense(request: BodyRequest.Sense): BodyResponse.SenseReport =
    BodyResponse.SenseReport(
        protocol = request.protocol,
        requestId = request.requestId,
        agentId = request.agentId,
        state = LifecycleState.READY,
        position = SENSE_POSITION,
        vitals = SensedVitals(health = 18.0, food = 6, saturation = 1.5),
        time = SensedTime(timeOfDay = 6_000, day = 2, isDay = true),
        ground =
            SensedGround(
                standingIn = "minecraft:air",
                standingOn = "minecraft:stone",
            ),
        entities =
            listOf(
                SensedEntity(
                    what = "minecraft:zombie",
                    position = listOf(4.5, 64.0, 2.5),
                    visible = true,
                ),
            ),
        entitiesOmitted = 0,
        // The one block a walk can name: standing on it is [4, 64, 2].
        blocks = listOf(SensedBlock(what = "minecraft:stone", position = listOf(4, 63, 2), visible = true)),
        blocksOmitted = 0,
        items = emptyList(),
        itemsOmitted = 0,
        inventory =
            listOf(
                SensedStack(
                    slot = 36,
                    what = "minecraft:bread",
                    count = 3,
                ),
            ),
        recipes = emptyList(),
        recipesOmitted = 0,
    )

private fun crowdedSense(request: BodyRequest.Sense): BodyResponse.SenseReport =
    fakeSense(request).copy(
        entities =
            (0 until BODY_SENSE_MAX_OFFERS_PER_KIND).map { index ->
                SensedEntity(
                    what = "minecraft:zombie",
                    position =
                        listOf(
                            10.5 + (index % 4) * 0.1,
                            64.0,
                            2.5 + (index / 4) * 0.05,
                        ),
                    visible = true,
                )
            },
        entitiesOmitted = 2,
        blocks =
            (0 until BODY_SENSE_MAX_OFFERS_PER_KIND).map {
                SensedBlock(
                    what = "minecraft:stone",
                    position = listOf(1, 63, 2),
                    visible = true,
                )
            },
        blocksOmitted = 3,
        items =
            (0 until BODY_SENSE_MAX_OFFERS_PER_KIND).map { index ->
                SensedItem(
                    what = "minecraft:apple",
                    count = 1,
                    position =
                        listOf(
                            12.5 + (index % 4) * 0.1,
                            64.0,
                            2.5 + (index / 4) * 0.05,
                        ),
                    visible = true,
                )
            },
        itemsOmitted = 4,
    )

/** A walk onto the one block [perception] offered. */
private fun walkOnto(perception: Perception): Intent.MoveTo =
    Intent.MoveTo(perception.surroundings.first { it.kind == ObservationKind.BLOCK }.ref)

@Suppress("LongMethod")
private fun interactionSense(request: BodyRequest.Sense): BodyResponse.SenseReport =
    BodyResponse.SenseReport(
        protocol = request.protocol,
        requestId = request.requestId,
        agentId = request.agentId,
        state = LifecycleState.READY,
        position = SENSE_POSITION,
        vitals = SensedVitals(health = 20.0, food = 20, saturation = 5.0),
        time = SensedTime(timeOfDay = 6_000, day = 2, isDay = true),
        ground =
            SensedGround(
                standingIn = "minecraft:air",
                standingOn = "minecraft:stone",
            ),
        entities =
            listOf(
                SensedEntity(
                    what = "minecraft:zombie",
                    position = listOf(4.5, 64.0, 2.5),
                    visible = true,
                ),
            ),
        entitiesOmitted = 0,
        blocks =
            listOf(
                SensedBlock(
                    what = "minecraft:stone",
                    position = listOf(2, 63, 2),
                    visible = true,
                ),
                SensedBlock(
                    what = "minecraft:red_bed",
                    position = listOf(3, 63, 2),
                    visible = true,
                ),
            ),
        blocksOmitted = 0,
        items =
            listOf(
                SensedItem(
                    what = "minecraft:apple",
                    count = 1,
                    position = listOf(3.5, 64.0, 2.5),
                    visible = true,
                ),
            ),
        itemsOmitted = 0,
        inventory =
            listOf(
                SensedStack(slot = 36, what = "minecraft:dirt", count = 2),
                SensedStack(slot = 38, what = "minecraft:iron_helmet", count = 1),
                SensedStack(slot = 39, what = "minecraft:iron_sword", count = 1),
                SensedStack(slot = 40, what = "minecraft:oak_log", count = 2),
            ),
        recipes =
            listOf(
                SensedRecipe(
                    what = "minecraft:oak_planks",
                    resultCount = 4,
                    maxCrafts = 2,
                    requiresTable = false,
                ),
            ),
        recipesOmitted = 0,
    )

private fun fakeDig(request: BodyRequest.Dig): BodyResponse.DigReport =
    BodyResponse.DigReport(
        protocol = request.protocol,
        requestId = request.requestId,
        agentId = request.agentId,
        state = LifecycleState.READY,
        outcome = DigOutcome.DUG,
        elapsedMs = 20,
        attempted = true,
    )

private fun fakeCollect(request: BodyRequest.Collect): BodyResponse.CollectReport =
    BodyResponse.CollectReport(
        protocol = request.protocol,
        requestId = request.requestId,
        agentId = request.agentId,
        state = LifecycleState.READY,
        outcome = CollectOutcome.COLLECTED,
        from = SENSE_POSITION,
        position = SENSE_POSITION,
        travelled = 0.0,
        elapsedMs = 20,
        what = "minecraft:apple",
        count = 1,
    )

private fun fakePlace(request: BodyRequest.Place): BodyResponse.PlaceReport =
    BodyResponse.PlaceReport(
        protocol = request.protocol,
        requestId = request.requestId,
        agentId = request.agentId,
        state = LifecycleState.READY,
        outcome = PlaceOutcome.PLACED,
        elapsedMs = 20,
        what = "minecraft:dirt",
        position = listOf(2, 64, 2),
        remaining = 0,
    )

private fun fakeEquip(request: BodyRequest.Equip): BodyResponse.EquipReport =
    BodyResponse.EquipReport(
        protocol = request.protocol,
        requestId = request.requestId,
        agentId = request.agentId,
        state = LifecycleState.READY,
        outcome = EquipOutcome.EQUIPPED,
        elapsedMs = 20,
        what = "minecraft:bread",
        count = 3,
        toSlot = request.slot,
    )

private fun fakeCraft(request: BodyRequest.Craft): BodyResponse.CraftReport =
    BodyResponse.CraftReport(
        protocol = request.protocol,
        requestId = request.requestId,
        agentId = request.agentId,
        state = LifecycleState.READY,
        outcome = dev.loom.bodycontrol.CraftOutcome.CRAFTED,
        elapsedMs = 20,
        attempted = true,
        what = "minecraft:oak_planks",
        count = 4 * request.crafts,
        crafts = request.crafts,
    )

private fun fakeAttack(request: BodyRequest.Attack): BodyResponse.AttackReport =
    BodyResponse.AttackReport(
        protocol = request.protocol,
        requestId = request.requestId,
        agentId = request.agentId,
        state = LifecycleState.READY,
        outcome = dev.loom.bodycontrol.AttackOutcome.ATTACKED,
        from = SENSE_POSITION,
        position = SENSE_POSITION,
        travelled = 0.0,
        elapsedMs = 20,
        attacks = request.attacks,
    )

private fun fakeSleep(request: BodyRequest.Sleep): BodyResponse.SleepReport =
    BodyResponse.SleepReport(
        protocol = request.protocol,
        requestId = request.requestId,
        agentId = request.agentId,
        state = LifecycleState.READY,
        outcome = dev.loom.bodycontrol.SleepOutcome.WOKE,
        elapsedMs = 20,
        entered = true,
    )

private fun fakeMove(request: BodyRequest.MoveTo): BodyResponse.MoveReport =
    BodyResponse.MoveReport(
        protocol = request.protocol,
        requestId = request.requestId,
        agentId = request.agentId,
        state = LifecycleState.READY,
        outcome = MoveOutcome.ARRIVED,
        from = SENSE_POSITION,
        position = request.destination,
        travelled = distance(SENSE_POSITION, request.destination),
        elapsedMs = 250,
    )

private fun fakeFlee(request: BodyRequest.Flee): BodyResponse.FleeReport =
    BodyResponse.FleeReport(
        protocol = request.protocol,
        requestId = request.requestId,
        agentId = request.agentId,
        state = LifecycleState.READY,
        outcome = MoveOutcome.ARRIVED,
        from = SENSE_POSITION,
        position = listOf(-4.5, 64.0, 2.5),
        travelled = 6.0,
        elapsedMs = 500,
        separation = 9.0,
        separationGained = 6.0,
    )

private fun fakeEat(request: BodyRequest.Eat): BodyResponse.EatReport =
    BodyResponse.EatReport(
        protocol = request.protocol,
        requestId = request.requestId,
        agentId = request.agentId,
        state = LifecycleState.READY,
        outcome = EatOutcome.EATEN,
        vitals = SensedVitals(health = 18.0, food = 11, saturation = 6.0),
        what = "minecraft:bread",
        remaining = 2,
    )

private fun fakeSay(
    request: BodyRequest.Say,
    outcome: SayOutcome = SayOutcome.SENT,
): BodyResponse.SayReport =
    BodyResponse.SayReport(
        protocol = request.protocol,
        requestId = request.requestId,
        agentId = request.agentId,
        state = LifecycleState.READY,
        outcome = outcome,
    )

private fun fakeHalt(request: BodyRequest.Halt): BodyResponse.HaltReport =
    BodyResponse.HaltReport(
        protocol = request.protocol,
        requestId = request.requestId,
        agentId = request.agentId,
        state = LifecycleState.READY,
        stopped = false,
    )

private fun fakeStatus(
    request: BodyRequest.Status,
    handled: Int,
): BodyResponse.StatusReport =
    BodyResponse.StatusReport(
        protocol = request.protocol,
        requestId = request.requestId,
        agentId = request.agentId,
        state = LifecycleState.READY,
        uptimeMs = 100,
        stateMs = 80,
        requestsHandled = handled.toLong(),
    )

private fun assertRoundTripEvidence(
    harness: PortHarness,
    agent: AgentId,
    movementRequestId: String,
    eatingRequestId: String,
) {
    val move = harness.connector.requests.filterIsInstance<BodyRequest.MoveTo>().single()
    val eat = harness.connector.requests.filterIsInstance<BodyRequest.Eat>().single()
    assertEquals(agent.value, move.agentId)
    assertEquals(listOf(4.0, 64.0, 2.0), move.destination)
    assertEquals(eatingRequestId, eat.senseId)
    assertEquals(36, eat.slot)
    assertTrue(
        harness.connector.connections.any { connection ->
            connection.requests.any {
                it is BodyRequest.Sense && it.requestId == movementRequestId
            } && move in connection.requests
        },
        "the movement must use the sense session whose request the decision answered",
    )
    assertTrue(
        harness.connector.connections.any { connection ->
            connection.requests.any {
                it is BodyRequest.Sense && it.requestId == eatingRequestId
            } && eat in connection.requests
        },
        "the meal must use the exact sense session that offered its slot",
    )
    assertEquals(2, harness.server.senseAudits.size)
    assertEquals(listOf(move), harness.server.moveAudits.map { it.second })
    assertEquals(
        listOf(
            BodyEvent.SPAWNED,
            BodyEvent.PERCEIVED,
            BodyEvent.HEALTH_CHANGED,
            BodyEvent.INTENT_APPLIED,
            BodyEvent.MOVED,
            BodyEvent.PERCEIVED,
            BodyEvent.INTENT_APPLIED,
            BodyEvent.ENERGY_CHANGED,
        ),
        harness.records.map { it.event },
    )
    assertEquals(
        movementRequestId,
        harness.records.first { it.event == BodyEvent.INTENT_APPLIED }.requestId,
    )
    // The walk's terminal is joined to its decision by id and typed by how it ended.
    val moved = harness.records.single { it.event == BodyEvent.MOVED }
    assertEquals(movementRequestId, moved.requestId)
    assertEquals(EventTag.MOVEMENT_ARRIVED, moved.outcome)
}

private fun assertRequestUsesSenseConnection(
    harness: PortHarness,
    senseId: String,
    request: BodyRequest,
) {
    assertTrue(
        harness.connector.connections.any { connection ->
            connection.requests.any {
                it is BodyRequest.Sense && it.requestId == senseId
            } && request in connection.requests
        },
        "${request::class.simpleName} must use the retained connection that answered $senseId",
    )
}

private fun distance(
    from: List<Double>,
    to: List<Double>,
): Double {
    val deltas = from.zip(to) { left, right -> left - right }
    return kotlin.math.sqrt(deltas.sumOf { it * it })
}

private fun <T> CompletionStage<T>.await(): T =
    toCompletableFuture().get(PORT_TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)

/** How a walk named by a block resolves, and how the blocks it can name are placed. */
class PlayerWalkResolutionTest {
    @TempDir
    lateinit var temporary: Path

    private val harnesses = mutableListOf<PortHarness>()
    private val tick = AtomicLong(500)

    @AfterEach
    fun cleanUp() {
        harnesses.forEach { runCatching(it.port::close) }
    }

    private fun harness(
        agent: AgentId,
        options: HarnessOptions = HarnessOptions(),
    ): PortHarness = buildPortHarness(temporary, tick, agent, options).also(harnesses::add)

    @Test
    fun `a walk goes to the cell above the exact block its own request offered`() {
        val agent = AgentId("agent-walk")
        val harness = harness(agent)
        harness.port.spawn(agent).await()

        fun sensed() = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())

        // A creature's ref names no block, and a ref nothing issued names nothing: refused
        // before the body is asked for anything.
        for (named in listOf<
            (
                Perception,
            ) -> String,
            >({ seen -> seen.surroundings.single { it.kind == ObservationKind.ENTITY }.ref }, { "zz9" })) {
            val seen = sensed()
            val refused =
                harness.port.apply(
                    agent,
                    Intent.MoveTo(named(seen)),
                    seen.requestId,
                ).await() as IntentOutcome.Rejected
            assertEquals(EventTag.MOVE_BLOCK_UNNAMED, refused.tag)
        }
        // A block offered by an earlier request is not a later request's to spend, and a walk
        // no request asked for has no sense answer to resolve against.
        val earlier = sensed()
        val later = sensed()
        val stale = harness.port.apply(agent, walkOnto(earlier), later.requestId).await() as IntentOutcome.Rejected
        assertEquals(EventTag.MOVE_BLOCK_UNNAMED, stale.tag)
        val unasked = harness.port.apply(agent, walkOnto(sensed())).await() as IntentOutcome.Rejected
        assertEquals(EventTag.MOVE_SENSE_FORGOTTEN, unasked.tag)
        assertTrue(harness.connector.requests.none { it is BodyRequest.MoveTo })

        val current = sensed()
        val accepted = harness.port.apply(agent, walkOnto(current), current.requestId).await()
        assertTrue(accepted is IntentOutcome.Accepted, accepted.toString())
        val move = harness.connector.requests.filterIsInstance<BodyRequest.MoveTo>().single()
        // The sensed stone is at [4, 63, 2], so the walk is to stand on it: nothing else chosen.
        assertEquals(listOf(4.0, 64.0, 2.0), move.destination)
        assertEquals(0, move.tolerance)
    }

    @Test
    fun `a walk onto a block the server gives no collision ends in that block's own cell`() {
        val agent = AgentId("agent-walk-into")
        val server = FakePlayerServerState().apply { passable += listOf(4, 63, 2) }
        val harness = harness(agent, HarnessOptions(server = server))
        harness.port.spawn(agent).await()
        val perception = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())

        val accepted = harness.port.apply(agent, walkOnto(perception), perception.requestId).await()

        assertTrue(accepted is IntentOutcome.Accepted, accepted.toString())
        val move = harness.connector.requests.filterIsInstance<BodyRequest.MoveTo>().single()
        // The sensed block at [4, 63, 2] is one a body stands in, so the walk ends there.
        assertEquals(listOf(4.0, 63.0, 2.0), move.destination)
    }

    @Test
    fun `the block a body stands on and a plant it stands in are here rather than a compass point`() {
        val agent = AgentId("agent-standing")
        val harness =
            harness(
                agent,
                HarnessOptions(
                    intercept = { request ->
                        (request as? BodyRequest.Sense)?.let {
                            fakeSense(it).copy(
                                blocks =
                                    listOf(
                                        SensedBlock(
                                            what = "minecraft:terracotta",
                                            position = listOf(1, 63, 2),
                                            visible = true,
                                        ),
                                        SensedBlock(
                                            what = "minecraft:dead_bush",
                                            position = listOf(1, 64, 2),
                                            visible = true,
                                        ),
                                        SensedBlock(
                                            what = "minecraft:terracotta",
                                            position = listOf(1, 63, 1),
                                            visible = true,
                                        ),
                                    ),
                            )
                        }
                    },
                ),
            )
        harness.port.spawn(agent).await()
        val blocks =
            requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
                .surroundings
                .filter { it.kind == ObservationKind.BLOCK }
                .map { Triple(it.what, it.direction, it.verticalOffset) }
                .toSet()
        // The body stands at [1.5, 64, 2.5]: on the terracotta at [1, 63, 2], in the bush above
        // it, beside the terracotta at [1, 63, 1] one block north.
        assertEquals(
            setOf(
                Triple("block.minecraft.terracotta", Bearing.HERE, -0.5),
                Triple("block.minecraft.dead_bush", Bearing.HERE, 0.5),
                Triple("block.minecraft.terracotta", Bearing.NORTH, -0.5),
            ),
            blocks,
        )
        // Neither block in the agent's own spot is a walk, and nothing is asked of the body
        // for one; the neighbour still is.
        for (what in listOf("block.minecraft.terracotta", "block.minecraft.dead_bush")) {
            val seen = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
            val own = seen.surroundings.first { it.what == what && it.direction == Bearing.HERE }
            val refused = harness.port.apply(agent, Intent.MoveTo(own.ref), seen.requestId).await()
            assertEquals(EventTag.MOVE_OWN_SPOT, (refused as IntentOutcome.Rejected).tag)
        }
        assertTrue(harness.connector.requests.none { it is BodyRequest.MoveTo })
        val seen = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val beside = seen.surroundings.first { it.direction == Bearing.NORTH }
        val walked = harness.port.apply(agent, Intent.MoveTo(beside.ref), seen.requestId).await()
        assertTrue(walked is IntentOutcome.Accepted, walked.toString())
    }

    @Test
    fun `the block under feet in a shaft is offered as unsafe to dig and a dig of it is refused`() {
        // r13 eta at body:00000148 and 519: a 1x1 shaft it dug itself, every wall unstandable.
        val agent = AgentId("agent-shaft")
        val support = listOf(1, 63, 2)
        val walls = listOf(listOf(2, 63, 2), listOf(0, 63, 2), listOf(1, 63, 3), listOf(1, 63, 1))
        val server =
            FakePlayerServerState().apply {
                buried += walls
                buried += walls.map { listOf(it[0], 62, it[2]) }
            }
        val harness =
            harness(
                agent,
                HarnessOptions(
                    server = server,
                    intercept = { request ->
                        (request as? BodyRequest.Sense)?.let {
                            fakeSense(
                                it,
                            ).copy(blocks = listOf(SensedBlock("minecraft:yellow_terracotta", support, visible = true)))
                        }
                    },
                ),
            )
        harness.port.spawn(agent).await()
        val seen = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val underfoot = seen.surroundings.single { it.kind == ObservationKind.BLOCK }
        assertEquals(false, underfoot.digSafe)

        val refused = harness.port.apply(agent, Intent.Dig(underfoot.ref), seen.requestId).await()

        assertEquals(EventTag.DIG_WOULD_TRAP, (refused as IntentOutcome.Rejected).tag)
        assertTrue(harness.connector.requests.none { it is BodyRequest.Dig })
        // With one wall open to climb onto, the same dig is safe and is asked of the body.
        server.buried.remove(walls.first())
        val open = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        assertEquals(true, open.surroundings.single { it.kind == ObservationKind.BLOCK }.digSafe)
    }

    @Test
    fun `a ledge over open air and a pillar top are unsafe to dig out from under the body`() {
        // The r14 audit's ledge scenario: a support dig must never be offered where the hole it
        // leaves has no standable way out, whether the body would drop through it or be stuck in it.
        val support = listOf(1, 63, 2)
        val sides = listOf(listOf(2, 63, 2), listOf(0, 63, 2), listOf(1, 63, 3), listOf(1, 63, 1))
        // A ledge: one side is ground to climb onto, but the cell under the ledge block is open air,
        // so the dig drops the body through.
        val ledge = FakePlayerServerState().apply { passable += listOf(1, 62, 2) }
        // A pillar top: every side is open air over open air, nothing to climb back onto.
        val pillar =
            FakePlayerServerState().apply {
                buried += sides
                buried += sides.map { listOf(it[0], 62, it[2]) }
            }
        for ((name, server) in listOf("ledge" to ledge, "pillar" to pillar)) {
            val agent = AgentId("agent-$name")
            val harness =
                harness(
                    agent,
                    HarnessOptions(
                        server = server,
                        intercept = { request ->
                            (request as? BodyRequest.Sense)?.let {
                                fakeSense(
                                    it,
                                ).copy(blocks = listOf(SensedBlock("minecraft:stone", support, visible = true)))
                            }
                        },
                    ),
                )
            harness.port.spawn(agent).await()
            val seen = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
            val underfoot = seen.surroundings.single { it.kind == ObservationKind.BLOCK }
            assertEquals(false, underfoot.digSafe, name)
            val refused = harness.port.apply(agent, Intent.Dig(underfoot.ref), seen.requestId).await()
            assertEquals(EventTag.DIG_WOULD_TRAP, (refused as IntentOutcome.Rejected).tag, name)
            assertTrue(harness.connector.requests.none { it is BodyRequest.Dig }, name)
        }
    }

    @Test
    fun `a block observation states what digging it yields, from the server, for that player`() {
        // The r13 bushes yielded sticks and the terracotta nothing; the mind is now told which.
        val agent = AgentId("agent-yields")
        val asked = mutableListOf<Pair<List<Int>, PlayerIdentity>>()
        val yields =
            BlockYields { position, player ->
                asked += position to player
                listOf(BlockYield("item.minecraft.stick", 0, 2, 100))
            }
        val harness = harness(agent, HarnessOptions(yields = yields))
        harness.port.spawn(agent).await()
        val seen = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val block = seen.surroundings.single { it.kind == ObservationKind.BLOCK }
        assertEquals(listOf(BlockYield("item.minecraft.stick", 0, 2, 100)), block.yields)
        assertEquals(listOf(listOf(4, 63, 2) to playerIdentityOf(agent)), asked)
        assertEquals(true, block.digSafe)
    }

    @Test
    fun `a completed dig's server drop is attributed to its own request and told to the digger`() {
        // r14: beta's dig b4v5 dropped two sticks delta took before beta's next review, and beta
        // judged a dig that yielded as having yielded nothing.
        val agent = AgentId("agent-drops")
        val attributed = mutableListOf<Triple<String, List<Int>, Long>>()
        val drops =
            object : OwnDrops {
                val told = mutableListOf<OwnDropReading>()

                override fun attribute(
                    identity: PlayerIdentity,
                    requestId: String,
                    block: List<Int>,
                    sinceTick: Long,
                ): List<OwnDropReading> {
                    attributed += Triple(requestId, block, sinceTick)
                    val taken = OwnDropReading(requestId, "item.minecraft.cobblestone", 1, DropFate.TAKEN, null)
                    val lying =
                        OwnDropReading(requestId, "item.minecraft.stick", 2, DropFate.LYING, listOf(6.5, 64.0, 2.5))
                    told.addAll(0, listOf(taken, lying))
                    return listOf(taken, lying)
                }

                override fun of(identity: PlayerIdentity): List<OwnDropReading> = told.toList()
            }
        val harness =
            harness(
                agent,
                HarnessOptions(
                    intercept = { request -> (request as? BodyRequest.Sense)?.let(::interactionSense) },
                    ownDrops = drops,
                ),
            )
        harness.port.spawn(agent).await()
        val perception = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        assertEquals(emptyList<OwnDrop>(), perception.selfState.ownDrops)
        val stone = perception.surroundings.single { it.what == "block.minecraft.stone" }
        harness.port.apply(agent, Intent.Dig(stone.ref), perception.requestId).await()

        assertEquals(listOf(perception.requestId to listOf(2, 63, 2)), attributed.map { it.first to it.second })
        val interaction = harness.records.single { it.event == BodyEvent.WORLD_INTERACTION }
        assertTrue(
            interaction.detail.endsWith("; dropped 1 item.minecraft.cobblestone, 2 item.minecraft.stick"),
            interaction.detail,
        )
        val next = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val (taken, lying) = next.selfState.ownDrops
        assertEquals(OwnDrop(perception.requestId, "item.minecraft.cobblestone", 1, DropFate.TAKEN), taken)
        assertEquals(DropFate.LYING, lying.fate)
        assertEquals(Bearing.EAST, lying.direction)
        assertEquals(5.0, requireNotNull(lying.distance), 1e-9)
        assertEquals(0.0, lying.verticalOffset)
    }

    @Test
    fun `an own lying drop is asked for by its server uuid and offered back by its ref`() {
        // r15 kappa body:00000080: its own dig dropped a stick it never saw, and iota's own stick
        // lay out of sight five blocks off; neither could be named to collect.
        val agent = AgentId("agent-retained")
        // The fake server names the sensed apple by this uuid, so it is the own drop's entity.
        val apple = UUID.nameUUIDFromBytes("item:0:minecraft:apple:1".toByteArray(StandardCharsets.UTF_8))
        val drops =
            object : OwnDrops {
                override fun attribute(
                    identity: PlayerIdentity,
                    requestId: String,
                    block: List<Int>,
                    sinceTick: Long,
                ): List<OwnDropReading> = emptyList()

                override fun of(identity: PlayerIdentity): List<OwnDropReading> =
                    listOf(
                        OwnDropReading(
                            "req-dug",
                            "item.minecraft.apple",
                            1,
                            DropFate.LYING,
                            listOf(3.5, 64.0, 2.5),
                            apple,
                        ),
                        OwnDropReading("req-dug", "item.minecraft.stick", 1, DropFate.TAKEN, null),
                    )
            }
        val senses = CopyOnWriteArrayList<BodyRequest.Sense>()
        val harness =
            harness(
                agent,
                HarnessOptions(
                    intercept = { request ->
                        (request as? BodyRequest.Sense)?.let {
                            senses += it
                            interactionSense(it)
                        }
                    },
                    ownDrops = drops,
                ),
            )
        harness.port.spawn(agent).await()
        val perception = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        assertEquals(listOf(apple.toString()), senses.single().retainItems)
        val item = perception.surroundings.single { it.kind == ObservationKind.DROPPED_ITEM }
        val (lying, taken) = perception.selfState.ownDrops
        assertEquals(item.ref, lying.ref)
        assertNull(taken.ref)
    }

    @Test
    fun `an item the body found no route to is said so and offered to nothing after`() {
        // r15: one stick hanging over a trench drew five noRoute collects from two agents.
        val agent = AgentId("agent-no-route")
        val harness =
            harness(
                agent,
                HarnessOptions(
                    intercept = { request ->
                        when (request) {
                            is BodyRequest.Sense -> interactionSense(request)
                            is BodyRequest.Collect ->
                                fakeCollect(request).copy(outcome = CollectOutcome.NO_ROUTE, what = null, count = null)
                            else -> null
                        }
                    },
                ),
            )
        harness.port.spawn(agent).await()
        val first = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val item = first.surroundings.single { it.kind == ObservationKind.DROPPED_ITEM }
        assertNull(item.routeRefused)
        val refused =
            harness.port.apply(agent, Intent.Collect(item.ref, approach = true), first.requestId).await()
        assertEquals(EventTag.COLLECT_NO_ROUTE, (refused as IntentOutcome.Rejected).tag)
        val next = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        assertEquals(true, next.surroundings.single { it.kind == ObservationKind.DROPPED_ITEM }.routeRefused)
    }

    @Test
    fun `a dig the server credits to another player is that player's break, not this one's`() {
        // The second v23 rehearsal, tick 3262: two finishes on one instant-break bush crossed in
        // the server, and both bodies saw the block go after their own finish.
        val agent = AgentId("agent-contest")
        val attributed = mutableListOf<String>()
        val drops =
            object : OwnDrops {
                override fun attribute(
                    identity: PlayerIdentity,
                    requestId: String,
                    block: List<Int>,
                    sinceTick: Long,
                ): List<OwnDropReading> {
                    attributed += requestId
                    return emptyList()
                }

                override fun of(identity: PlayerIdentity): List<OwnDropReading> = emptyList()
            }
        val server = FakePlayerServerState().apply { digsLost = true }
        val harness =
            harness(
                agent,
                HarnessOptions(
                    server = server,
                    intercept = { request -> (request as? BodyRequest.Sense)?.let(::interactionSense) },
                    ownDrops = drops,
                ),
            )
        harness.port.spawn(agent).await()
        val perception = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val stone = perception.surroundings.single { it.what == "block.minecraft.stone" }
        val outcome = harness.port.apply(agent, Intent.Dig(stone.ref), perception.requestId).await()

        assertEquals(EventTag.DIG_CONTEST_GONE, (outcome as IntentOutcome.Rejected).tag)
        val applied = harness.records.single { it.event == BodyEvent.INTENT_APPLIED }
        assertEquals(EventTag.DIG_CONTEST_GONE, applied.outcome)
        val interaction = harness.records.single { it.event == BodyEvent.WORLD_INTERACTION }
        assertEquals(EventTag.SERVER_EFFECT_CONFIRMED, interaction.outcome)
        assertTrue(interaction.detail.contains("body dug"), interaction.detail)
        assertTrue(interaction.detail.endsWith("; dropped nothing of its own"), interaction.detail)
        // Its drops are the winner's: nothing is attributed to this dig.
        assertEquals(emptyList<String>(), attributed)
    }

    @Test
    fun `a flight refused by a busy body halts the routine work and is asked again`() {
        // r13 eta body:00000272: the flee was refused notReady behind a dig decided earlier.
        val agent = AgentId("agent-preempt")
        var busy = true
        val harness =
            harness(
                agent,
                HarnessOptions(
                    intercept = { request ->
                        when {
                            request is BodyRequest.Halt -> {
                                busy = false
                                null
                            }
                            request is BodyRequest.Flee && busy -> throw BodyControlFailure.Refused(ErrorCode.NOT_READY)
                            else -> null
                        }
                    },
                ),
            )
        harness.port.spawn(agent).await()
        val seen = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val threat = seen.surroundings.first { it.kind == ObservationKind.ENTITY }

        val fled = harness.port.apply(agent, Intent.Flee(threat.ref), seen.requestId).await()

        assertTrue(fled is IntentOutcome.Accepted, fled.toString())
        val asked = harness.connector.requests.filter { it is BodyRequest.Flee || it is BodyRequest.Halt }
        // The connector lists requests per connection, so the halt's place is proven by the retry
        // succeeding at all: the body stays busy until it is halted.
        assertEquals(mapOf("Flee" to 2, "Halt" to 1), asked.groupingBy { it::class.simpleName!! }.eachCount())
        // The retry is a fresh request, never a reused id.
        assertEquals(2, asked.filterIsInstance<BodyRequest.Flee>().map { it.requestId }.distinct().size)
    }

    @Test
    fun `a covered block is offered as not standable and a walk onto it is refused before the body`() {
        val agent = AgentId("agent-walk-covered")
        val server = FakePlayerServerState().apply { buried += listOf(4, 63, 2) }
        val harness = harness(agent, HarnessOptions(server = server))
        harness.port.spawn(agent).await()
        val perception = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val block = perception.surroundings.single { it.kind == ObservationKind.BLOCK }
        assertEquals(false, block.standable)

        val refused = harness.port.apply(agent, walkOnto(perception), perception.requestId).await()

        assertEquals(EventTag.MOVE_NOT_STANDABLE, (refused as IntentOutcome.Rejected).tag)
        assertTrue(harness.connector.requests.none { it is BodyRequest.MoveTo })
    }

    @Test
    fun `a walk the ground refuses and one that stalls each end under their own tag`() {
        val agent = AgentId("agent-walk-endings")
        var ending = MoveOutcome.NO_ROUTE
        val harness =
            harness(
                agent,
                HarnessOptions(
                    intercept = { request ->
                        (request as? BodyRequest.MoveTo)?.let {
                            val moved = ending == MoveOutcome.STALLED
                            BodyResponse.MoveReport(
                                protocol = it.protocol,
                                requestId = it.requestId,
                                agentId = it.agentId,
                                state = LifecycleState.READY,
                                outcome = ending,
                                from = listOf(1.5, 64.0, 2.5),
                                position = if (moved) listOf(2.5, 64.0, 2.5) else listOf(1.5, 64.0, 2.5),
                                travelled = if (moved) 1.0 else 0.0,
                                elapsedMs = 10,
                            )
                        }
                    },
                ),
            )
        harness.port.spawn(agent).await()

        val refusedOn = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        val refused = harness.port.apply(agent, walkOnto(refusedOn), refusedOn.requestId).await()
        assertEquals(EventTag.MOVE_NO_ROUTE, (refused as IntentOutcome.Rejected).tag)
        assertTrue(harness.records.none { it.event == BodyEvent.MOVED })

        ending = MoveOutcome.STALLED
        val stalledOn = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        harness.port.apply(agent, walkOnto(stalledOn), stalledOn.requestId).await()
        val moved = harness.records.single { it.event == BodyEvent.MOVED }
        assertEquals(stalledOn.requestId, moved.requestId)
        assertEquals(EventTag.MOVEMENT_STALLED, moved.outcome)
    }
}
