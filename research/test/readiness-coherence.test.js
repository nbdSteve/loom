'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')

const { readinessMetrics } = require('../lib/readiness.js')

const R11 = path.join(__dirname, '..', 'experiments', '2026-10-16-openai-10-agent-10m-r11')
let sequence = 0

function record (agentId, fields) {
  sequence += 1
  return { recordId: `body:${String(sequence).padStart(8, '0')}`, tick: sequence, agentId, ...fields }
}

/** One idle goal review whose decided record carries [goal], answered by the body. */
function review (agentId, id, goal, applied = 'accepted') {
  const requestKey = `${agentId}:${id}`
  return [
    record(agentId, { event: 'decided', requestId: id, requestKey, intent: 'idle', goal }),
    record(agentId, { event: 'intentApplied', requestId: id, requestKey, intent: 'idle', outcome: applied })
  ]
}

const measured = (goalId, conditionAdvanced, stepAdvanced, outcome = 'goalContinued', conditionMet = false) =>
  ({ outcome, presentedGoalId: goalId, presentedRevision: 0, goalId, revision: 0, stepsWithoutProgress: 0, conditionMet, conditionAdvanced, stepAdvanced })

function gate4 (bodyRecords) {
  return readinessMetrics({ bodyRecords, harnessRecords: [], metrics: {}, generation: 4 })
}

test('r11, judged under the typed gate, is not ready: its goals advanced on movement alone', () => {
  const records = fs.readFileSync(path.join(R11, 'evidence.jsonl'), 'utf8').trim().split('\n').map(JSON.parse)
  const metrics = JSON.parse(fs.readFileSync(path.join(R11, 'metrics.json'), 'utf8'))
  const result = readinessMetrics({
    bodyRecords: records.filter((entry) => entry.source === 'body'),
    harnessRecords: records.filter((entry) => entry.source === 'harness'),
    metrics,
    generation: 4
  })
  // The mechanical rates were whole, and remain so; the coherence half is what fails.
  assert.equal(result.mechanicalVerdict, 'ready')
  assert.equal(result.goalCoherence.verdict, 'incoherent')
  assert.equal(result.goalCoherence.multiStepGoals, 0)
  assert.equal(result.verdict, 'notReady')
  // Under generation 3 it was ready, which is the reading the independent audit refused.
  assert.equal(metrics.behaviorGate.readiness.verdict, 'ready')
})

test('a goal whose condition advanced twice and was completed when met is coherent', () => {
  const result = gate4([
    ...review('kappa', 'r1', { outcome: 'goalAdopted', disposition: 'adopt', goalId: 'g', revision: 0 }),
    ...review('kappa', 'r2', measured('g', false, true)),
    ...review('kappa', 'r3', measured('g', true, true)),
    ...review('kappa', 'r4', measured('g', true, true, 'goalCompleted', true))
  ])
  assert.equal(result.goalCoherence.verdict, 'coherent', JSON.stringify(result.goalCoherence))
  assert.equal(result.goalCoherence.multiStepGoals, 1)
  assert.equal(result.goalCoherence.conditionSteps, 2)
  assert.equal(result.goalCoherence.stepOnlySteps, 1)
  assert.equal(result.goalCoherence.metCompleted, 1)
  assert.equal(result.verdict, 'ready')
})

test('steps credited only for carrying out the step are no multi-step goal', () => {
  // r11 alpha, beta, iota and theta: every review moved the body, none advanced the condition.
  const result = gate4([
    ...review('alpha', 'r1', { outcome: 'goalAdopted', disposition: 'adopt', goalId: 'g', revision: 0 }),
    ...review('alpha', 'r2', measured('g', false, true)),
    ...review('alpha', 'r3', measured('g', false, true)),
    ...review('alpha', 'r4', measured('g', false, false))
  ])
  assert.equal(result.goalCoherence.multiStepGoals, 0)
  assert.equal(result.goalCoherence.stepOnlySteps, 2)
  assert.equal(result.goalCoherence.unadvancedSteps, 1)
  assert.equal(result.goalCoherence.verdict, 'incoherent')
  assert.equal(result.verdict, 'notReady')
})

test('each incoherent decision fails coherence and is a blocker, refused before the body', () => {
  const coherent = [
    ...review('kappa', 'k1', measured('k', true, true)),
    ...review('kappa', 'k2', measured('k', true, true))
  ]
  for (const [tag, cited] of [
    ['goalStepMismatch', 'alpha body:00000233, gamma body:00000048, theta body:00000063'],
    ['goalConditionMetIgnored', 'delta body:00000119, epsilon body:00000121, zeta body:00000147'],
    ['goalCompletionUnmet', 'a completion the inventory does not show']
  ]) {
    const refused = review('delta', 'd1', { ...measured('d', false, false, tag), conditionMet: tag === 'goalConditionMetIgnored' }, tag)
    const result = gate4([...coherent, ...refused])
    assert.equal(result.goalCoherence.verdict, 'incoherent', cited)
    assert.equal(result.blockers.goalIncoherent, 1, cited)
    assert.equal(result.actions.nonSuccess.find((entry) => entry.class === 'goalIncoherent').evidence.outcome, tag)
    assert.ok(result.rates.decisionValidity < 1, cited)
    assert.equal(result.verdict, 'notReady', cited)
  }
})

test('a met condition answered by a revise to a new target is answered', () => {
  const result = gate4([
    ...review('zeta', 'z1', measured('z', true, true)),
    ...review('zeta', 'z2', measured('z', true, true, 'goalRevised', true))
  ])
  assert.equal(result.goalCoherence.metRevised, 1)
  assert.equal(result.goalCoherence.metIgnored, 0)
  assert.equal(result.goalCoherence.verdict, 'coherent')
})
