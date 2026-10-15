'use strict'

const fs = require('node:fs')
const path = require('node:path')

const { SERVED_PROMPTS } = require('../lib/experiment-mode.js')
const { readJson } = require('../lib/util.js')

// The committed proof config is historical (it pins unserved prompts); tests exercise the
// same crash-containment conditions under the prompts the mind serves today.
function proofConfig () {
  const config = structuredClone(
    readJson(path.join(__dirname, '..', 'config', 'first-tribe-scripted-proof.json'))
  )
  config.configFile = path.join(__dirname, '..', 'config', 'first-tribe-scripted-proof.json')
  config.cognition.prompts = { ...SERVED_PROMPTS }
  return config
}

function primaryBehaviorConfig () {
  return goalContinuityConfig()
}

function openAiPrimaryBehaviorConfig () {
  return openAiGoalContinuityConfig()
}

function configNamed (name) {
  const file = path.join(__dirname, '..', 'config', name)
  const config = structuredClone(readJson(file))
  config.configFile = file
  return config
}

function goalContinuityConfig () {
  return configNamed('first-tribe-routine-v13-agency-pilot-scripted.json')
}

function openAiGoalContinuityConfig () {
  return configNamed('first-tribe-routine-v13-agency-pilot.json')
}

function openAiProofConfig () {
  const config = proofConfig()
  config.cognition.backend = 'openai'
  config.cognition.provider = 'OpenAI Chat Completions'
  config.cognition.providerEndpoint = 'https://api.example.test/v1'
  config.cognition.models = {
    ordinary: 'small-model',
    pivotal: 'large-model'
  }
  config.cognition.maxExternalSpendUsd = 0.25
  config.cognition.pricing = {
    currency: 'USD',
    effectiveDate: '2026-10-12',
    source: 'fixed test rates',
    models: {
      'small-model': {
        inputNanoUsdPerToken: 100,
        cachedInputNanoUsdPerToken: 10,
        outputNanoUsdPerToken: 500
      },
      'large-model': {
        inputNanoUsdPerToken: 200,
        cachedInputNanoUsdPerToken: 20,
        outputNanoUsdPerToken: 1000
      }
    }
  }
  return config
}

function perception (agentId, requestId, tick, position = [0.5, 64, 0.5]) {
  return {
    requestId,
    agentId,
    trigger: { event: 'goalReview' },
    importance: 'ROUTINE',
    capturedTick: tick,
    selfState: {
      health: 20,
      food: 20,
      saturation: 5,
      position,
      inventorySummary: [],
      craftingOptions: [],
      craftingOptionsOmitted: 0,
      world: {
        dayPhase: 'day',
        isDay: true,
        ticksUntilNightfall: 6000
      }
    },
    surroundings: [],
    surroundingsOmitted: 0
  }
}

function decision (
  agentId,
  requestId,
  reasoning = 'No bounded survival action is needed.',
  promptVersion = SERVED_PROMPTS.routine
) {
  return {
    requestId,
    agentId,
    intent: { action: 'idle' },
    reasoning,
    provenance: {
      tier: 'ROUTINE',
      model: 'scripted-stand-in',
      promptVersion
    }
  }
}

