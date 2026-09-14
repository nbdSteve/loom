/*
 * Deterministic lifecycle ordering for the persistent player body.
 */
package dev.loom.body.player

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyLifecycle
import dev.loom.body.BodySpawnResult
import dev.loom.body.EventTag
import dev.loom.body.completedBodyOperation
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

private const val WITNESS_RETRY_MILLIS = 50L

internal data class StartupFailure(
    val reason: String,
    val tag: String,
    val preserveLifecycle: Boolean = false,
)

/** Shared terminal and disconnect transitions used by lifecycle and actuation. */
internal class PlayerLifecycleTransitions(
    private val context: PlayerBodyContext,
    private val sessions: PlayerSenseSessions,
) {
    fun markDead(runtime: PlayerRuntime) {
        runtime.lifecycle = BodyLifecycle.DEAD
        runtime.detail = "terminal death witnessed by the server"
        forget(runtime.identity.agentId)
        runtime.process?.let(context.infrastructure.supervisor::stop)
    }

    fun markDisconnected(
        runtime: PlayerRuntime,
        detail: String,
    ) {
        if (runtime.lifecycle == BodyLifecycle.DEAD) return
        runtime.lifecycle = BodyLifecycle.DISCONNECTED
        runtime.detail = detail
        forget(runtime.identity.agentId)
    }

    fun forget(agentId: AgentId) {
        sessions.release(agentId)
        context.refs.forget(agentId)
        context.threatWatch.forget(agentId)
        context.vitalsWatch.forget(agentId)
    }
}

/**
 * Starts, readies, stops and reconciles the player process.
 *
 * Success is visible only after both the control process and the server witness
 * agree. Every partial start is cleaned before its failure is completed.
 */
