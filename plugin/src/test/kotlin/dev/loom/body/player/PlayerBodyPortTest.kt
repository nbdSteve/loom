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
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.BodyLifecycle
import dev.loom.body.BodySpawnResult
import dev.loom.body.EventTag
import dev.loom.body.IntentOutcome
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.EatOutcome
import dev.loom.bodycontrol.ErrorCode
import dev.loom.bodycontrol.LifecycleState
import dev.loom.bodycontrol.MoveOutcome
import dev.loom.bodycontrol.SensedEntity
import dev.loom.bodycontrol.SensedGround
import dev.loom.bodycontrol.SensedStack
import dev.loom.bodycontrol.SensedTime
import dev.loom.bodycontrol.SensedVitals
import dev.loom.contract.Intent
import dev.loom.contract.Trigger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val PORT_TEST_TIMEOUT: Duration = Duration.ofSeconds(3)
private val SENSE_POSITION = listOf(1.5, 64.0, 2.5)

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

private class FakePlayerServerState : PlayerServerState {
    @Volatile
    var connected = true

    val dead = mutableSetOf<AgentId>()
    val senseAudits = CopyOnWriteArrayList<Pair<PlayerIdentity, BodyResponse.SenseReport>>()
    val moveAudits = CopyOnWriteArrayList<Pair<PlayerIdentity, BodyRequest>>()

    override fun connected(identity: PlayerIdentity): Boolean = connected

    override fun isDead(agentId: AgentId): Boolean = synchronized(dead) { agentId in dead }

    override fun dayOrdinal(): Long = 2

    override fun subjectsOf(
        identity: PlayerIdentity,
        entities: List<SensedEntity>,
    ): List<UUID?> =
        entities.mapIndexed { index, entity ->
            UUID.nameUUIDFromBytes(
                "$index:${entity.what}".toByteArray(StandardCharsets.UTF_8),
            )
        }

    override fun auditSense(
        identity: PlayerIdentity,
        report: BodyResponse.SenseReport,
    ) {
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
}

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

class PlayerBodyPortTest {
    @TempDir
    lateinit var temporary: Path

    private val harnesses = mutableListOf<PortHarness>()
    private val tick = AtomicLong(100)

    @AfterEach
    fun cleanUp() {
        harnesses.forEach { runCatching(it.port::close) }
    }

    @Test
    fun `spawn registers identity before launch and completes only after server readiness`() {
        val registered = CopyOnWriteArrayList<PlayerIdentity>()
        val server = FakePlayerServerState().apply { connected = false }
        val harness =
            harness(
                registered = registered,
                server = server,
                beforeStart = { identity ->
                    assertEquals(listOf(identity), registered)
                },
            )
        val agent = AgentId("agent-ready")

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
        val harness = harness()
        val agent = AgentId("agent-round-trip")
        harness.port.spawn(agent).await()

        val movementPerception =
            requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
        assertEquals(18.0, movementPerception.selfState.health)
        assertEquals(6, movementPerception.selfState.food)
        assertEquals(1.5, movementPerception.selfState.saturation)
        assertEquals("entity.minecraft.zombie", movementPerception.surroundings.single().what)
        assertEquals("item.minecraft.bread", movementPerception.selfState.inventorySummary.single().what)

        val moveOutcome =
            harness.port
                .apply(
                    agent,
                    Intent.MoveTo(listOf(4L, 64L, 2L)),
                    movementPerception.requestId,
                ).await()
        assertTrue(moveOutcome is IntentOutcome.Accepted)

        val eatingPerception =
            requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())
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
    fun `a process crash is a diagnosable disconnect and the same identity can restart`() {
        val harness = harness()
        val agent = AgentId("agent-restart")
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
    }

