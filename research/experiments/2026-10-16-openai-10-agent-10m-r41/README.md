# 10-agent ten-minute routine-v36 held objective live shakedown

This directory is the sanitized, bounded publication from `2026-10-16-openai-10-agent-10m-r41`.
Every number below is generated from [evidence.jsonl](./evidence.jsonl) through the
committed research publisher; [metrics.json](./metrics.json) is the complete deterministic derivation.

## Question

Can ten independently embodied Loom agents complete the bounded routine-v36 held-objective-10m-10 profile using only native event-driven cognition, while preserving complete per-kind perception and lifecycle evidence?
This live-model run observes independently routed agents without assigned roles, pairings, social goals, or a scripted outcome.

## Setup facts

- Source commit: `c8814407bd2eed510979d755b1643959b8460b00`.
- Paper 1.21.1, build 133, fixed seed `20011`.
- Agents: `tribe-alpha` as `loom_aa88da42d7`, `tribe-beta` as `loom_eab7eb8acb`, `tribe-delta` as `loom_3b4ef3e082`, `tribe-epsilon` as `loom_003398683d`, `tribe-eta` as `loom_421b3b32c1`, `tribe-gamma` as `loom_18c040d2cc`, `tribe-iota` as `loom_8b6535c92c`, `tribe-kappa` as `loom_f309750e7b`, `tribe-theta` as `loom_9bc6579ca4`, `tribe-zeta` as `loom_3d8263b30e`.
- Cognition: `openai`, 378 global calls,
  40 per agent, 6 outstanding.
- External spend: $0.351533220 provider-reported actual,
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

- 23 paired self-position sample(s), the two agents' perceptions taken within 20 ticks of each other, placed two agents within eight blocks
- 107 perception(s) showed another player within eight blocks
- 1 agent(s) died under the server witness: tribe-alpha.
- 373 perceptions produced 347 decisions and 347 applied intents.
- No body process was deliberately crashed and no harness `/loombody think` command was issued.
- Native scheduled reviews by agent: {"tribe-alpha":5,"tribe-beta":5,"tribe-delta":5,"tribe-epsilon":5,"tribe-eta":5,"tribe-gamma":5,"tribe-iota":5,"tribe-kappa":5,"tribe-theta":5,"tribe-zeta":5}.
- Server-witnessed deaths: {"tribe-alpha":{"attempted":5,"completed":5,"lifeTicks":11991,"maximum":5,"minimum":5,"settledAfterDeath":[],"tick":12066}}; surviving agents continued.
- Per-kind audit samples: 373; complete: true.
- Cleanup verification recorded: true.

## Behavioral gate

