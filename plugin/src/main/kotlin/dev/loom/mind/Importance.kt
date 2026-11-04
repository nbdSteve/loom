/*
 * How important a decision is, decided by the body's side of the boundary.
 *
 * Importance is what drives model routing on the mind side, so classifying it
 * here - above the body port, below the bridge - is deliberate. It is the body
 * that knows what just happened to it; letting the mind decide its own tier would
 * make cost a function of the mind's judgement, and letting the adapter decide it
 * would tie the tiering to one body flavor.
 *
 * The classification is total over `Trigger` and has no default branch, so adding
 * a trigger to the contract will not compile until its tier has been chosen. That
 * is the intent: a new event silently defaulting to a tier is how routine traffic
 * ends up on an expensive model.
 */
package dev.loom.mind

import dev.loom.body.PIVOTAL_THREAT_DISTANCE_BLOCKS
import dev.loom.contract.DecisionImportance
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import dev.loom.contract.reviewsGoal

/**
 * The tier [Trigger] warrants.
 *
 * - A **goal review** is the scheduled, ordinary case and by far the most
 *   frequent, so it is routine. Its whole purpose is to be cheap enough to run
 *   often.
 * - A **settled step** is a goal review that follows the goal's last step at once
 *   rather than at the next scheduled one, so it is routine for the same reason.
 * - A **sighting** is routine too: noticing a block is the most common thing that
 *   happens to a body, and reacting to one is a small, reversible choice.
 * - Being **spoken to** is notable: it is social, another party is waiting, and
 *   the answer becomes part of a relationship the agent has to keep consistent.
 * - **Damage** is pivotal. It is the only trigger that can end the agent, and
 *   Loom is permadeath: there is no second body, so this decision is worth the
 *   frontier model every time.
 * - A **threat sighting** is pivotal when the body can see the threat and it is
 *   already inside [PIVOTAL_THREAT_DISTANCE_BLOCKS]. Those two facts are what make
 *   the next few seconds able to end the agent. Any other threat sighting is notable
 *   rather than routine - something that hunts this agent's kind is in view and that
 *   is never ordinary - but it is not yet the moment worth the frontier model, and
 *   tiering every distant or walled-off zombie as pivotal would spend a permadeath
 *   budget on a threat that cannot reach.
 */
fun importanceOf(trigger: Trigger): DecisionImportance =
    when (trigger) {
        is Trigger.PlanReview -> DecisionImportance.PLANNING
        is Trigger.GoalReview -> DecisionImportance.ROUTINE
        is Trigger.StepSettled -> DecisionImportance.ROUTINE
        is Trigger.BlockSighted -> DecisionImportance.ROUTINE
        is Trigger.Spoken -> DecisionImportance.NOTABLE
        is Trigger.Damaged -> DecisionImportance.PIVOTAL
        is Trigger.ThreatSighted -> threatImportance(trigger)
    }

/**
 * The tier one threat sighting warrants, given what the body already verified.
 *
 * Split out rather than inlined because the pivotal case is a conjunction of two
 * separately-measured facts and a reader has to be able to see that both are required: a
 * threat behind a wall and a threat across the perception radius are each survivable.
 *
 * WHAT IS NOT IN THE CONJUNCTION IS THE POINT. [Trigger.ThreatSighted.reachable] is read
 * by the mind as evidence and is deliberately not read here, because a threat Loom cannot
 * route *to* is not a threat that cannot reach the body - the engine answers a perfectly
 * reachable target with no route whenever the body is off the ground, which is exactly
 * what a body being hit is. An in-world run priced its most dangerous moment - a zombie
 * two blocks away, in the open, landing blows - as notable for that reason, and the agent
 * died. An inability to route must never make danger cheaper.
 */
private fun threatImportance(sighting: Trigger.ThreatSighted): DecisionImportance =
    if (sighting.visible && sighting.distance <= PIVOTAL_THREAT_DISTANCE_BLOCKS) {
        DecisionImportance.PIVOTAL
    } else {
        DecisionImportance.NOTABLE
    }

/**
 * The same perception, classified at the tier its trigger warrants.
 *
 * A body port captures what happened; it does not decide how much that matters.
 * This is applied on the way to the mind so the tier stamped on the resulting
 * decision's provenance is the one the event actually earned.
 */
fun Perception.classified(): Perception {
    val dangerous = trigger.reviewsGoal || trigger is Trigger.ThreatSighted
    val pivotal = dangerous && (huntedNearby() || rangedInRange() || weakBesideAHunter())
    return copy(importance = if (pivotal) DecisionImportance.PIVOTAL else importanceOf(trigger))
}

/** The creatures that hunt the agent's kind from range, so a sighting past melee reach can already hit. */
val RANGED_HUNTERS: Set<String> =
    setOf("skeleton", "stray", "bogged", "pillager", "witch", "blaze").map { "entity.minecraft.$it" }.toSet()

/** How far off a ranged hunter in sight can hit: a skeleton's bow reaches this far. */
const val RANGED_THREAT_BLOCKS: Double = 16.0

/** The health at or below which any hunter near the agent makes its next decision pivotal. */
const val LOW_HEALTH: Double = 10.0

/**
 * Whether a ranged hunter in sight is close enough to shoot (r24: eta reviewed at health 4.5 with a
 * skeleton 9.8 blocks off, was tiered routine and crafted, and was shot dead).
 */
private fun Perception.rangedInRange(): Boolean =
    surroundings.any { it.visible && it.what in RANGED_HUNTERS && it.distance <= RANGED_THREAT_BLOCKS } ||
        (trigger as? Trigger.ThreatSighted)?.let {
            it.visible && it.threat in RANGED_HUNTERS && it.distance <= RANGED_THREAT_BLOCKS
        } == true

/** Whether the agent is weak while any hunter is near enough to matter. */
private fun Perception.weakBesideAHunter(): Boolean =
    selfState.health <= LOW_HEALTH &&
        (
            trigger is Trigger.ThreatSighted ||
                surroundings.any { it.hunts == true && it.distance <= dangerReachOf(it.visible) }
        )

/**
 * Whether a creature that hunts this agent's kind is in sight inside
 * [PIVOTAL_THREAT_DISTANCE_BLOCKS] - the threat sighting's own pivotal conjunction, read off a
 * goal review's surroundings. A review beside such a creature is answered by the frontier model:
 * in r22 four of four routine reviews beside a hunter chose a dig, one at health 1.0.
 */
private fun Perception.huntedNearby(): Boolean =
    surroundings.any { it.hunts == true && it.visible && it.distance <= PIVOTAL_THREAT_DISTANCE_BLOCKS }

/** How near a hunter is danger enough to answer first, in sight or out of it. */
private fun dangerReachOf(visible: Boolean): Double = if (visible) DANGER_SIGHT_BLOCKS else DANGER_UNSEEN_BLOCKS
