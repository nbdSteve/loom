'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')

const {
  DIAGNOSTIC_STAGES,
  FAILURE_DIAGNOSTIC_VERSION,
  FALLBACK_DIAGNOSTIC_VERSION,
  MAX_FALLBACK_DIAGNOSTIC_BYTES,
  boundedJsonLines,
  failureCode,
  writeFailureDiagnostic
} = require('../lib/failure-diagnostic.js')
const { budgetRefusalFailure } = require('../lib/harness.js')
const { RunFailure } = require('../lib/lifecycle.js')
const { scanText } = require('../lib/evidence.js')
const { openAiGoalContinuityConfig, openAiProofConfig, writeJsonLines } = require('./fixtures.js')

const SOURCE = {
  commit: '1'.repeat(40),
  repository: 'https://example.test/loom.git'
}

function rawFiles (directory) {
  return {
    bodyFile: path.join(directory, 'body.jsonl'),
    harnessFile: path.join(directory, 'harness.jsonl'),
    providerUsageFile: path.join(directory, 'usage.jsonl')
  }
}

function cognitionFailure ({
  model,
  httpStatus,
  providerType,
  providerCode,
  responseShape,
  mapping
}) {
  return {
    schemaVersion: 'loom-cognition-failure/1',
    backend: 'openai',
    model,
    httpStatus,
    providerType,
    providerCode,
    responseShape,
    mapping
  }
}

