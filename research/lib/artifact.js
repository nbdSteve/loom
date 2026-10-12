'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { execFileSync } = require('node:child_process')

const { currentSchemaVersion, validateManifest, versionTag } = require('../schema/manifest-schema.js')
const {
  METRICS_VERSION,
  NORMALIZED_EVIDENCE_VERSION,
  PUBLIC_SCAN_PATTERNS,
  deriveMetrics,
  normalizeEvidence,
  readJsonLines,
  scanText
} = require('./evidence.js')
const {
  canonicalJson,
  offlineIdentity,
  readJson,
  resolveRelativeFile,
  sha256File,
  sourceTreeSha256,
  writeJson
} = require('./util.js')

const MANIFEST_FILE = 'manifest.json'
const EVIDENCE_FILE = 'evidence.jsonl'
const METRICS_FILE = 'metrics.json'
const README_FILE = 'README.md'
const ALLOWED_ARTIFACT_FILES = new Set([MANIFEST_FILE, EVIDENCE_FILE, METRICS_FILE, README_FILE])
const ZERO_SHA256 = '0'.repeat(64)
const NANO_USD_PER_USD = 1_000_000_000
const MAX_PROVIDER_REQUEST_BYTES = 256 * 1024

function configuredAgents (config) {
  return [...config.agents]
    .sort((first, second) => first.localeCompare(second))
    .map((agentId, index) => ({
      ...offlineIdentity(agentId),
      controlPort: config.network.bodyControlBasePort + index,
      processDirectory: `bodies/${offlineIdentity(agentId).username}`
    }))
}

function pricingErrors (config) {
  const errors = []
  const pricing = config.cognition?.pricing
  if (pricing?.currency !== 'USD') errors.push('cognition.pricing.currency must be USD')
  if (!/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(pricing?.effectiveDate ?? '')) {
    errors.push('cognition.pricing.effectiveDate must be an ISO calendar date')
  }
  if (typeof pricing?.source !== 'string' || pricing.source.length < 1 || pricing.source.length > 500) {
    errors.push('cognition.pricing.source must contain a bounded source description')
  }
  const models = pricing?.models
  if (models === null || typeof models !== 'object' || Array.isArray(models)) {
    errors.push('cognition.pricing.models must be an object')
    return errors
  }
  for (const model of new Set([
    config.cognition?.models?.ordinary,
    config.cognition?.models?.pivotal
  ])) {
    if (typeof model !== 'string' || model.length === 0) {
      errors.push('cognition model ids must be non-empty strings')
      continue
    }
    const rates = models[model]
    if (rates === null || typeof rates !== 'object' || Array.isArray(rates)) {
      errors.push(`cognition.pricing.models has no rates for ${model}`)
      continue
    }
    for (const field of [
      'inputNanoUsdPerToken',
      'cachedInputNanoUsdPerToken',
      'outputNanoUsdPerToken'
    ]) {
      if (!Number.isSafeInteger(rates[field]) || rates[field] < 0 || rates[field] > 1_000_000) {
        errors.push(`cognition.pricing.models.${model}.${field} must be an integer in 0..1000000`)
      }
    }
  }
  return errors
}

function maximumConfiguredSpendNanoUsd (config) {
  let perCall = 0
  for (const model of new Set([
    config.cognition.models.ordinary,
    config.cognition.models.pivotal
  ])) {
    const rates = config.cognition.pricing.models[model]
    const inputRate = Math.max(
      rates.inputNanoUsdPerToken,
      rates.cachedInputNanoUsdPerToken
    )
    perCall = Math.max(
      perCall,
      (config.cognition.maxRequestBytes * inputRate) +
        (config.cognition.maxOutputTokens * rates.outputNanoUsdPerToken)
    )
  }
  return perCall * config.cognition.globalCallCeiling
}

