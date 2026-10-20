'use strict'

const fs = require('node:fs')
const path = require('node:path')

const { behaviorGateMetrics } = require('./behavior-gate.js')
const { admissionEndMetrics, controlledStopMetrics } = require('./controlled-stop.js')
const { cognitionCoalescingMetrics, dangerEpisodeMetrics, isPreempted } = require('./danger.js')
const { maximumOf, minimumOf } = require('./log-tail.js')
const { deathMetrics } = require('./witnessed-death.js')
const { canonicalLine, sortedValue } = require('./util.js')

const NORMALIZED_EVIDENCE_VERSION = 'loom-normalized-evidence/1'
const METRICS_VERSION = 'loom-derived-metrics/1'
const MAX_PUBLIC_STRING = 512
const MAX_BODY_OFFERS_PER_KIND = 48
const MAX_MODEL_VISIBLE_OBSERVATIONS = MAX_BODY_OFFERS_PER_KIND * 3
const PERCEPTION_AUDIT_KINDS = ['entities', 'blocks', 'droppedItems']
const MAX_GOAL_ID_CHARS = 64
const GOAL_DISPOSITIONS = ['adopt', 'continue', 'revise', 'replace', 'complete', 'abandon']
const GOAL_OUTCOMES = [
  'goalAdopted',
  'goalContinued',
  'goalRevised',
  'goalCompleted',
  'goalAbandoned',
  'goalUnaddressed',
  'goalStale',
  'goalNotHeld',
  'goalAlreadyHeld',
  'goalStatementMissing',
  'goalNotReviewed',
  'goalSuccessConditionMissing',
  'goalStepMissing',
  'goalStepResultMissing',
  'goalStalled',
  'goalSuccessTargetMissing',
  'goalStepActionMissing',
  'goalStepMismatch',
  'goalConditionMetIgnored',
  'goalCompletionUnmet',
  'goalSuccessUnobtainable',
  'goalAlreadyMet',
  'goalStepIdle',
  'goalChoiceMissing',
  'goalStepUnproductive',
  'goalOwnDropIgnored',
  'goalReplaced',
  'goalCompletedAdopted',
  'goalAbandonedAchievable',
  'goalReviseItemChanged',
  'goalCompletedWithoutNext',
  'goalCrowdsExploration',
  'goalReviseUnchanged',
  'goalDiscoveryNotOffered',
  'goalDeferred',
  'goalSurvivalRequired'
]
const GOAL_STEP_RESULTS = ['done', 'progressing', 'noProgress']
// What a measured step showed, from routine-v22: the exact target reached, an item gained, a
// new drop in sight, or nothing.
// From routine-v27 a walk to the edge of sight that brought a source of the item into sight.
// From routine-v28 a gain of a direct input of a recipe for the goal's item.
const GOAL_STEP_EVIDENCE = ['arrived', 'gained', 'gainedInput', 'dropped', 'revealed', 'none']
// Outcomes that act on the very goal the answered review was handed.
const GOAL_CONTINUING_OUTCOMES = ['goalContinued', 'goalRevised', 'goalCompleted']

const PUBLIC_SCAN_PATTERNS = [
  {
    name: 'OpenAI-style API key',
    expression: /\bsk-[A-Za-z0-9_-]{16,}\b/g
  },
  {
    name: 'authorization bearer value',
    expression: /\bBearer\s+[A-Za-z0-9._~+/=-]{12,}\b/gi
  },
  {
    name: 'private key material',
    expression: /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/g
  },
  {
    name: 'AWS secret access key assignment',
    expression: /\bAWS_SECRET_ACCESS_KEY\s*[:=]\s*\S+/gi
  },
  {
    name: 'secret-bearing JSON field',
    expression: /"(?:api[_-]?key|access[_-]?token|refresh[_-]?token|device[_-]?token|account[_-]?token|session[_-]?token|credential|authorization|cookie)"\s*:\s*"(?!\[redacted\])[^"]+"/gi
  },
  {
    name: 'GitHub-style token',
    expression: /\b(?:github_pat_|gh[pousr]_)[A-Za-z0-9_]{16,}\b/g
  },
  {
    name: 'local absolute filesystem path',
    expression: /(?:\/Users\/|\/home\/|\/private\/tmp\/|\/tmp\/|\/var\/folders\/|[A-Za-z]:\\Users\\)[^\s"'<>]*/g
  },
  {
    name: 'provider request or thread identifier',
    expression: /\b(?:chatcmpl|resp|thread|run)_[A-Za-z0-9_-]{8,}\b/g
  },
  {
    name: 'transient process id field',
    expression: /"(?:pid|processId)"\s*:\s*[1-9][0-9]*/g
  }
]

function resetExpression (pattern) {
  pattern.expression.lastIndex = 0
}

function scanText (text) {
  const matches = []
  for (const pattern of PUBLIC_SCAN_PATTERNS) {
    resetExpression(pattern)
    const found = text.match(pattern.expression)
    if (found !== null) {
      matches.push({ pattern: pattern.name, count: found.length })
    }
  }
  return matches
}

function sanitizeString (value, state) {
  let result = value
  for (const pattern of PUBLIC_SCAN_PATTERNS) {
    resetExpression(pattern)
    result = result.replace(pattern.expression, () => {
      state.redactions += 1
      return '[redacted]'
    })
  }
  if (result.length > MAX_PUBLIC_STRING) {
    state.redactions += 1
    result = `${result.slice(0, MAX_PUBLIC_STRING - 15)}…[truncated]`
  }
  return result
}

