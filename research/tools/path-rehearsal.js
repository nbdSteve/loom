#!/usr/bin/env node
'use strict'

// The path rehearsal: a deterministic live run of the goal paths no research run has reached.
//
// One real Paper 1.21.1 server with the Loom plugin and real Mineflayer bodies, on a flat world
// laid out by console commands (`tools/path-rehearsal.json`), with the cognition service answering
// as the path exerciser (`LOOM_COGNITION_BACKEND=scripted-paths`, `cognition/src/exerciser.rs`).
// Each agent walks one path; the server witnesses what each body did, and the run's own evidence,
// normalized and judged by readiness gate generation 10, must show every path as the exerciser
// drove it: a craft and a goal completed and the next adopted in one answer, an own drop ignored
// and refused, an own drop still told lying past 4800 ticks, a
// flight from a peer classed as one, a collect without moving that vanilla's own pickup ends, a
// walk to the edge of sight credited as revealing a source, and a revise to another item refused.
//
// It is not a research run: its world is built for the paths, its mind is scripted, and nothing
// is published. It writes under `.scratch/path-rehearsal/` and prints one report.
//
// Usage: node research/tools/path-rehearsal.js [--skip-build] [--keep]

const fs = require('node:fs')
const path = require('node:path')

const { configuredAgents } = require('../lib/artifact.js')
const { normalizeEvidence } = require('../lib/evidence.js')
const { bodyEvents, buildComponents, ensurePaper, findJava21, verifyNoListeners, writeServerFiles } = require('../lib/harness.js')
const { ProcessSupervisor, RunFailure, delay, waitForCondition, waitForPort } = require('../lib/lifecycle.js')
const { seedPaperRuntime } = require('../lib/paper-runtime.js')
const { readinessMetrics } = require('../lib/readiness.js')

const REPO = path.resolve(__dirname, '..', '..')
const CONFIG = JSON.parse(fs.readFileSync(path.join(__dirname, 'path-rehearsal.json'), 'utf8'))
const GENERATION = 10
const RETENTION_TICKS = 4800
const POLL_MS = 500

function serverConfig () {
  return {
    ...CONFIG,
    network: {
      ...CONFIG.network,
      cognitionUpstreamPort: CONFIG.network.cognitionPort,
      cognitionProxyPort: CONFIG.network.cognitionPort
    }
  }
}

/** Points the generated server at a flat world without structures. */
function flatten (runDirectory) {
  const file = path.join(runDirectory, 'server.properties')
  const properties = fs.readFileSync(file, 'utf8')
    .replace('generate-structures=true', 'generate-structures=false')
  fs.writeFileSync(file, `${properties}level-type=${CONFIG.world.levelType.replace(':', '\\:')}\n`)
}

function paperEnvironment (runDirectory, javaHome) {
  const config = serverConfig()
  return {
    PATH: process.env.PATH,
    HOME: process.env.HOME,
    JAVA_HOME: javaHome,
    LOOM_BODY_DIR: path.join(REPO, 'body'),
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
    LOOM_COGNITION_PORT: String(config.network.cognitionPort),
    LOOM_COGNITION_TIMEOUT_MS: String(config.runtime.cognitionBridge.timeoutMillis),
    LOOM_COGNITION_CONNECT_TIMEOUT_MS: String(config.runtime.cognitionBridge.connectTimeoutMillis),
    LOOM_COGNITION_WORKERS: String(config.runtime.cognitionBridge.workers),
    LOOM_COGNITION_QUEUE_DEPTH: String(config.runtime.cognitionBridge.queueDepth),
    LOOM_COGNITION_PENDING_PER_AGENT: String(config.runtime.cognitionBridge.perAgentPending),
    LOOM_NATIVE_COGNITION: 'held'
  }
}

function latestTick (file) {
  return bodyEvents(file).reduce((most, record) => Math.max(most, record.tick ?? 0), 0)
}

