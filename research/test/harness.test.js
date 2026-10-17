'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const fs = require('node:fs')
const net = require('node:net')
const os = require('node:os')
const path = require('node:path')

const {
  authenticationFailure,
  conciseJavaVersion,
  ensurePaper,
  importProviderUsage,
  keychainCredentialProvider,
  primaryBehaviorRunFailure,
  verifyFreeDisk,
  verifyNoListeners
} = require('../lib/harness.js')
const {
  openAiProofConfig,
  perceptionAuditFixture,
  primaryBehaviorConfig,
  proofConfig,
  writeJsonLines
} = require('./fixtures.js')

test('the Java tool attestation keeps the version banner and drops runtime detail', () => {
  const full =
    'openjdk version "21.0.11" 2026-04-21 LTS ' +
    'OpenJDK Runtime Environment Corretto-21.0.11.10.1 (build 21.0.11+10-LTS) ' +
    'OpenJDK 64-Bit Server VM Corretto-21.0.11.10.1 (build 21.0.11+10-LTS, mixed mode, sharing)'
  assert.equal(
    conciseJavaVersion(full),
    'openjdk version "21.0.11" 2026-04-21 LTS'
  )
  assert.ok(conciseJavaVersion('x'.repeat(300)).length <= 200)
})

test('the Keychain credential is trimmed and consumed exactly once in memory', () => {
  const calls = []
  const provider = keychainCredentialProvider('loom-openai', (service) => {
    calls.push(service)
    return '  credential-value-for-test\n'
  })
  assert.equal(provider(), 'credential-value-for-test')
  assert.deepEqual(calls, ['loom-openai'])
  assert.throws(
    () => provider(),
    /credential was requested more than once/
  )
  assert.deepEqual(calls, ['loom-openai'])
})

test('Keychain lookup failures stay bounded and never repeat lookup detail', () => {
  assert.throws(
    () => keychainCredentialProvider('bad service'),
    /Keychain service must contain/
  )
  const empty = keychainCredentialProvider('loom-openai', () => ' \n')
  assert.throws(() => empty(), /credential was empty/)

  const failed = keychainCredentialProvider('loom-openai', () => {
    throw new Error('sensitive Keychain diagnostic')
  })
  assert.throws(
    () => failed(),
    (error) => {
      assert.equal(error.message, 'the approved OpenAI Keychain credential could not be read')
      assert.doesNotMatch(error.message, /sensitive/)
      return true
    }
  )
})

test('provider authentication refusals become one provider-safe fatal failure', () => {
  const failure = authenticationFailure(
    "cognition failed: the model provider rejected Loom's credential " +
      '(HTTP 401, invalid_request_error/invalid_api_key); check OPENAI_API_KEY'
  )
  assert.equal(failure.stopReason, 'cognitionFailed')
  assert.equal(
    failure.message,
    "the model provider rejected Loom's credential (HTTP 401); check OPENAI_API_KEY"
  )
  const classified = authenticationFailure('message deliberately ignored', {
    mapping: 'providerAuthentication',
    httpStatus: 403
  })
  assert.equal(classified.stopReason, 'cognitionFailed')
  assert.equal(
    classified.message,
    "the model provider rejected Loom's credential (HTTP 403); check OPENAI_API_KEY"
  )
  assert.equal(authenticationFailure('cognition failed: the provider was busy'), null)
})

test('primary behavior stops on fatal evidence and a lost initial condition', () => {
  const config = primaryBehaviorConfig()
  const firstPerception = {
    event: 'perceived',
    agentId: 'tribe-alpha',
    perception: {
      selfState: {
        position: config.primaryBehavior.initialPositions['tribe-alpha'],
        inventorySummary: []
      }
    },
    perceptionAudit: perceptionAuditFixture()
  }
  assert.equal(primaryBehaviorRunFailure(config, [firstPerception]), null)

  const moved = structuredClone(firstPerception)
  moved.perception.selfState.position[0] += 1
  assert.match(
    primaryBehaviorRunFailure(config, [moved]).message,
    /lost a pinned initial condition/
  )
  assert.match(
    primaryBehaviorRunFailure(config, [{ event: 'witnessRevived', agentId: 'tribe-alpha' }]).message,
    /permadeath breach/
  )
})

