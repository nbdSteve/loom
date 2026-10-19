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
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.body.ShownGoal
import dev.loom.body.describe
import dev.loom.body.tagOf
import dev.loom.contract.Decision
import dev.loom.contract.Perception

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
    /** The authoritative server tick the journal stamps its records with. */
    fun tick(): Long = currentTick()

    /** Records that the mind decided, what it decided with, and what became of its goal choice. */
    fun decided(
        agentId: AgentId,
        decision: Decision,
        goal: GoalEvidence? = null,
        shown: ShownGoal? = null,
    ) {
        eventLog.append(
            recordOf(agentId, decision, BodyEvent.DECIDED, "${describe(decision.intent)}: ${decision.reasoning}")
                .copy(goal = goal, shown = shown),
        )
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

    /**
     * Records a danger decision discarded because newer damage preempted it while it was with
     * the mind: decided and billed, but the newer answer governs the body, so this one is never
     * applied. Its outcome is [EventTag.DANGER_PREEMPTED], which tells it apart from a discard
     * for a body that has gone.
     */
    fun preempted(
        agentId: AgentId,
        decision: Decision,
    ) {
        eventLog.append(
            recordOf(
                agentId,
                decision,
                BodyEvent.DECISION_DISCARDED,
                "${describe(decision.intent)} was not applied: newer damage preempted it",
            ).copy(outcome = EventTag.DANGER_PREEMPTED),
        )
    }

    /**
     * Records a perception the mind never answered, and what stopped it.
     *
     * The cycle happened - the agent perceived the world and then did nothing - so it
     * belongs on the agent's chain like any other. Without this record a run's log shows
     * only the cycles that worked, which overstates how much of an agent's behavior its
     * mind actually accounts for, and leaves a stalled mind looking like a quiet agent.
     *
     * [reason] is the failure's own text and stays in the record's detail, where it is
     * useful to whoever is reading the log. The countable classification goes in the
     * outcome, and the two are deliberately not the same field: a transport message is
     * unbounded and is not something any display should be putting in front of a person.
     */
    fun unavailable(
        agentId: AgentId,
        perception: Perception,
        outcome: String,
        reason: String,
    ) {
        eventLog.append(
            BodyEventRecord(
                tick = currentTick(),
                agentId = agentId.value,
                event = BodyEvent.COGNITION_UNAVAILABLE,
                detail = reason,
                requestId = perception.requestId,
                trigger = tagOf(perception.trigger),
                outcome = outcome,
                perception = perception,
            ),
        )
    }

    /** Records that [agentId]'s due scheduled review waits, for the countable [reason]. */
    fun reviewDeferred(
        agentId: AgentId,
        reason: String,
    ) {
        eventLog.append(
            BodyEventRecord(
                tick = currentTick(),
                agentId = agentId.value,
                event = BodyEvent.GOAL_REVIEW_DEFERRED,
                detail = "a scheduled goal review is due and waits: $reason",
                trigger = tagOf(dev.loom.contract.Trigger.GoalReview),
                outcome = reason,
            ),
        )
    }

    /**
     * Records a decision refused before the body was asked, because it contradicts its own goal.
     *
     * The intentApplied record a body would have written, under the refusal's own tag, so the
     * invariant of one intentApplied or one decisionDiscarded per decided still holds.
     */
    fun refused(
        agentId: AgentId,
        decision: Decision,
        tag: String,
    ) {
        eventLog.append(
            BodyEventRecord(
                tick = currentTick(),
                agentId = agentId.value,
                event = BodyEvent.INTENT_APPLIED,
                detail = "${describe(decision.intent)} -> refused before the body: $tag",
                requestId = decision.requestId,
                intent = tagOf(decision.intent),
                outcome = tag,
            ),
        )
    }

    private fun append(
        agentId: AgentId,
        decision: Decision,
        event: BodyEvent,
        detail: String,
    ) {
        eventLog.append(recordOf(agentId, decision, event, detail))
    }

    private fun recordOf(
        agentId: AgentId,
        decision: Decision,
        event: BodyEvent,
        detail: String,
    ): BodyEventRecord =
        BodyEventRecord(
            tick = currentTick(),
            // Loom's own id for the agent it asked about, never the mind's echo of
            // it, so no answer can file a record against another agent.
            agentId = agentId.value,
            event = event,
            detail = detail,
            requestId = decision.requestId,
            provenance = decision.provenance,
            intent = tagOf(decision.intent),
            decision = decision,
        )
}
