'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')

const { PROFILES } = require('../lib/profiles.js')
const { readinessMetrics } = require('../lib/readiness.js')

const R17 = path.join(__dirname, '..', 'experiments', '2026-10-16-openai-10-agent-10m-r17')
const BARS = PROFILES['pursued-goal-10m-10'].coherenceBars
const STICK = 'item.minecraft.stick'
let sequence = 0

function record (agentId, fields) {
  sequence += 1
  return { recordId: `body:${String(sequence).padStart(8, '0')}`, tick: sequence, agentId, ...fields }
}

/**
 * One goal review: what it perceived, its decision carrying [goal] and the mind's [choice], and
 * the body's answer, plus [terminal] for an accepted action.
 */
function review (agentId, id, { intent = 'idle', goal, choice, perception = {}, applied = 'accepted', terminal, from }) {
  const requestKey = `${agentId}:${id}`
  const decision = { intent: { action: intent, ...(from === undefined ? {} : { from }) }, ...(choice === undefined ? {} : { goal: choice }) }
  return [
    record(agentId, { event: 'perceived', requestId: id, requestKey, perception: { requestId: id, ...perception } }),
    record(agentId, { event: 'decided', requestId: id, requestKey, intent, decision, goal }),
    record(agentId, { event: 'intentApplied', requestId: id, requestKey, intent, outcome: applied }),
    ...(terminal === undefined ? [] : [record(agentId, { requestId: id, requestKey, ...terminal })]),
    // A dig the server confirmed, the way every accepted world step is.
    ...(intent === 'dig' && applied === 'accepted'
      ? [record(agentId, { event: 'worldInteraction', requestId: id, requestKey, outcome: 'serverEffectConfirmed' })]
      : [])
  ]
}

const held = (count) => ({ selfState: { inventorySummary: count === 0 ? [] : [{ ref: 'i1', what: STICK, count }] } })

const stepped = (goalId, conditionAdvanced, outcome = 'goalContinued', conditionMet = false) => ({
  outcome,
  presentedGoalId: goalId,
  presentedRevision: 0,
  goalId,
  revision: 0,
  stepsWithoutProgress: 0,
  conditionMet,
  conditionAdvanced,
  stepAdvanced: conditionAdvanced,
  stepEvidence: conditionAdvanced ? 'gained' : 'none'
})

/** One agent adopting four sticks with none held, gaining at two digs and completing it. */
function pursued (agentId) {
  return [
    ...review(agentId, `${agentId}1`, {
      intent: 'dig',
      goal: { outcome: 'goalAdopted', disposition: 'adopt', goalId: agentId, revision: 0 },
      choice: { disposition: 'adopt', successItem: STICK, successCount: 4 },
      perception: held(0)
    }),
    ...review(agentId, `${agentId}2`, { intent: 'dig', goal: stepped(agentId, true), perception: held(2) }),
    ...review(agentId, `${agentId}3`, { intent: 'dig', goal: stepped(agentId, true), perception: held(3) }),
    ...review(agentId, `${agentId}4`, {
      intent: 'dig',
      goal: { ...stepped(agentId, true, 'goalCompleted', true), outcome: 'goalCompleted' },
      perception: held(4)
    })
  ]
}

function gate9 (bodyRecords, bars = BARS) {
  return readinessMetrics({ bodyRecords, harnessRecords: [], metrics: {}, generation: 9, bars })
}