function validateRunConfig (config) {
  const errors = []
  if (config === null || typeof config !== 'object' || Array.isArray(config)) {
    return ['configuration is not a JSON object']
  }
  if (!/^[0-9]{4}-[0-9]{2}-[0-9]{2}-[a-z0-9][a-z0-9-]*$/.test(config.experimentId ?? '')) {
    errors.push('experimentId must be a dated lowercase slug')
  }
  if (!Array.isArray(config.agents) || config.agents.length < 3 || config.agents.length > 16) {
    errors.push('agents must contain 3..16 agent ids')
  } else {
    if (new Set(config.agents).size !== config.agents.length) errors.push('agents contains a duplicate')
    for (const agent of config.agents) {
      try {
        offlineIdentity(agent)
      } catch (cause) {
        errors.push(cause.message)
      }
    }
  }
  if (config.network?.host !== '127.0.0.1') errors.push('every listener must use the 127.0.0.1 loopback literal')
  const ports = [
    config.network?.minecraftPort,
    config.network?.cognitionProxyPort,
    config.network?.cognitionUpstreamPort
  ]
  if (Array.isArray(config.agents) && Number.isInteger(config.network?.bodyControlBasePort)) {
    for (let index = 0; index < config.agents.length; index += 1) {
      ports.push(config.network.bodyControlBasePort + index)
    }
  }
  if (ports.some((port) => !Number.isInteger(port) || port < 1 || port > 65535)) {
    errors.push('all configured ports must be integers in 1..65535')
  }
  if (new Set(ports).size !== ports.length) errors.push('configured listeners must use distinct ports')

  const positiveBounds = [
    'startupConcurrency',
    'startupSeconds',
    'runDurationSeconds',
    'simulationTickCeiling',
    'startupAttempts',
    'shutdownSeconds',
    'logBytes',
    'artifactBytes',
    'evidenceRecords'
  ]
  for (const key of positiveBounds) {
    if (!Number.isInteger(config.bounds?.[key]) || config.bounds[key] < 1) {
      errors.push(`bounds.${key} must be a positive integer`)
    }
  }
  if (config.bounds?.startupConcurrency > Math.min(8, config.agents?.length ?? 0)) {
    errors.push('startupConcurrency exceeds the fleet or product ceiling')
  }
  if (config.bounds?.startupAttempts > 3) errors.push('startupAttempts exceeds the body fleet ceiling')
  if (
    !Number.isInteger(config.runtime?.paperMemoryMiB) ||
    config.runtime.paperMemoryMiB < 512 ||
    config.runtime.paperMemoryMiB > 4096
  ) {
    errors.push('runtime.paperMemoryMiB must be an integer in 512..4096')
  }
  const boundedRuntime = [
    ['body.startupRetryMillis', config.runtime?.body?.startupRetryMillis, 1, 5000],
    ['body.healthCheckIntervalTicks', config.runtime?.body?.healthCheckIntervalTicks, 1, 12000],
    ['body.healthFailureLimit', config.runtime?.body?.healthFailureLimit, 1, 10],
    ['body.ioWorkersPerAgent', config.runtime?.body?.ioWorkersPerAgent, 1, 8],
    ['cognitionBridge.timeoutMillis', config.runtime?.cognitionBridge?.timeoutMillis, 4, 120000],
    ['cognitionBridge.connectTimeoutMillis', config.runtime?.cognitionBridge?.connectTimeoutMillis, 1, 30000],
    ['cognitionBridge.workers', config.runtime?.cognitionBridge?.workers, 1, 64],
    ['cognitionBridge.queueDepth', config.runtime?.cognitionBridge?.queueDepth, 1, 1024],
    ['cognitionBridge.perAgentPending', config.runtime?.cognitionBridge?.perAgentPending, 1, 64]
  ]
  for (const [name, value, minimum, maximum] of boundedRuntime) {
    if (!Number.isInteger(value) || value < minimum || value > maximum) {
      errors.push(`runtime.${name} must be an integer in ${minimum}..${maximum}`)
    }
  }
  if (
    Number.isInteger(config.runtime?.cognitionBridge?.timeoutMillis) &&
    Number.isInteger(config.runtime?.cognitionBridge?.connectTimeoutMillis) &&
    config.runtime.cognitionBridge.timeoutMillis <
      (2 * config.runtime.cognitionBridge.connectTimeoutMillis) + 2
  ) {
    errors.push('cognition bridge timeout cannot leave each retry a one-millisecond read')
  }
  if (config.world?.seed === undefined || !/^-?[0-9]+$/.test(String(config.world.seed))) {
    errors.push('world.seed must be an explicit integer string')
  }
  if (!['peaceful', 'easy', 'normal', 'hard'].includes(config.world?.difficulty)) {
    errors.push('world.difficulty must be peaceful, easy, normal, or hard')
  }
  if (typeof config.world?.mobSpawning !== 'boolean') {
    errors.push('world.mobSpawning must be a boolean')
  }
  if (config.world?.worldBorderDiameterBlocks > config.world?.maxWorldSizeBlocks) {
    errors.push('world border diameter exceeds max world size')
  }
  if (!['scripted', 'openai'].includes(config.cognition?.backend)) {
    errors.push('cognition.backend must be scripted or openai')
  }
  for (const key of ['perAgentCallCeiling', 'globalCallCeiling', 'globalOutstandingCeiling', 'maxOutputTokens']) {
    if (!Number.isInteger(config.cognition?.[key]) || config.cognition[key] < 1) {
      errors.push(`cognition.${key} must be a positive integer`)
    }
  }
  if (
    !Number.isInteger(config.cognition?.maxRequestBytes) ||
    config.cognition.maxRequestBytes < 1 ||
    config.cognition.maxRequestBytes > MAX_PROVIDER_REQUEST_BYTES
  ) {
    errors.push(`cognition.maxRequestBytes must be an integer in 1..${MAX_PROVIDER_REQUEST_BYTES}`)
  }
  if (
    Number.isInteger(config.cognition?.globalCallCeiling) &&
    Number.isInteger(config.cognition?.perAgentCallCeiling) &&
    Array.isArray(config.agents) &&
    config.cognition.globalCallCeiling > config.cognition.perAgentCallCeiling * config.agents.length
  ) {
    errors.push('global call ceiling exceeds the sum of per-agent ceilings')
  }
  if (
    typeof config.cognition?.maxExternalSpendUsd !== 'number' ||
    !Number.isFinite(config.cognition.maxExternalSpendUsd) ||
    config.cognition.maxExternalSpendUsd < 0
  ) {
    errors.push('cognition.maxExternalSpendUsd must be a non-negative number')
  }
  if (config.cognition?.backend === 'scripted' && config.cognition?.maxExternalSpendUsd !== 0) {
    errors.push('scripted cognition must declare zero external spend')
  }
  const priceErrors = pricingErrors(config)
  errors.push(...priceErrors)
  if (
    priceErrors.length === 0 &&
    Number.isSafeInteger(config.cognition?.globalCallCeiling) &&
    Number.isSafeInteger(config.cognition?.maxRequestBytes) &&
    Number.isSafeInteger(config.cognition?.maxOutputTokens)
  ) {
    const maximumNanoUsd = maximumConfiguredSpendNanoUsd(config)
    if (!Number.isSafeInteger(maximumNanoUsd)) {
      errors.push('configured worst-case external spend is not safely representable')
    } else if (maximumNanoUsd > config.cognition.maxExternalSpendUsd * NANO_USD_PER_USD) {
      errors.push('maxExternalSpendUsd is below the configured worst-case call and token bounds')
    }
  }
  if (
    !Number.isInteger(config.cognition?.providerTimeoutMillis) ||
    config.cognition.providerTimeoutMillis < 1 ||
    config.cognition.providerTimeoutMillis > 120_000
  ) {
    errors.push('cognition.providerTimeoutMillis must be an integer in 1..120000')
  }
  if (config.cognition?.backend === 'scripted') {
    if (config.cognition.providerEndpoint !== null) {
      errors.push('scripted cognition must declare a null provider endpoint')
    }
  } else {
    try {
      const endpoint = new URL(config.cognition?.providerEndpoint)
      const loopbackHttp =
        endpoint.protocol === 'http:' &&
        ['localhost', '127.0.0.1', '::1'].includes(endpoint.hostname)
      if (
        endpoint.username !== '' ||
        endpoint.password !== '' ||
        endpoint.search !== '' ||
        endpoint.hash !== '' ||
        (endpoint.protocol !== 'https:' && !loopbackHttp)
      ) {
        errors.push('cognition.providerEndpoint must be HTTPS or loopback HTTP with no user info, query, or fragment')
      }
    } catch {
      errors.push('cognition.providerEndpoint must be an absolute provider URL')
    }
  }
  if (
    Number.isInteger(config.cognition?.providerTimeoutMillis) &&
    Number.isInteger(config.runtime?.cognitionBridge?.timeoutMillis) &&
    Number.isInteger(config.runtime?.cognitionBridge?.connectTimeoutMillis)
  ) {
    const readTimeout =
      (config.runtime.cognitionBridge.timeoutMillis -
        (2 * config.runtime.cognitionBridge.connectTimeoutMillis)) /
      2
    if (config.cognition.providerTimeoutMillis >= readTimeout) {
      errors.push('provider timeout must expire before the cognition bridge read timeout')
    }
  }
  if (config.cognition?.socialRolesInjected !== false) {
    errors.push('socialRolesInjected must be false')
  }
  if (config.cognition?.eventDrivenOnly !== true) {
    errors.push('eventDrivenOnly must be true')
  }
  if (typeof config.paper?.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(config.paper.sha256)) {
    errors.push('paper.sha256 must be a lowercase SHA-256')
  }
  try {
    const paperDownload = new URL(config.paper?.downloadUrl)
    if (
      paperDownload.protocol !== 'https:' ||
      paperDownload.username !== '' ||
      paperDownload.password !== '' ||
      paperDownload.search !== '' ||
      paperDownload.hash !== ''
    ) {
      errors.push('paper.downloadUrl must be HTTPS with no user info, query, or fragment')
    }
  } catch {
    errors.push('paper.downloadUrl must be an absolute HTTPS URL')
  }
  if (!config.agents?.includes(config.proof?.crashAgent)) {
    errors.push('proof.crashAgent must name one configured agent')
  }
  if (
    typeof config.proof?.crashAfterSeconds !== 'number' ||
    config.proof.crashAfterSeconds <= 0 ||
    config.proof.crashAfterSeconds >= config.bounds?.runDurationSeconds
  ) {
    errors.push('proof.crashAfterSeconds must fall inside the run duration')
  }
  if (
    typeof config.proof?.cycleIntervalSeconds !== 'number' ||
    config.proof.cycleIntervalSeconds <= 0 ||
    config.proof.cycleIntervalSeconds > config.bounds?.runDurationSeconds
  ) {
    errors.push('proof.cycleIntervalSeconds must be positive and no longer than the run')
  }
  return errors
}

