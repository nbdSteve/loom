# 10-agent ten-minute grounded-walk live shakedown

This directory is the sanitized, bounded publication from `2026-10-16-openai-10-agent-10m-r5`.
Every number below is generated from [evidence.jsonl](./evidence.jsonl) through the
committed research publisher; [metrics.json](./metrics.json) is the complete deterministic derivation.

## Question

Can ten independently embodied Loom agents complete the bounded routine-v14 grounded-walk-10m-10 profile using only native event-driven cognition, while preserving complete per-kind perception and lifecycle evidence?
This live-model run observes independently routed agents without assigned roles, pairings, social goals, or a scripted outcome.

## Setup facts

- Source commit: `9fe9c39cdf94046b5ec8889e89bf8e39aa98d703`.
- Paper 1.21.1, build 133, fixed seed `27183455263279`.
- Agents: `tribe-alpha` as `loom_aa88da42d7`, `tribe-beta` as `loom_eab7eb8acb`, `tribe-delta` as `loom_3b4ef3e082`, `tribe-epsilon` as `loom_003398683d`, `tribe-eta` as `loom_421b3b32c1`, `tribe-gamma` as `loom_18c040d2cc`, `tribe-iota` as `loom_8b6535c92c`, `tribe-kappa` as `loom_f309750e7b`, `tribe-theta` as `loom_9bc6579ca4`, `tribe-zeta` as `loom_3d8263b30e`.
- Cognition: `openai`, 150 global calls,
  24 per agent, 6 outstanding.
- External spend: $0.035752970 provider-reported actual,
  $29.184000000 worst case under the call/token bounds,
  and $29.190000 absolute declared ceiling.
- Wall-clock ceiling: 600s. Simulation ceiling: 12000 ticks.
- The server, body control, and cognition listeners were loopback-only; no real player account was used.
- Native goal reviews: 6 expected per agent,
  2400-tick cadence, 100-tick slices.
- Initial positions: {"tribe-alpha":[12.5,105,0.5],"tribe-beta":[10.5,99,7.5],"tribe-gamma":[4.5,102,11.5],"tribe-delta":[-3.5,110,11.5],"tribe-epsilon":[-9.5,111,7.5],"tribe-zeta":[-11.5,110,0.5],"tribe-eta":[-9.5,110,-6.5],"tribe-theta":[-3.5,108,-10.5],"tribe-iota":[4.5,111,-10.5],"tribe-kappa":[10.5,108,-6.5]}.
- Initial world clock: {"timeOfDay":0,"day":0,"isDay":true}.
- Starting inventories were empty; no role, target, social goal, hierarchy, pairing, conflict, or scripted objective was injected.

## Observations

- 3 bounded co-location sample(s) placed two agents within eight blocks
- 59 perceptions produced 57 decisions and 57 applied intents.
- No body process was deliberately crashed and no harness `/loombody think` command was issued.
- Native scheduled reviews by agent: {"tribe-alpha":5,"tribe-beta":5,"tribe-delta":5,"tribe-epsilon":5,"tribe-eta":5,"tribe-gamma":5,"tribe-iota":5,"tribe-kappa":5,"tribe-theta":5,"tribe-zeta":5}.
- No agent died.
- Per-kind audit samples: 59; complete: true.
- Cleanup verification recorded: true.

## Behavioral gate

- Mechanical pilot verdict: **notApplicable** (loom-primary-behavior-gate/2).
- Criteria: not applicable outside the 180-second pilot profile.
- Meaningful behaviors by agent: {"tribe-epsilon":1,"tribe-eta":1,"tribe-iota":2,"tribe-kappa":5,"tribe-theta":3}; typed goal continuity: ["tribe-alpha","tribe-beta","tribe-delta","tribe-epsilon","tribe-eta","tribe-gamma","tribe-iota","tribe-kappa","tribe-theta","tribe-zeta"].
- Non-idle goal-review actions: 40, accepted 13, confirmed 12.
- Calls by agent: {"tribe-alpha":5,"tribe-beta":5,"tribe-delta":5,"tribe-epsilon":5,"tribe-eta":8,"tribe-gamma":5,"tribe-iota":5,"tribe-kappa":5,"tribe-theta":9,"tribe-zeta":5}; peak outstanding 2.
- A pass still needs a reader to confirm: each counted meaningful behavior has an evidence-grounded aim; no counted action is justified only by being active; no cross-agent goal leakage in the typed fields.

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
node research/bin/loom-tribe.js run --config research/config/tribe-grounded-walk-10m-10.json
```

The harness validates a prospective manifest before launch, builds the three runtime components, verifies the
pinned Paper jar, creates a disposable server, enforces call and lifecycle bounds, publishes only normalized
evidence, validates it again, and removes the mutable run directory.
