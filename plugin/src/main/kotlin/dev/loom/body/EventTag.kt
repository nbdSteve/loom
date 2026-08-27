/*
 * The short, stable names the append-only log counts by.
 *
 * `IntentDetail` renders a round trip for a human to read; these name it for a
 * machine. Both are needed and neither substitutes for the other: "how often did the
 * body refuse what the mind decided" is a number a run must be able to state without
 * parsing prose, and a rendered phrase is free to change wording without breaking a
 * query only if the countable half is a field of its own.
 *
 * Every tag that names a contract variant is deliberately the contract's own
 * discriminator value, so one question asked of a run's log and of its perceptions
 * means the same thing. `EventTagTest` pins that against the contract serializers
 * rather than trusting these strings. The rest - what a body answered an intent with,
 * why a spawn was refused, what a conversion did to a body - never leave this runtime,
 * and are pinned outright instead.
 *
 * Each mapping is total with no default branch: a new trigger, intent, or outcome
 * will not compile until it has been named, because an unnamed variant would land in
 * the log as a value no analysis knows to look for.
 */
package dev.loom.body

import dev.loom.contract.Intent
import dev.loom.contract.Trigger

/**
 * The tags themselves, named once.
 *
 * Anything that reads a record back - an analysis, an operator display - has to test
 * these values, and a literal spelled twice is a literal that can be spelled wrong
 * once. Naming them here means the same constant produces the tag and recognises it.
 */
object EventTag {
    /** A scheduled goal review: the agent's own cadence, not the world's prompting. */
    const val GOAL_REVIEW: String = "goalReview"

    /** Somebody spoke to the agent. */
    const val SPOKEN: String = "spoken"

    /** The agent was hurt. */
    const val DAMAGED: String = "damaged"

    /** The agent noticed a block worth reacting to. */
    const val BLOCK_SIGHTED: String = "blockSighted"

    /** The mind chose to rest, which is the one way an agent refills its energy reserve. */
    const val REST: String = "rest"

    /** The mind chose to do nothing this cycle. Distinct from resting, which refills. */
    const val IDLE: String = "idle"

    /** The mind chose to walk somewhere. */
    const val MOVE_TO: String = "moveTo"

    /** The mind chose to speak. */
    const val SAY: String = "say"

    /** The mind chose to run from something. */
    const val FLEE: String = "flee"

    /** The body carried the intent out. The only outcome that means the agent acted. */
    const val ACCEPTED: String = "accepted"

    /** The body could have done it and would not, in this state. */
    const val REJECTED: String = "rejected"

    /** The body has no way to do this at all yet. */
    const val UNSUPPORTED: String = "unsupported"

    /** The intent arrived for an agent that no longer had a body. */
    const val NO_SUCH_BODY: String = "noSuchBody"

    /** A spawn was refused because the agent has died, and Loom agents do not return. */
    const val HAS_DIED: String = "hasDied"

    /**
     * A spawn was refused because the agent already has a body.
     *
     * The one refusal that leaves the agent *embodied*, which is why the refusals are
     * classified in a field at all: a display that read every refusal as "no body"
     * labelled a live, visible body "no body" the second time an operator typed the
     * spawn command, and no later record corrected it.
     */
    const val ALREADY_EMBODIED: String = "alreadyEmbodied"

    /** A spawn was refused because the world it named could not be resolved. */
    const val UNKNOWN_WORLD: String = "unknownWorld"

    /** A spawn was refused because Loom could not hold the world around the body. */
    const val WORLD_UNHELD: String = "worldUnheld"

    /** A spawn was refused because the world itself would not take the body. */
    const val WORLD_REFUSED: String = "worldRefused"

    /** A conversion was refused: the body is unchanged, and the agent still has it. */
    const val CONVERSION_REFUSED: String = "conversionRefused"

    /**
     * A conversion took the body: the agent lives on, with no body in the world.
     *
     * The opposite fact to [CONVERSION_REFUSED] under the same event name, and the
     * reason a conversion states which of the two it was. Nothing re-embodies an agent
     * here, so a display that carried its previous activity forward would keep saying
     * `standing by` about an agent that has nothing to stand with.
     */
    const val BODY_TAKEN: String = "bodyTaken"

