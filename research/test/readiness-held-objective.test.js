'use strict'

const assert = require('node:assert/strict')
const { test } = require('node:test')

const { PROFILES } = require('../lib/profiles.js')
const { HELD_OBJECTIVE_GOAL_INCOHERENT_TAGS, readinessMetrics } = require('../lib/readiness.js')

// Gate generation 20 (the r39 audit). Every metric, denominator and bar of 19 is kept; what moves
// is how four kinds of ending are classed, each read the way the plugin journals it.

const bars = PROFILES['held-objective-10m-10'].coherenceBars

function key (agentId, requestId) {
  return `${agentId}:${requestId}`
}

function perceived (agentId, requestId, tick, cover) {
  return {
    event: 'perceived',
    agentId,
    tick,
    requestKey: key(agentId, requestId),
    perception: { capturedTick: tick, selfState: { world: { dayPhase: 'day', isDay: true, ticksUntilNightfall: 6000 }, cover } }
  }
}

function decided (agentId, requestId, tick, intent, decision = { action: intent }) {
  return { event: 'decided', agentId, tick, requestKey: key(agentId, requestId), intent, decision: { intent: decision } }
}

function applied (agentId, requestId, tick, outcome = 'accepted') {
  return { event: 'intentApplied', agentId, tick, requestKey: key(agentId, requestId), outcome }
}

function witnessed (agentId, requestId, tick) {
  return { event: 'worldInteraction', agentId, tick, requestKey: key(agentId, requestId), outcome: 'serverEffectConfirmed' }
}

function classesOf (records, generation) {
  const result = readinessMetrics({ bodyRecords: records, harnessRecords: [], metrics: {}, generation, bars })
  return Object.fromEntries(result.actions.nonSuccess.map((entry) => [entry.requestKey, entry.class]))
}

test('generation 20 names its own version and keeps every bar of 19', () => {
  const result = readinessMetrics({ bodyRecords: [], harnessRecords: [], metrics: {}, generation: 20, bars })
  assert.equal(result.version, 'loom-readiness-gate/20')
  assert.deepEqual({ ...bars }, { ...PROFILES['shelter-goal-10m-10'].coherenceBars })
  assert.equal(bars.progressingHeldTimeShare, 0.5)
  assert.equal(bars.minAgentProgressingHeldTimeShare, 0.25)
  assert.equal(bars.substantiveCompletions, 3)
  assert.equal(bars.substantiveMultiStepAgents, 3)
})

test('an objective one step meets, a replace by its own item and an unsupported end are goal incoherence', () => {
  // r39 alpha 10106: "hold 9 dirt" replaced by "hold 10 dirt", the step that met it its next dig.
  for (const tag of ['goalTrivial', 'goalReplaceSameItem', 'goalReasonMissing', 'goalReasonUnsupported']) {
    assert.ok(HELD_OBJECTIVE_GOAL_INCOHERENT_TAGS.has(tag), tag)
    const records = [
      perceived('a', 'r1', 100),
      decided('a', 'r1', 100, 'dig', { action: 'dig', block: 'b1' }),
      applied('a', 'r1', 101, tag)
    ]
    assert.equal(classesOf(records, 20)[key('a', 'r1')], 'goalIncoherent', tag)
    // Generation 19 is frozen: it never knew these tags.
    assert.equal(classesOf(records, 19)[key('a', 'r1')], 'unexplainedRefusal', tag)
  }
})

test('any action refused behind the agent\'s own earlier running request is the dangerOverlap blocker', () => {
  // r39 iota sense-u5: a one-block placement held the body 330 ticks under skeleton fire, and three
  // later survival placements were refused controlNotReady.
  const records = [
    perceived('iota', 'u5', 100),
    decided('iota', 'u5', 100, 'place', { action: 'place', block: 'b1', item: 's1', face: 'up' }),
    perceived('iota', 'u6', 200),
    decided('iota', 'u6', 200, 'place', { action: 'place', block: 'b2', item: 's1', face: 'up' }),
    applied('iota', 'u6', 201, 'controlNotReady'),
    applied('iota', 'u5', 430),
    witnessed('iota', 'u5', 431)
  ]
  assert.equal(classesOf(records, 20)[key('iota', 'u6')], 'dangerOverlap')
  // Generation 17 to 19 read only a flight's this way, so the placement was a concurrent race.
  assert.equal(classesOf(records, 19)[key('iota', 'u6')], 'concurrent')
  const result = readinessMetrics({ bodyRecords: records, harnessRecords: [], metrics: {}, generation: 20, bars })
  assert.equal(result.blockers.dangerOverlap, 1)
  assert.equal(result.mechanicalVerdict, 'notReady')
})

