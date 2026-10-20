/*
 * Asynchronous actuation through one Mineflayer body-control process.
 */
package dev.loom.body.player

import dev.loom.body.AgentId
import dev.loom.body.EventTag
import dev.loom.body.IntentOutcome
import dev.loom.body.RefResolution
import dev.loom.body.SlotResolution
import dev.loom.body.WorldTargetResolution
import dev.loom.body.blocksApart
import dev.loom.body.completedBodyOperation
import dev.loom.body.horizontalBearing
import dev.loom.body.huntsPlayers
import dev.loom.bodycontrol.BODY_CONTROL_PROTOCOL_VERSION
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.ErrorCode
import dev.loom.contract.Bearing
import dev.loom.contract.Intent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

private const val MOVEMENT_DEADLINE_MS = 20_000
private const val FLEE_DISTANCE_BLOCKS = 24

/** Flight attempts against a busy body: the first, one after the halt, and settling retries. */
private const val PREEMPT_ATTEMPTS = 6

/** How long a halted interaction is given to settle between flight attempts. */
private const val PREEMPT_SETTLE_MS = 100L

/** From a block's corner coordinate to its centre on a horizontal axis. */
private const val BLOCK_CENTER_OFFSET = 0.5

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
                        EventTag.BODY_PROCESS_EXITED,
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
                pending.answer.complete(
                    evidence.auditAndRecord(
                        pending.runtime,
                        pending.intent,
                        pending.requestId,
                        execution,
                    ),
                )
            }
        }
    }
}

