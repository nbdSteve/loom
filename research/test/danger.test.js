'use strict'

const assert = require('node:assert/strict')
const { test } = require('node:test')

const { dangerEpisodeMetrics, isPreempted } = require('../lib/danger.js')

const coalesced = { event: 'cognitionUnavailable', agentId: 'tribe-alpha', outcome: 'dangerCoalesced', trigger: 'damaged' }

test('a danger answer newer damage preempted is counted beside the coalesced perceptions', () => {
  const preempted = { event: 'decisionDiscarded', agentId: 'tribe-alpha', outcome: 'dangerPreempted' }
  assert.equal(isPreempted(preempted), true)
  assert.equal(isPreempted({ ...preempted, outcome: undefined }), false, 'a discard for a body that has gone')
  assert.equal(dangerEpisodeMetrics([coalesced, preempted]).preempted, 1)
  // Evidence with no preemption derives exactly the earlier shape.
  assert.deepEqual(dangerEpisodeMetrics([coalesced]), { coalesced: 1, byAgent: { 'tribe-alpha': 1 }, byTrigger: { damaged: 1 } })
})

test('a living agent\'s preempted danger answer needs no death and is no discarded cycle', () => {
  // v32 plains rehearsal 23: kappa's preempted answer failed witnessedDeathsAccounted.
  const { deathViolation } = require('../lib/witnessed-death.js')
  const { deriveMetrics } = require('../lib/evidence.js')
  const body = (fields) => ({ source: 'body', agentId: 'tribe-kappa', tick: 100, ...fields })
  const records = [
    body({ event: 'perceived', requestId: 'creeper', requestKey: 'tribe-kappa:creeper', trigger: 'damaged' }),
    body({ event: 'perceived', requestId: 'zombie', requestKey: 'tribe-kappa:zombie', trigger: 'damaged' }),
    body({ event: 'decisionDiscarded', requestId: 'creeper', requestKey: 'tribe-kappa:creeper', outcome: 'dangerPreempted' }),
    body({ event: 'decided', requestId: 'zombie', requestKey: 'tribe-kappa:zombie', intent: 'flee' }),
    body({ event: 'intentApplied', requestId: 'zombie', requestKey: 'tribe-kappa:zombie', intent: 'flee', outcome: 'accepted' })
  ]
  assert.equal(deathViolation(records), null)
  const untyped = records.map((record) => record.event === 'decisionDiscarded' ? { ...record, outcome: undefined } : record)
  assert.match(deathViolation(untyped), /decisionDiscarded without a witnessed death/)
  const cycles = deriveMetrics(records, ['tribe-kappa']).cycles
  assert.equal(cycles.discarded, 0)
  assert.equal(cycles.preempted, 1)
  assert.deepEqual(cycles.incomplete, [])
  assert.equal(deriveMetrics(untyped, ['tribe-kappa']).cycles.preempted, undefined)
})
