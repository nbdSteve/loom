'use strict'

// A primary-behavior run that reaches a configured cognition bound, or whose provider
// becomes unavailable with a typed classification, stops admitting cognition and still
// publishes what it collected. Everything else stays an integrity failure.

const CONTROLLED_STOP_REASON = 'controlledStop'
const STOP_KINDS = Object.freeze(['cognitionBudgetExhausted', 'providerUnavailable'])
const BUDGET_STOP_BOUNDS = Object.freeze(['globalCall', 'globalOutstanding', 'perAgentCall', 'spend'])
const PROVIDER_UNAVAILABLE_MAPPINGS = Object.freeze([
  'providerConnection',
  'providerRateLimit',
  'providerResponseRead',
  'providerServer',
  'providerTimeout',
  'providerTransport'
])
const ADMISSION_CLOSED_BOUND = 'admissionClosed'
const ADMISSION_CLOSED_EVENT = 'cognitionAdmissionClosed'
// A normal ending closes admission too, so in-flight work can settle before shutdown.
const ADMISSION_ENDED_EVENT = 'cognitionAdmissionEnded'
// The proxy bounds every forwarded request at 20 s; the rest lets the body record it.
const PROXY_REQUEST_TIMEOUT_MILLIS = 20_000
const CONTROLLED_STOP_DRAIN_MILLIS = PROXY_REQUEST_TIMEOUT_MILLIS + 5_000
const CLASSIFICATION_FIELDS = Object.freeze([
  'httpStatus',
  'mapping',
  'model',
  'providerCode',
  'providerType',
  'responseShape'
])

function requestFields (request) {
  return {
    agentId: request.agentId,
    requestId: request.requestId,
    trigger: request.trigger ?? null,
    importance: request.importance ?? null,
    origin: request.origin ?? null
  }
}

function budgetStopFor (refusal) {
  if (!BUDGET_STOP_BOUNDS.includes(refusal?.bound)) return null
  return {
    kind: 'cognitionBudgetExhausted',
    ...requestFields(refusal),
    bound: refusal.bound,
    unit: refusal.unit,
    ceiling: refusal.ceiling,
    observed: refusal.observed
  }
}

function providerStopFor (request, classified) {
  if (
    classified === null ||
    classified === undefined ||
    !PROVIDER_UNAVAILABLE_MAPPINGS.includes(classified.mapping) ||
    classified.httpStatus === 401 ||
    classified.httpStatus === 403
  ) {
    return null
  }
  return {
    kind: 'providerUnavailable',
    ...requestFields(request),
    classification: Object.fromEntries(
      CLASSIFICATION_FIELDS.map((field) => [field, classified[field] ?? null])
    )
  }
}

// Scheduled reviews an agent must and may have attempted in a window of `windowTicks`
// after the start. Review k is due no earlier than k cadences, and no later than one
// stagger across the population plus one sweep after that.
function reviewWindowBounds (schedule, agentCount, windowTicks) {
  const stagger = (agentCount + 1) * schedule.sliceTicks
  let minimum = 0
  let maximum = 0
  for (let review = 0; review < schedule.expectedReviewsPerAgent; review += 1) {
    const earliest = review * schedule.cadenceTicks
    if (earliest <= windowTicks) maximum += 1
    if (earliest + stagger <= windowTicks) minimum += 1
  }
  return { minimum, maximum }
}

function sumUsage (records) {
  const usage = {
    providerCalls: 0,
    promptTokens: 0,
    cachedPromptTokens: 0,
    completionTokens: 0,
    totalTokens: 0,
    costNanoUsd: 0
  }
  for (const record of records) {
    usage.providerCalls += 1
    for (const field of ['promptTokens', 'cachedPromptTokens', 'completionTokens', 'totalTokens', 'costNanoUsd']) {
      usage[field] += record[field]
    }
  }
  usage.costUsd = Number((usage.costNanoUsd / 1_000_000_000).toFixed(9))
  return usage
}

function isNativeReview (record) {
  return record.trigger === 'goalReview' && record.origin === 'nativeGoalReviewSchedule'
}

