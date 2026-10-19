/*
 * The survival responses a goal review may take before any goal step, with no goal choice: a
 * flight from or an attack on a hunter near enough to matter, and a meal at or below the hunger
 * threshold. A held or mandatory goal - a discovery goal above all - is deferred by one, never
 * answered: it is neither credited nor stalled by the response, and the review is owed again once
 * the response settles. Mirrors `OfferedRefs::survives` in `cognition/src/offered.rs`.
 */
package dev.loom.mind

import dev.loom.contract.Intent
import dev.loom.contract.ObservationKind
import dev.loom.contract.Perception

/** The vanilla food level at or below which a goal review may eat before any goal step. */
const val HUNGRY_FOOD_LEVEL: Long = 7

/** How near a hunter in sight is danger enough to answer first; `DANGER_SIGHT_BLOCKS` in the mind. */
const val DANGER_SIGHT_BLOCKS: Double = 16.0

/** How near an unseen hunter is; `DANGER_UNSEEN_BLOCKS` in the mind. */
const val DANGER_UNSEEN_BLOCKS: Double = 8.0

/** What a perception offers a survival response: the hunters near enough, and the meals while hungry. */
data class SurvivalOffer(
    val hunters: Set<String> = emptySet(),
    val meals: Set<String> = emptySet(),
) {
    /** Whether [intent] is one of the survival responses this offer allows. */
    fun survives(intent: Intent): Boolean =
        when (intent) {
            is Intent.Flee -> intent.from in hunters
            is Intent.Attack -> intent.target in hunters
            is Intent.Eat -> intent.ref in meals
            else -> false
        }

    companion object {
        fun of(perception: Perception): SurvivalOffer =
            SurvivalOffer(
                hunters =
                    perception.surroundings
                        .filter { it.kind == ObservationKind.ENTITY && it.hunts == true }
                        .filter { it.distance <= if (it.visible) DANGER_SIGHT_BLOCKS else DANGER_UNSEEN_BLOCKS }
                        .map { it.ref }
                        .toSet(),
                meals =
                    perception.selfState.inventorySummary
                        .takeIf { perception.selfState.food <= HUNGRY_FOOD_LEVEL }
                        .orEmpty()
                        .map { it.ref }
                        .toSet(),
            )
    }
}

/**
 * Whether [intent] is a survival response a goal review may take with no goal choice, deferring
 * the goal. A flight is always one, as it was before hunters were offered by name.
 */
internal fun GoalObservation?.defers(intent: Intent): Boolean =
    intent is Intent.Flee || this?.survival?.survives(intent) == true
