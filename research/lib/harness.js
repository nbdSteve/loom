'use strict'

const fs = require('node:fs')
const net = require('node:net')
const os = require('node:os')
const path = require('node:path')
const { spawn, spawnSync } = require('node:child_process')

const {
  assertRunConfig,
  componentSourceHashes,
  configuredAgents,
  publishArtifact,
  validateProspectiveManifest,
  worstCaseCallNanoUsd,
  worstCaseTierNanoUsd
} = require('./artifact.js')
const { CognitionBudgetProxy } = require('./budget-proxy.js')
const {
  ADMISSION_CLOSED_BOUND,
  ADMISSION_CLOSED_EVENT,
  ADMISSION_ENDED_EVENT,
  BUDGET_STOP_BOUNDS,
  CONTROLLED_STOP_DRAIN_MILLIS,
  CONTROLLED_STOP_REASON,
  budgetStopFor,
  isProviderDrainFailure,
  isRecordedCognitionFailure,
  isRecordedFailureEnding,
  isRecordedFailureRequest,
  providerStopFor
} = require('./controlled-stop.js')
const { readJsonLines } = require('./evidence.js')
const {
  createInterventionSchedule,
  isPrimaryBehaviorMode,
  postCrashReserveFor,
  primaryBehaviorStimulusRecord,
  requestOriginFor
} = require('./experiment-mode.js')
const { writeFailureDiagnostic } = require('./failure-diagnostic.js')
const { JsonLinesTail, maximumOf } = require('./log-tail.js')
const { PaperClock } = require('./paper-clock.js')
const { SuspensionWatch, seedPaperRuntime } = require('./paper-runtime.js')
const { profileOf, ticksPerSecondOf } = require('./profiles.js')
const { DeathSettlement } = require('./witnessed-death.js')
const { INITIAL_CONDITIONS_EVENT, heldAtBegin } = require('./initial-conditions.js')
const {
  BoundedJournal,
  ProcessSupervisor,
  RunFailure,
  delay,
  signalProcessGroup,
  waitForCondition,
  waitForPort,
  waitForProcessGroupExit
} = require('./lifecycle.js')
const {
  commandOutput,
  readJson,
  sha256Buffer,
  sha256File
} = require('./util.js')

const STARTUP_EVENT = 'spawned'
const ALL_AGENTS_DIED = 'allAgentsDied'
const { isCoalesced, isPreempted } = require('./danger.js')
const PROVIDER_USAGE_SCHEMA_VERSION = 'loom-provider-usage/1'
const PAPER_DOWNLOAD_BYTES = 64 * 1024 * 1024
const PAPER_USER_AGENT = 'loom-experiment-harness/0.1.0 (https://github.com/nbdSteve/loom)'
const KEYCHAIN_SERVICE_PATTERN = /^[A-Za-z0-9._-]{1,100}$/
const RUNTIME_ENVIRONMENT_KEYS = [
  'HOME',
  'LANG',
  'LC_ALL',
  'LC_CTYPE',
  'LOGNAME',
  'PATH',
  'SystemRoot',
  'TMPDIR',
  'USER',
  'WINDIR'
]

function combinedOutput (command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd,
    env: options.env,
    encoding: 'utf8',
    timeout: options.timeout ?? 30_000
  })
  if (result.error !== undefined) throw result.error
  if (result.status !== 0) {
    throw new Error(`${path.basename(command)} exited ${result.status}`)
  }
  return `${result.stdout ?? ''}${result.stderr ?? ''}`.trim().replace(/\s+/g, ' ')
}

function javaMajor (java) {
  const output = combinedOutput(java, ['-version'])
  const match = /version "([0-9]+)/.exec(output)
  return match === null ? null : Number(match[1])
}

function conciseJavaVersion (output) {
  const banner =
    /^((?:openjdk|java) version "[^"]+"(?: [0-9]{4}-[0-9]{2}-[0-9]{2})?(?: LTS)?)/i.exec(output)
  return (banner?.[1] ?? output).slice(0, 200)
}

function findJava21 () {
  const candidates = []
  if (process.env.LOOM_JAVA_21) candidates.push(process.env.LOOM_JAVA_21)
  if (process.env.JAVA_HOME) candidates.push(path.join(process.env.JAVA_HOME, 'bin', 'java'))
  if (process.platform === 'darwin' && fs.existsSync('/usr/libexec/java_home')) {
    const home = commandOutput('/usr/libexec/java_home', ['-v', '21'])
    candidates.push(path.join(home, 'bin', 'java'))
  }
  candidates.push('java')
  for (const candidate of [...new Set(candidates)]) {
    try {
      if (javaMajor(candidate) === 21) {
        if (candidate.includes(path.sep)) return fs.realpathSync(path.resolve(candidate))
        const locator = process.platform === 'win32' ? 'where' : 'which'
        return fs.realpathSync(
          path.resolve(commandOutput(locator, [candidate]).split(/\r?\n/)[0])
        )
      }
    } catch {
      // Try the next explicit candidate.
    }
  }
  throw new Error('Java 21 was not found; set LOOM_JAVA_21 to its java executable')
}

// How long a build step's process group has to exit after SIGTERM before it is killed.
const BUILD_STOP_GRACE_MILLIS = 5_000

/**
 * Runs one build step as its own process group and, past its bound, a failure or an abort, stops
 * the whole group rather than only the process it started: a synchronous timeout signals that one
 * process, so the v35 shelter rehearsal's body check, cut at its bound on a loaded host, left its
 * test runner loading the host after the run had already failed.
 */
async function runBuild (command, args, { cwd, env, timeout, signal }) {
  signal?.throwIfAborted()
  const step = [command, ...args].join(' ')
  const child = spawn(command, args, { cwd, env, stdio: 'inherit', detached: process.platform !== 'win32' })
  const exited = new Promise((resolve, reject) => {
    child.once('error', reject)
    child.once('exit', (code, exitSignal) => resolve({ code, exitSignal }))
  })
  let reason
  let stopping
  const stop = (cause) => {
    reason ??= cause
    stopping ??= (async () => {
      signalProcessGroup(child, 'SIGTERM')
      if (!(await waitForProcessGroupExit(child, BUILD_STOP_GRACE_MILLIS))) signalProcessGroup(child, 'SIGKILL')
      await waitForProcessGroupExit(child, BUILD_STOP_GRACE_MILLIS)
    })()
    return stopping
  }
  const timer = setTimeout(() => stop(new Error(`${step} exceeded its ${timeout} ms build bound`)), timeout)
  const aborted = () => stop(signal.reason)
  signal?.addEventListener('abort', aborted, { once: true })
  try {
    const { code, exitSignal } = await exited
    if (reason === undefined && code === 0) return
    await stop(new Error(`${step} exited ${code ?? exitSignal}`))
    throw reason
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', aborted)
  }
}

async function buildComponents (repositoryRoot, java, skipBuild, signal) {
  const pluginJar = path.join(repositoryRoot, 'plugin', 'build', 'libs', 'loom-plugin-0.1.0.jar')
  const cognitionBinary = path.join(repositoryRoot, 'cognition', 'target', 'release', 'loom-cognition')
  if (!skipBuild) {
    await runBuild('npm', ['ci'], {
      cwd: path.join(repositoryRoot, 'body'),
      env: process.env,
      timeout: 180_000,
      signal
    })
    await runBuild('cargo', ['build', '--release', '--bin', 'loom-cognition'], {
      cwd: path.join(repositoryRoot, 'cognition'),
      env: process.env,
      timeout: 300_000,
      signal
    })
    const javaHome = path.dirname(path.dirname(path.resolve(java)))
    // CI tests every commit, so a run builds and does not test.
    await runBuild('./gradlew', ['shadowJar'], {
      cwd: path.join(repositoryRoot, 'plugin'),
      env: { ...process.env, JAVA_HOME: javaHome },
      timeout: 600_000,
      signal
    })
  }
  for (const file of [pluginJar, cognitionBinary]) {
    if (!fs.existsSync(file)) throw new Error(`required build output is missing: ${path.relative(repositoryRoot, file)}`)
  }
  const sourceHashes = componentSourceHashes(repositoryRoot)
  return {
    files: { pluginJar, cognitionBinary },
    hashes: {
      plugin: {
        buildSha256: sha256File(pluginJar),
        sourceSha256: sourceHashes.plugin
      },
      body: {
        buildSha256: sha256Buffer(Buffer.from(`${sourceHashes.body}\n${sha256File(path.join(repositoryRoot, 'body', 'package-lock.json'))}\n`)),
        sourceSha256: sourceHashes.body
      },
      cognition: {
        buildSha256: sha256File(cognitionBinary),
        sourceSha256: sourceHashes.cognition
      }
    }
  }
}

