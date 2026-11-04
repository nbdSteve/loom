'use strict'

// The follow-up reviews a scheduled review may owe, by the mode that pins them: one from v23, and
// from v26 a second while an own goal-item drop lies (`MAX_FOLLOW_UPS_PER_REVIEW` in the JVM's
// schedule). A mode that pins none owes none.

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { execFileSync } = require('node:child_process')

const { currentSchemaVersion, validateManifest, versionTag } = require('../schema/manifest-schema.js')
const {
  ADMISSION_CLOSED_BOUND,
  ADMISSION_CLOSED_EVENT,
  ADMISSION_ENDED_EVENT,
  BUDGET_STOP_BOUNDS,
  CONTROLLED_STOP_DRAIN_MILLIS,
  CONTROLLED_STOP_REASON,
  PROVIDER_UNAVAILABLE_MAPPINGS,
  STOP_KINDS,
  isProviderDrainFailure,
  isRecordedFailureEnding,
  isRecordedFailureRequest,
  recordedFailureKeysOf
} = require('./controlled-stop.js')
const {
  METRICS_VERSION,
  NORMALIZED_EVIDENCE_VERSION,
  PUBLIC_SCAN_PATTERNS,
  deriveMetrics,
  normalizeEvidence,
  readJsonLines,
  scanText
} = require('./evidence.js')
const { isCoalesced, isExposureEnding, isShutdownEnding, unansweredAccountedFor } = require('./danger.js')
const { everyHeldAtBegin, heldAtBegin } = require('./initial-conditions.js')
const { CADENCE, PROFILES, SCENES, exposureOf, placementOf, profileFor, profileOf, ticksPerSecondOf } = require('./profiles.js')
const { RunFailure } = require('./lifecycle.js')
const {
  CRASH_CONTAINMENT_MODE,
  PRIMARY_BEHAVIOR_MODES,
  SERVED_PROMPTS,
  isHistoricalMode,
  primaryBehaviorModeOf,
  configuredMode,
  isPrimaryBehaviorMode,
  nativeOriginOf
} = require('./experiment-mode.js')
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
const PRIMARY_BEHAVIOR_DIRECTIVES = [
  'roles',
  'resourceTargets',
  'socialGoals',
  'hierarchy',
  'pairings',
  'conflict',
  'scriptedObjectives'
]

function configuredAgents (config) {
  return [...config.agents]
    .sort((first, second) => first.localeCompare(second))
    .map((agentId, index) => ({
      ...offlineIdentity(agentId),
      controlPort: config.network.bodyControlBasePort + index,
      processDirectory: `bodies/${offlineIdentity(agentId).username}`
    }))
}

const MAX_OBSERVERS = 4
const OBSERVER_USERNAME = /^[A-Za-z0-9_]{3,16}$/

function configuredObservers (config) {
  return config.observers ?? []
}

function observerErrors (config) {
  const observers = configuredObservers(config)
  if (!Array.isArray(observers) || observers.length > MAX_OBSERVERS) {
    return [`observers must be a list of at most ${MAX_OBSERVERS} usernames`]
  }
  const errors = []
  const keys = observers.map((name) => String(name).toLowerCase())
  if (new Set(keys).size !== keys.length) errors.push('observers contains a duplicate')
  const agentUsernames = new Set(
    (Array.isArray(config.agents) ? config.agents : []).flatMap((agentId) => {
      try {
        return [offlineIdentity(agentId).username.toLowerCase()]
      } catch {
        return []
      }
    })
  )
  for (const name of observers) {
    if (typeof name !== 'string' || !OBSERVER_USERNAME.test(name)) {
      errors.push(`observer ${JSON.stringify(name)} must be a Minecraft username of 3..16 letters, digits or underscores`)
    } else if (agentUsernames.has(name.toLowerCase())) {
      errors.push(`observer ${name} is an agent's username`)
    }
  }
  return errors
}

