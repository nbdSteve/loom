'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const net = require('node:net')
const os = require('node:os')
const path = require('node:path')

const { publishArtifact, validateArtifact } = require('../lib/artifact.js')
const { CognitionBudgetProxy } = require('../lib/budget-proxy.js')
const {
  budgetStopFor,
  providerStopFor,
  reviewWindowBounds
} = require('../lib/controlled-stop.js')
const {
  authenticationFailure,
  drainControlledStop,
  primaryBehaviorRunFailure
} = require('../lib/harness.js')
const { readJson } = require('../lib/util.js')
const {
  LIVE_MODEL,
  bodyRecord,
  controlledStopRawRecords,
  goalContinuityConfig,
  openAiGoalContinuityConfig,
  primaryBehaviorRawRecords,
  proofConfig,
  writeJsonLines
} = require('./fixtures.js')

const SOURCE = {
  commit: 'a'.repeat(40),
  repository: 'https://github.com/nbdSteve/loom.git'
}
const COMPONENTS = {
  plugin: { buildSha256: 'b'.repeat(64), sourceSha256: 'c'.repeat(64) },
  body: { buildSha256: 'd'.repeat(64), sourceSha256: 'e'.repeat(64) },
  cognition: { buildSha256: 'f'.repeat(64), sourceSha256: '1'.repeat(64) }
}
const TOOLS = {
  os: 'test-os',
  architecture: 'test-arch',
  java: 'openjdk version 21',
  node: 'v22.0.0',
  npm: '10.0.0',
  rustc: 'rustc 1.90.0',
  cargo: 'cargo 1.90.0',
  git: 'git version 2.50.0',
  lsof: 'lsof 4.91',
  gradle: 'Gradle 8.14'
}

function publish ({ config = goalContinuityConfig(), raw, stopReason = 'controlledStop' }) {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-controlled-stop-'))
  const body = path.join(temporary, 'body.jsonl')
  const harness = path.join(temporary, 'harness.jsonl')
  writeJsonLines(body, raw.body)
  writeJsonLines(harness, raw.harness)
  const artifactDirectory = path.join(temporary, 'published', config.experimentId)
  const result = publishArtifact({
    config,
    source: SOURCE,
    components: COMPONENTS,
    tools: TOOLS,
    startedAt: '2026-10-13T00:00:00.000Z',
    endedAt: '2026-10-13T00:01:00.000Z',
    stopReason,
    rawBodyEvidence: body,
    rawHarnessEvidence: harness,
    artifactDirectory
  })
  return { ...result, artifactDirectory }
}

function readme (published) {
  return fs.readFileSync(path.join(published.artifactDirectory, 'README.md'), 'utf8')
}

