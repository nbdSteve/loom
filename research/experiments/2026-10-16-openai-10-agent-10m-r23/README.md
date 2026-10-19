# 10-agent ten-minute routine-v30 horizon-goal live shakedown

This directory is the sanitized, bounded publication from `2026-10-16-openai-10-agent-10m-r23`.
Every number below is generated from [evidence.jsonl](./evidence.jsonl) through the
committed research publisher; [metrics.json](./metrics.json) is the complete deterministic derivation.

## Question

Can ten independently embodied Loom agents complete the bounded routine-v30 night-goal-10m-10 profile using only native event-driven cognition, while preserving complete per-kind perception and lifecycle evidence?
This live-model run observes independently routed agents without assigned roles, pairings, social goals, or a scripted outcome.

## Setup facts

- Source commit: `842b751dc41730e23b0715494986b1508ab54c41`.
- Paper 1.21.1, build 133, fixed seed `20011`.
- Agents: `tribe-alpha` as `loom_aa88da42d7`, `tribe-beta` as `loom_eab7eb8acb`, `tribe-delta` as `loom_3b4ef3e082`, `tribe-epsilon` as `loom_003398683d`, `tribe-eta` as `loom_421b3b32c1`, `tribe-gamma` as `loom_18c040d2cc`, `tribe-iota` as `loom_8b6535c92c`, `tribe-kappa` as `loom_f309750e7b`, `tribe-theta` as `loom_9bc6579ca4`, `tribe-zeta` as `loom_3d8263b30e`.
- Cognition: `openai`, 150 global calls,
  24 per agent, 6 outstanding.
- External spend: $0.788134510 provider-reported actual,
  $29.184000000 worst case under the call/token bounds,
  and $29.190000 absolute declared ceiling.
- Wall-clock ceiling: 600s. Simulation ceiling: 12000 ticks.
- The server, body control, and cognition listeners were loopback-only; no real player account was used.
- Native goal reviews: 5 expected per agent,
  2400-tick cadence, 100-tick slices.
- Initial positions: {"tribe-alpha":[12.5,67,0.5],"tribe-beta":[9.5,67,7.5],"tribe-gamma":[3.5,67,11.5],"tribe-delta":[-4.5,66,12.5],"tribe-epsilon":[-9.5,66,7.5],"tribe-zeta":[-11.5,66,0.5],"tribe-eta":[-9.5,66,-7.5],"tribe-theta":[-3.5,67,-11.5],"tribe-iota":[3.5,68,-11.5],"tribe-kappa":[9.5,69,-7.5]}.
- Initial world clock: {"timeOfDay":9000,"day":0,"isDay":true}.
- Starting inventories were empty; no role, target, social goal, hierarchy, pairing, conflict, or scripted objective was injected.

## Observations

- 26 paired self-position sample(s), the two agents' perceptions taken within 20 ticks of each other, placed two agents within eight blocks
- 99 perception(s) showed another player within eight blocks
- 7 agent(s) died under the server witness: tribe-alpha, tribe-beta, tribe-delta, tribe-gamma, tribe-iota, tribe-kappa, tribe-zeta.
- 265 perceptions produced 149 decisions and 149 applied intents.
- No body process was deliberately crashed and no harness `/loombody think` command was issued.
- Native scheduled reviews by agent: {"tribe-alpha":4,"tribe-beta":5,"tribe-delta":5,"tribe-epsilon":5,"tribe-eta":5,"tribe-gamma":4,"tribe-iota":3,"tribe-kappa":4,"tribe-theta":5,"tribe-zeta":4}.
- Server-witnessed deaths: {"tribe-alpha":{"attempted":4,"completed":4,"lifeTicks":9270,"maximum":4,"minimum":4,"settledAfterDeath":["tribe-alpha:loomaa88da42d7-sense-wc"],"tick":9330},"tribe-beta":{"attempted":5,"completed":5,"lifeTicks":10938,"maximum":5,"minimum":5,"settledAfterDeath":["tribe-beta:loomeab7eb8acb-sense-11s"],"tick":10998},"tribe-delta":{"attempted":5,"completed":5,"lifeTicks":10801,"maximum":5,"minimum":5,"settledAfterDeath":[],"tick":10861},"tribe-gamma":{"attempted":4,"completed":4,"lifeTicks":8821,"maximum":4,"minimum":4,"settledAfterDeath":[],"tick":8881},"tribe-iota":{"attempted":3,"completed":3,"lifeTicks":7413,"maximum":4,"minimum":3,"settledAfterDeath":["tribe-iota:loom8b6535c92c-sense-pa"],"tick":7473},"tribe-kappa":{"attempted":4,"completed":4,"lifeTicks":8532,"maximum":4,"minimum":4,"settledAfterDeath":["tribe-kappa:loomf309750e7b-sense-tf"],"tick":8592},"tribe-zeta":{"attempted":4,"completed":4,"lifeTicks":10090,"maximum":5,"minimum":4,"settledAfterDeath":["tribe-zeta:loom3d8263b30e-sense-ye"],"tick":10150}}; surviving agents continued.
- Per-kind audit samples: 265; complete: true.
- Cleanup verification recorded: true.

