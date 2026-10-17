'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')

const { PROFILES } = require('../lib/profiles.js')
const { readinessMetrics } = require('../lib/readiness.js')

const R19 = path.join(__dirname, '..', 'experiments', '2026-10-16-openai-10-agent-10m-r19')
const BARS = PROFILES['explored-goal-10m-10'].coherenceBars
const LOG = 'item.minecraft.oak_log'
const PLANKS = 'item.minecraft.oak_planks'
const DIRT = 'item.minecraft.dirt'
let sequence = 0

function record (agentId, fields) {
  sequence += 10
  return { recordId: `body:${String(sequence).padStart(8, '0')}`, tick: sequence, agentId, ...fields }
}

/** One goal review: its perception, its decision carrying [goal] and [choice], the body's answer. */
function review (agentId, id, { intent = 'dig', goal, choice, perception = {}, shown, applied = 'accepted', onto }) {
  const requestKey = `${agentId}:${id}`
  const decision = {
    intent: { action: intent, ...(onto === undefined ? {} : { onto }) },
    ...(choice === undefined ? {} : { goal: choice })
  }
  return [
    record(agentId, { event: 'perceived', requestId: id, requestKey, perception: { requestId: id, ...perception } }),
    record(agentId, {
      event: 'decided',
      requestId: id,
      requestKey,
      intent,
      decision,
      goal,
      ...(shown === undefined ? {} : { shown: { goal: shown } })
    }),
    record(agentId, { event: 'intentApplied', requestId: id, requestKey, intent, outcome: applied }),
    ...(['dig', 'craft', 'collect'].includes(intent) && applied === 'accepted'
      ? [record(agentId, { event: 'worldInteraction', requestId: id, requestKey, outcome: 'serverEffectConfirmed' })]
      : []),
    ...(intent === 'moveTo' && applied === 'accepted'
      ? [record(agentId, { event: 'moved', requestId: id, requestKey, outcome: 'movementArrived' })]
      : [])
  ]
}

/** A sight holding `count` of `item`, with a dirt block in dig reach under the feet. */
const sight = (item, count, extra = {}) => ({
  selfState: { inventorySummary: count === 0 ? [] : [{ ref: 'i1', what: item, count }], ownDrops: [] },
  surroundings: [{
    kind: 'block',
    ref: 'g1',
    what: 'block.minecraft.grass_block',
    distance: 0.5,
    verticalOffset: -0.5,
    visible: true,
    digSafe: true,
    yields: [{ what: DIRT, least: 1, most: 1, perHundred: 100 }]
  }],
  ...extra
})

const stepped = (goalId, advanced, outcome = 'goalContinued', extra = {}) => ({
  outcome,
  presentedGoalId: goalId,
  presentedRevision: 0,
  goalId: outcome === 'goalCompleted' ? undefined : goalId,
  revision: outcome === 'goalCompleted' ? undefined : 0,
  stepsWithoutProgress: 0,
  conditionMet: false,
  conditionAdvanced: advanced,
  stepAdvanced: advanced,
  stepEvidence: advanced ? 'gained' : 'none',
  ...extra
})

/**
 * One agent walking to the edge of sight, digging four logs over two gains, then crafting planks
 * from them as a goal chained to the log goal, which it advances.
 */
