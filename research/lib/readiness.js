'use strict'

const { isCoalesced, isExposureEnding, isPreempted, isShutdownEnding } = require('./danger.js')

// The readiness gate a ten-minute run must pass before it is called a success. A rate is not
// the target: every decision must be valid, every action must end in a typed terminal the
// evidence can attribute, and every claim the server checked must be true. Each action that
// did not succeed is listed with the exact record that ended it, and a legitimate concurrent
// race - the target went or changed, the agent's own later decision superseded it, or the
// agent died - is classified apart from the defects the gate refuses.
const READINESS_VERSION = 'loom-readiness-gate/1'
// Gate generation 4: the verdict includes goal coherence, measured from the typed goal.
const COHERENT_READINESS_VERSION = 'loom-readiness-gate/2'
// The goal ledger's refusals of a decision that contradicts its own goal, from generation 4.
const GOAL_INCOHERENT_TAGS = new Set(['goalStepMismatch', 'goalConditionMetIgnored', 'goalCompletionUnmet'])
// Gate generation 5: a step is credited only on the evidence it produced, a success item must
// be one the agent could get, and a dig that would trap the agent is refused.
const EVIDENCED_READINESS_VERSION = 'loom-readiness-gate/3'
const EVIDENCED_GOAL_INCOHERENT_TAGS = new Set([...GOAL_INCOHERENT_TAGS, 'goalSuccessUnobtainable'])
// Gate generation 6: a goal already met when it is set, or pursued by idling, is refused too.
const GROUNDED_READINESS_VERSION = 'loom-readiness-gate/4'
const GROUNDED_GOAL_INCOHERENT_TAGS = new Set([...EVIDENCED_GOAL_INCOHERENT_TAGS, 'goalAlreadyMet', 'goalStepIdle'])
// Gate generation 7: an answer that acts while ignoring its held goal, a dig or collect that cannot
// give the goal's item, and a continue or revise past the stall bound are refused with their
// intent; and a body that finds no route through the terrain is the ground's answer rather than
// the decision's fault, because the decision could not have known (r15: five collects of one
// stick hanging over a trench).
const TOLD_READINESS_VERSION = 'loom-readiness-gate/5'
const TOLD_GOAL_INCOHERENT_TAGS = new Set([
  ...GROUNDED_GOAL_INCOHERENT_TAGS, 'goalChoiceMissing', 'goalStepUnproductive', 'goalStalled'
])
const TERRAIN_TAGS = new Set(['moveNoRoute', 'retreatNoRoute', 'collectNoRoute', 'attackNoRoute'])
// Gate generation 8: a continue or revise that does not collect the agent's own lying drop of its
// goal's item is refused with its intent, and coherence needs no such refusal (r16: in 11 of 21
// such reviews the agent dug again or idled, and another agent took the drops).
const COLLECTED_READINESS_VERSION = 'loom-readiness-gate/6'
const COLLECTED_GOAL_INCOHERENT_TAGS = new Set([...TOLD_GOAL_INCOHERENT_TAGS, 'goalOwnDropIgnored'])
// Gate generation 9: an abandon of a goal sight could still meet is refused with its intent; a
// flight counts only when its threat was in view or near, or it measurably gained separation, and
// a flight from another managed player is classed apart; and coherence must clear the profile's
// pre-registered positive bars, because r17 passed generation 8 on short stick goals and idling
// once the schema withheld every answer its counters count.
const PURSUED_READINESS_VERSION = 'loom-readiness-gate/7'
const PURSUED_GOAL_INCOHERENT_TAGS = new Set([...COLLECTED_GOAL_INCOHERENT_TAGS, 'goalAbandonedAchievable'])
// Gate generation 10: a revise that changes the goal's item is refused with its intent; a flight
// counts only when it answered damage, a threat in view, or one out of sight within
// UNSEEN_NEAR_THREAT_BLOCKS, because r18's four flights all ran from mobs out of sight at 15 to 16
// blocks and the separation they gained rescued them; and the pursuit bars measure what the r18
// audit found the generation-9 measures miscounted - completions that needed two advances, forced
// cuts apart from chosen ones, idling beside any goal choice, per-agent and time-weighted goal
// holding, the kinds of item pursued, and goal steps that crafted.
const WOODED_READINESS_VERSION = 'loom-readiness-gate/8'
const WOODED_GOAL_INCOHERENT_TAGS = new Set([...PURSUED_GOAL_INCOHERENT_TAGS, 'goalReviseItemChanged'])
// Gate generation 11: the r19 audit found the generation-10 bars cleared on goals a guaranteed
// local dig met (dirt dug beside the feet), on held time that advanced nothing, and on counters
// the request schema makes zero by construction. A goal now counts toward the substance bars only
// when no guaranteed local dig gave its item at adoption, or when a later goal is chained from it;
// held time counts only while the goal advanced within a progress window; a revise that changes
// nothing is no goal activity; a no-route to the agent's own fresh drop is a blocker unless its
// own record shows it lodged out of reach; a plain completion beside a next goal is refused; and
// the counters the schema guarantees are reported apart from the behaviour the bars measure.
const SUBSTANTIVE_READINESS_VERSION = 'loom-readiness-gate/9'
const SUBSTANTIVE_GOAL_INCOHERENT_TAGS = new Set([...WOODED_GOAL_INCOHERENT_TAGS, 'goalCompletedWithoutNext', 'goalReviseUnchanged'])
// Gate generation 12: the r20 audit found the generation-11 bars cleared on one-step goals - 28 of
// 39 asked for one more than was held - on chained crafts the adopting craft itself met, on
// perceptions counted as danger, and on held time after a death. A goal now counts toward the
// substance and chain bars only when it needed two or more beyond what was held and lay, was not
// re-adopted at or below a total the same agent had just cut from, and - when chained - advanced
// after the decision that adopted it; "local" means sight guaranteed the whole count; held time
// ends at the agent's death; danger is counted in answered episodes; a review that fled a hunter
// is no idle; and a flight refused for no route from inside the agent's own dug hole is the
// selfTrapped blocker rather than terrain.
const GROUNDED_GOAL_READINESS_VERSION = 'loom-readiness-gate/10'
// Gate generation 13: the r22 audit found every goal local - dirt from underfoot or a single seed
// - because a success count could never pass what sight handed over whole, and no agent looked for
// wood. Horizon goals may now ask past sight when a source, a typed recipe or the frontier grounds
// them, and a goal counts toward substance only when it advanced through resources sight did not
// guarantee at its adoption - more of its item held than was held and guaranteed then, or a gained
// input of its recipe path - at its original target; a local goal counts only when a later goal of
// another item, chained from it, advanced; and a goal set beside offered exploration that sight
// hands over whole is refused as goalCrowdsExploration. A no-route flight is the agent's own hole
// only when it dug beneath itself where the flight began.
const HORIZON_READINESS_VERSION = 'loom-readiness-gate/11'
const HORIZON_GOAL_INCOHERENT_TAGS = new Set([...SUBSTANTIVE_GOAL_INCOHERENT_TAGS, 'goalCrowdsExploration'])
// Gate generation 14: the r23 audit found generation 13 measured goals against the wrong
// evidence. Held time progresses only from a goal's first actual advance, not from its adoption;
// what sight guaranteed is the calibrated mean a block's sampled rolls give, not the least of
// them, and includes what a recipe makes from an input held or in reach; a chained goal a single
// craft application met in full is a real chain; a discovery goal - set when nothing grounds an
// item goal - is counted apart, by its reveals and the resource goals it became; and danger, the
// agents that explored and the kinds of goal item are reported as description, never as bars a
// run clears.
const DISCOVERY_READINESS_VERSION = 'loom-readiness-gate/12'
// Gate generation 15: the r24 audit found generation 14 counted idle and refused stretches as
// progressing time, missed a one-craft chain its agent died before reviewing, left own dropped
// items out of local capacity, classed a health floor reached while the mind decided as an invalid
// decision, and published generation 14 as readiness gate 12. From 15 the readiness version names
// its gate generation, progressing time counts only after an accepted action, a chained goal a
// confirmed craft met in full counts whenever a later perception of its agent shows it held, local
// capacity counts own and other dropped items in sight, an attack refused at the health floor
// after a perception that showed health above it is a concurrent-world race, and the descriptive
// measures are reported apart from the measured verdict evidence.
const SURVIVAL_READINESS_VERSION = 'loom-readiness-gate/15'
// Gate generation 16: the r27 audit found generation 15 counted the agent's own flights as
// progressing goal time, classed a refusal its own flight caused (the body ran out of reach of the
// block its perception offered) as the decision's fault, and classed a no-route from a body under
// water as the ground's answer, which hid the drowning. From 16 a flight's interval is never
// progressing time, a reach refusal after the agent's own movement ended between its perception and
// its refusal is the ownMovementRace race, a no-route whose perception shows water in the body's own
// cells is the waterTrapped blocker, and a dig refused for flooding the body is inapplicable.
const WATER_READINESS_VERSION = 'loom-readiness-gate/16'
// Gate generation 17: the r33 audit found generation 16 counted a survival deferral beside a met
// goal as ignoring it, was blind to the paid danger answers newer damage preempted, and read a
// flight refused behind the agent's own running flight as an ordinary race. From 17 a `goalDeferred`
// decision is never metIgnored, each preempted answer is a typed `superseded` action, a
// `controlNotReady` flight refused while another of the agent's own flights still ran is the
// `dangerOverlap` blocker, and the report counts the JVM's callReserved refusals, tier fallbacks
// and preempted answers.
const DANGER_READINESS_VERSION = 'loom-readiness-gate/17'
// Gate generation 18: the r34 audit found a run's drain counted against it - two deaths after
// cognition admission closed - and an older danger answer's flight replacing a newer one. From 18
// work that drained after the run's exposure ended is typed (`exposureEnded`) and never a
// cognition failure, and an older flight a newer danger answer was applied after is the
// superseded `retreatStale`, counted apart. A collect or attack whose approach stalled or
// over-travelled is the body's incomplete movement, as a walk's or flight's is, never the
// decision's inapplicable fault (r34: delta's three collects stalled in the cell its flight did).
// A collect's no-route is the ground's answer only when the server verified it - no cell a body
// stands in would pick the item up (`terrain.pickupCells` 0), or no walk over the server's ground
// reaches one (`terrain.walkable` false) - and otherwise the body's claim, the `unverifiedTerrain`
// blocker (r34: three no-routes to one lodged log, and nothing said whether a route could exist).
const EXPOSURE_READINESS_VERSION = 'loom-readiness-gate/18'
// Gate generation 19 (the r36 audit): all ten agents died at night in the open, and no goal any
// agent could hold was one a hiding place could meet. From 19 a shelter goal is the model's own
// choice, met only when the server's ground seals the agent in and credited `covered` only for a
// step that left it more enclosed than the goal had yet been; it names no item and is counted apart
// from item goals, as a discovery goal is, so no item-chain measure moves for it. The run's shelter
// is measured beside them from the server's own record (`shelterChanged`): the pre-registered
// `shelterProgressAgentsBeforeNight` - agents whose own adopted shelter goal took a server-witnessed
// step credited `covered` before nightfall - and, reported, completions, cover quality, time
// sheltered through the night, damage taken while sealed in, and who survived to the exposure's end.
const SHELTER_READINESS_VERSION = 'loom-readiness-gate/19'
// Gate generation 20 (the r39 audit): every structural bar was met and progressing held time was
// missed by a wide margin with goals that never stayed held - alpha and kappa replaced a met dirt
// goal by the same item one higher at every step, gamma abandoned shelter goals while taking their
// own steps, and a one-block placement held iota's body under skeleton fire until it died, so three
// later placements were refused as `controlNotReady` and read as concurrent races. Every metric,
// denominator and bar of 19 is kept. From 20 a goal one step meets, a replace by the held goal's
// own item, and an end stated without a reason or with one its evidence does not support are goal
// incoherence; a `controlNotReady` refusal while the agent's own earlier request still ran is the
// `dangerOverlap` blocker for any action, not only a flight, and one behind the agent's own newer
// request is `superseded`; and a placement refused `placeDestinationBlocked` at a closing its
// perception's cover offered is a `targetRace`, since a body moved into the cell after the
// perception, where any other is inapplicable as before.
const HELD_OBJECTIVE_READINESS_VERSION = 'loom-readiness-gate/20'
// From generation 18, the approach endings judged as a walk's or flight's own.
const APPROACH_MOVEMENT_TAGS = new Set(['collectStalled', 'collectOverTravelled', 'attackStalled', 'attackOverTravelled'])
// From generation 18, a collect's no-route the server's ground did not verify.
const TERRAIN_BLOCKER_CLASSES = Object.freeze(['unverifiedTerrain'])
// From generation 17, a flight refused behind the agent's own running flight.
const DANGER_OVERLAP_BLOCKER_CLASSES = Object.freeze(['dangerOverlap'])
// From generation 16, a no-route from a body its own perception showed in water.
const WATER_BLOCKER_CLASSES = Object.freeze(['waterTrapped'])
// From generation 16, a refusal the agent's own movement caused after its perception was taken.
const OWN_MOVEMENT_RACE_CLASS = 'ownMovementRace'
// The refusals that say the target is no longer within the body's reach or sight.
const REACH_REFUSAL = /(OutOfReach|OutOfRange|NotVisible)$/
// The blocks that fill a cell with water, as `BukkitBlockGround.holdsWater` reads them.
const WATER_BLOCKS = new Set(['block.minecraft.water', 'block.minecraft.bubble_column'])
// The body's two cells above its feet, measured as a block observation's vertical offset.
const BODY_HEIGHT_BLOCKS = 2
// The body's attack health floor, `COMBAT_MIN_HEALTH` in `PlayerSurvivalPreparer.kt`.
const ATTACK_MIN_HEALTH = 4.5
const DISCOVERY_GOAL_INCOHERENT_TAGS = new Set([...HORIZON_GOAL_INCOHERENT_TAGS, 'goalDiscoveryNotOffered'])
const SURVIVAL_GOAL_INCOHERENT_TAGS = new Set([...DISCOVERY_GOAL_INCOHERENT_TAGS, 'goalSurvivalRequired', 'survivalRequired'])
// From generation 19, a shelter set where none was offered, a subgoal fed where none could feed a
// shelter, and a subgoal replaced from under the shelter it fed.
const SHELTER_GOAL_INCOHERENT_TAGS = new Set([
  ...SURVIVAL_GOAL_INCOHERENT_TAGS, 'goalShelterNotOffered', 'goalFeedsNotOffered', 'goalSubgoalReplaced'
])
// From generation 20, an objective one step meets, a replace by the held goal's own item, and an
// end stated without a reason or with one its evidence does not support.
const HELD_OBJECTIVE_GOAL_INCOHERENT_TAGS = new Set([
  ...SHELTER_GOAL_INCOHERENT_TAGS, 'goalTrivial', 'goalReplaceSameItem', 'goalReasonMissing', 'goalReasonUnsupported'
])
// The generation-13 bars that cannot fail a run that pursued anything at all: from generation 14
// they are measured and reported, and no verdict rests on them.
const DESCRIPTIVE_MEASURES = Object.freeze(['distinctGoalItems', 'exploringAgents', 'dangerEpisodes'])
// From generation 12, a no-route flight from inside a hole the agent dug under itself.
const SELF_TRAPPED_BLOCKER_CLASSES = Object.freeze(['selfTrapped'])
// How many more of its item than was held a goal must need to count as more than one step.
const MIN_SUBSTANTIVE_GAIN = 2
// How long after a goal's last advance its held time still counts as pursuit.
const PROGRESS_WINDOW_TICKS = 2400
// The interaction reach from the eyes, and the eye height it is measured from (offered.rs).
const INTERACTION_REACH_BLOCKS = 5.1
const EYE_HEIGHT_BLOCKS = 1.62
// How far above the feet an own drop lies when no walk can reach it: lodged in a tree.
const LODGED_RISE_BLOCKS = 2
const UNSEEN_NEAR_THREAT_BLOCKS = 8
// A flight is worth its decision only from a threat in view or within this many blocks...
const NEAR_THREAT_BLOCKS = 16
// ...or when it gained at least this much separation.
const MEASURED_SEPARATION_GAIN = 4
// The outcomes of a goal review whose agent held or took up a goal and stepped toward it.
const GOAL_ACTIVE_OUTCOMES = new Set(['goalAdopted', 'goalContinued', 'goalRevised', 'goalReplaced', 'goalCompletedAdopted'])
const COMPLETING_OUTCOMES = new Set(['goalCompleted', 'goalCompletedAdopted'])

