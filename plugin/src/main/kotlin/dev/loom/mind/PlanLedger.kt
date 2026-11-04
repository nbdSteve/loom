/*
 * Each agent's model-owned plan and its latest actions, kept between calls.
 *
 * The mind is stateless per request, so the plan is held here and shown on every call with the
 * agent's last [MAX_RECENT_ACTIONS] actions. Only a planning call (`Trigger.PlanReview`) sets the
 * objective and subgoals; a step call continues, marks its step done or asks for a replan. A
 * planning call is due at start, when a subgoal's steps are all done, when a step call asks for a
 * replan, and every [PLAN_CHECK_IN_TICKS]. The ledger applies exactly the update a decision names
 * and records it; it never judges or rewrites a plan.
 */
package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.PlanEvidence
import dev.loom.contract.Decision
import dev.loom.contract.ItemChange
import dev.loom.contract.MAX_PLAN_STEPS
import dev.loom.contract.MAX_RECENT_ACTIONS
import dev.loom.contract.Perception
import dev.loom.contract.Plan
import dev.loom.contract.PlanCause
import dev.loom.contract.PlanUpdate
import dev.loom.contract.RecentAction
import dev.loom.contract.Trigger

/** Server ticks between planning check-ins on a plan nothing else replanned. */
const val PLAN_CHECK_IN_TICKS: Long = 6000

/** Server ticks before a planning call that went unanswered is asked again. */
const val PLAN_RETRY_TICKS: Long = 200

/** Agents the ledger keeps plans for; matches the configured fleet bound. */
private const val MAX_PLAN_AGENTS = 16

/** Asked requests one agent may have awaiting an answer; the oldest is dropped past it. */
private const val MAX_ASKED = 8

private const val OBJECTIVE_CHARS = 200
private const val ITEM_CHARS = 160
private const val REASON_CHARS = 200

/** Thread-safe: every method is synchronized. */
class PlanLedger {
    private class AgentPlan {
        var plan: Plan? = null
        val recent = ArrayDeque<RecentAction>()
        val asked = LinkedHashMap<String, Map<String, Long>>()

        /** The inventory before the newest recent action, until a perception shows what it changed. */
        var before: Map<String, Long>? = null

        /** Why a planning call is owed, other than having no plan or a check-in falling due. */
        var owed: Trigger.PlanReview? = null
        var plannedTick: Long = 0
        var askedTick: Long? = null
    }

    private val agents = mutableMapOf<AgentId, AgentPlan>()

    private fun of(agentId: AgentId): AgentPlan? =
        agents[agentId] ?: AgentPlan().takeIf { agents.size < MAX_PLAN_AGENTS }?.also { agents[agentId] = it }

    /** [perception] with [agentId]'s plan and recent actions, the inventory change of its last action measured. */
    @Synchronized
    fun shown(
        agentId: AgentId,
        perception: Perception,
    ): Perception {
        val agent = of(agentId) ?: return perception
        val now = inventoryOf(perception)
        agent.before?.let { before ->
            val last = agent.recent.removeLastOrNull()
            if (last != null) agent.recent.addLast(last.copy(changes = changesOf(before, now)))
            agent.before = null
        }
        agent.asked[perception.requestId] = now
        while (agent.asked.size > MAX_ASKED) agent.asked.remove(agent.asked.keys.first())
        return perception.copy(plan = agent.plan, recent = agent.recent.toList())
    }

    /**
     * Applies [decision]'s plan update for [agentId], answered to a planning call when [planning], at
     * server tick [tick], and answers what it did. Only a planning call's plan is taken.
     */
    @Synchronized
    fun resolve(
        agentId: AgentId,
        decision: Decision,
        planning: Boolean,
        tick: Long,
    ): PlanEvidence? {
        val agent = of(agentId) ?: return null
        val update = decision.plan ?: PlanUpdate.Continue
        val held = agent.plan
        val (next, evidence) =
            if (planning) planned(held, update) else stepped(held, update)
        agent.plan = next
        if (planning && evidence.applied) {
            agent.owed = null
            agent.plannedTick = tick
            agent.askedTick = null
        }
        if (!planning && evidence.applied) agent.owed = owedAfter(next, update)
        return evidence.copy(
            objective = next?.objective,
            subgoal = next?.subgoals?.getOrNull(next.subgoal.toInt()),
            step = next?.steps?.getOrNull(next.step.toInt()),
        )
    }

    /** The planning call [agentId] owes at [tick], once per [PLAN_RETRY_TICKS]; `null` when none is due. */
    @Synchronized
    fun due(
        agentId: AgentId,
        tick: Long,
    ): Trigger.PlanReview? {
        val agent = of(agentId)
        val trigger =
            when {
                agent == null -> null
                agent.plan == null -> Trigger.PlanReview(PlanCause.START)
                agent.owed != null -> agent.owed
                tick - agent.plannedTick >= PLAN_CHECK_IN_TICKS -> Trigger.PlanReview(PlanCause.CHECK_IN)
                else -> null
            }
        val asked = agent?.askedTick
        val waiting = asked != null && tick - asked < PLAN_RETRY_TICKS
        return trigger?.takeIf { !waiting }?.also { agent?.askedTick = tick }
    }

