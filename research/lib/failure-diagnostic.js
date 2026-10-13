'use strict'

const fs = require('node:fs')
const path = require('node:path')

const { validatedCognitionFailure } = require('./cognition-failure.js')
const { scanText } = require('./evidence.js')
const { canonicalJson, sortedValue } = require('./util.js')

const FAILURE_DIAGNOSTIC_VERSION = 'loom-failed-run-diagnostic/2'
const MAX_FAILURE_DIAGNOSTIC_BYTES = 64 * 1024
const MAX_INPUT_LINE_BYTES = 256 * 1024
const BODY_EVENTS = new Set([
  'attacked',
  'bodyDisconnected',
  'cognitionUnavailable',
  'decided',
  'decisionDiscarded',
  'despawned',
  'died',
  'intentApplied',
  'moved',
  'perceived',
  'slept',
  'spawned',
  'spoke',
  'witnessDenied',
  'witnessDisputed',
  'witnessQuit',
  'witnessRevived',
  'worldInteraction'
])
const HARNESS_EVENTS = new Set([
  'cleanupVerified',
  'cognitionForwarded',
  'cognitionProxyReady',
  'cognitionRefused',
  'cognitionStimulusAttested',
  'controlledBodyCrash',
  'experimentEnded',
  'experimentStarted',
  'initialConditionsAttested',
  'postCrashCallReserveReleased',
  'preflightValidated',
  'processExited',
  'processGroupSignalled',
  'processStarted',
  'providerUsage',
  'runFailed'
])
const FORWARD_OUTCOMES = new Set([
  'decision',
  'cognitionFailure',
  'upstreamRefusal',
  'upstreamUnavailable'
])
const COGNITION_FAILURE_OUTCOMES = new Set([
  'cognitionFailure',
  'upstreamRefusal'
])
const REFUSAL_REASONS = new Set([
  'globalCallCeiling',
  'globalOutstandingCeiling',
  'perAgentCallCeiling',
  'postCrashCallReserve'
])
const STOP_REASONS = new Set([
  'cancelled',
  'cognitionFailed',
  'diskFailure',
  'logLimit',
  'paperFailed',
  'simulationTickCeiling',
  'startupFailed'
])

function increment (counter, key) {
  counter[key] = (counter[key] ?? 0) + 1
}

function boundedJsonLines (file, limits) {
  const metadata = {
    available: false,
    sourceBytes: 0,
    bytesRead: 0,
    validRecords: 0,
    invalidRecords: 0,
    truncatedAtByteBound: false,
    recordLimitReached: false,
    partialTailIgnored: false
  }
  let descriptor
  try {
    const stat = fs.statSync(file)
    metadata.available = true
    metadata.sourceBytes = stat.size
    const bytesToRead = Math.min(stat.size, limits.maxBytes)
    const buffer = Buffer.alloc(bytesToRead)
    descriptor = fs.openSync(file, 'r')
    metadata.bytesRead = fs.readSync(descriptor, buffer, 0, bytesToRead, 0)
    const text = buffer.subarray(0, metadata.bytesRead).toString('utf8')
    metadata.truncatedAtByteBound = stat.size > bytesToRead
    const lastNewline = text.lastIndexOf('\n')
    metadata.partialTailIgnored =
      metadata.truncatedAtByteBound ||
      (text.length > 0 && !text.endsWith('\n'))
    if (lastNewline === -1) return { metadata, records: [] }
    const complete = text.slice(0, lastNewline)
    if (complete.length === 0) return { metadata, records: [] }
    const lines = complete.split('\n')
    const records = []
    for (const line of lines) {
      if (records.length + metadata.invalidRecords >= limits.maxRecords) {
        metadata.recordLimitReached = true
        break
      }
      if (Buffer.byteLength(line) > MAX_INPUT_LINE_BYTES) {
        metadata.invalidRecords += 1
        continue
      }
      try {
        const record = JSON.parse(line)
        if (record === null || typeof record !== 'object' || Array.isArray(record)) {
          metadata.invalidRecords += 1
        } else {
          records.push(record)
        }
      } catch {
        metadata.invalidRecords += 1
      }
    }
    metadata.validRecords = records.length
    return { metadata, records }
  } catch (cause) {
    if (cause.code === 'ENOENT') return { metadata, records: [] }
    metadata.readFailed = true
    return { metadata, records: [] }
  } finally {
    if (descriptor !== undefined) fs.closeSync(descriptor)
  }
}

