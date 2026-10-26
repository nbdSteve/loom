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
 * rather than trusting these strings. Runtime-only outcomes are pinned outright.
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

    /** The held goal's last step ended, prompting one follow-up goal review. */
    const val STEP_SETTLED: String = "stepSettled"

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

    /** The mind chose to eat one stack the body is carrying. */
    const val EAT: String = "eat"

    /** The mind chose to dig one offered block. */
    const val DIG: String = "dig"

    /** The mind chose to collect one offered dropped item. */
    const val COLLECT: String = "collect"

    /** The mind chose to place one offered held stack. */
    const val PLACE: String = "place"

    /** The mind chose to equip one offered inventory stack. */
    const val EQUIP: String = "equip"

    /** The mind chose to apply one offered recipe. */
    const val CRAFT: String = "craft"

    /** The mind chose to attack one offered living target. */
    const val ATTACK: String = "attack"

    /** The mind chose to sleep in one offered bed. */
    const val SLEEP: String = "sleep"

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

    /** A supervised player process could not reach the ready boundary. */
    const val BODY_START_FAILED: String = "bodyStartFailed"

    /** The player process was alive but did not become ready before its startup bound. */
    const val BODY_START_TIMED_OUT: String = "bodyStartTimedOut"

    /** The supervised child exited or disappeared without server evidence of death. */
    const val BODY_PROCESS_EXITED: String = "bodyProcessExited"

    /** Consecutive bounded status checks exhausted this agent's health budget. */
    const val BODY_HEALTH_FAILED: String = "bodyHealthFailed"

    /** A body-control round trip failed while the supervised process was still present. */
    const val CONTROL_UNAVAILABLE: String = "controlUnavailable"

    /**
     * The player process was healthy, but could not admit another operation in its
     * current state - most commonly because one movement already owns the path.
     */
    const val CONTROL_NOT_READY: String = "controlNotReady"

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

    /** A hunter out of sight and beyond [THREAT_NEAR_BLOCKS], not worth a flight. */
    const val THREAT_DISTANT_UNSEEN: String = "threatDistantUnseen"

    /** A hunter in sight but beyond [THREAT_SIGHT_NEWS_BLOCKS], not worth a flight. */
    const val THREAT_DISTANT_SEEN: String = "threatDistantSeen"

    /** A witnessed server damage event reached cognition as one `damaged` trigger. */
    const val DAMAGE_SUBMITTED: String = "damageSubmitted"

    /**
     * A threat or damage perception the mind was not asked about, because the agent is
     * already deciding or acting on the same threat, or did so moments ago.
     */
    const val DANGER_COALESCED: String = "dangerCoalesced"

    /** Urgent danger sent at the ordinary tier because the pivotal ceiling no longer fits it. */
    const val TIER_FALLBACK: String = "tierFallback"

    /**
     * A danger decision discarded unapplied, because newer damage from another attacker or at low
     * health was sent while it was with the mind and that newer answer governs the body now.
     */
    const val DANGER_PREEMPTED: String = "dangerPreempted"

    /** A dig's one pickup attempt found its own goal-item drop nowhere in a fresh sense. */
    const val OWN_DROP_NOT_SENSED: String = "ownDropNotSensed"

    /** A due scheduled review waits behind a review or follow-up of the agent's still with the mind. */
    const val REVIEW_IN_FLIGHT: String = "reviewInFlight"

    /** A due scheduled review waits while the body carries out the agent's flight. */
    const val FLEEING: String = "fleeing"

    /** A due scheduled review waits while the mind decides a danger of the agent's. */
    const val DANGER_DECIDING: String = "dangerDeciding"

    /** A due scheduled review waits behind a follow-up the same sweep claimed for the agent. */
    const val FOLLOW_UP_CLAIMED: String = "followUpClaimed"

    /** An event decision that took no survival response while a hunter was immediate danger (r24). */
    const val SURVIVAL_REQUIRED: String = "survivalRequired"

    /**
     * A perception the mind was not asked about because the agent already has cognition in
     * flight for the same kind of trigger: one scheduled goal review, and one event, at a time.
     * A redundant event folds into the answer already coming instead of spending the agent's
     * outstanding-request budget, and a review is never refused, only deferred until it fits.
     */
    const val COGNITION_COALESCED: String = "cognitionCoalesced"

    /** A perception still waiting for the mind when the plugin shut down; it was never answered. */
    const val COGNITION_SHUTDOWN: String = "cognitionShutdown"

    /** Damage inside an agent's open damage episode; the episode's trigger already stands. */
    const val DAMAGE_COALESCED: String = "damageCoalesced"

    /** Damage to an agent whose body was not live: starting, stopping or disconnected. */
    const val DAMAGE_BODY_UNAVAILABLE: String = "damageBodyUnavailable"

    /** Damage to an agent already dead, including a witnessed breach. */
    const val DAMAGE_AGENT_DEAD: String = "damageAgentDead"

    /** The agent died between the damage and its perception; death takes precedence. */
    const val DAMAGE_DIED_BEFORE_COGNITION: String = "damageDiedBeforeCognition"

    /** The body could not capture a perception for the damage. */
    const val DAMAGE_NOT_PERCEIVED: String = "damageNotPerceived"

    /** A perception refused because the body already held as many senses as it keeps. */
    const val SENSE_AT_CAPACITY: String = "senseAtCapacity"

    /** Damage witnessed while Loom was shutting down. */
    const val DAMAGE_SHUTDOWN: String = "damageShutdown"

    /** The agent got beyond what it can perceive of the threat. It ran away. */
    const val RETREAT_SAFE: String = "retreatSafe"

    /**
     * The flight reached ground beyond the requested distance from where the threat stood, but
     * the threat kept pace: the live separation at its end is short of that distance (r22: eight
     * "safe" flights ended under 12 of the 24 asked for, and the bites resumed 40 ticks later).
     */
    const val RETREAT_SHORT: String = "retreatShort"

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

    /**
     * A walk named the block to stand on through a handle the agent no longer holds bindings
     * for: the decision arrived after the request that minted it had been evicted.
     */
    const val MOVE_SENSE_FORGOTTEN: String = "moveSenseForgotten"

    /**
     * A walk named a block the request that answered it never issued a handle for, which is a
     * mind naming something it was never given rather than Loom's latency.
     */
    const val MOVE_BLOCK_UNNAMED: String = "moveBlockUnnamed"

    /**
     * A walk named a block in the agent's own column - the one under its feet, or a plant it
     * stands in - which goes nowhere: refused rather than answered as an arrival that moved
     * nothing. Never offered to a walk, so this is a mind that ignored its offer.
     */
    const val MOVE_OWN_SPOT: String = "moveOwnSpot"

    /**
     * A walk named a block no body can stand at - a solid block with a solid cell over it, or a
     * plant with nothing under it - so the ground could only refuse it. Never offered to a walk.
     */
    const val MOVE_NOT_STANDABLE: String = "moveNotStandable"

    /** A walk whose destination lies beyond the body's movement reach. */
    const val MOVE_OUT_OF_RANGE: String = "moveOutOfRange"

    /** A walk the ground gave no route for. */
    const val MOVE_NO_ROUTE: String = "moveNoRoute"

    /** A walk whose route search the body's own budget stopped before it learned the ground. */
    const val MOVE_SEARCH_OVER_BUDGET: String = "moveSearchOverBudget"

    /** A flight from a threat beyond the body's movement reach. */
    const val RETREAT_OUT_OF_RANGE: String = "retreatOutOfRange"

    /** A flight the ground gave no route for. */
    const val RETREAT_NO_ROUTE: String = "retreatNoRoute"

    /** A flight whose route search the body's own budget stopped before it learned the ground. */
    const val RETREAT_SEARCH_OVER_BUDGET: String = "retreatSearchOverBudget"

    /** How a walk or flight that moved the body ended, on its `moved` record. */
    const val MOVEMENT_ARRIVED: String = "movementArrived"

    /** The movement started and then made no further progress. */
    const val MOVEMENT_STALLED: String = "movementStalled"

    /** The movement reached its travel bound before arriving. */
    const val MOVEMENT_OVER_TRAVELLED: String = "movementOverTravelled"

    /** The movement reached its time bound before arriving. */
    const val MOVEMENT_TIMED_OUT: String = "movementTimedOut"

    /** A halt or a later intent stopped the movement after it had moved the body. */
    const val MOVEMENT_CANCELLED: String = "movementCancelled"

    /** The movement moved the body and then ended without a route, reach or named threat. */
    const val MOVEMENT_REFUSED: String = "movementRefused"

    /** A later intent that was not a flight took the body over. */
    const val RETREAT_SUPERSEDED: String = "retreatSuperseded"

    /**
     * An older flight that a newer danger answer of the same agent's was applied after, ended before
     * it could take the body: refused behind the newer one, or displaced from its takeover before it
     * began. Never halts the newer answer (r34 beta: an older sighting's flight, retried after
     * notReady, halted the newer damage's flight and ran in its place).
     */
    const val RETREAT_STALE: String = "retreatStale"

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
     * The eat tags below are the countable half of an eat refusal, kept apart because
     * they are answers about different things - the handle, the slot, and the player -
     * and one number covering all of them would
     * make an agent with nothing edible indistinguishable from one that was full.
     */
    const val EAT_SLOT_FORGOTTEN: String = "eatSlotForgotten"

    /** The request is remembered, and named no such stack. */
    const val EAT_SLOT_UNNAMED: String = "eatSlotUnnamed"

    /** The slot the handle named holds nothing now. */
    const val EAT_SLOT_EMPTY: String = "eatSlotEmpty"

    /** The named slot now holds a different stack from the sense answer the mind saw. */
    const val EAT_SLOT_CHANGED: String = "eatSlotChanged"

    /** The slot holds something the engine does not call food. */
    const val EAT_NOT_FOOD: String = "eatNotFood"

    /** The food bar was already full, so vanilla would not consume the food. */
    const val EAT_RESERVE_FULL: String = "eatReserveFull"

    /** The game could not equip or consume a stack that was present and edible. */
    const val EAT_SLOT_INACCESSIBLE: String = "eatSlotInaccessible"

    const val DIG_SENSE_FORGOTTEN: String = "digSenseForgotten"

