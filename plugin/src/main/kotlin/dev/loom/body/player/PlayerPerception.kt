/*
 * Bounded perception and request-scoped session ownership for the player body.
 */
package dev.loom.body.player

import dev.loom.body.AgentId
import dev.loom.body.AgentVitals
import dev.loom.body.BODY_SENSE_MAX_OFFERS_PER_KIND
import dev.loom.body.BodyEvent
import dev.loom.body.EventTag
import dev.loom.body.PERCEPTION_RADIUS_BLOCKS
import dev.loom.body.PendingPerceptions
import dev.loom.body.blocksApart
import dev.loom.body.completedBodyOperation
import dev.loom.body.tagOf
import dev.loom.bodycontrol.BODY_CONTROL_PROTOCOL_VERSION
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.MAX_RETAINED_ITEMS
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
        capture(agentId, tagOf(trigger)).thenApplyOn(context.authority.main) { raw ->
            raw?.let { sceneFor(agentId, it) }?.let { scene -> commit(scene, trigger) }
        }

    fun senseThreat(agentId: AgentId): CompletionStage<Perception?> =
        capture(agentId, "threat scan").thenApplyOn(context.authority.main) { raw ->
            raw?.let { sceneFor(agentId, it) }?.let { scene ->
                // The player body makes no route search toward a threat, so it states none.
                val outcome = context.threatWatch.consider(agentId, scene.candidates) { null }
                outcome.suppressed.forEach { context.journal.recordSuppressedThreat(agentId, it) }
                outcome.sighted?.let { trigger ->
                    commit(scene, trigger).also { sent ->
                        val creature = scene.candidates.firstOrNull { it.ref == trigger.ref }
                        creature?.let { subjects.remember(sent.requestId, it.subject) }
                    }
                }
                    ?: run {
                        scene.raw.connection.close()
                        null
                    }
            }
        }

    /** The server identity of each recent sighting's creature, by request. */
    val subjects = ThreatSubjects()

    /** A fresh sense held for one pickup attempt, its own drops retained even out of sight; no perception. */
    fun senseForPickup(agentId: AgentId): CompletionStage<HeldSense?> =
        capture(agentId, "own drop pickup").thenApplyOn(context.authority.main) { raw ->
            raw?.let { sceneFor(agentId, it) }?.let(sessions::remember)
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

    private fun capture(
        agentId: AgentId,
        asked: String,
    ): CompletionStage<RawSense?> {
        val runtime = registry.ready(agentId)
        val atCapacity =
            runtime != null &&
                sessions.openCount(agentId) + runtime.sensesInFlight >= BODY_SENSE_MEMORY
        if (atCapacity) {
            context.journal.classified(
                agentId,
                BodyEvent.PERCEPTION_REFUSED,
                "a $asked perception was not taken: the body already held every sense it keeps",
                EventTag.SENSE_AT_CAPACITY,
            )
        }
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
                limit = BODY_SENSE_MAX_OFFERS_PER_KIND,
                // The agent's own drops are reported even out of sight, so it can collect them.
                retainItems =
                    context.authority.ownDrops
                        .of(runtime.identity)
                        .mapNotNull { it.entity?.toString() }
                        .distinct()
                        .take(MAX_RETAINED_ITEMS)
                        .ifEmpty { null },
            )
        val capturedTick = context.authority.currentTick()
        val auditSnapshot = context.authority.serverState.captureSenseAudit(runtime.identity)
        val stage =
            CompletableFuture.supplyAsync(
                { requestSense(runtime.identity, capturedTick, auditSnapshot, request) },
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
        auditSnapshot: SenseAuditSnapshot?,
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
            RawSense(identity, capturedTick, auditSnapshot, connection, report)
        }.getOrElse { failure ->
            connection.close()
            throw failure
        }
    }

    private fun validateSense(
        request: BodyRequest.Sense,
        report: BodyResponse.SenseReport,
    ) {
        if (!matchesSenseRequest(request, report)) {
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
        val omitted = perception.surroundingsOmitted
        val detail =
            "${perception.surroundings.size} observation(s)" +
                if (omitted > 0) ", $omitted more nearby not reported" else ""
        context.perceptions.hold(
            agentId,
            perception,
            PendingPerceptions.Captured(detail, scene.perceptionAudit, food),
            after = { context.vitalsWatch.recordChanges(agentId, food, health) },
        )
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

/**
 * The tallest a player's eyes stand above its feet.
 *
 * The body admits a creature or item by its distance from the eyes, while the reported
 * position is the feet, so a thing up a slope at the radius from the eyes can lie up to
 * this much further from the feet. A check measured from the feet must allow it.
 */
internal const val MAX_PLAYER_EYE_HEIGHT_BLOCKS: Double = 1.62

internal fun matchesSenseRequest(
    request: BodyRequest.Sense,
    report: BodyResponse.SenseReport,
): Boolean {
    val listsFit =
        maxOf(report.entities.size, report.blocks.size, report.items.size) <= request.limit
    val radius = request.radius.toDouble() + MAX_PLAYER_EYE_HEIGHT_BLOCKS
    val entitiesFit =
        report.entities.none { blocksApart(report.position, it.position) > radius }
    val blocksFit =
        report.blocks.none {
            blocksApart(report.position, it.position.map(Int::toDouble)) > radius
        }
    val itemsFit =
        report.items.none { blocksApart(report.position, it.position) > radius }
    return listsFit && listOf(entitiesFit, blocksFit, itemsFit).all { it }
}

/** The server identity of each recent threat sighting's creature, by request; the oldest is dropped. */
internal class ThreatSubjects {
    private val byRequest = LinkedHashMap<String, String>()

    @Synchronized
    operator fun get(requestId: String): String? = byRequest[requestId]

    @Synchronized
    fun remember(
        requestId: String,
        subject: java.util.UUID,
    ) {
        byRequest[requestId] = subject.toString()
        while (byRequest.size > MAX_THREAT_SUBJECTS) byRequest.remove(byRequest.keys.first())
    }
}

/** Recent sightings whose creature identity is kept for danger tracking. */
private const val MAX_THREAT_SUBJECTS = 64
