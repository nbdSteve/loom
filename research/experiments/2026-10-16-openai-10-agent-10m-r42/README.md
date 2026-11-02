# 10-agent ten-minute routine-v36 held objective live shakedown

This directory is the sanitized, bounded publication from `2026-10-16-openai-10-agent-10m-r42`.
Every number below is generated from [evidence.jsonl](./evidence.jsonl) through the
committed research publisher; [metrics.json](./metrics.json) is the complete deterministic derivation.

## Question

Can ten independently embodied Loom agents complete the bounded routine-v36 held-objective-10m-10 profile using only native event-driven cognition, while preserving complete per-kind perception and lifecycle evidence?
This live-model run observes independently routed agents without assigned roles, pairings, social goals, or a scripted outcome.

## Setup facts

- Source commit: `4fb50fe62cda5cd7d7693a13185490eac7d26f94`.
- Paper 1.21.1, build 133, fixed seed `20011`.
- Agents: `tribe-alpha` as `loom_aa88da42d7`, `tribe-beta` as `loom_eab7eb8acb`, `tribe-delta` as `loom_3b4ef3e082`, `tribe-epsilon` as `loom_003398683d`, `tribe-eta` as `loom_421b3b32c1`, `tribe-gamma` as `loom_18c040d2cc`, `tribe-iota` as `loom_8b6535c92c`, `tribe-kappa` as `loom_f309750e7b`, `tribe-theta` as `loom_9bc6579ca4`, `tribe-zeta` as `loom_3d8263b30e`.
- Cognition: `openai`, 378 global calls,
  40 per agent, 6 outstanding.
- External spend: $0.206179410 provider-reported actual,
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

- 21 paired self-position sample(s), the two agents' perceptions taken within 20 ticks of each other, placed two agents within eight blocks
- 99 perception(s) showed another player within eight blocks
- 212 perceptions produced 212 decisions and 212 applied intents.
- No body process was deliberately crashed and no harness `/loombody think` command was issued.
- Native scheduled reviews by agent: {"tribe-alpha":5,"tribe-beta":5,"tribe-delta":5,"tribe-epsilon":5,"tribe-eta":5,"tribe-gamma":5,"tribe-iota":5,"tribe-kappa":5,"tribe-theta":5,"tribe-zeta":5}.
- No agent died.
- Per-kind audit samples: 212; complete: true.
- Cleanup verification recorded: true.

## Behavioral gate

