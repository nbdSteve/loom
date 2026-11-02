# 10-agent ten-minute routine-v33 balanced-envelope live shakedown

This directory is the sanitized, bounded publication from `2026-10-16-openai-10-agent-10m-r35`.
Every number below is generated from [evidence.jsonl](./evidence.jsonl) through the
committed research publisher; [metrics.json](./metrics.json) is the complete deterministic derivation.

## Question

Can ten independently embodied Loom agents complete the bounded routine-v33 balanced-envelope-10m-10 profile using only native event-driven cognition, while preserving complete per-kind perception and lifecycle evidence?
This live-model run observes independently routed agents without assigned roles, pairings, social goals, or a scripted outcome.

## Setup facts

- Source commit: `e53584c43336cf7f6e02d8ba2aa46773d687e39c`.
- Paper 1.21.1, build 133, fixed seed `20011`.
- Agents: `tribe-alpha` as `loom_aa88da42d7`, `tribe-beta` as `loom_eab7eb8acb`, `tribe-delta` as `loom_3b4ef3e082`, `tribe-epsilon` as `loom_003398683d`, `tribe-eta` as `loom_421b3b32c1`, `tribe-gamma` as `loom_18c040d2cc`, `tribe-iota` as `loom_8b6535c92c`, `tribe-kappa` as `loom_f309750e7b`, `tribe-theta` as `loom_9bc6579ca4`, `tribe-zeta` as `loom_3d8263b30e`.
- Cognition: `openai`, 378 global calls,
  40 per agent, 6 outstanding.
- External spend: $2.092221690 provider-reported actual,
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

- 44 paired self-position sample(s), the two agents' perceptions taken within 20 ticks of each other, placed two agents within eight blocks
- 155 perception(s) showed another player within eight blocks
- 9 agent(s) died under the server witness: tribe-alpha, tribe-beta, tribe-delta, tribe-epsilon, tribe-gamma, tribe-iota, tribe-kappa, tribe-theta, tribe-zeta.
- 362 perceptions produced 226 decisions and 226 applied intents.
- No body process was deliberately crashed and no harness `/loombody think` command was issued.
- Native scheduled reviews by agent: {"tribe-alpha":5,"tribe-beta":5,"tribe-delta":5,"tribe-epsilon":5,"tribe-eta":5,"tribe-gamma":5,"tribe-iota":5,"tribe-kappa":4,"tribe-theta":4,"tribe-zeta":4}.
- Server-witnessed deaths: {"tribe-alpha":{"attempted":5,"completed":5,"heldAtEnd":0,"lifeTicks":10581,"maximum":5,"minimum":4,"settledAfterDeath":["tribe-alpha:loomaa88da42d7-sense-11f"],"tick":10652},"tribe-beta":{"attempted":5,"completed":5,"heldAtEnd":0,"lifeTicks":11397,"maximum":5,"minimum":5,"settledAfterDeath":["tribe-beta:loomeab7eb8acb-sense-13l","tribe-beta:loomeab7eb8acb-sense-13w"],"tick":11468},"tribe-delta":{"attempted":5,"completed":5,"heldAtEnd":0,"lifeTicks":11158,"maximum":5,"minimum":5,"settledAfterDeath":["tribe-delta:loom3b4ef3e082-sense-12r","tribe-delta:loom3b4ef3e082-sense-12v"],"tick":11229},"tribe-epsilon":{"attempted":5,"completed":5,"heldAtEnd":0,"lifeTicks":10865,"maximum":5,"minimum":5,"settledAfterDeath":["tribe-epsilon:loom003398683d-sense-11p","tribe-epsilon:loom003398683d-sense-11r"],"tick":10936},"tribe-gamma":{"attempted":5,"completed":5,"heldAtEnd":0,"lifeTicks":11402,"maximum":5,"minimum":5,"settledAfterDeath":["tribe-gamma:loom18c040d2cc-sense-14b"],"tick":11473},"tribe-iota":{"attempted":5,"completed":5,"heldAtEnd":0,"lifeTicks":11549,"maximum":5,"minimum":5,"settledAfterDeath":["tribe-iota:loom8b6535c92c-sense-14g"],"tick":11620},"tribe-kappa":{"attempted":4,"completed":4,"heldAtEnd":1,"lifeTicks":10651,"maximum":5,"minimum":4,"settledAfterDeath":["tribe-kappa:loomf309750e7b-sense-10k"],"tick":10722},"tribe-theta":{"attempted":4,"completed":4,"heldAtEnd":1,"lifeTicks":10621,"maximum":5,"minimum":4,"settledAfterDeath":["tribe-theta:loom9bc6579ca4-sense-10u","tribe-theta:loom9bc6579ca4-sense-10y"],"tick":10692},"tribe-zeta":{"attempted":4,"completed":4,"heldAtEnd":1,"lifeTicks":11007,"maximum":5,"minimum":5,"settledAfterDeath":["tribe-zeta:loom3d8263b30e-sense-12b"],"tick":11078}}; surviving agents continued.
- Per-kind audit samples: 362; complete: true.
- Cleanup verification recorded: true.

