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

Build configuration, tooling, and CI arrive with the first working slice.
