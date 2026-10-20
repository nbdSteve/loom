# 10-agent ten-minute routine-v32 survival-discovery live shakedown

This directory is the sanitized, bounded publication from `2026-10-16-openai-10-agent-10m-r33`.
Every number below is generated from [evidence.jsonl](./evidence.jsonl) through the
committed research publisher; [metrics.json](./metrics.json) is the complete deterministic derivation.

## Question

Can ten independently embodied Loom agents complete the bounded routine-v32 survival-envelope-10m-10 profile using only native event-driven cognition, while preserving complete per-kind perception and lifecycle evidence?
This live-model run observes independently routed agents without assigned roles, pairings, social goals, or a scripted outcome.

## Setup facts

- Source commit: `03df52d623d4ccdc346535592df0a82a3c99c5ae`.
- Paper 1.21.1, build 133, fixed seed `20011`.
- Agents: `tribe-alpha` as `loom_aa88da42d7`, `tribe-beta` as `loom_eab7eb8acb`, `tribe-delta` as `loom_3b4ef3e082`, `tribe-epsilon` as `loom_003398683d`, `tribe-eta` as `loom_421b3b32c1`, `tribe-gamma` as `loom_18c040d2cc`, `tribe-iota` as `loom_8b6535c92c`, `tribe-kappa` as `loom_f309750e7b`, `tribe-theta` as `loom_9bc6579ca4`, `tribe-zeta` as `loom_3d8263b30e`.
- Cognition: `openai`, 378 global calls,
  40 per agent, 6 outstanding.
- External spend: $2.127959420 provider-reported actual,
  $29.178265600 worst case under the call/token bounds,
  and $29.190000 absolute declared ceiling.
- Wall-clock ceiling: 600s. Simulation ceiling: 12000 ticks.
- The server, body control, and cognition listeners were loopback-only; no real player account was used.
- Native goal reviews: 5 expected per agent,
  2400-tick cadence, 100-tick slices.
- Initial positions: {"tribe-alpha":[12.5,67,0.5],"tribe-beta":[9.5,67,7.5],"tribe-gamma":[3.5,67,11.5],"tribe-delta":[-4.5,66,12.5],"tribe-epsilon":[-9.5,66,7.5],"tribe-zeta":[-11.5,66,0.5],"tribe-eta":[-9.5,66,-7.5],"tribe-theta":[-3.5,67,-11.5],"tribe-iota":[3.5,68,-11.5],"tribe-kappa":[9.5,69,-7.5]}.
- Initial world clock: {"timeOfDay":9000,"day":0,"isDay":true}.
- Starting inventories were empty; no role, target, social goal, hierarchy, pairing, conflict, or scripted objective was injected.

## Observations