    /** Records that [requestId]'s [action] ended as [outcome]; its inventory change is measured at the next call. */
    @Synchronized
    fun acted(
        agentId: AgentId,
        requestId: String,
        action: String,
        outcome: String,
    ) {
        val agent = agents[agentId] ?: return
        agent.recent.addLast(RecentAction(action.take(ACTION_CHARS), outcome.take(OUTCOME_CHARS)))
        while (agent.recent.size > MAX_RECENT_ACTIONS) agent.recent.removeFirst()
        agent.before = agent.asked.remove(requestId)
    }

    /** Forgets [agentId]'s plan and actions. */
    @Synchronized
    fun clear(agentId: AgentId) {
        agents.remove(agentId)
    }
}

private const val ACTION_CHARS = 32
private const val OUTCOME_CHARS = 64

private fun tagOf(update: PlanUpdate): String =
    when (update) {
        PlanUpdate.Continue -> "continue"
        PlanUpdate.StepDone -> "stepDone"
        is PlanUpdate.Replan -> "replan"
        is PlanUpdate.ReplacePlan -> "replacePlan"
    }

/** A step call's [update] to [held]: continue, a step done, or a replan asked; never a new plan. */
private fun stepped(
    held: Plan?,
    update: PlanUpdate,
): Pair<Plan?, PlanEvidence> {
    val tag = tagOf(update)
    return when (update) {
        PlanUpdate.Continue -> held to PlanEvidence(tag, applied = held != null)
        PlanUpdate.StepDone ->
            if (held == null || held.step >= held.steps.size) {
                held to PlanEvidence(tag, applied = false)
            } else {
                val step = held.step + 1
                // The last step done finishes the current subgoal.
                val finished = step >= held.steps.size && held.subgoal < held.subgoals.size
                val next = held.copy(step = step, subgoal = if (finished) held.subgoal + 1 else held.subgoal)
                next to PlanEvidence(tag, true, done = held.steps[held.step.toInt()])
            }
        is PlanUpdate.Replan -> held to PlanEvidence(tag, applied = true, reason = update.reason.take(REASON_CHARS))
        is PlanUpdate.ReplacePlan ->
            held to PlanEvidence(tag, applied = false, reason = update.reason.take(REASON_CHARS))
    }
}

/** The planning call owed after a step call's applied [update] left [plan]. */
private fun owedAfter(
    plan: Plan?,
    update: PlanUpdate,
): Trigger.PlanReview? =
    when {
        update is PlanUpdate.Replan -> Trigger.PlanReview(PlanCause.BLOCKED, update.reason.take(REASON_CHARS))
        plan != null && plan.step >= plan.steps.size -> Trigger.PlanReview(PlanCause.SUBGOAL_DONE)
        else -> null
    }

/**
 * A planning call's [update] to [held]: only a new plan is taken, its subgoals after those [held]
 * already finished, so the tree keeps its done marks.
 */
private fun planned(
    held: Plan?,
    update: PlanUpdate,
): Pair<Plan?, PlanEvidence> {
    val tag = tagOf(update)
    val replacing = update as? PlanUpdate.ReplacePlan
    val objective = replacing?.objective?.trim()?.take(OBJECTIVE_CHARS).orEmpty()
    val subgoals = bounded(replacing?.subgoals.orEmpty(), ITEM_CHARS)
    val steps = bounded(replacing?.steps.orEmpty(), ITEM_CHARS)
    val reason = replacing?.reason?.take(REASON_CHARS)
    val finished =
        held?.takeIf { it.objective == objective }?.let { it.subgoals.take(it.subgoal.toInt()) }.orEmpty()
    val kept = finished.takeLast((MAX_PLAN_STEPS - subgoals.size).coerceAtLeast(0))
    return if (objective.isEmpty() || subgoals.isEmpty() || steps.isEmpty()) {
        held to PlanEvidence(tag, false, reason = reason)
    } else {
        Plan(objective, kept + subgoals, kept.size.toLong(), steps, 0) to PlanEvidence(tag, true, reason = reason)
    }
}

private fun bounded(
    items: List<String>,
    chars: Int,
): List<String> = items.map { it.trim().take(chars) }.filter { it.isNotEmpty() }.take(MAX_PLAN_STEPS)

private fun inventoryOf(perception: Perception): Map<String, Long> =
    perception.selfState.inventorySummary
        .groupBy { it.what }
        .mapValues { (_, stacks) -> stacks.sumOf { it.count } }

private fun changesOf(
    before: Map<String, Long>,
    now: Map<String, Long>,
): List<ItemChange> =
    (before.keys + now.keys)
        .sorted()
        .mapNotNull { item ->
            val delta = (now[item] ?: 0) - (before[item] ?: 0)
            ItemChange(item, delta).takeIf { delta != 0L }
        }.take(MAX_PLAN_STEPS)
