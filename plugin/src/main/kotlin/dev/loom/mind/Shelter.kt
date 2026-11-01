/*
 * The shelter goal: what a review sets when night nears and its agent stands open to it (r36: all
 * ten agents died at night in the open, every death under hostile attack, and no agent ever had a
 * goal that a hiding place could meet).
 *
 * A shelter goal names no item. Its success condition is that the server's ground seals the space
 * the agent stands in (`dev.loom.body.player.Cover`), and its steps are the existing one-shot
 * actions the cover reading names: the dig underfoot that deepens a pit, a placement of a solid
 * held stack that closes an open face, or a walk onto natural cover more enclosed than where the
 * agent stands. A step is credited only when the ground then shows the agent more enclosed than
 * the goal had yet been where it stands, and a move only when it lands more enclosed than where it
 * left, so a flight away and a walk back re-credit nothing. When nothing held can close the cover,
 * gathering a closer - a safe dig of a block in reach that yields a solid block, never one of the
 * shelter's own faces, roof or floor, or a collect of a solid drop - is a step of the shelter goal
 * itself, credited when the agent then holds more solid items (routine-v36: r39 alpha's dirt
 * subgoal took its shelter's identity twice and dug the shelter's own west wall for dirt).
 * Engine-free and stateless, like `Discovery.kt`; the ledger owns the state.
 */
package dev.loom.mind

import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.body.WorldClock
import dev.loom.contract.BlockFace
import dev.loom.contract.CoverProgress
import dev.loom.contract.DayPhase
import dev.loom.contract.EquipmentDestination
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalProgress
import dev.loom.contract.GoalSummary
import dev.loom.contract.Intent
import dev.loom.contract.IntentAction
import dev.loom.contract.Perception
import dev.loom.contract.StepEvidence
import dev.loom.contract.action
import dev.loom.contract.targetRef

/** How long before nightfall a shelter goal is offered: [WorldClock.SHELTER_LEAD_TICKS]. */
const val SHELTER_LEAD_TICKS: Long = WorldClock.SHELTER_LEAD_TICKS

/**
 * The most sites of natural cover a shelter step may walk onto, the most enclosed first;
 * `MAX_SHELTER_SITES` in the mind.
 */
const val MAX_SHELTER_SITES: Int = 4

/** A shelter goal's wording: the body's, like every goal's. */
const val SHELTER_STATEMENT: String = "seal the space you stand in against the night"

/** How enclosed a reading shows the agent: sealed first, then faces closed, then faces closable. */
data class CoverRank(
    val sealed: Boolean,
    val closed: Int,
    val closable: Int,
) : Comparable<CoverRank> {
    override fun compareTo(other: CoverRank): Int =
        compareValuesBy(this, other, { it.sealed }, { it.closed }, { it.closable })

    /** This rank as a shelter goal's progress states it. */
    val progress: CoverProgress get() = CoverProgress(closed.toLong(), closable.toLong(), sealed)
}

/**
 * What a review saw of its agent's cover: how enclosed it stands, whether a walk leads out, the
 * placements that would close an open face (support ref and side), the descent underfoot, the
 * solid stacks it holds by ref, the solid-block items sight could give, the standable blocks out
 * of its own column more enclosed than where it stands, by ref, and whether night is near.
 */
