# Disposable Paper acceptance run

This is the smallest real-server path for the Mineflayer player cutover. It
covers process startup and readiness, bounded perception, cognition, movement,
eating, process disconnect and restart, terminal server-witnessed death, denied
reconnect, tick-deterministic goal reviews, and complete cleanup.

Use a disposable Paper 1.21.1 server bound to loopback in offline mode. Do not
point the body at a shared or internet-reachable server: offline mode lets
anything that can reach the port claim a username.

## 1. Build all three runtimes

Requirements:

- Java 21
- Node 22 or newer
- Rust and Cargo
- a Paper 1.21.1 server jar

From the repository root:

```sh
cd body
npm ci
npm run check

cd ../cognition
cargo build --bin loom-cognition

cd ../plugin
JAVA_HOME=/path/to/jdk-21 ./gradlew shadowJar
```

Copy `plugin/build/libs/loom-0.1.0.jar` into the disposable server's `plugins/`
directory as `Loom.jar`.

## 2. Configure the disposable server

The acceptance world should be flat and quiet so movement evidence is about
Loom rather than terrain generation or ambient mobs. The relevant
`server.properties` settings are:

```properties
online-mode=false
server-ip=127.0.0.1
server-port=25565
level-type=minecraft:flat
generate-structures=false
spawn-monsters=false
enable-status=true
```

Accept the Paper EULA. Start the server once if it needs to generate its files,
then stop it and install the plugin jar.

The commands below assume the operator is the console or an op. `/loombody` and
`/loomwatch` both default to op-only permissions.

## 3. Start the mind and Paper

In one terminal:

```sh
cd cognition
LOOM_COGNITION_BACKEND=scripted cargo run --bin loom-cognition
```

The deterministic scripted backend is enough for this acceptance path. It
chooses a meal when food is low and flees from a hostile sighting, while still
using the real socket, contract, routing, provenance, and decision journal.

In the disposable Paper directory, start Paper with the body package's absolute
path:

```sh
LOOM_BODY_DIR=/absolute/path/to/loom/body \
LOOM_BODY_MC_VERSION=1.21.1 \
JAVA_HOME=/path/to/jdk-21 \
  /path/to/jdk-21/bin/java -jar paper-1.21.1.jar nogui
```

`LOOM_BODY_NODE` may name a specific Node executable. The plugin otherwise uses
`node`, connects to Paper's configured port, and derives the loopback control
port as the Minecraft port plus 20. All body hosts must be loopback literals.

Watch the durable record in another terminal:

```sh
tail -f plugins/Loom/body-events.jsonl
```

The durable death ledger is
`plugins/Loom/player-deaths.jsonl`.

## 4. Prove startup and readiness

At the Paper console:

```text
loombody create e2e
loombody inspect e2e
```

The immediate create reply says startup began. The asynchronous completion line
must later say the body is ready. `inspect` then names:

- the deterministic `loom_...` Minecraft username;
- lifecycle `ready`;
- the loopback body-control endpoint.

Record that username as `<body-player>` for the remaining commands.

Readiness has crossed all required boundaries at this point: the identity was
registered with the witness before launch, the child is alive, body-control
handshake and status succeeded, the process reported `ready`, and Paper sees the
exact registered player.

The event record should contain `witnessJoined`, then `spawned`. A scheduled
`goalReview` perception follows on the next 100-tick schedule slice and produces
the ordinary `perceived` → `decided` → `intentApplied` chain.

`loombody create another-agent` must be rejected without starting another
process. This cutover intentionally owns one configured player identity;
multi-agent orchestration is separate work.

## 5. Prove bounded perception and manual cognition

Run:

```text
loombody think e2e
```

The command returns immediately. The log then records one perception with the
player's health, vanilla food, saturation, position, occupied inventory, time,
and bounded nearby scene; one decision with model and prompt provenance; and one
applied outcome with the same request id.

The observer remains read-only:

```text
loomwatch e2e
```

The established observer/evidence vocabulary is intentionally unchanged. Its
`energy` label and the event record's `vitals`/`energyChanged` names carry the
player's food level on a 0–20 scale in this adapter. Health remains separate,
and saturation remains explicit in the perception and decision input.

## 6. Prove eating

Give the player bread in an ordinary player slot and lower its food using
vanilla mechanics:

```text
item replace entity <body-player> hotbar.0 with minecraft:bread 3
effect give <body-player> minecraft:hunger 20 20 true
```

Wait until the food level is at or below 7, then stop the effect and request a
review:

```text
effect clear <body-player> minecraft:hunger
loombody think e2e
```

The scripted mind must choose `eat` using the request-scoped inventory ref.
Evidence must show:

