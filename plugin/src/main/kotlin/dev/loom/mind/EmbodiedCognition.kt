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
import dev.loom.contract.GoalSummary
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
    /** Durable permadeath, consulted when an answer arrives: a dead agent's answer is discarded. */
    val isDead: (AgentId) -> Boolean = { false },
    /** Whether the run's exposure has ended, after which nothing new is sent, applied or owed. */
    val exposure: ExposureGate = ExposureGate(),
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
    internal val journal: DecisionJournal,
    private val callbacks: CognitionCallbacks = CognitionCallbacks(),
    private val goals: GoalLedger = GoalLedger(),
) {
    private val exposure: ExposureGate get() = callbacks.exposure

    private val danger = DangerEpisodes(journal::tick)

    // Perceptions sent to the mind whose decision has not yet been recorded or lost, so a
    // plugin shutdown can end each chain rather than leave it open.
    private val inFlight = linkedMapOf<String, Pair<AgentId, Perception>>()

    private val preemption = DangerPreemption()
    private val admission = DangerAdmission(danger, preemption, mind.outstandingPerAgent) { callbacks.exposure.ended }
    private var shutDown = false

    /** Whether [shutDown] has run, after which no review is taken. */
    internal val isShutDown: Boolean get() = shutDown

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
        attacker: String? = null,
    ): CompletionStage<Perception?> {
        val considered = CompletableFuture<Perception?>()
        val perceived =
            if (trigger is Trigger.Damaged) {
                bodies.damage.perceive(
                    agentId,
                    trigger,
                    attacker,
                )
            } else {
                bodies.perceive(agentId, trigger)
            }
        perceived.whenComplete { perception, failure ->
            mainThread.execute {
                if (failure != null) {
                    val cause = underlying(failure)
                    callbacks.onBodyUnavailable(agentId, cause)
                    considered.completeExceptionally(cause)
                    return@execute
                }
                val classified = perception?.classified()
                if (classified != null) {
                    submit(agentId, classified, attacker)
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
        dangerSubject: String? = null,
    ) {
        if (shutDown) return
        val subject = dangerSubject ?: bodies.held.dangerSubjectOf(agentId, perception.requestId)
        // Recorded as shown: classified, a review with its horizon, which the ledger judges against.
        val shown = perception.withHorizon(goals.recipes, goals.heldItemOf(agentId))
        bodies.held.shown(agentId, shown)
        val coalesced = admission.coalescedOf(agentId, perception, subject, inFlight)
        if (coalesced != null) {
            // The perception still ends its chain, and gives back what its request holds.
            journal.unavailable(agentId, perception, coalesced.first, coalesced.second)
            bodies.held.release(agentId, perception.requestId)
            bodies.held.unsent(agentId, perception)
            return
        }
        val carried = goals.carried(agentId, shown)
        inFlight[carried.requestId] = agentId to carried
        preemption.sent(carried, subject)
        mind.submit(carried).whenComplete { decision, failure ->
            // On a bridge thread: everything past here touches the body, so it runs on the main thread.
            mainThread.execute {
                // A shutdown already ended this chain; nothing may be recorded twice.
                if (inFlight.remove(carried.requestId) == null) return@execute
                val supersession = preemption.settled(carried.requestId)
                // A ready flight is applied while the answer that superseded it is still coming;
                // anything else a newer answer superseded is discarded.
                val discardedNow =
                    supersession == DangerPreemption.Supersession.NEWER_ANSWERED ||
                        supersession == DangerPreemption.Supersession.NEWER_PENDING && decision?.intent !is Intent.Flee
                when {
                    decision == null -> lost(agentId, carried, failure ?: missingBoth(carried))
                    // Admitted while the run was exposed and answered after it ended: typed, never applied.
                    exposure.ended -> {
                        goals.forget(agentId, decision.requestId)
                        danger.forget(agentId)
                        bodies.held.release(agentId, decision.requestId)
                        journal.withheld(agentId, decision, EventTag.EXPOSURE_ENDED, "the run's exposure had ended")
                        callbacks.onApplied(AppliedDecision(decision, IntentOutcome.NoSuchBody))
                    }
                    // A preempting damage's answer governs the body now; this older one is discarded.
                    discardedNow -> {
                        danger.settled(agentId, decision.requestId, fled = false)
                        bodies.held.release(agentId, decision.requestId)
                        journal.withheld(agentId, decision, EventTag.DANGER_PREEMPTED, "newer damage preempted it")
                        callbacks.onApplied(AppliedDecision(decision, IntentOutcome.NoSuchBody))
                    }
                    // An answer for an agent that died while the mind was deciding is no decision
                    // of a living agent: it is discarded, never recorded as decided (r21 gamma).
                    callbacks.isDead(agentId) -> {
                        goals.forget(agentId, decision.requestId)
                        danger.forget(agentId)
                        bodies.held.release(agentId, decision.requestId)
                        journal.discarded(agentId, decision)
                        callbacks.onApplied(AppliedDecision(decision, IntentOutcome.NoSuchBody))
                    }
                    else ->
                        actOn(
                            agentId,
                            decision,
                            carried.shownGoal(),
                            carried,
                        )
                }
            }
        }
    }

    /**
     * Why [agentId] cannot take a scheduled review now, as a countable tag, or `null`: a review of
     * its still with the mind, its flight still being carried out, or a danger of its the mind is
     * still deciding - whose answer's flight the review's step would be refused as not ready
     * behind (r23: five such refusals, eta's only chain craft among them). Main thread only.
     */
    fun reviewHoldOf(agentId: AgentId): String? =
        when {
            inFlightFor(agentId, review = true) > 0 -> EventTag.REVIEW_IN_FLIGHT
            fleeing.holds(agentId) -> EventTag.FLEEING
            inFlight.values.any { (agent, seen) -> agent == agentId && dangerKeyOf(seen.trigger) != null } ->
                EventTag.DANGER_DECIDING
            else -> null
        }

    /**
     * The agents whose flight the body is still carrying out: a review then could only be refused
     * as not ready (r20: theta's follow-up fired during a pivotal flight, and both its collects
     * were refused `controlNotReady`).
     *
     * Keyed by each flight's own request, so an older flight's completion releases only its own
     * hold and never a newer flight's still running (r27: iota's review ran during its own flight,
     * and its dig was refused out of reach where the flight had carried it).
     */
    private val fleeing = FlightHolds()

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
        // Nothing was done about the threat, so its next report is asked about again rather than
        // cooled down as if answered: v29 rehearsal zeta's danger call was refused for its review
        // reserve and the three bites that followed, the last one fatal, were all coalesced.
        danger.settledDamage(
            agentId,
            perception.requestId,
            fled = false,
        )?.let { tell(agentId, it.trigger, it.attacker) }
        bodies.held.release(agentId, perception.requestId)
        val classified = failureOf(failure)
        if (classified == CognitionFailure.CallReserved) bodies.held.unsent(agentId, perception)
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
        carried: Perception? = null,
    ) {
        val goal = goals.resolve(agentId, decision)
        journal.decided(agentId, decision, goal, shown)
        val answersDanger = carried != null && dangerKeyOf(carried.trigger) != null
        val refusing = refusalOf(goal, carried, decision.intent)
        // A decision incoherent with its own goal is refused here, before the body is asked:
        // its typed step is not its intent, or it ignored a met success condition.
        if (refusing != null) {
            val refused = IntentOutcome.Rejected("the decision contradicts its own goal or danger: $refusing", refusing)
            journal.refused(agentId, decision, refusing)
            goals.stepped(agentId, decision.requestId, tagOf(decision.intent), refusing)
            danger.settledDamage(
                agentId,
                decision.requestId,
                fled = decision.intent is Intent.Flee,
            )?.let { tell(agentId, it.trigger, it.attacker) }
            bodies.held.release(agentId, decision.requestId)
            callbacks.onApplied(AppliedDecision(decision, refused))
            return
        }
        // A plain completion or an abandon leaves no goal held: it owes a review soon after, so the
        // next goal is not a whole cadence away (r19: six completions left their agent goal-less
        // for 2100 to 2400 ticks; r39: zeta held nothing for 2291 ticks after its abandon at 3611).
        if (goal?.outcome in GOAL_ENDING_OUTCOMES) {
            callbacks.onStepSettled.settled(agentId, journal.tick(), owed = true)
        }
        // A flight, and any answer to danger, holds the agent's reviews until the body has carried it out.
        val flight = decision.intent is Intent.Flee || answersDanger
        // Asked before this decision's own flight hold is taken, so the hold it finds is an older one's.
        val applying = bodies.governing(agentId, decision, answersDanger && fleeing.holds(agentId))
        if (flight) fleeing.hold(agentId, decision.requestId)
        applying.whenComplete { outcome, failure ->
            mainThread.execute {
                if (flight) fleeing.release(agentId, decision.requestId)
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
                goals.settledAt(agentId, decision.requestId, journal.tick())
                // One immediate review owed to the goal item a step left lying, then control returns.
                followUp(agentId, decision.requestId, goal, goalStep, applied)
                val held = danger.settledDamage(agentId, decision.requestId, fled = decision.intent is Intent.Flee)
                if (applied == IntentOutcome.NoSuchBody) {
                    danger.forget(agentId)
                } else {
                    held?.let {
                        tell(agentId, it.trigger, it.attacker)
                    }
                }
                if (applied == IntentOutcome.NoSuchBody) {
                    journal.discarded(agentId, decision)
                }
                callbacks.onApplied(AppliedDecision(decision, applied))
            }
        }
    }

    /** The review a settled decision owes [agentId]: one now for its goal item left lying, else as owed. */
    private fun followUp(
        agentId: AgentId,
        requestId: String,
        goal: GoalEvidence?,
        goalStep: GoalSummary?,
        applied: IntentOutcome,
    ) {
        // After the exposure ends nothing new is owed: no review and no pickup.
        if (exposure.ended) return
        val item = lyingStepItem(goal, goalStep, applied, goals.recipes)
        if (item == null) {
            callbacks.onStepSettled.oweReview(
                agentId,
                journal.tick(),
                goal,
                SettledStep(goalStep, applied, goals.recipes),
            )
            return
        }
        // The dig's one pickup of its own goal item, then the mind is asked at once.
        bodies.pickups.attempt(agentId, requestId, item).whenComplete { tried, _ ->
            mainThread.execute {
                // After a pickup the review waits the follow-up delay, so the client's inventory has
                // heard what the server gave (the path rehearsal: an immediate sense lagged the
                // server by the logs just picked up, and the witness disputed it).
                if (tried == null && canReview(agentId)) {
                    tell(agentId, Trigger.StepSettled)
                } else {
                    callbacks.onStepSettled.settled(agentId, journal.tick(), owed = true)
                }
            }
        }
    }

    /**
     * Asks [agentId]'s mind about [trigger] now: the damage it took while its last danger answer
     * ran, or the step that left its own drop of its goal's item lying, so the one collection that
     * drop is owed comes before a peer's pickup rather than a cadence later (r24: all three of
     * epsilon's logs were taken by idle peers before its follow-up).
     */
    private fun tell(
        agentId: AgentId,
        trigger: Trigger,
        attacker: String? = null,
    ) {
        if (!callbacks.isDead(agentId) && !shutDown && !exposure.ended) consider(agentId, trigger, attacker)
    }
}

/**
 * [decision] applied to [agentId]'s body as the newest danger answer governs it: one that is not
 * itself a flight, applied [afterOlderFlight] of the agent's own still being carried out, first stops
 * that flight, which would otherwise refuse it as not ready (r34 epsilon), while a flight takes over by
 * replacing it.
 */
private fun BodyPort.governing(
    agentId: AgentId,
    decision: Decision,
    afterOlderFlight: Boolean,
): CompletionStage<IntentOutcome> =
    if (afterOlderFlight && decision.intent !is Intent.Flee) {
        halts
            .halt(agentId, decision.requestId)
            .handle { _, _ -> Unit }
            .thenCompose { apply(agentId, decision.intent, decision.requestId) }
    } else {
        apply(agentId, decision.intent, decision.requestId)
    }

/** Unwraps the wrapper completion stages use without exposing transport internals. */
private fun underlying(failure: Throwable): Throwable =
    if (failure is CompletionException && failure.cause != null) {
        requireNotNull(failure.cause)
    } else {
        failure
    }

/** What this perception showed cognition of its goal, when it reviews one. */
private fun Perception.shownGoal(): ShownGoal? = ShownGoal(goal, lastRefusal, horizon).takeIf { trigger.reviewsGoal }

/** One settled step of a goal: the goal as it stood after it, what the body did, and the recipes. */
private class SettledStep(
    val goalStep: GoalSummary?,
    val applied: IntentOutcome,
    val recipes: RecipeInputs,
)

/**
 * Owes [agentId] a follow-up review at [tick] when its decision settled [step]'s outcome: a goal a
 * survival response deferred is owed one whatever the response did, and a held goal's step one
 * when the body carried it out - owed past the ordinary allowance when it left the agent's own
 * drop of the goal's item, or of a direct input of its recipe, lying.
 */
private fun StepSettlement.oweReview(
    agentId: AgentId,
    tick: Long,
    goal: GoalEvidence?,
    step: SettledStep,
) {
    if (goal?.outcome == GoalEvidence.DEFERRED) return settled(agentId, tick, owed = true)
    val goalStep = step.goalStep ?: return
    val applied = step.applied
    val effected = applied is IntentOutcome.Accepted || (applied as? IntentOutcome.Rejected)?.effected == true
    val sheltering = goalStep.shelter || goalStep.feeds != null
    when {
        // A shelter is followed step by step to its seal, a refused step as well: night will not wait.
        sheltering && applied != IntentOutcome.NoSuchBody -> sheltered(agentId, tick)
        // A discovery is followed through its reveal and into the resource goal it became.
        effected && goalStep.discoveryChain(goal) -> discovered(agentId, tick)
        effected -> {
            val dropped = lyingStepItem(goal, goalStep, applied, step.recipes) != null
            settled(agentId, tick, owed = dropped || goal?.ownGoalDropLying == true)
        }
        // A step the body refused is owed an ordinary review, so the goal does not wait a whole
        // cadence for its next step (r36: zeta stood idle 1584 ticks after its refused dig).
        applied is IntentOutcome.Rejected -> settled(agentId, tick, owed = false)
    }
}

/** [shown] as the mind receives it: a goal review carries its agent's goal and last refusal. */
private fun GoalLedger.carried(
    agentId: AgentId,
    shown: Perception,
): Perception =
    if (shown.trigger.reviewsGoal) {
        shown.copy(
            goal = present(agentId, shown.requestId, shown.capturedTick, GoalObservation.of(shown)),
            lastRefusal = takeRefusal(agentId),
        )
    } else {
        shown
    }

/**
 * Why a decision is refused before the body acts, or `null`: its own goal refused it, or - in an
 * event, which carries no goal choice - it took no survival response beside an immediate hunter.
 */
private fun refusalOf(
    goal: GoalEvidence?,
    carried: Perception?,
    intent: Intent,
): String? =
    goal?.outcome?.takeIf { it in GoalEvidence.REFUSING }
        ?: EventTag.SURVIVAL_REQUIRED.takeIf {
            goal == null && carried != null && SurvivalOffer.of(carried).withholds(intent)
        }

/** Whether this settled step is a discovery's: its walk, or the first step of the goal it became. */
private fun GoalSummary.discoveryChain(goal: GoalEvidence?): Boolean = discovery || goal?.discoveredFrom != null

/**
 * The item a settled goal step left lying as its own drop, owed one collection now: the goal's
 * own item, else a direct input of a recipe for it (r27: delta's dig dropped the oak log its
 * oak-wood goal is crafted from, got no pickup and no review, and a peer took it 1400 ticks later).
 */
internal fun lyingStepItem(
    goal: GoalEvidence?,
    goalStep: GoalSummary?,
    applied: IntentOutcome,
    recipes: RecipeInputs,
): String? {
    val target = goalStep?.successItem?.takeIf { goal?.outcome != GoalEvidence.DEFERRED } ?: return null
    val dropped = (applied as? IntentOutcome.Accepted)?.dropped.orEmpty()
    return target.takeIf { it in dropped } ?: recipes.inputsOf(target).sorted().firstOrNull { it in dropped }
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

/**
 * Whether [agentId] can take a scheduled goal review now: nothing in
 * [EmbodiedCognition.reviewHoldOf] holds it. A schedule asks this before claiming, so an owed
 * review that does not fit is deferred to a later slice rather than refused and lost. Main thread
 * only.
 */
fun EmbodiedCognition.canReview(agentId: AgentId): Boolean = !isShutDown && reviewHoldOf(agentId) == null

/** The goal outcomes that leave no goal held, each owing a review soon after. */
private val GOAL_ENDING_OUTCOMES = setOf(GoalEvidence.COMPLETED, GoalEvidence.ABANDONED)