test('a failed run retains only bounded aggregate operator diagnostics', () => {
  const repositoryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-failed-run-'))
  const diagnosticRoot = path.join(repositoryRoot, '.scratch', 'tribe-failures')
  const files = rawFiles(repositoryRoot)
  const config = openAiProofConfig()
  const secret = 'sk-this-is-a-fake-secret-value-1234567890'
  const requestId = 'resp_provider-private-request-12345678'
  const prompt = 'secret raw prompt and reasoning must not survive'
  const localPath = '/Users/operator/private/loom'
  writeJsonLines(files.bodyFile, [
    {
      tick: 10,
      agentId: 'tribe-alpha',
      event: 'bodyDisconnected',
      requestId,
      detail: `${secret} ${prompt} ${localPath}`
    },
    {
      tick: 11,
      agentId: 'tribe-beta',
      event: 'decided',
      requestId,
      decision: { reasoning: prompt }
    }
  ])
  writeJsonLines(files.harnessFile, [
    {
      observedAt: '2026-10-12T00:00:00.000Z',
      event: 'controlledBodyCrash',
      agentId: 'tribe-alpha',
      afterTick: 9,
      processId: 12345,
      requestId
    },
    {
      observedAt: '2026-10-12T00:00:01.000Z',
      event: 'postCrashCallReserveReleased',
      agentId: 'tribe-alpha'
    },
    {
      observedAt: '2026-10-12T00:00:02.000Z',
      event: 'cognitionForwarded',
      agentId: 'tribe-beta',
      requestId,
      outcome: 'decision',
      prompt
    },
    {
      observedAt: '2026-10-12T00:00:03.000Z',
      event: 'runFailed',
      stopReason: 'startupFailed',
      authorization: `Bearer ${secret}`
    },
    {
      observedAt: '2026-10-12T00:00:04.000Z',
      event: 'cleanupVerified'
    }
  ])
  writeJsonLines(files.providerUsageFile, [{
    schemaVersion: 'loom-provider-usage/1',
    agentId: 'tribe-beta',
    requestId,
    model: 'small-model',
    promptTokens: 100,
    cachedPromptTokens: 20,
    completionTokens: 10,
    totalTokens: 110
  }])

  try {
    const retained = writeFailureDiagnostic({
      repositoryRoot,
      diagnosticRoot,
      config,
      source: SOURCE,
      failure: new RunFailure(
        'startupFailed',
        'two peers did not decide after the controlled crash'
      ),
      startedAt: '2026-10-12T00:00:00.000Z',
      endedAt: '2026-10-12T00:00:05.000Z',
      cleanupVerified: true,
      ...files,
      proxySnapshot: {
        forwardedCalls: 5,
        outstandingCalls: 0,
        callsByAgent: {
          'tribe-alpha': 2,
          'tribe-beta': 2,
          'tribe-gamma': 1
        },
        postCrashReserve: {
          released: true,
          remainingByPeer: {
            'tribe-beta': 0,
            'tribe-gamma': 1
          }
        }
      },
      clock: () => new Date('2026-10-12T00:00:06.000Z')
    })
    const text = fs.readFileSync(retained.file, 'utf8')
    const diagnostic = JSON.parse(text)
    assert.equal(diagnostic.schemaVersion, FAILURE_DIAGNOSTIC_VERSION)
    assert.equal(diagnostic.failure.code, 'postCrashPeerDecisionRequirement')
    assert.equal(diagnostic.crashContainment.crashObserved, true)
    assert.equal(diagnostic.crashContainment.reserveReleased, true)
    assert.equal(diagnostic.crashContainment.peerDecisionsAfterCrash['tribe-beta'], 1)
    assert.equal(diagnostic.crashContainment.peerDecisionsAfterCrash['tribe-gamma'], 0)
    assert.equal(diagnostic.cognition.providerUsage.byModel['small-model'].calls, 1)
    assert.equal(diagnostic.cognition.providerUsage.externalSpendNanoUsd, 13_200)
    assert.equal(diagnostic.cleanup.verifiedByListenerProbe, true)
    assert.equal(diagnostic.privacy.publicResearchEvidence, false)
    assert.deepEqual(scanText(text), [])
    for (const value of [secret, requestId, prompt, localPath]) {
      assert.doesNotMatch(text, new RegExp(value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
    }
    assert.ok(retained.bytes <= 64 * 1024)
    assert.equal(
      retained.label,
      path.join('.scratch', 'tribe-failures', `${config.experimentId}.json`)
    )
  } finally {
    fs.rmSync(repositoryRoot, { recursive: true, force: true })
  }
})

test('classified cognition failures aggregate exactly while legacy refusals stay unknown', () => {
  const repositoryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-failure-classes-'))
  const files = rawFiles(repositoryRoot)
  const config = openAiProofConfig()
  const requestRejected = cognitionFailure({
    model: 'small-model',
    httpStatus: 400,
    providerType: 'invalid_request_error',
    providerCode: 'invalid_json_schema',
    responseShape: 'apiErrorEnvelope',
    mapping: 'providerRequest'
  })
  const safetyRefusal = cognitionFailure({
    model: 'large-model',
    httpStatus: 200,
    providerType: null,
    providerCode: null,
    responseShape: 'chatCompletion',
    mapping: 'providerSafetyRefusal'
  })
  writeJsonLines(files.bodyFile, [])
  writeJsonLines(files.providerUsageFile, [])
  writeJsonLines(files.harnessFile, [
    {
      event: 'cognitionForwarded',
      agentId: 'tribe-alpha',
      requestId: 'private-request-a',
      importance: 'ROUTINE',
      outcome: 'cognitionFailure',
      failure: requestRejected
    },
    {
      event: 'cognitionForwarded',
      agentId: 'tribe-beta',
      requestId: 'private-request-b',
      importance: 'NOTABLE',
      outcome: 'cognitionFailure',
      failure: requestRejected
    },
    {
      event: 'cognitionForwarded',
      agentId: 'tribe-gamma',
      requestId: 'private-request-c',
      importance: 'PIVOTAL',
      outcome: 'cognitionFailure',
      failure: safetyRefusal
    },
    {
      event: 'cognitionForwarded',
      agentId: 'tribe-gamma',
      requestId: 'legacy-private-request',
      importance: 'ROUTINE',
      outcome: 'upstreamRefusal'
    }
  ])

  try {
    const retained = writeFailureDiagnostic({
      repositoryRoot,
      diagnosticRoot: path.join(repositoryRoot, 'diagnostics'),
      config,
      source: SOURCE,
      failure: new RunFailure('cognitionFailed', 'classified test failure'),
      cleanupVerified: true,
      ...files,
      clock: () => new Date('2026-10-12T00:00:00.000Z')
    })
    const summary = retained.diagnostic.cognition.harness.failureClassifications
    assert.equal(summary.total, 4)
    assert.equal(summary.classified, 3)
    assert.equal(summary.unclassified, 1)
    assert.deepEqual(summary.byClassification, [
      {
        ...safetyRefusal,
        count: 1
      },
      {
        ...requestRejected,
        count: 2
      }
    ])
    const text = fs.readFileSync(retained.file, 'utf8')
    assert.doesNotMatch(text, /private-request/)
    assert.deepEqual(scanText(text), [])
  } finally {
    fs.rmSync(repositoryRoot, { recursive: true, force: true })
  }
})

test('partial and oversized raw journals are summarized without becoming evidence', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-diagnostic-input-'))
  const file = path.join(directory, 'partial.jsonl')
  fs.writeFileSync(file, '{"event":"decided"}\n{"credential":"secret')
  try {
    const input = boundedJsonLines(file, { maxBytes: 25, maxRecords: 10 })
    assert.equal(input.records.length, 1)
    assert.equal(input.metadata.validRecords, 1)
    assert.equal(input.metadata.truncatedAtByteBound, true)
    assert.equal(input.metadata.partialTailIgnored, true)
  } finally {
    fs.rmSync(directory, { recursive: true, force: true })
  }
})

test('provider authentication failures have a stable provider-safe code', () => {
  assert.equal(
    failureCode(new RunFailure(
      'cognitionFailed',
      "the model provider rejected Loom's credential (HTTP 401); check OPENAI_API_KEY"
    )),
    'providerAuthenticationRejected'
  )
})

const SECRET = 'sk-this-is-a-fake-secret-value-1234567890'
const PROVIDER_ID = 'resp_provider-private-request-12345678'
const LOCAL_PATH = '/Users/operator/private/loom'

// The primary-behavior shape of the lost run: two forwarded calls with usage, then one
// native event call refused at the per-agent ceiling.
function refusedPrimaryRun (directory) {
  const files = rawFiles(directory)
  writeJsonLines(files.bodyFile, [
    { tick: 5, agentId: 'tribe-beta', event: 'perceived', requestId: PROVIDER_ID, detail: LOCAL_PATH }
  ])
  writeJsonLines(files.harnessFile, [
    {
      event: 'cognitionForwarded',
      agentId: 'tribe-beta',
      requestId: PROVIDER_ID,
      trigger: 'goalReview',
      importance: 'ROUTINE',
      origin: 'nativeGoalReviewSchedule',
      outcome: 'decision'
    },
    {
      event: 'cognitionRefused',
      agentId: 'tribe-beta',
      requestId: 'loom-private-request',
      reason: 'perAgentCallCeiling',
      bound: 'perAgentCall',
      unit: 'calls',
      ceiling: 4,
      observed: 4,
      forwarded: false,
      trigger: 'threatSighted',
      importance: 'NOTABLE',
      origin: 'nativeEventDriver',
      prompt: `${SECRET} raw prompt`
    }
  ])
  writeJsonLines(files.providerUsageFile, [
    {
      schemaVersion: 'loom-provider-usage/1',
      agentId: 'tribe-beta',
      requestId: PROVIDER_ID,
      model: 'gpt-5.4-nano',
      promptTokens: 1000,
      cachedPromptTokens: 0,
      completionTokens: 100,
      totalTokens: 1100
    },
    {
      schemaVersion: 'loom-provider-usage/1',
      agentId: 'tribe-alpha',
      requestId: 'another',
      model: 'gpt-5.4',
      promptTokens: 2000,
      cachedPromptTokens: 1000,
      completionTokens: 10,
      totalTokens: 2010
    }
  ])
  return files
}

// nano: 1000*200 + 100*1250 = 325000; full: 1000*2500 + 1000*250 + 10*15000 = 2900000
const EXPECTED_SPEND_NANO_USD = 3_225_000

const PROXY_SNAPSHOT = {
  forwardedCalls: 11,
  outstandingCalls: 0,
  refusedByBound: { perAgentCall: 1 },
  callsByAgent: { 'tribe-alpha': 4, 'tribe-beta': 4, 'tribe-gamma': 3 },
  postCrashReserve: null
}

function retain (directory, overrides = {}) {
  const files = refusedPrimaryRun(directory)
  return writeFailureDiagnostic({
    repositoryRoot: directory,
    diagnosticRoot: path.join(directory, '.scratch', 'tribe-failures'),
    config: openAiGoalContinuityConfig(),
    source: SOURCE,
    failure: budgetRefusalFailure({
      agentId: 'tribe-beta',
      origin: 'nativeEventDriver',
      bound: 'perAgentCall',
      unit: 'calls',
      ceiling: 4,
      observed: 4
    }),
    startedAt: '2026-10-14T00:00:00.000Z',
    endedAt: '2026-10-14T00:01:00.000Z',
    cleanupVerified: true,
    ...files,
    proxySnapshot: PROXY_SNAPSHOT,
    clock: () => new Date('2026-10-14T00:01:01.000Z'),
    ...overrides
  })
}

function assertPrivate (file) {
  const text = fs.readFileSync(file, 'utf8')
  assert.deepEqual(scanText(text), [])
  for (const value of [SECRET, PROVIDER_ID, LOCAL_PATH, 'loom-private-request', 'raw prompt']) {
    assert.equal(text.includes(value), false, `${value} leaked`)
  }
  assert.equal(fs.statSync(file).mode & 0o777, 0o600)
  assert.deepEqual(
    fs.readdirSync(path.dirname(file)).filter((name) => name.endsWith('.partial')),
    []
  )
  return text
}

test('a primary-behavior budget refusal retains the exact typed refusal and usage aggregates', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-primary-diagnostic-'))
  try {
    const retained = retain(directory)
    assert.equal(retained.fallback, false)
    assertPrivate(retained.file)
    const diagnostic = retained.diagnostic
    assert.equal(diagnostic.schemaVersion, FAILURE_DIAGNOSTIC_VERSION)
    assert.deepEqual(diagnostic.failure, { code: 'cognitionBudgetRefused', stopReason: 'cognitionFailed' })
    assert.equal(diagnostic.configuredConditions.mode, 'routine-v28-explored-goal')
    assert.equal(diagnostic.configuredConditions.controlledCrash, null)
    assert.equal(diagnostic.crashContainment, null)
    assert.deepEqual(diagnostic.cognition.harness.refusals, [{
      agentId: 'tribe-beta',
      bound: 'perAgentCall',
      ceiling: 4,
      forwarded: false,
      importance: 'NOTABLE',
      observed: 4,
      origin: 'nativeEventDriver',
      trigger: 'threatSighted',
      unit: 'calls'
    }])
    assert.equal(diagnostic.cognition.proxy.postCrashReserve, null)
    assert.deepEqual(diagnostic.cognition.proxy.refusedByBound, { perAgentCall: 1 })
    const usage = diagnostic.cognition.providerUsage
    assert.equal(usage.externalSpendNanoUsd, EXPECTED_SPEND_NANO_USD)
    assert.equal(usage.byModel['gpt-5.4-nano'].totalTokens, 1100)
    assert.equal(usage.byModel['gpt-5.4'].calls, 1)
  } finally {
    fs.rmSync(directory, { recursive: true, force: true })
  }
})