function sensitiveField (key) {
  const normalized = key.replace(/[^A-Za-z0-9]/g, '').toLowerCase()
  return [
    'apikey',
    'accesstoken',
    'refreshtoken',
    'devicetoken',
    'accounttoken',
    'sessiontoken',
    'credential',
    'authorization',
    'cookie'
  ].includes(normalized)
}

function transientField (key) {
  const normalized = key.replace(/[^A-Za-z0-9]/g, '').toLowerCase()
  return ['pid', 'processid', 'providerrequestid', 'providerthreadid'].includes(normalized)
}

function sanitizeValue (value, state, key = '') {
  if (sensitiveField(key) || transientField(key)) {
    state.redactions += 1
    return '[redacted]'
  }
  if (typeof value === 'string') return sanitizeString(value, state)
  if (Array.isArray(value)) return value.map((item) => sanitizeValue(item, state))
  if (value === null || typeof value !== 'object') return value
  return Object.fromEntries(
    Object.entries(value).map(([childKey, item]) => [
      childKey,
      sanitizeValue(item, state, childKey)
    ])
  )
}

function readJsonLines (file, limits) {
  const stat = fs.statSync(file)
  if (stat.size > limits.maxBytes) {
    throw new Error(`${path.basename(file)} is ${stat.size} bytes, over the ${limits.maxBytes} byte evidence input bound`)
  }
  const text = fs.readFileSync(file, 'utf8')
  if (text.length === 0) return []
  if (!text.endsWith('\n')) {
    throw new Error(`${path.basename(file)} ends with a partial JSONL record`)
  }
  const lines = text.slice(0, -1).split('\n')
  if (lines.length > limits.maxRecords) {
    throw new Error(`${path.basename(file)} has ${lines.length} records, over the ${limits.maxRecords} record bound`)
  }
  return lines.map((line, index) => {
    if (Buffer.byteLength(line) > limits.maxLineBytes) {
      throw new Error(`${path.basename(file)} line ${index + 1} is over the ${limits.maxLineBytes} byte line bound`)
    }
    try {
      return JSON.parse(line)
    } catch (cause) {
      throw new Error(`${path.basename(file)} line ${index + 1} is malformed JSON: ${cause.message}`)
    }
  })
}

function validatePerceptionAudit (audit, label = 'perception audit') {
  if (audit === null || typeof audit !== 'object' || Array.isArray(audit)) {
    throw new Error(`${label} is not an object`)
  }
  const clock = audit.worldClock
  if (
    clock === null ||
    typeof clock !== 'object' ||
    Array.isArray(clock) ||
    !Number.isInteger(clock.timeOfDay) ||
    clock.timeOfDay < 0 ||
    clock.timeOfDay > 23_999 ||
    !Number.isSafeInteger(clock.day) ||
    clock.day < 0 ||
    typeof clock.isDay !== 'boolean'
  ) {
    throw new Error(`${label} has an incomplete or invalid world clock`)
  }

  let selectedTotal = 0
  for (const kind of PERCEPTION_AUDIT_KINDS) {
    const counts = audit[kind]
    if (counts === null || typeof counts !== 'object' || Array.isArray(counts)) {
      throw new Error(`${label} has no complete ${kind} counts`)
    }
    for (const field of [
      'rawBodyListCount',
      'bodyReportedOmittedCount',
      'jvmSelectedCount',
      'jvmCutOmittedCount'
    ]) {
      if (!Number.isSafeInteger(counts[field]) || counts[field] < 0) {
        throw new Error(`${label} ${kind}.${field} must be a non-negative integer`)
      }
    }
    if (counts.rawBodyListCount > MAX_BODY_OFFERS_PER_KIND) {
      throw new Error(`${label} ${kind}.rawBodyListCount exceeds the body list bound`)
    }
    if (
      counts.bodyReportedOmittedCount > 0 &&
      counts.rawBodyListCount !== MAX_BODY_OFFERS_PER_KIND
    ) {
      throw new Error(`${label} ${kind} body omissions require a saturated body list`)
    }
    if (counts.jvmSelectedCount > counts.rawBodyListCount) {
      throw new Error(`${label} ${kind} selected more than the body returned`)
    }
    if (
      counts.jvmCutOmittedCount !==
      counts.rawBodyListCount - counts.jvmSelectedCount
    ) {
      throw new Error(`${label} ${kind} JVM cut arithmetic is inconsistent`)
    }
    // Three body lists of at most 48 entries fit exactly inside the shared 144-entry
    // scene. Any JVM cut in a current-generation audit is therefore a hidden drop,
    // not ordinary bounded selection. PlayerSceneSelector still handles malformed or
    // future wider inputs deterministically, but those inputs cannot cross this wire.
    if (counts.jvmSelectedCount !== counts.rawBodyListCount) {
      throw new Error(`${label} ${kind} dropped an offer inside the complete 48-per-kind envelope`)
    }
    selectedTotal += counts.jvmSelectedCount
  }
  if (selectedTotal > MAX_MODEL_VISIBLE_OBSERVATIONS) {
    throw new Error(`${label} exceeds the model-visible scene bound`)
  }
  return audit
}

function validGoalId (value) {
  return value === undefined ||
    (typeof value === 'string' && value.length > 0 && value.length <= MAX_GOAL_ID_CHARS)
}

