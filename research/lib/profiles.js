'use strict'

// Named, frozen primary-behavior profiles. A profile pins the scene, population, duration,
// review plan, call envelope and runtime bounds a run uses; a mode pins only its prompts and
// selector attestation. Nothing here is a behavior policy: positions are measured placement
// facts and every bound is a resource ceiling.

const AGENT_ORDER = Object.freeze([
  'tribe-alpha', 'tribe-beta', 'tribe-gamma', 'tribe-delta', 'tribe-epsilon',
  'tribe-zeta', 'tribe-eta', 'tribe-theta', 'tribe-iota', 'tribe-kappa'
])

const SCENES = Object.freeze({
  'r5-neutral': Object.freeze({
    seed: '76192346017',
    positions: Object.freeze([[10.5, 68, 3.5], [1.5, 68, 1.5], [-9.5, 68, -7.5]]),
    coverRule: null
  }),
  // The second neutral seed's ring of radius 12 around the origin, surveyed slot by slot from
  // the saved region files and marker-confirmed. Each body stands on terracotta.
  's2-ring': Object.freeze({
    seed: '27183455263279',
    positions: Object.freeze([
      [12.5, 105, 0.5], [10.5, 99, 7.5], [4.5, 102, 11.5], [-3.5, 110, 11.5], [-9.5, 111, 7.5],
      [-11.5, 110, 0.5], [-9.5, 110, -6.5], [-3.5, 108, -10.5], [4.5, 111, -10.5], [10.5, 108, -6.5]
    ]),
    // Disclosed after the strict survey: three slots' feet cells hold a dead_bush.
    coverRule: 'A collision-free, non-fluid cover block (dead_bush, short_grass, fern, snow layer 1) in the feet cell counts as passable. Nothing is cleared or placed.'
  })
})

const TWO_CYCLE_TICKS = 48_000
const CADENCE = Object.freeze({ cadenceTicks: 2400, sliceTicks: 100 })

const PROFILES = {
  'pilot-180s': Object.freeze({
    scene: 'r5-neutral',
    agents: 3,
    runDurationSeconds: 180,
    simulationTickCeiling: 4000,
    expectedReviewsPerAgent: 2,
    callCeilings: Object.freeze({ perAgent: 6, global: 12, outstanding: 3 }),
    maxExternalSpendUsd: 2.34
  }),
  // Two full day/night cycles. Review 20 is due at tick 48,000 itself, so the plan names 21
  // and the window rule owes only the ones the ending made due.
  //
  // Sized from the provider-free normal-difficulty samples after danger coalescing
  // (research/HARNESS.md, "Measured two-cycle budgets"): the busiest agent spent 11 danger
  // calls in its one night, so two nights and 21 reviews need 43; the per-agent share is
  // that with a quarter of headroom. Bounds are four times the extrapolated 48,000-tick need.
  'two-cycle-3': Object.freeze({
    scene: 's2-ring',
    agents: 3,
    runDurationSeconds: 3600,
    simulationTickCeiling: TWO_CYCLE_TICKS,
    expectedReviewsPerAgent: 21,
    callCeilings: Object.freeze({ perAgent: 56, global: 150, outstanding: 3 }),
    maxExternalSpendUsd: 29.19,
    bounds: Object.freeze({ logBytes: 16 * 1024 * 1024, evidenceRecords: 10_000, artifactBytes: 8 * 1024 * 1024 }),
    paperMemoryMiB: 1024
  }),
  'two-cycle-10': Object.freeze({
    scene: 's2-ring',
    agents: 10,
    runDurationSeconds: 3600,
    simulationTickCeiling: TWO_CYCLE_TICKS,
    expectedReviewsPerAgent: 21,
    callCeilings: Object.freeze({ perAgent: 56, global: 400, outstanding: 6 }),
    maxExternalSpendUsd: 77.83,
    bounds: Object.freeze({ logBytes: 32 * 1024 * 1024, evidenceRecords: 20_000, artifactBytes: 32 * 1024 * 1024 }),
    paperMemoryMiB: 2048
  })
}