const STAGE_FAULTS = {
  inputRead: { stages: { readInput: () => { throw new Error(LOCAL_PATH) } } },
  summary: { stages: { summarize: () => { throw new TypeError('no proof') } } },
  serialize: { stages: { serialize: () => undefined } },
  sanitize: { stages: { scan: () => [{ pattern: 'openaiKey', count: 1 }] } },
  sizeCheck: { maxBytes: 100 },
  write: { stages: { write: () => { throw new Error('ENOSPC') } } }
}

test('every ordinary diagnostic stage failure falls back to the minimal diagnostic', () => {
  assert.deepEqual(Object.keys(STAGE_FAULTS), DIAGNOSTIC_STAGES)
  for (const [stage, overrides] of Object.entries(STAGE_FAULTS)) {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), `loom-fallback-${stage}-`))
    try {
      const retained = retain(directory, overrides)
      assert.equal(retained.fallback, true, stage)
      assert.equal(retained.failedStage, stage)
      assert.equal(path.basename(retained.file), `${openAiGoalContinuityConfig().experimentId}.fallback.json`)
      assert.ok(retained.bytes <= MAX_FALLBACK_DIAGNOSTIC_BYTES)
      const text = assertPrivate(retained.file)
      const fallback = JSON.parse(text)
      assert.equal(fallback.schemaVersion, FALLBACK_DIAGNOSTIC_VERSION)
      assert.equal(fallback.failedStage, stage)
      assert.deepEqual(fallback.failure, { code: 'cognitionBudgetRefused', stopReason: 'cognitionFailed' })
      assert.equal(fallback.mode, 'routine-v28-explored-goal')
      assert.equal(fallback.proxy.forwardedCalls, 11)
      assert.deepEqual(fallback.proxy.refusedByBound, { perAgentCall: 1 })
      assert.equal(fallback.providerUsage.status, 'aggregated')
      assert.equal(fallback.providerUsage.calls, 2)
      assert.equal(fallback.providerUsage.externalSpendNanoUsd, EXPECTED_SPEND_NANO_USD)
      assert.equal(fallback.providerUsage.byModel['gpt-5.4'].cachedPromptTokens, 1000)
    } finally {
      fs.rmSync(directory, { recursive: true, force: true })
    }
  }
})

