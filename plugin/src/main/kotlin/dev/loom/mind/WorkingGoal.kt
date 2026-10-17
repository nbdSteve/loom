/*
 * The working-goal half of the goal ledger: what a review saw that a step can change, how a
 * step's effect is measured from two such readings, and which fields each goal choice needs.
 *
 * Engine-free and stateless, so every rule is a function of its arguments; the ledger owns the
 * state and calls these.
 *
 * Progress is evidence that advanced the goal's typed success condition or its typed step, and
 * nothing else. The r11 audit found 24 of 28 credited steps were the body merely moving - a
 * shuffle between two bushes, a fall after digging the block underfoot - which the mind itself
 * judged noProgress, so the stall bound never fired. Here a move is credited only as a walk or
 * flight step that arrived somewhere the goal had not been, and the success condition is the
 * held count of the item it names, measured from the agent's own inventory.
 *
 * A step is the decision's own intent, typed by the ledger rather than claimed by the mind, and
 * a dig is credited for a drop only when the server attributed that drop to the dig's own request
 * (the r14 audit: delta was credited for a drop beta's dig made, and three of four collects took
 * another agent's drop). A drop is credited as the step's evidence but is not progress: until the
 * agent holds it, it counts toward the stall bound like any other step that advanced nothing (the
 * r15 audit: iota dug seven times, was credited five drops and never held a stick). A goal must be
 * one the agent could still meet from what it holds and what is in sight - by adopt, continue and
 * revise alike - one it has not met already, and pursued only by a dig or collect that can give
 * its item.
 */
package dev.loom.mind

import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.contract.AIR_ITEM
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalProgress
import dev.loom.contract.GoalSummary
import dev.loom.contract.IntentAction
import dev.loom.contract.InventoryChange
import dev.loom.contract.MAX_INVENTORY_CHANGES
import dev.loom.contract.ObservationKind
import dev.loom.contract.Perception
import dev.loom.contract.SelfState
import dev.loom.contract.StepEvidence
import dev.loom.contract.isItemName
import kotlin.math.floor

/** Distinct cells one goal remembers having been reviewed at; the oldest is forgotten past it. */
const val MAX_GOAL_CELLS: Int = 64

/**
 * How far off a dropped item can be collected with an approach, `MOVE_REACH_BLOCKS` in
 * `body/src/movement.js`: a drop further off gives nothing a goal may count on.
 */
const val COLLECT_REACH_BLOCKS = 32.0

/** Steps carried out by moving the body, credited only on arrival somewhere new to the goal. */
private val MOVEMENT_STEPS = setOf(IntentAction.MOVE_TO, IntentAction.FLEE)

/** Steps carried out by acting on the world, credited when the body carried them out. */
private val WORLD_STEPS =
    setOf(
        IntentAction.EAT,
        IntentAction.DIG,
        IntentAction.COLLECT,
        IntentAction.PLACE,
        IntentAction.EQUIP,
        IntentAction.CRAFT,
        IntentAction.ATTACK,
        IntentAction.SLEEP,
    )

/**
 * What a review saw that a step can change or a goal can name: the agent's inventory by item
 * and count, its place, how many more of each item it could get from what is in sight - a drop
 * within collecting reach the body has found a route to, craftable, or the most a visible block
 * it may dig can drop - the refs that perception showed in sight, the requests whose dig the
 * server saw drop something for this agent, and which items each block and dropped-item handle
 * can give. Compared between the review that affirmed a goal and the next.
 */
data class GoalObservation(
    val inventory: Map<String, Long>,
    val position: List<Double>,
    val capacity: Map<String, Long> = emptyMap(),
    val visible: Set<String> = emptySet(),
    val dropped: Set<String> = emptySet(),
    val gives: Map<String, Set<String>> = emptyMap(),
) {
    /** The block cell the agent stood in. */
    val cell: List<Int> get() = position.map { floor(it).toInt() }

    companion object {
        fun of(state: SelfState): GoalObservation =
            GoalObservation(inventory = inventoryOf(state), position = state.position)

        fun of(perception: Perception): GoalObservation {
            val seen = perception.surroundings
            // r15: an unreachable stick stood in two agents' capacity for the whole run.
            val drops =
                seen.filter { it.kind == ObservationKind.DROPPED_ITEM && it.routeRefused != true }
                    .filter { it.distance <= COLLECT_REACH_BLOCKS }
            val digs = seen.filter { it.kind == ObservationKind.BLOCK && it.visible && it.digSafe == true }
            val crafted = perception.selfState.craftingOptions.map { it.what to it.resultCount * it.maxCrafts }
            val dug = digs.flatMap { it.yields.orEmpty() }.map { it.what to it.most }
            return GoalObservation(
                inventory = inventoryOf(perception.selfState),
                position = perception.selfState.position,
                capacity =
                    (drops.map { it.what to (it.count ?: 1) } + crafted + dug)
                        .filter { (what, _) -> isItemName(what) && what != AIR_ITEM }
                        .groupBy({ it.first }, { it.second })
                        .mapValues { (_, counts) -> counts.sum() },
                visible = seen.filter { it.visible }.map { it.ref }.toSet(),
                dropped = perception.selfState.ownDrops.map { it.requestId }.toSet(),
                gives =
                    seen
                        .filter { it.kind != ObservationKind.ENTITY }
                        .associate { seen -> seen.ref to (seen.yields?.map { it.what } ?: listOf(seen.what)).toSet() },
            )
        }

        private fun inventoryOf(state: SelfState): Map<String, Long> =
            state.inventorySummary.groupBy({ it.what }, { it.count }).mapValues { (_, counts) -> counts.sum() }
    }
}

