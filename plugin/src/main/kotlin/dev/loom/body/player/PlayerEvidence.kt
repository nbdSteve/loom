/*
 * Evidence ordering and outcome translation for player-body actions.
 */
package dev.loom.body.player

import dev.loom.body.AgentId
import dev.loom.body.AgentVitals
import dev.loom.body.EventTag
import dev.loom.body.IntentOutcome
import dev.loom.body.RetreatEvidence
import dev.loom.body.WitnessedPlayer
import dev.loom.body.describe
import dev.loom.body.tagOf
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.CollectOutcome
import dev.loom.bodycontrol.DigOutcome
import dev.loom.bodycontrol.EatOutcome
import dev.loom.bodycontrol.EquipOutcome
import dev.loom.bodycontrol.PlaceOutcome
import dev.loom.contract.DropFate
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
    /**
     * Audits [execution] against the server, records it, and answers its outcome as the server
     * attributes it: a claimed dig the server credits to another managed player is that player's
     * break, so this one's is the lost race, never a dug.
     */
    fun auditAndRecord(
        runtime: PlayerRuntime,
        intent: Intent,
        requestId: String?,
        execution: ActionExecution,
    ): IntentOutcome {
        val agentId = runtime.identity.agentId
        val audited =
            execution.interaction?.let { it to context.authority.serverState.auditInteraction(runtime.identity, it) }
        val brokenByOther = audited?.second?.brokenByOther == true
        // Only this body's own break dropped anything for it.
        val drops = if (brokenByOther) emptyList() else attributedDrops(runtime, requestId, execution.interaction)
        val ended = execution.outcome
        val outcome =
            when {
                brokenByOther ->
                    IntentOutcome.Rejected(
                        "the server credits the dug block to another player",
                        EventTag.DIG_CONTEST_GONE,
                    )
                ended is IntentOutcome.Accepted ->
                    ended.copy(dropped = drops.filter { it.fate == DropFate.LYING }.map { it.what }.toSet())
                ended is IntentOutcome.Rejected && audited?.second?.effected == true -> ended.copy(effected = true)
                else -> ended
            }
        (execution.interaction as? ServerInteractionClaim.Collect)
            ?.takeIf { it.report.outcome == CollectOutcome.NO_ROUTE }
            ?.itemSubject
            ?.let(context.routeRefusals::refused)
        recordAction(agentId, intent, requestId, outcome)
        recordWalk(runtime, requestId, execution)
        recordFlight(runtime, requestId, execution)
        recordMeal(agentId, execution)
        audited?.let { recordInteraction(runtime, intent, requestId, it, drops) }
        return outcome
    }

    private fun recordWalk(
        runtime: PlayerRuntime,
        requestId: String?,
        execution: ActionExecution,
    ) {
        val request = execution.moveRequest ?: return
        val report = execution.moveReport ?: return
        context.authority.serverState.auditMovement(runtime.identity, request, report)
        recordMovement(
            runtime.identity.agentId,
            requestId,
            MovementEnding(report.outcome, report.from, report.position, report.travelled),
        )
    }

    private fun recordFlight(
        runtime: PlayerRuntime,
        requestId: String?,
        execution: ActionExecution,
    ) {
        val request = execution.fleeRequest ?: return
        val report = execution.fleeReport ?: return
        val agentId = runtime.identity.agentId
        context.authority.serverState.auditMovement(runtime.identity, request, report)
        recordMovement(
            agentId,
            requestId,
            MovementEnding(report.outcome, report.from, report.position, report.travelled),
        )
        context.journal.recordRetreat(
            agentId,
            "flight ended ${report.outcome.name.lowercase()}, " +
                "separation ${report.separation ?: "unread"}",
            retreatTag(report.outcome, report.separation, request.distance),
            requestId,
            RetreatEvidence(report.separation, report.separationGained),
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
        audited: Pair<ServerInteractionClaim, ServerInteractionAudit>,
        drops: List<OwnDropReading>,
    ) {
        val (claim, audit) = audited
        val controlRequest = claim.controlRequest
        val dropped =
            when {
                audit.brokenByOther -> "; dropped nothing of its own"
                !dug(claim) || requestId == null -> ""
                drops.isEmpty() -> "; dropped nothing"
                else -> "; dropped " + drops.joinToString(", ") { "${it.count} ${it.what}" }
            }
        val senseId =
            when (controlRequest) {
                is BodyRequest.Dig -> controlRequest.senseId
                is BodyRequest.Collect -> controlRequest.senseId
                is BodyRequest.Place -> controlRequest.senseId
                is BodyRequest.Equip -> controlRequest.senseId
                is BodyRequest.Craft -> controlRequest.senseId
                is BodyRequest.Attack -> controlRequest.senseId
                is BodyRequest.Sleep -> controlRequest.senseId
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
                    "body ${claim.bodyOutcome.lowercase()}${journeyOf(claim)}; vanilla ${audit.detail}$dropped",
            requestId = requestId,
            confirmed = audit.confirmed,
        )
    }

    /**
     * Attributes what the server dropped for a completed dig to the dig's own request: what the dig
     * yielded is that request's evidence, wherever the drop goes after.
     */
    private fun attributedDrops(
        runtime: PlayerRuntime,
        requestId: String?,
        claim: ServerInteractionClaim?,
    ): List<OwnDropReading> {
        if (requestId == null || claim !is ServerInteractionClaim.Dig || !dug(claim)) return emptyList()
        return context.authority.ownDrops.attribute(
            runtime.identity,
            requestId,
            claim.offered.position,
            claim.startedTick,
        )
    }

    private fun dug(claim: ServerInteractionClaim): Boolean =
        claim is ServerInteractionClaim.Dig && claim.report.outcome == DigOutcome.DUG

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
        requestId: String?,
        ending: MovementEnding,
    ) {
        if (ending.travelled <= 0.0 && ending.from == ending.position) return
        context.journal.recordMovement(
            agentId,
            "from ${ending.from.joinToString(",")} to ${ending.position.joinToString(",")}; " +
                "travelled ${ending.travelled}; ${ending.outcome.name.lowercase()}",
            requestId,
            movementTag(ending.outcome),
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
        CollectOutcome.NO_ROUTE ->
            rejected(
                "no standable cell within pickup reach of the item has a route",
                EventTag.COLLECT_NO_ROUTE,
            )
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

/**
 * Where an approaching interaction's body went, against where its offered target was, so an
 * ending such as a collect that arrived and timed out can be attributed from the record alone.
 */
private fun journeyOf(claim: ServerInteractionClaim): String =
    when (claim) {
        is ServerInteractionClaim.Collect ->
            journey(claim.report.from, claim.report.position, claim.report.travelled, claim.offered.position)
        is ServerInteractionClaim.Attack ->
            journey(claim.report.from, claim.report.position, claim.report.travelled, claim.offeredTarget.position)
        else -> ""
    }

private fun journey(
    from: List<Double>,
    position: List<Double>,
    travelled: Double,
    target: List<Double>,
): String =
    "; from ${from.joinToString(",")} to ${position.joinToString(",")} travelled $travelled; " +
        "target at ${target.joinToString(",")}"
