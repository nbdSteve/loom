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
import dev.loom.contract.HorizonGoal
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
    /** The server's recipe registry, which chains, steps and horizon goals are judged by. */
    val recipes: RecipeInputs = RecipeInputs.NONE,
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
        val explored: Boolean = false,
        val inputs: Set<String> = emptySet(),
        val settledTick: Long? = null,
        /** For a shelter goal, the most enclosed it has been at each cell, which a step there must pass. */
        val bestByCell: Map<List<Int>, CoverRank> = emptyMap(),
        /** For a resource subgoal, the shelter goal it feeds, held again once the subgoal ends. */
        val parent: Held? = null,
    ) {
        /**
         * This goal as [now] shows it, and the goal to hold after: its settled step measured, once.
         * A step an earlier review measured is not measured again, and one still in flight is
         * measured when it settles.
         */
        fun measuredFor(
            now: GoalObservation?,
            at: Long,
        ): Pair<GoalSummary, Held> {
            val earlier = summary.progress
            if (measured && earlier != null) {
                return summary.copy(progress = remeasured(summary, earlier, now)) to this
            }
            // A reveal is the walk's own: seen at the review right after it, never at a later one.
            val fresh = settledTick != null && at - settledTick <= REVEAL_WINDOW_TICKS
            // A shelter is credited only past the most enclosed it has been where it stands, and a move
            // only past the cover it left, so a return is no step.
            val reference = shelterReferenceOf(bestByCell, observed, now).takeIf { summary.shelter }
            val step = StepRecord(visited, stepVisible, reached, towardSource, explored, inputs, fresh, reference)
            val progress = progressOf(summary, observed, now, step, stepsWithoutProgress) ?: earlier
            val shown = summary.copy(progress = progress)
            val settled = summary.lastStep != null && progress != null
            val bests = if (summary.shelter) bestByCell.passedAt(now, displaced(summary, observed, now)) else bestByCell
            return shown to if (settled) copy(summary = shown, measured = true, bestByCell = bests) else this
        }

        /**
         * This shelter goal held again by decision [by], whose perception was taken at [at] and
         * read [observed], once the resource subgoal under it ended; [intent] is its next step,
         * none when the ending answer took no step of it.
         */
        fun resumed(
            at: Long,
            by: String,
            observed: GoalObservation?,
            intent: Intent?,
        ): Held =
            copy(
                summary =
                    summary.copy(
                        lastStep = null,
                        progress = null,
                        step = intent?.let(::stepOf) ?: summary.step,
                        stepAction = intent?.action ?: summary.stepAction,
                        stepRef = if (intent != null) intent.targetRef else summary.stepRef,
                    ),
                affirmedTick = at,
                affirmedBy = by,
                observed = observed,
                measured = false,
                reached = null,
                settledTick = null,
            )
    }

    private data class Basis(
        val session: Long,
        val presented: GoalSummary?,
        val capturedTick: Long,
        val expiredGoalId: String?,
        val observed: GoalObservation?,
        val fresh: Boolean,
    ) {
        /**
         * What a decision with no goal choice made of this review: a survival response defers a
         * held goal, or the discovery goal the review owed - neither credited nor counted, and
         * owed its review again once it settles; anything else leaves it unaddressed.
         */
        fun unanswered(intent: Intent): String {
            val owed = presented != null || observed?.discoveryOffered == true
            return if (owed && observed.defers(intent)) GoalEvidence.DEFERRED else GoalEvidence.UNADDRESSED
        }

        /**
         * What [intent]'s target was to this basis, for a goal of [item]: in sight - a walk's step is
         * evidence only toward a target this perception showed in sight - a source of [item] out of
         * dig reach, and a cell at the edge of sight.
         */
        fun targeting(
            intent: Intent,
            item: String?,
        ): Triple<Boolean, Boolean, Boolean> {
            val target = intent.targetRef ?: return Triple(false, false, false)
            val inReach = target in observed?.inReach.orEmpty()
            val toward = item != null && !inReach && item in observed?.gives?.get(target).orEmpty()
            return Triple(observed?.visible?.contains(target) == true, toward, target in observed?.explore.orEmpty())
        }

        /**
         * The evidence a decision's goal [choice] starts from: what this basis presented and
         * measured, and the goal held now, [current].
         */
        fun evidence(
            choice: GoalChoice?,
            current: GoalSummary?,
        ): GoalEvidence {
            // What a review measured is evidence only at the review that measured it.
            val measured = presented?.progress?.takeIf { fresh }
            val item = presented?.successItem
            return GoalEvidence(
                outcome = GoalEvidence.UNADDRESSED,
                disposition = choice?.disposition,
                presentedGoalId = presented?.goalId,
                presentedRevision = presented?.revision,
                goalId = current?.goalId,
                revision = current?.revision,
                expiredGoalId = expiredGoalId,
                stepResult = choice?.stepResult,
                stepsWithoutProgress = measured?.stepsWithoutProgress,
                conditionMet = presented?.progress?.conditionMet,
                conditionAdvanced = measured?.conditionAdvanced,
                stepAdvanced = measured?.stepAdvanced,
                stepEvidence = measured?.stepEvidence,
                targetHeld = item?.let { observed?.inventory?.get(it) ?: 0 },
                targetCapacity = item?.let { observed?.capacity?.get(it) ?: 0 },
                presentedDiscovery = presented?.discovery?.takeIf { it },
                presentedShelter = presented?.shelter?.takeIf { it },
                steppedBy = presented?.lastStep?.requestId?.takeIf { fresh },
                cover = presented?.progress?.cover,
                reason = choice?.reason,
            )
        }
    }

    private class AgentGoals(
        val session: Long,
    ) {
        var held: Held? = null
        var refusal: GoalRefusal? = null
        val pending = LinkedHashMap<String, Basis>()

        /** The agent's latest goals met, by id with their item, newest last. */
        private val met = ArrayDeque<Pair<String, String>>()

        /**
         * Records [presented] as met when [tag] completed it, and answers which met goal the
         * goal [next] starts is chained to: the newest whose item is a direct input of a recipe
         * for [next]'s item, by [recipes] (r19: no goal built on another goal's item).
         */
        fun chainOf(
            tag: String,
            presented: GoalSummary?,
            next: GoalSummary?,
            starting: Boolean,
            recipes: RecipeInputs,
        ): String? {
            val item = presented?.successItem
            if (item != null && tag in COMPLETING) {
                met.addLast(presented.goalId to item)
                while (met.size > MAX_MET_GOALS) met.removeFirst()
            }
            val wanted = next?.successItem?.takeIf { starting } ?: return null
            val needs = recipes.inputsOf(wanted)
            // A switch into a recipe made from what the held goal already gathered builds on it,
            // met or not (r23: eta crafted eight planks from its logs with no goal held).
            val built = (presented?.progress?.conditionHeld ?: 0) > 0 && tag == GoalEvidence.REPLACED
            return presented?.goalId.takeIf { built && item != null && item in needs }
                ?: met.lastOrNull { (_, made) -> made in needs }?.first
        }

        /**
         * What a met or abandoned resource subgoal [basis] presented leaves held: the shelter goal it
         * fed, whose next step is [decision]'s intent; `null` for any other choice.
         */
        fun resumedBy(
            basis: Basis,
            choice: GoalChoice,
            decision: Decision,
            evidence: GoalEvidence,
        ): GoalEvidence? {
            val parent = held?.parent?.takeIf { basis.presented?.feeds != null && choice.disposition in ENDING }
            return parent?.let {
                val step = decision.intent.takeIf { it.action !in PASSIVE }
                val resumed = it.resumed(basis.capturedTick, decision.requestId, basis.observed, step)
                held = resumed
                evidence.copy(
                    outcome = acceptedTag(choice.disposition, basis.presented),
                    goalId = resumed.summary.goalId,
                    revision = resumed.summary.revision,
                    shelter = true,
                    resumed = resumed.summary.goalId,
                )
            }
        }

        /**
         * The evidence of [choice], one whose shape fits the goal [basis] presented, once applied: a
         * resource subgoal that ends hands back its shelter goal, and any other choice is applied.
         */
        fun answered(
            basis: Basis,
            choice: GoalChoice,
            decision: Decision,
            evidence: GoalEvidence,
            recipes: RecipeInputs,
        ): GoalEvidence =
            shapeRefusalOf(basis.presented, choice)?.let { evidence.copy(outcome = it) }
                ?: resumedBy(basis, choice, decision, evidence)
                ?: applied(basis, choice, decision, evidence, recipes)

        /**
         * Ends the held goal at the stall bound, and answers the evidence: a stalled resource subgoal
         * hands back the shelter goal it fed, with no step yet.
         */
        fun stalled(
            basis: Basis,
            decision: Decision,
            evidence: GoalEvidence,
        ): GoalEvidence {
            val parent = held?.parent?.resumed(basis.capturedTick, decision.requestId, basis.observed, null)
            held = parent
            return evidence.copy(
                outcome = GoalEvidence.STALLED,
                goalId = parent?.summary?.goalId,
                revision = parent?.summary?.revision,
                resumed = parent?.summary?.goalId,
            )
        }

        /** The goal [choice] leaves held once applied to [basis], and the evidence of it. */
        fun applied(
            basis: Basis,
            choice: GoalChoice,
            decision: Decision,
            evidence: GoalEvidence,
            recipes: RecipeInputs,
        ): GoalEvidence {
            val requestId = decision.requestId
            val presented = basis.presented
            val next = nextGoalOf(presented, choice, Affirmation(requestId, basis.capturedTick, decision.intent))
            // A continue or revise carries the run of steps that changed nothing; only an adopted
            // goal starts it again, since only a measured change is evidence of progress.
            val fresh = choice.disposition in STARTING
            val carriedCount = carriedCountOf(presented, choice, recipes, basis.observed?.horizon.orEmpty())
            // A new goal starts with only the cell it was adopted at; a kept goal remembers every cell
            // it has been reviewed at, so a walk back to one of them is no arrival anywhere new.
            val earlier = if (fresh) emptySet() else held?.visited.orEmpty()
            val visited = (earlier + listOfNotNull(basis.observed?.cell)).toList().takeLast(MAX_GOAL_CELLS).toSet()
            val item = next?.successItem
            val (stepVisible, towardSource, explored) = basis.targeting(decision.intent, item)
            val tag = acceptedTag(choice.disposition, presented)
            val chainedFrom = chainOf(tag, presented, next, fresh, recipes)
            val before = held
            held =
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
                        explored = explored,
                        inputs =
                            item?.let { recipes.inputsOf(it) + basis.observed?.horizon.orEmpty().pathOf(it) }.orEmpty(),
                        bestByCell =
                            if (fresh) {
                                emptyMap<List<Int>, CoverRank>().passedAt(basis.observed.takeIf { _ -> it.shelter })
                            } else {
                                before?.bestByCell.orEmpty()
                            },
                        // A subgoal keeps the shelter goal it feeds; any other new goal stands alone.
                        parent = if (feeds(choice)) before else before?.parent?.takeIf { !fresh },
                    )
                }
            return evidence.copy(
                outcome = tag,
                goalId = next?.goalId,
                revision = next?.revision,
                chainedFrom = chainedFrom,
                discovery = next?.discovery?.takeIf { it },
                shelter = next?.shelter?.takeIf { it },
                feeds = next?.feeds,
                // A met discovery goal replaced by an item goal for what it revealed.
                discoveredFrom =
                    presented?.goalId?.takeIf {
                        presented.discovery && tag == GoalEvidence.COMPLETED_ADOPTED && next?.successItem != null
                    },
                ownGoalDropLying = basis.observed?.ownDrops?.containsValue(item)?.takeIf { it && item != null },
            )
        }
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
                val (summary, measured) = held.measuredFor(observed, capturedTick)
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
        val evidence = basis.evidence(choice, current)
        val unchanged =
            basis.session == goals.session &&
                current?.goalId == basis.presented?.goalId &&
                current?.revision == basis.presented?.revision
        val incoherent =
            if (unchanged) {
                stalledOf(basis.presented, choice)
                    ?: incoherenceOf(basis.presented, choice, decision.intent, basis.observed, recipes)
            } else {
                null
            }
        val resolved =
            when {
                incoherent == GoalEvidence.STALLED -> goals.stalled(basis, decision, evidence)
                incoherent != null -> evidence.copy(outcome = incoherent)
                choice == null -> evidence.copy(outcome = basis.unanswered(decision.intent))
                !unchanged -> evidence.copy(outcome = GoalEvidence.STALE)
                else -> goals.answered(basis, choice, decision, evidence, recipes)
            }
        if (resolved.outcome !in GoalEvidence.TOLD_NOTHING) {
            goals.refusal = refusalOf(decision.requestId, resolved.outcome, basis.presented, choice, basis.observed)
        }
        return resolved
    }

    /**
     * Records the body's [outcome] tag for [action] when [requestId] is the held goal's latest
     * step, and answers the held goal when it was, `null` otherwise: only then has a step of the
     * held goal settled.
     */
    @Synchronized
    fun stepped(
        agentId: AgentId,
        requestId: String,
        action: String,
        outcome: String,
        reached: Boolean? = null,
    ): GoalSummary? {
        val goals = agents[agentId]
        val held = goals?.held
        if (held?.affirmedBy != requestId) return null
        val summary = held.summary.copy(lastStep = GoalStep(requestId, action, outcome))
        goals.held = held.copy(summary = summary, reached = reached)
        return summary
    }

    /** Stamps the server tick [requestId], the held goal's latest step, settled at; a reveal is read against it. */
    @Synchronized
    fun settledAt(
        agentId: AgentId,
        requestId: String,
        tick: Long,
    ) {
        val goals = agents[agentId] ?: return
        val held = goals.held?.takeIf { it.affirmedBy == requestId } ?: return
        goals.held = held.copy(settledTick = tick)
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

    /** How many agents currently hold state, for bound tests. */
    @Synchronized
    fun trackedAgents(): Int = agents.size
}

