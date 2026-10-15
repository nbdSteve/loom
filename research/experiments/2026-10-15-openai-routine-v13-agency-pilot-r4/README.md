# Routine-v13 three-agent goal-continuity pilot

This directory is the sanitized, bounded publication from `2026-10-15-openai-routine-v13-agency-pilot-r4`.
Every number below is generated from [evidence.jsonl](./evidence.jsonl) through the
committed research publisher; [metrics.json](./metrics.json) is the complete deterministic derivation.

## Question

Can three independently embodied Loom agents complete the bounded routine-v13 pilot using only native event-driven cognition, while preserving complete per-kind perception and lifecycle evidence?
This live-model run observes independently routed agents without assigned roles, pairings, social goals, or a scripted outcome.

## Setup facts

- Source commit: `e08f6fdcbe47b240131bc1c1660c030a2c819272`.
- Paper 1.21.1, build 133, fixed seed `76192346017`.
- Agents: `tribe-alpha` as `loom_aa88da42d7`, `tribe-beta` as `loom_eab7eb8acb`, `tribe-gamma` as `loom_18c040d2cc`.
- Cognition: `openai`, 12 global calls,
  4 per agent, 3 outstanding.
- External spend: $0.005577980 provider-reported actual,
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

- 2 bounded co-location sample(s) placed two agents within eight blocks
- 10 perceptions produced 9 decisions and 9 applied intents.
- No body process was deliberately crashed and no harness `/loombody think` command was issued.
- Native scheduled reviews by agent: {"tribe-alpha":1,"tribe-beta":1,"tribe-gamma":1}.
- No agent died.
- Per-kind audit samples: 10; complete: true.
- Cleanup verification recorded: true.

## Controlled stop

- Stop: `cognitionBudgetExhausted` for `tribe-alpha:loomaa88da42d7-sense-8y` (goalReview, nativeGoalReviewSchedule).
- Refusal: `perAgentCall` at 4 of 4 calls; the call was not forwarded.
- Admission closed with 9 call(s) forwarded and 0 outstanding; 0 later request(s) were refused.
- Stop tick 2500, 2480 ticks after the start.
- Native reviews: 3 completed of 6 planned; by agent {"tribe-alpha":{"attempted":2,"completed":1,"maximum":2,"minimum":1},"tribe-beta":{"attempted":1,"completed":1,"maximum":2,"minimum":1},"tribe-gamma":{"attempted":1,"completed":1,"maximum":2,"minimum":1}}.
- Chains: 9 complete decision chain(s), 1 typed refusal chain(s).
- Provider usage: 9 call(s), 35347 tokens, $0.005577980.
- Behavioral verdict: **no-go**. A stopped run is a partial record, not a pass.

## Behavioral gate

- Mechanical pilot verdict: **no-go** (loom-primary-behavior-gate/1).
- Criteria: completeNativeReviews failed; meaningfulBehaviorAgents held; noFailureEvidence failed; noJvmCut held; typedGoalContinuity failed.
- Meaningful behaviors by agent: {"tribe-beta":1,"tribe-gamma":1}; typed goal continuity: [].
- Non-idle goal-review actions: 2, accepted 2, confirmed 2.
- Calls by agent: {"tribe-alpha":4,"tribe-beta":4,"tribe-gamma":1}; peak outstanding 2.
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
- The run ended in a controlled cognitionBudgetExhausted stop after 3 of 6 planned native reviews; its behavioral verdict is no-go.
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