- the sense answer offered the exact slot;
- the decision names that ref;
- `intentApplied` is accepted as `ate bread`;
- one bread leaves the stack;
- a later perception reports the new food and saturation.

No automatic-eat policy runs in Node. If the mind does not issue the request,
the body does not eat.

## 7. Prove movement and threat debounce

Keep the difficulty above peaceful, disable ambient spawning, and summon one
stationary hostile on open ground:

```text
difficulty normal
gamerule doMobSpawning false
execute at <body-player> run summon minecraft:zombie ~4 ~ ~ {NoAI:1b,PersistenceRequired:1b}
```

Within one 20-tick threat scan, the record must show a pivotal
`threatSighted` perception and a `flee` decision. The player must move away on
the real server, and the applied path must end with:

- one accepted `intentApplied`;
- one `moved` record with non-zero travelled distance;
- one terminal `retreating` outcome such as `retreatSafe`.

While that same zombie remains in view, later scans should record
`threatDebounced` rather than buying more decisions. This proves that
request-local Mineflayer observations were correlated to one stable
server-witnessed creature for the existing threat budget.

The coordinator can legitimately receive body-control `notReady` while the
first movement still owns the path. That is an operational refusal, not a
process failure: the player process must remain alive and `inspect` must remain
`ready`.

Remove the test zombie when finished:

```text
kill @e[type=minecraft:zombie]
```

## 8. Prove server-tick review cadence

Goal reviews use `server.currentTick`, not wall time. Find two scheduled
`perceived` records whose trigger is `goalReview`:

```sh
jq -r 'select(.event == "perceived" and .trigger == "goalReview") | .tick' \
  plugins/Loom/body-events.jsonl
```

After a scheduled review, freeze Paper for several wall-clock seconds:

```text
tick freeze
```

No review may appear while ticks are frozen. Resume and sprint one cadence:

```text
tick unfreeze
tick sprint 2400
```

The next scheduled review is due from the authoritative tick sequence. Repeating
the same tick sequence produces the same cadence regardless of how quickly the
host executed it. This is the opposite of the retired wall-time scheduler:
tick sprint is now a valid deterministic fast-forward.

## 9. Prove disconnect and restart continuity

Find the supervised child process from a shell:

```sh
pgrep -af loom-body.js
```

Terminate that child, not Paper:

```sh
kill <body-process-pid>
```

The witness records a quit as continuity. `loombody inspect e2e` must become
`disconnected`, no `died` record or tombstone may be written, and the same
identity must restart:

```text
loombody create e2e
loombody inspect e2e
```

The username and UUID are unchanged. A process or socket failure is never
allowed to manufacture death.

Prove graceful stop as well:

```text
loombody stop e2e
loombody inspect e2e
```

The asynchronous completion must report success, the child must exit, its
control listener must close, and the event log must contain `despawned`.

Start it once more for the terminal case:

```text
loombody create e2e
```

## 10. Prove terminal death and denied reconnect

Kill the player through the server:

```text
kill <body-player>
```

The server witness must write exactly one `died` record and one durable
tombstone. The coordinator becomes `dead` and stops the child. A new create is
rejected:

```text
loombody inspect e2e
loombody create e2e
```

Stop and restart Paper, then run `loombody create e2e` again. It must still be
rejected from the replayed ledger.

Finally, prove that the durable witness does not depend on an in-memory
registration. From `body/`, manually launch the dead account using the username
shown by `inspect` and a spare control port:

```sh
LOOM_BODY_AGENT_ID=e2e \
LOOM_BODY_USERNAME=<body-player> \
LOOM_BODY_MC_HOST=127.0.0.1 \
LOOM_BODY_MC_PORT=25565 \
LOOM_BODY_MC_VERSION=1.21.1 \
LOOM_BODY_CONTROL_HOST=127.0.0.1 \
LOOM_BODY_CONTROL_PORT=25586 \
  npm start
```

Paper must deny the login with the permadeath notice. The event record must
contain `witnessDenied` with outcome `loginDeadIdentity`. The body process then
exits because it never became a ready player.

## 11. Prove complete cleanup

Stop Paper and the cognition service. Confirm that no supervised Node child,
body-control listener, cognition listener, or Paper process remains:

```sh
pgrep -af 'loom-body.js|loom-cognition|paper-1.21.1'
lsof -nP -iTCP:8642 -iTCP:25565 -iTCP:25585 -iTCP:25586
```

Those commands should find nothing from this run. A successful shutdown leaves
no held sense connection, scheduled keepalive, worker pool, control listener,
or child process behind.

Before keeping evidence, also check the Paper log for `body event log` warnings.
The JSONL record is append-only and its request ids are the join across
perception, decision, intent, movement, witness, and observer evidence.
