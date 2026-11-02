# 10-agent ten-minute routine-v24 answered-goal live shakedown

This directory is the sanitized, bounded publication from `2026-10-16-openai-10-agent-10m-r16`.
Every number below is generated from [evidence.jsonl](./evidence.jsonl) through the
committed research publisher; [metrics.json](./metrics.json) is the complete deterministic derivation.

## Question

Can ten independently embodied Loom agents complete the bounded routine-v24 answered-goal-10m-10 profile using only native event-driven cognition, while preserving complete per-kind perception and lifecycle evidence?
This live-model run observes independently routed agents without assigned roles, pairings, social goals, or a scripted outcome.

## Setup facts

- Source commit: `f0921f6ff5d715a64e7071d7530066db30743e7c`.
- Paper 1.21.1, build 133, fixed seed `27183455263279`.
- Agents: `tribe-alpha` as `loom_aa88da42d7`, `tribe-beta` as `loom_eab7eb8acb`, `tribe-delta` as `loom_3b4ef3e082`, `tribe-epsilon` as `loom_003398683d`, `tribe-eta` as `loom_421b3b32c1`, `tribe-gamma` as `loom_18c040d2cc`, `tribe-iota` as `loom_8b6535c92c`, `tribe-kappa` as `loom_f309750e7b`, `tribe-theta` as `loom_9bc6579ca4`, `tribe-zeta` as `loom_3d8263b30e`.
- Cognition: `openai`, 150 global calls,
  24 per agent, 6 outstanding.
- External spend: $0.077657420 provider-reported actual,
  $29.184000000 worst case under the call/token bounds,
  and $29.190000 absolute declared ceiling.
- Wall-clock ceiling: 600s. Simulation ceiling: 12000 ticks.
- The server, body control, and cognition listeners were loopback-only; no real player account was used.
- Native goal reviews: 5 expected per agent,
  2400-tick cadence, 100-tick slices.
- Initial positions: {"tribe-alpha":[12.5,105,0.5],"tribe-beta":[10.5,99,7.5],"tribe-gamma":[4.5,102,11.5],"tribe-delta":[-3.5,110,11.5],"tribe-epsilon":[-9.5,111,7.5],"tribe-zeta":[-11.5,110,0.5],"tribe-eta":[-9.5,110,-6.5],"tribe-theta":[-3.5,108,-10.5],"tribe-iota":[4.5,111,-10.5],"tribe-kappa":[10.5,108,-6.5]}.
- Initial world clock: {"timeOfDay":0,"day":0,"isDay":true}.
- Starting inventories were empty; no role, target, social goal, hierarchy, pairing, conflict, or scripted objective was injected.

## Observations

- 5 paired self-position sample(s), the two agents' perceptions taken within 20 ticks of each other, placed two agents within eight blocks
- 76 perception(s) showed another player within eight blocks
- 85 perceptions produced 85 decisions and 85 applied intents.
- No body process was deliberately crashed and no harness `/loombody think` command was issued.
- Native scheduled reviews by agent: {"tribe-alpha":5,"tribe-beta":5,"tribe-delta":5,"tribe-epsilon":5,"tribe-eta":5,"tribe-gamma":5,"tribe-iota":5,"tribe-kappa":5,"tribe-theta":5,"tribe-zeta":5}.
- No agent died.
- Per-kind audit samples: 85; complete: true.
- Cleanup verification recorded: true.

## Behavioral gate

- Mechanical pilot verdict: **notApplicable** (loom-primary-behavior-gate/7).
- Criteria: not applicable outside the 180-second pilot profile.
- Meaningful behaviors by agent: {"tribe-alpha":7,"tribe-beta":5,"tribe-delta":6,"tribe-epsilon":7,"tribe-eta":2,"tribe-gamma":2,"tribe-iota":5,"tribe-kappa":7,"tribe-theta":6,"tribe-zeta":2}; typed goal continuity: ["tribe-alpha","tribe-beta","tribe-delta","tribe-epsilon","tribe-eta","tribe-gamma","tribe-iota","tribe-kappa","tribe-theta","tribe-zeta"].
- Non-idle goal-review actions: 55, accepted 50, confirmed 50.
- Calls by agent: {"tribe-alpha":10,"tribe-beta":9,"tribe-delta":9,"tribe-epsilon":10,"tribe-eta":6,"tribe-gamma":7,"tribe-iota":8,"tribe-kappa":10,"tribe-theta":9,"tribe-zeta":7}; peak outstanding 3.
- A pass still needs a reader to confirm: each counted meaningful behavior has an evidence-grounded aim; no counted action is justified only by being active; no cross-agent goal leakage in the typed fields.
- Readiness: **notReady** (loom-readiness-gate/5); decision validity 0.894118, typed terminal accounting 1, server truthfulness 1.
- Actions by class: {"confirmed":50,"goalIncoherent":9}; blockers: {"goalIncoherent":9}; legitimate races: 0; cognition failures: {}; server disputes: 0.
- Goal coherence: **incoherent**, part of the verdict (mechanically notReady); {"alreadyMetConditions":0,"conditionSteps":18,"goalsMeasured":25,"idleSteps":4,"measuredSteps":47,"metCompleted":15,"metConditions":15,"metIgnored":0,"metRevised":0,"multiStepGoals":2,"stalled":4,"stepMismatches":0,"stepOnlySteps":17,"unadvancedSteps":12,"unansweredGoals":0,"unevidencedCredits":0,"unmetCompletions":0,"unobtainableConditions":0,"unproductiveSteps":1}.
  - `tribe-alpha:loomaa88da42d7-sense-9a` collect: goalIncoherent, intentApplied goalStalled at tick 2709.
  - `tribe-eta:loom421b3b32c1-sense-9v` dig: goalIncoherent, intentApplied goalStepUnproductive at tick 2993.
  - `tribe-epsilon:loom003398683d-sense-a2` dig: goalIncoherent, intentApplied goalStalled at tick 2995.
  - `tribe-gamma:loom18c040d2cc-sense-ac` idle: goalIncoherent, intentApplied goalStepIdle at tick 3098.
  - `tribe-kappa:loomf309750e7b-sense-bc` collect: goalIncoherent, intentApplied goalStalled at tick 3394.
  - `tribe-iota:loom8b6535c92c-sense-ra` idle: goalIncoherent, intentApplied goalStepIdle at tick 8102.
  - `tribe-epsilon:loom003398683d-sense-yn` dig: goalIncoherent, intentApplied goalStalled at tick 10201.
  - `tribe-iota:loom8b6535c92c-sense-z3` idle: goalIncoherent, intentApplied goalStepIdle at tick 10404.
  - `tribe-theta:loom9bc6579ca4-sense-zs` idle: goalIncoherent, intentApplied goalStepIdle at tick 10606.

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
node research/bin/loom-tribe.js run --config research/config/tribe-answered-goal-10m-10.json
```

The harness validates a prospective manifest before launch, builds the three runtime components, verifies the
pinned Paper jar, creates a disposable server, enforces call and lifecycle bounds, publishes only normalized
evidence, validates it again, and removes the mutable run directory.