    /** A flight was accepted and the body is walking the first hop of it. */
    const val RETREAT_STARTED: String = "retreatStarted"

    /** A route ended with the threat still there, and another hop was routed. */
    const val RETREAT_CONTINUED: String = "retreatContinued"

    /**
     * The flight is held, waiting for the body to be back on the ground.
     *
     * Not a refusal: a ground navigator will not search mid-air, and a body being hit is
     * mid-air. Counted apart from the failures because it resolves itself.
     */
    const val RETREAT_PENDING_GROUND: String = "retreatPendingGround"

    /**
     * A flight is held because the tick's path-search budget was already spent.
     *
     * Kept apart from [RETREAT_FAILED] because they are opposite facts about a run. This one
     * says Loom's own per-tick search budget ran out under contention and the flight will ask
     * again at its cadence; the other says the ground itself offered nothing and the agent
     * has been left standing in range of something hunting it. A run that could not tell them
     * apart could not say whether its agents were cornered or merely over budget.
     */
    const val RETREAT_PENDING_BUDGET: String = "retreatPendingBudget"

    /** The agent got beyond what it can perceive of the threat. It ran away. */
    const val RETREAT_SAFE: String = "retreatSafe"

    /**
     * The threat died, despawned, or left the world. The running was not what ended it.
     *
     * Filed for a flight that never started as well as for one that did. A `Flee` whose
     * handle still names a creature the body's world no longer holds is the same fact about
     * the run, and counting it as [RETREAT_FAILED] would say an agent was left standing in
     * range of something that is not there.
     */
    const val RETREAT_THREAT_GONE: String = "retreatThreatGone"

    /**
     * A flight named its threat through a handle the agent no longer holds bindings for.
     *
     * Loom's own ref window rather than the world: the decision arrived after the request
     * that minted the handle had been evicted. Counted apart from [RETREAT_FAILED] because
     * nothing here is known about whether the agent is in range of anything - the body could
     * not say what it had been asked to run from.
     */
    const val RETREAT_THREAT_FORGOTTEN: String = "retreatThreatForgotten"

    /**
     * A flight named a threat the request that answered it never issued a handle for.
     *
     * A different fact from the window having passed, and worth its own count: that one is
     * Loom's latency, and this one is a mind naming something it was never given.
     */
    const val RETREAT_THREAT_UNNAMED: String = "retreatThreatUnnamed"

    /** A later intent that was not a flight took the body over. */
    const val RETREAT_SUPERSEDED: String = "retreatSuperseded"

    /**
     * The flight ended with the agent still in range of the threat.
     *
     * The one retreat outcome that is a failure rather than a resolution, and the count
     * that says whether a run's deaths were agents that could not get away. Only the
     * refusals that leave a *real* threat unescaped are filed here - ground that offered
     * nowhere, and a chosen target the routing seam would not take - so a threat that had
     * gone and a handle that named nothing are counted under their own tags rather than
     * inflating this one.
     */
    const val RETREAT_FAILED: String = "retreatFailed"
}

/** Names which event prompted cognition. */
fun tagOf(trigger: Trigger): String =
    when (trigger) {
        is Trigger.GoalReview -> EventTag.GOAL_REVIEW
        is Trigger.Spoken -> EventTag.SPOKEN
        is Trigger.Damaged -> EventTag.DAMAGED
        is Trigger.BlockSighted -> EventTag.BLOCK_SIGHTED
    }

/** Names the action the mind chose, without its payload. */
fun tagOf(intent: Intent): String =
    when (intent) {
        is Intent.Rest -> EventTag.REST
        is Intent.Idle -> EventTag.IDLE
        is Intent.MoveTo -> EventTag.MOVE_TO
        is Intent.Say -> EventTag.SAY
        is Intent.Flee -> EventTag.FLEE
    }

/** Names how the body answered an intent, so refusals are countable. */
fun tagOf(outcome: IntentOutcome): String =
    when (outcome) {
        is IntentOutcome.Accepted -> EventTag.ACCEPTED
        is IntentOutcome.Rejected -> EventTag.REJECTED
        is IntentOutcome.Unsupported -> EventTag.UNSUPPORTED
        IntentOutcome.NoSuchBody -> EventTag.NO_SUCH_BODY
    }