/** The goal ledger refusals gate [generation] counts as incoherent decisions. */
function goalIncoherentTagsOf (generation) {
  if (generation >= 20) return HELD_OBJECTIVE_GOAL_INCOHERENT_TAGS
  if (generation >= 19) return SHELTER_GOAL_INCOHERENT_TAGS
  if (generation >= 15) return SURVIVAL_GOAL_INCOHERENT_TAGS
  if (generation >= 14) return DISCOVERY_GOAL_INCOHERENT_TAGS
  if (generation >= 13) return HORIZON_GOAL_INCOHERENT_TAGS
  if (generation >= 11) return SUBSTANTIVE_GOAL_INCOHERENT_TAGS
  if (generation >= 10) return WOODED_GOAL_INCOHERENT_TAGS
  if (generation >= 9) return PURSUED_GOAL_INCOHERENT_TAGS
  if (generation >= 8) return COLLECTED_GOAL_INCOHERENT_TAGS
  if (generation >= 7) return TOLD_GOAL_INCOHERENT_TAGS
  if (generation >= 6) return GROUNDED_GOAL_INCOHERENT_TAGS
  return generation >= 5 ? EVIDENCED_GOAL_INCOHERENT_TAGS : GOAL_INCOHERENT_TAGS
}
// The evidence that may credit a step; `none`, or no evidence stated at all, never does.
const CREDITING_STEP_EVIDENCE = new Set(['arrived', 'gained', 'gainedInput', 'dropped', 'revealed'])
// From generation 19 a shelter step that left the agent more enclosed is evidence too.
const SHELTER_CREDITING_STEP_EVIDENCE = new Set([...CREDITING_STEP_EVIDENCE, 'covered'])

const PASSIVE_INTENTS = Object.freeze(['idle', 'rest'])
const INTERACTION_INTENTS = Object.freeze(['dig', 'collect', 'place', 'equip', 'craft', 'attack', 'sleep'])

// Every class an action can end in. `confirmed` and the race classes are success or a correct
// fail-closed ending; every other class is a blocker.
const RACE_CLASSES = Object.freeze(['targetRace', 'superseded', 'concurrent', 'death'])
// From generation 7 a refusal the ground gave is a legitimate ending of its own as well.
const TERRAIN_CLASS = 'terrain'

/** The legitimate non-success classes under gate [generation]. */
function raceClassesOf (generation) {
  if (generation >= 16) return [...RACE_CLASSES, TERRAIN_CLASS, OWN_MOVEMENT_RACE_CLASS]
  return generation >= 7 ? [...RACE_CLASSES, TERRAIN_CLASS] : RACE_CLASSES
}
// From generation 9, a flight the evidence cannot show was worth taking, and one from a peer.
const FLIGHT_BLOCKER_CLASSES = Object.freeze(['needlessFlight', 'peerFlight'])
// From generation 11, a no-route to the agent's own fresh drop the record does not show lodged.
const OWN_DROP_BLOCKER_CLASSES = Object.freeze(['ownDropUnreached'])
const BLOCKER_CLASSES = Object.freeze([
  'wrongKindRef',
  'noOpTarget',
  'inapplicable',
  'unknownTimeout',
  'incompleteMovement',
  'falseSuccess',
  'protocolError',
  'staleRef',
  'unconfirmed',
  'untypedTerminal',
  'unexplainedRefusal'
])
// Blockers that are the decision's own fault: it named something it was not offered, a walk
// that goes nowhere, or an action its own perception already showed the body must refuse.
const INVALID_DECISION_CLASSES = Object.freeze(['wrongKindRef', 'noOpTarget', 'inapplicable'])
// Blockers that mean the record does not say, in a type, how the action ended.
const UNTYPED_CLASSES = Object.freeze(['unconfirmed', 'untypedTerminal', 'unexplainedRefusal'])

// The refusals whose cause the body states and the decision owns. Anything not listed here or
// matched by a rule below is an unexplained refusal.
const INAPPLICABLE_TAGS = new Set([
  'eatNotFood', 'eatReserveFull', 'eatSlotInaccessible',
  'moveNotStandable', 'moveOutOfRange', 'moveNoRoute', 'moveSearchOverBudget',
  'retreatOutOfRange', 'retreatNoRoute', 'retreatSearchOverBudget',
  'digOutOfReach', 'digNotVisible', 'digNotDiggable',
  'collectOutOfRange', 'collectNoRoute', 'collectSearchOverBudget', 'collectStalled', 'collectOverTravelled',
  'placeItemNotHeld', 'placeNotPlaceable', 'placeDestinationBlocked', 'placeOutOfReach', 'placeNotVisible',
  'equipIncompatible',
  'craftQuantityExceeded', 'craftRecipeUnavailable', 'craftWorkstationOutOfReach', 'craftWorkstationNotVisible',
  'attackTargetUncorroborated', 'attackHealthBound', 'attackOutOfRange', 'attackNoRoute',
  'attackSearchOverBudget', 'attackStalled', 'attackOverTravelled',
  'sleepNotBed', 'sleepWrongDimension', 'sleepOutOfReach', 'sleepNotVisible', 'sleepOccupied',
  'sleepNotNight', 'sleepUnsafe', 'sleepRefused',
  'sayTextRefused', 'sayRateLimited', 'sayOutstanding'
])
const PROTOCOL_TAGS = new Set(['rejected', 'controlUnavailable', 'unsupported', 'noSuchBody'])

/** The class a refusal tag ends its action in, under gate [generation] (the current one by default). */
function refusalClass (tag, generation = 12) {
  if (goalIncoherentTagsOf(generation).has(tag)) return 'goalIncoherent'
  if (generation >= 18 && tag === 'retreatStale') return 'superseded'
  if (generation >= 18 && APPROACH_MOVEMENT_TAGS.has(tag)) return 'incompleteMovement'
  if (generation >= 7 && TERRAIN_TAGS.has(tag)) return TERRAIN_CLASS
  if (generation >= 5 && tag === 'digWouldTrap') return 'inapplicable'
  if (generation >= 11 && tag === 'digDropUnreachable') return 'inapplicable'
  if (generation >= 16 && tag === 'digFloodsBody') return 'inapplicable'
  if (tag === 'controlNotReady') return 'concurrent'
  // A strike landed and the target then left range (r22 delta): a race with the target.
  if (tag === 'attackTargetEscaped') return 'targetRace'
  if (tag === 'moveOwnSpot') return 'noOpTarget'
  if (/BodyDied$/.test(tag)) return 'death'
  if (/Cancelled$/.test(tag)) return 'superseded'
  if (/(Gone|Changed|SlotEmpty)$/.test(tag)) return 'targetRace'
  if (/Unnamed$/.test(tag)) return 'wrongKindRef'
  if (/Forgotten$/.test(tag)) return 'staleRef'
  if (/TimedOut$/.test(tag)) return 'unknownTimeout'
  if (PROTOCOL_TAGS.has(tag) || /(LibraryFailure|Disconnected)$/.test(tag)) return 'protocolError'
  if (INAPPLICABLE_TAGS.has(tag)) return 'inapplicable'
  return 'unexplainedRefusal'
}

const MOVED_CLASSES = Object.freeze({
  movementArrived: 'confirmed',
  movementCancelled: 'superseded',
  movementTimedOut: 'unknownTimeout',
  movementStalled: 'incompleteMovement',
  movementOverTravelled: 'incompleteMovement',
  movementRefused: 'inapplicable'
})
const RETREAT_CLASSES = Object.freeze({
  retreatSafe: 'confirmed',
  // Arrived, with the threat keeping pace (r22): judged as a flight that ran, like a safe one.
  retreatShort: 'confirmed',
  retreatSuperseded: 'superseded',
  retreatThreatGone: 'targetRace',
  retreatThreatForgotten: 'staleRef',
  retreatThreatUnnamed: 'wrongKindRef',
  retreatFailed: 'incompleteMovement'
})

/**
 * How a safe flight is judged from generation 9: from another managed player it is `peerFlight`;
 * it is worth its decision when the threat was in view or within [NEAR_THREAT_BLOCKS] when it
 * was decided, or when the flight gained [MEASURED_SEPARATION_GAIN] or more; otherwise it is a
 * `needlessFlight` (r17: nine flights from mobs out of sight 22 blocks off gained under six).
 */
function flightClass (decision, own, retreat, generation = 9) {
  const perception = own('perceived')[0]?.perception
  const from = decision.decision?.intent?.from
  const target = perception?.surroundings?.find((seen) => seen.ref === from)
  if (target?.what === 'entity.minecraft.player') return 'peerFlight'
  const trigger = perception?.trigger
  const threat = trigger?.event === 'threatSighted' && trigger.ref === from
    ? { visible: trigger.visible, distance: trigger.distance }
    : target === undefined ? undefined : { visible: target.visible, distance: target.distance }
  if (generation >= 10) {
    // The reason for a flight is the threat, not what the flight then gained (r18 body:00000309).
    const warranted = trigger?.event === 'damaged' ||
      (threat !== undefined && (threat.visible === true || threat.distance <= UNSEEN_NEAR_THREAT_BLOCKS))
    return warranted ? 'confirmed' : 'needlessFlight'
  }
  const near = trigger?.event === 'damaged' ||
    (threat !== undefined && (threat.visible === true || threat.distance <= NEAR_THREAT_BLOCKS))
  const gained = retreat.retreat?.separationGained ?? 0
  return near || gained >= MEASURED_SEPARATION_GAIN ? 'confirmed' : 'needlessFlight'
}

/**
 * Whether a collect the ground refused named the agent's own drop, told in its own perception,
 * lying less than LODGED_RISE_BLOCKS above its feet: a drop it made where no route reaches, which
 * the ground's answer does not excuse unless the record shows it lodged out of reach (r19 kappa
 * body:00000384 and beta 453 dug pockets and could not collect from them).
 */
function ownDropUnreached (decision, own) {
  const item = decision.decision?.intent?.item
  if (decision.intent !== 'collect' || item === undefined) return false
  const perception = own.find((record) => record.event === 'perceived')?.perception
  const drop = (perception?.selfState?.ownDrops ?? []).find((told) => told.ref === item && told.fate === 'lying')
  return drop !== undefined && (drop.verticalOffset ?? 0) < LODGED_RISE_BLOCKS
}

/** How far off, horizontally, a block below the feet is one a body digs itself a hole with. */
const DUG_BENEATH_REACH_BLOCKS = 1.6

