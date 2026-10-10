# Disposable three-agent Paper proof

This is the smallest real-server proof for Loom's configured Mineflayer fleet.
It is disposable, deterministic apart from ordinary Minecraft timing, and is
not a long-duration or publishable experiment.

It proves three concurrent agents through:

- deterministic identity, endpoint, and process-directory allocation;
- bounded concurrent startup and readiness;
- independent perception, cognition, movement, eating, native interaction,
  request-scoped refs, and evidence;
- two agents completing different server-confirmed world interactions without
  sharing refs;
- one hard child-process crash while a peer remains ready and acts;
- one process disconnect and restart without collateral damage;
- one server-witnessed terminal death without collateral damage;
- aggregate inspection and complete bounded shutdown.

Use a Paper 1.21.1 server bound to loopback in offline mode. Never point this
configuration at a shared or internet-reachable server: offline mode lets
anything that can reach the port claim a username.

## 1. Build the runtimes

Requirements:

- Java 21;
- Node 22 or newer;
- Rust and Cargo;
- a Paper 1.21.1 server jar.

From the repository root:

```sh
cd body
npm ci
npm run check

cd ../cognition
cargo build --bin loom-cognition

cd ../plugin
JAVA_HOME=/path/to/jdk-21 ./gradlew check shadowJar
```

Copy `plugin/build/libs/loom-plugin-0.1.0.jar` into the disposable server's `plugins/`
directory as `Loom.jar`.

The hermetic counterpart to this real-server proof is:

```sh
cd plugin
JAVA_HOME=/path/to/jdk-21 \
  ./gradlew test \
  --tests 'dev.loom.body.player.PlayerBodyPortTest.three-agent disposable proof isolates decisions refs crashes death and evidence'
```

That test drives the same coordinator seams with a deterministic shared-world
harness. It is useful when the only missing external dependency is the Paper
1.21.1 server jar.

## 2. Configure the disposable server

Use a flat, quiet world:

```properties
online-mode=false
server-ip=127.0.0.1
server-port=25565
level-type=minecraft:flat
generate-structures=false
spawn-monsters=true
enable-status=true
```

Accept the Paper EULA. Start once if Paper needs to generate its files, then
stop it and install the plugin jar.

The proof uses these configured ids:

```text
proof-alpha
proof-beta
proof-gamma
```

The allocator sorts ids before assigning resources. With control base `25585`,
their endpoints are therefore:

```text
proof-alpha -> 127.0.0.1:25585
proof-beta  -> 127.0.0.1:25586
proof-gamma -> 127.0.0.1:25587
```

Each process runs from
`plugins/Loom/bodies/<deterministic-offline-username>/`.

## 3. Start the mind and Paper

Start the credential-free scripted mind:

```sh
cd cognition
LOOM_COGNITION_BACKEND=scripted cargo run --bin loom-cognition
```

Start Paper from the disposable server directory:

```sh
LOOM_BODY_DIR=/absolute/path/to/loom/body \
LOOM_BODY_AGENTS=proof-gamma,proof-alpha,proof-beta \
LOOM_BODY_CONTROL_PORT=25585 \
LOOM_BODY_STARTUP_CONCURRENCY=2 \
LOOM_BODY_STARTUP_ATTEMPTS=2 \
LOOM_BODY_HEALTH_INTERVAL_TICKS=100 \
LOOM_BODY_HEALTH_FAILURES=3 \
LOOM_COGNITION_PENDING_PER_AGENT=2 \
LOOM_BODY_MC_VERSION=1.21.1 \
JAVA_HOME=/path/to/jdk-21 \
  /path/to/jdk-21/bin/java -jar paper-1.21.1.jar nogui
```

`LOOM_BODY_NODE` may name a specific Node executable. All body and control
hosts must remain loopback literals.

The plugin pre-registers all three identities, then starts at most two bodies at
once. The log eventually reports `3 ready, 0 rejected`.

Watch the evidence in another terminal:

```sh
tail -f plugins/Loom/body-events.jsonl
```

The synchronous death ledger is
`plugins/Loom/player-deaths.jsonl`.

## 4. Prove allocation and readiness

At the Paper console:

```text
loombody inspect
loombody inspect proof-alpha
loombody inspect proof-beta
loombody inspect proof-gamma
```

Bare `inspect` must report three configured bodies and aggregate lifecycle
counts. Each per-agent line must name a different:

- deterministic `loom_...` username and stable offline UUID;
- loopback control endpoint;
- lifecycle state.