// Provider-free only. The accelerated profiles prove the whole two-cycle structure in one
// continuous run of about 20 minutes by running the server clock at twice vanilla's rate; the sample profiles run the real clock for a
// bounded wall time so log, evidence, memory, startup and tick-rate bounds can be measured.
// Neither changes the paid profiles above.
for (const agents of [3, 10]) {
  const base = PROFILES[`two-cycle-${agents}`]
  PROFILES[`two-cycle-${agents}-accelerated`] = Object.freeze({
    ...base,
    runDurationSeconds: 1440,
    ticksPerSecond: 40,
    scriptedOnly: true
  })
  // The same continuous 40 tick/s run in a peaceful world, so the whole 48,000-tick
  // structure is exercised with every agent alive; the normal-difficulty profile above is
  // where deaths are proven.
  PROFILES[`two-cycle-${agents}-structure`] = Object.freeze({
    ...base,
    runDurationSeconds: 1440,
    ticksPerSecond: 40,
    world: Object.freeze({ difficulty: 'peaceful', mobSpawning: false }),
    scriptedOnly: true
  })
  PROFILES[`two-cycle-${agents}-sample`] = Object.freeze({
    ...base,
    runDurationSeconds: 1320,
    scriptedOnly: true
  })
}
// The ten-agent ten-minute live shakedown: the two-cycle-10 scene, population, world, models,
// prompts and request/output bounds over a real-clock window of 600 seconds or 12,000 ticks.
// Review 6 is due at tick 12,000 itself, so the plan names six and the window rule owes only
// the ones the ending made due. 150 calls at the worst-case price are $29.184. The bounds are
// about eight times the 12,000-tick share of the ten-agent normal-difficulty sample.
PROFILES['shakedown-10m-10'] = Object.freeze({
  ...PROFILES['two-cycle-10'],
  runDurationSeconds: 600,
  simulationTickCeiling: 12_000,
  expectedReviewsPerAgent: 6,
  callCeilings: Object.freeze({ perAgent: 24, global: 150, outstanding: 6 }),
  maxExternalSpendUsd: 29.19,
  bounds: Object.freeze({ logBytes: 16 * 1024 * 1024, evidenceRecords: 8000, artifactBytes: 16 * 1024 * 1024 })
})
// The same ten-minute shakedown under the routine-v14 grounded-walk mode. Every pin is the
// shakedown's own; it is a separate name so the v13 shakedown's committed evidence keeps
// naming the profile it ran under.
PROFILES['grounded-walk-10m-10'] = Object.freeze({ ...PROFILES['shakedown-10m-10'] })
// And again under routine-v15, for the same reason: r5 ran under the v14 name.
PROFILES['grounded-walk-v15-10m-10'] = Object.freeze({ ...PROFILES['shakedown-10m-10'] })
// And under routine-v17's working goal, because r6 ran under the v15 name.
PROFILES['working-goal-10m-10'] = Object.freeze({ ...PROFILES['shakedown-10m-10'] })
// And under routine-v18's offered refs, because r9 ran under the v17 name.
PROFILES['offered-refs-10m-10'] = Object.freeze({ ...PROFILES['shakedown-10m-10'] })
Object.freeze(PROFILES)

/** The server tick rate a profile runs at; vanilla's 20 unless the profile accelerates it. */
function ticksPerSecondOf (profile) {
  return profile.ticksPerSecond ?? 20
}

function profileOf (name) {
  return Object.hasOwn(PROFILES, name) ? PROFILES[name] : undefined
}

/** The agent ids and exact initial positions a profile places, in slot order. */
function placementOf (profile) {
  const scene = SCENES[profile.scene]
  const agents = AGENT_ORDER.slice(0, profile.agents)
  return {
    agents,
    positions: Object.fromEntries(agents.map((agentId, slot) => [agentId, [...scene.positions[slot]]]))
  }
}

module.exports = {
  AGENT_ORDER,
  CADENCE,
  PROFILES,
  SCENES,
  TWO_CYCLE_TICKS,
  placementOf,
  profileOf,
  ticksPerSecondOf
}
