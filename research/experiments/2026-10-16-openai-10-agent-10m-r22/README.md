# 10-agent ten-minute routine-v29 night-goal live shakedown

This directory is the sanitized, bounded publication from `2026-10-16-openai-10-agent-10m-r22`.
Every number below is generated from [evidence.jsonl](./evidence.jsonl) through the
committed research publisher; [metrics.json](./metrics.json) is the complete deterministic derivation.

## Question

Can ten independently embodied Loom agents complete the bounded routine-v29 night-goal-10m-10 profile using only native event-driven cognition, while preserving complete per-kind perception and lifecycle evidence?
This live-model run observes independently routed agents without assigned roles, pairings, social goals, or a scripted outcome.

## Setup facts

- Source commit: `d2ec8d8d1ff26d89c4de25cf3601c8f973e7fa63`.
- Paper 1.21.1, build 133, fixed seed `20011`.
- Agents: `tribe-alpha` as `loom_aa88da42d7`, `tribe-beta` as `loom_eab7eb8acb`, `tribe-delta` as `loom_3b4ef3e082`, `tribe-epsilon` as `loom_003398683d`, `tribe-eta` as `loom_421b3b32c1`, `tribe-gamma` as `loom_18c040d2cc`, `tribe-iota` as `loom_8b6535c92c`, `tribe-kappa` as `loom_f309750e7b`, `tribe-theta` as `loom_9bc6579ca4`, `tribe-zeta` as `loom_3d8263b30e`.
- Cognition: `openai`, 150 global calls,
  24 per agent, 6 outstanding.
- External spend: $0.913835000 provider-reported actual,
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

- 45 paired self-position sample(s), the two agents' perceptions taken within 20 ticks of each other, placed two agents within eight blocks
- 123 perception(s) showed another player within eight blocks
- 1 attack intent(s) were applied
- 9 agent(s) died under the server witness: tribe-alpha, tribe-beta, tribe-delta, tribe-epsilon, tribe-eta, tribe-gamma, tribe-kappa, tribe-theta, tribe-zeta.
- 303 perceptions produced 123 decisions and 123 applied intents.
- No body process was deliberately crashed and no harness `/loombody think` command was issued.
- Native scheduled reviews by agent: {"tribe-alpha":3,"tribe-beta":3,"tribe-delta":4,"tribe-epsilon":3,"tribe-eta":3,"tribe-gamma":4,"tribe-iota":5,"tribe-kappa":4,"tribe-theta":4,"tribe-zeta":3}.
- Server-witnessed deaths: {"tribe-alpha":{"attempted":3,"completed":3,"lifeTicks":6065,"maximum":3,"minimum":3,"settledAfterDeath":[],"tick":6136},"tribe-beta":{"attempted":3,"completed":3,"lifeTicks":7309,"maximum":4,"minimum":3,"settledAfterDeath":["tribe-beta:loomeab7eb8acb-sense-qr"],"tick":7380},"tribe-delta":{"attempted":4,"completed":4,"lifeTicks":7903,"maximum":4,"minimum":3,"settledAfterDeath":[],"tick":7974},"tribe-epsilon":{"attempted":3,"completed":3,"lifeTicks":7508,"maximum":4,"minimum":3,"settledAfterDeath":["tribe-epsilon:loom003398683d-sense-qd"],"tick":7579},"tribe-eta":{"attempted":3,"completed":3,"lifeTicks":7680,"maximum":4,"minimum":3,"settledAfterDeath":["tribe-eta:loom421b3b32c1-sense-qi"],"tick":7751},"tribe-gamma":{"attempted":4,"completed":4,"lifeTicks":8073,"maximum":4,"minimum":3,"settledAfterDeath":["tribe-gamma:loom18c040d2cc-sense-si"],"tick":8144},"tribe-kappa":{"attempted":4,"completed":4,"lifeTicks":8104,"maximum":4,"minimum":3,"settledAfterDeath":["tribe-kappa:loomf309750e7b-sense-sd","tribe-kappa:loomf309750e7b-sense-sf"],"tick":8175},"tribe-theta":{"attempted":4,"completed":4,"lifeTicks":8355,"maximum":4,"minimum":4,"settledAfterDeath":[],"tick":8426},"tribe-zeta":{"attempted":3,"completed":3,"lifeTicks":8164,"maximum":4,"minimum":3,"settledAfterDeath":["tribe-zeta:loom3d8263b30e-sense-s5"],"tick":8235}}; surviving agents continued.
- Per-kind audit samples: 303; complete: true.
- Cleanup verification recorded: true.

## Behavioral gate

