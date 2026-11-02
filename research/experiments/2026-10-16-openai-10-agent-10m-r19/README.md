# 10-agent ten-minute routine-v27 wooded-goal live shakedown

This directory is the sanitized, bounded publication from `2026-10-16-openai-10-agent-10m-r19`.
Every number below is generated from [evidence.jsonl](./evidence.jsonl) through the
committed research publisher; [metrics.json](./metrics.json) is the complete deterministic derivation.

## Question

Can ten independently embodied Loom agents complete the bounded routine-v27 wooded-goal-10m-10 profile using only native event-driven cognition, while preserving complete per-kind perception and lifecycle evidence?
This live-model run observes independently routed agents without assigned roles, pairings, social goals, or a scripted outcome.

## Setup facts

- Source commit: `3a457e3594a111536bcdc9b3406871da482c8de4`.
- Paper 1.21.1, build 133, fixed seed `10001`.
- Agents: `tribe-alpha` as `loom_aa88da42d7`, `tribe-beta` as `loom_eab7eb8acb`, `tribe-delta` as `loom_3b4ef3e082`, `tribe-epsilon` as `loom_003398683d`, `tribe-eta` as `loom_421b3b32c1`, `tribe-gamma` as `loom_18c040d2cc`, `tribe-iota` as `loom_8b6535c92c`, `tribe-kappa` as `loom_f309750e7b`, `tribe-theta` as `loom_9bc6579ca4`, `tribe-zeta` as `loom_3d8263b30e`.
- Cognition: `openai`, 150 global calls,
  24 per agent, 6 outstanding.
- External spend: $0.132583260 provider-reported actual,
  $29.184000000 worst case under the call/token bounds,
  and $29.190000 absolute declared ceiling.
- Wall-clock ceiling: 600s. Simulation ceiling: 12000 ticks.
- The server, body control, and cognition listeners were loopback-only; no real player account was used.
- Native goal reviews: 5 expected per agent,
  2400-tick cadence, 100-tick slices.
- Initial positions: {"tribe-alpha":[12.5,72,0.5],"tribe-beta":[9.5,68,7.5],"tribe-gamma":[3.5,67,11.5],"tribe-delta":[-4.5,66,14.5],"tribe-epsilon":[-9.5,68,7.5],"tribe-zeta":[-11.5,69,0.5],"tribe-eta":[-9.5,72,-7.5],"tribe-theta":[-3.5,77,-11.5],"tribe-iota":[3.5,79,-11.5],"tribe-kappa":[9.5,78,-7.5]}.
- Initial world clock: {"timeOfDay":0,"day":0,"isDay":true}.
- Starting inventories were empty; no role, target, social goal, hierarchy, pairing, conflict, or scripted objective was injected.

## Observations

- 5 paired self-position sample(s), the two agents' perceptions taken within 20 ticks of each other, placed two agents within eight blocks
- 91 perception(s) showed another player within eight blocks
- 122 perceptions produced 122 decisions and 122 applied intents.
- No body process was deliberately crashed and no harness `/loombody think` command was issued.
- Native scheduled reviews by agent: {"tribe-alpha":5,"tribe-beta":5,"tribe-delta":5,"tribe-epsilon":5,"tribe-eta":5,"tribe-gamma":5,"tribe-iota":5,"tribe-kappa":5,"tribe-theta":5,"tribe-zeta":5}.
- No agent died.
- Per-kind audit samples: 122; complete: true.
- Cleanup verification recorded: true.

## Behavioral gate

