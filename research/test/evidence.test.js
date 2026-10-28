'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')

const {
  deriveMetrics,
  normalizeEvidence,
  scanText,
  validatePerceptionAudit
} = require('../lib/evidence.js')
const {
  fixedRawRecords,
  perceptionAuditFixture,
  primaryBehaviorRawRecords,
  writeJsonLines
} = require('./fixtures.js')

function temporaryDirectory () {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'loom-evidence-test-'))
}

test('normalization keeps fleet request identity unambiguous and metrics are deterministic', () => {
  const directory = temporaryDirectory()
  const bodyFile = path.join(directory, 'body.jsonl')
  const harnessFile = path.join(directory, 'harness.jsonl')
  const outputFile = path.join(directory, 'evidence.jsonl')
  const raw = fixedRawRecords()
  writeJsonLines(bodyFile, raw.body)
  writeJsonLines(harnessFile, raw.harness)

  const normalized = normalizeEvidence({
    experimentId: '2026-10-12-scripted-tribe-crash-proof',
    bodyFile,
    harnessFile,
    outputFile,
    maxInputBytes: 1024 * 1024,
    maxOutputBytes: 1024 * 1024,
    maxRecords: 1000
  })
  const metrics = deriveMetrics(normalized.records, [
    { agentId: 'tribe-alpha' },
    { agentId: 'tribe-beta' },
    { agentId: 'tribe-gamma' }
  ])

  assert.deepEqual(
    metrics,
    deriveMetrics(structuredClone(normalized.records), [
      { agentId: 'tribe-alpha' },
      { agentId: 'tribe-beta' },
      { agentId: 'tribe-gamma' }
    ])
  )
  const firstRequests = normalized.records
    .filter((record) => record.source === 'body' && record.requestId === 'sense-1')
    .map((record) => record.requestKey)
  assert.equal(new Set(firstRequests).size, 3, 'same local request id must remain distinct across agents')
  assert.deepEqual(metrics.crashContainment.peerDecisionsAfterCrash, {
    'tribe-beta': 1,
    'tribe-gamma': 1
  })
  assert.equal(metrics.cycles.incomplete.length, 0)
  assert.deepEqual(metrics.timing, { firstTick: 15, lastTick: 41, tickSpan: 26 })
  assert.equal(metrics.behavior.byIntent.idle, 5, 'applied intents are counted once, not again at decision time')
  assert.equal(
    metrics.records.agents.find((agent) => agent.agentId === 'tribe-beta').appliedByIntent.idle,
    2
  )
  assert.equal(metrics.findings.counterevidence.includes('No agent produced a speech intent.'), true)
  assert.equal(
    metrics.findings.counterevidence.includes(
      'No movement record accompanied the co-location samples; shared spawn placement is a sufficient explanation.'
    ),
    true
  )
  assert.deepEqual(scanText(fs.readFileSync(outputFile, 'utf8')), [])
})

for (const fixture of ['empty', 'full', 'omitted']) {
  test(`the ${fixture} per-kind perception audit fixture is complete and arithmetically valid`, () => {
    const audit = perceptionAuditFixture(fixture)
    assert.equal(validatePerceptionAudit(audit), audit)
  })
}

test('incomplete and inconsistent per-kind perception audit fixtures are refused', () => {
  assert.throws(
    () => validatePerceptionAudit(perceptionAuditFixture('inconsistent')),
    /droppedItems\.jvmCutOmittedCount|JVM cut arithmetic/
  )
  const incomplete = perceptionAuditFixture('empty')
  delete incomplete.blocks
  assert.throws(
    () => validatePerceptionAudit(incomplete),
    /no complete blocks counts/
  )
  const unsaturated = perceptionAuditFixture('empty')
  unsaturated.entities = {
    rawBodyListCount: 3,
    bodyReportedOmittedCount: 1,
    jvmSelectedCount: 3,
    jvmCutOmittedCount: 0
  }
  assert.throws(
    () => validatePerceptionAudit(unsaturated),
    /body omissions require a saturated body list/
  )
  for (const kind of ['entities', 'blocks', 'droppedItems']) {
    const dropped = perceptionAuditFixture('empty')
    dropped[kind] = {
      rawBodyListCount: 1,
      bodyReportedOmittedCount: 0,
      jvmSelectedCount: 0,
      jvmCutOmittedCount: 1
    }
    assert.throws(
      () => validatePerceptionAudit(dropped),
      /dropped an offer inside the complete 48-per-kind envelope/
    )
  }
})

