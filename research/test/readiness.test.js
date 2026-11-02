'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')

const { BLOCKER_CLASSES, RACE_CLASSES, readinessMetrics, refusalClass } = require('../lib/readiness.js')
const { GATE_GENERATION } = require('../lib/behavior-gate.js')

const AGENT = 'tribe-alpha'
let sequence = 0

function record (fields) {
  sequence += 1
  return { recordId: `body:${String(sequence).padStart(8, '0')}`, tick: sequence, agentId: AGENT, ...fields }
}

/** One decision and the records that answered it, under one request key. */
function action (id, intent, ...answers) {
  const requestKey = `${AGENT}:${id}`
  return [
    record({ event: 'decided', requestId: id, requestKey, intent }),
    ...answers.map((answer) => record({ requestId: id, requestKey, intent, ...answer }))
  ]
}

const applied = (outcome) => ({ event: 'intentApplied', outcome })
const checked = (outcome = 'serverEffectConfirmed') => ({ event: 'worldInteraction', outcome })

function readiness (bodyRecords, { harnessRecords = [], metrics = {} } = {}) {
  return readinessMetrics({ bodyRecords, harnessRecords, metrics })
}

test('every refusal tag the body can file is classified, none as unexplained', () => {
  const source = fs.readFileSync(
    path.join(__dirname, '..', '..', 'plugin', 'src', 'main', 'kotlin', 'dev', 'loom', 'body', 'EventTag.kt'),
    'utf8'
  )
  const constants = Object.fromEntries([...source.matchAll(/const val (\w+): String = "(\w+)"/g)].map((match) => [match[1], match[2]]))
  const block = source.slice(source.indexOf('val INTENT_REFUSAL_TAGS'))
  const tags = [...block.slice(0, block.indexOf('\n    )')).matchAll(/EventTag\.(\w+)/g)].map((match) => constants[match[1]])
  assert.ok(tags.length > 100, `${tags.length} refusal tags`)
  for (const tag of tags) {
    assert.notEqual(refusalClass(tag, GATE_GENERATION), 'unexplainedRefusal', tag)
  }
  assert.equal(refusalClass('somethingNobodyFiles'), 'unexplainedRefusal')
  // r22 delta body:00002116: its first strike landed and the skeleton then left range.
  assert.equal(refusalClass('attackTargetEscaped'), 'targetRace')
  assert.equal(refusalClass('attackOutOfRange'), 'inapplicable')
})

test('a run whose every action succeeded or lost a legitimate race is ready', () => {
  const records = [
    ...action('r1', 'dig', applied('accepted'), checked()),
    ...action('r2', 'moveTo', applied('accepted'), { event: 'moved', outcome: 'movementArrived' }),
    ...action('r3', 'flee', applied('accepted'), { event: 'retreating', outcome: 'retreatSafe' }),
    // A flight the threat kept pace with still ran (r22).
    ...action('r3b', 'flee', applied('accepted'), { event: 'retreating', outcome: 'retreatShort' }),
    ...action('r4', 'eat', applied('accepted')),
    record({ event: 'energyChanged' }),
    ...action('r5', 'idle', applied('accepted')),
    // Races: the target went, changed, the agent's own decisions overlapped, and a death.
    ...action('r6', 'dig', applied('digBlockGone'), checked()),
    ...action('r7', 'collect', applied('collectItemChanged'), checked()),
    ...action('r8', 'dig', applied('controlNotReady')),
    ...action('r9', 'place', applied('placeCancelled'), checked()),
    ...action('r10', 'craft', applied('craftBodyDied'), checked())
  ]
  const result = readiness(records)
  assert.equal(result.verdict, 'ready', JSON.stringify(result, null, 2))
  assert.equal(result.actions.total, 10)
  assert.equal(result.actions.byClass.confirmed, 5)
  assert.equal(result.races, 5)
  assert.deepEqual(result.rates, { decisionValidity: 1, typedTerminalAccounting: 1, serverTruthfulness: 1 })
  assert.ok(result.actions.nonSuccess.every((entry) => entry.race))
  assert.deepEqual(
    result.actions.nonSuccess.map((entry) => [entry.requestKey, entry.class, entry.evidence.outcome]),
    [
      [`${AGENT}:r6`, 'targetRace', 'digBlockGone'],
      [`${AGENT}:r7`, 'targetRace', 'collectItemChanged'],
      [`${AGENT}:r8`, 'concurrent', 'controlNotReady'],
      [`${AGENT}:r9`, 'superseded', 'placeCancelled'],
      [`${AGENT}:r10`, 'death', 'craftBodyDied']
    ]
  )
})

