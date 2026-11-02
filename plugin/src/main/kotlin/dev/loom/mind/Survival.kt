/*
 * The survival responses a goal review may take before any goal step, with no goal choice: a
 * flight from or an attack on a hunter near enough to matter, and a meal at or below the hunger
 * threshold. A held or mandatory goal - a discovery goal above all - is deferred by one, never
 * answered: it is neither credited nor stalled by the response, and the review is owed again once
 * the response settles. Mirrors `OfferedRefs::survives` in `cognition/src/offered.rs`.
 */
package dev.loom.mind

import dev.loom.contract.BlockFace
import dev.loom.contract.EquipmentDestination
import dev.loom.contract.Intent
import dev.loom.contract.ObservationKind
import dev.loom.contract.Perception
import dev.loom.contract.Trigger

/** The vanilla food level at or below which a goal review may eat before any goal step. */
const val HUNGRY_FOOD_LEVEL: Long = 7

/**
 * The vanilla food level below which a player no longer heals: at or below [LOW_HEALTH] and under
 * it, a stack the server names food is a meal on any trigger (r33: every agent's food fell below
 * 18 and stayed there). `HEALING_FOOD_LEVEL` in the mind.
 */
const val HEALING_FOOD_LEVEL: Long = 18

/** How near a hunter in sight is danger enough to answer first; `DANGER_SIGHT_BLOCKS` in the mind. */
const val DANGER_SIGHT_BLOCKS: Double = 16.0

/** How near an unseen hunter is; `DANGER_UNSEEN_BLOCKS` in the mind. */
const val DANGER_UNSEEN_BLOCKS: Double = 8.0