/**
 * Whether the agent behind a no-route flight had dug itself into the ground before it: a dig
 * decision naming a block its perception showed below its feet and within a step of where it
 * stood, its support or the block beside it (r20: all 13 flights refused for no route came from
 * shafts and pits the four agents had dug themselves; delta's by digging beside its support).
 */
function dugOwnSupport (bodyRecords, flight, generation = 12) {
  const perceptionOf = new Map(bodyRecords
    .filter((record) => record.event === 'perceived' && record.agentId === flight.agentId)
    .map((record) => [record.requestKey, record.perception]))
  const fledFrom = perceptionOf.get(flight.requestKey)?.selfState?.position
  return bodyRecords.some((record) => {
    if (record.event !== 'decided' || record.agentId !== flight.agentId || record.intent !== 'dig') return false
    if (!(record.tick < flight.tick)) return false
    const perception = perceptionOf.get(record.requestKey)
    const block = record.decision?.intent?.block
    const seen = (perception?.surroundings ?? []).find((entry) => entry.ref === block)
    if (seen === undefined || seen.verticalOffset >= 0) return false
    // From generation 13 the dig must be where the flight began: r22's v30 rehearsal delta dug
    // grass under itself at tick 2772, fled 50 blocks into a ravine at tick 7057, and its no-route
    // flights out of the ravine were filed as its own hole.
    const dugAt = perception?.selfState?.position
    if (generation >= 13 && (!Array.isArray(dugAt) || !Array.isArray(fledFrom) ||
        Math.hypot(dugAt[0] - fledFrom[0], dugAt[1] - fledFrom[1], dugAt[2] - fledFrom[2]) > DUG_BENEATH_REACH_BLOCKS + 1)) {
      return false
    }
    const horizontal = Math.sqrt(Math.max(0, seen.distance ** 2 - seen.verticalOffset ** 2))
    return seen.direction === 'here' || horizontal <= DUG_BENEATH_REACH_BLOCKS
  })
}

/**
 * Whether the perception a decision answered showed water in the body's own cells: a water block
 * in its own column at its feet or head (r27 epsilon, body:00001278: four walks out of a flooded
 * pit refused for no route while it drowned).
 */
function inWater (own) {
  const perception = own.find((record) => record.event === 'perceived')?.perception
  return (perception?.surroundings ?? []).some((seen) =>
    seen.kind === 'block' && WATER_BLOCKS.has(seen.what) && seen.direction === 'here' &&
    seen.verticalOffset >= 0 && seen.verticalOffset < BODY_HEIGHT_BLOCKS)
}

/**
 * Whether a movement of the agent's own ended after the perception a refused decision answered and
 * before the refusal: the body was carried away from what the perception offered (r27 iota
 * body:00001888: a block 1.8 blocks from the eyes, refused out of reach after its own flight `mc`
 * arrived 7.7 blocks off).
 */
function movedAfterPerception (bodyRecords, decision, own, refusal) {
  const perceived = own.find((record) => record.event === 'perceived')
  if (!Number.isInteger(perceived?.tick) || !Number.isInteger(refusal?.tick)) return false
  return bodyRecords.some((record) =>
    ['moved', 'retreating'].includes(record.event) && record.agentId === decision.agentId &&
    record.requestKey !== decision.requestKey &&
    record.tick >= perceived.tick && record.tick <= refusal.tick)
}

/**
 * Whether another flight of the agent's own was decided before [refusal] and had not ended by it
 * (r33 gamma body:00003000: its sighting flight was refused behind its own running follow-up flight).
 */
function ownFlightRunning (bodyRecords, decision, refusal) {
  const ended = new Map()
  for (const record of bodyRecords) {
    if (record.agentId !== decision.agentId || record.requestKey === undefined) continue
    if (['retreating', 'decisionDiscarded'].includes(record.event) ||
        (record.event === 'intentApplied' && record.outcome !== 'accepted')) {
      if (!ended.has(record.requestKey)) ended.set(record.requestKey, record.tick)
    }
  }
  return bodyRecords.some((record) =>
    record.event === 'decided' && record.intent === 'flee' && record.agentId === decision.agentId &&
    record.requestKey !== decision.requestKey && record.tick <= refusal.tick &&
    (ended.get(record.requestKey) ?? Infinity) > refusal.tick)
}

// The actions that take a body's one exclusive owner: movement and the seven native interactions.
const EXCLUSIVE_INTENTS = new Set(['moveTo', 'flee', ...INTERACTION_INTENTS])

/**
 * Which of the agent's own other requests held its body when [refusal] came, from generation 20:
 * `earlier` when one asked before [decision] was, and decided before the refusal, had not ended by
 * it - its own earlier work kept the body, which the newest decision is owed (r39 iota sense-u5
 * held its placement 330 ticks under skeleton fire, and three later placements were refused) -
 * `newer` when only one asked after it had, so the newer decision took the body as it should, and
 * `undefined` when neither.
 */
function ownRequestRunning (bodyRecords, decision, refusal) {
  const ended = new Map()
  const decided = new Map()
  // A request's age is when it was asked: answers arrive in whatever order the minds return them.
  const asked = new Map()
  bodyRecords.forEach((record, index) => {
    if (record.agentId !== decision.agentId || record.requestKey === undefined) return
    if (record.event === 'perceived' && !asked.has(record.requestKey)) asked.set(record.requestKey, index)
    if (record.event === 'decided' && !decided.has(record.requestKey)) {
      decided.set(record.requestKey, { record, index, asked: asked.get(record.requestKey) ?? index })
    }
    const settled = ['retreating', 'moved', 'decisionDiscarded'].includes(record.event) ||
      (record.event === 'intentApplied' &&
        (record.outcome !== 'accepted' || !MOVEMENT_INTENT_SET.has(decided.get(record.requestKey)?.record.intent)))
    if (settled && !ended.has(record.requestKey)) ended.set(record.requestKey, index)
  })
  const own = decided.get(decision.requestKey)
  const refusedAt = bodyRecords.indexOf(refusal)
  if (own === undefined || refusedAt < 0) return undefined
  const holding = [...decided.values()].filter(({ record, index }) =>
    record.requestKey !== decision.requestKey && EXCLUSIVE_INTENTS.has(record.intent) && index < refusedAt &&
    (ended.get(record.requestKey) ?? Infinity) > refusedAt)
  if (holding.some((held) => held.asked < own.asked)) return 'earlier'
  return holding.length > 0 ? 'newer' : undefined
}

// The actions whose acceptance starts work the body ends later, with its own record.
const MOVEMENT_INTENT_SET = new Set(['moveTo', 'flee'])

/**
 * Whether [decision] placed at a closing its own perception's cover offered: the support and side
 * the cover named, from generation 20.
 */
function offeredClosing (decision, own) {
  const intent = decision.decision?.intent
  if (decision.intent !== 'place' || intent === undefined) return false
  const closings = own.find((record) => record.event === 'perceived')?.perception?.selfState?.cover?.closings ?? []
  return closings.some((closing) => closing.block === intent.block && closing.side === intent.face)
}

/** The terminal of one accepted action, and the record that states it. */
function acceptedClass (decision, byKey, window, generation = 3) {
  const own = (event) => (byKey.get(decision.requestKey) ?? []).filter((record) => record.event === event)
  if (INTERACTION_INTENTS.includes(decision.intent) || decision.intent === 'say') {
    const checked = own('worldInteraction')
    if (checked.length === 0) return { class: 'unconfirmed' }
    const disputed = checked.find((record) => record.outcome !== 'serverEffectConfirmed')
    return disputed === undefined
      ? { class: 'confirmed', evidence: checked[0] }
      : { class: 'falseSuccess', evidence: disputed }
  }
  if (decision.intent === 'moveTo') {
    const moved = own('moved')[0]
    if (moved === undefined) return { class: 'noOpTarget' }
    return { class: MOVED_CLASSES[moved.outcome] ?? 'untypedTerminal', evidence: moved }
  }
  if (decision.intent === 'flee') {
    const retreat = own('retreating')[0]
    if (retreat === undefined) return { class: 'untypedTerminal' }
    const ended = RETREAT_CLASSES[retreat.outcome] ?? 'untypedTerminal'
    if (generation >= 9 && ended === 'confirmed') {
      return { class: flightClass(decision, own, retreat, generation), evidence: retreat }
    }
    return { class: ended, evidence: retreat }
  }
  if (decision.intent === 'eat') {
    const fed = window.find((record) => record.event === 'energyChanged' && record.agentId === decision.agentId)
    return fed === undefined ? { class: 'unconfirmed' } : { class: 'confirmed', evidence: fed }
  }
  return { class: 'untypedTerminal' }
}

/** The one fact a non-success entry names, so it is attributable without the prose. */
function evidenceOf (record) {
  if (record === undefined) return null
  return { recordId: record.recordId, event: record.event, outcome: record.outcome ?? null, tick: record.tick }
}

function ratio (part, whole) {
  return whole === 0 ? null : Number((part / whole).toFixed(6))
}

/**
 * Multi-step coherence of the held goals: how many affirmed steps the body measured as having
 * changed the agent's inventory or place, how many goals were advanced by two or more such
 * steps, and how many goals ended complete after at least one.
 */
function goalCoherenceOf (bodyRecords) {
  const byGoal = new Map()
  let progressedSteps = 0
  let measuredSteps = 0
  for (const record of bodyRecords) {
    const goal = record.goal
    if (record.event !== 'decided' || goal?.presentedGoalId === undefined) continue
    if (!Number.isInteger(goal.stepsWithoutProgress)) continue
    measuredSteps += 1
    const entry = byGoal.get(goal.presentedGoalId) ?? { progressed: 0, completed: false }
    if (goal.stepsWithoutProgress === 0) {
      progressedSteps += 1
      entry.progressed += 1
    }
    if (goal.outcome === 'goalCompleted') entry.completed = true
    byGoal.set(goal.presentedGoalId, entry)
  }
  const goals = [...byGoal.values()]
  return {
    measuredSteps,
    progressedSteps,
    goalsMeasured: goals.length,
    multiStepGoals: goals.filter((goal) => goal.progressed >= 2).length,
    completedAfterProgress: goals.filter((goal) => goal.completed && goal.progressed >= 1).length
  }
}

/**
 * Whether the run's goals were pursued coherently, from the typed goal evidence a generation-4
 * run carries on every decided record. A step is credited only when the ledger measured it as
 * advancing the goal's success condition or carrying out its typed step; a multi-step goal is
 * one whose success condition advanced at two or more steps; and every met success condition
 * must have been completed, or revised to a new target, at the review that saw it.
 */
function typedGoalCoherenceOf (bodyRecords, generation = 4, bars = undefined) {
  const evidenced = generation >= 5
  const byGoal = new Map()
  const counts = {
    measuredSteps: 0,
    conditionSteps: 0,
    stepOnlySteps: 0,
    unadvancedSteps: 0,
    metConditions: 0,
    metCompleted: 0,
    metRevised: 0,
    metIgnored: 0,
    stepMismatches: 0,
    unmetCompletions: 0,
    stalled: 0,
    ...(evidenced ? { unevidencedCredits: 0, unobtainableConditions: 0 } : {}),
    ...(generation >= 6 ? { alreadyMetConditions: 0, idleSteps: 0 } : {}),
    ...(generation >= 7 ? { unansweredGoals: 0, unproductiveSteps: 0 } : {}),
    ...(generation >= 8 ? { ignoredOwnDrops: 0 } : {}),
    ...(generation >= 11 ? { reviseItemChanges: 0, completionsWithoutNext: 0, revisesUnchanged: 0 } : {})
  }
  for (const record of bodyRecords) {
    const goal = record.goal
    if (record.event !== 'decided' || goal === undefined) continue
    if (generation >= 11 && goal.outcome === 'goalReviseItemChanged') counts.reviseItemChanges += 1
    if (generation >= 11 && goal.outcome === 'goalCompletedWithoutNext') counts.completionsWithoutNext += 1
    if (generation >= 11 && goal.outcome === 'goalReviseUnchanged') counts.revisesUnchanged += 1
    if (goal.outcome === 'goalStepMismatch') counts.stepMismatches += 1
    if (goal.outcome === 'goalCompletionUnmet') counts.unmetCompletions += 1
    if (goal.outcome === 'goalStalled') counts.stalled += 1
    if (evidenced && goal.outcome === 'goalSuccessUnobtainable') counts.unobtainableConditions += 1
    if (generation >= 6 && goal.outcome === 'goalAlreadyMet') counts.alreadyMetConditions += 1
    if (generation >= 6 && goal.outcome === 'goalStepIdle') counts.idleSteps += 1
    if (generation >= 7 && goal.outcome === 'goalChoiceMissing') counts.unansweredGoals += 1
    if (generation >= 7 && goal.outcome === 'goalStepUnproductive') counts.unproductiveSteps += 1
    if (generation >= 8 && goal.outcome === 'goalOwnDropIgnored') counts.ignoredOwnDrops += 1
    if (goal.conditionMet === true) {
      counts.metConditions += 1
      // A replace of a met goal completes it and adopts the next in one answer (routine-v26).
      if (COMPLETING_OUTCOMES.has(goal.outcome)) counts.metCompleted += 1
      else if (goal.outcome === 'goalRevised') counts.metRevised += 1
      // A survival deferral allows no goal choice at all (r33 kappa body:00000640, 2977).
      else if (generation >= 17 && goal.outcome === 'goalDeferred') counts.metDeferred = (counts.metDeferred ?? 0) + 1
      else counts.metIgnored += 1
    }
    if (goal.presentedGoalId === undefined || typeof goal.conditionAdvanced !== 'boolean') continue
    counts.measuredSteps += 1
    const entry = byGoal.get(goal.presentedGoalId) ?? { condition: 0 }
    if (goal.conditionAdvanced) {
      counts.conditionSteps += 1
      entry.condition += 1
    } else if (goal.stepAdvanced === true) counts.stepOnlySteps += 1
    else counts.unadvancedSteps += 1
    // r13: 22 steps the mind itself called noProgress were credited because a dig was accepted.
    const crediting = generation >= 19 ? SHELTER_CREDITING_STEP_EVIDENCE : CREDITING_STEP_EVIDENCE
    if (evidenced && goal.stepAdvanced === true && !crediting.has(goal.stepEvidence)) {
      counts.unevidencedCredits += 1
    }
    byGoal.set(goal.presentedGoalId, entry)
  }
  const goals = [...byGoal.values()]
  const multiStepGoals = goals.filter((goal) => goal.condition >= 2).length
  const pursuit = pursuitFor(generation, bodyRecords, byGoal, bars)
  const coherent = (pursuit === undefined || pursuit.verdict === 'cleared') && counts.stepMismatches === 0 && counts.unmetCompletions === 0 && counts.metIgnored === 0 &&
    multiStepGoals >= 1 &&
    (!evidenced || (counts.unevidencedCredits === 0 && counts.unobtainableConditions === 0)) &&
    (generation < 6 || (counts.alreadyMetConditions === 0 && counts.idleSteps === 0)) &&
    (generation < 7 || (counts.unansweredGoals === 0 && counts.unproductiveSteps === 0)) &&
    (generation < 8 || counts.ignoredOwnDrops === 0) &&
    (generation < 11 || (counts.reviseItemChanges === 0 && counts.completionsWithoutNext === 0 && counts.revisesUnchanged === 0))
  return {
    ...counts,
    goalsMeasured: goals.length,
    multiStepGoals,
    ...(generation >= 11 ? { schemaGuarantees: schemaGuaranteesOf(counts) } : {}),
    ...(pursuit === undefined ? {} : { pursuit }),
    verdict: coherent ? 'coherent' : 'incoherent'
  }
}

