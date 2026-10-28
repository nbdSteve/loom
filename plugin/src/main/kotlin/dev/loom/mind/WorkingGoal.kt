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
import dev.loom.contract.DropFate
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalProgress
import dev.loom.contract.GoalSummary
import dev.loom.contract.HorizonGoal
import dev.loom.contract.HorizonGrounding
import dev.loom.contract.Intent
import dev.loom.contract.IntentAction
import dev.loom.contract.InventoryChange
import dev.loom.contract.MAX_INVENTORY_CHANGES
import dev.loom.contract.Observation
import dev.loom.contract.ObservationKind
import dev.loom.contract.Perception
import dev.loom.contract.SelfState
import dev.loom.contract.StepEvidence
import dev.loom.contract.isItemName
import kotlin.math.floor
import kotlin.math.sqrt

/** Capacity is summed in hundredths of an item, the unit a block's sampled mean is stated in. */
private const val HUNDRED = 100L

/** Distinct cells one goal remembers having been reviewed at; the oldest is forgotten past it. */
const val MAX_GOAL_CELLS: Int = 64

/**
 * How far off a dropped item can be collected with an approach, `MOVE_REACH_BLOCKS` in
 * `body/src/movement.js`: a drop further off gives nothing a goal may count on.
 */
const val COLLECT_REACH_BLOCKS = 32.0

/** `INTERACTION_REACH_BLOCKS` in `body/src/sensing.js`: eyes to a block's centre. */
const val INTERACTION_REACH_BLOCKS = 5.1

