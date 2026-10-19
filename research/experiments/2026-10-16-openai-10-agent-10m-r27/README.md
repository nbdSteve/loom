# 10-agent ten-minute routine-v32 survival-discovery live shakedown

This directory is the sanitized, bounded publication from `2026-10-16-openai-10-agent-10m-r27`.
Every number below is generated from [evidence.jsonl](./evidence.jsonl) through the
committed research publisher; [metrics.json](./metrics.json) is the complete deterministic derivation.

## Question

Can ten independently embodied Loom agents complete the bounded routine-v32 survival-envelope-10m-10 profile using only native event-driven cognition, while preserving complete per-kind perception and lifecycle evidence?
This live-model run observes independently routed agents without assigned roles, pairings, social goals, or a scripted outcome.

## Setup facts

- Source commit: `6b2bdcf0e5e42496fbdd3344614bedd857ff2c2a`.
- Paper 1.21.1, build 133, fixed seed `20011`.
- Agents: `tribe-alpha` as `loom_aa88da42d7`, `tribe-beta` as `loom_eab7eb8acb`, `tribe-delta` as `loom_3b4ef3e082`, `tribe-epsilon` as `loom_003398683d`, `tribe-eta` as `loom_421b3b32c1`, `tribe-gamma` as `loom_18c040d2cc`, `tribe-iota` as `loom_8b6535c92c`, `tribe-kappa` as `loom_f309750e7b`, `tribe-theta` as `loom_9bc6579ca4`, `tribe-zeta` as `loom_3d8263b30e`.
- Cognition: `openai`, 378 global calls,
  40 per agent, 6 outstanding.
- External spend: $1.757358700 provider-reported actual,
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

- 31 paired self-position sample(s), the two agents' perceptions taken within 20 ticks of each other, placed two agents within eight blocks
- 100 perception(s) showed another player within eight blocks
- 9 agent(s) died under the server witness: tribe-alpha, tribe-delta, tribe-epsilon, tribe-eta, tribe-gamma, tribe-iota, tribe-kappa, tribe-theta, tribe-zeta.
- 391 perceptions produced 221 decisions and 221 applied intents.
- No body process was deliberately crashed and no harness `/loombody think` command was issued.
- Native scheduled reviews by agent: {"tribe-alpha":3,"tribe-beta":5,"tribe-delta":4,"tribe-epsilon":3,"tribe-eta":3,"tribe-gamma":3,"tribe-iota":3,"tribe-kappa":4,"tribe-theta":5,"tribe-zeta":4}.
- Server-witnessed deaths: {"tribe-alpha":{"attempted":3,"completed":3,"heldAtEnd":1,"lifeTicks":7334,"maximum":4,"minimum":3,"settledAfterDeath":["tribe-alpha:loomaa88da42d7-sense-qa","tribe-alpha:loomaa88da42d7-sense-qp"],"tick":7399},"tribe-delta":{"attempted":4,"completed":4,"heldAtEnd":0,"lifeTicks":7981,"maximum":4,"minimum":3,"settledAfterDeath":["tribe-delta:loom3b4ef3e082-sense-t0"],"tick":8046},"tribe-epsilon":{"attempted":3,"completed":3,"heldAtEnd":0,"lifeTicks":6214,"maximum":3,"minimum":3,"settledAfterDeath":["tribe-epsilon:loom003398683d-sense-mw"],"tick":6279},"tribe-eta":{"attempted":3,"completed":3,"heldAtEnd":0,"lifeTicks":7155,"maximum":3,"minimum":3,"settledAfterDeath":[],"tick":7220},"tribe-gamma":{"attempted":3,"completed":3,"heldAtEnd":0,"lifeTicks":7279,"maximum":4,"minimum":3,"settledAfterDeath":[],"tick":7344},"tribe-iota":{"attempted":3,"completed":3,"heldAtEnd":0,"lifeTicks":6765,"maximum":3,"minimum":3,"settledAfterDeath":["tribe-iota:loom8b6535c92c-sense-nr"],"tick":6830},"tribe-kappa":{"attempted":4,"completed":4,"heldAtEnd":0,"lifeTicks":9622,"maximum":5,"minimum":4,"settledAfterDeath":["tribe-kappa:loomf309750e7b-sense-xz"],"tick":9687},"tribe-theta":{"attempted":5,"completed":5,"heldAtEnd":0,"lifeTicks":10976,"maximum":5,"minimum":5,"settledAfterDeath":["tribe-theta:loom9bc6579ca4-sense-12u"],"tick":11041},"tribe-zeta":{"attempted":4,"completed":4,"heldAtEnd":0,"lifeTicks":10123,"maximum":5,"minimum":4,"settledAfterDeath":["tribe-zeta:loom3d8263b30e-sense-106","tribe-zeta:loom3d8263b30e-sense-zf"],"tick":10188}}; surviving agents continued.
- Per-kind audit samples: 391; complete: true.
- Cleanup verification recorded: true.

## Behavioral gate

