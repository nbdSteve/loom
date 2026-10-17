/*
 * Each agent's one short-term goal, carried from one goal review to the next.
 *
 * Owned on this side of the bridge because this is where a perception, the decision
 * answering it and the body's outcome meet: the mind stays stateless per request, and
 * the body reports what happened without knowing why. The ledger only stores what a
 * decision explicitly chose - it never invents, rewrites or assigns a goal.
 *
 * Every rule is deterministic and engine-free:
 * - a goal is keyed by Loom's own [AgentId], never by the mind's echo of it;
 * - a decision resolves only against the goal its own perception carried, so a
 *   concurrent review that changed the goal first leaves the later answer stale;
 * - a goal not adopted, continued or revised within [lifetimeTicks] of server time
 *   expires at the next review;
 * - spawn, disconnect, despawn, death and revival clear the agent's goal and every
 *   answer still in flight for it, because a new body session is not the world the
 *   goal was chosen in;
 * - each review measures, from the agent's own perceptions, whether the goal's typed success
 *   condition advanced or its typed step was carried out (`WorkingGoal.kt`), and a continue or
 *   revise past [MAX_GOAL_STEPS_WITHOUT_PROGRESS] steps in a row that advanced neither is refused
 *   with its intent and ends the goal. Moving about, falling, returning and a drop not yet held
 *   advance nothing, and the count survives a revise; only real progress, completion, abandonment
 *   or a new goal resets it;
 * - a step is measured once: a later review whose answer took no new step sees the condition as
 *   it is then, and neither re-credits the step nor counts it again;
 * - a goal's step is typed from the decision's own intent, and its wording from its typed
 *   success condition, never claimed by the mind, so none of them can disagree;
 * - a decision that leaves a met success condition neither completed nor revised, completes an
 *   unmet one, acts while leaving a held goal unanswered, sets or keeps a condition already met or
 *   more than what is held and in sight could give, pursues a goal while idling, or digs or
 *   collects what cannot give its item, is refused before its intent reaches the body
 *   (`incoherenceOf`);
 * - a goal decision the ledger refused is told, with the numbers it was judged by, to the agent's
 *   next goal review, once;
 * - a replace ends the held goal and adopts a new one under its own decision's id in one answer,
 *   which is how a goal at the stall bound keeps acting without losing a review cycle;
 * - a continue or revise that does not collect the agent's own lying drop of its goal's item is
 *   refused, and a walk is credited only onto a block that is itself a source of the goal's item.
 */
package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.GoalEvidence
import dev.loom.contract.Decision
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalRefusal
import dev.loom.contract.GoalStep
import dev.loom.contract.GoalSummary
import dev.loom.contract.Intent
import dev.loom.contract.MAX_GOAL_ID_CHARS
import dev.loom.contract.MAX_GOAL_STEPS_WITHOUT_PROGRESS
import dev.loom.contract.action
import dev.loom.contract.targetRef

/** A held goal survives one unaddressed review and expires at the one after. */
const val GOAL_LIFETIME_TICKS: Long = 2L * DEFAULT_GOAL_REVIEW_CADENCE_TICKS

/** Matches the configured fleet bound, so every configured agent can hold a goal. */
const val MAX_GOAL_AGENTS: Int = 16

/** Goal reviews one agent may have awaiting an answer; the oldest is dropped past it. */
const val MAX_PENDING_GOAL_REVIEWS: Int = 8

private val CLEARING_EVENTS =
    setOf(
        BodyEvent.SPAWNED,
        BodyEvent.BODY_DISCONNECTED,
        BodyEvent.DESPAWNED,
        BodyEvent.DIED,
        BodyEvent.WITNESS_REVIVED,
    )

/**
 * Bounded, per-agent goal state. Thread-safe: [saw] runs on whichever thread appended
 * a record, everything else on the main thread.
 */