function explorer (agentId, log = LOG) {
  return [
    ...review(agentId, `${agentId}0`, {
      intent: 'moveTo',
      onto: 'x1',
      goal: { outcome: 'goalUnaddressed' },
      perception: { ...sight(LOG, 0), explore: [{ ref: 'x1' }] }
    }),
    ...review(agentId, `${agentId}1`, {
      goal: { outcome: 'goalAdopted', disposition: 'adopt', goalId: agentId, revision: 0 },
      choice: { disposition: 'adopt', successItem: log, successCount: 4 },
      perception: sight(log, 0)
    }),
    ...review(agentId, `${agentId}2`, { goal: stepped(agentId, true), perception: sight(log, 2) }),
    ...review(agentId, `${agentId}3`, {
      intent: 'craft',
      goal: {
        ...stepped(agentId, true, 'goalCompletedAdopted', { conditionMet: true }),
        goalId: `${agentId}-planks`,
        chainedFrom: agentId
      },
      choice: { disposition: 'replace', successItem: PLANKS, successCount: 16 },
      perception: sight(log, 4)
    }),
    ...review(agentId, `${agentId}4`, {
      intent: 'craft',
      goal: stepped(`${agentId}-planks`, true),
      perception: sight(PLANKS, 8)
    })
  ]
}

/** A sighting of a hostile creature, the danger a run must meet. */
const danger = (agentId) => record(agentId, {
  event: 'perceived',
  requestId: `${agentId}-threat`,
  requestKey: `${agentId}:${agentId}-threat`,
  trigger: 'threatSighted',
  perception: { trigger: { event: 'threatSighted' } }
})

function gate11 (bodyRecords, bars = BARS) {
  return readinessMetrics({ bodyRecords, harnessRecords: [], metrics: {}, generation: 11, bars })
}

test('r19, judged under gate generation 11, is not ready on the measures its audit re-derived', () => {
  const records = fs.readFileSync(path.join(R19, 'evidence.jsonl'), 'utf8').trim().split('\n').map(JSON.parse)
  const metrics = JSON.parse(fs.readFileSync(path.join(R19, 'metrics.json'), 'utf8'))
  const result = readinessMetrics({
    bodyRecords: records.filter((entry) => entry.source === 'body'),
    harnessRecords: records.filter((entry) => entry.source === 'harness'),
    metrics,
    generation: 11,
    bars: BARS
  })
  assert.equal(result.version, 'loom-readiness-gate/9')
  // theta body:00000310 and epsilon 352: the craft race the body has since fixed.
  assert.equal(result.blockers.protocolError, 2)
  // kappa body:00000384 and 511, beta 453: a collect of the agent's own fresh drop, refused
  // noRoute, is no longer read as terrain.
  assert.equal(result.blockers.ownDropUnreached, 3)
  const { measured, verdict } = result.goalCoherence.pursuit
  assert.equal(verdict, 'notCleared')
  // 28 of 31 revises restated the total they were shown.
  assert.equal(measured.noOpRevises, 28)
  // Ten of 34 goals were dirt one dig at a time, and no goal built on another's item.
  assert.equal(measured.substantiveMultiStepAgents, 1)
  assert.equal(measured.substantiveCompletions, 1)
  assert.equal(measured.chainedGoals, 0)
  assert.equal(measured.craftChainSteps, 0)
  // No agent walked to the edge of sight, and no perception met a threat.
  assert.equal(measured.exploringAgents, 0)
  assert.equal(measured.dangerEncounters, 0)
  // The counters the request schema guarantees are reported apart, and r19 held every one.
  const guaranteed = result.goalCoherence.schemaGuarantees
  assert.equal(guaranteed.held, true)
  assert.match(guaranteed.meaning, /not behaviour/)
  assert.equal(result.verdict, 'notReady')
  // Generation 10 is frozen: r19 re-derives as published.
  assert.equal(metrics.behaviorGate.readiness.version, 'loom-readiness-gate/8')
})

test('three explorers each chaining planks to a two-gain log goal clear every pre-registered bar', () => {
  const birch = 'item.minecraft.birch_log'
  const result = gate11([...explorer('alpha'), ...explorer('beta'), ...explorer('gamma', birch), danger('alpha')])
  const { measured, verdict } = result.goalCoherence.pursuit
  assert.equal(measured.substantiveMultiStepAgents, 3)
  assert.equal(measured.substantiveCompletions, 3)
  assert.equal(measured.chainedGoals, 3)
  assert.equal(measured.craftChainSteps, 6)
  assert.equal(measured.exploringAgents, 3)
  assert.equal(measured.dangerEncounters, 1)
  assert.equal(measured.distinctGoalItems, 3)
  assert.equal(verdict, 'cleared', JSON.stringify(measured))
  assert.equal(result.verdict, 'ready', JSON.stringify(result.blockers))
})