/**
 * The positive bars a generation-9 run must clear, pre-registered in its profile, and what the
 * run measured against each (the r17 audit): the share of goal reviews that held or took up a
 * goal and stepped toward it; the reviews that held no goal and idled although cells at the edge
 * of sight were offered to look further; the agents with a goal whose condition advanced at two
 * or more steps at its original target, never cut; and the completions of goals at their
 * original target that needed two or more more than were held when adopted. No bars stated is
 * no bar cleared.
 */
function pursuitOf (bodyRecords, byGoal, bars) {
  const perceptionOf = new Map(bodyRecords
    .filter((record) => record.event === 'perceived' && record.requestKey !== undefined)
    .map((record) => [record.requestKey, record.perception]))
  const reviews = bodyRecords.filter((record) =>
    record.event === 'decided' && record.goal !== undefined && record.goal.outcome !== 'goalNotReviewed')
  const active = reviews.filter((record) => GOAL_ACTIVE_OUTCOMES.has(record.goal.outcome)).length
  const idleWithExploration = reviews.filter((record) =>
    record.goal.outcome === 'goalUnaddressed' &&
    record.goal.presentedGoalId === undefined &&
    PASSIVE_INTENTS.includes(record.intent) &&
    (perceptionOf.get(record.requestKey)?.explore?.length ?? 0) > 0).length
  // Each goal's original total and whether a revise ever cut it, and what was held at adoption.
  const original = new Map()
  for (const record of reviews) {
    const goal = record.goal
    const count = record.decision?.goal?.successCount
    if (['goalAdopted', 'goalReplaced', 'goalCompletedAdopted'].includes(goal.outcome) && goal.goalId !== undefined) {
      const item = record.decision?.goal?.successItem
      const held = (perceptionOf.get(record.requestKey)?.selfState?.inventorySummary ?? [])
        .filter((stack) => stack.what === item)
        .reduce((sum, stack) => sum + stack.count, 0)
      original.set(goal.goalId, { agentId: record.agentId, count, held, cut: false })
    } else if (goal.outcome === 'goalRevised' && original.has(goal.goalId) && Number.isInteger(count)) {
      const entry = original.get(goal.goalId)
      if (count < entry.count) entry.cut = true
    }
  }
  const atOriginal = (goalId) => original.has(goalId) && !original.get(goalId).cut
  const multiStepAgents = new Set([...byGoal.entries()]
    .filter(([goalId, goal]) => goal.condition >= 2 && atOriginal(goalId))
    .map(([goalId]) => original.get(goalId).agentId))
  const originalTargetCompletions = reviews.filter((record) => {
    const goalId = record.goal.presentedGoalId
    if (!COMPLETING_OUTCOMES.has(record.goal.outcome) || record.goal.conditionMet !== true || !atOriginal(goalId)) return false
    const entry = original.get(goalId)
    return entry.count - entry.held >= 2
  }).length
  const measured = {
    goalActiveReviewShare: ratio(active, reviews.length),
    idleWithExploration,
    multiStepAgentsAtOriginalTarget: multiStepAgents.size,
    originalTargetCompletions
  }
  const cleared = bars !== undefined &&
    (measured.goalActiveReviewShare ?? 0) >= bars.goalActiveReviewShare &&
    idleWithExploration <= bars.maxIdleWithExploration &&
    multiStepAgents.size >= bars.multiStepAgentsAtOriginalTarget &&
    originalTargetCompletions >= bars.originalTargetCompletions
  return { bars: bars ?? null, measured, verdict: cleared ? 'cleared' : 'notCleared' }
}

/**
 * The generation-10 pursuit bars and what the run measured against each (the r18 audit): the
 * fleet's and every agent's share of goal reviews that held or took up a goal and stepped toward
 * it; the fleet's and every agent's share of time from its first perception to the run's end
 * spent holding a goal; the goal reviews that idled or rested beside offered cells at the edge of
 * sight, whatever their goal choice; the agents with a goal whose condition advanced at two or
 * more steps at its original target, where a cut that sight forced is no cut and a revise to
 * another item is; the completions at an original target that advanced at two or more steps and
 * needed two or more beyond what was held, or lay as the agent's own drop in reach, when adopted;
 * the kinds of item goals were set for; and the accepted goal steps that crafted.
 */
function woodedPursuitOf (bodyRecords, byGoal, bars) {
  const perceptionOf = new Map(bodyRecords
    .filter((record) => record.event === 'perceived' && record.requestKey !== undefined)
    .map((record) => [record.requestKey, record.perception]))
  const appliedOf = new Map(bodyRecords
    .filter((record) => record.event === 'intentApplied' && record.requestKey !== undefined)
    .map((record) => [record.requestKey, record.outcome]))
  const reviews = bodyRecords.filter((record) =>
    record.event === 'decided' && record.goal !== undefined && record.goal.outcome !== 'goalNotReviewed')
  const active = (record) => GOAL_ACTIVE_OUTCOMES.has(record.goal.outcome)
  const byAgent = new Map()
  for (const record of reviews) {
    const entry = byAgent.get(record.agentId) ?? { reviews: 0, active: 0 }
    entry.reviews += 1
    if (active(record)) entry.active += 1
    byAgent.set(record.agentId, entry)
  }
  const idleBesideExploration = reviews.filter((record) =>
    PASSIVE_INTENTS.includes(record.intent) &&
    (perceptionOf.get(record.requestKey)?.explore?.length ?? 0) > 0).length
  const original = new Map()
  const items = new Set()
  for (const record of reviews) {
    const goal = record.goal
    const choice = record.decision?.goal
    if (['goalAdopted', 'goalReplaced', 'goalCompletedAdopted'].includes(goal.outcome) && goal.goalId !== undefined) {
      const item = choice?.successItem
      items.add(item)
      const self = perceptionOf.get(record.requestKey)?.selfState
      const carried = (self?.inventorySummary ?? []).filter((stack) => stack.what === item)
        .reduce((sum, stack) => sum + stack.count, 0)
      const lying = (self?.ownDrops ?? []).filter((drop) => drop.what === item && drop.fate === 'lying')
        .reduce((sum, drop) => sum + drop.count, 0)
      original.set(goal.goalId, { agentId: record.agentId, item, count: choice?.successCount, held: carried + lying, cut: false })
    } else if (goal.outcome === 'goalRevised' && original.has(goal.goalId)) {
      const entry = original.get(goal.goalId)
      const count = choice?.successCount
      const forced = Number.isInteger(goal.targetHeld) && Number.isInteger(goal.targetCapacity) &&
        goal.targetHeld + goal.targetCapacity < entry.count
      if (choice?.successItem !== undefined && choice.successItem !== entry.item) entry.cut = true
      else if (Number.isInteger(count) && count < entry.count) {
        if (forced) entry.count = count
        else entry.cut = true
      }
    }
  }
  const atOriginal = (goalId) => original.has(goalId) && !original.get(goalId).cut
  const advances = (goalId) => byGoal.get(goalId)?.condition ?? 0
  const multiStepAgents = new Set([...original.entries()]
    .filter(([goalId]) => advances(goalId) >= 2 && atOriginal(goalId))
    .map(([, entry]) => entry.agentId))
  const originalTargetCompletions = reviews.filter((record) => {
    const goalId = record.goal.presentedGoalId
    if (!COMPLETING_OUTCOMES.has(record.goal.outcome) || record.goal.conditionMet !== true || !atOriginal(goalId)) return false
    const entry = original.get(goalId)
    return entry.count - entry.held >= 2 && advances(goalId) >= 2
  }).length
  const craftSteps = reviews.filter((record) =>
    record.intent === 'craft' && active(record) && appliedOf.get(record.requestKey) === 'accepted').length
  const held = heldTimeOf(bodyRecords)
  const shares = [...byAgent.values()].map((entry) => entry.active / entry.reviews)
  const measured = {
    goalActiveReviewShare: ratio(reviews.filter(active).length, reviews.length),
    minAgentGoalActiveReviewShare: shares.length === 0 ? null : Number(Math.min(...shares).toFixed(6)),
    goalHeldTimeShare: held.fleet,
    minAgentGoalHeldTimeShare: held.minAgent,
    idleBesideExploration,
    multiStepAgentsAtOriginalTarget: multiStepAgents.size,
    originalTargetCompletions,
    distinctGoalItems: [...items].filter((item) => typeof item === 'string').length,
    craftSteps
  }
  const at = (value, bar) => (value ?? 0) >= bar
  const cleared = bars !== undefined &&
    at(measured.goalActiveReviewShare, bars.goalActiveReviewShare) &&
    at(measured.minAgentGoalActiveReviewShare, bars.minAgentGoalActiveReviewShare) &&
    at(measured.goalHeldTimeShare, bars.goalHeldTimeShare) &&
    at(measured.minAgentGoalHeldTimeShare, bars.minAgentGoalHeldTimeShare) &&
    idleBesideExploration <= bars.maxIdleBesideExploration &&
    at(measured.multiStepAgentsAtOriginalTarget, bars.multiStepAgentsAtOriginalTarget) &&
    at(originalTargetCompletions, bars.originalTargetCompletions) &&
    at(measured.distinctGoalItems, bars.distinctGoalItems) &&
    at(craftSteps, bars.craftSteps)
  return { bars: bars ?? null, measured, verdict: cleared ? 'cleared' : 'notCleared' }
}

/**
 * The counters the per-request schema makes zero by construction, reported apart: a zero here is
 * no evidence of the mind's behaviour, only that the schema held and no backend bypassed it, and a
 * nonzero one is a defect of the schema or of a backend (the r19 audit read "coherent" as behaviour
 * when it was mostly a statement about the schema). The behaviour is what the pursuit bars measure.
 */
const SCHEMA_GUARANTEED_COUNTERS = Object.freeze([
  'stepMismatches', 'unmetCompletions', 'metIgnored', 'unobtainableConditions', 'alreadyMetConditions',
  'idleSteps', 'unansweredGoals', 'unproductiveSteps', 'ignoredOwnDrops', 'reviseItemChanges',
  'completionsWithoutNext', 'revisesUnchanged'
])

function schemaGuaranteesOf (counts) {
  const counted = Object.fromEntries(SCHEMA_GUARANTEED_COUNTERS.map((name) => [name, counts[name] ?? 0]))
  return {
    ...counted,
    held: Object.values(counted).every((count) => count === 0),
    meaning: 'zero by the request schema; a nonzero count is a defect of the schema or of a backend that bypassed it, not behaviour'
  }
}

/**
 * The fleet's and the least-holding agent's share of server time spent holding a goal: for each
 * agent, from its first record of any kind to the run's last record, the time after each decided
 * record whose resolution left a goal held, up to its next decided record. Generations 9 and 10
 * are committed under this measure; generation 11 measures from the first perception instead
 * (`progressingTimeOf`), as the r19 audit found this one counted the time before it.
 */