function assertNoGo (published, kind) {
  const stop = published.metrics.controlledStop
  assert.equal(validateArtifact(published.artifactDirectory).ok, true)
  assert.equal(published.manifest.timing.stopReason, 'controlledStop')
  assert.equal(stop.kind, kind)
  assert.equal(stop.behavioralVerdict, 'no-go')
  assert.deepEqual(published.manifest.controlledStop, stop)
  assert.equal(published.manifest.integrityChecks.controlledStopAccounted.passed, true)
  assert.equal(published.manifest.integrityChecks.expectedNativeReviewsObserved.passed, true)
  assert.equal(published.manifest.integrityChecks.completeDecisionChains.passed, true)
  assert.match(published.manifest.outcome, /behavioral verdict no-go/)
  const text = readme(published)
  assert.match(text, /## Controlled stop/)
  assert.match(text, /Behavioral verdict: \*\*no-go\*\*/)
  assert.match(text, new RegExp(`of ${stop.nativeReviews.planned} planned`))
  return stop
}

function rejects (raw, pattern, config = goalContinuityConfig()) {
  assert.throws(() => publish({ config, raw }), pattern)
}

test('a global-outstanding stop publishes complete earlier chains and the drained in-flight work', () => {
  const published = publish({ raw: controlledStopRawRecords({ inFlight: 3 }) })
  const stop = assertNoGo(published, 'cognitionBudgetExhausted')
  assert.deepEqual(stop.refusal, { bound: 'globalOutstanding', unit: 'calls', ceiling: 3, observed: 3 })
  assert.equal(stop.outstandingAtClose, 3)
  assert.equal(stop.forwardedAfterClose, 3)
  assert.equal(stop.refusedAfterClose, 1)
  assert.deepEqual(stop.chains, { complete: 6, refused: 2 })
  assert.equal(stop.nativeReviews.planned, 6)
  assert.equal(stop.nativeReviews.completed, 3)
  assert.match(readme(published), /`globalOutstanding` at 3 of 3 calls; the call was not forwarded/)
})

test('a per-agent call stop names its agent and reconciles that agent\'s forwarded calls', () => {
  const published = publish({
    raw: controlledStopRawRecords({ bound: 'perAgentCall', eventChains: 3 })
  })
  const stop = assertNoGo(published, 'cognitionBudgetExhausted')
  assert.equal(stop.trigger.agentId, 'tribe-beta')
  assert.deepEqual(stop.refusal, { bound: 'perAgentCall', unit: 'calls', ceiling: 4, observed: 4 })
})

test('a global call stop counts calls admitted before the closure, including in-flight ones', () => {
  const published = publish({
    raw: controlledStopRawRecords({
      bound: 'globalCall',
      eventChains: 6,
      eventAgents: ['tribe-alpha', 'tribe-beta', 'tribe-gamma'],
      inFlight: 3,
      stopAgent: 'tribe-alpha'
    })
  })
  const stop = assertNoGo(published, 'cognitionBudgetExhausted')
  assert.equal(stop.forwardedAtClose, 12)
  assert.equal(stop.refusal.observed, 12)
})

test('a spend stop is typed, but a spend refusal the reservation could not produce is refused', () => {
  const stop = budgetStopFor({
    agentId: 'tribe-beta',
    requestId: 'r',
    bound: 'spend',
    unit: 'nanoUsd',
    ceiling: 2_340_000_000,
    observed: 2_334_720_000,
    trigger: 'damaged',
    importance: 'ROUTINE',
    origin: 'nativeEventDriver'
  })
  assert.equal(stop.kind, 'cognitionBudgetExhausted')
  assert.equal(stop.bound, 'spend')
  assert.equal(budgetStopFor({ bound: 'postCrashReserve' }), null)
  rejects(controlledStopRawRecords({ bound: 'spend' }), /controlled budget stop states an impossible count/)
})

test('a live budget stop reconciles provider calls tokens and spend from usage evidence', () => {
  const published = publish({
    config: openAiGoalContinuityConfig(),
    raw: controlledStopRawRecords({ config: openAiGoalContinuityConfig(), inFlight: 3 })
  })
  const stop = assertNoGo(published, 'cognitionBudgetExhausted')
  assert.equal(stop.usage.providerCalls, 6)
  assert.equal(stop.usage.totalTokens, 6 * 2100)
  assert.equal(stop.usage.costNanoUsd, 6 * 525_000)
  assert.equal(published.manifest.cognition.actualExternalSpendUsd, stop.usage.costUsd)
  assert.match(readme(published), /Provider usage: 6 call\(s\), 12600 tokens, \$0\.003150000/)
})

test('a classified provider unavailability stops under its own kind and keeps its classification', () => {
  const config = openAiGoalContinuityConfig()
  const published = publish({
    config,
    raw: controlledStopRawRecords({ config, kind: 'providerUnavailable', inFlight: 1 })
  })
  const stop = assertNoGo(published, 'providerUnavailable')
  assert.equal(stop.providerClassification.mapping, 'providerServer')
  assert.equal(stop.providerClassification.model, LIVE_MODEL)
  assert.equal(stop.refusal, undefined)
  assert.equal(stop.usage.providerCalls, 4)
  assert.match(readme(published), /Provider classification: .*providerServer/)
})

test('a stop before any decision publishes with zero owed reviews', () => {
  const published = publish({
    raw: controlledStopRawRecords({ inFlight: 3, reviews: false, stopTick: 40 })
  })
  const stop = assertNoGo(published, 'cognitionBudgetExhausted')
  assert.equal(stop.nativeReviews.completed, 3 * 0)
  for (const entry of Object.values(stop.nativeReviews.byAgent)) {
    assert.deepEqual(entry, { attempted: 0, completed: 0, minimum: 0, maximum: 1 })
  }
})

test('owed and possible scheduled reviews follow the stop window at boundary ticks', () => {
  const schedule = { cadenceTicks: 2400, sliceTicks: 100, expectedReviewsPerAgent: 2 }
  assert.deepEqual(reviewWindowBounds(schedule, 3, 0), { minimum: 0, maximum: 1 })
  assert.deepEqual(reviewWindowBounds(schedule, 3, 399), { minimum: 0, maximum: 1 })
  assert.deepEqual(reviewWindowBounds(schedule, 3, 400), { minimum: 1, maximum: 1 })
  assert.deepEqual(reviewWindowBounds(schedule, 3, 2399), { minimum: 1, maximum: 1 })
  assert.deepEqual(reviewWindowBounds(schedule, 3, 2400), { minimum: 1, maximum: 2 })
  assert.deepEqual(reviewWindowBounds(schedule, 3, 2799), { minimum: 1, maximum: 2 })
  assert.deepEqual(reviewWindowBounds(schedule, 3, 2800), { minimum: 2, maximum: 2 })
  assert.deepEqual(reviewWindowBounds(schedule, 3, 100_000), { minimum: 2, maximum: 2 })
})

test('a stop window that owed a review the evidence lacks is refused', () => {
  const raw = controlledStopRawRecords({ inFlight: 3 })
  raw.body = raw.body.filter((record) => !(record.agentId === 'tribe-gamma' && record.requestId === 'native-1'))
  raw.harness = raw.harness.filter((record) => !(record.agentId === 'tribe-gamma' && record.requestId === 'native-1'))
  rejects(raw, /tribe-gamma did not record the expected native scheduled reviews/)
})

test('a missing earlier chain is refused', () => {
  const raw = controlledStopRawRecords({ inFlight: 3 })
  raw.body = raw.body.filter((record) => !(record.agentId === 'tribe-alpha' && record.requestId === 'native-1'))
  rejects(raw, /forwarded request tribe-alpha:native-1 has no earlier perception chain/)
})

test('unaccounted provider usage is refused', () => {
  const config = openAiGoalContinuityConfig()
  const raw = controlledStopRawRecords({ config, inFlight: 3 })
  const usage = raw.harness.find((record) => record.event === 'providerUsage')
  raw.harness.splice(raw.harness.indexOf(usage), 0, { ...usage, requestId: 'late-1' })
  rejects(raw, /provider usage tribe-alpha:late-1 has no forwarded cognition request/, config)
  const missing = controlledStopRawRecords({ config, inFlight: 3 })
  missing.harness = missing.harness.filter((record) =>
    !(record.event === 'providerUsage' && record.requestId === 'flight-1')
  )
  rejects(missing, /successful live cognition request tribe-gamma:flight-1 has no provider usage/, config)
})

test('an untyped refusal is refused', () => {
  const raw = controlledStopRawRecords({ inFlight: 3 })
  const late = raw.harness.find((record) => record.requestId === 'late-1')
  delete late.bound
  rejects(raw, /refusal tribe-alpha:late-1 is untyped/)
  const bare = controlledStopRawRecords({ inFlight: 3 })
  bare.harness = bare.harness.filter((record) => record.requestId !== 'late-1')
  rejects(bare, /unavailable decision tribe-alpha:late-1 has no typed refusal/)
})

test('an impossible count is refused', () => {
  const raw = controlledStopRawRecords({ inFlight: 2 })
  for (const record of raw.harness) {
    if (record.event === 'cognitionAdmissionClosed') record.outstandingCalls = 3
  }
  rejects(raw, /in-flight accounting is impossible/)
  const perAgent = controlledStopRawRecords({ bound: 'perAgentCall', eventChains: 2 })
  rejects(perAgent, /impossible count|configured ceiling/)
})

test('a stop with no matching evidence is refused', () => {
  const raw = controlledStopRawRecords({ inFlight: 3 })
  raw.body = raw.body.filter((record) => record.requestId !== 'stop-1')
  rejects(raw, /no matching perception and refusal evidence/)
  const orphan = controlledStopRawRecords({ inFlight: 3 })
  for (const record of orphan.harness) {
    if (record.event === 'cognitionAdmissionClosed') record.requestId = 'never-1'
  }
  rejects(orphan, /no matching perception and refusal evidence|no matching typed refusal/)
  const reason = primaryBehaviorRawRecords(goalContinuityConfig())
  rejects(reason, /controlled stop must carry both its stop reason and its admission closure/)
})

test('a dirty shutdown or a killed process is refused', () => {
  const dirty = controlledStopRawRecords({ inFlight: 3 })
  dirty.harness = dirty.harness.filter((record) => record.event !== 'cleanupVerified')
  rejects(dirty, /integrity check cleanupVerified did not pass/)
  const killed = controlledStopRawRecords({ inFlight: 3 })
  killed.harness.splice(killed.harness.length - 1, 0, {
    observedAt: '2026-10-13T00:03:00.500Z',
    event: 'processGroupSignalled',
    name: 'paper',
    signal: 'SIGKILL'
  })
  rejects(killed, /process terminated by SIGKILL/)
})

test('secret path and provider-id material never reaches a stop artifact', () => {
  const raw = controlledStopRawRecords({ inFlight: 3 })
  const record = raw.body.find((entry) => entry.requestId === 'stop-1' && entry.event === 'cognitionUnavailable')
  record.detail = 'sk-proj-abcdefghijklmnopqrstuvwxyz0123456789 /Users/someone/secret req_0123456789abcdef'
  const published = publish({ raw })
  const text = fs.readFileSync(path.join(published.artifactDirectory, 'evidence.jsonl'), 'utf8')
  assert.doesNotMatch(text, /sk-proj-|\/Users\/someone|req_0123456789abcdef/)
  assert.equal(validateArtifact(published.artifactDirectory).ok, true)
  const evidence = path.join(published.artifactDirectory, 'evidence.jsonl')
  fs.appendFileSync(evidence, '')
  const manifestFile = path.join(published.artifactDirectory, 'manifest.json')
  const manifest = readJson(manifestFile)
  manifest.controlledStop.trigger.origin = '/Users/someone/secret'
  fs.writeFileSync(manifestFile, JSON.stringify(manifest))
  const validation = validateArtifact(published.artifactDirectory)
  assert.equal(validation.ok, false)
  assert.ok(validation.errors.some((error) => /local absolute path|manifest controlled stop/.test(error)))
})

test('crash-containment evidence cannot claim a controlled stop', () => {
  assert.throws(
    () => publish({ config: proofConfig(), raw: controlledStopRawRecords({ inFlight: 3 }) }),
    /published artifact is invalid/
  )
})

test('a normal completion still requires the full configured review plan', () => {
  const full = publish({
    raw: primaryBehaviorRawRecords(goalContinuityConfig()),
    stopReason: 'durationElapsed'
  })
  assert.equal(full.metrics.controlledStop, undefined)
  assert.equal(full.manifest.controlledStop, undefined)
  assert.equal(full.manifest.integrityChecks.controlledStopAccounted, undefined)
  assert.equal(validateArtifact(full.artifactDirectory).ok, true)
  const short = primaryBehaviorRawRecords(goalContinuityConfig())
  short.body = short.body.filter((record) => record.requestId !== 'native-2')
  short.harness = short.harness.filter((record) => record.requestId !== 'native-2')
  assert.throws(
    () => publish({ raw: short, stopReason: 'durationElapsed' }),
    /did not record the expected native scheduled reviews/
  )
  const refused = controlledStopRawRecords({ inFlight: 3 })
  refused.harness = refused.harness.filter((record) => record.event !== 'cognitionAdmissionClosed')
  assert.throws(
    () => publish({ raw: refused, stopReason: 'durationElapsed' }),
    /cognition refusal, unavailable decision, or discarded decision/
  )
})

test('every committed generation-3 artifact still validates', () => {
  const root = path.join(__dirname, '..', 'experiments')
  for (const name of fs.readdirSync(root)) {
    const manifest = readJson(path.join(root, name, 'manifest.json'))
    if (manifest.schemaVersion !== 'loom-experiment-manifest/3') continue
    assert.deepEqual(validateArtifact(path.join(root, name)).errors, [], name)
  }
})

test('the live run treats only typed refusals after an admission closure as non-fatal', () => {
  const config = goalContinuityConfig()
  const raw = controlledStopRawRecords({ inFlight: 3 })
  const body = raw.body.map((record) => ({ ...record }))
  assert.equal(primaryBehaviorRunFailure(config, body, raw.harness), null)
  assert.match(
    primaryBehaviorRunFailure(config, body, []).message,
    /cognition request became unavailable/
  )
  const untyped = [...body, bodyRecord(600, 'tribe-gamma', 'cognitionUnavailable', 'x', { requestId: 'other-1' })]
  assert.match(primaryBehaviorRunFailure(config, untyped, raw.harness).message, /became unavailable/)
  for (const event of ['died', 'witnessDisputed', 'witnessRevived', 'bodyDisconnected', 'decisionDiscarded']) {
    const fatal = [...body, bodyRecord(600, 'tribe-gamma', event, 'x')]
    assert.notEqual(primaryBehaviorRunFailure(config, fatal, raw.harness), null, event)
  }
})

test('provider classification selects a controlled stop while 401 and 403 stay fatal', () => {
  const request = { agentId: 'a', requestId: 'r', trigger: 'damaged', importance: 'ROUTINE', origin: 'nativeEventDriver' }
  const classified = (mapping, httpStatus) => ({
    schemaVersion: 'loom-cognition-failure/1',
    backend: 'openai',
    model: LIVE_MODEL,
    httpStatus,
    providerType: null,
    providerCode: null,
    responseShape: 'apiErrorEnvelope',
    mapping
  })
  for (const mapping of ['providerTimeout', 'providerRateLimit', 'providerServer', 'providerConnection']) {
    assert.equal(providerStopFor(request, classified(mapping, 503)).kind, 'providerUnavailable')
  }
  for (const mapping of ['providerDecisionParse', 'providerUsageMissing', 'providerSafetyRefusal']) {
    assert.equal(providerStopFor(request, classified(mapping, 200)), null)
  }
  assert.equal(providerStopFor(request, null), null)
  for (const status of [401, 403]) {
    assert.equal(providerStopFor(request, classified('providerAuthentication', status)), null)
    assert.equal(
      authenticationFailure('x', classified('providerAuthentication', status)).code,
      'providerAuthenticationRejected'
    )
  }
})

function upstream (answer, delayMilliseconds = 0) {
  const server = net.createServer((socket) => {
    socket.once('data', (chunk) => {
      const perception = JSON.parse(chunk.toString('utf8').split('\n')[0])
      setTimeout(() => socket.end(`${JSON.stringify(answer(perception))}\n`), delayMilliseconds)
    })
  })
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server)))
}

