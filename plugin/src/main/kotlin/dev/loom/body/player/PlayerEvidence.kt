/*
 * Evidence ordering and outcome translation for player-body actions.
 */
package dev.loom.body.player

import dev.loom.body.AgentId
import dev.loom.body.AgentVitals
import dev.loom.body.BodyEvent
import dev.loom.body.EventTag
import dev.loom.body.IntentOutcome
import dev.loom.body.WitnessedPlayer
import dev.loom.body.describe
import dev.loom.body.tagOf
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.CollectOutcome
import dev.loom.bodycontrol.DigOutcome
import dev.loom.bodycontrol.EatOutcome
import dev.loom.bodycontrol.EquipOutcome
import dev.loom.bodycontrol.MoveOutcome
import dev.loom.bodycontrol.PlaceOutcome
import dev.loom.contract.Intent

private const val PLAYER_FOOD_CAPACITY = 20.0
private const val PLAYER_HEALTH_CAPACITY = 20.0

internal data class ActionExecution(
    val outcome: IntentOutcome,
    val moveRequest: BodyRequest.MoveTo? = null,
    val moveReport: BodyResponse.MoveReport? = null,
    val fleeRequest: BodyRequest.Flee? = null,
    val fleeReport: BodyResponse.FleeReport? = null,
    val eatReport: BodyResponse.EatReport? = null,
    val interaction: ServerInteractionClaim? = null,
)

/** Records decisions before the movement or vitals evidence they produced. */
internal class PlayerEvidenceRecorder(
    private val context: PlayerBodyContext,
) {
    fun auditAndRecord(
        runtime: PlayerRuntime,
        intent: Intent,
        requestId: String?,
        execution: ActionExecution,
    ) {
        val agentId = runtime.identity.agentId
        recordAction(agentId, intent, requestId, execution.outcome)
        recordWalk(runtime, execution)
        recordFlight(runtime, execution)
        recordMeal(agentId, execution)
        execution.interaction?.let { recordInteraction(runtime, intent, requestId, it) }
    }

    private fun recordWalk(
        runtime: PlayerRuntime,
        execution: ActionExecution,
    ) {
        val request = execution.moveRequest ?: return
        val report = execution.moveReport ?: return
        context.authority.serverState.auditMovement(runtime.identity, request, report)
        recordMovement(
            runtime.identity.agentId,
            report.outcome,
            report.from,
            report.position,
            report.travelled,
        )
    }

    private fun recordFlight(
        runtime: PlayerRuntime,
        execution: ActionExecution,
    ) {
        val request = execution.fleeRequest ?: return
        val report = execution.fleeReport ?: return
        val agentId = runtime.identity.agentId
        context.authority.serverState.auditMovement(runtime.identity, request, report)
        recordMovement(agentId, report.outcome, report.from, report.position, report.travelled)
        context.journal.recordRetreat(
            agentId,
            "flight ended ${report.outcome.name.lowercase()}, " +
                "separation ${report.separation ?: "unread"}",
            retreatTag(report.outcome),
        )
    }

    private fun recordMeal(
        agentId: AgentId,
        execution: ActionExecution,
    ) {
        val report = execution.eatReport ?: return
        context.vitalsWatch.recordChanges(
            agentId,
            AgentVitals(report.vitals.food.toDouble(), PLAYER_FOOD_CAPACITY),
            AgentVitals(
                report.vitals.health.coerceAtLeast(0.0),
                PLAYER_HEALTH_CAPACITY,
            ),
        )
    }

    private fun recordInteraction(
        runtime: PlayerRuntime,
        intent: Intent,
        requestId: String?,
        claim: ServerInteractionClaim,
    ) {
        val controlRequest = claim.controlRequest
        val audit = context.authority.serverState.auditInteraction(runtime.identity, claim)
        val senseId =
            when (controlRequest) {
                is BodyRequest.Dig -> controlRequest.senseId
                is BodyRequest.Collect -> controlRequest.senseId
                is BodyRequest.Place -> controlRequest.senseId
                is BodyRequest.Equip -> controlRequest.senseId
                else -> error("only world-interaction requests are recorded here")
            }
        context.journal.recordWorldInteraction(
            agentId = runtime.identity.agentId,
            witness =
                WitnessedPlayer(
                    uuid = runtime.identity.uuid.toString(),
                    username = runtime.identity.username,
                ),
            detail =
                "${describe(intent)}; sense $senseId; control ${controlRequest.requestId}; " +
                    "body ${claim.bodyOutcome.lowercase()}; vanilla ${audit.detail}",
            requestId = requestId,
            confirmed = audit.confirmed,
        )
    }

    fun recordAction(
        agentId: AgentId,
        intent: Intent,
        requestId: String?,
        outcome: IntentOutcome,
    ) {
        context.journal.recordAction(
            agentId = agentId,
            detail = "${describe(intent)} -> ${describe(outcome)}",
            requestId = requestId,
            intent = tagOf(intent),
            outcome = tagOf(outcome),
        )
    }

    private fun recordMovement(
        agentId: AgentId,
        outcome: MoveOutcome,
        from: List<Double>,
        position: List<Double>,
        travelled: Double,
    ) {
        if (travelled <= 0.0 && from == position) return
        context.journal.record(
            agentId,
            BodyEvent.MOVED,
            "from ${from.joinToString(",")} to ${position.joinToString(",")}; " +
                "travelled $travelled; ${outcome.name.lowercase()}",
        )
    }
}

