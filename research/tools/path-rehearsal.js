#!/usr/bin/env node
'use strict'

// The path rehearsal: a deterministic live run of the goal paths no research run has reached.
//
// One real Paper 1.21.1 server with the Loom plugin and real Mineflayer bodies, on a flat world
// laid out by console commands (`tools/path-rehearsal.json`), with the cognition service answering
// as the path exerciser (`LOOM_COGNITION_BACKEND=scripted-paths`, `cognition/src/exerciser.rs`).
// Each agent walks one path; the server witnesses what each body did, and the run's own evidence,
// normalized and judged by readiness gate generation 11, must show every path as the exerciser
// drove it: a craft of two applications and a goal completed and the next adopted in one answer,
// each chained to the goal whose item its recipe is made from, an own drop ignored and refused,
// an own drop still told lying past 4800 ticks, a flight from a peer classed as one, a collect
// without moving that vanilla's own pickup ends, a walk to the edge of sight credited as
// revealing a source, a revise to another item refused, a revise to the held total refused, a
// plain completion beside a source refused, a plain completion with none accepted and followed by
// the review it owed, and a block beside the support under an overhang reported unsafe to dig.
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
const { isCoalesced } = require('../lib/danger.js')
const { readinessMetrics } = require('../lib/readiness.js')

const REPO = path.resolve(__dirname, '..', '..')
const CONFIG = JSON.parse(fs.readFileSync(path.join(__dirname, 'path-rehearsal.json'), 'utf8'))
const GENERATION = 14
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

/**
 * Points the generated server at a flat world without structures, and with no natural monster
 * spawning from the first tick: superflat ground lies below y 40, where slimes spawn in any light,
 * and the one hostile the paths need is summoned. Bukkit fills every other setting of its own file.
 */
