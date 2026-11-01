# 10-agent ten-minute routine-v36 held objective live shakedown

This directory is the sanitized, bounded publication from `2026-10-16-openai-10-agent-10m-r44`.
Every number below is generated from [evidence.jsonl](./evidence.jsonl) through the
committed research publisher; [metrics.json](./metrics.json) is the complete deterministic derivation.

## Question

Can ten independently embodied Loom agents complete the bounded routine-v36 held-objective-follow-up-10m-10 profile using only native event-driven cognition, while preserving complete per-kind perception and lifecycle evidence?
This live-model run observes independently routed agents without assigned roles, pairings, social goals, or a scripted outcome.

## Setup facts

- Source commit: `555e51f54d86964bf0e799d67a22dff380721c1e`.
- Paper 1.21.1, build 133, fixed seed `20011`.
- Agents: `tribe-alpha` as `loom_aa88da42d7`, `tribe-beta` as `loom_eab7eb8acb`, `tribe-delta` as `loom_3b4ef3e082`, `tribe-epsilon` as `loom_003398683d`, `tribe-eta` as `loom_421b3b32c1`, `tribe-gamma` as `loom_18c040d2cc`, `tribe-iota` as `loom_8b6535c92c`, `tribe-kappa` as `loom_f309750e7b`, `tribe-theta` as `loom_9bc6579ca4`, `tribe-zeta` as `loom_3d8263b30e`.
- Cognition: `openai`, 378 global calls,
  40 per agent, 6 outstanding.
- External spend: $0.400170800 provider-reported actual,
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

- 42 paired self-position sample(s), the two agents' perceptions taken within 20 ticks of each other, placed two agents within eight blocks
- 136 perception(s) showed another player within eight blocks
- 1 attack intent(s) were applied
- 366 perceptions produced 350 decisions and 350 applied intents.
- No body process was deliberately crashed and no harness `/loombody think` command was issued.
- Native scheduled reviews by agent: {"tribe-alpha":5,"tribe-beta":5,"tribe-delta":5,"tribe-epsilon":5,"tribe-eta":5,"tribe-gamma":5,"tribe-iota":5,"tribe-kappa":5,"tribe-theta":5,"tribe-zeta":5}.
- No agent died.
- Per-kind audit samples: 366; complete: true.
- Cleanup verification recorded: true.

## Behavioral gate

