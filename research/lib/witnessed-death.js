'use strict'

const { heldReviewOf, reviewWindowBounds } = require('./controlled-stop.js')
const { isPreempted } = require('./danger.js')

// A server-witnessed death is an experiment outcome: that agent's chain ends and its
// peers continue. Only its own disconnect and discarded in-flight decision may follow.
const AFTER_DEATH_EVENTS = Object.freeze(['bodyDisconnected', 'decisionDiscarded'])
// The server may witness the killing blow, or a hit on a corpse, after it records the death;
// such a record says no cognition was asked for, and is evidence rather than activity.
const AFTER_DEATH_DAMAGE_OUTCOMES = Object.freeze(['damageDiedBeforeCognition', 'damageAgentDead'])

// Permadeath is absolute: after a death nothing new may begin. The one exception is the typed
// terminal settlement of a request the agent provably decided before it died: its single
// intentApplied or decisionDiscarded, and at most one movement and one retreat report per
// such request, because a flight in progress reports how it ended. A server effect first
// recorded after the death (worldInteraction), a new perception, decision or cognition, and
// any record for a request not pending at the death stay fatal.
//
// A perception the agent took before it died whose answer came after is pending too, and may
// end once with the JVM's decisionDiscarded or cognitionUnavailable and nothing else: the JVM
// records no decision for a dead agent. In r21 gamma's damaged perception at tick 6683 was
// answered 2.3 s later, after the death, and its decided record stopped the run.
const SETTLEMENT_REPORTS = Object.freeze(['moved', 'retreating'])

function allowedAfterDeath (record) {
  return AFTER_DEATH_EVENTS.includes(record.event) ||
    (record.event === 'damageWitnessed' && AFTER_DEATH_DAMAGE_OUTCOMES.includes(record.outcome))
}

class DeathSettlement {
  constructor () {
    this.pending = new Map()
    this.perceived = new Map()
    this.reportsLeft = new Map()
    this.settled = new Map()
  }

  pendingOf (agentId) {
    if (!this.pending.has(agentId)) this.pending.set(agentId, new Set())
    return this.pending.get(agentId)
  }

  perceivedOf (agentId) {
    if (!this.perceived.has(agentId)) this.perceived.set(agentId, new Set())
    return this.perceived.get(agentId)
  }

  /** Answers why a record is inadmissible after its agent's death, or null. */
  observe (record, dead) {
    const agentId = record.agentId
    const key = `${agentId}:${record.requestId}`
    const pending = this.pendingOf(agentId)
    const perceived = this.perceivedOf(agentId)
    if (!dead) {
      if (record.event === 'perceived') perceived.add(key)
      if (record.event === 'decided') {
        perceived.delete(key)
        pending.add(key)
      }
      if (['intentApplied', 'decisionDiscarded', 'cognitionUnavailable'].includes(record.event)) perceived.delete(key)
      if (record.event === 'intentApplied' || record.event === 'decisionDiscarded') pending.delete(key)
      if (record.event === 'died') {
        this.reportsLeft.set(agentId, Object.fromEntries(SETTLEMENT_REPORTS.map((event) => [event, pending.size])))
      }
      return null
    }
    if (allowedAfterDeath(record) && record.event !== 'decisionDiscarded') return null
    if (['intentApplied', 'decisionDiscarded'].includes(record.event) && pending.has(key)) {
      pending.delete(key)
      if (!this.settled.has(agentId)) this.settled.set(agentId, [])
      this.settled.get(agentId).push(key)
      return null
    }
    if (['decisionDiscarded', 'cognitionUnavailable'].includes(record.event) && perceived.has(key)) {
      perceived.delete(key)
      if (!this.settled.has(agentId)) this.settled.set(agentId, [])
      this.settled.get(agentId).push(key)
      return null
    }
    const left = this.reportsLeft.get(agentId)
    if (SETTLEMENT_REPORTS.includes(record.event) && left?.[record.event] > 0) {
      left[record.event] -= 1
      return null
    }
    return `${agentId} recorded ${record.event} after its witnessed death`
  }

  /** Request keys settled after their agent's death, by agent. */
  settledAfterDeath (agentId) {
    return [...(this.settled.get(agentId) ?? [])].sort()
  }
}

function deathTicks (bodyRecords) {
  const ticks = new Map()
  for (const record of bodyRecords) {
    if (record.event === 'died' && !ticks.has(record.agentId)) ticks.set(record.agentId, record.tick)
  }
  return ticks
}

// Returns the first reason a body record breaks the death rule, or null.
function deathViolation (bodyRecords, settlement = new DeathSettlement()) {
  const dead = new Map()
  const disconnects = new Set()
  for (const record of bodyRecords) {
    const agentId = record.agentId
    if (!dead.has(agentId)) {
      settlement.observe(record, false)
      if (record.event === 'died') {
        dead.set(agentId, record.tick)
      } else if (AFTER_DEATH_EVENTS.includes(record.event) && !isPreempted(record)) {
        // A danger answer newer damage preempted is a living agent's superseded answer, and
        // needs no death (v32 plains rehearsal 23: kappa's).
        return `${agentId} recorded ${record.event} without a witnessed death`
      }
      continue
    }
    if (record.event === 'died') return `${agentId} died twice`
    const refused = settlement.observe(record, true)
    if (refused !== null) return refused
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
  const settlement = new DeathSettlement()
  const violation = deathViolation(bodyRecords, settlement)
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
      settledAfterDeath: settlement.settledAfterDeath(agentId),
      attempted: reviews.length,
      completed: reviews.filter((record) => record.event === 'cognitionForwarded' && record.outcome === 'decision').length,
      ...bounds,
      ...heldReviewOf(bodyRecords, agentId, tick)
    }
  }
  return {
    deaths: ticks.size,
    byAgent,
    violation
  }
}

module.exports = {
  AFTER_DEATH_DAMAGE_OUTCOMES,
  DeathSettlement,
  AFTER_DEATH_EVENTS,
  allowedAfterDeath,
  deathMetrics,
  deathTicks,
  deathViolation
}
