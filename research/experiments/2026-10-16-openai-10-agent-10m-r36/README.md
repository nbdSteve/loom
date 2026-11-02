# 10-agent ten-minute routine-v34 balanced follow-up live shakedown

This directory is the sanitized, bounded publication from `2026-10-16-openai-10-agent-10m-r36`.
Every number below is generated from [evidence.jsonl](./evidence.jsonl) through the
committed research publisher; [metrics.json](./metrics.json) is the complete deterministic derivation.

## Question

Can ten independently embodied Loom agents complete the bounded routine-v34 balanced-follow-up-10m-10 profile using only native event-driven cognition, while preserving complete per-kind perception and lifecycle evidence?
This live-model run observes independently routed agents without assigned roles, pairings, social goals, or a scripted outcome.

## Setup facts

- Source commit: `61c0f3f11ecb8ede4847bc87f742215d77d3d9b1`.
- Paper 1.21.1, build 133, fixed seed `20011`.
- Agents: `tribe-alpha` as `loom_aa88da42d7`, `tribe-beta` as `loom_eab7eb8acb`, `tribe-delta` as `loom_3b4ef3e082`, `tribe-epsilon` as `loom_003398683d`, `tribe-eta` as `loom_421b3b32c1`, `tribe-gamma` as `loom_18c040d2cc`, `tribe-iota` as `loom_8b6535c92c`, `tribe-kappa` as `loom_f309750e7b`, `tribe-theta` as `loom_9bc6579ca4`, `tribe-zeta` as `loom_3d8263b30e`.
- Cognition: `openai`, 378 global calls,
  40 per agent, 6 outstanding.
- External spend: $2.045245640 provider-reported actual,
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

- 40 paired self-position sample(s), the two agents' perceptions taken within 20 ticks of each other, placed two agents within eight blocks
- 111 perception(s) showed another player within eight blocks
- 10 agent(s) died under the server witness: tribe-alpha, tribe-beta, tribe-delta, tribe-epsilon, tribe-eta, tribe-gamma, tribe-iota, tribe-kappa, tribe-theta, tribe-zeta.
- 475 perceptions produced 273 decisions and 273 applied intents.
- No body process was deliberately crashed and no harness `/loombody think` command was issued.
- Native scheduled reviews by agent: {"tribe-alpha":5,"tribe-beta":5,"tribe-delta":4,"tribe-epsilon":4,"tribe-eta":5,"tribe-gamma":4,"tribe-iota":5,"tribe-kappa":5,"tribe-theta":5,"tribe-zeta":5}.
- Server-witnessed deaths: {"tribe-alpha":{"attempted":5,"completed":5,"heldAtEnd":0,"lifeTicks":10864,"maximum":5,"minimum":5,"settledAfterDeath":["tribe-alpha:loomaa88da42d7-sense-135"],"tick":10945},"tribe-beta":{"attempted":5,"completed":5,"heldAtEnd":0,"lifeTicks":10044,"maximum":5,"minimum":4,"settledAfterDeath":["tribe-beta:loomeab7eb8acb-sense-zj","tribe-beta:loomeab7eb8acb-sense-zn"],"tick":10125},"tribe-delta":{"attempted":4,"completed":4,"heldAtEnd":0,"lifeTicks":9255,"maximum":4,"minimum":4,"settledAfterDeath":["tribe-delta:loom3b4ef3e082-sense-wr","tribe-delta:loom3b4ef3e082-sense-wv"],"tick":9336},"tribe-epsilon":{"attempted":4,"completed":4,"heldAtEnd":0,"lifeTicks":9425,"maximum":4,"minimum":4,"settledAfterDeath":["tribe-epsilon:loom003398683d-sense-wq"],"tick":9506},"tribe-eta":{"attempted":5,"completed":5,"heldAtEnd":0,"lifeTicks":11753,"maximum":5,"minimum":5,"settledAfterDeath":["tribe-eta:loom421b3b32c1-sense-15o"],"tick":11834},"tribe-gamma":{"attempted":4,"completed":4,"heldAtEnd":0,"lifeTicks":9980,"maximum":5,"minimum":4,"settledAfterDeath":["tribe-gamma:loom18c040d2cc-sense-y8"],"tick":10061},"tribe-iota":{"attempted":5,"completed":5,"heldAtEnd":0,"lifeTicks":10604,"maximum":5,"minimum":4,"settledAfterDeath":["tribe-iota:loom8b6535c92c-sense-11p"],"tick":10685},"tribe-kappa":{"attempted":5,"completed":5,"heldAtEnd":0,"lifeTicks":11232,"maximum":5,"minimum":5,"settledAfterDeath":["tribe-kappa:loomf309750e7b-sense-12w"],"tick":11313},"tribe-theta":{"attempted":5,"completed":5,"heldAtEnd":0,"lifeTicks":11574,"maximum":5,"minimum":5,"settledAfterDeath":["tribe-theta:loom9bc6579ca4-sense-14m"],"tick":11655},"tribe-zeta":{"attempted":5,"completed":5,"heldAtEnd":0,"lifeTicks":11435,"maximum":5,"minimum":5,"settledAfterDeath":[],"tick":11516}}; surviving agents continued.
- Per-kind audit samples: 475; complete: true.
- Cleanup verification recorded: true.