function assertRunConfig (config) {
  const errors = validateRunConfig(config)
  if (errors.length > 0) throw new Error(`invalid tribe run configuration:\n- ${errors.join('\n- ')}`)
}

function prospectiveManifest (config, source) {
  const now = '1970-01-01T00:00:00.000Z'
  const agents = configuredAgents(config)
  const metrics = emptyMetrics(agents)
  return buildManifest({
    config,
    source,
    components: {
      plugin: { buildSha256: ZERO_SHA256, sourceSha256: ZERO_SHA256 },
      body: { buildSha256: ZERO_SHA256, sourceSha256: ZERO_SHA256 },
      cognition: { buildSha256: ZERO_SHA256, sourceSha256: ZERO_SHA256 }
    },
    tools: {
      os: 'preflight',
      architecture: 'preflight',
      java: 'preflight',
      node: 'preflight',
      npm: 'preflight',
      rustc: 'preflight',
      cargo: 'preflight',
      git: 'preflight',
      lsof: 'preflight',
      gradle: 'preflight'
    },
    startedAt: now,
    endedAt: now,
    stopReason: 'proofComplete',
    evidence: {
      sha256: ZERO_SHA256,
      records: 1,
      bytes: 1,
      redactions: 0
    },
    metrics: {
      value: metrics,
      sha256: ZERO_SHA256,
      bytes: 1
    },
    warnings: [],
    validation: {
      beforeLaunch: true,
      beforePublication: true
    }
  })
}

function emptyMetrics (agents) {
  return {
    schemaVersion: METRICS_VERSION,
    records: {
      total: 1,
      body: 1,
      harness: 0,
      byEvent: { preflight: 1 },
      agents: agents.map((agent) => ({
        agentId: agent.agentId,
        records: 0,
        byEvent: {},
        decisionsByIntent: {},
        appliedByIntent: {},
        byOutcome: {},
        decisions: 0,
        actions: 0
      }))
    },
    timing: { firstTick: 0, lastTick: 0, tickSpan: 0 },
    cognition: {
      forwardedCalls: 0,
      refusedCalls: 0,
      providerUsageCalls: 0,
      usageByModel: {},
      externalSpendNanoUsd: 0,
      externalSpendUsd: 0,
      decisionsByStamp: []
    },
    cycles: { perceived: 0, decided: 0, applied: 0, unavailable: 0, discarded: 0, incomplete: [] },
    behavior: {
      decisionsByIntent: {},
      byIntent: {},
      byOutcome: {},
      movementRecords: 0,
      worldInteractionRecords: 0,
      speechIntents: 0,
      attackIntents: 0,
      sleepIntents: 0,
      deaths: 0,
      disconnects: 0
    },
    spatial: {
      coLocationThresholdBlocks: 8,
      maximumTickSeparation: 20,
      coLocationSamples: []
    },
    crashContainment: {
      controlledCrashes: [],
      cleanupVerified: false,
      peerDecisionsAfterCrash: {}
    },
    findings: {
      observations: [],
      interpretations: [],
      counterevidence: [],
      limitations: []
    }
  }
}

