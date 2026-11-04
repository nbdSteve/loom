'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const { FAILURE_REASONS, MIND_REFUSALS, failureReasonOf } = require('../lib/cognition-failure.js')

const COGNITION_SOURCE = path.join(__dirname, '..', '..', 'cognition', 'crates', 'cognition', 'src')

test('each named mind refusal is a message the mind really sends', () => {
  // The error frame is `cognition failed: {e}` (`server.rs` `answer`), so a reworded refusal
  // would silently become `unclassified`.
  const source = ['server.rs', 'mind.rs', 'model.rs']
    .map((file) => fs.readFileSync(path.join(COGNITION_SOURCE, file), 'utf8'))
    .join('\n')
  assert.match(source, /encode_error_with_failure\(&format!\("cognition failed: \{e\}"\)/)
  for (const message of MIND_REFUSALS.keys()) {
    const refusal = message.slice('cognition failed: '.length)
    assert.ok(source.includes(`"${refusal}"`), refusal)
  }
})

test('a failure reason is the provider mapping, else the mind refusal, else unclassified', () => {
  assert.equal(failureReasonOf('anything', { mapping: 'providerDecisionParse' }), 'providerDecisionParse')
  assert.equal(
    failureReasonOf('cognition failed: the answer named a ref this perception did not offer for that action', null),
    'mindUnofferedRef'
  )
  assert.equal(failureReasonOf('cognition failed: something private', null), 'unclassified')
  assert.equal(failureReasonOf(undefined, undefined), 'unclassified')
  for (const reason of MIND_REFUSALS.values()) assert.ok(FAILURE_REASONS.has(reason))
})