## Behavioral gate

- Mechanical pilot verdict: **notApplicable** (loom-primary-behavior-gate/13).
- Criteria: not applicable outside the 180-second pilot profile.
- Meaningful behaviors by agent: {"tribe-alpha":5,"tribe-beta":7,"tribe-delta":2,"tribe-epsilon":6,"tribe-eta":4,"tribe-gamma":5,"tribe-kappa":1,"tribe-theta":9,"tribe-zeta":2}; typed goal continuity: ["tribe-alpha","tribe-beta","tribe-delta","tribe-epsilon","tribe-eta","tribe-gamma","tribe-kappa","tribe-theta"].
- Non-idle goal-review actions: 70, accepted 65, confirmed 63.
- Calls by agent: {"tribe-alpha":19,"tribe-beta":19,"tribe-delta":18,"tribe-epsilon":15,"tribe-eta":13,"tribe-gamma":18,"tribe-iota":10,"tribe-kappa":14,"tribe-theta":12,"tribe-zeta":12}; peak outstanding 4.
- A pass still needs a reader to confirm: each counted meaningful behavior has an evidence-grounded aim; no counted action is justified only by being active; no cross-agent goal leakage in the typed fields.
- Readiness: **notReady** (loom-readiness-gate/11); decision validity 1, typed terminal accounting 1, server truthfulness 1.
- Actions by class: {"concurrent":5,"confirmed":120,"death":4,"superseded":10,"targetRace":10}; blockers: none; legitimate races: 29; cognition failures: {}; server disputes: 0.
- Goal coherence: **incoherent**, part of the verdict (mechanically ready); {"alreadyMetConditions":0,"completionsWithoutNext":0,"conditionSteps":11,"goalsMeasured":17,"idleSteps":0,"ignoredOwnDrops":0,"measuredSteps":40,"metCompleted":4,"metConditions":4,"metIgnored":0,"metRevised":0,"multiStepGoals":3,"pursuit":{"bars":{"chainedGoals":2,"craftChainSteps":1,"dangerEpisodes":1,"distinctGoalItems":3,"exploringAgents":3,"goalActiveReviewShare":0.6,"maxIdleBesideExploration":0,"minAgentGoalActiveReviewShare":0.4,"minAgentProgressingHeldTimeShare":0.25,"progressingHeldTimeShare":0.5,"substantiveCompletions":3,"substantiveMultiStepAgents":3},"measured":{"chainedGoals":0,"craftChainSteps":0,"crowdedGoals":0,"dangerEpisodes":79,"deaths":7,"discoveredGoals":5,"distinctGoalItems":9,"exploringAgents":9,"goalActiveReviewShare":0.685714,"horizonGoals":15,"idleBesideExploration":0,"minAgentGoalActiveReviewShare":0,"minAgentProgressingHeldTimeShare":0,"noOpRevises":0,"oneStepGoals":6,"progressingHeldTimeShare":0.396741,"readoptedGoals":1,"substantiveCompletions":2,"substantiveMultiStepAgents":2},"schemaShaped":["goalActiveReviewShare","minAgentGoalActiveReviewShare"],"verdict":"notCleared"},"reviseItemChanges":0,"revisesUnchanged":0,"schemaGuarantees":{"alreadyMetConditions":0,"completionsWithoutNext":0,"held":true,"idleSteps":0,"ignoredOwnDrops":0,"meaning":"zero by the request schema; a nonzero count is a defect of the schema or of a backend that bypassed it, not behaviour","metIgnored":0,"reviseItemChanges":0,"revisesUnchanged":0,"stepMismatches":0,"unansweredGoals":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0},"stalled":0,"stepMismatches":0,"stepOnlySteps":7,"unadvancedSteps":22,"unansweredGoals":0,"unevidencedCredits":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0}.
  - `tribe-gamma:loom18c040d2cc-sense-j7` dig: concurrent (race), intentApplied controlNotReady at tick 5700.
  - `tribe-alpha:loomaa88da42d7-sense-nu` flee: superseded (race), retreating retreatSuperseded at tick 7138.
  - `tribe-iota:loom8b6535c92c-sense-pa` flee: death (race), intentApplied retreatThreatGone at tick 7474.
  - `tribe-zeta:loom3d8263b30e-sense-p5` flee: targetRace (race), intentApplied retreatThreatGone at tick 7556.
  - `tribe-kappa:loomf309750e7b-sense-pf` flee: superseded (race), retreating retreatSuperseded at tick 7646.
  - `tribe-delta:loom3b4ef3e082-sense-p6` flee: targetRace (race), intentApplied retreatThreatGone at tick 7556.
  - `tribe-epsilon:loom003398683d-sense-pf` flee: targetRace (race), intentApplied retreatThreatGone at tick 7567.
  - `tribe-beta:loomeab7eb8acb-sense-pl` flee: targetRace (race), intentApplied retreatThreatGone at tick 7568.
  - `tribe-gamma:loom18c040d2cc-sense-s4` flee: superseded (race), retreating retreatSuperseded at tick 8231.
  - `tribe-gamma:loom18c040d2cc-sense-s3` dig: concurrent (race), intentApplied controlNotReady at tick 8193.
  - `tribe-kappa:loomf309750e7b-sense-sd` moveTo: concurrent (race), intentApplied controlNotReady at tick 8316.
  - `tribe-gamma:loom18c040d2cc-sense-tn` flee: superseded (race), retreating retreatSuperseded at tick 8652.
  - `tribe-kappa:loomf309750e7b-sense-tf` flee: death (race), intentApplied accepted at tick 8593.
  - `tribe-epsilon:loom003398683d-sense-vu` flee: superseded (race), retreating retreatSuperseded at tick 9489.
  - `tribe-delta:loom3b4ef3e082-sense-w4` flee: targetRace (race), intentApplied retreatThreatGone at tick 9582.
  - `tribe-eta:loom421b3b32c1-sense-w5` flee: targetRace (race), intentApplied retreatThreatGone at tick 9582.
  - `tribe-zeta:loom3d8263b30e-sense-wu` flee: superseded (race), retreating retreatSuperseded at tick 9818.
  - `tribe-delta:loom3b4ef3e082-sense-xs` flee: targetRace (race), intentApplied retreatThreatGone at tick 10033.
  - `tribe-zeta:loom3d8263b30e-sense-xw` flee: targetRace (race), intentApplied retreatThreatGone at tick 10048.
  - `tribe-zeta:loom3d8263b30e-sense-y3` flee: targetRace (race), intentApplied retreatThreatGone at tick 10076.
  - `tribe-delta:loom3b4ef3e082-sense-y7` moveTo: concurrent (race), intentApplied controlNotReady at tick 10109.
  - `tribe-zeta:loom3d8263b30e-sense-ye` flee: death (race), intentApplied accepted at tick 10151.
  - `tribe-eta:loom421b3b32c1-sense-yq` moveTo: superseded (race), moved movementCancelled at tick 10309.
  - `tribe-eta:loom421b3b32c1-sense-z9` craft: concurrent (race), intentApplied controlNotReady at tick 10393.
  - `tribe-epsilon:loom003398683d-sense-zb` moveTo: superseded (race), moved movementCancelled at tick 10452.
  - `tribe-delta:loom3b4ef3e082-sense-zo` flee: superseded (race), retreating retreatSuperseded at tick 10523.
  - `tribe-beta:loomeab7eb8acb-sense-106` flee: superseded (race), retreating retreatSuperseded at tick 10662.
  - `tribe-beta:loomeab7eb8acb-sense-11s` flee: death (race), intentApplied accepted at tick 10999.
  - `tribe-theta:loom9bc6579ca4-sense-11i` flee: targetRace (race), intentApplied retreatThreatGone at tick 11078.

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