async function ensurePaper (repositoryRoot, config, suppliedFile) {
  const cached = path.join(
    repositoryRoot,
    '.scratch',
    'paper',
    `paper-${config.paper.minecraftVersion}-${config.paper.build}.jar`
  )
  const file = suppliedFile === undefined ? cached : path.resolve(suppliedFile)
  if (!fs.existsSync(file)) {
    if (suppliedFile !== undefined) throw new Error(`Paper jar does not exist: ${file}`)
    fs.mkdirSync(path.dirname(file), { recursive: true })
    const response = await fetch(config.paper.downloadUrl, {
      headers: { 'User-Agent': PAPER_USER_AGENT },
      redirect: 'follow',
      signal: AbortSignal.timeout(120_000)
    })
    if (!response.ok) throw new Error(`Paper download failed with HTTP ${response.status}`)
    const contentLength = Number(response.headers.get('content-length') ?? 0)
    if (contentLength > PAPER_DOWNLOAD_BYTES) throw new Error('Paper download exceeded the 64 MiB bound')
    if (response.body === null) throw new Error('Paper download had no response body')
    const reader = response.body.getReader()
    const chunks = []
    let bytes = 0
    while (true) {
      const next = await reader.read()
      if (next.done) break
      bytes += next.value.byteLength
      if (bytes > PAPER_DOWNLOAD_BYTES) {
        await reader.cancel()
        throw new Error('Paper download exceeded the 64 MiB bound')
      }
      chunks.push(Buffer.from(next.value))
    }
    const buffer = Buffer.concat(chunks, bytes)
    const temporary = `${file}.partial-${process.pid}`
    fs.writeFileSync(temporary, buffer, { flag: 'wx' })
    if (sha256File(temporary) !== config.paper.sha256) {
      fs.rmSync(temporary, { force: true })
      throw new Error('downloaded Paper jar did not match the pinned SHA-256')
    }
    fs.renameSync(temporary, file)
  }
  if (sha256File(file) !== config.paper.sha256) {
    throw new Error('Paper jar did not match the pinned SHA-256')
  }
  return file
}

function writeServerFiles ({ runDirectory, paperFile, pluginJar, config }) {
  fs.mkdirSync(path.join(runDirectory, 'plugins'), { recursive: true })
  fs.copyFileSync(paperFile, path.join(runDirectory, 'paper.jar'))
  fs.copyFileSync(pluginJar, path.join(runDirectory, 'plugins', 'Loom.jar'))
  fs.writeFileSync(path.join(runDirectory, 'eula.txt'), 'eula=true\n', { flag: 'wx' })
  const properties = [
    'accepts-transfers=false',
    'allow-flight=true',
    'enable-command-block=false',
    'enable-jmx-monitoring=false',
    'enable-query=false',
    'enable-rcon=false',
    'enforce-secure-profile=false',
    'force-gamemode=false',
    'gamemode=survival',
    'generate-structures=true',
    'hardcore=false',
    `difficulty=${config.world.difficulty}`,
    `level-seed=${config.world.seed}`,
    `max-world-size=${config.world.maxWorldSizeBlocks}`,
    'motd=Loom bounded tribe experiment',
    'online-mode=false',
    'prevent-proxy-connections=true',
    'rate-limit=0',
    `server-ip=${config.network.host}`,
    `server-port=${config.network.minecraftPort}`,
    `simulation-distance=${config.world.simulationDistanceChunks}`,
    'spawn-protection=0',
    `spawn-monsters=${config.world.mobSpawning}`,
    `view-distance=${config.world.viewDistanceChunks}`,
    'white-list=false'
  ]
  fs.writeFileSync(path.join(runDirectory, 'server.properties'), `${properties.join('\n')}\n`, { flag: 'wx' })
}

function bodyEvents (file) {
  if (!fs.existsSync(file)) return []
  const text = fs.readFileSync(file, 'utf8')
  const lastNewline = text.lastIndexOf('\n')
  if (lastNewline === -1) return []
  const complete = text.slice(0, lastNewline)
  if (complete.length === 0) return []
  return complete.split('\n').map((line) => JSON.parse(line))
}

function latestBodyTick (file) {
  const records = bodyEvents(file)
  return maximumOf(records.map((record) => record.tick))
}

function paperCommand (paper, command) {
  if (!paper.child.stdin?.writable) throw new RunFailure('paperFailed', 'Paper console is not writable')
  paper.child.stdin.write(`${command}\n`)
}

/**
 * The one step a primary behavior run's admission close ends its exposure with: the plugin's
 * `loombody end-exposure`, sent once, in the same turn the proxy closed admission, so nothing new is
 * damaged, killed, targeted or applied after it while admitted work drains (r34: delta and gamma
 * were shot dead in the drain after admission closed, and both deaths counted).
 */
function exposureEnder (paper, config) {
  let sent = false
  return () => {
    if (sent || !isPrimaryBehaviorMode(config)) return
    sent = true
    paperCommand(paper, 'loombody end-exposure')
  }
}

function applyPrimaryBehaviorInitialConditions (paper, config, journal) {
  if (!isPrimaryBehaviorMode(config)) return
  paperCommand(paper, 'gamerule doDaylightCycle true')
  paperCommand(paper, `time set ${config.primaryBehavior.initialWorldClock.timeOfDay}`)
  const positions = {}
  for (const agent of configuredAgents(config)) {
    const position = config.primaryBehavior.initialPositions[agent.agentId]
    positions[agent.agentId] = position
    paperCommand(paper, `clear ${agent.username}`)
    paperCommand(paper, `tp ${agent.username} ${position.join(' ')}`)
  }
  const ticksPerSecond = ticksPerSecondOf(profileOf(config.primaryBehavior.profile) ?? {})
  if (ticksPerSecond !== 20) {
    paperCommand(paper, `tick rate ${ticksPerSecond}`)
    journal.append('serverTickRateAttested', { ticksPerSecond })
  }
  journal.append('initialConditionsAttested', {
    positions,
    worldClock: config.primaryBehavior.initialWorldClock,
    startingInventories: config.primaryBehavior.startingInventories
  })
  // Paper runs console commands in order, so every condition above is in place first.
  paperCommand(paper, 'loombody begin')
}

/**
 * When the plugin's native cognition sources begin. A primary behavior run holds them until
 * its initial conditions are applied: the fleet starts two bodies at a time, so the first
 * agent's first scheduled review could otherwise arrive before the last agent had spawned.
 */
/**
 * The follow-ups each scheduled review earns, as the plan states them, for the JVM. A plan that
 * states none leaves the JVM's own allowance in place.
 */