## Behavioral gate

- Mechanical pilot verdict: **notApplicable** (loom-primary-behavior-gate/18).
- Criteria: not applicable outside the 180-second pilot profile.
- Meaningful behaviors by agent: {"tribe-alpha":20,"tribe-beta":11,"tribe-delta":8,"tribe-epsilon":7,"tribe-eta":17,"tribe-gamma":8,"tribe-iota":6,"tribe-kappa":6,"tribe-theta":11,"tribe-zeta":10}; typed goal continuity: ["tribe-alpha","tribe-beta","tribe-delta","tribe-epsilon","tribe-eta","tribe-gamma","tribe-iota","tribe-kappa","tribe-theta","tribe-zeta"].
- Non-idle goal-review actions: 130, accepted 124, confirmed 117.
- Calls by agent: {"tribe-alpha":34,"tribe-beta":23,"tribe-delta":30,"tribe-epsilon":14,"tribe-eta":39,"tribe-gamma":18,"tribe-iota":27,"tribe-kappa":30,"tribe-theta":28,"tribe-zeta":36}; peak outstanding 6.
- A pass still needs a reader to confirm: each counted meaningful behavior has an evidence-grounded aim; no counted action is justified only by being active; no cross-agent goal leakage in the typed fields.
- Readiness: **notReady** (loom-readiness-gate/18); decision validity 1, typed terminal accounting 1, server truthfulness 1.
- Actions by class: {"confirmed":208,"death":6,"protocolError":1,"superseded":55,"targetRace":3,"terrain":1}; blockers: {"protocolError":1}; legitimate races: 65; cognition failures: {}; server disputes: 0.
- Goal coherence: **incoherent**, part of the verdict (mechanically notReady); {"alreadyMetConditions":0,"completionsWithoutNext":0,"conditionSteps":44,"goalsMeasured":48,"idleSteps":0,"ignoredOwnDrops":0,"measuredSteps":105,"metCompleted":25,"metConditions":26,"metDeferred":1,"metIgnored":0,"metRevised":0,"multiStepGoals":7,"pursuit":{"bars":{"chainedGoals":2,"craftChainSteps":1,"goalActiveReviewShare":0.6,"maxIdleBesideExploration":0,"minAgentGoalActiveReviewShare":0.4,"minAgentProgressingHeldTimeShare":0.25,"progressingHeldTimeShare":0.5,"substantiveCompletions":3,"substantiveMultiStepAgents":3},"description":{"crowdedGoals":0,"dangerEpisodes":142,"deaths":10,"deferredReviews":5,"discoveryGoals":9,"discoveryReveals":3,"discoveryTransitions":3,"distinctGoalItems":16,"exploringAgents":10,"horizonGoals":15,"ownDropPickups":{"accepted":18}},"descriptive":["distinctGoalItems","exploringAgents","dangerEpisodes"],"measured":{"chainedGoals":10,"craftChainSteps":11,"crowdedGoals":0,"dangerEpisodes":142,"deaths":10,"deferredReviews":5,"discoveredGoals":8,"discoveryGoals":9,"discoveryReveals":3,"discoveryTransitions":3,"distinctGoalItems":16,"exploringAgents":10,"goalActiveReviewShare":0.900763,"horizonGoals":15,"idleBesideExploration":0,"minAgentGoalActiveReviewShare":0.7,"minAgentProgressingHeldTimeShare":0,"noOpRevises":0,"oneStepGoals":16,"progressingHeldTimeShare":0.153628,"readoptedGoals":1,"substantiveCompletions":3,"substantiveMultiStepAgents":3},"schemaShaped":["goalActiveReviewShare","minAgentGoalActiveReviewShare"],"verdict":"notCleared"},"reviseItemChanges":0,"revisesUnchanged":0,"schemaGuarantees":{"alreadyMetConditions":0,"completionsWithoutNext":0,"held":true,"idleSteps":0,"ignoredOwnDrops":0,"meaning":"zero by the request schema; a nonzero count is a defect of the schema or of a backend that bypassed it, not behaviour","metIgnored":0,"reviseItemChanges":0,"revisesUnchanged":0,"stepMismatches":0,"unansweredGoals":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0},"stalled":0,"stepMismatches":0,"stepOnlySteps":15,"unadvancedSteps":46,"unansweredGoals":0,"unevidencedCredits":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0}.
- Calls the JVM decided before the mind: 0 refused to keep calls in reserve (`callReserved`), 0 sent at the ordinary tier past the pivotal ceiling (`tierFallback`), 1 answered but preempted by newer damage (`dangerPreempted`).
  - `tribe-epsilon:loom003398683d-sense-26` collect: targetRace (race), intentApplied collectItemGone at tick 719.
  - `tribe-gamma:loom18c040d2cc-sense-23` collect: targetRace (race), intentApplied collectItemGone at tick 720.
  - `tribe-kappa:loomf309750e7b-sense-2t` collect: targetRace (race), intentApplied collectItemGone at tick 917.
  - `tribe-iota:loom8b6535c92c-sense-jv` moveTo: terrain (race), intentApplied moveNoRoute at tick 5819.
  - `tribe-zeta:loom3d8263b30e-sense-kr` dig: protocolError, intentApplied digLibraryFailure at tick 6519.
  - `tribe-delta:loom3b4ef3e082-sense-qy` moveTo: superseded (race), moved movementCancelled at tick 7951.
  - `tribe-delta:loom3b4ef3e082-sense-s7` flee: superseded (race), retreating retreatSuperseded at tick 8226.
  - `tribe-delta:loom3b4ef3e082-sense-s5` flee: superseded (race), retreating retreatSuperseded at tick 8271.
  - `tribe-zeta:loom3d8263b30e-sense-s8` flee: superseded (race), retreating retreatSuperseded at tick 8334.
  - `tribe-delta:loom3b4ef3e082-sense-t0` flee: superseded (race), retreating retreatSuperseded at tick 8469.
  - `tribe-zeta:loom3d8263b30e-sense-sz` flee: superseded (race), retreating retreatSuperseded at tick 8551.
  - `tribe-delta:loom3b4ef3e082-sense-tm` flee: superseded (race), retreating retreatSuperseded at tick 8603.
  - `tribe-delta:loom3b4ef3e082-sense-uc` flee: superseded (race), retreating retreatSuperseded at tick 8776.
  - `tribe-delta:loom3b4ef3e082-sense-um` flee: superseded (race), retreating retreatSuperseded at tick 8805.
  - `tribe-kappa:loomf309750e7b-sense-u8` flee: superseded (race), retreating retreatSuperseded at tick 9012.
  - `tribe-delta:loom3b4ef3e082-sense-vh` flee: superseded (race), retreating retreatSuperseded at tick 9018.
  - `tribe-delta:loom3b4ef3e082-sense-vl` flee: superseded (race), retreating retreatSuperseded at tick 9117.
  - `tribe-gamma:loom18c040d2cc-sense-uz` flee: superseded (race), retreating retreatSuperseded at tick 9207.
  - `tribe-delta:loom3b4ef3e082-sense-vz` flee: superseded (race), retreating retreatSuperseded at tick 9183.
  - `tribe-kappa:loomf309750e7b-sense-uy` flee: superseded (race), retreating retreatSuperseded at tick 9172.
  - `tribe-iota:loom8b6535c92c-sense-v5` flee: superseded (race), retreating retreatSuperseded at tick 9196.
  - `tribe-eta:loom421b3b32c1-sense-w3` flee: superseded (race), retreating retreatSuperseded at tick 9387.
  - `tribe-delta:loom3b4ef3e082-sense-wr` flee: death (race), intentApplied accepted at tick 9337.
  - `tribe-epsilon:loom003398683d-sense-w2` flee: superseded (race), retreating retreatSuperseded at tick 9414.
  - `tribe-iota:loom8b6535c92c-sense-we` flee: superseded (race), retreating retreatSuperseded at tick 9546.
  - `tribe-kappa:loomf309750e7b-sense-wg` flee: superseded (race), retreating retreatSuperseded at tick 9608.
  - `tribe-eta:loom421b3b32c1-sense-xi` flee: superseded (race), retreating retreatSuperseded at tick 9683.
  - `tribe-beta:loomeab7eb8acb-sense-y0` flee: superseded (race), retreating retreatSuperseded at tick 9816.
  - `tribe-iota:loom8b6535c92c-sense-xr` flee: superseded (race), retreating retreatSuperseded at tick 9864.
  - `tribe-kappa:loomf309750e7b-sense-xp` flee: superseded (race), retreating retreatSuperseded at tick 9954.
  - `tribe-zeta:loom3d8263b30e-sense-y1` flee: superseded (race), retreating retreatSuperseded at tick 9966.
  - `tribe-alpha:loomaa88da42d7-sense-yz` flee: superseded (race), retreating retreatSuperseded at tick 9975.
  - `tribe-beta:loomeab7eb8acb-sense-yu` flee: superseded (race), retreating retreatSuperseded at tick 10003.
  - `tribe-beta:loomeab7eb8acb-sense-z1` flee: superseded (race), retreating retreatSuperseded at tick 10009.
  - `tribe-gamma:loom18c040d2cc-sense-y8` flee: death (race), intentApplied controlUnavailable at tick 10062.
  - `tribe-alpha:loomaa88da42d7-sense-zn` flee: superseded (race), retreating retreatSuperseded at tick 10166.
  - `tribe-eta:loom421b3b32c1-sense-za` flee: superseded (race), retreating retreatSuperseded at tick 10175.
  - `tribe-iota:loom8b6535c92c-sense-za` flee: superseded (race), retreating retreatSuperseded at tick 10205.
  - `tribe-iota:loom8b6535c92c-sense-zh` flee: superseded (race), retreating retreatSuperseded at tick 10224.
  - `tribe-zeta:loom3d8263b30e-sense-zj` flee: superseded (race), retreating retreatSuperseded at tick 10417.
  - `tribe-alpha:loomaa88da42d7-sense-10h` moveTo: superseded (race), moved movementCancelled at tick 10408.
  - `tribe-eta:loom421b3b32c1-sense-10h` flee: superseded (race), retreating retreatSuperseded at tick 10449.
  - `tribe-iota:loom8b6535c92c-sense-10f` flee: superseded (race), retreating retreatSuperseded at tick 10441.
  - `tribe-iota:loom8b6535c92c-sense-10j` flee: superseded (race), retreating retreatSuperseded at tick 10477.
  - `tribe-iota:loom8b6535c92c-sense-10n` flee: superseded (race), retreating retreatSuperseded at tick 10532.
  - `tribe-zeta:loom3d8263b30e-sense-10g` flee: superseded (race), retreating retreatSuperseded at tick 10582.
  - `tribe-alpha:loomaa88da42d7-sense-12a` flee: superseded (race), retreating retreatSuperseded at tick 10757.
  - `tribe-theta:loom9bc6579ca4-sense-11b` flee: superseded (race), retreating retreatSuperseded at tick 10868.
  - `tribe-alpha:loomaa88da42d7-sense-12w` flee: superseded (race), retreating retreatSuperseded at tick 10935.
  - `tribe-alpha:loomaa88da42d7-sense-135` flee: death (race), intentApplied accepted at tick 10946.
  - `tribe-theta:loom9bc6579ca4-sense-127` flee: superseded (race), intentApplied retreatStale at tick 11030.
  - `tribe-theta:loom9bc6579ca4-sense-128` flee: superseded (race), retreating retreatSuperseded at tick 11113.
  - `tribe-zeta:loom3d8263b30e-sense-12s` flee: superseded (race), retreating retreatSuperseded at tick 11190.
  - `tribe-kappa:loomf309750e7b-sense-127` flee: superseded (race), retreating retreatSuperseded at tick 11211.
  - `tribe-kappa:loomf309750e7b-sense-12i` flee: superseded (race), retreating retreatSuperseded at tick 11218.
  - `tribe-kappa:loomf309750e7b-sense-12k` flee: superseded (race), retreating retreatSuperseded at tick 11288.
  - `tribe-zeta:loom3d8263b30e-sense-13e` flee: superseded (race), retreating retreatSuperseded at tick 11334.
  - `tribe-kappa:loomf309750e7b-sense-12w` flee: death (race), intentApplied accepted at tick 11314.
  - `tribe-eta:loom421b3b32c1-sense-140` flee: superseded (race), retreating retreatSuperseded at tick 11415.
  - `tribe-theta:loom9bc6579ca4-sense-13t` flee: superseded (race), retreating retreatSuperseded at tick 11573.
  - `tribe-theta:loom9bc6579ca4-sense-144` flee: superseded (race), retreating retreatSuperseded at tick 11585.
  - `tribe-theta:loom9bc6579ca4-sense-14a` flee: superseded (race), retreating retreatSuperseded at tick 11647.
  - `tribe-theta:loom9bc6579ca4-sense-14m` flee: death (race), intentApplied accepted at tick 11656.
  - `tribe-eta:loom421b3b32c1-sense-15f` flee: superseded (race), retreating retreatSuperseded at tick 11809.
  - `tribe-eta:loom421b3b32c1-sense-15o` flee: death (race), intentApplied accepted at tick 11835.
  - `tribe-gamma:loom18c040d2cc-sense-y6` flee: superseded (race), decisionDiscarded dangerPreempted at tick 10031.

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
node research/bin/loom-tribe.js run --config research/config/tribe-balanced-follow-up-10m-10.json
```

The harness validates a prospective manifest before launch, builds the three runtime components, verifies the
pinned Paper jar, creates a disposable server, enforces call and lifecycle bounds, publishes only normalized
evidence, validates it again, and removes the mutable run directory.