test('r17, judged under gate generation 9, is not ready: its flights and its pursuit fall short', () => {
  const records = fs.readFileSync(path.join(R17, 'evidence.jsonl'), 'utf8').trim().split('\n').map(JSON.parse)
  const metrics = JSON.parse(fs.readFileSync(path.join(R17, 'metrics.json'), 'utf8'))
  const result = readinessMetrics({
    bodyRecords: records.filter((entry) => entry.source === 'body'),
    harnessRecords: records.filter((entry) => entry.source === 'harness'),
    metrics,
    generation: 9,
    bars: BARS
  })
  // The audit's nine flights from mobs out of sight 22 blocks off and one flight from a peer.
  assert.equal(result.blockers.needlessFlight, 9)
  assert.equal(result.blockers.peerFlight, 1)
  const { pursuit } = result.goalCoherence
  assert.equal(pursuit.verdict, 'notCleared')
  assert.ok(pursuit.measured.goalActiveReviewShare < BARS.goalActiveReviewShare, JSON.stringify(pursuit))
  assert.ok(pursuit.measured.originalTargetCompletions < BARS.originalTargetCompletions, JSON.stringify(pursuit))
  assert.equal(result.verdict, 'notReady')
  // Under generation 8 it was ready, which is the reading the independent audit refused.
  assert.equal(metrics.behaviorGate.readiness.verdict, 'ready')
})

test('three agents pursuing original targets to completion clear every pre-registered bar', () => {
  const result = gate9([...pursued('alpha'), ...pursued('beta'), ...pursued('gamma')])
  const { pursuit } = result.goalCoherence
  assert.deepEqual(pursuit.measured, {
    goalActiveReviewShare: 0.75,
    idleWithExploration: 0,
    multiStepAgentsAtOriginalTarget: 3,
    originalTargetCompletions: 3
  })
  assert.equal(pursuit.verdict, 'cleared')
  assert.equal(result.goalCoherence.verdict, 'coherent', JSON.stringify(result.goalCoherence))
  assert.equal(result.verdict, 'ready')
  // No bars stated is no bar cleared.
  const unbarred = readinessMetrics({ bodyRecords: [...pursued('alpha'), ...pursued('beta'), ...pursued('gamma')], harnessRecords: [], metrics: {}, generation: 9 })
  assert.equal(unbarred.goalCoherence.pursuit.verdict, 'notCleared')
})

test('an idle beside offered exploration fails the pursuit bar; an idle with nowhere to look does not count', () => {
  const run = [...pursued('alpha'), ...pursued('beta'), ...pursued('gamma')]
  const unaddressed = { outcome: 'goalUnaddressed' }
  const blind = gate9([...run, ...review('delta', 'd1', { goal: unaddressed })])
  assert.equal(blind.goalCoherence.pursuit.measured.idleWithExploration, 0)
  const explore = { explore: [{ ref: 'x0', distance: 12, direction: 'north', verticalOffset: 0 }] }
  const looked = gate9([...run, ...review('delta', 'd1', { goal: unaddressed, perception: explore })])
  assert.equal(looked.goalCoherence.pursuit.measured.idleWithExploration, 1)
  assert.equal(looked.goalCoherence.pursuit.verdict, 'notCleared')
  assert.equal(looked.verdict, 'notReady')
})

test('a goal cut by a revise, or adopted nearly held, is no original-target completion', () => {
  const cut = (agentId) => [
    ...review(agentId, `${agentId}1`, {
      intent: 'dig',
      goal: { outcome: 'goalAdopted', disposition: 'adopt', goalId: agentId, revision: 0 },
      choice: { disposition: 'adopt', successItem: STICK, successCount: 4 },
      perception: held(0)
    }),
    ...review(agentId, `${agentId}2`, {
      intent: 'dig',
      goal: { ...stepped(agentId, true), outcome: 'goalRevised' },
      choice: { disposition: 'revise', successItem: STICK, successCount: 2 },
      perception: held(1)
    }),
    ...review(agentId, `${agentId}3`, { intent: 'dig', goal: stepped(agentId, true, 'goalCompleted', true), perception: held(2) })
  ]
  const near = (agentId) => [
    ...review(agentId, `${agentId}1`, {
      intent: 'dig',
      goal: { outcome: 'goalAdopted', disposition: 'adopt', goalId: agentId, revision: 0 },
      choice: { disposition: 'adopt', successItem: STICK, successCount: 3 },
      perception: held(2)
    }),
    ...review(agentId, `${agentId}2`, { intent: 'dig', goal: stepped(agentId, true, 'goalCompleted', true), perception: held(3) })
  ]
  const result = gate9([...cut('alpha'), ...near('beta')])
  assert.equal(result.goalCoherence.pursuit.measured.originalTargetCompletions, 0)
  assert.equal(result.goalCoherence.pursuit.measured.multiStepAgentsAtOriginalTarget, 0)
})