function followUpsEnvironmentOf (config) {
  const schedule = config.primaryBehavior?.nativeGoalReviewSchedule
  const followUps = schedule?.followUpsPerReview
  const agents = Array.isArray(config.agents) ? config.agents.length : 0
  const global = config.cognition?.globalCallCeiling
  const perAgent = config.cognition?.perAgentCallCeiling
  // The run's ceilings, kept by the JVM for the scheduled reviews the living still owe: the
  // night's danger spent r20's whole ceiling before its last reviews, and r22's fixed shares
  // stranded the calls of the dead (`CallReserve`).
  const bounded = agents > 0 && Number.isInteger(global) && Number.isInteger(perAgent)
  return {
    ...(Number.isInteger(followUps) ? { LOOM_GOAL_FOLLOW_UPS_PER_REVIEW: String(followUps) } : {}),
    // A periodic schedule: one review per agent every `cadenceTicks`, with as many reviews in flight
    // as the agent's outstanding slots but one (`EmbodiedCognition.reviewsInFlight`).
    ...(Number.isInteger(schedule?.reviewsInFlightPerAgent)
      ? { LOOM_GOAL_REVIEW_CADENCE_TICKS: String(schedule.cadenceTicks) }
      : {}),
    ...(bounded && Number.isInteger(schedule?.expectedReviewsPerAgent)
      ? {
          LOOM_COGNITION_CALLS_PER_AGENT: String(perAgent),
          LOOM_COGNITION_CALLS_GLOBAL: String(global),
          LOOM_COGNITION_AGENTS: String(agents),
          LOOM_EXPECTED_REVIEWS_PER_AGENT: String(schedule.expectedReviewsPerAgent),
          ...(Number.isInteger(config.cognition?.pivotalCallCeiling)
            ? { LOOM_COGNITION_CALLS_PIVOTAL: String(config.cognition.pivotalCallCeiling) }
            : {})
        }
      : {})
  }
}

function nativeCognitionStartOf (config) {
  return isPrimaryBehaviorMode(config) ? 'held' : 'immediate'
}

// What ends a primary-behavior run because continuing is impossible or unsafe. A witness
// dispute, a disputed world interaction and a living agent's discarded decision are ordinary
// typed product faults instead: the run keeps them as evidence, readiness fails closed on them
// (a server dispute, a server truthfulness below 1, a `protocolError` action), and the run goes
// on to its duration.
const PRIMARY_BEHAVIOR_FATAL_EVENTS = new Map([
  ['bodyDisconnected', 'a body disconnected'],
  ['cognitionUnavailable', 'a cognition request became unavailable'],
  ['despawned', 'a body despawned during the run'],
  ['spawnRejected', 'a configured body spawn was rejected'],
  ['witnessDenied', 'the player witness denied a managed login'],
  ['witnessQuit', 'a managed player left the server during the run'],
  ['witnessRevived', 'the player witness observed a permadeath breach']
])

// Checks primary-behavior evidence incrementally, so each poll costs only the records
// appended since the last one. Feed harness records before the body records read in
// the same poll: the proxy journals a refusal before the body can record it.
class PrimaryBehaviorGuard {
  constructor (config) {
    this.config = config
    this.active = isPrimaryBehaviorMode(config)
    this.dead = new Set()
    this.settlement = new DeathSettlement()
    this.typed = new Set()
    this.recordedFailures = new Set()
    this.closed = false
    this.providerStopped = false
    this.firstPerceptionChecked = new Set()
    this.witnessedAtBegin = new Set()
    this.exposureEnded = new Set()
    this.failure = null
  }

  observeHarness (records) {
    if (!this.active) return
    for (const record of records) {
      if (record.event === ADMISSION_ENDED_EVENT) this.closed = true
      if (record.event === ADMISSION_CLOSED_EVENT && !this.closed) {
        this.closed = true
        if (record.kind === 'providerUnavailable') {
          this.providerStopped = true
          this.typed.add(`${record.agentId}:${record.requestId}`)
        }
      }
      // A call the same outage failed while it drained is part of the provider stop.
      if (this.providerStopped && isProviderDrainFailure(record)) this.typed.add(`${record.agentId}:${record.requestId}`)
      if (isRecordedFailureRequest(record)) this.recordedFailures.add(`${record.agentId}:${record.requestId}`)
      if (
        record.event === 'cognitionRefused' &&
        [...BUDGET_STOP_BOUNDS, ADMISSION_CLOSED_BOUND].includes(record.bound) &&
        record.forwarded === false
      ) {
        this.typed.add(`${record.agentId}:${record.requestId}`)
      }
    }
  }

  observeBody (records) {
    if (!this.active || this.failure !== null) return this.failure
    for (const record of records) {
      this.failure = this.check(record)
      if (this.failure !== null) return this.failure
    }
    return null
  }

  check (record) {
    if (record.event === 'exposureEnded') {
      this.exposureEnded.add(record.agentId)
      return null
    }
    if (record.event === INITIAL_CONDITIONS_EVENT) return this.beginFailure(record)
    // Admitted work that drained after the exposure ended ends typed, never applied.
    if (
      ['cognitionUnavailable', 'decisionDiscarded'].includes(record.event) &&
      record.outcome === 'exposureEnded' &&
      this.exposureEnded.has(record.agentId)
    ) return null
    const dead = this.dead.has(record.agentId)
    const refusedAfterDeath = this.settlement.observe(record, dead)
    if (dead) {
      if (refusedAfterDeath === null) return null
      return new RunFailure('startupFailed', `primary behavior run stopped because ${refusedAfterDeath}`)
    }
    if (record.event === 'died') {
      this.dead.add(record.agentId)
      return null
    }
    // A perception the JVM coalesced - into a danger episode, or into cognition already in
    // flight for that agent - was never sent.
    if (isCoalesced(record)) return null
    // A danger answer newer damage preempted is superseded, not lost: the newer one governs.
    if (isPreempted(record)) return null
    if (
      record.event === 'cognitionUnavailable' &&
      this.closed &&
      this.typed.has(`${record.agentId}:${record.requestId}`)
    ) return null
    // A recorded cognition failure ends as that perception's own typed failure; the run goes on.
    if (isRecordedFailureEnding(record, this.recordedFailures)) return null
    const detail = PRIMARY_BEHAVIOR_FATAL_EVENTS.get(record.event)
    if (detail !== undefined) {
      // A closed camelCase outcome tag says which kind; free text never reaches the message.
      const tag = typeof record.outcome === 'string' && /^[a-z][A-Za-z]{0,63}$/.test(record.outcome)
        ? ` (${record.outcome} at tick ${record.tick})`
        : ''
      return new RunFailure('startupFailed', `primary behavior run stopped because ${detail}${tag}`)
    }
    if (record.event === 'perceived' && !this.firstPerceptionChecked.has(record.agentId)) {
      this.firstPerceptionChecked.add(record.agentId)
      return this.firstPerceptionFailure(record)
    }
    return null
  }

  // The server's reading at begin is where the pinned conditions must hold, so one that does not
  // hold them stops the run there, and the agent's first perception is then held to its clock only.
  beginFailure (witnessed) {
    const expected = this.config.primaryBehavior.initialPositions[witnessed.agentId]
    if (expected === undefined) return null
    if (this.witnessedAtBegin.has(witnessed.agentId)) {
      return new RunFailure('startupFailed', `${witnessed.agentId} was read twice when the run began`)
    }
    this.witnessedAtBegin.add(witnessed.agentId)
    const entry = {
      outcome: witnessed.outcome,
      position: witnessed.initial?.position,
      inventoryStacks: witnessed.initial?.inventoryStacks
    }
    if (!heldAtBegin(entry, expected)) {
      return new RunFailure(
        'startupFailed',
        `${witnessed.agentId} did not hold its pinned initial conditions when the run began`
      )
    }
    return null
  }

  firstPerceptionFailure (first) {
    const expected = this.config.primaryBehavior.initialPositions[first.agentId]
    if (expected === undefined) return null
    const position = first.perception?.selfState?.position
    const inventory = first.perception?.selfState?.inventorySummary
    const worldClock = first.perceptionAudit?.worldClock
    if (this.witnessedAtBegin.has(first.agentId)) {
      if (worldClock === null || typeof worldClock !== 'object') {
        return new RunFailure('startupFailed', `${first.agentId}'s first perception stated no world clock`)
      }
      return null
    }
    if (
      !Array.isArray(position) ||
      position.length !== expected.length ||
      position.some((coordinate, index) => coordinate !== expected[index]) ||
      !Array.isArray(inventory) ||
      inventory.length !== 0 ||
      worldClock === null ||
      typeof worldClock !== 'object'
    ) {
      return new RunFailure(
        'startupFailed',
        `${first.agentId} lost a pinned initial condition before its first perception`
      )
    }
    return null
  }
}

