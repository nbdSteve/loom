/*
 * Asynchronous actuation through one Mineflayer body-control process.
 */
package dev.loom.body.player

import dev.loom.body.AgentId
import dev.loom.body.EventTag
import dev.loom.body.IntentOutcome
import dev.loom.body.RefResolution
import dev.loom.body.SlotResolution
import dev.loom.body.completedBodyOperation
import dev.loom.bodycontrol.BODY_CONTROL_PROTOCOL_VERSION
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.ErrorCode
import dev.loom.contract.Intent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

private const val MOVEMENT_DEADLINE_MS = 20_000
private const val FLEE_DISTANCE_BLOCKS = 24

internal data class PendingAction(
    val agentId: AgentId,
    val intent: Intent,
    val requestId: String?,
    val runtime: PlayerRuntime,
    val sense: HeldSense?,
    val answer: CompletableFuture<IntentOutcome>,
)

/** Applies prepared body-control operations and reconciles their completion. */
internal class PlayerActuation(
    private val context: PlayerBodyContext,
    private val registry: PlayerRuntimeRegistry,
    private val sessions: PlayerSenseSessions,
    private val transitions: PlayerLifecycleTransitions,
    private val preparer: PlayerActionPreparer,
    private val evidence: PlayerEvidenceRecorder,
) {
    fun apply(
        agentId: AgentId,
        intent: Intent,
        requestId: String?,
    ): CompletionStage<IntentOutcome> {
        val runtime = registry.ready(agentId) ?: return completedBodyOperation(IntentOutcome.NoSuchBody)
        val sense = requestId?.let { sessions.get(agentId, it) }
        sense?.stopKeepalive()
        val answer = CompletableFuture<IntentOutcome>()
        val pending = PendingAction(agentId, intent, requestId, runtime, sense, answer)
        CompletableFuture
            .supplyAsync(
                preparer.prepare(runtime, intent, requestId, sense),
                context.infrastructure.io,
            ).whenComplete { execution, failure ->
                context.authority.main.execute {
                    complete(pending, execution, failure)
                }
            }
        return answer
    }

    private fun complete(
        pending: PendingAction,
        execution: ActionExecution?,
        failure: Throwable?,
    ) {
        pending.requestId?.let {
            sessions.settle(pending.agentId, it, pending.sense)
        }
        when {
            failure != null -> {
                val cause = unwrapCompletion(failure)
                val processGone = pending.runtime.process?.isAlive() != true
                val notReady =
                    cause is BodyControlFailure.Refused &&
                        cause.code == ErrorCode.NOT_READY
                if (processGone) {
                    transitions.markDisconnected(
                        pending.runtime,
                        "player body became unavailable during actuation",
                    )
                    pending.answer.complete(IntentOutcome.NoSuchBody)
                } else {
                    val outcome =
                        if (notReady) {
                            IntentOutcome.Rejected(
                                "the player body was not ready for another operation",
                                EventTag.CONTROL_NOT_READY,
                            )
                        } else {
                            IntentOutcome.Rejected(
                                "the body-control request was unavailable",
                                EventTag.CONTROL_UNAVAILABLE,
                            )
                        }
                    evidence.recordAction(
                        pending.agentId,
                        pending.intent,
                        pending.requestId,
                        outcome,
                    )
                    pending.answer.complete(outcome)
                }
            }
            registry.ready(pending.agentId) !== pending.runtime || execution == null ->
                pending.answer.complete(IntentOutcome.NoSuchBody)
            else -> {
                evidence.auditAndRecord(
                    pending.runtime,
                    pending.intent,
                    pending.requestId,
                    execution,
                )
                pending.answer.complete(execution.outcome)
            }
        }
    }
}

