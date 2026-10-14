# Routine-v13 three-agent goal-continuity pilot

This directory is the sanitized, bounded publication from `2026-10-15-openai-routine-v13-agency-pilot-r5`.
Every number below is generated from [evidence.jsonl](./evidence.jsonl) through the
committed research publisher; [metrics.json](./metrics.json) is the complete deterministic derivation.

## Question

Can three independently embodied Loom agents complete the bounded routine-v13 pilot using only native event-driven cognition, while preserving complete per-kind perception and lifecycle evidence?
This live-model run observes independently routed agents without assigned roles, pairings, social goals, or a scripted outcome.

## Setup facts

- Source commit: `c672c5e1ec87d1f1cfd1401cf75dd50e8e68b568`.
- Paper 1.21.1, build 133, fixed seed `76192346017`.
- Agents: `tribe-alpha` as `loom_aa88da42d7`, `tribe-beta` as `loom_eab7eb8acb`, `tribe-gamma` as `loom_18c040d2cc`.
- Cognition: `openai`, 12 global calls,
  6 per agent, 3 outstanding.
- External spend: $0.003447040 provider-reported actual,
  $2.334720000 worst case under the call/token bounds,
  and $2.340000 absolute declared ceiling.
- Wall-clock ceiling: 180s. Simulation ceiling: 4000 ticks.
- The server, body control, and cognition listeners were loopback-only; no real player account was used.
- Native goal reviews: 2 expected per agent,
  2400-tick cadence, 100-tick slices.
- Initial positions: {"tribe-alpha":[10.5,68,3.5],"tribe-beta":[1.5,68,1.5],"tribe-gamma":[-9.5,68,-7.5]}.
- Initial world clock: {"timeOfDay":0,"day":0,"isDay":true}.
- Starting inventories were empty; no role, target, social goal, hierarchy, pairing, conflict, or scripted objective was injected.

## Observations

- No bounded social or spatial observation class was recorded.
- 6 perceptions produced 6 decisions and 6 applied intents.
- No body process was deliberately crashed and no harness `/loombody think` command was issued.
- Native scheduled reviews by agent: {"tribe-alpha":2,"tribe-beta":2,"tribe-gamma":2}.
- No agent died.
- Per-kind audit samples: 6; complete: true.
- Cleanup verification recorded: true.

## Behavioral gate

- Mechanical pilot verdict: **no-go** (loom-primary-behavior-gate/1).
- Criteria: completeNativeReviews held; meaningfulBehaviorAgents failed; noFailureEvidence held; noJvmCut held; typedGoalContinuity held.
- Meaningful behaviors by agent: {"tribe-gamma":1}; typed goal continuity: ["tribe-alpha","tribe-beta","tribe-gamma"].
- Non-idle goal-review actions: 3, accepted 1, confirmed 1.
- Calls by agent: {"tribe-alpha":2,"tribe-beta":2,"tribe-gamma":2}; peak outstanding 1.
- A pass still needs a reader to confirm: each counted meaningful behavior has an evidence-grounded aim; no counted action is justified only by being active; no cross-agent goal leakage in the typed fields.

## Interpretations

- The run supports the narrower claim that the bounded fleet, native scheduler, cognition bridge, per-kind evidence chain,
  publication, and cleanup worked together under the declared conditions.
- Model-driven actions are observations, not proof of motive or emergence. Any social or spatial interpretation is limited to the recorded evidence and counterevidence.

## Counterevidence and null results

- No agent produced a speech intent.
- No bounded perception sample placed two agents within eight blocks.
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
node research/bin/loom-tribe.js run --config research/config/first-tribe-routine-v13-agency-pilot.json
```

The harness validates a prospective manifest before launch, builds the three runtime components, verifies the
pinned Paper jar, creates a disposable server, enforces call and lifecycle bounds, publishes only normalized
evidence, validates it again, and removes the mutable run directory.
