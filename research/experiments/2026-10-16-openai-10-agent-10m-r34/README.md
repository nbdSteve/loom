# 10-agent ten-minute routine-v32 survival-discovery live shakedown

This directory is the sanitized, bounded publication from `2026-10-16-openai-10-agent-10m-r34`.
Every number below is generated from [evidence.jsonl](./evidence.jsonl) through the
committed research publisher; [metrics.json](./metrics.json) is the complete deterministic derivation.

## Question

Can ten independently embodied Loom agents complete the bounded routine-v32 balanced-envelope-10m-10 profile using only native event-driven cognition, while preserving complete per-kind perception and lifecycle evidence?
This live-model run observes independently routed agents without assigned roles, pairings, social goals, or a scripted outcome.

## Setup facts

- Source commit: `73487c4ce5a158afd18a14125092163e1dfab8d0`.
- Paper 1.21.1, build 133, fixed seed `20011`.
- Agents: `tribe-alpha` as `loom_aa88da42d7`, `tribe-beta` as `loom_eab7eb8acb`, `tribe-delta` as `loom_3b4ef3e082`, `tribe-epsilon` as `loom_003398683d`, `tribe-eta` as `loom_421b3b32c1`, `tribe-gamma` as `loom_18c040d2cc`, `tribe-iota` as `loom_8b6535c92c`, `tribe-kappa` as `loom_f309750e7b`, `tribe-theta` as `loom_9bc6579ca4`, `tribe-zeta` as `loom_3d8263b30e`.
- Cognition: `openai`, 378 global calls,
  40 per agent, 6 outstanding.
- External spend: $1.446838820 provider-reported actual,
  $29.178265600 worst case under the call/token bounds,
  and $29.190000 absolute declared ceiling.
- Wall-clock ceiling: 600s. Simulation ceiling: 12000 ticks.
- The server, body control, and cognition listeners were loopback-only; no real player account was used.
- Native goal reviews: 5 expected per agent,
  2400-tick cadence, 100-tick slices.
- Initial positions: {"tribe-alpha":[12.5,67,0.5],"tribe-beta":[9.5,67,7.5],"tribe-gamma":[3.5,67,11.5],"tribe-delta":[-4.5,66,12.5],"tribe-epsilon":[-9.5,66,7.5],"tribe-zeta":[-11.5,66,0.5],"tribe-eta":[-9.5,66,-7.5],"tribe-theta":[-3.5,67,-11.5],"tribe-iota":[3.5,68,-11.5],"tribe-kappa":[9.5,69,-7.5]}.
- Initial world clock: {"timeOfDay":6000,"day":0,"isDay":true}.
- Starting inventories were empty; no role, target, social goal, hierarchy, pairing, conflict, or scripted objective was injected.

## Observations

- 64 paired self-position sample(s), the two agents' perceptions taken within 20 ticks of each other, placed two agents within eight blocks
- 132 perception(s) showed another player within eight blocks
- 1 attack intent(s) were applied
- 6 agent(s) died under the server witness: tribe-alpha, tribe-beta, tribe-delta, tribe-epsilon, tribe-eta, tribe-gamma.
- 350 perceptions produced 217 decisions and 217 applied intents.
- No body process was deliberately crashed and no harness `/loombody think` command was issued.
- Native scheduled reviews by agent: {"tribe-alpha":5,"tribe-beta":5,"tribe-delta":5,"tribe-epsilon":5,"tribe-eta":5,"tribe-gamma":5,"tribe-iota":5,"tribe-kappa":5,"tribe-theta":5,"tribe-zeta":5}.
- Server-witnessed deaths: {"tribe-alpha":{"attempted":5,"completed":5,"heldAtEnd":0,"lifeTicks":11121,"maximum":5,"minimum":5,"settledAfterDeath":["tribe-alpha:loomaa88da42d7-sense-135"],"tick":11196},"tribe-beta":{"attempted":5,"completed":5,"heldAtEnd":0,"lifeTicks":10782,"maximum":5,"minimum":5,"settledAfterDeath":[],"tick":10857},"tribe-delta":{"attempted":5,"completed":5,"heldAtEnd":0,"lifeTicks":12066,"maximum":5,"minimum":5,"settledAfterDeath":[],"tick":12141},"tribe-epsilon":{"attempted":5,"completed":5,"heldAtEnd":0,"lifeTicks":10598,"maximum":5,"minimum":4,"settledAfterDeath":["tribe-epsilon:loom003398683d-sense-111","tribe-epsilon:loom003398683d-sense-115"],"tick":10673},"tribe-eta":{"attempted":5,"completed":5,"heldAtEnd":0,"lifeTicks":11591,"maximum":5,"minimum":5,"settledAfterDeath":["tribe-eta:loom421b3b32c1-sense-13z","tribe-eta:loom421b3b32c1-sense-146","tribe-eta:loom421b3b32c1-sense-14e"],"tick":11666},"tribe-gamma":{"attempted":5,"completed":5,"heldAtEnd":0,"lifeTicks":12112,"maximum":5,"minimum":5,"settledAfterDeath":["tribe-gamma:loom18c040d2cc-sense-16j"],"tick":12187}}; surviving agents continued.
- Per-kind audit samples: 350; complete: true.
- Cleanup verification recorded: true.