function heldTimeOf (bodyRecords) {
  const end = maximumTick(bodyRecords)
  const agents = new Map()
  for (const record of bodyRecords) {
    if (record.agentId === undefined || !Number.isInteger(record.tick)) continue
    const entry = agents.get(record.agentId) ?? { start: record.tick, held: 0, since: null }
    if (record.event === 'decided' && record.goal !== undefined) {
      if (entry.since !== null) entry.held += record.tick - entry.since
      entry.since = record.goal.goalId !== undefined ? record.tick : null
    }
    agents.set(record.agentId, entry)
  }
  let held = 0
  let span = 0
  const shares = []
  for (const entry of agents.values()) {
    const total = entry.held + (entry.since === null ? 0 : end - entry.since)
    const window = end - entry.start
    if (window <= 0) continue
    held += total
    span += window
    shares.push(total / window)
  }
  return {
    fleet: ratio(held, span),
    minAgent: shares.length === 0 ? null : Number(Math.min(...shares).toFixed(6))
  }
}

function maximumTick (records) {
  let most = 0
  for (const record of records) if (Number.isInteger(record.tick) && record.tick > most) most = record.tick
  return most
}

/** The distance from the agent's eyes to an observation, from its feet-relative reading. */
function fromEyes (seen) {
  const horizontal = Math.max(seen.distance ** 2 - seen.verticalOffset ** 2, 0)
  return Math.sqrt(horizontal + (seen.verticalOffset - EYE_HEIGHT_BLOCKS) ** 2)
}

/**
 * Whether a guaranteed local dig gave `item` when a goal of it was set: a visible block in dig
 * reach, safe to dig, whose every sampled roll drops at least one of it (r19: dirt dug beside or
 * under the feet carried five of eight original-target completions).
 */
function guaranteedLocally (perception, item) {
  return (perception?.surroundings ?? []).some((seen) =>
    seen.kind === 'block' && seen.visible === true && seen.digSafe === true &&
    fromEyes(seen) <= INTERACTION_REACH_BLOCKS &&
    (seen.yields ?? []).some((given) => given.what === item && given.least >= 1))
}

/**
 * The generation-11 pursuit bars and what the run measured against each (the r19 audit). Beside
 * generation 10's review and idle measures it counts:
 * - a revise that sets the same item and total it was shown as no goal activity;
 * - the fleet's and the least agent's share of time holding a goal that advanced within
 *   PROGRESS_WINDOW_TICKS, not merely held one (iota and kappa held goals 94% and 93% of r19 and
 *   advanced nothing for most of it);
 * - only substantive goals toward the multi-step and completion bars: a goal no guaranteed local
 *   dig gave the item of when it was set, or one a later goal is chained from;
 * - goals chained from an earlier met goal (typed `chainedFrom`), craft steps of chained goals,
 *   the agents that walked to the edge of sight, and the danger the run met.
 */
function substantivePursuitOf (bodyRecords, byGoal, bars) {
  const perceptionOf = new Map(bodyRecords
    .filter((record) => record.event === 'perceived' && record.requestKey !== undefined)
    .map((record) => [record.requestKey, record.perception]))
  const appliedOf = new Map(bodyRecords
    .filter((record) => record.event === 'intentApplied' && record.requestKey !== undefined)
    .map((record) => [record.requestKey, record.outcome]))
  const reviews = bodyRecords.filter((record) =>
    record.event === 'decided' && record.goal !== undefined && record.goal.outcome !== 'goalNotReviewed')
  const noOp = (record) => record.goal.outcome === 'goalRevised' &&
    record.decision?.goal?.successItem === record.shown?.goal?.successItem &&
    record.decision?.goal?.successCount === record.shown?.goal?.successCount
  const active = (record) => GOAL_ACTIVE_OUTCOMES.has(record.goal.outcome) && !noOp(record)
  const byAgent = new Map()
  for (const record of reviews) {
    const entry = byAgent.get(record.agentId) ?? { reviews: 0, active: 0 }
    entry.reviews += 1
    if (active(record)) entry.active += 1
    byAgent.set(record.agentId, entry)
  }
  const idleBesideExploration = reviews.filter((record) =>
    PASSIVE_INTENTS.includes(record.intent) &&
    (perceptionOf.get(record.requestKey)?.explore?.length ?? 0) > 0).length
  const original = new Map()
  const items = new Set()
  const chainedFrom = new Set()
  let chainedGoals = 0
  for (const record of reviews) {
    const goal = record.goal
    const choice = record.decision?.goal
    if (['goalAdopted', 'goalReplaced', 'goalCompletedAdopted'].includes(goal.outcome) && goal.goalId !== undefined) {
      const item = choice?.successItem
      items.add(item)
      const perception = perceptionOf.get(record.requestKey)
      const self = perception?.selfState
      const carried = (self?.inventorySummary ?? []).filter((stack) => stack.what === item)
        .reduce((sum, stack) => sum + stack.count, 0)
      const lying = (self?.ownDrops ?? []).filter((drop) => drop.what === item && drop.fate === 'lying')
        .reduce((sum, drop) => sum + drop.count, 0)
      if (goal.chainedFrom !== undefined) chainedFrom.add(goal.chainedFrom)
      original.set(goal.goalId, {
        agentId: record.agentId,
        item,
        count: choice?.successCount,
        held: carried + lying,
        cut: false,
        local: guaranteedLocally(perception, item),
        chained: goal.chainedFrom !== undefined
      })
    } else if (goal.outcome === 'goalRevised' && original.has(goal.goalId)) {
      const entry = original.get(goal.goalId)
      const count = choice?.successCount
      const forced = Number.isInteger(goal.targetHeld) && Number.isInteger(goal.targetCapacity) &&
        goal.targetHeld + goal.targetCapacity < entry.count
      if (choice?.successItem !== undefined && choice.successItem !== entry.item) entry.cut = true
      else if (Number.isInteger(count) && count < entry.count) {
        if (forced) entry.count = count
        else entry.cut = true
      }
    }
  }
  const advances = (goalId) => byGoal.get(goalId)?.condition ?? 0
  for (const [goalId, entry] of original) if (entry.chained && advances(goalId) >= 1) chainedGoals += 1
  const substantive = (goalId) => {
    const entry = original.get(goalId)
    return entry !== undefined && (!entry.local || chainedFrom.has(goalId))
  }
  const atOriginal = (goalId) => original.has(goalId) && !original.get(goalId).cut
  const multiStepAgents = new Set([...original.entries()]
    .filter(([goalId]) => advances(goalId) >= 2 && atOriginal(goalId) && substantive(goalId))
    .map(([, entry]) => entry.agentId))
  const substantiveCompletions = reviews.filter((record) => {
    const goalId = record.goal.presentedGoalId
    if (!COMPLETING_OUTCOMES.has(record.goal.outcome) || record.goal.conditionMet !== true) return false
    if (!atOriginal(goalId) || !substantive(goalId)) return false
    const entry = original.get(goalId)
    return entry.count - entry.held >= 2 && advances(goalId) >= 2
  }).length
  const chainedGoalIds = new Set([...original.entries()].filter(([, entry]) => entry.chained).map(([goalId]) => goalId))
  const craftChainSteps = reviews.filter((record) =>
    record.intent === 'craft' && active(record) && appliedOf.get(record.requestKey) === 'accepted' &&
    chainedGoalIds.has(record.goal.goalId)).length
  const exploringAgents = new Set(bodyRecords.filter((record) => {
    if (record.event !== 'decided' || record.intent !== 'moveTo') return false
    const onto = record.decision?.intent?.onto
    const offered = (perceptionOf.get(record.requestKey)?.explore ?? []).map((cell) => cell.ref)
    return onto !== undefined && offered.includes(onto) && appliedOf.get(record.requestKey) === 'accepted'
  }).map((record) => record.agentId)).size
  const dangerEncounters = bodyRecords.filter((record) =>
    record.event === 'perceived' &&
    ['threatSighted', 'damaged'].includes(record.perception?.trigger?.event)).length
  const held = progressingTimeOf(bodyRecords)
  const shares = [...byAgent.values()].map((entry) => entry.active / entry.reviews)
  const measured = {
    goalActiveReviewShare: ratio(reviews.filter(active).length, reviews.length),
    minAgentGoalActiveReviewShare: shares.length === 0 ? null : Number(Math.min(...shares).toFixed(6)),
    progressingHeldTimeShare: held.fleet,
    minAgentProgressingHeldTimeShare: held.minAgent,
    idleBesideExploration,
    noOpRevises: reviews.filter(noOp).length,
    substantiveMultiStepAgents: multiStepAgents.size,
    substantiveCompletions,
    chainedGoals,
    distinctGoalItems: [...items].filter((item) => typeof item === 'string').length,
    craftChainSteps,
    exploringAgents,
    dangerEncounters
  }
  const at = (value, bar) => (value ?? 0) >= bar
  const cleared = bars !== undefined &&
    at(measured.goalActiveReviewShare, bars.goalActiveReviewShare) &&
    at(measured.minAgentGoalActiveReviewShare, bars.minAgentGoalActiveReviewShare) &&
    at(measured.progressingHeldTimeShare, bars.progressingHeldTimeShare) &&
    at(measured.minAgentProgressingHeldTimeShare, bars.minAgentProgressingHeldTimeShare) &&
    idleBesideExploration <= bars.maxIdleBesideExploration &&
    at(measured.substantiveMultiStepAgents, bars.substantiveMultiStepAgents) &&
    at(substantiveCompletions, bars.substantiveCompletions) &&
    at(chainedGoals, bars.chainedGoals) &&
    at(measured.distinctGoalItems, bars.distinctGoalItems) &&
    at(craftChainSteps, bars.craftChainSteps) &&
    at(exploringAgents, bars.exploringAgents) &&
    at(dangerEncounters, bars.dangerEncounters)
  return { bars: bars ?? null, measured, verdict: cleared ? 'cleared' : 'notCleared' }
}

/** The pursuit measure gate [generation] holds goals to, if any. */
function pursuitFor (generation, bodyRecords, byGoal, bars) {
  if (generation >= 12) return groundedPursuitOf(bodyRecords, byGoal, bars, generation)
  if (generation >= 11) return substantivePursuitOf(bodyRecords, byGoal, bars)
  if (generation >= 10) return woodedPursuitOf(bodyRecords, byGoal, bars)
  if (generation >= 9) return pursuitOf(bodyRecords, byGoal, bars)
  return undefined
}

/** How many of [item] sight guarantees one dig at a time: each visible dig-safe block in reach's least. */
function guaranteedCountOf (perception, item) {
  return (perception?.surroundings ?? [])
    .filter((seen) => seen.kind === 'block' && seen.visible === true && seen.digSafe === true &&
      fromEyes(seen) <= INTERACTION_REACH_BLOCKS)
    .reduce((sum, seen) => sum + (seen.yields ?? [])
      .filter((given) => given.what === item)
      .reduce((least, given) => least + given.least, 0), 0)
}

/**
 * How many of [item] sight could be counted on for when a goal of it was set, from generation 14:
 * each visible dig-safe block in reach at the calibrated mean of the server's sampled rolls, in
 * whole items, and what an offered craft makes. An item a recipe makes from an input held or in
 * reach is guaranteed outright: its goal needed no looking further (r23).
 */
function expectedLocalCountOf (perception, item, withDrops = false) {
  const meanOf = (what) => Math.floor((perception?.surroundings ?? [])
    .filter((seen) => seen.kind === 'block' && seen.visible === true && seen.digSafe === true &&
      fromEyes(seen) <= INTERACTION_REACH_BLOCKS)
    .reduce((sum, seen) => sum + (seen.yields ?? [])
      .filter((given) => given.what === what)
      .reduce((total, given) => total + (given.perHundred ?? given.least * 100), 0), 0) / 100)
  const self = perception?.selfState
  const heldOf = (what) => (self?.inventorySummary ?? []).filter((stack) => stack.what === what)
    .reduce((sum, stack) => sum + stack.count, 0)
  const made = (perception?.horizon ?? []).some((entry) => entry.what === item &&
    entry.grounding === 'recipe' && entry.via !== undefined && (heldOf(entry.via) > 0 || meanOf(entry.via) >= 1))
  if (made) return Infinity
  const crafted = (self?.craftingOptions ?? []).filter((option) => option.what === item)
    .reduce((sum, option) => sum + option.resultCount * option.maxCrafts, 0)
  if (!withDrops) return meanOf(item) + crafted
  // From generation 15 dropped items in sight - the agent's own lying drops and any other - count.
  const lying = (perception?.surroundings ?? []).filter((seen) => seen.kind === 'droppedItem' && seen.what === item &&
    seen.routeRefused !== true).reduce((sum, seen) => sum + (seen.count ?? 1), 0)
  return meanOf(item) + crafted + lying
}

/**
 * The generation-12 pursuit bars and what the run measured against each (the r20 audit). As
 * generation 11, except:
 * - a goal is substantive only when it needed MIN_SUBSTANTIVE_GAIN or more beyond what was held
 *   and lay, was not re-adopted at or below a total the same agent had cut from for that item,
 *   and either sight did not guarantee its whole count one dig at a time or a later goal chained
 *   from it;
 * - a chained goal counts only when it needed MIN_SUBSTANTIVE_GAIN more and advanced at least
 *   twice - once more than the decision that adopted it - and a craft steps a chain only after
 *   that adopting decision;
 * - a review answered by a flight from a hunter is goal activity, not an idle;
 * - held time ends at each agent's death;
 * - danger is the number of danger perceptions answered rather than coalesced, with the deaths.
 */
