# Loom

Loom is an embodied artificial-society prototype in Minecraft. A Loom agent has
a persistent player body in a Paper world, an event-driven mind, a bounded view
of its surroundings, and one permanent life.

## Repository layout

- `plugin/` — the Kotlin Paper plugin. It supervises the player process,
  reconciles it with the server witness, schedules cognition from server ticks,
  applies decisions, and writes the evidence record.
- `body/` — the Node/Mineflayer player process. It owns Minecraft protocol
  mechanics and answers a loopback body-control connection without choosing
  goals or running gameplay policy.
- `cognition/` — the Rust mind service. It accepts one bounded perception,
  routes it to the configured model tier, and returns one provenance-stamped
  decision.
- `contract/` — the two versioned JSON contracts and their shared fixture
  corpora.
- `research/` — append-only experiment manifests and evidence.

The contracts are intentionally separate:

- `contract/loom.schema.json` is the perception/decision contract between the
  Paper coordinator and the Rust mind.
- `contract/body-control.schema.json` is the lifecycle, sensing, eating, and
  movement contract between the Paper coordinator and one Mineflayer process.

Both runtimes use hand-written bindings held to the schemas and fixture bytes by
conformance tests.

## Runtime today

The product runtime is hard-cut to the persistent Mineflayer player body.
`LoomPlugin` does not select or instantiate the older Villager adapter. That
implementation remains in the repository, unused, for a later removal change;
there is no compatibility switch.

`/loombody create <agentId>` deterministically derives one offline player
username and UUID, registers that exact identity with the server witness, starts
one Node process, completes the body-control handshake, waits for the process to
report `ready`, and waits for the server to confirm the player is present. Only
then is the body visible as ready to cognition. The current product deployment
owns one configured identity and refuses a second agent id; multi-agent
orchestration is not part of this cutover.

The Mineflayer process is a mechanics boundary, not a second mind:

- `sense` reports a bounded scene, health, vanilla food and saturation, and the
  occupied player inventory slots.
- `eat` consumes the exact request-scoped slot the mind named.
- `moveTo` and `flee` run the bounded movement policy already implemented in
  `body/src/movement.js`.
- `halt` cancels the one movement operation in flight.
- no timer in Node chooses a meal, destination, threat, or goal.

The coordinator preserves request identity across perception, decision, and
actuation. Creature and inventory refs remain scoped to the sense answer that
minted them. Threat debounce uses a server-corroborated entity UUID when one can
be matched, while the body-control wire keeps its request-local indexes.

## Lifecycle and permadeath

The JVM supervisor owns startup, readiness, timeout, shutdown, and partial
failure ordering. Startup failures clean up the partial child before the
operation completes. Shutdown asks the control protocol first, then escalates
through bounded process termination. Plugin disable stops new cognition and
schedules first, closes cognition, closes every held body-control session and
child process, and closes the event log last.

A process exit, socket failure, or control refusal is never death. It makes the
body `disconnected` or `failed`, and the same player identity may be restarted.
Only server witness evidence writes a durable death tombstone and makes the
agent terminal. The tombstone survives a Paper restart and denies both
`/loombody create` and an independently launched process using the dead account.

## Cognition and scheduling

Cognition is event-driven. Scheduled goal reviews use Paper's authoritative
server tick, not elapsed wall time:

- the default cadence is 2,400 server ticks;
- the population is staggered over 24 slices;
- pausing the server advances no review;
- `/tick sprint` advances the same deterministic review sequence;
- `BodyPort.tick` never invokes cognition.

Threat scans remain separate events. A hunting creature in view can earn an
immediate pivotal perception, and unchanged sightings are suppressed by the
existing cadence, debounce, and nightly budget rather than producing duplicate
model calls.

The default cognition backend is the deterministic scripted stand-in, so a
checkout and all tests require no credential. Set
`LOOM_COGNITION_BACKEND=openai` to use configured models; see
`cognition/README.md`.

## Commands

The body command manages the persistent player identity:

```text
/loombody create <agentId>
/loombody inspect <agentId>
/loombody think <agentId>
/loombody stop <agentId>
```

`/loomwatch <agentId>` remains a read-only view over the append-only event
record. Its established `vitals`/`energyChanged` evidence names and `energy`
display label are intentionally preserved in this cutover. For the player
adapter that compatibility slot carries vanilla food on a 0–20 scale; health
remains separate, and saturation is present in the perception/decision record.

## Build and test

Requirements are Java 21, Node 22 or newer, and a current stable Rust toolchain.

```sh
cd body
npm ci
npm run check

cd ../cognition
cargo fmt --all --check
cargo clippy --all-targets --all-features -- -D warnings
cargo test

cd ../plugin
JAVA_HOME=/path/to/jdk-21 ./gradlew check shadowJar
```

See `DEMO.md` for the disposable Paper 1.21.1 acceptance path covering
readiness, perception, cognition, movement, eating, disconnect, restart,
permadeath, denied reconnect, tick-deterministic reviews, and complete cleanup.
`AGENTS.md` carries the architectural invariants and sharp edges worth preserving
when changing any of those seams.
