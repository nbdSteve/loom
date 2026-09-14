/*
 * Evidence ordering and outcome translation for player-body actions.
 */
package dev.loom.body.player

import dev.loom.body.AgentId
import dev.loom.body.AgentVitals
import dev.loom.body.BodyEvent
import dev.loom.body.EventTag
import dev.loom.body.IntentOutcome
import dev.loom.body.describe
import dev.loom.body.tagOf
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.EatOutcome
import dev.loom.bodycontrol.MoveOutcome
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
        execution.moveRequest?.let { request ->
            execution.moveReport?.let { report ->
                context.authority.serverState.auditMovement(runtime.identity, request, report)
                recordMovement(agentId, report.outcome, report.from, report.position, report.travelled)
            }
        }
        execution.fleeRequest?.let { request ->
            execution.fleeReport?.let { report ->
                context.authority.serverState.auditMovement(runtime.identity, request, report)
                recordMovement(agentId, report.outcome, report.from, report.position, report.travelled)
                context.journal.recordRetreat(
                    agentId,
                    "flight ended ${report.outcome.name.lowercase()}, " +
                        "separation ${report.separation ?: "unread"}",
                    retreatTag(report.outcome),
                )
            }
        }
        execution.eatReport?.let { report ->
            context.vitalsWatch.recordChanges(
                agentId,
                AgentVitals(report.vitals.food.toDouble(), PLAYER_FOOD_CAPACITY),
                AgentVitals(
                    report.vitals.health.coerceAtLeast(0.0),
                    PLAYER_HEALTH_CAPACITY,
                ),
            )
        }
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

private fun retreatTag(outcome: MoveOutcome): String =
    when (outcome) {
        MoveOutcome.ARRIVED -> EventTag.RETREAT_SAFE
        MoveOutcome.CANCELLED -> EventTag.RETREAT_SUPERSEDED
        MoveOutcome.SENSE_FORGOTTEN -> EventTag.RETREAT_THREAT_FORGOTTEN
        MoveOutcome.THREAT_UNNAMED -> EventTag.RETREAT_THREAT_UNNAMED
        MoveOutcome.THREAT_GONE -> EventTag.RETREAT_THREAT_GONE
        else -> EventTag.RETREAT_FAILED
    }