function perceptionAuditFixture (kind = 'empty', worldClock = {
  timeOfDay: 100,
  day: 0,
  isDay: true
}) {
  const empty = {
    rawBodyListCount: 0,
    bodyReportedOmittedCount: 0,
    jvmSelectedCount: 0,
    jvmCutOmittedCount: 0
  }
  if (kind === 'empty') {
    return {
      worldClock,
      entities: { ...empty },
      blocks: { ...empty },
      droppedItems: { ...empty }
    }
  }
  if (kind === 'full') {
    return {
      worldClock,
      entities: {
        rawBodyListCount: 2,
        bodyReportedOmittedCount: 0,
        jvmSelectedCount: 2,
        jvmCutOmittedCount: 0
      },
      blocks: {
        rawBodyListCount: 12,
        bodyReportedOmittedCount: 0,
        jvmSelectedCount: 12,
        jvmCutOmittedCount: 0
      },
      droppedItems: {
        rawBodyListCount: 2,
        bodyReportedOmittedCount: 0,
        jvmSelectedCount: 2,
        jvmCutOmittedCount: 0
      }
    }
  }
  if (kind === 'omitted') {
    return {
      worldClock,
      entities: {
        rawBodyListCount: 48,
        bodyReportedOmittedCount: 3,
        jvmSelectedCount: 48,
        jvmCutOmittedCount: 0
      },
      blocks: {
        rawBodyListCount: 48,
        bodyReportedOmittedCount: 120,
        jvmSelectedCount: 48,
        jvmCutOmittedCount: 0
      },
      droppedItems: {
        rawBodyListCount: 48,
        bodyReportedOmittedCount: 1,
        jvmSelectedCount: 48,
        jvmCutOmittedCount: 0
      }
    }
  }
  if (kind === 'inconsistent') {
    const audit = perceptionAuditFixture('empty', worldClock)
    delete audit.droppedItems.jvmCutOmittedCount
    audit.entities = {
      rawBodyListCount: 3,
      bodyReportedOmittedCount: 0,
      jvmSelectedCount: 2,
      jvmCutOmittedCount: 0
    }
    return audit
  }
  throw new Error(`unknown perception audit fixture ${kind}`)
}

function observationsForAudit (audit) {
  const observations = []
  const kinds = [
    ['entities', 'entity', 'entity.minecraft.test'],
    ['blocks', 'block', 'block.minecraft.test'],
    ['droppedItems', 'droppedItem', 'item.minecraft.test']
  ]
  for (const [auditKind, kind, what] of kinds) {
    for (let index = 0; index < audit[auditKind].jvmSelectedCount; index += 1) {
      observations.push({
        kind,
        what,
        distance: 1 + (index / 100),
        direction: 'north',
        verticalOffset: 0,
        visible: true,
        ref: `${kind.slice(0, 1)}${observations.length.toString(36)}`,
        ...(kind === 'droppedItem' ? { count: 1 } : {})
      })
    }
  }
  return observations
}

function bodyRecord (tick, agentId, event, detail, fields = {}) {
  return { tick, agentId, event, detail, ...fields }
}

