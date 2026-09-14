/*
 * Lifecycle ownership for the persistent Mineflayer player process.
 */
package dev.loom.body.player

import dev.loom.body.AgentId
import dev.loom.bodycontrol.BODY_CONTROL_PROTOCOL_VERSION
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.LifecycleState
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

private const val READINESS_RETRY_MILLIS = 50L

/** A launched process, without exposing JVM process APIs above the supervisor seam. */
interface SupervisedBodyProcess {
    val identity: PlayerIdentity
    val endpoint: BodyControlEndpoint
    val exitCode: CompletionStage<Int>

    fun isAlive(): Boolean
}

/** Starts, readies, and stops Mineflayer body processes. */
interface BodyProcessSupervisor : AutoCloseable {
    fun start(identity: PlayerIdentity): CompletionStage<SupervisedBodyProcess>

    fun stop(process: SupervisedBodyProcess): CompletionStage<Unit>

    override fun close()
}

/** Stable categories for startup and cleanup failures. */
enum class BodySupervisorFailureKind {
    ALREADY_RUNNING,
    BODY_ARTIFACT_MISSING,
    PROCESS_LAUNCH_FAILED,
    PROCESS_EXITED,
    CONTROL_UNAVAILABLE,
    PLAYER_NOT_READY,
    STARTUP_TIMED_OUT,
}

/** A bounded supervisor failure whose message contains no process or network detail. */
class BodySupervisorFailure(
    val kind: BodySupervisorFailureKind,
) : RuntimeException("body process ${kind.name.lowercase()}")

/**
 * Production supervisor.
 *
 * Startup has one success boundary: a live child has answered a handshake with the
 * registered username and then reported `ready`. Any failure before that boundary
 * first shuts down (or kills) the partial child and only then completes the start
 * exceptionally. Shutdown likewise has a fixed escalation: protocol shutdown,
 * bounded wait, ordinary destroy, bounded wait, forced destroy.
 */