- Mechanical pilot verdict: **notApplicable** (loom-primary-behavior-gate/20).
- Criteria: not applicable outside the 180-second pilot profile.
- Meaningful behaviors by agent: {"tribe-alpha":31,"tribe-beta":22,"tribe-delta":35,"tribe-epsilon":21,"tribe-eta":30,"tribe-gamma":31,"tribe-iota":26,"tribe-kappa":30,"tribe-theta":37,"tribe-zeta":33}; typed goal continuity: ["tribe-alpha","tribe-beta","tribe-delta","tribe-epsilon","tribe-eta","tribe-gamma","tribe-iota","tribe-kappa","tribe-theta","tribe-zeta"].
- Non-idle goal-review actions: 312, accepted 307, confirmed 306.
- Calls by agent: {"tribe-alpha":40,"tribe-beta":27,"tribe-delta":38,"tribe-epsilon":30,"tribe-eta":33,"tribe-gamma":38,"tribe-iota":27,"tribe-kappa":38,"tribe-theta":38,"tribe-zeta":38}; peak outstanding 6.
- A pass still needs a reader to confirm: each counted meaningful behavior has an evidence-grounded aim; no counted action is justified only by being active; no cross-agent goal leakage in the typed fields.
- Readiness: **notReady** (loom-readiness-gate/20); decision validity 1, typed terminal accounting 1, server truthfulness 1.
- Actions by class: {"confirmed":310,"superseded":1,"targetRace":4,"terrain":1}; blockers: none; legitimate races: 6; cognition failures: {}; server disputes: 0.
- Goal coherence: **incoherent**, part of the verdict (mechanically ready); {"alreadyMetConditions":0,"completionsWithoutNext":0,"conditionSteps":220,"goalsMeasured":84,"idleSteps":0,"ignoredOwnDrops":0,"measuredSteps":291,"metCompleted":40,"metConditions":50,"metIgnored":0,"metRevised":10,"multiStepGoals":37,"pursuit":{"bars":{"chainedGoals":2,"craftChainSteps":1,"goalActiveReviewShare":0.6,"maxIdleBesideExploration":0,"minAgentGoalActiveReviewShare":0.4,"minAgentProgressingHeldTimeShare":0.25,"progressingHeldTimeShare":0.5,"shelterProgressAgentsBeforeNight":3,"substantiveCompletions":3,"substantiveMultiStepAgents":3},"description":{"crowdedGoals":0,"dangerEpisodes":4,"deaths":1,"deferredReviews":0,"discoveryGoals":11,"discoveryReveals":4,"discoveryTransitions":6,"distinctGoalItems":9,"exploringAgents":9,"horizonGoals":15,"ownDropPickups":{"accepted":37,"ownDropNotSensed":5}},"descriptive":["distinctGoalItems","exploringAgents","dangerEpisodes"],"measured":{"chainedGoals":0,"craftChainSteps":1,"crowdedGoals":0,"dangerEpisodes":4,"deaths":1,"deferredReviews":0,"discoveredGoals":3,"discoveryGoals":11,"discoveryReveals":4,"discoveryTransitions":6,"distinctGoalItems":9,"exploringAgents":9,"goalActiveReviewShare":0.880466,"horizonGoals":15,"idleBesideExploration":0,"minAgentGoalActiveReviewShare":0.766667,"minAgentProgressingHeldTimeShare":0.070665,"noOpRevises":0,"oneStepGoals":0,"progressingHeldTimeShare":0.427766,"readoptedGoals":2,"substantiveCompletions":2,"substantiveMultiStepAgents":2},"schemaShaped":["goalActiveReviewShare","minAgentGoalActiveReviewShare"],"verdict":"notCleared"},"reviseItemChanges":0,"revisesUnchanged":0,"schemaGuarantees":{"alreadyMetConditions":0,"completionsWithoutNext":0,"held":true,"idleSteps":0,"ignoredOwnDrops":0,"meaning":"zero by the request schema; a nonzero count is a defect of the schema or of a backend that bypassed it, not behaviour","metIgnored":0,"reviseItemChanges":0,"revisesUnchanged":0,"stepMismatches":0,"unansweredGoals":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0},"stalled":0,"stepMismatches":0,"stepOnlySteps":22,"unadvancedSteps":49,"unansweredGoals":0,"unevidencedCredits":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0}.
- Calls the JVM decided before the mind: 20 refused to keep calls in reserve (`callReserved`), 0 sent at the ordinary tier past the pivotal ceiling (`tierFallback`), 0 answered but preempted by newer damage (`dangerPreempted`).
  - `tribe-gamma:loom18c040d2cc-sense-25` collect: targetRace (race), intentApplied collectItemGone at tick 710.
  - `tribe-epsilon:loom003398683d-sense-26` collect: targetRace (race), intentApplied collectItemGone at tick 710.
  - `tribe-gamma:loom18c040d2cc-sense-2z` collect: targetRace (race), intentApplied collectItemGone at tick 908.
  - `tribe-kappa:loomf309750e7b-sense-2r` collect: targetRace (race), intentApplied collectItemGone at tick 910.
  - `tribe-epsilon:loom003398683d-sense-lo` collect: terrain (race), intentApplied collectNoRoute at tick 6207.
  - `tribe-alpha:loomaa88da42d7-sense-13b` moveTo: superseded (race), moved movementCancelled at tick 10563.

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
