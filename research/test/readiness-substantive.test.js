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

const R20 = path.join(__dirname, '..', 'experiments', '2026-10-16-openai-10-agent-10m-r20')

test('r20, judged under gate generation 12, is not ready on the measures its audit re-derived', () => {
  const records = fs.readFileSync(path.join(R20, 'evidence.jsonl'), 'utf8').trim().split('\n').map(JSON.parse)
  const metrics = JSON.parse(fs.readFileSync(path.join(R20, 'metrics.json'), 'utf8'))
  const result = readinessMetrics({
    bodyRecords: records.filter((entry) => entry.source === 'body'),
    harnessRecords: records.filter((entry) => entry.source === 'harness'),
    metrics,
    generation: 12,
    bars: { ...BARS, dangerEpisodes: 1 }
  })
  assert.equal(result.version, 'loom-readiness-gate/10')
  // All 13 flights refused for no route ran from pits and shafts the agents had dug themselves.
  assert.equal(result.blockers.selfTrapped, 13)
  assert.equal(result.actions.byClass.terrain, 0)
  const { measured, verdict, schemaShaped } = result.goalCoherence.pursuit
  assert.equal(verdict, 'notCleared')
  // 28 of 39 goals asked for one more than was held; 13 were re-adopted at a total just cut from.
  assert.equal(measured.oneStepGoals, 28)
  assert.equal(measured.readoptedGoals, 13)
  // The three chained crafts were each met by the craft that adopted them, so none counts.
  assert.equal(measured.chainedGoals, 0)
  assert.equal(measured.craftChainSteps, 0)
  assert.equal(measured.substantiveCompletions, 0)
  // Danger is answered perceptions, not coalesced ones, and three agents died.
  assert.equal(measured.dangerEpisodes, 49)
  assert.equal(measured.deaths, 3)
  assert.deepEqual(schemaShaped, ['goalActiveReviewShare', 'minAgentGoalActiveReviewShare'])
  // Generation 11 is frozen: r20 re-derives as published.
  assert.equal(metrics.behaviorGate.readiness.version, 'loom-readiness-gate/9')
})

test('under generation 12 a one-step goal is no substance, and a chain counts only after its adopting craft', () => {
  const bars12 = { ...BARS, dangerEpisodes: 1 }
  const gate12 = (bodyRecords) => readinessMetrics({ bodyRecords, harnessRecords: [], metrics: {}, generation: 12, bars: bars12 })
  // The explorer's planks goal advanced once, by the craft that adopted it: no chain yet.
  assert.equal(gate12(explorer('alpha')).goalCoherence.pursuit.measured.chainedGoals, 0)
  // One more review that measures the second craft's planks: it needed 16 beyond 0 and advanced
  // twice, a chain that counts, with the second craft its one chain step.
  const onward = [
    ...explorer('alpha'),
    ...review('alpha', 'alpha5', { intent: 'craft', goal: stepped('alpha-planks', true), perception: sight(PLANKS, 12) })
  ]
  const alone = gate12(onward).goalCoherence.pursuit.measured
  assert.equal(alone.chainedGoals, 1)
  assert.equal(alone.craftChainSteps, 2)
  assert.equal(alone.oneStepGoals, 0)
  const tiny = [
    ...review('kappa', 'k1', {
      goal: { outcome: 'goalAdopted', disposition: 'adopt', goalId: 'kappa', revision: 0 },
      choice: { disposition: 'adopt', successItem: LOG, successCount: 1 },
      perception: { selfState: { inventorySummary: [], ownDrops: [] }, surroundings: [] }
    }),
    ...review('kappa', 'k2', {
      goal: { ...stepped('kappa', true, 'goalCompleted', { conditionMet: true }) },
      choice: { disposition: 'complete' },
      perception: { selfState: { inventorySummary: [{ ref: 'i1', what: LOG, count: 1 }], ownDrops: [] }, surroundings: [] }
    })
  ]
  assert.equal(gate12(tiny).goalCoherence.pursuit.measured.oneStepGoals, 1)
})