function fixedRawRecords () {
  const body = []
  const harness = [
    {
      observedAt: '2026-10-12T00:00:00.000Z',
      event: 'preflightValidated',
      experimentId: '2026-10-12-scripted-tribe-crash-proof',
      schemaVersion: 'loom-experiment-manifest/3'
    },
    {
      observedAt: '2026-10-12T00:00:00.500Z',
      event: 'experimentStarted',
      cognitionBackend: 'scripted',
      startTick: 15
    }
  ]
  const agents = ['tribe-alpha', 'tribe-beta', 'tribe-gamma']
  for (const [index, agentId] of agents.entries()) {
    body.push(bodyRecord(10 + index, agentId, 'spawned', 'ready'))
    const requestId = 'sense-1'
    const seen = perception(agentId, requestId, 20, [index * 3 + 0.5, 64, 0.5])
    const chose = decision(agentId, requestId)
    body.push(bodyRecord(20, agentId, 'perceived', 'zero observations', {
      requestId,
      trigger: 'goalReview',
      perception: seen
    }))
    body.push(bodyRecord(21, agentId, 'decided', 'idle', {
      requestId,
      provenance: chose.provenance,
      intent: 'idle',
      decision: chose
    }))
    body.push(bodyRecord(21, agentId, 'intentApplied', 'idle accepted', {
      requestId,
      intent: 'idle',
      outcome: 'accepted'
    }))
    harness.push({
      observedAt: `2026-10-12T00:00:0${index + 1}.000Z`,
      event: 'cognitionForwarded',
      agentId,
      requestId,
      trigger: 'goalReview',
      importance: 'ROUTINE',
      outcome: 'decision',
      provenance: chose.provenance
    })
  }
  body.push(bodyRecord(30, 'tribe-alpha', 'bodyDisconnected', 'body exited', {
    outcome: 'bodyProcessExited'
  }))
  harness.push({
    observedAt: '2026-10-12T00:00:10.000Z',
    event: 'controlledBodyCrash',
    agentId: 'tribe-alpha',
    controlPort: 25585,
    afterTick: 25
  })
  for (const [index, agentId] of ['tribe-beta', 'tribe-gamma'].entries()) {
    const requestId = 'sense-2'
    const seen = perception(agentId, requestId, 40, [index * 4 + 0.5, 64, 0.5])
    const chose = decision(agentId, requestId)
    body.push(bodyRecord(40, agentId, 'perceived', 'zero observations', {
      requestId,
      trigger: 'goalReview',
      perception: seen
    }))
    body.push(bodyRecord(41, agentId, 'decided', 'idle', {
      requestId,
      provenance: chose.provenance,
      intent: 'idle',
      decision: chose
    }))
    body.push(bodyRecord(41, agentId, 'intentApplied', 'idle accepted', {
      requestId,
      intent: 'idle',
      outcome: 'accepted'
    }))
    harness.push({
      observedAt: `2026-10-12T00:00:1${index + 1}.000Z`,
      event: 'cognitionForwarded',
      agentId,
      requestId,
      trigger: 'goalReview',
      importance: 'ROUTINE',
      outcome: 'decision',
      provenance: chose.provenance
    })
  }
  harness.push({
    observedAt: '2026-10-12T00:00:28.000Z',
    event: 'experimentEnded',
    stopReason: 'durationElapsed',
    endTick: 41
  })
  harness.push({
    observedAt: '2026-10-12T00:00:29.000Z',
    event: 'cleanupVerified',
    listeners: 'closed'
  })
  return { body, harness }
}

// The scripted stand-in's goal rule, as the ledger records it: adopt at the first
// review, continue the carried goal at the second.
const SCRIPTED_GOAL_STATEMENT = 'scripted stand-in goal: stay fed and unhurt'

function scriptedGoalStep (review, agentId, tick) {
  if (review === 0) {
    return {
      choice: { disposition: 'adopt', statement: SCRIPTED_GOAL_STATEMENT },
      evidence: { outcome: 'goalAdopted', disposition: 'adopt', goalId: 'native-1', revision: 0 }
    }
  }
  return {
    carried: {
      goalId: 'native-1',
      statement: SCRIPTED_GOAL_STATEMENT,
      revision: 0,
      adoptedTick: tick - 2400,
      lastStep: { requestId: 'native-1', action: 'idle', outcome: 'accepted' }
    },
    choice: { disposition: 'continue' },
    evidence: {
      outcome: 'goalContinued',
      disposition: 'continue',
      presentedGoalId: 'native-1',
      presentedRevision: 0,
      goalId: 'native-1',
      revision: 0
    }
  }
}