/** Resolves main-thread handles before returning one immutable I/O operation. */
internal class PlayerActionPreparer(
    private val context: PlayerBodyContext,
    private val interactions: PlayerInteractionPreparer = PlayerInteractionPreparer(context),
    private val survival: PlayerSurvivalPreparer = PlayerSurvivalPreparer(context),
    private val speech: PlayerSpeech = PlayerSpeech(context),
) {
    fun prepare(
        runtime: PlayerRuntime,
        intent: Intent,
        requestId: String?,
        sense: HeldSense?,
    ): () -> ActionExecution =
        when (intent) {
            is Intent.Say ->
                speech.prepare(runtime, intent.text, requestId) { request ->
                    withControl(runtime, null) { connection ->
                        connection.request(request, context.infrastructure.settings.requestTimeout)
                    }
                }
            is Intent.Idle -> {
                { halt(runtime, "standing still") }
            }
            is Intent.Rest -> {
                { halt(runtime, "resting") }
            }
            is Intent.MoveTo -> prepareMove(runtime, intent, requestId, sense)
            is Intent.Eat -> prepareEat(runtime, intent, requestId, sense)
            is Intent.Flee -> prepareFlee(runtime, intent, requestId, sense)
            is Intent.Dig -> interactions.prepare(runtime, intent, requestId, sense)
            is Intent.Collect -> interactions.prepare(runtime, intent, requestId, sense)
            is Intent.Place -> interactions.prepare(runtime, intent, requestId, sense)
            is Intent.Equip -> interactions.prepare(runtime, intent, requestId, sense)
            is Intent.Craft -> survival.prepare(runtime, intent, requestId, sense)
            is Intent.Attack -> survival.prepare(runtime, intent, requestId, sense)
            is Intent.Sleep -> survival.prepare(runtime, intent, requestId, sense)
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

    /**
     * Resolves the block a walk names, on the main thread, to where a body stands on it: the
     * cell directly above the exact position one sense answer reported, or that cell itself
     * for a block the server gives no collision, such as a dead bush.
     *
     * Nothing here judges whether that place can be reached: the destination is the named
     * block's own, and the ground answers for it. A ref this agent no longer holds, or one the
     * request never issued, is refused before anything is asked of the body.
     */
    private fun prepareMove(
        runtime: PlayerRuntime,
        intent: Intent.MoveTo,
        requestId: String?,
        sense: HeldSense?,
    ): () -> ActionExecution {
        // A cell at the edge of sight is where its walk ends: the server stood it up when it was offered.
        val explored = sense?.exploreCells?.get(intent.onto)?.map(Int::toDouble)
        val resolved =
            requestId?.let {
                context.refs.blocks.resolve(
                    runtime.identity.agentId,
                    it,
                    intent.onto,
                    context.authority.currentTick(),
                )
            } ?: WorldTargetResolution.Stale
        val block =
            (resolved as? WorldTargetResolution.Bound)?.let { sense?.raw?.report?.blocks?.getOrNull(it.index) }
        return when {
            explored != null -> {
                { move(runtime, explored, sense) }
            }
            resolved == WorldTargetResolution.Unknown ->
                rejectedAction("that request never named such a block", EventTag.MOVE_BLOCK_UNNAMED)
            block == null ->
                rejectedAction(
                    "the block was named by a request this agent no longer holds",
                    EventTag.MOVE_SENSE_FORGOTTEN,
                )
            ownColumn(block.position, sense) ->
                rejectedAction("the block is in the agent's own spot, so a walk goes nowhere", EventTag.MOVE_OWN_SPOT)
            else -> {
                // A block with no collision is stood in, so its own cell is the place; any
                // other is stood on, so the cell above it is. The server says which, and says
                // when a covered block leaves nowhere to stand at all.
                val cell =
                    context.authority.ground.standCell(block.position)
                        ?: return rejectedAction(
                            "no body can stand at that block; it is covered",
                            EventTag.MOVE_NOT_STANDABLE,
                        )
                val destination = cell.map(Int::toDouble)
                val operation: () -> ActionExecution = { move(runtime, destination, sense) }
                operation
            }
        }
    }

    /** Whether [position] lies in the column the body stood in when [sense] was answered. */
    private fun ownColumn(
        position: List<Int>,
        sense: HeldSense?,
    ): Boolean {
        val standing = sense?.raw?.report?.position ?: return false
        val (x, _, z) = position
        val bearing = horizontalBearing(x + BLOCK_CENTER_OFFSET - standing[0], z + BLOCK_CENTER_OFFSET - standing[2])
        return bearing == Bearing.HERE
    }

    private fun move(
        runtime: PlayerRuntime,
        destination: List<Double>,
        sense: HeldSense?,
    ): ActionExecution {
        val request =
            BodyRequest.MoveTo(
                protocol = BODY_CONTROL_PROTOCOL_VERSION,
                requestId = context.infrastructure.requestIds.next("move"),
                agentId = runtime.identity.agentId.value,
                destination = destination,
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
                outcome = movementOutcome(report.outcome, WALK),
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
                            sprint = context.sprintsFrom(sense, resolved.subject, entity),
                            avoid = otherHuntersOf(sense, entity).takeIf { it.isNotEmpty() },
                            // Takes over from a running walk or flight only once its own threat checks
                            // out, so a refused flight never stops a valid one (r33 gamma).
                            replace = true,
                        )
                    val operation: () -> ActionExecution = { flee(runtime, sense, request) }
                    operation
                }
            }
        }
    }

    /**
     * One flight, which preempts routine work: a flee is the answer to a threat, so when the body
     * is busy with a dig or another exclusive request it is halted and the flight asked for again,
     * a bounded number of times while the halted work settles. r13 eta's flight was refused as
     * notReady for 130 ticks behind a dig decided before the zombie appeared.
     */
    private fun flee(
        runtime: PlayerRuntime,
        sense: HeldSense,
        request: BodyRequest.Flee,
    ): ActionExecution {
        var attempt = request
        var halted = false
        repeat(PREEMPT_ATTEMPTS) { tries ->
            try {
                return fleeOnce(sense, attempt, context.infrastructure.settings.movementTimeout)
            } catch (busy: BodyControlFailure.Refused) {
                if (busy.code != ErrorCode.NOT_READY || tries == PREEMPT_ATTEMPTS - 1) throw busy
                if (!halted) {
                    halt(runtime, "halted routine work for a flight")
                    halted = true
                } else {
                    Thread.sleep(PREEMPT_SETTLE_MS)
                }
                attempt = attempt.copy(requestId = context.infrastructure.requestIds.next("flee"))
            }
        }
        error("unreachable: the last attempt returns or throws")
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

/**
 * Whether the flight from [entity] of [sense] sprints: only from a threat near enough, or closing
 * fast enough, to need it (`ThreatApproach`).
 */
private fun PlayerBodyContext.sprintsFrom(
    sense: HeldSense,
    subject: java.util.UUID,
    entity: Int,
): Boolean {
    val seen = sense.raw.report.entities.getOrNull(entity)?.position ?: return true
    return approach.sprints(subject, sense.raw.capturedTick, blocksApart(sense.raw.report.position, seen))
}

/** One flight request on the sense connection that named its threat. */
private fun fleeOnce(
    sense: HeldSense,
    request: BodyRequest.Flee,
    timeout: java.time.Duration,
): ActionExecution {
    val report =
        sense.raw.connection.request(request, timeout) as? BodyResponse.FleeReport
            ?: throw BodyControlFailure.ProtocolViolation()
    return ActionExecution(
        outcome = movementOutcome(report.outcome, "flight"),
        fleeRequest = request,
        fleeReport = report,
        fleeHunters =
            (listOf(request.entity) + request.avoid.orEmpty())
                .mapNotNull { sense.raw.report.entities.getOrNull(it)?.position },
    )
}

/**
 * The other creatures of [sense] that hunt the agent's kind, by the answer's own ordering, that a
 * flight from [entity] must end clear of too (r24: gamma's flight from a spider ended 0.6 blocks
 * from a creeper). The JVM names them, so the body still chooses nothing.
 */
internal fun otherHuntersOf(
    sense: HeldSense,
    entity: Int,
): List<Int> =
    sense.raw.report.entities
        .withIndex()
        .filter {
                (index, seen) ->
            index != entity && huntsPlayers("entity.minecraft." + seen.what.removePrefix("minecraft:"))
        }
        .map { it.index }
        .take(dev.loom.bodycontrol.MAX_FLIGHT_AVOID)