function validateProspectiveManifest (config, source) {
  assertRunConfig(config)
  const manifest = prospectiveManifest(config, source)
  const result = validateManifest(manifest)
  if (!result.ok) {
    throw new Error(`prospective manifest is invalid:\n- ${result.errors.join('\n- ')}`)
  }
  const unsafe = scanText(canonicalJson(manifest))
  if (unsafe.length > 0) {
    throw new Error(
      `prospective manifest contains unsafe public material:\n- ${unsafe
        .map((match) => `${match.pattern}: ${match.count}`)
        .join('\n- ')}`
    )
  }
  return manifest
}

function buildManifest ({
  config,
  source,
  components,
  tools,
  startedAt,
  endedAt,
  stopReason,
  evidence,
  metrics,
  warnings,
  validation
}) {
  const metricValue = metrics.value
  const agents = configuredAgents(config)
  const startedMillis = Date.parse(startedAt)
  const endedMillis = Date.parse(endedAt)
  return {
    schemaVersion: versionTag(currentSchemaVersion()),
    experimentId: config.experimentId,
    title: config.title,
    sourceCommit: source.commit,
    sourceCommitShort: source.commit.slice(0, 12),
    sourceRepository: source.repository,
    fieldProvenance:
      'Evidence counts, behavior metrics, timing ticks, cognition counts, provider-reported spend, and findings are generated from evidence.jsonl. ' +
      'Wall time, tool versions, component hashes, Paper identity, configuration, and cleanup are recorded by the harness.',
    rawEvidence: {
      file: EVIDENCE_FILE,
      format: NORMALIZED_EVIDENCE_VERSION,
      sha256: evidence.sha256,
      records: evidence.records,
      bytes: evidence.bytes,
      immutable: true,
      credentialScan: {
        scannedFor: PUBLIC_SCAN_PATTERNS.map((pattern) => pattern.name),
        matches: 0,
        redactions: evidence.redactions,
        note: 'The publisher recursively sanitized bounded strings, then scanned every committed artifact file.'
      }
    },
    metrics: {
      file: METRICS_FILE,
      format: METRICS_VERSION,
      sha256: metrics.sha256,
      bytes: metrics.bytes,
      derivedFrom: evidence.sha256
    },
    environment: {
      server: {
        software: 'Paper',
        minecraftVersion: config.paper.minecraftVersion,
        paperBuild: config.paper.build,
        paperSha256: config.paper.sha256
      },
      world: {
        seed: String(config.world.seed),
        maxWorldSizeBlocks: config.world.maxWorldSizeBlocks,
        worldBorderDiameterBlocks: config.world.worldBorderDiameterBlocks,
        viewDistanceChunks: config.world.viewDistanceChunks,
        simulationDistanceChunks: config.world.simulationDistanceChunks
      },
      loopbackOnly: true,
      onlineMode: false,
      tools,
      attested: { source: 'harness startup probes and pinned run configuration' }
    },
    clientPresence: {
      realPlayerAccounts: 0,
      interactiveObservers: 0
    },
    network: { ...config.network },
    runtime: structuredClone(config.runtime),
    protocol: {
      goalReviewCycleSeconds: config.proof.cycleIntervalSeconds,
      difficulty: config.world.difficulty,
      mobSpawning: config.world.mobSpawning,
      controlledBodyCrash: {
        agentId: config.proof.crashAgent,
        afterSeconds: config.proof.crashAfterSeconds,
        signal: 'SIGKILL'
      }
    },
    software: components,
    agents,
    provenance: {
      backend: config.cognition.backend,
      provider: config.cognition.provider,
      models: config.cognition.models,
      prompts: config.cognition.prompts,
      decisionsByStamp: metricValue.cognition.decisionsByStamp,
      attested: { source: 'configured routing plus decision provenance in normalized evidence' }
    },
    cognition: {
      eventDrivenOnly: true,
      socialRolesInjected: false,
      perAgentCallCeiling: config.cognition.perAgentCallCeiling,
      globalCallCeiling: config.cognition.globalCallCeiling,
      globalOutstandingCeiling: config.cognition.globalOutstandingCeiling,
      maxOutputTokens: config.cognition.maxOutputTokens,
      maxRequestBytes: config.cognition.maxRequestBytes,
      providerEndpoint: config.cognition.providerEndpoint,
      providerTimeoutMillis: config.cognition.providerTimeoutMillis,
      providerRetryLimit: 0,
      proxyRetryLimit: 0,
      preWriteTransportRetryLimit: 1,
      pricing: structuredClone(config.cognition.pricing),
      maxExternalSpendUsd: config.cognition.maxExternalSpendUsd,
      worstCaseConfiguredSpendUsd: Number(
        (maximumConfiguredSpendNanoUsd(config) / NANO_USD_PER_USD).toFixed(9)
      ),
      actualExternalSpendUsd: metricValue.cognition.externalSpendUsd,
      spendAccounting:
        config.cognition.backend === 'scripted'
          ? 'No external provider was called.'
          : 'Provider-reported tokens priced with the pinned integer nano-USD rates.',
      actualForwardedCalls: metricValue.cognition.forwardedCalls,
      actualRefusedCalls: metricValue.cognition.refusedCalls
    },
    bounds: {
      startupConcurrency: config.bounds.startupConcurrency,
      startupSeconds: config.bounds.startupSeconds,
      runDurationSeconds: config.bounds.runDurationSeconds,
      simulationTickCeiling: config.bounds.simulationTickCeiling,
      startupAttempts: config.bounds.startupAttempts,
      shutdownSeconds: config.bounds.shutdownSeconds,
      logBytes: config.bounds.logBytes,
      artifactBytes: config.bounds.artifactBytes,
      evidenceRecords: config.bounds.evidenceRecords
    },
    featureFlags: { ...config.featureFlags },
    timing: {
      startedAt,
      endedAt,
      wallDurationMillis: endedMillis - startedMillis,
      firstTick: metricValue.timing.firstTick,
      lastTick: metricValue.timing.lastTick,
      tickSpan: metricValue.timing.tickSpan,
      stopReason,
      attested: { source: 'harness monotonic deadline, UTC clock, and normalized body ticks' }
    },
    eventCounts: {
      total: metricValue.records.body,
      byEvent: metricValue.records.byEvent,
      agents: metricValue.records.agents.map((agent) => ({
        agentId: agent.agentId,
        records: agent.records,
        byEvent: agent.byEvent
      }))
    },
    integrityChecks: {
      threeIndependentBodies: {
        passed: agents.length >= 3 && metricValue.records.agents.filter((agent) => agent.byEvent.spawned > 0).length >= 3,
        derivedFrom: EVIDENCE_FILE
      },
      completeDecisionChains: {
        passed: metricValue.cycles.incomplete.length === 0,
        derivedFrom: EVIDENCE_FILE
      },
      crashContained: {
        passed:
          metricValue.crashContainment.controlledCrashes.length > 0 &&
          Object.keys(metricValue.crashContainment.peerDecisionsAfterCrash).length >= 2,
        derivedFrom: EVIDENCE_FILE
      },
      cleanupVerified: {
        passed: metricValue.crashContainment.cleanupVerified,
        derivedFrom: EVIDENCE_FILE
      },
      absolutePermadeathEnabled: {
        passed: config.featureFlags.permadeath === true,
        configuredBy: 'Loom player witness and synchronous death ledger'
      },
      noDroppedEvidenceWarning: {
        passed: !warnings.some((warning) => warning.includes('dropped')),
        attested: { source: 'bounded Paper log scan performed by the harness' }
      }
    },
    knownWarnings: warnings,
    outcome: outcomeStatement(config, metricValue),
    scientificUsability: {
      verdict:
        config.cognition.backend === 'scripted'
          ? 'Usable as a bounded composition, evidence, crash-containment, and cleanup proof; not as evidence of model-driven emergence.'
          : 'Usable only for the observations and counterevidence derivable from this bounded run.',
      scope: [
        'The configured isolated bodies shared one disposable Paper world under fixed resource and cognition bounds.',
        'Recorded per-agent cycles, action outcomes, lifecycle failures, and cleanup can be reproduced from normalized evidence.'
      ],
      outOfScope: [
        'No single bounded run establishes durable cooperation, role specialization, hierarchy, settlement, or conflict norms.',
        config.cognition.backend === 'scripted'
          ? 'Scripted cognition and harness staging are never evidence of emergence.'
          : 'The controlled crash and fixed experimental setup are interventions, not emergent behavior.'
      ]
    },
    publication: {
      manifestValidatedBeforeLaunch: validation.beforeLaunch,
      manifestValidatedBeforePublication: validation.beforePublication,
      normalized: true,
      rawWorldCommitted: false,
      serverLogsCommitted: false,
      deathLedgerCommitted: false
    }
  }
}

