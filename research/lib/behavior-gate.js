'use strict'

const { unansweredAccountedFor } = require('./danger.js')
const { PROFILES } = require('./profiles.js')
const { readinessMetrics } = require('./readiness.js')

// Mechanical measurements behind the pre-registered primary-behavior gates. Criteria
// that need a reader (an evidence-grounded aim, no action justified only by being
// active, no cross-agent goal leakage) are named, never scored here.

// Committed evidence derives the gate generation it was published under: the stimulus
// attestation names it, and evidence that names none is generation 1.
// Generation 3 adds the readiness gate: every decision valid, every action's terminal typed,
// and every server-checked claim true, with each non-success listed by its exact record.
// Generation 4 makes goal coherence part of that verdict, measured from the typed goal.
// Generation 5 credits a step only on its evidence and refuses unobtainable success items.
// Generation 6 counts a review action as meaningful only when it is a step of a goal, counts the
// follow-up reviews a settled step owes, and refuses goals already met or pursued by idling.
// Generation 7 refuses an answer that ignores its held goal, a step that cannot give the goal's
// item and an answer past the stall bound, and reads a body's noRoute as the ground's answer.
// Generation 8 refuses a step that leaves the agent's own drop of its goal's item lying, counts a
// replaced goal's action as a step of its new goal, and credits a walk only onto a goal source.
// Generation 9 judges flights, refuses abandoning an achievable goal, and holds coherence to the
// profile's pre-registered positive bars.
// Generation 10 judges a flight by its threat rather than its gain, refuses a revise that changes
// the goal's item, and measures pursuit per agent, over time, by item kind and by craft.
// Generation 11 refuses a revise that restates its total and a plain completion beside a next
// goal's source, classes a noRoute to an agent's own fresh drop as a blocker rather than terrain,
// and measures substantive pursuit: progressing held time, goals sight did not guarantee or a
// later goal built on, chained goals, exploration walks and danger met, with the counters the
// schema guarantees reported apart from behavioral evidence.
// Generation 12 holds goals to substance a one-step goal cannot fake, counts chains only past
// their adopting craft, ends held time at death, counts danger in answered episodes, and classes a
// no-route flight from the agent's own dug hole as the selfTrapped blocker.
// Generation 13 holds goals to substance through resources sight did not guarantee, lets horizon
// goals ask past sight when grounded, and refuses a goal that crowds out offered exploration.
// Generation 14 measures progressing time from a goal's first advance, sight's guarantee at the
// calibrated mean and through recipes, counts a chained goal one craft met in full, counts
// discovery goals apart, and reports danger, exploration and item kinds as description only.
// Generation 15 counts progressing time only after accepted actions, a chained goal met by one
// confirmed craft whether or not a review followed, dropped items as local capacity, an attack
// refused at the health floor reached while deciding as a race, and names its readiness version
// after its own generation.
// Generation 16 counts no flight as progressing time, a reach refusal the agent's own movement
// caused after its perception as a movement race, a no-route from a body in water as the
// waterTrapped blocker, and a dig refused for flooding the body as inapplicable.
// Generation 17 never counts a survival deferral as ignoring a met goal, sees each preempted danger
// answer as a superseded action, classes a flight refused behind the agent's own flight as the
// dangerOverlap blocker, and reports the JVM's callReserved refusals, tier fallbacks and preempted answers.
// Generation 18 types the work that drained after the run's exposure ended and counts an older
// flight a newer danger answer was applied after as the superseded retreatStale.
// Generation 19 counts a shelter goal apart from item goals, credits its covered steps, refuses a
// shelter or subgoal set where none was offered or a subgoal replaced, and measures the run's
// shelter from the server's sealed record against the profile's pre-registered shelter bar.
// Generation 20 refuses an objective one step meets, a replace by the held goal's own item and an
// end stated without a supported reason as goal incoherence, classes any action refused behind the
// agent's own earlier running request as the dangerOverlap blocker and one behind its own newer
// request as superseded, and a placement blocked at a closing its cover offered as a target race.
const GATE_GENERATION = 20
// The goal outcomes of a decision whose action was a step of a goal it held or took up.
const GOAL_GROUNDED_OUTCOMES = Object.freeze(['goalAdopted', 'goalContinued', 'goalRevised', 'goalReplaced', 'goalCompletedAdopted'])
const GATE_VERSION = `loom-primary-behavior-gate/${GATE_GENERATION}`
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

// The follow-up review a settled goal step owes, from generation 6.
function isFollowUpReviewRecord (record) {
  return record.trigger === 'stepSettled' && record.origin === 'nativeGoalFollowUp'
}

