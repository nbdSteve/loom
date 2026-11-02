# 10-agent ten-minute routine-v31 discovery-goal live shakedown

This directory is the sanitized, bounded publication from `2026-10-16-openai-10-agent-10m-r24`.
Every number below is generated from [evidence.jsonl](./evidence.jsonl) through the
committed research publisher; [metrics.json](./metrics.json) is the complete deterministic derivation.

## Question

Can ten independently embodied Loom agents complete the bounded routine-v31 discovery-goal-10m-10 profile using only native event-driven cognition, while preserving complete per-kind perception and lifecycle evidence?
This live-model run observes independently routed agents without assigned roles, pairings, social goals, or a scripted outcome.

## Setup facts

- Source commit: `153b41301b943c481fcd27f27e3a3be29635d167`.
- Paper 1.21.1, build 133, fixed seed `20011`.
- Agents: `tribe-alpha` as `loom_aa88da42d7`, `tribe-beta` as `loom_eab7eb8acb`, `tribe-delta` as `loom_3b4ef3e082`, `tribe-epsilon` as `loom_003398683d`, `tribe-eta` as `loom_421b3b32c1`, `tribe-gamma` as `loom_18c040d2cc`, `tribe-iota` as `loom_8b6535c92c`, `tribe-kappa` as `loom_f309750e7b`, `tribe-theta` as `loom_9bc6579ca4`, `tribe-zeta` as `loom_3d8263b30e`.
- Cognition: `openai`, 150 global calls,
  24 per agent, 6 outstanding.
- External spend: $1.129669310 provider-reported actual,
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

- 63 paired self-position sample(s), the two agents' perceptions taken within 20 ticks of each other, placed two agents within eight blocks
- 139 perception(s) showed another player within eight blocks
- 1 attack intent(s) were applied
- 10 agent(s) died under the server witness: tribe-alpha, tribe-beta, tribe-delta, tribe-epsilon, tribe-eta, tribe-gamma, tribe-iota, tribe-kappa, tribe-theta, tribe-zeta.
- 295 perceptions produced 146 decisions and 146 applied intents.
- No body process was deliberately crashed and no harness `/loombody think` command was issued.
- Native scheduled reviews by agent: {"tribe-alpha":3,"tribe-beta":3,"tribe-delta":4,"tribe-epsilon":4,"tribe-eta":4,"tribe-gamma":3,"tribe-iota":3,"tribe-kappa":3,"tribe-theta":3,"tribe-zeta":4}.
- Server-witnessed deaths: {"tribe-alpha":{"attempted":3,"completed":3,"lifeTicks":7197,"maximum":3,"minimum":3,"settledAfterDeath":["tribe-alpha:loomaa88da42d7-sense-p3"],"tick":7256},"tribe-beta":{"attempted":3,"completed":3,"lifeTicks":7368,"maximum":4,"minimum":3,"settledAfterDeath":[],"tick":7427},"tribe-delta":{"attempted":4,"completed":4,"lifeTicks":8184,"maximum":4,"minimum":3,"settledAfterDeath":[],"tick":8243},"tribe-epsilon":{"attempted":4,"completed":4,"lifeTicks":7702,"maximum":4,"minimum":3,"settledAfterDeath":["tribe-epsilon:loom003398683d-sense-qz"],"tick":7761},"tribe-eta":{"attempted":4,"completed":4,"lifeTicks":8096,"maximum":4,"minimum":3,"settledAfterDeath":["tribe-eta:loom421b3b32c1-sense-rx"],"tick":8155},"tribe-gamma":{"attempted":3,"completed":3,"lifeTicks":6729,"maximum":3,"minimum":3,"settledAfterDeath":["tribe-gamma:loom18c040d2cc-sense-nl"],"tick":6788},"tribe-iota":{"attempted":3,"completed":3,"lifeTicks":7806,"maximum":4,"minimum":3,"settledAfterDeath":["tribe-iota:loom8b6535c92c-sense-qt"],"tick":7865},"tribe-kappa":{"attempted":3,"completed":3,"lifeTicks":6683,"maximum":3,"minimum":3,"settledAfterDeath":["tribe-kappa:loomf309750e7b-sense-nk"],"tick":6742},"tribe-theta":{"attempted":3,"completed":3,"lifeTicks":7754,"maximum":4,"minimum":3,"settledAfterDeath":[],"tick":7813},"tribe-zeta":{"attempted":4,"completed":4,"lifeTicks":9426,"maximum":4,"minimum":4,"settledAfterDeath":[],"tick":9485}}; surviving agents continued.
- Per-kind audit samples: 295; complete: true.
- Cleanup verification recorded: true.

## Behavioral gate

