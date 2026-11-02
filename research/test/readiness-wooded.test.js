'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')

const { PROFILES } = require('../lib/profiles.js')
const { readinessMetrics } = require('../lib/readiness.js')

const R18 = path.join(__dirname, '..', 'experiments', '2026-10-16-openai-10-agent-10m-r18')
const BARS = PROFILES['wooded-goal-10m-10'].coherenceBars
const LOG = 'item.minecraft.oak_log'
const PLANKS = 'item.minecraft.oak_planks'
let sequence = 0

function record (agentId, fields) {
  sequence += 10
  return { recordId: `body:${String(sequence).padStart(8, '0')}`, tick: sequence, agentId, ...fields }
}

/** One goal review: its perception, its decision carrying [goal] and [choice], the body's answer. */
function review (agentId, id, { intent = 'dig', goal, choice, perception = {}, applied = 'accepted', terminal, from }) {
  const requestKey = `${agentId}:${id}`
  const decision = { intent: { action: intent, ...(from === undefined ? {} : { from }) }, ...(choice === undefined ? {} : { goal: choice }) }
  return [
    record(agentId, { event: 'perceived', requestId: id, requestKey, perception: { requestId: id, ...perception } }),
    record(agentId, { event: 'decided', requestId: id, requestKey, intent, decision, goal }),
    record(agentId, { event: 'intentApplied', requestId: id, requestKey, intent, outcome: applied }),
    ...(terminal === undefined ? [] : [record(agentId, { requestId: id, requestKey, ...terminal })]),
    ...(['dig', 'craft', 'collect'].includes(intent) && applied === 'accepted'
      ? [record(agentId, { event: 'worldInteraction', requestId: id, requestKey, outcome: 'serverEffectConfirmed' })]
      : [])
  ]
}

const holding = (item, count, lying = 0) => ({
  selfState: {
    inventorySummary: count === 0 ? [] : [{ ref: 'i1', what: item, count }],
    ownDrops: lying === 0 ? [] : [{ requestId: 'r0', what: item, count: lying, fate: 'lying', ref: 'd1' }]
  }
})

const stepped = (goalId, advanced, outcome = 'goalContinued', met = false, extra = {}) => ({
  outcome,
  presentedGoalId: goalId,
  presentedRevision: 0,
  goalId: outcome === 'goalCompleted' ? undefined : goalId,
  revision: outcome === 'goalCompleted' ? undefined : 0,
  stepsWithoutProgress: 0,
  conditionMet: met,
  conditionAdvanced: advanced,
  stepAdvanced: advanced,
  stepEvidence: advanced ? 'gained' : 'none',
  ...extra
})

/**
 * One agent digging four logs from none over two gains, then crafting planks from them as the
 * next goal in the same answer that completed the first.
 */
function woodsman (agentId) {
  return [
    ...review(agentId, `${agentId}1`, {
      goal: { outcome: 'goalAdopted', disposition: 'adopt', goalId: agentId, revision: 0 },
      choice: { disposition: 'adopt', successItem: LOG, successCount: 4 },
      perception: holding(LOG, 0)
    }),
    ...review(agentId, `${agentId}2`, { goal: stepped(agentId, true), perception: holding(LOG, 2) }),
    ...review(agentId, `${agentId}3`, {
      intent: 'craft',
      goal: { ...stepped(agentId, true, 'goalCompletedAdopted', true), goalId: `${agentId}-planks` },
      choice: { disposition: 'replace', successItem: PLANKS, successCount: 16 },
      perception: holding(LOG, 4)
    })
  ]
}

function gate10 (bodyRecords, bars = BARS) {
  return readinessMetrics({ bodyRecords, harnessRecords: [], metrics: {}, generation: 10, bars })
}