function flatten (runDirectory) {
  const file = path.join(runDirectory, 'server.properties')
  const properties = fs.readFileSync(file, 'utf8')
    .replace('generate-structures=true', 'generate-structures=false')
  fs.writeFileSync(file, `${properties}level-type=${CONFIG.world.levelType.replace(':', '\\:')}\n`)
  fs.writeFileSync(path.join(runDirectory, 'bukkit.yml'), `spawn-limits:\n  monsters: ${CONFIG.world.monsterSpawnLimit}\n`)
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
  const crafted = of('tribe-alpha', 'decided').find((record) =>
    record.intent === 'craft' && (record.decision?.intent?.crafts ?? 1) >= 2 && confirmed(record) &&
    byKey(record.requestKey).some((entry) => entry.event === 'intentApplied' && entry.outcome === 'accepted'))
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
  // theta's plain completion owes a review: a perception it settles into, triggered stepSettled.
  const completed = of('tribe-theta', 'decided').find((record) => record.goal?.outcome === 'goalCompleted')
  const owed = completed === undefined
    ? undefined
    : of('tribe-theta', 'perceived').find((record) =>
      record.tick > completed.tick && (record.trigger ?? record.perception?.trigger?.event) === 'stepSettled')
  // eta's block east of its support, one below its feet, has stone two above it: unsafe to dig.
  const pocket = of('tribe-eta', 'perceived').find((record) =>
    (record.perception?.surroundings ?? []).some((seen) =>
      seen.kind === 'block' && seen.direction === 'east' && seen.verticalOffset === -0.5 &&
      seen.distance < 1.6 && seen.digSafe === false))
  // iota's log in the nook beside its pit, collected with approach (r28 plains alpha, tick 9765).
  const nook = of('tribe-iota', 'decided').find((record) =>
    record.intent === 'collect' && record.decision?.intent?.approach === true && confirmed(record) &&
    byKey(record.requestKey).some((entry) => entry.event === 'intentApplied' && entry.outcome === 'accepted'))
  // kappa's goal review beside a creeper flees it with no goal choice, and the flight runs.
  const hunted = of('tribe-kappa', 'decided').find((record) =>
    record.intent === 'flee' && record.decision?.goal === undefined &&
    byKey(record.requestKey).some((entry) => entry.event === 'perceived' && (entry.trigger ?? entry.perception?.trigger?.event) === 'goalReview') &&
    byKey(record.requestKey).some((entry) => entry.event === 'intentApplied' && entry.outcome === 'accepted'))
  // kappa stands against the world border's north face: its flight runs along the border and
  // arrives, and no perception offers it a walk past the border (v29 rehearsal theta).
  // Any of its flights may be the one that arrives: a later perception's answer can halt one.
  const alongBorder = of('tribe-kappa', 'decided')
    .filter((record) => record.intent === 'flee')
    .flatMap((record) => byKey(record.requestKey))
    .find((entry) => entry.event === 'moved' && entry.outcome === 'movementArrived' &&
      Number((entry.summary ?? entry.detail).split(' to ')[1].split(';')[0].split(',')[2]) >= -63.7 - 1e-6)
  const bordered = of('tribe-kappa', 'perceived').find((record) =>
    (record.perception?.explore ?? []).length > 0 &&
    record.perception.explore.every((cell) => cell.direction !== 'north'))
  // epsilon's log goal asked past the one log in sight and was met through the log the walk east
  // revealed: a horizon goal advanced past what sight guaranteed at its adoption (r22).
  const discovered = (readiness.goalCoherence?.pursuit?.measured?.discoveredGoals ?? 0) > 0
    ? of('tribe-epsilon', 'decided').find((record) => record.goal?.conditionMet === true)
    : undefined
  // kappa, clear of its creeper with nothing grounding an item goal, set a discovery goal, walked
  // toward the tree line, and the walk that brought a log into sight became a log goal (r23).
  const discoveryRevealed = of('tribe-kappa', 'decided').find((record) =>
    record.goal?.presentedDiscovery === true && record.goal?.stepEvidence === 'revealed')
  const discoveryBecameResourceGoal = of('tribe-kappa', 'decided').find((record) =>
    record.goal?.discoveredFrom !== undefined && byKey(record.requestKey).some((entry) =>
      entry.event === 'worldInteraction' && entry.outcome === 'serverEffectConfirmed'))
  return {
    discoverySetWithNothingGrounded: evidence(of('tribe-kappa', 'decided').find((record) =>
      record.goal?.discovery === true && record.goal?.outcome === 'goalAdopted')),
    discoveryRevealed: evidence(discoveryRevealed),
    discoveryBecameResourceGoal: evidence(discoveryBecameResourceGoal),
    localGoalCrowdingRefused: evidence(applied('tribe-gamma', 'goalCrowdsExploration')),
    horizonGoalAdvancedPastSight: evidence(discovered),
    reviewFleesAHunterWithNoGoalChoice: evidence(hunted),
    flightAlongTheBorderArrived: evidence(alongBorder),
    noWalkOfferedPastTheBorder: evidence(bordered),
    nookDropCollectedFromThePit: evidence(nook),
    craftOfTwoApplicationsConfirmed: evidence(crafted),
    chainedToTheGoalItsRecipeUses: evidence(of('tribe-alpha', 'decided').find((record) => record.goal?.chainedFrom !== undefined)),
    reviseToTheHeldTotalRefused: evidence(applied('tribe-eta', 'goalReviseUnchanged')),
    completionBesideASourceRefused: evidence(applied('tribe-eta', 'goalCompletedWithoutNext')),
    plainCompletionOwedItsReview: evidence(owed),
    pocketUnderAnOverhangUnsafe: evidence(pocket),
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
    cognitionFailures: body.filter((record) => record.event === 'cognitionUnavailable' && !isCoalesced(record)).length,
    disputes: body.filter((record) => record.event === 'witnessDisputed').length,
    unexpectedClasses: Object.fromEntries(Object.entries(byClass).filter(([name]) => !expected.has(name))),
    unexpectedRefusals: counted
      .filter((entry) => entry.class === 'goalIncoherent')
      .map((entry) => entry.evidence?.outcome)
      .filter((outcome) => ![
        'goalOwnDropIgnored', 'goalReviseItemChanged', 'goalReviseUnchanged', 'goalCompletedWithoutNext',
        'goalCrowdsExploration'
      ].includes(outcome))
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
    // The world border the configuration states, as a research run sets it: kappa's flight runs
    // against its north face, and a rehearsal without it proved nothing about the border.
    command(`worldborder set ${config.world.worldBorderDiameterBlocks}`)
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

if (require.main === module) {
  main().catch((fault) => {
    process.stderr.write(`${fault.stack ?? fault}\n`)
    process.exitCode = 1
  })
}

module.exports = { faultsOf, pathsOf }