function validateGoalEvidence (goal, event, label) {
  if (goal === null || typeof goal !== 'object' || Array.isArray(goal)) {
    throw new Error(`${label} goal evidence is not an object`)
  }
  if (event !== 'decided') throw new Error(`${label} carries goal evidence outside a decided record`)
  if (!GOAL_OUTCOMES.includes(goal.outcome)) throw new Error(`${label} has an unknown goal outcome`)
  if (goal.disposition !== undefined && !GOAL_DISPOSITIONS.includes(goal.disposition)) {
    throw new Error(`${label} has an unknown goal disposition`)
  }
  for (const field of ['presentedGoalId', 'goalId', 'expiredGoalId', 'chainedFrom', 'discoveredFrom']) {
    if (!validGoalId(goal[field])) throw new Error(`${label} goal ${field} is not a bounded id`)
  }
  for (const field of ['presentedRevision', 'revision']) {
    if (goal[field] !== undefined && (!Number.isSafeInteger(goal[field]) || goal[field] < 0)) {
      throw new Error(`${label} goal ${field} must be a non-negative integer`)
    }
  }
  if (goal.stepResult !== undefined && !GOAL_STEP_RESULTS.includes(goal.stepResult)) {
    throw new Error(`${label} has an unknown goal step result`)
  }
  for (const field of ['stepsWithoutProgress', 'targetHeld', 'targetCapacity']) {
    if (goal[field] !== undefined && (!Number.isSafeInteger(goal[field]) || goal[field] < 0)) {
      throw new Error(`${label} goal ${field} must be a non-negative integer`)
    }
  }
  if (goal.stepEvidence !== undefined && !GOAL_STEP_EVIDENCE.includes(goal.stepEvidence)) {
    throw new Error(`${label} has an unknown goal step evidence`)
  }
  for (const field of ['conditionMet', 'conditionAdvanced', 'stepAdvanced', 'discovery', 'presentedDiscovery']) {
    if (goal[field] !== undefined && typeof goal[field] !== 'boolean') {
      throw new Error(`${label} goal ${field} must be a boolean`)
    }
  }
  if ((goal.presentedGoalId === undefined) !== (goal.presentedRevision === undefined) ||
    (goal.goalId === undefined) !== (goal.revision === undefined)) {
    throw new Error(`${label} goal ids and revisions must be stated together`)
  }
  return goal
}

function selectedPerceptionAuditCount (audit) {
  return PERCEPTION_AUDIT_KINDS.reduce(
    (sum, kind) => sum + audit[kind].jvmSelectedCount,
    0
  )
}

function normalizeBodyRecord (
  experimentId,
  record,
  index,
  state,
  requirePerceptionAudit
) {
  if (record === null || typeof record !== 'object' || Array.isArray(record)) {
    throw new Error(`body evidence line ${index + 1} is not an object`)
  }
  if (!Number.isInteger(record.tick) || record.tick < 0) {
    throw new Error(`body evidence line ${index + 1} has an invalid tick`)
  }
  if (typeof record.agentId !== 'string' || typeof record.event !== 'string') {
    throw new Error(`body evidence line ${index + 1} has no agentId or event`)
  }
  if (record.perception?.agentId !== undefined && record.perception.agentId !== record.agentId) {
    throw new Error(`body evidence line ${index + 1} files a perception under another agent`)
  }
  if (record.event === 'perceived') {
    if (record.perceptionAudit === undefined || record.perceptionAudit === null) {
      if (requirePerceptionAudit) {
        throw new Error(`body evidence line ${index + 1} has no required per-kind perception audit`)
      }
    } else {
      validatePerceptionAudit(record.perceptionAudit, `body evidence line ${index + 1} perception audit`)
      const expectedOmitted = PERCEPTION_AUDIT_KINDS.reduce(
        (sum, kind) =>
          sum +
          record.perceptionAudit[kind].bodyReportedOmittedCount +
          record.perceptionAudit[kind].jvmCutOmittedCount,
        0
      )
      if (
        !Array.isArray(record.perception?.surroundings) ||
        selectedPerceptionAuditCount(record.perceptionAudit) !==
          record.perception.surroundings.length
      ) {
        throw new Error(
          `body evidence line ${index + 1} perception audit does not match the model-visible scene`
        )
      }
      if (record.perception.surroundingsOmitted !== expectedOmitted) {
        throw new Error(
          `body evidence line ${index + 1} perception audit does not match the aggregate omission count`
        )
      }
    }
  }

  if (record.goal !== undefined && record.goal !== null) {
    validateGoalEvidence(record.goal, record.event, `body evidence line ${index + 1}`)
  }

  const requestId = record.requestId ?? record.perception?.requestId ?? record.decision?.requestId
  const summary = record.event === 'cognitionUnavailable'
    ? `cognition unavailable: ${record.outcome ?? 'unclassified'}`
    : sanitizeString(String(record.detail ?? ''), state)
  const normalized = {
    schemaVersion: NORMALIZED_EVIDENCE_VERSION,
    experimentId,
    recordId: `body:${String(index + 1).padStart(8, '0')}`,
    source: 'body',
    sourceSequence: index + 1,
    tick: record.tick,
    agentId: record.agentId,
    event: record.event,
    summary
  }
  if (requestId !== undefined && requestId !== null) {
    normalized.requestId = requestId
    normalized.requestKey = `${record.agentId}:${requestId}`
  }
  for (const field of [
    'trigger',
    'intent',
    'outcome',
    'provenance',
    'vitals',
    'health',
    'witness',
    'perception',
    'perceptionAudit',
    'decision',
    'goal',
    'shown',
    'retreat',
    'dispute'
  ]) {
    if (record[field] !== undefined && record[field] !== null) {
      normalized[field] = sanitizeValue(record[field], state)
    }
  }
  return sortedValue(normalized)
}