test('r18, judged under gate generation 10, is not ready on the measures its audit re-derived', () => {
  const records = fs.readFileSync(path.join(R18, 'evidence.jsonl'), 'utf8').trim().split('\n').map(JSON.parse)
  const metrics = JSON.parse(fs.readFileSync(path.join(R18, 'metrics.json'), 'utf8'))
  const result = readinessMetrics({
    bodyRecords: records.filter((entry) => entry.source === 'body'),
    harnessRecords: records.filter((entry) => entry.source === 'harness'),
    metrics,
    generation: 10,
    bars: BARS
  })
  assert.equal(result.version, 'loom-readiness-gate/8')
  // The four safe flights ran from mobs out of sight at 15 to 16 blocks (body:00000309, 472,
  // 708, 721): the separation they gained no longer rescues them.
  assert.equal(result.blockers.needlessFlight, 4)
  assert.equal(result.blockers.goalIncoherent, 1)
  const { measured, verdict } = result.goalCoherence.pursuit
  assert.equal(verdict, 'notCleared')
  // The audit's figures: 47% of fleet time held a goal, zeta 13%, delta's review share one half.
  assert.equal(measured.goalHeldTimeShare, 0.471077)
  assert.equal(measured.minAgentGoalHeldTimeShare, 0.129819)
  assert.equal(measured.minAgentGoalActiveReviewShare, 0.5)
  // Of the eight r17-measure completions, three needed one gain and epsilon's at
  // body:00000486 had its own stick lying in reach when it was set.
  assert.equal(measured.originalTargetCompletions, 4)
  assert.equal(measured.craftSteps, 0)
  assert.equal(result.verdict, 'notReady')
  // Generation 9 is frozen: r18 re-derives as published.
  assert.equal(metrics.behaviorGate.readiness.version, 'loom-readiness-gate/7')
})

test('three woodsmen each crafting after a two-gain log goal clear every pre-registered bar', () => {
  const result = gate10([...woodsman('alpha'), ...woodsman('beta'), ...woodsman('gamma')])
  const { measured, verdict } = result.goalCoherence.pursuit
  assert.equal(measured.multiStepAgentsAtOriginalTarget, 3)
  assert.equal(measured.originalTargetCompletions, 3)
  assert.equal(measured.distinctGoalItems, 2)
  assert.equal(measured.craftSteps, 3)
  assert.equal(measured.idleBesideExploration, 0)
  assert.equal(measured.goalActiveReviewShare, 1)
  assert.equal(verdict, 'cleared', JSON.stringify(measured))
  assert.equal(result.verdict, 'ready', JSON.stringify(result.blockers))
})

test('a completion counts only after two advances and two beyond what was held or lay in reach', () => {
  const oneGain = (agentId, lying) => [
    ...review(agentId, `${agentId}1`, {
      goal: { outcome: 'goalAdopted', disposition: 'adopt', goalId: agentId, revision: 0 },
      choice: { disposition: 'adopt', successItem: LOG, successCount: 4 },
      perception: holding(LOG, 0, lying)
    }),
    ...review(agentId, `${agentId}2`, { goal: stepped(agentId, true, 'goalCompleted', true), perception: holding(LOG, 4) })
  ]
  // r18 theta body:00000172 and iota 525: one dig gave all of it.
  assert.equal(gate10(oneGain('theta', 0)).goalCoherence.pursuit.measured.originalTargetCompletions, 0)
  const lyingTwice = (agentId) => [
    ...review(agentId, `${agentId}1`, {
      goal: { outcome: 'goalAdopted', disposition: 'adopt', goalId: agentId, revision: 0 },
      choice: { disposition: 'adopt', successItem: LOG, successCount: 4 },
      perception: holding(LOG, 1, 2)
    }),
    ...review(agentId, `${agentId}2`, { goal: stepped(agentId, true), perception: holding(LOG, 3) }),
    ...review(agentId, `${agentId}3`, { goal: stepped(agentId, true, 'goalCompleted', true), perception: holding(LOG, 4) })
  ]
  // r18 kappa body:00000336: two of its own sticks lay in reach when the goal was set.
  assert.equal(gate10(lyingTwice('kappa')).goalCoherence.pursuit.measured.originalTargetCompletions, 0)
})

test('a cut sight forced is no cut, and a revise to another item is one', () => {
  const cut = (agentId, targetHeld, targetCapacity, item = LOG) => [
    ...review(agentId, `${agentId}1`, {
      goal: { outcome: 'goalAdopted', disposition: 'adopt', goalId: agentId, revision: 0 },
      choice: { disposition: 'adopt', successItem: LOG, successCount: 5 },
      perception: holding(LOG, 0)
    }),
    ...review(agentId, `${agentId}2`, {
      goal: { ...stepped(agentId, true, 'goalRevised'), targetHeld, targetCapacity },
      choice: { disposition: 'revise', successItem: item, successCount: 3 },
      perception: holding(LOG, 1)
    }),
    ...review(agentId, `${agentId}3`, { goal: stepped(agentId, true, 'goalCompleted', true), perception: holding(LOG, 3) })
  ]
  // r18 delta body:00000053, eta 217 and kappa 528: sight held one and could give two.
  assert.equal(gate10(cut('delta', 1, 2)).goalCoherence.pursuit.measured.originalTargetCompletions, 1)
  // A cut sight did not force is the mind's own, and no original target.
  assert.equal(gate10(cut('beta', 1, 6)).goalCoherence.pursuit.measured.originalTargetCompletions, 0)
  // r18 zeta body:00000569 revised a scute goal into sticks under the same goal.
  assert.equal(gate10(cut('zeta', 1, 2, 'item.minecraft.stick')).goalCoherence.pursuit.measured.originalTargetCompletions, 0)
})