test('a flight that stalled against the world border is a blocker, ended at the border face', () => {
  // v29 plains rehearsal 2 theta (twice, from skeletons) and rehearsal 3 iota (from the zombie
  // that killed it): each flight searched over the 128-block border and jumped in place against
  // it until the stall bound ended it. The body now searches inside the border; the gate keeps
  // such a flight a blocker rather than excusing it as terrain.
  const fixture = path.join(__dirname, 'fixtures', 'v29-border-stalls.jsonl')
  const bodyRecords = fs.readFileSync(fixture, 'utf8').trim().split('\n').map(JSON.parse)
  const result = readinessMetrics({ bodyRecords, harnessRecords: [], metrics: {}, generation: 12, bars: { ...BARS, dangerEpisodes: 1 } })
  const stalls = result.actions.nonSuccess.filter((entry) => entry.class === 'incompleteMovement')
  assert.deepEqual(stalls.map((entry) => entry.agentId), ['tribe-theta', 'tribe-theta', 'tribe-iota'])
  assert.equal(result.blockers.incompleteMovement, 3)
  for (const moved of bodyRecords.filter((record) => record.event === 'moved')) {
    assert.equal(moved.outcome, 'movementStalled')
    // The summary's end position: one horizontal coordinate is the border face less half a body.
    const end = moved.summary.split(' to ')[1].split(';')[0].split(',').map(Number)
    const touching = Math.max(Math.abs(end[0]), Math.abs(end[2])) + 0.3
    assert.ok(Math.abs(touching - 64) < 0.05, moved.summary)
  }
})

test('under generation 13 a horizon goal counts only once it advances past what sight guaranteed at its adoption', () => {
  const bars13 = { ...BARS, dangerEpisodes: 1 }
  const gate13 = (bodyRecords) => readinessMetrics({ bodyRecords, harnessRecords: [], metrics: {}, generation: 13, bars: bars13 })
  const horizon = [{ what: LOG, grounding: 'sight', most: 9, frontier: true }]
  // One log in sight, guaranteed one dig at a time: a goal of four asks past it.
  const logs = (count, extra = {}) => ({
    selfState: { inventorySummary: count === 0 ? [] : [{ ref: 'i1', what: LOG, count }], ownDrops: [] },
    surroundings: [{
      kind: 'block',
      ref: 'b1',
      what: 'block.minecraft.oak_log',
      distance: 1.5,
      verticalOffset: 0.5,
      visible: true,
      digSafe: true,
      yields: [{ what: LOG, least: 1, most: 1, perHundred: 100 }]
    }],
    explore: [{ ref: 'x1' }],
    horizon,
    ...extra
  })
  const pursued = (agentId, heldLater) => [
    ...review(agentId, `${agentId}1`, {
      goal: { outcome: 'goalAdopted', disposition: 'adopt', goalId: agentId, revision: 0 },
      choice: { disposition: 'adopt', successItem: LOG, successCount: 4 },
      perception: logs(0)
    }),
    ...review(agentId, `${agentId}2`, { goal: stepped(agentId, true), perception: logs(1) }),
    ...review(agentId, `${agentId}3`, { goal: stepped(agentId, true), perception: logs(heldLater) })
  ]
  // Only the one guaranteed log was ever held: two advances, but nothing found beyond sight.
  const guaranteedOnly = gate13(pursued('alpha', 1)).goalCoherence.pursuit.measured
  assert.equal(guaranteedOnly.substantiveMultiStepAgents, 0)
  assert.equal(guaranteedOnly.horizonGoals, 1)
  assert.equal(guaranteedOnly.discoveredGoals, 0)
  // Three held: two came from logs sight did not show when the goal was set.
  const found = gate13(pursued('alpha', 3)).goalCoherence.pursuit.measured
  assert.equal(found.substantiveMultiStepAgents, 1)
  assert.equal(found.discoveredGoals, 1)
  // Generation 12 is frozen: there the same goal counts whatever it found.
  const twelve = readinessMetrics({ bodyRecords: pursued('alpha', 1), harnessRecords: [], metrics: {}, generation: 12, bars: bars13 })
  assert.equal(twelve.goalCoherence.pursuit.measured.substantiveMultiStepAgents, 1)
  assert.equal(twelve.version, 'loom-readiness-gate/10')
})