- 73 paired self-position sample(s), the two agents' perceptions taken within 20 ticks of each other, placed two agents within eight blocks
- 134 perception(s) showed another player within eight blocks
- 10 agent(s) died under the server witness: tribe-alpha, tribe-beta, tribe-delta, tribe-epsilon, tribe-eta, tribe-gamma, tribe-iota, tribe-kappa, tribe-theta, tribe-zeta.
- 401 perceptions produced 221 decisions and 221 applied intents.
- No body process was deliberately crashed and no harness `/loombody think` command was issued.
- Native scheduled reviews by agent: {"tribe-alpha":5,"tribe-beta":5,"tribe-delta":4,"tribe-epsilon":4,"tribe-eta":3,"tribe-gamma":4,"tribe-iota":4,"tribe-kappa":4,"tribe-theta":3,"tribe-zeta":4}.
- Server-witnessed deaths: {"tribe-alpha":{"attempted":5,"completed":5,"heldAtEnd":0,"lifeTicks":11048,"maximum":5,"minimum":5,"settledAfterDeath":[],"tick":11114},"tribe-beta":{"attempted":5,"completed":5,"heldAtEnd":0,"lifeTicks":10065,"maximum":5,"minimum":4,"settledAfterDeath":["tribe-beta:loomeab7eb8acb-sense-yz"],"tick":10131},"tribe-delta":{"attempted":4,"completed":4,"heldAtEnd":0,"lifeTicks":8247,"maximum":4,"minimum":3,"settledAfterDeath":["tribe-delta:loom3b4ef3e082-sense-tu"],"tick":8313},"tribe-epsilon":{"attempted":4,"completed":4,"heldAtEnd":0,"lifeTicks":7732,"maximum":4,"minimum":3,"settledAfterDeath":["tribe-epsilon:loom003398683d-sense-re"],"tick":7798},"tribe-eta":{"attempted":3,"completed":3,"heldAtEnd":1,"lifeTicks":8149,"maximum":4,"minimum":3,"settledAfterDeath":["tribe-eta:loom421b3b32c1-sense-sy"],"tick":8215},"tribe-gamma":{"attempted":4,"completed":4,"heldAtEnd":0,"lifeTicks":8280,"maximum":4,"minimum":3,"settledAfterDeath":["tribe-gamma:loom18c040d2cc-sense-tc"],"tick":8346},"tribe-iota":{"attempted":4,"completed":4,"heldAtEnd":0,"lifeTicks":9570,"maximum":4,"minimum":4,"settledAfterDeath":["tribe-iota:loom8b6535c92c-sense-y8"],"tick":9636},"tribe-kappa":{"attempted":4,"completed":4,"heldAtEnd":0,"lifeTicks":8900,"maximum":4,"minimum":4,"settledAfterDeath":["tribe-kappa:loomf309750e7b-sense-w0"],"tick":8966},"tribe-theta":{"attempted":3,"completed":3,"heldAtEnd":0,"lifeTicks":7860,"maximum":4,"minimum":3,"settledAfterDeath":[],"tick":7926},"tribe-zeta":{"attempted":4,"completed":4,"heldAtEnd":0,"lifeTicks":9133,"maximum":4,"minimum":4,"settledAfterDeath":["tribe-zeta:loom3d8263b30e-sense-w6"],"tick":9199}}; surviving agents continued.
- Per-kind audit samples: 401; complete: true.
- Cleanup verification recorded: true.

## Behavioral gate