    @Test
    fun `a busy movement refusal leaves the healthy player process ready`() {
        var refuseMovement = true
        val harness =
            harness(
                intercept = { request ->
                    if (refuseMovement && request is BodyRequest.MoveTo) {
                        throw BodyControlFailure.Refused(ErrorCode.NOT_READY)
                    }
                    null
                },
            )
        val agent = AgentId("agent-busy")
        harness.port.spawn(agent).await()
        val perception = requireNotNull(harness.port.perceive(agent, Trigger.GoalReview).await())

        val refused =
            harness.port
                .apply(
                    agent,
                    Intent.MoveTo(listOf(4L, 64L, 2L)),
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
        assertTrue(
            harness.port
                .apply(agent, Intent.MoveTo(listOf(4L, 64L, 2L)))
                .await() is IntentOutcome.Accepted,
        )
    }

    @Test
    fun `repeated senses of one server subject spend the existing threat debounce`() {
        val harness = harness()
        val agent = AgentId("agent-threat")
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
        val harness = harness()
        val agent = AgentId("agent-terminal")
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
    fun `the hard cut owns one configured identity and refuses a second agent`() {
        val harness = harness()
        harness.port.spawn(AgentId("agent-one")).await()

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
        val harness = harness()
        val agent = AgentId("agent-stop-failure")
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
        val harness = harness()
        val agent = AgentId("agent-cleanup")
        harness.port.spawn(agent).await()
        harness.port.perceive(agent, Trigger.GoalReview).await()
        assertTrue(harness.connector.connections.any { !it.isClosed() })

        harness.port.close()

        assertTrue(harness.connector.connections.all(FakeControlConnection::isClosed))
        assertTrue(harness.supervisor.closed)
        assertTrue(harness.supervisor.processes.none(FakeBodyProcess::isAlive))
        assertTrue(harness.io.isShutdown)
        assertTrue(harness.scheduler.isShutdown)
    }

    private fun harness(
        registered: CopyOnWriteArrayList<PlayerIdentity> = CopyOnWriteArrayList(),
        server: FakePlayerServerState = FakePlayerServerState(),
        beforeStart: (PlayerIdentity) -> Unit = {},
        intercept: (BodyRequest) -> BodyResponse? = { null },
    ): PortHarness {
        val endpoint = BodyControlEndpoint("127.0.0.1", 25_585)
        val supervisor = FakeBodySupervisor(endpoint, beforeStart)
        val connector = FakeControlConnector(intercept)
        val records = CopyOnWriteArrayList<BodyEventRecord>()
        val io = Executors.newFixedThreadPool(2)
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val port =
            PlayerBodyPort(
                infrastructure =
                    PlayerBodyInfrastructure(
                        settings =
                            PlayerBodySettings(
                                bodyDirectory = temporary.toAbsolutePath(),
                                controlEndpoint = endpoint,
                                connectTimeout = Duration.ofMillis(100),
                                requestTimeout = Duration.ofMillis(100),
                                movementTimeout = Duration.ofMillis(200),
                                startupTimeout = Duration.ofSeconds(1),
                                shutdownTimeout = Duration.ofMillis(200),
                            ),
                        supervisor = supervisor,
                        connector = connector,
                        requestIds = BodyControlRequestIds(),
                        io = io,
                        scheduler = scheduler,
                    ),
                authority =
                    PlayerBodyAuthority(
                        main = { task -> task.run() },
                        registerIdentity = registered::add,
                        serverState = server,
                        eventLog = BodyEventLog(records::add),
                        currentTick = tick::get,
                    ),
            )
        return PortHarness(
            port = port,
            supervisor = supervisor,
            connector = connector,
            server = server,
            registered = registered,
            records = records,
            io = io,
            scheduler = scheduler,
        ).also(harnesses::add)
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
        is BodyRequest.Halt -> fakeHalt(request)
        is BodyRequest.Status -> fakeStatus(request, handled)
        is BodyRequest.Handshake,
        is BodyRequest.Shutdown,
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
        inventory =
            listOf(
                SensedStack(
                    slot = 36,
                    what = "minecraft:bread",
                    count = 3,
                ),
            ),
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