class ProcessBodySupervisor(
    private val settings: PlayerBodySettings,
    private val connector: BodyControlConnector,
    private val requestIds: BodyControlRequestIds,
    private val executor: ExecutorService,
) : BodyProcessSupervisor {
    private val processes = ConcurrentHashMap<AgentId, ProcessBodyHandle>()
    private val starts = ConcurrentHashMap<AgentId, CompletableFuture<SupervisedBodyProcess>>()
    private val lifecycleLock = Any()
    private val starting = mutableSetOf<AgentId>()

    @Volatile
    private var closing = false

    override fun start(identity: PlayerIdentity): CompletionStage<SupervisedBodyProcess> {
        val refusal = reserveStart(identity)
        if (refusal != null) return failed(refusal)

        val answer = CompletableFuture<SupervisedBodyProcess>()
        starts[identity.agentId] = answer
        executor.execute {
            try {
                runCatching {
                    if (closing) {
                        throw BodySupervisorFailure(BodySupervisorFailureKind.CONTROL_UNAVAILABLE)
                    }
                    launchAndReady(identity)
                }.onSuccess { process -> answer.complete(process) }
                    .onFailure { failure -> answer.completeExceptionally(failure) }
            } finally {
                try {
                    synchronized(lifecycleLock) {
                        starting -= identity.agentId
                    }
                } finally {
                    starts.remove(identity.agentId, answer)
                }
            }
        }
        return answer
    }

    private fun reserveStart(identity: PlayerIdentity): BodySupervisorFailure? =
        synchronized(lifecycleLock) {
            val existing = processes[identity.agentId]
            when {
                closing ->
                    BodySupervisorFailure(BodySupervisorFailureKind.CONTROL_UNAVAILABLE)
                identity.agentId in starting || existing?.isAlive() == true ->
                    BodySupervisorFailure(BodySupervisorFailureKind.ALREADY_RUNNING)
                else -> {
                    existing?.let { processes.remove(identity.agentId, it) }
                    starting += identity.agentId
                    null
                }
            }
        }

    private fun launchAndReady(identity: PlayerIdentity): SupervisedBodyProcess {
        val process = launchProcess(identity, bodyEntrypoint())
        val handle = ProcessBodyHandle(identity, settings.controlEndpoint, process)
        processes[identity.agentId] = handle
        process.onExit().thenAccept {
            processes.remove(identity.agentId, handle)
        }

        return runCatching {
            if (closing) {
                throw BodySupervisorFailure(BodySupervisorFailureKind.CONTROL_UNAVAILABLE)
            }
            awaitReady(handle)
            handle
        }.getOrElse { failure ->
            stopNow(handle)
            throw failure.asSupervisorFailure()
        }
    }

    private fun bodyEntrypoint() =
        settings.bodyDirectory.resolve("bin/loom-body.js").also { entrypoint ->
            if (!Files.isRegularFile(entrypoint)) {
                throw BodySupervisorFailure(BodySupervisorFailureKind.BODY_ARTIFACT_MISSING)
            }
        }

    private fun launchProcess(
        identity: PlayerIdentity,
        entrypoint: java.nio.file.Path,
    ): Process =
        runCatching {
            ProcessBuilder(settings.nodeExecutable, entrypoint.toString())
                .directory(settings.bodyDirectory.toFile())
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .apply {
                    environment().apply {
                        remove("DEBUG")
                        remove("NODE_DEBUG")
                        put("LOOM_BODY_AGENT_ID", identity.agentId.value)
                        put("LOOM_BODY_USERNAME", identity.username)
                        put("LOOM_BODY_MC_HOST", settings.minecraftHost)
                        put("LOOM_BODY_MC_PORT", settings.minecraftPort.toString())
                        put("LOOM_BODY_MC_VERSION", settings.minecraftVersion)
                        put("LOOM_BODY_CONTROL_HOST", settings.controlEndpoint.host)
                        put("LOOM_BODY_CONTROL_PORT", settings.controlEndpoint.port.toString())
                    }
                }.start()
        }.getOrElse {
            throw BodySupervisorFailure(BodySupervisorFailureKind.PROCESS_LAUNCH_FAILED)
        }

    private fun awaitReady(handle: ProcessBodyHandle) {
        val deadline = System.nanoTime() + settings.startupTimeout.toNanos()
        var reachedControl = false
        while (System.nanoTime() < deadline) {
            when (
                val probe =
                    probeReadiness(
                        handle,
                        connector,
                        requestIds,
                        settings.requestTimeout,
                        closing,
                    )
            ) {
                ReadinessProbe.Ready -> return
                ReadinessProbe.LoginPending -> reachedControl = true
                ReadinessProbe.ControlPending -> Unit
                is ReadinessProbe.Failed -> throw BodySupervisorFailure(probe.kind)
            }
            Thread.sleep(READINESS_RETRY_MILLIS)
        }
        throw BodySupervisorFailure(
            if (reachedControl) {
                BodySupervisorFailureKind.STARTUP_TIMED_OUT
            } else {
                BodySupervisorFailureKind.CONTROL_UNAVAILABLE
            },
        )
    }

    override fun stop(process: SupervisedBodyProcess): CompletionStage<Unit> {
        val handle = process as? ProcessBodyHandle
        return if (handle == null) {
            failed(IllegalArgumentException("unknown body process"))
        } else {
            synchronized(handle.stopLock) {
                handle.stopFuture
                    ?: CompletableFuture<Unit>().also { stopping ->
                        handle.stopFuture = stopping
                        executor.execute {
                            runCatching { stopNow(handle) }
                                .onSuccess { stopping.complete(Unit) }
                                .onFailure { failure ->
                                    stopping.completeExceptionally(failure)
                                }
                        }
                    }
            }
        }
    }

    private fun stopNow(handle: ProcessBodyHandle) {
        synchronized(handle.stopWorkLock) {
            if (!handle.isAlive()) {
                processes.remove(handle.identity.agentId, handle)
                return
            }

            runCatching {
                connector.connect(handle.endpoint, handle.identity).use { connection ->
                    connection.request(
                        BodyRequest.Shutdown(
                            protocol = BODY_CONTROL_PROTOCOL_VERSION,
                            requestId = requestIds.next("shutdown"),
                            agentId = handle.identity.agentId.value,
                        ),
                        settings.requestTimeout,
                    )
                }
            }
            if (handle.process.waitFor(settings.shutdownTimeout)) {
                processes.remove(handle.identity.agentId, handle)
                return
            }

            handle.process.destroy()
            if (!handle.process.waitFor(settings.shutdownTimeout.dividedBy(2))) {
                handle.process.destroyForcibly()
                handle.process.waitFor(settings.shutdownTimeout.dividedBy(2))
            }
            processes.remove(handle.identity.agentId, handle)
        }
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (closing) return
            closing = true
        }

        // A start that had already crossed ProcessBuilder owns cleanup in its
        // failure path. Closing the live snapshot first makes its readiness probe
        // return promptly; waiting for every start then closes the launch-before-map
        // race before the final sweep.
        stopAll(processes.values)
        val deadline = System.nanoTime() + settings.shutdownTimeout.toNanos()
        starts.values.toList().forEach { start ->
            val remaining = deadline - System.nanoTime()
            if (remaining > 0) {
                runCatching {
                    start.get(remaining, TimeUnit.NANOSECONDS)
                }
            }
        }
        stopAll(processes.values)
        processes.clear()
    }

    private fun stopAll(handles: Collection<ProcessBodyHandle>) {
        handles
            .distinctBy { it.identity.agentId }
            .sortedBy { it.identity.agentId.value }
            .forEach { handle ->
                runCatching { stop(handle).toCompletableFuture().join() }
            }
    }
}

