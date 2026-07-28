# Project agent memory

This file is the project's committed home for project-intrinsic agent knowledge: build, test, release, architecture, and sharp-edge notes that should travel with the code.

## The shared contract

`contract/loom.schema.json` (JSON Schema draft 2020-12) is the single source of truth for the perception/decision messages exchanged between the body (`plugin/`, Kotlin) and the mind (`cognition/`, Rust). Both sides carry **hand-written** binding types, not code-generated ones, kept honest against the schema by conformance tests. Edit the schema first; then update both sides' types until their conformance tests pass again.

- Rust bindings: `cognition/crates/contract/src/lib.rs` (serde). Conformance: `cognition/crates/contract/tests/contract_conformance.rs`.
- JVM bindings: `plugin/src/main/kotlin/dev/loom/contract/Contract.kt` (kotlinx-serialization) + canonical `LoomJson` config. Conformance: `plugin/src/test/kotlin/dev/loom/contract/ContractConformanceTest.kt`.
- Shared fixtures: `contract/fixtures/valid` (must validate + round-trip byte-for-byte on both sides) and `contract/fixtures/invalid` (must be rejected by the schema). Fixtures are stored as compact JSON with no trailing newline; field order matches the internal-tag-first declaration order both serializers emit. Adding a contract case means adding a fixture in both dirs.
  Byte-for-byte round-trip depends on serde_json (ryu) and Java's `Double.toString` producing identical float text, which they do only for values both render the same way.
  Keep fixture float values away from anything needing exponential notation (e.g. `1e-7`, which ryu emits as `1e-7` but Java as `1.0E-7`); such a value would pass one side's round-trip test and fail the other's.

**Union wire tagging is internal.** `Trigger` is tagged on `event`, `Intent` on `action`, with the variant fields inlined. Rust uses `#[serde(tag = "...")]`; JVM uses `@JsonClassDiscriminator` (needs a file-level `@OptIn(ExperimentalSerializationApi::class)`). Empty variants (`Trigger.GoalReview`, `Intent.Idle`) are unit/`data object`, serializing to just `{"event":"goalReview"}` / `{"action":"idle"}`. Keep the two tag configs identical or the round-trip fixtures diverge.

## The body port

`plugin/src/main/kotlin/dev/loom/body/BodyPort.kt` is the inbound port the rest of Loom uses to give an agent a physical presence.
Its surface is exactly four capability areas - lifecycle, actuation, perception, and the server-tick hook - and it should stay that size.
Only Loom domain and contract types appear in its signatures; no engine type and no world handle crosses it, which is what makes the body flavor replaceable.

`dev.loom.body` is engine-free by rule, and the movement arithmetic (`WalkGait`), perception bound (`PerceptionBound`), mortality rule (`Mortality`), and world-loading footprint (`WorldFootprint`) live there so they are unit-testable without a server.

Two design constraints are load-bearing, not stylistic:

- **Movement is a cheap Loom-owned step, never synchronous pathfinding.** Vanilla goal selectors with synchronous A* were measured at ~25ms/tick for 100 bodies against ~1ms for 400 stepped bodies. Adding a pathfinder to the tick path would undo that.
- **`BodyPort.tick` drives actuation only and must never invoke cognition.** Cognition is event-driven by construction: the contract's `Trigger` deliberately has no per-tick variant.

**Death is terminal - Loom is permadeath.** When a body dies the agent is gone for good: no respawn, no reincarnation, no second body ever.
New agents will come from reproduction, never from restarting a dead one.
`NmsEntityBodyPort.tick` retires a body that has died or been removed in-world - it drops from `bodies`, the death is recorded, and `spawn` refuses that agent id from then on.
The rule is held by `Mortality` in the engine-free domain, which records a death and deliberately offers no way to clear one, so a revive path cannot be added by accident.
Despawning is not death: it is how Loom puts a body away (as on plugin unload) and leaves the agent embodiable.
A death owes the mind a *terminal* body-death `Trigger` once cognition is wired - "this agent has DIED", not "take another body".
That signal is deliberately not sent from `tick`, and no death `Trigger` exists in the contract yet, so it is left as a marked owed signal at the retire site rather than built now.
`Mortality` currently holds the dead in memory, so its refusal spans the server process; making death outlive a restart belongs with agent-identity persistence, which does not exist yet.
The append-only event log is meanwhile the durable record of who has died.