test('each defect the gate refuses is its own class with the record that ended it', () => {
  const cases = [
    ['wrongKindRef', action('w', 'collect', applied('collectItemUnnamed'))],
    ['noOpTarget', action('n1', 'moveTo', applied('moveOwnSpot'))],
    // An accepted walk that moved nothing has no movement record at all.
    ['noOpTarget', action('n2', 'moveTo', applied('accepted'))],
    ['unknownTimeout', action('t', 'collect', applied('collectTimedOut'), checked())],
    ['unknownTimeout', action('mt', 'moveTo', applied('accepted'), { event: 'moved', outcome: 'movementTimedOut' })],
    ['incompleteMovement', action('s', 'moveTo', applied('accepted'), { event: 'moved', outcome: 'movementStalled' })],
    ['falseSuccess', action('f', 'dig', applied('accepted'), checked('disputedWorldInteraction'))],
    ['protocolError', action('p', 'dig', applied('rejected'))],
    ['protocolError', action('d', 'dig', { event: 'decisionDiscarded' })],
    ['staleRef', action('st', 'dig', applied('digSenseForgotten'))],
    ['inapplicable', action('i', 'eat', applied('eatReserveFull'))],
    ['unconfirmed', action('u', 'dig', applied('accepted'))],
    ['untypedTerminal', action('x', 'dig')],
    ['unexplainedRefusal', action('e', 'dig', applied('somethingNew'))]
  ]
  for (const [expected, records] of cases) {
    const result = readiness(records)
    assert.equal(result.verdict, 'notReady', expected)
    assert.equal(result.blockers[expected], 1, `${expected}: ${JSON.stringify(result.blockers)}`)
    const [entry] = result.actions.nonSuccess
    assert.equal(entry.class, expected)
    assert.equal(entry.race, false)
  }
  for (const name of [...BLOCKER_CLASSES, ...RACE_CLASSES]) {
    assert.ok(Object.hasOwn(readiness([]).actions.byClass, name), name)
  }
})

test('the three rates are what the gate requires to be whole', () => {
  const records = [
    ...action('a', 'dig', applied('accepted'), checked()),
    ...action('b', 'dig', applied('digBlockUnnamed')),
    ...action('c', 'dig', applied('accepted')),
    ...action('d', 'dig', applied('accepted'), checked('disputedWorldInteraction')),
    record({ event: 'cognitionUnavailable', outcome: 'providerDecisionParse', requestKey: `${AGENT}:e` }),
    // Deliberately not asked, and so neither valid nor invalid.
    record({ event: 'cognitionUnavailable', outcome: 'dangerCoalesced', requestKey: `${AGENT}:f` }),
    record({ event: 'witnessDisputed', outcome: 'disputedVitals' })
  ]
  const result = readiness(records)
  // Five answers asked of the mind; the parse failure and the wrong-kind ref are invalid.
  assert.equal(result.rates.decisionValidity, 0.6)
  // One of four actions has no typed terminal.
  assert.equal(result.rates.typedTerminalAccounting, 0.75)
  // Two of the server's three checks agreed.
  assert.equal(result.rates.serverTruthfulness, Number((1 / 3).toFixed(6)))
  assert.deepEqual(result.cognitionFailures, { providerDecisionParse: 1 })
  assert.equal(result.serverDisputes, 1)
})

test('a settled-after-death request is a death race whatever its record says', () => {
  const records = action('z', 'dig', applied('digTimedOut'))
  const result = readiness(records, {
    metrics: { witnessedDeaths: { byAgent: { [AGENT]: { settledAfterDeath: [`${AGENT}:z`] } } } }
  })
  assert.equal(result.verdict, 'ready')
  assert.equal(result.actions.byClass.death, 1)
})

test('goal coherence counts the steps the body measured as progress, per goal', () => {
  const decided = (goal) => record({ event: 'decided', intent: 'idle', goal })
  const records = [
    decided({ outcome: 'goalAdopted', goalId: 'g1' }),
    decided({ outcome: 'goalContinued', presentedGoalId: 'g1', stepsWithoutProgress: 0 }),
    decided({ outcome: 'goalRevised', presentedGoalId: 'g1', stepsWithoutProgress: 0 }),
    decided({ outcome: 'goalCompleted', presentedGoalId: 'g1', stepsWithoutProgress: 1 }),
    decided({ outcome: 'goalAdopted', goalId: 'g2' }),
    decided({ outcome: 'goalContinued', presentedGoalId: 'g2', stepsWithoutProgress: 1 }),
    decided({ outcome: 'goalStalled', presentedGoalId: 'g2', stepsWithoutProgress: 3 })
  ]
  assert.deepEqual(readiness(records).goalCoherence, {
    measuredSteps: 5,
    progressedSteps: 2,
    goalsMeasured: 2,
    multiStepGoals: 1,
    completedAfterProgress: 1
  })
})

test('from gate generation 7 a body noRoute is the terrain answer and from 8 an ignored own drop is incoherent', () => {
  // r15: five collects of a stick no cell beside could reach; r16: digs beside the agent's own sticks.
  const records = [
    ...action('t', 'collect', applied('collectNoRoute')),
    ...action('o', 'dig', applied('goalOwnDropIgnored'))
  ]
  const at = (generation) => readinessMetrics({ bodyRecords: records, harnessRecords: [], metrics: {}, generation })
  assert.equal(at(6).actions.byClass.inapplicable, 1)
  assert.equal(at(6).actions.byClass.terrain, undefined)
  assert.equal(at(7).actions.byClass.terrain, 1)
  assert.equal(at(7).actions.byClass.unexplainedRefusal, 1)
  const eight = at(8)
  assert.equal(eight.version, 'loom-readiness-gate/6')
  assert.equal(eight.actions.byClass.terrain, 1)
  assert.equal(eight.actions.byClass.goalIncoherent, 1)
  assert.equal(eight.actions.nonSuccess.find((entry) => entry.class === 'terrain').race, true)
})