test('under generation 13 local dirt counts only when a later goal of another item chained from it advanced, and crowding is refused', () => {
  const bars13 = { ...BARS, dangerEpisodes: 1 }
  const gate13 = (bodyRecords) => readinessMetrics({ bodyRecords, harnessRecords: [], metrics: {}, generation: 13, bars: bars13 })
  const dirtGoal = (agentId, chained) => [
    ...review(agentId, `${agentId}1`, {
      goal: { outcome: 'goalAdopted', disposition: 'adopt', goalId: agentId, revision: 0 },
      choice: { disposition: 'adopt', successItem: DIRT, successCount: 3 },
      perception: { ...sight(DIRT, 0), surroundings: [...sight(DIRT, 0).surroundings, ...sight(DIRT, 0).surroundings, ...sight(DIRT, 0).surroundings] }
    }),
    ...review(agentId, `${agentId}2`, { goal: stepped(agentId, true), perception: sight(DIRT, 2) }),
    ...review(agentId, `${agentId}3`, {
      goal: {
        ...stepped(agentId, true, 'goalCompletedAdopted', { conditionMet: true }),
        goalId: `${agentId}-next`,
        ...(chained ? { chainedFrom: agentId } : {})
      },
      choice: { disposition: 'replace', successItem: 'item.minecraft.coarse_dirt', successCount: 4 },
      perception: sight(DIRT, 3)
    }),
    ...(chained
      ? review(agentId, `${agentId}4`, { intent: 'craft', goal: stepped(`${agentId}-next`, true), perception: sight(DIRT, 1) })
      : [])
  ]
  assert.equal(gate13(dirtGoal('zeta', false)).goalCoherence.pursuit.measured.substantiveMultiStepAgents, 0)
  assert.equal(gate13(dirtGoal('zeta', true)).goalCoherence.pursuit.measured.substantiveMultiStepAgents, 1)
  // A goal that crowds out offered exploration is a refused, incoherent decision.
  const crowded = review('kappa', 'k1', {
    goal: { outcome: 'goalCrowdsExploration' },
    choice: { disposition: 'adopt', successItem: DIRT, successCount: 1 },
    applied: 'goalCrowdsExploration',
    perception: { ...sight(DIRT, 0), explore: [{ ref: 'x1' }] }
  })
  const result = gate13(crowded)
  assert.equal(result.actions.byClass.goalIncoherent, 1)
  assert.equal(result.goalCoherence.pursuit.measured.crowdedGoals, 1)
  assert.equal(result.version, 'loom-readiness-gate/11')
})

test('under generation 13 a no-route flight is the agent own hole only where it dug beneath itself', () => {
  // The v30 rehearsal: delta dug grass under itself at tick 2772, fled fifty blocks into a ravine
  // at tick 7057, and its no-route flights out of the ravine were filed as its own hole.
  const at = (position) => ({ selfState: { position, inventorySummary: [], ownDrops: [] } })
  const underfoot = { kind: 'block', ref: 'g1', what: 'block.minecraft.grass_block', direction: 'here', distance: 0.5, verticalOffset: -0.5, visible: true }
  const records = (fleeFrom) => [
    record('delta', { event: 'perceived', requestId: 'd1', requestKey: 'delta:d1', perception: { requestId: 'd1', ...at([0.5, 66, 0.5]), surroundings: [underfoot] } }),
    record('delta', { event: 'decided', requestId: 'd1', requestKey: 'delta:d1', intent: 'dig', decision: { intent: { action: 'dig', block: 'g1' } } }),
    record('delta', { event: 'intentApplied', requestId: 'd1', requestKey: 'delta:d1', intent: 'dig', outcome: 'accepted' }),
    record('delta', { event: 'worldInteraction', requestId: 'd1', requestKey: 'delta:d1', outcome: 'serverEffectConfirmed' }),
    record('delta', { event: 'perceived', requestId: 'f1', requestKey: 'delta:f1', perception: { requestId: 'f1', ...at(fleeFrom), trigger: { event: 'threatSighted' } } }),
    record('delta', { event: 'decided', requestId: 'f1', requestKey: 'delta:f1', intent: 'flee', decision: { intent: { action: 'flee' } } }),
    record('delta', { event: 'intentApplied', requestId: 'f1', requestKey: 'delta:f1', intent: 'flee', outcome: 'retreatNoRoute' })
  ]
  const classOf = (generation, fleeFrom) => readinessMetrics({ bodyRecords: records(fleeFrom), harnessRecords: [], metrics: {}, generation })
    .actions.nonSuccess.find((entry) => entry.requestKey === 'delta:f1').class
  assert.equal(classOf(12, [-41.6, 48, -58.4]), 'selfTrapped')
  assert.equal(classOf(13, [-41.6, 48, -58.4]), 'terrain')
  assert.equal(classOf(13, [0.5, 65, 0.5]), 'selfTrapped')
})

// Gate generation 14 (the r23 audit).
const BARS14 = PROFILES['discovery-goal-10m-10'].coherenceBars
const gate14 = (bodyRecords, bars = BARS14) =>
  readinessMetrics({ bodyRecords, harnessRecords: [], metrics: {}, generation: 14, bars })
const STICK = 'item.minecraft.stick'