function groundedPursuitOf (bodyRecords, byGoal, bars, generation = 12) {
  const horizonRule = generation >= 13
  const discoveryRule = generation >= 14
  const survivalRule = generation >= 15
  const localCountOf = discoveryRule
    ? (perception, item) => expectedLocalCountOf(perception, item, survivalRule)
    : guaranteedCountOf
  const perceptionOf = new Map(bodyRecords
    .filter((record) => record.event === 'perceived' && record.requestKey !== undefined)
    .map((record) => [record.requestKey, record.perception]))
  const appliedOf = new Map(bodyRecords
    .filter((record) => record.event === 'intentApplied' && record.requestKey !== undefined)
    .map((record) => [record.requestKey, record.outcome]))
  const reviews = bodyRecords.filter((record) =>
    record.event === 'decided' && record.goal !== undefined && record.goal.outcome !== 'goalNotReviewed')
  const noOp = (record) => record.goal.outcome === 'goalRevised' &&
    record.decision?.goal?.successItem === record.shown?.goal?.successItem &&
    record.decision?.goal?.successCount === record.shown?.goal?.successCount
  const fled = (record) => record.intent === 'flee' && record.goal.outcome === 'goalUnaddressed'
  // From routine-v31 a survival response defers the goal its review owed: a flight from or attack
  // on a hunter, or a meal at the hunger threshold, which is goal activity and never its progress.
  const deferred = (record) => record.goal.outcome === 'goalDeferred'
  const active = (record) =>
    (GOAL_ACTIVE_OUTCOMES.has(record.goal.outcome) && !noOp(record)) || fled(record) || deferred(record)
  const byAgent = new Map()
  for (const record of reviews) {
    const entry = byAgent.get(record.agentId) ?? { reviews: 0, active: 0 }
    entry.reviews += 1
    if (active(record)) entry.active += 1
    byAgent.set(record.agentId, entry)
  }
  const idleBesideExploration = reviews.filter((record) =>
    PASSIVE_INTENTS.includes(record.intent) &&
    (perceptionOf.get(record.requestKey)?.explore?.length ?? 0) > 0).length
  const original = new Map()
  const items = new Set()
  const chainedFrom = new Set()
  const cutFrom = new Map()
  for (const record of reviews) {
    const goal = record.goal
    const choice = record.decision?.goal
    // A discovery goal names no item, and is counted apart from item goals (generation 14); so
    // does a shelter goal (generation 19).
    if (discoveryRule && goal.discovery === true) continue
    if (generation >= 19 && (goal.shelter === true || goal.presentedShelter === true)) continue
    if (['goalAdopted', 'goalReplaced', 'goalCompletedAdopted'].includes(goal.outcome) && goal.goalId !== undefined) {
      const item = choice?.successItem
      items.add(item)
      const perception = perceptionOf.get(record.requestKey)
      const self = perception?.selfState
      const carried = (self?.inventorySummary ?? []).filter((stack) => stack.what === item)
        .reduce((sum, stack) => sum + stack.count, 0)
      const lying = (self?.ownDrops ?? []).filter((drop) => drop.what === item && drop.fate === 'lying')
        .reduce((sum, drop) => sum + drop.count, 0)
      const count = choice?.successCount
      const held = carried + lying
      if (goal.chainedFrom !== undefined) chainedFrom.add(goal.chainedFrom)
      const recut = cutFrom.get(`${record.agentId}:${item}`)
      original.set(goal.goalId, {
        agentId: record.agentId,
        item,
        count,
        held,
        cut: false,
        adoptedBy: record.requestKey,
        small: !Number.isInteger(count) || count - held < MIN_SUBSTANTIVE_GAIN,
        readopted: recut !== undefined && Number.isInteger(count) && count <= recut,
        local: Number.isInteger(count) && localCountOf(perception, item) >= count - held,
        guaranteed: held + localCountOf(perception, item),
        chained: goal.chainedFrom !== undefined,
        chainedFromGoal: goal.chainedFrom,
        horizon: (record.shown?.horizon ?? perception?.horizon ?? []).some((entry) => entry.what === item)
      })
    } else if (goal.outcome === 'goalRevised' && original.has(goal.goalId)) {
      const entry = original.get(goal.goalId)
      const count = choice?.successCount
      const forced = Number.isInteger(goal.targetHeld) && Number.isInteger(goal.targetCapacity) &&
        goal.targetHeld + goal.targetCapacity < entry.count
      if (choice?.successItem !== undefined && choice.successItem !== entry.item) entry.cut = true
      else if (Number.isInteger(count) && count < entry.count) {
        const key = `${entry.agentId}:${entry.item}`
        cutFrom.set(key, Math.max(cutFrom.get(key) ?? 0, entry.count))
        if (forced) entry.count = count
        else entry.cut = true
      }
    }
  }
  const advances = (goalId) => byGoal.get(goalId)?.condition ?? 0
  const sized = (entry) => !entry.small && !entry.readopted
  // From generation 13: what each goal's later reviews saw of its item, and of its recipe path.
  const mostHeld = new Map()
  const gainedInput = new Set()
  for (const record of reviews) {
    const goalId = record.goal.presentedGoalId
    const entry = original.get(goalId)
    if (entry === undefined) continue
    if (record.goal.stepEvidence === 'gainedInput') gainedInput.add(goalId)
    const self = perceptionOf.get(record.requestKey)?.selfState
    const held = (self?.inventorySummary ?? []).filter((stack) => stack.what === entry.item)
      .reduce((sum, stack) => sum + stack.count, 0)
    mostHeld.set(goalId, Math.max(mostHeld.get(goalId) ?? 0, held))
  }
  // A goal advanced through resources sight did not guarantee when it was set (r22).
  const discovered = (goalId) => {
    const entry = original.get(goalId)
    return entry !== undefined && ((mostHeld.get(goalId) ?? 0) > entry.guaranteed || gainedInput.has(goalId))
  }
  // A local goal feeds a later goal of another item, chained from it, that advanced.
  const fed = new Set([...original.entries()]
    .filter(([childId, child]) => child.chainedFromGoal !== undefined && advances(childId) >= 1 &&
      original.get(child.chainedFromGoal)?.item !== child.item)
    .map(([, child]) => child.chainedFromGoal))
  // From generation 14 a chained goal one craft application met in full is a real chain: the goal
  // it was chained from gathered the input, and the craft made the whole output (r23).
  const craftMet = new Set(reviews.filter((record) =>
    record.goal.conditionMet === true && record.goal.stepEvidence === 'gained' &&
    record.shown?.goal?.stepAction === 'craft' && record.shown?.goal?.goalId === record.goal.presentedGoalId)
    .map((record) => record.goal.presentedGoalId))
  // From generation 15 a confirmed craft's chained goal is met in full when any later perception of
  // its agent shows the count held, whether or not a review followed before it died (r24 delta).
  if (survivalRule) {
    for (const [goalId, entry] of original) {
      if (!entry.chained || craftMet.has(goalId)) continue
      const after = bodyRecords.findIndex((record) => record.requestKey === entry.adoptedBy && record.event === 'intentApplied')
      if (after < 0) continue
      const crafted = bodyRecords.some((record, index) => index > after - 2 && record.requestKey === entry.adoptedBy &&
        record.event === 'worldInteraction' && record.outcome === 'serverEffectConfirmed')
      const shown = bodyRecords.slice(after).some((record) => record.event === 'perceived' && record.agentId === entry.agentId &&
        (record.perception?.selfState?.inventorySummary ?? []).filter((stack) => stack.what === entry.item)
          .reduce((sum, stack) => sum + stack.count, 0) >= entry.count)
      if (crafted && shown) craftMet.add(goalId)
    }
  }
  const crossChained = (entry) => {
    const parent = original.get(entry.chainedFromGoal)
    return parent !== undefined && parent.item !== entry.item && advances(entry.chainedFromGoal) >= 1
  }
  const chainCounts = (goalId, entry) => advances(goalId) >= 2 ||
    (discoveryRule && craftMet.has(goalId) && crossChained(entry))
  let chainedGoals = 0
  for (const [goalId, entry] of original) {
    if (entry.chained && sized(entry) && chainCounts(goalId, entry)) chainedGoals += 1
  }
  const substantive = (goalId) => {
    const entry = original.get(goalId)
    if (entry === undefined || !sized(entry)) return false
    if (!horizonRule) return !entry.local || chainedFrom.has(goalId)
    return entry.local ? fed.has(goalId) : discovered(goalId)
  }
  const atOriginal = (goalId) => original.has(goalId) && !original.get(goalId).cut
  const multiStepAgents = new Set([...original.entries()]
    .filter(([goalId]) => advances(goalId) >= 2 && atOriginal(goalId) && substantive(goalId))
    .map(([, entry]) => entry.agentId))
  const substantiveCompletions = reviews.filter((record) => {
    const goalId = record.goal.presentedGoalId
    if (!COMPLETING_OUTCOMES.has(record.goal.outcome) || record.goal.conditionMet !== true) return false
    if (!atOriginal(goalId) || !substantive(goalId)) return false
    return advances(goalId) >= 2
  }).length
  const chainedGoalIds = new Set([...original.entries()]
    .filter(([, entry]) => entry.chained && sized(entry)).map(([goalId]) => goalId))
  const craftChainSteps = reviews.filter((record) =>
    record.intent === 'craft' && active(record) && appliedOf.get(record.requestKey) === 'accepted' &&
    chainedGoalIds.has(record.goal.goalId) &&
    (original.get(record.goal.goalId)?.adoptedBy !== record.requestKey ||
      (discoveryRule && craftMet.has(record.goal.goalId)))).length
  const exploringAgents = new Set(bodyRecords.filter((record) => {
    if (record.event !== 'decided' || record.intent !== 'moveTo') return false
    const onto = record.decision?.intent?.onto
    const offered = (perceptionOf.get(record.requestKey)?.explore ?? []).map((cell) => cell.ref)
    return onto !== undefined && offered.includes(onto) && appliedOf.get(record.requestKey) === 'accepted'
  }).map((record) => record.agentId)).size
  const answered = new Set(bodyRecords
    .filter((record) => record.event === 'decided' && record.requestKey !== undefined)
    .map((record) => record.requestKey))
  const dangerEpisodes = bodyRecords.filter((record) =>
    record.event === 'perceived' && answered.has(record.requestKey) &&
    ['threatSighted', 'damaged'].includes(record.perception?.trigger?.event ?? record.trigger)).length
  const deaths = new Set(bodyRecords.filter((record) => record.event === 'died').map((record) => record.agentId)).size
  const held = progressingTimeOf(bodyRecords, {
    endAtDeath: true, fromAdvance: discoveryRule, actingOnly: survivalRule, withoutFlights: generation >= 16
  })
  const shares = [...byAgent.values()].map((entry) => entry.active / entry.reviews)
  const measured = {
    goalActiveReviewShare: ratio(reviews.filter(active).length, reviews.length),
    minAgentGoalActiveReviewShare: shares.length === 0 ? null : Number(Math.min(...shares).toFixed(6)),
    progressingHeldTimeShare: held.fleet,
    minAgentProgressingHeldTimeShare: held.minAgent,
    idleBesideExploration,
    noOpRevises: reviews.filter(noOp).length,
    oneStepGoals: [...original.values()].filter((entry) => entry.small).length,
    readoptedGoals: [...original.values()].filter((entry) => entry.readopted).length,
    substantiveMultiStepAgents: multiStepAgents.size,
    substantiveCompletions,
    chainedGoals,
    distinctGoalItems: [...items].filter((item) => typeof item === 'string').length,
    craftChainSteps,
    exploringAgents,
    dangerEpisodes,
    deaths,
    ...(horizonRule
      ? {
          horizonGoals: [...original.values()].filter((entry) => entry.horizon && !entry.local).length,
          discoveredGoals: [...original.keys()].filter((goalId) => !original.get(goalId).local && discovered(goalId)).length,
          crowdedGoals: reviews.filter((record) => record.goal.outcome === 'goalCrowdsExploration').length
        }
      : {}),
    ...(discoveryRule
      ? {
          discoveryGoals: reviews.filter((record) => record.goal.discovery === true &&
            ['goalAdopted', 'goalReplaced', 'goalCompletedAdopted'].includes(record.goal.outcome)).length,
          discoveryReveals: reviews.filter((record) => record.goal.presentedDiscovery === true &&
            record.goal.stepEvidence === 'revealed').length,
          discoveryTransitions: reviews.filter((record) => record.goal.discoveredFrom !== undefined).length,
          deferredReviews: reviews.filter(deferred).length
        }
      : {})
  }
  const at = (value, bar) => (value ?? 0) >= bar
  const cleared = bars !== undefined &&
    at(measured.goalActiveReviewShare, bars.goalActiveReviewShare) &&
    at(measured.minAgentGoalActiveReviewShare, bars.minAgentGoalActiveReviewShare) &&
    at(measured.progressingHeldTimeShare, bars.progressingHeldTimeShare) &&
    at(measured.minAgentProgressingHeldTimeShare, bars.minAgentProgressingHeldTimeShare) &&
    idleBesideExploration <= bars.maxIdleBesideExploration &&
    at(measured.substantiveMultiStepAgents, bars.substantiveMultiStepAgents) &&
    at(substantiveCompletions, bars.substantiveCompletions) &&
    at(chainedGoals, bars.chainedGoals) &&
    at(craftChainSteps, bars.craftChainSteps) &&
    (discoveryRule || (
      at(measured.distinctGoalItems, bars.distinctGoalItems) &&
      at(exploringAgents, bars.exploringAgents) &&
      at(dangerEpisodes, bars.dangerEpisodes)))
  return {
    bars: bars ?? null,
    measured,
    ...(discoveryRule ? { descriptive: DESCRIPTIVE_MEASURES } : {}),
    // From generation 15 what describes the run sits apart from the evidence the verdict reads.
    ...(survivalRule ? { description: descriptionOf(measured, bodyRecords) } : {}),
    // The two review shares are close to fixed by the request schema, which offers no idle or
    // rest while a goal is held: they are reported beside the behaviour, not as it (r20).
    schemaShaped: ['goalActiveReviewShare', 'minAgentGoalActiveReviewShare'],
    verdict: cleared ? 'cleared' : 'notCleared'
  }
}

