# 10-agent ten-minute routine-v35 shelter goal live shakedown

This directory is the sanitized, bounded publication from `2026-10-16-openai-10-agent-10m-r39`.
Every number below is generated from [evidence.jsonl](./evidence.jsonl) through the
committed research publisher; [metrics.json](./metrics.json) is the complete deterministic derivation.

## Question

Can ten independently embodied Loom agents complete the bounded routine-v35 shelter-goal-10m-10 profile using only native event-driven cognition, while preserving complete per-kind perception and lifecycle evidence?
This live-model run observes independently routed agents without assigned roles, pairings, social goals, or a scripted outcome.

## Setup facts

- Source commit: `1330e39a11c16e610b249d73f3160501004ffc83`.
- Paper 1.21.1, build 133, fixed seed `20011`.
- Agents: `tribe-alpha` as `loom_aa88da42d7`, `tribe-beta` as `loom_eab7eb8acb`, `tribe-delta` as `loom_3b4ef3e082`, `tribe-epsilon` as `loom_003398683d`, `tribe-eta` as `loom_421b3b32c1`, `tribe-gamma` as `loom_18c040d2cc`, `tribe-iota` as `loom_8b6535c92c`, `tribe-kappa` as `loom_f309750e7b`, `tribe-theta` as `loom_9bc6579ca4`, `tribe-zeta` as `loom_3d8263b30e`.
- Cognition: `openai`, 378 global calls,
  40 per agent, 6 outstanding.
- External spend: $0.563451110 provider-reported actual,
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

- 30 paired self-position sample(s), the two agents' perceptions taken within 20 ticks of each other, placed two agents within eight blocks
- 172 perception(s) showed another player within eight blocks
- 5 agent(s) died under the server witness: tribe-delta, tribe-eta, tribe-iota, tribe-theta, tribe-zeta.
- 286 perceptions produced 245 decisions and 245 applied intents.
- No body process was deliberately crashed and no harness `/loombody think` command was issued.
- Native scheduled reviews by agent: {"tribe-alpha":5,"tribe-beta":5,"tribe-delta":4,"tribe-epsilon":5,"tribe-eta":4,"tribe-gamma":5,"tribe-iota":4,"tribe-kappa":5,"tribe-theta":4,"tribe-zeta":4}.
- Server-witnessed deaths: {"tribe-delta":{"attempted":4,"completed":4,"heldAtEnd":0,"lifeTicks":9457,"maximum":4,"minimum":4,"settledAfterDeath":["tribe-delta:loom3b4ef3e082-sense-xi"],"tick":9522},"tribe-eta":{"attempted":4,"completed":4,"heldAtEnd":0,"lifeTicks":9442,"maximum":4,"minimum":4,"settledAfterDeath":["tribe-eta:loom421b3b32c1-sense-wp","tribe-eta:loom421b3b32c1-sense-wt"],"tick":9507},"tribe-iota":{"attempted":4,"completed":4,"heldAtEnd":0,"lifeTicks":8999,"maximum":4,"minimum":4,"settledAfterDeath":["tribe-iota:loom8b6535c92c-sense-u5"],"tick":9064},"tribe-theta":{"attempted":4,"completed":4,"heldAtEnd":1,"lifeTicks":10541,"maximum":5,"minimum":4,"settledAfterDeath":["tribe-theta:loom9bc6579ca4-sense-10w","tribe-theta:loom9bc6579ca4-sense-118"],"tick":10606},"tribe-zeta":{"attempted":4,"completed":4,"heldAtEnd":0,"lifeTicks":8590,"maximum":4,"minimum":4,"settledAfterDeath":["tribe-zeta:loom3d8263b30e-sense-tm"],"tick":8655}}; surviving agents continued.
- Per-kind audit samples: 286; complete: true.
- Cleanup verification recorded: true.

## Behavioral gate

