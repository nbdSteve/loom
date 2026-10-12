'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')

const {
  FAILURE_DIAGNOSTIC_VERSION,
  boundedJsonLines,
  failureCode,
  writeFailureDiagnostic
} = require('../lib/failure-diagnostic.js')
const { RunFailure } = require('../lib/lifecycle.js')
const { scanText } = require('../lib/evidence.js')
const { openAiProofConfig, writeJsonLines } = require('./fixtures.js')

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

test('diagnostic output is fail-closed when its own byte bound is too small', () => {
  const repositoryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-diagnostic-bound-'))
  const files = rawFiles(repositoryRoot)
  const config = openAiProofConfig()
  writeJsonLines(files.bodyFile, [])
  writeJsonLines(files.harnessFile, [])
  writeJsonLines(files.providerUsageFile, [])
  try {
    assert.throws(
      () => writeFailureDiagnostic({
        repositoryRoot,
        diagnosticRoot: path.join(repositoryRoot, 'diagnostics'),
        config,
        source: SOURCE,
        failure: new RunFailure('cancelled', 'cancelled'),
        cleanupVerified: true,
        ...files,
        clock: () => new Date('2026-10-12T00:00:00.000Z'),
        maxBytes: 100
      }),
      /exceeded its 100 byte bound/
    )
  } finally {
    fs.rmSync(repositoryRoot, { recursive: true, force: true })
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