test('an older decision refused behind the agent\'s own newer one is superseded, and a settled one leaves a race', () => {
  // Answers arrive in whatever order the minds return them: the newer decision took the body first.
  const behindNewer = [
    perceived('a', 'r1', 100),
    perceived('a', 'r2', 150),
    decided('a', 'r2', 160, 'moveTo', { action: 'moveTo', onto: 'b2' }),
    applied('a', 'r2', 161),
    decided('a', 'r1', 170, 'dig', { action: 'dig', block: 'b1' }),
    applied('a', 'r1', 171, 'controlNotReady'),
    { event: 'moved', agentId: 'a', tick: 260, requestKey: key('a', 'r2'), outcome: 'movementArrived' }
  ]
  const classes = classesOf(behindNewer, 20)
  assert.equal(classes[key('a', 'r1')], 'superseded')
  // With nothing of its own running, the refusal stays the concurrent race it always was.
  const alone = [
    perceived('a', 'r1', 100),
    decided('a', 'r1', 100, 'dig', { action: 'dig', block: 'b1' }),
    applied('a', 'r1', 101, 'controlNotReady')
  ]
  assert.equal(classesOf(alone, 20)[key('a', 'r1')], 'concurrent')
  // An earlier request that had already ended holds nothing.
  const ended = [
    perceived('a', 'r0', 50),
    decided('a', 'r0', 50, 'dig', { action: 'dig', block: 'b0' }),
    applied('a', 'r0', 60),
    witnessed('a', 'r0', 61),
    ...alone
  ]
  assert.equal(classesOf(ended, 20)[key('a', 'r1')], 'concurrent')
})

test('requests are dated as the plugin dates them, by when each was asked, never by when its answer came', () => {
  // The r39 offline audit: a review asked before a sighting whose flight was applied first. Its late
  // dig is refused behind the newer flight and never halts it: superseded, as the plugin decides it.
  const lateRoutine = [
    perceived('b', 'r1', 100),
    perceived('b', 'r2', 110),
    decided('b', 'r2', 120, 'flee', { action: 'flee', from: 'e1' }),
    applied('b', 'r2', 121),
    decided('b', 'r1', 130, 'dig', { action: 'dig', block: 'b1' }),
    applied('b', 'r1', 131, 'controlNotReady'),
    { event: 'retreating', agentId: 'b', tick: 200, requestKey: key('b', 'r2'), outcome: 'retreatSafe' }
  ]
  assert.equal(classesOf(lateRoutine, 20)[key('b', 'r1')], 'superseded')
  // A third, newer decision governs even while an earlier request still runs: the plugin neither
  // halts nor asks again for an answer a newer one replaced, so the refusal is not the overlap.
  const thirdNewer = [
    perceived('b', 'r0', 50),
    decided('b', 'r0', 50, 'dig', { action: 'dig', block: 'b0' }),
    perceived('b', 'r1', 100),
    perceived('b', 'r2', 110),
    decided('b', 'r2', 120, 'idle'),
    applied('b', 'r2', 121),
    decided('b', 'r1', 130, 'place', { action: 'place', block: 'b2', item: 's1', face: 'up' }),
    applied('b', 'r1', 131, 'controlNotReady'),
    applied('b', 'r0', 139),
    witnessed('b', 'r0', 140)
  ]
  assert.equal(classesOf(thirdNewer, 20)[key('b', 'r1')], 'superseded')
  // A newer decision its own goal ledger refused never reached the body, so the earlier work is the overlap.
  const refusedNewer = thirdNewer.map((record) =>
    record.event === 'intentApplied' && record.requestKey === key('b', 'r2') ? { ...record, outcome: 'goalTrivial' } : record)
  assert.equal(classesOf(refusedNewer, 20)[key('b', 'r1')], 'dangerOverlap')
})

test('a placement blocked at a closing its cover offered is a target race, any other is inapplicable', () => {
  const cover = { sealed: false, walkOut: true, open: ['roof'], closings: [{ face: 'roof', block: 'b7', side: 'down' }] }
  const offered = [
    perceived('g', 'r1', 100, cover),
    decided('g', 'r1', 100, 'place', { action: 'place', block: 'b7', item: 's1', face: 'down' }),
    applied('g', 'r1', 101, 'placeDestinationBlocked')
  ]
  assert.equal(classesOf(offered, 20)[key('g', 'r1')], 'targetRace')
  assert.equal(classesOf(offered, 19)[key('g', 'r1')], 'inapplicable')
  const unoffered = [
    perceived('g', 'r1', 100, cover),
    decided('g', 'r1', 100, 'place', { action: 'place', block: 'b8', item: 's1', face: 'up' }),
    applied('g', 'r1', 101, 'placeDestinationBlocked')
  ]
  assert.equal(classesOf(unoffered, 20)[key('g', 'r1')], 'inapplicable')
})