/** Mineflayer's player eye height, from which interaction reach is measured. */
const val EYE_HEIGHT_BLOCKS = 1.62

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
    /** The items each of those requests' drops held, by request; absent for one not told. */
    val droppedItems: Map<String, Set<String>> = emptyMap(),
    val gives: Map<String, Set<String>> = emptyMap(),
    /** The agent's own lying drops a collect can route to, by ref, with the item each holds. */
    val ownDrops: Map<String, String> = emptyMap(),
    /** The cells at the edge of sight this perception offered to walk to, by ref. */
    val explore: Set<String> = emptySet(),
    /** The blocks within the body's interaction reach of the eyes, by ref: dug where it stands. */
    val inReach: Set<String> = emptySet(),
    /** The items [capacity] counts only from a source recalled out of sight, not from sight. */
    val recalledOnly: Set<String> = emptySet(),
    /** The horizon goals the perception offered, by item: what may be asked beyond [capacity]. */
    val horizon: Map<String, HorizonGoal> = emptyMap(),
    /** The items a craft offered now makes: a recipe step, never sight handing an item over whole. */
    val craftable: Set<String> = emptySet(),
    /** The items the blocks under and beside the agent's feet give: its footing, guaranteed. */
    val footing: Set<String> = emptySet(),
    /** The survival responses this perception allows before any goal step. */
    val survival: SurvivalOffer = SurvivalOffer(),
    /**
     * The blocks in reach a dig can be counted on for, by ref, with the items each gives at least
     * once a dig on the server's sampled mean: a source worth staying for, where 13% grass is not
     * (r24 theta was refused a walk past grass for seeds the schema itself offered).
     */
    val productive: Map<String, Set<String>> = emptyMap(),
    /** The agent's cover, from the server's ground, when the perception read one (`Shelter.kt`). */
    val cover: CoverObservation? = null,
    /** How many of each item the agent's own drops have lying, wherever they lie: no objective's to ask. */
    val ownLying: Map<String, Long> = emptyMap(),
) {
    /** Whether a discovery goal may be set: cells at the edge of sight, and no horizon goal at all. */
    val discoveryOffered: Boolean get() = explore.isNotEmpty() && horizon.isEmpty()

    /**
     * Whether a discovery goal is met: sight shows a useful resource, or a source remembered out of
     * sight now grounds a goal (r24 zeta held a discovery goal beside a log recalled 6.1 blocks off).
     */
    val discoveryMet: Boolean
        get() =
            revealed.isNotEmpty() ||
                horizon.values.any { it.grounding == HorizonGrounding.RECALLED && it.what !in footing }

    /**
     * The useful resources sight shows: items no footing gives that sight can give now and that a
     * horizon goal is grounded in or made from. What a discovery goal is met by.
     */
    val revealed: Set<String>
        get() =
            horizon.values
                .flatMap { listOfNotNull(it.what, it.via) }
                .filter { it !in footing && inSight(it) > 0 }
                .toSet()

    /** The most of [item] a goal may ask: what is held and sight gives, or its horizon's most. */
    fun reachOf(item: String): Long = maxOf((inventory[item] ?: 0) + (capacity[item] ?: 0), horizon[item]?.most ?: 0)

    /**
     * Whether a goal of [item] would crowd out looking further: cells at the edge of sight are
     * offered, and no horizon goal is grounded in [item] or made from it.
     */
    fun crowdsExploration(item: String): Boolean =
        explore.isNotEmpty() && item !in horizon && horizon.values.none { it.via == item } && item !in craftable

    /**
     * Whether [intent] walks to the edge of sight while a block in reach already gives one of
     * [toward] (r23: epsilon walked away from two logs in dig reach).
     */
    fun looksPastASource(
        intent: Intent,
        toward: Set<String>,
    ): Boolean =
        intent is Intent.MoveTo && intent.onto in explore &&
            productive.values.any { items -> items.any { it in toward } }

    /** The block cell the agent stood in. */
    val cell: List<Int> get() = position.map { floor(it).toInt() }

    /** Whether a block in reach can be counted on to give one of [items] at a dig. */
    fun digsFor(items: Set<String>): Boolean = productive.values.any { gives -> gives.any(items::contains) }

    /** How many of [item] sight itself could give: none for an item only recalled out of sight. */
    fun inSight(item: String): Long = if (item in recalledOnly) 0 else capacity[item] ?: 0

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
            // In hundredths of an item: a drop or craft exactly, a block the mean of the server's
            // sampled rolls (r16: goals sized to every bush's most failed; r19: leaves counted at
            // the middle of their range as about a stick each gave one in 33 digs).
            val crafted =
                perception.selfState.craftingOptions.map { it.what to HUNDRED * it.resultCount * it.maxCrafts }
            val dug = digs.flatMap { it.yields.orEmpty() }.filter { it.most >= 1 }.map { it.what to it.perHundred }
            // A source seen recently and out of sight now counts, so a goal for it can be set and
            // walked toward (r20: no agent without a log in sight could set a log goal).
            val recalled = perception.recalled.map { it.what to it.perHundred }
            val reachable = drops.map { it.ref }.toSet()
            return GoalObservation(
                inventory = inventoryOf(perception.selfState),
                position = perception.selfState.position,
                capacity =
                    (drops.map { it.what to HUNDRED * (it.count ?: 1) } + crafted + dug + recalled)
                        .filter { (what, _) -> isItemName(what) && what != AIR_ITEM }
                        .groupBy({ it.first }, { it.second })
                        .mapValues { (_, hundredths) -> hundredths.sum() / HUNDRED }
                        .filterValues { it >= 1 },
                visible = seen.filter { it.visible }.map { it.ref }.toSet(),
                dropped = perception.selfState.ownDrops.map { it.requestId }.toSet(),
                droppedItems =
                    perception.selfState.ownDrops
                        .groupBy({ it.requestId }, { it.what })
                        .mapValues { (_, items) -> items.toSet() },
                gives =
                    seen
                        .filter { it.kind != ObservationKind.ENTITY }
                        .associate { seen -> seen.ref to (seen.yields?.map { it.what } ?: listOf(seen.what)).toSet() } +
                        perception.selfState.craftingOptions.associate { it.ref to setOf(it.what) },
                ownDrops =
                    perception.selfState.ownDrops
                        .filter { it.fate == DropFate.LYING }
                        .mapNotNull { drop -> drop.ref?.takeIf { it in reachable }?.let { it to drop.what } }
                        .toMap(),
                explore = perception.explore.map { it.ref }.toSet(),
                inReach =
                    seen
                        .filter { it.kind == ObservationKind.BLOCK && it.visible }
                        .filter { fromEyes(it) <= INTERACTION_REACH_BLOCKS }
                        .map { it.ref }
                        .toSet(),
                recalledOnly =
                    recalled.map { it.first }.toSet() -
                        (drops.map { it.what } + crafted.map { it.first } + dug.map { it.first }).toSet(),
                horizon = perception.horizon.associateBy { it.what },
                craftable = perception.selfState.craftingOptions.map { it.what }.toSet(),
                footing = footingOf(perception),
                survival = SurvivalOffer.of(perception),
                productive = productiveOf(digs),
                cover = CoverObservation.of(perception),
                ownLying =
                    perception.selfState.ownDrops
                        .filter { it.fate == DropFate.LYING }
                        .groupBy({ it.what }, { it.count })
                        .mapValues { (_, counts) -> counts.sum() },
            )
        }

        /** The visible dig-safe blocks in reach among [digs] that give an item at least once a dig, by ref. */
        private fun productiveOf(digs: List<Observation>): Map<String, Set<String>> =
            digs
                .filter { fromEyes(it) <= INTERACTION_REACH_BLOCKS }
                .associate { block ->
                    val sure = block.yields.orEmpty().filter { it.perHundred >= HUNDRED }
                    block.ref to sure.map { it.what }.toSet()
                }.filterValues { it.isNotEmpty() }

        /** The distance from the eyes to [seen], from its feet-relative reading, as the mind measures it. */
        private fun fromEyes(seen: Observation): Double {
            val horizontal =
                (seen.distance * seen.distance - seen.verticalOffset * seen.verticalOffset).coerceAtLeast(
                    0.0,
                )
            val rise = seen.verticalOffset - EYE_HEIGHT_BLOCKS
            return sqrt(horizontal + rise * rise)
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
    /**
     * Whether the step's target is itself a source of the goal's item out of dig reach, as a walk
     * must be to arrive anywhere a dig could not already reach.
     */
    val towardSource: Boolean = false,
    /** Whether the step walked to a cell at the edge of sight. */
    val explored: Boolean = false,
    /** The direct inputs of a recipe for the goal's item, whose gain is a typed step toward it. */
    val inputs: Set<String> = emptySet(),
    /** Whether this review comes straight after the step settled, as a reveal must. */
    val fresh: Boolean = true,
    /** For a shelter goal, the most enclosed the goal had yet been, which a step must pass. */
    val best: CoverRank? = null,
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
    return when {
        goal.discovery -> discoveryProgressOf(goal, before, observed, step, priorCount)
        goal.shelter -> shelterProgressOf(goal, before, observed, step.best, priorCount)
        else -> itemProgressOf(goal, before, observed, step, priorCount)
    }
}