// A goal-review action counts as confirmed only by server or body evidence, between its
// decision and that agent's next one, that it changed something: its own
// server-confirmed interaction, a movement report, or a food change for an eat. The
// body may record that evidence before or after the intentApplied record.
function confirmationOf (decision, decisionIndex, bodyRecords, nextDecisionIndex) {
  const window = bodyRecords.slice(decisionIndex + 1, nextDecisionIndex)
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
  const stimulus = harnessRecords.find((record) => record.event === 'cognitionStimulusAttested')
  const schedule = stimulus?.nativeGoalReviewSchedule
  if (schedule === undefined) return undefined
  const generation = Number.isInteger(stimulus.behaviorGateGeneration) ? stimulus.behaviorGateGeneration : 1
  const pilotProfile = stimulus.profile === undefined || stimulus.profile === 'pilot-180s'
  const grounded = generation >= 6
  const nativeReviewKeys = new Set(
    harnessRecords
      .filter((record) => record.event === 'cognitionForwarded' &&
        (isNativeReviewRecord(record) || (grounded && isFollowUpReviewRecord(record))))
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
  // A request settled after its agent's death is permadeath's typed exception, not behavior.
  const settledAfterDeath = new Set(
    Object.values(metrics.witnessedDeaths?.byAgent ?? {}).flatMap((entry) => entry.settledAfterDeath ?? [])
  )
  const actions = { nonIdle: 0, accepted: 0, confirmed: 0, byConfirmation: {} }
  const meaningful = {}
  for (const { record, index, next } of decisions) {
    if (!nativeReviewKeys.has(record.requestKey) || PASSIVE_INTENTS.includes(record.intent)) continue
    if (settledAfterDeath.has(record.requestKey)) continue
    actions.nonIdle += 1
    const applied = bodyRecords.find((entry) =>
      entry.event === 'intentApplied' && entry.requestKey === record.requestKey
    )
    if (applied?.outcome !== 'accepted') continue
    actions.accepted += 1
    const confirmation = confirmationOf(record, index, bodyRecords, next)
    if (confirmation === null) continue
    actions.confirmed += 1
    actions.byConfirmation[confirmation] = (actions.byConfirmation[confirmation] ?? 0) + 1
    // An action justified only by being active is not meaningful: from generation 6 it must be a
    // step of a goal (r14: walks with no goal and a dig of a block that drops nothing counted).
    if (grounded && !GOAL_GROUNDED_OUTCOMES.includes(record.goal?.outcome)) continue
    meaningful[record.agentId] = (meaningful[record.agentId] ?? 0) + 1
  }
  actions.acceptedAndConfirmedRate = actions.nonIdle === 0
    ? null
    : Number((actions.confirmed / actions.nonIdle).toFixed(6))

  // Server-witnessed native interactions by operation, so a claim about one operation
  // can be checked against the server's verdict for that operation alone.
  const intentByRequest = new Map(
    bodyRecords.filter((record) => record.event === 'decided').map((record) => [record.requestKey, record.intent])
  )
  const interactionsByOperation = {}
  for (const record of bodyRecords) {
    if (record.event !== 'worldInteraction') continue
    const operation = intentByRequest.get(record.requestKey) ?? 'unattributed'
    const entry = interactionsByOperation[operation] ?? { confirmed: 0, disputed: 0 }
    if (record.outcome === 'serverEffectConfirmed') entry.confirmed += 1
    else entry.disputed += 1
    interactionsByOperation[operation] = entry
  }
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
      unansweredAccountedFor(bodyRecords, {
        admissionEnded: metrics.admissionEnd !== undefined,
        refusedKeys: new Set(harnessRecords.filter((record) => record.event === 'cognitionRefused').map((record) => record.requestKey))
      }) &&
      metrics.cycles.discarded === 0 &&
      metrics.cognition.refusedCalls === afterEnd &&
      metrics.behavior.deaths === 0 &&
      metrics.behavior.disconnects === 0 &&
      metrics.behavior.witnessDisputeRecords === 0 &&
      metrics.crashContainment.cleanupVerified === true,
    noJvmCut: jvmCut === 0
  }
  return {
    version: `loom-primary-behavior-gate/${generation}`,
    meaningfulBehaviorByAgent: Object.fromEntries(Object.entries(meaningful).sort()),
    goalContinuityAgents: goalAgents,
    goalReviewActions: actions,
    ...(generation >= 2
      ? { interactionsByOperation: Object.fromEntries(Object.entries(interactionsByOperation).sort()) }
      : {}),
    ...(generation >= 3
      ? {
          readiness: readinessMetrics({
            bodyRecords,
            harnessRecords,
            metrics,
            generation,
            bars: PROFILES[stimulus.profile]?.coherenceBars
          })
        }
      : {}),
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
    // The pilot criteria belong to the 180-second pilot; a longer profile's own gate is
    // judged against its pre-registered thresholds instead.
    ...(pilotProfile
      ? { pilotCriteria: pilot, pilotVerdict: Object.values(pilot).every(Boolean) ? 'pass' : 'no-go' }
      : { pilotCriteria: null, pilotVerdict: 'notApplicable' }),
    manualCriteria: MANUAL_CRITERIA
  }
}

module.exports = {
  GATE_GENERATION,
  GATE_VERSION,
  MANUAL_CRITERIA,
  behaviorGateMetrics
}
