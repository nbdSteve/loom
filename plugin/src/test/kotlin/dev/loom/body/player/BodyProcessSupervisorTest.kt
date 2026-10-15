/*
 * Process ownership at the JVM/Node seam.
 *
 * These tests launch a real child process but replace Minecraft and the control
 * socket with the narrow connector interface. That is the smallest layer which
 * can prove environment identity, readiness classification, orderly shutdown,
 * startup cleanup and the close-during-launch race without starting Paper.
 */
package dev.loom.body.player

import dev.loom.body.AgentId
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.LifecycleState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val SUPERVISOR_TEST_TIMEOUT: Duration = Duration.ofSeconds(3)

private fun supervisorIdentity(): PlayerIdentity =
    PlayerIdentity(
        AgentId("agent-supervisor"),
        "loom_abcdefghij",
        UUID.fromString("00000000-0000-3000-8000-000000000002"),
    )

private class SupervisorConnector(
    var status: LifecycleState,
    private val stopMarker: Path,
) : BodyControlConnector {
    val requests = CopyOnWriteArrayList<BodyRequest>()

    override fun connect(
        endpoint: BodyControlEndpoint,
        identity: PlayerIdentity,
    ): BodyControlConnection =
        object : BodyControlConnection {
            override val identity: PlayerIdentity = identity

            override fun request(
                request: BodyRequest,
                timeout: Duration,
            ): BodyResponse {
                requests += request
                return when (request) {
                    is BodyRequest.Status ->
                        BodyResponse.StatusReport(
                            protocol = request.protocol,
                            requestId = request.requestId,
                            agentId = request.agentId,
                            state = status,
                            uptimeMs = 10,
                            stateMs = 5,
                            requestsHandled = requests.size.toLong(),
                        )
                    is BodyRequest.Shutdown -> {
                        Files.writeString(stopMarker, "stop")
                        BodyResponse.ShutdownAccepted(
                            protocol = request.protocol,
                            requestId = request.requestId,
                            agentId = request.agentId,
                            state = LifecycleState.STOPPING,
                        )
                    }
                    else -> error("the supervisor sent gameplay over its lifecycle connector: $request")
                }
            }

            override fun close() = Unit
        }
}

private class RetryConnector(
    private val attempts: Path,
    private val stopMarker: Path,
) : BodyControlConnector {
    override fun connect(
        endpoint: BodyControlEndpoint,
        identity: PlayerIdentity,
    ): BodyControlConnection =
        object : BodyControlConnection {
            override val identity: PlayerIdentity = identity

            override fun request(
                request: BodyRequest,
                timeout: Duration,
            ): BodyResponse =
                when (request) {
                    is BodyRequest.Status -> {
                        if (Files.readString(attempts).trim() == "1") {
                            throw BodyControlFailure.Unavailable()
                        }
                        BodyResponse.StatusReport(
                            protocol = request.protocol,
                            requestId = request.requestId,
                            agentId = request.agentId,
                            state = LifecycleState.READY,
                            uptimeMs = 10,
                            stateMs = 5,
                            requestsHandled = 1,
                        )
                    }
                    is BodyRequest.Shutdown -> {
                        Files.writeString(stopMarker, "stop")
                        BodyResponse.ShutdownAccepted(
                            protocol = request.protocol,
                            requestId = request.requestId,
                            agentId = request.agentId,
                            state = LifecycleState.STOPPING,
                        )
                    }
                    else -> error("unexpected lifecycle request $request")
                }

            override fun close() = Unit
        }
}

class BodyProcessSupervisorTest {
    @TempDir
    lateinit var temporary: Path

    private val supervisors = mutableListOf<ProcessBodySupervisor>()
    private val executors = mutableListOf<ExecutorService>()