data class CoverObservation(
    val rank: CoverRank,
    val walkOut: Boolean,
    val closings: Set<Pair<String, BlockFace>> = emptySet(),
    val descend: String? = null,
    val solid: Set<String> = emptySet(),
    /** Of [solid], the stack in the main hand, which a placement must already be holding. */
    val solidHeld: Set<String> = emptySet(),
    val blockItems: Set<String> = emptySet(),
    val sites: Set<String> = emptySet(),
    val nightNear: Boolean = false,
    /** The blocks and drops whose gathering gives a closer, while faces can close and nothing solid is held. */
    val gathers: Set<String> = emptySet(),
    /** How many solid items the agent holds, which a gathering step must raise. */
    val solidCount: Long = 0,
    /** The items of the stacks in [solid], by name. */
    val solidItems: Set<String> = emptySet(),
) {
    /** Whether [intent] is a step a shelter goal may take now. */
    fun steps(intent: Intent): Boolean =
        when (intent) {
            is Intent.Dig -> intent.block == descend || intent.block in gathers
            is Intent.Collect -> intent.item in gathers
            is Intent.Place -> (intent.block to intent.face) in closings && intent.item in solidHeld
            is Intent.Equip -> intent.destination == EquipmentDestination.HAND && intent.item in toEquip
            is Intent.MoveTo -> intent.onto in sites
            else -> false
        }

    /** The solid stacks an equip may put in the main hand: every one, while none is held and a face can close. */
    val toEquip: Set<String> get() = if (closings.isEmpty() || solidHeld.isNotEmpty()) emptySet() else solid

    /** Whether any shelter step is offered now. */
    val stepped: Boolean
        get() =
            descend != null || (closings.isNotEmpty() && solid.isNotEmpty()) || sites.isNotEmpty() ||
                gathers.isNotEmpty()

    /** Whether a shelter goal may be set: night is near, the agent is not sealed, and a step exists. */
    val offered: Boolean get() = nightNear && !rank.sealed && stepped

    companion object {
        /** What [perception]'s cover reading shows, or `null` when it carried none. */
        fun of(perception: Perception): CoverObservation? {
            val cover = perception.selfState.cover ?: return null
            val closed = dev.loom.contract.MAX_COVER_FACES - cover.open.size
            val rank = CoverRank(cover.sealed, closed, cover.closings.size)
            val world = perception.selfState.world
            return CoverObservation(
                rank = rank,
                walkOut = cover.walkOut,
                closings = cover.closings.map { it.block to it.side }.toSet(),
                descend = cover.descend,
                solid = perception.selfState.inventorySummary.filter { it.solid == true }.map { it.ref }.toSet(),
                solidHeld =
                    perception.selfState.inventorySummary
                        .filter { it.solid == true && it.held == true }
                        .map { it.ref }
                        .toSet(),
                blockItems = cover.blockItems.toSet(),
                sites =
                    perception.surroundings
                        .filter { it.direction != dev.loom.contract.Bearing.HERE && (it.cover ?: 0) > closed }
                        // The most enclosed first, the nearest of those first, as sight lists them.
                        .sortedByDescending { it.cover }
                        .take(MAX_SHELTER_SITES)
                        .map { it.ref }
                        .toSet(),
                nightNear = world.dayPhase == DayPhase.NIGHT || world.ticksUntilNightfall <= SHELTER_LEAD_TICKS,
                // Narrowed as the mind narrows them, to a dig in reach that leaves a way out or a
                // collect with a route (the r39 offline audit: the two runtimes read different lists).
                gathers =
                    cover.gathers
                        .filter { ref -> perception.surroundings.any { it.ref == ref && stepNow(it) } }
                        .toSet(),
                solidCount = perception.selfState.inventorySummary.filter { it.solid == true }.sumOf { it.count },
                solidItems = perception.selfState.inventorySummary.filter { it.solid == true }.map { it.what }.toSet(),
            )
        }
    }
}

/** Whether [choice] sets a shelter goal. */
internal fun shelters(choice: GoalChoice?): Boolean = choice?.shelter == true

/** Whether [choice] adopts a resource subgoal under a held shelter goal. */
internal fun feeds(choice: GoalChoice?): Boolean = choice?.feeds == true

/**
 * Why a shelter choice, a choice about a held shelter goal, or a resource subgoal cannot stand
 * beside [intent], or `null`: a shelter set where none is offered or over a held one; a revise of
 * one, which has no item to revise; a completion of one the ground does not show sealed; a step of
 * one that is not a shelter step; a subgoal adopted where none can feed a shelter or for an item
 * that does not close one; and a subgoal replaced, which would drop the shelter it feeds.
 */
internal fun shelterRefusalOf(
    presented: GoalSummary?,
    choice: GoalChoice?,
    intent: Intent,
    observed: GoalObservation?,
): String? {
    val cover = observed?.cover
    val setting = shelters(choice)
    val unoffered = held(presented) || cover?.offered != true || choice?.successItem != null
    return GoalEvidence.SHELTER_NOT_OFFERED.takeIf { setting && unoffered }
        ?: heldRefusalOf(presented, choice, intent, cover)
        ?: subgoalRefusalOf(presented, choice, intent, cover)
}

/** Whether [presented] is a shelter goal. */
private fun held(presented: GoalSummary?): Boolean = presented?.shelter == true

/** Why a choice about a held or newly set shelter goal cannot stand beside [intent], or `null`. */
private fun heldRefusalOf(
    presented: GoalSummary?,
    choice: GoalChoice?,
    intent: Intent,
    cover: CoverObservation?,
): String? {
    val disposition = choice?.disposition
    val stepping = shelters(choice) || (held(presented) && disposition == GoalDisposition.CONTINUE)
    val met = presented?.progress?.conditionMet == true
    return when {
        held(presented) && disposition == GoalDisposition.REVISE -> GoalEvidence.REVISE_ITEM_CHANGED
        held(presented) && disposition == GoalDisposition.COMPLETE && !met -> GoalEvidence.COMPLETION_UNMET
        stepping && cover?.steps(intent) != true -> GoalEvidence.STEP_UNPRODUCTIVE
        else -> null
    }
}