function ask (port, agentId, requestId, trigger = 'damaged') {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: '127.0.0.1', port })
    let response = ''
    socket.once('connect', () => socket.write(`${JSON.stringify({
      requestId,
      agentId,
      trigger: { event: trigger },
      importance: 'ROUTINE'
    })}\n`))
    socket.on('data', (chunk) => {
      response += chunk.toString('utf8')
      const newline = response.indexOf('\n')
      if (newline !== -1) {
        socket.destroy()
        resolve(JSON.parse(response.slice(0, newline)))
      }
    })
    socket.once('error', reject)
  })
}

const decisionAnswer = (perception) => ({
  requestId: perception.requestId,
  agentId: perception.agentId,
  intent: { action: 'idle' },
  reasoning: 'test',
  provenance: { tier: 'ROUTINE', model: 'scripted-stand-in', promptVersion: 'routine-v13' }
})

async function stoppingProxy ({ answer = decisionAnswer, delay = 0, budget, spend }) {
  const server = await upstream(answer, delay)
  const events = []
  const proxy = new CognitionBudgetProxy({
    listen: { host: '127.0.0.1', port: 0 },
    upstream: { host: '127.0.0.1', port: server.address().port },
    agents: ['alpha', 'beta', 'gamma'],
    budget,
    models: { ordinary: LIVE_MODEL, pivotal: 'gpt-5.4' },
    requestOrigin: () => 'nativeEventDriver',
    spend,
    journal: { append: (event, fields) => events.push({ event, ...fields }) },
    onRefusal: (refusal) => {
      const stop = budgetStopFor(refusal)
      if (stop !== null) proxy.closeAdmission(stop)
    },
    onCognitionFailure: (message, classified, request) => {
      const stop = providerStopFor(request, classified)
      if (stop !== null) proxy.closeAdmission(stop)
    }
  })
  await proxy.start()
  return {
    proxy,
    events,
    close: async () => {
      await proxy.close()
      await new Promise((resolve) => server.close(resolve))
    }
  }
}