internal fun eatOutcome(report: BodyResponse.EatReport): IntentOutcome =
    when (report.outcome) {
        EatOutcome.EATEN -> IntentOutcome.Accepted("ate ${requireNotNull(report.what)}")
        EatOutcome.SENSE_FORGOTTEN ->
            IntentOutcome.Rejected(
                "the body no longer remembers the sense that named that stack",
                EventTag.EAT_SLOT_FORGOTTEN,
            )
        EatOutcome.SLOT_UNNAMED ->
            IntentOutcome.Rejected("that sense named no such stack", EventTag.EAT_SLOT_UNNAMED)
        EatOutcome.SLOT_EMPTY ->
            IntentOutcome.Rejected("that slot is empty now", EventTag.EAT_SLOT_EMPTY)
        EatOutcome.SLOT_CHANGED ->
            IntentOutcome.Rejected("that slot now holds a different stack", EventTag.EAT_SLOT_CHANGED)
        EatOutcome.NOT_EDIBLE ->
            IntentOutcome.Rejected("that stack is not edible", EventTag.EAT_NOT_FOOD)
        EatOutcome.RESERVE_FULL ->
            IntentOutcome.Rejected(
                "the player's food bar is already full",
                EventTag.EAT_RESERVE_FULL,
            )
        EatOutcome.SLOT_INACCESSIBLE ->
            IntentOutcome.Rejected(
                "the game could not equip or consume that stack",
                EventTag.EAT_SLOT_INACCESSIBLE,
            )
    }

internal fun movementOutcome(
    outcome: MoveOutcome,
    movement: String,
): IntentOutcome =
    when (outcome) {
        MoveOutcome.ARRIVED -> IntentOutcome.Accepted("$movement arrived")
        MoveOutcome.CANCELLED -> IntentOutcome.Accepted("$movement was cancelled after moving")
        MoveOutcome.STALLED -> IntentOutcome.Accepted("$movement stalled after starting")
        MoveOutcome.OVER_TRAVELLED -> IntentOutcome.Accepted("$movement reached its travel bound")
        MoveOutcome.TIMED_OUT -> IntentOutcome.Accepted("$movement reached its time bound")
        MoveOutcome.OUT_OF_RANGE ->
            IntentOutcome.Rejected("$movement target is outside the body reach")
        MoveOutcome.NO_ROUTE ->
            IntentOutcome.Rejected("the ground offered no route for this $movement")
        MoveOutcome.SEARCH_OVER_BUDGET ->
            IntentOutcome.Rejected("the body search budget ended before a route was known")
        MoveOutcome.SENSE_FORGOTTEN ->
            IntentOutcome.Rejected(
                "the body no longer remembers the sense that named that threat",
                EventTag.RETREAT_THREAT_FORGOTTEN,
            )
        MoveOutcome.THREAT_UNNAMED ->
            IntentOutcome.Rejected(
                "that sense named no such threat",
                EventTag.RETREAT_THREAT_UNNAMED,
            )
        MoveOutcome.THREAT_GONE ->
            IntentOutcome.Rejected(
                "the named threat is gone",
                EventTag.RETREAT_THREAT_GONE,
            )
    }