/**
 * How soon after its walk settles a review may credit the walk with a reveal: the follow-up the
 * walk owes, and its decision time (r24: eta was credited for a peer's drop 2300 ticks later).
 */
const val REVEAL_WINDOW_TICKS: Long = 200

/** Met goals one agent remembers for chaining; the oldest is forgotten past it. */
private const val MAX_MET_GOALS = 4

/** The tags of a decision that met and ended the goal it was shown. */
private val COMPLETING = setOf(GoalEvidence.COMPLETED, GoalEvidence.COMPLETED_ADOPTED)

/** The dispositions that keep the held goal, and so the run of steps that changed nothing. */
private val KEEPING = setOf(GoalDisposition.CONTINUE, GoalDisposition.REVISE)

/** The dispositions that start a goal of their own, and so a run of steps of its own. */
private val STARTING = setOf(GoalDisposition.ADOPT, GoalDisposition.REPLACE)

/** The dispositions that end the held goal. */
private val ENDING = setOf(GoalDisposition.COMPLETE, GoalDisposition.ABANDON)

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
            if (discovers(choice)) {
                discoveryGoalOf(by.requestId, by.capturedTick, by.intent)
            } else if (shelters(choice)) {
                shelterGoalOf(by.requestId, by.capturedTick, by.intent)
            } else {
                GoalSummary(
                    goalId = by.requestId,
                    statement = statementOf(requireNotNull(choice.successItem), requireNotNull(choice.successCount)),
                    revision = 0,
                    adoptedTick = by.capturedTick,
                    successItem = choice.successItem,
                    successCount = choice.successCount,
                    step = stepOf(by.intent),
                    stepAction = by.intent.action,
                    stepRef = by.intent.targetRef,
                    feeds = presented?.goalId?.takeIf { feeds(choice) },
                )
            }
        GoalDisposition.CONTINUE ->
            requireNotNull(presented).copy(
                lastStep = null,
                step = stepOf(by.intent),
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
                step = stepOf(by.intent),
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
        // A resource subgoal is adopted under the shelter goal it feeds (`Shelter.kt`).
        adopting && presented != null && !feeds(choice) -> GoalEvidence.ALREADY_HELD
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

/**
 * The run of steps that advanced nothing [choice] carries on from [presented]: none for a goal it
 * starts afresh, except a replace of an unmet goal that sets the same item again, at any total,
 * which is the old goal relabelled (r17 alpha body:00000163; r18: three replaces at one more), or
 * an item made from it by [recipes] or a [horizon] path, which still needs the source the old goal
 * never reached (r34: zeta's oak log goal stalled three steps and was replaced by oak buttons, and
 * two more walks advanced nothing before it was abandoned).
 */
private fun carriedCountOf(
    presented: GoalSummary?,
    choice: GoalChoice,
    recipes: RecipeInputs,
    horizon: Map<String, HorizonGoal>,
): Long {
    val path = choice.successItem?.let { recipes.inputsOf(it) + horizon.pathOf(it) }.orEmpty()
    val relabelled =
        choice.disposition == GoalDisposition.REPLACE &&
            presented?.progress?.conditionMet != true &&
            presented?.successItem?.let { it == choice.successItem || it in path } == true
    val fresh = choice.disposition in STARTING && !relabelled
    return if (fresh) 0 else presented?.progress?.stepsWithoutProgress ?: 0
}

/** The tag an applied [disposition] is recorded under; a replace of a met goal completed it first. */
private fun acceptedTag(
    disposition: GoalDisposition,
    presented: GoalSummary?,
): String =
    when (disposition) {
        GoalDisposition.ADOPT -> GoalEvidence.ADOPTED
        GoalDisposition.CONTINUE -> GoalEvidence.CONTINUED
        GoalDisposition.REVISE -> GoalEvidence.REVISED
        GoalDisposition.REPLACE ->
            if (presented?.progress?.conditionMet == true) GoalEvidence.COMPLETED_ADOPTED else GoalEvidence.REPLACED
        GoalDisposition.COMPLETE -> GoalEvidence.COMPLETED
        GoalDisposition.ABANDON -> GoalEvidence.ABANDONED
    }

/**
 * These bests with the cover [now] read recorded at the cell it was read at, kept to the
 * [MAX_GOAL_CELLS] most recent cells. A cell the agent was [displaced] into starts at the most
 * enclosed the goal has been anywhere, so cover lost to a flight and rebuilt where it landed is no
 * step (the r39 offline audit).
 */
private fun Map<List<Int>, CoverRank>.passedAt(
    now: GoalObservation?,
    displaced: Boolean = false,
): Map<List<Int>, CoverRank> {
    val rank = now?.cover?.rank ?: return this
    val cell = now.cell
    val floor = values.maxOrNull().takeIf { displaced && cell !in this }
    val passed = listOfNotNull(this[cell], rank, floor).max()
    return (this - setOf(cell) + (cell to passed)).entries.toList().takeLast(MAX_GOAL_CELLS).associate { it.toPair() }
}

/** Applies the lifecycle clearing rule to one appended record. */
fun GoalLedger.saw(record: BodyEventRecord) {
    if (record.event in CLEARING_EVENTS) clear(AgentId(record.agentId))
}

/** [log], with every record it takes also offered to [GoalLedger.saw] once it is written. */
fun GoalLedger.watching(log: BodyEventLog): BodyEventLog =
    BodyEventLog { record ->
        log.append(record)
        saw(record)
    }