class GoalLedger(
    private val lifetimeTicks: Long = GOAL_LIFETIME_TICKS,
) {
    private data class Held(
        val summary: GoalSummary,
        val affirmedTick: Long,
        val affirmedBy: String,
        val observed: GoalObservation?,
        val stepsWithoutProgress: Long,
        val visited: Set<List<Int>>,
        val stepVisible: Boolean = false,
        val reached: Boolean? = null,
        val measured: Boolean = false,
        val towardSource: Boolean = false,
    ) {
        /**
         * This goal as [now] shows it, and the goal to hold after: its settled step measured, once.
         * A step an earlier review measured is not measured again, and one still in flight is
         * measured when it settles.
         */
        fun measuredFor(now: GoalObservation?): Pair<GoalSummary, Held> {
            val earlier = summary.progress
            if (measured && earlier != null) {
                return summary.copy(progress = remeasured(summary, earlier, now)) to this
            }
            val step = StepRecord(visited, stepVisible, reached, towardSource)
            val progress = progressOf(summary, observed, now, step, stepsWithoutProgress) ?: earlier
            val shown = summary.copy(progress = progress)
            val settled = summary.lastStep != null && progress != null
            return shown to if (settled) copy(summary = shown, measured = true) else this
        }
    }

    private data class Basis(
        val session: Long,
        val presented: GoalSummary?,
        val capturedTick: Long,
        val expiredGoalId: String?,
        val observed: GoalObservation?,
        val fresh: Boolean,
    )

    private class AgentGoals(
        val session: Long,
    ) {
        var held: Held? = null
        var refusal: GoalRefusal? = null
        val pending = LinkedHashMap<String, Basis>()
    }

    private val agents = HashMap<AgentId, AgentGoals>()

    // Ledger-wide, so an agent cleared and re-entered can never match an older basis.
    private var sessions = 0L

    /**
     * The goal to carry into [agentId]'s goal review [requestId], recording what was
     * carried so the answer can be checked against it.
     */
    @Synchronized
    fun present(
        agentId: AgentId,
        requestId: String,
        capturedTick: Long,
        observed: GoalObservation? = null,
    ): GoalSummary? {
        val goals = trackedFor(agentId, requestId) ?: return null
        val held = goals.held
        var expired: String? = null
        if (held != null && capturedTick - held.affirmedTick > lifetimeTicks) {
            expired = held.summary.goalId
            goals.held = null
        }
        val fresh = goals.held?.measured != true
        val presented =
            goals.held?.let { held ->
                val (summary, measured) = held.measuredFor(observed)
                goals.held = measured
                summary
            }
        goals.pending.remove(requestId)
        goals.pending[requestId] = Basis(goals.session, presented, capturedTick, expired, observed, fresh)
        if (goals.pending.size > MAX_PENDING_GOAL_REVIEWS) {
            goals.pending.remove(goals.pending.keys.first())
        }
        return presented
    }

    /**
     * Why the ledger refused [agentId]'s last goal decision, told once: the next goal review's
     * perception carries it, and no later one does.
     */
    @Synchronized
    fun takeRefusal(agentId: AgentId): GoalRefusal? =
        agents[agentId]?.let { goals -> goals.refusal.also { goals.refusal = null } }

    private fun trackedFor(
        agentId: AgentId,
        requestId: String,
    ): AgentGoals? =
        when {
            requestId.codePointCount(0, requestId.length) > MAX_GOAL_ID_CHARS -> null
            agentId in agents -> agents[agentId]
            agents.size < MAX_GOAL_AGENTS -> AgentGoals(++sessions).also { agents[agentId] = it }
            else -> null
        }

    /** Resolves [decision]'s goal choice for [agentId], or `null` when there is nothing to say. */
    @Synchronized
    fun resolve(
        agentId: AgentId,
        decision: Decision,
    ): GoalEvidence? {
        val goals = agents[agentId]
        val basis = goals?.pending?.remove(decision.requestId)
        val choice = decision.goal
        if (goals == null || basis == null) {
            return choice?.let { GoalEvidence(GoalEvidence.NOT_REVIEWED, it.disposition) }
        }
        val current = goals.held?.summary
        // What a review measured is evidence only at the review that measured it.
        val measured = basis.presented?.progress?.takeIf { basis.fresh }
        val evidence =
            GoalEvidence(
                outcome = GoalEvidence.UNADDRESSED,
                disposition = choice?.disposition,
                presentedGoalId = basis.presented?.goalId,
                presentedRevision = basis.presented?.revision,
                goalId = current?.goalId,
                revision = current?.revision,
                expiredGoalId = basis.expiredGoalId,
                stepResult = choice?.stepResult,
                stepsWithoutProgress = measured?.stepsWithoutProgress,
                conditionMet = basis.presented?.progress?.conditionMet,
                conditionAdvanced = measured?.conditionAdvanced,
                stepAdvanced = measured?.stepAdvanced,
                stepEvidence = measured?.stepEvidence,
            )
        val unchanged =
            basis.session == goals.session &&
                current?.goalId == basis.presented?.goalId &&
                current?.revision == basis.presented?.revision
        val incoherent =
            if (unchanged) {
                stalledOf(basis.presented, choice)
                    ?: incoherenceOf(basis.presented, choice, decision.intent, basis.observed)
            } else {
                null
            }
        val resolved =
            when {
                incoherent == GoalEvidence.STALLED -> {
                    goals.held = null
                    evidence.copy(outcome = incoherent, goalId = null, revision = null)
                }
                incoherent != null -> evidence.copy(outcome = incoherent)
                choice == null -> evidence
                !unchanged -> evidence.copy(outcome = GoalEvidence.STALE)
                else -> apply(goals, basis, choice, decision, evidence)
            }
        if (resolved.outcome !in GoalEvidence.TOLD_NOTHING) {
            goals.refusal = refusalOf(decision.requestId, resolved.outcome, basis.presented, choice, basis.observed)
        }
        return resolved
    }

    /**
     * Records the body's [outcome] tag for [action] when [requestId] is the held goal's latest
     * step, and answers whether it was: only then has a step of the held goal settled.
     */
    @Synchronized
    fun stepped(
        agentId: AgentId,
        requestId: String,
        action: String,
        outcome: String,
        reached: Boolean? = null,
    ): Boolean {
        val goals = agents[agentId]
        val held = goals?.held
        if (held?.affirmedBy != requestId) return false
        goals.held =
            held.copy(
                summary = held.summary.copy(lastStep = GoalStep(requestId, action, outcome)),
                reached = reached,
            )
        return true
    }

    /** Drops the record of a review the mind never answered. */
    @Synchronized
    fun forget(
        agentId: AgentId,
        requestId: String,
    ) {
        agents[agentId]?.pending?.remove(requestId)
    }

    /** Clears [agentId]'s goal and every answer in flight for it. */
    @Synchronized
    fun clear(agentId: AgentId) {
        agents.remove(agentId)
    }

    /** Applies the lifecycle clearing rule to one appended record. */
    fun saw(record: BodyEventRecord) {
        if (record.event in CLEARING_EVENTS) clear(AgentId(record.agentId))
    }

    /** How many agents currently hold state, for bound tests. */
    @Synchronized
    fun trackedAgents(): Int = agents.size

    private fun apply(
        goals: AgentGoals,
        basis: Basis,
        choice: GoalChoice,
        decision: Decision,
        evidence: GoalEvidence,
    ): GoalEvidence {
        val requestId = decision.requestId
        val refusal = shapeRefusalOf(basis.presented, choice)
        if (refusal != null) return evidence.copy(outcome = refusal)
        val presented = basis.presented
        val next = nextGoalOf(presented, choice, Affirmation(requestId, basis.capturedTick, decision.intent))
        // A continue or revise carries the run of steps that changed nothing; only an adopted
        // goal starts it again, since only a measured change is evidence of progress.
        val fresh = choice.disposition in STARTING
        val carriedCount = if (fresh) 0 else presented?.progress?.stepsWithoutProgress ?: 0
        // A new goal starts with only the cell it was adopted at; a kept goal remembers every cell
        // it has been reviewed at, so a walk back to one of them is no arrival anywhere new.
        val earlier = if (fresh) emptySet() else goals.held?.visited.orEmpty()
        val visited = (earlier + listOfNotNull(basis.observed?.cell)).toList().takeLast(MAX_GOAL_CELLS).toSet()
        // A walk's step is evidence only toward a target this perception showed in sight.
        val target = decision.intent.targetRef
        val stepVisible = target != null && basis.observed?.visible?.contains(target) == true
        val item = next?.successItem
        val towardSource = item != null && target != null && item in basis.observed?.gives?.get(target).orEmpty()
        goals.held =
            next?.let {
                Held(
                    it,
                    basis.capturedTick,
                    requestId,
                    basis.observed,
                    carriedCount,
                    visited,
                    stepVisible,
                    towardSource = towardSource,
                )
            }
        return evidence.copy(
            outcome = acceptedTag(choice.disposition),
            goalId = next?.goalId,
            revision = next?.revision,
        )
    }
}