internal fun digOutcome(report: BodyResponse.DigReport): IntentOutcome =
    when (report.outcome) {
        DigOutcome.DUG -> IntentOutcome.Accepted("dug the offered block")
        DigOutcome.SENSE_FORGOTTEN -> rejected("dig sense forgotten", EventTag.DIG_SENSE_FORGOTTEN)
        DigOutcome.BLOCK_UNNAMED -> rejected("dig block unnamed", EventTag.DIG_BLOCK_UNNAMED)
        DigOutcome.BLOCK_GONE -> rejected("dig block gone", EventTag.DIG_BLOCK_GONE)
        DigOutcome.BLOCK_CHANGED -> rejected("dig block changed", EventTag.DIG_BLOCK_CHANGED)
        DigOutcome.OUT_OF_REACH -> rejected("dig target out of reach", EventTag.DIG_OUT_OF_REACH)
        DigOutcome.NOT_VISIBLE -> rejected("dig target not visible", EventTag.DIG_NOT_VISIBLE)
        DigOutcome.NOT_DIGGABLE -> rejected("dig target not diggable", EventTag.DIG_NOT_DIGGABLE)
        DigOutcome.TIMED_OUT -> rejected("dig timed out", EventTag.DIG_TIMED_OUT)
        DigOutcome.CANCELLED -> rejected("dig cancelled", EventTag.DIG_CANCELLED)
        DigOutcome.LIBRARY_FAILURE -> rejected("dig library failure", EventTag.DIG_LIBRARY_FAILURE)
    }

internal fun collectOutcome(report: BodyResponse.CollectReport): IntentOutcome =
    when (report.outcome) {
        CollectOutcome.COLLECTED ->
            IntentOutcome.Accepted("collected ${requireNotNull(report.count)} ${requireNotNull(report.what)}")
        CollectOutcome.SENSE_FORGOTTEN ->
            rejected("collect sense forgotten", EventTag.COLLECT_SENSE_FORGOTTEN)
        CollectOutcome.ITEM_UNNAMED ->
            rejected("collect item unnamed", EventTag.COLLECT_ITEM_UNNAMED)
        CollectOutcome.ITEM_GONE -> rejected("collect item gone", EventTag.COLLECT_ITEM_GONE)
        CollectOutcome.ITEM_CHANGED -> rejected("collect item changed", EventTag.COLLECT_ITEM_CHANGED)
        CollectOutcome.OUT_OF_RANGE ->
            rejected("collect item out of range", EventTag.COLLECT_OUT_OF_RANGE)
        CollectOutcome.NO_ROUTE -> rejected("collect route unavailable", EventTag.COLLECT_NO_ROUTE)
        CollectOutcome.SEARCH_OVER_BUDGET ->
            rejected("collect search over budget", EventTag.COLLECT_SEARCH_OVER_BUDGET)
        CollectOutcome.STALLED -> rejected("collect approach stalled", EventTag.COLLECT_STALLED)
        CollectOutcome.OVER_TRAVELLED ->
            rejected("collect approach reached its travel bound", EventTag.COLLECT_OVER_TRAVELLED)
        CollectOutcome.TIMED_OUT -> rejected("collect timed out", EventTag.COLLECT_TIMED_OUT)
        CollectOutcome.CANCELLED -> rejected("collect cancelled", EventTag.COLLECT_CANCELLED)
        CollectOutcome.LIBRARY_FAILURE ->
            rejected("collect library failure", EventTag.COLLECT_LIBRARY_FAILURE)
    }

internal fun placeOutcome(report: BodyResponse.PlaceReport): IntentOutcome =
    if (report.outcome == PlaceOutcome.PLACED) {
        IntentOutcome.Accepted(
            "placed ${requireNotNull(report.what)} at ${requireNotNull(report.position).joinToString(",")}",
        )
    } else {
        requireNotNull(PLACE_REFUSALS[report.outcome])
    }