- Mechanical pilot verdict: **notApplicable** (loom-primary-behavior-gate/12).
- Criteria: not applicable outside the 180-second pilot profile.
- Meaningful behaviors by agent: {"tribe-alpha":6,"tribe-beta":6,"tribe-delta":7,"tribe-epsilon":6,"tribe-eta":6,"tribe-gamma":7,"tribe-iota":10,"tribe-kappa":6,"tribe-theta":7,"tribe-zeta":6}; typed goal continuity: ["tribe-alpha","tribe-beta","tribe-delta","tribe-epsilon","tribe-eta","tribe-gamma","tribe-iota","tribe-kappa","tribe-theta","tribe-zeta"].
- Non-idle goal-review actions: 67, accepted 67, confirmed 67.
- Calls by agent: {"tribe-alpha":10,"tribe-beta":13,"tribe-delta":14,"tribe-epsilon":13,"tribe-eta":13,"tribe-gamma":14,"tribe-iota":10,"tribe-kappa":14,"tribe-theta":14,"tribe-zeta":13}; peak outstanding 5.
- A pass still needs a reader to confirm: each counted meaningful behavior has an evidence-grounded aim; no counted action is justified only by being active; no cross-agent goal leakage in the typed fields.
- Readiness: **notReady** (loom-readiness-gate/10); decision validity 0.99187, typed terminal accounting 1, server truthfulness 1.
- Actions by class: {"confirmed":113,"death":2,"inapplicable":1,"superseded":6,"targetRace":1}; blockers: {"inapplicable":1}; legitimate races: 9; cognition failures: {}; server disputes: 0.
- Goal coherence: **incoherent**, part of the verdict (mechanically notReady); {"alreadyMetConditions":0,"completionsWithoutNext":0,"conditionSteps":45,"goalsMeasured":18,"idleSteps":0,"ignoredOwnDrops":0,"measuredSteps":57,"metCompleted":9,"metConditions":9,"metIgnored":0,"metRevised":0,"multiStepGoals":10,"pursuit":{"bars":{"chainedGoals":2,"craftChainSteps":1,"dangerEpisodes":1,"distinctGoalItems":3,"exploringAgents":3,"goalActiveReviewShare":0.6,"maxIdleBesideExploration":0,"minAgentGoalActiveReviewShare":0.4,"minAgentProgressingHeldTimeShare":0.25,"progressingHeldTimeShare":0.5,"substantiveCompletions":3,"substantiveMultiStepAgents":3},"measured":{"chainedGoals":0,"craftChainSteps":0,"dangerEpisodes":56,"deaths":9,"distinctGoalItems":2,"exploringAgents":2,"goalActiveReviewShare":1,"idleBesideExploration":0,"minAgentGoalActiveReviewShare":1,"minAgentProgressingHeldTimeShare":0.660522,"noOpRevises":0,"oneStepGoals":8,"progressingHeldTimeShare":0.944973,"readoptedGoals":0,"substantiveCompletions":0,"substantiveMultiStepAgents":0},"schemaShaped":["goalActiveReviewShare","minAgentGoalActiveReviewShare"],"verdict":"notCleared"},"reviseItemChanges":0,"revisesUnchanged":0,"schemaGuarantees":{"alreadyMetConditions":0,"completionsWithoutNext":0,"held":true,"idleSteps":0,"ignoredOwnDrops":0,"meaning":"zero by the request schema; a nonzero count is a defect of the schema or of a backend that bypassed it, not behaviour","metIgnored":0,"reviseItemChanges":0,"revisesUnchanged":0,"stepMismatches":0,"unansweredGoals":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0},"stalled":0,"stepMismatches":0,"stepOnlySteps":9,"unadvancedSteps":3,"unansweredGoals":0,"unevidencedCredits":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0}.
  - `tribe-eta:loom421b3b32c1-sense-o2` flee: superseded (race), retreating retreatSuperseded at tick 7184.
  - `tribe-gamma:loom18c040d2cc-sense-od` flee: superseded (race), retreating retreatSuperseded at tick 7261.
  - `tribe-kappa:loomf309750e7b-sense-oq` flee: superseded (race), retreating retreatSuperseded at tick 7444.
  - `tribe-kappa:loomf309750e7b-sense-p1` flee: superseded (race), retreating retreatSuperseded at tick 7494.
  - `tribe-delta:loom3b4ef3e082-sense-qb` attack: inapplicable, intentApplied attackOutOfRange at tick 7743.
  - `tribe-eta:loom421b3b32c1-sense-qi` flee: death (race), intentApplied accepted at tick 7752.
  - `tribe-theta:loom9bc6579ca4-sense-qd` flee: superseded (race), retreating retreatSuperseded at tick 7882.
  - `tribe-zeta:loom3d8263b30e-sense-ql` flee: targetRace (race), intentApplied retreatThreatGone at tick 7869.
  - `tribe-gamma:loom18c040d2cc-sense-rb` flee: superseded (race), retreating retreatSuperseded at tick 8010.
  - `tribe-zeta:loom3d8263b30e-sense-s5` flee: death (race), intentApplied controlUnavailable at tick 8236.

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
