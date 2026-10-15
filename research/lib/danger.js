'use strict'

// The JVM records a threat or damage perception it coalesced into the agent's active danger
// episode as cognitionUnavailable with this outcome: the mind was deliberately not asked.
const DANGER_COALESCED = 'dangerCoalesced'

function isCoalesced (record) {
  return record.event === 'cognitionUnavailable' && record.outcome === DANGER_COALESCED
}

// A perception still waiting for the mind when the plugin shut down after the run ended.
const COGNITION_SHUTDOWN = 'cognitionShutdown'

function isShutdownEnding (record) {
  return record.event === 'cognitionUnavailable' && record.outcome === COGNITION_SHUTDOWN
}

/**
 * Whether every unanswered perception is accounted for: coalesced into a danger episode,
 * ended by the shutdown after an admission end, or refused by an admission refusal.
 */
function unansweredAccountedFor (bodyRecords, { admissionEnded, refusedKeys }) {
  return bodyRecords
    .filter((record) => record.event === 'cognitionUnavailable')
    .every((record) =>
      isCoalesced(record) ||
      (admissionEnded && isShutdownEnding(record)) ||
      refusedKeys.has(record.requestKey))
}

// Derived only when the evidence holds a coalesced perception.
function dangerEpisodeMetrics (bodyRecords) {
  const coalesced = bodyRecords.filter(isCoalesced)
  if (coalesced.length === 0) return undefined
  const byAgent = {}
  const byTrigger = {}
  for (const record of coalesced) {
    byAgent[record.agentId] = (byAgent[record.agentId] ?? 0) + 1
    byTrigger[record.trigger ?? 'unattested'] = (byTrigger[record.trigger ?? 'unattested'] ?? 0) + 1
  }
  return {
    coalesced: coalesced.length,
    byAgent: Object.fromEntries(Object.entries(byAgent).sort()),
    byTrigger: Object.fromEntries(Object.entries(byTrigger).sort())
  }
}

module.exports = {
  COGNITION_SHUTDOWN,
  DANGER_COALESCED,
  dangerEpisodeMetrics,
  isCoalesced,
  isShutdownEnding,
  unansweredAccountedFor
}
