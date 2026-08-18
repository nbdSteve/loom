# Loom

An embodied artificial-society prototype in Minecraft.

Loom gives software agents physical bodies in a shared Minecraft world and minds
driven by large language models, then studies how they survive, cooperate, and
build a small society together.

## Repository layout

This is a monorepo with two runtimes and a shared contract:

- `plugin/` - the Kotlin Paper plugin: the physical world and the agent bodies.
- `cognition/` - the Rust cognition service: the agent minds.
- `contract/` - the shared perception/decision schema exchanged between them.

The body exposes only a bounded perception to the mind, and the mind never
reaches directly into world state. Cognition is event-driven, never per-tick, and
routes decisions to appropriately-sized models rather than always using a
frontier model. Every decision is logged, research-grade and append-only, with
its full perception, reasoning, action, and result.

## What runs today

The shared contract is in place: `contract/loom.schema.json` is its single source
of truth, with hand-written Rust and Kotlin bindings checked against it by
conformance tests and CI on both runtimes.

Bodies are in place on top of it. An agent can be given a body in a live world,
handed a movement intent expressed as a contract `Intent`, observed through a
bounded contract `Perception`, and removed again - driven in-game through
`/loombody`. The body flavor sits behind a single inbound port
(`dev.loom.body.BodyPort`) with one adapter behind it today, and the boundary is
enforced by architecture tests rather than convention, so a different flavor is a
new implementation of that port and one changed line of wiring.

Two world rules are already in force. Death is terminal: Loom is permadeath, so
an agent whose body dies in-world is gone for good and is never embodied again.
And living agents hold the world loaded around themselves, so a world with no
human players keeps ticking and a body's removal always means a real death.

The minds are connected now, and they are real models.
A body's perception travels to the Rust cognition service over newline-delimited JSON, the mind routes it by importance to one of two configured models, and the decision that comes back is applied to that body.
Agents drive themselves: each reviews its goals on its own on a cadence, with nobody asking it to, and `/loombody think` still triggers a round trip on demand.
Every decision carries the model that made it and the prompt version that shaped it, stamped from the route rather than claimed by the backend.
The scripted stand-in is still here and is still the default, so a fresh checkout runs with no credentials and the test suites stay hermetic; real inference is one setting away.
See `cognition/README.md` for backend selection, the credential, and both model tiers, and `AGENTS.md` for build, test, and gate commands and for the sharp edges around the engine seam.
