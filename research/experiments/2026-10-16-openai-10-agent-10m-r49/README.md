# 10-agent ten-minute routine-v37 held objective live shakedown

This directory is the sanitized, bounded publication from `2026-10-16-openai-10-agent-10m-r49`.
Every number below is generated from [evidence.jsonl](./evidence.jsonl) through the
committed research publisher; [metrics.json](./metrics.json) is the complete deterministic derivation.

## Question

Can ten independently embodied Loom agents complete the bounded routine-v37 held-objective-follow-up-10m-10 profile using only native event-driven cognition, while preserving complete per-kind perception and lifecycle evidence?
This live-model run observes independently routed agents without assigned roles, pairings, social goals, or a scripted outcome.

## Setup facts

- Source commit: `cc5b28564e00a9f5a63f04eeefab9971926d3041`.
- Paper 1.21.1, build 133, fixed seed `20011`.
- Agents: `tribe-alpha` as `loom_aa88da42d7`, `tribe-beta` as `loom_eab7eb8acb`, `tribe-delta` as `loom_3b4ef3e082`, `tribe-epsilon` as `loom_003398683d`, `tribe-eta` as `loom_421b3b32c1`, `tribe-gamma` as `loom_18c040d2cc`, `tribe-iota` as `loom_8b6535c92c`, `tribe-kappa` as `loom_f309750e7b`, `tribe-theta` as `loom_9bc6579ca4`, `tribe-zeta` as `loom_3d8263b30e`.
- Cognition: `openai`, 378 global calls,
  40 per agent, 6 outstanding.
- External spend: $0.444449620 provider-reported actual,
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

- 63 paired self-position sample(s), the two agents' perceptions taken within 20 ticks of each other, placed two agents within eight blocks
- 209 perception(s) showed another player within eight blocks
- 1 attack intent(s) were applied
- 1 agent(s) died under the server witness: tribe-iota.
- 349 perceptions produced 329 decisions and 329 applied intents.
- No body process was deliberately crashed and no harness `/loombody think` command was issued.
- Native scheduled reviews by agent: {"tribe-alpha":5,"tribe-beta":5,"tribe-delta":5,"tribe-epsilon":5,"tribe-eta":5,"tribe-gamma":5,"tribe-iota":4,"tribe-kappa":5,"tribe-theta":5,"tribe-zeta":5}.
- Server-witnessed deaths: {"tribe-iota":{"attempted":4,"completed":4,"lifeTicks":9271,"maximum":4,"minimum":4,"settledAfterDeath":[],"tick":9370}}; surviving agents continued.
- Per-kind audit samples: 349; complete: true.
- Cleanup verification recorded: true.

## Behavioral gate