function emptyAgentSummary (agentId) {
  return {
    agentId,
    records: 0,
    events: {},
    postCrashEvents: {}
  }
}

function bodySummary (records, configuredAgentIds, crashAfterTick) {
  const agents = new Map(
    configuredAgentIds.map((agentId) => [agentId, emptyAgentSummary(agentId)])
  )
  const events = {}
  let firstTick
  let lastTick
  let invalidShapeRecords = 0
  let unknownAgentRecords = 0
  let worldRecords = 0
  let unclassifiedEvents = 0
  for (const record of records) {
    if (
      typeof record.agentId !== 'string' ||
      typeof record.event !== 'string' ||
      !Number.isInteger(record.tick) ||
      record.tick < 0
    ) {
      invalidShapeRecords += 1
      continue
    }
    firstTick = firstTick === undefined ? record.tick : Math.min(firstTick, record.tick)
    lastTick = lastTick === undefined ? record.tick : Math.max(lastTick, record.tick)
    if (BODY_EVENTS.has(record.event)) increment(events, record.event)
    else unclassifiedEvents += 1
    if (record.agentId === 'loom:world') {
      worldRecords += 1
      continue
    }
    const agent = agents.get(record.agentId)
    if (agent === undefined) {
      unknownAgentRecords += 1
      continue
    }
    agent.records += 1
    if (BODY_EVENTS.has(record.event)) {
      increment(agent.events, record.event)
      if (Number.isInteger(crashAfterTick) && record.tick > crashAfterTick) {
        increment(agent.postCrashEvents, record.event)
      }
    }
  }
  return sortedValue({
    firstTick: firstTick ?? null,
    lastTick: lastTick ?? null,
    events,
    agents: [...agents.values()],
    worldRecords,
    unknownAgentRecords,
    unclassifiedEvents,
    invalidShapeRecords
  })
}

function cognitionPhase () {
  return {
    forwarded: 0,
    forwardedByOutcome: {},
    refused: 0,
    refusedByReason: {}
  }
}

