/*
 * What the offline end-to-end acceptance tests share: real Node bodies already running on
 * loopback control ports, wired into the real JVM player-body fleet.
 */
package dev.loom.plugin

import dev.loom.body.player.BodyControlEndpoint
import dev.loom.body.player.BodyControlRequestIds
import dev.loom.body.player.BodyProcessSupervisor
import dev.loom.body.player.PlayerBodyInfrastructure
import dev.loom.body.player.PlayerBodySettings
import dev.loom.body.player.PlayerIdentity
import dev.loom.body.player.SocketBodyControlConnector
import dev.loom.body.player.SupervisedBodyProcess
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The repository root, from the plugin project the tests run in. */
internal val REPO: Path = Paths.get("").toAbsolutePath().parent

/** How long any one step of an end-to-end test may take. */
internal val E2E_TIMEOUT: Duration = Duration.ofSeconds(20)

internal fun infrastructure(
    identity: PlayerIdentity,
    temporary: Path,
    port: Int,
    closers: MutableList<() -> Unit>,
): PlayerBodyInfrastructure {
    val endpoint = BodyControlEndpoint("127.0.0.1", port)
    val io = Executors.newFixedThreadPool(2)
    val scheduler = Executors.newSingleThreadScheduledExecutor()
    closers += { io.shutdownNow() }
    closers += { scheduler.shutdownNow() }
    return PlayerBodyInfrastructure(
        identity = identity,
        settings =
            PlayerBodySettings(
                bodyDirectory = temporary.toAbsolutePath(),
                processDirectory = temporary.resolve(identity.username).toAbsolutePath(),
                controlEndpoint = endpoint,
                connectTimeout = Duration.ofSeconds(2),
                requestTimeout = Duration.ofSeconds(5),
                movementTimeout = Duration.ofSeconds(5),
                startupTimeout = Duration.ofSeconds(10),
                shutdownTimeout = Duration.ofSeconds(2),
                healthCheckIntervalTicks = 100_000,
                healthFailureLimit = 3,
            ),
        supervisor = AlreadyRunning(endpoint),
        connector = SocketBodyControlConnector(Duration.ofSeconds(2), Duration.ofSeconds(5)),
        requestIds = BodyControlRequestIds(scope = identity.username),
        io = io,
        scheduler = scheduler,
    )
}

/** The Node peers are already running; the supervisor only names them. */
internal class AlreadyRunning(
    private val endpoint: BodyControlEndpoint,
) : BodyProcessSupervisor {
    override fun start(identity: PlayerIdentity): CompletionStage<SupervisedBodyProcess> {
        val alive = AtomicBoolean(true)
        return CompletableFuture.completedFuture(
            object : SupervisedBodyProcess {
                override val identity = identity
                override val endpoint = this@AlreadyRunning.endpoint
                override val exitCode: CompletionStage<Int> = CompletableFuture()

                override fun isAlive(): Boolean = alive.get()
            },
        )
    }

    override fun stop(process: SupervisedBodyProcess): CompletionStage<Unit> = CompletableFuture.completedFuture(Unit)

    override fun close() = Unit
}

internal fun <T> CompletionStage<T>.await(): T = toCompletableFuture().get(E2E_TIMEOUT.seconds, TimeUnit.SECONDS)

internal fun <T> CompletableFuture<T>.await(): T = get(E2E_TIMEOUT.seconds, TimeUnit.SECONDS)

internal fun nodeOrNull(): String? =
    runCatching {
        val probe = ProcessBuilder("node", "--version").redirectErrorStream(true).start()
        probe.inputStream.readAllBytes()
        "node".takeIf { probe.waitFor(E2E_TIMEOUT.seconds, TimeUnit.SECONDS) && probe.exitValue() == 0 }
    }.getOrNull()
