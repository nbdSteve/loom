/*
 * Request-scoped preparation and execution of native world interactions.
 */
package dev.loom.body.player

import dev.loom.body.EventTag
import dev.loom.body.IntentOutcome
import dev.loom.body.SlotResolution
import dev.loom.body.WorldTargetResolution
import dev.loom.bodycontrol.BODY_CONTROL_PROTOCOL_VERSION
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.contract.Intent
import dev.loom.bodycontrol.BlockFace as ControlBlockFace
import dev.loom.bodycontrol.EquipmentDestination as ControlEquipmentDestination

private const val INTERACTION_DEADLINE_MS = 20_000

/** Resolves offered refs on the main thread, then returns one immutable socket operation. */
internal class PlayerInteractionPreparer(
    private val context: PlayerBodyContext,
    private val preemption: PlayerPreemption,
) {
    fun prepare(
        runtime: PlayerRuntime,
        intent: Intent.Dig,
        requestId: String?,
        sense: HeldSense?,
    ): () -> ActionExecution {
        val resolved =
            requestId?.let {
                context.refs.blocks.resolve(
                    runtime.identity.agentId,
                    it,
                    intent.block,
                    context.authority.currentTick(),
                )
            } ?: WorldTargetResolution.Stale
        return when (resolved) {
            WorldTargetResolution.Stale ->
                rejectedInteraction(
                    "the block was named by a request this agent no longer holds",
                    EventTag.DIG_SENSE_FORGOTTEN,
                )
            WorldTargetResolution.Unknown ->
                rejectedInteraction(
                    "that request never named such a block",
                    EventTag.DIG_BLOCK_UNNAMED,
                )
            is WorldTargetResolution.Bound ->
                if (trapsBody(context.authority.ground, sense, resolved.index, intent.block)) {
                    rejectedInteraction(
                        "digging the block underfoot would leave no way out",
                        EventTag.DIG_WOULD_TRAP,
                    )
                } else if (floodsBody(context.authority.ground, sense, resolved.index)) {
                    rejectedInteraction(
                        "digging the block would let water flood where the body stands",
                        EventTag.DIG_FLOODS_BODY,
                    )
                } else if (strandsDrop(context.authority.ground, sense, resolved.index)) {
                    rejectedInteraction(
                        "the block's drop would land where no body can step down to collect it",
                        EventTag.DIG_DROP_UNREACHABLE,
                    )
                } else {
                    val applied = AppliedDecision(runtime, sense, requestId)
                    withSenseOrReject(applied, EventTag.DIG_SENSE_FORGOTTEN, "block") {
                        BodyRequest.Dig(
                            protocol = BODY_CONTROL_PROTOCOL_VERSION,
                            requestId = context.infrastructure.requestIds.next("dig"),
                            agentId = runtime.identity.agentId.value,
                            senseId = requireNotNull(requestId),
                            block = resolved.index,
                            timeoutMs = INTERACTION_DEADLINE_MS,
                        )
                    }
                }
        }
    }

    fun prepare(
        runtime: PlayerRuntime,
        intent: Intent.Collect,
        requestId: String?,
        sense: HeldSense?,
    ): () -> ActionExecution {
        val resolved =
            requestId?.let {
                context.refs.droppedItems.resolve(
                    runtime.identity.agentId,
                    it,
                    intent.item,
                    context.authority.currentTick(),
                )
            } ?: WorldTargetResolution.Stale
        return when (resolved) {
            WorldTargetResolution.Stale ->
                rejectedInteraction(
                    "the dropped item was named by a request this agent no longer holds",
                    EventTag.COLLECT_SENSE_FORGOTTEN,
                )
            WorldTargetResolution.Unknown ->
                rejectedInteraction(
                    "that request never named such a dropped item",
                    EventTag.COLLECT_ITEM_UNNAMED,
                )
            is WorldTargetResolution.Bound ->
                withSenseOrReject(
                    AppliedDecision(runtime, sense, requestId),
                    EventTag.COLLECT_SENSE_FORGOTTEN,
                    "dropped item",
                ) {
                    BodyRequest.Collect(
                        protocol = BODY_CONTROL_PROTOCOL_VERSION,
                        requestId = context.infrastructure.requestIds.next("collect"),
                        agentId = runtime.identity.agentId.value,
                        senseId = requireNotNull(requestId),
                        item = resolved.index,
                        approach = intent.approach,
                        timeoutMs = INTERACTION_DEADLINE_MS,
                        sprint = false,
                    )
                }
        }
    }

    fun prepare(
        runtime: PlayerRuntime,
        intent: Intent.Place,
        requestId: String?,
        sense: HeldSense?,
    ): () -> ActionExecution {
        val block =
            requestId?.let {
                context.refs.blocks.resolve(
                    runtime.identity.agentId,
                    it,
                    intent.block,
                    context.authority.currentTick(),
                )
            } ?: WorldTargetResolution.Stale
        val slot =
            requestId?.let {
                context.refs.slots.resolve(
                    runtime.identity.agentId,
                    it,
                    intent.item,
                    context.authority.currentTick(),
                )
            } ?: SlotResolution.Stale
        return when {
            block == WorldTargetResolution.Stale || slot == SlotResolution.Stale ->
                rejectedInteraction(
                    "the sense that named the block or stack is no longer held",
                    EventTag.PLACE_SENSE_FORGOTTEN,
                )
            block == WorldTargetResolution.Unknown ->
                rejectedInteraction(
                    "that request never named such a block",
                    EventTag.PLACE_BLOCK_UNNAMED,
                )
            slot == SlotResolution.Unknown ->
                rejectedInteraction(
                    "that request never named such a stack",
                    EventTag.PLACE_SLOT_UNNAMED,
                )
            block is WorldTargetResolution.Bound && slot is SlotResolution.Bound ->
                withSenseOrReject(
                    AppliedDecision(runtime, sense, requestId),
                    EventTag.PLACE_SENSE_FORGOTTEN,
                    "block and stack",
                ) {
                    BodyRequest.Place(
                        protocol = BODY_CONTROL_PROTOCOL_VERSION,
                        requestId = context.infrastructure.requestIds.next("place"),
                        agentId = runtime.identity.agentId.value,
                        senseId = requireNotNull(requestId),
                        block = block.index,
                        slot = slot.slot,
                        face = ControlBlockFace.valueOf(intent.face.name),
                        timeoutMs = INTERACTION_DEADLINE_MS,
                    )
                }
            else -> error("all place ref resolutions are covered")
        }
    }

    fun prepare(
        runtime: PlayerRuntime,
        intent: Intent.Equip,
        requestId: String?,
        sense: HeldSense?,
    ): () -> ActionExecution {
        val resolved =
            requestId?.let {
                context.refs.slots.resolve(
                    runtime.identity.agentId,
                    it,
                    intent.item,
                    context.authority.currentTick(),
                )
            } ?: SlotResolution.Stale
        return when (resolved) {
            SlotResolution.Stale ->
                rejectedInteraction(
                    "the stack was named by a request this agent no longer holds",
                    EventTag.EQUIP_SENSE_FORGOTTEN,
                )
            SlotResolution.Unknown ->
                rejectedInteraction(
                    "that request never named such a stack",
                    EventTag.EQUIP_SLOT_UNNAMED,
                )
            is SlotResolution.Bound ->
                withSenseOrReject(AppliedDecision(runtime, sense, requestId), EventTag.EQUIP_SENSE_FORGOTTEN, "stack") {
                    BodyRequest.Equip(
                        protocol = BODY_CONTROL_PROTOCOL_VERSION,
                        requestId = context.infrastructure.requestIds.next("equip"),
                        agentId = runtime.identity.agentId.value,
                        senseId = requireNotNull(requestId),
                        slot = resolved.slot,
                        destination = ControlEquipmentDestination.valueOf(intent.destination.name),
                        timeoutMs = INTERACTION_DEADLINE_MS,
                    )
                }
        }
    }

    private fun withSenseOrReject(
        applied: AppliedDecision,
        forgottenTag: String,
        target: String,
        request: () -> BodyRequest,
    ): () -> ActionExecution {
        val (runtime, sense, requestId) = applied
        return if (sense == null || requestId == null) {
            rejectedInteraction(
                "the sense connection that named that $target is no longer open",
                forgottenTag,
            )
        } else {
            val prepared = request()
            val startedTick = context.authority.currentTick()
            val operation: () -> ActionExecution = { interact(runtime, sense, prepared, startedTick, requestId) }
            operation
        }
    }

    private fun interact(
        runtime: PlayerRuntime,
        sense: HeldSense,
        prepared: BodyRequest,
        startedTick: Long,
        decidedBy: String,
    ): ActionExecution {
        val (request, response) =
            preemption.send(runtime, decidedBy, prepared) {
                sense.raw.connection.request(it, context.infrastructure.settings.movementTimeout)
            }
        return when (request) {
            is BodyRequest.Dig -> digExecution(sense, request, response, startedTick)
            is BodyRequest.Collect -> collectExecution(sense, request, response, startedTick)
            is BodyRequest.Place -> placeExecution(sense, request, response, startedTick)
            is BodyRequest.Equip -> equipExecution(sense, request, response, startedTick)
            else -> throw BodyControlFailure.ProtocolViolation()
        }
    }

    private fun digExecution(
        sense: HeldSense,
        request: BodyRequest.Dig,
        response: BodyResponse,
        startedTick: Long,
    ): ActionExecution {
        val report =
            response as? BodyResponse.DigReport
                ?: throw BodyControlFailure.ProtocolViolation()
        return ActionExecution(
            outcome = digOutcome(report),
            interaction =
                ServerInteractionClaim.Dig(
                    request,
                    report,
                    requireNotNull(sense.raw.report.blocks.getOrNull(request.block)),
                    startedTick,
                ),
        )
    }

    private fun collectExecution(
        sense: HeldSense,
        request: BodyRequest.Collect,
        response: BodyResponse,
        startedTick: Long,
    ): ActionExecution {
        val report =
            response as? BodyResponse.CollectReport
                ?: throw BodyControlFailure.ProtocolViolation()
        return ActionExecution(
            outcome = collectOutcome(report),
            interaction =
                ServerInteractionClaim.Collect(
                    request,
                    report,
                    requireNotNull(sense.raw.report.items.getOrNull(request.item)),
                    sense.droppedItemSubjects[request.item],
                    startedTick,
                ),
        )
    }

    private fun placeExecution(
        sense: HeldSense,
        request: BodyRequest.Place,
        response: BodyResponse,
        startedTick: Long,
    ): ActionExecution {
        val report =
            response as? BodyResponse.PlaceReport
                ?: throw BodyControlFailure.ProtocolViolation()
        return ActionExecution(
            outcome = placeOutcome(report),
            interaction =
                ServerInteractionClaim.Place(
                    request,
                    report,
                    requireNotNull(sense.raw.report.blocks.getOrNull(request.block)),
                    requireNotNull(
                        sense.raw.report.inventory.firstOrNull {
                            it.slot == request.slot
                        },
                    ),
                    startedTick,
                ),
        )
    }

    private fun equipExecution(
        sense: HeldSense,
        request: BodyRequest.Equip,
        response: BodyResponse,
        startedTick: Long,
    ): ActionExecution {
        val report =
            response as? BodyResponse.EquipReport
                ?: throw BodyControlFailure.ProtocolViolation()
        return ActionExecution(
            outcome = equipOutcome(report),
            interaction =
                ServerInteractionClaim.Equip(
                    request,
                    report,
                    requireNotNull(
                        sense.raw.report.inventory.firstOrNull {
                            it.slot == request.slot
                        },
                    ),
                    startedTick,
                ),
        )
    }
}

