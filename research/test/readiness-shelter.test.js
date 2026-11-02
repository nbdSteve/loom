'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')

const { PROFILES } = require('../lib/profiles.js')
const { readinessMetrics } = require('../lib/readiness.js')

// Gate generation 19 (the r36 audit: all ten agents died at night in the open). One agent's own
// shelter goal, three digs down and one placement over its head, read the way the plugin journals
// it, with the server's sealed record beside it.

const NIGHTFALL = 7_800
const bars = PROFILES['shelter-goal-10m-10'].coherenceBars

function perceived (agentId, requestId, tick, dayPhase = 'day') {
  return {
    event: 'perceived',
    agentId,
    tick,
    requestKey: `${agentId}:${requestId}`,
    perception: {
      capturedTick: tick,
      selfState: { world: { dayPhase, isDay: dayPhase !== 'night', ticksUntilNightfall: NIGHTFALL - tick } }
    }
  }
}

function decided (agentId, requestId, tick, intent, goal) {
  return { event: 'decided', agentId, tick, requestKey: `${agentId}:${requestId}`, intent, goal }
}

function applied (agentId, requestId, tick, intent, outcome = 'accepted') {
  return { event: 'intentApplied', agentId, tick, requestKey: `${agentId}:${requestId}`, intent, outcome }
}

function witnessed (agentId, requestId, tick) {
  return { event: 'worldInteraction', agentId, tick, requestKey: `${agentId}:${requestId}`, outcome: 'serverEffectConfirmed' }
}

/** One agent's shelter: adopted at 5000, dug three times, roofed, sealed at 5400 and held to the end. */
function shelteredAgent (agentId, { lastStepTick = 5_300, confirmed = true } = {}) {
  const adopt = { outcome: 'goalAdopted', disposition: 'adopt', goalId: 'r1', revision: 0, shelter: true }
  const step = (requestId, tick, intent, closed, closable) => [
    perceived(agentId, requestId, tick),
    decided(agentId, requestId, tick, intent, {
      outcome: 'goalContinued',
      disposition: 'continue',
      presentedGoalId: 'r1',
      presentedRevision: 0,
      goalId: 'r1',
      revision: 0,
      presentedShelter: true,
      conditionAdvanced: true,
      stepAdvanced: true,
      stepEvidence: 'covered',
      steppedBy: `prior-${requestId}`,
      cover: { closed, closable, sealed: false }
    })
  ]
  return [
    perceived(agentId, 'r1', 5_000),
    decided(agentId, 'r1', 5_000, 'dig', adopt),
    applied(agentId, 'prior-r2', 5_050, 'dig'),
    witnessed(agentId, 'prior-r2', 5_050),
    ...step('r2', 5_100, 'dig', 4, 4),
    applied(agentId, 'prior-r3', lastStepTick - 50, 'place'),
    ...(confirmed ? [witnessed(agentId, 'prior-r3', lastStepTick - 50)] : []),
    ...step('r3', lastStepTick, 'place', 8, 1),
    { event: 'shelterChanged', agentId, tick: 5_400, outcome: 'sheltered', shelter: { sealed: true, closed: 9 } },
    decided(agentId, 'r4', 5_500, 'rest', {
      outcome: 'goalCompleted',
      disposition: 'complete',
      presentedGoalId: 'r1',
      presentedRevision: 0,
      presentedShelter: true,
      conditionMet: true,
      cover: { closed: 9, closable: 0, sealed: true }
    })
  ]
}

function judged (bodyRecords, generation = 19) {
  return readinessMetrics({ bodyRecords, harnessRecords: [], metrics: {}, generation, bars })
}

test('generation 18 is frozen: it states no shelter at all', () => {
  assert.equal(judged(shelteredAgent('a'), 18).shelter, undefined)
  assert.equal(judged(shelteredAgent('a'), 18).version, 'loom-readiness-gate/18')
})

test('an agent\'s own shelter, witnessed step by step before night, is shelter progress and sealed time', () => {
  const records = [
    ...shelteredAgent('a'),
    { event: 'damageWitnessed', agentId: 'a', tick: 9_000, outcome: 'damageSubmitted' },
    { event: 'exposureEnded', agentId: 'a', tick: 11_800 }
  ]
  const result = judged(records)
  assert.equal(result.version, 'loom-readiness-gate/19')
  assert.equal(result.shelter.nightfallTick, NIGHTFALL)
  assert.deepEqual(result.shelter.progressingAgents, ['a'])
  assert.deepEqual(result.shelter.measured, {
    shelterProgressAgentsBeforeNight: 1,
    shelterGoals: 1,
    shelterCompletions: 1,
    coverQuality: { a: 9 },
    // Sealed from 5400 to the exposure's end, counted from nightfall: 11800 - 7800.
    timeShelteredTicks: 4_000,
    damageWhileSheltered: 1,
    survivedExposure: 1,
    shelteredAtExposureEnd: 1
  })
  // One agent is not the three the profile pre-registers.
  assert.equal(result.shelter.verdict, 'notCleared')
  assert.equal(result.verdict, 'notReady')
})

test('three agents sheltering before night clear the bar; a step after nightfall or unwitnessed does not count', () => {
  const late = shelteredAgent('d', { lastStepTick: NIGHTFALL + 100 })
  // Drop d's first, early covered step so only its late one remains.
  const lateOnly = late.filter((record) => record.requestKey !== 'd:r2')
  const records = [
    ...shelteredAgent('a'),
    ...shelteredAgent('b'),
    ...shelteredAgent('c', { confirmed: false }),
    ...lateOnly,
    { event: 'exposureEnded', agentId: 'a', tick: 11_800 }
  ]
  const result = judged(records)
  // c's placement was never confirmed by the server, but its witnessed dig before it still counts.
  assert.deepEqual(result.shelter.progressingAgents, ['a', 'b', 'c'])
  assert.equal(result.shelter.verdict, 'cleared')
  // A death ends a sealed interval; a shelter goal is counted apart from every item measure.
  const died = [...shelteredAgent('a'), { event: 'died', agentId: 'a', tick: 9_000 }, { event: 'exposureEnded', agentId: 'a', tick: 11_800 }]
  const dead = judged(died).shelter.measured
  assert.equal(dead.timeShelteredTicks, 9_000 - NIGHTFALL)
  assert.equal(dead.survivedExposure, 0)
})

test('r36, judged under generation 19 against the shelter profile, shows no shelter and does not clear it', () => {
  const R36 = path.join(__dirname, '..', 'experiments', '2026-10-16-openai-10-agent-10m-r36')
  const records = fs.readFileSync(path.join(R36, 'evidence.jsonl'), 'utf8').trim().split('\n').map(JSON.parse)
  const metrics = JSON.parse(fs.readFileSync(path.join(R36, 'metrics.json'), 'utf8'))
  const result = readinessMetrics({
    bodyRecords: records.filter((entry) => entry.source === 'body'),
    harnessRecords: records.filter((entry) => entry.source === 'harness'),
    metrics,
    generation: 19,
    bars
  })
  assert.equal(result.shelter.measured.shelterGoals, 0)
  assert.equal(result.shelter.measured.survivedExposure, 0)
  assert.equal(result.shelter.verdict, 'notCleared')
  // Its item-chain measures are the ones generation 18 published.
  assert.deepEqual(result.goalCoherence.pursuit.measured, metrics.behaviorGate.readiness.goalCoherence.pursuit.measured)
})
