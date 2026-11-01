# 10-agent ten-minute routine-v36 held objective live shakedown

This directory is the sanitized, bounded publication from `2026-10-16-openai-10-agent-10m-r45`.
Every number below is generated from [evidence.jsonl](./evidence.jsonl) through the
committed research publisher; [metrics.json](./metrics.json) is the complete deterministic derivation.

## Question

Can ten independently embodied Loom agents complete the bounded routine-v36 held-objective-follow-up-10m-10 profile using only native event-driven cognition, while preserving complete per-kind perception and lifecycle evidence?
This live-model run observes independently routed agents without assigned roles, pairings, social goals, or a scripted outcome.

## Setup facts

- Source commit: `1828f96aac97da43e6105fb9155aacb30b38bd9e`.
- Paper 1.21.1, build 133, fixed seed `20011`.
- Agents: `tribe-alpha` as `loom_aa88da42d7`, `tribe-beta` as `loom_eab7eb8acb`, `tribe-delta` as `loom_3b4ef3e082`, `tribe-epsilon` as `loom_003398683d`, `tribe-eta` as `loom_421b3b32c1`, `tribe-gamma` as `loom_18c040d2cc`, `tribe-iota` as `loom_8b6535c92c`, `tribe-kappa` as `loom_f309750e7b`, `tribe-theta` as `loom_9bc6579ca4`, `tribe-zeta` as `loom_3d8263b30e`.
- Cognition: `openai`, 378 global calls,
  40 per agent, 6 outstanding.
- External spend: $0.393923090 provider-reported actual,
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

- 59 paired self-position sample(s), the two agents' perceptions taken within 20 ticks of each other, placed two agents within eight blocks
- 236 perception(s) showed another player within eight blocks
- 1 agent(s) died under the server witness: tribe-zeta.
- 376 perceptions produced 353 decisions and 353 applied intents.
- No body process was deliberately crashed and no harness `/loombody think` command was issued.
- Native scheduled reviews by agent: {"tribe-alpha":5,"tribe-beta":5,"tribe-delta":5,"tribe-epsilon":5,"tribe-eta":5,"tribe-gamma":5,"tribe-iota":5,"tribe-kappa":5,"tribe-theta":5,"tribe-zeta":4}.
- Server-witnessed deaths: {"tribe-zeta":{"attempted":4,"completed":4,"lifeTicks":9198,"maximum":4,"minimum":4,"settledAfterDeath":["tribe-zeta:loom3d8263b30e-sense-ye"],"tick":9270}}; surviving agents continued.
- Per-kind audit samples: 376; complete: true.
- Cleanup verification recorded: true.

## Behavioral gate