- Mechanical pilot verdict: **notApplicable** (loom-primary-behavior-gate/15).
- Criteria: not applicable outside the 180-second pilot profile.
- Meaningful behaviors by agent: {"tribe-alpha":7,"tribe-beta":8,"tribe-delta":8,"tribe-epsilon":12,"tribe-eta":5,"tribe-gamma":7,"tribe-iota":5,"tribe-kappa":7,"tribe-theta":11,"tribe-zeta":11}; typed goal continuity: ["tribe-alpha","tribe-beta","tribe-delta","tribe-epsilon","tribe-eta","tribe-gamma","tribe-iota","tribe-kappa","tribe-theta","tribe-zeta"].
- Non-idle goal-review actions: 96, accepted 94, confirmed 92.
- Calls by agent: {"tribe-alpha":23,"tribe-beta":22,"tribe-delta":30,"tribe-epsilon":19,"tribe-eta":14,"tribe-gamma":18,"tribe-iota":19,"tribe-kappa":28,"tribe-theta":27,"tribe-zeta":26}; peak outstanding 6.
- A pass still needs a reader to confirm: each counted meaningful behavior has an evidence-grounded aim; no counted action is justified only by being active; no cross-agent goal leakage in the typed fields.
- Readiness: **notReady** (loom-readiness-gate/15); decision validity 0.995475, typed terminal accounting 1, server truthfulness 1.
- Actions by class: {"confirmed":179,"death":4,"inapplicable":1,"peerFlight":1,"superseded":22,"targetRace":8,"terrain":6}; blockers: {"inapplicable":1,"peerFlight":1}; legitimate races: 40; cognition failures: {}; server disputes: 0.
- Goal coherence: **incoherent**, part of the verdict (mechanically notReady); {"alreadyMetConditions":0,"completionsWithoutNext":0,"conditionSteps":29,"goalsMeasured":35,"idleSteps":0,"ignoredOwnDrops":0,"measuredSteps":76,"metCompleted":17,"metConditions":17,"metIgnored":0,"metRevised":0,"multiStepGoals":4,"pursuit":{"bars":{"chainedGoals":2,"craftChainSteps":1,"goalActiveReviewShare":0.6,"maxIdleBesideExploration":0,"minAgentGoalActiveReviewShare":0.4,"minAgentProgressingHeldTimeShare":0.25,"progressingHeldTimeShare":0.5,"substantiveCompletions":3,"substantiveMultiStepAgents":3},"description":{"crowdedGoals":0,"dangerEpisodes":125,"deaths":9,"deferredReviews":2,"discoveryGoals":9,"discoveryReveals":3,"discoveryTransitions":3,"distinctGoalItems":13,"exploringAgents":10,"horizonGoals":6,"ownDropPickups":{"accepted":7}},"descriptive":["distinctGoalItems","exploringAgents","dangerEpisodes"],"measured":{"chainedGoals":2,"craftChainSteps":3,"crowdedGoals":0,"dangerEpisodes":125,"deaths":9,"deferredReviews":2,"discoveredGoals":1,"discoveryGoals":9,"discoveryReveals":3,"discoveryTransitions":3,"distinctGoalItems":13,"exploringAgents":10,"goalActiveReviewShare":0.895833,"horizonGoals":6,"idleBesideExploration":0,"minAgentGoalActiveReviewShare":0.777778,"minAgentProgressingHeldTimeShare":0,"noOpRevises":0,"oneStepGoals":19,"progressingHeldTimeShare":0.156336,"readoptedGoals":0,"substantiveCompletions":0,"substantiveMultiStepAgents":0},"schemaShaped":["goalActiveReviewShare","minAgentGoalActiveReviewShare"],"verdict":"notCleared"},"reviseItemChanges":0,"revisesUnchanged":0,"schemaGuarantees":{"alreadyMetConditions":0,"completionsWithoutNext":0,"held":true,"idleSteps":0,"ignoredOwnDrops":0,"meaning":"zero by the request schema; a nonzero count is a defect of the schema or of a backend that bypassed it, not behaviour","metIgnored":0,"reviseItemChanges":0,"revisesUnchanged":0,"stepMismatches":0,"unansweredGoals":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0},"stalled":0,"stepMismatches":0,"stepOnlySteps":7,"unadvancedSteps":40,"unansweredGoals":0,"unevidencedCredits":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0}.
  - `tribe-delta:loom3b4ef3e082-sense-gp` collect: targetRace (race), intentApplied collectItemGone at tick 4874.
  - `tribe-delta:loom3b4ef3e082-sense-ik` flee: peerFlight, retreating retreatSafe at tick 5506.
  - `tribe-theta:loom9bc6579ca4-sense-jr` flee: superseded (race), retreating retreatSuperseded at tick 5922.
  - `tribe-epsilon:loom003398683d-sense-la` moveTo: terrain (race), intentApplied moveNoRoute at tick 6102.
  - `tribe-epsilon:loom003398683d-sense-lp` moveTo: terrain (race), intentApplied moveNoRoute at tick 6151.
  - `tribe-epsilon:loom003398683d-sense-m4` moveTo: terrain (race), intentApplied moveNoRoute at tick 6200.
  - `tribe-epsilon:loom003398683d-sense-mh` moveTo: terrain (race), intentApplied moveNoRoute at tick 6251.
  - `tribe-delta:loom3b4ef3e082-sense-lr` flee: superseded (race), retreating retreatSuperseded at tick 6437.
  - `tribe-iota:loom8b6535c92c-sense-mv` dig: inapplicable, intentApplied digOutOfReach at tick 6630.
  - `tribe-delta:loom3b4ef3e082-sense-n3` flee: superseded (race), retreating retreatSuperseded at tick 6721.
  - `tribe-alpha:loomaa88da42d7-sense-nk` flee: superseded (race), retreating retreatSuperseded at tick 6738.
  - `tribe-delta:loom3b4ef3e082-sense-ne` flee: superseded (race), retreating retreatSuperseded at tick 6779.
  - `tribe-iota:loom8b6535c92c-sense-nr` flee: death (race), intentApplied controlUnavailable at tick 6831.
  - `tribe-beta:loomeab7eb8acb-sense-o7` flee: superseded (race), retreating retreatSuperseded at tick 6955.
  - `tribe-alpha:loomaa88da42d7-sense-oh` flee: superseded (race), retreating retreatSuperseded at tick 6984.
  - `tribe-delta:loom3b4ef3e082-sense-o8` flee: superseded (race), retreating retreatSuperseded at tick 7004.
  - `tribe-beta:loomeab7eb8acb-sense-pe` moveTo: terrain (race), intentApplied moveNoRoute at tick 7150.
  - `tribe-eta:loom421b3b32c1-sense-og` flee: targetRace (race), intentApplied retreatThreatGone at tick 7162.
  - `tribe-delta:loom3b4ef3e082-sense-p9` flee: superseded (race), retreating retreatSuperseded at tick 7211.
  - `tribe-eta:loom421b3b32c1-sense-or` flee: targetRace (race), intentApplied retreatThreatGone at tick 7216.
  - `tribe-gamma:loom18c040d2cc-sense-oy` flee: superseded (race), retreating retreatSuperseded at tick 7281.
  - `tribe-alpha:loomaa88da42d7-sense-pu` flee: targetRace (race), intentApplied retreatThreatGone at tick 7259.
  - `tribe-gamma:loom18c040d2cc-sense-p5` flee: targetRace (race), intentApplied retreatThreatGone at tick 7282.
  - `tribe-alpha:loomaa88da42d7-sense-q1` flee: targetRace (race), intentApplied retreatThreatGone at tick 7300.
  - `tribe-delta:loom3b4ef3e082-sense-pw` flee: targetRace (race), intentApplied retreatThreatGone at tick 7320.
  - `tribe-gamma:loom18c040d2cc-sense-ph` flee: targetRace (race), intentApplied retreatThreatGone at tick 7330.
  - `tribe-alpha:loomaa88da42d7-sense-qa` flee: death (race), intentApplied accepted at tick 7400.
  - `tribe-delta:loom3b4ef3e082-sense-ql` flee: superseded (race), retreating retreatSuperseded at tick 7589.
  - `tribe-beta:loomeab7eb8acb-sense-r4` moveTo: terrain (race), intentApplied moveNoRoute at tick 7598.
  - `tribe-kappa:loomf309750e7b-sense-uj` flee: superseded (race), retreating retreatSuperseded at tick 8936.
  - `tribe-zeta:loom3d8263b30e-sense-vi` flee: superseded (race), retreating retreatSuperseded at tick 9171.
  - `tribe-kappa:loomf309750e7b-sense-vp` flee: superseded (race), retreating retreatSuperseded at tick 9199.
  - `tribe-kappa:loomf309750e7b-sense-vy` flee: superseded (race), retreating retreatSuperseded at tick 9259.
  - `tribe-kappa:loomf309750e7b-sense-xa` flee: superseded (race), retreating retreatSuperseded at tick 9598.
  - `tribe-zeta:loom3d8263b30e-sense-xg` flee: superseded (race), retreating retreatSuperseded at tick 9756.
  - `tribe-kappa:loomf309750e7b-sense-xz` flee: death (race), intentApplied retreatThreatGone at tick 9688.
  - `tribe-zeta:loom3d8263b30e-sense-y7` flee: superseded (race), retreating retreatSuperseded at tick 9915.
  - `tribe-zeta:loom3d8263b30e-sense-z4` flee: superseded (race), retreating retreatSuperseded at tick 10102.
  - `tribe-zeta:loom3d8263b30e-sense-zf` flee: death (race), intentApplied accepted at tick 10189.
  - `tribe-theta:loom9bc6579ca4-sense-z1` flee: superseded (race), retreating retreatSuperseded at tick 10251.
  - `tribe-theta:loom9bc6579ca4-sense-10n` flee: superseded (race), retreating retreatSuperseded at tick 10666.
  - `tribe-theta:loom9bc6579ca4-sense-11w` flee: superseded (race), retreating retreatSuperseded at tick 10932.

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