/** The dispositions that keep the held goal, and so the run of steps that changed nothing. */
private val KEEPING = setOf(GoalDisposition.CONTINUE, GoalDisposition.REVISE)

/** The dispositions that start a goal of their own, and so a run of steps of its own. */
private val STARTING = setOf(GoalDisposition.ADOPT, GoalDisposition.REPLACE)

/** The decision affirming a goal: its request, when its perception was taken, and its intent. */
private data class Affirmation(
    val requestId: String,
    val capturedTick: Long,
    val intent: Intent,
)

/**
 * The goal [choice] leaves held after [presented], or `null` when it ends the goal. Its step is
 * typed from the affirming decision's own intent, so a step can never name what its intent does
 * not do (r14 gamma's step named a dropped stick while its intent walked the other way).
 */
private fun nextGoalOf(
    presented: GoalSummary?,
    choice: GoalChoice,
    by: Affirmation,
): GoalSummary? =
    when (choice.disposition) {
        // A replace ends the held goal and starts a new one under its own decision's id.
        GoalDisposition.ADOPT, GoalDisposition.REPLACE ->
            GoalSummary(
                goalId = by.requestId,
                statement = statementOf(requireNotNull(choice.successItem), requireNotNull(choice.successCount)),
                revision = 0,
                adoptedTick = by.capturedTick,
                successItem = choice.successItem,
                successCount = choice.successCount,
                step = choice.step,
                stepAction = by.intent.action,
                stepRef = by.intent.targetRef,
            )
        GoalDisposition.CONTINUE ->
            requireNotNull(presented).copy(
                lastStep = null,
                step = choice.step,
                stepAction = by.intent.action,
                stepRef = by.intent.targetRef,
                progress = null,
            )
        GoalDisposition.REVISE ->
            requireNotNull(presented).copy(
                statement = statementOf(requireNotNull(choice.successItem), requireNotNull(choice.successCount)),
                revision = presented.revision + 1,
                lastStep = null,
                successItem = choice.successItem,
                successCount = choice.successCount,
                step = choice.step,
                stepAction = by.intent.action,
                stepRef = by.intent.targetRef,
                progress = null,
            )
        GoalDisposition.COMPLETE, GoalDisposition.ABANDON -> null
    }