**Agents carry their own loaded world, and permadeath detection depends on it.**
Loom has no human players, so by default nothing keeps a chunk loaded and an agent nobody is watching stops ticking.
That is not merely a liveness problem: `RemovalReason.UNLOADED_TO_CHUNK` removes a body without killing it, so a bare "removed means dead" check would mark an unwatched agent permanently dead - an agent dying for want of an observer.
Instead Loom holds the world open around every living agent, which makes any removal of a living body a real death and needs no unload-versus-death test at all.
The policy is `WorldFootprint` in the engine-free domain (the coalesced union of a chunk area around each living agent, so cost tracks the population's spatial footprint rather than its head count, and a group that splits off keeps its own area); `dev.loom.body.nms.HeldChunks` is the engine half that makes the holds real and records each change to the event log under the reserved world-level agent id `loom:world`, which `AgentId` refuses so no agent's records can be mistaken for the world's.
The hold is taken before a body registers as live - spawn refuses rather than leave a live body unheld - and `follow` keeps it sliding with the agents from then on.
The mechanism is Bukkit's plugin chunk ticket, which lands at chunk level 31 - `ChunkLevel.ENTITY_TICKING_LEVEL` - so a held chunk ticks its entities.
That level *is* the invariant: hold chunks any more weakly and false deaths come back silently.
Tickets outlive the tick that added them, so they must be released on disable or the world stays pinned loaded with no Loom running.

## The one adapter, and the NMS seam

`dev.loom.body.nms` is the only package in the plugin allowed to touch `net.minecraft` or CraftBukkit, and `LoomPlugin` is the only place that names a concrete adapter.
`BodyBoundaryTest` fails the build if either stops being true, so the seam is enforced rather than documented.
A Minecraft version bump should touch this package and nothing else.

Read the header of `dev/loom/body/nms/LoomBodyEntity.kt` before changing the body's mechanism.
It records why a literally-new registered entity type is not viable here, why the body extends a vanilla class instead, and why the base type is Villager.
Two sharp edges are easy to reintroduce:

- Spawning must use `SpawnReason.CUSTOM`; any other reason routes through natural-spawn gating that silently discards the body.
- `Mob.serverAiStep` is `final`, so suppressing vanilla behavior means overriding **both** `Villager.customServerAiStep()` (deprecated) and `customServerAiStep(boolean)` without calling `super`. Missing one gives the body vanilla behavior back.

Engine helpers specified in extents rather than radii have already caused one bug (a perception radius that covered half its documented area).
Prefer expressing such bounds in the domain, as `PerceptionBound` does, where a test can pin them.

**Adding a future flavor** (fake player, external client, headless sim) means: a new package under `dev.loom.body`, a new `BodyPort` implementation in it, and one changed construction line in `LoomPlugin`.
Nothing above the port should need editing; if it does, the port is leaking. The ArchUnit rules already require any new implementation to live in its own adapter package.

The plugin ships with the stable `plugin.yml` descriptor, not `paper-plugin.yml`: Paper's own descriptor drops declarative `commands:` in favor of Brigadier registration, which is still experimental API on 1.21.1 and cannot be opted into under warnings-as-errors.

## Verifying a body change

Gates do not cover in-world behavior; a body change is not done until it has been seen working on a real server.
Build the jar (`./gradlew shadowJar`), drop it into a Paper 1.21.1 server's `plugins/`, and drive it with `/loombody spawn|move|perceive|despawn|list`.
Verify from a **client's** point of view rather than from the plugin's own log: a body that moves server-side only, or that a vanilla client cannot render, looks identical in the log to one that works.
The append-only `plugins/Loom/body-events.jsonl` is the research record - newline-delimited JSON, one record per line, written off the main thread - and metrics should be derived from it rather than added as separate counters.
The write queue is bounded so a stalled disk never blocks the tick; records that never reach the file are counted and surfaced as a server-log warning, so before trusting a run's metrics check the server log for "body event log" warnings.

## Gates (fail-the-build from commit 1)

- Rust: `cd cognition && cargo fmt --all --check && cargo clippy --all-targets --all-features -- -D warnings && cargo test`. Lint policy lives in `[workspace.lints]` in `cognition/Cargo.toml` (clippy pedantic/all denied, no unwrap/expect/panic in non-test code, `unsafe_code` forbidden).
- JVM: `cd plugin && ./gradlew check`. Runs detekt + ktlint + ArchUnit + warnings-as-errors compile, none ignoring failures. Build on **Corretto 21** (`jvmToolchain(21)`), Gradle wrapper pinned to 8.14. detekt config: `plugin/config/detekt/detekt.yml` (formatting delegated to ktlint).
  The plugin compiles against a Mojang-mapped server via `paperweight-userdev`; the first build downloads and decompiles it (a few hundred MB, several minutes), and later builds are fast. Paper's runtime is Mojang-mapped, so the jar ships as compiled and is never reobfuscated (`ReobfArtifactConfiguration.MOJANG_PRODUCTION`).
  detekt thresholds are treated as design feedback: clear them by splitting along a real seam rather than by adding suppressions.
- CI runs both suites: `.github/workflows/contract.yml`.

- Add durable project-specific notes here as they are discovered through real work.

## Maintaining this file

Keep this file for knowledge useful to almost every future agent session in this project.
Do not repeat what the codebase already shows; point to the authoritative file or command instead.
Prefer rewriting or pruning existing entries over appending new ones.
When updating this file, preserve this bar for all agents and keep entries concise.
