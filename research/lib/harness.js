'use strict'

const fs = require('node:fs')
const net = require('node:net')
const os = require('node:os')
const path = require('node:path')
const { execFileSync, spawnSync } = require('node:child_process')

const { assertRunConfig, componentSourceHashes, configuredAgents, publishArtifact, validateProspectiveManifest } = require('./artifact.js')
const { CognitionBudgetProxy } = require('./budget-proxy.js')
const { readJsonLines } = require('./evidence.js')
const {
  BoundedJournal,
  ProcessSupervisor,
  RunFailure,
  delay,
  waitForCondition,
  waitForPort
} = require('./lifecycle.js')
const {
  commandOutput,
  readJson,
  sha256Buffer,
  sha256File
} = require('./util.js')

const STARTUP_EVENT = 'spawned'
const PROVIDER_USAGE_SCHEMA_VERSION = 'loom-provider-usage/1'
const PAPER_DOWNLOAD_BYTES = 64 * 1024 * 1024
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

function runBuild (command, args, options) {
  execFileSync(command, args, {
    cwd: options.cwd,
    env: options.env,
    stdio: 'inherit',
    timeout: options.timeout
  })
}

function buildComponents (repositoryRoot, java, skipBuild) {
  const pluginJar = path.join(repositoryRoot, 'plugin', 'build', 'libs', 'loom-plugin-0.1.0.jar')
  const cognitionBinary = path.join(repositoryRoot, 'cognition', 'target', 'release', 'loom-cognition')
  if (!skipBuild) {
    runBuild('npm', ['ci'], {
      cwd: path.join(repositoryRoot, 'body'),
      env: process.env,
      timeout: 180_000
    })
    runBuild('npm', ['run', 'check'], {
      cwd: path.join(repositoryRoot, 'body'),
      env: process.env,
      timeout: 300_000
    })
    runBuild('cargo', ['build', '--release', '--bin', 'loom-cognition'], {
      cwd: path.join(repositoryRoot, 'cognition'),
      env: process.env,
      timeout: 300_000
    })
    const javaHome = path.dirname(path.dirname(path.resolve(java)))
    runBuild('./gradlew', ['check', 'shadowJar'], {
      cwd: path.join(repositoryRoot, 'plugin'),
      env: { ...process.env, JAVA_HOME: javaHome },
      timeout: 600_000
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
  return records.length === 0 ? 0 : Math.max(...records.map((record) => record.tick))
}

function paperCommand (paper, command) {
  if (!paper.child.stdin?.writable) throw new RunFailure('paperFailed', 'Paper console is not writable')
  paper.child.stdin.write(`${command}\n`)
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

function runtimeEnvironment () {
  return Object.fromEntries(
    RUNTIME_ENVIRONMENT_KEYS
      .filter((name) => process.env[name] !== undefined)
      .map((name) => [name, process.env[name]])
  )
}

function cognitionEnvironment (config, usageFile) {
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
    const credential = process.env.OPENAI_API_KEY?.trim()
    if (credential === undefined || credential.length === 0) {
      throw new RunFailure('startupFailed', 'OPENAI_API_KEY is not available to the cognition process')
    }
    environment.OPENAI_API_KEY = credential
    environment.LOOM_OPENAI_BASE_URL = config.cognition.providerEndpoint
    environment.LOOM_OPENAI_USAGE_LOG = usageFile
  }
  return environment
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
    java: combinedOutput(java, ['-version']),
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

async function runExperiment ({
  repositoryRoot,
  configFile,
  artifactRoot,
  paperJar,
  skipBuild = false,
  signal
}) {
  const absoluteRoot = path.resolve(repositoryRoot)
  const config = readJson(path.resolve(configFile))
  assertRunConfig(config)
  config.configFile = path.resolve(configFile)
  const source = sourceIdentity(absoluteRoot)
  validateProspectiveManifest(config, source)
  const java = findJava21()
  const components = buildComponents(absoluteRoot, java, skipBuild)
  const paperFile = await ensurePaper(absoluteRoot, config, paperJar)
  await verifyNoListeners(config, 'startup')

  const scratchRoot = path.join(absoluteRoot, '.scratch', 'tribe-runs')
  fs.mkdirSync(scratchRoot, { recursive: true })
  const runDirectory = fs.mkdtempSync(path.join(scratchRoot, `${config.experimentId}-`))
  const rawHarnessEvidence = path.join(runDirectory, 'harness-events.jsonl')
  const rawBodyEvidence = path.join(runDirectory, 'plugins', 'Loom', 'body-events.jsonl')
  const rawProviderUsage = path.join(runDirectory, 'provider-usage.jsonl')
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
  try {
    writeServerFiles({
      runDirectory,
      paperFile,
      pluginJar: components.files.pluginJar,
      config
    })
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
      env: cognitionEnvironment(config, rawProviderUsage),
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
      journal
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
        LOOM_COGNITION_PENDING_PER_AGENT: String(config.runtime.cognitionBridge.perAgentPending)
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
    const startTick = latestBodyTick(rawBodyEvidence)
    journal.append('experimentStarted', {
      agents: [...config.agents].sort(),
      cognitionBackend: config.cognition.backend,
      startTick
    })
    startedAt = new Date().toISOString()
    const startedMillis = Date.now()
    const deadline = startedMillis + config.bounds.runDurationSeconds * 1000
    let nextCycle = startedMillis
    let crashed = false
    while (Date.now() < deadline) {
      if (controller.signal.aborted) throw controller.signal.reason
      if (fs.existsSync(rawBodyEvidence) && fs.statSync(rawBodyEvidence).size > config.bounds.logBytes) {
        throw new RunFailure('logLimit', 'body evidence exceeded the configured log byte bound')
      }
      if (latestBodyTick(rawBodyEvidence) - startTick >= config.bounds.simulationTickCeiling) {
        stopReason = 'simulationTickCeiling'
        break
      }
      const now = Date.now()
      if (!crashed && now - startedMillis >= config.proof.crashAfterSeconds * 1000) {
        const crashAgent = configuredAgents(config).find((agent) => agent.agentId === config.proof.crashAgent)
        if (crashAgent === undefined) throw new RunFailure('startupFailed', 'configured crash agent is absent')
        const before = bodyEvents(rawBodyEvidence)
        const afterTick = before.length === 0 ? 0 : Math.max(...before.map((record) => record.tick))
        const pid = bodyPidForPort(crashAgent.controlPort)
        process.kill(pid, 'SIGKILL')
        journal.append('controlledBodyCrash', {
          agentId: crashAgent.agentId,
          controlPort: crashAgent.controlPort,
          afterTick
        })
        paperCommand(paper, `loombody think ${crashAgent.agentId}`)
        crashed = true
        await waitForCondition({
          description: 'the controlled body crash to be recorded',
          timeoutMilliseconds: Math.min(15_000, Math.max(1, deadline - Date.now())),
          signal: controller.signal,
          poll: async () =>
            bodyEvents(rawBodyEvidence).some(
              (record) => record.agentId === crashAgent.agentId && record.event === 'bodyDisconnected'
            )
        })
      }
      if (Date.now() >= nextCycle) {
        for (const agent of config.agents) {
          if (crashed && agent === config.proof.crashAgent) continue
          paperCommand(paper, `loombody think ${agent}`)
        }
        nextCycle = Date.now() + config.proof.cycleIntervalSeconds * 1000
      }
      await delay(Math.min(250, Math.max(1, deadline - Date.now())), controller.signal)
    }
    endedAt = new Date().toISOString()

    const crashAgent = config.proof.crashAgent
    const crashEvent = bodyEvents(rawHarnessEvidence).find((record) => record.event === 'controlledBodyCrash')
    const peerDecisions = bodyEvents(rawBodyEvidence).filter(
      (record) =>
        record.event === 'decided' &&
        record.agentId !== crashAgent &&
        record.tick > crashEvent.afterTick
    )
    if (new Set(peerDecisions.map((record) => record.agentId)).size < 2) {
      throw new RunFailure('startupFailed', 'two peers did not decide after the controlled crash')
    }
    journal.append('experimentEnded', {
      stopReason,
      endTick: latestBodyTick(rawBodyEvidence)
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
    await cleanupStep(async () => {
      if (supervisor !== undefined) await supervisor.stopAll(config.bounds.shutdownSeconds * 1000)
    })
    await cleanupStep(async () => {
      if (proxy !== undefined) await proxy.close()
    })
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
    fs.rmSync(runDirectory, { recursive: true, force: true })
    throw failure
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
  } finally {
    fs.rmSync(runDirectory, { recursive: true, force: true })
  }
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
  bodyEvents,
  buildComponents,
  ensurePaper,
  findJava21,
  importProviderUsage,
  runExperiment,
  sourceIdentity,
  toolVersions,
  verifyNoListeners,
  writeServerFiles
}