internal class PlayerLifecycle(
    private val context: PlayerBodyContext,
    private val registry: PlayerRuntimeRegistry,
    private val sessions: PlayerSenseSessions,
    private val transitions: PlayerLifecycleTransitions,
) {
    fun spawn(agentId: AgentId): CompletionStage<BodySpawnResult> =
        when (val reservation = registry.reserve(agentId)) {
            is StartReservation.Rejected -> completedBodyOperation(reservation.result)
            is StartReservation.Reserved -> launch(reservation.runtime)
        }

    private fun launch(runtime: PlayerRuntime): CompletionStage<BodySpawnResult> {
        val answer = CompletableFuture<BodySpawnResult>()
        val deadline = System.nanoTime() + context.infrastructure.settings.startupTimeout.toNanos()
        context.infrastructure.supervisor.start(runtime.identity).whenComplete { process, failure ->
            context.authority.main.execute {
                when {
                    failure != null || process == null -> failStartup(runtime, failure, answer)
                    !registry.isCurrent(runtime) || context.closing ->
                        context.infrastructure.supervisor.stop(process).whenComplete { _, _ ->
                            context.authority.main.execute {
                                answer.complete(
                                    context.journal.reject(
                                        runtime.identity.agentId,
                                        "the body was stopped before startup completed",
                                        EventTag.BODY_START_FAILED,
                                    ),
                                )
                            }
                        }
                    else -> {
                        runtime.process = process
                        observeExit(runtime, process)
                        awaitWitness(runtime, process, deadline, answer)
                    }
                }
            }
        }
        return answer
    }

    private fun awaitWitness(
        runtime: PlayerRuntime,
        process: SupervisedBodyProcess,
        deadline: Long,
        answer: CompletableFuture<BodySpawnResult>,
    ) {
        val agentId = runtime.identity.agentId
        when (witnessState(context, registry, runtime, process, deadline)) {
            WitnessState.CANCELLED ->
                cleanupFailedStartup(
                    runtime,
                    process,
                    StartupFailure("startup was cancelled", EventTag.BODY_START_FAILED),
                    answer,
                )
            WitnessState.DEAD -> {
                runtime.lifecycle = BodyLifecycle.DEAD
                runtime.detail = "terminal death witnessed by the server"
                cleanupFailedStartup(
                    runtime,
                    process,
                    StartupFailure(
                        "the server witnessed this agent's death during startup",
                        EventTag.HAS_DIED,
                        preserveLifecycle = true,
                    ),
                    answer,
                )
            }
            WitnessState.READY -> completeReady(context, runtime, answer)
            WitnessState.EXITED -> {
                transitions.markDisconnected(runtime, "player process exited during startup")
                answer.complete(
                    context.journal.reject(
                        agentId,
                        "the player process exited before the server witnessed readiness",
                        EventTag.BODY_START_FAILED,
                    ),
                )
            }
            WitnessState.TIMED_OUT ->
                cleanupFailedStartup(
                    runtime,
                    process,
                    StartupFailure(
                        "the server did not witness the player before the startup deadline",
                        EventTag.BODY_START_TIMED_OUT,
                    ),
                    answer,
                )
            WitnessState.WAITING ->
                context.infrastructure.scheduler.schedule(
                    {
                        context.authority.main.execute {
                            awaitWitness(runtime, process, deadline, answer)
                        }
                    },
                    WITNESS_RETRY_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
        }
    }

    private fun failStartup(
        runtime: PlayerRuntime,
        failure: Throwable?,
        answer: CompletableFuture<BodySpawnResult>,
    ) {
        val agentId = runtime.identity.agentId
        if (context.authority.serverState.isDead(agentId)) {
            runtime.lifecycle = BodyLifecycle.DEAD
            runtime.detail = "terminal death witnessed by the server"
            answer.complete(
                context.journal.reject(
                    agentId,
                    "the server witnessed this agent's death during startup",
                    EventTag.HAS_DIED,
                ),
            )
            return
        }
        val kind = (unwrapCompletion(failure) as? BodySupervisorFailure)?.kind
        runtime.lifecycle = BodyLifecycle.FAILED
        runtime.detail = kind?.name?.lowercase() ?: "process startup failed"
        val tag =
            if (kind == BodySupervisorFailureKind.STARTUP_TIMED_OUT) {
                EventTag.BODY_START_TIMED_OUT
            } else {
                EventTag.BODY_START_FAILED
            }
        answer.complete(
            context.journal.reject(
                agentId,
                "the player body could not reach readiness (${runtime.detail})",
                tag,
            ),
        )
    }

    private fun cleanupFailedStartup(
        runtime: PlayerRuntime,
        process: SupervisedBodyProcess,
        failure: StartupFailure,
        answer: CompletableFuture<BodySpawnResult>,
    ) {
        if (!failure.preserveLifecycle) {
            runtime.lifecycle = BodyLifecycle.FAILED
            runtime.detail = failure.reason
        }
        sessions.release(runtime.identity.agentId)
        context.infrastructure.supervisor.stop(process).whenComplete { _, stopFailure ->
            context.authority.main.execute {
                val cleanupFailed = unwrapCompletion(stopFailure) != null
                val recordedReason =
                    if (cleanupFailed) {
                        "${failure.reason}; player process cleanup failed"
                    } else {
                        failure.reason
                    }
                if (cleanupFailed && !failure.preserveLifecycle) {
                    runtime.lifecycle = BodyLifecycle.FAILED
                    runtime.detail = recordedReason
                }
                answer.complete(
                    context.journal.reject(
                        runtime.identity.agentId,
                        recordedReason,
                        failure.tag,
                    ),
                )
            }
        }
    }

    private fun observeExit(
        runtime: PlayerRuntime,
        process: SupervisedBodyProcess,
    ) {
        process.exitCode.whenComplete { exitCode, _ ->
            context.authority.main.execute {
                if (registry.isCurrent(runtime) && runtime.process === process) {
                    when (runtime.lifecycle) {
                        BodyLifecycle.DEAD,
                        BodyLifecycle.STOPPING,
                        -> Unit
                        else ->
                            transitions.markDisconnected(
                                runtime,
                                "player process exited with status ${exitCode ?: "unknown"}",
                            )
                    }
                }
            }
        }
    }

    fun despawn(agentId: AgentId): CompletionStage<Boolean> =
        registry.runtime(agentId)?.let(::stopRuntime)
            ?: completedBodyOperation(false)

    private fun stopRuntime(runtime: PlayerRuntime): CompletionStage<Boolean> {
        val agentId = runtime.identity.agentId
        val process = runtime.process
        val wasDead = runtime.lifecycle == BodyLifecycle.DEAD
        transitions.forget(agentId)
        if (!wasDead) {
            runtime.lifecycle = BodyLifecycle.STOPPING
            runtime.detail = "stopping player process"
        }
        if (process == null) {
            if (!wasDead) {
                runtime.lifecycle = BodyLifecycle.DISCONNECTED
                runtime.detail = "stopped"
            }
            return completedBodyOperation(false)
        }

        val answer = CompletableFuture<Boolean>()
        context.infrastructure.supervisor.stop(process).whenComplete { _, stopFailure ->
            context.authority.main.execute {
                val failure = unwrapCompletion(stopFailure)
                if (failure != null) {
                    if (registry.isCurrent(runtime) && !wasDead) {
                        runtime.lifecycle = BodyLifecycle.FAILED
                        runtime.detail = "player process cleanup failed"
                    }
                    answer.completeExceptionally(failure)
                } else {
                    if (registry.isCurrent(runtime) && !wasDead) {
                        runtime.lifecycle = BodyLifecycle.DISCONNECTED
                        runtime.detail = "stopped"
                        context.journal.record(agentId, BodyEvent.DESPAWNED, "player process stopped")
                    }
                    answer.complete(true)
                }
            }
        }
        return answer
    }

    fun tick(tick: Long) {
        sessions.expire(tick)
        for (runtime in registry.all()) {
            when {
                runtime.lifecycle == BodyLifecycle.DEAD -> Unit
                context.authority.serverState.isDead(runtime.identity.agentId) ->
                    transitions.markDead(runtime)
                runtime.lifecycle == BodyLifecycle.READY && runtime.process?.isAlive() != true ->
                    transitions.markDisconnected(runtime, "player process is no longer running")
            }
        }
    }

    fun close() {
        if (context.closing) return
        val runtimes = registry.beginClosing()
        runtimes.forEach { runtime ->
            transitions.forget(runtime.identity.agentId)
            if (runtime.lifecycle != BodyLifecycle.DEAD) {
                runtime.lifecycle = BodyLifecycle.STOPPING
                runtime.detail = "plugin shutdown"
            }
        }
        context.infrastructure.supervisor.close()
        runtimes.forEach { runtime ->
            if (runtime.lifecycle != BodyLifecycle.DEAD) {
                runtime.lifecycle = BodyLifecycle.DISCONNECTED
                runtime.detail = "stopped"
            }
        }
        context.infrastructure.scheduler.shutdownNow()
        context.infrastructure.io.shutdown()
        context.infrastructure.io.awaitTermination(
            context.infrastructure.settings.shutdownTimeout.toMillis(),
            TimeUnit.MILLISECONDS,
        )
    }
}

private enum class WitnessState {
    CANCELLED,
    DEAD,
    READY,
    EXITED,
    TIMED_OUT,
    WAITING,
}

private fun witnessState(
    context: PlayerBodyContext,
    registry: PlayerRuntimeRegistry,
    runtime: PlayerRuntime,
    process: SupervisedBodyProcess,
    deadline: Long,
): WitnessState =
    when {
        !registry.isCurrent(runtime) || runtime.process !== process || context.closing ->
            WitnessState.CANCELLED
        context.authority.serverState.isDead(runtime.identity.agentId) -> WitnessState.DEAD
        context.authority.serverState.connected(runtime.identity) -> WitnessState.READY
        !process.isAlive() -> WitnessState.EXITED
        System.nanoTime() >= deadline -> WitnessState.TIMED_OUT
        else -> WitnessState.WAITING
    }

private fun completeReady(
    context: PlayerBodyContext,
    runtime: PlayerRuntime,
    answer: CompletableFuture<BodySpawnResult>,
) {
    runtime.lifecycle = BodyLifecycle.READY
    runtime.detail = "ready as ${runtime.identity.username}"
    context.journal.record(
        runtime.identity.agentId,
        BodyEvent.SPAWNED,
        "player ${runtime.identity.username} (${runtime.identity.uuid}) ready at " +
            context.infrastructure.settings.controlEndpoint,
    )
    answer.complete(BodySpawnResult.Spawned(runtime.identity.agentId))
}
