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
  }),
  // The first seed of the sequence from 10001 whose ring around the origin is dry land among trees
  // with wood to dig and craft (r18's terracotta badlands gave only sticks): an oak and birch
  // forest. Surveyed by the since-retired ring survey from a fresh Paper 1.21.1 world, slot by
  // slot at ten even bearings: each slot is the first radius from 12 to 16 whose column's highest
  // non-leaf collision block is ground, not a log, with two passable cells above it. Every body
  // stands on grass with 90 to 135 logs within sixteen blocks.
  'f1-forest-ring': Object.freeze({
    seed: '10001',
    positions: Object.freeze([
      [12.5, 72, 0.5], [9.5, 68, 7.5], [3.5, 67, 11.5], [-4.5, 66, 14.5], [-9.5, 68, 7.5],
      [-11.5, 69, 0.5], [-9.5, 72, -7.5], [-3.5, 77, -11.5], [3.5, 79, -11.5], [9.5, 78, -7.5]
    ]),
    coverRule: 'A collision-free, non-fluid cover block (short_grass, a peony) in the feet cell counts as passable; leaves above a slot are passed through to the ground beneath them. Nothing is cleared or placed.'
  }),
  // The first seed of the sequence from 20001 whose ring around the origin is flat dry land with
  // most slots' nearest log beyond what a body senses, surveyed by the since-retired ring survey: one
  // tree stands west of the ring, 3.6 and 6.1 blocks from two slots, and the woods lie 10.8 to
  // 25.7 blocks from the rest, so wood must be looked for. Every slot stands on grass_block with
  // air in its feet cell. The run starts at time 9000, so dusk falls about 3500 ticks in and
  // night, with the hostile creatures it spawns on open ground, holds for the rest of the run.
  'e1-explore-ring': Object.freeze({
    seed: '20011',
    positions: Object.freeze([
      [12.5, 67, 0.5], [9.5, 67, 7.5], [3.5, 67, 11.5], [-4.5, 66, 12.5], [-9.5, 66, 7.5],
      [-11.5, 66, 0.5], [-9.5, 66, -7.5], [-3.5, 67, -11.5], [3.5, 68, -11.5], [9.5, 69, -7.5]
    ]),
    initialTimeOfDay: 9000,
    coverRule: null
  }),
  // `e1-explore-ring`'s seed, positions, border and difficulty, starting at time 6000 instead: the
  // r33 audit found 9000 left about two daylight reviews per agent, so its pursuit bars measured
  // night survival. From 6000 a ten-minute run holds 7000 daylight ticks of pursuit before dusk at
  // 13000 and 5000 night ticks of natural danger after it (`exposureOf`).
  'e2-balanced-ring': Object.freeze({
    seed: '20011',
    positions: Object.freeze([
      [12.5, 67, 0.5], [9.5, 67, 7.5], [3.5, 67, 11.5], [-4.5, 66, 12.5], [-9.5, 66, 7.5],
      [-11.5, 66, 0.5], [-9.5, 66, -7.5], [-3.5, 67, -11.5], [3.5, 68, -11.5], [9.5, 69, -7.5]
    ]),
    initialTimeOfDay: 6000,
    coverRule: null
  }),
  // A seed drawn at random, as a signed 64-bit integer from eight bytes of Node's
  // `crypto.randomBytes`, and accepted as the first draw whose ring the since-retired survey placed in
  // full, whatever its resources; no draw was rejected. Each slot is the survey's first radius from
  // 12 to 16 at its bearing (all twelve) whose column's highest non-leaf collision block is ground,
  // not a log, with two passable cells above it. Nine slots stand on grass_block and one on a
  // pumpkin, with 66 to 125 logs within sixteen blocks. It starts at dawn.
  // A world drawn fresh for every run: the harness draws its seed at launch (`drawWorldSeed`),
  // vanilla places each agent at a safe spot near the world spawn, and the border is centred there.
  'random-world': Object.freeze({
    seed: null,
    positions: null,
    coverRule: 'Vanilla spawn placement within its spawn radius of the world spawn. Nothing is cleared or placed.'
  }),
  'x1-random-ring': Object.freeze({
    seed: '1441207653247942039',
    positions: Object.freeze([
      [12.5, 86, 0.5], [9.5, 85, 7.5], [3.5, 80, 11.5], [-3.5, 78, 11.5], [-9.5, 79, 7.5],
      [-11.5, 80, 0.5], [-9.5, 83, -7.5], [-3.5, 85, -11.5], [3.5, 86, -11.5], [9.5, 85, -7.5]
    ]),
    coverRule: 'A collision-free, non-fluid cover block (short_grass) in the feet cell counts as passable. Nothing is cleared or placed.'
  })
})