/** Why a resource subgoal's adoption, replace or completion cannot stand beside [intent], or `null`. */
private fun subgoalRefusalOf(
    presented: GoalSummary?,
    choice: GoalChoice?,
    intent: Intent,
    cover: CoverObservation?,
): String? {
    val disposition = choice?.disposition
    val subgoal = presented?.feeds != null
    // A met subgoal resumes its shelter goal with this answer's step, which must be the shelter's.
    val resumesAstray =
        subgoal && disposition == GoalDisposition.COMPLETE && intent.action !in PASSIVE && cover?.steps(intent) != true
    return when {
        // From routine-v36 gathering a closer is the shelter's own step, and no subgoal is offered.
        feeds(choice) -> GoalEvidence.FEEDS_NOT_OFFERED
        subgoal && disposition == GoalDisposition.REPLACE -> GoalEvidence.SUBGOAL_REPLACED
        resumesAstray -> GoalEvidence.STEP_UNPRODUCTIVE
        else -> null
    }
}

/** The shelter goal a decision [requestId] sets, its perception taken at [capturedTick] and its step [intent]. */
internal fun shelterGoalOf(
    requestId: String,
    capturedTick: Long,
    intent: Intent,
): GoalSummary =
    GoalSummary(
        goalId = requestId,
        statement = SHELTER_STATEMENT,
        revision = 0,
        adoptedTick = capturedTick,
        step = stepOf(intent),
        stepAction = intent.action,
        stepRef = intent.targetRef,
        shelter = true,
    )

/**
 * The body-measured effect of a shelter goal's last step: met when the ground seals the agent in,
 * covered when an accepted step left it more enclosed than [reference] - the most the goal had yet
 * been where it now stands, and for a move the cover it left as well - and gainedInput when an
 * accepted step its affirming review offered as a gather left it holding more solid items. Moving
 * away and back, a refused step, or cover a survival response closed is credited to no step; the
 * condition still reads what the ground shows.
 */
internal fun shelterProgressOf(
    goal: GoalSummary,
    before: GoalObservation,
    observed: GoalObservation,
    step: StepRecord,
    priorCount: Long,
): GoalProgress {
    val rank = observed.cover?.rank
    val reference = step.best
    val improved = rank != null && (reference == null || rank > reference)
    val accepted = goal.lastStep?.outcome == EventTag.ACCEPTED
    // Carried elsewhere since the step was chosen, the step's own effect cannot be read where it stands.
    val covered =
        improved && accepted && goal.stepAction in SHELTER_ACTIONS && !displaced(goal, before, observed, step.overtaken)
    val gathered = !covered && accepted && gathered(goal, before, observed)
    // Cover the agent came to stand in by anything but its own accepted step - a flight, a fall, a
    // peer's block - advances nothing and resets no stall (the r39 offline audit).
    return GoalProgress(
        conditionMet = rank?.sealed == true,
        conditionHeld = 0,
        inventoryDelta = deltaOf(before.inventory, observed.inventory),
        conditionAdvanced = covered,
        stepAdvanced = covered || gathered,
        stepEvidence =
            when {
                covered -> StepEvidence.COVERED
                gathered -> StepEvidence.GAINED_INPUT
                else -> StepEvidence.NONE
            },
        stepsWithoutProgress = if (covered || gathered) 0 else priorCount + 1,
        cover = rank?.progress,
    )
}

/**
 * Whether [goal]'s last step was a gather its affirming review [before] offered, and left the agent
 * holding more solid items by [observed]: never a descent or any other dig that happened to give a
 * solid block.
 */
private fun gathered(
    goal: GoalSummary,
    before: GoalObservation,
    observed: GoalObservation,
): Boolean =
    goal.stepAction in GATHER_ACTIONS && goal.stepRef in before.cover?.gathers.orEmpty() &&
        (observed.cover?.solidCount ?: 0) > (before.cover?.solidCount ?: 0)

/** The actions a shelter step takes: a descent, a closing, or a walk onto natural cover. */
private val SHELTER_ACTIONS = setOf(IntentAction.DIG, IntentAction.PLACE, IntentAction.MOVE_TO)

/** The actions that gather a closer for a shelter: a dig for a solid block, or a collect of one. */
private val GATHER_ACTIONS = setOf(IntentAction.DIG, IntentAction.COLLECT)