- Mechanical pilot verdict: **notApplicable** (loom-primary-behavior-gate/20).
- Criteria: not applicable outside the 180-second pilot profile.
- Meaningful behaviors by agent: {"tribe-alpha":33,"tribe-beta":32,"tribe-delta":32,"tribe-epsilon":23,"tribe-eta":22,"tribe-gamma":24,"tribe-iota":20,"tribe-kappa":29,"tribe-theta":28,"tribe-zeta":31}; typed goal continuity: ["tribe-alpha","tribe-beta","tribe-delta","tribe-epsilon","tribe-eta","tribe-gamma","tribe-iota","tribe-kappa","tribe-theta","tribe-zeta"].
- Non-idle goal-review actions: 320, accepted 301, confirmed 301.
- Calls by agent: {"tribe-alpha":38,"tribe-beta":38,"tribe-delta":38,"tribe-epsilon":37,"tribe-eta":30,"tribe-gamma":34,"tribe-iota":25,"tribe-kappa":38,"tribe-theta":35,"tribe-zeta":38}; peak outstanding 6.
- A pass still needs a reader to confirm: each counted meaningful behavior has an evidence-grounded aim; no counted action is justified only by being active; no cross-agent goal leakage in the typed fields.
- Readiness: **notReady** (loom-readiness-gate/20); decision validity 0.994286, typed terminal accounting 1, server truthfulness 1.
- Actions by class: {"confirmed":306,"inapplicable":2,"superseded":1,"targetRace":11,"terrain":7}; blockers: {"inapplicable":2}; legitimate races: 19; cognition failures: {}; server disputes: 0.
- Goal coherence: **incoherent**, part of the verdict (mechanically notReady); {"alreadyMetConditions":0,"completionsWithoutNext":0,"conditionSteps":194,"goalsMeasured":85,"idleSteps":0,"ignoredOwnDrops":0,"measuredSteps":279,"metCompleted":45,"metConditions":45,"metIgnored":0,"metRevised":0,"multiStepGoals":43,"pursuit":{"bars":{"chainedGoals":2,"craftChainSteps":1,"goalActiveReviewShare":0.6,"maxIdleBesideExploration":0,"minAgentGoalActiveReviewShare":0.4,"minAgentProgressingHeldTimeShare":0.25,"progressingHeldTimeShare":0.5,"shelterProgressAgentsBeforeNight":3,"substantiveCompletions":3,"substantiveMultiStepAgents":3},"description":{"crowdedGoals":0,"dangerEpisodes":7,"deaths":0,"deferredReviews":0,"discoveryGoals":7,"discoveryReveals":6,"discoveryTransitions":6,"distinctGoalItems":18,"exploringAgents":10,"horizonGoals":21,"ownDropPickups":{"accepted":35,"ownDropNotSensed":2,"ownDropUnreachable":1}},"descriptive":["distinctGoalItems","exploringAgents","dangerEpisodes"],"measured":{"chainedGoals":2,"craftChainSteps":6,"crowdedGoals":0,"dangerEpisodes":7,"deaths":0,"deferredReviews":0,"discoveredGoals":6,"discoveryGoals":7,"discoveryReveals":6,"discoveryTransitions":6,"distinctGoalItems":18,"exploringAgents":10,"goalActiveReviewShare":0.848397,"horizonGoals":21,"idleBesideExploration":0,"minAgentGoalActiveReviewShare":0.735294,"minAgentProgressingHeldTimeShare":0.110916,"noOpRevises":0,"oneStepGoals":0,"progressingHeldTimeShare":0.381915,"readoptedGoals":1,"substantiveCompletions":3,"substantiveMultiStepAgents":3},"schemaShaped":["goalActiveReviewShare","minAgentGoalActiveReviewShare"],"verdict":"notCleared"},"reviseItemChanges":0,"revisesUnchanged":0,"schemaGuarantees":{"alreadyMetConditions":0,"completionsWithoutNext":0,"held":true,"idleSteps":0,"ignoredOwnDrops":0,"meaning":"zero by the request schema; a nonzero count is a defect of the schema or of a backend that bypassed it, not behaviour","metIgnored":0,"reviseItemChanges":0,"revisesUnchanged":0,"stepMismatches":0,"unansweredGoals":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0},"stalled":0,"stepMismatches":0,"stepOnlySteps":28,"unadvancedSteps":57,"unansweredGoals":0,"unevidencedCredits":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0}.
- Calls the JVM decided before the mind: 11 refused to keep calls in reserve (`callReserved`), 0 sent at the ordinary tier past the pivotal ceiling (`tierFallback`), 0 answered but preempted by newer damage (`dangerPreempted`).
  - `tribe-epsilon:loom003398683d-sense-26` collect: targetRace (race), intentApplied collectItemGone at tick 704.
  - `tribe-gamma:loom18c040d2cc-sense-23` collect: targetRace (race), intentApplied collectItemGone at tick 706.
  - `tribe-epsilon:loom003398683d-sense-30` collect: targetRace (race), intentApplied collectItemGone at tick 899.
  - `tribe-kappa:loomf309750e7b-sense-2v` collect: targetRace (race), intentApplied collectItemGone at tick 900.
  - `tribe-zeta:loom3d8263b30e-sense-46` collect: targetRace (race), intentApplied collectItemGone at tick 1300.
  - `tribe-kappa:loomf309750e7b-sense-4g` collect: targetRace (race), intentApplied collectItemGone at tick 1303.
  - `tribe-zeta:loom3d8263b30e-sense-50` collect: targetRace (race), intentApplied collectItemGone at tick 1498.
  - `tribe-eta:loom421b3b32c1-sense-al` moveTo: terrain (race), intentApplied moveNoRoute at tick 3011.
  - `tribe-eta:loom421b3b32c1-sense-b0` moveTo: terrain (race), intentApplied moveNoRoute at tick 3109.
  - `tribe-theta:loom9bc6579ca4-sense-ci` dig: targetRace (race), intentApplied digBlockGone at tick 3659.
  - `tribe-theta:loom9bc6579ca4-sense-d9` craft: targetRace (race), intentApplied craftIngredientsChanged at tick 3802.
  - `tribe-kappa:loomf309750e7b-sense-g0` moveTo: terrain (race), intentApplied moveNoRoute at tick 4529.
  - `tribe-kappa:loomf309750e7b-sense-k1` moveTo: terrain (race), intentApplied moveNoRoute at tick 5745.
  - `tribe-iota:loom8b6535c92c-sense-l3` dig: targetRace (race), intentApplied digBlockChanged at tick 6012.
  - `tribe-gamma:loom18c040d2cc-sense-md` moveTo: terrain (race), intentApplied moveNoRoute at tick 6296.
  - `tribe-kappa:loomf309750e7b-sense-ms` place: targetRace (race), intentApplied placeBlockChanged at tick 6399.
  - `tribe-epsilon:loom003398683d-sense-uf` moveTo: terrain (race), intentApplied moveNoRoute at tick 8511.
  - `tribe-theta:loom9bc6579ca4-sense-yu` place: inapplicable, intentApplied placeItemNotHeld at tick 9800.
  - `tribe-epsilon:loom003398683d-sense-10m` dig: superseded (race), intentApplied digCancelled at tick 10409.
  - `tribe-theta:loom9bc6579ca4-sense-10y` attack: inapplicable, intentApplied attackOutOfRange at tick 10416.
  - `tribe-epsilon:loom003398683d-sense-126` moveTo: terrain (race), intentApplied moveNoRoute at tick 10605.

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