- Mechanical pilot verdict: **notApplicable** (loom-primary-behavior-gate/20).
- Criteria: not applicable outside the 180-second pilot profile.
- Meaningful behaviors by agent: {"tribe-alpha":29,"tribe-beta":34,"tribe-delta":16,"tribe-epsilon":30,"tribe-eta":31,"tribe-gamma":34,"tribe-iota":32,"tribe-kappa":31,"tribe-theta":21,"tribe-zeta":28}; typed goal continuity: ["tribe-alpha","tribe-beta","tribe-delta","tribe-epsilon","tribe-eta","tribe-gamma","tribe-iota","tribe-kappa","tribe-theta","tribe-zeta"].
- Non-idle goal-review actions: 328, accepted 316, confirmed 316.
- Calls by agent: {"tribe-alpha":38,"tribe-beta":38,"tribe-delta":24,"tribe-epsilon":38,"tribe-eta":38,"tribe-gamma":38,"tribe-iota":38,"tribe-kappa":38,"tribe-theta":28,"tribe-zeta":39}; peak outstanding 6.
- A pass still needs a reader to confirm: each counted meaningful behavior has an evidence-grounded aim; no counted action is justified only by being active; no cross-agent goal leakage in the typed fields.
- Readiness: **notReady** (loom-readiness-gate/20); decision validity 1, typed terminal accounting 1, server truthfulness 1.
- Actions by class: {"confirmed":319,"superseded":4,"targetRace":7,"terrain":6}; blockers: none; legitimate races: 17; cognition failures: {}; server disputes: 0.
- Goal coherence: **incoherent**, part of the verdict (mechanically ready); {"alreadyMetConditions":0,"completionsWithoutNext":0,"conditionSteps":189,"goalsMeasured":88,"idleSteps":0,"ignoredOwnDrops":0,"measuredSteps":287,"metCompleted":46,"metConditions":46,"metIgnored":0,"metRevised":0,"multiStepGoals":48,"pursuit":{"bars":{"chainedGoals":2,"craftChainSteps":1,"goalActiveReviewShare":0.6,"maxIdleBesideExploration":0,"minAgentGoalActiveReviewShare":0.4,"minAgentProgressingHeldTimeShare":0.25,"progressingHeldTimeShare":0.5,"shelterProgressAgentsBeforeNight":3,"substantiveCompletions":3,"substantiveMultiStepAgents":3},"description":{"crowdedGoals":0,"dangerEpisodes":5,"deaths":1,"deferredReviews":0,"discoveryGoals":8,"discoveryReveals":3,"discoveryTransitions":4,"distinctGoalItems":14,"exploringAgents":8,"horizonGoals":21,"ownDropPickups":{"accepted":40,"ownDropNotSensed":3,"ownDropUnreachable":1}},"descriptive":["distinctGoalItems","exploringAgents","dangerEpisodes"],"measured":{"chainedGoals":2,"craftChainSteps":6,"crowdedGoals":0,"dangerEpisodes":5,"deaths":1,"deferredReviews":0,"discoveredGoals":6,"discoveryGoals":8,"discoveryReveals":3,"discoveryTransitions":4,"distinctGoalItems":14,"exploringAgents":8,"goalActiveReviewShare":0.850575,"horizonGoals":21,"idleBesideExploration":0,"minAgentGoalActiveReviewShare":0.666667,"minAgentProgressingHeldTimeShare":0.035629,"noOpRevises":0,"oneStepGoals":0,"progressingHeldTimeShare":0.387526,"readoptedGoals":5,"substantiveCompletions":2,"substantiveMultiStepAgents":3},"schemaShaped":["goalActiveReviewShare","minAgentGoalActiveReviewShare"],"verdict":"notCleared"},"reviseItemChanges":0,"revisesUnchanged":0,"schemaGuarantees":{"alreadyMetConditions":0,"completionsWithoutNext":0,"held":true,"idleSteps":0,"ignoredOwnDrops":0,"meaning":"zero by the request schema; a nonzero count is a defect of the schema or of a backend that bypassed it, not behaviour","metIgnored":0,"reviseItemChanges":0,"revisesUnchanged":0,"stepMismatches":0,"unansweredGoals":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0},"stalled":0,"stepMismatches":0,"stepOnlySteps":34,"unadvancedSteps":64,"unansweredGoals":0,"unevidencedCredits":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0}.
- Calls the JVM decided before the mind: 12 refused to keep calls in reserve (`callReserved`), 0 sent at the ordinary tier past the pivotal ceiling (`tierFallback`), 3 answered but preempted by newer damage (`dangerPreempted`).
  - `tribe-epsilon:loom003398683d-sense-28` dig: targetRace (race), intentApplied digBlockGone at tick 710.
  - `tribe-epsilon:loom003398683d-sense-2n` dig: targetRace (race), intentApplied digBlockGone at tick 868.
  - `tribe-eta:loom421b3b32c1-sense-3f` dig: targetRace (race), intentApplied digBlockGone at tick 1070.
  - `tribe-alpha:loomaa88da42d7-sense-d2` collect: targetRace (race), intentApplied collectItemGone at tick 3507.
  - `tribe-theta:loom9bc6579ca4-sense-bz` collect: targetRace (race), intentApplied collectItemGone at tick 3508.
  - `tribe-alpha:loomaa88da42d7-sense-mr` moveTo: terrain (race), intentApplied moveNoRoute at tick 6107.
  - `tribe-iota:loom8b6535c92c-sense-lr` moveTo: terrain (race), intentApplied moveNoRoute at tick 6213.
  - `tribe-zeta:loom3d8263b30e-sense-oh` place: targetRace (race), intentApplied placeSlotChanged at tick 6838.
  - `tribe-zeta:loom3d8263b30e-sense-pk` place: targetRace (race), intentApplied placeSlotChanged at tick 7025.
  - `tribe-alpha:loomaa88da42d7-sense-sw` moveTo: terrain (race), intentApplied moveNoRoute at tick 7469.
  - `tribe-alpha:loomaa88da42d7-sense-t3` moveTo: terrain (race), intentApplied moveNoRoute at tick 7524.
  - `tribe-epsilon:loom003398683d-sense-rv` dig: superseded (race), intentApplied digCancelled at tick 7841.
  - `tribe-alpha:loomaa88da42d7-sense-10w` moveTo: terrain (race), intentApplied moveNoRoute at tick 9813.
  - `tribe-epsilon:loom003398683d-sense-10s` collect: terrain (race), intentApplied collectNoRoute at tick 10214.
  - `tribe-zeta:loom3d8263b30e-sense-xw` dig: superseded (race), decisionDiscarded dangerPreempted at tick 9210.
  - `tribe-zeta:loom3d8263b30e-sense-y2` dig: superseded (race), decisionDiscarded dangerPreempted at tick 9247.
  - `tribe-zeta:loom3d8263b30e-sense-y6` dig: superseded (race), decisionDiscarded dangerPreempted at tick 9260.

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
node research/bin/loom-tribe.js run --config research/config/tribe-held-objective-10m-10.json
```

The harness validates a prospective manifest before launch, builds the three runtime components, verifies the
pinned Paper jar, creates a disposable server, enforces call and lifecycle bounds, publishes only normalized
evidence, validates it again, and removes the mutable run directory.