/**
     * A dig of the block underfoot that would leave the body in a hole it cannot leave: refused
     * before the body is asked. Never offered, because the block observation says digSafe false.
     */
    const val DIG_WOULD_TRAP: String = "digWouldTrap"

    /**
     * A dig below the feet whose drop would land where no body can step down to collect it:
     * refused before the body is asked, and never offered, because the observation says digSafe
     * false (r19 beta body:00000441, kappa 00000383).
     */
    const val DIG_DROP_UNREACHABLE: String = "digDropUnreachable"

    /**
     * A dig that would let water flood a cell the body occupies: refused before the body is asked,
     * and never offered, because the observation says digSafe false (r27 epsilon drowned).
     */
    const val DIG_FLOODS_BODY: String = "digFloodsBody"

    const val DIG_BLOCK_UNNAMED: String = "digBlockUnnamed"
    const val DIG_BLOCK_GONE: String = "digBlockGone"

    /**
     * A dig whose finish crossed another managed player's in the server: the body saw the block
     * go after its own finish, and the server's break event names the other player. Neither client
     * can tell the two apart, so the server's attribution is the outcome (r23 rehearsal, iota).
     */
    const val DIG_CONTEST_GONE: String = "digContestGone"
    const val DIG_BLOCK_CHANGED: String = "digBlockChanged"
    const val DIG_OUT_OF_REACH: String = "digOutOfReach"
    const val DIG_NOT_VISIBLE: String = "digNotVisible"
    const val DIG_NOT_DIGGABLE: String = "digNotDiggable"
    const val DIG_TIMED_OUT: String = "digTimedOut"
    const val DIG_CANCELLED: String = "digCancelled"

    /** A say line failed the shared text bound before anything was sent. */
    const val SAY_TEXT_REFUSED: String = "sayTextRefused"

    /** The body's per-agent speech window was full, so nothing was sent. */
    const val SAY_RATE_LIMITED: String = "sayRateLimited"

    /** This agent already had one say in flight. */
    const val SAY_OUTSTANDING: String = "sayOutstanding"
    const val DIG_LIBRARY_FAILURE: String = "digLibraryFailure"

    const val COLLECT_SENSE_FORGOTTEN: String = "collectSenseForgotten"
    const val COLLECT_ITEM_UNNAMED: String = "collectItemUnnamed"
    const val COLLECT_ITEM_GONE: String = "collectItemGone"
    const val COLLECT_ITEM_CHANGED: String = "collectItemChanged"
    const val COLLECT_OUT_OF_RANGE: String = "collectOutOfRange"
    const val COLLECT_NO_ROUTE: String = "collectNoRoute"
    const val COLLECT_SEARCH_OVER_BUDGET: String = "collectSearchOverBudget"
    const val COLLECT_STALLED: String = "collectStalled"
    const val COLLECT_OVER_TRAVELLED: String = "collectOverTravelled"
    const val COLLECT_TIMED_OUT: String = "collectTimedOut"
    const val COLLECT_CANCELLED: String = "collectCancelled"
    const val COLLECT_LIBRARY_FAILURE: String = "collectLibraryFailure"

    const val PLACE_SENSE_FORGOTTEN: String = "placeSenseForgotten"
    const val PLACE_BLOCK_UNNAMED: String = "placeBlockUnnamed"
    const val PLACE_SLOT_UNNAMED: String = "placeSlotUnnamed"
    const val PLACE_BLOCK_GONE: String = "placeBlockGone"
    const val PLACE_BLOCK_CHANGED: String = "placeBlockChanged"
    const val PLACE_SLOT_EMPTY: String = "placeSlotEmpty"
    const val PLACE_SLOT_CHANGED: String = "placeSlotChanged"
    const val PLACE_ITEM_NOT_HELD: String = "placeItemNotHeld"
    const val PLACE_NOT_PLACEABLE: String = "placeNotPlaceable"
    const val PLACE_DESTINATION_BLOCKED: String = "placeDestinationBlocked"
    const val PLACE_OUT_OF_REACH: String = "placeOutOfReach"
    const val PLACE_NOT_VISIBLE: String = "placeNotVisible"
    const val PLACE_TIMED_OUT: String = "placeTimedOut"
    const val PLACE_CANCELLED: String = "placeCancelled"
    const val PLACE_LIBRARY_FAILURE: String = "placeLibraryFailure"

    const val EQUIP_SENSE_FORGOTTEN: String = "equipSenseForgotten"
    const val EQUIP_SLOT_UNNAMED: String = "equipSlotUnnamed"
    const val EQUIP_SLOT_EMPTY: String = "equipSlotEmpty"
    const val EQUIP_SLOT_CHANGED: String = "equipSlotChanged"
    const val EQUIP_INCOMPATIBLE: String = "equipIncompatible"
    const val EQUIP_TIMED_OUT: String = "equipTimedOut"
    const val EQUIP_CANCELLED: String = "equipCancelled"
    const val EQUIP_LIBRARY_FAILURE: String = "equipLibraryFailure"

    const val CRAFT_SENSE_FORGOTTEN: String = "craftSenseForgotten"
    const val CRAFT_RECIPE_UNNAMED: String = "craftRecipeUnnamed"
    const val CRAFT_QUANTITY_EXCEEDED: String = "craftQuantityExceeded"
    const val CRAFT_INGREDIENTS_CHANGED: String = "craftIngredientsChanged"
    const val CRAFT_RECIPE_UNAVAILABLE: String = "craftRecipeUnavailable"
    const val CRAFT_WORKSTATION_GONE: String = "craftWorkstationGone"
    const val CRAFT_WORKSTATION_CHANGED: String = "craftWorkstationChanged"
    const val CRAFT_WORKSTATION_OUT_OF_REACH: String = "craftWorkstationOutOfReach"
    const val CRAFT_WORKSTATION_NOT_VISIBLE: String = "craftWorkstationNotVisible"
    const val CRAFT_TIMED_OUT: String = "craftTimedOut"
    const val CRAFT_CANCELLED: String = "craftCancelled"
    const val CRAFT_BODY_DIED: String = "craftBodyDied"
    const val CRAFT_DISCONNECTED: String = "craftDisconnected"
    const val CRAFT_LIBRARY_FAILURE: String = "craftLibraryFailure"

    const val ATTACK_SENSE_FORGOTTEN: String = "attackSenseForgotten"
    const val ATTACK_TARGET_UNNAMED: String = "attackTargetUnnamed"
    const val ATTACK_TARGET_UNCORROBORATED: String = "attackTargetUncorroborated"
    const val ATTACK_TARGET_GONE: String = "attackTargetGone"
    const val ATTACK_TARGET_CHANGED: String = "attackTargetChanged"
    const val ATTACK_SLOT_UNNAMED: String = "attackSlotUnnamed"
    const val ATTACK_SLOT_EMPTY: String = "attackSlotEmpty"
    const val ATTACK_SLOT_CHANGED: String = "attackSlotChanged"
    const val ATTACK_HEALTH_BOUND: String = "attackHealthBound"
    const val ATTACK_OUT_OF_RANGE: String = "attackOutOfRange"

    /** A strike landed and the target was then out of range: it was knocked or walked off (r22 delta). */
    const val ATTACK_TARGET_ESCAPED: String = "attackTargetEscaped"
    const val ATTACK_NO_ROUTE: String = "attackNoRoute"
    const val ATTACK_SEARCH_OVER_BUDGET: String = "attackSearchOverBudget"
    const val ATTACK_STALLED: String = "attackStalled"
    const val ATTACK_OVER_TRAVELLED: String = "attackOverTravelled"
    const val ATTACK_TIMED_OUT: String = "attackTimedOut"
    const val ATTACK_CANCELLED: String = "attackCancelled"
    const val ATTACK_BODY_DIED: String = "attackBodyDied"
    const val ATTACK_DISCONNECTED: String = "attackDisconnected"
    const val ATTACK_LIBRARY_FAILURE: String = "attackLibraryFailure"

    const val SLEEP_SENSE_FORGOTTEN: String = "sleepSenseForgotten"
    const val SLEEP_BED_UNNAMED: String = "sleepBedUnnamed"
    const val SLEEP_BED_GONE: String = "sleepBedGone"
    const val SLEEP_BED_CHANGED: String = "sleepBedChanged"
    const val SLEEP_NOT_BED: String = "sleepNotBed"
    const val SLEEP_WRONG_DIMENSION: String = "sleepWrongDimension"
    const val SLEEP_OUT_OF_REACH: String = "sleepOutOfReach"
    const val SLEEP_NOT_VISIBLE: String = "sleepNotVisible"
    const val SLEEP_OCCUPIED: String = "sleepOccupied"
    const val SLEEP_NOT_NIGHT: String = "sleepNotNight"
    const val SLEEP_UNSAFE: String = "sleepUnsafe"
    const val SLEEP_REFUSED: String = "sleepRefused"
    const val SLEEP_TIMED_OUT: String = "sleepTimedOut"
    const val SLEEP_CANCELLED: String = "sleepCancelled"
    const val SLEEP_BODY_DIED: String = "sleepBodyDied"
    const val SLEEP_DISCONNECTED: String = "sleepDisconnected"
    const val SLEEP_LIBRARY_FAILURE: String = "sleepLibraryFailure"

    /** The server observed exactly the vanilla effect a bounded interaction reported. */
    const val SERVER_EFFECT_CONFIRMED: String = "serverEffectConfirmed"

    /** The server's event or final inventory disagreed with the player body's report. */
    const val DISPUTED_WORLD_INTERACTION: String = "disputedWorldInteraction"

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

    /**
     * A player body's account of its own vitals did not match the server's reading.
     *
     * The six `DISPUTED_` tags say which kind of evidence a body got wrong, and they are six
     * rather than one because the causes differ and a run has to count them apart: a wrong
     * vital is a client reporting a state it was never sent, a wrong inventory is a client's
     * item model having fallen behind the server's, and a wrong position is a physics
     * simulation that has diverged from the one the server ran. Only the server is
     * authoritative, so every one of them is the body's disagreement and never the
     * server's.
     */
    const val DISPUTED_VITALS: String = "disputedVitals"

    /** A player body's account of what it is carrying did not match the server's. */
    const val DISPUTED_INVENTORY: String = "disputedInventory"

    /** One vitals cross-check found both of its kinds of disagreement at once. */
    const val DISPUTED_BOTH: String = "disputedBoth"

    /**
     * A player body's account of where a movement request left it did not match the server's.
     *
     * The three movement tags are the same shape of statement as the three above, and are
     * kept apart from them for the reason [DISPUTED_BOTH] exists at all: a position the
     * server disagrees with is a client whose simulation diverged or a body that was moved,
     * while a journey fault is a report contradicting its own arithmetic and needs no server
     * to find. A run reading them alike could not tell those two apart.
     */
    const val DISPUTED_POSITION: String = "disputedPosition"

    /** A server-observed line and the speaker's claim for it differ in text. */
    const val DISPUTED_SPEECH_TEXT: String = "disputedSpeechText"

    /** A claimed line was observed from another managed sender. */
    const val DISPUTED_SPEECH_SENDER: String = "disputedSpeechSender"

    /** A managed player chatted with no Loom say claim behind it. */
    const val UNCLAIMED_SPEECH: String = "unclaimedSpeech"

    /** A body reported a line sent that the server never attributed to it. */
    const val UNOBSERVED_SPEECH: String = "unobservedSpeech"

    /** A movement report that contradicts its own arithmetic, whatever the server reads. */
    const val DISPUTED_JOURNEY: String = "disputedJourney"

    /** One movement cross-check found both of its kinds of disagreement at once. */
    const val DISPUTED_MOVEMENT: String = "disputedMovement"
}