test('a witnessed death ends only that agent while its peers continue', () => {
  const config = primaryBehaviorConfig()
  const died = { event: 'died', agentId: 'tribe-alpha', tick: 900 }
  const peer = { event: 'decided', agentId: 'tribe-beta', tick: 950 }
  assert.equal(primaryBehaviorRunFailure(config, [died, peer]), null)
  assert.equal(primaryBehaviorRunFailure(config, [
    { event: 'decided', agentId: 'tribe-alpha', requestId: 'r0' },
    died,
    { event: 'decisionDiscarded', agentId: 'tribe-alpha', requestId: 'r0' },
    { event: 'bodyDisconnected', agentId: 'tribe-alpha' }
  ]), null)
  // Concurrent orderings around a death: only the typed settlement of a request decided
  // before the death is admitted, once, with at most one movement and retreat report each.
  const decided = (requestId) => ({ event: 'decided', agentId: 'tribe-alpha', requestId })
  const applied = (requestId) => ({ event: 'intentApplied', agentId: 'tribe-alpha', requestId })
  const report = (event) => ({ event, agentId: 'tribe-alpha' })
  const orderings = [
    [[decided('r1'), died, applied('r1'), report('moved'), report('retreating')], null],
    [[decided('r1'), decided('r2'), died, applied('r2'), applied('r1'), report('moved'), report('moved')], null],
    [[decided('r1'), died, { event: 'decisionDiscarded', agentId: 'tribe-alpha', requestId: 'r1' }], null],
    [[died, decided('r1')], /recorded decided after its witnessed death/],
    [[died, applied('r1')], /recorded intentApplied after its witnessed death/],
    [[decided('r1'), applied('r1'), died, applied('r1')], /recorded intentApplied after its witnessed death/],
    [[decided('r1'), died, applied('r1'), applied('r1')], /recorded intentApplied after its witnessed death/],
    [[decided('r1'), died, applied('r1'), report('moved'), report('moved')], /recorded moved after its witnessed death/],
    [[died, report('moved')], /recorded moved after its witnessed death/],
    [[decided('r1'), died, { event: 'worldInteraction', agentId: 'tribe-alpha', requestId: 'r1', outcome: 'serverEffectConfirmed' }],
      /recorded worldInteraction after its witnessed death/],
    [[died, { event: 'decisionDiscarded', agentId: 'tribe-alpha', requestId: 'r9' }], /recorded decisionDiscarded after its witnessed death/]
  ]
  for (const [records, expected] of orderings) {
    const failure = primaryBehaviorRunFailure(config, records)
    if (expected === null) assert.equal(failure, null, JSON.stringify(records))
    else assert.match(failure?.message ?? '', expected, JSON.stringify(records))
  }
  // The server can witness the killing blow after the death it caused.
  assert.equal(primaryBehaviorRunFailure(config, [
    died,
    { event: 'damageWitnessed', agentId: 'tribe-alpha', outcome: 'damageDiedBeforeCognition' },
    { event: 'damageWitnessed', agentId: 'tribe-alpha', outcome: 'damageAgentDead' }
  ]), null)
  assert.match(
    primaryBehaviorRunFailure(config, [died, { event: 'damageWitnessed', agentId: 'tribe-alpha', outcome: 'damageSubmitted' }]).message,
    /recorded damageWitnessed after its witnessed death/
  )
  assert.match(
    primaryBehaviorRunFailure(config, [died, { event: 'perceived', agentId: 'tribe-alpha' }]).message,
    /tribe-alpha recorded perceived after its witnessed death/
  )
  assert.match(
    primaryBehaviorRunFailure(config, [died, { event: 'bodyDisconnected', agentId: 'tribe-beta' }]).message,
    /a body disconnected/
  )
  for (const event of ['witnessRevived', 'witnessDisputed', 'witnessDenied', 'witnessQuit']) {
    assert.notEqual(primaryBehaviorRunFailure(config, [died, { event, agentId: 'tribe-beta' }]), null, event)
  }
})

