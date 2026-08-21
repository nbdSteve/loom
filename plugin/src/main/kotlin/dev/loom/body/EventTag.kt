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
