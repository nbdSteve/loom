# 10-agent ten-minute routine-v28 explored-goal live shakedown

This directory is the sanitized, bounded publication from `2026-10-16-openai-10-agent-10m-r20`.
Every number below is generated from [evidence.jsonl](./evidence.jsonl) through the
committed research publisher; [metrics.json](./metrics.json) is the complete deterministic derivation.

## Question

Can ten independently embodied Loom agents complete the bounded routine-v28 explored-goal-10m-10 profile using only native event-driven cognition, while preserving complete per-kind perception and lifecycle evidence?
This live-model run observes independently routed agents without assigned roles, pairings, social goals, or a scripted outcome.

## Setup facts

- Source commit: `17545e6ee0f00715bcc8b15725e2915d0dcaddfc`.
- Paper 1.21.1, build 133, fixed seed `20011`.
- Agents: `tribe-alpha` as `loom_aa88da42d7`, `tribe-beta` as `loom_eab7eb8acb`, `tribe-delta` as `loom_3b4ef3e082`, `tribe-epsilon` as `loom_003398683d`, `tribe-eta` as `loom_421b3b32c1`, `tribe-gamma` as `loom_18c040d2cc`, `tribe-iota` as `loom_8b6535c92c`, `tribe-kappa` as `loom_f309750e7b`, `tribe-theta` as `loom_9bc6579ca4`, `tribe-zeta` as `loom_3d8263b30e`.
- Cognition: `openai`, 150 global calls,
  24 per agent, 6 outstanding.
- External spend: $0.374889690 provider-reported actual,
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

- 35 paired self-position sample(s), the two agents' perceptions taken within 20 ticks of each other, placed two agents within eight blocks
- 143 perception(s) showed another player within eight blocks
- 3 agent(s) died under the server witness: tribe-beta, tribe-gamma, tribe-kappa.
- 181 perceptions produced 150 decisions and 149 applied intents.
- No body process was deliberately crashed and no harness `/loombody think` command was issued.
- Native scheduled reviews by agent: {"tribe-alpha":4,"tribe-beta":2,"tribe-delta":4,"tribe-epsilon":4,"tribe-eta":4,"tribe-gamma":3,"tribe-iota":4,"tribe-kappa":3,"tribe-theta":4,"tribe-zeta":4}.
- Server-witnessed deaths: {"tribe-beta":{"attempted":2,"completed":2,"lifeTicks":5279,"maximum":3,"minimum":2,"settledAfterDeath":[],"tick":5343},"tribe-gamma":{"attempted":3,"completed":3,"lifeTicks":6067,"maximum":3,"minimum":3,"settledAfterDeath":[],"tick":6131},"tribe-kappa":{"attempted":3,"completed":3,"lifeTicks":5657,"maximum":3,"minimum":2,"settledAfterDeath":["tribe-kappa:loomf309750e7b-sense-j5"],"tick":5721}}; surviving agents continued.
- Per-kind audit samples: 181; complete: true.
- Cleanup verification recorded: true.

## Controlled stop

- Stop: `cognitionBudgetExhausted` for `tribe-zeta:loom3d8263b30e-sense-t1` (stepSettled, nativeGoalFollowUp).
- Refusal: `globalCall` at 150 of 150 calls; the call was not forwarded.
- Admission closed with 150 call(s) forwarded and 0 outstanding; 0 later request(s) were refused.
- Stop tick 8465, 8401 ticks after the start.
- Native reviews: 36 completed of 50 planned; by agent {"tribe-alpha":{"attempted":4,"completed":4,"maximum":4,"minimum":4},"tribe-beta":{"attempted":2,"completed":2,"maximum":4,"minimum":4},"tribe-delta":{"attempted":4,"completed":4,"maximum":4,"minimum":4},"tribe-epsilon":{"attempted":4,"completed":4,"maximum":4,"minimum":4},"tribe-eta":{"attempted":4,"completed":4,"maximum":4,"minimum":4},"tribe-gamma":{"attempted":3,"completed":3,"maximum":4,"minimum":4},"tribe-iota":{"attempted":4,"completed":4,"maximum":4,"minimum":4},"tribe-kappa":{"attempted":3,"completed":3,"maximum":4,"minimum":4},"tribe-theta":{"attempted":4,"completed":4,"maximum":4,"minimum":4},"tribe-zeta":{"attempted":4,"completed":4,"maximum":4,"minimum":4}}.
- Chains: 150 complete decision chain(s), 31 typed refusal chain(s).
- Provider usage: 150 call(s), 1088046 tokens, $0.374889690.
- Behavioral verdict: **no-go**. A stopped run is a partial record, not a pass.