/** Why [choice] cannot apply to the goal its perception carried, or `null` if it can. */
private fun shapeRefusalOf(
    presented: GoalSummary?,
    choice: GoalChoice,
): String? {
    val adopting = choice.disposition == GoalDisposition.ADOPT
    return when {
        adopting && presented != null -> GoalEvidence.ALREADY_HELD
        !adopting && presented == null -> GoalEvidence.NOT_HELD
        else -> missingFieldOf(choice)
    }
}

/** A continue or revise of a goal at the no-progress bound, which ends it, or `null`. */
private fun stalledOf(
    presented: GoalSummary?,
    choice: GoalChoice?,
): String? =
    GoalEvidence.STALLED.takeIf {
        choice?.disposition in KEEPING &&
            (presented?.progress?.stepsWithoutProgress ?: 0) >= MAX_GOAL_STEPS_WITHOUT_PROGRESS
    }

/** The tag an applied [disposition] is recorded under. */
private fun acceptedTag(disposition: GoalDisposition): String =
    when (disposition) {
        GoalDisposition.ADOPT -> GoalEvidence.ADOPTED
        GoalDisposition.CONTINUE -> GoalEvidence.CONTINUED
        GoalDisposition.REVISE -> GoalEvidence.REVISED
        GoalDisposition.REPLACE -> GoalEvidence.REPLACED
        GoalDisposition.COMPLETE -> GoalEvidence.COMPLETED
        GoalDisposition.ABANDON -> GoalEvidence.ABANDONED
    }

/** [log], with every record it takes also offered to [GoalLedger.saw] once it is written. */
fun GoalLedger.watching(log: BodyEventLog): BodyEventLog =
    BodyEventLog { record ->
        log.append(record)
        saw(record)
    }
