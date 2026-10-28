/*
 * Asynchronous actuation through one Mineflayer body-control process.
 */
package dev.loom.body.player

import dev.loom.body.AgentId
import dev.loom.body.EventTag
import dev.loom.body.IntentOutcome
import dev.loom.body.RefResolution
import dev.loom.body.SlotResolution
import dev.loom.body.SprintChoice
import dev.loom.body.SprintReason
import dev.loom.body.WorldTargetResolution
import dev.loom.body.blocksApart
import dev.loom.body.completedBodyOperation
import dev.loom.body.horizontalBearing
import dev.loom.body.huntsPlayers
import dev.loom.bodycontrol.BODY_CONTROL_PROTOCOL_VERSION
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.ErrorCode
import dev.loom.bodycontrol.MoveOutcome
import dev.loom.contract.Bearing
import dev.loom.contract.Intent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

private const val MOVEMENT_DEADLINE_MS = 20_000
private const val FLEE_DISTANCE_BLOCKS = 24

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
        // The newest decision applied governs the body: an older flight still being carried out
        // never halts or replaces it (r34 beta).
        requestId?.let { preparer.newest.applied(agentId, it) }
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

    /** The order this agent's perceptions were asked in, which dates each decision applied. */
    val newest: NewestDecisions get() = preparer.newest

    /**
     * Stops whatever cancellable work [agentId]'s body is carrying out for decision [decidedBy], off
     * the main thread, with the same halt an idle decision sends; nothing is recorded, since no
     * decision of its own asked. A decision a newer one of the agent's own has replaced halts nothing.
     */
    fun halt(
        agentId: AgentId,
        decidedBy: String?,
    ): CompletionStage<Boolean> {
        val replaced = decidedBy != null && preparer.newest.outdated(agentId, decidedBy)
        val runtime = registry.ready(agentId)?.takeUnless { replaced } ?: return completedBodyOperation(false)
        val stopping = preparer.prepare(runtime, Intent.Idle, null, null)
        return CompletableFuture
            .supplyAsync(stopping, context.infrastructure.io)
            .handle { stopped, failure -> failure == null && stopped?.outcome is IntentOutcome.Accepted }
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
    private val speech: PlayerSpeech = PlayerSpeech(context),
    /** The newest decision applied to each agent's body; an older flight never halts or replaces it. */
    val newest: NewestDecisions = NewestDecisions(),
) {
    private val preemption =
        PlayerPreemption(newest, context.infrastructure.requestIds::next) { runtime ->
            halt(runtime, "halted earlier work for a newer decision")
        }
    private val interactions = PlayerInteractionPreparer(context, preemption)
    private val survival = PlayerSurvivalPreparer(context, preemption)

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
                { halt(runtime, "standing still", requestId) }
            }
            is Intent.Rest -> {
                { halt(runtime, "resting", requestId) }
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

    /**
     * Halts the body's work, for decision [decidedBy] when one asked: an idle or a rest leaves the
     * work of a decision asked after its own running, since that one governs the body (the r39
     * offline audit: an older review's idle halted a newer danger answer's flight).
     */
    private fun halt(
        runtime: PlayerRuntime,
        effect: String,
        decidedBy: String? = null,
    ): ActionExecution {
        if (decidedBy != null && newest.outdated(runtime.identity.agentId, decidedBy)) {
            return ActionExecution(IntentOutcome.Accepted("left a newer decision's work running"))
        }
        return withControl(runtime, null) { connection ->
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
        val explored = sense?.targets?.exploreCells?.get(intent.onto)?.map(Int::toDouble)
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
                { move(runtime, explored, sense, requestId) }
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
                val operation: () -> ActionExecution = { move(runtime, destination, sense, requestId) }
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
        decidedBy: String?,
    ): ActionExecution {
        val prepared =
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
            val (request, response) =
                preemption.send(runtime, decidedBy, prepared) {
                    connection.request(it, context.infrastructure.settings.movementTimeout)
                }
            val report = response as? BodyResponse.MoveReport ?: throw BodyControlFailure.ProtocolViolation()
            ActionExecution(
                outcome = movementOutcome(report.outcome, WALK),
                moveRequest = request as BodyRequest.MoveTo,
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
                    val sprint = context.sprintFrom(sense, resolved.subject, entity)
                    val request =
                        BodyRequest.Flee(
                            protocol = BODY_CONTROL_PROTOCOL_VERSION,
                            requestId = context.infrastructure.requestIds.next("flee"),
                            agentId = runtime.identity.agentId.value,
                            senseId = requestId,
                            entity = entity,
                            distance = FLEE_DISTANCE_BLOCKS,
                            timeoutMs = MOVEMENT_DEADLINE_MS,
                            sprint = sprint.sprint,
                            avoid = otherHuntersOf(sense, entity).takeIf { it.isNotEmpty() },
                            // Takes over from a running walk or flight only once its own threat checks
                            // out, so a refused flight never stops a valid one (r33 gamma).
                            replace = true,
                        )
                    val operation: () -> ActionExecution = { flee(runtime, sense, request, sprint, requestId) }
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
        sprint: SprintChoice,
        decidedBy: String,
    ): ActionExecution {
        val agentId = runtime.identity.agentId
        var attempt = request
        var halted = false
        repeat(PREEMPT_ATTEMPTS) { tries ->
            try {
                val timeout = context.infrastructure.settings.movementTimeout
                val fled = fleeOnce(sense, attempt, timeout).copy(fleeSprint = sprint)
                // Displaced from its takeover by the newer flight before it began: stale, not superseded.
                val unstarted = fled.fleeReport?.outcome == MoveOutcome.CANCELLED && fled.fleeReport.travelled == 0.0
                val stale = unstarted && newest.outdated(agentId, decidedBy)
                return if (stale) fled.copy(outcome = STALE_FLIGHT, fleeStale = true) else fled
            } catch (busy: BodyControlFailure.Refused) {
                if (busy.code != ErrorCode.NOT_READY || tries == PREEMPT_ATTEMPTS - 1) throw busy
                // Refused behind a newer answer of the agent's own: it governs, and is never halted.
                if (newest.outdated(agentId, decidedBy)) return ActionExecution(STALE_FLIGHT)
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

/** An older flight that a newer decision of the same agent's was applied after. */
private val STALE_FLIGHT = IntentOutcome.Rejected("a newer danger answer governs the body", EventTag.RETREAT_STALE)

private fun rejectedAction(
    reason: String,
    tag: String,
): () -> ActionExecution =
    {
        ActionExecution(IntentOutcome.Rejected(reason, tag))
    }

/**
 * Whether the flight from [entity] of [sense] sprints, and why: only from a threat near enough, or
 * closing fast enough, to need it (`ThreatApproach`), or one the sense states no position for.
 */
private fun PlayerBodyContext.sprintFrom(
    sense: HeldSense,
    subject: java.util.UUID,
    entity: Int,
): SprintChoice {
    val seen =
        sense.raw.report.entities.getOrNull(entity)?.position
            ?: return SprintChoice(true, SprintReason.UNLOCATED)
    return approach.choice(subject, sense.raw.capturedTick, blocksApart(sense.raw.report.position, seen))
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

/**
 * The newest decision applied to each agent's body, dated by when its perception was asked - the
 * order the research record writes `perceived` in - and never by when its answer arrived, since
 * answers arrive in whatever order the minds return them. A flight still being carried out for an
 * older decision never halts or replaces the body's newer work (r34 beta: an older sighting's flight,
 * retried after notReady, halted the newer damage's flight and ran in its place), and neither does a
 * routine answer asked before a danger and applied while that danger's flight runs (the r39 offline
 * audit: dated by its arrival, it halted the newer flight). A request never told to [asked] is dated
 * when first seen. Asked and applied on the main thread, read from the I/O thread an action retries on.
 */
internal class NewestDecisions {
    private val dates =
        object : LinkedHashMap<String, Long>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
                size > MAX_DATED_REQUESTS
        }
    private var asked = 0L
    private val newest = HashMap<AgentId, String>()

    /** [requestId]'s perception is the newest asked of its agent's mind. */
    @Synchronized
    fun asked(requestId: String) {
        dateOf(requestId)
    }

    /** [requestId]'s decision is applied to [agentId]'s body, and governs it unless a newer one already does. */
    @Synchronized
    fun applied(
        agentId: AgentId,
        requestId: String,
    ) {
        // Dated before the comparison, so a request seen first here is never dated after the one it is weighed against.
        val date = dateOf(requestId)
        val governing = newest[agentId]
        if (governing == null || date >= dateOf(governing)) newest[agentId] = requestId
    }

    /** Whether a decision asked after [requestId]'s has been applied to [agentId]'s body. */
    @Synchronized
    fun outdated(
        agentId: AgentId,
        requestId: String,
    ): Boolean {
        val governing = newest[agentId] ?: return false
        return governing != requestId && dateOf(governing) > dateOf(requestId)
    }

    private fun dateOf(requestId: String): Long = dates.getOrPut(requestId) { ++asked }

    private companion object {
        /**
         * Requests dated at once: far more than one agent's perceptions can still be answered within
         * the bridge's deadline, so a request is never forgotten while its decision can still arrive.
         */
        const val MAX_DATED_REQUESTS = 256
    }
}