function outcomeStatement (config, metrics) {
  const crashes = metrics.crashContainment.controlledCrashes.length
  const peers = Object.keys(metrics.crashContainment.peerDecisionsAfterCrash).length
  const social = metrics.findings.observations.length
  return `${config.agents.length} configured bodies produced ${metrics.cycles.decided} decisions in one bounded shared world; ` +
    `${crashes} controlled body crash(es) left ${peers} peer agent(s) deciding afterward. ` +
    `${social} bounded social or spatial observation class(es) were recorded; null results remain in counterevidence.`
}

function readNormalizedEvidence (file, bounds) {
  const records = readJsonLines(file, {
    maxBytes: bounds.artifactBytes,
    maxRecords: bounds.evidenceRecords,
    maxLineBytes: 256 * 1024
  })
  for (const [index, record] of records.entries()) {
    if (record.schemaVersion !== NORMALIZED_EVIDENCE_VERSION) {
      throw new Error(`evidence line ${index + 1} declares ${JSON.stringify(record.schemaVersion)}`)
    }
  }
  return records
}

function semanticErrors (artifactDirectory, manifest) {
  const errors = []
  const root = path.resolve(artifactDirectory)
  if (path.basename(root) !== manifest.experimentId) {
    errors.push(`artifact directory ${path.basename(root)} does not match experimentId ${manifest.experimentId}`)
  }
  let evidenceFile
  let metricsFile
  try {
    evidenceFile = resolveRelativeFile(root, manifest.rawEvidence.file)
    metricsFile = resolveRelativeFile(root, manifest.metrics.file)
  } catch (cause) {
    errors.push(cause.message)
    return errors
  }
  for (const file of [evidenceFile, metricsFile, path.join(root, README_FILE)]) {
    if (!fs.existsSync(file)) errors.push(`${path.basename(file)} is missing`)
  }
  if (errors.length > 0) return errors

  const entries = fs.readdirSync(root, { withFileTypes: true })
    .sort((first, second) => first.name.localeCompare(second.name))
  for (const entry of entries) {
    if (!entry.isFile() || entry.isSymbolicLink() || !ALLOWED_ARTIFACT_FILES.has(entry.name)) {
      errors.push(`unexpected committed artifact entry ${entry.name}`)
    }
  }
  const names = entries.filter((entry) => entry.isFile() && !entry.isSymbolicLink()).map((entry) => entry.name)
  const totalBytes = names.reduce((total, name) => total + fs.statSync(path.join(root, name)).size, 0)
  if (totalBytes > manifest.bounds.artifactBytes) {
    errors.push(`artifact is ${totalBytes} bytes, over the ${manifest.bounds.artifactBytes} byte bound`)
  }

  if (sha256File(evidenceFile) !== manifest.rawEvidence.sha256) errors.push('evidence SHA-256 does not match manifest')
  if (fs.statSync(evidenceFile).size !== manifest.rawEvidence.bytes) errors.push('evidence byte count does not match manifest')
  if (sha256File(metricsFile) !== manifest.metrics.sha256) errors.push('metrics SHA-256 does not match manifest')
  if (fs.statSync(metricsFile).size !== manifest.metrics.bytes) errors.push('metrics byte count does not match manifest')
  if (manifest.metrics.derivedFrom !== manifest.rawEvidence.sha256) errors.push('metrics do not name the evidence they derive from')

  let records
  let metrics
  try {
    records = readNormalizedEvidence(evidenceFile, manifest.bounds)
    metrics = readJson(metricsFile)
  } catch (cause) {
    errors.push(cause.message)
    return errors
  }
  if (records.length !== manifest.rawEvidence.records) errors.push('evidence record count does not match manifest')
  const derived = deriveMetrics(records, manifest.agents)
  try {
    assert.deepStrictEqual(metrics, derived)
  } catch {
    errors.push('metrics.json is not the deterministic derivation of evidence.jsonl')
  }
  try {
    assert.deepStrictEqual(manifest.eventCounts.byEvent, metrics.records.byEvent)
    assert.equal(manifest.eventCounts.total, metrics.records.body)
    assert.deepStrictEqual(
      manifest.eventCounts.agents,
      metrics.records.agents.map((agent) => ({
        agentId: agent.agentId,
        records: agent.records,
        byEvent: agent.byEvent
      }))
    )
    assert.deepStrictEqual(manifest.provenance.decisionsByStamp, metrics.cognition.decisionsByStamp)
  } catch {
    errors.push('manifest derived counts do not match metrics.json')
  }
  for (const stamp of manifest.provenance.decisionsByStamp) {
    const expectedModel =
      stamp.tier === 'PIVOTAL'
        ? manifest.provenance.models.pivotal
        : manifest.provenance.models.ordinary
    const promptKey = stamp.tier.toLowerCase()
    if (stamp.model !== expectedModel) {
      errors.push(`decision provenance for ${stamp.tier} does not match the configured model`)
    }
    if (stamp.promptVersion !== manifest.provenance.prompts[promptKey]) {
      errors.push(`decision provenance for ${stamp.tier} does not match the configured prompt`)
    }
  }

  if (manifest.sourceCommitShort !== manifest.sourceCommit.slice(0, manifest.sourceCommitShort.length)) {
    errors.push('sourceCommitShort is not a prefix of sourceCommit')
  }
  const sortedAgentIds = [...manifest.agents].map((agent) => agent.agentId).sort()
  if (manifest.agents.map((agent) => agent.agentId).join('\0') !== sortedAgentIds.join('\0')) {
    errors.push('agents are not in canonical agent-id order')
  }
  for (const [index, agent] of manifest.agents.entries()) {
    const expected = offlineIdentity(agent.agentId)
    if (agent.username !== expected.username || agent.uuid !== expected.uuid) {
      errors.push(`agent ${agent.agentId} does not carry its deterministic offline identity`)
    }
    if (agent.controlPort !== manifest.agents[0].controlPort + index) {
      errors.push('agent control ports are not consecutive in canonical order')
    }
    if (agent.processDirectory !== `bodies/${agent.username}`) {
      errors.push(`agent ${agent.agentId} process directory is not its deterministic relative directory`)
    }
  }
  if (new Set(manifest.agents.map((agent) => agent.controlPort)).size !== manifest.agents.length) {
    errors.push('agent control ports are not unique')
  }
  const allPorts = [
    manifest.network.minecraftPort,
    manifest.network.cognitionProxyPort,
    manifest.network.cognitionUpstreamPort,
    ...manifest.agents.map((agent) => agent.controlPort)
  ]
  if (new Set(allPorts).size !== allPorts.length) errors.push('manifest listeners do not have distinct ports')
  if (manifest.cognition.actualForwardedCalls !== metrics.cognition.forwardedCalls) {
    errors.push('actualForwardedCalls does not match evidence')
  }
  if (manifest.cognition.actualRefusedCalls !== metrics.cognition.refusedCalls) {
    errors.push('actualRefusedCalls does not match evidence')
  }
  if (manifest.cognition.actualExternalSpendUsd !== metrics.cognition.externalSpendUsd) {
    errors.push('actualExternalSpendUsd does not match provider usage evidence')
  }
  if (manifest.cognition.actualForwardedCalls > manifest.cognition.globalCallCeiling) {
    errors.push('forwarded cognition calls exceeded the global ceiling')
  }
  if (manifest.cognition.actualExternalSpendUsd > manifest.cognition.maxExternalSpendUsd) {
    errors.push('actual external spend exceeded the declared ceiling')
  }
  let maximumSpendNanoUsd = 0
  for (const model of new Set(Object.values(manifest.provenance.models))) {
    const rates = manifest.cognition.pricing.models[model]
    if (rates === undefined) continue
    const inputRate = Math.max(
      rates.inputNanoUsdPerToken,
      rates.cachedInputNanoUsdPerToken
    )
    maximumSpendNanoUsd = Math.max(
      maximumSpendNanoUsd,
      (manifest.cognition.maxRequestBytes * inputRate) +
        (manifest.cognition.maxOutputTokens * rates.outputNanoUsdPerToken)
    )
  }
  maximumSpendNanoUsd *= manifest.cognition.globalCallCeiling
  if (
    manifest.cognition.worstCaseConfiguredSpendUsd !==
    Number((maximumSpendNanoUsd / NANO_USD_PER_USD).toFixed(9))
  ) {
    errors.push('worstCaseConfiguredSpendUsd does not match the call and token bounds')
  }
  if (maximumSpendNanoUsd > manifest.cognition.maxExternalSpendUsd * NANO_USD_PER_USD) {
    errors.push('declared external spend ceiling is below the worst-case call and token bounds')
  }
  const perAgentCalls = new Map()
  let providerUsageCalls = 0
  const forwardedRequests = new Set()
  const forwardedDecisions = new Map()
  const usageRequests = new Map()
  const usageRecords = []
  for (const record of records) {
    if (record.source !== 'harness') continue
    if (record.event === 'cognitionForwarded') {
      perAgentCalls.set(record.agentId, (perAgentCalls.get(record.agentId) ?? 0) + 1)
      forwardedRequests.add(record.requestKey)
      if (record.outcome === 'decision') {
        forwardedDecisions.set(record.requestKey, record.provenance?.model)
      }
    }
    if (record.event === 'providerUsage') {
      providerUsageCalls += 1
      usageRecords.push(record)
      if (usageRequests.has(record.requestKey)) {
        errors.push(`provider usage duplicates ${record.requestKey}`)
      }
      usageRequests.set(record.requestKey, record.model)
      const rates = manifest.cognition.pricing.models[record.model]
      if (rates === undefined) {
        errors.push(`provider usage names unpriced model ${record.model}`)
        continue
      }
      const expected =
        ((record.promptTokens - record.cachedPromptTokens) * rates.inputNanoUsdPerToken) +
        (record.cachedPromptTokens * rates.cachedInputNanoUsdPerToken) +
        (record.completionTokens * rates.outputNanoUsdPerToken)
      if (record.costNanoUsd !== expected) {
        errors.push(`provider usage ${record.requestKey} has the wrong pinned-price cost`)
      }
      if (
        record.promptTokens > manifest.cognition.maxRequestBytes ||
        record.completionTokens > manifest.cognition.maxOutputTokens
      ) {
        errors.push(`provider usage ${record.requestKey} crossed a token bound`)
      }
    }
  }
  for (const record of usageRecords) {
    if (!forwardedRequests.has(record.requestKey)) {
      errors.push(`provider usage ${record.requestKey} has no forwarded cognition request`)
    }
  }
  if (manifest.provenance.backend !== 'scripted') {
    for (const [requestKey, model] of forwardedDecisions) {
      const usageModel = usageRequests.get(requestKey)
      if (usageModel === undefined) {
        errors.push(`successful live cognition request ${requestKey} has no provider usage`)
      } else if (usageModel !== model) {
        errors.push(`provider usage ${requestKey} does not match its routed decision model`)
      }
    }
  }
  if (providerUsageCalls !== metrics.cognition.providerUsageCalls) {
    errors.push('provider usage call count does not match evidence')
  }
  if (providerUsageCalls > manifest.cognition.actualForwardedCalls) {
    errors.push('provider usage exceeds forwarded cognition calls')
  }
  if (manifest.provenance.backend === 'scripted' && providerUsageCalls !== 0) {
    errors.push('scripted cognition cannot have provider usage')
  }
  for (const [agentId, count] of perAgentCalls) {
    if (count > manifest.cognition.perAgentCallCeiling) {
      errors.push(`${agentId} exceeded the per-agent cognition call ceiling`)
    }
  }
  if (manifest.timing.wallDurationMillis > manifest.bounds.runDurationSeconds * 1000 + 10_000) {
    errors.push('wall duration exceeded the run ceiling plus bounded shutdown allowance')
  }
  if (manifest.timing.tickSpan > manifest.bounds.simulationTickCeiling) {
    errors.push('simulation tick span exceeded its ceiling')
  }
  if (manifest.environment.world.worldBorderDiameterBlocks > manifest.environment.world.maxWorldSizeBlocks) {
    errors.push('world border diameter exceeds max world size')
  }

  const readme = fs.readFileSync(path.join(root, README_FILE), 'utf8')
  for (const heading of [
    '## Setup facts',
    '## Observations',
    '## Interpretations',
    '## Counterevidence and null results',
    '## Limitations'
  ]) {
    if (!readme.includes(heading)) errors.push(`README is missing ${heading}`)
  }
  for (const name of names) {
    const file = path.join(root, name)
    const text = fs.readFileSync(file, 'utf8')
    for (const match of scanText(text)) {
      errors.push(`${name} contains ${match.count} match(es) for ${match.pattern}`)
    }
  }
  return errors
}