test('primary behavior normalization requires every perception audit and derives each kind separately', () => {
  const directory = temporaryDirectory()
  const bodyFile = path.join(directory, 'body.jsonl')
  const harnessFile = path.join(directory, 'harness.jsonl')
  const outputFile = path.join(directory, 'evidence.jsonl')
  const raw = primaryBehaviorRawRecords()
  writeJsonLines(bodyFile, raw.body)
  writeJsonLines(harnessFile, raw.harness)

  const normalized = normalizeEvidence({
    experimentId: '2026-10-13-scripted-routine-v12-agency-pilot-dry-run',
    bodyFile,
    harnessFile,
    outputFile,
    maxInputBytes: 1024 * 1024,
    maxOutputBytes: 1024 * 1024,
    maxRecords: 1000,
    requirePerceptionAudit: true
  })
  const metrics = deriveMetrics(normalized.records)
  assert.equal(metrics.perceptionAudit.complete, true)
  assert.equal(metrics.perceptionAudit.samples, 6)
  assert.deepEqual(metrics.perceptionAudit.byKind.entities, {
    samples: 6,
    rawBodyListCount: 150,
    bodyReportedOmittedCount: 9,
    jvmSelectedCount: 150,
    jvmCutOmittedCount: 0
  })
  assert.deepEqual(metrics.perceptionAudit.byKind.blocks, {
    samples: 6,
    rawBodyListCount: 180,
    bodyReportedOmittedCount: 360,
    jvmSelectedCount: 180,
    jvmCutOmittedCount: 0
  })
  assert.deepEqual(metrics.perceptionAudit.byKind.droppedItems, {
    samples: 6,
    rawBodyListCount: 150,
    bodyReportedOmittedCount: 3,
    jvmSelectedCount: 150,
    jvmCutOmittedCount: 0
  })
  assert.deepEqual(metrics.primaryBehavior.nativeGoalReviewsByAgent, {
    'tribe-alpha': 2,
    'tribe-beta': 2,
    'tribe-gamma': 2
  })

  const missing = primaryBehaviorRawRecords()
  delete missing.body.find((record) => record.event === 'perceived').perceptionAudit
  writeJsonLines(path.join(directory, 'missing-body.jsonl'), missing.body)
  assert.throws(
    () => normalizeEvidence({
      experimentId: '2026-10-13-scripted-routine-v12-agency-pilot-dry-run',
      bodyFile: path.join(directory, 'missing-body.jsonl'),
      harnessFile,
      outputFile: path.join(directory, 'missing-evidence.jsonl'),
      maxInputBytes: 1024 * 1024,
      maxOutputBytes: 1024 * 1024,
      maxRecords: 1000,
      requirePerceptionAudit: true
    }),
    /no required per-kind perception audit/
  )

  const inconsistentOmission = primaryBehaviorRawRecords()
  inconsistentOmission.body.find(
    (record) => record.event === 'perceived'
  ).perception.surroundingsOmitted += 1
  writeJsonLines(
    path.join(directory, 'inconsistent-omission-body.jsonl'),
    inconsistentOmission.body
  )
  assert.throws(
    () => normalizeEvidence({
      experimentId: '2026-10-13-scripted-routine-v12-agency-pilot-dry-run',
      bodyFile: path.join(directory, 'inconsistent-omission-body.jsonl'),
      harnessFile,
      outputFile: path.join(directory, 'inconsistent-omission-evidence.jsonl'),
      maxInputBytes: 1024 * 1024,
      maxOutputBytes: 1024 * 1024,
      maxRecords: 1000,
      requirePerceptionAudit: true
    }),
    /does not match the aggregate omission count/
  )
})