- Mechanical pilot verdict: **notApplicable** (loom-primary-behavior-gate/19).
- Criteria: not applicable outside the 180-second pilot profile.
- Meaningful behaviors by agent: {"tribe-alpha":33,"tribe-beta":24,"tribe-delta":15,"tribe-epsilon":14,"tribe-eta":8,"tribe-gamma":22,"tribe-iota":14,"tribe-kappa":31,"tribe-theta":11,"tribe-zeta":8}; typed goal continuity: ["tribe-alpha","tribe-beta","tribe-delta","tribe-epsilon","tribe-eta","tribe-gamma","tribe-iota","tribe-kappa","tribe-theta","tribe-zeta"].
- Non-idle goal-review actions: 205, accepted 199, confirmed 199.
- Calls by agent: {"tribe-alpha":38,"tribe-beta":28,"tribe-delta":26,"tribe-epsilon":19,"tribe-eta":16,"tribe-gamma":28,"tribe-iota":19,"tribe-kappa":38,"tribe-theta":26,"tribe-zeta":13}; peak outstanding 6.
- A pass still needs a reader to confirm: each counted meaningful behavior has an evidence-grounded aim; no counted action is justified only by being active; no cross-agent goal leakage in the typed fields.
- Readiness: **notReady** (loom-readiness-gate/19); decision validity 0.995918, typed terminal accounting 1, server truthfulness 1.
- Actions by class: {"concurrent":3,"confirmed":217,"death":4,"inapplicable":1,"ownMovementRace":1,"superseded":7,"targetRace":5,"terrain":2}; blockers: {"inapplicable":1}; legitimate races: 22; cognition failures: {}; server disputes: 0.
- Goal coherence: **incoherent**, part of the verdict (mechanically notReady); {"alreadyMetConditions":0,"completionsWithoutNext":0,"conditionSteps":124,"goalsMeasured":95,"idleSteps":0,"ignoredOwnDrops":0,"measuredSteps":183,"metCompleted":55,"metConditions":55,"metIgnored":0,"metRevised":0,"multiStepGoals":23,"pursuit":{"bars":{"chainedGoals":2,"craftChainSteps":1,"goalActiveReviewShare":0.6,"maxIdleBesideExploration":0,"minAgentGoalActiveReviewShare":0.4,"minAgentProgressingHeldTimeShare":0.25,"progressingHeldTimeShare":0.5,"shelterProgressAgentsBeforeNight":3,"substantiveCompletions":3,"substantiveMultiStepAgents":3},"description":{"crowdedGoals":0,"dangerEpisodes":32,"deaths":5,"deferredReviews":0,"discoveryGoals":6,"discoveryReveals":5,"discoveryTransitions":6,"distinctGoalItems":12,"exploringAgents":9,"horizonGoals":20,"ownDropPickups":{"accepted":17,"collectNoRoute":3}},"descriptive":["distinctGoalItems","exploringAgents","dangerEpisodes"],"measured":{"chainedGoals":2,"craftChainSteps":2,"crowdedGoals":0,"dangerEpisodes":32,"deaths":5,"deferredReviews":0,"discoveredGoals":7,"discoveryGoals":6,"discoveryReveals":5,"discoveryTransitions":6,"distinctGoalItems":12,"exploringAgents":9,"goalActiveReviewShare":0.873239,"horizonGoals":20,"idleBesideExploration":0,"minAgentGoalActiveReviewShare":0.789474,"minAgentProgressingHeldTimeShare":0,"noOpRevises":0,"oneStepGoals":39,"progressingHeldTimeShare":0.18144,"readoptedGoals":5,"substantiveCompletions":1,"substantiveMultiStepAgents":2},"schemaShaped":["goalActiveReviewShare","minAgentGoalActiveReviewShare"],"verdict":"notCleared"},"reviseItemChanges":0,"revisesUnchanged":0,"schemaGuarantees":{"alreadyMetConditions":0,"completionsWithoutNext":0,"held":true,"idleSteps":0,"ignoredOwnDrops":0,"meaning":"zero by the request schema; a nonzero count is a defect of the schema or of a backend that bypassed it, not behaviour","metIgnored":0,"reviseItemChanges":0,"revisesUnchanged":0,"stepMismatches":0,"unansweredGoals":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0},"stalled":0,"stepMismatches":0,"stepOnlySteps":19,"unadvancedSteps":40,"unansweredGoals":0,"unevidencedCredits":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0}.
- Calls the JVM decided before the mind: 2 refused to keep calls in reserve (`callReserved`), 0 sent at the ordinary tier past the pivotal ceiling (`tierFallback`), 3 answered but preempted by newer damage (`dangerPreempted`).
  - `tribe-epsilon:loom003398683d-sense-26` collect: targetRace (race), intentApplied collectItemGone at tick 706.
  - `tribe-gamma:loom18c040d2cc-sense-23` collect: targetRace (race), intentApplied collectItemGone at tick 706.
  - `tribe-eta:loom421b3b32c1-sense-2l` dig: targetRace (race), intentApplied digBlockGone at tick 861.
  - `tribe-kappa:loomf309750e7b-sense-2t` collect: targetRace (race), intentApplied collectItemGone at tick 901.
  - `tribe-delta:loom3b4ef3e082-sense-qc` moveTo: terrain (race), intentApplied moveNoRoute at tick 7613.
  - `tribe-delta:loom3b4ef3e082-sense-s0` moveTo: terrain (race), intentApplied moveNoRoute at tick 8046.
  - `tribe-iota:loom8b6535c92c-sense-u5` place: death (race), intentApplied placeCancelled at tick 9065.
  - `tribe-iota:loom8b6535c92c-sense-v0` place: concurrent (race), intentApplied controlNotReady at tick 8926.
  - `tribe-iota:loom8b6535c92c-sense-vb` place: concurrent (race), intentApplied controlNotReady at tick 8997.
  - `tribe-iota:loom8b6535c92c-sense-vo` place: concurrent (race), intentApplied controlNotReady at tick 9051.
  - `tribe-theta:loom9bc6579ca4-sense-vm` place: inapplicable, intentApplied placeItemNotHeld at tick 9241.
  - `tribe-delta:loom3b4ef3e082-sense-ww` dig: ownMovementRace (race), intentApplied digOutOfReach at tick 9374.
  - `tribe-delta:loom3b4ef3e082-sense-x3` flee: targetRace (race), intentApplied retreatThreatGone at tick 9449.
  - `tribe-eta:loom421b3b32c1-sense-wp` flee: death (race), intentApplied controlUnavailable at tick 9508.
  - `tribe-delta:loom3b4ef3e082-sense-xi` flee: death (race), intentApplied controlUnavailable at tick 9523.
  - `tribe-theta:loom9bc6579ca4-sense-xt` flee: superseded (race), retreating retreatSuperseded at tick 9965.
  - `tribe-theta:loom9bc6579ca4-sense-zv` flee: superseded (race), retreating retreatSuperseded at tick 10454.
  - `tribe-theta:loom9bc6579ca4-sense-10j` flee: superseded (race), retreating retreatSuperseded at tick 10575.
  - `tribe-theta:loom9bc6579ca4-sense-10u` flee: superseded (race), retreating retreatSuperseded at tick 10579.
  - `tribe-theta:loom9bc6579ca4-sense-10w` flee: death (race), intentApplied accepted at tick 10607.
  - `tribe-eta:loom421b3b32c1-sense-wf` attack: superseded (race), decisionDiscarded dangerPreempted at tick 9466.
  - `tribe-delta:loom3b4ef3e082-sense-xe` place: superseded (race), decisionDiscarded dangerPreempted at tick 9474.
  - `tribe-theta:loom9bc6579ca4-sense-103` attack: superseded (race), decisionDiscarded dangerPreempted at tick 10423.

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
node research/bin/loom-tribe.js run --config research/config/tribe-shelter-goal-10m-10.json
```

The harness validates a prospective manifest before launch, builds the three runtime components, verifies the
pinned Paper jar, creates a disposable server, enforces call and lifecycle bounds, publishes only normalized
evidence, validates it again, and removes the mutable run directory.