- Mechanical pilot verdict: **notApplicable** (loom-primary-behavior-gate/14).
- Criteria: not applicable outside the 180-second pilot profile.
- Meaningful behaviors by agent: {"tribe-alpha":3,"tribe-beta":4,"tribe-delta":6,"tribe-epsilon":4,"tribe-eta":6,"tribe-gamma":3,"tribe-iota":2,"tribe-kappa":4,"tribe-theta":4,"tribe-zeta":4}; typed goal continuity: ["tribe-alpha","tribe-beta","tribe-delta","tribe-epsilon","tribe-eta","tribe-gamma","tribe-kappa","tribe-theta","tribe-zeta"].
- Non-idle goal-review actions: 54, accepted 50, confirmed 47.
- Calls by agent: {"tribe-alpha":14,"tribe-beta":14,"tribe-delta":18,"tribe-epsilon":15,"tribe-eta":15,"tribe-gamma":18,"tribe-iota":10,"tribe-kappa":16,"tribe-theta":14,"tribe-zeta":15}; peak outstanding 6.
- A pass still needs a reader to confirm: each counted meaningful behavior has an evidence-grounded aim; no counted action is justified only by being active; no cross-agent goal leakage in the typed fields.
- Readiness: **notReady** (loom-readiness-gate/12); decision validity 0.986301, typed terminal accounting 1, server truthfulness 1.
- Actions by class: {"concurrent":1,"confirmed":126,"death":3,"goalIncoherent":1,"inapplicable":1,"superseded":11,"targetRace":3}; blockers: {"goalIncoherent":1,"inapplicable":1}; legitimate races: 18; cognition failures: {}; server disputes: 0.
- Goal coherence: **incoherent**, part of the verdict (mechanically notReady); {"alreadyMetConditions":0,"completionsWithoutNext":0,"conditionSteps":14,"goalsMeasured":21,"idleSteps":0,"ignoredOwnDrops":0,"measuredSteps":39,"metCompleted":8,"metConditions":8,"metIgnored":0,"metRevised":0,"multiStepGoals":2,"pursuit":{"bars":{"chainedGoals":2,"craftChainSteps":1,"goalActiveReviewShare":0.6,"maxIdleBesideExploration":0,"minAgentGoalActiveReviewShare":0.4,"minAgentProgressingHeldTimeShare":0.25,"progressingHeldTimeShare":0.5,"substantiveCompletions":3,"substantiveMultiStepAgents":3},"descriptive":["distinctGoalItems","exploringAgents","dangerEpisodes"],"measured":{"chainedGoals":0,"craftChainSteps":0,"crowdedGoals":0,"dangerEpisodes":92,"deaths":10,"deferredReviews":0,"discoveredGoals":4,"discoveryGoals":9,"discoveryReveals":3,"discoveryTransitions":3,"distinctGoalItems":10,"exploringAgents":8,"goalActiveReviewShare":0.851852,"horizonGoals":5,"idleBesideExploration":0,"minAgentGoalActiveReviewShare":0.5,"minAgentProgressingHeldTimeShare":0,"noOpRevises":0,"oneStepGoals":7,"progressingHeldTimeShare":0.117953,"readoptedGoals":0,"substantiveCompletions":0,"substantiveMultiStepAgents":0},"schemaShaped":["goalActiveReviewShare","minAgentGoalActiveReviewShare"],"verdict":"notCleared"},"reviseItemChanges":0,"revisesUnchanged":0,"schemaGuarantees":{"alreadyMetConditions":0,"completionsWithoutNext":0,"held":false,"idleSteps":0,"ignoredOwnDrops":0,"meaning":"zero by the request schema; a nonzero count is a defect of the schema or of a backend that bypassed it, not behaviour","metIgnored":0,"reviseItemChanges":0,"revisesUnchanged":0,"stepMismatches":0,"unansweredGoals":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":1},"stalled":0,"stepMismatches":0,"stepOnlySteps":8,"unadvancedSteps":17,"unansweredGoals":0,"unevidencedCredits":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":1}.
  - `tribe-theta:loom9bc6579ca4-sense-3g` moveTo: goalIncoherent, intentApplied goalStepUnproductive at tick 1089.
  - `tribe-epsilon:loom003398683d-sense-a9` collect: targetRace (race), intentApplied collectItemGone at tick 3087.
  - `tribe-gamma:loom18c040d2cc-sense-a9` collect: targetRace (race), intentApplied collectItemGone at tick 3097.
  - `tribe-beta:loomeab7eb8acb-sense-h8` moveTo: superseded (race), moved movementCancelled at tick 5117.
  - `tribe-gamma:loom18c040d2cc-sense-ke` flee: superseded (race), retreating retreatSuperseded at tick 6084.
  - `tribe-gamma:loom18c040d2cc-sense-kn` flee: superseded (race), retreating retreatSuperseded at tick 6143.
  - `tribe-gamma:loom18c040d2cc-sense-kx` flee: targetRace (race), intentApplied retreatThreatGone at tick 6143.
  - `tribe-gamma:loom18c040d2cc-sense-lb` collect: concurrent (race), intentApplied controlNotReady at tick 6209.
  - `tribe-theta:loom9bc6579ca4-sense-l2` flee: superseded (race), retreating retreatSuperseded at tick 6346.
  - `tribe-kappa:loomf309750e7b-sense-lf` flee: superseded (race), retreating retreatSuperseded at tick 6346.
  - `tribe-kappa:loomf309750e7b-sense-ly` moveTo: superseded (race), moved movementCancelled at tick 6424.
  - `tribe-gamma:loom18c040d2cc-sense-mk` flee: superseded (race), retreating retreatSuperseded at tick 6585.
  - `tribe-iota:loom8b6535c92c-sense-mp` flee: superseded (race), retreating retreatSuperseded at tick 6750.
  - `tribe-gamma:loom18c040d2cc-sense-nl` flee: death (race), intentApplied accepted at tick 6789.
  - `tribe-eta:loom421b3b32c1-sense-mu` flee: superseded (race), retreating retreatSuperseded at tick 6816.
  - `tribe-alpha:loomaa88da42d7-sense-nl` flee: superseded (race), retreating retreatSuperseded at tick 6936.
  - `tribe-theta:loom9bc6579ca4-sense-pt` flee: superseded (race), retreating retreatSuperseded at tick 7660.
  - `tribe-iota:loom8b6535c92c-sense-qt` flee: death (race), intentApplied controlUnavailable at tick 7866.
  - `tribe-eta:loom421b3b32c1-sense-rx` flee: death (race), intentApplied accepted at tick 8156.
  - `tribe-delta:loom3b4ef3e082-sense-sc` attack: inapplicable, intentApplied attackHealthBound at tick 8217.

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