test('an idle beside a cell to look from counts whatever the goal choice, and per-agent bars bite', () => {
  const run = [...woodsman('alpha'), ...woodsman('beta'), ...woodsman('gamma')]
  const edge = { explore: [{ ref: 'x1', distance: 12, direction: 'east', verticalOffset: 0 }] }
  // r18: all eleven completes idled, and beta body:00000123 abandoned and idled, beside cells.
  const completeIdle = review('delta', 'd1', {
    intent: 'idle',
    goal: { outcome: 'goalCompleted', disposition: 'complete' },
    perception: edge
  })
  const result = gate10([...run, ...completeIdle])
  assert.equal(result.goalCoherence.pursuit.measured.idleBesideExploration, 1)
  assert.equal(result.goalCoherence.pursuit.verdict, 'notCleared')
  // An agent that never holds a goal drags the per-agent bars below their floor.
  const idler = review('eta', 'e1', { intent: 'moveTo', goal: { outcome: 'goalUnaddressed' } })
  const measured = gate10([...run, ...idler, ...review('eta', 'e2', { intent: 'moveTo', goal: { outcome: 'goalUnaddressed' } })])
    .goalCoherence.pursuit.measured
  assert.equal(measured.minAgentGoalActiveReviewShare, 0)
  assert.equal(measured.minAgentGoalHeldTimeShare, 0)
})

test('a flight is judged by its threat, not by what it then gained', () => {
  const flee = (id, seen, gained, trigger = { event: 'goalReview' }) => review('alpha', id, {
    intent: 'flee',
    from: 'c1',
    perception: { trigger, surroundings: [{ ref: 'c1', ...seen }] },
    terminal: { event: 'retreating', outcome: 'retreatSafe', retreat: { separation: 20, separationGained: gained } }
  })
  const unseen = (distance) => ({ what: 'entity.minecraft.skeleton', visible: false, distance })
  const blockers = (records) => Object.entries(gate10(records).blockers).filter(([, count]) => count > 0).map(([name]) => name)
  // r18 body:00000309: a skeleton out of sight 15.7 blocks off, ten blocks gained.
  assert.deepEqual(blockers(flee('f1', unseen(15.7), 10.6)), ['needlessFlight'])
  assert.deepEqual(blockers(flee('f2', unseen(7.5), 0)), [])
  assert.deepEqual(blockers(flee('f3', { ...unseen(15.7), visible: true }, 0)), [])
  assert.deepEqual(blockers(flee('f4', unseen(15.7), 0, { event: 'damaged', amount: 2, source: 'entity.minecraft.skeleton' })), [])
  // Generation 9 let the gain rescue it.
  const generation9 = readinessMetrics({ bodyRecords: flee('g9', unseen(15.7), 10.6), harnessRecords: [], metrics: {}, generation: 9 })
  assert.equal(generation9.blockers.needlessFlight, 0)
})

test('a revise that changes the goal item is a refused decision from generation 10', () => {
  const run = [...woodsman('alpha'), ...woodsman('beta'), ...woodsman('gamma')]
  const changed = review('zeta', 'z1', {
    goal: { outcome: 'goalReviseItemChanged', disposition: 'revise' },
    applied: 'goalReviseItemChanged'
  })
  const result = gate10([...run, ...changed])
  assert.equal(result.blockers.goalIncoherent, 1)
  assert.equal(result.verdict, 'notReady')
  // Generation 9 read the tag by its suffix as a target that changed, a legitimate race.
  const generation9 = readinessMetrics({ bodyRecords: [...run, ...changed], harnessRecords: [], metrics: {}, generation: 9 })
  assert.equal(generation9.blockers.goalIncoherent, 0)
  assert.equal(generation9.actions.byClass.targetRace, 1)
})