function validateArtifact (artifactDirectory) {
  const file = path.join(artifactDirectory, MANIFEST_FILE)
  if (!fs.existsSync(file)) {
    return { ok: false, version: null, errors: [`${file} is missing`] }
  }
  let manifest
  try {
    manifest = readJson(file)
  } catch (cause) {
    return { ok: false, version: null, errors: [`${file}: ${cause.message}`] }
  }
  const schema = validateManifest(manifest)
  if (!schema.ok) return schema
  const errors = semanticErrors(artifactDirectory, manifest)
  return { ok: errors.length === 0, version: schema.version, errors }
}

function publishArtifact ({
  config,
  source,
  components,
  tools,
  startedAt,
  endedAt,
  stopReason,
  rawBodyEvidence,
  rawHarnessEvidence,
  artifactDirectory,
  warnings = []
}) {
  assertRunConfig(config)
  if (fs.existsSync(artifactDirectory)) {
    throw new Error(`artifact directory already exists: ${artifactDirectory}`)
  }
  fs.mkdirSync(path.dirname(artifactDirectory), { recursive: true })
  fs.mkdirSync(artifactDirectory, { recursive: false })
  try {
    const evidenceFile = path.join(artifactDirectory, EVIDENCE_FILE)
    const normalized = normalizeEvidence({
      experimentId: config.experimentId,
      bodyFile: rawBodyEvidence,
      harnessFile: rawHarnessEvidence,
      outputFile: evidenceFile,
      maxInputBytes: config.bounds.logBytes,
      maxOutputBytes: config.bounds.artifactBytes,
      maxRecords: config.bounds.evidenceRecords
    })
    const evidenceSha = sha256File(evidenceFile)
    const metricValue = deriveMetrics(normalized.records, configuredAgents(config))
    const metricsFile = path.join(artifactDirectory, METRICS_FILE)
    writeJson(metricsFile, metricValue)
    const metricsStat = fs.statSync(metricsFile)
    const metricMetadata = {
      value: metricValue,
      sha256: sha256File(metricsFile),
      bytes: metricsStat.size
    }
    const manifest = buildManifest({
      config,
      source,
      components,
      tools,
      startedAt,
      endedAt,
      stopReason,
      evidence: {
        sha256: evidenceSha,
        records: normalized.records.length,
        bytes: normalized.bytes,
        redactions: normalized.redactions
      },
      metrics: metricMetadata,
      warnings,
      validation: {
        beforeLaunch: true,
        beforePublication: true
      }
    })
    fs.writeFileSync(path.join(artifactDirectory, README_FILE), renderReadme(config, manifest, metricValue), {
      encoding: 'utf8',
      flag: 'wx'
    })
    fs.writeFileSync(path.join(artifactDirectory, MANIFEST_FILE), canonicalJson(manifest), {
      encoding: 'utf8',
      flag: 'wx'
    })

    const validation = validateArtifact(artifactDirectory)
    if (!validation.ok) {
      throw new Error(`published artifact is invalid:\n- ${validation.errors.join('\n- ')}`)
    }
    return { manifest, metrics: metricValue, validation }
  } catch (cause) {
    fs.rmSync(artifactDirectory, { recursive: true, force: true })
    throw cause
  }
}