// Whether `agentId`'s scheduled review was held when its window closed at `endTick`: a
// `goalReviewDeferred` record after its last perceived goal review, so the review was due and
// owed, never lost. Derived only when the evidence records held reviews at all, so evidence from
// before them derives exactly the metrics it always did.
function heldReviewOf (bodyRecords, agentId, endTick) {
  if (!bodyRecords.some((record) => record.event === 'goalReviewDeferred')) return {}
  const within = (record) => record.agentId === agentId && Number.isInteger(record.tick) &&
    (endTick === null || record.tick <= endTick)
  const lastOf = (event) => bodyRecords
    .filter((record) => within(record) && record.event === event &&
      (event !== 'perceived' || record.trigger === 'goalReview'))
    .reduce((latest, record) => Math.max(latest, record.tick), -Infinity)
  const deferred = lastOf('goalReviewDeferred')
  return { heldAtEnd: deferred > -Infinity && deferred > lastOf('perceived') ? 1 : 0 }
}

// Derived only when the evidence holds an admission closure, so evidence without one
// derives exactly the metrics it always did.
function controlledStopMetrics (bodyRecords, harnessRecords, firstTick) {
  const closures = harnessRecords.filter((record) => record.event === ADMISSION_CLOSED_EVENT)
  if (closures.length === 0) return undefined
  const closure = closures[0]
  const closedAt = harnessRecords.indexOf(closure)
  const triggerKey = `${closure.agentId}:${closure.requestId}`
  const triggerPerception = bodyRecords.find(
    (record) => record.event === 'perceived' && record.requestKey === triggerKey
  )
  const stopTick = triggerPerception?.tick ?? null
  const schedule = harnessRecords
    .find((record) => record.event === 'cognitionStimulusAttested')
    ?.nativeGoalReviewSchedule
  const agents = [...new Set(bodyRecords.map((record) => record.agentId))]
    .filter((agentId) => typeof agentId === 'string')
    .sort()
  const before = harnessRecords.slice(0, closedAt)
  const after = harnessRecords.slice(closedAt + 1)
  const windowTicks = stopTick === null ? null : stopTick - firstTick
  const reviewsByAgent = {}
  for (const agentId of agents) {
    const attempted = before.filter((record) =>
      ['cognitionForwarded', 'cognitionRefused'].includes(record.event) &&
      record.agentId === agentId &&
      isNativeReview(record)
    ).length
    const completed = harnessRecords.filter((record) =>
      record.event === 'cognitionForwarded' &&
      record.outcome === 'decision' &&
      record.agentId === agentId &&
      isNativeReview(record)
    ).length
    const bounds = schedule === undefined || windowTicks === null
      ? { minimum: null, maximum: null }
      : reviewWindowBounds(schedule, agents.length, windowTicks)
    reviewsByAgent[agentId] = { attempted, completed, ...bounds, ...heldReviewOf(bodyRecords, agentId, stopTick) }
  }
  const chains = new Map()
  for (const record of bodyRecords) {
    if (record.requestKey === undefined) continue
    const events = chains.get(record.requestKey) ?? new Set()
    events.add(record.event)
    chains.set(record.requestKey, events)
  }
  let completeChains = 0
  let refusedChains = 0
  for (const events of chains.values()) {
    if (!events.has('perceived')) continue
    if (events.has('decided') && (events.has('intentApplied') || events.has('decisionDiscarded'))) {
      completeChains += 1
    } else if (events.has('cognitionUnavailable')) {
      refusedChains += 1
    }
  }
  const refusedAfter = after.filter((record) => record.event === 'cognitionRefused')
  const stop = {
    reason: CONTROLLED_STOP_REASON,
    kind: closure.kind ?? null,
    trigger: {
      agentId: closure.agentId ?? null,
      requestKey: triggerKey,
      trigger: closure.trigger ?? null,
      importance: closure.importance ?? null,
      origin: closure.origin ?? null
    },
    ...(closure.kind === 'cognitionBudgetExhausted'
      ? {
          refusal: {
            bound: closure.bound ?? null,
            unit: closure.unit ?? null,
            ceiling: closure.ceiling ?? null,
            observed: closure.observed ?? null
          }
        }
      : {}),
    ...(closure.kind === 'providerUnavailable'
      ? { providerClassification: closure.classification ?? null }
      : {}),
    admissionClosures: closures.length,
    forwardedAtClose: closure.forwardedCalls ?? null,
    outstandingAtClose: closure.outstandingCalls ?? null,
    forwardedAfterClose: after.filter((record) => record.event === 'cognitionForwarded').length,
    refusedAfterClose: refusedAfter.length,
    stopTick,
    windowTicks,
    nativeReviews: {
      plannedPerAgent: schedule?.expectedReviewsPerAgent ?? null,
      planned: schedule === undefined ? null : schedule.expectedReviewsPerAgent * agents.length,
      attemptedBeforeStop: Object.values(reviewsByAgent).reduce((sum, entry) => sum + entry.attempted, 0),
      completed: Object.values(reviewsByAgent).reduce((sum, entry) => sum + entry.completed, 0),
      byAgent: reviewsByAgent
    },
    chains: {
      complete: completeChains,
      refused: refusedChains
    },
    usage: sumUsage(harnessRecords.filter((record) => record.event === 'providerUsage')),
    behavioralVerdict: 'no-go'
  }
  return stop
}

