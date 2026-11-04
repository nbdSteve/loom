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
  importObserverSessions,
  importProviderUsage,
  keychainCredentialProvider,
  primaryBehaviorRunFailure,
  runBuild,
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

test('a build step past its bound, failed or aborted stops its whole process group', { skip: process.platform === 'win32' }, async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-build-'))
  const options = { cwd: directory, env: process.env, timeout: 10_000 }
  // Each step's shell starts a runner of its own, as npm starts a test runner, which outlives the
  // shell unless the step's whole group is stopped (the v35 shelter rehearsal's body check).
  const step = (name, rest) => ['-c', `sleep 30 & echo $! > ${name}; ${rest}`]
  const runnerOf = async (name) => {
    const file = path.join(directory, name)
    while (!fs.existsSync(file) || fs.readFileSync(file, 'utf8').trim() === '') await new Promise((resolve) => setTimeout(resolve, 10))
    return Number(fs.readFileSync(file, 'utf8'))
  }
  const alive = (pid) => {
    try {
      process.kill(pid, 0)
      return true
    } catch {
      return false
    }
  }
  try {
    await runBuild('sh', ['-c', 'exit 0'], options)
    await assert.rejects(runBuild('sh', step('bound', 'wait'), { ...options, timeout: 200 }), /exceeded its 200 ms build bound/)
    assert.equal(alive(await runnerOf('bound')), false)
    await assert.rejects(runBuild('sh', step('failed', 'exit 3'), options), /exited 3$/)
    assert.equal(alive(await runnerOf('failed')), false)
    const controller = new AbortController()
    const aborted = runBuild('sh', step('aborted', 'wait'), { ...options, signal: controller.signal })
    const runner = await runnerOf('aborted')
    controller.abort(new Error('received SIGINT'))
    await assert.rejects(aborted, /received SIGINT/)
    assert.equal(alive(runner), false)
    await assert.rejects(runBuild('sh', ['-c', 'exit 0'], { ...options, signal: controller.signal }), /received SIGINT/)
  } finally {
    fs.rmSync(directory, { recursive: true, force: true })
  }
})

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