function renderReadme (config, manifest, metrics) {
  const observationLines = metrics.findings.observations.length > 0
    ? metrics.findings.observations
    : ['No bounded social or spatial observation class was recorded.']
  const scripted = config.cognition.backend === 'scripted'
  const questionQualification = scripted
    ? 'This scripted proof tests the harness and runtime composition. It does not test model-driven emergence.'
    : 'This live-model run observes independently routed agents without assigned roles, pairings, social goals, or a scripted outcome.'
  const interpretationQualification = scripted
    ? '- Scripted decisions are acceptance traffic. They are not evidence of cooperation, specialization, avoidance,\n  conflict, settlement, or any other emergent social outcome.'
    : '- Model-driven actions are observations, not proof of motive or emergence. Any social or spatial interpretation is limited to the recorded evidence and counterevidence.'
  return `# ${config.title}

This directory is the sanitized, bounded publication from \`${config.experimentId}\`.
Every number below is generated from [evidence.jsonl](./evidence.jsonl) through the
committed research publisher; [metrics.json](./metrics.json) is the complete deterministic derivation.

## Question

Can three independently embodied Loom agents continue repeated perceive/decide/act cycles in one
bounded world, while one body process fails, without corrupting peer evidence or leaving processes behind?
${questionQualification}

## Setup facts

- Source commit: \`${manifest.sourceCommit}\`.
- Paper ${manifest.environment.server.minecraftVersion}, build ${manifest.environment.server.paperBuild}, fixed seed \`${manifest.environment.world.seed}\`.
- Agents: ${manifest.agents.map((agent) => `\`${agent.agentId}\` as \`${agent.username}\``).join(', ')}.
- Cognition: \`${manifest.provenance.backend}\`, ${manifest.cognition.globalCallCeiling} global calls,
  ${manifest.cognition.perAgentCallCeiling} per agent, ${manifest.cognition.globalOutstandingCeiling} outstanding.
- External spend: $${manifest.cognition.actualExternalSpendUsd.toFixed(9)} provider-reported actual,
  $${manifest.cognition.worstCaseConfiguredSpendUsd.toFixed(9)} worst case under the call/token bounds,
  and $${manifest.cognition.maxExternalSpendUsd.toFixed(6)} absolute declared ceiling.
- Wall-clock ceiling: ${manifest.bounds.runDurationSeconds}s. Simulation ceiling: ${manifest.bounds.simulationTickCeiling} ticks.
- The server, body control, and cognition listeners were loopback-only; no real player account was used.

## Observations

${observationLines.map((line) => `- ${line}`).join('\n')}
- ${metrics.cycles.perceived} perceptions produced ${metrics.cycles.decided} decisions and ${metrics.cycles.applied} applied intents.
- ${metrics.crashContainment.controlledCrashes.length} body process was deliberately crashed; peer decisions after it:
  ${JSON.stringify(metrics.crashContainment.peerDecisionsAfterCrash)}.
- Cleanup verification recorded: ${metrics.crashContainment.cleanupVerified}.

## Interpretations

- The run supports the narrower claim that the bounded fleet, cognition bridge, evidence chain, crash isolation,
  publication, and cleanup worked together under the declared conditions.
${interpretationQualification}

## Counterevidence and null results

${metrics.findings.counterevidence.map((line) => `- ${line}`).join('\n')}
- A null social result remains valid evidence: the publisher records it instead of replacing it with a story.

## Limitations

${metrics.findings.limitations.map((line) => `- ${line}`).join('\n')}
- The disposable world, server logs, raw provider failures, death ledger, credentials, and process metadata are not committed.
- One short run cannot establish persistence over days, durable relationships, or repeatability across seeds.

## Reproduction

From a clean checkout, install the three pinned toolchains, then run:

\`\`\`sh
node research/bin/loom-tribe.js run --config research/config/${path.basename(config.configFile ?? 'first-tribe-scripted-proof.json')}
\`\`\`

The harness validates a prospective manifest before launch, builds the three runtime components, verifies the
pinned Paper jar, creates a disposable server, enforces call and lifecycle bounds, publishes only normalized
evidence, validates it again, and removes the mutable run directory.
`
}

function componentSourceHashes (repositoryRoot) {
  const tracked = (directory) => {
    const root = path.join(repositoryRoot, directory)
    const listed = execFileSync('git', ['ls-files', '-z', '--', directory], {
      cwd: repositoryRoot,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe']
    })
    const prefix = `${directory}/`
    const files = listed
      .split('\0')
      .filter(Boolean)
      .map((file) => {
        if (!file.startsWith(prefix)) throw new Error(`git returned a file outside ${directory}: ${file}`)
        return file.slice(prefix.length)
      })
    return sourceTreeSha256(root, files)
  }
  return {
    plugin: tracked('plugin'),
    body: tracked('body'),
    cognition: tracked('cognition')
  }
}

module.exports = {
  ALLOWED_ARTIFACT_FILES,
  EVIDENCE_FILE,
  MANIFEST_FILE,
  METRICS_FILE,
  README_FILE,
  assertRunConfig,
  buildManifest,
  componentSourceHashes,
  configuredAgents,
  prospectiveManifest,
  publishArtifact,
  semanticErrors,
  validateArtifact,
  validateProspectiveManifest,
  validateRunConfig
}