function primaryBehaviorRawRecords (config = primaryBehaviorConfig()) {
  const carriesGoals = config.mode === 'routine-v13-goal-continuity'
  const body = []
  const harness = [
    {
      observedAt: '2026-10-13T00:00:00.000Z',
      event: 'preflightValidated',
      experimentId: config.experimentId,
      schemaVersion: 'loom-experiment-manifest/3'
    },
    {
      observedAt: '2026-10-13T00:00:00.250Z',
      event: 'initialConditionsAttested',
      positions: config.primaryBehavior.initialPositions,
      worldClock: config.primaryBehavior.initialWorldClock,
      startingInventories: 'empty'
    },
    {
      observedAt: '2026-10-13T00:00:00.500Z',
      event: 'experimentStarted',
      cognitionBackend: 'scripted',
      mode: config.mode,
      startTick: 15
    }
  ]
  for (const [agentIndex, agentId] of config.agents.entries()) {
    body.push(bodyRecord(10 + agentIndex, agentId, 'spawned', 'ready'))
    for (let review = 0; review < 2; review += 1) {
      const tick = 100 + (agentIndex * 100) + (review * 2400)
      const requestId = `native-${review + 1}`
      const seen = perception(
        agentId,
        requestId,
        tick,
        config.primaryBehavior.initialPositions[agentId]
      )
      const chose = decision(
        agentId,
        requestId,
        'The scripted dry run stands still.',
        config.cognition.prompts.routine
      )
      const goal = carriesGoals ? scriptedGoalStep(review, agentId, tick) : undefined
      if (goal?.carried !== undefined) seen.goal = goal.carried
      if (goal !== undefined) chose.goal = goal.choice
      const audit = perceptionAuditFixture(
        review === 0 ? 'full' : 'omitted',
        {
          timeOfDay: tick,
          day: 0,
          isDay: true
        }
      )
      seen.surroundings = observationsForAudit(audit)
      seen.surroundingsOmitted = [
        audit.entities,
        audit.blocks,
        audit.droppedItems
      ].reduce(
        (sum, counts) =>
          sum +
          counts.bodyReportedOmittedCount +
          counts.jvmCutOmittedCount,
        0
      )
      body.push(bodyRecord(tick, agentId, 'perceived', 'audited perception', {
        requestId,
        trigger: 'goalReview',
        perception: seen,
        perceptionAudit: audit
      }))
      body.push(bodyRecord(tick + 1, agentId, 'decided', 'idle', {
        requestId,
        provenance: chose.provenance,
        intent: 'idle',
        decision: chose,
        ...(goal === undefined ? {} : { goal: goal.evidence })
      }))
      body.push(bodyRecord(tick + 1, agentId, 'intentApplied', 'idle accepted', {
        requestId,
        intent: 'idle',
        outcome: 'accepted'
      }))
      harness.push({
        observedAt: `2026-10-13T00:00:${String(1 + agentIndex + (review * 10)).padStart(2, '0')}.000Z`,
        event: 'cognitionForwarded',
        agentId,
        requestId,
        trigger: 'goalReview',
        importance: 'ROUTINE',
        origin: 'nativeGoalReviewSchedule',
        outcome: 'decision',
        provenance: chose.provenance
      })
    }
  }
  harness.push({
    observedAt: '2026-10-13T00:02:59.000Z',
    event: 'cognitionStimulusAttested',
    mode: config.mode,
    externalCognitionStimuli: 'none',
    harnessThinkCommandsIssued: 0,
    nativeSchedulersOnly: true,
    behaviorGateGeneration: 2,
    nativeGoalReviewSchedule: config.primaryBehavior.nativeGoalReviewSchedule
  })
  harness.push({
    observedAt: '2026-10-13T00:03:00.000Z',
    event: 'experimentEnded',
    stopReason: 'durationElapsed',
    endTick: 3000
  })
  harness.push({
    observedAt: '2026-10-13T00:03:01.000Z',
    event: 'cleanupVerified',
    listeners: 'closed'
  })
  return { body, harness }
}

const LIVE_MODEL = 'gpt-5.4-nano'
const FLIGHT_AGENTS = ['tribe-gamma', 'tribe-alpha', 'tribe-beta']

function usageFor (agentId, requestId, observedAt) {
  return {
    observedAt,
    event: 'providerUsage',
    agentId,
    requestId,
    model: LIVE_MODEL,
    promptTokens: 2000,
    cachedPromptTokens: 0,
    completionTokens: 100,
    totalTokens: 2100,
    costNanoUsd: 525_000
  }
}