test('server witness disputes remain explicit counterevidence and limitations', () => {
  const directory = temporaryDirectory()
  const bodyFile = path.join(directory, 'body.jsonl')
  const harnessFile = path.join(directory, 'harness.jsonl')
  const outputFile = path.join(directory, 'evidence.jsonl')
  const raw = fixedRawRecords()
  raw.body.push({
    tick: 42,
    agentId: 'tribe-beta',
    event: 'witnessDisputed',
    detail: 'saturation disagreed',
    outcome: 'disputedVitals'
  })
  writeJsonLines(bodyFile, raw.body)
  writeJsonLines(harnessFile, raw.harness)
  const normalized = normalizeEvidence({
    experimentId: '2026-10-12-scripted-tribe-crash-proof',
    bodyFile,
    harnessFile,
    outputFile,
    maxInputBytes: 1024 * 1024,
    maxOutputBytes: 1024 * 1024,
    maxRecords: 1000
  })
  const metrics = deriveMetrics(normalized.records)
  assert.equal(metrics.behavior.witnessDisputeRecords, 1)
  assert.deepEqual(metrics.behavior.witnessDisputesByOutcome, { disputedVitals: 1 })
  assert.ok(metrics.findings.counterevidence.some((line) =>
    line.includes('1 server witness dispute record(s)')
  ))
  assert.ok(metrics.findings.limitations.some((line) =>
    line.includes('limit any claim')
  ))
})