function normalizeHarnessRecord (experimentId, record, index, state) {
  if (record === null || typeof record !== 'object' || Array.isArray(record)) {
    throw new Error(`harness evidence line ${index + 1} is not an object`)
  }
  if (typeof record.event !== 'string' || typeof record.observedAt !== 'string') {
    throw new Error(`harness evidence line ${index + 1} has no event or observedAt`)
  }
  const normalized = {
    schemaVersion: NORMALIZED_EVIDENCE_VERSION,
    experimentId,
    recordId: `harness:${String(index + 1).padStart(8, '0')}`,
    source: 'harness',
    sourceSequence: index + 1,
    observedAt: record.observedAt,
    event: record.event
  }
  for (const [key, value] of Object.entries(record)) {
    if (
      key === 'observedAt' ||
      key === 'event' ||
      key === 'schemaVersion' ||
      key === 'experimentId' ||
      key === 'failure' ||
      value === undefined ||
      value === null
    ) continue
    normalized[key] = sanitizeValue(value, state, key)
  }
  if (normalized.agentId !== undefined && normalized.requestId !== undefined) {
    normalized.requestKey = `${normalized.agentId}:${normalized.requestId}`
  }
  return sortedValue(normalized)
}

function normalizeEvidence ({
  experimentId,
  bodyFile,
  harnessFile,
  outputFile,
  maxInputBytes,
  maxOutputBytes,
  maxRecords,
  maxLineBytes = 256 * 1024,
  requirePerceptionAudit = false
}) {
  const limits = { maxBytes: maxInputBytes, maxRecords, maxLineBytes }
  const body = readJsonLines(bodyFile, limits)
  const harness = readJsonLines(harnessFile, limits)
  if (body.length + harness.length > maxRecords) {
    throw new Error(`combined evidence has ${body.length + harness.length} records, over the ${maxRecords} record bound`)
  }
  const state = { redactions: 0 }
  const records = [
    ...body.map((record, index) =>
      normalizeBodyRecord(
        experimentId,
        record,
        index,
        state,
        requirePerceptionAudit
      )),
    ...harness.map((record, index) => normalizeHarnessRecord(experimentId, record, index, state))
  ]
  if (records.length === 0) throw new Error('an experiment cannot publish empty evidence')

  let bytes = 0
  const lines = records.map((record) => {
    const line = `${canonicalLine(record)}\n`
    bytes += Buffer.byteLength(line)
    if (bytes > maxOutputBytes) {
      throw new Error(`normalized evidence is over the ${maxOutputBytes} byte artifact bound`)
    }
    return line
  })
  fs.writeFileSync(outputFile, lines.join(''), { encoding: 'utf8', flag: 'wx' })
  return { records, bytes, redactions: state.redactions }
}

function increment (object, key) {
  object[key] = (object[key] ?? 0) + 1
}

function euclideanDistance (first, second) {
  return Math.sqrt(first.reduce((sum, value, index) => sum + Math.pow(value - second[index], 2), 0))
}

