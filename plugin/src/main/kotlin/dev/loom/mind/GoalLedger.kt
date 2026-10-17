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
 *   and ends the goal. Moving about, falling and returning advance nothing, and the count
 *   survives a revise; only real progress, completion, abandonment or a new goal resets it;
 * - a decision whose typed step disagrees with its own intent, that leaves a met success
 *   condition neither completed nor revised, or that completes an unmet one is refused before
 *   its intent reaches the body (`incoherenceOf`).
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
import dev.loom.contract.GoalStep
import dev.loom.contract.GoalSummary
import dev.loom.contract.MAX_GOAL_ID_CHARS
import dev.loom.contract.MAX_GOAL_STEPS_WITHOUT_PROGRESS

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
    )

    private data class Basis(
        val session: Long,
        val presented: GoalSummary?,
        val capturedTick: Long,
        val expiredGoalId: String?,
        val observed: GoalObservation?,
    )

    private class AgentGoals(
        val session: Long,
    ) {
        var held: Held? = null
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
        val presented =
            goals.held?.let { held ->
                held.summary.copy(
                    progress =
                        progressOf(
                            held.summary,
                            held.observed,
                            observed,
                            StepRecord(held.visited, held.stepVisible, held.reached),
                            held.stepsWithoutProgress,
                        ) ?: held.summary.progress,
                )
            }
        goals.pending.remove(requestId)
        goals.pending[requestId] = Basis(goals.session, presented, capturedTick, expired, observed)
        if (goals.pending.size > MAX_PENDING_GOAL_REVIEWS) {
            goals.pending.remove(goals.pending.keys.first())
        }
        return presented
    }

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
                stepsWithoutProgress = basis.presented?.progress?.stepsWithoutProgress,
                conditionMet = basis.presented?.progress?.conditionMet,
                conditionAdvanced = basis.presented?.progress?.conditionAdvanced,
                stepAdvanced = basis.presented?.progress?.stepAdvanced,
                stepEvidence = basis.presented?.progress?.stepEvidence,
            )
        val unchanged =
            basis.session == goals.session &&
                current?.goalId == basis.presented?.goalId &&
                current?.revision == basis.presented?.revision
        val incoherent =
            if (unchanged) {
                incoherenceOf(
                    basis.presented,
                    choice,
                    decision.intent,
                    basis.observed,
                )
            } else {
                null
            }
        return when {
            incoherent != null -> evidence.copy(outcome = incoherent)
            choice == null -> evidence
            !unchanged -> evidence.copy(outcome = GoalEvidence.STALE)
            else -> apply(goals, basis, choice, decision.requestId, evidence)
        }
    }

    /** Records the body's [outcome] tag for [action] when [requestId] is the held goal's latest step. */
    @Synchronized
    fun stepped(
        agentId: AgentId,
        requestId: String,
        action: String,
        outcome: String,
        reached: Boolean? = null,
    ) {
        val goals = agents[agentId] ?: return
        val held = goals.held
        if (held?.affirmedBy == requestId) {
            goals.held =
                held.copy(
                    summary = held.summary.copy(lastStep = GoalStep(requestId, action, outcome)),
                    reached = reached,
                )
        }
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

    /** [log], with every record it takes also offered to [saw] once it is written. */
    fun watching(log: BodyEventLog): BodyEventLog =
        BodyEventLog { record ->
            log.append(record)
            saw(record)
        }

    /** How many agents currently hold state, for bound tests. */
    @Synchronized
    fun trackedAgents(): Int = agents.size

    private fun apply(
        goals: AgentGoals,
        basis: Basis,
        choice: GoalChoice,
        requestId: String,
        evidence: GoalEvidence,
    ): GoalEvidence {
        val refusal = refusalOf(basis.presented, choice) ?: stalledOf(basis.presented, choice)
        if (refusal == GoalEvidence.STALLED) goals.held = null
        if (refusal != null) {
            return if (refusal == GoalEvidence.STALLED) {
                evidence.copy(outcome = refusal, goalId = null, revision = null)
            } else {
                evidence.copy(outcome = refusal)
            }
        }
        val presented = basis.presented
        val next = nextGoalOf(presented, choice, requestId, basis.capturedTick)
        // A continue or revise carries the run of steps that changed nothing; only an adopted
        // goal starts it again, since only a measured change is evidence of progress.
        val carriedCount =
            if (choice.disposition == GoalDisposition.ADOPT) 0 else presented?.progress?.stepsWithoutProgress ?: 0
        // A new goal starts with only the cell it was adopted at; a kept goal remembers every cell
        // it has been reviewed at, so a walk back to one of them is no arrival anywhere new.
        val earlier = if (choice.disposition == GoalDisposition.ADOPT) emptySet() else goals.held?.visited.orEmpty()
        val visited = (earlier + listOfNotNull(basis.observed?.cell)).toList().takeLast(MAX_GOAL_CELLS).toSet()
        // A walk's step is evidence only toward a target this perception showed in sight.
        val stepVisible = choice.stepRef != null && basis.observed?.visible?.contains(choice.stepRef) == true
        goals.held =
            next?.let { Held(it, basis.capturedTick, requestId, basis.observed, carriedCount, visited, stepVisible) }
        return evidence.copy(
            outcome = acceptedTag(choice.disposition),
            goalId = next?.goalId,
            revision = next?.revision,
        )
    }
}