- Mechanical pilot verdict: **notApplicable** (loom-primary-behavior-gate/20).
- Criteria: not applicable outside the 180-second pilot profile.
- Meaningful behaviors by agent: {"tribe-alpha":14,"tribe-beta":16,"tribe-delta":14,"tribe-epsilon":11,"tribe-eta":19,"tribe-gamma":15,"tribe-iota":24,"tribe-kappa":17,"tribe-theta":18,"tribe-zeta":20}; typed goal continuity: ["tribe-alpha","tribe-beta","tribe-delta","tribe-epsilon","tribe-eta","tribe-gamma","tribe-iota","tribe-kappa","tribe-theta","tribe-zeta"].
- Non-idle goal-review actions: 190, accepted 174, confirmed 174.
- Calls by agent: {"tribe-alpha":19,"tribe-beta":21,"tribe-delta":19,"tribe-epsilon":17,"tribe-eta":22,"tribe-gamma":20,"tribe-iota":27,"tribe-kappa":22,"tribe-theta":22,"tribe-zeta":23}; peak outstanding 6.
- A pass still needs a reader to confirm: each counted meaningful behavior has an evidence-grounded aim; no counted action is justified only by being active; no cross-agent goal leakage in the typed fields.
- Readiness: **notReady** (loom-readiness-gate/20); decision validity 0.915094, typed terminal accounting 1, server truthfulness 1.
- Actions by class: {"confirmed":174,"goalIncoherent":18,"targetRace":3,"terrain":1}; blockers: {"goalIncoherent":18}; legitimate races: 4; cognition failures: {}; server disputes: 0.
- Goal coherence: **incoherent**, part of the verdict (mechanically notReady); {"alreadyMetConditions":0,"completionsWithoutNext":18,"conditionSteps":112,"goalsMeasured":48,"idleSteps":0,"ignoredOwnDrops":0,"measuredSteps":172,"metCompleted":18,"metConditions":36,"metIgnored":18,"metRevised":0,"multiStepGoals":26,"pursuit":{"bars":{"chainedGoals":2,"craftChainSteps":1,"goalActiveReviewShare":0.6,"maxIdleBesideExploration":0,"minAgentGoalActiveReviewShare":0.4,"minAgentProgressingHeldTimeShare":0.25,"progressingHeldTimeShare":0.5,"shelterProgressAgentsBeforeNight":3,"substantiveCompletions":3,"substantiveMultiStepAgents":3},"description":{"crowdedGoals":0,"dangerEpisodes":0,"deaths":0,"deferredReviews":0,"discoveryGoals":6,"discoveryReveals":2,"discoveryTransitions":3,"distinctGoalItems":10,"exploringAgents":9,"horizonGoals":13,"ownDropPickups":{"accepted":17,"ownDropNotSensed":1}},"descriptive":["distinctGoalItems","exploringAgents","dangerEpisodes"],"measured":{"chainedGoals":3,"craftChainSteps":3,"crowdedGoals":0,"dangerEpisodes":0,"deaths":0,"deferredReviews":0,"discoveredGoals":4,"discoveryGoals":6,"discoveryReveals":2,"discoveryTransitions":3,"distinctGoalItems":10,"exploringAgents":9,"goalActiveReviewShare":0.811321,"horizonGoals":13,"idleBesideExploration":0,"minAgentGoalActiveReviewShare":0.736842,"minAgentProgressingHeldTimeShare":0.029081,"noOpRevises":0,"oneStepGoals":0,"progressingHeldTimeShare":0.291249,"readoptedGoals":0,"substantiveCompletions":2,"substantiveMultiStepAgents":4},"schemaShaped":["goalActiveReviewShare","minAgentGoalActiveReviewShare"],"verdict":"notCleared"},"reviseItemChanges":0,"revisesUnchanged":0,"schemaGuarantees":{"alreadyMetConditions":0,"completionsWithoutNext":18,"held":false,"idleSteps":0,"ignoredOwnDrops":0,"meaning":"zero by the request schema; a nonzero count is a defect of the schema or of a backend that bypassed it, not behaviour","metIgnored":18,"reviseItemChanges":0,"revisesUnchanged":0,"stepMismatches":0,"unansweredGoals":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0},"stalled":0,"stepMismatches":0,"stepOnlySteps":16,"unadvancedSteps":44,"unansweredGoals":0,"unevidencedCredits":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0}.
- Calls the JVM decided before the mind: 0 refused to keep calls in reserve (`callReserved`), 0 sent at the ordinary tier past the pivotal ceiling (`tierFallback`), 0 answered but preempted by newer damage (`dangerPreempted`).
  - `tribe-gamma:loom18c040d2cc-sense-23` collect: targetRace (race), intentApplied collectItemGone at tick 730.
  - `tribe-epsilon:loom003398683d-sense-2m` dig: targetRace (race), intentApplied digBlockGone at tick 896.
  - `tribe-alpha:loomaa88da42d7-sense-l4` equip: goalIncoherent, intentApplied goalCompletedWithoutNext at tick 5685.
  - `tribe-epsilon:loom003398683d-sense-ke` dig: targetRace (race), intentApplied digBlockChanged at tick 5893.
  - `tribe-delta:loom3b4ef3e082-sense-kx` equip: goalIncoherent, intentApplied goalCompletedWithoutNext at tick 5954.
  - `tribe-theta:loom9bc6579ca4-sense-oo` idle: goalIncoherent, intentApplied goalCompletedWithoutNext at tick 6943.
  - `tribe-alpha:loomaa88da42d7-sense-qy` equip: goalIncoherent, intentApplied goalCompletedWithoutNext at tick 7437.
  - `tribe-delta:loom3b4ef3e082-sense-ql` equip: goalIncoherent, intentApplied goalCompletedWithoutNext at tick 7626.
  - `tribe-epsilon:loom003398683d-sense-r3` moveTo: goalIncoherent, intentApplied goalCompletedWithoutNext at tick 7839.
  - `tribe-theta:loom9bc6579ca4-sense-t0` equip: goalIncoherent, intentApplied goalCompletedWithoutNext at tick 8226.
  - `tribe-gamma:loom18c040d2cc-sense-st` equip: goalIncoherent, intentApplied goalCompletedWithoutNext at tick 8251.
  - `tribe-kappa:loomf309750e7b-sense-sy` collect: terrain (race), intentApplied collectNoRoute at tick 8328.
  - `tribe-eta:loom421b3b32c1-sense-th` equip: goalIncoherent, intentApplied goalCompletedWithoutNext at tick 8388.
  - `tribe-iota:loom8b6535c92c-sense-ux` idle: goalIncoherent, intentApplied goalCompletedWithoutNext at tick 8692.
  - `tribe-alpha:loomaa88da42d7-sense-z0` equip: goalIncoherent, intentApplied goalCompletedWithoutNext at tick 9829.
  - `tribe-delta:loom3b4ef3e082-sense-yn` idle: goalIncoherent, intentApplied goalCompletedWithoutNext at tick 10024.
  - `tribe-epsilon:loom003398683d-sense-yr` equip: goalIncoherent, intentApplied goalCompletedWithoutNext at tick 10128.
  - `tribe-eta:loom421b3b32c1-sense-zl` rest: goalIncoherent, intentApplied goalCompletedWithoutNext at tick 10225.
  - `tribe-gamma:loom18c040d2cc-sense-zr` idle: goalIncoherent, intentApplied goalCompletedWithoutNext at tick 10328.
  - `tribe-iota:loom8b6535c92c-sense-10r` equip: goalIncoherent, intentApplied goalCompletedWithoutNext at tick 10426.
  - `tribe-theta:loom9bc6579ca4-sense-112` equip: goalIncoherent, intentApplied goalCompletedWithoutNext at tick 10627.
  - `tribe-kappa:loomf309750e7b-sense-114` idle: goalIncoherent, intentApplied goalCompletedWithoutNext at tick 10682.

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