function deriveMetrics (records, configuredAgents = []) {
  const bodyRecords = records.filter((record) => record.source === 'body')
  const harnessRecords = records.filter((record) => record.source === 'harness')
  const agents = new Map(configuredAgents.map((agent) => [
    agent.agentId,
    {
      agentId: agent.agentId,
      records: 0,
      byEvent: {},
      decisionsByIntent: {},
      appliedByIntent: {},
      byOutcome: {},
      decisions: 0,
      actions: 0
    }
  ]))
  const byEvent = {}
  const preempted = bodyRecords.filter(isPreempted).length
  const decisionsByIntent = {}
  const appliedByIntent = {}
  const byOutcome = {}
  const requestChains = new Map()
  const stamps = new Map()
  const positions = new Map()
  const coLocations = []
  // From gate generation 6: perceptions that showed another player within eight blocks, which a
  // pairing of self-positions taken 20 ticks apart can miss (r14 beta saw delta at 1.9 blocks).
  const playerSightings = []
  const perceptionAudits = []

  for (const record of bodyRecords) {
    increment(byEvent, record.event)
    if (record.agentId !== 'loom:world') {
      const agent = agents.get(record.agentId) ?? {
        agentId: record.agentId,
        records: 0,
        byEvent: {},
        decisionsByIntent: {},
        appliedByIntent: {},
        byOutcome: {},
        decisions: 0,
        actions: 0
      }
      agent.records += 1
      increment(agent.byEvent, record.event)
      if (record.event === 'decided') {
        agent.decisions += 1
        if (record.intent !== undefined) {
          increment(agent.decisionsByIntent, record.intent)
          increment(decisionsByIntent, record.intent)
        }
      }
      if (record.event === 'intentApplied') {
        agent.actions += 1
        if (record.intent !== undefined) {
          increment(agent.appliedByIntent, record.intent)
          increment(appliedByIntent, record.intent)
        }
      }
      if (record.outcome !== undefined) increment(agent.byOutcome, record.outcome)
      agents.set(record.agentId, agent)
    }
    if (record.outcome !== undefined) increment(byOutcome, record.outcome)
    if (record.requestKey !== undefined) {
      const chain = requestChains.get(record.requestKey) ?? []
      chain.push(record.event)
      requestChains.set(record.requestKey, chain)
    }
    if (record.provenance !== undefined && record.event === 'decided') {
      const key = [
        record.provenance.tier,
        record.provenance.model,
        record.provenance.promptVersion
      ].join('\0')
      const stamp = stamps.get(key) ?? { ...record.provenance, decisions: 0 }
      stamp.decisions += 1
      stamps.set(key, stamp)
    }
    const position = record.perception?.selfState?.position
    if (
      record.event === 'perceived' &&
      Array.isArray(position) &&
      position.length === 3 &&
      position.every(Number.isFinite)
    ) {
      positions.set(record.agentId, { tick: record.tick, position })
      for (const [otherAgent, other] of positions) {
        if (otherAgent === record.agentId || Math.abs(other.tick - record.tick) > 20) continue
        const pair = [record.agentId, otherAgent].sort()
        const distance = euclideanDistance(position, other.position)
        if (distance <= 8) {
          const finding = { agents: pair, tick: Math.max(record.tick, other.tick), distanceBlocks: distance }
          const previous = coLocations[coLocations.length - 1]
          if (
            previous === undefined ||
            previous.tick !== finding.tick ||
            previous.agents.join('\0') !== finding.agents.join('\0')
          ) {
            coLocations.push(finding)
          }
        }
      }
    }
    const nearestPlayer = (record.perception?.surroundings ?? [])
      .filter((seen) => seen.kind === 'entity' && seen.what === 'entity.minecraft.player' && Number.isFinite(seen.distance))
      .reduce((nearest, seen) => Math.min(nearest, seen.distance), Infinity)
    if (record.event === 'perceived' && nearestPlayer <= 8) {
      playerSightings.push({ agentId: record.agentId, tick: record.tick, distanceBlocks: nearestPlayer })
    }
    if (record.event === 'perceived' && record.perceptionAudit !== undefined) {
      validatePerceptionAudit(record.perceptionAudit, `${record.recordId} perception audit`)
      perceptionAudits.push(record)
    }
  }

  const forwarded = harnessRecords.filter((record) => record.event === 'cognitionForwarded')
  const refused = harnessRecords.filter((record) => record.event === 'cognitionRefused')
  const providerUsage = harnessRecords.filter((record) => record.event === 'providerUsage')
  const crashes = harnessRecords.filter((record) => record.event === 'controlledBodyCrash')
  const cleanup = harnessRecords.filter((record) => record.event === 'cleanupVerified')
  const stimulusRecords = harnessRecords.filter(
    (record) => record.event === 'cognitionStimulusAttested'
  )
  const incomplete = []
  for (const [requestKey, chain] of requestChains) {
    const perceived = chain.includes('perceived')
    const terminal =
      chain.includes('intentApplied') ||
      chain.includes('decisionDiscarded') ||
      chain.includes('cognitionUnavailable')
    if (perceived && !terminal) incomplete.push({ requestKey, events: chain })
  }
  const ticks = bodyRecords.map((record) => record.tick)
  const observedFirstTick = minimumOf(ticks)
  const observedLastTick = maximumOf(ticks)
  const startTick = harnessRecords.find((record) => record.event === 'experimentStarted')?.startTick
  const endTick = harnessRecords.findLast((record) => record.event === 'experimentEnded')?.endTick
  const firstTick = Number.isInteger(startTick) ? startTick : observedFirstTick
  const lastTick = Number.isInteger(endTick) ? endTick : observedLastTick
  const sortedAgents = [...agents.values()]
    .sort((first, second) => first.agentId.localeCompare(second.agentId))
    .map((agent) => ({
      ...agent,
      byEvent: sortedValue(agent.byEvent),
      decisionsByIntent: sortedValue(agent.decisionsByIntent),
      appliedByIntent: sortedValue(agent.appliedByIntent),
      byOutcome: sortedValue(agent.byOutcome)
    }))
  const usageByModel = {}
  let externalSpendNanoUsd = 0
  for (const record of providerUsage) {
    if (
      typeof record.model !== 'string' ||
      !['promptTokens', 'cachedPromptTokens', 'completionTokens', 'totalTokens', 'costNanoUsd']
        .every((field) => Number.isSafeInteger(record[field]) && record[field] >= 0) ||
      record.cachedPromptTokens > record.promptTokens ||
      record.promptTokens + record.completionTokens !== record.totalTokens
    ) {
      throw new Error(`provider usage ${record.recordId} is malformed or inconsistent`)
    }
    const model = usageByModel[record.model] ?? {
      calls: 0,
      promptTokens: 0,
      cachedPromptTokens: 0,
      completionTokens: 0,
      totalTokens: 0,
      costNanoUsd: 0
    }
    model.calls += 1
    model.promptTokens += record.promptTokens
    model.cachedPromptTokens += record.cachedPromptTokens
    model.completionTokens += record.completionTokens
    model.totalTokens += record.totalTokens
    model.costNanoUsd += record.costNanoUsd
    externalSpendNanoUsd += record.costNanoUsd
    usageByModel[record.model] = model
  }

  // Only speech the body accepted counts; an applied record may carry a refusal outcome.
  const socialSpeech = bodyRecords.filter((record) =>
    record.event === 'intentApplied' && record.intent === 'say' && record.outcome === 'accepted'
  ).length
  const attacks = appliedByIntent.attack ?? 0
  const witnessDisputes = bodyRecords.filter((record) => record.event === 'witnessDisputed')
  const witnessDisputesByOutcome = {}
  for (const record of witnessDisputes) {
    increment(witnessDisputesByOutcome, record.outcome ?? 'unclassified')
  }
  const configuredBackend = harnessRecords
    .find((record) => record.event === 'experimentStarted')
    ?.cognitionBackend
  const scriptedEvidence =
    configuredBackend === 'scripted' ||
    (stamps.size > 0 && [...stamps.values()].every((stamp) => stamp.model === 'scripted-stand-in'))
  const spatialGeneration = Number.isInteger(stimulusRecords[0]?.behaviorGateGeneration)
    ? stimulusRecords[0].behaviorGateGeneration
    : 1
  // Generation 6 states exactly what each spatial measure counts; earlier generations keep the
  // sentence their committed evidence was published under.
  const spatialStated = spatialGeneration >= 6
  const observations = []
  if (coLocations.length > 0) {
    observations.push(spatialStated
      ? `${coLocations.length} paired self-position sample(s), the two agents' perceptions taken within 20 ticks of each other, placed two agents within eight blocks`
      : `${coLocations.length} bounded co-location sample(s) placed two agents within eight blocks`)
  }
  if (spatialStated && playerSightings.length > 0) {
    observations.push(`${playerSightings.length} perception(s) showed another player within eight blocks`)
  }
  if (socialSpeech > 0) observations.push(`${socialSpeech} speech intent(s) were accepted by the body`)
  if (attacks > 0) observations.push(`${attacks} attack intent(s) were applied`)

  const counterevidence = []
  if (socialSpeech === 0) counterevidence.push('No agent produced a speech intent.')
  if (spatialStated) {
    if (coLocations.length === 0 && playerSightings.length === 0) {
      counterevidence.push('Neither a paired self-position sample nor any perception placed two agents within eight blocks.')
    }
  } else if (coLocations.length === 0) {
    counterevidence.push('No bounded perception sample placed two agents within eight blocks.')
  }
  if (attacks === 0) counterevidence.push('No attack intent was applied.')
  if ((byEvent.worldInteraction ?? 0) === 0) {
    counterevidence.push('No server-confirmed native world interaction occurred.')
  }
  if (coLocations.length > 0 && (byEvent.moved ?? 0) === 0) {
    counterevidence.push(
      'No movement record accompanied the co-location samples; shared spawn placement is a sufficient explanation.'
    )
  }
  if (witnessDisputes.length > 0) {
    const classes = Object.entries(witnessDisputesByOutcome)
      .sort(([first], [second]) => first.localeCompare(second))
      .map(([outcome, count]) => `${outcome}: ${count}`)
      .join(', ')
    counterevidence.push(
      `${witnessDisputes.length} server witness dispute record(s) were emitted (${classes}); client-reported readings were not fully corroborated.`
    )
  }

  const result = {
    schemaVersion: METRICS_VERSION,
    records: {
      total: records.length,
      body: bodyRecords.length,
      harness: harnessRecords.length,
      byEvent: sortedValue(byEvent),
      agents: sortedAgents
    },
    timing: {
      firstTick,
      lastTick,
      tickSpan: lastTick - firstTick
    },
    cognition: {
      forwardedCalls: forwarded.length,
      refusedCalls: refused.length,
      providerUsageCalls: providerUsage.length,
      usageByModel: sortedValue(usageByModel),
      externalSpendNanoUsd,
      externalSpendUsd: Number((externalSpendNanoUsd / 1_000_000_000).toFixed(9)),
      decisionsByStamp: [...stamps.values()].sort((first, second) =>
        [first.tier, first.model, first.promptVersion]
          .join('\0')
          .localeCompare([second.tier, second.model, second.promptVersion].join('\0'))
      )
    },
    cycles: {
      perceived: byEvent.perceived ?? 0,
      decided: byEvent.decided ?? 0,
      applied: byEvent.intentApplied ?? 0,
      unavailable: byEvent.cognitionUnavailable ?? 0,
      // A danger answer newer damage preempted is superseded, never lost, so it is counted apart
      // and only when there is one: earlier evidence derives exactly the cycles it always did.
      discarded: (byEvent.decisionDiscarded ?? 0) - preempted,
      ...(preempted === 0 ? {} : { preempted }),
      incomplete
    },
    behavior: {
      decisionsByIntent: sortedValue(decisionsByIntent),
      byIntent: sortedValue(appliedByIntent),
      byOutcome: sortedValue(byOutcome),
      movementRecords: byEvent.moved ?? 0,
      worldInteractionRecords: byEvent.worldInteraction ?? 0,
      speechIntents: socialSpeech,
      attackIntents: attacks,
      sleepIntents: appliedByIntent.sleep ?? 0,
      deaths: byEvent.died ?? 0,
      disconnects: byEvent.bodyDisconnected ?? 0,
      witnessDisputeRecords: witnessDisputes.length,
      witnessDisputesByOutcome: sortedValue(witnessDisputesByOutcome)
    },
    spatial: {
      coLocationThresholdBlocks: 8,
      maximumTickSeparation: 20,
      coLocationSamples: coLocations,
      ...(spatialStated ? { playerSightings: playerSightings.length } : {})
    },
    crashContainment: {
      controlledCrashes: crashes.map((record) => ({
        agentId: record.agentId,
        controlPort: record.controlPort
      })),
      cleanupVerified: cleanup.length === 1,
      peerDecisionsAfterCrash: peerDecisionsAfterCrash(bodyRecords, crashes)
    },
    findings: {
      observations,
      interpretations: [
        'Counts describe recorded behavior only; they do not establish motive, cooperation, specialization, hierarchy, or emergence.'
      ],
      counterevidence,
      limitations: [
        'Perception positions are samples rather than continuous trajectories.',
        ...(witnessDisputes.length > 0
          ? ['Server witness disputes limit any claim that depends on the disputed client-reported readings.']
          : []),
        scriptedEvidence
          ? 'A scripted cognition run validates composition and containment, not autonomous social behavior.'
          : 'One bounded live-model run cannot establish that an observed pattern is stable or emergent.'
      ]
    }
  }
  const controlledStop = controlledStopMetrics(bodyRecords, harnessRecords, firstTick)
  if (controlledStop !== undefined) {
    result.controlledStop = controlledStop
    result.findings.limitations.push(
      `The run ended in a controlled ${controlledStop.kind ?? 'unclassified'} stop after ` +
        `${controlledStop.nativeReviews.completed} of ${controlledStop.nativeReviews.planned ?? 'unattested'} ` +
        'planned native reviews; its behavioral verdict is no-go.'
    )
  }
  const danger = dangerEpisodeMetrics(bodyRecords)
  if (danger !== undefined) result.dangerEpisodes = danger
  const inFlight = cognitionCoalescingMetrics(bodyRecords)
  if (inFlight !== undefined) result.cognitionCoalescing = inFlight
  const admissionEnd = admissionEndMetrics(bodyRecords, harnessRecords, firstTick)
  if (admissionEnd !== undefined) result.admissionEnd = admissionEnd
  const deaths = deathMetrics(bodyRecords, harnessRecords, firstTick)
  if (deaths !== undefined) {
    result.witnessedDeaths = deaths
    result.findings.observations.push(
      `${deaths.deaths} agent(s) died under the server witness: ${Object.keys(deaths.byAgent).join(', ')}.`
    )
  }
  const speech = speechMetrics(bodyRecords, socialSpeech)
  if (speech !== undefined) result.speech = speech
  const goalRecords = bodyRecords.filter((record) => record.goal !== undefined)
  if (goalRecords.length > 0) result.goalContinuity = goalContinuityMetrics(goalRecords)
  if (perceptionAudits.length > 0) {
    result.perceptionAudit = perceptionAuditMetrics(perceptionAudits, byEvent.perceived ?? 0)
  }
  if (
    stimulusRecords.length > 0 ||
    harnessRecords.some((record) => record.origin !== undefined)
  ) {
    result.primaryBehavior = primaryBehaviorMetrics(harnessRecords, stimulusRecords)
    const gate = behaviorGateMetrics({ bodyRecords, harnessRecords, metrics: result })
    if (gate !== undefined) result.behaviorGate = gate
  }
  return sortedValue(result)
}