Record the usernames as `<alpha>`, `<beta>`, and `<gamma>`.

The record must contain one `witnessJoined` and one `spawned` for each exact
agent id. Request ids emitted later must be distinct across the three control
processes.

## 5. Separate the shared-world scenes

Move the players apart on the same world so one proof action does not
accidentally affect another:

```text
execute as <alpha> at @s run tp @s 0 ~ 0
execute as <beta> at @s run tp @s 32 ~ 0
execute as <gamma> at @s run tp @s 64 ~ 0
gamerule doMobSpawning false
difficulty normal
```

Run one manual review for each:

```text
loombody think proof-alpha
loombody think proof-beta
loombody think proof-gamma
```

Each command returns immediately. The record must reconstruct three independent
`perceived` → `decided` → `intentApplied` chains by `agentId` and `requestId`.
No chain may contain another agent's request, ref, decision, or action.

## 6. Prove native interaction and hard-crash isolation

The default scripted backend deliberately does not invent a resource goal, so
this section uses a temporary finite cognition fixture rather than adding such a
policy to the product. Stop the default mind and start a loopback-only
newline-delimited responder on `127.0.0.1:8642`. Keep it unarmed until the world
is staged. For every response it must echo the perception's `requestId` and
`agentId`, include fixed local proof provenance, and do only this:

- for alpha, select an offered red-wool block ref for
  `{"action":"dig","block":"<ref>"}`, then an offered dropped-apple ref for
  `{"action":"collect","item":"<ref>","approach":true}`;
- for beta, select its offered dirt inventory ref for
  `{"action":"equip","item":"<ref>","destination":"hand"}`, then that same
  offered dirt ref and an offered gold-block ref for
  `{"action":"place","block":"<ref>","item":"<ref>","face":"up"}`;
- otherwise return `{"action":"idle"}`.

The fixture must advance only when the expected offer is present and must never
accept a coordinate, entity id, slot number, or item name as an action target.
It is an acceptance driver for typed Loom intents, not a body plugin or a
background collector. Paper remains connected to it through the normal
cognition bridge.

Build isolated ground and stage alpha's first target:

```text
execute at <alpha> run fill ~-6 ~-1 ~-6 ~6 ~-1 ~6 minecraft:stone
execute at <beta> run fill ~-6 ~-1 ~-6 ~6 ~-1 ~6 minecraft:stone
execute at <alpha> run setblock ~1 ~ ~ minecraft:red_wool
```

Arm the fixture and request alpha's decision:

```text
loombody think proof-alpha
```

The evidence must contain an alpha `perceived` record offering the red-wool
block ref, a `decided` `dig`, an `intentApplied`, and a `worldInteraction` with
outcome `serverEffectConfirmed`. Its bounded detail must join the sense request,
the body-control request, body outcome `dug`, and Paper's exact
`blockBreak minecraft:red_wool` location and tick.

Stage one exact dropped-item entity and ask again:

```text
execute at <alpha> run summon minecraft:item ~1.75 ~0.25 ~ {PickupDelay:40s,Item:{id:"minecraft:apple",count:2}}
loombody think proof-alpha
```

The second chain must select only that perception's dropped-item ref and end in
a confirmed `entityPickupItem` for the exact server entity UUID, item type, and
`2/2` count.

Now crash alpha's OS process without giving its signal handler a graceful
shutdown path:

```sh
alpha_pid="$(lsof -tiTCP:25585 -sTCP:LISTEN)"
test -n "$alpha_pid"
kill -KILL "$alpha_pid"
```

Wait for `bodyDisconnected` and `witnessQuit` for alpha. There must be no alpha
death tombstone, and beta and gamma must remain `ready`.

Only after the crash, stage beta's independent refs and reset the fixture's
beta phase to its first action:

```text
execute at <beta> run setblock ~1 ~-1 ~ minecraft:gold_block
execute at <beta> run setblock ~1 ~ ~ minecraft:air
item replace entity <beta> inventory.0 with minecraft:dirt 3
loombody think proof-beta
loombody think proof-beta
```

The first beta chain equips the exact offered dirt stack and is confirmed
against Paper's final equipment slot. The second names a fresh gold-block ref,
the dirt ref offered in that same perception, and face `up`; it must end in
`blockPlace minecraft:dirt` at the block above the support and a confirmed
`worldInteraction`. Alpha's block and item refs must never appear in either beta
chain.

Restart alpha before continuing the rest of the proof:

```text
loombody create proof-alpha
loombody inspect proof-alpha
```