// The night a run's danger exposure is measured in: vanilla's hostile spawning, as the behavior
// gate reads it (`NIGHT_START`, `NIGHT_END` in `behavior-gate.js`).
const NIGHT_START_TICK = 13_000
const NIGHT_END_TICK = 23_000

/** The daylight and night ticks a run from [start] lasting [ticks] world ticks holds. */
function exposureOf (start, ticks) {
  let daylightTicks = 0
  let nightTicks = 0
  for (let tick = 0; tick < ticks; tick += 1) {
    const time = (start + tick) % 24_000
    if (time >= NIGHT_START_TICK && time < NIGHT_END_TICK) nightTicks += 1
    else daylightTicks += 1
  }
  return { daylightTicks, nightTicks }
}

const TWO_CYCLE_TICKS = 48_000
const CADENCE = Object.freeze({ cadenceTicks: 2400, sliceTicks: 100 })

const PROFILES = {
  'pilot-180s': Object.freeze({
    scene: 'r5-neutral',
    agents: 3,
    runDurationSeconds: 180,
    simulationTickCeiling: 4000,
    expectedReviewsPerAgent: 2,
    // From routine-v26 two scheduled reviews may owe two follow-ups each, so three agents plan 18
    // reviews; the global ceiling holds them and the spend ceiling is those calls at the
    // worst-case price.
    callCeilings: Object.freeze({ perAgent: 6, global: 18, outstanding: 3 }),
    maxExternalSpendUsd: 3.51
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
    // From routine-v26 each scheduled review may owe two follow-ups, the second only while an own
    // goal-item drop lies, so 21 planned reviews per agent become 63 and the per-agent ceiling 64;
    // the global ceiling is three agents' 64 and the spend ceiling those calls at the worst-case
    // price. Re-sized for the follow-ups, not re-measured.
    callCeilings: Object.freeze({ perAgent: 64, global: 192, outstanding: 3 }),
    maxExternalSpendUsd: 37.36,
    bounds: Object.freeze({ logBytes: 16 * 1024 * 1024, evidenceRecords: 10_000, artifactBytes: 8 * 1024 * 1024 }),
    paperMemoryMiB: 1024
  }),
  'two-cycle-10': Object.freeze({
    scene: 's2-ring',
    agents: 10,
    runDurationSeconds: 3600,
    simulationTickCeiling: TWO_CYCLE_TICKS,
    expectedReviewsPerAgent: 21,
    // From routine-v26 each scheduled review may owe two follow-ups, the second only while an own
    // goal-item drop lies, so the 210 planned reviews become 630 and the ceilings 64 per agent and
    // 640 in all; the spend ceiling is 640 calls at the worst-case price. Re-sized for the
    // follow-ups, not re-measured.
    callCeilings: Object.freeze({ perAgent: 64, global: 640, outstanding: 6 }),
    maxExternalSpendUsd: 124.52,
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
// And under routine-v19's actionable refs, because r10 ran under the v18 name.
PROFILES['actionable-refs-10m-10'] = Object.freeze({ ...PROFILES['shakedown-10m-10'] })
// And under routine-v20's typed goal, because r11 ran under the v19 name.
PROFILES['typed-goal-10m-10'] = Object.freeze({ ...PROFILES['shakedown-10m-10'] })
// And under routine-v21, because r12 ran under the v20 name.
PROFILES['grounded-step-10m-10'] = Object.freeze({ ...PROFILES['shakedown-10m-10'] })
// And under routine-v22, because r13 ran under the v21 name.
PROFILES['obtainable-goal-10m-10'] = Object.freeze({ ...PROFILES['shakedown-10m-10'] })
// And under routine-v23, because r14 ran under the v22 name.
PROFILES['own-evidence-10m-10'] = Object.freeze({ ...PROFILES['shakedown-10m-10'] })
// And under routine-v24, because r15 ran under the v23 name. Its plan names the five scheduled
// reviews a run can finish: the sixth is due at tick 12,000 itself, which the window ends on
// (the r15 audit found every agent reviewed five times against a plan of six).
PROFILES['answered-goal-10m-10'] = Object.freeze({ ...PROFILES['shakedown-10m-10'], expectedReviewsPerAgent: 5 })
// And under routine-v25, because r16 ran under the v24 name, with the same five-review plan.
PROFILES['collected-goal-10m-10'] = Object.freeze({ ...PROFILES['shakedown-10m-10'], expectedReviewsPerAgent: 5 })
// And under routine-v26, because r17 ran under the v25 name, with the same five-review plan.
// Its coherence bars are pre-registered here, before the run, and are the gate's positive half
// from generation 9 (the r17 audit): at least three in five goal reviews hold or take up a goal and
// step toward it, no review idles without a goal while cells at the edge of sight are offered, at
// least two agents advance one goal at two or more steps at its original total, and at least three
// goals are completed at their original total two or more above what was held when adopted.
PROFILES['pursued-goal-10m-10'] = Object.freeze({
  ...PROFILES['shakedown-10m-10'],
  expectedReviewsPerAgent: 5,
  coherenceBars: Object.freeze({
    goalActiveReviewShare: 0.6,
    maxIdleWithExploration: 0,
    multiStepAgentsAtOriginalTarget: 2,
    originalTargetCompletions: 3
  })
})
// Under routine-v27 in the forest scene, with the same window, envelope and five-review plan. Its
// bars are pre-registered here, before the run, and are the generation-10 gate's positive half
// (the r18 audit): three in five goal reviews, and two in five of every agent's, hold or take up a
// goal and step toward it; the fleet holds a goal half its time and every agent a quarter of its
// own; no goal review idles or rests beside a cell at the edge of sight; three agents advance one
// goal at two or more steps at its original total; three goals are completed at their original
// total after two or more advances, two or more above what was held or lay as the agent's own drop
// when adopted; goals are set for at least two kinds of item; and at least one goal step crafts.
PROFILES['wooded-goal-10m-10'] = Object.freeze({
  ...PROFILES['shakedown-10m-10'],
  scene: 'f1-forest-ring',
  expectedReviewsPerAgent: 5,
  coherenceBars: Object.freeze({
    goalActiveReviewShare: 0.6,
    minAgentGoalActiveReviewShare: 0.4,
    goalHeldTimeShare: 0.5,
    minAgentGoalHeldTimeShare: 0.25,
    maxIdleBesideExploration: 0,
    multiStepAgentsAtOriginalTarget: 3,
    originalTargetCompletions: 3,
    distinctGoalItems: 2,
    craftSteps: 1
  })
})
// The v28 profile moves the scene to the plains a walk from the woods, starting in the
// afternoon, and pre-registers the generation-11 bars before the run (the r19 audit). Goal
// activity counts no revise that restates its total, and held time only while the goal advanced
// within the last 2400 ticks; multi-step agents and completions count only goals that sight did
// not guarantee one dig at a time, or that a later goal built on; two goals must be chained to an
// earlier goal whose item their recipe uses and advance; one craft must step a chained goal;
// three agents must walk to the edge of sight; and one perception must answer a threat sighted or
// damage taken. The shares and the idle bar are r18's.
PROFILES['explored-goal-10m-10'] = Object.freeze({
  ...PROFILES['shakedown-10m-10'],
  scene: 'e1-explore-ring',
  expectedReviewsPerAgent: 5,
  coherenceBars: Object.freeze({
    goalActiveReviewShare: 0.6,
    minAgentGoalActiveReviewShare: 0.4,
    progressingHeldTimeShare: 0.5,
    minAgentProgressingHeldTimeShare: 0.25,
    maxIdleBesideExploration: 0,
    substantiveMultiStepAgents: 3,
    substantiveCompletions: 3,
    chainedGoals: 2,
    distinctGoalItems: 3,
    craftChainSteps: 1,
    exploringAgents: 3,
    dangerEncounters: 1
  })
})
// The v29 profile keeps the plains scene and pre-registers the generation-12 bars (the r20 audit).
// A goal counts toward substance only when it needed two or more beyond what was held, was not
// re-adopted at a total just cut from, and - chained - advanced after its adoption; danger is
// counted in answered episodes. The plan earns one follow-up a scheduled review, so its hundred
// calls leave a third of the ceiling to the night's events.
PROFILES['night-goal-10m-10'] = Object.freeze({
  ...PROFILES['shakedown-10m-10'],
  scene: 'e1-explore-ring',
  expectedReviewsPerAgent: 5,
  coherenceBars: Object.freeze({
    goalActiveReviewShare: 0.6,
    minAgentGoalActiveReviewShare: 0.4,
    progressingHeldTimeShare: 0.5,
    minAgentProgressingHeldTimeShare: 0.25,
    maxIdleBesideExploration: 0,
    substantiveMultiStepAgents: 3,
    substantiveCompletions: 3,
    chainedGoals: 2,
    distinctGoalItems: 3,
    craftChainSteps: 1,
    exploringAgents: 3,
    dangerEpisodes: 1
  })
})
// The v31 profile keeps the plains scene, envelope and plan, and pre-registers the generation-14
// bars (the r23 audit): the same strict substance bars, with held time progressing only from a
// goal's first advance, and none of the bars a run that pursued anything could not fail.
PROFILES['discovery-goal-10m-10'] = Object.freeze({
  ...PROFILES['shakedown-10m-10'],
  scene: 'e1-explore-ring',
  expectedReviewsPerAgent: 5,
  coherenceBars: Object.freeze({
    goalActiveReviewShare: 0.6,
    minAgentGoalActiveReviewShare: 0.4,
    progressingHeldTimeShare: 0.5,
    minAgentProgressingHeldTimeShare: 0.25,
    maxIdleBesideExploration: 0,
    substantiveMultiStepAgents: 3,
    substantiveCompletions: 3,
    chainedGoals: 2,
    craftChainSteps: 1
  })
})
// The v32 profile keeps the discovery-goal profile's plains scene, plan and bars, and widens the
// call envelope by tier within the unchanged $29.19 ceiling (the r24 audit): r24's late damage
// calls were refused by its 150-call ceiling after 149 calls, while its routine and notable calls,
// which the ordinary model answers at a twelfth of the pivotal model's worst price, were priced as
// pivotal ones. At most 130 calls may be pivotal and the other 248 ordinary: 130 x $0.19456 plus
// 248 x $0.0156672 is $29.1782656, under the ceiling, at the measured 65,536-byte request and
// 2,048-token response bounds and the six-outstanding cap.
PROFILES['survival-envelope-10m-10'] = Object.freeze({
  ...PROFILES['discovery-goal-10m-10'],
  callCeilings: Object.freeze({ perAgent: 40, global: 378, outstanding: 6, pivotal: 130 })
})
// The survival envelope's bounds, bars, plan and population on the balanced scene (the r33 audit),
// with the daylight and night exposure it must hold pre-registered here, before any run.
PROFILES['balanced-envelope-10m-10'] = Object.freeze({
  ...PROFILES['survival-envelope-10m-10'],
  scene: 'e2-balanced-ring',
  exposure: Object.freeze({ daylightTicks: 7000, nightTicks: 5000 })
})
// The balanced envelope's scene, bounds, bars and exposure with two follow-ups a scheduled review
// in place of one (the r35 audit): with one, the review a settled step owes was kept back for an
// owed one, so 26 of r35's 60 day goal steps waited a whole cadence for their next review. 50
// scheduled reviews and 100 follow-ups leave 228 of the 378 calls to events, past the third the
// mode keeps, and 15 a living agent's own, under its 40.
PROFILES['balanced-follow-up-10m-10'] = Object.freeze({
  ...PROFILES['balanced-envelope-10m-10'],
  followUpsPerReview: 2
})
// The balanced follow-up profile's scene, bounds, exposure, plan and every bar unchanged, with the
// shelter bar pre-registered here before any run (the r36 audit: all ten agents died at night in
// the open): at least three agents' own adopted shelter goals took a server-witnessed step that
// left them more enclosed before nightfall, measured by gate generation 19.
PROFILES['shelter-goal-10m-10'] = Object.freeze({
  ...PROFILES['balanced-follow-up-10m-10'],
  coherenceBars: Object.freeze({
    ...PROFILES['balanced-follow-up-10m-10'].coherenceBars,
    shelterProgressAgentsBeforeNight: 3
  })
})
// The shelter-goal profile's scene, bounds, exposure, plan and every bar unchanged - progressing
// held time at least 0.50 and every agent's at least 0.25, three substantive completions and
// multi-step agents, and the shelter bar - under a name of its own, so r39's committed evidence
// keeps the profile it ran (the r39 audit: every structural bar met, held time missed with goals
// that never stayed held), measured by gate generation 20.
PROFILES['held-objective-10m-10'] = Object.freeze({ ...PROFILES['shelter-goal-10m-10'] })
// The held-objective profile's scene, bounds, exposure and every bar unchanged, with four follow-ups
// a scheduled review in place of two (R43: every agent waited four times 1500 to 2200 ticks for its
// next review once its two follow-ups were spent, chained goals advanced once before night replaced
// them, and 73 of the 378 calls went unused). 50 scheduled reviews and 200 follow-ups leave 128 of
// the 378 calls to events, the third the mode keeps, and 15 of a living agent's own 40.
PROFILES['held-objective-follow-up-10m-10'] = Object.freeze({
  ...PROFILES['held-objective-10m-10'],
  followUpsPerReview: 4
})
// The held-objective follow-up profile's plan and bars over one real-clock hour on the random
// x1-random-ring scene from dawn: three days and nights. Its per-ten-minute call envelope is scaled by six, so
// 30 reviews and 120 follow-ups an agent leave 768 of the 2,268 calls to events, and 780 pivotal
// and 1,488 ordinary calls at their worst case are $175.0695936. The evidence bounds keep the
// ten-minute margin over six times the largest ten-minute volume, under GitHub's 100 MiB file limit.
PROFILES['long-run-60m-10'] = Object.freeze({
  ...PROFILES['held-objective-follow-up-10m-10'],
  scene: 'x1-random-ring',
  runDurationSeconds: 3600,
  simulationTickCeiling: 72_000,
  expectedReviewsPerAgent: 30,
  exposure: Object.freeze({ daylightTicks: 42_000, nightTicks: 30_000 }),
  callCeilings: Object.freeze({ perAgent: 240, global: 2268, outstanding: 6, pivotal: 780 }),
  maxExternalSpendUsd: 175.08,
  bounds: Object.freeze({ logBytes: 128 * 1024 * 1024, evidenceRecords: 64_000, artifactBytes: 96 * 1024 * 1024 })
})
// The long-run profile with periodic cognition: one review per agent every 60 ticks (3 s) whatever
// the body is doing, up to four with the mind at once (the 12 s provider timeout over the 3 s
// interval), no follow-ups, and only the newest answer applied. 1,200 reviews an agent leave a
// third of 18,000 calls to events; 1,800 pivotal and 16,200 ordinary calls at their worst case
// are $604.01664. The 50 outstanding are ten agents' four reviews and one event each.
const PERIODIC_CADENCE = Object.freeze({ cadenceTicks: 60, sliceTicks: 3, reviewsInFlightPerAgent: 4 })
PROFILES['periodic-60m-10'] = Object.freeze({
  ...PROFILES['long-run-60m-10'],
  cadence: PERIODIC_CADENCE,
  followUpsPerReview: 0,
  expectedReviewsPerAgent: 1200,
  callCeilings: Object.freeze({ perAgent: 1800, global: 18_000, outstanding: 50, pivotal: 1800 }),
  maxExternalSpendUsd: 604.02,
  bounds: Object.freeze({ logBytes: 1024 * 1024 * 1024, evidenceRecords: 1_000_000, artifactBytes: 1024 * 1024 * 1024 })
})
// The same over ten minutes: 200 reviews an agent, $100.66944 at worst.
PROFILES['periodic-10m-10'] = Object.freeze({
  ...PROFILES['periodic-60m-10'],
  runDurationSeconds: 600,
  simulationTickCeiling: 12_000,
  expectedReviewsPerAgent: 200,
  exposure: undefined,
  callCeilings: Object.freeze({ perAgent: 300, global: 3000, outstanding: 50, pivotal: 300 }),
  maxExternalSpendUsd: 100.67,
  bounds: Object.freeze({ logBytes: 256 * 1024 * 1024, evidenceRecords: 200_000, artifactBytes: 256 * 1024 * 1024 })
})
// One agent on a world drawn fresh every run, thinking every 3 s, for as long as its config says:
// `durationScaled` profiles take the run's duration from the config and scale their plan, ceilings
// and bounds by the hour (`profileFor`). Per hour: 1,200 reviews, 1,800 calls (a third left to
// events), no separate pivotal cap, so every call at the pivotal worst case is $350.208.
PROFILES['solo-1'] = Object.freeze({
  ...PROFILES['periodic-60m-10'],
  agents: 1,
  scene: 'random-world',
  exposure: undefined,
  durationScaled: Object.freeze({
    reviewsPerHour: 1200,
    callsPerHour: 1800,
    outstanding: 5,
    logBytesPerHour: 128 * 1024 * 1024,
    evidenceRecordsPerHour: 120_000
  })
})
Object.freeze(PROFILES)

/** [profile] as [config] runs it: a duration-scaled profile takes the config's duration. */
function profileFor (profile, config) {
  const scaled = profile?.durationScaled
  const seconds = config?.bounds?.runDurationSeconds
  if (scaled === undefined || !Number.isInteger(seconds) || seconds < 1 || seconds % 3600 !== 0) return profile
  const hours = seconds / 3600
  const calls = scaled.callsPerHour * hours
  const ticks = seconds * ticksPerSecondOf(profile)
  // Every call at the pivotal worst case, to the cent above.
  const worstNanoUsd = calls * 194_560_000
  return {
    ...profile,
    runDurationSeconds: seconds,
    simulationTickCeiling: ticks,
    expectedReviewsPerAgent: scaled.reviewsPerHour * hours,
    callCeilings: { perAgent: calls, global: calls, outstanding: scaled.outstanding, pivotal: calls },
    maxExternalSpendUsd: Math.ceil(worstNanoUsd / 10_000_000) / 100,
    bounds: {
      logBytes: Math.min(1024 * 1024 * 1024, scaled.logBytesPerHour * hours),
      evidenceRecords: Math.min(1_000_000, scaled.evidenceRecordsPerHour * hours),
      artifactBytes: Math.min(1024 * 1024 * 1024, scaled.logBytesPerHour * hours)
    }
  }
}

/** A fresh signed 64-bit world seed, as Minecraft reads `level-seed`. */
function drawWorldSeed () {
  return require('node:crypto').randomBytes(8).readBigInt64BE().toString()
}

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
    // A drawn world pins no positions: vanilla places each agent near the world spawn.
    positions: scene.positions === null
      ? undefined
      : Object.fromEntries(agents.map((agentId, slot) => [agentId, [...scene.positions[slot]]]))
  }
}

module.exports = {
  AGENT_ORDER,
  drawWorldSeed,
  profileFor,
  exposureOf,
  CADENCE,
  PROFILES,
  SCENES,
  TWO_CYCLE_TICKS,
  placementOf,
  profileOf,
  ticksPerSecondOf
}
