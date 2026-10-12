# Bounded live-model first tribe experiment

This directory is the sanitized, bounded publication from `2026-10-12-openai-first-tribe-r5`.
Every number below is generated from [evidence.jsonl](./evidence.jsonl) through the
committed research publisher; [metrics.json](./metrics.json) is the complete deterministic derivation.

## Question

Can three independently embodied Loom agents continue repeated perceive/decide/act cycles in one
bounded world, while one body process fails, without corrupting peer evidence or leaving processes behind?
This live-model run observes independently routed agents without assigned roles, pairings, social goals, or a scripted outcome.

## Setup facts

- Source commit: `0ed67ebb20296efa1b3c7e394d2e970f160a10e0`.
- Paper 1.21.1, build 133, fixed seed `76192346017`.
- Agents: `tribe-alpha` as `loom_aa88da42d7`, `tribe-beta` as `loom_eab7eb8acb`, `tribe-gamma` as `loom_18c040d2cc`.
- Cognition: `openai`, 24 global calls,
  8 per agent, 3 outstanding.
- External spend: $0.010145350 provider-reported actual,
  $4.669440000 worst case under the call/token bounds,
  and $4.670000 absolute declared ceiling.
- Wall-clock ceiling: 180s. Simulation ceiling: 4000 ticks.
- The server, body control, and cognition listeners were loopback-only; no real player account was used.

## Observations

- No bounded social or spatial observation class was recorded.
- 19 perceptions produced 19 decisions and 19 applied intents.
- 1 body process was deliberately crashed; peer decisions after it:
  {"tribe-beta":5,"tribe-gamma":5}.
- Cleanup verification recorded: true.

## Interpretations

- The run supports the narrower claim that the bounded fleet, cognition bridge, evidence chain, crash isolation,
  publication, and cleanup worked together under the declared conditions.
- Model-driven actions are observations, not proof of motive or emergence. Any social or spatial interpretation is limited to the recorded evidence and counterevidence.

## Counterevidence and null results

- No agent produced a speech intent.
- No bounded perception sample placed two agents within eight blocks.
- No attack intent was applied.
- No server-confirmed native world interaction occurred.
- A null social result remains valid evidence: the publisher records it instead of replacing it with a story.

## Limitations

- Perception positions are samples rather than continuous trajectories.
- One bounded live-model run cannot establish that an observed pattern is stable or emergent.
- The disposable world, server logs, raw provider failures, death ledger, credentials, and process metadata are not committed.
- One short run cannot establish persistence over days, durable relationships, or repeatability across seeds.

## Reproduction

This paid experiment id is consumed and must not be run again. From a clean checkout,
install the pinned research dependencies and validate the committed artifact:

```sh
npm --prefix research ci
npm --prefix research run tribe -- validate research/experiments/2026-10-12-openai-first-tribe-r5
```

The fixed live template remains `research/config/first-tribe-openai.json`. A future live
repetition requires a new experiment id and separate paid-run authorization while preserving
the pinned conditions. The validator re-derives metrics and spend, verifies hashes and bounds,
and repeats the artifact secret and path scan.