/** [progressOf] for a goal whose success condition is holding an item. */
private fun itemProgressOf(
    goal: GoalSummary,
    before: GoalObservation,
    observed: GoalObservation,
    step: StepRecord,
    priorCount: Long,
): GoalProgress {
    val item = goal.successItem
    val held = item?.let { observed.inventory[it] } ?: 0
    val heldBefore = item?.let { before.inventory[it] } ?: 0
    val conditionAdvanced = held > heldBefore
    val evidence = stepEvidenceOf(goal, before, observed, step)
    val stepAdvanced = evidence != StepEvidence.NONE
    // A drop is the step's evidence, but not progress until it is held.
    val progressed = conditionAdvanced || (stepAdvanced && evidence != StepEvidence.DROPPED)
    // A drop of the goal's item still lying in reach is neither progress nor a stall: the next
    // step must collect it (r18: all three stalls fired on a dig that had just dropped two sticks).
    val awaitingCollect = evidence == StepEvidence.DROPPED && item != null && observed.ownDrops.containsValue(item)
    return GoalProgress(
        conditionMet = item != null && held >= (goal.successCount ?: Long.MAX_VALUE),
        conditionHeld = held,
        inventoryDelta = deltaOf(before.inventory, observed.inventory),
        conditionAdvanced = conditionAdvanced,
        stepAdvanced = stepAdvanced,
        stepEvidence = evidence,
        stepsWithoutProgress = stallCountOf(progressed, awaitingCollect, priorCount),
    )
}

/** The run of steps that advanced nothing after one that [progressed], or awaits its own collect. */
private fun stallCountOf(
    progressed: Boolean,
    awaitingCollect: Boolean,
    priorCount: Long,
): Long =
    when {
        progressed -> 0
        awaitingCollect -> priorCount
        else -> priorCount + 1
    }

/**
 * What showed the goal's typed step was carried out, by the body's own outcome for it and what
 * it changed. A walk or flight is evidence only when the body arrived, at a target in sight when
 * it was chosen, in a cell the goal had not been reviewed at; a world action only when the agent
 * then held more of the goal's item or of a direct input of a recipe for it, or when the server
 * attributed a drop of one of those to that step's own request, wherever the drop went after.
 * A dig that broke a block and dropped nothing advances nothing (r13 alpha dug terracotta four
 * times, credited every time), and another agent's drop in sight is no evidence of this agent's
 * step (r14 delta).
 */
