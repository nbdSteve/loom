'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')

const { PROFILES } = require('../lib/profiles.js')
const { readinessMetrics } = require('../lib/readiness.js')

const R34 = path.join(__dirname, '..', 'experiments', '2026-10-16-openai-10-agent-10m-r34')
const records = fs.readFileSync(path.join(R34, 'evidence.jsonl'), 'utf8').trim().split('\n').map(JSON.parse)
const metrics = JSON.parse(fs.readFileSync(path.join(R34, 'metrics.json'), 'utf8'))

/** r34's own evidence judged under gate [generation], against its own profile's bars. */
function judged (generation) {
  return readinessMetrics({
    bodyRecords: records.filter((entry) => entry.source === 'body'),
    harnessRecords: records.filter((entry) => entry.source === 'harness'),
    metrics,
    generation,
    bars: PROFILES['balanced-envelope-10m-10'].coherenceBars
  })
}

test('r34 re-derives exactly as published under generation 17, which is frozen', () => {
  const seventeen = judged(17)
  assert.equal(seventeen.version, 'loom-readiness-gate/17')
  assert.equal(seventeen.exposure, undefined)
  assert.deepEqual(seventeen, metrics.behaviorGate.readiness)
})

test('r34, judged under generation 18, does not count the two deaths after its admission closed as readiness', () => {
  // delta was shot dead at tick 12141 and gamma at 12187, 78 and 124 ticks after admission closed
  // at 12063; r34's plugin had no exposure end, so admission's end is the boundary.
  const eighteen = judged(18)
  assert.equal(eighteen.version, 'loom-readiness-gate/18')
  assert.deepEqual(eighteen.exposure, {
    endTick: 12_063,
    source: 'cognitionAdmissionEnded',
    deathsAfter: ['tribe-delta', 'tribe-gamma'],
    damageAfter: eighteen.exposure.damageAfter,
    drained: 0
  })
  assert.ok(eighteen.exposure.damageAfter >= 2, `${eighteen.exposure.damageAfter}`)
  assert.equal(eighteen.mechanicalVerdict, 'notReady')
})
