'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')

const {
  deriveMetrics,
  normalizeEvidence,
  scanText
} = require('../lib/evidence.js')
const { fixedRawRecords, writeJsonLines } = require('./fixtures.js')

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