// One shared stop scene: every agent's first native review completes, then one request
// meets the stop. `inFlight` keeps one forwarded request open across the admission
// closure, and `late` sends one more request after it.
function controlledStopRawRecords ({
  config = goalContinuityConfig(),
  kind = 'cognitionBudgetExhausted',
  bound = 'globalOutstanding',
  stopAgent = 'tribe-beta',
  stopTick = 500,
  eventChains = 0,
  eventAgents = [stopAgent],
  inFlight = 0,
  reviews = true,
  late = true,
  live = config.cognition.backend === 'openai'
} = {}) {
  const raw = primaryBehaviorRawRecords(config)
  const dropped = reviews ? ['native-2'] : ['native-1', 'native-2']
  raw.body = raw.body.filter((record) => !dropped.includes(record.requestId))
  raw.harness = raw.harness.filter((record) => !dropped.includes(record.requestId))
  if (live) {
    for (const record of raw.harness) {
      if (record.event === 'experimentStarted') record.cognitionBackend = 'openai'
    }
  }
  const tail = raw.harness.splice(raw.harness.findIndex(
    (record) => record.event === 'cognitionStimulusAttested'
  ))
  let second = 30
  const at = () => `2026-10-13T00:00:${String(second++).padStart(2, '0')}.000Z`
  const stamp = (record) => {
    if (!live) return record
    const model = LIVE_MODEL
    if (record.provenance !== undefined) record.provenance = { ...record.provenance, model }
    if (record.decision?.provenance !== undefined) {
      record.decision.provenance = { ...record.decision.provenance, model }
    }
    return record
  }
  for (const record of [...raw.body, ...raw.harness]) stamp(record)
  if (live) {
    for (const record of raw.harness.filter((entry) => entry.event === 'cognitionForwarded')) {
      raw.harness.push(usageFor(record.agentId, record.requestId, at()))
    }
  }
  const perceive = (agentId, requestId, tick, trigger) => {
    const seen = perception(agentId, requestId, tick, config.primaryBehavior.initialPositions[agentId])
    seen.trigger = { event: trigger }
    raw.body.push(bodyRecord(tick, agentId, 'perceived', 'audited perception', {
      requestId,
      trigger,
      perception: seen,
      perceptionAudit: perceptionAuditFixture('empty', { timeOfDay: tick, day: 0, isDay: true })
    }))
    return seen
  }
  const origin = (trigger) => trigger === 'goalReview' ? 'nativeGoalReviewSchedule' : 'nativeEventDriver'
  const complete = (agentId, requestId, tick, trigger) => {
    const chose = stamp({ decision: decision(agentId, requestId, 'Stand still.', config.cognition.prompts.routine) }).decision
    raw.body.push(bodyRecord(tick + 1, agentId, 'decided', 'idle', {
      requestId,
      provenance: chose.provenance,
      intent: 'idle',
      decision: chose
    }))
    raw.body.push(bodyRecord(tick + 1, agentId, 'intentApplied', 'idle accepted', {
      requestId,
      intent: 'idle',
      outcome: 'accepted'
    }))
    raw.harness.push({
      observedAt: at(),
      event: 'cognitionForwarded',
      agentId,
      requestId,
      trigger,
      importance: 'ROUTINE',
      origin: origin(trigger),
      outcome: 'decision',
      provenance: chose.provenance
    })
    if (live) raw.harness.push(usageFor(agentId, requestId, at()))
  }
  const unavailable = (agentId, requestId, tick, trigger, seen) => {
    raw.body.push(bodyRecord(tick + 1, agentId, 'cognitionUnavailable', 'refused', {
      requestId,
      trigger,
      outcome: 'mindRefused',
      perception: seen
    }))
  }
  for (let index = 0; index < eventChains; index += 1) {
    const requestId = `event-${index + 1}`
    const tick = 400 + index * 10
    const agentId = eventAgents[index % eventAgents.length]
    perceive(agentId, requestId, tick, 'damaged')
    complete(agentId, requestId, tick, 'damaged')
  }
  const forwardedBefore = () => raw.harness.filter((record) => record.event === 'cognitionForwarded').length
  for (let index = 0; index < inFlight; index += 1) {
    perceive(FLIGHT_AGENTS[index % 3], `flight-${index + 1}`, stopTick - 5, 'damaged')
  }
  const triggerSeen = perceive(stopAgent, 'stop-1', stopTick, 'damaged')
  const request = {
    agentId: stopAgent,
    requestId: 'stop-1',
    trigger: 'damaged',
    importance: 'ROUTINE',
    origin: 'nativeEventDriver'
  }
  const forwardedCalls = forwardedBefore() + inFlight + (kind === 'providerUnavailable' ? 1 : 0)
  const perAgentCalls = raw.harness.filter((record) =>
    record.event === 'cognitionForwarded' && record.agentId === stopAgent
  ).length
  if (kind === 'cognitionBudgetExhausted') {
    const numbers = {
      perAgentCall: { unit: 'calls', ceiling: config.cognition.perAgentCallCeiling, observed: perAgentCalls },
      globalCall: { unit: 'calls', ceiling: config.cognition.globalCallCeiling, observed: forwardedCalls },
      globalOutstanding: {
        unit: 'calls',
        ceiling: config.cognition.globalOutstandingCeiling,
        observed: config.cognition.globalOutstandingCeiling
      },
      spend: {
        unit: 'nanoUsd',
        ceiling: Math.round(config.cognition.maxExternalSpendUsd * 1_000_000_000),
        observed: 0
      }
    }[bound]
    raw.harness.push({
      observedAt: at(),
      event: 'cognitionRefused',
      ...request,
      bound,
      reason: `${bound}Ceiling`,
      ...numbers,
      forwarded: false
    })
    raw.harness.push({
      observedAt: at(),
      event: 'cognitionAdmissionClosed',
      kind,
      ...request,
      bound,
      ...numbers,
      outstandingCalls: inFlight,
      forwardedCalls
    })
  } else {
    raw.harness.push({
      observedAt: at(),
      event: 'cognitionForwarded',
      ...request,
      outcome: 'cognitionFailure',
      provenance: null
    })
    raw.harness.push({
      observedAt: at(),
      event: 'cognitionAdmissionClosed',
      kind,
      ...request,
      classification: {
        httpStatus: 503,
        mapping: 'providerServer',
        model: live ? LIVE_MODEL : 'scripted-stand-in',
        providerCode: null,
        providerType: 'server_error',
        responseShape: 'apiErrorEnvelope'
      },
      outstandingCalls: 1 + inFlight,
      forwardedCalls
    })
  }
  unavailable(stopAgent, 'stop-1', stopTick, 'damaged', triggerSeen)
  for (let index = 0; index < inFlight; index += 1) {
    complete(FLIGHT_AGENTS[index % 3], `flight-${index + 1}`, stopTick + 2, 'damaged')
  }
  if (late) {
    const seen = perceive('tribe-alpha', 'late-1', stopTick + 20, 'damaged')
    raw.harness.push({
      observedAt: at(),
      event: 'cognitionRefused',
      agentId: 'tribe-alpha',
      requestId: 'late-1',
      bound: 'admissionClosed',
      reason: 'admissionClosed',
      unit: 'calls',
      ceiling: 0,
      observed: 0,
      forwarded: false,
      trigger: 'damaged',
      importance: 'ROUTINE',
      origin: 'nativeEventDriver'
    })
    unavailable('tribe-alpha', 'late-1', stopTick + 20, 'damaged', seen)
  }
  for (const record of tail) {
    if (record.event === 'experimentEnded') {
      record.stopReason = 'controlledStop'
      record.endTick = stopTick + 40
    }
  }
  raw.harness.push(...tail)
  return raw
}

function writeJsonLines (file, records, trailingNewline = true) {
  const text = records.map((record) => JSON.stringify(record)).join('\n')
  fs.writeFileSync(file, `${text}${trailingNewline ? '\n' : ''}`)
}

module.exports = {
  LIVE_MODEL,
  bodyRecord,
  configNamed,
  controlledStopRawRecords,
  decision,
  fixedRawRecords,
  goalContinuityConfig,
  openAiGoalContinuityConfig,
  openAiPrimaryBehaviorConfig,
  openAiProofConfig,
  perception,
  perceptionAuditFixture,
  primaryBehaviorRawRecords,
  primaryBehaviorConfig,
  proofConfig,
  writeJsonLines
}