test('the fallback drops unsafe identifiers and fails closed past its byte bound', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-fallback-unsafe-'))
  try {
    const config = openAiGoalContinuityConfig()
    config.experimentId = `2026-10-14-${SECRET}`
    config.agents = ['tribe-alpha', LOCAL_PATH, PROVIDER_ID]
    const retained = retain(directory, {
      config,
      source: { commit: PROVIDER_ID },
      stages: { summarize: () => { throw new Error('boom') } }
    })
    const fallback = JSON.parse(assertPrivate(retained.file))
    assert.equal(fallback.experimentId, null)
    assert.equal(fallback.sourceCommit, null)
    assert.deepEqual(Object.keys(fallback.proxy.callsByAgent), ['tribe-alpha'])
    assert.match(path.basename(retained.file), /^unidentified-[0-9a-f]{12}\.fallback\.json$/)

    assert.throws(
      () => retain(fs.mkdtempSync(path.join(directory, 'tight-')), {
        maxBytes: 100,
        fallbackMaxBytes: 100
      }),
      /fallback diagnostic exceeded its 100 byte bound/
    )
  } finally {
    fs.rmSync(directory, { recursive: true, force: true })
  }
})

test('an existing diagnostic is never overwritten, and a later failure of the same id is kept beside it', () => {
  // A rehearsal id is reused by every rehearsal: the second v23 rehearsal failure lost its whole
  // diagnostic to the first one's file and kept only a fallback.
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-no-overwrite-'))
  try {
    const first = retain(directory)
    const original = fs.readFileSync(first.file, 'utf8')
    const second = retain(directory)
    assert.equal(second.fallback, false)
    assert.notEqual(second.file, first.file)
    assert.match(path.basename(second.file), /\.2\.json$/)
    assert.equal(fs.readFileSync(first.file, 'utf8'), original)
    const third = retain(directory)
    assert.match(path.basename(third.file), /\.3\.json$/)
    assert.equal(fs.readFileSync(first.file, 'utf8'), original)
    assert.equal(fs.readFileSync(second.file, 'utf8'), fs.readFileSync(third.file, 'utf8'))
  } finally {
    fs.rmSync(directory, { recursive: true, force: true })
  }
})