    @AfterEach
    fun cleanUp() {
        supervisors.forEach { runCatching(it::close) }
        executors.forEach {
            it.shutdownNow()
            it.awaitTermination(SUPERVISOR_TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `startup supplies exact identity and process directory and orderly stop leaves no child`() {
        val fixture = fixture(LifecycleState.READY)
        val identity = supervisorIdentity()

        val process = fixture.supervisor.start(identity).await()

        assertTrue(process.isAlive())
        awaitCondition { Files.exists(fixture.environment) }
        assertEquals(
            "${identity.agentId.value}|${identity.username}|127.0.0.1|25565|1.21.1|" +
                "127.0.0.1|${fixture.settings.controlEndpoint.port}|" +
                fixture.settings.processDirectory.toRealPath(),
            Files.readString(fixture.environment),
        )
        assertTrue(fixture.connector.requests.first() is BodyRequest.Status)

        fixture.supervisor.stop(process).await()

        assertFalse(process.isAlive())
        assertEquals(0, process.exitCode.await())
        assertTrue(fixture.connector.requests.last() is BodyRequest.Shutdown)
    }

    @Test
    fun `one live identity cannot be launched twice`() {
        val fixture = fixture(LifecycleState.READY)
        val identity = supervisorIdentity()
        fixture.supervisor.start(identity).await()

        val failure = failureOf(fixture.supervisor.start(identity))

        assertEquals(
            BodySupervisorFailureKind.ALREADY_RUNNING,
            (failure as BodySupervisorFailure).kind,
        )
    }

    @Test
    fun `startup retries one cleaned failed child and then stops at the configured bound`() {
        val bodyDirectory = temporary.resolve("retry-body").toAbsolutePath()
        val bin = bodyDirectory.resolve("bin")
        Files.createDirectories(bin)
        val attempts = bodyDirectory.resolve("attempts")
        val stop = bodyDirectory.resolve("stop")
        val pid = bodyDirectory.resolve("pid")
        Files.writeString(
            bin.resolve("loom-body.js"),
            """
            count=0
            if [ -f '$attempts' ]; then count=${'$'}(cat '$attempts'); fi
            count=${'$'}((count + 1))
            printf '%s' "${'$'}count" > '$attempts'
            if [ "${'$'}count" -eq 1 ]; then exit 1; fi
            rm -f '$stop'
            printf '%s' "${'$'}${'$'}" > '$pid'
            while [ ! -f '$stop' ]; do sleep 0.02; done
            """.trimIndent(),
        )
        val executor = Executors.newFixedThreadPool(2)
        executors += executor
        val settings =
            settings(bodyDirectory, Duration.ofSeconds(1)).copy(
                startupAttempts = 2,
                startupRetryDelay = Duration.ofMillis(10),
            )
        val supervisor =
            ProcessBodySupervisor(
                settings,
                RetryConnector(attempts, stop),
                BodyControlRequestIds(scope = "retry"),
                executor,
            )
        supervisors += supervisor

        val process = supervisor.start(supervisorIdentity()).await()

        assertEquals("2", Files.readString(attempts))
        assertTrue(process.isAlive())
        supervisor.stop(process).await()
        assertFalse(process.isAlive())
    }

    @Test
    fun `health is one bounded status check and never stops the child itself`() {
        val fixture = fixture(LifecycleState.READY)
        val process = fixture.supervisor.start(supervisorIdentity()).await()

        assertTrue(fixture.supervisor.health(process).await())
        fixture.connector.status = LifecycleState.CONNECTING
        assertFalse(fixture.supervisor.health(process).await())

        assertTrue(process.isAlive())
        fixture.supervisor.stop(process).await()
    }

    @Test
    fun `a process which never reaches ready is stopped before startup fails`() {
        val fixture =
            fixture(
                state = LifecycleState.CONNECTING,
                startupTimeout = Duration.ofMillis(180),
            )

        val failure = failureOf(fixture.supervisor.start(supervisorIdentity()))

        assertEquals(
            BodySupervisorFailureKind.STARTUP_TIMED_OUT,
            (failure as BodySupervisorFailure).kind,
        )
        awaitCondition { Files.exists(fixture.pid) }
        assertFalse(processIsAlive(Files.readString(fixture.pid).trim().toLong()))
        assertTrue(fixture.connector.requests.any { it is BodyRequest.Shutdown })
    }

    @Test
    fun `closing during readiness owns the partially started process`() {
        val fixture =
            fixture(
                state = LifecycleState.CONNECTING,
                startupTimeout = Duration.ofSeconds(2),
            )
        val starting = fixture.supervisor.start(supervisorIdentity())
        awaitCondition { Files.exists(fixture.pid) }

        fixture.supervisor.close()

        assertTrue(starting.toCompletableFuture().isCompletedExceptionally)
        assertFalse(processIsAlive(Files.readString(fixture.pid).trim().toLong()))
        assertTrue(fixture.connector.requests.any { it is BodyRequest.Shutdown })
    }

    @Test
    fun `a missing body artifact is a closed startup category`() {
        val directory = temporary.resolve("missing-body").toAbsolutePath()
        Files.createDirectories(directory)
        val executor = Executors.newFixedThreadPool(2)
        executors += executor
        val settings = settings(directory, Duration.ofMillis(200))
        val supervisor =
            ProcessBodySupervisor(
                settings = settings,
                connector = SupervisorConnector(LifecycleState.READY, directory.resolve("stop")),
                requestIds = BodyControlRequestIds(),
                executor = executor,
            )
        supervisors += supervisor

        val failure = failureOf(supervisor.start(supervisorIdentity()))

        assertEquals(
            BodySupervisorFailureKind.BODY_ARTIFACT_MISSING,
            (failure as BodySupervisorFailure).kind,
        )
    }

    private fun fixture(
        state: LifecycleState,
        startupTimeout: Duration = Duration.ofSeconds(1),
    ): SupervisorFixture {
        val bodyDirectory = temporary.resolve("body-${supervisors.size}").toAbsolutePath()
        val bin = bodyDirectory.resolve("bin")
        Files.createDirectories(bin)
        val stop = bodyDirectory.resolve("stop")
        val environment = bodyDirectory.resolve("environment")
        val pid = bodyDirectory.resolve("pid")
        val entrypoint = bin.resolve("loom-body.js")
        Files.writeString(
            entrypoint,
            """
            printf '%s|%s|%s|%s|%s|%s|%s|%s' \
              "${'$'}LOOM_BODY_AGENT_ID" \
              "${'$'}LOOM_BODY_USERNAME" \
              "${'$'}LOOM_BODY_MC_HOST" \
              "${'$'}LOOM_BODY_MC_PORT" \
              "${'$'}LOOM_BODY_MC_VERSION" \
              "${'$'}LOOM_BODY_CONTROL_HOST" \
              "${'$'}LOOM_BODY_CONTROL_PORT" \
              "${'$'}PWD" > '$environment'
            printf '%s' "${'$'}${'$'}" > '$pid'
            while [ ! -f '$stop' ]; do sleep 0.02; done
            """.trimIndent(),
        )
        val connector = SupervisorConnector(state, stop)
        val executor = Executors.newFixedThreadPool(2)
        executors += executor
        val settings = settings(bodyDirectory, startupTimeout)
        val supervisor =
            ProcessBodySupervisor(
                settings = settings,
                connector = connector,
                requestIds = BodyControlRequestIds(),
                executor = executor,
            )
        supervisors += supervisor
        return SupervisorFixture(supervisor, connector, settings, environment, pid)
    }

    private fun settings(
        bodyDirectory: Path,
        startupTimeout: Duration,
    ): PlayerBodySettings =
        PlayerBodySettings(
            bodyDirectory = bodyDirectory,
            processDirectory = bodyDirectory.resolve("runtime"),
            nodeExecutable = "/bin/sh",
            controlEndpoint = BodyControlEndpoint("127.0.0.1", unusedPort()),
            connectTimeout = Duration.ofMillis(50),
            requestTimeout = Duration.ofMillis(50),
            movementTimeout = Duration.ofMillis(100),
            startupTimeout = startupTimeout,
            shutdownTimeout = Duration.ofMillis(300),
            startupAttempts = 1,
        )

    private data class SupervisorFixture(
        val supervisor: ProcessBodySupervisor,
        val connector: SupervisorConnector,
        val settings: PlayerBodySettings,
        val environment: Path,
        val pid: Path,
    )
}

private fun unusedPort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

private fun <T> CompletionStage<T>.await(): T =
    toCompletableFuture().get(SUPERVISOR_TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)

private fun failureOf(stage: CompletionStage<*>): Throwable =
    assertThrows<ExecutionException> {
        stage.toCompletableFuture().get(SUPERVISOR_TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
    }.cause ?: error("a failed completion had no cause")

private fun awaitCondition(condition: () -> Boolean) {
    val deadline = System.nanoTime() + SUPERVISOR_TEST_TIMEOUT.toNanos()
    while (!condition()) {
        check(System.nanoTime() < deadline) { "condition did not become true before the test deadline" }
        Thread.sleep(10)
    }
}

private fun processIsAlive(pid: Long): Boolean = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