private fun rejectedInteraction(
    reason: String,
    tag: String,
): () -> ActionExecution =
    {
        ActionExecution(IntentOutcome.Rejected(reason, tag))
    }

/** Whether the sensed block at [index] is the body's support with no exit once it is dug. */
private fun trapsBody(
    ground: BlockGround,
    sense: HeldSense?,
    index: Int,
    named: String,
): Boolean {
    val report = sense?.raw?.report
    val block = report?.blocks?.getOrNull(index)
    // A shelter's descent leaves no walk out by design: the pit it digs is the shelter, so it is
    // held to the descent's own safety rather than to a way out (`Cover.kt`).
    val descends = named == sense?.targets?.descend && report != null && descentSafe(ground, report.position)
    return report != null && block != null && !descends && !digLeavesExit(ground, report.position, block.position)
}

/** Whether digging the sensed block at [index] would let water flood a cell the body occupies. */
private fun floodsBody(
    ground: BlockGround,
    sense: HeldSense?,
    index: Int,
): Boolean {
    val report = sense?.raw?.report
    val block = report?.blocks?.getOrNull(index)
    return report != null && block != null && !digLeavesBodyDry(ground, report.position, block.position)
}

/** Whether the sensed block at [index] would drop where no body can step down to collect it. */
private fun strandsDrop(
    ground: BlockGround,
    sense: HeldSense?,
    index: Int,
): Boolean {
    val report = sense?.raw?.report
    val block = report?.blocks?.getOrNull(index)
    return report != null && block != null && !digLeavesDropReachable(ground, report.position, block.position)
}