## Behavioral gate

- Mechanical pilot verdict: **notApplicable** (loom-primary-behavior-gate/17).
- Criteria: not applicable outside the 180-second pilot profile.
- Meaningful behaviors by agent: {"tribe-alpha":9,"tribe-beta":12,"tribe-delta":12,"tribe-epsilon":9,"tribe-eta":11,"tribe-gamma":11,"tribe-iota":6,"tribe-kappa":6,"tribe-theta":8,"tribe-zeta":5}; typed goal continuity: ["tribe-alpha","tribe-beta","tribe-delta","tribe-epsilon","tribe-eta","tribe-gamma","tribe-iota","tribe-kappa","tribe-theta","tribe-zeta"].
- Non-idle goal-review actions: 106, accepted 100, confirmed 98.
- Calls by agent: {"tribe-alpha":27,"tribe-beta":30,"tribe-delta":28,"tribe-epsilon":23,"tribe-eta":24,"tribe-gamma":25,"tribe-iota":17,"tribe-kappa":21,"tribe-theta":13,"tribe-zeta":13}; peak outstanding 6.
- A pass still needs a reader to confirm: each counted meaningful behavior has an evidence-grounded aim; no counted action is justified only by being active; no cross-agent goal leakage in the typed fields.
- Readiness: **notReady** (loom-readiness-gate/17); decision validity 0.986175, typed terminal accounting 1, server truthfulness 1.
- Actions by class: {"concurrent":1,"confirmed":168,"death":3,"inapplicable":3,"incompleteMovement":1,"superseded":37,"targetRace":3,"terrain":2}; blockers: {"inapplicable":3,"incompleteMovement":1}; legitimate races: 46; cognition failures: {}; server disputes: 0.
- Goal coherence: **incoherent**, part of the verdict (mechanically notReady); {"alreadyMetConditions":0,"completionsWithoutNext":0,"conditionSteps":40,"goalsMeasured":38,"idleSteps":0,"ignoredOwnDrops":0,"measuredSteps":90,"metCompleted":19,"metConditions":19,"metIgnored":0,"metRevised":0,"multiStepGoals":7,"pursuit":{"bars":{"chainedGoals":2,"craftChainSteps":1,"goalActiveReviewShare":0.6,"maxIdleBesideExploration":0,"minAgentGoalActiveReviewShare":0.4,"minAgentProgressingHeldTimeShare":0.25,"progressingHeldTimeShare":0.5,"substantiveCompletions":3,"substantiveMultiStepAgents":3},"description":{"crowdedGoals":0,"dangerEpisodes":111,"deaths":6,"deferredReviews":0,"discoveryGoals":7,"discoveryReveals":5,"discoveryTransitions":6,"distinctGoalItems":15,"exploringAgents":10,"horizonGoals":12,"ownDropPickups":{"accepted":18,"collectNoRoute":1,"collectStalled":2}},"descriptive":["distinctGoalItems","exploringAgents","dangerEpisodes"],"measured":{"chainedGoals":2,"craftChainSteps":3,"crowdedGoals":0,"dangerEpisodes":111,"deaths":6,"deferredReviews":0,"discoveredGoals":4,"discoveryGoals":7,"discoveryReveals":5,"discoveryTransitions":6,"distinctGoalItems":15,"exploringAgents":10,"goalActiveReviewShare":0.90566,"horizonGoals":12,"idleBesideExploration":0,"minAgentGoalActiveReviewShare":0.75,"minAgentProgressingHeldTimeShare":0,"noOpRevises":0,"oneStepGoals":18,"progressingHeldTimeShare":0.289354,"readoptedGoals":0,"substantiveCompletions":2,"substantiveMultiStepAgents":3},"schemaShaped":["goalActiveReviewShare","minAgentGoalActiveReviewShare"],"verdict":"notCleared"},"reviseItemChanges":0,"revisesUnchanged":0,"schemaGuarantees":{"alreadyMetConditions":0,"completionsWithoutNext":0,"held":true,"idleSteps":0,"ignoredOwnDrops":0,"meaning":"zero by the request schema; a nonzero count is a defect of the schema or of a backend that bypassed it, not behaviour","metIgnored":0,"reviseItemChanges":0,"revisesUnchanged":0,"stepMismatches":0,"unansweredGoals":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0},"stalled":0,"stepMismatches":0,"stepOnlySteps":14,"unadvancedSteps":36,"unansweredGoals":0,"unevidencedCredits":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0}.
- Calls the JVM decided before the mind: 0 refused to keep calls in reserve (`callReserved`), 0 sent at the ordinary tier past the pivotal ceiling (`tierFallback`), 1 answered but preempted by newer damage (`dangerPreempted`).
  - `tribe-delta:loom3b4ef3e082-sense-bq` collect: inapplicable, intentApplied collectStalled at tick 3622.
  - `tribe-delta:loom3b4ef3e082-sense-k5` collect: inapplicable, intentApplied collectStalled at tick 6026.
  - `tribe-delta:loom3b4ef3e082-sense-q8` collect: inapplicable, intentApplied collectStalled at tick 7835.
  - `tribe-kappa:loomf309750e7b-sense-rl` collect: terrain (race), intentApplied collectNoRoute at tick 8218.
  - `tribe-zeta:loom3d8263b30e-sense-rz` collect: targetRace (race), intentApplied collectItemGone at tick 8356.
  - `tribe-delta:loom3b4ef3e082-sense-u1` flee: incompleteMovement, retreating retreatFailed at tick 8951.
  - `tribe-delta:loom3b4ef3e082-sense-v2` flee: superseded (race), retreating retreatSuperseded at tick 9068.
  - `tribe-delta:loom3b4ef3e082-sense-vw` flee: superseded (race), retreating retreatSuperseded at tick 9258.
  - `tribe-iota:loom8b6535c92c-sense-vb` flee: superseded (race), retreating retreatSuperseded at tick 9299.
  - `tribe-beta:loomeab7eb8acb-sense-x0` flee: superseded (race), retreating retreatSuperseded at tick 9445.
  - `tribe-epsilon:loom003398683d-sense-x6` flee: superseded (race), retreating retreatSuperseded at tick 9780.
  - `tribe-gamma:loom18c040d2cc-sense-x9` flee: superseded (race), retreating retreatSuperseded at tick 9753.
  - `tribe-alpha:loomaa88da42d7-sense-z5` flee: targetRace (race), intentApplied retreatThreatGone at tick 10216.
  - `tribe-alpha:loomaa88da42d7-sense-ze` flee: targetRace (race), intentApplied retreatThreatGone at tick 10256.
  - `tribe-epsilon:loom003398683d-sense-ze` moveTo: superseded (race), moved movementCancelled at tick 10351.
  - `tribe-beta:loomeab7eb8acb-sense-10s` flee: superseded (race), retreating retreatSuperseded at tick 10458.
  - `tribe-beta:loomeab7eb8acb-sense-111` flee: superseded (race), retreating retreatSuperseded at tick 10501.
  - `tribe-beta:loomeab7eb8acb-sense-115` flee: superseded (race), retreating retreatSuperseded at tick 10458.
  - `tribe-beta:loomeab7eb8acb-sense-11d` flee: superseded (race), retreating retreatSuperseded at tick 10521.
  - `tribe-epsilon:loom003398683d-sense-108` flee: superseded (race), retreating retreatSuperseded at tick 10625.
  - `tribe-beta:loomeab7eb8acb-sense-11k` flee: superseded (race), retreating retreatSuperseded at tick 10597.
  - `tribe-alpha:loomaa88da42d7-sense-10l` flee: superseded (race), retreating retreatSuperseded at tick 10609.
  - `tribe-epsilon:loom003398683d-sense-10j` attack: concurrent (race), intentApplied controlNotReady at tick 10577.
  - `tribe-alpha:loomaa88da42d7-sense-10n` flee: superseded (race), retreating retreatSuperseded at tick 10618.
  - `tribe-epsilon:loom003398683d-sense-10r` flee: superseded (race), retreating retreatSuperseded at tick 10672.
  - `tribe-epsilon:loom003398683d-sense-111` flee: death (race), intentApplied retreatThreatGone at tick 10674.
  - `tribe-beta:loomeab7eb8acb-sense-129` flee: superseded (race), retreating retreatSuperseded at tick 10756.
  - `tribe-alpha:loomaa88da42d7-sense-11k` flee: superseded (race), retreating retreatSuperseded at tick 10858.
  - `tribe-theta:loom9bc6579ca4-sense-10x` collect: terrain (race), intentApplied collectNoRoute at tick 10916.
  - `tribe-alpha:loomaa88da42d7-sense-12c` flee: superseded (race), retreating retreatSuperseded at tick 11043.
  - `tribe-alpha:loomaa88da42d7-sense-12g` flee: superseded (race), retreating retreatSuperseded at tick 11056.
  - `tribe-theta:loom9bc6579ca4-sense-121` flee: superseded (race), retreating retreatSuperseded at tick 11311.
  - `tribe-kappa:loomf309750e7b-sense-11z` flee: superseded (race), retreating retreatSuperseded at tick 11290.
  - `tribe-zeta:loom3d8263b30e-sense-11v` flee: superseded (race), retreating retreatSuperseded at tick 11310.
  - `tribe-zeta:loom3d8263b30e-sense-124` flee: superseded (race), retreating retreatSuperseded at tick 11400.
  - `tribe-eta:loom421b3b32c1-sense-12y` flee: superseded (race), retreating retreatSuperseded at tick 11467.
  - `tribe-kappa:loomf309750e7b-sense-12j` flee: superseded (race), retreating retreatSuperseded at tick 11444.
  - `tribe-eta:loom421b3b32c1-sense-13d` flee: superseded (race), retreating retreatSuperseded at tick 11484.
  - `tribe-kappa:loomf309750e7b-sense-12z` flee: superseded (race), retreating retreatSuperseded at tick 11547.
  - `tribe-eta:loom421b3b32c1-sense-13z` flee: death (race), intentApplied accepted at tick 11667.
  - `tribe-kappa:loomf309750e7b-sense-13p` flee: superseded (race), retreating retreatSuperseded at tick 11661.
  - `tribe-kappa:loomf309750e7b-sense-13r` flee: superseded (race), retreating retreatSuperseded at tick 11714.
  - `tribe-eta:loom421b3b32c1-sense-146` flee: death (race), intentApplied accepted at tick 11667.
  - `tribe-gamma:loom18c040d2cc-sense-144` flee: superseded (race), retreating retreatSuperseded at tick 11724.
  - `tribe-gamma:loom18c040d2cc-sense-148` flee: superseded (race), retreating retreatSuperseded at tick 11811.
  - `tribe-iota:loom8b6535c92c-sense-13z` flee: superseded (race), retreating retreatSuperseded at tick 11836.
  - `tribe-gamma:loom18c040d2cc-sense-154` flee: superseded (race), retreating retreatSuperseded at tick 11930.
  - `tribe-delta:loom3b4ef3e082-sense-15s` flee: superseded (race), retreating retreatSuperseded at tick 11923.
  - `tribe-gamma:loom18c040d2cc-sense-159` flee: superseded (race), retreating retreatSuperseded at tick 12010.
  - `tribe-gamma:loom18c040d2cc-sense-15y` flee: superseded (race), decisionDiscarded dangerPreempted at tick 12102.

## Interpretations

- The run supports the narrower claim that the bounded fleet, native scheduler, cognition bridge, per-kind evidence chain,
  publication, and cleanup worked together under the declared conditions.
- Model-driven actions are observations, not proof of motive or emergence. Any social or spatial interpretation is limited to the recorded evidence and counterevidence.

## Counterevidence and null results

- No agent produced a speech intent.
- A null social result remains valid evidence: the publisher records it instead of replacing it with a story.

## Limitations

- Perception positions are samples rather than continuous trajectories.
- One bounded live-model run cannot establish that an observed pattern is stable or emergent.
- The disposable world, server logs, raw provider failures, death ledger, credentials, and process metadata are not committed.
- One short run cannot establish persistence over days, durable relationships, or repeatability across seeds.

## Reproduction

From a clean checkout, install the three pinned toolchains, then run:

```sh
node research/bin/loom-tribe.js run --config research/config/tribe-night-goal-10m-10.json
```

The harness validates a prospective manifest before launch, builds the three runtime components, verifies the
pinned Paper jar, creates a disposable server, enforces call and lifecycle bounds, publishes only normalized
evidence, validates it again, and removes the mutable run directory.