test('Paper download identifies the harness and verifies bytes before caching', async () => {
  const repositoryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-paper-download-'))
  const content = Buffer.from('bounded Paper fixture')
  const config = proofConfig()
  config.paper.sha256 = crypto.createHash('sha256').update(content).digest('hex')
  let request
  const previousFetch = global.fetch
  global.fetch = async (url, options) => {
    request = { url, options }
    let read = false
    return {
      ok: true,
      headers: { get: (name) => name === 'content-length' ? String(content.length) : null },
      body: {
        getReader: () => ({
          read: async () => {
            if (read) return { done: true }
            read = true
            return { done: false, value: content }
          }
        })
      }
    }
  }
  try {
    const file = await ensurePaper(repositoryRoot, config)
    assert.equal(fs.readFileSync(file, 'utf8'), content.toString('utf8'))
    assert.equal(request.url, config.paper.downloadUrl)
    assert.equal(
      request.options.headers['User-Agent'],
      'loom-experiment-harness/0.1.0 (https://github.com/nbdSteve/loom)'
    )
  } finally {
    global.fetch = previousFetch
    fs.rmSync(repositoryRoot, { recursive: true, force: true })
  }
})

test('provider usage is imported with exact pinned-price cost and request identity', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-usage-import-'))
  const file = path.join(directory, 'usage.jsonl')
  writeJsonLines(file, [{
    schemaVersion: 'loom-provider-usage/1',
    agentId: 'tribe-alpha',
    requestId: 'sense-1',
    model: 'small-model',
    promptTokens: 100,
    cachedPromptTokens: 20,
    completionTokens: 10,
    totalTokens: 110
  }])
  const events = []
  const count = importProviderUsage({
    config: openAiProofConfig(),
    file,
    journal: { append: (event, fields) => events.push({ event, ...fields }) }
  })
  assert.equal(count, 1)
  assert.deepEqual(events, [{
    event: 'providerUsage',
    agentId: 'tribe-alpha',
    requestId: 'sense-1',
    model: 'small-model',
    promptTokens: 100,
    cachedPromptTokens: 20,
    completionTokens: 10,
    totalTokens: 110,
    costNanoUsd: 13_200
  }])
})

test('duplicate inconsistent or unknown provider usage is refused', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-usage-refusal-'))
  const file = path.join(directory, 'usage.jsonl')
  const record = {
    schemaVersion: 'loom-provider-usage/1',
    agentId: 'tribe-alpha',
    requestId: 'sense-1',
    model: 'small-model',
    promptTokens: 100,
    cachedPromptTokens: 20,
    completionTokens: 10,
    totalTokens: 110
  }
  writeJsonLines(file, [record, record])
  assert.throws(
    () => importProviderUsage({
      config: openAiProofConfig(),
      file,
      journal: { append: () => {} }
    }),
    /duplicate provider usage/
  )
  writeJsonLines(path.join(directory, 'unknown.jsonl'), [{ ...record, model: 'unpriced-model' }])
  assert.throws(
    () => importProviderUsage({
      config: openAiProofConfig(),
      file: path.join(directory, 'unknown.jsonl'),
      journal: { append: () => {} }
    }),
    /unknown request/
  )
})

test('an occupied configured port is refused before startup', async () => {
  const server = net.createServer()
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const config = proofConfig()
  config.network.minecraftPort = server.address().port
  try {
    await assert.rejects(
      verifyNoListeners(config, 'startup'),
      /already in use before startup/
    )
  } finally {
    await new Promise((resolve) => server.close(resolve))
  }
})

