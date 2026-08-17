/*
 * The decision half of the round trip, written to the append-only event log.
 *
 * The twin of the body adapter's own journal, on this side of the seam because a
 * decision's provenance is only known here: the body is handed an intent and has no
 * standing to say which model named it. It is engine-free like the rest of the port,
 * so what a run records is testable with no server at all.
 */
package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.describe
import dev.loom.contract.Decision

/**
 * Records decisions against [eventLog], stamped with the current server tick.
 *
 * @param eventLog append-only sink the run's records are written to - the same log
 *   the body writes to, so a decision and the action it caused sit on one chain.
 * @param currentTick supplies the server tick for stamping records. A decision is
 *   stamped with the tick it landed on, not the tick its perception was captured
 *   on: how long the mind took is exactly the gap between the two.
 */
class DecisionJournal(
    private val eventLog: BodyEventLog,
    private val currentTick: () -> Long,
) {
    /** Records that the mind decided, and what it decided with. */
    fun decided(
        agentId: AgentId,
        decision: Decision,
    ) {
        append(agentId, decision, BodyEvent.DECIDED, "${describe(decision.intent)}: ${decision.reasoning}")
    }

    /**
     * Records a decision the body could no longer be asked to carry out.
     *
     * The agent died or travelled between perceiving and acting. The decision was
     * still made and billed, so it is recorded rather than dropped - a perception with
     * no visible consequence is indistinguishable from a mind that never answered.
     */
    fun discarded(
        agentId: AgentId,
        decision: Decision,
    ) {
        append(
            agentId,
            decision,
            BodyEvent.DECISION_DISCARDED,
            "${describe(decision.intent)} was not applied: no body for this agent, which has died or travelled",
        )
    }

    private fun append(
        agentId: AgentId,
        decision: Decision,
        event: BodyEvent,
        detail: String,
    ) {
        eventLog.append(
            BodyEventRecord(
                tick = currentTick(),
                // Loom's own id for the agent it asked about, never the mind's echo of
                // it, so no answer can file a record against another agent.
                agentId = agentId.value,
                event = event,
                detail = detail,
                requestId = decision.requestId,
                provenance = decision.provenance,
            ),
        )
    }
}