function observersWhoJoined (records) {
  return new Set(
    records
      .filter((record) => record.source === 'harness' && record.event === 'observerJoined')
      .map((record) => record.username.toLowerCase())
  ).size
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

/**
 * The configured worst case: every call at the dearest model's request and response bounds, or,
 * with a pivotal call ceiling, at most that many calls at the pivotal model's bounds and the rest
 * of the global ceiling at the ordinary model's (r24: 149 of 150 calls were spent while the run's
 * cheap routine and notable calls were priced as pivotal ones).
 */
function maximumConfiguredSpendNanoUsd (cognition) {
  const settings = cognition.cognition ?? cognition
  const pivotal = settings.pivotalCallCeiling
  if (!Number.isInteger(pivotal)) return worstCaseCallNanoUsd(cognition) * settings.globalCallCeiling
  const tier = worstCaseTierNanoUsd(cognition)
  // A JVM tier fallback sends danger past the pivotal ceiling at the ordinary tier inside the
  // global ceiling, so it is already one of the ordinary calls counted here: the bound is unchanged.
  return pivotal * tier.pivotal + (settings.globalCallCeiling - pivotal) * tier.ordinary
}

/** The worst one call can cost at each tier's model, under the request and response bounds. */
function worstCaseTierNanoUsd (cognition) {
  const settings = cognition.cognition ?? cognition
  const models = settings.models ?? cognition.provenance?.models
  const priced = (model) => {
    const rates = settings.pricing.models[model]
    const inputRate = Math.max(rates.inputNanoUsdPerToken, rates.cachedInputNanoUsdPerToken)
    return (settings.maxRequestBytes * inputRate) + (settings.maxOutputTokens * rates.outputNanoUsdPerToken)
  }
  return { ordinary: priced(models.ordinary), pivotal: priced(models.pivotal) }
}

function worstCaseCallNanoUsd (config) {
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
  return perCall
}

function sameJson (first, second) {
  try {
    assert.deepStrictEqual(first, second)
    return true
  } catch {
    return false
  }
}

function primaryBehaviorErrors (config) {
  const errors = []
  const behavior = config.primaryBehavior
  if (behavior === null || typeof behavior !== 'object' || Array.isArray(behavior)) {
    return ['primaryBehavior must configure the research-only behavior mode']
  }
  const profile = profileFor(profileOf(behavior.profile), config)
  if (profile === undefined) {
    return [`primaryBehavior.profile must be one of ${Object.keys(PROFILES).join(', ')}`]
  }
  const scene = SCENES[profile.scene]
  const placement = placementOf(profile)
  if (profile.scriptedOnly === true && config.cognition?.backend !== 'scripted') {
    errors.push(`profile ${behavior.profile} is provider-free only`)
  }
  if (!sameJson([...(config.agents ?? [])].sort(), [...placement.agents].sort())) {
    errors.push(`profile ${behavior.profile} must use ${placement.agents.join(', ')}`)
  }
  if (!sameJson(behavior.initialPositions, placement.positions)) {
    errors.push(`primaryBehavior.initialPositions must pin the ${profile.scene} slots for ${behavior.profile}`)
  }
  if (behavior.scene !== profile.scene) {
    errors.push(`primaryBehavior.scene must be ${profile.scene} for ${behavior.profile}`)
  }
  const clock = behavior.initialWorldClock
  // A scene may start later in the day so that night, and the danger it brings, falls inside a
  // bounded run; every scene before `e1-explore-ring` starts at dawn.
  const startTime = scene?.initialTimeOfDay ?? 0
  if (
    clock?.timeOfDay !== startTime ||
    clock?.day !== 0 ||
    clock?.isDay !== true
  ) {
    errors.push(`primaryBehavior.initialWorldClock must pin day 0 at time ${startTime}`)
  }
  // A profile that pre-registers its exposure holds exactly that daylight and night (the r33 audit).
  if (profile.exposure !== undefined &&
      !sameJson(exposureOf(startTime, profile.simulationTickCeiling), { ...profile.exposure })) {
    errors.push(`profile ${behavior.profile} must hold ${JSON.stringify(profile.exposure)} ticks of daylight and night`)
  }
  const schedule = behavior.nativeGoalReviewSchedule
  const cadence = profile.cadence ?? CADENCE
  if (
    schedule?.cadenceTicks !== cadence.cadenceTicks ||
    schedule?.sliceTicks !== cadence.sliceTicks ||
    schedule?.reviewsInFlightPerAgent !== cadence.reviewsInFlightPerAgent ||
    schedule?.expectedReviewsPerAgent !== profile.expectedReviewsPerAgent
  ) {
    errors.push(
      `primaryBehavior.nativeGoalReviewSchedule must pin ${cadence.cadenceTicks}/${cadence.sliceTicks} ticks and ` +
        `${profile.expectedReviewsPerAgent} reviews per agent for ${behavior.profile}`
    )
  }
  // From routine-v23 each scheduled review may be followed by one review once its goal step
  // settles (`MAX_FOLLOW_UPS_PER_REVIEW` in `GoalReviewSchedule.kt`), so the planned reviews are
  // the scheduled ones and as many follow-ups, and both ceilings must hold them.
  const followUps = schedule?.followUpsPerReview ?? 0
  // A profile may pin its own plan of follow-ups in place of its mode's (r35: one a review left
  // 26 of 60 day steps waiting a whole cadence).
  const pinnedFollowUps = profile.followUpsPerReview ?? primaryBehaviorModeOf(config.mode)?.followUpsPerReview ?? 0
  if (followUps !== 0 && followUps !== pinnedFollowUps) {
    errors.push(`primaryBehavior.nativeGoalReviewSchedule.followUpsPerReview must be ${pinnedFollowUps} when stated`)
  }
  const plannedReviews = Number.isInteger(schedule?.expectedReviewsPerAgent)
    ? schedule.expectedReviewsPerAgent * (1 + followUps)
    : undefined
  if (plannedReviews !== undefined && plannedReviews > config.cognition?.perAgentCallCeiling) {
    errors.push('planned native reviews exceed the per-agent cognition call ceiling')
  }
  if (
    plannedReviews !== undefined &&
    Array.isArray(config.agents) &&
    plannedReviews * config.agents.length > config.cognition?.globalCallCeiling
  ) {
    errors.push('planned native reviews exceed the global cognition call ceiling')
  }
  // From routine-v29 the plan must leave the mode's share of the global ceiling to events: r20
  // planned exactly 150 reviews against a ceiling of 150, and the night's danger stopped the run.
  const reserveShare = primaryBehaviorModeOf(config.mode)?.eventCallReserveShare ?? 0
  if (
    plannedReviews !== undefined &&
    reserveShare > 0 &&
    Array.isArray(config.agents) &&
    plannedReviews * config.agents.length > Math.floor(config.cognition?.globalCallCeiling * (1 - reserveShare))
  ) {
    errors.push('planned native reviews leave less than the mode\'s event share of the global cognition call ceiling')
  }
  if (behavior.externalCognitionStimuli !== 'none') {
    errors.push('primaryBehavior.externalCognitionStimuli must be none')
  }
  if (behavior.startingInventories !== 'empty') {
    errors.push('primaryBehavior.startingInventories must be empty')
  }
  for (const directive of PRIMARY_BEHAVIOR_DIRECTIVES) {
    if (behavior.injectedDirectives?.[directive] !== false) {
      errors.push(`primaryBehavior.injectedDirectives.${directive} must be false`)
    }
  }
  const pinned = primaryBehaviorModeOf(configuredMode(config))
  if (behavior.categoryFairSelection !== pinned.categoryFairSelection) {
    errors.push(`primaryBehavior.categoryFairSelection must be ${pinned.categoryFairSelection} for ${configuredMode(config)}`)
  }
  if ((schedule?.followUpsPerReview ?? 0) !== pinnedFollowUps) {
    errors.push(
      `primaryBehavior.nativeGoalReviewSchedule.followUpsPerReview must be ${pinnedFollowUps} ` +
        `for ${configuredMode(config)} and ${behavior.profile}`
    )
  }
  if (config.proof !== undefined) {
    errors.push('the primary behavior mode cannot configure crash or operator-think proof traffic')
  }
  if (
    (scene.seed === null ? !/^(-?[0-9]+|random)$/.test(config.world?.seed ?? '') : config.world?.seed !== scene.seed) ||
    config.world?.difficulty !== (profile.world?.difficulty ?? 'normal') ||
    config.world?.mobSpawning !== (profile.world?.mobSpawning ?? true) ||
    config.world?.worldBorderDiameterBlocks !== (profile.world?.borderDiameterBlocks ?? 128) ||
    config.world?.viewDistanceChunks !== (profile.world?.viewDistanceChunks ?? 4) ||
    config.world?.simulationDistanceChunks !== (profile.world?.simulationDistanceChunks ?? 4)
  ) {
    errors.push(`profile ${behavior.profile} must use the ${profile.scene} seed, its pinned difficulty and mob rule, border, and distances`)
  }
  if (
    config.bounds?.runDurationSeconds !== profile.runDurationSeconds ||
    config.bounds?.simulationTickCeiling !== profile.simulationTickCeiling
  ) {
    errors.push(
      `profile ${behavior.profile} must run for ${profile.runDurationSeconds} seconds or ${profile.simulationTickCeiling} simulation ticks`
    )
  }
  if (profile.bounds !== undefined) {
    for (const [name, value] of Object.entries(profile.bounds)) {
      if (config.bounds?.[name] !== value) errors.push(`profile ${behavior.profile} must use bounds.${name} ${value}`)
    }
  }
  if (profile.paperMemoryMiB !== undefined && config.runtime?.paperMemoryMiB !== profile.paperMemoryMiB) {
    errors.push(`profile ${behavior.profile} must use runtime.paperMemoryMiB ${profile.paperMemoryMiB}`)
  }
  const ceilings = profile.callCeilings
  if (
    config.cognition?.perAgentCallCeiling !== ceilings.perAgent ||
    config.cognition?.globalCallCeiling !== ceilings.global ||
    config.cognition?.globalOutstandingCeiling !== ceilings.outstanding ||
    config.cognition?.pivotalCallCeiling !== ceilings.pivotal
  ) {
    errors.push(
      `profile ${behavior.profile} must use the ${ceilings.perAgent}/${ceilings.global}/${ceilings.outstanding} cognition ceilings`
    )
  }
  // More bridge workers than outstanding slots turns ordinary concurrency into refusals.
  if (
    !Number.isInteger(config.runtime?.cognitionBridge?.workers) ||
    config.runtime.cognitionBridge.workers > config.cognition?.globalOutstandingCeiling
  ) {
    errors.push('primary behavior bridge workers must not exceed the global outstanding ceiling')
  }
  if (
    config.cognition?.providerTimeoutMillis !== 12_000 ||
    config.cognition?.maxRequestBytes !== 65_536 ||
    config.cognition?.maxOutputTokens !== 2048
  ) {
    errors.push('the primary behavior mode must retain the provider timeout and request/output bounds')
  }
  if (!sameJson(config.cognition?.prompts, { ...pinned.prompts })) {
    errors.push(`${configuredMode(config)} must use prompts ${Object.values(pinned.prompts).join(', ')}`)
  }
  if (
    config.cognition?.backend === 'openai' &&
    (
      config.cognition.models?.ordinary !== 'gpt-5.4-nano' ||
      config.cognition.models?.pivotal !== 'gpt-5.4' ||
      config.cognition.maxExternalSpendUsd !== profile.maxExternalSpendUsd
    )
  ) {
    errors.push(`the live ${behavior.profile} profile must retain gpt-5.4-nano/gpt-5.4 and the $${profile.maxExternalSpendUsd} ceiling`)
  }
  return errors
}

function validateRunConfig (config) {
  const errors = []
  if (config === null || typeof config !== 'object' || Array.isArray(config)) {
    return ['configuration is not a JSON object']
  }
  if (!/^[0-9]{4}-[0-9]{2}-[0-9]{2}-[a-z0-9][a-z0-9-]*$/.test(config.experimentId ?? '')) {
    errors.push('experimentId must be a dated lowercase slug')
  }
  const modes = [CRASH_CONTAINMENT_MODE, ...Object.keys(PRIMARY_BEHAVIOR_MODES)]
  if (isHistoricalMode(configuredMode(config))) {
    errors.push(`mode ${configuredMode(config)} is historical; its artifacts still validate but no run may select it`)
  } else if (!modes.includes(configuredMode(config))) {
    errors.push(`mode must be one of ${modes.join(', ')}`)
  }
  if (!sameJson(config.cognition?.prompts, { ...SERVED_PROMPTS })) {
    errors.push(`cognition.prompts must be the served ${Object.values(SERVED_PROMPTS).join(', ')}; this configuration is historical`)
  }
  // No runtime route reads a flag, so a flag set false would be a false manifest claim.
  for (const [flag, value] of Object.entries(config.featureFlags ?? {})) {
    if (value !== true) errors.push(`featureFlags.${flag} must be true: no runtime route can switch it off`)
  }
  if (!Array.isArray(config.agents) || config.agents.length < 1 || config.agents.length > 16) {
    errors.push('agents must contain 1..16 agent ids')
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
  errors.push(...observerErrors(config))
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
  // `random` asks the harness to draw a fresh seed at launch (a drawn world's config only).
  if (config.world?.seed === undefined || !/^(-?[0-9]+|random)$/.test(String(config.world.seed))) {
    errors.push('world.seed must be an explicit integer string or random')
  }
  if (!['peaceful', 'easy', 'normal', 'hard'].includes(config.world?.difficulty)) {
    errors.push('world.difficulty must be peaceful, easy, normal, or hard')
  }
  if (typeof config.world?.mobSpawning !== 'boolean') {
    errors.push('world.mobSpawning must be a boolean')
  }
  if (config.world?.worldBorderDiameterBlocks > 2 * config.world?.maxWorldSizeBlocks) {
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
  const pivotalCeiling = config.cognition?.pivotalCallCeiling
  if (pivotalCeiling !== undefined && (!Number.isInteger(pivotalCeiling) || pivotalCeiling < 0 ||
    pivotalCeiling > config.cognition?.globalCallCeiling)) {
    errors.push('cognition.pivotalCallCeiling must be an integer within the global call ceiling')
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
  if (isPrimaryBehaviorMode(config)) {
    errors.push(...primaryBehaviorErrors(config))
  } else {
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
    if (
      !Number.isInteger(config.proof?.postCrashCallsPerPeer) ||
      config.proof.postCrashCallsPerPeer < 1 ||
      config.proof.postCrashCallsPerPeer >= config.cognition?.perAgentCallCeiling
    ) {
      errors.push('proof.postCrashCallsPerPeer must fit below the per-agent call ceiling')
    } else if (
      Array.isArray(config.agents) &&
      config.proof.postCrashCallsPerPeer * Math.max(0, config.agents.length - 1) >=
        config.cognition?.globalCallCeiling
    ) {
      errors.push('the post-crash peer reserve must fit below the global call ceiling')
    }
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
      disconnects: 0,
      witnessDisputeRecords: 0,
      witnessDisputesByOutcome: {}
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

function initialConditionsMatch (config, metrics) {
  const observed = metrics.perceptionAudit?.initialByAgent ?? []
  const witnessed = metrics.initialConditionsWitnessed
  if (
    metrics.primaryBehavior?.initialConditionAttestations !== 1 ||
    !sameJson(
      metrics.primaryBehavior.configuredInitialPositions,
      config.primaryBehavior.initialPositions ?? null
    ) ||
    !sameJson(
      metrics.primaryBehavior.configuredInitialWorldClock,
      config.primaryBehavior.initialWorldClock
    ) ||
    metrics.primaryBehavior.startingInventories !== 'empty' ||
    observed.length !== config.agents.length
  ) {
    return false
  }
  // Where the server read every agent at begin, that reading witnesses the pinned conditions and
  // each first perception states only the clock (`initial-conditions.js`).
  if (witnessed !== undefined) {
    return everyHeldAtBegin(witnessed, config.agents, config.primaryBehavior.initialPositions) &&
      observed.every((entry) => entry.worldClock !== undefined)
  }
  return observed.every((entry) =>
    (config.primaryBehavior.initialPositions === undefined ||
      sameJson(entry.position, config.primaryBehavior.initialPositions[entry.agentId])) &&
    entry.inventoryStacks === 0 &&
    entry.worldClock !== undefined
  )
}

function expectedNativeReviewsObserved (config, metrics) {
  const expected = config.primaryBehavior.nativeGoalReviewSchedule.expectedReviewsPerAgent
  return config.agents.every((agentId) => agentReviewsObserved(agentId, metrics, expected))
}

// A review held when the window closed was due and owed, so it counts toward the minimum; one the
// JVM refused at its agent's spent ceiling was due and typed, so it counts as attempted.
function windowEntryHolds (entry) {
  if (entry === undefined) return false
  const attempted = entry.attempted + (entry.refusedAtCeiling ?? 0)
  return Number.isInteger(entry.minimum) &&
    Number.isInteger(entry.maximum) &&
    attempted + (entry.heldAtEnd === 1 ? 1 : 0) >= entry.minimum &&
    attempted <= entry.maximum &&
    entry.completed <= entry.attempted
}

// A run owes each agent's full plan, unless a death or a controlled stop ended that
// agent's window first; then it owes only the reviews that window made due.
function agentReviewsObserved (agentId, metrics, expected) {
  const death = metrics.witnessedDeaths?.byAgent?.[agentId]
  const stop = metrics.controlledStop
  if (death !== undefined && (stop === undefined || stop.stopTick === null || death.tick <= stop.stopTick)) {
    return windowEntryHolds(death)
  }
  if (stop !== undefined) return windowEntryHolds(stop.nativeReviews?.byAgent?.[agentId])
  if (metrics.admissionEnd !== undefined) {
    return windowEntryHolds(metrics.admissionEnd.nativeReviews?.byAgent?.[agentId])
  }
  return metrics.primaryBehavior?.nativeGoalReviewsByAgent?.[agentId] === expected
}

function protocolFor (config, metrics) {
  if (!isPrimaryBehaviorMode(config)) {
    return {
      mode: CRASH_CONTAINMENT_MODE,
      goalReviewCycleSeconds: config.proof.cycleIntervalSeconds,
      difficulty: config.world.difficulty,
      mobSpawning: config.world.mobSpawning,
      controlledBodyCrash: {
        agentId: config.proof.crashAgent,
        afterSeconds: config.proof.crashAfterSeconds,
        reservedCallsPerPeer: config.proof.postCrashCallsPerPeer,
        signal: 'SIGKILL'
      }
    }
  }
  return {
    mode: configuredMode(config),
    difficulty: config.world.difficulty,
    mobSpawning: config.world.mobSpawning,
    controlledBodyCrashPlanned: false,
    nativeGoalReviewSchedule: structuredClone(
      config.primaryBehavior.nativeGoalReviewSchedule
    ),
    externalCognitionStimuli: {
      configured: config.primaryBehavior.externalCognitionStimuli,
      harnessThinkCommandsIssued:
        metrics.primaryBehavior?.harnessThinkCommandsIssued ?? 0,
      nativeSchedulersOnly:
        metrics.primaryBehavior?.nativeSchedulersOnly ?? false,
      attested: {
        source: 'bounded harness intervention schedule and cognition request-origin journal'
      }
    },
    initialConditions: {
      configuredPositions: structuredClone(config.primaryBehavior.initialPositions),
      configuredWorldClock: structuredClone(config.primaryBehavior.initialWorldClock),
      startingInventories: config.primaryBehavior.startingInventories,
      observedFirstPerceptions:
        structuredClone(metrics.perceptionAudit?.initialByAgent ?? []),
      ...(metrics.initialConditionsWitnessed === undefined
        ? {
            attested: {
              source: 'Paper setup commands plus exact first body perception for each agent'
            }
          }
        : {
            witnessedAtBegin: structuredClone(metrics.initialConditionsWitnessed),
            attested: {
              source: 'Paper setup commands, the server reading of each agent when the run began, and each first body perception for its world clock'
            }
          })
    },
    injectedDirectives: structuredClone(config.primaryBehavior.injectedDirectives),
    ...(config.primaryBehavior.profile === undefined
      ? {}
      : {
          profile: config.primaryBehavior.profile,
          serverTicksPerSecond: ticksPerSecondOf(profileOf(config.primaryBehavior.profile) ?? {}),
          sceneCoverRule: SCENES[config.primaryBehavior.scene]?.coverRule ?? null
        }),
    scene: config.primaryBehavior.scene,
    categoryFairSelection: config.primaryBehavior.categoryFairSelection
  }
}

function integrityChecksFor (config, metrics, agents, warnings) {
  const common = {
    threeIndependentBodies: {
      passed:
        agents.length >= 3 &&
        metrics.records.agents.filter((agent) => agent.byEvent.spawned > 0).length >= 3,
      derivedFrom: EVIDENCE_FILE
    },
    completeDecisionChains: {
      passed: metrics.cycles.incomplete.length === 0,
      derivedFrom: EVIDENCE_FILE
    },
    cleanupVerified: {
      passed: metrics.crashContainment.cleanupVerified,
      derivedFrom: EVIDENCE_FILE
    },
    absolutePermadeathEnabled: {
      passed: config.featureFlags.permadeath === true,
      configuredBy: 'Loom player witness and synchronous death ledger'
    },
    noDroppedEvidenceWarning: {
      passed: !warnings.some((warning) => warning.includes('dropped')),
      attested: { source: 'bounded Paper log scan performed by the harness' }
    },
    serverEvidenceUncontested: {
      passed: metrics.behavior.witnessDisputeRecords === 0,
      derivedFrom: EVIDENCE_FILE
    }
  }
  if (!isPrimaryBehaviorMode(config)) {
    return {
      ...common,
      crashContained: {
        passed:
          metrics.crashContainment.controlledCrashes.length > 0 &&
          Object.keys(metrics.crashContainment.peerDecisionsAfterCrash).length >= 2,
        derivedFrom: EVIDENCE_FILE
      }
    }
  }
  return {
    ...common,
    noControlledBodyCrash: {
      passed: metrics.crashContainment.controlledCrashes.length === 0,
      derivedFrom: EVIDENCE_FILE
    },
    nativeSchedulersOnly: {
      passed:
        metrics.primaryBehavior?.stimulusAttestations === 1 &&
        metrics.primaryBehavior.externalCognitionStimuli === 'none' &&
        metrics.primaryBehavior.harnessThinkCommandsIssued === 0 &&
        metrics.primaryBehavior.nativeSchedulersOnly === true &&
        !Object.hasOwn(metrics.primaryBehavior.requestsByOrigin, 'unattested'),
      derivedFrom: EVIDENCE_FILE
    },
    expectedNativeReviewsObserved: {
      passed: expectedNativeReviewsObserved(config, metrics),
      derivedFrom: EVIDENCE_FILE
    },
    initialConditionsRecorded: {
      passed: initialConditionsMatch(config, metrics),
      derivedFrom: EVIDENCE_FILE
    },
    perKindPerceptionAuditComplete: {
      passed:
        metrics.perceptionAudit?.complete === true &&
        metrics.perceptionAudit.samples === metrics.cycles.perceived,
      derivedFrom: EVIDENCE_FILE
    },
    ...(metrics.witnessedDeaths === undefined
      ? {}
      : {
          witnessedDeathsAccounted: {
            passed: metrics.witnessedDeaths.violation === null,
            derivedFrom: EVIDENCE_FILE
          }
        }),
    ...(metrics.controlledStop === undefined
      ? {}
      : {
          controlledStopAccounted: {
            passed:
              metrics.controlledStop.admissionClosures === 1 &&
              STOP_KINDS.includes(metrics.controlledStop.kind) &&
              metrics.controlledStop.stopTick !== null &&
              metrics.controlledStop.behavioralVerdict === 'no-go',
            derivedFrom: EVIDENCE_FILE
          }
        }),
    plannedNativeCallsFitCeilings: {
      passed:
        config.primaryBehavior.nativeGoalReviewSchedule.expectedReviewsPerAgent <=
          config.cognition.perAgentCallCeiling &&
        config.primaryBehavior.nativeGoalReviewSchedule.expectedReviewsPerAgent *
          config.agents.length <= config.cognition.globalCallCeiling,
      configuredBy: 'native GoalReviewSchedule plan and cognition ceilings'
    }
  }
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
  validation,
  observersJoined = 0
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
      interactiveObservers: observersJoined,
      observers: [...configuredObservers(config)],
      admission: 'agentsAndObservers'
    },
    network: { ...config.network },
    runtime: structuredClone(config.runtime),
    protocol: protocolFor(config, metricValue),
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
      ...(Number.isInteger(config.cognition.pivotalCallCeiling)
        ? { pivotalCallCeiling: config.cognition.pivotalCallCeiling }
        : {}),
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
    integrityChecks: integrityChecksFor(config, metricValue, agents, warnings),
    knownWarnings: warnings,
    outcome: outcomeStatement(config, metricValue),
    scientificUsability: {
      verdict: scientificVerdict(config),
      scope: [
        'The configured isolated bodies shared one disposable Paper world under fixed resource and cognition bounds.',
        'Recorded per-agent cycles, action outcomes, lifecycle failures, and cleanup can be reproduced from normalized evidence.'
      ],
      outOfScope: [
        'No single bounded run establishes durable cooperation, role specialization, hierarchy, settlement, or conflict norms.',
        scientificOutOfScope(config)
      ]
    },
    publication: {
      manifestValidatedBeforeLaunch: validation.beforeLaunch,
      manifestValidatedBeforePublication: validation.beforePublication,
      normalized: true,
      rawWorldCommitted: false,
      serverLogsCommitted: false,
      deathLedgerCommitted: false
    },
    ...(isPrimaryBehaviorMode(config)
      ? { perceptionAudit: structuredClone(metricValue.perceptionAudit ?? null) }
      : {}),
    ...(metricValue.controlledStop === undefined
      ? {}
      : { controlledStop: structuredClone(metricValue.controlledStop) }),
    ...(metricValue.witnessedDeaths === undefined
      ? {}
      : { witnessedDeaths: structuredClone(metricValue.witnessedDeaths) }),
    ...(metricValue.behaviorGate === undefined
      ? {}
      : { behaviorGate: structuredClone(metricValue.behaviorGate) })
  }
}

function scientificVerdict (config) {
  if (config.cognition.backend === 'scripted') {
    return isPrimaryBehaviorMode(config)
      ? 'Usable as a credential-free proof of startup, native scheduling, audited publication, and cleanup; not as a behavior result.'
      : 'Usable as a bounded composition, evidence, crash-containment, and cleanup proof; not as evidence of model-driven emergence.'
  }
  return 'Usable only for the observations and counterevidence derivable from this bounded run.'
}

function scientificOutOfScope (config) {
  if (config.cognition.backend === 'scripted') {
    return 'Scripted cognition and harness staging are never evidence of autonomous behavior or emergence.'
  }
  return isPrimaryBehaviorMode(config)
    ? 'Pinned starting conditions are an experimental intervention; no goal, role, resource target, social policy, or cognition stimulus was injected.'
    : 'The controlled crash and fixed experimental setup are interventions, not emergent behavior.'
}

function outcomeStatement (config, metrics) {
  const stop = metrics.controlledStop
  if (stop !== undefined) {
    return `Controlled ${stop.kind} stop; behavioral verdict no-go. ` +
      `${stop.chains.complete} complete decision chain(s) and ${stop.chains.refused} typed refusal chain(s) were preserved; ` +
      `${stop.nativeReviews.completed} of ${stop.nativeReviews.planned} planned native reviews completed.`
  }
  if (isPrimaryBehaviorMode(config)) {
    const nativeReviews = Object.values(
      metrics.primaryBehavior?.nativeGoalReviewsByAgent ?? {}
    ).reduce((sum, count) => sum + count, 0)
    return `${config.agents.length} configured bodies produced ${metrics.cycles.decided} decisions in one bounded shared world; ` +
      `${nativeReviews} cognition request(s) came from the native goal-review schedule, with no harness-raised cognition stimulus or controlled body crash.`
  }
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

function primaryBehaviorSemanticErrors (manifest, metrics, records) {
  const errors = []
  const protocol = manifest.protocol
  if (Object.hasOwn(protocol, 'controlledBodyCrash')) {
    errors.push('primary behavior protocol cannot contain a controlled body crash')
  }
  if (protocol.controlledBodyCrashPlanned !== false) {
    errors.push('primary behavior protocol must attest that no body crash was planned')
  }
  const pinned = primaryBehaviorModeOf(protocol.mode)
  if (protocol.categoryFairSelection !== pinned.categoryFairSelection) {
    errors.push(`${protocol.mode} protocol must attest categoryFairSelection ${pinned.categoryFairSelection}`)
  }
  if (metrics.crashContainment.controlledCrashes.length !== 0) {
    errors.push('primary behavior evidence contains a controlled body crash')
  }
  if (
    records.some((record) =>
      record.source === 'harness' &&
      ['processExited', 'processGroupSignalled'].includes(record.event) &&
      record.signal === 'SIGKILL'
    )
  ) {
    errors.push('primary behavior evidence contains a process terminated by SIGKILL')
  }
  const stopped = manifest.timing.stopReason === CONTROLLED_STOP_REASON
  const deaths = metrics.witnessedDeaths
  const ended = metrics.admissionEnd
  if (ended !== undefined) errors.push(...admissionEndErrors(manifest, metrics, records))
  const refusedAfterEnd = ended?.refusedAfterEnd ?? 0
  const refusedKeys = new Set(
    records.filter((record) => record.source === 'harness' && record.event === 'cognitionRefused')
      .map((record) => record.requestKey)
  )
  const bodyOnly = records.filter((record) => record.source === 'body')
  const recordedFailureKeys = recordedFailureKeysOf(records.filter((record) => record.source === 'harness'))
  errors.push(...recordedFailureErrors(recordedFailureKeys, bodyOnly))
  if (
    (!stopped && (
      metrics.cognition.refusedCalls !== refusedAfterEnd ||
      !unansweredAccountedFor(bodyOnly, { admissionEnded: ended !== undefined, refusedKeys, recordedFailureKeys })
    ))
  ) {
    errors.push('primary behavior evidence contains a cognition refusal or unavailable decision')
  }
  if (stopped || metrics.controlledStop !== undefined || manifest.controlledStop !== undefined) {
    errors.push(...controlledStopErrors(manifest, metrics, records))
  }
  if (deaths === undefined) {
    if (metrics.behavior.deaths !== 0 || metrics.behavior.disconnects !== 0) {
      errors.push('primary behavior evidence contains a death or unmanaged body disconnect')
    }
  } else {
    if (deaths.violation !== null) errors.push(`primary behavior death evidence is inconsistent: ${deaths.violation}`)
    if (deaths.deaths !== metrics.behavior.deaths) errors.push('witnessed deaths do not match the death count')
    if (!sameJson(manifest.witnessedDeaths, deaths)) {
      errors.push('manifest witnessed deaths do not match metrics.json')
    }
  }
  if (!sameJson(manifest.behaviorGate, metrics.behaviorGate)) {
    errors.push('manifest behavioral gate does not match metrics.json')
  }
  if (stopped && metrics.behaviorGate?.pilotVerdict === 'pass') {
    errors.push('a controlled stop cannot carry a passing behavioral gate')
  }
  if (deaths === undefined && manifest.witnessedDeaths !== undefined) {
    errors.push('manifest claims witnessed deaths the evidence does not hold')
  }
  if (!sameJson(manifest.perceptionAudit, metrics.perceptionAudit)) {
    errors.push('manifest per-kind perception audit does not match metrics.json')
  }
  if (
    metrics.perceptionAudit?.complete !== true ||
    metrics.perceptionAudit.samples !== metrics.cycles.perceived
  ) {
    errors.push('primary behavior perception audit is incomplete')
  }
  const primary = metrics.primaryBehavior
  if (
    primary?.stimulusAttestations !== 1 ||
    primary.initialConditionAttestations !== 1 ||
    primary.externalCognitionStimuli !== 'none' ||
    primary.harnessThinkCommandsIssued !== 0 ||
    primary.nativeSchedulersOnly !== true
  ) {
    errors.push('primary behavior native-only cognition attestation is incomplete or inconsistent')
  }
  if (
    !sameJson(
      primary?.nativeGoalReviewSchedule,
      protocol.nativeGoalReviewSchedule
    ) ||
    !sameJson(
      primary?.configuredInitialPositions,
      protocol.initialConditions?.configuredPositions ?? null
    ) ||
    !sameJson(
      primary?.configuredInitialWorldClock,
      protocol.initialConditions?.configuredWorldClock
    ) ||
    primary?.startingInventories !== protocol.initialConditions?.startingInventories
  ) {
    errors.push('primary behavior harness attestations do not match the manifest protocol')
  }
  for (const record of records) {
    if (
      record.source !== 'harness' ||
      !['cognitionForwarded', 'cognitionRefused'].includes(record.event)
    ) continue
    // The same rule the proxy stamps origins by, so a follow-up review is its own native source.
    if (record.origin !== nativeOriginOf(record.trigger)) {
      errors.push(`cognition request ${record.requestKey ?? record.recordId} has no valid native origin`)
    }
  }
  const expectedReviews = protocol.nativeGoalReviewSchedule?.expectedReviewsPerAgent
  for (const agent of manifest.agents) {
    if (!agentReviewsObserved(agent.agentId, metrics, expectedReviews)) {
      errors.push(`${agent.agentId} did not record the expected native scheduled reviews`)
    }
  }
  const observed = new Map(
    (metrics.perceptionAudit?.initialByAgent ?? [])
      .map((entry) => [entry.agentId, entry])
  )
  const witnessedAtBegin = metrics.initialConditionsWitnessed === undefined
    ? undefined
    : new Map(metrics.initialConditionsWitnessed.map((entry) => [entry.agentId, entry]))
  if (witnessedAtBegin !== undefined && metrics.initialConditionsWitnessed.length !== witnessedAtBegin.size) {
    errors.push('an agent was read more than once when the run began')
  }
  for (const agent of manifest.agents) {
    const first = observed.get(agent.agentId)
    const configured = protocol.initialConditions?.configuredPositions?.[agent.agentId]
    if (witnessedAtBegin !== undefined) {
      if (!heldAtBegin(witnessedAtBegin.get(agent.agentId), configured)) {
        errors.push(`${agent.agentId} did not hold its pinned initial conditions when the run began`)
      }
      if (first === undefined || first.worldClock === undefined) {
        errors.push(`${agent.agentId} has no initial perception stating its world clock`)
      }
      continue
    }
    if (
      first === undefined ||
      !sameJson(first.position, configured) ||
      first.inventoryStacks !== 0 ||
      first.worldClock === undefined
    ) {
      errors.push(`${agent.agentId} has no matching empty-inventory initial perception`)
    }
  }
  for (const name of [
    'threeIndependentBodies',
    'completeDecisionChains',
    'cleanupVerified',
    'noControlledBodyCrash',
    'nativeSchedulersOnly',
    'expectedNativeReviewsObserved',
    'initialConditionsRecorded',
    'perKindPerceptionAuditComplete',
    'plannedNativeCallsFitCeilings',
    'noDroppedEvidenceWarning',
    // `serverEvidenceUncontested` is stated, never required: a witness dispute is no-go evidence.
    ...(stopped ? ['controlledStopAccounted'] : []),
    ...(deaths === undefined ? [] : ['witnessedDeathsAccounted'])
  ]) {
    if (manifest.integrityChecks[name]?.passed !== true) {
      errors.push(`primary behavior integrity check ${name} did not pass`)
    }
  }
  return errors
}

function worstCaseManifestCallNanoUsd (manifest) {
  let perCall = 0
  for (const model of new Set(Object.values(manifest.provenance.models))) {
    const rates = manifest.cognition.pricing.models[model]
    if (rates === undefined) continue
    perCall = Math.max(
      perCall,
      (manifest.cognition.maxRequestBytes *
        Math.max(rates.inputNanoUsdPerToken, rates.cachedInputNanoUsdPerToken)) +
        (manifest.cognition.maxOutputTokens * rates.outputNanoUsdPerToken)
    )
  }
  return perCall
}

function typedRefusal (record) {
  return [...BUDGET_STOP_BOUNDS, ADMISSION_CLOSED_BOUND].includes(record.bound) &&
    ['calls', 'nanoUsd'].includes(record.unit) &&
    Number.isSafeInteger(record.ceiling) &&
    Number.isSafeInteger(record.observed) &&
    record.forwarded === false
}

// Every recorded cognition failure is one perception the JVM ended as failed, never answered.
function recordedFailureErrors (recordedFailureKeys, body) {
  const errors = []
  for (const key of recordedFailureKeys) {
    const chain = body.filter((record) => record.requestKey === key)
    if (
      !chain.some((record) => record.event === 'perceived') ||
      !chain.some((record) => isRecordedFailureEnding(record, recordedFailureKeys)) ||
      chain.some((record) => ['decided', 'intentApplied'].includes(record.event))
    ) {
      errors.push(`failed cognition request ${key} has no matching failed perception chain`)
    }
  }
  return errors
}

// A normal ending closes admission once; only typed admission refusals may follow it.
function admissionEndErrors (manifest, metrics, records) {
  const errors = []
  const ended = metrics.admissionEnd
  const harness = records.filter((record) => record.source === 'harness')
  const body = records.filter((record) => record.source === 'body')
  const endIndex = harness.findIndex((record) => record.event === ADMISSION_ENDED_EVENT)
  if (ended.closures !== 1) errors.push('a run must end admission at most once')
  if (manifest.timing.stopReason === CONTROLLED_STOP_REASON) {
    errors.push('a controlled stop cannot also end admission normally')
  }
  if (!['durationElapsed', 'simulationTickCeiling', 'allAgentsDied'].includes(ended.reason) || ended.reason !== manifest.timing.stopReason) {
    errors.push('admission end does not name the run stop reason')
  }
  if (ended.reason === 'allAgentsDied' && metrics.witnessedDeaths?.deaths !== manifest.agents.length) {
    errors.push('a run that stopped because every agent died must record every agent dying')
  }
  if (
    ended.endTick === null ||
    ended.endTick > metrics.timing.lastTick ||
    ended.windowTicks < 0
  ) {
    errors.push('admission end is not bounded by the run ticks')
  }
  const before = harness.slice(0, endIndex)
  const after = harness.slice(endIndex + 1)
  if (
    !Number.isInteger(ended.outstandingAtEnd) ||
    ended.outstandingAtEnd > manifest.cognition.globalOutstandingCeiling ||
    ended.forwardedAfterEnd !== ended.outstandingAtEnd ||
    ended.forwardedAtEnd !==
      before.filter((record) => record.event === 'cognitionForwarded').length + ended.forwardedAfterEnd
  ) {
    errors.push('admission end in-flight accounting is impossible')
  }
  const refusedKeys = new Set()
  for (const record of after) {
    if (record.event !== 'cognitionRefused') continue
    refusedKeys.add(record.requestKey)
    if (record.bound !== ADMISSION_CLOSED_BOUND || !typedRefusal(record)) {
      errors.push(`refusal ${record.requestKey} after the run ended is not a typed admission refusal`)
    }
    const chain = new Set(body.filter((entry) => entry.requestKey === record.requestKey).map((entry) => entry.event))
    if (!chain.has('perceived') || !chain.has('cognitionUnavailable') || chain.has('decided')) {
      errors.push(`refusal ${record.requestKey} after the run ended has no matching refused chain`)
    }
  }
  const recordedFailureKeys = recordedFailureKeysOf(harness)
  for (const record of body) {
    if (record.event === 'cognitionUnavailable' && !isCoalesced(record) && !isShutdownEnding(record) &&
        !isExposureEnding(record) && !refusedKeys.has(record.requestKey) &&
        !isRecordedFailureEnding(record, recordedFailureKeys)) {
      errors.push(`unavailable decision ${record.requestKey} has no typed refusal`)
    }
  }
  for (const record of harness) {
    if (record.event === 'providerUsage' && refusedKeys.has(record.requestKey)) {
      errors.push(`refused request ${record.requestKey} carries provider usage`)
    }
  }
  return [...new Set(errors)]
}

// Every claim a controlled stop makes must be re-derivable from the evidence it names.
function controlledStopErrors (manifest, metrics, records) {
  const errors = []
  const stop = metrics.controlledStop
  if (manifest.timing.stopReason !== CONTROLLED_STOP_REASON || stop === undefined) {
    return ['a controlled stop must carry both its stop reason and its admission closure']
  }
  if (!sameJson(manifest.controlledStop, stop)) {
    errors.push('manifest controlled stop does not match metrics.json')
  }
  const harness = records.filter((record) => record.source === 'harness')
  const body = records.filter((record) => record.source === 'body')
  const closureIndex = harness.findIndex((record) => record.event === ADMISSION_CLOSED_EVENT)
  if (stop.admissionClosures !== 1) errors.push('a controlled stop must close admission exactly once')
  if (!STOP_KINDS.includes(stop.kind)) errors.push('controlled stop names no closed stop kind')
  if (stop.behavioralVerdict !== 'no-go') errors.push('a controlled stop must state behavioral verdict no-go')
  const triggerKey = stop.trigger.requestKey
  const chainEvents = (key) => new Set(
    body.filter((record) => record.requestKey === key).map((record) => record.event)
  )
  const triggerChain = chainEvents(triggerKey)
  if (!triggerChain.has('perceived') || !triggerChain.has('cognitionUnavailable')) {
    errors.push('controlled stop names a request with no matching perception and refusal evidence')
  }
  const before = harness.slice(0, closureIndex)
  const after = harness.slice(closureIndex + 1)
  const beforeForwarded = before.filter((record) => record.event === 'cognitionForwarded')
  if (stop.kind === 'cognitionBudgetExhausted') {
    const refusal = before.findLast((record) => record.event === 'cognitionRefused')
    const ceilings = {
      perAgentCall: manifest.cognition.perAgentCallCeiling,
      globalCall: manifest.cognition.globalCallCeiling,
      globalOutstanding: manifest.cognition.globalOutstandingCeiling,
      spend: Math.round(manifest.cognition.maxExternalSpendUsd * NANO_USD_PER_USD)
    }
    const bound = stop.refusal?.bound
    if (
      refusal === undefined ||
      refusal.requestKey !== triggerKey ||
      !typedRefusal(refusal) ||
      refusal.bound !== bound ||
      refusal.ceiling !== stop.refusal.ceiling ||
      refusal.observed !== stop.refusal.observed
    ) {
      errors.push('controlled budget stop has no matching typed refusal')
    } else if (!BUDGET_STOP_BOUNDS.includes(bound) || stop.refusal.ceiling !== ceilings[bound]) {
      errors.push('controlled budget stop names a bound that does not match its configured ceiling')
    } else {
      // Forwards journalled after the closure were admitted before it.
      const agentCalls = harness.filter((record) =>
        record.event === 'cognitionForwarded' && record.agentId === stop.trigger.agentId
      ).length
      const worstCase = worstCaseManifestCallNanoUsd(manifest)
      const possible = {
        perAgentCall: stop.refusal.observed === stop.refusal.ceiling && agentCalls === stop.refusal.ceiling,
        globalCall: stop.refusal.observed === stop.refusal.ceiling && stop.forwardedAtClose === stop.refusal.ceiling,
        globalOutstanding:
          stop.refusal.observed === stop.refusal.ceiling &&
          stop.outstandingAtClose === stop.refusal.ceiling,
        spend:
          stop.refusal.observed === stop.forwardedAtClose * worstCase &&
          stop.refusal.observed + worstCase > stop.refusal.ceiling
      }[bound]
      if (!possible || stop.forwardedAtClose > manifest.cognition.globalCallCeiling) {
        errors.push('controlled budget stop states an impossible count')
      }
    }
    if (before.filter((record) => record.event === 'cognitionRefused').length !== 1) {
      errors.push('controlled budget stop must be the first refusal of the run')
    }
  } else if (stop.kind === 'providerUnavailable') {
    const forwarded = before.findLast((record) => record.event === 'cognitionForwarded')
    const classification = stop.providerClassification
    if (
      forwarded === undefined ||
      forwarded.requestKey !== triggerKey ||
      forwarded.outcome !== 'cognitionFailure' ||
      classification === null ||
      typeof classification !== 'object' ||
      !PROVIDER_UNAVAILABLE_MAPPINGS.includes(classification.mapping) ||
      [401, 403].includes(classification.httpStatus) ||
      ![manifest.provenance.models.ordinary, manifest.provenance.models.pivotal]
        .includes(classification.model)
    ) {
      errors.push('controlled provider stop has no matching classified provider unavailability')
    }
    if (before.some((record) => record.event === 'cognitionRefused')) {
      errors.push('controlled provider stop follows an earlier refusal')
    }
  }
  if (
    beforeForwarded.filter((record) => record.outcome !== 'decision' && !isRecordedFailureRequest(record)).length !==
    (stop.kind === 'providerUnavailable' ? 1 : 0)
  ) {
    errors.push('controlled stop evidence contains an unclassified cognition failure')
  }
  if (
    !Number.isInteger(stop.forwardedAtClose) ||
    !Number.isInteger(stop.outstandingAtClose) ||
    stop.outstandingAtClose > manifest.cognition.globalOutstandingCeiling ||
    stop.forwardedAfterClose !== stop.outstandingAtClose - (stop.kind === 'providerUnavailable' ? 1 : 0) ||
    stop.forwardedAtClose !== beforeForwarded.length + stop.forwardedAfterClose
  ) {
    errors.push('controlled stop in-flight accounting is impossible')
  }
  for (const record of after) {
    if (record.event === 'cognitionRefused' && record.bound !== ADMISSION_CLOSED_BOUND) {
      errors.push(`refusal ${record.requestKey} after the controlled stop is not an admission refusal`)
    }
  }
  const typedKeys = new Set([
    ...harness.filter((record) => record.event === 'cognitionRefused').map((record) => record.requestKey),
    ...(stop.kind === 'providerUnavailable'
      ? [triggerKey, ...after.filter(isProviderDrainFailure).map((record) => record.requestKey)]
      : [])
  ])
  for (const record of harness) {
    if (record.event === 'cognitionRefused') {
      if (!typedRefusal(record)) errors.push(`refusal ${record.requestKey} is untyped`)
      const chain = chainEvents(record.requestKey)
      if (!chain.has('perceived') || !chain.has('cognitionUnavailable')) {
        errors.push(`refusal ${record.requestKey} has no matching perception and unavailable record`)
      }
      if (chain.has('decided')) errors.push(`refused request ${record.requestKey} carries a decision`)
    }
    if (record.event === 'cognitionForwarded' && !chainEvents(record.requestKey).has('perceived')) {
      errors.push(`forwarded request ${record.requestKey} has no earlier perception chain`)
    }
  }
  const recordedFailureKeys = recordedFailureKeysOf(harness)
  for (const record of body) {
    if (record.event === 'cognitionUnavailable' && !isCoalesced(record) && !isShutdownEnding(record) &&
        !isExposureEnding(record) && !typedKeys.has(record.requestKey) &&
        !isRecordedFailureEnding(record, recordedFailureKeys)) {
      errors.push(`unavailable decision ${record.requestKey} has no typed refusal`)
    }
    if (
      record.event === 'decided' &&
      !harness.some((entry) =>
        entry.event === 'cognitionForwarded' &&
        entry.outcome === 'decision' &&
        entry.requestKey === record.requestKey
      )
    ) {
      errors.push(`decision ${record.requestKey} has no forwarded cognition request`)
    }
  }
  const refusedKeys = new Set(harness.filter((record) => record.event === 'cognitionRefused').map((record) => record.requestKey))
  for (const record of harness) {
    if (record.event === 'providerUsage' && refusedKeys.has(record.requestKey)) {
      errors.push(`refused request ${record.requestKey} carries provider usage`)
    }
  }
  if (stop.usage.providerCalls !== metrics.cognition.providerUsageCalls ||
    stop.usage.costNanoUsd !== metrics.cognition.externalSpendNanoUsd) {
    errors.push('controlled stop usage does not reconcile with provider usage evidence')
  }
  const lastTick = metrics.timing.firstTick + (stop.windowTicks ?? 0)
  if (
    stop.stopTick === null ||
    stop.windowTicks === null ||
    stop.windowTicks < 0 ||
    stop.stopTick !== lastTick ||
    stop.stopTick > metrics.timing.lastTick
  ) {
    errors.push('controlled stop window is not bounded by the run ticks')
  }
  return [...new Set(errors)]
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
  let derived
  try {
    derived = deriveMetrics(records, manifest.agents)
  } catch (cause) {
    errors.push(cause.message)
    return errors
  }
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
  if (Number.isInteger(manifest.cognition.pivotalCallCeiling)) {
    maximumSpendNanoUsd = maximumConfiguredSpendNanoUsd({ ...manifest.cognition, models: manifest.provenance.models })
  }
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
      // A borrowed call is urgent danger past the agent's own ceiling, counted against the global one.
      const ownCalls = perAgentCalls.get(record.agentId) ?? 0
      if (record.borrowedCall === true) {
        if (ownCalls < manifest.cognition.perAgentCallCeiling || (record.trigger !== 'damaged' && record.importance !== 'PIVOTAL')) {
          errors.push(`${record.requestKey} borrowed a global call that was not urgent danger past its own ceiling`)
        }
      } else {
        perAgentCalls.set(record.agentId, ownCalls + 1)
      }
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
  if (
    manifest.timing.stopReason === CONTROLLED_STOP_REASON &&
    primaryBehaviorModeOf(manifest.protocol.mode) === undefined
  ) {
    errors.push('only a primary behavior run may end in a controlled stop')
  }
  const drainAllowance =
    manifest.timing.stopReason === CONTROLLED_STOP_REASON ? CONTROLLED_STOP_DRAIN_MILLIS : 0
  if (manifest.timing.wallDurationMillis > manifest.bounds.runDurationSeconds * 1000 + 10_000 + drainAllowance) {
    errors.push('wall duration exceeded the run ceiling plus bounded shutdown allowance')
  }
  // A run that closed admission is measured to that closure, and the bounded drain after it
  // adds ticks that admitted no new work.
  const ticksPerSecond = manifest.protocol.serverTicksPerSecond ?? 20
  const ended = metrics.admissionEnd
  if (ended === undefined) {
    if (manifest.timing.tickSpan > manifest.bounds.simulationTickCeiling) {
      errors.push('simulation tick span exceeded its ceiling')
    }
  } else {
    // The game clock is asked every two seconds, so the ceiling is noticed within three.
    if (ended.windowTicks > manifest.bounds.simulationTickCeiling + 3 * ticksPerSecond) {
      errors.push('simulation tick span to the admission end exceeded its ceiling')
    }
    if (metrics.timing.lastTick - ended.endTick > Math.ceil(CONTROLLED_STOP_DRAIN_MILLIS / 1000) * ticksPerSecond) {
      errors.push('the drain after the admission end exceeded its bound')
    }
  }
  if (manifest.environment.world.worldBorderDiameterBlocks > 2 * manifest.environment.world.maxWorldSizeBlocks) {
    errors.push('world border diameter exceeds max world size')
  }
  if (primaryBehaviorModeOf(manifest.protocol.mode) !== undefined) {
    errors.push(...primaryBehaviorSemanticErrors(manifest, metrics, records))
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

/** The README's statement of the readiness gate: its verdict, rates and every non-success. */
function readinessLines (readiness) {
  const nonZero = (counts) => Object.fromEntries(Object.entries(counts).filter(([, count]) => count > 0))
  const blockers = nonZero(readiness.blockers)
  const lines = [
    `- Readiness: **${readiness.verdict}** (${readiness.version}); decision validity ${readiness.rates.decisionValidity ?? 'n/a'}, ` +
      `typed terminal accounting ${readiness.rates.typedTerminalAccounting ?? 'n/a'}, server truthfulness ${readiness.rates.serverTruthfulness ?? 'n/a'}.`,
    `- Actions by class: ${JSON.stringify(nonZero(readiness.actions.byClass))}; blockers: ${Object.keys(blockers).length === 0 ? 'none' : JSON.stringify(blockers)}; ` +
      `legitimate races: ${readiness.races}; cognition failures: ${JSON.stringify(readiness.cognitionFailures)}; server disputes: ${readiness.serverDisputes}.`,
    readiness.mechanicalVerdict === undefined
      ? `- Goal coherence: ${JSON.stringify(readiness.goalCoherence)}.`
      : `- Goal coherence: **${readiness.goalCoherence.verdict}**, part of the verdict (mechanically ${readiness.mechanicalVerdict}); ` +
        `${JSON.stringify(Object.fromEntries(Object.entries(readiness.goalCoherence).filter(([name]) => name !== 'verdict')))}.`
  ]
  if (readiness.reporting !== undefined) {
    const { callReserved, tierFallbacks, preemptedAnswers } = readiness.reporting
    lines.push(`- Calls the JVM decided before the mind: ${callReserved} refused to keep calls in reserve (\`callReserved\`), ` +
      `${tierFallbacks} sent at the ordinary tier past the pivotal ceiling (\`tierFallback\`), ` +
      `${preemptedAnswers} answered but preempted by newer damage (\`dangerPreempted\`).`)
  }
  for (const entry of readiness.actions.nonSuccess) {
    lines.push(`  - \`${entry.requestKey}\` ${entry.intent}: ${entry.class}${entry.race ? ' (race)' : ''}, ` +
      `${entry.evidence === null ? 'no terminal record' : `${entry.evidence.event} ${entry.evidence.outcome ?? ''} at tick ${entry.evidence.tick}`.replace(/  +/g, ' ')}.`)
  }
  return `${lines.join('\n')}\n`
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
  // Generations 1 and 2 predate harness publication: they name no metrics file for this side
  // to re-derive their evidence against, so the schema is the whole of what can be checked.
  if (schema.version < 3 && manifest.metrics === undefined) {
    return { ok: true, version: schema.version, errors: [] }
  }
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
  const finalDirectory = artifactDirectory
  if (fs.existsSync(finalDirectory)) {
    throw new Error(`artifact directory already exists: ${finalDirectory}`)
  }
  fs.mkdirSync(path.dirname(finalDirectory), { recursive: true })
  // Built and validated under a hidden sibling, then renamed whole, so a crash never
  // leaves a partial directory at the published name.
  const staging = fs.mkdtempSync(
    path.join(path.dirname(finalDirectory), `.publishing-${path.basename(finalDirectory)}-`)
  )
  artifactDirectory = path.join(staging, path.basename(finalDirectory))
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
      maxRecords: config.bounds.evidenceRecords,
      requirePerceptionAudit: isPrimaryBehaviorMode(config)
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
      observersJoined: observersWhoJoined(normalized.records),
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
      // A refused integrity check is its own stop, never a disk failure (v32 plains rehearsal 14
      // read as one, which pointed at the disk rather than at the evidence).
      throw new RunFailure(
        'publicationRefused',
        `published artifact is invalid:\n- ${validation.errors.join('\n- ')}`,
        'artifactIntegrityFailed'
      )
    }
    if (fs.existsSync(finalDirectory)) {
      throw new Error(`artifact directory already exists: ${finalDirectory}`)
    }
    fs.renameSync(artifactDirectory, finalDirectory)
    return { manifest, metrics: metricValue, validation }
  } finally {
    fs.rmSync(staging, { recursive: true, force: true })
  }
}

const POPULATION_WORDS = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten']

/** The README's question names the configured population, not the three-agent pilot's. */
function populationWord (count) {
  return POPULATION_WORDS[count] ?? String(count)
}

/** The README question for a configuration, naming its own population and profile. */
function readmeQuestion (config) {
  return isPrimaryBehaviorMode(config)
    ? `Can ${populationWord(config.agents.length)} independently embodied Loom agents complete the bounded ${config.cognition.prompts.routine} ${config.primaryBehavior.profile === 'pilot-180s' ? 'pilot' : `${config.primaryBehavior.profile} profile`} using only native event-driven cognition, while preserving complete per-kind perception and lifecycle evidence?`
    : 'Can three independently embodied Loom agents continue repeated perceive/decide/act cycles in one bounded world, while one body process fails, without corrupting peer evidence or leaving processes behind?'
}

function renderReadme (config, manifest, metrics) {
  const observationLines = metrics.findings.observations.length > 0
    ? metrics.findings.observations
    : ['No bounded social or spatial observation class was recorded.']
  const scripted = config.cognition.backend === 'scripted'
  const primaryBehavior = isPrimaryBehaviorMode(config)
  const questionQualification = scripted
    ? primaryBehavior
      ? 'This scripted run tests the research mode, native scheduling, evidence publication, and cleanup. It is not a model-behavior result.'
      : 'This scripted proof tests the harness and runtime composition. It does not test model-driven emergence.'
    : 'This live-model run observes independently routed agents without assigned roles, pairings, social goals, or a scripted outcome.'
  const interpretationQualification = scripted
    ? '- Scripted decisions are acceptance traffic. They are not evidence of cooperation, specialization, avoidance,\n  conflict, settlement, or any other emergent social outcome.'
    : '- Model-driven actions are observations, not proof of motive or emergence. Any social or spatial interpretation is limited to the recorded evidence and counterevidence.'
  const question = readmeQuestion(config)
  const protocolObservations = primaryBehavior
    ? `- No body process was deliberately crashed and no harness \`/loombody think\` command was issued.
- Native scheduled reviews by agent: ${JSON.stringify(metrics.primaryBehavior?.nativeGoalReviewsByAgent ?? {})}.
${metrics.witnessedDeaths === undefined
  ? '- No agent died.'
  : `- Server-witnessed deaths: ${JSON.stringify(metrics.witnessedDeaths.byAgent)}; surviving agents continued.`}
- Per-kind audit samples: ${metrics.perceptionAudit?.samples ?? 0}; complete: ${metrics.perceptionAudit?.complete ?? false}.
- Cleanup verification recorded: ${metrics.crashContainment.cleanupVerified}.`
    : `- ${metrics.crashContainment.controlledCrashes.length} body process was deliberately crashed; peer decisions after it:
  ${JSON.stringify(metrics.crashContainment.peerDecisionsAfterCrash)}.
- Cleanup verification recorded: ${metrics.crashContainment.cleanupVerified}.`
  const interpretation = primaryBehavior
    ? '- The run supports the narrower claim that the bounded fleet, native scheduler, cognition bridge, per-kind evidence chain,\n  publication, and cleanup worked together under the declared conditions.'
    : '- The run supports the narrower claim that the bounded fleet, cognition bridge, evidence chain, crash isolation,\n  publication, and cleanup worked together under the declared conditions.'
  const gate = metrics.behaviorGate
  const gateSection = gate === undefined
    ? ''
    : `
## Behavioral gate

- Mechanical pilot verdict: **${gate.pilotVerdict}** (${gate.version}).
- Criteria: ${gate.pilotCriteria === null
  ? 'not applicable outside the 180-second pilot profile'
  : Object.entries(gate.pilotCriteria).map(([name, passed]) => `${name} ${passed ? 'held' : 'failed'}`).join('; ')}.
- Meaningful behaviors by agent: ${JSON.stringify(gate.meaningfulBehaviorByAgent)}; typed goal continuity: ${JSON.stringify(gate.goalContinuityAgents)}.
- Non-idle goal-review actions: ${gate.goalReviewActions.nonIdle}, accepted ${gate.goalReviewActions.accepted}, confirmed ${gate.goalReviewActions.confirmed}.
- Calls by agent: ${JSON.stringify(gate.calls.forwardedByAgent)}; peak outstanding ${gate.calls.peakOutstanding ?? 'unrecorded'}.
- A pass still needs a reader to confirm: ${gate.manualCriteria.join('; ')}.
${gate.readiness === undefined ? '' : readinessLines(gate.readiness)}`
  const stop = metrics.controlledStop
  const stopSection = stop === undefined
    ? ''
    : `
## Controlled stop

- Stop: \`${stop.kind}\` for \`${stop.trigger.requestKey}\` (${stop.trigger.trigger ?? 'unattested'}, ${stop.trigger.origin ?? 'unattested'}).
${stop.refusal === undefined
  ? `- Provider classification: ${JSON.stringify(stop.providerClassification)}.`
  : `- Refusal: \`${stop.refusal.bound}\` at ${stop.refusal.observed} of ${stop.refusal.ceiling} ${stop.refusal.unit}; the call was not forwarded.`}
- Admission closed with ${stop.forwardedAtClose} call(s) forwarded and ${stop.outstandingAtClose} outstanding; ${stop.refusedAfterClose} later request(s) were refused.
- Stop tick ${stop.stopTick}, ${stop.windowTicks} ticks after the start.
- Native reviews: ${stop.nativeReviews.completed} completed of ${stop.nativeReviews.planned} planned; by agent ${JSON.stringify(stop.nativeReviews.byAgent)}.
- Chains: ${stop.chains.complete} complete decision chain(s), ${stop.chains.refused} typed refusal chain(s).
- Provider usage: ${stop.usage.providerCalls} call(s), ${stop.usage.totalTokens} tokens, $${stop.usage.costUsd.toFixed(9)}.
- Behavioral verdict: **${stop.behavioralVerdict}**. A stopped run is a partial record, not a pass.
`
  return `# ${config.title}

This directory is the sanitized, bounded publication from \`${config.experimentId}\`.
Every number below is generated from [evidence.jsonl](./evidence.jsonl) through the
committed research publisher; [metrics.json](./metrics.json) is the complete deterministic derivation.

## Question

${question}
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
${primaryBehavior
  ? `- Native goal reviews: ${manifest.protocol.nativeGoalReviewSchedule.expectedReviewsPerAgent} expected per agent,
  ${manifest.protocol.nativeGoalReviewSchedule.cadenceTicks}-tick cadence, ${manifest.protocol.nativeGoalReviewSchedule.sliceTicks}-tick slices.
- Initial positions: ${JSON.stringify(manifest.protocol.initialConditions.configuredPositions)}.
- Initial world clock: ${JSON.stringify(manifest.protocol.initialConditions.configuredWorldClock)}.
- Starting inventories were empty; no role, target, social goal, hierarchy, pairing, conflict, or scripted objective was injected.`
  : ''}

## Observations

${observationLines.map((line) => `- ${line}`).join('\n')}
- ${metrics.cycles.perceived} perceptions produced ${metrics.cycles.decided} decisions and ${metrics.cycles.applied} applied intents.
${protocolObservations}
${stopSection}${gateSection}
## Interpretations

${interpretation}
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
  readmeQuestion,
  semanticErrors,
  validateArtifact,
  validateProspectiveManifest,
  validateRunConfig,
  worstCaseCallNanoUsd,
  worstCaseTierNanoUsd
}