test('a danger answer newer damage preempted is superseded, and any other discard by a living agent is kept and the run goes on', () => {
  const config = primaryBehaviorConfig()
  const preempted = { event: 'decisionDiscarded', agentId: 'tribe-alpha', requestId: 'r1', outcome: 'dangerPreempted', tick: 6100 }
  assert.equal(primaryBehaviorRunFailure(config, [preempted]), null)
  assert.equal(primaryBehaviorRunFailure(config, [{ ...preempted, outcome: undefined }]), null)
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
  const witness = (requestId) =>
    ({ event: 'worldInteraction', agentId: 'tribe-alpha', requestId, outcome: 'serverEffectConfirmed' })
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
    // The server's witness of a settled request's interaction follows its settlement, once (the
    // v35 shelter rehearsal: delta was killed while its closing was in flight).
    [[decided('r1'), died, applied('r1'), witness('r1')], null],
    [[decided('r1'), died, applied('r1'), report('moved'), witness('r1')], null],
    [[decided('r1'), died, witness('r1')], /recorded worldInteraction after its witnessed death/],
    [[decided('r1'), died, applied('r1'), witness('r1'), witness('r1')], /recorded worldInteraction after its witnessed death/],
    [[decided('r1'), died, applied('r1'), witness('r2')], /recorded worldInteraction after its witnessed death/],
    [[decided('r1'), died, { event: 'decisionDiscarded', agentId: 'tribe-alpha', requestId: 'r1' }, witness('r1')],
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
  for (const event of ['witnessRevived', 'witnessDenied', 'witnessQuit']) {
    assert.notEqual(primaryBehaviorRunFailure(config, [died, { event, agentId: 'tribe-beta' }]), null, event)
  }
  // A peer's witness dispute is an ordinary typed fault, kept as evidence.
  assert.equal(primaryBehaviorRunFailure(config, [died, { event: 'witnessDisputed', agentId: 'tribe-beta' }]), null)
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
  return path.join(__dirname, '..', 'config', 'first-tribe-routine-v36-agency-pilot-scripted.json')
}

function openAiGoalContinuityConfigFile () {
  return path.join(__dirname, '..', 'config', 'first-tribe-routine-v36-agency-pilot.json')
}

test('no host-state gate delays or refuses a run', () => {
  // A busy host never stops a run before it starts or holds it waiting; only a suspension the run
  // itself measured makes it invalid.
  const runtime = require('../lib/paper-runtime.js')
  assert.equal(runtime.awaitQuietHost, undefined)
  assert.equal(runtime.MAX_LOAD_PER_CPU, undefined)
  const harness = fs.readFileSync(path.join(__dirname, '..', 'lib', 'harness.js'), 'utf8')
  // Nothing reads the host's load, swap, memory pressure, power, lid or display state.
  for (const source of [harness, fs.readFileSync(path.join(__dirname, '..', 'lib', 'paper-runtime.js'), 'utf8')]) {
    assert.doesNotMatch(source, /loadavg|swapusage|memory_pressure|vm_pressure|pmset|ioreg|AppleClamshellState|freemem/)
  }
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

test('a host suspension mid-run ends the run integrity-invalid, whatever else happens after it', () => {
  const { SuspensionWatch } = require('../lib/paper-runtime.js')
  let now = 0
  const watch = new SuspensionWatch({ now: () => now })
  now = 250
  assert.equal(watch.check(), null)
  // The clamshell sleep that cut a rehearsal: 422 seconds with the whole host stopped. The gap
  // alone is the run's failure: nothing else need fail for it to be invalid.
  now += 422_000
  const failure = watch.check()
  assert.equal(failure.code, 'hostSuspended')
  assert.equal(failure.stopReason, 'startupFailed')
  assert.match(failure.message, /suspended for 422 s/)
  // A failure that ends an interval spanning a suspension is the suspension's; one that does not
  // is its own (rehearsal 20: the wake aborted the run as a player leaving).
  const left = new Error('a managed player left the server during the run')
  now += 250
  assert.equal(watch.explain(left), left)
  now += 941_000
  assert.equal(watch.explain(left).code, 'hostSuspended')
  // The poll loop throws the suspension it sees, before it reads any more evidence.
  const harness = fs.readFileSync(path.join(__dirname, '..', 'lib', 'harness.js'), 'utf8')
  assert.match(harness, /const suspended = explained\(null\)\n\s+if \(suspended !== null\) throw suspended/)
  // A suspension that spans the deadline ends the loop by its clock, so the loop's end asks the
  // watch before admission closes, and the drain asks it at every poll and explains its failure
  // (r37: a 265 s idle sleep 11 s before the window's end failed the drain as upstream unavailable).
  assert.match(harness, /const suspendedAtEnd = explained\(null\)\n\s+if \(suspendedAtEnd !== null\) throw suspendedAtEnd\n\s+if \(controlledStop === undefined && isPrimaryBehaviorMode\(config\)\) \{\n\s+pollEvidence\(\)\n\s+proxy\.closeAdmission/)
  assert.match(harness, /await drainControlledStop\(\{\n\s+proxy,\n\s+poll: \(\) => explained\(null\) \?\? pollEvidence\(\),[\s\S]{0,400}\} catch \(cause\) \{\n\s+throw explained\(cause\)/)
})

test('a suspension the drain sees ends it as the suspension, not as its own timeout', async () => {
  const { drainControlledStop } = require('../lib/harness.js')
  const { SuspensionWatch } = require('../lib/paper-runtime.js')
  let now = 0
  const watch = new SuspensionWatch({ now: () => now })
  const proxy = { snapshot: () => ({ outstandingCalls: 1 }) }
  // r37's call was still with the mind when the host woke 265 s later.
  now += 265_000
  await assert.rejects(
    drainControlledStop({ proxy, poll: () => watch.check(), settlement: { unsettled: new Set() }, timeoutMilliseconds: 1_000 }),
    (failure) => failure.code === 'hostSuspended' && /suspended for 265 s/.test(failure.message)
  )
})

test('the harness holds the host awake on macOS for as long as it runs', async () => {
  const { EventEmitter } = require('node:events')
  const { holdIdleSleepAssertion } = require('../lib/harness.js')
  const spawned = []
  const child = () => Object.assign(new EventEmitter(), {
    exitCode: null,
    signalCode: null,
    killed: [],
    kill (signal) { this.killed.push(signal) }
  })
  const starting = (fake) => (command, args, options) => {
    spawned.push({ command, args, options })
    setImmediate(() => fake.emit('spawn'))
    return fake
  }
  // An idle-sleep assertion scoped to this process, so it ends with the harness however it ends.
  const held = child()
  const release = await holdIdleSleepAssertion({ platform: 'darwin', spawnProcess: starting(held), pid: 4242 })
  assert.deepEqual(spawned, [{ command: '/usr/bin/caffeinate', args: ['-i', '-w', '4242'], options: { stdio: 'ignore' } }])
  release()
  assert.deepEqual(held.killed, ['SIGTERM'])
  // An assertion that cannot be held stops the run before anything is built.
  const failing = child()
  await assert.rejects(
    holdIdleSleepAssertion({
      platform: 'darwin',
      spawnProcess: () => {
        setImmediate(() => failing.emit('error', new Error('spawn /usr/bin/caffeinate ENOENT')))
        return failing
      },
      pid: 4242
    }),
    /the idle-sleep assertion could not be held: spawn \/usr\/bin\/caffeinate ENOENT/
  )
  // Elsewhere nothing is spawned.
  spawned.length = 0
  const none = await holdIdleSleepAssertion({ platform: 'linux', spawnProcess: starting(child()), pid: 4242 })
  none()
  assert.deepEqual(spawned, [])
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

test('the JVM is told the ceilings and plan its call reserve keeps the living\'s reviews within', () => {
  const { followUpsEnvironmentOf } = require('../lib/harness.js')
  const config = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'config', 'tribe-held-objective-10m-10.json'), 'utf8'))
  // r20 spent its whole ceiling of 150 on the night's danger before its last reviews, and r22's
  // fixed shares of 15 stranded the dead's calls: the JVM keeps the pooled ceilings instead.
  assert.deepEqual(followUpsEnvironmentOf(config), {
    LOOM_GOAL_FOLLOW_UPS_PER_REVIEW: '4',
    LOOM_COGNITION_CALLS_PER_AGENT: '40',
    LOOM_COGNITION_CALLS_GLOBAL: '378',
    LOOM_COGNITION_AGENTS: '10',
    LOOM_EXPECTED_REVIEWS_PER_AGENT: '5',
    LOOM_COGNITION_CALLS_PIVOTAL: '130'
  })
})

test('a perception answered after its agent died ends once with a discard or an unavailable answer', () => {
  // r21 gamma: its damaged perception at tick 6683 was answered 2.3 s later, after the death,
  // and the decision recorded for it stopped the run. The JVM now records no decision for a
  // dead agent, only the discard or the unavailable answer that ends the perception.
  const { deathViolation } = require('../lib/witnessed-death.js')
  const died = { event: 'died', agentId: 'tribe-alpha', tick: 900 }
  const perceived = (requestId) => ({ event: 'perceived', agentId: 'tribe-alpha', requestId })
  const ended = (event, requestId, outcome) => ({ event, agentId: 'tribe-alpha', requestId, outcome })
  const orderings = [
    [[perceived('p1'), died, ended('decisionDiscarded', 'p1')], null],
    [[perceived('p1'), died, ended('cognitionUnavailable', 'p1', 'agentDead')], null],
    [[perceived('p1'), died, { event: 'decided', agentId: 'tribe-alpha', requestId: 'p1' }], /recorded decided after its witnessed death/],
    [[perceived('p1'), died, ended('decisionDiscarded', 'p1'), ended('cognitionUnavailable', 'p1')],
      /recorded cognitionUnavailable after its witnessed death/],
    [[perceived('p1'), ended('cognitionUnavailable', 'p1', 'dangerCoalesced'), died, ended('decisionDiscarded', 'p1')],
      /recorded decisionDiscarded after its witnessed death/],
    [[died, ended('cognitionUnavailable', 'p9')], /recorded cognitionUnavailable after its witnessed death/]
  ]
  for (const [records, expected] of orderings) {
    const violation = deathViolation(records)
    if (expected === null) assert.equal(violation, null, JSON.stringify(records))
    else assert.match(violation ?? '', expected, JSON.stringify(records))
  }
})

test('observer sessions are journalled for configured observers only, after Paper stopped', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-observer-import-'))
  const file = path.join(directory, 'observers.jsonl')
  const config = { ...proofConfig(), observers: ['nbdSteve'] }
  const uuid = '7c3a0f0e-0000-3000-8000-000000000000'
  writeJsonLines(file, [
    { event: 'observerJoined', tick: 40, username: 'nbdSteve', uuid },
    { event: 'observerLeft', tick: 90, username: 'nbdSteve', uuid }
  ])
  const events = []
  const journal = { append: (event, fields) => events.push({ event, ...fields }) }
  assert.equal(importObserverSessions({ config, file, journal }), 2)
  assert.deepEqual(events, [
    { event: 'observerJoined', serverTick: 40, username: 'nbdSteve', uuid },
    { event: 'observerLeft', serverTick: 90, username: 'nbdSteve', uuid }
  ])
  assert.equal(importObserverSessions({ config, file: path.join(directory, 'absent.jsonl'), journal }), 0)
  writeJsonLines(path.join(directory, 'stranger.jsonl'), [{ event: 'observerJoined', tick: 1, username: 'stranger', uuid }])
  assert.throws(
    () => importObserverSessions({ config, file: path.join(directory, 'stranger.jsonl'), journal }),
    /unconfigured observer/
  )
})
