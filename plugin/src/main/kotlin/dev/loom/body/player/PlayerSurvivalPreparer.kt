/*
 * Request-scoped preparation and execution of crafting, combat, and sleeping.
 */
package dev.loom.body.player

import dev.loom.body.EventTag
import dev.loom.body.IntentOutcome
import dev.loom.body.RefResolution
import dev.loom.body.SlotResolution
import dev.loom.body.WorldTargetResolution
import dev.loom.bodycontrol.BODY_CONTROL_PROTOCOL_VERSION
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.contract.Intent

private const val CRAFT_DEADLINE_MS = 10_000
private const val COMBAT_DEADLINE_MS = 8_000
private const val SLEEP_DEADLINE_MS = 20_000
private const val COMBAT_MAX_PURSUIT_DISTANCE = 7.5
private const val COMBAT_MIN_HEALTH = 4.5

/** Resolves survival refs on the main thread, then returns one immutable socket operation. */
internal class PlayerSurvivalPreparer(
    private val context: PlayerBodyContext,
    private val preemption: PlayerPreemption,
) {
    fun prepare(
        runtime: PlayerRuntime,
        intent: Intent.Craft,
        requestId: String?,
        sense: HeldSense?,
    ): () -> ActionExecution {
        val resolved =
            requestId?.let {
                context.refs.recipes.resolve(
                    runtime.identity.agentId,
                    it,
                    intent.recipe,
                    context.authority.currentTick(),
                )
            } ?: WorldTargetResolution.Stale
        return when (resolved) {
            WorldTargetResolution.Stale ->
                rejectedSurvival(
                    "the recipe was named by a request this agent no longer holds",
                    EventTag.CRAFT_SENSE_FORGOTTEN,
                )
            WorldTargetResolution.Unknown ->
                rejectedSurvival(
                    "that request never named such a recipe",
                    EventTag.CRAFT_RECIPE_UNNAMED,
                )
            is WorldTargetResolution.Bound ->
                prepareBoundCraft(runtime, intent, requestId, sense, resolved.index)
        }
    }

    fun prepare(
        runtime: PlayerRuntime,
        intent: Intent.Attack,
        requestId: String?,
        sense: HeldSense?,
    ): () -> ActionExecution {
        val target =
            requestId?.let {
                context.refs.creatures.resolve(
                    runtime.identity.agentId,
                    it,
                    intent.target,
                    context.authority.currentTick(),
                )
            } ?: RefResolution.Stale
        val weapon =
            requestId?.let {
                context.refs.slots.resolve(
                    runtime.identity.agentId,
                    it,
                    intent.weapon,
                    context.authority.currentTick(),
                )
            } ?: SlotResolution.Stale
        return when {
            target == RefResolution.Stale || weapon == SlotResolution.Stale ->
                rejectedSurvival(
                    "the sense that named the target or weapon is no longer held",
                    EventTag.ATTACK_SENSE_FORGOTTEN,
                )
            target == RefResolution.Unknown ->
                rejectedSurvival(
                    "that request never named such a target",
                    EventTag.ATTACK_TARGET_UNNAMED,
                )
            weapon == SlotResolution.Unknown ->
                rejectedSurvival(
                    "that request never named such a weapon stack",
                    EventTag.ATTACK_SLOT_UNNAMED,
                )
            target is RefResolution.Bound && weapon is SlotResolution.Bound ->
                prepareBoundAttack(
                    runtime,
                    intent,
                    ResolvedAttack(requestId, sense, target, weapon),
                )
            else -> error("all attack ref resolutions are covered")
        }
    }

    fun prepare(
        runtime: PlayerRuntime,
        intent: Intent.Sleep,
        requestId: String?,
        sense: HeldSense?,
    ): () -> ActionExecution {
        val resolved =
            requestId?.let {
                context.refs.blocks.resolve(
                    runtime.identity.agentId,
                    it,
                    intent.bed,
                    context.authority.currentTick(),
                )
            } ?: WorldTargetResolution.Stale
        return when (resolved) {
            WorldTargetResolution.Stale ->
                rejectedSurvival(
                    "the bed was named by a request this agent no longer holds",
                    EventTag.SLEEP_SENSE_FORGOTTEN,
                )
            WorldTargetResolution.Unknown ->
                rejectedSurvival(
                    "that request never named such a bed",
                    EventTag.SLEEP_BED_UNNAMED,
                )
            is WorldTargetResolution.Bound ->
                withSenseOrReject(AppliedDecision(runtime, sense, requestId), EventTag.SLEEP_SENSE_FORGOTTEN, "bed") {
                    BodyRequest.Sleep(
                        protocol = BODY_CONTROL_PROTOCOL_VERSION,
                        requestId = context.infrastructure.requestIds.next("sleep"),
                        agentId = runtime.identity.agentId.value,
                        senseId = requireNotNull(requestId),
                        block = resolved.index,
                        timeoutMs = SLEEP_DEADLINE_MS,
                    )
                }
        }
    }

    private fun prepareBoundCraft(
        runtime: PlayerRuntime,
        intent: Intent.Craft,
        requestId: String?,
        sense: HeldSense?,
        recipe: Int,
    ): () -> ActionExecution {
        val offered = sense?.raw?.report?.recipes?.getOrNull(recipe)
        return when {
            offered == null || requestId == null ->
                rejectedSurvival(
                    "the sense connection that named that recipe is no longer open",
                    EventTag.CRAFT_SENSE_FORGOTTEN,
                )
            intent.crafts > offered.maxCrafts ->
                rejectedSurvival(
                    "the requested craft count exceeds the offered ingredient bound",
                    EventTag.CRAFT_QUANTITY_EXCEEDED,
                )
            else ->
                withSenseOrReject(
                    AppliedDecision(runtime, sense, requestId),
                    EventTag.CRAFT_SENSE_FORGOTTEN,
                    "recipe",
                ) {
                    BodyRequest.Craft(
                        protocol = BODY_CONTROL_PROTOCOL_VERSION,
                        requestId = context.infrastructure.requestIds.next("craft"),
                        agentId = runtime.identity.agentId.value,
                        senseId = requestId,
                        recipe = recipe,
                        crafts = intent.crafts.toInt(),
                        timeoutMs = CRAFT_DEADLINE_MS,
                    )
                }
        }
    }

    private fun prepareBoundAttack(
        runtime: PlayerRuntime,
        intent: Intent.Attack,
        resolved: ResolvedAttack,
    ): () -> ActionExecution {
        val sense = resolved.sense
        val entity = sense?.entityIndex?.get(resolved.target.subject)
        val targetSubject = entity?.let { sense.entitySubjects[it] }
        return when {
            entity == null || resolved.requestId == null ->
                rejectedSurvival(
                    "the sense connection that named that target is no longer open",
                    EventTag.ATTACK_SENSE_FORGOTTEN,
                )
            targetSubject == null ->
                rejectedSurvival(
                    "the server did not corroborate that attack target",
                    EventTag.ATTACK_TARGET_UNCORROBORATED,
                )
            else ->
                withSenseOrReject(
                    AppliedDecision(runtime, sense, resolved.requestId),
                    EventTag.ATTACK_SENSE_FORGOTTEN,
                    "target and weapon",
                ) {
                    BodyRequest.Attack(
                        protocol = BODY_CONTROL_PROTOCOL_VERSION,
                        requestId = context.infrastructure.requestIds.next("attack"),
                        agentId = runtime.identity.agentId.value,
                        senseId = resolved.requestId,
                        entity = entity,
                        slot = resolved.weapon.slot,
                        attacks = intent.attacks.toInt(),
                        approach = intent.approach,
                        maxPursuitDistance = COMBAT_MAX_PURSUIT_DISTANCE,
                        minHealth = COMBAT_MIN_HEALTH,
                        timeoutMs = COMBAT_DEADLINE_MS,
                        sprint = false,
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
            rejectedSurvival(
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
            is BodyRequest.Craft -> craftExecution(sense, request, response, startedTick)
            is BodyRequest.Attack -> attackExecution(sense, request, response, startedTick)
            is BodyRequest.Sleep -> sleepExecution(sense, request, response, startedTick)
            else -> throw BodyControlFailure.ProtocolViolation()
        }
    }

    private fun craftExecution(
        sense: HeldSense,
        request: BodyRequest.Craft,
        response: BodyResponse,
        startedTick: Long,
    ): ActionExecution {
        val report =
            response as? BodyResponse.CraftReport
                ?: throw BodyControlFailure.ProtocolViolation()
        return ActionExecution(
            outcome = craftOutcome(report),
            interaction =
                ServerInteractionClaim.Craft(
                    request,
                    report,
                    requireNotNull(sense.raw.report.recipes.getOrNull(request.recipe)),
                    startedTick,
                ),
        )
    }

    private fun attackExecution(
        sense: HeldSense,
        request: BodyRequest.Attack,
        response: BodyResponse,
        startedTick: Long,
    ): ActionExecution {
        val report =
            response as? BodyResponse.AttackReport
                ?: throw BodyControlFailure.ProtocolViolation()
        return ActionExecution(
            outcome = attackOutcome(report),
            interaction =
                ServerInteractionClaim.Attack(
                    request,
                    report,
                    requireNotNull(sense.raw.report.entities.getOrNull(request.entity)),
                    requireNotNull(sense.entitySubjects[request.entity]),
                    requireNotNull(
                        sense.raw.report.inventory.firstOrNull {
                            it.slot == request.slot
                        },
                    ),
                    startedTick,
                ),
        )
    }

    private fun sleepExecution(
        sense: HeldSense,
        request: BodyRequest.Sleep,
        response: BodyResponse,
        startedTick: Long,
    ): ActionExecution {
        val report =
            response as? BodyResponse.SleepReport
                ?: throw BodyControlFailure.ProtocolViolation()
        return ActionExecution(
            outcome = sleepOutcome(report),
            interaction =
                ServerInteractionClaim.Sleep(
                    request,
                    report,
                    requireNotNull(sense.raw.report.blocks.getOrNull(request.block)),
                    startedTick,
                ),
        )
    }
}

private data class ResolvedAttack(
    val requestId: String?,
    val sense: HeldSense?,
    val target: RefResolution.Bound,
    val weapon: SlotResolution.Bound,
)

private fun rejectedSurvival(
    reason: String,
    tag: String,
): () -> ActionExecution =
    {
        ActionExecution(IntentOutcome.Rejected(reason, tag))
    }