function primaryBehaviorRunFailure (config, records, harnessRecords = []) {
  const guard = new PrimaryBehaviorGuard(config)
  guard.observeHarness(harnessRecords)
  return guard.observeBody(records)
}

// Tracks which perceived requests still lack a terminal body record.
class RequestSettlement {
  constructor () {
    this.unsettled = new Set()
    this.settled = new Set()
  }

  observe (records) {
    for (const record of records) {
      if (record.requestId === undefined || record.requestId === null) continue
      const key = `${record.agentId}:${record.requestId}`
      if (record.event === 'perceived' && !this.settled.has(key)) this.unsettled.add(key)
      if (['intentApplied', 'decisionDiscarded', 'cognitionUnavailable'].includes(record.event)) {
        this.settled.add(key)
        this.unsettled.delete(key)
      }
    }
  }
}

function bodyPidForPort (port) {
  let output
  try {
    output = combinedOutput('lsof', [`-tiTCP:${port}`, '-sTCP:LISTEN'])
  } catch {
    throw new RunFailure('startupFailed', `no body listener was found on control port ${port}`)
  }
  const pids = output.split(/\s+/).filter(Boolean)
  if (pids.length !== 1 || !/^[1-9][0-9]*$/.test(pids[0])) {
    throw new RunFailure('startupFailed', `control port ${port} did not resolve to one process`)
  }
  const pid = Number(pids[0])
  const command = combinedOutput('ps', ['-p', String(pid), '-o', 'command='])
  if (!command.includes('loom-body.js')) {
    throw new RunFailure('startupFailed', `control port ${port} was not owned by a Loom body`)
  }
  return pid
}

async function portIsOpen (host, port) {
  return await new Promise((resolve) => {
    const socket = net.createConnection({ host, port })
    const finish = (open) => {
      socket.destroy()
      resolve(open)
    }
    socket.setTimeout(250)
    socket.once('connect', () => finish(true))
    socket.once('error', () => finish(false))
    socket.once('timeout', () => finish(false))
  })
}

async function verifyNoListeners (config, phase = 'cleanup') {
  const ports = [
    config.network.minecraftPort,
    config.network.cognitionProxyPort,
    config.network.cognitionUpstreamPort,
    ...configuredAgents(config).map((agent) => agent.controlPort)
  ]
  const stillOpen = []
  for (const port of ports) {
    if (await portIsOpen(config.network.host, port)) stillOpen.push(port)
  }
  if (stillOpen.length > 0) {
    const detail = phase === 'startup'
      ? 'configured ports were already in use before startup'
      : 'listeners remained after cleanup'
    throw new RunFailure('startupFailed', `${detail} on ${stillOpen.join(',')}`)
  }
}

// Paper, its world, the JVM, every bounded log, and publication all write to this
// volume; running out mid-run loses the run, so refuse to start without headroom.
const MIN_FREE_DISK_BYTES = 2 * 1024 * 1024 * 1024

function requiredFreeDiskBytes (config) {
  const logs = config.bounds.logBytes * (configuredAgents(config).length + 3)
  return Math.max(MIN_FREE_DISK_BYTES, 4 * logs + 2 * config.bounds.artifactBytes)
}

function verifyFreeDisk (directory, config, statfs = fs.statfsSync) {
  const stats = statfs(directory)
  const available = Number(stats.bavail) * Number(stats.bsize)
  const required = requiredFreeDiskBytes(config)
  if (!(available >= required)) {
    throw new RunFailure(
      'diskFailure',
      `only ${Math.floor(available / 1024 / 1024)} MiB is free on the run volume; ` +
        `${Math.ceil(required / 1024 / 1024)} MiB is required before startup`
    )
  }
  return { available, required }
}

function runtimeEnvironment () {
  return Object.fromEntries(
    RUNTIME_ENVIRONMENT_KEYS
      .filter((name) => process.env[name] !== undefined)
      .map((name) => [name, process.env[name]])
  )
}

function systemKeychainPassword (service) {
  const result = spawnSync(
    'security',
    ['find-generic-password', '-s', service, '-w'],
    {
      env: runtimeEnvironment(),
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
      timeout: 30_000
    }
  )
  if (result.error !== undefined || result.status !== 0) {
    throw new RunFailure('startupFailed', 'the approved OpenAI Keychain credential could not be read')
  }
  return result.stdout
}

function keychainCredentialProvider (service, lookup = systemKeychainPassword) {
  if (typeof service !== 'string' || !KEYCHAIN_SERVICE_PATTERN.test(service)) {
    throw new Error('Keychain service must contain 1..100 letters, digits, dots, underscores, or hyphens')
  }
  let consumed = false
  return () => {
    if (consumed) {
      throw new RunFailure('startupFailed', 'the approved OpenAI Keychain credential was requested more than once')
    }
    consumed = true
    let value
    try {
      value = lookup(service)
    } catch (cause) {
      if (cause instanceof RunFailure) throw cause
      throw new RunFailure('startupFailed', 'the approved OpenAI Keychain credential could not be read')
    }
    const credential = typeof value === 'string' ? value.trim() : ''
    if (credential.length === 0) {
      throw new RunFailure('startupFailed', 'the approved OpenAI Keychain credential was empty')
    }
    return credential
  }
}

function cognitionEnvironment (config, usageFile, credentialProvider) {
  const environment = {
    ...runtimeEnvironment(),
    LOOM_COGNITION_BACKEND: config.cognition.backend,
    LOOM_COGNITION_ADDR: `${config.network.host}:${config.network.cognitionUpstreamPort}`,
    LOOM_OPENAI_FAST_MODEL: config.cognition.models.ordinary,
    LOOM_OPENAI_DEEP_MODEL: config.cognition.models.pivotal,
    LOOM_OPENAI_TIMEOUT_MS: String(config.cognition.providerTimeoutMillis),
    LOOM_OPENAI_MAX_OUTPUT_TOKENS: String(config.cognition.maxOutputTokens),
    LOOM_OPENAI_MAX_REQUEST_BYTES: String(config.cognition.maxRequestBytes)
  }
  if (config.cognition.backend === 'openai') {
    const credential = (
      credentialProvider === undefined
        ? process.env.OPENAI_API_KEY
        : credentialProvider()
    )?.trim()
    if (credential === undefined || credential.length === 0) {
      throw new RunFailure('startupFailed', 'OPENAI_API_KEY is not available to the cognition process')
    }
    environment.OPENAI_API_KEY = credential
    environment.LOOM_OPENAI_BASE_URL = config.cognition.providerEndpoint
    environment.LOOM_OPENAI_USAGE_LOG = usageFile
  }
  return environment
}

function authenticationFailure (message, classified) {
  if (
    classified?.mapping === 'providerAuthentication' &&
    (classified.httpStatus === 401 || classified.httpStatus === 403)
  ) {
    return new RunFailure(
      'cognitionFailed',
      `the model provider rejected Loom's credential (HTTP ${classified.httpStatus}); check OPENAI_API_KEY`,
      'providerAuthenticationRejected'
    )
  }
  if (typeof message !== 'string') return null
  const match = /model provider rejected Loom's credential \(HTTP (401|403)(?:[,)])/.exec(message)
  if (match === null) return null
  return new RunFailure(
    'cognitionFailed',
    `the model provider rejected Loom's credential (HTTP ${match[1]}); check OPENAI_API_KEY`,
    'providerAuthenticationRejected'
  )
}

/**
 * What one cognition failure does to a run: a rejected credential ends it; in primary behavior
 * mode a classified provider outage stops admission, an unsafe failure ends it, and any other is
 * recorded as that request's own failure while the run continues (`isRecordedCognitionFailure`).
 * `null` when the run goes on.
 */
