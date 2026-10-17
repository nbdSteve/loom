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
// The evidence that may credit a step; `none`, or no evidence stated at all, never does.
const CREDITING_STEP_EVIDENCE = new Set(['arrived', 'gained', 'dropped'])

const PASSIVE_INTENTS = Object.freeze(['idle', 'rest'])
const INTERACTION_INTENTS = Object.freeze(['dig', 'collect', 'place', 'equip', 'craft', 'attack', 'sleep'])

// Every class an action can end in. `confirmed` and the race classes are success or a correct
// fail-closed ending; every other class is a blocker.
const RACE_CLASSES = Object.freeze(['targetRace', 'superseded', 'concurrent', 'death'])
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
function refusalClass (tag, generation = 5) {
  if ((generation >= 5 ? EVIDENCED_GOAL_INCOHERENT_TAGS : GOAL_INCOHERENT_TAGS).has(tag)) return 'goalIncoherent'
  if (generation >= 5 && tag === 'digWouldTrap') return 'inapplicable'
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

/** The terminal of one accepted action, and the record that states it. */
function acceptedClass (decision, byKey, window) {
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
    return { class: RETREAT_CLASSES[retreat.outcome] ?? 'untypedTerminal', evidence: retreat }
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
function typedGoalCoherenceOf (bodyRecords, generation = 4) {
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
    ...(evidenced ? { unevidencedCredits: 0, unobtainableConditions: 0 } : {})
  }
  for (const record of bodyRecords) {
    const goal = record.goal
    if (record.event !== 'decided' || goal === undefined) continue
    if (goal.outcome === 'goalStepMismatch') counts.stepMismatches += 1
    if (goal.outcome === 'goalCompletionUnmet') counts.unmetCompletions += 1
    if (goal.outcome === 'goalStalled') counts.stalled += 1
    if (evidenced && goal.outcome === 'goalSuccessUnobtainable') counts.unobtainableConditions += 1
    if (goal.conditionMet === true) {
      counts.metConditions += 1
      if (goal.outcome === 'goalCompleted') counts.metCompleted += 1
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
  const coherent = counts.stepMismatches === 0 && counts.unmetCompletions === 0 && counts.metIgnored === 0 &&
    multiStepGoals >= 1 &&
    (!evidenced || (counts.unevidencedCredits === 0 && counts.unobtainableConditions === 0))
  return { ...counts, goalsMeasured: goals.length, multiStepGoals, verdict: coherent ? 'coherent' : 'incoherent' }
}

function readinessMetrics ({ bodyRecords, harnessRecords, metrics, generation = 3 }) {
  const typed = generation >= 4
  const goalIncoherentTags = generation >= 5 ? EVIDENCED_GOAL_INCOHERENT_TAGS : GOAL_INCOHERENT_TAGS
  const blockerClasses = typed ? [...BLOCKER_CLASSES, 'goalIncoherent'] : BLOCKER_CLASSES
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

  const byClass = Object.fromEntries([...RACE_CLASSES, 'confirmed', ...blockerClasses].map((name) => [name, 0]))
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
      ending = acceptedClass(decision, byKey, window)
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
        race: RACE_CLASSES.includes(ending.class),
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
  const coherence = typed ? typedGoalCoherenceOf(bodyRecords, generation) : undefined
  const ready = mechanicallyReady && (coherence === undefined || coherence.verdict === 'coherent')
  return {
    version: generation >= 5 ? EVIDENCED_READINESS_VERSION : typed ? COHERENT_READINESS_VERSION : READINESS_VERSION,
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
  EVIDENCED_GOAL_INCOHERENT_TAGS,
  EVIDENCED_READINESS_VERSION,
  GOAL_INCOHERENT_TAGS,
  RACE_CLASSES,
  READINESS_VERSION,
  readinessMetrics,
  refusalClass
}
