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

test('r34 delta\'s four blockers, judged under generation 18, are one stuck body\'s incomplete movements', () => {
  // Three collects of its own logs (ticks 3622, 6026, 7835) and a flight (8951), all from the one
  // cell under the oak's leaves at (-8.43, 66, -27.5), none of them a decision's fault.
  const keys = ['bq', 'k5', 'q8', 'u1'].map((suffix) => `tribe-delta:loom3b4ef3e082-sense-${suffix}`)
  const classOf = (result, key) => result.actions.nonSuccess.find((entry) => entry.requestKey === key)?.class
  const seventeen = judged(17)
  const eighteen = judged(18)
  assert.deepEqual(keys.map((key) => classOf(seventeen, key)), ['inapplicable', 'inapplicable', 'inapplicable', 'incompleteMovement'])
  assert.deepEqual(keys.map((key) => classOf(eighteen, key)), Array(4).fill('incompleteMovement'))
  assert.equal(eighteen.blockers.inapplicable, 0)
  assert.equal(eighteen.blockers.incompleteMovement, 4)
  // A stall is the body's failure, so the decisions that met it are valid.
  assert.ok(eighteen.rates.decisionValidity > seventeen.rates.decisionValidity)
})

test('r34\'s collects of the lodged log are the ground\'s answer only once the server verifies it', () => {
  // kappa (tick 8218) and theta (10916) were offered the log ce29f989 delta's pickup had already
  // found no route to; r34 recorded no reading of the ground, so under 18 neither is excused.
  const keys = ['tribe-kappa:loomf309750e7b-sense-rl', 'tribe-theta:loom9bc6579ca4-sense-10x']
  const classOf = (result, key) => result.actions.nonSuccess.find((entry) => entry.requestKey === key)?.class
  assert.deepEqual(keys.map((key) => classOf(judged(17), key)), ['terrain', 'terrain'])
  assert.deepEqual(keys.map((key) => classOf(judged(18), key)), ['unverifiedTerrain', 'unverifiedTerrain'])
  // The server's count of cells a body could pick the log up from - none, as
  // `R34TerrainReplayTest` reads r34's ground - verifies it.
  const verified = records.map((entry) => keys.includes(entry.requestKey) && entry.event === 'intentApplied'
    ? { ...entry, terrain: { pickupCells: 0 } }
    : entry)
  const reread = readinessMetrics({
    bodyRecords: verified.filter((entry) => entry.source === 'body'),
    harnessRecords: verified.filter((entry) => entry.source === 'harness'),
    metrics,
    generation: 18,
    bars: PROFILES['balanced-envelope-10m-10'].coherenceBars
  })
  assert.deepEqual(keys.map((key) => classOf(reread, key)), ['terrain', 'terrain'])
  assert.equal(reread.blockers.unverifiedTerrain, 0)
  // Cells a walk over the server's ground reaches leave the no-route the body's claim; cells no
  // walk reaches verify it.
  const regrounded = (terrain) => readinessMetrics({
    bodyRecords: records.filter((entry) => entry.source === 'body').map((entry) =>
      keys.includes(entry.requestKey) && entry.event === 'intentApplied' ? { ...entry, terrain } : entry),
    harnessRecords: records.filter((entry) => entry.source === 'harness'),
    metrics,
    generation: 18,
    bars: PROFILES['balanced-envelope-10m-10'].coherenceBars
  })
  assert.equal(regrounded({ pickupCells: 2, walkable: true }).blockers.unverifiedTerrain, 2)
  assert.equal(regrounded({ pickupCells: 2 }).blockers.unverifiedTerrain, 2)
  assert.equal(regrounded({ pickupCells: 2, walkable: false }).blockers.unverifiedTerrain, 0)
})
