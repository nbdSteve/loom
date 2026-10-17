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
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.body.IntentOutcome
import dev.loom.body.ShownGoal
import dev.loom.body.tagOf
import dev.loom.contract.Decision
import dev.loom.contract.Intent
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import dev.loom.contract.reviewsGoal
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage

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

/** Optional operator callbacks kept separate from the required round-trip ports. */
data class CognitionCallbacks(
    val onApplied: (AppliedDecision) -> Unit = {},
    val onUnavailable: (Perception, Throwable) -> Unit = { _, _ -> },
    val onBodyUnavailable: (AgentId, Throwable) -> Unit = { _, _ -> },
    /** Told when a step the held goal took was carried out and settled, so a follow-up review is owed. */
    val onStepSettled: StepSettlement = StepSettlement.NONE,
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
 * @param goals each agent's short-term goal, carried into its goal reviews and
 *   resolved against the decisions that answer them.
 * @param callbacks operator reporting for applied, unanswered and unavailable
 *   body work. Every callback runs on the main thread.
 *   A lost decision is visible rather than silent. Called on the main thread,
 *   so a handler may safely touch world state.
 *   Process exits and control timeouts arrive as body-unavailable callbacks,
 *   never as inferred deaths.
 */
class EmbodiedCognition(
    private val bodies: BodyPort,
    private val mind: CognitionBridge,
    private val mainThread: MainThread,
    private val journal: DecisionJournal,
    private val callbacks: CognitionCallbacks = CognitionCallbacks(),
    private val goals: GoalLedger = GoalLedger(),
) {
    private val danger = DangerEpisodes(journal::tick)

    // Perceptions sent to the mind whose decision has not yet been recorded or lost, so a
    // plugin shutdown can end each chain rather than leave it open.
    private val inFlight = linkedMapOf<String, Pair<AgentId, Perception>>()
    private var shutDown = false

    /**
     * Captures [agentId]'s perception of [trigger] and sets cognition going.
     *
     * Must be called on the main thread. This schedules the round trip and does
     * not wait for body I/O or cognition. The returned stage completes with the
     * perception that was sent, or `null` if the agent has no body to perceive
     * with.
     *
     * The perception is classified here rather than by the body: a body port
     * reports what happened, and how much that matters - which decides what the
     * decision costs - is this side's call. See [importanceOf].
     */
    fun consider(
        agentId: AgentId,
        trigger: Trigger,
    ): CompletionStage<Perception?> {
        val considered = CompletableFuture<Perception?>()
        bodies.perceive(agentId, trigger).whenComplete { perception, failure ->
            mainThread.execute {
                if (failure != null) {
                    val cause = underlying(failure)
                    callbacks.onBodyUnavailable(agentId, cause)
                    considered.completeExceptionally(cause)
                    return@execute
                }
                val classified = perception?.classified()
                if (classified != null) {
                    submit(agentId, classified)
                }
                considered.complete(classified)
            }
        }
        return considered
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
        if (shutDown) return
        val coalesced =
            when {
                // The agent is already answering this threat.
                !danger.admit(agentId, perception) ->
                    EventTag.DANGER_COALESCED to "coalesced into the active danger episode for this threat"
                // One review and one event at a time: the answer already coming covers this one.
                inFlightFor(agentId, review = perception.trigger.reviewsGoal) > 0 -> {
                    danger.withdraw(agentId, perception.requestId)
                    EventTag.COGNITION_COALESCED to
                        "coalesced: the agent already has cognition in flight for this kind of trigger"
                }
                else -> null
            }
        if (coalesced != null) {
            // The perception still ends its chain, and gives back what its request holds.
            journal.unavailable(agentId, perception, coalesced.first, coalesced.second)
            bodies.held.release(agentId, perception.requestId)
            return
        }
        val carried =
            if (perception.trigger.reviewsGoal) {
                perception.copy(
                    goal =
                        goals.present(
                            agentId,
                            perception.requestId,
                            perception.capturedTick,
                            GoalObservation.of(perception),
                        ),
                    lastRefusal = goals.takeRefusal(agentId),
                )
            } else {
                perception
            }
        inFlight[carried.requestId] = agentId to carried
        mind.submit(carried).whenComplete { decision, failure ->
            // This runs on a bridge thread. Everything past here touches the body,
            // so it goes through the main thread or not at all.
            mainThread.execute {
                // A shutdown already ended this chain; nothing may be recorded twice.
                if (inFlight.remove(carried.requestId) == null) return@execute
                if (decision == null) {
                    lost(agentId, carried, failure ?: missingBoth(carried))
                } else {
                    actOn(agentId, decision, carried.shownGoal())
                }
            }
        }
    }

    /**
     * Whether [agentId] can take a scheduled goal review now: no review of its is still
     * waiting for the mind. A schedule asks this before claiming, so an owed review that does
     * not fit is deferred to a later slice rather than refused and lost. Main thread only.
     */
    fun canReview(agentId: AgentId): Boolean = !shutDown && inFlightFor(agentId, review = true) == 0

    /** How many of [agentId]'s perceptions of one kind - reviews or events - await the mind. */
    private fun inFlightFor(
        agentId: AgentId,
        review: Boolean,
    ): Int =
        inFlight.values.count { (agent, perception) ->
            agent == agentId && perception.trigger.reviewsGoal == review
        }

    /**
     * Ends every chain still waiting for the mind, for a plugin shutdown.
     *
     * Must be called on the main thread before the bridge closes. A perception the mind has
     * not answered, or whose answer has not reached the main thread, is journalled as
     * unavailable with [EventTag.COGNITION_SHUTDOWN], and any later answer is ignored, so the
     * record never holds a perception with no ending.
     */
    fun shutDown() {
        shutDown = true
        val pending = inFlight.values.toList()
        inFlight.clear()
        for ((agentId, perception) in pending) {
            goals.forget(agentId, perception.requestId)
            journal.unavailable(
                agentId,
                perception,
                EventTag.COGNITION_SHUTDOWN,
                "the plugin shut down before the mind answered",
            )
        }
    }

    /**
     * Submits a perception that is still crossing an asynchronous body boundary.
     *
     * Used by vigilance, which must send the exact perception whose trigger names
     * its request-scoped threat handle. The completion is marshalled onto the main
     * thread before classification or cognition.
     */
    fun submitWhenPerceived(
        agentId: AgentId,
        perception: CompletionStage<Perception?>,
    ) {
        perception.whenComplete { captured, failure ->
            mainThread.execute {
                if (failure != null) {
                    callbacks.onBodyUnavailable(agentId, underlying(failure))
                } else if (captured != null) {
                    submit(agentId, captured.classified())
                }
            }
        }
    }

    /**
     * Records a perception the mind never answered, then reports it.
     *
     * The record comes first and is not optional, for the same reason a decision's is
     * not: a run that logged only the cycles that succeeded would read as though every
     * perception got an answer, and a stalled mind would be indistinguishable from a
     * quiet agent. [CognitionCallbacks.onUnavailable] stays a callback because *reporting* is an operator
     * convenience, while recording is the research record.
     *
     * Runs on the main thread, like every other journal write from this class, so the
     * log's records stay in the order the run happened in.
     */
    private fun lost(
        agentId: AgentId,
        perception: Perception,
        failure: Throwable,
    ) {
        goals.forget(agentId, perception.requestId)
        danger.settled(agentId, perception.requestId)
        bodies.held.release(agentId, perception.requestId)
        val classified = failureOf(failure)
        journal.unavailable(agentId, perception, classified.tag, failure.message ?: classified.toString())
        callbacks.onUnavailable(perception, failure)
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
        shown: ShownGoal?,
    ) {
        val goal = goals.resolve(agentId, decision)
        journal.decided(agentId, decision, goal, shown)
        // A decision incoherent with its own goal is refused here, before the body is asked:
        // its typed step is not its intent, or it ignored a met success condition.
        if (goal != null && goal.outcome in GoalEvidence.REFUSING) {
            val refused = IntentOutcome.Rejected("the decision contradicts its own goal: ${goal.outcome}", goal.outcome)
            journal.refused(agentId, decision, goal.outcome)
            goals.stepped(agentId, decision.requestId, tagOf(decision.intent), goal.outcome)
            danger.settled(agentId, decision.requestId, fled = decision.intent is Intent.Flee)
            bodies.held.release(agentId, decision.requestId)
            callbacks.onApplied(AppliedDecision(decision, refused))
            return
        }
        // A plain completion leaves no goal held: it owes a review soon after, so the next goal is
        // not a whole cadence away (r19: six completions left their agent goal-less for 2100 to
        // 2400 ticks).
        if (goal?.outcome == GoalEvidence.COMPLETED) {
            callbacks.onStepSettled.settled(agentId, journal.tick(), owed = true)
        }
        bodies.apply(agentId, decision.intent, decision.requestId).whenComplete { outcome, failure ->
            mainThread.execute {
                val applied =
                    if (failure != null) {
                        callbacks.onBodyUnavailable(agentId, underlying(failure))
                        IntentOutcome.NoSuchBody
                    } else {
                        outcome ?: IntentOutcome.NoSuchBody
                    }
                // Cognition is asynchronous, so an agent can die or disconnect between
                // perceiving and acting. The body records nothing for a decision it never
                // received, so the loss is recorded here or nowhere.
                val goalStep =
                    goals.stepped(
                        agentId,
                        decision.requestId,
                        tagOf(decision.intent),
                        tagOf(applied),
                        (applied as? IntentOutcome.Accepted)?.reached,
                    )
                val effected =
                    applied is IntentOutcome.Accepted || (applied as? IntentOutcome.Rejected)?.effected == true
                if (goalStep != null && effected) {
                    // A step that left its own drop of the goal's item lying owes a review to collect it.
                    val dropped = goalStep.successItem in (applied as? IntentOutcome.Accepted)?.dropped.orEmpty()
                    callbacks.onStepSettled.settled(
                        agentId,
                        journal.tick(),
                        owed = dropped || goal?.ownGoalDropLying == true,
                    )
                }
                danger.settled(agentId, decision.requestId, fled = decision.intent is Intent.Flee)
                if (applied == IntentOutcome.NoSuchBody) danger.forget(agentId)
                if (applied == IntentOutcome.NoSuchBody) {
                    journal.discarded(agentId, decision)
                }
                callbacks.onApplied(AppliedDecision(decision, applied))
            }
        }
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

    /** Unwraps the wrapper completion stages use without exposing transport internals. */
    private fun underlying(failure: Throwable): Throwable =
        if (failure is CompletionException && failure.cause != null) {
            requireNotNull(failure.cause)
        } else {
            failure
        }
}

/** What this perception showed cognition of its goal, when it reviews one. */
private fun Perception.shownGoal(): ShownGoal? = ShownGoal(goal, lastRefusal).takeIf { trigger.reviewsGoal }