- Mechanical pilot verdict: **notApplicable** (loom-primary-behavior-gate/10).
- Criteria: not applicable outside the 180-second pilot profile.
- Meaningful behaviors by agent: {"tribe-alpha":8,"tribe-beta":10,"tribe-delta":11,"tribe-epsilon":9,"tribe-eta":11,"tribe-gamma":13,"tribe-iota":11,"tribe-kappa":11,"tribe-theta":11,"tribe-zeta":13}; typed goal continuity: ["tribe-alpha","tribe-beta","tribe-delta","tribe-epsilon","tribe-eta","tribe-gamma","tribe-iota","tribe-kappa","tribe-theta","tribe-zeta"].
- Non-idle goal-review actions: 122, accepted 115, confirmed 115.
- Calls by agent: {"tribe-alpha":10,"tribe-beta":12,"tribe-delta":12,"tribe-epsilon":10,"tribe-eta":12,"tribe-gamma":14,"tribe-iota":11,"tribe-kappa":13,"tribe-theta":14,"tribe-zeta":14}; peak outstanding 4.
- A pass still needs a reader to confirm: each counted meaningful behavior has an evidence-grounded aim; no counted action is justified only by being active; no cross-agent goal leakage in the typed fields.
- Readiness: **notReady** (loom-readiness-gate/8); decision validity 1, typed terminal accounting 1, server truthfulness 1.
- Actions by class: {"confirmed":115,"protocolError":2,"terrain":5}; blockers: {"protocolError":2}; legitimate races: 5; cognition failures: {}; server disputes: 0.
- Goal coherence: **coherent**, part of the verdict (mechanically notReady); {"alreadyMetConditions":0,"conditionSteps":56,"goalsMeasured":33,"idleSteps":0,"ignoredOwnDrops":0,"measuredSteps":106,"metCompleted":19,"metConditions":19,"metIgnored":0,"metRevised":0,"multiStepGoals":12,"pursuit":{"bars":{"craftSteps":1,"distinctGoalItems":2,"goalActiveReviewShare":0.6,"goalHeldTimeShare":0.5,"maxIdleBesideExploration":0,"minAgentGoalActiveReviewShare":0.4,"minAgentGoalHeldTimeShare":0.25,"multiStepAgentsAtOriginalTarget":3,"originalTargetCompletions":3},"measured":{"craftSteps":4,"distinctGoalItems":8,"goalActiveReviewShare":0.934426,"goalHeldTimeShare":0.812698,"idleBesideExploration":0,"minAgentGoalActiveReviewShare":0.9,"minAgentGoalHeldTimeShare":0.752597,"multiStepAgentsAtOriginalTarget":6,"originalTargetCompletions":8},"verdict":"cleared"},"stalled":0,"stepMismatches":0,"stepOnlySteps":23,"unadvancedSteps":27,"unansweredGoals":0,"unevidencedCredits":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0}.
  - `tribe-theta:loom9bc6579ca4-sense-jx` craft: protocolError, intentApplied craftLibraryFailure at tick 5906.
  - `tribe-epsilon:loom003398683d-sense-q0` craft: protocolError, intentApplied craftLibraryFailure at tick 7702.
  - `tribe-kappa:loomf309750e7b-sense-ri` collect: terrain (race), intentApplied collectNoRoute at tick 8101.
  - `tribe-theta:loom9bc6579ca4-sense-uc` collect: terrain (race), intentApplied collectNoRoute at tick 8900.
  - `tribe-alpha:loomaa88da42d7-sense-y0` collect: terrain (race), intentApplied collectNoRoute at tick 9998.
  - `tribe-beta:loomeab7eb8acb-sense-yy` collect: terrain (race), intentApplied collectNoRoute at tick 10200.
  - `tribe-kappa:loomf309750e7b-sense-10r` collect: terrain (race), intentApplied collectNoRoute at tick 10798.

## Interpretations

- The run supports the narrower claim that the bounded fleet, native scheduler, cognition bridge, per-kind evidence chain,
  publication, and cleanup worked together under the declared conditions.
- Model-driven actions are observations, not proof of motive or emergence. Any social or spatial interpretation is limited to the recorded evidence and counterevidence.

## Counterevidence and null results

- No agent produced a speech intent.
- No attack intent was applied.
- No movement record accompanied the co-location samples; shared spawn placement is a sufficient explanation.
- A null social result remains valid evidence: the publisher records it instead of replacing it with a story.

## Limitations

- Perception positions are samples rather than continuous trajectories.
- One bounded live-model run cannot establish that an observed pattern is stable or emergent.
- The disposable world, server logs, raw provider failures, death ledger, credentials, and process metadata are not committed.
- One short run cannot establish persistence over days, durable relationships, or repeatability across seeds.

## Reproduction

From a clean checkout, install the three pinned toolchains, then run:

```sh
node research/bin/loom-tribe.js run --config research/config/tribe-wooded-goal-10m-10.json
```

The harness validates a prospective manifest before launch, builds the three runtime components, verifies the
pinned Paper jar, creates a disposable server, enforces call and lifecycle bounds, publishes only normalized
evidence, validates it again, and removes the mutable run directory.
