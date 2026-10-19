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
