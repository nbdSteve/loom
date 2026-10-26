'use strict'

const assert = require('node:assert/strict')
const { test } = require('node:test')

const { drained, faultsOf } = require('../tools/path-rehearsal.js')

const AGENTS = ['tribe-alpha', 'tribe-beta']

function body (event, agentId, requestId, extra = {}) {
  return { source: 'body', event, agentId, requestId, requestKey: `${agentId}:${requestId}`, tick: 7392, ...extra }
}

test('a decision with no typed terminal is a fault however late it was taken', () => {
  // The path rehearsal that stopped Paper at its window's end left alpha's decision at tick 7392,
  // after the window, with no terminal; it was exempted as late. No decision is exempt now.
  const late = { requestKey: 'tribe-alpha:sense-p8', class: 'untypedTerminal', evidence: undefined }
  const readiness = { actions: { nonSuccess: [late], byClass: { confirmed: 3, untypedTerminal: 1 } } }
  const faults = faultsOf([body('decided', 'tribe-alpha', 'sense-p8')], readiness)
  assert.equal(faults.untypedTerminals, 1)
  assert.deepEqual(faults.unexpectedClasses, { untypedTerminal: 1 })
})

test('work withheld once admission closed is typed, never a cognition failure', () => {
  const records = [
    body('cognitionUnavailable', 'tribe-beta', 'sense-q1', { outcome: 'exposureEnded' }),
    body('cognitionUnavailable', 'tribe-beta', 'sense-q2', { outcome: 'dangerCoalesced' }),
    body('cognitionUnavailable', 'tribe-beta', 'sense-q3', { outcome: 'cognitionFailed' })
  ]
  const faults = faultsOf(records, { actions: { nonSuccess: [], byClass: {} } })
  assert.equal(faults.cognitionFailures, 1)
  assert.equal(faults.untypedTerminals, 0)
})

test('Paper stops only once every agent\'s exposure ended and every perception has its terminal', () => {
  const sent = [
    body('perceived', 'tribe-alpha', 'sense-p8'),
    body('decided', 'tribe-alpha', 'sense-p8'),
    body('perceived', 'tribe-beta', 'sense-q1')
  ]
  assert.equal(drained(sent, AGENTS), false)
  const ended = [...sent, body('exposureEnded', 'tribe-alpha', null), body('exposureEnded', 'tribe-beta', null)]
  assert.equal(drained(ended, AGENTS), false, 'two perceptions still have no terminal')
  const withheld = [...ended, body('cognitionUnavailable', 'tribe-beta', 'sense-q1', { outcome: 'exposureEnded' })]
  assert.equal(drained(withheld, AGENTS), false, 'alpha\'s decision is still being carried out')
  const applied = [...withheld, body('intentApplied', 'tribe-alpha', 'sense-p8', { outcome: 'accepted' })]
  assert.equal(drained(applied, AGENTS), true)
})