// Speech counts only server-attributed lines as spoken; a client `sent` alone is a claim.
// Absent unless the run holds speech evidence, so earlier committed metrics re-derive unchanged.
function speechMetrics (records, applied) {
  const confirmed = records.filter((record) =>
    record.event === 'worldInteraction' && record.intent === 'say' && record.outcome === 'serverEffectConfirmed')
  const disputed = records.filter((record) => record.event === 'witnessDisputed' && record.intent === 'say')
  const heard = records.filter((record) => record.event === 'perceived' && record.trigger === 'spoken')
  if (confirmed.length + disputed.length + heard.length === 0) return undefined
  const disputedByOutcome = {}
  for (const record of disputed) increment(disputedByOutcome, record.outcome ?? 'unclassified')
  const confirmedByAgent = {}
  for (const record of confirmed) increment(confirmedByAgent, record.agentId)
  const heardByAgent = {}
  for (const record of heard) increment(heardByAgent, record.agentId)
  return {
    applied,
    confirmed: confirmed.length,
    confirmedByAgent: sortedValue(confirmedByAgent),
    disputed: disputed.length,
    disputedByOutcome: sortedValue(disputedByOutcome),
    heard: heard.length,
    heardByAgent: sortedValue(heardByAgent)
  }
}

// Continuity is a join of typed fields: a review continued a goal only when the body
// resolved its choice against the exact goal that review's perception carried.
function goalContinuityMetrics (records) {
  const byOutcome = {}
  const agents = new Map()
  for (const record of records) {
    validateGoalEvidence(record.goal, record.event, record.recordId)
    const goal = record.goal
    increment(byOutcome, goal.outcome)
    const agent = agents.get(record.agentId) ?? {
      agentId: record.agentId,
      byOutcome: {},
      goalsAdopted: 0,
      continuingReviews: 0
    }
    increment(agent.byOutcome, goal.outcome)
    if (goal.outcome === 'goalAdopted') agent.goalsAdopted += 1
    const actedOnCarriedGoal =
      GOAL_CONTINUING_OUTCOMES.includes(goal.outcome) &&
      goal.presentedGoalId !== undefined &&
      (goal.outcome === 'goalCompleted' || goal.goalId === goal.presentedGoalId)
    if (actedOnCarriedGoal) agent.continuingReviews += 1
    agents.set(record.agentId, agent)
  }
  const sortedAgents = [...agents.values()]
    .sort((first, second) => first.agentId.localeCompare(second.agentId))
    .map((agent) => ({ ...agent, byOutcome: sortedValue(agent.byOutcome) }))
  const continuity = {
    byOutcome: sortedValue(byOutcome),
    agents: sortedAgents,
    agentsWithContinuity: sortedAgents
      .filter((agent) => agent.continuingReviews > 0)
      .map((agent) => agent.agentId)
  }
  const working = workingGoalMetrics(records)
  return working === undefined ? continuity : { ...continuity, workingGoal: working }
}