test('a budget refusal closes admission while concurrent in-flight work settles', async () => {
  const run = await stoppingProxy({
    delay: 150,
    budget: { perAgentCallCeiling: 4, globalCallCeiling: 12, globalOutstandingCeiling: 2 }
  })
  try {
    const first = ask(run.proxy.listen.port, 'alpha', 'one')
    const second = ask(run.proxy.listen.port, 'beta', 'one')
    await new Promise((resolve) => setTimeout(resolve, 30))
    assert.match((await ask(run.proxy.listen.port, 'gamma', 'one')).error, /experiment budget/)
    assert.match((await ask(run.proxy.listen.port, 'gamma', 'two')).error, /experiment budget/)
    assert.equal((await first).intent.action, 'idle')
    assert.equal((await second).intent.action, 'idle')
    const names = run.events.map((event) => event.event)
    assert.deepEqual(names, [
      'cognitionRefused',
      'cognitionAdmissionClosed',
      'cognitionRefused',
      'cognitionForwarded',
      'cognitionForwarded'
    ])
    const closure = run.events[1]
    assert.equal(closure.kind, 'cognitionBudgetExhausted')
    assert.equal(closure.bound, 'globalOutstanding')
    assert.equal(closure.outstandingCalls, 2)
    assert.equal(closure.forwardedCalls, 2)
    assert.equal(run.events[2].bound, 'admissionClosed')
    assert.equal(run.proxy.snapshot().outstandingCalls, 0)
  } finally {
    await run.close()
  }
})

