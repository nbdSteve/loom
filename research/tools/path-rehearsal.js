#!/usr/bin/env node
'use strict'

// The path rehearsal: a deterministic live run of the goal paths no research run has reached.
//
// One real Paper 1.21.1 server with the Loom plugin and real Mineflayer bodies, on a flat world
// laid out by console commands (`tools/path-rehearsal.json`), with the cognition service answering
// as the path exerciser (`LOOM_COGNITION_BACKEND=scripted-paths`, `cognition/src/exerciser.rs`).
// Each agent walks one path; the server witnesses what each body did, and the run's own evidence,
// normalized and judged by readiness gate generation 20, must show every path as the exerciser
// drove it: a craft of two applications and a goal completed and the next adopted in one answer,
// each chained to the goal whose item its recipe is made from, an own drop ignored and refused,
// an own drop still told lying past 4800 ticks, a collect
// without moving that vanilla's own pickup ends, a walk to the edge of sight credited as
// revealing a source, a walk beside a source sight showed out of dig reach credited as arriving,
// a revise to another item refused, a revise to the held total refused, a
// plain completion beside a source refused, a plain completion with none accepted and followed by
// the review it owed, a block beside the support under an overhang reported unsafe to dig, and,
// once a console command brings dusk, a shelter goal dug down and roofed over the agent's head,
// each step one its cover reading named, until the server's ground seals it in. From generation 20
// a goal one collect meets is refused, a met goal is revised for more of its own item under the
// same goal, a replace states that the goal it ends was completed, and the oak pressure plate gamma
// holds - no collision, so never a closer - is never reported solid where the dirt it digs is.
// Its window closes as a research run's does: admission closes before Paper stops, and every
// decision reaches one typed terminal, so `untypedTerminal` must be zero.
//
// It is not a research run: its world is built for the paths, its mind is scripted, and nothing
// is published. It writes under `.scratch/path-rehearsal/` and prints one report.
//
// Usage: node research/tools/path-rehearsal.js [--skip-build] [--keep]

const fs = require('node:fs')
const path = require('node:path')

const { configuredAgents } = require('../lib/artifact.js')
const { normalizeEvidence } = require('../lib/evidence.js')
const {
  RequestSettlement,
  bodyEvents,
  buildComponents,
  ensurePaper,
  findJava21,
  holdIdleSleepAssertion,
  verifyNoListeners,
  writeServerFiles
} = require('../lib/harness.js')
const { ProcessSupervisor, RunFailure, delay, waitForCondition, waitForPort } = require('../lib/lifecycle.js')
const { seedPaperRuntime } = require('../lib/paper-runtime.js')
const { isCoalesced, isExposureEnding } = require('../lib/danger.js')
const { readinessMetrics } = require('../lib/readiness.js')