/**
 * The working-goal evidence a v17 run carries: the mind's verdict on each previous step, the
 * body's own count of steps in a row that changed nothing, and the goals the bound ended.
 * Derived only when a record carries it, so evidence from before it re-derives unchanged.
 */
function workingGoalMetrics (records) {
  const judged = records.filter((record) =>
    record.goal.stepResult !== undefined || record.goal.stepsWithoutProgress !== undefined)
  if (judged.length === 0) return undefined
  const byAgent = {}
  for (const record of records) {
    const goal = record.goal
    const agent = byAgent[record.agentId] ??= {
      stepResults: {},
      maxStepsWithoutProgress: 0,
      stalled: 0,
      completed: 0
    }
    if (goal.stepResult !== undefined) increment(agent.stepResults, goal.stepResult)
    if (goal.stepsWithoutProgress !== undefined) {
      agent.maxStepsWithoutProgress = Math.max(agent.maxStepsWithoutProgress, goal.stepsWithoutProgress)
    }
    if (goal.outcome === 'goalStalled') agent.stalled += 1
    if (goal.outcome === 'goalCompleted') agent.completed += 1
  }
  const stepResults = {}
  for (const agent of Object.values(byAgent)) {
    for (const [result, count] of Object.entries(agent.stepResults)) stepResults[result] = (stepResults[result] ?? 0) + count
  }
  return {
    stepResults: sortedValue(stepResults),
    stalled: Object.values(byAgent).reduce((sum, agent) => sum + agent.stalled, 0),
    completed: Object.values(byAgent).reduce((sum, agent) => sum + agent.completed, 0),
    byAgent: sortedValue(Object.fromEntries(Object.entries(byAgent).map(([agentId, agent]) =>
      [agentId, { ...agent, stepResults: sortedValue(agent.stepResults) }])))
  }
}