function cognitionFailureAction (config, message, classified, request) {
  const failure = authenticationFailure(message, classified)
  if (failure !== null) return { abort: failure }
  if (!isPrimaryBehaviorMode(config)) return null
  const providerStop = request === undefined ? null : providerStopFor(request, classified)
  if (providerStop !== null) return { stop: providerStop }
  if (isRecordedCognitionFailure(message, classified)) return null
  return {
    abort: new RunFailure(
      'cognitionFailed',
      `primary behavior cognition failed unsafely (${classified?.mapping ?? 'unclassified'})`,
      'cognitionDecisionFailed'
    )
  }
}

function budgetRefusalFailure (refusal) {
  const origin = refusal.origin ?? 'unattested'
  return new RunFailure(
    'cognitionFailed',
    `primary behavior cognition for ${refusal.agentId} (${origin}) was refused by the ` +
      `${refusal.bound} bound at ${refusal.observed} of ${refusal.ceiling} ${refusal.unit}`,
    'cognitionBudgetRefused'
  )
}

const OBSERVER_EVENTS = new Set(['observerJoined', 'observerLeft'])

// Read after Paper has stopped, so the leave its shutdown kick wrote is included.
function importObserverSessions ({ config, file, journal }) {
  if (!fs.existsSync(file)) return 0
  const observers = new Set((config.observers ?? []).map((name) => name.toLowerCase()))
  const records = readJsonLines(file, {
    maxBytes: config.bounds.logBytes,
    maxRecords: config.bounds.evidenceRecords,
    maxLineBytes: 1024
  })
  for (const record of records) {
    if (
      !OBSERVER_EVENTS.has(record?.event) ||
      !Number.isInteger(record.tick) ||
      typeof record.username !== 'string' ||
      !observers.has(record.username.toLowerCase()) ||
      typeof record.uuid !== 'string'
    ) {
      throw new RunFailure('paperFailed', 'the observer log named an unconfigured observer')
    }
    journal.append(record.event, { serverTick: record.tick, username: record.username, uuid: record.uuid })
  }
  return records.length
}

function importProviderUsage ({ config, file, journal }) {
  if (config.cognition.backend !== 'openai') return 0
  if (!fs.existsSync(file)) {
    throw new RunFailure('cognitionFailed', 'the cognition process produced no provider usage journal')
  }
  const records = readJsonLines(file, {
    maxBytes: config.bounds.logBytes,
    maxRecords: config.bounds.evidenceRecords,
    maxLineBytes: 16 * 1024
  })
  const allowedAgents = new Set(config.agents)
  const configuredModels = new Set([
    config.cognition.models.ordinary,
    config.cognition.models.pivotal
  ])
  const requests = new Set()
  for (const record of records) {
    if (
      record?.schemaVersion !== PROVIDER_USAGE_SCHEMA_VERSION ||
      !allowedAgents.has(record.agentId) ||
      !configuredModels.has(record.model) ||
      typeof record.requestId !== 'string' ||
      record.requestId.length === 0
    ) {
      throw new RunFailure('cognitionFailed', 'the provider usage journal named an unknown request')
    }
    for (const field of [
      'promptTokens',
      'cachedPromptTokens',
      'completionTokens',
      'totalTokens'
    ]) {
      if (!Number.isSafeInteger(record[field]) || record[field] < 0) {
        throw new RunFailure('cognitionFailed', 'the provider usage journal contained an invalid token count')
      }
    }
    if (
      record.cachedPromptTokens > record.promptTokens ||
      record.promptTokens + record.completionTokens !== record.totalTokens ||
      record.promptTokens > config.cognition.maxRequestBytes ||
      record.completionTokens > config.cognition.maxOutputTokens
    ) {
      throw new RunFailure('cognitionFailed', 'provider usage crossed or contradicted a configured token bound')
    }
    const requestKey = `${record.agentId}\0${record.requestId}`
    if (requests.has(requestKey)) {
      throw new RunFailure('cognitionFailed', 'one cognition request produced duplicate provider usage')
    }
    requests.add(requestKey)
    const rates = config.cognition.pricing.models[record.model]
    const uncachedPromptTokens = record.promptTokens - record.cachedPromptTokens
    const costNanoUsd =
      (uncachedPromptTokens * rates.inputNanoUsdPerToken) +
      (record.cachedPromptTokens * rates.cachedInputNanoUsdPerToken) +
      (record.completionTokens * rates.outputNanoUsdPerToken)
    if (!Number.isSafeInteger(costNanoUsd)) {
      throw new RunFailure('cognitionFailed', 'provider usage cost was not safely representable')
    }
    journal.append('providerUsage', {
      agentId: record.agentId,
      requestId: record.requestId,
      model: record.model,
      promptTokens: record.promptTokens,
      cachedPromptTokens: record.cachedPromptTokens,
      completionTokens: record.completionTokens,
      totalTokens: record.totalTokens,
      costNanoUsd
    })
  }
  return records.length
}

function toolVersions (java, repositoryRoot) {
  const javaHome = path.dirname(path.dirname(path.resolve(java)))
  const gradleOutput = combinedOutput('./gradlew', ['--version'], {
    cwd: path.join(repositoryRoot, 'plugin'),
    env: { ...runtimeEnvironment(), JAVA_HOME: javaHome }
  })
  const gradleVersion = /\bGradle ([^\s]+)/.exec(gradleOutput)?.[1]
  if (gradleVersion === undefined) throw new Error('Gradle did not report its version')
  return {
    os: `${os.platform()} ${os.release()}`,
    architecture: os.arch(),
    java: conciseJavaVersion(combinedOutput(java, ['-version'])),
    node: process.version,
    npm: combinedOutput('npm', ['--version']),
    rustc: combinedOutput('rustc', ['--version']),
    cargo: combinedOutput('cargo', ['--version']),
    git: combinedOutput('git', ['--version']),
    lsof: combinedOutput('lsof', ['-v']),
    gradle: `Gradle ${gradleVersion}`
  }
}

function sourceIdentity (repositoryRoot) {
  const dirty = commandOutput('git', ['status', '--porcelain'], { cwd: repositoryRoot })
  if (dirty !== '') throw new Error('the experiment must run from a clean checkout')
  const repository = commandOutput('git', ['remote', 'get-url', 'origin'], { cwd: repositoryRoot })
  if (path.isAbsolute(repository) || repository.startsWith('file:')) {
    throw new Error('the source repository remote must not publish a local filesystem path')
  }
  if (/^[A-Za-z][A-Za-z0-9+.-]*:\/\//.test(repository)) {
    const parsed = new URL(repository)
    if (parsed.username !== '' || parsed.password !== '') {
      throw new Error('the source repository remote must not contain credentials')
    }
  }
  return {
    commit: commandOutput('git', ['rev-parse', 'HEAD'], { cwd: repositoryRoot }),
    repository
  }
}

// macOS's own helper for a power assertion scoped to one process.
const CAFFEINATE = '/usr/bin/caffeinate'

/**
 * Holds macOS's idle-sleep assertion until the returned release is called or this process exits,
 * so an idle host cannot suspend a run: r37 was cut 11 s before its window's end by a 265 s idle
 * sleep. A closed lid still sleeps the host, which no assertion prevents, and the suspension watch
 * ends such a run as `hostSuspended`. Elsewhere it holds nothing.
 */
async function holdIdleSleepAssertion ({ platform = process.platform, spawnProcess = spawn, pid = process.pid } = {}) {
  if (platform !== 'darwin') return () => {}
  const child = spawnProcess(CAFFEINATE, ['-i', '-w', String(pid)], { stdio: 'ignore' })
  try {
    await new Promise((resolve, reject) => {
      child.once('spawn', resolve)
      child.once('error', reject)
    })
  } catch (cause) {
    throw new Error(`the idle-sleep assertion could not be held: ${cause.message}`)
  }
  return () => {
    if (child.exitCode === null && child.signalCode === null) child.kill('SIGTERM')
  }
}

