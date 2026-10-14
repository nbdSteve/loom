'use strict'

// Mechanical measurements behind the pre-registered primary-behavior gates. Criteria
// that need a reader (an evidence-grounded aim, no action justified only by being
// active, no cross-agent goal leakage) are named, never scored here.

const GATE_VERSION = 'loom-primary-behavior-gate/1'
const PASSIVE_INTENTS = Object.freeze(['idle', 'rest'])
const MOVEMENT_INTENTS = Object.freeze(['moveTo', 'flee'])
const NIGHT_START = 13_000
const NIGHT_END = 23_000
const MANUAL_CRITERIA = Object.freeze([
  'each counted meaningful behavior has an evidence-grounded aim',
  'no counted action is justified only by being active',
  'no cross-agent goal leakage in the typed fields'
])

function isNativeReviewRecord (record) {
  return record.trigger === 'goalReview' && record.origin === 'nativeGoalReviewSchedule'
}

// A goal-review action counts as confirmed only by later server or body evidence that
// it changed something: its own server-confirmed interaction, a movement report before
// that agent's next decision, or a food change before that decision for an eat.
function confirmationOf (decision, applied, bodyRecords, nextDecisionIndex) {
  const window = bodyRecords.slice(bodyRecords.indexOf(applied) + 1, nextDecisionIndex)
  if (
    window.some((record) =>
      record.event === 'worldInteraction' &&
      record.requestKey === decision.requestKey &&
      record.outcome === 'serverEffectConfirmed'
    )
  ) return 'serverEffect'
  if (
    MOVEMENT_INTENTS.includes(decision.intent) &&
    window.some((record) => record.event === 'moved' && record.agentId === decision.agentId)
  ) return 'movement'
  if (
    decision.intent === 'eat' &&
    window.some((record) => record.event === 'energyChanged' && record.agentId === decision.agentId)
  ) return 'vitals'
  return null
}

