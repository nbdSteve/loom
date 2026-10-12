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
- `contract/body-control.schema.json` is the lifecycle, sensing, eating,
  movement, and bounded native-interaction contract between the Paper
  coordinator and one Mineflayer process.

Both runtimes use hand-written bindings held to the schemas and fixture bytes by
conformance tests.

## Runtime today

The product runtime is a bounded configured fleet of persistent Mineflayer
player bodies. `LoomPlugin` constructs `PlayerBodyPort` directly; it remains the
sole body implementation and acts as a fleet façade over isolated copies of the
existing one-player runtime.

`LOOM_BODY_AGENTS` is a comma-separated list of agent ids (maximum 16, default
`e2e`). On plugin enable Loom canonicalizes that set, derives one stable offline
username and UUID per id, pre-registers every identity with the server witness,
allocates consecutive loopback control ports and distinct process directories,
and starts the configured processes through a bounded concurrency gate. The same
set receives the same allocations after restart regardless of list order.

Each member owns its process supervisor, control connector, request-id
namespace, cognition/ref windows, threat and vitals watches, movement,
world-interaction sessions, I/O workers, keepalive scheduler, health budget,
and shutdown state.
Only the server witness, append-only evidence sink, and globally bounded
cognition worker pool are shared. A crash, exhausted health budget, or terminal
death changes one member and cannot stop or reclassify another.

The Mineflayer process is a mechanics boundary, not a second mind:

- `sense` reports a bounded scene including nearby blocks, dropped items, and
  currently applicable recipes, plus health, vanilla food and saturation, and
  the occupied player inventory slots.
- `eat` consumes the exact request-scoped slot the mind named.
- `moveTo` and `flee` run the bounded movement policy already implemented in
  `body/src/movement.js`.
- `dig`, `collect`, `place`, and `equip` apply one exact block, dropped-item, or
  inventory reference offered by the sense result the mind answered.
- `craft`, `attack`, and `sleep` apply one exact offered recipe, living target
  and weapon, or bed under bounded quantity, pursuit, swing, health, reach, and
  wait limits.
- `halt` cancels cancellable exclusive work; a conflicting request is refused
  rather than queued or allowed to replace it.
- no timer in Node chooses a meal, destination, threat, resource, item, recipe,
  weapon, bed, face, equipment destination, or goal.

The coordinator preserves request identity across perception, decision, and
actuation. Creature, block, dropped-item, inventory, and recipe refs remain scoped to
the sense answer and control connection that minted them. Threat debounce uses
a server-corroborated entity UUID when one can be matched, while the
body-control wire keeps its request-local indexes. Paper separately reconciles
completed native interactions against block-break, pickup, placement, crafting,
entity damage/death, bed entry/leave, or final inventory state and writes a
`worldInteraction` record for the vanilla result.

## Lifecycle and permadeath

Each JVM supervisor owns startup, readiness, retry, health, shutdown, and
partial-failure ordering for one player. Every failed startup attempt cleans up
its partial child before a bounded retry. Periodic status checks have a
consecutive-failure limit; exhausting it writes `bodyDisconnected` for that
agent and stops only its child. Shutdown asks the control protocol first, then
escalates through bounded process termination. Plugin disable stops new
cognition and schedules first, closes cognition, closes every held body-control
session and child process in deterministic agent order, and closes the event log
last.

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

The cognition bridge also limits outstanding requests per agent before they
enter its globally bounded worker/queue pool. One agent can exhaust its own
budget without consuming another agent's admission slots.

The default cognition backend is the deterministic scripted stand-in, so a
checkout and all tests require no credential. Set
`LOOM_COGNITION_BACKEND=openai` to use configured models; see
`cognition/README.md`.

## Commands

The body command manages configured identities:

```text
/loombody create <agentId>
/loombody inspect
/loombody inspect <agentId>
/loombody think <agentId>
/loombody stop <agentId>
```

Configured bodies start automatically. `create` restarts a configured member
after a non-terminal disconnect and refuses ids outside the fleet. Bare
`inspect` reports aggregate lifecycle counts without exposing cognition prompts,
transport details, or credentials.

The main fleet settings are:

```text
LOOM_BODY_AGENTS=e2e
LOOM_BODY_CONTROL_PORT=<Paper port + 20>
LOOM_BODY_PROCESS_ROOT=plugins/Loom/bodies
LOOM_BODY_STARTUP_CONCURRENCY=4
LOOM_BODY_STARTUP_ATTEMPTS=2
LOOM_BODY_STARTUP_RETRY_MS=250
LOOM_BODY_HEALTH_INTERVAL_TICKS=100
LOOM_BODY_HEALTH_FAILURES=3
LOOM_BODY_IO_WORKERS_PER_AGENT=4
LOOM_COGNITION_PENDING_PER_AGENT=4
```

Every numeric setting is bounded at startup; invalid or colliding allocations
are refused before any body is launched.

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
cargo test --all-features

cd ../plugin
JAVA_HOME=/path/to/jdk-21 ./gradlew check shadowJar
```

See `DEMO.md` for the disposable Paper 1.21.1 acceptance path covering
readiness, perception, cognition, movement, eating, all seven native
interactions, one hard child-process crash with an unaffected peer, restart,
permadeath, denied reconnect, tick-deterministic reviews, and complete cleanup.
`AGENTS.md` carries the architectural invariants and sharp edges worth
preserving when changing any of those seams.