Its deterministic identity, endpoint, and process directory must be unchanged.
Stop the finite fixture and restart the credential-free scripted mind before
the survival-policy checks below.

## 7. Prove movement for alpha

Summon one stationary hostile near alpha:

```text
execute at <alpha> run summon minecraft:zombie ~4 ~ ~ {NoAI:1b,PersistenceRequired:1b,Invulnerable:1b}
```

Within one threat scan, alpha must produce:

- a `perceived` record with trigger `threatSighted`;
- a `decided` record choosing `flee`;
- an accepted `intentApplied`;
- a non-zero `moved`;
- a terminal `retreating` outcome.

Beta and gamma must remain `ready`, and neither may receive alpha's creature
ref or movement evidence.

Remove the zombie:

```text
kill @e[type=minecraft:zombie,distance=..16]
```

## 8. Prove eating for beta

Give beta bread and lower only beta's food:

```text
item replace entity <beta> hotbar.0 with minecraft:bread 3
effect give <beta> minecraft:hunger 60 40 true
```

Wait until beta's food is at or below 7, then:

```text
effect clear <beta> minecraft:hunger
loombody think proof-beta
```

The scripted mind must choose `eat` using the inventory ref in beta's own
perception. Evidence must show the exact offered slot, accepted meal, reduced
stack, and later food/saturation reading. Alpha and gamma inventory refs remain
unspendable by beta because a ref is scoped to one agent, one sense answer, and
one control connection.

## 9. Prove gamma disconnect containment

Find the process listening on gamma's allocated control port and terminate it:

```sh
gamma_pid="$(lsof -tiTCP:25587 -sTCP:LISTEN)"
test -n "$gamma_pid"
kill "$gamma_pid"
```

The record must contain `bodyDisconnected` and `witnessQuit` for
`proof-gamma`, with no `died` record or death-ledger line. Inspect must show:

```text
proof-alpha -> ready
proof-beta  -> ready
proof-gamma -> disconnected
```

Restart only gamma:

```text
loombody create proof-gamma
loombody inspect proof-gamma
```

Its username, UUID, endpoint, process directory, cognition/ref state, and
evidence attribution remain its own. Alpha and beta are neither stopped nor
restarted.

## 10. Prove beta terminal death containment

Kill beta through the server:

```text
kill <beta>
```

Paper's witness must write exactly one `died` record carrying beta's presented
username and UUID, then one synchronous tombstone. Beta becomes `dead` and only
beta's child stops:

```text
loombody inspect proof-alpha
loombody inspect proof-beta
loombody inspect proof-gamma
loombody create proof-beta
```

Alpha and gamma remain `ready`. Beta's create is rejected with `hasDied`.
Killing a process never produced this state; only the server witness did.

Restart Paper with the same `LOOM_BODY_AGENTS` set. The allocator must reproduce
the same three identities, endpoints, and process directories. The replayed
ledger refuses beta while alpha and gamma start independently.

## 11. Reconstruct per-agent evidence

The following produces one ordered chain per agent without reading free-form
detail:

```sh
jq -s '
  sort_by(.tick)
  | group_by(.agentId)
  | map({
      agentId: .[0].agentId,
      events: map({
        tick,
        event,
        requestId,
        trigger,
        intent,
        outcome,
        witness
      })
    })
' plugins/Loom/body-events.jsonl
```

Confirm:

- every lifecycle, perception, decision, action, refusal, disconnect, and death
  is filed under exactly one configured `agentId`;
- alpha's dig and collection each join one of alpha's offered refs to one exact
  body-control request and one confirmed vanilla event;
- beta's equip and placement use only beta's refs and complete after alpha's
  `SIGKILL`;
- alpha's movement records join alpha's perception only;
- beta's meal and death records join beta only;
- gamma's disconnect/restart records join gamma only;
- aggregate Paper logs report counts and agent ids, never prompts,
  provider credentials, or unbounded transport messages.

## 12. Prove bounded cleanup

Stop Paper and cognition. Confirm no process or listener from the proof remains:

```sh
pgrep -af 'loom-body.js|loom-cognition|paper-1.21.1'
lsof -nP \
  -iTCP:8642 \
  -iTCP:25565 \
  -iTCP:25585 \
  -iTCP:25586 \
  -iTCP:25587
```

Those commands should find nothing from this run. Before keeping any evidence,
also check the Paper log for `body event log` warnings. The JSONL file is
append-only evidence; the death ledger is authorisation state and is not an
experiment artifact.