const REPO = path.resolve(__dirname, '..', '..')
const CONFIG = JSON.parse(fs.readFileSync(path.join(__dirname, 'path-rehearsal.json'), 'utf8'))
const GENERATION = 20
const RETENTION_TICKS = 4800
const POLL_MS = 500
// How long admitted work may take to reach its typed terminals once admission closes: the longest
// body action deadline (a sleep's 20 s) and its control round trip, with room for a late answer.
const DRAIN_MS = 60_000
// How soon after its decision a placement must be applied: one at the server's answer takes a few
// ticks, where r39's held its body 330.
const PLACEMENT_SETTLE_TICKS = 40

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
  // The log goal it became is stepped by its dig, at once or after the walk beside a log revealed
  // out of dig reach (r34 zeta).
  const became = of('tribe-kappa', 'decided').find((record) => record.goal?.discoveredFrom !== undefined)
  const discoveryBecameResourceGoal = became && of('tribe-kappa', 'decided').find((record) =>
    record.goal?.goalId === became.goal.goalId && byKey(record.requestKey).some((entry) =>
      entry.event === 'worldInteraction' && entry.outcome === 'serverEffectConfirmed'))
  // A walk to the cell beside a source sight showed out of dig reach, credited when it brought the
  // source within reach (r34 zeta's logs were in view and out of reach).
  const besideFarSource = records.find((record) => record.source === 'body' && record.event === 'decided' &&
    ['tribe-epsilon', 'tribe-kappa'].includes(record.agentId) && record.goal?.stepEvidence === 'arrived')
  // gamma, once dusk is brought, sets a shelter goal, digs down and closes the roof over its head
  // with the dirt its digs gave, each step one its cover reading named, until the server's ground
  // seals it in, and completes it there (r36: all ten agents died at night in the open).
  const shelterAdopted = of('tribe-gamma', 'decided').find((record) =>
    record.goal?.shelter === true && record.goal?.outcome === 'goalAdopted')
  const coveredBeforeNight = (readiness.shelter?.progressingAgents ?? []).includes('tribe-gamma')
    ? of('tribe-gamma', 'decided').find((record) => record.goal?.stepEvidence === 'covered')
    : undefined
  const roofed = of('tribe-gamma', 'decided').find((record) =>
    record.intent === 'place' && confirmed(record) &&
    byKey(record.requestKey).some((entry) => entry.event === 'intentApplied' && entry.outcome === 'accepted'))
  const sealedIn = of('tribe-gamma', 'shelterChanged').find((record) =>
    record.outcome === 'sheltered' && record.shelter?.sealed === true)
  const shelterCompleted = of('tribe-gamma', 'decided').find((record) =>
    record.goal?.presentedShelter === true && record.goal?.outcome === 'goalCompleted' &&
    record.goal?.conditionMet === true)
  // A dig that dropped its goal's own item made its one body pickup, which the server confirmed.
  const pickedUp = records.find((record) => record.source === 'body' && record.event === 'ownDropPickup' &&
    record.outcome === 'accepted')
  // A met goal revised for more of its own item keeps its identity and what it advanced (r39: alpha
  // and kappa replaced a met dirt goal by the same item one higher at every step).
  const revisedForMore = body.find((record) => record.event === 'decided' && record.goal?.outcome === 'goalRevised' &&
    record.goal?.conditionMet === true && record.goal?.goalId === record.goal?.presentedGoalId &&
    byKey(record.requestKey).some((entry) => entry.event === 'intentApplied' && entry.outcome === 'accepted'))
  // A replace of a met goal states that it was completed, and stands.
  const statedReason = body.find((record) => record.event === 'decided' && record.goal?.outcome === 'goalCompletedAdopted' &&
    record.goal?.reason === 'completed')
  // gamma holds an oak pressure plate (D1, r39: reported solid though it has no collision): every
  // perception that lists it reports it not solid, and the dirt its digs gave is.
  const gammaSeen = of('tribe-gamma', 'perceived').map((record) => record.perception?.selfState?.inventorySummary ?? [])
  const plates = gammaSeen.flat().filter((stack) => stack.what === 'item.minecraft.oak_pressure_plate')
  const dirt = gammaSeen.flat().filter((stack) => stack.what === 'item.minecraft.dirt')
  const plateNeverSolid = plates.length > 0 && plates.every((stack) => stack.solid !== true) &&
    dirt.length > 0 && dirt.every((stack) => stack.solid === true)
    ? of('tribe-gamma', 'perceived').find((record) =>
      (record.perception?.selfState?.inventorySummary ?? []).some((stack) => stack.what === 'item.minecraft.oak_pressure_plate'))
    : undefined
  // A placement settles at the server's answer (D2, r39: iota's held its body 330 ticks): gamma's
  // roof is applied within two seconds of its decision.
  const settledPromptly = roofed === undefined
    ? undefined
    : byKey(roofed.requestKey).find((entry) => entry.event === 'intentApplied' && entry.outcome === 'accepted' &&
      entry.tick - roofed.tick <= PLACEMENT_SETTLE_TICKS)
  return {
    trivialGoalRefused: evidence(applied('tribe-delta', 'goalTrivial')),
    metGoalRevisedKeepingItsId: evidence(revisedForMore),
    replaceStatedItsReason: evidence(statedReason),
    nonCollidingItemNeverSolid: evidence(plateNeverSolid),
    placementSettledAtTheServersAnswer: evidence(settledPromptly),
    shelterAdoptedAsNightNeared: evidence(shelterAdopted),
    shelterStepCoveredBeforeNight: evidence(coveredBeforeNight),
    roofPlacedFromItsOwnDigs: evidence(roofed),
    sealedInByTheServersGround: evidence(sealedIn),
    sealedShelterCompleted: evidence(shelterCompleted),
    ownGoalDropPickedUpOnce: evidence(pickedUp),
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
    // beta's log stands on a three-high pillar: its one pickup finds no route, typed, and the drop
    // stays lying where no route reaches, so a later collect is never asked for it.
    ownGoalDropPickupNoRoute: evidence(records.find((record) => record.source === 'body' &&
      record.agentId === 'tribe-beta' && record.event === 'ownDropPickup' && record.outcome === 'collectNoRoute')),
    ownDropToldLyingPastRetention: evidence(retained),
    collectWithoutApproachConfirmed: evidence(passive),
    exploreRevealedASource: evidence(revealed),
    walkBesideAFarSourceArrived: evidence(besideFarSource),
    reviseToAnotherItemRefused: evidence(applied('tribe-epsilon', 'goalReviseItemChanged'))
  }
}

/**
 * The mechanical faults no path exercises: a failed decision, a dispute, a protocol error, and a
 * decision with no typed terminal. Admission closes before Paper stops, so no decision is exempt
 * for coming late: work admitted before the close drains to its own terminal, and an answer after
 * it is withheld as `exposureEnded`.
 */