/** Resolves main-thread handles before returning one immutable I/O operation. */
internal class PlayerActionPreparer(
    private val context: PlayerBodyContext,
) {
    fun prepare(
        runtime: PlayerRuntime,
        intent: Intent,
        requestId: String?,
        sense: HeldSense?,
    ): () -> ActionExecution =
        when (intent) {
            is Intent.Say -> {
                { ActionExecution(IntentOutcome.Unsupported("say")) }
            }
            is Intent.Idle -> {
                { halt(runtime, "standing still") }
            }
            is Intent.Rest -> {
                { halt(runtime, "resting") }
            }
            is Intent.MoveTo -> {
                { move(runtime, intent, sense) }
            }
            is Intent.Eat -> prepareEat(runtime, intent, requestId, sense)
            is Intent.Flee -> prepareFlee(runtime, intent, requestId, sense)
        }

    private fun halt(
        runtime: PlayerRuntime,
        effect: String,
    ): ActionExecution =
        withControl(runtime, null) { connection ->
            connection.request(
                BodyRequest.Halt(
                    protocol = BODY_CONTROL_PROTOCOL_VERSION,
                    requestId = context.infrastructure.requestIds.next("halt"),
                    agentId = runtime.identity.agentId.value,
                ),
                context.infrastructure.settings.requestTimeout,
            ) as? BodyResponse.HaltReport
                ?: throw BodyControlFailure.ProtocolViolation()
            ActionExecution(IntentOutcome.Accepted(effect))
        }

    private fun move(
        runtime: PlayerRuntime,
        intent: Intent.MoveTo,
        sense: HeldSense?,
    ): ActionExecution {
        val request =
            BodyRequest.MoveTo(
                protocol = BODY_CONTROL_PROTOCOL_VERSION,
                requestId = context.infrastructure.requestIds.next("move"),
                agentId = runtime.identity.agentId.value,
                destination = intent.target.map(Long::toDouble),
                tolerance = 0,
                timeoutMs = MOVEMENT_DEADLINE_MS,
                sprint = false,
            )
        return withControl(runtime, sense) { connection ->
            val report =
                connection.request(
                    request,
                    context.infrastructure.settings.movementTimeout,
                ) as? BodyResponse.MoveReport
                    ?: throw BodyControlFailure.ProtocolViolation()
            ActionExecution(
                outcome = movementOutcome(report.outcome, "walk"),
                moveRequest = request,
                moveReport = report,
            )
        }
    }

    private fun prepareEat(
        runtime: PlayerRuntime,
        intent: Intent.Eat,
        requestId: String?,
        sense: HeldSense?,
    ): () -> ActionExecution {
        val resolved =
            requestId?.let {
                context.refs.slots.resolve(
                    runtime.identity.agentId,
                    it,
                    intent.ref,
                    context.authority.currentTick(),
                )
            } ?: SlotResolution.Stale
        return when (resolved) {
            SlotResolution.Stale ->
                rejectedAction(
                    "no request of this agent's named that stack",
                    EventTag.EAT_SLOT_FORGOTTEN,
                )
            SlotResolution.Unknown ->
                rejectedAction(
                    "this request named no such stack",
                    EventTag.EAT_SLOT_UNNAMED,
                )
            is SlotResolution.Bound ->
                if (sense == null || requestId == null) {
                    rejectedAction(
                        "the sense connection that named that stack is no longer open",
                        EventTag.EAT_SLOT_FORGOTTEN,
                    )
                } else {
                    val request =
                        BodyRequest.Eat(
                            protocol = BODY_CONTROL_PROTOCOL_VERSION,
                            requestId = context.infrastructure.requestIds.next("eat"),
                            agentId = runtime.identity.agentId.value,
                            senseId = requestId,
                            slot = resolved.slot,
                        )
                    val operation: () -> ActionExecution = { eat(sense, request) }
                    operation
                }
        }
    }

    private fun eat(
        sense: HeldSense,
        request: BodyRequest.Eat,
    ): ActionExecution {
        val report =
            sense.raw.connection.request(
                request,
                context.infrastructure.settings.requestTimeout,
            ) as? BodyResponse.EatReport
                ?: throw BodyControlFailure.ProtocolViolation()
        return ActionExecution(eatOutcome(report), eatReport = report)
    }

    private fun prepareFlee(
        runtime: PlayerRuntime,
        intent: Intent.Flee,
        requestId: String?,
        sense: HeldSense?,
    ): () -> ActionExecution {
        val resolved =
            requestId?.let {
                context.refs.creatures.resolve(
                    runtime.identity.agentId,
                    it,
                    intent.from,
                    context.authority.currentTick(),
                )
            } ?: RefResolution.Stale
        return when (resolved) {
            RefResolution.Stale ->
                rejectedAction(
                    "the threat was named by a request this agent no longer holds",
                    EventTag.RETREAT_THREAT_FORGOTTEN,
                )
            RefResolution.Unknown ->
                rejectedAction(
                    "that request never named such a threat",
                    EventTag.RETREAT_THREAT_UNNAMED,
                )
            is RefResolution.Bound -> {
                val entity = sense?.entityIndex?.get(resolved.subject)
                if (entity == null || requestId == null) {
                    rejectedAction(
                        "the sense connection that named that threat is no longer open",
                        EventTag.RETREAT_THREAT_FORGOTTEN,
                    )
                } else {
                    val request =
                        BodyRequest.Flee(
                            protocol = BODY_CONTROL_PROTOCOL_VERSION,
                            requestId = context.infrastructure.requestIds.next("flee"),
                            agentId = runtime.identity.agentId.value,
                            senseId = requestId,
                            entity = entity,
                            distance = FLEE_DISTANCE_BLOCKS,
                            timeoutMs = MOVEMENT_DEADLINE_MS,
                            sprint = true,
                        )
                    val operation: () -> ActionExecution = { flee(sense, request) }
                    operation
                }
            }
        }
    }

    private fun flee(
        sense: HeldSense,
        request: BodyRequest.Flee,
    ): ActionExecution {
        val report =
            sense.raw.connection.request(
                request,
                context.infrastructure.settings.movementTimeout,
            ) as? BodyResponse.FleeReport
                ?: throw BodyControlFailure.ProtocolViolation()
        return ActionExecution(
            outcome = movementOutcome(report.outcome, "flight"),
            fleeRequest = request,
            fleeReport = report,
        )
    }

    private fun <T> withControl(
        runtime: PlayerRuntime,
        sense: HeldSense?,
        operation: (BodyControlConnection) -> T,
    ): T =
        if (sense != null) {
            operation(sense.raw.connection)
        } else {
            context.infrastructure.connector
                .connect(
                    context.infrastructure.settings.controlEndpoint,
                    runtime.identity,
                ).use(operation)
        }
}

private fun rejectedAction(
    reason: String,
    tag: String,
): () -> ActionExecution =
    {
        ActionExecution(IntentOutcome.Rejected(reason, tag))
    }
