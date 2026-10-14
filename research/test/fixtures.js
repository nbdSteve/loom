'use strict'

const fs = require('node:fs')
const path = require('node:path')

const { readJson } = require('../lib/util.js')

function proofConfig () {
  const config = structuredClone(
    readJson(path.join(__dirname, '..', 'config', 'first-tribe-scripted-proof.json'))
  )
  config.configFile = path.join(__dirname, '..', 'config', 'first-tribe-scripted-proof.json')
  return config
}

function primaryBehaviorConfig () {
  const config = structuredClone(
    readJson(path.join(
      __dirname,
      '..',
      'config',
      'first-tribe-routine-v12-agency-pilot-scripted.json'
    ))
  )
  config.configFile = path.join(
    __dirname,
    '..',
    'config',
    'first-tribe-routine-v12-agency-pilot-scripted.json'
  )
  return config
}

function openAiPrimaryBehaviorConfig () {
  const config = structuredClone(
    readJson(path.join(
      __dirname,
      '..',
      'config',
      'first-tribe-routine-v12-agency-pilot.json'
    ))
  )
  config.configFile = path.join(
    __dirname,
    '..',
    'config',
    'first-tribe-routine-v12-agency-pilot.json'
  )
  return config
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
  promptVersion = 'routine-v11'
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

function primaryBehaviorRawRecords () {
  const config = primaryBehaviorConfig()
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
        'routine-v12'
      )
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
        decision: chose
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

function writeJsonLines (file, records, trailingNewline = true) {
  const text = records.map((record) => JSON.stringify(record)).join('\n')
  fs.writeFileSync(file, `${text}${trailingNewline ? '\n' : ''}`)
}

module.exports = {
  bodyRecord,
  decision,
  fixedRawRecords,
  openAiPrimaryBehaviorConfig,
  openAiProofConfig,
  perception,
  perceptionAuditFixture,
  primaryBehaviorRawRecords,
  primaryBehaviorConfig,
  proofConfig,
  writeJsonLines
}
