# Bounded scripted tribe and body-crash proof

This directory is the sanitized, bounded publication from `2026-10-12-scripted-tribe-crash-proof`.
Every number below is generated from [evidence.jsonl](./evidence.jsonl) through the
committed research publisher; [metrics.json](./metrics.json) is the complete deterministic derivation.

## Question

Can three independently embodied Loom agents continue repeated perceive/decide/act cycles in one
bounded world, while one body process fails, without corrupting peer evidence or leaving processes behind?
This scripted proof tests the harness and runtime composition. It does not test model-driven emergence.

## Setup facts

- Source commit: `772e5cacd40e67fa835e5e62c1de0e2186e0fa87`.
- Paper 1.21.1, build 133, fixed seed `76192346017`.
- Agents: `tribe-alpha` as `loom_aa88da42d7`, `tribe-beta` as `loom_eab7eb8acb`, `tribe-gamma` as `loom_18c040d2cc`.
- Cognition: `scripted`, 18 global calls,
  6 per agent, 2 outstanding.
- External spend: $0.000000000 provider-reported actual,
  $0.000000000 worst case under the call/token bounds,
  and $0.000000 absolute declared ceiling.
- Wall-clock ceiling: 28s. Simulation ceiling: 1200 ticks.
- The server, body control, and cognition listeners were loopback-only; no real player account was used.

## Observations

- No bounded social or spatial observation class was recorded.
- 20 perceptions produced 15 decisions and 15 applied intents.
- 1 body process was deliberately crashed; peer decisions after it:
  {"tribe-beta":4,"tribe-gamma":3}.
- Cleanup verification recorded: true.

## Interpretations

- The run supports the narrower claim that the bounded fleet, cognition bridge, evidence chain, crash isolation,
  publication, and cleanup worked together under the declared conditions.
- Scripted decisions are acceptance traffic. They are not evidence of cooperation, specialization, avoidance,
  conflict, settlement, or any other emergent social outcome.

## Counterevidence and null results

- No agent produced a speech intent.
- No bounded perception sample placed two agents within eight blocks.
- No attack intent was applied.
- No server-confirmed native world interaction occurred.
- 78 server witness dispute record(s) were emitted (disputedVitals: 78); client-reported readings were not fully corroborated.
- A null social result remains valid evidence: the publisher records it instead of replacing it with a story.

## Limitations

- Perception positions are samples rather than continuous trajectories.
- Server witness disputes limit any claim that depends on the disputed client-reported readings.
- A scripted cognition run validates composition and containment, not autonomous social behavior.
- The disposable world, server logs, raw provider failures, death ledger, credentials, and process metadata are not committed.
- One short run cannot establish persistence over days, durable relationships, or repeatability across seeds.

## Reproduction

From a clean checkout, install the three pinned toolchains, then run:

```sh
node research/bin/loom-tribe.js run --config research/config/first-tribe-scripted-proof.json
```

The harness validates a prospective manifest before launch, builds the three runtime components, verifies the
pinned Paper jar, creates a disposable server, enforces call and lifecycle bounds, publishes only normalized
evidence, validates it again, and removes the mutable run directory.
