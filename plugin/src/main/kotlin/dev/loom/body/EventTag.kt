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

    /** The agent noticed a living thing that hunts its kind. */
    const val THREAT_SIGHTED: String = "threatSighted"

    /** The agent noticed a block worth reacting to. */
    const val BLOCK_SIGHTED: String = "blockSighted"

    /** The mind chose to stop and hold position on purpose. It refills nothing. */
    const val REST: String = "rest"

    /** The mind chose to do nothing this cycle. Distinct from a deliberate rest. */
    const val IDLE: String = "idle"

    /** The mind chose to walk somewhere. */
    const val MOVE_TO: String = "moveTo"

    /** The mind chose to speak. */
    const val SAY: String = "say"

    /** The mind chose to run from something. */
    const val FLEE: String = "flee"

    /** The mind chose to eat one stack the body is carrying. It is the only refill. */
    const val EAT: String = "eat"

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

    /**
     * A threat was seen again too soon after the last one Loom reported.
     *
     * The ordinary way a sighting is refused, and the reason a threat standing in view
     * does not cost a model call twenty times a second. Countable on its own so a run
     * can state how much of its threat traffic the cadence absorbed.
     */
    const val THREAT_DEBOUNCED: String = "threatDebounced"

    /**
     * A threat's cadence elapsed and it is in exactly the state already reported.
     *
     * Kept apart from [THREAT_DEBOUNCED] because the bound that refused was not the
     * clock but the news: a standing hostile behind the same wall at the same distance
     * is something the mind has already been told.
     */
    const val THREAT_UNCHANGED: String = "threatUnchanged"

    /**
     * A threat was not reported because this agent's pivotal budget for the night is spent.
     *
     * The one suppression that is Loom declining to pay rather than Loom having nothing
     * new to say, which is why it is never folded into either neighbour: a run whose
     * agent died of a hostile it was never asked about has to be able to say which.
     */
    const val THREAT_OVER_NIGHTLY_CAP: String = "threatOverNightlyCap"

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

    /**
     * A meal was named by a handle no request of the agent's still remembers.
     *
     * The six eat tags below are the countable half of an eat refusal, kept apart for
     * the reason the retreat tags are: they are answers about different things - the
     * handle, the slot, and the reserve - and one number covering all of them would
     * make an agent with nothing edible indistinguishable from one that was full.
     */
    const val EAT_SLOT_FORGOTTEN: String = "eatSlotForgotten"

    /** The request is remembered, and named no such stack. */
    const val EAT_SLOT_UNNAMED: String = "eatSlotUnnamed"

    /** The slot the handle named holds nothing now. */
    const val EAT_SLOT_EMPTY: String = "eatSlotEmpty"

    /** The slot holds something the engine does not call food. */
    const val EAT_NOT_FOOD: String = "eatNotFood"

    /** The reserve was already full, so eating would have thrown the food away. */
    const val EAT_RESERVE_FULL: String = "eatReserveFull"

    /** The reserve was spent, which is terminal: nothing eats its way out of a permadeath. */
    const val EAT_RESERVE_SPENT: String = "eatReserveSpent"

    /**
     * A login was refused because the agent has died, and Loom agents do not return.
     *
     * The tag that says permadeath is working, and the only one of the three login refusals
     * that is a design outcome rather than a fault. Counting a run's refusals by tag is how
     * an operator tells "the dead stayed dead" from "Loom stopped being able to tell".
     */
    const val LOGIN_DEAD_IDENTITY: String = "loginDeadIdentity"

    /**
     * A login was refused because Loom could not read or write its own death ledger.
     *
     * Fail-closed: with the durable state unreadable, Loom cannot say who is dead, so it
     * admits nobody rather than risk admitting a dead agent. Kept apart from
     * [LOGIN_DEAD_IDENTITY] because it is the opposite fact about the run - this one says the
     * refusals carry no information about who died, so the run's survival numbers are
     * suspect until the ledger is understood.
     */
    const val LOGIN_LEDGER_UNSAFE: String = "loginLedgerUnsafe"

    /**
     * A login named a registered identity with the wrong half of it.
     *
     * The username matched a registration and the uuid did not, or the reverse. In Loom's
     * offline mode the two are related by a hash and this cannot happen, so it fires only
     * where the assumption has stopped holding - a mode change, or something claiming a
     * managed name. Refused rather than resolved, because guessing which half is authoritative
     * is how a witness starts recording evidence about the wrong agent.
     */
    const val LOGIN_IDENTITY_MISMATCH: String = "loginIdentityMismatch"

    /**
     * A dead agent's player got back in through a respawn.
     *
     * The two `REVIVED_` tags name how a permadeath was breached, which is the whole content
     * of a revival record: the removal on death did not take effect before the client asked
     * to respawn. Distinct from [REVIVED_AFTER_LOGIN] because they implicate different layers
     * - this one the removal, the other the login refusal - and a run cannot fix the layer it
     * cannot name.
     */
    const val REVIVED_AFTER_RESPAWN: String = "revivedAfterRespawn"

    /** A dead agent's player got back in through a login the refusal should have stopped. */
    const val REVIVED_AFTER_LOGIN: String = "revivedAfterLogin"
}

/** Names which event prompted cognition. */
fun tagOf(trigger: Trigger): String =
    when (trigger) {
        is Trigger.GoalReview -> EventTag.GOAL_REVIEW
        is Trigger.Spoken -> EventTag.SPOKEN
        is Trigger.Damaged -> EventTag.DAMAGED
        is Trigger.ThreatSighted -> EventTag.THREAT_SIGHTED
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
        is Intent.Eat -> EventTag.EAT
    }

/**
 * Every tag an [IntentOutcome.Rejected] can be filed under.
 *
 * Derived rather than listed, because a refusal now carries its own tag: reading a record back
 * means asking "did the body refuse this?" of a value that is [EventTag.REJECTED] only when the
 * refusal had nothing more specific to say. Anything that answered that question by testing
 * for [EventTag.REJECTED] alone read a refused meal as a decision that reached no body at all -
 * which is what the operator display did, in exactly the run the eat path enables.
 *
 * The eat half comes from [EAT_REFUSALS], so a refusal added there is covered the day it is
 * added rather than the day somebody remembers to repeat it here.
 */
val INTENT_REFUSAL_TAGS: Set<String> = setOf(EventTag.REJECTED) + EAT_REFUSALS.map { tagOf(it) }

/** Names how the body answered an intent, so refusals are countable. */
fun tagOf(outcome: IntentOutcome): String =
    when (outcome) {
        is IntentOutcome.Accepted -> EventTag.ACCEPTED
        // The refusal's own tag, which is EventTag.REJECTED unless it had something more
        // specific to say: a meal refused six distinct ways has to be countable six ways.
        is IntentOutcome.Rejected -> outcome.tag
        is IntentOutcome.Unsupported -> EventTag.UNSUPPORTED
        IntentOutcome.NoSuchBody -> EventTag.NO_SUCH_BODY
    }