- Mechanical pilot verdict: **notApplicable** (loom-primary-behavior-gate/16).
- Criteria: not applicable outside the 180-second pilot profile.
- Meaningful behaviors by agent: {"tribe-alpha":9,"tribe-beta":8,"tribe-delta":15,"tribe-epsilon":5,"tribe-eta":7,"tribe-gamma":6,"tribe-iota":7,"tribe-kappa":4,"tribe-theta":3,"tribe-zeta":11}; typed goal continuity: ["tribe-alpha","tribe-beta","tribe-delta","tribe-epsilon","tribe-eta","tribe-gamma","tribe-iota","tribe-theta","tribe-zeta"].
- Non-idle goal-review actions: 89, accepted 87, confirmed 82.
- Calls by agent: {"tribe-alpha":27,"tribe-beta":14,"tribe-delta":29,"tribe-epsilon":20,"tribe-eta":22,"tribe-gamma":23,"tribe-iota":28,"tribe-kappa":28,"tribe-theta":22,"tribe-zeta":27}; peak outstanding 6.
- A pass still needs a reader to confirm: each counted meaningful behavior has an evidence-grounded aim; no counted action is justified only by being active; no cross-agent goal leakage in the typed fields.
- Readiness: **notReady** (loom-readiness-gate/16); decision validity 1, typed terminal accounting 1, server truthfulness 1.
- Actions by class: {"concurrent":1,"confirmed":175,"death":3,"superseded":36,"targetRace":6}; blockers: none; legitimate races: 46; cognition failures: {}; server disputes: 0.
- Goal coherence: **incoherent**, part of the verdict (mechanically ready); {"alreadyMetConditions":0,"completionsWithoutNext":0,"conditionSteps":25,"goalsMeasured":32,"idleSteps":0,"ignoredOwnDrops":0,"measuredSteps":73,"metCompleted":17,"metConditions":19,"metIgnored":2,"metRevised":0,"multiStepGoals":6,"pursuit":{"bars":{"chainedGoals":2,"craftChainSteps":1,"goalActiveReviewShare":0.6,"maxIdleBesideExploration":0,"minAgentGoalActiveReviewShare":0.4,"minAgentProgressingHeldTimeShare":0.25,"progressingHeldTimeShare":0.5,"substantiveCompletions":3,"substantiveMultiStepAgents":3},"description":{"crowdedGoals":0,"dangerEpisodes":132,"deaths":10,"deferredReviews":4,"discoveryGoals":8,"discoveryReveals":3,"discoveryTransitions":4,"distinctGoalItems":13,"exploringAgents":8,"horizonGoals":6,"ownDropPickups":{"accepted":11}},"descriptive":["distinctGoalItems","exploringAgents","dangerEpisodes"],"measured":{"chainedGoals":1,"craftChainSteps":1,"crowdedGoals":0,"dangerEpisodes":132,"deaths":10,"deferredReviews":4,"discoveredGoals":3,"discoveryGoals":8,"discoveryReveals":3,"discoveryTransitions":4,"distinctGoalItems":13,"exploringAgents":8,"goalActiveReviewShare":0.932584,"horizonGoals":6,"idleBesideExploration":0,"minAgentGoalActiveReviewShare":0.75,"minAgentProgressingHeldTimeShare":0,"noOpRevises":0,"oneStepGoals":14,"progressingHeldTimeShare":0.22339,"readoptedGoals":0,"substantiveCompletions":1,"substantiveMultiStepAgents":2},"schemaShaped":["goalActiveReviewShare","minAgentGoalActiveReviewShare"],"verdict":"notCleared"},"reviseItemChanges":0,"revisesUnchanged":0,"schemaGuarantees":{"alreadyMetConditions":0,"completionsWithoutNext":0,"held":false,"idleSteps":0,"ignoredOwnDrops":0,"meaning":"zero by the request schema; a nonzero count is a defect of the schema or of a backend that bypassed it, not behaviour","metIgnored":2,"reviseItemChanges":0,"revisesUnchanged":0,"stepMismatches":0,"unansweredGoals":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0},"stalled":0,"stepMismatches":0,"stepOnlySteps":10,"unadvancedSteps":38,"unansweredGoals":0,"unevidencedCredits":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0}.
  - `tribe-epsilon:loom003398683d-sense-26` dig: targetRace (race), intentApplied digBlockGone at tick 707.
  - `tribe-gamma:loom18c040d2cc-sense-il` dig: targetRace (race), intentApplied digBlockGone at tick 5506.
  - `tribe-theta:loom9bc6579ca4-sense-jc` moveTo: superseded (race), moved movementCancelled at tick 5839.
  - `tribe-kappa:loomf309750e7b-sense-jr` flee: superseded (race), retreating retreatSuperseded at tick 5899.
  - `tribe-kappa:loomf309750e7b-sense-km` flee: superseded (race), retreating retreatSuperseded at tick 6108.
  - `tribe-alpha:loomaa88da42d7-sense-lr` flee: targetRace (race), intentApplied retreatThreatGone at tick 6374.
  - `tribe-iota:loom8b6535c92c-sense-lr` flee: superseded (race), retreating retreatSuperseded at tick 6504.
  - `tribe-iota:loom8b6535c92c-sense-m0` flee: superseded (race), retreating retreatSuperseded at tick 6546.
  - `tribe-epsilon:loom003398683d-sense-mu` flee: targetRace (race), intentApplied retreatThreatGone at tick 6686.
  - `tribe-eta:loom421b3b32c1-sense-nf` flee: superseded (race), retreating retreatSuperseded at tick 6965.
  - `tribe-iota:loom8b6535c92c-sense-o7` flee: superseded (race), retreating retreatSuperseded at tick 7099.
  - `tribe-delta:loom3b4ef3e082-sense-on` flee: superseded (race), retreating retreatSuperseded at tick 7171.
  - `tribe-iota:loom8b6535c92c-sense-pe` flee: superseded (race), retreating retreatSuperseded at tick 7278.
  - `tribe-iota:loom8b6535c92c-sense-pz` flee: superseded (race), retreating retreatSuperseded at tick 7412.
  - `tribe-theta:loom9bc6579ca4-sense-pb` flee: superseded (race), retreating retreatSuperseded at tick 7453.
  - `tribe-alpha:loomaa88da42d7-sense-pi` moveTo: superseded (race), moved movementCancelled at tick 7457.
  - `tribe-epsilon:loom003398683d-sense-pw` flee: superseded (race), retreating retreatSuperseded at tick 7584.
  - `tribe-delta:loom3b4ef3e082-sense-qq` flee: superseded (race), retreating retreatSuperseded at tick 7640.
  - `tribe-theta:loom9bc6579ca4-sense-q4` flee: superseded (race), retreating retreatSuperseded at tick 7620.
  - `tribe-iota:loom8b6535c92c-sense-qw` flee: superseded (race), retreating retreatSuperseded at tick 7656.
  - `tribe-theta:loom9bc6579ca4-sense-qb` flee: superseded (race), retreating retreatSuperseded at tick 7676.
  - `tribe-epsilon:loom003398683d-sense-ql` flee: superseded (race), retreating retreatSuperseded at tick 7691.
  - `tribe-theta:loom9bc6579ca4-sense-ql` flee: superseded (race), retreating retreatSuperseded at tick 7735.
  - `tribe-eta:loom421b3b32c1-sense-r3` flee: superseded (race), retreating retreatSuperseded at tick 7908.
  - `tribe-eta:loom421b3b32c1-sense-ri` flee: superseded (race), retreating retreatSuperseded at tick 7968.
  - `tribe-gamma:loom18c040d2cc-sense-rg` moveTo: superseded (race), moved movementCancelled at tick 8036.
  - `tribe-delta:loom3b4ef3e082-sense-sx` flee: superseded (race), retreating retreatSuperseded at tick 8170.
  - `tribe-eta:loom421b3b32c1-sense-sp` flee: superseded (race), retreating retreatSuperseded at tick 8212.
  - `tribe-eta:loom421b3b32c1-sense-sy` flee: death (race), intentApplied accepted at tick 8216.
  - `tribe-gamma:loom18c040d2cc-sense-sb` flee: superseded (race), retreating retreatSuperseded at tick 8247.
  - `tribe-kappa:loomf309750e7b-sense-s3` flee: superseded (race), retreating retreatSuperseded at tick 8225.
  - `tribe-gamma:loom18c040d2cc-sense-sc` flee: concurrent (race), intentApplied controlNotReady at tick 8228.
  - `tribe-kappa:loomf309750e7b-sense-s7` flee: superseded (race), retreating retreatSuperseded at tick 8266.
  - `tribe-delta:loom3b4ef3e082-sense-tu` flee: death (race), intentApplied accepted at tick 8314.
  - `tribe-gamma:loom18c040d2cc-sense-tc` flee: death (race), intentApplied controlUnavailable at tick 8347.
  - `tribe-kappa:loomf309750e7b-sense-sy` flee: superseded (race), retreating retreatSuperseded at tick 8416.
  - `tribe-kappa:loomf309750e7b-sense-u0` flee: superseded (race), retreating retreatSuperseded at tick 8615.
  - `tribe-kappa:loomf309750e7b-sense-ub` flee: superseded (race), retreating retreatSuperseded at tick 8695.
  - `tribe-zeta:loom3d8263b30e-sense-u2` flee: superseded (race), retreating retreatSuperseded at tick 8731.
  - `tribe-kappa:loomf309750e7b-sense-v3` flee: superseded (race), retreating retreatSuperseded at tick 8819.
  - `tribe-beta:loomeab7eb8acb-sense-yq` flee: targetRace (race), intentApplied retreatThreatGone at tick 10106.
  - `tribe-alpha:loomaa88da42d7-sense-zv` flee: superseded (race), retreating retreatSuperseded at tick 10490.
  - `tribe-alpha:loomaa88da42d7-sense-10q` flee: superseded (race), retreating retreatSuperseded at tick 10648.
  - `tribe-alpha:loomaa88da42d7-sense-10x` flee: superseded (race), retreating retreatSuperseded at tick 10700.
  - `tribe-alpha:loomaa88da42d7-sense-119` flee: targetRace (race), intentApplied retreatThreatGone at tick 10700.
  - `tribe-alpha:loomaa88da42d7-sense-11n` flee: superseded (race), retreating retreatSuperseded at tick 10826.

## Interpretations

- The run supports the narrower claim that the bounded fleet, native scheduler, cognition bridge, per-kind evidence chain,
  publication, and cleanup worked together under the declared conditions.
- Model-driven actions are observations, not proof of motive or emergence. Any social or spatial interpretation is limited to the recorded evidence and counterevidence.

## Counterevidence and null results

- No agent produced a speech intent.
- No attack intent was applied.
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