- Mechanical pilot verdict: **notApplicable** (loom-primary-behavior-gate/20).
- Criteria: not applicable outside the 180-second pilot profile.
- Meaningful behaviors by agent: {"tribe-alpha":31,"tribe-beta":17,"tribe-delta":33,"tribe-epsilon":30,"tribe-eta":32,"tribe-gamma":29,"tribe-iota":16,"tribe-kappa":18,"tribe-theta":32,"tribe-zeta":20}; typed goal continuity: ["tribe-alpha","tribe-beta","tribe-delta","tribe-epsilon","tribe-eta","tribe-gamma","tribe-iota","tribe-kappa","tribe-theta","tribe-zeta"].
- Non-idle goal-review actions: 298, accepted 289, confirmed 288.
- Calls by agent: {"tribe-alpha":38,"tribe-beta":23,"tribe-delta":38,"tribe-epsilon":38,"tribe-eta":38,"tribe-gamma":38,"tribe-iota":28,"tribe-kappa":24,"tribe-theta":38,"tribe-zeta":26}; peak outstanding 6.
- A pass still needs a reader to confirm: each counted meaningful behavior has an evidence-grounded aim; no counted action is justified only by being active; no cross-agent goal leakage in the typed fields.
- Readiness: **notReady** (loom-readiness-gate/20); decision validity 0.99696, typed terminal accounting 1, server truthfulness 1.
- Actions by class: {"confirmed":294,"goalIncoherent":1,"superseded":3,"targetRace":6,"terrain":3}; blockers: {"goalIncoherent":1}; legitimate races: 12; cognition failures: {}; server disputes: 0.
- Goal coherence: **incoherent**, part of the verdict (mechanically notReady); {"alreadyMetConditions":0,"completionsWithoutNext":0,"conditionSteps":181,"goalsMeasured":81,"idleSteps":0,"ignoredOwnDrops":0,"measuredSteps":257,"metCompleted":43,"metConditions":43,"metIgnored":0,"metRevised":0,"multiStepGoals":47,"pursuit":{"bars":{"chainedGoals":2,"craftChainSteps":1,"goalActiveReviewShare":0.6,"maxIdleBesideExploration":0,"minAgentGoalActiveReviewShare":0.4,"minAgentProgressingHeldTimeShare":0.25,"progressingHeldTimeShare":0.5,"shelterProgressAgentsBeforeNight":3,"substantiveCompletions":3,"substantiveMultiStepAgents":3},"description":{"crowdedGoals":0,"dangerEpisodes":9,"deaths":1,"deferredReviews":1,"discoveryGoals":6,"discoveryReveals":5,"discoveryTransitions":5,"distinctGoalItems":14,"exploringAgents":10,"horizonGoals":21,"ownDropPickups":{"accepted":40,"ownDropNotSensed":2}},"descriptive":["distinctGoalItems","exploringAgents","dangerEpisodes"],"measured":{"chainedGoals":4,"craftChainSteps":8,"crowdedGoals":0,"dangerEpisodes":9,"deaths":1,"deferredReviews":1,"discoveredGoals":7,"discoveryGoals":6,"discoveryReveals":5,"discoveryTransitions":5,"distinctGoalItems":14,"exploringAgents":10,"goalActiveReviewShare":0.83125,"horizonGoals":21,"idleBesideExploration":0,"minAgentGoalActiveReviewShare":0.73913,"minAgentProgressingHeldTimeShare":0.099272,"noOpRevises":0,"oneStepGoals":0,"progressingHeldTimeShare":0.270737,"readoptedGoals":2,"substantiveCompletions":2,"substantiveMultiStepAgents":5},"schemaShaped":["goalActiveReviewShare","minAgentGoalActiveReviewShare"],"verdict":"notCleared"},"reviseItemChanges":0,"revisesUnchanged":0,"schemaGuarantees":{"alreadyMetConditions":0,"completionsWithoutNext":0,"held":false,"idleSteps":0,"ignoredOwnDrops":0,"meaning":"zero by the request schema; a nonzero count is a defect of the schema or of a backend that bypassed it, not behaviour","metIgnored":0,"reviseItemChanges":0,"revisesUnchanged":0,"stepMismatches":0,"unansweredGoals":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":1},"stalled":0,"stepMismatches":0,"stepOnlySteps":26,"unadvancedSteps":50,"unansweredGoals":0,"unevidencedCredits":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":1}.
- Calls the JVM decided before the mind: 10 refused to keep calls in reserve (`callReserved`), 0 sent at the ordinary tier past the pivotal ceiling (`tierFallback`), 0 answered but preempted by newer damage (`dangerPreempted`).
  - `tribe-gamma:loom18c040d2cc-sense-23` collect: targetRace (race), intentApplied collectItemGone at tick 742.
  - `tribe-gamma:loom18c040d2cc-sense-2i` collect: targetRace (race), intentApplied collectItemGone at tick 835.
  - `tribe-epsilon:loom003398683d-sense-2n` dig: targetRace (race), intentApplied digBlockGone at tick 896.
  - `tribe-iota:loom8b6535c92c-sense-2f` collect: targetRace (race), intentApplied collectItemGone at tick 837.
  - `tribe-eta:loom421b3b32c1-sense-4k` moveTo: terrain (race), intentApplied moveNoRoute at tick 1338.
  - `tribe-eta:loom421b3b32c1-sense-ab` moveTo: terrain (race), intentApplied moveNoRoute at tick 3041.
  - `tribe-kappa:loomf309750e7b-sense-ku` place: targetRace (race), intentApplied placeDestinationBlocked at tick 6037.
  - `tribe-zeta:loom3d8263b30e-sense-lf` moveTo: goalIncoherent, intentApplied goalStepUnproductive at tick 6134.
  - `tribe-iota:loom8b6535c92c-sense-v0` attack: targetRace (race), intentApplied attackTargetEscaped at tick 8812.
  - `tribe-iota:loom8b6535c92c-sense-vj` flee: superseded (race), retreating retreatSuperseded at tick 8885.
  - `tribe-iota:loom8b6535c92c-sense-vn` flee: superseded (race), retreating retreatSuperseded at tick 8905.
  - `tribe-iota:loom8b6535c92c-sense-x7` flee: superseded (race), retreating retreatSuperseded at tick 9244.
  - `tribe-zeta:loom3d8263b30e-sense-11o` moveTo: terrain (race), intentApplied moveNoRoute at tick 10728.

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
node research/bin/loom-tribe.js run --config research/config/tribe-held-objective-10m-10.json
```

The harness validates a prospective manifest before launch, builds the three runtime components, verifies the
pinned Paper jar, creates a disposable server, enforces call and lifecycle bounds, publishes only normalized
evidence, validates it again, and removes the mutable run directory.