/** The dispositions that keep the held goal, and so the run of steps that changed nothing. */
private val KEEPING = setOf(GoalDisposition.CONTINUE, GoalDisposition.REVISE)

/** The goal [choice] leaves held after [presented], or `null` when it ends the goal. */
private fun nextGoalOf(
    presented: GoalSummary?,
    choice: GoalChoice,
    requestId: String,
    capturedTick: Long,
): GoalSummary? =
    when (choice.disposition) {
        GoalDisposition.ADOPT ->
            GoalSummary(
                goalId = requestId,
                statement = requireNotNull(choice.statement),
                revision = 0,
                adoptedTick = capturedTick,
                successCondition = choice.successCondition,
                successItem = choice.successItem,
                successCount = choice.successCount,
                step = choice.step,
                stepAction = choice.stepAction,
                stepRef = choice.stepRef,
            )
        GoalDisposition.CONTINUE ->
            requireNotNull(presented).copy(
                lastStep = null,
                step = choice.step,
                stepAction = choice.stepAction,
                stepRef = choice.stepRef,
                progress = null,
            )
        GoalDisposition.REVISE ->
            requireNotNull(presented).copy(
                statement = requireNotNull(choice.statement),
                revision = presented.revision + 1,
                lastStep = null,
                successCondition = choice.successCondition,
                successItem = choice.successItem,
                successCount = choice.successCount,
                step = choice.step,
                stepAction = choice.stepAction,
                stepRef = choice.stepRef,
                progress = null,
            )
        GoalDisposition.COMPLETE, GoalDisposition.ABANDON -> null
    }

/** Why [choice] cannot apply to the goal its perception carried, or `null` if it can. */
private fun refusalOf(
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
    choice: GoalChoice,
): String? =
    GoalEvidence.STALLED.takeIf {
        choice.disposition in KEEPING &&
            (presented?.progress?.stepsWithoutProgress ?: 0) >= MAX_GOAL_STEPS_WITHOUT_PROGRESS
    }

/** The tag an applied [disposition] is recorded under. */
private fun acceptedTag(disposition: GoalDisposition): String =
    when (disposition) {
        GoalDisposition.ADOPT -> GoalEvidence.ADOPTED
        GoalDisposition.CONTINUE -> GoalEvidence.CONTINUED
        GoalDisposition.REVISE -> GoalEvidence.REVISED
        GoalDisposition.COMPLETE -> GoalEvidence.COMPLETED
        GoalDisposition.ABANDON -> GoalEvidence.ABANDONED
    }