test('a run refuses to start without free disk headroom on the run volume', () => {
  const config = primaryBehaviorConfig()
  const statfs = (free) => () => ({ bavail: free / 4096, bsize: 4096 })
  const gib = 1024 * 1024 * 1024
  assert.deepEqual(verifyFreeDisk('/run', config, statfs(3 * gib)), { available: 3 * gib, required: 2 * gib })
  assert.throws(
    () => verifyFreeDisk('/run', config, statfs(1.2 * gib)),
    (cause) => cause.stopReason === 'diskFailure' && /1228 MiB is free on the run volume; 2048 MiB is required/.test(cause.message)
  )
  const large = primaryBehaviorConfig()
  large.bounds.logBytes = 256 * 1024 * 1024
  assert.throws(() => verifyFreeDisk('/run', large, statfs(3 * gib)), /is required before startup/)
})

test('a full run refuses low disk before any build, listener, Paper start or credential read', async () => {
  const { execFileSync } = require('node:child_process')
  const { runExperiment } = require('../lib/harness.js')
  const repository = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-disk-'))
  const git = (...args) => execFileSync('git', args, { cwd: repository, stdio: 'ignore' })
  git('init', '-q')
  git('config', 'user.email', 'test@example.invalid')
  git('config', 'user.name', 'test')
  fs.writeFileSync(path.join(repository, '.gitignore'), '.scratch/\n')
  git('add', '.gitignore')
  git('commit', '-q', '-m', 'init')
  git('remote', 'add', 'origin', 'https://github.com/nbdSteve/loom.git')
  let credentialReads = 0
  for (const config of [goalContinuityConfigFile(), openAiGoalContinuityConfigFile()]) {
    await assert.rejects(
      runExperiment({
        repositoryRoot: repository,
        configFile: config,
        artifactRoot: path.join(repository, 'artifacts'),
        credentialProvider: () => {
          credentialReads += 1
          return 'never'
        },
        statfs: () => ({ bavail: 1024, bsize: 4096 })
      }),
      (cause) => cause.stopReason === 'diskFailure' && /MiB is required before startup/.test(cause.message)
    )
  }
  assert.equal(credentialReads, 0)
  assert.equal(fs.existsSync(path.join(repository, 'artifacts')), false)
  assert.deepEqual(fs.readdirSync(path.join(repository, '.scratch', 'tribe-runs')), [])
})

function goalContinuityConfigFile () {
  return path.join(__dirname, '..', 'config', 'first-tribe-routine-v24-agency-pilot-scripted.json')
}

function openAiGoalContinuityConfigFile () {
  return path.join(__dirname, '..', 'config', 'first-tribe-routine-v24-agency-pilot.json')
}

test('a busy host is refused before startup and a quiet one is admitted', async () => {
  const { awaitQuietHost, MAX_LOAD_PER_CPU } = require('../lib/paper-runtime.js')
  assert.equal(MAX_LOAD_PER_CPU, 0.5)
  const quiet = await awaitQuietHost({ loadavg: () => [5.9, 0, 0], cpus: () => 12, settleMs: 0 })
  assert.deepEqual(quiet, { load: 5.9, limit: 6 })
  await assert.rejects(
    awaitQuietHost({ loadavg: () => [16.4, 0, 0], cpus: () => 12, settleMs: 0, wait: async () => {} }),
    (cause) => cause.stopReason === 'startupFailed' && /host load 16.40 is above the 6.00 start threshold/.test(cause.message)
  )
  const readings = [9, 8, 4]
  const settled = await awaitQuietHost({ loadavg: () => [readings.shift() ?? 4, 0, 0], cpus: () => 12, settleMs: 60_000, wait: async () => {} })
  assert.equal(settled.load, 4)
})