## Behavioral gate

- Mechanical pilot verdict: **notApplicable** (loom-primary-behavior-gate/11).
- Criteria: not applicable outside the 180-second pilot profile.
- Meaningful behaviors by agent: {"tribe-alpha":12,"tribe-beta":5,"tribe-delta":11,"tribe-epsilon":12,"tribe-eta":12,"tribe-gamma":6,"tribe-iota":12,"tribe-kappa":6,"tribe-theta":8,"tribe-zeta":11}; typed goal continuity: ["tribe-alpha","tribe-beta","tribe-delta","tribe-epsilon","tribe-eta","tribe-gamma","tribe-iota","tribe-kappa","tribe-theta","tribe-zeta"].
- Non-idle goal-review actions: 100, accepted 96, confirmed 96.
- Calls by agent: {"tribe-alpha":14,"tribe-beta":8,"tribe-delta":15,"tribe-epsilon":16,"tribe-eta":21,"tribe-gamma":13,"tribe-iota":15,"tribe-kappa":13,"tribe-theta":21,"tribe-zeta":14}; peak outstanding 6.
- A pass still needs a reader to confirm: each counted meaningful behavior has an evidence-grounded aim; no counted action is justified only by being active; no cross-agent goal leakage in the typed fields.
- Readiness: **notReady** (loom-readiness-gate/9); decision validity 1, typed terminal accounting 1, server truthfulness 1.
- Actions by class: {"concurrent":2,"confirmed":130,"death":1,"superseded":4,"terrain":13}; blockers: none; legitimate races: 20; cognition failures: {}; server disputes: 0.
- Goal coherence: **incoherent**, part of the verdict (mechanically ready); {"alreadyMetConditions":0,"completionsWithoutNext":0,"conditionSteps":70,"goalsMeasured":35,"idleSteps":0,"ignoredOwnDrops":0,"measuredSteps":90,"metCompleted":28,"metConditions":28,"metIgnored":0,"metRevised":0,"multiStepGoals":10,"pursuit":{"bars":{"chainedGoals":2,"craftChainSteps":1,"dangerEncounters":1,"distinctGoalItems":3,"exploringAgents":3,"goalActiveReviewShare":0.6,"maxIdleBesideExploration":0,"minAgentGoalActiveReviewShare":0.4,"minAgentProgressingHeldTimeShare":0.25,"progressingHeldTimeShare":0.5,"substantiveCompletions":3,"substantiveMultiStepAgents":3},"measured":{"chainedGoals":3,"craftChainSteps":4,"dangerEncounters":79,"distinctGoalItems":8,"exploringAgents":1,"goalActiveReviewShare":0.990099,"idleBesideExploration":0,"minAgentGoalActiveReviewShare":0.909091,"minAgentProgressingHeldTimeShare":0.59386,"noOpRevises":0,"progressingHeldTimeShare":0.883662,"substantiveCompletions":0,"substantiveMultiStepAgents":0},"verdict":"notCleared"},"reviseItemChanges":0,"revisesUnchanged":0,"schemaGuarantees":{"alreadyMetConditions":0,"completionsWithoutNext":0,"held":true,"idleSteps":0,"ignoredOwnDrops":0,"meaning":"zero by the request schema; a nonzero count is a defect of the schema or of a backend that bypassed it, not behaviour","metIgnored":0,"reviseItemChanges":0,"revisesUnchanged":0,"stepMismatches":0,"unansweredGoals":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0},"stalled":0,"stepMismatches":0,"stepOnlySteps":16,"unadvancedSteps":4,"unansweredGoals":0,"unevidencedCredits":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0}.
  - `tribe-iota:loom8b6535c92c-sense-gl` flee: terrain (race), intentApplied retreatNoRoute at tick 4889.
  - `tribe-delta:loom3b4ef3e082-sense-ic` dig: superseded (race), intentApplied digCancelled at tick 5413.
  - `tribe-delta:loom3b4ef3e082-sense-ig` flee: terrain (race), intentApplied retreatNoRoute at tick 5415.
  - `tribe-epsilon:loom003398683d-sense-is` flee: terrain (race), intentApplied retreatNoRoute at tick 5494.
  - `tribe-gamma:loom18c040d2cc-sense-it` collect: superseded (race), intentApplied collectCancelled at tick 5509.
  - `tribe-iota:loom8b6535c92c-sense-jf` flee: terrain (race), intentApplied retreatNoRoute at tick 5678.
  - `tribe-kappa:loomf309750e7b-sense-j5` dig: death (race), decisionDiscarded at tick 5723.
  - `tribe-eta:loom421b3b32c1-sense-lf` flee: superseded (race), retreating retreatSuperseded at tick 6311.
  - `tribe-theta:loom9bc6579ca4-sense-li` collect: concurrent (race), intentApplied controlNotReady at tick 6299.
  - `tribe-theta:loom9bc6579ca4-sense-lh` collect: concurrent (race), intentApplied controlNotReady at tick 6304.
  - `tribe-zeta:loom3d8263b30e-sense-lq` flee: terrain (race), intentApplied retreatNoRoute at tick 6374.
  - `tribe-epsilon:loom003398683d-sense-m3` flee: terrain (race), intentApplied retreatNoRoute at tick 6454.
  - `tribe-delta:loom3b4ef3e082-sense-mg` flee: terrain (race), intentApplied retreatNoRoute at tick 6515.
  - `tribe-zeta:loom3d8263b30e-sense-mf` flee: terrain (race), intentApplied retreatNoRoute at tick 6570.
  - `tribe-epsilon:loom003398683d-sense-mk` flee: terrain (race), intentApplied retreatNoRoute at tick 6595.
  - `tribe-delta:loom3b4ef3e082-sense-n3` flee: terrain (race), intentApplied retreatNoRoute at tick 6701.
  - `tribe-theta:loom9bc6579ca4-sense-s9` dig: superseded (race), intentApplied digCancelled at tick 8313.
  - `tribe-epsilon:loom003398683d-sense-sw` flee: terrain (race), intentApplied retreatNoRoute at tick 8411.
  - `tribe-zeta:loom3d8263b30e-sense-su` flee: terrain (race), intentApplied retreatNoRoute at tick 8444.
  - `tribe-iota:loom8b6535c92c-sense-t7` flee: terrain (race), intentApplied retreatNoRoute at tick 8456.

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
- The run ended in a controlled cognitionBudgetExhausted stop after 36 of 50 planned native reviews; its behavioral verdict is no-go.
- The disposable world, server logs, raw provider failures, death ledger, credentials, and process metadata are not committed.
- One short run cannot establish persistence over days, durable relationships, or repeatability across seeds.

## Reproduction

From a clean checkout, install the three pinned toolchains, then run:

```sh
node research/bin/loom-tribe.js run --config research/config/tribe-explored-goal-10m-10.json
```

The harness validates a prospective manifest before launch, builds the three runtime components, verifies the
pinned Paper jar, creates a disposable server, enforces call and lifecycle bounds, publishes only normalized
evidence, validates it again, and removes the mutable run directory.