test('per-agent, global-call and spend refusals each close admission under their own bound', async () => {
  for (const [bound, budget, spend, calls] of [
    ['perAgentCall', { perAgentCallCeiling: 1, globalCallCeiling: 3, globalOutstandingCeiling: 1 }, undefined, [['alpha', 'one'], ['alpha', 'two']]],
    ['globalCall', { perAgentCallCeiling: 2, globalCallCeiling: 1, globalOutstandingCeiling: 1 }, undefined, [['alpha', 'one'], ['beta', 'one']]],
    ['spend', { perAgentCallCeiling: 2, globalCallCeiling: 3, globalOutstandingCeiling: 1 }, { ceilingNanoUsd: 150, worstCaseCallNanoUsd: 100 }, [['alpha', 'one'], ['beta', 'one']]]
  ]) {
    const run = await stoppingProxy({ budget, spend })
    try {
      for (const [agentId, requestId] of calls) await ask(run.proxy.listen.port, agentId, requestId)
      assert.match((await ask(run.proxy.listen.port, 'gamma', 'late')).error, /experiment budget/)
      const closure = run.events.find((event) => event.event === 'cognitionAdmissionClosed')
      assert.equal(closure.bound, bound)
      assert.equal(run.events.at(-1).bound, 'admissionClosed')
      assert.equal(run.events.filter((event) => event.event === 'cognitionAdmissionClosed').length, 1)
    } finally {
      await run.close()
    }
  }
})

