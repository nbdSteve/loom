'use strict'

// The JVM records a threat or damage perception it coalesced into the agent's active danger
// episode as cognitionUnavailable with this outcome: the mind was deliberately not asked.
const DANGER_COALESCED = 'dangerCoalesced'
// A perception the JVM coalesced because the agent already had cognition in flight for the
// same kind of trigger - one review and one event at a time. Also deliberately not asked.
const COGNITION_COALESCED = 'cognitionCoalesced'
// A perception the JVM did not send because the agent's remaining calls were kept for its
// scheduled reviews (`CallReserve`). Also deliberately not asked.
const CALL_RESERVED = 'callReserved'

// A danger decision the JVM discarded unapplied because newer damage preempted it while it was
// with the mind: answered and billed, and superseded by the newer answer.
const DANGER_PREEMPTED = 'dangerPreempted'

function isPreempted (record) {
  return record.event === 'decisionDiscarded' && record.outcome === DANGER_PREEMPTED
}

function isCoalesced (record) {
  return record.event === 'cognitionUnavailable' &&
    [DANGER_COALESCED, COGNITION_COALESCED, CALL_RESERVED].includes(record.outcome)
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

// Derived only when the evidence holds a perception coalesced into a danger episode.
function dangerEpisodeMetrics (bodyRecords) {
  const coalesced = bodyRecords.filter((record) =>
    record.event === 'cognitionUnavailable' && record.outcome === DANGER_COALESCED)
  if (coalesced.length === 0) return undefined
  const byAgent = {}
  const byTrigger = {}
  for (const record of coalesced) {
    byAgent[record.agentId] = (byAgent[record.agentId] ?? 0) + 1
    byTrigger[record.trigger ?? 'unattested'] = (byTrigger[record.trigger ?? 'unattested'] ?? 0) + 1
  }
  // Stated only when newer damage preempted an answer, so earlier evidence re-derives unchanged.
  const preempted = bodyRecords.filter(isPreempted).length
  return {
    coalesced: coalesced.length,
    byAgent: Object.fromEntries(Object.entries(byAgent).sort()),
    byTrigger: Object.fromEntries(Object.entries(byTrigger).sort()),
    ...(preempted === 0 ? {} : { preempted })
  }
}

// Derived only when the evidence holds a perception coalesced into cognition in flight.
function cognitionCoalescingMetrics (bodyRecords) {
  const coalesced = bodyRecords.filter((record) =>
    record.event === 'cognitionUnavailable' && record.outcome === COGNITION_COALESCED)
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
  COGNITION_COALESCED,
  COGNITION_SHUTDOWN,
  cognitionCoalescingMetrics,
  DANGER_COALESCED,
  DANGER_PREEMPTED,
  isPreempted,
  dangerEpisodeMetrics,
  CALL_RESERVED,
  isCoalesced,
  isShutdownEnding,
  unansweredAccountedFor
}