/** What a perception offers a survival response: the hunters near enough, and the meals while hungry. */
data class SurvivalOffer(
    val hunters: Set<String> = emptySet(),
    val meals: Set<String> = emptySet(),
    /** The hunters that are immediate danger, beside which only a survival response is taken. */
    val immediate: Set<String> = emptySet(),
    /** Whether a flight can run at all: false where no short walk leads out (`Cover.walkOut`). */
    val fleeable: Boolean = true,
    /**
     * Where no flight can run, the placements of a solid held stack that close an open face of the
     * agent's own cover, by support ref, side and stack: shutting a hunter out is the response.
     */
    val closings: Set<Triple<String, BlockFace, String>> = emptySet(),
    /** Where no flight can run and no solid stack is in hand, the solid stacks an equip may ready. */
    val readying: Set<String> = emptySet(),
) {
    /**
     * Whether [intent] is withheld: a hunter is immediate danger and [intent] is no survival
     * response (r24: seven reviews beside a near hunter dug, crafted or walked).
     */
    fun withholds(intent: Intent): Boolean =
        immediate.isNotEmpty() && !(intent is Intent.Flee && fleeable) && !survives(intent)

    /** Whether [intent] is one of the survival responses this offer allows. */
    fun survives(intent: Intent): Boolean =
        when (intent) {
            is Intent.Flee -> fleeable && intent.from in hunters
            is Intent.Attack -> intent.target in hunters
            is Intent.Eat -> intent.ref in meals
            is Intent.Place -> Triple(intent.block, intent.face, intent.item) in closings
            // Readying the solid block that closes the cover, where none is in hand yet.
            is Intent.Equip -> intent.destination == EquipmentDestination.HAND && intent.item in readying
            else -> false
        }

    companion object {
        fun of(perception: Perception): SurvivalOffer {
            val cover = perception.selfState.cover
            // Sealed in, no hunter can reach the agent and no flight can leave (`Cover.kt`).
            if (cover?.sealed == true) return SurvivalOffer(meals = mealsOf(perception))
            // The creature that just hurt the agent is immediate danger wherever it stands, as the
            // mind offers it (`OfferedRefs::of`, v32 plains rehearsal 15).
            val attacker = attackerOf(perception)
            // Where no short walk leads out no flight can run; closing the cover is the response,
            // and with nothing to close it with no response is due before the agent's own choice.
            val trapped = cover?.walkOut == false
            val reading = CoverObservation.of(perception)
            val closings = if (trapped) closingsOf(perception, reading) else emptySet()
            val readying = if (trapped) reading?.toEquip.orEmpty() else emptySet()
            return SurvivalOffer(
                fleeable = !trapped,
                closings = closings,
                readying = readying,
                immediate =
                    if (trapped && closings.isEmpty() && readying.isEmpty()) {
                        emptySet()
                    } else {
                        immediateOf(perception) + attacker
                    },
                hunters =
                    perception.surroundings
                        .filter { it.kind == ObservationKind.ENTITY && it.hunts == true }
                        .filter { it.distance <= if (it.visible) DANGER_SIGHT_BLOCKS else DANGER_UNSEEN_BLOCKS }
                        .map { it.ref }
                        .toSet() + attacker,
                meals = mealsOf(perception),
            )
        }

        /** The hunters that are immediate danger, by how near each stands. */
        private fun immediateOf(perception: Perception): Set<String> =
            perception.surroundings
                .filter { it.kind == ObservationKind.ENTITY && it.hunts == true }
                .filter {
                    // At low health every hunter near enough to answer is immediate (r27 theta).
                    it.distance <=
                        if (perception.selfState.health <= LOW_HEALTH) {
                            if (it.visible) DANGER_SIGHT_BLOCKS else DANGER_UNSEEN_BLOCKS
                        } else {
                            immediateReachOf(it, perception)
                        }
                }.map { it.ref }
                .toSet()

        /** Each closing of the agent's cover paired with the solid stack in its main hand. */
        private fun closingsOf(
            perception: Perception,
            cover: CoverObservation?,
        ): Set<Triple<String, BlockFace, String>> =
            perception.selfState.cover?.closings.orEmpty()
                .flatMap { closing -> cover?.solidHeld.orEmpty().map { Triple(closing.block, closing.side, it) } }
                .toSet()

        /** Every stack while hungry, as before, else only food while hurt and not healing. */
        private fun mealsOf(perception: Perception): Set<String> {
            val state = perception.selfState
            val stacks =
                when {
                    state.food <= HUNGRY_FOOD_LEVEL -> state.inventorySummary
                    state.health <= LOW_HEALTH && state.food < HEALING_FOOD_LEVEL ->
                        state.inventorySummary.filter { it.edible == true }
                    else -> emptyList()
                }
            return stacks.map { it.ref }.toSet()
        }

        /** The attacker a damage trigger names, as a set of at most the one ref the scene shows. */
        private fun attackerOf(perception: Perception): Set<String> {
            val ref = (perception.trigger as? Trigger.Damaged)?.attackerRef ?: return emptySet()
            return setOfNotNull(
                ref.takeIf { perception.surroundings.any { it.kind == ObservationKind.ENTITY && it.ref == ref } },
            )
        }
    }
}

/**
 * Whether [intent] is a survival response a goal review may take with no goal choice, deferring
 * the goal. A flight is always one, as it was before hunters were offered by name.
 */
internal fun GoalObservation?.defers(intent: Intent): Boolean =
    intent is Intent.Flee || this?.survival?.survives(intent) == true

/** How near an unseen hunter is immediate danger; `IMMEDIATE_UNSEEN_BLOCKS` in the mind. */
const val IMMEDIATE_UNSEEN_BLOCKS: Double = 4.0

/**
 * How near [seen] must be to be immediate danger: anywhere near when the agent was just hurt, a
 * ranged hunter in sight within bow range, another in sight within pivotal range, and one unseen
 * within [IMMEDIATE_UNSEEN_BLOCKS]. Mirrors `OfferedRefs::sighted` in the mind.
 */
private fun immediateReachOf(
    seen: dev.loom.contract.Observation,
    perception: Perception,
): Double =
    when {
        perception.trigger is dev.loom.contract.Trigger.Damaged ->
            if (seen.visible) DANGER_SIGHT_BLOCKS else DANGER_UNSEEN_BLOCKS
        !seen.visible -> IMMEDIATE_UNSEEN_BLOCKS
        seen.what in RANGED_HUNTERS -> RANGED_THREAT_BLOCKS
        else -> dev.loom.body.PIVOTAL_THREAT_DISTANCE_BLOCKS
    }