/** Runs one experiment with the host held awake for the whole of it, builds included. */
async function runExperiment (options) {
  const release = await holdIdleSleepAssertion()
  try {
    return await runHeldExperiment(options)
  } finally {
    release()
  }
}

async function runHeldExperiment ({
  repositoryRoot,
  configFile,
  artifactRoot,
  paperJar,
  skipBuild = false,
  credentialProvider,
  failureDiagnosticRoot,
  signal,
  statfs = fs.statfsSync
}) {
  const absoluteRoot = path.resolve(repositoryRoot)
  const config = readJson(path.resolve(configFile))
  assertRunConfig(config)
  config.configFile = path.resolve(configFile)
  const source = sourceIdentity(absoluteRoot)
  validateProspectiveManifest(config, source)
  const scratchRoot = path.join(absoluteRoot, '.scratch', 'tribe-runs')
  fs.mkdirSync(scratchRoot, { recursive: true })
  // Before any build, Paper, listener or credential: nothing has been spent yet.
  verifyFreeDisk(scratchRoot, config, statfs)
  const java = findJava21()
  const components = await buildComponents(absoluteRoot, java, skipBuild, signal)
  const paperFile = await ensurePaper(absoluteRoot, config, paperJar)
  await verifyNoListeners(config, 'startup')

  verifyFreeDisk(scratchRoot, config, statfs)
  const runDirectory = fs.mkdtempSync(path.join(scratchRoot, `${config.experimentId}-`))
  const rawHarnessEvidence = path.join(runDirectory, 'harness-events.jsonl')
  const rawBodyEvidence = path.join(runDirectory, 'plugins', 'Loom', 'body-events.jsonl')
  const rawProviderUsage = path.join(runDirectory, 'provider-usage.jsonl')
  const diagnosticRoot = failureDiagnosticRoot === undefined
    ? path.join(absoluteRoot, '.scratch', 'tribe-failures')
    : path.resolve(failureDiagnosticRoot)
  const controller = new AbortController()
  const abortFromOutside = () => controller.abort(signal.reason ?? new RunFailure('cancelled', 'run cancelled'))
  if (signal?.aborted) abortFromOutside()
  signal?.addEventListener('abort', abortFromOutside, { once: true })
  const abort = (failure) => {
    if (!controller.signal.aborted) controller.abort(failure)
  }
  let journal
  let supervisor
  let proxy
  let startedAt
  let endedAt
  let stopReason = 'durationElapsed'
  let published
  let clean = false
  let failure
  let controlledStop
  // Ends the run's exposure in the same turn cognition admission closes; assigned once Paper runs.
  let endExposure = () => {}
  const stopAdmission = (stop) => {
    try {
      if (proxy.closeAdmission(stop)) {
        controlledStop = stop
        endExposure()
      }
    } catch (cause) {
      abort(cause instanceof RunFailure
        ? cause
        : new RunFailure('diskFailure', 'the controlled stop could not be journalled'))
    }
  }
  const throwRetainedFailure = (runFailure) => {
    let retained
    let retentionFailed = false
    let proxySnapshot
    try {
      proxySnapshot = proxy?.snapshot()
    } catch {
      proxySnapshot = undefined
    }
    try {
      retained = writeFailureDiagnostic({
        repositoryRoot: absoluteRoot,
        diagnosticRoot,
        config,
        source,
        failure: runFailure,
        startedAt,
        endedAt,
        cleanupVerified: clean,
        bodyFile: rawBodyEvidence,
        harnessFile: rawHarnessEvidence,
        providerUsageFile: rawProviderUsage,
        proxySnapshot
      })
    } catch {
      retentionFailed = true
    } finally {
      fs.rmSync(runDirectory, { recursive: true, force: true })
    }
    if (retentionFailed) {
      throw new RunFailure(
        runFailure.stopReason,
        `${runFailure.message}; sanitized failed-run diagnostic could not be retained`,
        runFailure.code
      )
    }
    const kind = retained.fallback
      ? `minimal fallback diagnostic (${retained.failedStage} stage failed)`
      : 'sanitized failed-run diagnostic'
    throw new RunFailure(
      runFailure.stopReason,
      `${runFailure.message}; ${kind} retained at ${retained.label}`,
      runFailure.code
    )
  }
  try {
    // Paper starts from the verified runtime cache rather than downloading and remapping. No host
    // state - load, swap, memory pressure, battery, lid, display or power - delays or refuses a run.
    writeServerFiles({
      runDirectory,
      paperFile,
      pluginJar: components.files.pluginJar,
      config
    })
    await seedPaperRuntime({ repositoryRoot: absoluteRoot, paperFile, config, java, runDirectory })
    journal = new BoundedJournal(rawHarnessEvidence, config.bounds.logBytes)
    supervisor = new ProcessSupervisor({
      logBytes: config.bounds.logBytes,
      journal,
      onFailure: abort
    })
    journal.append('preflightValidated', {
      experimentId: config.experimentId,
      schemaVersion: 'loom-experiment-manifest/3'
    })
    const cognition = supervisor.start({
      name: 'cognition',
      command: components.files.cognitionBinary,
      cwd: path.join(absoluteRoot, 'cognition'),
      env: cognitionEnvironment(config, rawProviderUsage, credentialProvider),
      logFile: path.join(runDirectory, 'cognition.log')
    })
    const startupDeadline = Date.now() + config.bounds.startupSeconds * 1000
    const startupRemaining = () => {
      const remaining = startupDeadline - Date.now()
      if (remaining <= 0) throw new RunFailure('startupFailed', 'the shared startup deadline elapsed')
      return remaining
    }
    await waitForPort({
      host: config.network.host,
      port: config.network.cognitionUpstreamPort,
      timeoutMilliseconds: startupRemaining(),
      signal: controller.signal
    })
    proxy = new CognitionBudgetProxy({
      listen: {
        host: config.network.host,
        port: config.network.cognitionProxyPort
      },
      upstream: {
        host: config.network.host,
        port: config.network.cognitionUpstreamPort
      },
      agents: config.agents,
      budget: config.cognition,
      models: config.cognition.models,
      postCrashReserve: postCrashReserveFor(config),
      requestOrigin: (trigger) => requestOriginFor(config, trigger),
      spend: {
        ceilingNanoUsd: Math.round(config.cognition.maxExternalSpendUsd * 1_000_000_000),
        worstCaseCallNanoUsd: worstCaseCallNanoUsd(config),
        ...(Number.isInteger(config.cognition.pivotalCallCeiling) ? { tier: worstCaseTierNanoUsd(config) } : {})
      },
      journal,
      onCognitionFailure: (message, classified, request) => {
        const action = cognitionFailureAction(config, message, classified, request)
        if (action?.abort !== undefined) {
          abort(action.abort)
        } else if (action?.stop !== undefined) {
          stopAdmission(action.stop)
        }
      },
      onRefusal: (refusal) => {
        if (!isPrimaryBehaviorMode(config) || refusal.bound === ADMISSION_CLOSED_BOUND) return
        const budgetStop = budgetStopFor(refusal)
        if (budgetStop === null) {
          abort(budgetRefusalFailure(refusal))
        } else {
          stopAdmission(budgetStop)
        }
      },
      onUnavailable: () => {
        if (isPrimaryBehaviorMode(config)) {
          abort(new RunFailure(
            'cognitionFailed',
            'primary behavior cognition upstream became unavailable',
            'cognitionUpstreamUnavailable'
          ))
        }
      }
    })
    await proxy.start()
    journal.append('cognitionProxyReady', {
      callCeilings: {
        perAgent: config.cognition.perAgentCallCeiling,
        global: config.cognition.globalCallCeiling,
        outstanding: config.cognition.globalOutstandingCeiling
      }
    })

    const javaHome = path.dirname(path.dirname(path.resolve(java)))
    const paper = supervisor.start({
      name: 'paper',
      command: java,
      args: [
        `-Xms${Math.min(512, config.runtime.paperMemoryMiB)}M`,
        `-Xmx${config.runtime.paperMemoryMiB}M`,
        '-jar',
        'paper.jar',
        '--nogui'
      ],
      cwd: runDirectory,
      env: {
        ...runtimeEnvironment(),
        JAVA_HOME: javaHome,
        LOOM_BODY_DIR: path.join(absoluteRoot, 'body'),
        LOOM_BODY_AGENTS: config.agents.join(','),
        LOOM_BODY_MC_HOST: config.network.host,
        LOOM_BODY_MC_PORT: String(config.network.minecraftPort),
        LOOM_BODY_MC_VERSION: config.paper.minecraftVersion,
        LOOM_BODY_CONTROL_HOST: config.network.host,
        LOOM_BODY_CONTROL_PORT: String(config.network.bodyControlBasePort),
        LOOM_BODY_STARTUP_CONCURRENCY: String(config.bounds.startupConcurrency),
        LOOM_BODY_STARTUP_ATTEMPTS: String(config.bounds.startupAttempts),
        LOOM_BODY_STARTUP_RETRY_MS: String(config.runtime.body.startupRetryMillis),
        LOOM_BODY_HEALTH_INTERVAL_TICKS: String(config.runtime.body.healthCheckIntervalTicks),
        LOOM_BODY_HEALTH_FAILURES: String(config.runtime.body.healthFailureLimit),
        LOOM_BODY_IO_WORKERS_PER_AGENT: String(config.runtime.body.ioWorkersPerAgent),
        LOOM_BODY_PROCESS_ROOT: path.join(runDirectory, 'plugins', 'Loom', 'bodies'),
        LOOM_COGNITION_HOST: config.network.host,
        LOOM_COGNITION_PORT: String(config.network.cognitionProxyPort),
        LOOM_COGNITION_TIMEOUT_MS: String(config.runtime.cognitionBridge.timeoutMillis),
        LOOM_COGNITION_CONNECT_TIMEOUT_MS: String(config.runtime.cognitionBridge.connectTimeoutMillis),
        LOOM_COGNITION_WORKERS: String(config.runtime.cognitionBridge.workers),
        LOOM_COGNITION_QUEUE_DEPTH: String(config.runtime.cognitionBridge.queueDepth),
        LOOM_COGNITION_PENDING_PER_AGENT: String(config.runtime.cognitionBridge.perAgentPending),
        LOOM_NATIVE_COGNITION: nativeCognitionStartOf(config),
        LOOM_ADMISSION: 'closed',
        LOOM_OBSERVERS: (config.observers ?? []).join(','),
        ...followUpsEnvironmentOf(config)
      },
      logFile: path.join(runDirectory, 'paper.log'),
      stdin: true
    })
    await waitForPort({
      host: config.network.host,
      port: config.network.minecraftPort,
      timeoutMilliseconds: startupRemaining(),
      signal: controller.signal
    })
    await waitForCondition({
      description: 'all configured bodies to spawn',
      timeoutMilliseconds: startupRemaining(),
      signal: controller.signal,
      poll: async () => {
        const spawned = new Set(
          bodyEvents(rawBodyEvidence)
            .filter((record) => record.event === STARTUP_EVENT)
            .map((record) => record.agentId)
        )
        return config.agents.every((agent) => spawned.has(agent))
      }
    })

    paperCommand(paper, `gamerule doMobSpawning ${config.world.mobSpawning}`)
    paperCommand(paper, `difficulty ${config.world.difficulty}`)
    paperCommand(paper, `worldborder set ${config.world.worldBorderDiameterBlocks}`)
    if (
      isPrimaryBehaviorMode(config) &&
      bodyEvents(rawBodyEvidence).some((record) => record.event === 'perceived')
    ) {
      throw new RunFailure(
        'startupFailed',
        'native cognition began before the primary behavior initial conditions were applied'
      )
    }
    applyPrimaryBehaviorInitialConditions(paper, config, journal)
    const startTick = latestBodyTick(rawBodyEvidence)
    journal.append('experimentStarted', {
      agents: [...config.agents].sort(),
      cognitionBackend: config.cognition.backend,
      mode: config.mode ?? 'crash-containment',
      startTick
    })
    startedAt = new Date().toISOString()
    const startedMillis = Date.now()
    const deadline = startedMillis + config.bounds.runDurationSeconds * 1000
    const interventions = createInterventionSchedule(config, startedMillis)
    let thinkCommandsIssued = 0
    const clock = isPrimaryBehaviorMode(config)
      ? new PaperClock({
        write: (command) => paperCommand(paper, command),
        logFile: path.join(runDirectory, 'paper.log')
      })
      : { poll: () => null }
    let gameStart = null
    let serverTick = startTick
    const bodyTail = new JsonLinesTail(rawBodyEvidence)
    const harnessTail = new JsonLinesTail(rawHarnessEvidence)
    const guard = new PrimaryBehaviorGuard(config)
    const settlement = new RequestSettlement()
    endExposure = exposureEnder(paper, config)
    const pollEvidence = () => {
      const body = bodyTail.poll()
      guard.observeHarness(harnessTail.poll())
      settlement.observe(body)
      return guard.observeBody(body)
    }
    const suspension = new SuspensionWatch()
    // Primary-behavior polls never wait inside the loop but for its own short delay, so a long gap
    // is the host itself: the run ends integrity-invalid as `hostSuspended` and is never published,
    // and whatever ended the interval it spans is the suspension's.
    const explained = (cause) => isPrimaryBehaviorMode(config) ? suspension.explain(cause) : cause
    while (Date.now() < deadline) {
      if (controller.signal.aborted) throw explained(controller.signal.reason)
      const suspended = explained(null)
      if (suspended !== null) throw suspended
      if (bodyTail.offset > config.bounds.logBytes) {
        throw new RunFailure('logLimit', 'body evidence exceeded the configured log byte bound')
      }
      const invalidPrimaryBehavior = pollEvidence()
      if (invalidPrimaryBehavior !== null) throw invalidPrimaryBehavior
      if (controlledStop !== undefined) {
        stopReason = CONTROLLED_STOP_REASON
        break
      }
      // Nothing is left to observe once every agent has died; the run ends and publishes.
      if (isPrimaryBehaviorMode(config) && config.agents.every((agentId) => guard.dead.has(agentId))) {
        stopReason = ALL_AGENTS_DIED
        break
      }
      const gameTime = clock.poll()
      if (gameTime !== null) gameStart ??= gameTime
      const elapsedTicks = gameTime === null ? 0 : gameTime - gameStart
      serverTick = Math.max(bodyTail.maxTick, startTick + elapsedTicks)
      if (Math.max(bodyTail.maxTick - startTick, elapsedTicks) >= config.bounds.simulationTickCeiling) {
        stopReason = 'simulationTickCeiling'
        break
      }
      const now = Date.now()
      for (const intervention of interventions.due(now)) {
        if (intervention.type === 'operatorGoalReview') {
          paperCommand(paper, intervention.command)
          thinkCommandsIssued += 1
          continue
        }
        const crashAgent = configuredAgents(config)
          .find((agent) => agent.agentId === intervention.agentId)
        if (crashAgent === undefined) {
          throw new RunFailure('startupFailed', 'configured crash agent is absent')
        }
        pollEvidence()
        const afterTick = bodyTail.maxTick
        const pid = bodyPidForPort(crashAgent.controlPort)
        process.kill(pid, intervention.signal)
        journal.append('controlledBodyCrash', {
          agentId: crashAgent.agentId,
          controlPort: crashAgent.controlPort,
          afterTick
        })
        await waitForCondition({
          description: 'the controlled body crash to be recorded',
          timeoutMilliseconds: Math.min(15_000, Math.max(1, deadline - Date.now())),
          signal: controller.signal,
          poll: async () =>
            bodyEvents(rawBodyEvidence).some(
              (record) =>
                record.agentId === crashAgent.agentId &&
                record.event === 'bodyDisconnected'
            )
        })
        proxy.markControlledBodyCrash(crashAgent.agentId)
      }
      try {
        await delay(Math.min(250, Math.max(1, deadline - Date.now())), controller.signal)
      } catch (cause) {
        throw explained(cause)
      }
    }
    // A suspension that spans the deadline ends the loop by its own clock before the loop could see
    // it, and the drain after it is the first to wake (r37: a 265 s idle sleep 11 s before the
    // window's end failed the drain as `cognitionUpstreamUnavailable`), so both ask the watch too.
    const suspendedAtEnd = explained(null)
    if (suspendedAtEnd !== null) throw suspendedAtEnd
    if (controlledStop === undefined && isPrimaryBehaviorMode(config)) {
      pollEvidence()
      proxy.closeAdmission({
        kind: 'runEnded',
        reason: stopReason,
        endTick: Math.max(bodyTail.maxTick, serverTick)
      }, ADMISSION_ENDED_EVENT)
      endExposure()
    }
    if (controlledStop !== undefined || isPrimaryBehaviorMode(config)) {
      if (controlledStop !== undefined) stopReason = CONTROLLED_STOP_REASON
      try {
        await drainControlledStop({
          proxy,
          poll: () => explained(null) ?? pollEvidence(),
          settlement,
          exposureRecorded: () => !isPrimaryBehaviorMode(config) || config.agents.every((agentId) => guard.exposureEnded.has(agentId)),
          signal: controller.signal
        })
      } catch (cause) {
        throw explained(cause)
      }
    }
    endedAt = new Date().toISOString()
    const invalidPrimaryBehavior = primaryBehaviorRunFailure(
      config,
      bodyEvents(rawBodyEvidence),
      bodyEvents(rawHarnessEvidence)
    )
    if (invalidPrimaryBehavior !== null) throw invalidPrimaryBehavior

    const stimulus = primaryBehaviorStimulusRecord(config, thinkCommandsIssued)
    if (stimulus !== undefined) {
      journal.append('cognitionStimulusAttested', stimulus)
      // The owed reviews depend on the ending, each agent's life span and the cadence, so the
      // publisher derives and validates them from the evidence rather than a fixed count here.
    } else {
      const crashAgent = config.proof.crashAgent
      const crashEvent = bodyEvents(rawHarnessEvidence)
        .find((record) => record.event === 'controlledBodyCrash')
      const peerDecisions = bodyEvents(rawBodyEvidence).filter(
        (record) =>
          record.event === 'decided' &&
          record.agentId !== crashAgent &&
          record.tick > crashEvent.afterTick
      )
      if (new Set(peerDecisions.map((record) => record.agentId)).size < 2) {
        throw new RunFailure('startupFailed', 'two peers did not decide after the controlled crash')
      }
    }
    journal.append('experimentEnded', {
      stopReason,
      endTick: Math.max(latestBodyTick(rawBodyEvidence), serverTick)
    })
    if (cognition.child.exitCode !== null) throw new RunFailure('cognitionFailed', 'cognition exited during the run')
  } catch (cause) {
    failure = cause instanceof RunFailure
      ? cause
      : new RunFailure('startupFailed', cause instanceof Error ? cause.message : 'run failed')
    stopReason = failure.stopReason
    if (journal !== undefined) {
      try {
        journal.append('runFailed', { stopReason })
      } catch {
        // A journal failure must not prevent process cleanup.
      }
    }
  } finally {
    let cleanupFailure
    const cleanupStep = async (step) => {
      try {
        await step()
      } catch (cause) {
        cleanupFailure ??= cause instanceof RunFailure
          ? cause
          : new RunFailure('startupFailed', 'cleanup could not be verified')
      }
    }
    // Before any process is signalled, so a call the stop cuts off is typed as the stop's.
    proxy?.beginStopping()
    await cleanupStep(async () => {
      if (supervisor !== undefined) await supervisor.stopAll(config.bounds.shutdownSeconds * 1000)
    })
    await cleanupStep(async () => {
      if (proxy !== undefined) await proxy.close()
    })
    if (journal !== undefined) {
      await cleanupStep(async () => importObserverSessions({
        config,
        file: path.join(runDirectory, 'plugins', 'Loom', 'observers.jsonl'),
        journal
      }))
    }
    if (failure === undefined && journal !== undefined) {
      await cleanupStep(async () => importProviderUsage({
        config,
        file: rawProviderUsage,
        journal
      }))
    }
    await cleanupStep(async () => verifyNoListeners(config))
    if (cleanupFailure === undefined && journal !== undefined) {
      await cleanupStep(async () => journal.append('cleanupVerified', { listeners: 'closed' }))
    }
    clean = cleanupFailure === undefined
    failure ??= cleanupFailure
    try {
      journal?.close()
    } catch {
      failure ??= new RunFailure('diskFailure', 'harness journal could not be closed')
    }
    signal?.removeEventListener('abort', abortFromOutside)
  }

  if (failure !== undefined) {
    throwRetainedFailure(failure)
  }
  try {
    if (!clean) throw new RunFailure('startupFailed', 'cleanup was not verified')
    published = publishArtifact({
      config,
      source,
      components: components.hashes,
      tools: toolVersions(java, absoluteRoot),
      startedAt,
      endedAt,
      stopReason,
      rawBodyEvidence,
      rawHarnessEvidence,
      artifactDirectory: path.join(path.resolve(artifactRoot), config.experimentId),
      warnings: paperWarnings(path.join(runDirectory, 'paper.log'))
    })
    return published
  } catch (cause) {
    const publicationFailure = cause instanceof RunFailure
      ? cause
      : new RunFailure(
        'diskFailure',
        cause instanceof Error ? cause.message : 'artifact publication failed'
      )
    throwRetainedFailure(publicationFailure)
  } finally {
    fs.rmSync(runDirectory, { recursive: true, force: true })
  }
}