test('a budget refusal failure states the bound in its message and a closed code', () => {
  const failure = budgetRefusalFailure({
    agentId: 'tribe-gamma',
    origin: 'nativeEventDriver',
    bound: 'globalOutstanding',
    unit: 'calls',
    ceiling: 3,
    observed: 3
  })
  assert.equal(failure.stopReason, 'cognitionFailed')
  assert.equal(failureCode(failure), 'cognitionBudgetRefused')
  assert.match(failure.message, /tribe-gamma \(nativeEventDriver\).*globalOutstanding bound at 3 of 3 calls/)
  assert.equal(
    failureCode(new RunFailure('cognitionFailed', 'x', 'not-a-closed-code')),
    'cognitionProcessOrProviderFailure'
  )
})

test('every configuration mode and terminal fault enters the diagnostic path with its usage', () => {
  const { RunFailure: Failure } = require('../lib/lifecycle.js')
  const { configNamed } = require('./fixtures.js')
  const configs = fs.readdirSync(path.join(__dirname, '..', 'config'))
    .filter((name) => name.endsWith('.json'))
    .map(configNamed)
  assert.ok(configs.some((config) => config.mode === 'routine-v12-primary-behavior'))
  assert.ok(configs.some((config) => config.mode === 'routine-v28-explored-goal'))
  const faults = [
    [new Failure('cognitionFailed', 'drain', 'cognitionDrainTimedOut'), 'cognitionDrainTimedOut'],
    [new Failure('cognitionFailed', 'auth', 'providerAuthenticationRejected'), 'providerAuthenticationRejected'],
    [new Failure('cognitionFailed', 'upstream', 'cognitionUpstreamUnavailable'), 'cognitionUpstreamUnavailable'],
    [new Failure('diskFailure', 'published artifact is invalid'), 'boundedJournalFailure'],
    [new Failure('cancelled', 'run cancelled'), 'runCancelled'],
    [new Failure('startupFailed', 'the host was suspended for 422 s', 'hostSuspended'), 'hostSuspended'],
    [new Failure('startupFailed', 'listeners remained after cleanup'), 'startupOrAcceptanceFailure']
  ]
  for (const config of configs) {
    for (const [failure, code] of faults) {
      const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-mode-diagnostic-'))
      try {
        const retained = retain(directory, { config: structuredClone(config), failure })
        const label = `${config.mode ?? 'crash-containment'} ${code}`
        assert.equal(retained.fallback, false, label)
        assertPrivate(retained.file)
        assert.equal(retained.diagnostic.failure.code, code, label)
        assert.equal(retained.diagnostic.failure.stopReason, failure.stopReason, label)
        assert.equal(retained.diagnostic.configuredConditions.mode, config.mode ?? 'crash-containment', label)
        assert.equal(retained.diagnostic.cleanup.verifiedByListenerProbe, true, label)
        assert.equal(typeof retained.diagnostic.cognition.providerUsage.externalSpendNanoUsd, 'number', label)
      } finally {
        fs.rmSync(directory, { recursive: true, force: true })
      }
    }
  }
})