/** Names which event prompted cognition. */
fun tagOf(trigger: Trigger): String =
    when (trigger) {
        is Trigger.GoalReview -> EventTag.GOAL_REVIEW
        is Trigger.StepSettled -> EventTag.STEP_SETTLED
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
        is Intent.Dig -> EventTag.DIG
        is Intent.Collect -> EventTag.COLLECT
        is Intent.Place -> EventTag.PLACE
        is Intent.Equip -> EventTag.EQUIP
        is Intent.Craft -> EventTag.CRAFT
        is Intent.Attack -> EventTag.ATTACK
        is Intent.Sleep -> EventTag.SLEEP
    }

/**
 * Every tag an [IntentOutcome.Rejected] can be filed under.
 *
 * Listed from the player action translator so the observer recognises every specific
 * refusal as a body refusal rather than as a missing body.
 */
val INTENT_REFUSAL_TAGS: Set<String> =
    setOf(
        EventTag.REJECTED,
        GoalEvidence.CONDITION_MET_IGNORED,
        GoalEvidence.COMPLETION_UNMET,
        GoalEvidence.SUCCESS_UNOBTAINABLE,
        GoalEvidence.ALREADY_MET,
        GoalEvidence.STEP_IDLE,
        EventTag.CONTROL_UNAVAILABLE,
        EventTag.CONTROL_NOT_READY,
        EventTag.EAT_SLOT_FORGOTTEN,
        EventTag.EAT_SLOT_UNNAMED,
        EventTag.EAT_SLOT_EMPTY,
        EventTag.EAT_SLOT_CHANGED,
        EventTag.EAT_NOT_FOOD,
        EventTag.EAT_RESERVE_FULL,
        EventTag.EAT_SLOT_INACCESSIBLE,
        EventTag.RETREAT_THREAT_FORGOTTEN,
        EventTag.RETREAT_THREAT_UNNAMED,
        EventTag.RETREAT_THREAT_GONE,
        EventTag.RETREAT_STALE,
        EventTag.MOVE_SENSE_FORGOTTEN,
        EventTag.MOVE_BLOCK_UNNAMED,
        EventTag.MOVE_OWN_SPOT,
        EventTag.MOVE_NOT_STANDABLE,
        EventTag.MOVE_OUT_OF_RANGE,
        EventTag.MOVE_NO_ROUTE,
        EventTag.MOVE_SEARCH_OVER_BUDGET,
        EventTag.RETREAT_OUT_OF_RANGE,
        EventTag.RETREAT_NO_ROUTE,
        EventTag.RETREAT_SEARCH_OVER_BUDGET,
        EventTag.DIG_SENSE_FORGOTTEN,
        EventTag.DIG_BLOCK_UNNAMED,
        EventTag.DIG_WOULD_TRAP,
        EventTag.DIG_DROP_UNREACHABLE,
        EventTag.DIG_FLOODS_BODY,
        EventTag.DIG_BLOCK_GONE,
        EventTag.DIG_CONTEST_GONE,
        EventTag.DIG_BLOCK_CHANGED,
        EventTag.DIG_OUT_OF_REACH,
        EventTag.DIG_NOT_VISIBLE,
        EventTag.DIG_NOT_DIGGABLE,
        EventTag.DIG_TIMED_OUT,
        EventTag.DIG_CANCELLED,
        EventTag.SAY_TEXT_REFUSED,
        EventTag.SAY_RATE_LIMITED,
        EventTag.SAY_OUTSTANDING,
        EventTag.DIG_LIBRARY_FAILURE,
        EventTag.COLLECT_SENSE_FORGOTTEN,
        EventTag.COLLECT_ITEM_UNNAMED,
        EventTag.COLLECT_ITEM_GONE,
        EventTag.COLLECT_ITEM_CHANGED,
        EventTag.COLLECT_OUT_OF_RANGE,
        EventTag.COLLECT_NO_ROUTE,
        EventTag.COLLECT_SEARCH_OVER_BUDGET,
        EventTag.COLLECT_STALLED,
        EventTag.COLLECT_OVER_TRAVELLED,
        EventTag.COLLECT_TIMED_OUT,
        EventTag.COLLECT_CANCELLED,
        EventTag.COLLECT_LIBRARY_FAILURE,
        EventTag.PLACE_SENSE_FORGOTTEN,
        EventTag.PLACE_BLOCK_UNNAMED,
        EventTag.PLACE_SLOT_UNNAMED,
        EventTag.PLACE_BLOCK_GONE,
        EventTag.PLACE_BLOCK_CHANGED,
        EventTag.PLACE_SLOT_EMPTY,
        EventTag.PLACE_SLOT_CHANGED,
        EventTag.PLACE_ITEM_NOT_HELD,
        EventTag.PLACE_NOT_PLACEABLE,
        EventTag.PLACE_DESTINATION_BLOCKED,
        EventTag.PLACE_OUT_OF_REACH,
        EventTag.PLACE_NOT_VISIBLE,
        EventTag.PLACE_TIMED_OUT,
        EventTag.PLACE_CANCELLED,
        EventTag.PLACE_LIBRARY_FAILURE,
        EventTag.EQUIP_SENSE_FORGOTTEN,
        EventTag.EQUIP_SLOT_UNNAMED,
        EventTag.EQUIP_SLOT_EMPTY,
        EventTag.EQUIP_SLOT_CHANGED,
        EventTag.EQUIP_INCOMPATIBLE,
        EventTag.EQUIP_TIMED_OUT,
        EventTag.EQUIP_CANCELLED,
        EventTag.EQUIP_LIBRARY_FAILURE,
        EventTag.CRAFT_SENSE_FORGOTTEN,
        EventTag.CRAFT_RECIPE_UNNAMED,
        EventTag.CRAFT_QUANTITY_EXCEEDED,
        EventTag.CRAFT_INGREDIENTS_CHANGED,
        EventTag.CRAFT_RECIPE_UNAVAILABLE,
        EventTag.CRAFT_WORKSTATION_GONE,
        EventTag.CRAFT_WORKSTATION_CHANGED,
        EventTag.CRAFT_WORKSTATION_OUT_OF_REACH,
        EventTag.CRAFT_WORKSTATION_NOT_VISIBLE,
        EventTag.CRAFT_TIMED_OUT,
        EventTag.CRAFT_CANCELLED,
        EventTag.CRAFT_BODY_DIED,
        EventTag.CRAFT_DISCONNECTED,
        EventTag.CRAFT_LIBRARY_FAILURE,
        EventTag.ATTACK_SENSE_FORGOTTEN,
        EventTag.ATTACK_TARGET_UNNAMED,
        EventTag.ATTACK_TARGET_UNCORROBORATED,
        EventTag.ATTACK_TARGET_GONE,
        EventTag.ATTACK_TARGET_CHANGED,
        EventTag.ATTACK_SLOT_UNNAMED,
        EventTag.ATTACK_SLOT_EMPTY,
        EventTag.ATTACK_SLOT_CHANGED,
        EventTag.ATTACK_HEALTH_BOUND,
        EventTag.ATTACK_OUT_OF_RANGE,
        EventTag.ATTACK_TARGET_ESCAPED,
        EventTag.ATTACK_NO_ROUTE,
        EventTag.ATTACK_SEARCH_OVER_BUDGET,
        EventTag.ATTACK_STALLED,
        EventTag.ATTACK_OVER_TRAVELLED,
        EventTag.ATTACK_TIMED_OUT,
        EventTag.ATTACK_CANCELLED,
        EventTag.ATTACK_BODY_DIED,
        EventTag.ATTACK_DISCONNECTED,
        EventTag.ATTACK_LIBRARY_FAILURE,
        EventTag.SLEEP_SENSE_FORGOTTEN,
        EventTag.SLEEP_BED_UNNAMED,
        EventTag.SLEEP_BED_GONE,
        EventTag.SLEEP_BED_CHANGED,
        EventTag.SLEEP_NOT_BED,
        EventTag.SLEEP_WRONG_DIMENSION,
        EventTag.SLEEP_OUT_OF_REACH,
        EventTag.SLEEP_NOT_VISIBLE,
        EventTag.SLEEP_OCCUPIED,
        EventTag.SLEEP_NOT_NIGHT,
        EventTag.SLEEP_UNSAFE,
        EventTag.SLEEP_REFUSED,
        EventTag.SLEEP_TIMED_OUT,
        EventTag.SLEEP_CANCELLED,
        EventTag.SLEEP_BODY_DIED,
        EventTag.SLEEP_DISCONNECTED,
        EventTag.SLEEP_LIBRARY_FAILURE,
    )

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