// Lets already-forwarded work settle, inside one explicit bound, before shutdown, after
// a controlled stop or a normal primary-behavior ending closed admission.
async function drainControlledStop ({
  proxy,
  poll,
  settlement,
  exposureRecorded = () => true,
  signal,
  timeoutMilliseconds = CONTROLLED_STOP_DRAIN_MILLIS
}) {
  let invalid = null
  try {
    await waitForCondition({
      description: 'admitted cognition to settle',
      timeoutMilliseconds,
      signal,
      poll: async () => {
        invalid = poll()
        return invalid !== null ||
          (proxy.snapshot().outstandingCalls === 0 && settlement.unsettled.size === 0 && exposureRecorded())
      }
    })
  } catch (cause) {
    if (signal?.aborted) throw cause
    throw new RunFailure(
      'cognitionFailed',
      'already-forwarded cognition did not settle within the drain bound',
      'cognitionDrainTimedOut'
    )
  }
  if (invalid !== null) throw invalid
}

function paperWarnings (logFile) {
  if (!fs.existsSync(logFile)) return ['Paper log was unavailable to the harness warning scan.']
  const text = fs.readFileSync(logFile, 'utf8')
  const warnings = []
  if (/body event log.*(?:drop|fail)/i.test(text)) warnings.push('Paper reported a body event log drop or failure.')
  if (/player-deaths\.jsonl.*(?:fail|unreadable)/i.test(text)) warnings.push('Paper reported a death-ledger failure.')
  return warnings
}

module.exports = {
  followUpsEnvironmentOf,
  applyPrimaryBehaviorInitialConditions,
  authenticationFailure,
  bodyEvents,
  budgetRefusalFailure,
  buildComponents,
  conciseJavaVersion,
  PrimaryBehaviorGuard,
  cognitionFailureAction,
  RequestSettlement,
  drainControlledStop,
  exposureEnder,
  ensurePaper,
  findJava21,
  holdIdleSleepAssertion,
  importObserverSessions,
  importProviderUsage,
  keychainCredentialProvider,
  nativeCognitionStartOf,
  primaryBehaviorRunFailure,
  runBuild,
  runExperiment,
  sourceIdentity,
  toolVersions,
  verifyFreeDisk,
  verifyNoListeners,
  writeServerFiles
}