/** What the ledger knows about the goal's last step beyond the step itself. */
internal data class StepRecord(
    /** Every cell the goal has been reviewed at before now. */
    val visited: Set<List<Int>>,
    /** Whether the step's ref was in sight in the perception that chose it. */
    val visible: Boolean,
    /** For a walk or flight, whether the body reported arriving. */
    val reached: Boolean?,
)

/**
 * The body-measured effect of the goal's last step: [before] is what the goal's affirming review
 * saw and [observed] what this review sees. `null` when either reading is missing. [priorCount]
 * is the run of steps that advanced nothing before this one.
 */
internal fun progressOf(
    goal: GoalSummary,
    before: GoalObservation?,
    observed: GoalObservation?,
    step: StepRecord,
    priorCount: Long,
): GoalProgress? {
    if (before == null || observed == null) return null
    val item = goal.successItem
    val held = item?.let { observed.inventory[it] } ?: 0
    val heldBefore = item?.let { before.inventory[it] } ?: 0
    val conditionAdvanced = held > heldBefore
    val evidence = stepEvidenceOf(goal, before, observed, step)
    val stepAdvanced = evidence != StepEvidence.NONE
    // A drop is the step's evidence, but not progress until it is held.
    val progressed = conditionAdvanced || (stepAdvanced && evidence != StepEvidence.DROPPED)
    return GoalProgress(
        conditionMet = item != null && held >= (goal.successCount ?: Long.MAX_VALUE),
        conditionHeld = held,
        inventoryDelta = deltaOf(before.inventory, observed.inventory),
        conditionAdvanced = conditionAdvanced,
        stepAdvanced = stepAdvanced,
        stepEvidence = evidence,
        stepsWithoutProgress = if (progressed) 0 else priorCount + 1,
    )
}

/**
 * What showed the goal's typed step was carried out, by the body's own outcome for it and what
 * it changed. A walk or flight is evidence only when the body arrived, at a target in sight when
 * it was chosen, in a cell the goal had not been reviewed at; a world action only when the agent
 * then held more of something, or when the server attributed a drop to that step's own request,
 * wherever the drop went after. A dig that broke a block and dropped nothing advances nothing
 * (r13 alpha dug terracotta four times, credited every time), and another agent's drop in sight
 * is no evidence of this agent's step (r14 delta).
 */
private fun stepEvidenceOf(
    goal: GoalSummary,
    before: GoalObservation,
    observed: GoalObservation,
    step: StepRecord,
): StepEvidence {
    val accepted = goal.lastStep?.outcome == EventTag.ACCEPTED
    val gained = observed.inventory.any { (what, count) -> count > (before.inventory[what] ?: 0) }
    val dropped = goal.lastStep?.requestId?.let { it in observed.dropped } == true
    val arrived = step.reached == true && step.visible && observed.cell !in step.visited
    return when {
        !accepted -> StepEvidence.NONE
        goal.stepAction in MOVEMENT_STEPS -> if (arrived) StepEvidence.ARRIVED else StepEvidence.NONE
        goal.stepAction !in WORLD_STEPS -> StepEvidence.NONE
        gained -> StepEvidence.GAINED
        dropped -> StepEvidence.DROPPED
        else -> StepEvidence.NONE
    }
}

/** Every item whose held count changed, in name order, bounded to one per inventory slot. */
private fun deltaOf(
    before: Map<String, Long>,
    after: Map<String, Long>,
): List<InventoryChange> =
    (before.keys + after.keys)
        .sorted()
        .mapNotNull { what ->
            val change = (after[what] ?: 0) - (before[what] ?: 0)
            if (change == 0L) null else InventoryChange(what, change)
        }.take(MAX_INVENTORY_CHANGES)

/**
 * What the measurement of a goal's step that a review already measured shows at a later review:
 * the condition as it is now, and nothing measured again. A step is measured once; a review whose
 * answer did not take a new step must not re-credit the old one (r15: seven re-measurements, three
 * re-credits) nor count it twice toward the stall bound.
 */
internal fun remeasured(
    goal: GoalSummary,
    measured: GoalProgress,
    observed: GoalObservation?,
): GoalProgress {
    val item = goal.successItem ?: return measured
    val held = observed?.inventory?.get(item) ?: measured.conditionHeld
    return measured.copy(conditionMet = held >= (goal.successCount ?: Long.MAX_VALUE), conditionHeld = held)
}

/** The goal's wording, from its typed success condition: the total of the item to hold. */
internal fun statementOf(
    item: String,
    count: Long,
): String = "hold $count $item"

/** The first field [choice]'s disposition requires that it did not give, or `null`. */
internal fun missingFieldOf(choice: GoalChoice): String? {
    val adopting = choice.disposition == GoalDisposition.ADOPT
    val worded = adopting || choice.disposition == GoalDisposition.REVISE
    val stepped = worded || choice.disposition == GoalDisposition.CONTINUE
    return when {
        worded && (choice.successItem == null || choice.successCount == null) -> GoalEvidence.SUCCESS_TARGET_MISSING
        stepped && choice.step == null -> GoalEvidence.STEP_MISSING
        !adopting && choice.stepResult == null -> GoalEvidence.STEP_RESULT_MISSING
        else -> null
    }
}