internal fun equipOutcome(report: BodyResponse.EquipReport): IntentOutcome =
    when (report.outcome) {
        EquipOutcome.EQUIPPED ->
            IntentOutcome.Accepted(
                "equipped ${requireNotNull(report.count)} ${requireNotNull(report.what)} " +
                    "in slot ${requireNotNull(report.toSlot)}",
            )
        EquipOutcome.SENSE_FORGOTTEN ->
            rejected("equip sense forgotten", EventTag.EQUIP_SENSE_FORGOTTEN)
        EquipOutcome.SLOT_UNNAMED -> rejected("equip slot unnamed", EventTag.EQUIP_SLOT_UNNAMED)
        EquipOutcome.SLOT_EMPTY -> rejected("equip slot empty", EventTag.EQUIP_SLOT_EMPTY)
        EquipOutcome.SLOT_CHANGED -> rejected("equip stack changed", EventTag.EQUIP_SLOT_CHANGED)
        EquipOutcome.INCOMPATIBLE ->
            rejected("equip stack is incompatible", EventTag.EQUIP_INCOMPATIBLE)
        EquipOutcome.TIMED_OUT -> rejected("equip timed out", EventTag.EQUIP_TIMED_OUT)
        EquipOutcome.CANCELLED -> rejected("equip cancelled", EventTag.EQUIP_CANCELLED)
        EquipOutcome.LIBRARY_FAILURE ->
            rejected("equip library failure", EventTag.EQUIP_LIBRARY_FAILURE)
    }

private fun rejected(
    reason: String,
    tag: String,
): IntentOutcome = IntentOutcome.Rejected(reason, tag)

private val PLACE_REFUSALS: Map<PlaceOutcome, IntentOutcome.Rejected> =
    mapOf(
        PlaceOutcome.SENSE_FORGOTTEN to
            IntentOutcome.Rejected("place sense forgotten", EventTag.PLACE_SENSE_FORGOTTEN),
        PlaceOutcome.BLOCK_UNNAMED to
            IntentOutcome.Rejected("place block unnamed", EventTag.PLACE_BLOCK_UNNAMED),
        PlaceOutcome.SLOT_UNNAMED to
            IntentOutcome.Rejected("place slot unnamed", EventTag.PLACE_SLOT_UNNAMED),
        PlaceOutcome.BLOCK_GONE to
            IntentOutcome.Rejected("place support gone", EventTag.PLACE_BLOCK_GONE),
        PlaceOutcome.BLOCK_CHANGED to
            IntentOutcome.Rejected("place support changed", EventTag.PLACE_BLOCK_CHANGED),
        PlaceOutcome.SLOT_EMPTY to
            IntentOutcome.Rejected("place slot empty", EventTag.PLACE_SLOT_EMPTY),
        PlaceOutcome.SLOT_CHANGED to
            IntentOutcome.Rejected("place stack changed", EventTag.PLACE_SLOT_CHANGED),
        PlaceOutcome.ITEM_NOT_HELD to
            IntentOutcome.Rejected("place stack is not already held", EventTag.PLACE_ITEM_NOT_HELD),
        PlaceOutcome.NOT_PLACEABLE to
            IntentOutcome.Rejected("place stack is not a block item", EventTag.PLACE_NOT_PLACEABLE),
        PlaceOutcome.DESTINATION_BLOCKED to
            IntentOutcome.Rejected(
                "place destination is blocked",
                EventTag.PLACE_DESTINATION_BLOCKED,
            ),
        PlaceOutcome.OUT_OF_REACH to
            IntentOutcome.Rejected("place support out of reach", EventTag.PLACE_OUT_OF_REACH),
        PlaceOutcome.NOT_VISIBLE to
            IntentOutcome.Rejected("place support not visible", EventTag.PLACE_NOT_VISIBLE),
        PlaceOutcome.TIMED_OUT to
            IntentOutcome.Rejected("place timed out", EventTag.PLACE_TIMED_OUT),
        PlaceOutcome.CANCELLED to
            IntentOutcome.Rejected("place cancelled", EventTag.PLACE_CANCELLED),
        PlaceOutcome.LIBRARY_FAILURE to
            IntentOutcome.Rejected("place library failure", EventTag.PLACE_LIBRARY_FAILURE),
    )

private fun retreatTag(outcome: MoveOutcome): String =
    when (outcome) {
        MoveOutcome.ARRIVED -> EventTag.RETREAT_SAFE
        MoveOutcome.CANCELLED -> EventTag.RETREAT_SUPERSEDED
        MoveOutcome.SENSE_FORGOTTEN -> EventTag.RETREAT_THREAT_FORGOTTEN
        MoveOutcome.THREAT_UNNAMED -> EventTag.RETREAT_THREAT_UNNAMED
        MoveOutcome.THREAT_GONE -> EventTag.RETREAT_THREAT_GONE
        else -> EventTag.RETREAT_FAILED
    }