## Behavioral gate

- Mechanical pilot verdict: **notApplicable** (loom-primary-behavior-gate/18).
- Criteria: not applicable outside the 180-second pilot profile.
- Meaningful behaviors by agent: {"tribe-alpha":8,"tribe-beta":7,"tribe-delta":13,"tribe-epsilon":5,"tribe-eta":12,"tribe-gamma":10,"tribe-iota":12,"tribe-kappa":5,"tribe-theta":7,"tribe-zeta":10}; typed goal continuity: ["tribe-alpha","tribe-beta","tribe-delta","tribe-epsilon","tribe-eta","tribe-gamma","tribe-iota","tribe-kappa","tribe-theta","tribe-zeta"].
- Non-idle goal-review actions: 102, accepted 97, confirmed 96.
- Calls by agent: {"tribe-alpha":27,"tribe-beta":23,"tribe-delta":25,"tribe-epsilon":24,"tribe-eta":29,"tribe-gamma":25,"tribe-iota":24,"tribe-kappa":15,"tribe-theta":21,"tribe-zeta":24}; peak outstanding 6.
- A pass still needs a reader to confirm: each counted meaningful behavior has an evidence-grounded aim; no counted action is justified only by being active; no cross-agent goal leakage in the typed fields.
- Readiness: **notReady** (loom-readiness-gate/18); decision validity 0.995575, typed terminal accounting 1, server truthfulness 1.
- Actions by class: {"concurrent":1,"confirmed":183,"death":4,"inapplicable":1,"superseded":31,"targetRace":6}; blockers: {"inapplicable":1}; legitimate races: 42; cognition failures: {}; server disputes: 0.
- Goal coherence: **incoherent**, part of the verdict (mechanically notReady); {"alreadyMetConditions":0,"completionsWithoutNext":0,"conditionSteps":38,"goalsMeasured":37,"idleSteps":0,"ignoredOwnDrops":0,"measuredSteps":86,"metCompleted":20,"metConditions":20,"metIgnored":0,"metRevised":0,"multiStepGoals":5,"pursuit":{"bars":{"chainedGoals":2,"craftChainSteps":1,"goalActiveReviewShare":0.6,"maxIdleBesideExploration":0,"minAgentGoalActiveReviewShare":0.4,"minAgentProgressingHeldTimeShare":0.25,"progressingHeldTimeShare":0.5,"substantiveCompletions":3,"substantiveMultiStepAgents":3},"description":{"crowdedGoals":0,"dangerEpisodes":124,"deaths":9,"deferredReviews":1,"discoveryGoals":7,"discoveryReveals":7,"discoveryTransitions":7,"distinctGoalItems":12,"exploringAgents":10,"horizonGoals":14,"ownDropPickups":{"accepted":17}},"descriptive":["distinctGoalItems","exploringAgents","dangerEpisodes"],"measured":{"chainedGoals":3,"craftChainSteps":5,"crowdedGoals":0,"dangerEpisodes":124,"deaths":9,"deferredReviews":1,"discoveredGoals":6,"discoveryGoals":7,"discoveryReveals":7,"discoveryTransitions":7,"distinctGoalItems":12,"exploringAgents":10,"goalActiveReviewShare":0.941176,"horizonGoals":14,"idleBesideExploration":0,"minAgentGoalActiveReviewShare":0.75,"minAgentProgressingHeldTimeShare":0,"noOpRevises":0,"oneStepGoals":14,"progressingHeldTimeShare":0.280169,"readoptedGoals":0,"substantiveCompletions":0,"substantiveMultiStepAgents":2},"schemaShaped":["goalActiveReviewShare","minAgentGoalActiveReviewShare"],"verdict":"notCleared"},"reviseItemChanges":0,"revisesUnchanged":0,"schemaGuarantees":{"alreadyMetConditions":0,"completionsWithoutNext":0,"held":true,"idleSteps":0,"ignoredOwnDrops":0,"meaning":"zero by the request schema; a nonzero count is a defect of the schema or of a backend that bypassed it, not behaviour","metIgnored":0,"reviseItemChanges":0,"revisesUnchanged":0,"stepMismatches":0,"unansweredGoals":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0},"stalled":0,"stepMismatches":0,"stepOnlySteps":14,"unadvancedSteps":34,"unansweredGoals":0,"unevidencedCredits":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":0}.
- Calls the JVM decided before the mind: 0 refused to keep calls in reserve (`callReserved`), 0 sent at the ordinary tier past the pivotal ceiling (`tierFallback`), 0 answered but preempted by newer damage (`dangerPreempted`).
  - `tribe-alpha:loomaa88da42d7-sense-2q` dig: targetRace (race), intentApplied digBlockGone at tick 869.
  - `tribe-alpha:loomaa88da42d7-sense-hh` craft: inapplicable, intentApplied craftQuantityExceeded at tick 5107.
  - `tribe-delta:loom3b4ef3e082-sense-s8` collect: targetRace (race), intentApplied collectItemGone at tick 8205.
  - `tribe-kappa:loomf309750e7b-sense-rx` dig: targetRace (race), intentApplied digBlockGone at tick 8372.
  - `tribe-alpha:loomaa88da42d7-sense-vj` flee: superseded (race), retreating retreatSuperseded at tick 9338.
  - `tribe-delta:loom3b4ef3e082-sense-xb` flee: superseded (race), retreating retreatSuperseded at tick 9809.
  - `tribe-epsilon:loom003398683d-sense-wv` flee: superseded (race), retreating retreatSuperseded at tick 9845.
  - `tribe-alpha:loomaa88da42d7-sense-xh` flee: superseded (race), retreating retreatSuperseded at tick 9845.
  - `tribe-alpha:loomaa88da42d7-sense-yb` flee: superseded (race), retreating retreatSuperseded at tick 10080.
  - `tribe-alpha:loomaa88da42d7-sense-ys` flee: superseded (race), retreating retreatSuperseded at tick 10113.
  - `tribe-alpha:loomaa88da42d7-sense-yy` flee: superseded (race), retreating retreatSuperseded at tick 10168.
  - `tribe-beta:loomeab7eb8acb-sense-yk` flee: superseded (race), retreating retreatSuperseded at tick 10179.
  - `tribe-alpha:loomaa88da42d7-sense-z3` flee: superseded (race), retreating retreatSuperseded at tick 10193.
  - `tribe-alpha:loomaa88da42d7-sense-zc` flee: superseded (race), retreating retreatSuperseded at tick 10210.
  - `tribe-theta:loom9bc6579ca4-sense-yx` flee: superseded (race), retreating retreatSuperseded at tick 10284.
  - `tribe-epsilon:loom003398683d-sense-yv` moveTo: concurrent (race), intentApplied controlNotReady at tick 10240.
  - `tribe-kappa:loomf309750e7b-sense-z0` flee: superseded (race), retreating retreatSuperseded at tick 10369.
  - `tribe-alpha:loomaa88da42d7-sense-107` flee: superseded (race), retreating retreatSuperseded at tick 10425.
  - `tribe-theta:loom9bc6579ca4-sense-zr` flee: superseded (race), retreating retreatSuperseded at tick 10490.
  - `tribe-alpha:loomaa88da42d7-sense-10e` flee: superseded (race), retreating retreatSuperseded at tick 10450.
  - `tribe-gamma:loom18c040d2cc-sense-zv` flee: superseded (race), retreating retreatSuperseded at tick 10480.
  - `tribe-alpha:loomaa88da42d7-sense-10i` flee: targetRace (race), intentApplied retreatThreatGone at tick 10471.
  - `tribe-theta:loom9bc6579ca4-sense-10j` flee: superseded (race), retreating retreatSuperseded at tick 10677.
  - `tribe-epsilon:loom003398683d-sense-10f` flee: superseded (race), retreating retreatSuperseded at tick 10717.
  - `tribe-theta:loom9bc6579ca4-sense-10u` flee: death (race), intentApplied accepted at tick 10693.
  - `tribe-zeta:loom3d8263b30e-sense-10t` flee: superseded (race), retreating retreatSuperseded at tick 10743.
  - `tribe-epsilon:loom003398683d-sense-10q` flee: superseded (race), retreating retreatSuperseded at tick 10776.
  - `tribe-epsilon:loom003398683d-sense-111` flee: superseded (race), retreating retreatSuperseded at tick 10822.
  - `tribe-zeta:loom3d8263b30e-sense-11i` flee: superseded (race), retreating retreatSuperseded at tick 10945.
  - `tribe-zeta:loom3d8263b30e-sense-11t` flee: superseded (race), retreating retreatSuperseded at tick 10977.
  - `tribe-gamma:loom18c040d2cc-sense-11v` flee: superseded (race), retreating retreatSuperseded at tick 11005.
  - `tribe-zeta:loom3d8263b30e-sense-12b` flee: death (race), intentApplied accepted at tick 11079.
  - `tribe-beta:loomeab7eb8acb-sense-12s` flee: targetRace (race), intentApplied retreatThreatGone at tick 11257.
  - `tribe-iota:loom8b6535c92c-sense-12y` flee: targetRace (race), intentApplied retreatThreatGone at tick 11268.
  - `tribe-iota:loom8b6535c92c-sense-13b` flee: superseded (race), retreating retreatSuperseded at tick 11359.
  - `tribe-gamma:loom18c040d2cc-sense-13e` flee: superseded (race), retreating retreatSuperseded at tick 11359.
  - `tribe-iota:loom8b6535c92c-sense-13f` flee: superseded (race), retreating retreatSuperseded at tick 11434.
  - `tribe-gamma:loom18c040d2cc-sense-13i` flee: superseded (race), retreating retreatSuperseded at tick 11419.
  - `tribe-beta:loomeab7eb8acb-sense-13l` flee: death (race), intentApplied accepted at tick 11469.
  - `tribe-eta:loom421b3b32c1-sense-13o` flee: superseded (race), retreating retreatSuperseded at tick 11554.
  - `tribe-eta:loom421b3b32c1-sense-13u` flee: superseded (race), retreating retreatSuperseded at tick 11568.
  - `tribe-iota:loom8b6535c92c-sense-14g` flee: death (race), intentApplied controlUnavailable at tick 11621.
  - `tribe-eta:loom421b3b32c1-sense-14l` flee: superseded (race), retreating retreatSuperseded at tick 11761.

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
node research/bin/loom-tribe.js run --config research/config/tribe-balanced-envelope-10m-10.json
```

The harness validates a prospective manifest before launch, builds the three runtime components, verifies the
pinned Paper jar, creates a disposable server, enforces call and lifecycle bounds, publishes only normalized
evidence, validates it again, and removes the mutable run directory.