test('a classified provider failure closes admission after its forwarded record', async () => {
  const run = await stoppingProxy({
    budget: { perAgentCallCeiling: 4, globalCallCeiling: 12, globalOutstandingCeiling: 3 },
    answer: () => ({
      error: 'the model provider is unavailable',
      failure: {
        schemaVersion: 'loom-cognition-failure/1',
        backend: 'openai',
        model: LIVE_MODEL,
        httpStatus: 503,
        providerType: 'server_error',
        providerCode: null,
        responseShape: 'apiErrorEnvelope',
        mapping: 'providerServer'
      }
    })
  })
  try {
    assert.match((await ask(run.proxy.listen.port, 'alpha', 'one')).error, /unavailable/)
    assert.match((await ask(run.proxy.listen.port, 'beta', 'one')).error, /experiment budget/)
    assert.deepEqual(run.events.map((event) => event.event), [
      'cognitionForwarded',
      'cognitionAdmissionClosed',
      'cognitionRefused'
    ])
    assert.equal(run.events[1].kind, 'providerUnavailable')
    assert.equal(run.events[1].classification.mapping, 'providerServer')
    assert.equal(run.events[1].outstandingCalls, 1)
  } finally {
    await run.close()
  }
})

test('the controlled-stop drain waits for settled chains and is bounded', async () => {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-drain-'))
  const file = path.join(temporary, 'body.jsonl')
  writeJsonLines(file, [bodyRecord(1, 'a', 'perceived', 'x', { requestId: 'one' })])
  let outstanding = 1
  const proxy = { snapshot: () => ({ outstandingCalls: outstanding }) }
  await assert.rejects(
    drainControlledStop({ proxy, rawBodyEvidence: file, timeoutMilliseconds: 250 }),
    (cause) => cause.code === 'controlledStopDrainTimedOut'
  )
  setTimeout(() => {
    outstanding = 0
    writeJsonLines(file, [
      bodyRecord(1, 'a', 'perceived', 'x', { requestId: 'one' }),
      bodyRecord(2, 'a', 'cognitionUnavailable', 'x', { requestId: 'one' })
    ])
  }, 150)
  await drainControlledStop({ proxy, rawBodyEvidence: file, timeoutMilliseconds: 2000 })
})