/**
 * The fleet's and the least agent's share of server time holding a goal that advanced within
 * PROGRESS_WINDOW_TICKS: for each agent, from its first perception to the run's last record, the
 * time after each decided record whose resolution left a goal held, up to its next decided record,
 * counted only until the window since the goal was set or last advanced closes.
 */
function progressingTimeOf (bodyRecords, { endAtDeath = false, fromAdvance = false, actingOnly = false, withoutFlights = false } = {}) {
  // From generation 15 a stretch counts only after an action the body accepted: an idle, a rest
  // or a refused step holds the goal without pursuing it (r24: theta's refused walk and beta's idle).
  const acceptedKeys = new Set(bodyRecords
    .filter((record) => record.event === 'intentApplied' && record.outcome === 'accepted')
    .map((record) => record.requestKey))
  const runEnd = maximumTick(bodyRecords)
  const diedAt = new Map(bodyRecords
    .filter((record) => endAtDeath && record.event === 'died' && record.agentId !== undefined)
    .map((record) => [record.agentId, record.tick]))
  // From generation 16 a flight's interval, from its decision to its terminal, is no pursuit (r27:
  // about 1500 ticks of flight counted as progressing goal time).
  const flights = withoutFlights ? flightIntervalsOf(bodyRecords, runEnd) : new Map()
  const agents = new Map()
  const count = (entry, until) => {
    if (entry.since === null) return
    const open = Math.min(until, entry.progressed + PROGRESS_WINDOW_TICKS)
    if (open > entry.since) entry.held += uncovered(entry.since, open, flights.get(entry.agentId) ?? [])
  }
  for (const record of bodyRecords) {
    if (record.agentId === undefined || !Number.isInteger(record.tick)) continue
    if (!agents.has(record.agentId)) {
      if (record.event !== 'perceived') continue
      agents.set(record.agentId, { agentId: record.agentId, start: record.tick, held: 0, since: null, goal: null, progressed: 0 })
    }
    const entry = agents.get(record.agentId)
    if (record.event !== 'decided' || record.goal === undefined) continue
    count(entry, record.tick)
    const goal = record.goal
    const advanced = goal.conditionAdvanced === true ||
      ['gainedInput', 'revealed'].includes(goal.stepEvidence)
    const acting = !actingOnly || (acceptedKeys.has(record.requestKey) && !PASSIVE_INTENTS.includes(record.intent))
    if (goal.goalId !== undefined && acting) {
      // From generation 14 a goal's held time progresses only from its first actual advance: an
      // adoption is a claim, not evidence (r23).
      if (goal.goalId !== entry.goal) entry.progressed = fromAdvance ? -Infinity : record.tick
      else if (advanced) entry.progressed = record.tick
      entry.goal = goal.goalId
      entry.since = record.tick
    } else {
      entry.goal = null
      entry.since = null
    }
  }
  let held = 0
  let span = 0
  const shares = []
  for (const [agentId, entry] of agents) {
    const end = diedAt.get(agentId) ?? runEnd
    count(entry, end)
    const window = end - entry.start
    if (window <= 0) continue
    held += entry.held
    span += window
    shares.push(entry.held / window)
  }
  return {
    fleet: ratio(held, span),
    minAgent: shares.length === 0 ? null : Number(Math.min(...shares).toFixed(6))
  }
}

/** The measures that describe a run and decide nothing, apart from the verdict evidence. */
function descriptionOf (measured, bodyRecords = []) {
  const described = ['distinctGoalItems', 'exploringAgents', 'dangerEpisodes', 'deaths', 'horizonGoals', 'crowdedGoals',
    'discoveryGoals', 'discoveryReveals', 'discoveryTransitions', 'deferredReviews']
  // A dig's one pickup of its own goal item, by body terminal: a peer's pickup first is
  // `collectItemGone`, the vanilla race; none is a decision, so none is an action class.
  const pickups = {}
  for (const record of bodyRecords) {
    if (record.event === 'ownDropPickup') pickups[record.outcome] = (pickups[record.outcome] ?? 0) + 1
  }
  return {
    ...Object.fromEntries(described.filter((name) => name in measured).map((name) => [name, measured[name]])),
    ownDropPickups: pickups
  }
}

/**
 * Each agent's flights as merged [from, to] tick intervals: from the flight's decision to the first
 * record that ends it - its retreat, its refusal, or a discard - or the run's end.
 */
function flightIntervalsOf (bodyRecords, runEnd) {
  const decidedAt = new Map()
  const endedAt = new Map()
  for (const record of bodyRecords) {
    if (record.requestKey === undefined || !Number.isInteger(record.tick)) continue
    if (record.event === 'decided' && record.intent === 'flee') decidedAt.set(record.requestKey, record)
    const ends = record.event === 'retreating' || record.event === 'decisionDiscarded' ||
      (record.event === 'intentApplied' && record.outcome !== 'accepted')
    if (ends && !endedAt.has(record.requestKey)) endedAt.set(record.requestKey, record.tick)
  }
  const byAgent = new Map()
  for (const [key, decided] of decidedAt) {
    const intervals = byAgent.get(decided.agentId) ?? []
    intervals.push([decided.tick, endedAt.get(key) ?? runEnd])
    byAgent.set(decided.agentId, intervals)
  }
  for (const [agentId, intervals] of byAgent) {
    intervals.sort((a, b) => a[0] - b[0])
    const merged = []
    for (const [from, to] of intervals) {
      const last = merged[merged.length - 1]
      if (last !== undefined && from <= last[1]) last[1] = Math.max(last[1], to)
      else merged.push([from, to])
    }
    byAgent.set(agentId, merged)
  }
  return byAgent
}

/** How many ticks of [from, to) no interval of the merged [intervals] covers. */
function uncovered (from, to, intervals) {
  let length = to - from
  for (const [start, end] of intervals) {
    const overlap = Math.min(to, end) - Math.max(from, start)
    if (overlap > 0) length -= overlap
  }
  return length
}

/**
 * When the run's exposure ended and what was witnessed after it: the plugin's own `exposureEnded`
 * tick, or - for evidence from before the plugin ended it - the tick cognition admission closed,
 * and the agents that died and the damage witnessed after that tick. From generation 18 a death
 * after the end is not readiness (r34: delta and gamma died in the drain 78 and 124 ticks after
 * admission closed, and both deaths counted).
 */
function exposureOf (bodyRecords, harnessRecords) {
  const plugin = bodyRecords.find((record) => record.event === 'exposureEnded')
  const admission = harnessRecords.find((record) =>
    ['cognitionAdmissionEnded', 'cognitionAdmissionClosed'].includes(record.event) && Number.isInteger(record.endTick))
  const end = plugin !== undefined
    ? { endTick: plugin.tick, source: 'exposureEnded' }
    : admission === undefined ? undefined : { endTick: admission.endTick, source: admission.event }
  if (end === undefined) return undefined
  const after = bodyRecords.filter((record) => record.tick > end.endTick)
  return {
    ...end,
    deathsAfter: [...new Set(after.filter((record) => record.event === 'died').map((record) => record.agentId))].sort(),
    damageAfter: after.filter((record) => record.event === 'damageWitnessed').length,
    drained: bodyRecords.filter(isExposureEnding).length
  }
}

/** The goal outcomes of a decision that took up a goal of its own. */
const ADOPTING_OUTCOMES = new Set(['goalAdopted', 'goalReplaced', 'goalCompletedAdopted'])

/**
 * The tick the run's first night fell, from the first perception taken by day: when it was taken
 * plus the ticks it stated to nightfall. `undefined` when no perception stated one.
 */
function nightfallOf (bodyRecords) {
  const first = bodyRecords.find((record) =>
    record.event === 'perceived' && Number.isInteger(record.perception?.capturedTick) &&
    record.perception?.selfState?.world?.dayPhase !== 'night' &&
    Number.isInteger(record.perception?.selfState?.world?.ticksUntilNightfall))
  return first === undefined
    ? undefined
    : first.perception.capturedTick + first.perception.selfState.world.ticksUntilNightfall
}

/**
 * Each agent's sealed intervals, [from, to), from the server's `shelterChanged` records: sealed in
 * from a `sheltered` record until the next `exposed` one, the agent's death, or [end].
 */
function shelteredIntervalsOf (bodyRecords, end) {
  const byAgent = new Map()
  const open = new Map()
  const close = (agentId, tick) => {
    if (!open.has(agentId)) return
    const intervals = byAgent.get(agentId) ?? []
    intervals.push([open.get(agentId), Math.min(tick, end)])
    byAgent.set(agentId, intervals)
    open.delete(agentId)
  }
  for (const record of bodyRecords) {
    if (record.agentId === undefined || !Number.isInteger(record.tick) || record.tick > end) continue
    if (record.event === 'shelterChanged' && record.shelter?.sealed === true && !open.has(record.agentId)) {
      open.set(record.agentId, record.tick)
    } else if ((record.event === 'shelterChanged' && record.shelter?.sealed === false) || record.event === 'died') {
      close(record.agentId, record.tick)
    }
  }
  for (const agentId of [...open.keys()]) close(agentId, end)
  return byAgent
}

/**
 * The run's shelter from generation 19 (the r36 audit): whether agents chose shelter and built it
 * before night, measured from their own goal evidence and the server's sealed record.
 *
 * - `shelterProgressAgentsBeforeNight`, the pre-registered bar: agents whose own adopted shelter
 *   goal was credited a `covered` step before nightfall, the step itself accepted by the body and,
 *   for a dig or a placement, confirmed by the server's interaction witness;
 * - reported: shelter goals adopted, completions of a sealed shelter, cover quality (the most sides
 *   each agent's shelter closed), time sheltered from nightfall to the exposure's end, damage
 *   witnessed while sealed in, and the agents alive, and sheltered, at the exposure's end.
 */
function shelterOf (bodyRecords, exposure, bars) {
  const runEnd = maximumTick(bodyRecords)
  const end = exposure?.endTick ?? runEnd
  const nightfall = nightfallOf(bodyRecords)
  const decided = bodyRecords.filter((record) => record.event === 'decided' && record.goal !== undefined)
  const adopted = new Set(decided
    .filter((record) => record.goal.shelter === true && ADOPTING_OUTCOMES.has(record.goal.outcome))
    .map((record) => `${record.agentId}:${record.goal.goalId}`))
  const appliedOf = new Map(bodyRecords
    .filter((record) => record.event === 'intentApplied' && record.requestKey !== undefined)
    .map((record) => [record.requestKey, record]))
  const confirmed = new Set(bodyRecords
    .filter((record) => record.event === 'worldInteraction' && record.outcome === 'serverEffectConfirmed')
    .map((record) => record.requestKey))
  const witnessedStep = (record) => {
    const key = `${record.agentId}:${record.goal.steppedBy}`
    const applied = appliedOf.get(key)
    if (applied?.outcome !== 'accepted') return false
    return !['dig', 'place'].includes(applied.intent) || confirmed.has(key)
  }
  const progressing = new Set(decided.filter((record) =>
    record.goal.presentedShelter === true && record.goal.stepEvidence === 'covered' &&
    adopted.has(`${record.agentId}:${record.goal.presentedGoalId}`) &&
    nightfall !== undefined && record.tick < nightfall && witnessedStep(record))
    .map((record) => record.agentId))
  const completions = decided.filter((record) =>
    record.goal.presentedShelter === true && record.goal.conditionMet === true &&
    COMPLETING_OUTCOMES.has(record.goal.outcome)).length
  const quality = {}
  for (const record of decided) {
    const closed = record.goal.presentedShelter === true ? record.goal.cover?.closed : undefined
    if (Number.isInteger(closed)) quality[record.agentId] = Math.max(quality[record.agentId] ?? 0, closed)
  }
  const intervals = shelteredIntervalsOf(bodyRecords, end)
  const night = nightfall ?? end
  let sheltered = 0
  for (const spans of intervals.values()) {
    for (const [from, to] of spans) sheltered += Math.max(0, to - Math.max(from, night))
  }
  const inside = (agentId, tick) => (intervals.get(agentId) ?? []).some(([from, to]) => tick >= from && tick < to)
  const agents = new Set(bodyRecords.filter((record) => record.event === 'perceived').map((record) => record.agentId))
  const dead = new Set(bodyRecords.filter((record) => record.event === 'died' && record.tick <= end).map((record) => record.agentId))
  const survivors = [...agents].filter((agentId) => !dead.has(agentId)).sort()
  const measured = {
    shelterProgressAgentsBeforeNight: progressing.size,
    shelterGoals: adopted.size,
    shelterCompletions: completions,
    coverQuality: Object.fromEntries(Object.entries(quality).sort()),
    timeShelteredTicks: sheltered,
    damageWhileSheltered: bodyRecords.filter((record) =>
      record.event === 'damageWitnessed' && record.tick <= end && inside(record.agentId, record.tick)).length,
    survivedExposure: survivors.length,
    shelteredAtExposureEnd: survivors.filter((agentId) => inside(agentId, end - 1)).length
  }
  const bar = bars?.shelterProgressAgentsBeforeNight
  return {
    nightfallTick: nightfall ?? null,
    endTick: end,
    bars: bar === undefined ? null : { shelterProgressAgentsBeforeNight: bar },
    measured,
    progressingAgents: [...progressing].sort(),
    verdict: bar === undefined ? 'unbarred' : measured.shelterProgressAgentsBeforeNight >= bar ? 'cleared' : 'notCleared'
  }
}