private fun stepEvidenceOf(
    goal: GoalSummary,
    before: GoalObservation,
    observed: GoalObservation,
    step: StepRecord,
): StepEvidence {
    val accepted = goal.lastStep?.outcome == EventTag.ACCEPTED
    val rose = { item: String -> (observed.inventory[item] ?: 0) > (before.inventory[item] ?: 0) }
    // Only the goal's own item, or a direct input of a recipe for it, is evidence (r19: a sapling
    // dug for a stick goal, and a stick and an apple for a sapling goal, reset the stall count).
    val item = goal.successItem
    val toward = setOfNotNull(item) + step.inputs
    val dropped = droppedToward(goal, observed, toward)
    return when {
        !accepted -> StepEvidence.NONE
        goal.stepAction in MOVEMENT_STEPS -> movementEvidenceOf(goal, before, observed, step)
        goal.stepAction !in WORLD_STEPS -> StepEvidence.NONE
        item != null && rose(item) -> StepEvidence.GAINED
        step.inputs.any(rose) -> StepEvidence.GAINED_INPUT
        dropped -> StepEvidence.DROPPED
        else -> StepEvidence.NONE
    }
}

/**
 * Whether the server attributed a drop to [goal]'s last step's own request, of an item [toward]
 * names, or of an item it did not tell.
 */
private fun droppedToward(
    goal: GoalSummary,
    observed: GoalObservation,
    toward: Set<String>,
): Boolean {
    val request = goal.lastStep?.requestId ?: return false
    val told = observed.droppedItems[request]
    return request in observed.dropped && (told == null || told.any { it in toward })
}

/**
 * What showed a walk or flight step was carried out. A walk is evidence only onto a block that is
 * itself a source of the goal's item out of dig reach (r16: two one-block walks onto terracotta
 * were credited and reset the stall count; r18: all seven arrivals were onto bushes already in dig
 * reach), and a walk to the edge of sight only when it brought a source of the item into a sight
 * that showed none (r18: five goals were abandoned to look further).
 */
private fun movementEvidenceOf(
    goal: GoalSummary,
    before: GoalObservation,
    observed: GoalObservation,
    step: StepRecord,
): StepEvidence {
    val reached = step.reached == true
    val item = goal.successItem
    val arrived = reached && step.visible && step.towardSource && observed.cell !in step.visited
    val revealed =
        reached && step.explored && item != null &&
            before.inSight(item) == 0L && observed.inSight(item) > 0
    return when {
        arrived -> StepEvidence.ARRIVED
        revealed -> StepEvidence.REVEALED
        broughtWithinReach(before, observed, step, setOfNotNull(item) + step.inputs) -> StepEvidence.ARRIVED
        else -> StepEvidence.NONE
    }
}

/**
 * Whether a walk to the cell beside a source sight showed beyond dig reach arrived at it: a dig can
 * now be counted on for one of [toward] where none could before (r34: zeta's logs were in view and
 * out of reach, and no walk toward them was ever evidence).
 */
private fun broughtWithinReach(
    before: GoalObservation,
    observed: GoalObservation,
    step: StepRecord,
    toward: Set<String>,
): Boolean =
    step.reached == true && step.explored && observed.cell !in step.visited &&
        !before.digsFor(toward) && observed.digsFor(toward)

/** Every item whose held count changed, in name order, bounded to one per inventory slot. */
internal fun deltaOf(
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
    val item = goal.successItem
    return when {
        // A discovery goal is met by whatever sight shows now, however it came into view; only the
        // walk that revealed it was ever credited, so nothing here is.
        goal.discovery -> measured.copy(conditionMet = observed?.discoveryMet ?: measured.conditionMet)
        // A shelter goal is met by whatever the ground shows now; nothing is credited again.
        goal.shelter -> {
            val rank = observed?.cover?.rank
            measured.copy(
                conditionMet = rank?.sealed ?: measured.conditionMet,
                cover = rank?.progress ?: measured.cover,
            )
        }
        item == null -> measured
        else -> {
            val held = observed?.inventory?.get(item) ?: measured.conditionHeld
            measured.copy(conditionMet = held >= (goal.successCount ?: Long.MAX_VALUE), conditionHeld = held)
        }
    }
}

/** The dispositions that set a held goal's target anew, beside an adopt's first one. */
private val RETARGETING = setOf(GoalDisposition.REVISE, GoalDisposition.REPLACE)

/** The first field [choice]'s disposition requires that it did not give, or `null`. */
internal fun missingFieldOf(choice: GoalChoice): String? {
    val adopting = choice.disposition == GoalDisposition.ADOPT
    // A discovery goal names no item; it is held to its own rules (`Discovery.kt`).
    val worded = (adopting || choice.disposition in RETARGETING) && !discovers(choice) && !shelters(choice)
    // The step is the decision's own intent and the body words it, so no choice must state one.
    return when {
        worded && (choice.successItem == null || choice.successCount == null) -> GoalEvidence.SUCCESS_TARGET_MISSING
        !adopting && choice.stepResult == null -> GoalEvidence.STEP_RESULT_MISSING
        else -> null
    }
}
