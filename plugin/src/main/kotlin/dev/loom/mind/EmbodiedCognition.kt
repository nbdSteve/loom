/*
 * The round trip: a body's perception becomes a decision, and the decision
 * becomes an action on that body.
 *
 * This is the only place the two ports meet, and the whole reason it is its own
 * class is the threading rule it encodes. Perception is captured on the main
 * thread (it reads world state), cognition runs off it, and the decided intent is
 * applied back on it. Nothing else in Loom needs to know that dance.
 *
 * It is also where a decision enters the run's durable record, because this is the
 * one place that holds the decision, the provenance the mind stamped on it, and what
 * the body did about it at the same time.
 *
 * It is engine-free - it names `BodyPort`, `CognitionBridge`, and `MainThread`,
 * never a server type - so the whole round trip is testable with no server at
 * all.
 */
package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.BodyPort
import dev.loom.body.IntentOutcome
import dev.loom.contract.Decision
import dev.loom.contract.Perception
import dev.loom.contract.Trigger

/**
 * The end of one decision's life: what the mind decided, and what the body did
 * about it.
 *
 * Both halves are kept because they answer different questions - the decision
 * carries the reasoning and provenance, the outcome says whether the body could
 * act on it - and a research record needs the pair. This is the
 * perception -> reasoning -> action -> result chain in one value.
 */
data class AppliedDecision(
    val decision: Decision,
    val outcome: IntentOutcome,
)

/**
 * Drives one agent's perception through the mind and back onto its body.
 *
 * @param bodies the body port. Every call to it happens on the main thread.
 * @param mind the cognition bridge. Called from the main thread but never waited
 *   on.
 * @param mainThread how a decision gets back to the main thread to be applied.
 * @param journal where every decision is durably recorded. Not optional and not a
 *   callback: a decision that reached a body without reaching the record would
 *   leave a run unable to say why an agent acted.
 * @param onApplied called on the main thread once a decision has been applied.
 * @param onUnavailable called when the mind did not answer, so a lost decision
 *   is visible rather than silent. Called on the main thread, like [onApplied],
 *   so a handler may safely touch world state.
 */
class EmbodiedCognition(
    private val bodies: BodyPort,
    private val mind: CognitionBridge,
    private val mainThread: MainThread,
    private val journal: DecisionJournal,
    private val onApplied: (AppliedDecision) -> Unit = {},
    private val onUnavailable: (Perception, Throwable) -> Unit = { _, _ -> },
) {
    /**
     * Captures [agentId]'s perception of [trigger] and sets cognition going.
     *
     * Must be called on the main thread, and returns on it: this schedules the
     * round trip and does not wait for any part of it. Returns the perception
     * that was sent, or `null` if the agent has no body to perceive with -
     * cognition for a bodyless agent is not attempted, since there would be
     * nothing to apply the decision to.
     *
     * The perception is classified here rather than by the body: a body port
     * reports what happened, and how much that matters - which decides what the
     * decision costs - is this side's call. See [importanceOf].
     */
    fun consider(
        agentId: AgentId,
        trigger: Trigger,
    ): Perception? {
        val perception = bodies.perceive(agentId, trigger)?.classified() ?: return null
        submit(agentId, perception)
        return perception
    }

    /**
     * Sends [perception] to the mind and arranges for the decision to be applied
     * to [agentId]'s body on the main thread.
     *
     * Separate from [consider] so a caller that already holds a perception - a
     * driver command, a test - uses the same one path to the mind.
     */
    fun submit(
        agentId: AgentId,
        perception: Perception,
    ) {
        mind.submit(perception).whenComplete { decision, failure ->
            // This runs on a bridge thread. Everything past here touches the body,
            // so it goes through the main thread or not at all.
            mainThread.execute {
                if (decision == null) {
                    onUnavailable(perception, failure ?: missingBoth(perception))
                } else {
                    onApplied(actOn(agentId, decision))
                }
            }
        }
    }

    /**
     * Records [decision], asks [agentId]'s body to carry it out, and records the
     * decision the body could no longer be asked for.
     *
     * The order is the record's: the decision is journaled before it is applied, so a
     * run reads perception -> decision -> what the body did about it, in that order,
     * however long the mind took. The request id goes to the body with the intent so
     * the action's own record joins back to this decision.
     */
    private fun actOn(
        agentId: AgentId,
        decision: Decision,
    ): AppliedDecision {
        journal.decided(agentId, decision)
        val outcome = bodies.apply(agentId, decision.intent, decision.requestId)
        // Cognition is asynchronous, so an agent can die or cross a portal between
        // perceiving and acting - which is the expected path for the events Loom exists
        // to study, not an error. The body records nothing for a decision it never
        // received, so the loss is recorded here or nowhere.
        if (outcome == IntentOutcome.NoSuchBody) {
            journal.discarded(agentId, decision)
        }
        return AppliedDecision(decision, outcome)
    }

    /**
     * The failure to report when a future completed with neither a decision nor a
     * cause.
     *
     * `CompletableFuture` allows a `null` success value, so this is reachable in
     * principle - a transport that returned nothing. Reporting it as its own
     * failure keeps that from silently looking like a successful no-op decision.
     */
    private fun missingBoth(perception: Perception): Throwable =
        CognitionUnavailableException(
            CognitionFailure.Failed("the mind completed without a decision"),
            "cognition for agent ${perception.agentId} completed with neither a decision nor a failure",
        )
}