/** Every path the exerciser drives, judged from the run's own normalized evidence. */
function pathsOf (records, readiness) {
  const body = records.filter((record) => record.source === 'body')
  const of = (agent, event) => body.filter((record) => record.agentId === agent && record.event === event)
  const byKey = (key) => body.filter((record) => record.requestKey === key)
  const confirmed = (decision) => byKey(decision.requestKey).some((record) =>
    record.event === 'worldInteraction' && record.outcome === 'serverEffectConfirmed')
  const applied = (agent, outcome) => of(agent, 'intentApplied').find((record) => record.outcome === outcome)
  const evidence = (record) => record === undefined ? null : record.recordId
  const crafted = of('tribe-alpha', 'decided').find((record) => record.intent === 'craft' && confirmed(record))
  const completedAdopted = of('tribe-alpha', 'decided').find((record) => record.goal?.outcome === 'goalCompletedAdopted')
  const passive = of('tribe-delta', 'decided').find((record) =>
    record.intent === 'collect' && record.decision?.intent?.approach === false && confirmed(record))
  const revealed = of('tribe-epsilon', 'decided').find((record) => record.goal?.stepEvidence === 'revealed')
  const peerFlight = readiness.actions.nonSuccess.find((entry) => entry.agentId === 'tribe-gamma' && entry.class === 'peerFlight')
  // beta's first dig is the drop that lands where no route reaches; a perception of beta at
  // least RETENTION_TICKS later must still tell it lying.
  const firstDig = of('tribe-beta', 'decided').find((record) => record.intent === 'dig' && confirmed(record))
  const dugAt = firstDig === undefined ? undefined : byKey(firstDig.requestKey).find((record) => record.event === 'worldInteraction')?.tick
  const retained = firstDig === undefined || dugAt === undefined
    ? undefined
    : of('tribe-beta', 'perceived').find((record) =>
      record.tick - dugAt >= RETENTION_TICKS &&
      (record.perception?.selfState?.ownDrops ?? []).some((drop) =>
        drop.requestId === firstDig.requestId && drop.fate === 'lying'))
  return {
    craftConfirmed: evidence(crafted),
    completedAndAdopted: evidence(completedAdopted),
    ownDropIgnoredRefused: evidence(applied('tribe-beta', 'goalOwnDropIgnored')),
    ownDropToldLyingPastRetention: evidence(retained),
    peerFlightClassed: peerFlight?.evidence?.recordId ?? null,
    collectWithoutApproachConfirmed: evidence(passive),
    exploreRevealedASource: evidence(revealed),
    reviseToAnotherItemRefused: evidence(applied('tribe-epsilon', 'goalReviseItemChanged'))
  }
}

/**
 * The mechanical faults no path exercises: a failed decision, a dispute, a protocol error. A
 * decision taken after the window, which the server's stop cut off, is no fault.
 */
function faultsOf (records, readiness, windowEnd) {
  const body = records.filter((record) => record.source === 'body')
  const late = new Set(body.filter((record) => record.event === 'decided' && record.tick > windowEnd).map((record) => record.requestKey))
  const counted = readiness.actions.nonSuccess.filter((entry) => !late.has(entry.requestKey))
  const byClass = {}
  for (const entry of counted) byClass[entry.class] = (byClass[entry.class] ?? 0) + 1
  const expected = new Set(['confirmed', 'peerFlight', 'goalIncoherent', 'terrain', 'targetRace', 'superseded', 'concurrent'])
  return {
    cognitionFailures: body.filter((record) => record.event === 'cognitionUnavailable' && record.outcome !== 'dangerCoalesced' && record.outcome !== 'cognitionCoalesced').length,
    disputes: body.filter((record) => record.event === 'witnessDisputed').length,
    unexpectedClasses: Object.fromEntries(Object.entries(byClass).filter(([name]) => !expected.has(name))),
    unexpectedRefusals: counted
      .filter((entry) => entry.class === 'goalIncoherent')
      .map((entry) => entry.evidence?.outcome)
      .filter((outcome) => !['goalOwnDropIgnored', 'goalReviseItemChanged'].includes(outcome))
  }
}

