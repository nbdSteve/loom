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

function decision (agentId, requestId, reasoning = 'No bounded survival action is needed.') {
  return {
    requestId,
    agentId,
    intent: { action: 'idle' },
    reasoning,
    provenance: {
      tier: 'ROUTINE',
      model: 'scripted-stand-in',
      promptVersion: 'routine-v11'
    }
  }
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

function writeJsonLines (file, records, trailingNewline = true) {
  const text = records.map((record) => JSON.stringify(record)).join('\n')
  fs.writeFileSync(file, `${text}${trailingNewline ? '\n' : ''}`)
}

module.exports = {
  bodyRecord,
  decision,
  fixedRawRecords,
  openAiProofConfig,
  perception,
  proofConfig,
  writeJsonLines
}
