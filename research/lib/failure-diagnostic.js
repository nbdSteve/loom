'use strict'

const crypto = require('node:crypto')
const fs = require('node:fs')
const path = require('node:path')

const { REFUSAL_BOUNDS } = require('./budget-proxy.js')
const { admissionEndMetrics, controlledStopMetrics } = require('./controlled-stop.js')
const { deathMetrics } = require('./witnessed-death.js')
const { validatedCognitionFailure } = require('./cognition-failure.js')
const { scanText } = require('./evidence.js')
const { canonicalJson, sortedValue } = require('./util.js')

const FAILURE_DIAGNOSTIC_VERSION = 'loom-failed-run-diagnostic/3'
const FALLBACK_DIAGNOSTIC_VERSION = 'loom-failed-run-fallback-diagnostic/1'
const MAX_FAILURE_DIAGNOSTIC_BYTES = 64 * 1024
const MAX_FALLBACK_DIAGNOSTIC_BYTES = 8 * 1024
const MAX_REFUSAL_DETAILS = 32
const DIAGNOSTIC_STAGES = ['inputRead', 'summary', 'serialize', 'sanitize', 'sizeCheck', 'write']
const EXPERIMENT_ID = /^[0-9]{4}-[0-9]{2}-[0-9]{2}-[a-z0-9][a-z0-9-]{0,99}$/
const AGENT_ID = /^[a-z0-9][a-z0-9-]{0,31}$/
const COMMIT = /^[0-9a-f]{40}$/
const TRIGGER = /^[a-z][A-Za-z]{0,39}$/
const IMPORTANCES = new Set(['ROUTINE', 'NOTABLE', 'PIVOTAL'])
const ORIGINS = new Set(['nativeEventDriver', 'nativeGoalReviewSchedule', 'nativeGoalFollowUp'])
const REFUSAL_UNITS = new Set(['calls', 'nanoUsd'])
const MODES = new Set([
  'crash-containment',
  'routine-v12-primary-behavior',
  'routine-v13-goal-continuity',
  'routine-v14-grounded-walk',
  'routine-v15-grounded-walk',
  'routine-v16-grounded-walk',
  'routine-v17-working-goal',
  'routine-v18-offered-refs',
  'routine-v19-actionable-refs',
  'routine-v20-typed-goal',
  'routine-v21-grounded-step',
  'routine-v22-obtainable-goal',
  'routine-v23-own-evidence',
  'routine-v24-answered-goal',
  'routine-v25-collected-goal',
  'routine-v26-pursued-goal',
  'routine-v27-wooded-goal',
  'routine-v28-explored-goal',
  'routine-v29-night-goal',
  'routine-v30-horizon-goal',
  'routine-v31-discovery-goal',
  'routine-v32-survival-discovery'
])
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
  'worldInteraction',
  'ownDropPickup',
  'goalReviewDeferred'
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
  'postCrashCallReserve',
  'spendCeiling'
])
const REFUSAL_BOUND_SET = new Set(REFUSAL_BOUNDS)
const FAILURE_CODES = new Set([
  'artifactIntegrityFailed',
  'boundedJournalFailure',
  'cognitionBudgetRefused',
  'cognitionDecisionFailed',
  'cognitionProcessOrProviderFailure',
  'cognitionUpstreamUnavailable',
  'hostSuspended',
  'cognitionDrainTimedOut',
  'logByteLimitReached',
  'paperProcessFailure',
  'postCrashPeerDecisionRequirement',
  'providerAuthenticationRejected',
  'runCancelled',
  'simulationTickCeilingReached',
  'startupOrAcceptanceFailure'
])
const STOP_REASONS = new Set([
  'cancelled',
  'cognitionFailed',
  'diskFailure',
  'logLimit',
  'paperFailed',
  'publicationRefused',
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

// Events whose closed outcome tag says why a run failed; only a camelCase tag is kept.
const OUTCOME_EVENTS = new Set([
  'bodyDisconnected',
  'cognitionUnavailable',
  'decisionDiscarded',
  'spawnRejected',
  'witnessDenied',
  'witnessDisputed',
  'worldInteraction',
  'ownDropPickup',
  'goalReviewDeferred'
])
const OUTCOME_TAG = /^[a-z][A-Za-z]{0,63}$/

// A disputed world interaction keeps only closed facts read from its detail: which operation,
// the body's own outcome token, and whether vanilla recorded a matching effect at all.
const INTERACTION_OPERATIONS = new Set(['dig', 'collect', 'place', 'equip', 'craft', 'attack', 'sleep'])
const BODY_OUTCOME = /(?:^|; )body ([a-z][a-z_]{0,31})(?:;|$)/
const MAX_INTERACTION_DISPUTES = 32

function interactionDispute (record) {
  const detail = typeof record.detail === 'string' ? record.detail : ''
  const operation = detail.split(' ', 1)[0]
  const vanilla = detail.match(/(?:^|; )vanilla (no )?/)
  return {
    agentId: record.agentId,
    tick: record.tick,
    operation: INTERACTION_OPERATIONS.has(operation) ? operation : 'unclassified',
    bodyOutcome: detail.match(BODY_OUTCOME)?.[1] ?? 'unclassified',
    vanillaEffect: vanilla === null ? 'unclassified' : vanilla[1] === undefined ? 'found' : 'absent'
  }
}

// The most recent cognition traffic, in source order, so a failure can be attributed to
// provider latency, redundant native events or lost reviews. Closed facts only.
const MAX_TIMELINE_ENTRIES = 128
const ORIGIN_TAG = /^native(GoalReviewSchedule|EventDriver)$/

function closedTag (value, pattern) {
  return typeof value === 'string' && pattern.test(value) ? value : 'unclassified'
}

function boundedCount (value) {
  return Number.isSafeInteger(value) && value >= 0 ? value : null
}

function cognitionTimeline (bodyRecords, harnessRecords, configuredAgentIds) {
  const configured = new Set(configuredAgentIds)
  const forwarded = harnessRecords
    .filter((record) => (record.event === 'cognitionForwarded' || record.event === 'cognitionRefused') &&
      configured.has(record.agentId))
    .map((record) => ({
      agentId: record.agentId,
      event: record.event,
      trigger: closedTag(record.trigger, TRIGGER),
      origin: closedTag(record.origin, ORIGIN_TAG),
      outcome: closedTag(record.outcome ?? record.bound, OUTCOME_TAG),
      outstandingAtAdmission: boundedCount(record.outstandingAtAdmission),
      latencyMs: boundedCount(record.latencyMs)
    }))
  const body = bodyRecords
    .filter((record) => (record.event === 'perceived' || record.event === 'cognitionUnavailable' ||
      record.event === 'decided') && configured.has(record.agentId) && Number.isInteger(record.tick))
    .map((record) => ({
      agentId: record.agentId,
      tick: record.tick,
      event: record.event,
      trigger: closedTag(record.trigger, TRIGGER),
      ...(record.event === 'cognitionUnavailable' ? { outcome: closedTag(record.outcome, OUTCOME_TAG) } : {})
    }))
  return {
    forwarded: forwarded.slice(-MAX_TIMELINE_ENTRIES),
    forwardedOmitted: Math.max(0, forwarded.length - MAX_TIMELINE_ENTRIES),
    body: body.slice(-MAX_TIMELINE_ENTRIES),
    bodyOmitted: Math.max(0, body.length - MAX_TIMELINE_ENTRIES)
  }
}

const REVIEW_WINDOW_COUNTS = ['attempted', 'minimum', 'maximum']

// Each configured agent's scheduled reviews against the window the publication check holds it
// to - its death, a controlled stop, the end of admission, or the whole plan - as bare counts,
// so a failed `expectedNativeReviewsObserved` states which agent fell short and by how much.
// A fault in reading them answers null rather than costing the rest of the diagnostic.
function nativeReviewWindows (bodyRecords, harnessRecords, config) {
  try {
    const started = harnessRecords.find((record) => record.event === 'experimentStarted')?.startTick
    const ticks = bodyRecords.map((record) => record.tick).filter(Number.isInteger)
    const firstTick = Number.isInteger(started) ? started : Math.min(...ticks)
    const deaths = deathMetrics(bodyRecords, harnessRecords, firstTick)?.byAgent ?? {}
    const stop = controlledStopMetrics(bodyRecords, harnessRecords, firstTick)
    const admission = admissionEndMetrics(bodyRecords, harnessRecords, firstTick)
    const expected = config.primaryBehavior?.nativeGoalReviewSchedule?.expectedReviewsPerAgent ?? null
    const counted = (window, entry) => ({
      window,
      ...Object.fromEntries(REVIEW_WINDOW_COUNTS.map((count) => [count, boundedCount(entry?.[count])]))
    })
    return Object.fromEntries([...config.agents].sort().map((agentId) => {
      const death = deaths[agentId]
      if (death !== undefined && (stop === undefined || stop.stopTick === null || death.tick <= stop.stopTick)) {
        return [agentId, counted('death', death)]
      }
      if (stop !== undefined) return [agentId, counted('controlledStop', stop.nativeReviews?.byAgent?.[agentId])]
      if (admission !== undefined) return [agentId, counted('admissionEnd', admission.nativeReviews?.byAgent?.[agentId])]
      const attempted = harnessRecords.filter((record) =>
        ['cognitionForwarded', 'cognitionRefused'].includes(record.event) && record.agentId === agentId &&
        record.trigger === 'goalReview' && record.origin === 'nativeGoalReviewSchedule').length
      return [agentId, counted('plan', { attempted, minimum: expected, maximum: expected })]
    }))
  } catch {
    return null
  }
}

function bodySummary (records, configuredAgentIds, crashAfterTick) {
  const outcomes = {}
  const interactionDisputes = []
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
    if (OUTCOME_EVENTS.has(record.event)) {
      const tag = typeof record.outcome === 'string' && OUTCOME_TAG.test(record.outcome)
        ? record.outcome
        : 'unclassified'
      outcomes[record.event] ??= {}
      increment(outcomes[record.event], tag)
      if (
        record.event === 'worldInteraction' && tag.startsWith('disputed') &&
        agents.has(record.agentId) && interactionDisputes.length < MAX_INTERACTION_DISPUTES
      ) interactionDisputes.push(interactionDispute(record))
    }
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
    outcomesByEvent: outcomes,
    interactionDisputes,
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

// Closed fields only: the agent, bound, numbers, and origin are enough to say which
// configured ceiling stopped a call without retaining the request or its perception.
function typedRefusal (record) {
  return {
    agentId: record.agentId,
    bound: REFUSAL_BOUND_SET.has(record.bound) ? record.bound : 'unclassified',
    unit: REFUSAL_UNITS.has(record.unit) ? record.unit : null,
    ceiling: safeCount(record.ceiling),
    observed: safeCount(record.observed),
    forwarded: record.forwarded === false ? false : null,
    origin: ORIGINS.has(record.origin) ? record.origin : null,
    trigger: typeof record.trigger === 'string' && TRIGGER.test(record.trigger)
      ? record.trigger
      : null,
    importance: IMPORTANCES.has(record.importance) ? record.importance : null
  }
}

function harnessSummary (records, config) {
  const configuredAgentIds = config.agents
  const crashAgent = config.proof?.crashAgent ?? null
  const configuredAgents = new Set(configuredAgentIds)
  const refusals = []
  let refusalsOmitted = 0
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
      if (refusals.length < MAX_REFUSAL_DETAILS) refusals.push(typedRefusal(record))
      else refusalsOmitted += 1
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
      refusals,
      refusalsOmitted,
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

function safeRefusedByBound (value) {
  return Object.fromEntries(
    REFUSAL_BOUNDS
      .filter((bound) => safeCount(value?.[bound]) !== null)
      .map((bound) => [bound, value[bound]])
  )
}

function safeProxySnapshot (snapshot, config) {
  if (snapshot === undefined || snapshot === null) return null
  const peers = [...config.agents]
    .filter((agent) => agent !== config.proof?.crashAgent)
    .sort()
  return {
    forwardedCalls: safeCount(snapshot.forwardedCalls),
    outstandingCalls: safeCount(snapshot.outstandingCalls),
    refusedByBound: safeRefusedByBound(snapshot.refusedByBound),
    callsByAgent: Object.fromEntries(
      [...config.agents].sort().map((agent) => [
        agent,
        safeCount(snapshot.callsByAgent?.[agent])
      ])
    ),
    postCrashReserve: config.proof === undefined
      ? null
      : {
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
  if (FAILURE_CODES.has(failure?.code)) return failure.code
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
    publicationRefused: 'artifactIntegrityFailed',
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
      mode: config.mode ?? 'crash-containment',
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
      maxExternalSpendUsd: config.cognition.maxExternalSpendUsd,
      durationSeconds: config.bounds.runDurationSeconds,
      simulationTickCeiling: config.bounds.simulationTickCeiling,
      controlledCrash: config.proof === undefined
        ? null
        : {
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
      providerUsage: providerUsageSummary(providerUsageInput.records, config),
      timeline: cognitionTimeline(bodyInput.records, harnessInput.records, config.agents),
      nativeReviewWindows: nativeReviewWindows(bodyInput.records, harnessInput.records, config)
    },
    crashContainment: config.proof === undefined
      ? null
      : {
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

// Most numbered names a diagnostic tries beside an earlier one for the same experiment.
const MAX_DIAGNOSTIC_NAMES = 100

// Publishes text under `file` only whole and never over an existing file: a partial
// write stays at a temporary name, and link(2) refuses an existing target. A rehearsal id is
// reused by every rehearsal, so a target an earlier failure already holds is kept and the new
// diagnostic takes the next numbered name (`<id>.2.json`); answers the name it was published
// under. A second rehearsal failure once lost its whole diagnostic to the first's file.
function writeExclusiveAtomic (file, text) {
  const directory = path.dirname(file)
  fs.mkdirSync(directory, { recursive: true, mode: 0o700 })
  const temporary = path.join(
    directory,
    `.${path.basename(file)}.${process.pid}.${crypto.randomBytes(6).toString('hex')}.partial`
  )
  let descriptor
  try {
    descriptor = fs.openSync(temporary, 'wx', 0o600)
    fs.writeSync(descriptor, text)
    fs.fsyncSync(descriptor)
    fs.closeSync(descriptor)
    descriptor = undefined
    fs.chmodSync(temporary, 0o600)
    return linkUnderFreeName(temporary, file)
  } finally {
    if (descriptor !== undefined) fs.closeSync(descriptor)
    fs.rmSync(temporary, { force: true })
  }
}

function linkUnderFreeName (temporary, file) {
  const extension = path.extname(file)
  const stem = file.slice(0, file.length - extension.length)
  for (let attempt = 1; attempt <= MAX_DIAGNOSTIC_NAMES; attempt += 1) {
    const target = attempt === 1 ? file : `${stem}.${attempt}${extension}`
    try {
      fs.linkSync(temporary, target)
      return target
    } catch (cause) {
      if (cause?.code !== 'EEXIST') throw cause
    }
  }
  throw new Error(`no free diagnostic name beside ${path.basename(file)}`)
}

function retainedLabel (repositoryRoot, file) {
  const relative = path.relative(repositoryRoot, file)
  return relative !== '' &&
    !path.isAbsolute(relative) &&
    relative !== '..' &&
    !relative.startsWith(`..${path.sep}`)
    ? relative
    : path.basename(file)
}

const DEFAULT_STAGES = Object.freeze({
  readInput: boundedJsonLines,
  summarize: failureDiagnostic,
  serialize: canonicalJson,
  scan: scanText,
  write: writeExclusiveAtomic
})

// Independent of the normal reader so a fault there cannot erase the spend record.
function fallbackProviderUsage (file, config) {
  try {
    const stat = fs.statSync(file)
    if (stat.size > config.bounds.logBytes) return { status: 'overBound' }
    const text = fs.readFileSync(file, 'utf8')
    const lines = text.slice(0, Math.max(0, text.lastIndexOf('\n'))).split('\n').filter(Boolean)
    if (lines.length > config.bounds.evidenceRecords) return { status: 'overBound' }
    const records = lines.map((line) => {
      try {
        return JSON.parse(line)
      } catch {
        return null
      }
    }).filter((record) => record !== null && typeof record === 'object')
    return { status: 'aggregated', ...providerUsageSummary(records, config) }
  } catch (cause) {
    return { status: cause?.code === 'ENOENT' ? 'absent' : 'unreadable' }
  }
}

// A string survives only when it matches its closed shape and the public secret scan.
function closedString (value, pattern) {
  return typeof value === 'string' && pattern.test(value) && scanText(value).length === 0
}

function fallbackDiagnostic ({
  config,
  source,
  failure,
  failedStage,
  retainedAt,
  cleanupVerified,
  providerUsageFile,
  proxySnapshot
}) {
  const agents = Array.isArray(config?.agents)
    ? [...config.agents].filter((agent) => closedString(agent, AGENT_ID)).sort().slice(0, 16)
    : []
  let proxy = null
  if (proxySnapshot !== null && typeof proxySnapshot === 'object') {
    proxy = {
      forwardedCalls: safeCount(proxySnapshot.forwardedCalls),
      outstandingCalls: safeCount(proxySnapshot.outstandingCalls),
      refusedByBound: safeRefusedByBound(proxySnapshot.refusedByBound),
      callsByAgent: Object.fromEntries(
        agents.map((agent) => [agent, safeCount(proxySnapshot.callsByAgent?.[agent])])
      )
    }
  }
  const usage = fallbackProviderUsage(providerUsageFile, config)
  const providerUsage = usage.status === 'aggregated'
    ? {
        status: 'aggregated',
        calls: Object.values(usage.byModel).reduce((sum, model) => sum + model.calls, 0),
        invalidRecords: usage.invalidRecords,
        byModel: usage.byModel,
        externalSpendNanoUsd: usage.externalSpendNanoUsd,
        externalSpendUsd: usage.externalSpendUsd
      }
    : { status: usage.status }
  return sortedValue({
    schemaVersion: FALLBACK_DIAGNOSTIC_VERSION,
    experimentId: closedString(config?.experimentId, EXPERIMENT_ID) ? config.experimentId : null,
    sourceCommit: closedString(source?.commit, COMMIT) ? source.commit : null,
    mode: MODES.has(config?.mode ?? 'crash-containment') ? (config?.mode ?? 'crash-containment') : null,
    failedStage: DIAGNOSTIC_STAGES.includes(failedStage) ? failedStage : 'unknown',
    failure: {
      code: failureCode(failure),
      stopReason: stableStopReason(failure)
    },
    retainedAt,
    cleanup: { verifiedByListenerProbe: cleanupVerified === true },
    proxy,
    providerUsage,
    privacy: {
      aggregateOnly: true,
      allowlistedFieldsOnly: true,
      publicResearchEvidence: false
    }
  })
}

function writeFallbackDiagnostic (options, failedStage, maxBytes) {
  const diagnostic = fallbackDiagnostic({ ...options, failedStage })
  const text = canonicalJson(diagnostic)
  const unsafe = scanText(text)
  if (unsafe.length > 0) throw new Error('fallback diagnostic contained unsafe material')
  const bytes = Buffer.byteLength(text)
  if (bytes > maxBytes) throw new Error(`fallback diagnostic exceeded its ${maxBytes} byte bound`)
  // Never named after an unvalidated id: an id that fails the slug test is not a path.
  const name = diagnostic.experimentId ?? `unidentified-${crypto.randomBytes(6).toString('hex')}`
  const file = writeExclusiveAtomic(path.join(options.diagnosticRoot, `${name}.fallback.json`), text)
  return {
    diagnostic,
    file,
    label: retainedLabel(options.repositoryRoot, file),
    bytes,
    fallback: true,
    failedStage: diagnostic.failedStage
  }
}

// The diagnostic's text with its cognition timeline halved, newest entries kept, until it fits
// [maxBytes]: the timeline is the one part that grows with a run, and the rest of the diagnostic is
// worth more than its oldest entries (v32 plains rehearsal 14: a ten-agent night's two full
// timelines took a failed run's diagnostic to 68 KB, and only the minimal fallback was kept).
function boundedSerialization (diagnostic, serialize, maxBytes) {
  let text = serialize(diagnostic)
  const timeline = diagnostic?.cognition?.timeline
  if (typeof text !== 'string' || timeline === undefined || timeline === null) return text
  for (let keep = MAX_TIMELINE_ENTRIES / 2; keep >= 1 && Buffer.byteLength(text) > maxBytes; keep /= 2) {
    for (const [entries, omitted] of [['forwarded', 'forwardedOmitted'], ['body', 'bodyOmitted']]) {
      const cut = Math.max(0, timeline[entries].length - keep)
      timeline[entries] = timeline[entries].slice(cut)
      timeline[omitted] += cut
    }
    text = serialize(diagnostic)
  }
  return text
}

function writeNormalDiagnostic (options, stages, maxBytes, progress) {
  const { config } = options
  const limits = {
    maxBytes: config.bounds.logBytes,
    maxRecords: config.bounds.evidenceRecords
  }
  progress.stage = 'inputRead'
  const bodyInput = stages.readInput(options.bodyFile, limits)
  const harnessInput = stages.readInput(options.harnessFile, limits)
  const providerUsageInput = stages.readInput(options.providerUsageFile, limits)
  progress.stage = 'summary'
  const diagnostic = stages.summarize({
    config,
    source: options.source,
    failure: options.failure,
    startedAt: options.startedAt,
    endedAt: options.endedAt,
    retainedAt: options.retainedAt,
    cleanupVerified: options.cleanupVerified,
    bodyInput,
    harnessInput,
    providerUsageInput,
    proxySnapshot: options.proxySnapshot
  })
  progress.stage = 'serialize'
  const text = boundedSerialization(diagnostic, stages.serialize, maxBytes)
  if (typeof text !== 'string') throw new Error('failed-run diagnostic did not serialize')
  progress.stage = 'sanitize'
  if (stages.scan(text).length > 0) {
    throw new Error('failed-run diagnostic contained unsafe material')
  }
  progress.stage = 'sizeCheck'
  const bytes = Buffer.byteLength(text)
  if (bytes > maxBytes) {
    throw new Error(`failed-run diagnostic exceeded its ${maxBytes} byte bound`)
  }
  progress.stage = 'write'
  const named = path.join(options.diagnosticRoot, `${config.experimentId}.json`)
  const file = stages.write(named, text) ?? named
  return {
    diagnostic,
    file,
    label: retainedLabel(options.repositoryRoot, file),
    bytes,
    fallback: false
  }
}

// The normal diagnostic is preferred; any fault in reading, summarizing, sanitizing,
// bounding, serializing, or writing it falls back to a closed allowlisted record.
function writeFailureDiagnostic ({
  clock = () => new Date(),
  maxBytes = MAX_FAILURE_DIAGNOSTIC_BYTES,
  fallbackMaxBytes = MAX_FALLBACK_DIAGNOSTIC_BYTES,
  stages = {},
  ...options
}) {
  let retainedAt
  try {
    retainedAt = clock().toISOString()
  } catch {
    retainedAt = new Date().toISOString()
  }
  const context = { ...options, retainedAt }
  const progress = { stage: 'inputRead' }
  try {
    return writeNormalDiagnostic(
      context,
      { ...DEFAULT_STAGES, ...stages },
      maxBytes,
      progress
    )
  } catch {
    return writeFallbackDiagnostic(context, progress.stage, fallbackMaxBytes)
  }
}

module.exports = {
  DIAGNOSTIC_STAGES,
  FAILURE_DIAGNOSTIC_VERSION,
  FALLBACK_DIAGNOSTIC_VERSION,
  MAX_FAILURE_DIAGNOSTIC_BYTES,
  MAX_FALLBACK_DIAGNOSTIC_BYTES,
  boundedJsonLines,
  failureCode,
  writeExclusiveAtomic,
  writeFailureDiagnostic
}