async function main () {
  const skipBuild = process.argv.includes('--skip-build')
  const keep = process.argv.includes('--keep')
  const config = serverConfig()
  const java = findJava21()
  const components = buildComponents(REPO, java, skipBuild)
  const paperFile = await ensurePaper(REPO, config)
  await verifyNoListeners(config, 'startup')
  const root = path.join(REPO, '.scratch', 'path-rehearsal')
  fs.mkdirSync(root, { recursive: true })
  const runDirectory = fs.mkdtempSync(path.join(root, 'run-'))
  writeServerFiles({ runDirectory, paperFile, pluginJar: components.files.pluginJar, config })
  flatten(runDirectory)
  await seedPaperRuntime({ repositoryRoot: REPO, paperFile, config, java, runDirectory })
  const bodyFile = path.join(runDirectory, 'plugins', 'Loom', 'body-events.jsonl')
  let failure = null
  const supervisor = new ProcessSupervisor({
    logBytes: config.bounds.logBytes,
    journal: { append: () => {} },
    onFailure: (fault) => { failure = fault }
  })
  let paper
  let windowEnd = Infinity
  try {
    supervisor.start({
      name: 'cognition',
      command: components.files.cognitionBinary,
      cwd: path.join(REPO, 'cognition'),
      env: {
        PATH: process.env.PATH,
        LOOM_COGNITION_BACKEND: 'scripted-paths',
        LOOM_COGNITION_ADDR: `${config.network.host}:${config.network.cognitionPort}`
      },
      logFile: path.join(runDirectory, 'cognition.log')
    })
    const signal = new AbortController().signal
    await waitForPort({ host: config.network.host, port: config.network.cognitionPort, timeoutMilliseconds: 30_000, signal })
    paper = supervisor.start({
      name: 'paper',
      command: java,
      args: ['-Xms512M', `-Xmx${config.runtime.paperMemoryMiB}M`, '-jar', 'paper.jar', '--nogui'],
      cwd: runDirectory,
      env: paperEnvironment(runDirectory, path.dirname(path.dirname(path.resolve(java)))),
      logFile: path.join(runDirectory, 'paper.log'),
      stdin: true
    })
    const command = (line) => paper.child.stdin.write(`${line}\n`)
    await waitForCondition({
      description: 'all bodies to spawn',
      timeoutMilliseconds: config.bounds.startupSeconds * 1000,
      signal,
      poll: async () => {
        if (failure !== null) throw failure
        const spawned = new Set(bodyEvents(bodyFile).filter((record) => record.event === 'spawned').map((record) => record.agentId))
        return config.agents.every((agent) => spawned.has(agent))
      }
    })
    for (const line of config.setup) command(line)
    for (const agent of configuredAgents(config)) {
      command(`clear ${agent.username}`)
      command(`tp ${agent.username} ${config.positions[agent.agentId].join(' ')}`)
    }
    await delay(2000)
    for (const line of config.afterPlacement) command(line)
    command('loombody begin')
    await delay(1000)
    const begin = latestTick(bodyFile)
    windowEnd = begin + config.bounds.runTicks
    const deadline = Date.now() + config.bounds.runSeconds * 1000
    while (latestTick(bodyFile) - begin < config.bounds.runTicks) {
      if (failure !== null) throw failure
      if (Date.now() > deadline) throw new RunFailure('runFailed', 'the rehearsal did not reach its tick window in time')
      await delay(POLL_MS)
    }
    command('stop')
    await waitForCondition({ description: 'Paper to stop', timeoutMilliseconds: 60_000, signal, poll: async () => paper.child.exitCode !== null })
  } finally {
    await supervisor.stopAll(10_000)
    for (const handle of supervisor.processes) {
      if (handle.child.exitCode === null) handle.child.kill('SIGKILL')
    }
  }
  const harnessFile = path.join(runDirectory, 'harness.jsonl')
  fs.writeFileSync(harnessFile, '')
  const { records } = normalizeEvidence({
    experimentId: 'path-rehearsal',
    bodyFile,
    harnessFile,
    outputFile: path.join(runDirectory, 'evidence.jsonl'),
    maxInputBytes: config.bounds.logBytes,
    maxOutputBytes: config.bounds.logBytes,
    maxRecords: 20_000
  })
  const readiness = readinessMetrics({
    bodyRecords: records.filter((record) => record.source === 'body'),
    harnessRecords: [],
    metrics: {},
    generation: GENERATION
  })
  const paths = pathsOf(records, readiness)
  const faults = faultsOf(records, readiness, windowEnd)
  const covered = Object.values(paths).every((recordId) => recordId !== null)
  const clean = faults.cognitionFailures === 0 && faults.disputes === 0 &&
    Object.keys(faults.unexpectedClasses).length === 0 && faults.unexpectedRefusals.length === 0
  const report = { verdict: covered && clean ? 'covered' : 'notCovered', paths, faults, byClass: readiness.actions.byClass }
  fs.writeFileSync(path.join(runDirectory, 'report.json'), `${JSON.stringify(report, null, 2)}\n`)
  process.stdout.write(`${JSON.stringify(report, null, 2)}\nrun directory: ${path.relative(REPO, runDirectory)}\n`)
  if (!keep) {
    for (const entry of ['world', 'world_nether', 'world_the_end', 'libraries', 'cache', 'versions', 'paper.jar']) {
      fs.rmSync(path.join(runDirectory, entry), { recursive: true, force: true })
    }
  }
  process.exitCode = report.verdict === 'covered' ? 0 : 1
}

main().catch((fault) => {
  process.stderr.write(`${fault.stack ?? fault}\n`)
  process.exitCode = 1
})
