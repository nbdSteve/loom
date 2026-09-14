/*
 * Bounded perception and request-scoped session ownership for the player body.
 */
package dev.loom.body.player

import dev.loom.body.AgentId
import dev.loom.body.AgentVitals
import dev.loom.body.EventTag
import dev.loom.body.PERCEPTION_MAX_OBSERVATIONS
import dev.loom.body.PERCEPTION_RADIUS_BLOCKS
import dev.loom.body.blocksApart
import dev.loom.body.completedBodyOperation
import dev.loom.body.tagOf
import dev.loom.bodycontrol.BODY_CONTROL_PROTOCOL_VERSION
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

private const val PLAYER_FOOD_CAPACITY = 20.0
private const val PLAYER_HEALTH_CAPACITY = 20.0
private const val BODY_SENSE_MEMORY = 4
private const val KEEPALIVE_SECONDS = 10L

/** Captures and commits Mineflayer sense reports without blocking Paper's tick. */
internal class PlayerPerception(
    private val context: PlayerBodyContext,
    private val registry: PlayerRuntimeRegistry,
    private val sessions: PlayerSenseSessions,
    private val transitions: PlayerLifecycleTransitions,
    private val translator: PlayerSceneTranslator,
) {
    fun perceive(
        agentId: AgentId,
        trigger: Trigger,
    ): CompletionStage<Perception?> =
        capture(agentId).thenApplyOn(context.authority.main) { raw ->
            raw?.let { sceneFor(agentId, it) }?.let { scene -> commit(scene, trigger) }
        }

    fun senseThreat(agentId: AgentId): CompletionStage<Perception?> =
        capture(agentId).thenApplyOn(context.authority.main) { raw ->
            raw?.let { sceneFor(agentId, it) }?.let { scene ->
                val outcome = context.threatWatch.consider(agentId, scene.candidates) { false }
                outcome.suppressed.forEach { context.journal.recordSuppressedThreat(agentId, it) }
                outcome.sighted?.let { trigger -> commit(scene, trigger) }
                    ?: run {
                        scene.raw.connection.close()
                        null
                    }
            }
        }

    private fun sceneFor(
        agentId: AgentId,
        raw: RawSense,
    ): PlayerScene? {
        val runtime = registry.ready(agentId)
        if (runtime == null || runtime.identity != raw.identity) {
            raw.connection.close()
            return null
        }
        return runCatching { translator.sceneOf(raw) }
            .getOrElse { failure ->
                raw.connection.close()
                throw failure
            }
    }

    private fun capture(agentId: AgentId): CompletionStage<RawSense?> {
        val runtime = registry.ready(agentId)
        val atCapacity =
            runtime != null &&
                sessions.openCount(agentId) + runtime.sensesInFlight >= BODY_SENSE_MEMORY
        return when {
            runtime == null || atCapacity -> completedBodyOperation(null)
            else -> startCapture(runtime)
        }
    }

    private fun startCapture(runtime: PlayerRuntime): CompletionStage<RawSense?> {
        runtime.sensesInFlight += 1
        val request =
            BodyRequest.Sense(
                protocol = BODY_CONTROL_PROTOCOL_VERSION,
                requestId = context.infrastructure.requestIds.next("sense"),
                agentId = runtime.identity.agentId.value,
                radius = PERCEPTION_RADIUS_BLOCKS.toInt(),
                limit = PERCEPTION_MAX_OBSERVATIONS,
            )
        val capturedTick = context.authority.currentTick()
        val stage =
            CompletableFuture.supplyAsync(
                { requestSense(runtime.identity, capturedTick, request) },
                context.infrastructure.io,
            )
        val answer = CompletableFuture<RawSense?>()
        stage.whenComplete { raw, failure ->
            context.authority.main.execute {
                if (registry.isCurrent(runtime)) {
                    runtime.sensesInFlight = (runtime.sensesInFlight - 1).coerceAtLeast(0)
                }
                when {
                    failure != null -> {
                        if (runtime.process?.isAlive() == false) {
                            transitions.markDisconnected(
                                runtime,
                                "player process exited during perception",
                                EventTag.BODY_PROCESS_EXITED,
                            )
                        }
                        answer.completeExceptionally(
                            unwrapCompletion(failure) ?: BodyControlFailure.Unavailable(),
                        )
                    }
                    registry.ready(runtime.identity.agentId) !== runtime -> {
                        raw?.connection?.close()
                        answer.complete(null)
                    }
                    else -> answer.complete(raw)
                }
            }
        }
        return answer
    }

    private fun requestSense(
        identity: PlayerIdentity,
        capturedTick: Long,
        request: BodyRequest.Sense,
    ): RawSense {
        val connection =
            context.infrastructure.connector.connect(
                context.infrastructure.settings.controlEndpoint,
                identity,
            )
        return runCatching {
            val report =
                connection.request(
                    request,
                    context.infrastructure.settings.requestTimeout,
                ) as? BodyResponse.SenseReport
                    ?: throw BodyControlFailure.ProtocolViolation()
            validateSense(request, report)
            RawSense(identity, capturedTick, connection, report)
        }.getOrElse { failure ->
            connection.close()
            throw failure
        }
    }

    private fun validateSense(
        request: BodyRequest.Sense,
        report: BodyResponse.SenseReport,
    ) {
        if (report.entities.size > request.limit ||
            report.entities.any {
                blocksApart(report.position, it.position) > request.radius.toDouble()
            }
        ) {
            throw BodyControlFailure.ProtocolViolation()
        }
    }

    private fun commit(
        scene: PlayerScene,
        trigger: Trigger,
    ): Perception {
        val agentId = scene.raw.identity.agentId
        val requestId = scene.raw.report.requestId
        val sense = sessions.remember(scene)
        armKeepalive(agentId, requestId, sense)

        val perception = scene.perception(trigger)
        val food = AgentVitals(scene.raw.report.vitals.food.toDouble(), PLAYER_FOOD_CAPACITY)
        val health =
            AgentVitals(
                scene.raw.report.vitals.health.coerceAtLeast(0.0),
                PLAYER_HEALTH_CAPACITY,
            )
        context.vitalsWatch.noted(agentId, food)
        context.journal.recordPerception(
            agentId = agentId,
            detail =
                "${perception.surroundings.size} observation(s)" +
                    if (perception.surroundingsOmitted > 0) {
                        ", ${perception.surroundingsOmitted} more nearby not reported"
                    } else {
                        ""
                    },
            requestId = requestId,
            trigger = tagOf(trigger),
            vitals = food,
        )
        context.vitalsWatch.recordChanges(agentId, food, health)
        return perception
    }

    private fun armKeepalive(
        agentId: AgentId,
        requestId: String,
        sense: HeldSense,
    ) {
        sense.keepalive =
            context.infrastructure.scheduler.scheduleAtFixedRate(
                {
                    if (!sense.isClosed()) {
                        context.infrastructure.io.execute {
                            val failure =
                                runCatching {
                                    sense.raw.connection.request(
                                        BodyRequest.Status(
                                            protocol = BODY_CONTROL_PROTOCOL_VERSION,
                                            requestId = context.infrastructure.requestIds.next("keepalive"),
                                            agentId = agentId.value,
                                        ),
                                        context.infrastructure.settings.requestTimeout,
                                    )
                                }.exceptionOrNull()
                            if (failure != null) {
                                context.authority.main.execute {
                                    if (sessions.get(agentId, requestId) === sense) {
                                        sessions.settle(agentId, requestId, sense)
                                    }
                                }
                            }
                        }
                    }
                },
                KEEPALIVE_SECONDS,
                KEEPALIVE_SECONDS,
                TimeUnit.SECONDS,
            )
    }
}