test('the Paper runtime cache verifies file by file against its own manifest', () => {
  const { verifiedRuntime } = require('../lib/paper-runtime.js')
  const crypto = require('node:crypto')
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-runtime-'))
  fs.mkdirSync(path.join(root, 'cache'))
  fs.writeFileSync(path.join(root, 'cache', 'mojang.jar'), 'jar')
  const hash = crypto.createHash('sha256').update('jar').digest('hex')
  assert.equal(verifiedRuntime(root), false)
  fs.writeFileSync(path.join(root, 'runtime-manifest.json'), JSON.stringify({ [path.join('cache', 'mojang.jar')]: hash }))
  assert.equal(verifiedRuntime(root), true)
  fs.writeFileSync(path.join(root, 'cache', 'mojang.jar'), 'tampered')
  assert.equal(verifiedRuntime(root), false)
  fs.writeFileSync(path.join(root, 'cache', 'mojang.jar'), 'jar')
  fs.writeFileSync(path.join(root, 'cache', 'extra.jar'), 'x')
  assert.equal(verifiedRuntime(root), false)
})

test('the Paper clock asks for game time on a bounded cadence and reads the latest answer', () => {
  const { PaperClock } = require('../lib/paper-clock.js')
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-clock-'))
  const log = path.join(directory, 'paper.log')
  fs.writeFileSync(log, '')
  let now = 0
  const written = []
  const clock = new PaperClock({ write: (command) => written.push(command), logFile: log, now: () => now })
  assert.equal(clock.poll(), null)
  assert.deepEqual(written, ['time query gametime'])
  fs.appendFileSync(log, '[17:00:00 INFO]: The time is 1200\n[17:00:01 INFO]: The time is 13')
  assert.equal(clock.poll(), 1200, 'a partial last line is not read yet')
  fs.appendFileSync(log, '50\n')
  now = 1_000
  assert.equal(clock.poll(), 1350)
  assert.deepEqual(written, ['time query gametime'], 'asked at most every two seconds')
  now = 2_000
  clock.poll()
  assert.equal(written.length, 2)
})

test('a host suspension mid-run is named from the measured clock gap, not blamed on bodies', () => {
  const { SuspensionWatch } = require('../lib/paper-runtime.js')
  let now = 0
  const watch = new SuspensionWatch({ now: () => now })
  now = 250
  assert.equal(watch.check(), null)
  // The clamshell sleep that cut a rehearsal: 422 seconds with the whole host stopped.
  now += 422_000
  const failure = watch.check()
  assert.equal(failure.code, 'hostSuspended')
  assert.equal(failure.stopReason, 'startupFailed')
  assert.match(failure.message, /suspended for 422 s/)
})

test('a primary behavior run holds native cognition until its initial conditions are applied', () => {
  const { applyPrimaryBehaviorInitialConditions, nativeCognitionStartOf } = require('../lib/harness.js')
  const { readJson } = require('../lib/util.js')
  const live = readJson(path.join(__dirname, '..', 'config', 'tribe-shakedown-10m-10.json'))
  const crashProof = readJson(path.join(__dirname, '..', 'config', 'first-tribe-scripted-proof.json'))
  assert.equal(nativeCognitionStartOf(live), 'held')
  assert.equal(nativeCognitionStartOf(crashProof), 'immediate')

  const written = []
  const paper = { child: { stdin: { writable: true, write: (line) => written.push(line.trim()) } } }
  const journalled = []
  applyPrimaryBehaviorInitialConditions(paper, live, { append: (event) => journalled.push(event) })
  // Begin is the last console command, after every position, inventory and clock condition.
  assert.equal(written.at(-1), 'loombody begin')
  assert.equal(written.filter((line) => line === 'loombody begin').length, 1)
  assert.ok(written.indexOf('time set 0') < written.length - 1)
  assert.equal(written.filter((line) => line.startsWith('tp ')).length, 10)
  assert.ok(written.findLastIndex((line) => line.startsWith('tp ')) < written.length - 1)
  assert.deepEqual(journalled, ['initialConditionsAttested'])
})