// Derived only when the evidence holds a normal-ending closure.
function admissionEndMetrics (bodyRecords, harnessRecords, firstTick) {
  const ends = harnessRecords.filter((record) => record.event === ADMISSION_ENDED_EVENT)
  if (ends.length === 0) return undefined
  const end = ends[0]
  const endedAt = harnessRecords.indexOf(end)
  const before = harnessRecords.slice(0, endedAt)
  const after = harnessRecords.slice(endedAt + 1)
  const schedule = harnessRecords
    .find((record) => record.event === 'cognitionStimulusAttested')
    ?.nativeGoalReviewSchedule
  const agents = [...new Set(bodyRecords.map((record) => record.agentId))]
    .filter((agentId) => typeof agentId === 'string')
    .sort()
  const endTick = Number.isInteger(end.endTick) ? end.endTick : null
  const windowTicks = endTick === null ? null : endTick - firstTick
  const byAgent = {}
  for (const agentId of agents) {
    const attempted = before.filter((record) =>
      ['cognitionForwarded', 'cognitionRefused'].includes(record.event) &&
      record.agentId === agentId &&
      isNativeReview(record)
    ).length
    const completed = harnessRecords.filter((record) =>
      record.event === 'cognitionForwarded' &&
      record.outcome === 'decision' &&
      record.agentId === agentId &&
      isNativeReview(record)
    ).length
    const bounds = schedule === undefined || windowTicks === null
      ? { minimum: null, maximum: null }
      : reviewWindowBounds(schedule, agents.length, windowTicks)
    byAgent[agentId] = { attempted, completed, ...bounds, ...heldReviewOf(bodyRecords, agentId, endTick) }
  }
  return {
    closures: ends.length,
    reason: end.reason ?? null,
    endTick,
    windowTicks,
    forwardedAtEnd: end.forwardedCalls ?? null,
    outstandingAtEnd: end.outstandingCalls ?? null,
    forwardedAfterEnd: after.filter((record) => record.event === 'cognitionForwarded').length,
    refusedAfterEnd: after.filter((record) => record.event === 'cognitionRefused').length,
    nativeReviews: { byAgent }
  }
}

module.exports = {
  ADMISSION_CLOSED_BOUND,
  ADMISSION_CLOSED_EVENT,
  ADMISSION_ENDED_EVENT,
  BUDGET_STOP_BOUNDS,
  CONTROLLED_STOP_DRAIN_MILLIS,
  CONTROLLED_STOP_REASON,
  PROVIDER_UNAVAILABLE_MAPPINGS,
  PROXY_REQUEST_TIMEOUT_MILLIS,
  STOP_KINDS,
  admissionEndMetrics,
  budgetStopFor,
  controlledStopMetrics,
  heldReviewOf,
  providerStopFor,
  reviewWindowBounds
}