test('a failed run keeps a bounded cognition timeline of closed facts', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-timeline-diagnostic-'))
  try {
    fs.mkdirSync(path.join(directory, 'own'))
    const files = rawFiles(path.join(directory, 'own'))
    const body = []
    const harness = []
    for (let index = 0; index < 200; index += 1) {
      body.push({ tick: index, agentId: 'tribe-alpha', event: 'perceived', trigger: 'damaged', detail: '/Users/x' })
      harness.push({
        observedAt: '2026-10-16T00:00:00.000Z',
        event: 'cognitionForwarded',
        agentId: 'tribe-alpha',
        requestId: `r-${index}`,
        trigger: 'damaged',
        origin: 'nativeEventDriver',
        outcome: 'decision',
        outstandingAtAdmission: 2,
        latencyMs: 31_000 + index,
        provenance: { model: 'sk-secret' }
      })
    }
    body.push({ tick: 201, agentId: 'tribe-alpha', event: 'cognitionUnavailable', trigger: 'damaged', outcome: 'agentBudgetExhausted', detail: 'x' })
    writeJsonLines(files.bodyFile, body)
    writeJsonLines(files.harnessFile, harness)
    writeJsonLines(files.providerUsageFile, [])
    const retained = retain(directory, { ...files })
    const timeline = retained.diagnostic.cognition.timeline
    assert.equal(timeline.forwarded.length, 128)
    assert.equal(timeline.forwardedOmitted, 72)
    assert.deepEqual(timeline.forwarded.at(-1), {
      agentId: 'tribe-alpha',
      event: 'cognitionForwarded',
      trigger: 'damaged',
      origin: 'nativeEventDriver',
      outcome: 'decision',
      outstandingAtAdmission: 2,
      latencyMs: 31_199
    })
    assert.deepEqual(timeline.body.at(-1), {
      agentId: 'tribe-alpha', tick: 201, event: 'cognitionUnavailable', trigger: 'damaged', outcome: 'agentBudgetExhausted'
    })
    assert.doesNotMatch(fs.readFileSync(retained.file, 'utf8'), /Users|sk-secret|r-199/)
  } finally {
    fs.rmSync(directory, { recursive: true, force: true })
  }
})