function harnessSummary (records, config) {
  const configuredAgentIds = config.agents
  const crashAgent = config.proof.crashAgent
  const configuredAgents = new Set(configuredAgentIds)
  const agents = new Map(configuredAgentIds.map((agentId) => [
    agentId,
    { agentId, beforeCrash: cognitionPhase(), afterCrash: cognitionPhase() }
  ]))
  const events = {}
  let crashIndex = -1
  let crashAfterTick
  let reserveReleased = false
  let cleanupVerified = false
  let invalidShapeRecords = 0
  let unclassifiedEvents = 0
  let unknownAgentRecords = 0
  let cognitionFailures = 0
  let unclassifiedCognitionFailures = 0
  const classifiedCognitionFailures = new Map()

  for (const [index, record] of records.entries()) {
    if (typeof record.event !== 'string') {
      invalidShapeRecords += 1
      continue
    }
    if (HARNESS_EVENTS.has(record.event)) increment(events, record.event)
    else unclassifiedEvents += 1
    if (
      crashIndex === -1 &&
      record.event === 'controlledBodyCrash' &&
      record.agentId === crashAgent
    ) {
      crashIndex = index
      if (Number.isInteger(record.afterTick) && record.afterTick >= 0) {
        crashAfterTick = record.afterTick
      }
    }
    if (
      record.event === 'postCrashCallReserveReleased' &&
      record.agentId === crashAgent
    ) {
      reserveReleased = true
    }
    if (record.event === 'cleanupVerified') cleanupVerified = true
    if (record.event !== 'cognitionForwarded' && record.event !== 'cognitionRefused') continue
    if (!configuredAgents.has(record.agentId)) {
      unknownAgentRecords += 1
      continue
    }
    const phase = crashIndex !== -1 && index > crashIndex ? 'afterCrash' : 'beforeCrash'
    const summary = agents.get(record.agentId)[phase]
    if (record.event === 'cognitionForwarded') {
      summary.forwarded += 1
      if (FORWARD_OUTCOMES.has(record.outcome)) {
        increment(summary.forwardedByOutcome, record.outcome)
      } else {
        increment(summary.forwardedByOutcome, 'unclassified')
      }
      if (COGNITION_FAILURE_OUTCOMES.has(record.outcome)) {
        cognitionFailures += 1
        const classified = validatedCognitionFailure(record.failure, {
          models: config.cognition.models,
          importance: record.importance
        })
        if (classified === null) {
          unclassifiedCognitionFailures += 1
        } else {
          const key = JSON.stringify(classified)
          const existing = classifiedCognitionFailures.get(key)
          if (existing === undefined) {
            classifiedCognitionFailures.set(key, {
              ...classified,
              count: 1
            })
          } else {
            existing.count += 1
          }
        }
      }
    } else {
      summary.refused += 1
      if (REFUSAL_REASONS.has(record.reason)) {
        increment(summary.refusedByReason, record.reason)
      } else {
        increment(summary.refusedByReason, 'unclassified')
      }
    }
  }

  return {
    summary: sortedValue({
      events,
      agents: [...agents.values()],
      failureClassifications: {
        total: cognitionFailures,
        classified: cognitionFailures - unclassifiedCognitionFailures,
        unclassified: unclassifiedCognitionFailures,
        byClassification: [...classifiedCognitionFailures.entries()]
          .sort(([left], [right]) => left < right ? -1 : left > right ? 1 : 0)
          .map(([, value]) => value)
      },
      unknownAgentRecords,
      unclassifiedEvents,
      invalidShapeRecords
    }),
    crash: {
      observed: crashIndex !== -1,
      afterTick: crashAfterTick ?? null,
      reserveReleased,
      cleanupVerified
    }
  }
}

function providerUsageSummary (records, config) {
  const configuredAgents = new Set(config.agents)
  const configuredModels = new Set([
    config.cognition.models.ordinary,
    config.cognition.models.pivotal
  ])
  const byModel = Object.fromEntries(
    [...configuredModels].sort().map((model) => [
      model,
      {
        calls: 0,
        promptTokens: 0,
        cachedPromptTokens: 0,
        completionTokens: 0,
        totalTokens: 0,
        costNanoUsd: 0
      }
    ])
  )
  let invalidRecords = 0
  let externalSpendNanoUsd = 0
  for (const record of records) {
    const tokenFields = [
      'promptTokens',
      'cachedPromptTokens',
      'completionTokens',
      'totalTokens'
    ]
    if (
      record.schemaVersion !== 'loom-provider-usage/1' ||
      !configuredAgents.has(record.agentId) ||
      !configuredModels.has(record.model) ||
      !tokenFields.every((field) => Number.isSafeInteger(record[field]) && record[field] >= 0) ||
      record.cachedPromptTokens > record.promptTokens ||
      record.promptTokens + record.completionTokens !== record.totalTokens
    ) {
      invalidRecords += 1
      continue
    }
    const rates = config.cognition.pricing.models[record.model]
    const uncachedPromptTokens = record.promptTokens - record.cachedPromptTokens
    const costNanoUsd =
      (uncachedPromptTokens * rates.inputNanoUsdPerToken) +
      (record.cachedPromptTokens * rates.cachedInputNanoUsdPerToken) +
      (record.completionTokens * rates.outputNanoUsdPerToken)
    if (!Number.isSafeInteger(costNanoUsd) || costNanoUsd < 0) {
      invalidRecords += 1
      continue
    }
    const model = byModel[record.model]
    model.calls += 1
    for (const field of tokenFields) model[field] += record[field]
    model.costNanoUsd += costNanoUsd
    externalSpendNanoUsd += costNanoUsd
  }
  return sortedValue({
    byModel,
    invalidRecords,
    externalSpendNanoUsd,
    externalSpendUsd: Number((externalSpendNanoUsd / 1_000_000_000).toFixed(9))
  })
}

