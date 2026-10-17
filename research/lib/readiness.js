'use strict'

const { isCoalesced, isShutdownEnding } = require('./danger.js')

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
  if (generation >= 10) return WOODED_GOAL_INCOHERENT_TAGS
  if (generation >= 9) return PURSUED_GOAL_INCOHERENT_TAGS
  if (generation >= 8) return COLLECTED_GOAL_INCOHERENT_TAGS
  if (generation >= 7) return TOLD_GOAL_INCOHERENT_TAGS
  if (generation >= 6) return GROUNDED_GOAL_INCOHERENT_TAGS
  return generation >= 5 ? EVIDENCED_GOAL_INCOHERENT_TAGS : GOAL_INCOHERENT_TAGS
}
// The evidence that may credit a step; `none`, or no evidence stated at all, never does.
const CREDITING_STEP_EVIDENCE = new Set(['arrived', 'gained', 'gainedInput', 'dropped', 'revealed'])

const PASSIVE_INTENTS = Object.freeze(['idle', 'rest'])
const INTERACTION_INTENTS = Object.freeze(['dig', 'collect', 'place', 'equip', 'craft', 'attack', 'sleep'])

// Every class an action can end in. `confirmed` and the race classes are success or a correct
// fail-closed ending; every other class is a blocker.
const RACE_CLASSES = Object.freeze(['targetRace', 'superseded', 'concurrent', 'death'])
// From generation 7 a refusal the ground gave is a legitimate ending of its own as well.
const TERRAIN_CLASS = 'terrain'

/** The legitimate non-success classes under gate [generation]. */
function raceClassesOf (generation) {
  return generation >= 7 ? [...RACE_CLASSES, TERRAIN_CLASS] : RACE_CLASSES
}
// From generation 9, a flight the evidence cannot show was worth taking, and one from a peer.
const FLIGHT_BLOCKER_CLASSES = Object.freeze(['needlessFlight', 'peerFlight'])
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
function refusalClass (tag, generation = 11) {
  if (goalIncoherentTagsOf(generation).has(tag)) return 'goalIncoherent'
  if (generation >= 7 && TERRAIN_TAGS.has(tag)) return TERRAIN_CLASS
  if (generation >= 5 && tag === 'digWouldTrap') return 'inapplicable'
  if (generation >= 11 && tag === 'digDropUnreachable') return 'inapplicable'
  if (tag === 'controlNotReady') return 'concurrent'
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
    ...(generation >= 8 ? { ignoredOwnDrops: 0 } : {})
  }
  for (const record of bodyRecords) {
    const goal = record.goal
    if (record.event !== 'decided' || goal === undefined) continue
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
    if (evidenced && goal.stepAdvanced === true && !CREDITING_STEP_EVIDENCE.has(goal.stepEvidence)) {
      counts.unevidencedCredits += 1
    }
    byGoal.set(goal.presentedGoalId, entry)
  }
  const goals = [...byGoal.values()]
  const multiStepGoals = goals.filter((goal) => goal.condition >= 2).length
  const pursuit = generation >= 10
    ? woodedPursuitOf(bodyRecords, byGoal, bars)
    : generation >= 9 ? pursuitOf(bodyRecords, byGoal, bars) : undefined
  const coherent = (pursuit === undefined || pursuit.verdict === 'cleared') && counts.stepMismatches === 0 && counts.unmetCompletions === 0 && counts.metIgnored === 0 &&
    multiStepGoals >= 1 &&
    (!evidenced || (counts.unevidencedCredits === 0 && counts.unobtainableConditions === 0)) &&
    (generation < 6 || (counts.alreadyMetConditions === 0 && counts.idleSteps === 0)) &&
    (generation < 7 || (counts.unansweredGoals === 0 && counts.unproductiveSteps === 0)) &&
    (generation < 8 || counts.ignoredOwnDrops === 0)
  return {
    ...counts,
    goalsMeasured: goals.length,
    multiStepGoals,
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
 * The fleet's and the least-holding agent's share of server time spent holding a goal: for each
 * agent, from its first perception to the run's last record, the time after each decided record
 * whose resolution left a goal held, up to its next decided record.
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

function readinessMetrics ({ bodyRecords, harnessRecords, metrics, generation = 3, bars }) {
  const typed = generation >= 4
  const goalIncoherentTags = goalIncoherentTagsOf(generation)
  const blockerClasses = typed
    ? [...BLOCKER_CLASSES, 'goalIncoherent', ...(generation >= 9 ? FLIGHT_BLOCKER_CLASSES : [])]
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

  // A perception the mind was asked about and did not answer with a decision is an invalid
  // decision; one it was deliberately not asked about, or that the run's own end cut off, is not.
  const cognitionFailures = bodyRecords.filter((record) =>
    record.event === 'cognitionUnavailable' &&
    !isCoalesced(record) &&
    !(admissionEnded && isShutdownEnding(record)) &&
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
  const mechanicallyReady = Object.values(rates).every(passes) &&
    Object.values(blockers).every((count) => count === 0) &&
    cognitionFailures.length === 0 &&
    disputes.length === 0
  const coherence = typed ? typedGoalCoherenceOf(bodyRecords, generation, bars) : undefined
  const ready = mechanicallyReady && (coherence === undefined || coherence.verdict === 'coherent')
  return {
    version: [
      READINESS_VERSION,
      COHERENT_READINESS_VERSION,
      EVIDENCED_READINESS_VERSION,
      GROUNDED_READINESS_VERSION,
      TOLD_READINESS_VERSION,
      COLLECTED_READINESS_VERSION,
      PURSUED_READINESS_VERSION,
      WOODED_READINESS_VERSION
    ][Math.min(Math.max(generation - 3, 0), 7)],
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
    ...(typed
      ? { goalCoherence: coherence, mechanicalVerdict: mechanicallyReady ? 'ready' : 'notReady' }
      : { goalCoherence: goalCoherenceOf(bodyRecords) }),
    verdict: ready ? 'ready' : 'notReady'
  }
}

module.exports = {
  BLOCKER_CLASSES,
  COHERENT_READINESS_VERSION,
  COLLECTED_GOAL_INCOHERENT_TAGS,
  COLLECTED_READINESS_VERSION,
  PURSUED_GOAL_INCOHERENT_TAGS,
  PURSUED_READINESS_VERSION,
  WOODED_GOAL_INCOHERENT_TAGS,
  WOODED_READINESS_VERSION,
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
