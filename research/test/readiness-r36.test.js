'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')

const { PROFILES } = require('../lib/profiles.js')
const { readinessMetrics } = require('../lib/readiness.js')

const R36 = path.join(__dirname, '..', 'experiments', '2026-10-16-openai-10-agent-10m-r36')
const records = fs.readFileSync(path.join(R36, 'evidence.jsonl'), 'utf8').trim().split('\n').map(JSON.parse)
const metrics = JSON.parse(fs.readFileSync(path.join(R36, 'metrics.json'), 'utf8'))

// r36 zeta's dig of short grass at (-65, 64, -36), one column past the 128-block world border.
const ZETA_DIG = 'tribe-zeta:loom3d8263b30e-sense-kr'

/** r36's evidence, or [given] in its place, judged under gate [generation] against its own profile's bars. */
function judged (generation, given = records) {
  return readinessMetrics({
    bodyRecords: given.filter((entry) => entry.source === 'body'),
    harnessRecords: given.filter((entry) => entry.source === 'harness'),
    metrics,
    generation,
    bars: PROFILES['balanced-follow-up-10m-10'].coherenceBars
  })
}

test('r36 re-derives exactly as published under generation 18, which is frozen', () => {
  const eighteen = judged(18)
  assert.equal(eighteen.version, 'loom-readiness-gate/18')
  assert.deepEqual(eighteen, metrics.behaviorGate.readiness)
})

test('r36\'s one blocker is zeta\'s dig past the border, the body\'s library failure and no server break', () => {
  const eighteen = judged(18)
  assert.equal(eighteen.mechanicalVerdict, 'notReady')
  const blocking = eighteen.actions.nonSuccess.filter((entry) => entry.class === 'protocolError')
  assert.deepEqual(blocking.map((entry) => entry.requestKey), [ZETA_DIG])
  const applied = records.find((entry) => entry.requestKey === ZETA_DIG && entry.event === 'intentApplied')
  assert.equal(applied.outcome, 'digLibraryFailure')
  assert.equal(applied.recordId, 'body:00000551')
  const witnessed = records.find((entry) => entry.requestKey === ZETA_DIG && entry.event === 'worldInteraction')
  assert.equal(witnessed.recordId, 'body:00000552')
  assert.match(witnessed.summary, /body library_failure; vanilla no matching blockBreak event$/)
})

test('the same dig refused before it is sent still blocks, so only a sense that never offers it clears the run', () => {
  // The body now refuses a dig of a block the border has moved past as `notDiggable` without a
  // packet, and offers no block past the border at all. The gate is unchanged: a decision naming a
  // block nothing can dig is still an inapplicable action, so the fix is in what a sense offers.
  const refused = records.map((entry) => entry.requestKey === ZETA_DIG && entry.event === 'intentApplied'
    ? { ...entry, outcome: 'digNotDiggable', summary: 'dig bbd7 -> rejected: dig target not diggable' }
    : entry)
  const eighteen = judged(18, refused)
  const zeta = eighteen.actions.nonSuccess.find((entry) => entry.requestKey === ZETA_DIG)
  assert.equal(zeta.class, 'inapplicable')
  assert.equal(eighteen.blockers.protocolError, 0)
  assert.equal(eighteen.blockers.inapplicable, 1)
  assert.equal(eighteen.mechanicalVerdict, 'notReady')
})