/** The health the perception a decision answered showed, or -Infinity when none was recorded. */
function perceivedHealthOf (own) {
  const health = own.find((record) => record.event === 'perceived')?.perception?.selfState?.health
  return Number.isFinite(health) ? health : -Infinity
}

function readinessMetrics ({ bodyRecords, harnessRecords, metrics, generation = 3, bars }) {
  const typed = generation >= 4
  const goalIncoherentTags = goalIncoherentTagsOf(generation)
  const blockerClasses = typed
    ? [
        ...BLOCKER_CLASSES,
        'goalIncoherent',
        ...(generation >= 9 ? FLIGHT_BLOCKER_CLASSES : []),
        ...(generation >= 11 ? OWN_DROP_BLOCKER_CLASSES : []),
        ...(generation >= 12 ? SELF_TRAPPED_BLOCKER_CLASSES : []),
        ...(generation >= 16 ? WATER_BLOCKER_CLASSES : []),
        ...(generation >= 17 ? DANGER_OVERLAP_BLOCKER_CLASSES : []),
        ...(generation >= 18 ? TERRAIN_BLOCKER_CLASSES : [])
      ]
    : BLOCKER_CLASSES
  const raceClasses = raceClassesOf(generation)
  const settledAfterDeath = new Set(
    Object.values(metrics.witnessedDeaths?.byAgent ?? {}).flatMap((entry) => entry.settledAfterDeath ?? [])
  )
  const refusedKeys = new Set(
    harnessRecords.filter((record) => record.event === 'cognitionRefused').map((record) => record.requestKey)
  )
  const admissionEnded = metrics.admissionEnd !== undefined
  const byKey = new Map()
  for (const record of bodyRecords) {
    if (record.requestKey === undefined) continue
    const chain = byKey.get(record.requestKey) ?? []
    chain.push(record)
    byKey.set(record.requestKey, chain)
  }
  const decisions = []
  bodyRecords.forEach((record, index) => {
    if (record.event === 'decided') decisions.push({ record, index })
  })
  const nextIndex = new Map()
  for (let position = decisions.length - 1, next = new Map(); position >= 0; position -= 1) {
    const { record, index } = decisions[position]
    nextIndex.set(record.requestKey, next.get(record.agentId) ?? bodyRecords.length)
    next.set(record.agentId, index)
  }

  const byClass = Object.fromEntries([...raceClasses, 'confirmed', ...blockerClasses].map((name) => [name, 0]))
  const nonSuccess = []
  let total = 0
  for (const { record: decision, index } of decisions) {
    const own = byKey.get(decision.requestKey) ?? []
    // A passive decision is no action, unless its own goal refused it: an idle under a step that
    // names a collect is a decision that contradicts itself (r11 delta, body:00000241).
    const refusedForGoal = typed && own.some((entry) => entry.event === 'intentApplied' && goalIncoherentTags.has(entry.outcome))
    if (PASSIVE_INTENTS.includes(decision.intent) && !refusedForGoal) continue
    total += 1
    const terminals = own.filter((record) => record.event === 'intentApplied' || record.event === 'decisionDiscarded')
    let ending
    if (settledAfterDeath.has(decision.requestKey)) {
      ending = { class: 'death', evidence: terminals[0] }
    } else if (terminals.length !== 1) {
      ending = { class: 'untypedTerminal', evidence: terminals[1] }
    } else if (terminals[0].event === 'decisionDiscarded') {
      ending = { class: 'protocolError', evidence: terminals[0] }
    } else if (terminals[0].outcome === 'accepted') {
      const window = bodyRecords.slice(index + 1, nextIndex.get(decision.requestKey))
      ending = acceptedClass(decision, byKey, window, generation)
      ending.evidence ??= terminals[0]
    } else {
      ending = { class: refusalClass(terminals[0].outcome, generation), evidence: terminals[0] }
      if (generation >= 15 && terminals[0].outcome === 'attackHealthBound' && perceivedHealthOf(own) > ATTACK_MIN_HEALTH) {
        ending.class = 'concurrent'
      }
      if (generation >= 18 && ending.class === TERRAIN_CLASS && decision.intent === 'collect') {
        // Verified ground excuses even the agent's own drop: it lodged where no body stands.
        const terrain = terminals[0].terrain
        if (terrain?.pickupCells !== 0 && terrain?.walkable !== false) {
          ending.class = ownDropUnreached(decision, own) ? 'ownDropUnreached' : 'unverifiedTerrain'
        }
      } else if (generation >= 11 && ending.class === TERRAIN_CLASS && ownDropUnreached(decision, own)) {
        ending.class = 'ownDropUnreached'
      }
      if (generation >= 12 && ending.class === TERRAIN_CLASS && decision.intent === 'flee' &&
          dugOwnSupport(bodyRecords, decision, generation)) {
        ending.class = 'selfTrapped'
      }
      if (generation >= 16 && ending.class === TERRAIN_CLASS && inWater(own)) ending.class = 'waterTrapped'
      if (generation >= 20 && terminals[0].outcome === 'controlNotReady') {
        const running = ownRequestRunning(bodyRecords, decision, terminals[0])
        if (running === 'earlier') ending.class = 'dangerOverlap'
        if (running === 'newer') ending.class = 'superseded'
      } else if (generation >= 17 && terminals[0].outcome === 'controlNotReady' && decision.intent === 'flee' &&
          ownFlightRunning(bodyRecords, decision, terminals[0])) {
        ending.class = 'dangerOverlap'
      }
      if (generation >= 20 && terminals[0].outcome === 'placeDestinationBlocked' && offeredClosing(decision, own)) {
        ending.class = 'targetRace'
      }
      if (generation >= 16 && ending.class === 'inapplicable' && REACH_REFUSAL.test(terminals[0].outcome) &&
          movedAfterPerception(bodyRecords, decision, own, terminals[0])) {
        ending.class = OWN_MOVEMENT_RACE_CLASS
      }
    }
    if (!Object.hasOwn(byClass, ending.class)) ending.class = 'unexplainedRefusal'
    byClass[ending.class] += 1
    if (ending.class !== 'confirmed') {
      nonSuccess.push({
        requestKey: decision.requestKey,
        agentId: decision.agentId,
        intent: decision.intent,
        class: ending.class,
        race: raceClasses.includes(ending.class),
        evidence: evidenceOf(ending.evidence)
      })
    }
  }
  // From generation 17 each paid danger answer newer damage preempted is an action the gate sees:
  // superseded by the newer answer, a race and never a loss (r33: fourteen were invisible to it).
  const preemptedAnswers = bodyRecords.filter(isPreempted)
  if (generation >= 17) {
    for (const record of preemptedAnswers) {
      total += 1
      byClass.superseded += 1
      nonSuccess.push({
        requestKey: record.requestKey,
        agentId: record.agentId,
        intent: record.intent ?? null,
        class: 'superseded',
        race: true,
        evidence: evidenceOf(record)
      })
    }
  }

  // A perception the mind was asked about and did not answer with a decision is an invalid
  // decision; one it was deliberately not asked about, or that the run's own end cut off, is not.
  const cognitionFailures = bodyRecords.filter((record) =>
    record.event === 'cognitionUnavailable' &&
    !isCoalesced(record) &&
    !(admissionEnded && isShutdownEnding(record)) &&
    !(generation >= 18 && isExposureEnding(record)) &&
    !refusedKeys.has(record.requestKey))
  const answered = decisions.length + cognitionFailures.length
  const invalidDecisions = cognitionFailures.length +
    INVALID_DECISION_CLASSES.reduce((sum, name) => sum + byClass[name], 0) +
    (typed ? byClass.goalIncoherent : 0)
  const untyped = UNTYPED_CLASSES.reduce((sum, name) => sum + byClass[name], 0)
  const checks = bodyRecords.filter((record) => record.event === 'worldInteraction')
  const disputes = bodyRecords.filter((record) => record.event === 'witnessDisputed')
  const confirmedChecks = checks.filter((record) => record.outcome === 'serverEffectConfirmed').length
  const rates = {
    decisionValidity: ratio(answered - invalidDecisions, answered),
    typedTerminalAccounting: ratio(total - untyped, total),
    serverTruthfulness: ratio(confirmedChecks, checks.length + disputes.length)
  }
  const blockers = Object.fromEntries(blockerClasses.map((name) => [name, byClass[name]]))
  const passes = (rate) => rate === null || rate === 1
  const exposure = generation >= 18 ? exposureOf(bodyRecords, harnessRecords) : undefined
  const mechanicallyReady = Object.values(rates).every(passes) &&
    Object.values(blockers).every((count) => count === 0) &&
    cognitionFailures.length === 0 &&
    disputes.length === 0 &&
    (exposure?.deathsAfter ?? []).length === 0
  const coherence = typed ? typedGoalCoherenceOf(bodyRecords, generation, bars) : undefined
  const shelter = generation >= 19 ? shelterOf(bodyRecords, exposure, bars) : undefined
  const ready = mechanicallyReady && (coherence === undefined || coherence.verdict === 'coherent') &&
    shelter?.verdict !== 'notCleared'

  return {
    version: [
      READINESS_VERSION,
      COHERENT_READINESS_VERSION,
      EVIDENCED_READINESS_VERSION,
      GROUNDED_READINESS_VERSION,
      TOLD_READINESS_VERSION,
      COLLECTED_READINESS_VERSION,
      PURSUED_READINESS_VERSION,
      WOODED_READINESS_VERSION,
      SUBSTANTIVE_READINESS_VERSION,
      GROUNDED_GOAL_READINESS_VERSION,
      HORIZON_READINESS_VERSION,
      DISCOVERY_READINESS_VERSION,
      SURVIVAL_READINESS_VERSION,
      WATER_READINESS_VERSION,
      DANGER_READINESS_VERSION,
      EXPOSURE_READINESS_VERSION,
      SHELTER_READINESS_VERSION,
      HELD_OBJECTIVE_READINESS_VERSION
    ][Math.min(Math.max(generation - 3, 0), 17)],
    actions: {
      total,
      byClass: Object.fromEntries(Object.entries(byClass).sort()),
      nonSuccess
    },
    cognitionFailures: Object.fromEntries(
      Object.entries(cognitionFailures.reduce((counts, record) => {
        const outcome = record.outcome ?? 'unclassified'
        counts[outcome] = (counts[outcome] ?? 0) + 1
        return counts
      }, {})).sort()
    ),
    serverDisputes: disputes.length,
    rates,
    blockers,
    races: nonSuccess.filter((entry) => entry.race).length,
    // What the JVM decided about calls before any reached the mind (r33: eight callReserved
    // refusals of alpha's danger reported nowhere).
    ...(generation >= 17
      ? {
          reporting: {
            callReserved: bodyRecords.filter((record) =>
              record.event === 'cognitionUnavailable' && record.outcome === 'callReserved').length,
            tierFallbacks: bodyRecords.filter((record) => record.event === 'cognitionTierFallback').length,
            preemptedAnswers: preemptedAnswers.length
          }
        }
      : {}),
    ...(exposure === undefined ? {} : { exposure }),
    ...(shelter === undefined ? {} : { shelter }),
    ...(typed
      ? { goalCoherence: coherence, mechanicalVerdict: mechanicallyReady ? 'ready' : 'notReady' }
      : { goalCoherence: goalCoherenceOf(bodyRecords) }),
    verdict: ready ? 'ready' : 'notReady'
  }
}

module.exports = {
  BLOCKER_CLASSES,
  HELD_OBJECTIVE_GOAL_INCOHERENT_TAGS,
  HELD_OBJECTIVE_READINESS_VERSION,
  SHELTER_GOAL_INCOHERENT_TAGS,
  SHELTER_READINESS_VERSION,
  SURVIVAL_GOAL_INCOHERENT_TAGS,
  SURVIVAL_READINESS_VERSION,
  WATER_READINESS_VERSION,
  DANGER_READINESS_VERSION,
  EXPOSURE_READINESS_VERSION,
  DISCOVERY_GOAL_INCOHERENT_TAGS,
  DISCOVERY_READINESS_VERSION,
  HORIZON_GOAL_INCOHERENT_TAGS,
  HORIZON_READINESS_VERSION,
  COHERENT_READINESS_VERSION,
  COLLECTED_GOAL_INCOHERENT_TAGS,
  COLLECTED_READINESS_VERSION,
  PURSUED_GOAL_INCOHERENT_TAGS,
  PURSUED_READINESS_VERSION,
  WOODED_GOAL_INCOHERENT_TAGS,
  WOODED_READINESS_VERSION,
  SUBSTANTIVE_GOAL_INCOHERENT_TAGS,
  SUBSTANTIVE_READINESS_VERSION,
  EVIDENCED_GOAL_INCOHERENT_TAGS,
  EVIDENCED_READINESS_VERSION,
  GOAL_INCOHERENT_TAGS,
  GROUNDED_GOAL_INCOHERENT_TAGS,
  GROUNDED_READINESS_VERSION,
  RACE_CLASSES,
  READINESS_VERSION,
  TERRAIN_CLASS,
  TOLD_GOAL_INCOHERENT_TAGS,
  TOLD_READINESS_VERSION,
  readinessMetrics,
  refusalClass
}
