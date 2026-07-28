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

import dev.loom.contract.DecisionImportance
import dev.loom.contract.Perception
import dev.loom.contract.Trigger

/**
 * The tier [Trigger] warrants.
 *
 * - A **goal review** is the scheduled, ordinary case and by far the most
 *   frequent, so it is routine. Its whole purpose is to be cheap enough to run
 *   often.
 * - A **sighting** is routine too: noticing a block is the most common thing that
 *   happens to a body, and reacting to one is a small, reversible choice.
 * - Being **spoken to** is notable: it is social, another party is waiting, and
 *   the answer becomes part of a relationship the agent has to keep consistent.
 * - **Damage** is pivotal. It is the only trigger that can end the agent, and
 *   Loom is permadeath: there is no second body, so this decision is worth the
 *   frontier model every time.
 */
fun importanceOf(trigger: Trigger): DecisionImportance =
    when (trigger) {
        is Trigger.GoalReview -> DecisionImportance.ROUTINE
        is Trigger.BlockSighted -> DecisionImportance.ROUTINE
        is Trigger.Spoken -> DecisionImportance.NOTABLE
        is Trigger.Damaged -> DecisionImportance.PIVOTAL
    }

/**
 * The same perception, classified at the tier its trigger warrants.
 *
 * A body port captures what happened; it does not decide how much that matters.
 * This is applied on the way to the mind so the tier stamped on the resulting
 * decision's provenance is the one the event actually earned.
 */
fun Perception.classified(): Perception = copy(importance = importanceOf(trigger))