test('a dirt goal sight guaranteed one dig at a time is no substance unless a later goal builds on it', () => {
  const dirt = (agentId, chained) => [
    ...review(agentId, `${agentId}1`, {
      goal: { outcome: 'goalAdopted', disposition: 'adopt', goalId: agentId, revision: 0 },
      choice: { disposition: 'adopt', successItem: DIRT, successCount: 4 },
      perception: sight(DIRT, 0)
    }),
    ...review(agentId, `${agentId}2`, { goal: stepped(agentId, true), perception: sight(DIRT, 2) }),
    ...review(agentId, `${agentId}3`, {
      goal: {
        ...stepped(agentId, true, 'goalCompletedAdopted', { conditionMet: true }),
        goalId: `${agentId}-next`,
        ...(chained ? { chainedFrom: agentId } : {})
      },
      choice: { disposition: 'replace', successItem: 'item.minecraft.coarse_dirt', successCount: 4 },
      perception: sight(DIRT, 4)
    })
  ]
  const alone = gate11(dirt('zeta', false)).goalCoherence.pursuit.measured
  assert.equal(alone.substantiveMultiStepAgents, 0)
  assert.equal(alone.substantiveCompletions, 0)
  const built = gate11(dirt('zeta', true)).goalCoherence.pursuit.measured
  assert.equal(built.substantiveMultiStepAgents, 1)
  assert.equal(built.substantiveCompletions, 1)
})

test('a revise that restates its shown total is no goal activity', () => {
  const restated = [
    ...review('iota', 'i1', {
      goal: { outcome: 'goalAdopted', disposition: 'adopt', goalId: 'iota', revision: 0 },
      choice: { disposition: 'adopt', successItem: LOG, successCount: 4 },
      perception: sight(LOG, 0)
    }),
    ...review('iota', 'i2', {
      goal: stepped('iota', false, 'goalRevised'),
      choice: { disposition: 'revise', successItem: LOG, successCount: 4 },
      shown: { successItem: LOG, successCount: 4 },
      perception: sight(LOG, 0)
    }),
    ...review('iota', 'i3', {
      goal: stepped('iota', false, 'goalRevised'),
      choice: { disposition: 'revise', successItem: LOG, successCount: 3 },
      shown: { successItem: LOG, successCount: 4 },
      perception: sight(LOG, 0)
    })
  ]
  const { measured } = gate11(restated).goalCoherence.pursuit
  assert.equal(measured.noOpRevises, 1)
  assert.equal(measured.goalActiveReviewShare, 0.666667)
})

test('a goal held without advancing counts toward progressing time only within its window', () => {
  // One goal adopted at the agent's first perception and never advanced, in a run of 6000 ticks:
  // only the 2400 ticks after its adoption progress.
  const start = record('kappa', { event: 'perceived', requestId: 'k0', requestKey: 'kappa:k0', perception: {} })
  const adopted = review('kappa', 'k1', {
    goal: { outcome: 'goalAdopted', disposition: 'adopt', goalId: 'kappa', revision: 0 },
    choice: { disposition: 'adopt', successItem: LOG, successCount: 4 },
    perception: sight(LOG, 0)
  })
  for (const entry of adopted) entry.tick = start.tick
  const later = record('kappa', { event: 'perceived', requestId: 'k9', requestKey: 'kappa:k9', perception: {} })
  later.tick = start.tick + 6000
  const { measured } = gate11([start, ...adopted, later]).goalCoherence.pursuit
  assert.equal(measured.progressingHeldTimeShare, 0.4)
})