function perceptionAuditMetrics (records, perceivedCount) {
  const byKind = {}
  for (const kind of PERCEPTION_AUDIT_KINDS) {
    byKind[kind] = {
      samples: 0,
      rawBodyListCount: 0,
      bodyReportedOmittedCount: 0,
      jvmSelectedCount: 0,
      jvmCutOmittedCount: 0
    }
  }
  const firstByAgent = new Map()
  for (const record of records) {
    for (const kind of PERCEPTION_AUDIT_KINDS) {
      const totals = byKind[kind]
      const counts = record.perceptionAudit[kind]
      totals.samples += 1
      totals.rawBodyListCount += counts.rawBodyListCount
      totals.bodyReportedOmittedCount += counts.bodyReportedOmittedCount
      totals.jvmSelectedCount += counts.jvmSelectedCount
      totals.jvmCutOmittedCount += counts.jvmCutOmittedCount
    }
    const current = firstByAgent.get(record.agentId)
    if (
      current === undefined ||
      record.tick < current.tick ||
      (record.tick === current.tick && record.sourceSequence < current.sourceSequence)
    ) {
      firstByAgent.set(record.agentId, record)
    }
  }
  return {
    complete: records.length === perceivedCount,
    samples: records.length,
    byKind,
    initialByAgent: [...firstByAgent.values()]
      .sort((first, second) => first.agentId.localeCompare(second.agentId))
      .map((record) => ({
        agentId: record.agentId,
        capturedTick: record.perception?.capturedTick ?? record.tick,
        position: record.perception?.selfState?.position,
        inventoryStacks: record.perception?.selfState?.inventorySummary?.length,
        worldClock: record.perceptionAudit.worldClock
      }))
  }
}

function primaryBehaviorMetrics (harnessRecords, stimulusRecords) {
  const initialConditionRecords = harnessRecords.filter(
    (record) => record.event === 'initialConditionsAttested'
  )
  const requestsByOrigin = {}
  const nativeGoalReviewsByAgent = {}
  for (const record of harnessRecords) {
    if (!['cognitionForwarded', 'cognitionRefused'].includes(record.event)) continue
    increment(requestsByOrigin, record.origin ?? 'unattested')
    if (
      record.event === 'cognitionForwarded' &&
      record.trigger === 'goalReview' &&
      record.origin === 'nativeGoalReviewSchedule'
    ) {
      increment(nativeGoalReviewsByAgent, record.agentId)
    }
  }
  const attestation = stimulusRecords.length === 1 ? stimulusRecords[0] : undefined
  const initialConditions =
    initialConditionRecords.length === 1 ? initialConditionRecords[0] : undefined
  return {
    stimulusAttestations: stimulusRecords.length,
    initialConditionAttestations: initialConditionRecords.length,
    externalCognitionStimuli: attestation?.externalCognitionStimuli ?? 'unattested',
    harnessThinkCommandsIssued: attestation?.harnessThinkCommandsIssued ?? null,
    nativeSchedulersOnly: attestation?.nativeSchedulersOnly === true,
    nativeGoalReviewSchedule: attestation?.nativeGoalReviewSchedule ?? null,
    configuredInitialPositions: initialConditions?.positions ?? null,
    configuredInitialWorldClock: initialConditions?.worldClock ?? null,
    startingInventories: initialConditions?.startingInventories ?? 'unattested',
    requestsByOrigin,
    nativeGoalReviewsByAgent: sortedValue(nativeGoalReviewsByAgent)
  }
}

function peerDecisionsAfterCrash (bodyRecords, crashes) {
  const result = {}
  for (const crash of crashes) {
    const observedAtTick = Number.isInteger(crash.afterTick) ? crash.afterTick : -1
    for (const record of bodyRecords) {
      if (record.event !== 'decided' || record.agentId === crash.agentId || record.tick <= observedAtTick) continue
      increment(result, record.agentId)
    }
  }
  return sortedValue(result)
}

module.exports = {
  METRICS_VERSION,
  NORMALIZED_EVIDENCE_VERSION,
  PUBLIC_SCAN_PATTERNS,
  deriveMetrics,
  normalizeEvidence,
  readJsonLines,
  sanitizeValue,
  scanText,
  validatePerceptionAudit
}