test('under generation 14 sight guarantees its calibrated mean, not its least, and a recipe from what is held', () => {
  // A dead bush drops nought to two sticks, one on average: a goal of two that holds one later
  // found nothing sight did not already promise.
  const bush = (count) => ({
    selfState: { inventorySummary: count === 0 ? [] : [{ ref: 'i1', what: STICK, count }], ownDrops: [] },
    surroundings: [{
      kind: 'block',
      ref: 'b1',
      what: 'block.minecraft.dead_bush',
      distance: 1.5,
      verticalOffset: 0,
      visible: true,
      digSafe: true,
      yields: [{ what: STICK, least: 0, most: 2, perHundred: 100 }]
    }],
    horizon: [{ what: STICK, grounding: 'sight', most: 9, frontier: true }]
  })
  const goal = [
    ...review('alpha', 'a1', {
      goal: { outcome: 'goalAdopted', disposition: 'adopt', goalId: 'alpha', revision: 0 },
      choice: { disposition: 'adopt', successItem: STICK, successCount: 3 },
      perception: bush(0)
    }),
    ...review('alpha', 'a2', { goal: stepped('alpha', true), perception: bush(1) })
  ]
  const bars13 = { ...BARS, dangerEpisodes: 0 }
  assert.equal(readinessMetrics({ bodyRecords: goal, harnessRecords: [], metrics: {}, generation: 13, bars: bars13 })
    .goalCoherence.pursuit.measured.discoveredGoals, 1)
  assert.equal(gate14(goal).goalCoherence.pursuit.measured.discoveredGoals, 0)
  // Holding one log, four planks are a recipe away: local, not discovered.
  const planks = review('beta', 'b1', {
    intent: 'craft',
    goal: { outcome: 'goalAdopted', disposition: 'adopt', goalId: 'beta', revision: 0 },
    choice: { disposition: 'adopt', successItem: PLANKS, successCount: 4 },
    perception: {
      selfState: { inventorySummary: [{ ref: 'i1', what: LOG, count: 1 }], ownDrops: [] },
      surroundings: [],
      horizon: [{ what: PLANKS, grounding: 'recipe', via: LOG, most: 8, frontier: false }]
    }
  })
  assert.equal(gate14(planks).goalCoherence.pursuit.measured.horizonGoals, 0)
})

test('under generation 14 a chained goal one craft met in full is a chain, and held time starts at the first advance', () => {
  const parentAndChild = [
    ...review('gamma', 'g1', {
      goal: { outcome: 'goalAdopted', disposition: 'adopt', goalId: 'gamma', revision: 0 },
      choice: { disposition: 'adopt', successItem: LOG, successCount: 3 },
      perception: sight(LOG, 0)
    }),
    ...review('gamma', 'g2', {
      intent: 'craft',
      goal: { ...stepped('gamma', true, 'goalReplaced'), goalId: 'gamma-planks', chainedFrom: 'gamma' },
      choice: { disposition: 'replace', successItem: PLANKS, successCount: 4, stepResult: 'progressing' },
      perception: sight(LOG, 1)
    }),
    ...review('gamma', 'g3', {
      intent: 'moveTo',
      onto: 'x1',
      goal: stepped('gamma-planks', true, 'goalCompletedAdopted', { conditionMet: true, goalId: 'gamma-next' }),
      shown: { goalId: 'gamma-planks', stepAction: 'craft' },
      perception: sight(PLANKS, 4)
    })
  ]
  const bars13 = { ...BARS, dangerEpisodes: 0 }
  const thirteen = readinessMetrics({ bodyRecords: parentAndChild, harnessRecords: [], metrics: {}, generation: 13, bars: bars13 })
  assert.equal(thirteen.goalCoherence.pursuit.measured.chainedGoals, 0)
  const fourteen = gate14(parentAndChild).goalCoherence.pursuit.measured
  assert.equal(fourteen.chainedGoals, 1)
  assert.equal(fourteen.craftChainSteps, 1)
  // A goal adopted and never advanced holds no progressing time from generation 14.
  const idleGoal = [
    ...review('delta', 'd1', {
      goal: { outcome: 'goalAdopted', disposition: 'adopt', goalId: 'delta', revision: 0 },
      choice: { disposition: 'adopt', successItem: LOG, successCount: 3 },
      perception: sight(LOG, 0)
    }),
    ...review('delta', 'd2', { goal: stepped('delta', false), perception: sight(LOG, 0) }),
    ...review('delta', 'd3', { goal: stepped('delta', false), perception: sight(LOG, 0) })
  ]
  assert.ok(readinessMetrics({ bodyRecords: idleGoal, harnessRecords: [], metrics: {}, generation: 13, bars: bars13 })
    .goalCoherence.pursuit.measured.progressingHeldTimeShare > 0)
  assert.equal(gate14(idleGoal).goalCoherence.pursuit.measured.progressingHeldTimeShare, 0)
})