test('unsafe strings are redacted before public evidence is written', () => {
  const directory = temporaryDirectory()
  const bodyFile = path.join(directory, 'body.jsonl')
  const harnessFile = path.join(directory, 'harness.jsonl')
  const outputFile = path.join(directory, 'evidence.jsonl')
  const raw = fixedRawRecords()
  raw.body[1].detail = 'provider echoed sk-abcdefghijklmnopqrstuvwxyz and /Users/operator/private.env'
  raw.body[2].decision.reasoning = 'request chatcmpl_1234567890 repeated a Bearer abcdefghijklmnop'
  raw.harness[0].apiKey = 'opaque-device-credential'
  raw.harness[0].processId = 43210
  raw.harness[0].note = 'temporary /tmp/loom-private and ghp_abcdefghijklmnopqrstuvwxyz'
  raw.harness.find((record) => record.event === 'cognitionForwarded').failure = {
    schemaVersion: 'loom-cognition-failure/1',
    backend: 'openai',
    model: 'small-model',
    httpStatus: 400,
    providerType: 'invalid_request_error',
    providerCode: 'private_provider_code',
    responseShape: 'apiErrorEnvelope',
    mapping: 'providerRequest'
  }
  writeJsonLines(bodyFile, raw.body)
  writeJsonLines(harnessFile, raw.harness)

  const normalized = normalizeEvidence({
    experimentId: '2026-10-12-scripted-tribe-crash-proof',
    bodyFile,
    harnessFile,
    outputFile,
    maxInputBytes: 1024 * 1024,
    maxOutputBytes: 1024 * 1024,
    maxRecords: 1000
  })

  const text = fs.readFileSync(outputFile, 'utf8')
  assert.ok(normalized.redactions >= 8)
  assert.match(text, /\[redacted\]/)
  assert.deepEqual(scanText(text), [])
  assert.doesNotMatch(text, /operator|chatcmpl|Bearer|sk-|opaque-device|43210|ghp_|\/tmp\//)
  assert.doesNotMatch(text, /private_provider_code|loom-cognition-failure/)
})

test('provider-reported usage deterministically derives exact token and spend totals', () => {
  const directory = temporaryDirectory()
  const bodyFile = path.join(directory, 'body.jsonl')
  const harnessFile = path.join(directory, 'harness.jsonl')
  const outputFile = path.join(directory, 'evidence.jsonl')
  const raw = fixedRawRecords()
  raw.harness.push({
    observedAt: '2026-10-12T00:00:20.000Z',
    event: 'providerUsage',
    agentId: 'tribe-beta',
    requestId: 'sense-2',
    model: 'small-model',
    promptTokens: 100,
    cachedPromptTokens: 20,
    completionTokens: 10,
    totalTokens: 110,
    costNanoUsd: 13_200
  })
  writeJsonLines(bodyFile, raw.body)
  writeJsonLines(harnessFile, raw.harness)
  const normalized = normalizeEvidence({
    experimentId: '2026-10-12-scripted-tribe-crash-proof',
    bodyFile,
    harnessFile,
    outputFile,
    maxInputBytes: 1024 * 1024,
    maxOutputBytes: 1024 * 1024,
    maxRecords: 1000
  })
  const metrics = deriveMetrics(normalized.records)
  assert.equal(metrics.cognition.providerUsageCalls, 1)
  assert.equal(metrics.cognition.externalSpendNanoUsd, 13_200)
  assert.equal(metrics.cognition.externalSpendUsd, 0.0000132)
  assert.equal(metrics.cognition.usageByModel['small-model'].promptTokens, 100)
})

test('malformed partial oversized and excessive evidence is refused', () => {
  const directory = temporaryDirectory()
  const bodyFile = path.join(directory, 'body.jsonl')
  const harnessFile = path.join(directory, 'harness.jsonl')
  const outputFile = path.join(directory, 'evidence.jsonl')
  const raw = fixedRawRecords()
  writeJsonLines(harnessFile, raw.harness)

  fs.writeFileSync(bodyFile, '{"tick":1')
  assert.throws(() => normalizeEvidence({
    experimentId: '2026-10-12-scripted-tribe-crash-proof',
    bodyFile,
    harnessFile,
    outputFile,
    maxInputBytes: 1024,
    maxOutputBytes: 1024,
    maxRecords: 100
  }), /partial JSONL/)

  writeJsonLines(bodyFile, [{ tick: 1, agentId: 'a', event: 'x', detail: 'x'.repeat(5000) }])
  assert.throws(() => normalizeEvidence({
    experimentId: '2026-10-12-scripted-tribe-crash-proof',
    bodyFile,
    harnessFile,
    outputFile,
    maxInputBytes: 100,
    maxOutputBytes: 1024,
    maxRecords: 100
  }), /evidence input bound/)

  writeJsonLines(bodyFile, raw.body)
  assert.throws(() => normalizeEvidence({
    experimentId: '2026-10-12-scripted-tribe-crash-proof',
    bodyFile,
    harnessFile,
    outputFile,
    maxInputBytes: 1024 * 1024,
    maxOutputBytes: 1024 * 1024,
    maxRecords: 2
  }), /record bound/)
})

function normalizedWithGoals (decorate) {
  const directory = temporaryDirectory()
  const bodyFile = path.join(directory, 'body.jsonl')
  const harnessFile = path.join(directory, 'harness.jsonl')
  const raw = fixedRawRecords()
  for (const record of raw.body) decorate(record)
  writeJsonLines(bodyFile, raw.body)
  writeJsonLines(harnessFile, raw.harness)
  return normalizeEvidence({
    experimentId: '2026-10-12-scripted-tribe-crash-proof',
    bodyFile,
    harnessFile,
    outputFile: path.join(directory, 'evidence.jsonl'),
    maxInputBytes: 1024 * 1024,
    maxOutputBytes: 1024 * 1024,
    maxRecords: 1000
  })
}

test('goal continuity is derived from typed goal evidence rather than prose', () => {
  const goals = {
    'tribe-alpha:sense-1': { outcome: 'goalStale', disposition: 'adopt' },
    'tribe-beta:sense-1': { outcome: 'goalAdopted', disposition: 'adopt', goalId: 'sense-1', revision: 0 },
    'tribe-beta:sense-2': {
      outcome: 'goalContinued',
      disposition: 'continue',
      presentedGoalId: 'sense-1',
      presentedRevision: 0,
      goalId: 'sense-1',
      revision: 0
    },
    'tribe-gamma:sense-1': { outcome: 'goalAdopted', disposition: 'adopt', goalId: 'sense-1', revision: 0 },
    'tribe-gamma:sense-2': {
      outcome: 'goalUnaddressed',
      presentedGoalId: 'sense-1',
      presentedRevision: 0,
      goalId: 'sense-1',
      revision: 0
    }
  }
  const normalized = normalizedWithGoals((record) => {
    const goal = goals[`${record.agentId}:${record.requestId}`]
    if (record.event === 'decided' && goal !== undefined) record.goal = goal
  })
  const continued = normalized.records.find((record) => record.requestKey === 'tribe-beta:sense-2' && record.event === 'decided')
  assert.deepEqual(continued.goal, goals['tribe-beta:sense-2'])

  const metrics = deriveMetrics(normalized.records)
  assert.deepEqual(metrics.goalContinuity.agentsWithContinuity, ['tribe-beta'])
  assert.equal(metrics.goalContinuity.byOutcome.goalAdopted, 2)
  const gamma = metrics.goalContinuity.agents.find((agent) => agent.agentId === 'tribe-gamma')
  assert.equal(gamma.continuingReviews, 0, 'holding a goal without choosing to continue it is not continuity')

  // A continue recorded against a goal other than the one carried proves nothing.
  const elsewhere = normalizedWithGoals((record) => {
    if (record.event === 'decided' && record.agentId === 'tribe-beta' && record.requestId === 'sense-2') {
      record.goal = { ...goals['tribe-beta:sense-2'], goalId: 'sense-9' }
    }
  })
  assert.deepEqual(deriveMetrics(elsewhere.records).goalContinuity.agentsWithContinuity, [])
})

test('every accepted end states its reason, counted from routine-v36, and an unknown one is refused', () => {
  const goals = {
    'tribe-gamma:sense-1': { outcome: 'goalAdopted', disposition: 'adopt', goalId: 'sense-1', revision: 0 },
    'tribe-gamma:sense-2': {
      outcome: 'goalCompletedAdopted',
      disposition: 'replace',
      presentedGoalId: 'sense-1',
      presentedRevision: 0,
      goalId: 'sense-2',
      revision: 0,
      reason: 'completed'
    },
    'tribe-beta:sense-1': { outcome: 'goalAdopted', disposition: 'adopt', goalId: 'sense-1', revision: 0 },
    'tribe-beta:sense-2': { outcome: 'goalAbandoned', disposition: 'abandon', presentedGoalId: 'sense-1', presentedRevision: 0, reason: 'impossible' },
    // A refused end states a reason too, and is no end.
    'tribe-alpha:sense-1': { outcome: 'goalReasonUnsupported', disposition: 'abandon', reason: 'stale' }
  }
  const normalized = normalizedWithGoals((record) => {
    const goal = goals[`${record.agentId}:${record.requestId}`]
    if (record.event === 'decided' && goal !== undefined) record.goal = goal
  })
  const metrics = deriveMetrics(normalized.records)
  assert.deepEqual(metrics.goalContinuity.endReasons, { completed: 1, impossible: 1 })
  assert.equal(metrics.goalContinuity.byOutcome.goalReasonUnsupported, 1)
  // Evidence from before routine-v36 states none, and derives no such measure.
  const before = normalizedWithGoals((record) => {
    if (record.event === 'decided' && record.agentId === 'tribe-beta' && record.requestId === 'sense-1') {
      record.goal = goals['tribe-beta:sense-1']
    }
  })
  assert.equal(deriveMetrics(before.records).goalContinuity.endReasons, undefined)
  assert.throws(() => normalizedWithGoals((record) => {
    if (record.event === 'decided' && record.agentId === 'tribe-beta' && record.requestId === 'sense-2') {
      record.goal = { ...goals['tribe-beta:sense-2'], reason: 'bored' }
    }
  }), /unknown goal end reason/)
})

test('a shelter goal\'s typed cover is kept and a malformed one refused at normalization', () => {
  const step = {
    outcome: 'goalContinued',
    disposition: 'continue',
    presentedGoalId: 'sense-1',
    presentedRevision: 0,
    goalId: 'sense-1',
    revision: 0,
    presentedShelter: true,
    stepEvidence: 'covered',
    cover: { closed: 8, closable: 1, sealed: false }
  }
  const normalized = normalizedWithGoals((record) => {
    if (record.event === 'decided' && record.agentId === 'tribe-beta' && record.requestId === 'sense-2') record.goal = step
  })
  const kept = normalized.records.find((record) => record.requestKey === 'tribe-beta:sense-2' && record.event === 'decided')
  assert.deepEqual(kept.goal.cover, step.cover)
  for (const cover of [null, [], { closed: 10, closable: 0, sealed: false }, { closed: 8, closable: -1, sealed: false },
    { closed: 8, closable: 1, sealed: 'no' }]) {
    assert.throws(() => normalizedWithGoals((record) => {
      if (record.event === 'decided' && record.agentId === 'tribe-beta') record.goal = { ...step, cover }
    }), /cover/)
  }
})

test('evidence without goal records derives no goal continuity section', () => {
  const metrics = deriveMetrics(normalizedWithGoals(() => {}).records)
  assert.equal(metrics.goalContinuity, undefined)
})

test('malformed goal evidence is refused at normalization', () => {
  const cases = [
    [{ event: 'decided' }, { outcome: 'goalPursued' }],
    [{ event: 'perceived' }, { outcome: 'goalAdopted' }],
    [{ event: 'decided' }, { outcome: 'goalAdopted', goalId: 'sense-1' }],
    [{ event: 'decided' }, { outcome: 'goalAdopted', goalId: 'x'.repeat(65), revision: 0 }],
    [{ event: 'decided' }, { outcome: 'goalRevised', disposition: 'pursue' }]
  ]
  for (const [{ event }, goal] of cases) {
    assert.throws(() => normalizedWithGoals((record) => {
      if (record.event === event && record.agentId === 'tribe-beta') record.goal = goal
    }), /goal/)
  }
})

test('speech metrics count only server-attributed lines, disputes, and nearby hearing', () => {
  const directory = temporaryDirectory()
  const bodyFile = path.join(directory, 'body.jsonl')
  const harnessFile = path.join(directory, 'harness.jsonl')
  const outputFile = path.join(directory, 'evidence.jsonl')
  const raw = fixedRawRecords()
  const before = deriveMetrics(normalizeEvidence({
    experimentId: '2026-10-12-scripted-tribe-crash-proof',
    bodyFile: (writeJsonLines(bodyFile, raw.body), bodyFile),
    harnessFile: (writeJsonLines(harnessFile, raw.harness), harnessFile),
    outputFile,
    maxInputBytes: 1024 * 1024,
    maxOutputBytes: 1024 * 1024,
    maxRecords: 1000
  }).records)
  assert.equal(before.speech, undefined)
  raw.body.push(
    { tick: 50, agentId: 'tribe-alpha', event: 'worldInteraction', requestId: 'req-say-1', intent: 'say', outcome: 'serverEffectConfirmed', detail: 'said 5 chars; control say-1; server attributed' },
    { tick: 50, agentId: 'tribe-beta', event: 'perceived', requestId: 'req-heard-1', trigger: 'spoken', detail: 'heard' },
    { tick: 60, agentId: 'tribe-beta', event: 'witnessDisputed', requestId: 'req-say-2', intent: 'say', outcome: 'unobservedSpeech', detail: 'claimed 3 chars; no server chat attributed' }
  )
  writeJsonLines(bodyFile, raw.body)
  const metrics = deriveMetrics(normalizeEvidence({
    experimentId: '2026-10-12-scripted-tribe-crash-proof',
    bodyFile,
    harnessFile,
    outputFile: path.join(directory, 'evidence-2.jsonl'),
    maxInputBytes: 1024 * 1024,
    maxOutputBytes: 1024 * 1024,
    maxRecords: 1000
  }).records)
  assert.equal(metrics.speech.confirmed, 1)
  assert.deepEqual(metrics.speech.confirmedByAgent, { 'tribe-alpha': 1 })
  assert.equal(metrics.speech.disputed, 1)
  assert.deepEqual(metrics.speech.disputedByOutcome, { unobservedSpeech: 1 })
  assert.equal(metrics.speech.heard, 1)
  assert.deepEqual(metrics.speech.heardByAgent, { 'tribe-beta': 1 })
})