function safeCount (value) {
  return Number.isSafeInteger(value) && value >= 0 ? value : null
}

function safeProxySnapshot (snapshot, config) {
  if (snapshot === undefined || snapshot === null) return null
  const peers = [...config.agents]
    .filter((agent) => agent !== config.proof.crashAgent)
    .sort()
  return {
    forwardedCalls: safeCount(snapshot.forwardedCalls),
    outstandingCalls: safeCount(snapshot.outstandingCalls),
    callsByAgent: Object.fromEntries(
      [...config.agents].sort().map((agent) => [
        agent,
        safeCount(snapshot.callsByAgent?.[agent])
      ])
    ),
    postCrashReserve: {
      crashAgent: config.proof.crashAgent,
      callsPerPeer: config.proof.postCrashCallsPerPeer,
      released: snapshot.postCrashReserve?.released === true,
      remainingByPeer: Object.fromEntries(
        peers.map((agent) => [
          agent,
          safeCount(snapshot.postCrashReserve?.remainingByPeer?.[agent])
        ])
      )
    }
  }
}

function stableStopReason (failure) {
  return STOP_REASONS.has(failure?.stopReason)
    ? failure.stopReason
    : 'unclassifiedFailure'
}

function failureCode (failure) {
  const message = typeof failure?.message === 'string' ? failure.message : ''
  if (message === 'two peers did not decide after the controlled crash') {
    return 'postCrashPeerDecisionRequirement'
  }
  if (/model provider rejected Loom's credential \(HTTP (?:401|403)\)/.test(message)) {
    return 'providerAuthenticationRejected'
  }
  const byStopReason = {
    cancelled: 'runCancelled',
    cognitionFailed: 'cognitionProcessOrProviderFailure',
    diskFailure: 'boundedJournalFailure',
    logLimit: 'logByteLimitReached',
    paperFailed: 'paperProcessFailure',
    simulationTickCeiling: 'simulationTickCeilingReached',
    startupFailed: 'startupOrAcceptanceFailure'
  }
  return byStopReason[failure?.stopReason] ?? 'unclassifiedFailure'
}