test('a failed run keeps the closed outcome tags of its failure records', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-outcome-diagnostic-'))
  try {
    fs.mkdirSync(path.join(directory, 'own'))
    const files = rawFiles(path.join(directory, 'own'))
    writeJsonLines(files.bodyFile, [
      { tick: 1, agentId: 'tribe-alpha', event: 'spawned', detail: 'ready' },
      { tick: 2, agentId: 'tribe-alpha', event: 'witnessDisputed', outcome: 'disputedPosition', detail: 'claimed 1,2,3' },
      { tick: 3, agentId: 'tribe-beta', event: 'witnessDisputed', outcome: 'has spaces /Users/x', detail: 'x' }
    ])
    writeJsonLines(files.harnessFile, [])
    writeJsonLines(files.providerUsageFile, [])
    const retained = retain(directory, { ...files })
    assertPrivate(retained.file)
    assert.deepEqual(retained.diagnostic.body.outcomesByEvent, {
      witnessDisputed: { disputedPosition: 1, unclassified: 1 }
    })
    assert.deepEqual(retained.diagnostic.body.interactionDisputes, [])
  } finally {
    fs.rmSync(directory, { recursive: true, force: true })
  }
})

test('a disputed world interaction keeps its operation, body outcome and vanilla effect only', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-dispute-diagnostic-'))
  try {
    fs.mkdirSync(path.join(directory, 'own'))
    const files = rawFiles(path.join(directory, 'own'))
    writeJsonLines(files.bodyFile, [
      {
        tick: 640,
        agentId: 'tribe-alpha',
        event: 'worldInteraction',
        outcome: 'disputedWorldInteraction',
        detail: 'dig bc0; sense s-1p; control c-1x; body dug; vanilla no matching blockBreak event'
      },
      {
        tick: 700,
        agentId: 'tribe-beta',
        event: 'worldInteraction',
        outcome: 'disputedWorldInteraction',
        detail: 'collect d3; sense /Users/secret; body collected; vanilla entityPickupItem 3daa 1/1 stick'
      },
      { tick: 701, agentId: 'tribe-beta', event: 'worldInteraction', outcome: 'disputedWorldInteraction', detail: 'sk-x' }
    ])
    writeJsonLines(files.harnessFile, [])
    writeJsonLines(files.providerUsageFile, [])
    const retained = retain(directory, { ...files })
    assert.deepEqual(retained.diagnostic.body.interactionDisputes, [
      { agentId: 'tribe-alpha', tick: 640, operation: 'dig', bodyOutcome: 'dug', vanillaEffect: 'absent' },
      { agentId: 'tribe-beta', tick: 700, operation: 'collect', bodyOutcome: 'collected', vanillaEffect: 'found' },
      { agentId: 'tribe-beta', tick: 701, operation: 'unclassified', bodyOutcome: 'unclassified', vanillaEffect: 'unclassified' }
    ])
    assert.doesNotMatch(fs.readFileSync(retained.file, 'utf8'), /Users|sk-x|bc0|3daa/)
  } finally {
    fs.rmSync(directory, { recursive: true, force: true })
  }
})