test('a completion that adopts its next goal in one answer counts as both', () => {
  const run = [
    ...pursued('alpha').filter((entry) => entry.requestId !== 'alpha4'),
    ...review('alpha', 'alpha4', {
      intent: 'dig',
      goal: { ...stepped('alpha', true, 'goalCompletedAdopted', true), goalId: 'alpha-next' },
      choice: { disposition: 'replace', successItem: STICK, successCount: 8 },
      perception: held(4)
    })
  ]
  const { pursuit } = gate9(run).goalCoherence
  assert.equal(pursuit.measured.originalTargetCompletions, 1)
  assert.equal(pursuit.measured.goalActiveReviewShare, 1)
})

test('a flight is judged by the threat it ran from and the ground it gained', () => {
  const flee = (id, { trigger, seen, gained }) => review('alpha', id, {
    intent: 'flee',
    from: 'c1',
    perception: { trigger, surroundings: seen === undefined ? [] : [{ ref: 'c1', ...seen }] },
    terminal: { event: 'retreating', outcome: 'retreatSafe', retreat: { separation: 20, separationGained: gained } }
  })
  const zombie = { what: 'entity.minecraft.zombie', visible: false, distance: 22 }
  const goalReview = { event: 'goalReview' }
  const cases = [
    // r17: out of sight 22 blocks off and under four gained.
    [{ trigger: goalReview, seen: zombie, gained: 2 }, 'needlessFlight'],
    [{ trigger: goalReview, seen: zombie, gained: 5 }, 'confirmed'],
    [{ trigger: goalReview, seen: { ...zombie, visible: true }, gained: 0 }, 'confirmed'],
    [{ trigger: goalReview, seen: { ...zombie, distance: 12 }, gained: 0 }, 'confirmed'],
    [{ trigger: { event: 'damaged', amount: 2, source: 'entity.minecraft.zombie' }, seen: zombie, gained: 0 }, 'confirmed'],
    [{ trigger: { event: 'threatSighted', ref: 'c1', what: 'entity.minecraft.zombie', visible: true, distance: 30 }, gained: 0 }, 'confirmed'],
    [{ trigger: goalReview, seen: { what: 'entity.minecraft.player', visible: true, distance: 3 }, gained: 9 }, 'peerFlight']
  ]
  for (const [index, [flight, expected]] of cases.entries()) {
    const result = gate9(flee(`f${index}`, flight))
    const blockers = Object.entries(result.blockers).filter(([, count]) => count > 0).map(([name]) => name)
    assert.deepEqual(blockers, expected === 'confirmed' ? [] : [expected], `case ${index}`)
  }
  // Generation 8 judged every safe flight confirmed.
  const generation8 = readinessMetrics({
    bodyRecords: flee('g8', cases[0][0]),
    harnessRecords: [],
    metrics: {},
    generation: 8
  })
  assert.equal(generation8.blockers.needlessFlight, undefined)
})

test('an abandon of an achievable goal is a refused decision from generation 9', () => {
  const run = [...pursued('alpha'), ...pursued('beta'), ...pursued('gamma')]
  const abandoned = review('theta', 't1', {
    goal: { outcome: 'goalAbandonedAchievable', disposition: 'abandon' },
    applied: 'goalAbandonedAchievable'
  })
  const result = gate9([...run, ...abandoned])
  assert.equal(result.blockers.goalIncoherent, 1)
  assert.equal(result.verdict, 'notReady')
  const generation8 = readinessMetrics({ bodyRecords: [...run, ...abandoned], harnessRecords: [], metrics: {}, generation: 8 })
  assert.equal(generation8.blockers.goalIncoherent, 0)
})