function failureDiagnostic ({
  config,
  source,
  failure,
  startedAt,
  endedAt,
  retainedAt,
  cleanupVerified,
  bodyInput,
  harnessInput,
  providerUsageInput,
  proxySnapshot
}) {
  const harness = harnessSummary(harnessInput.records, config)
  const body = bodySummary(
    bodyInput.records,
    config.agents,
    harness.crash.afterTick
  )
  return sortedValue({
    schemaVersion: FAILURE_DIAGNOSTIC_VERSION,
    experimentId: config.experimentId,
    sourceCommit: source.commit,
    failure: {
      code: failureCode(failure),
      stopReason: stableStopReason(failure)
    },
    timing: {
      startedAt: startedAt ?? null,
      endedAt: endedAt ?? retainedAt,
      retainedAt
    },
    configuredConditions: {
      agents: [...config.agents].sort(),
      cognitionBackend: config.cognition.backend,
      models: [...new Set([
        config.cognition.models.ordinary,
        config.cognition.models.pivotal
      ])].sort(),
      callBounds: {
        perAgent: config.cognition.perAgentCallCeiling,
        global: config.cognition.globalCallCeiling,
        outstanding: config.cognition.globalOutstandingCeiling
      },
      durationSeconds: config.bounds.runDurationSeconds,
      simulationTickCeiling: config.bounds.simulationTickCeiling,
      controlledCrash: {
        agentId: config.proof.crashAgent,
        afterSeconds: config.proof.crashAfterSeconds,
        reservedCallsPerPeer: config.proof.postCrashCallsPerPeer
      }
    },
    cleanup: {
      verifiedByListenerProbe: cleanupVerified === true,
      cleanupRecordObserved: harness.crash.cleanupVerified
    },
    inputs: {
      bodyEvidence: bodyInput.metadata,
      harnessEvidence: harnessInput.metadata,
      providerUsage: providerUsageInput.metadata
    },
    body,
    cognition: {
      harness: harness.summary,
      proxy: safeProxySnapshot(proxySnapshot, config),
      providerUsage: providerUsageSummary(providerUsageInput.records, config)
    },
    crashContainment: {
      crashObserved: harness.crash.observed,
      crashAfterTick: harness.crash.afterTick,
      reserveReleased: harness.crash.reserveReleased,
      peerDecisionsAfterCrash: Object.fromEntries(
        body.agents
          .filter((agent) => agent.agentId !== config.proof.crashAgent)
          .map((agent) => [
            agent.agentId,
            agent.postCrashEvents.decided ?? 0
          ])
      )
    },
    privacy: {
      aggregateOnly: true,
      credentialsRetained: false,
      localPathsRetained: false,
      processIdentifiersRetained: false,
      promptsOrReasoningRetained: false,
      providerRequestIdentifiersRetained: false,
      publicResearchEvidence: false
    }
  })
}

function writeFailureDiagnostic ({
  repositoryRoot,
  diagnosticRoot,
  config,
  source,
  failure,
  startedAt,
  endedAt,
  cleanupVerified,
  bodyFile,
  harnessFile,
  providerUsageFile,
  proxySnapshot,
  clock = () => new Date(),
  maxBytes = MAX_FAILURE_DIAGNOSTIC_BYTES
}) {
  const limits = {
    maxBytes: config.bounds.logBytes,
    maxRecords: config.bounds.evidenceRecords
  }
  const diagnostic = failureDiagnostic({
    config,
    source,
    failure,
    startedAt,
    endedAt,
    retainedAt: clock().toISOString(),
    cleanupVerified,
    bodyInput: boundedJsonLines(bodyFile, limits),
    harnessInput: boundedJsonLines(harnessFile, limits),
    providerUsageInput: boundedJsonLines(providerUsageFile, limits),
    proxySnapshot
  })
  const text = canonicalJson(diagnostic)
  const unsafe = scanText(text)
  if (unsafe.length > 0) {
    throw new Error('failed-run diagnostic contained unsafe material')
  }
  const bytes = Buffer.byteLength(text)
  if (bytes > maxBytes) {
    throw new Error(`failed-run diagnostic exceeded its ${maxBytes} byte bound`)
  }
  fs.mkdirSync(diagnosticRoot, { recursive: true })
  const file = path.join(diagnosticRoot, `${config.experimentId}.json`)
  fs.writeFileSync(file, text, { encoding: 'utf8', flag: 'wx', mode: 0o600 })
  const relative = path.relative(repositoryRoot, file)
  const label =
    relative !== '' &&
    !path.isAbsolute(relative) &&
    relative !== '..' &&
    !relative.startsWith(`..${path.sep}`)
      ? relative
      : path.basename(file)
  return { diagnostic, file, label, bytes }
}

module.exports = {
  FAILURE_DIAGNOSTIC_VERSION,
  MAX_FAILURE_DIAGNOSTIC_BYTES,
  boundedJsonLines,
  failureCode,
  writeFailureDiagnostic
}