test('under generation 14 a discovery goal is counted apart, and the bars no run could fail are description only', () => {
  const discovery = [
    ...review('kappa', 'k1', {
      intent: 'moveTo',
      onto: 'x1',
      goal: { outcome: 'goalAdopted', disposition: 'adopt', goalId: 'kappa', revision: 0, discovery: true },
      choice: { disposition: 'adopt', discover: true },
      perception: { ...sight(LOG, 0), explore: [{ ref: 'x1' }] }
    }),
    ...review('kappa', 'k2', {
      goal: {
        ...stepped('kappa', true, 'goalCompletedAdopted', { conditionMet: true, stepEvidence: 'revealed' }),
        goalId: 'kappa-log',
        presentedDiscovery: true,
        discoveredFrom: 'kappa'
      },
      choice: { disposition: 'replace', successItem: LOG, successCount: 4, stepResult: 'done' },
      perception: sight(LOG, 0)
    })
  ]
  const result = gate14(discovery)
  const measured = result.goalCoherence.pursuit.measured
  assert.equal(measured.discoveryGoals, 1)
  assert.equal(measured.discoveryReveals, 1)
  assert.equal(measured.discoveryTransitions, 1)
  // The discovery goal itself is no item goal, so it is no one-step goal either.
  assert.equal(measured.oneStepGoals, 0)
  assert.deepEqual(result.goalCoherence.pursuit.descriptive, ['distinctGoalItems', 'exploringAgents', 'dangerEpisodes'])
  assert.equal(result.version, 'loom-readiness-gate/12')
  // A bar no run could fail moves no verdict.
  const unreachable = { ...BARS14, dangerEpisodes: 1000, exploringAgents: 1000, distinctGoalItems: 1000 }
  assert.equal(gate14(discovery, unreachable).goalCoherence.pursuit.verdict, result.goalCoherence.pursuit.verdict)
  // A discovery goal set where it was not offered is a refused, incoherent decision.
  const refused = review('kappa', 'k9', {
    intent: 'moveTo',
    onto: 'x1',
    goal: { outcome: 'goalDiscoveryNotOffered' },
    choice: { disposition: 'adopt', discover: true },
    applied: 'goalDiscoveryNotOffered',
    perception: sight(LOG, 0)
  })
  assert.equal(gate14(refused).actions.byClass.goalIncoherent, 1)
})

const R24 = path.join(__dirname, '..', 'experiments', '2026-10-16-openai-10-agent-10m-r24')

test('r24, judged under gate generation 15, keeps its schema blocker and reads the health floor as a race', () => {
  const records = fs.readFileSync(path.join(R24, 'evidence.jsonl'), 'utf8').trim().split('\n').map(JSON.parse)
  const metrics = JSON.parse(fs.readFileSync(path.join(R24, 'metrics.json'), 'utf8'))
  const judged = (generation) => readinessMetrics({
    bodyRecords: records.filter((entry) => entry.source === 'body'),
    harnessRecords: records.filter((entry) => entry.source === 'harness'),
    metrics,
    generation,
    bars: PROFILES['discovery-goal-10m-10'].coherenceBars
  })
  const fourteen = judged(14)
  const fifteen = judged(15)
  assert.equal(fourteen.version, 'loom-readiness-gate/12')
  assert.equal(fifteen.version, 'loom-readiness-gate/15', 'the version now names its gate generation')
  // delta's attack, perceived at 8.5 and bitten to the 4.5 floor while it decided, is a race.
  const delta = (result) => result.actions.nonSuccess.find((entry) => entry.requestKey === 'tribe-delta:loom3b4ef3e082-sense-sc')
  assert.equal(delta(fourteen).class, 'inapplicable')
  assert.equal(delta(fifteen).class, 'concurrent')
  assert.equal(fifteen.blockers.inapplicable, 0)
  assert.equal(fifteen.blockers.goalIncoherent, 1, 'theta was refused, and stays a blocker of its run')
  // Idle and refused stretches no longer count as progressing time (r24 theta and beta).
  const held = (result) => result.goalCoherence.pursuit.measured.progressingHeldTimeShare
  assert.ok(held(fifteen) < held(fourteen), `${held(fifteen)} < ${held(fourteen)}`)
  // delta's planks, crafted and seen held before it died, are a chain.
  assert.equal(fourteen.goalCoherence.pursuit.measured.chainedGoals, 0)
  assert.ok(fifteen.goalCoherence.pursuit.measured.chainedGoals >= 1)
  // What describes the run sits apart from the verdict evidence.
  assert.equal(fifteen.goalCoherence.pursuit.description.deaths, 10)
  assert.equal(fifteen.goalCoherence.pursuit.verdict, 'notCleared')
})
