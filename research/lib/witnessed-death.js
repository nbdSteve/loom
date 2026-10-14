'use strict'

const { reviewWindowBounds } = require('./controlled-stop.js')

// A server-witnessed death is an experiment outcome: that agent's chain ends and its
// peers continue. Only its own disconnect and discarded in-flight decision may follow.
const AFTER_DEATH_EVENTS = Object.freeze(['bodyDisconnected', 'decisionDiscarded'])

function deathTicks (bodyRecords) {
  const ticks = new Map()
  for (const record of bodyRecords) {
    if (record.event === 'died' && !ticks.has(record.agentId)) ticks.set(record.agentId, record.tick)
  }
  return ticks
}

// Returns the first reason a body record breaks the death rule, or null.
function deathViolation (bodyRecords) {
  const dead = new Map()
  const disconnects = new Set()
  for (const record of bodyRecords) {
    const agentId = record.agentId
    if (!dead.has(agentId)) {
      if (record.event === 'died') {
        dead.set(agentId, record.tick)
      } else if (AFTER_DEATH_EVENTS.includes(record.event)) {
        return `${agentId} recorded ${record.event} without a witnessed death`
      }
      continue
    }
    if (record.event === 'died') return `${agentId} died twice`
    if (!AFTER_DEATH_EVENTS.includes(record.event)) {
      return `${agentId} recorded ${record.event} after its witnessed death`
    }
    if (record.event === 'bodyDisconnected') {
      if (disconnects.has(agentId)) return `${agentId} disconnected twice after its death`
      disconnects.add(agentId)
    }
  }
  return null
}

function isNativeReview (record) {
  return record.trigger === 'goalReview' && record.origin === 'nativeGoalReviewSchedule'
}

// Derived only when the evidence holds a death, so evidence without one derives exactly
// the metrics it always did.
function deathMetrics (bodyRecords, harnessRecords, firstTick) {
  const ticks = deathTicks(bodyRecords)
  if (ticks.size === 0) return undefined
  const schedule = harnessRecords
    .find((record) => record.event === 'cognitionStimulusAttested')
    ?.nativeGoalReviewSchedule
  const agents = new Set(bodyRecords.map((record) => record.agentId))
  const byAgent = {}
  for (const [agentId, tick] of [...ticks].sort(([first], [second]) => first.localeCompare(second))) {
    const reviews = harnessRecords.filter((record) =>
      ['cognitionForwarded', 'cognitionRefused'].includes(record.event) &&
      record.agentId === agentId &&
      isNativeReview(record)
    )
    const lifeTicks = tick - firstTick
    const bounds = schedule === undefined
      ? { minimum: null, maximum: null }
      : reviewWindowBounds(schedule, agents.size, lifeTicks)
    byAgent[agentId] = {
      tick,
      lifeTicks,
      attempted: reviews.length,
      completed: reviews.filter((record) => record.event === 'cognitionForwarded' && record.outcome === 'decision').length,
      ...bounds
    }
  }
  return {
    deaths: ticks.size,
    byAgent,
    violation: deathViolation(bodyRecords)
  }
}

module.exports = {
  AFTER_DEATH_EVENTS,
  deathMetrics,
  deathTicks,
  deathViolation
}