function behaviorGateMetrics ({ bodyRecords, harnessRecords, metrics }) {
  const schedule = harnessRecords
    .find((record) => record.event === 'cognitionStimulusAttested')
    ?.nativeGoalReviewSchedule
  if (schedule === undefined) return undefined
  const nativeReviewKeys = new Set(
    harnessRecords
      .filter((record) => record.event === 'cognitionForwarded' && isNativeReviewRecord(record))
      .map((record) => record.requestKey)
  )
  const nextDecision = new Map()
  const decisions = []
  bodyRecords.forEach((record, index) => {
    if (record.event !== 'decided') return
    const previous = nextDecision.get(record.agentId)
    if (previous !== undefined) previous.next = index
    const entry = { record, index, next: bodyRecords.length }
    nextDecision.set(record.agentId, entry)
    decisions.push(entry)
  })
  const actions = { nonIdle: 0, accepted: 0, confirmed: 0, byConfirmation: {} }
  const meaningful = {}
  for (const { record, next } of decisions) {
    if (!nativeReviewKeys.has(record.requestKey) || PASSIVE_INTENTS.includes(record.intent)) continue
    actions.nonIdle += 1
    const applied = bodyRecords.find((entry) =>
      entry.event === 'intentApplied' && entry.requestKey === record.requestKey
    )
    if (applied?.outcome !== 'accepted') continue
    actions.accepted += 1
    const confirmation = confirmationOf(record, applied, bodyRecords, next)
    if (confirmation === null) continue
    actions.confirmed += 1
    actions.byConfirmation[confirmation] = (actions.byConfirmation[confirmation] ?? 0) + 1
    meaningful[record.agentId] = (meaningful[record.agentId] ?? 0) + 1
  }
  actions.acceptedAndConfirmedRate = actions.nonIdle === 0
    ? null
    : Number((actions.confirmed / actions.nonIdle).toFixed(6))

  const omissions = {}
  for (const [kind, counts] of Object.entries(metrics.perceptionAudit?.byKind ?? {})) {
    omissions[kind] = {
      bodyReportedOmittedCount: counts.bodyReportedOmittedCount,
      rawBodyListCount: counts.rawBodyListCount,
      jvmCutOmittedCount: counts.jvmCutOmittedCount,
      bodyReportedOmissionShare: counts.rawBodyListCount + counts.bodyReportedOmittedCount === 0
        ? 0
        : Number((counts.bodyReportedOmittedCount /
          (counts.rawBodyListCount + counts.bodyReportedOmittedCount)).toFixed(6))
    }
  }
  const nightPerceptionsByAgent = {}
  for (const record of bodyRecords) {
    const clock = record.perceptionAudit?.worldClock
    if (record.event !== 'perceived' || clock === undefined) continue
    if (clock.timeOfDay < NIGHT_START || clock.timeOfDay >= NIGHT_END) continue
    const days = nightPerceptionsByAgent[record.agentId] ?? {}
    days[String(clock.day)] = (days[String(clock.day)] ?? 0) + 1
    nightPerceptionsByAgent[record.agentId] = days
  }
  const forwarded = harnessRecords.filter((record) => record.event === 'cognitionForwarded')
  const callsByAgent = {}
  for (const record of forwarded) callsByAgent[record.agentId] = (callsByAgent[record.agentId] ?? 0) + 1
  const admitted = forwarded
    .map((record) => record.outstandingAtAdmission)
    .filter(Number.isInteger)
  const tickSpan = metrics.timing.tickSpan
  const goalAgents = metrics.goalContinuity?.agentsWithContinuity ?? []
  const reviews = metrics.primaryBehavior?.nativeGoalReviewsByAgent ?? {}
  const agents = metrics.records.agents.map((agent) => agent.agentId)
  const jvmCut = Object.values(omissions).reduce((sum, kind) => sum + kind.jvmCutOmittedCount, 0)
  // Requests refused after a normal ending were never owed an answer.
  const afterEnd = metrics.admissionEnd?.refusedAfterEnd ?? 0
  const pilot = {
    meaningfulBehaviorAgents: Object.keys(meaningful).length >= 2,
    typedGoalContinuity: goalAgents.length >= 1,
    completeNativeReviews:
      agents.length > 0 &&
      agents.every((agentId) => reviews[agentId] === schedule.expectedReviewsPerAgent),
    noFailureEvidence:
      metrics.controlledStop === undefined &&
      metrics.cycles.incomplete.length === 0 &&
      metrics.cycles.unavailable === afterEnd &&
      metrics.cycles.discarded === 0 &&
      metrics.cognition.refusedCalls === afterEnd &&
      metrics.behavior.deaths === 0 &&
      metrics.behavior.disconnects === 0 &&
      metrics.behavior.witnessDisputeRecords === 0 &&
      metrics.crashContainment.cleanupVerified === true,
    noJvmCut: jvmCut === 0
  }
  return {
    version: GATE_VERSION,
    meaningfulBehaviorByAgent: Object.fromEntries(Object.entries(meaningful).sort()),
    goalContinuityAgents: goalAgents,
    goalReviewActions: actions,
    omissionsByKind: omissions,
    nightPerceptionsByAgent: Object.fromEntries(Object.entries(nightPerceptionsByAgent).sort()),
    calls: {
      forwardedByAgent: Object.fromEntries(Object.entries(callsByAgent).sort()),
      forwardedPerThousandTicks: tickSpan === 0 ? null : Number((forwarded.length * 1000 / tickSpan).toFixed(6)),
      peakOutstanding: admitted.length === forwarded.length && admitted.length > 0
        ? admitted.reduce((peak, value) => Math.max(peak, value), 0)
        : null
    },
    externalSpendNanoUsd: metrics.cognition.externalSpendNanoUsd,
    cleanupVerified: metrics.crashContainment.cleanupVerified,
    pilotCriteria: pilot,
    pilotVerdict: Object.values(pilot).every(Boolean) ? 'pass' : 'no-go',
    manualCriteria: MANUAL_CRITERIA
  }
}

module.exports = {
  GATE_VERSION,
  MANUAL_CRITERIA,
  behaviorGateMetrics
}