function faultsOf (records, readiness) {
  const body = records.filter((record) => record.source === 'body')
  const counted = readiness.actions.nonSuccess
  const byClass = {}
  for (const entry of counted) byClass[entry.class] = (byClass[entry.class] ?? 0) + 1
  const expected = new Set(['confirmed', 'goalIncoherent', 'terrain', 'targetRace', 'superseded', 'concurrent'])
  return {
    cognitionFailures: body.filter((record) =>
      record.event === 'cognitionUnavailable' && !isCoalesced(record) && !isExposureEnding(record)).length,
    untypedTerminals: readiness.actions.byClass.untypedTerminal ?? 0,
    disputes: body.filter((record) => record.event === 'witnessDisputed').length,
    unexpectedClasses: Object.fromEntries(Object.entries(byClass).filter(([name]) => !expected.has(name))),
    unexpectedRefusals: counted
      .filter((entry) => entry.class === 'goalIncoherent')
      .map((entry) => entry.evidence?.outcome)
      .filter((outcome) => ![
        'goalOwnDropIgnored', 'goalReviseItemChanged', 'goalReviseUnchanged', 'goalCompletedWithoutNext',
        'goalCrowdsExploration', 'goalTrivial'
      ].includes(outcome))
  }
}

/**
 * Whether every agent's exposure has ended and every perception sent before it has its one typed
 * terminal: an applied or discarded decision, or a mind that did not answer.
 */
function drained (records, agents, settlement = new RequestSettlement()) {
  settlement.observe(records)
  const ended = new Set(records.filter((record) => record.event === 'exposureEnded').map((record) => record.agentId))
  return agents.every((agentId) => ended.has(agentId)) && settlement.unsettled.size === 0
}

async function main () {
  const release = await holdIdleSleepAssertion()
  try {
    await rehearse()
  } finally {
    release()
  }
}

async function rehearse () {
  const skipBuild = process.argv.includes('--skip-build')
  const keep = process.argv.includes('--keep')
  const config = serverConfig()
  const java = findJava21()
  const components = await buildComponents(REPO, java, skipBuild)
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
  // An interrupt cancels the run rather than ending the process, so the cleanup below always runs:
  // ended outright, it left Paper, the cognition service and every body running.
  const cancel = new AbortController()
  const interrupted = () => cancel.abort()
  process.once('SIGINT', interrupted)
  process.once('SIGTERM', interrupted)
  const cancelled = () => {
    if (cancel.signal.aborted) throw new RunFailure('cancelled', 'the rehearsal was interrupted')
  }
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
    const signal = cancel.signal
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
        cancelled()
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
      for (const item of config.gives?.[agent.agentId] ?? []) command(`give ${agent.username} ${item}`)
    }
    await delay(2000)
    for (const line of config.afterPlacement) command(line)
    command('loombody begin')
    await delay(1000)
    const begin = latestTick(bodyFile)
    const deadline = Date.now() + config.bounds.runSeconds * 1000
    // A console command due part way through, such as the dusk a shelter path needs once the
    // reviews that must come by day have been taken.
    const timed = [...(config.timed ?? [])].sort((one, other) => one.afterTicks - other.afterTicks)
    for (let elapsed = 0; elapsed < config.bounds.runTicks; elapsed = latestTick(bodyFile) - begin) {
      if (failure !== null) throw failure
      cancelled()
      if (Date.now() > deadline) throw new RunFailure('runFailed', 'the rehearsal did not reach its tick window in time')
      while (timed.length > 0 && elapsed >= timed[0].afterTicks) command(timed.shift().command)
      await delay(POLL_MS)
    }
    // Admission closes before Paper stops, as a research run's does: nothing new is sent, applied
    // or counted, and every decision already taken reaches its one typed terminal first.
    command('loombody end-exposure')
    const settlement = new RequestSettlement()
    await waitForCondition({
      description: 'admitted work to reach its typed terminals after admission closed',
      timeoutMilliseconds: DRAIN_MS,
      signal,
      poll: async () => {
        if (failure !== null) throw failure
        cancelled()
        return drained(bodyEvents(bodyFile), config.agents, settlement)
      }
    })
    command('stop')
    await waitForCondition({ description: 'Paper to stop', timeoutMilliseconds: 60_000, signal, poll: async () => paper.child.exitCode !== null })
  } finally {
    await supervisor.stopAll(10_000)
    for (const handle of supervisor.processes) {
      if (handle.child.exitCode === null) handle.child.kill('SIGKILL')
    }
    process.off('SIGINT', interrupted)
    process.off('SIGTERM', interrupted)
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
  const faults = faultsOf(records, readiness)
  const covered = Object.values(paths).every((recordId) => recordId !== null)
  const clean = faults.cognitionFailures === 0 && faults.disputes === 0 && faults.untypedTerminals === 0 &&
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

module.exports = { drained, faultsOf, pathsOf }