private class ProcessBodyHandle(
    override val identity: PlayerIdentity,
    override val endpoint: BodyControlEndpoint,
    val process: Process,
) : SupervisedBodyProcess {
    val stopLock = Any()
    val stopWorkLock = Any()
    var stopFuture: CompletionStage<Unit>? = null

    override val exitCode: CompletionStage<Int> =
        process.onExit().thenApply { finished -> finished.exitValue() }

    override fun isAlive(): Boolean = process.isAlive
}

private fun Process.waitFor(timeout: Duration): Boolean =
    waitFor(
        timeout.toMillis().coerceAtLeast(1),
        TimeUnit.MILLISECONDS,
    )

private fun <T> failed(failure: Throwable): CompletionStage<T> = CompletableFuture.failedFuture(failure)

private sealed interface ReadinessProbe {
    data object Ready : ReadinessProbe

    data object LoginPending : ReadinessProbe

    data object ControlPending : ReadinessProbe

    data class Failed(
        val kind: BodySupervisorFailureKind,
    ) : ReadinessProbe
}

private fun probeReadiness(
    handle: ProcessBodyHandle,
    connector: BodyControlConnector,
    requestIds: BodyControlRequestIds,
    requestTimeout: Duration,
    closing: Boolean,
): ReadinessProbe {
    val interruption =
        when {
            closing -> ReadinessProbe.Failed(BodySupervisorFailureKind.CONTROL_UNAVAILABLE)
            !handle.isAlive() -> ReadinessProbe.Failed(BodySupervisorFailureKind.PROCESS_EXITED)
            else -> null
        }
    return interruption
        ?: try {
            connector.connect(handle.endpoint, handle.identity).use { connection ->
                val status =
                    connection.request(
                        BodyRequest.Status(
                            protocol = BODY_CONTROL_PROTOCOL_VERSION,
                            requestId = requestIds.next("status"),
                            agentId = handle.identity.agentId.value,
                        ),
                        requestTimeout,
                    ) as? BodyResponse.StatusReport
                        ?: throw BodyControlFailure.ProtocolViolation()
                when (status.state) {
                    LifecycleState.READY -> ReadinessProbe.Ready
                    LifecycleState.CONNECTING,
                    LifecycleState.LOGGED_IN,
                    -> ReadinessProbe.LoginPending
                    LifecycleState.DEAD,
                    LifecycleState.DISCONNECTED,
                    LifecycleState.STOPPING,
                    LifecycleState.FAILED,
                    -> ReadinessProbe.Failed(BodySupervisorFailureKind.PLAYER_NOT_READY)
                }
            }
        } catch (_: BodyControlFailure) {
            // The control listener and Minecraft login start independently.
            ReadinessProbe.ControlPending
        }
}

private fun Throwable.asSupervisorFailure(): BodySupervisorFailure =
    this as? BodySupervisorFailure
        ?: BodySupervisorFailure(BodySupervisorFailureKind.CONTROL_UNAVAILABLE)
