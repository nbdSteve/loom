# The Loom body process, and the Node side of the body-control protocol

This package is two things: the **body process**, one persistent Mineflayer player
driven over one loopback control channel, and the Node half of Loom's
**body-control** contract, which is the versioned newline-delimited JSON protocol
it is driven over.

**This is Loom's active product body.**
The Paper plugin registers each configured deterministic player identity with its
server witness, starts one `bin/loom-body.js` process per identity, connects to
that process's loopback control channel and waits for both control readiness and
the server-observed player before exposing the body to cognition.
`/loombody create|inspect|think|stop` manages members of that bounded fleet.
There is no alternate body implementation or compatibility switch.

The process in this package still owns exactly one agent identity, one Minecraft
player and one control endpoint. The fleet is a JVM coordinator over isolated
copies of this existing runtime; an agent id is never multiplexed through
another agent's process, socket, working directory, sense memory or movement
state.

The JVM supervisor owns lifecycle ordering. It registers the witness identity before
launch, completes startup only after handshake, process `ready` and server presence, and
cleans up a partial child before returning a startup failure. Shutdown asks this process
over the protocol first, then escalates through bounded ordinary and forced termination.
A process exit or socket failure is a disconnect and may be restarted under the same
identity. Only the Paper witness can establish death and make that identity terminal.

One operational refusal deserves special mention at this boundary: `notReady` while the
child is still alive can mean another exclusive action owns the player. The coordinator
records a refused intent and leaves the process ready; it must not turn expected action
contention into a disconnect or terminate a healthy child.

The wire is **sixteen operations at protocol generation 8**, and the split between them is
the split this whole package is built on.
Generation 8 moved because every movement answer may state the one correction its walk made
(`recovery`) and an arrival the `departure` its check found, which a controller meets without
asking; see "A walk checks it can be left" below.
Generation 7 moved because a dig report may answer `blockChanged` after the dig was handed to
Mineflayer: the world turned the offered block into another, as grass spreads onto dirt, before
this body's own finish, and the dig is stopped rather than finished on a block it was not offered.
Generation 6 moved because a dig report may answer `blockGone` after the dig was handed to
Mineflayer: another player broke the block while this body was still digging it, and `dug` is
this body's own break only, the server's update answering its own finish.
Generation 5 moved because the body-chosen entity, block, and dropped-item response lists
widened from 24 to 48 entries. Generation 4 moved because `sense` gained body-chosen
recipe offers and the craft, attack, and sleep reports introduced new closed outcome sets.
Merely adding those three request operation names would not have moved it.
An *operation* set may grow: a controller only ever asks for operations it has, and one a peer
does not have is refused by name.
A set the *body* answers from may not, because widening it hands a controller that agreed this
generation a frame it cannot read, for a request it made in good faith and without having asked
for anything new.
Refusing that frame is fail-closed rather than a misreading, but a broken round trip a peer
cannot anticipate is exactly what agreeing a generation is for.
That is why adding `moveTo`, `flee` and `halt` left generation 1 in place, adding
`searchOverBudget` moved it to 2, block and dropped-item answers moved it to 3, and
recipe and survival-skill answers moved it to 4.
Widening the three world-observation lists moved it to 5.
Adding `say` did not move it: its closed outcome set answers only a controller that sent `say`.
`contract/body-control.schema.json`'s own `ControlOperation` description is the authority.
`handshake`, `status` and `shutdown` are the process's own lifecycle.
`sense` is one bounded reading of a player's scene and vitals, `eat` is one explicit meal,
`moveTo` is one explicit walk to a place the controller named, `flee` is one explicit
flight from one creature a `sense` answer showed it, and `halt` cancels whichever movement
or interaction is cancellable.
`dig`, `collect`, `place` and `equip` are one-shot native interactions against exact block,
dropped-item and inventory offers from one retained `sense` answer.
An `equip` is `equipped` only after a bounded settle window in which the server did not
restate the window to undo it, because Mineflayer moves the stack locally before the server
answers and a vanilla server answers a refused click only with that restatement.
A `flee` with `replace` may take over from a running walk or flight: its threat is checked first and a refused one leaves the running movement alone, and a valid one ends it as `cancelled` and holds the body until it has drained (`REPLACE_SETTLE_MS`), so nothing else takes the body between them.
A takeover still waiting when a newer replacing flight or a halt arrives is displaced and answers `cancelled` without having moved, its separation measured against its threat where it stands, so an older flight can never start after a newer one (r34 beta).
`craft`, `attack` and `sleep` likewise name one offered recipe, one offered living target
and weapon, or one offered bed, with bounded quantity, swings, pursuit and wait.
A `sense` that arrives while this body's own craft application owns the inventory is read at that application's settled boundary, at most `SENSE_SETTLE_MAX_MS` later, because a client between two craft clicks holds its ingredients in the crafting grid, a state the server passes through inside one tick (r29 beta); the craft then holds its next application `SENSE_BOUNDARY_HOLD_MS`, because the server applies clicks between its once-a-tick witness samples (r32 eta).
`say` sends one line the controller chose through this player's own chat; `src/speaking.js`
owns it.
Its text is refused unless it is 1 to 128 code points and at most 256 UTF-8 bytes, carries no
unpaired surrogate, control, DEL, C1, section-sign escape, non-U+0020 space, zero-width,
bidi, line-separator, BOM or annotation character, separates words by exactly one space with
none leading or trailing, and does not begin with `/`.
Nothing is normalised, so the accepted text is the sent text and the server-observed text.
At most 2 lines are sent per sliding 10 s window per process, answered `rateLimited` beyond
that; one accepted `say` writes exactly one `chat_message` packet.
Mineflayer's `chat` plugin stays unloaded (it brings `whisper`, `tabComplete` and chat-pattern
listeners); `say` calls minecraft-protocol's `_client.chat` once, which is all `bot.chat` does
for such a line. Nothing listens for chat, so a heard line can never cause a reply.
`say` is synchronous and takes no exclusive owner.
None decides anything: a body acts only because the controller named the destination,
creature, block, dropped item, slot, recipe, bed, face or equipment destination, and it
does nothing at all in between.

## Build and test

```sh
cd body
npm ci          # installs exactly the committed lockfile
npm run check   # lint (standard) + tests (node:test)
```

There is no build step - the package is plain CommonJS - and no network beyond loopback
is touched by the tests: they need no Paper server, no Minecraft account and no
credential of any kind.
`npm run format` applies `standard --fix`.

Node 22 or newer, as `package.json` states.
The tests run under the built-in `node:test` runner, over an explicit `test/*.test.js`
glob rather than a bare directory: a directory positional such as `node --test test/`
is `require()`d rather than scanned on Node 25, and the glob also keeps `test/fixtures/`
and `test/harness.js` - a fault injector and a shared harness, neither of them a suite -
out of the run.

## Running it directly against a disposable Paper server

Ordinary product runs should let the Paper plugin supervise this process; `DEMO.md` is
the end-to-end procedure. Direct launch remains useful for body-only diagnosis and for
proving that the durable witness denies a dead identity even when no current plugin
registration exists.

The process is configured by environment only, and every value is explicit rather than
discovered.
There is nothing here that reads a secret: the body logs in with `auth: 'offline'` to a
loopback address, which is the whole of its authentication.

| Variable | |
| --- | --- |
| `LOOM_BODY_AGENT_ID` | required. 1 to 64 of `[A-Za-z0-9._:-]` |
| `LOOM_BODY_USERNAME` | required. 3 to 16 of `[A-Za-z0-9_]`, which is Minecraft's own rule |
| `LOOM_BODY_MC_HOST` | default `127.0.0.1`. A loopback literal, never a name |
| `LOOM_BODY_MC_PORT` | default 25565 |
| `LOOM_BODY_MC_VERSION` | optional. Negotiated by Mineflayer when unset or blank, which needs a server-list ping |
| `LOOM_BODY_CONTROL_HOST` | default `127.0.0.1`. A loopback literal, never a name |
| `LOOM_BODY_CONTROL_PORT` | required |

The server it connects to must be **disposable**, in **offline mode**, and bound to
loopback, which in `server.properties` is `online-mode=false` and
`server-ip=127.0.0.1`.
Offline mode is what lets a bounded username log in with no credential; it also means
anything that can reach the port can be that player, which is why the address is a
loopback literal and why the server is one you can throw away.

**Set `LOOM_BODY_MC_VERSION` unless the server answers a server-list ping.**
With no version configured, Mineflayer negotiates one by pinging the server first, so a
server with `enable-status=false` resets that ping and the login never happens.
A **blank** value counts as no version, because blanking a variable is how a setting is
turned off in an env file or a unit file: carried through as an empty string it would
reach Mineflayer as an explicit version instead of leaving it to negotiate one.
Surrounding whitespace is dropped for the same reason, so the value that decides whether
a version was configured is the value Mineflayer is given: a trailing newline picked up
from a here-doc is otherwise a version nothing can match and nothing names, since the
version is deliberately not a field this process refuses by name.
Because everything this process says is bounded, all an operator gets for it is
`failed`, with no attempt recorded on the server side either - a real run lost its first
attempt to exactly this and had to diagnose it with a separate probe.
Naming the version (`LOOM_BODY_MC_VERSION=1.21.1`) skips the ping altogether and needs
nothing of the server; `enable-status=true` is the other way, and the more fragile one,
since it is a setting a hardened disposable server is likely to have turned off.

```sh
cd body
LOOM_BODY_AGENT_ID=loom.demo.body \
LOOM_BODY_USERNAME=loom_demo \
LOOM_BODY_CONTROL_PORT=25585 \
  npm start
```

Every line it writes is bounded: one lifecycle state per transition, the name of the
one configuration field it refused, or a closed startup code.
A value, a path, a host name and a transport error's own text are all deliberately
absent, because a body process's output is a log somebody keeps.

Drive it over the control channel, on one connection, since a handshake is per
connection and each `requestId` may be used once:

```sh
{ printf '{"op":"handshake","protocol":8,"requestId":"h1","agentId":"loom.demo.body"}\n'
  sleep 1
  printf '{"op":"status","protocol":8,"requestId":"s1","agentId":"loom.demo.body"}\n'
  sleep 1
  printf '{"op":"sense","protocol":8,"requestId":"n1","agentId":"loom.demo.body","radius":24,"limit":48}\n'
  sleep 1
  printf '{"op":"eat","protocol":8,"requestId":"e1","agentId":"loom.demo.body","senseId":"n1","slot":36}\n'
  sleep 1
  printf '{"op":"flee","protocol":8,"requestId":"f1","agentId":"loom.demo.body","senseId":"n1","entity":0,"distance":16,"timeoutMs":10000,"sprint":true}\n'
  sleep 12
  printf '{"op":"moveTo","protocol":8,"requestId":"m1","agentId":"loom.demo.body","destination":[12,64,12],"tolerance":0,"timeoutMs":10000,"sprint":false}\n'
  sleep 1
  printf '{"op":"halt","protocol":8,"requestId":"z1","agentId":"loom.demo.body"}\n'
  sleep 1
  printf '{"op":"shutdown","protocol":8,"requestId":"x1","agentId":"loom.demo.body"}\n'
  sleep 1
} | nc 127.0.0.1 25585
```

A `status` answer carries the lifecycle state and three counts.
A `sense` answer carries the body's position, its three vitals, the world's time, the two
blocks it is standing in and on, bounded independent lists of entities, nearby non-air
blocks, dropped-item entities, and currently applicable recipes with omission counts,
plus its whole inventory by slot. The request limit is independently applied to the three
world-observation lists up to 48 entries each. Recipes retain their independent 24-entry
ceiling and use the smaller of that ceiling and the requested limit.
Only dropped items and blocks the body can see are listed, because each one is a handle a
`collect`, `dig` or `place` may name and those refuse what the body cannot see; anything behind
a wall or under the ground is skipped, not counted as omitted. The one exception is a dropped item
the controller names by its server uuid in the request's optional `retainItems`, at most eight:
it is listed with `visible` false, because Loom names an agent's own dig drops so the agent can
collect what its own dig dropped, and naming it chooses nothing the controller did not ask for.
The entity list's one exception is the same kind of ask: an entity the controller names by its
server uuid in `retainEntities`, at most four, is listed inside the radius even when nearer ones
fill the limit, in place of the farthest one the limit would have kept, and the omission count
still counts what was cut. Loom names the creature that has just hurt the agent, so a skeleton
shooting from past a crowd can still be fled from or struck (v32 plains rehearsal 15).
Sight to an item is aimed at its
centre, as it is to a creature, because a ray to the point it rests on meets the floor. Sight to
a block is aimed at its centre and then at each face turned toward the eyes, because a centre
ray meets a nearer floor block first and would call open ground more than two blocks away
unseen; `dig` and `place` check sight with the same rule.
It is refused `notReady` unless the body is `ready`.
An `eat` names a `senseId` - the `requestId` of the `sense` answer that offered the slot -
so the `e1` frame above eats from the answer `n1` returned, on that same connection.
It is answered with one of eight closed outcomes rather than with an error, because every
one of them is a fact about the agent's own inventory rather than about the frame: `eaten`
and the seven refusals `senseForgotten`, `slotUnnamed`, `slotEmpty`, `slotChanged`,
`notEdible`, `reserveFull` and `slotInaccessible`.
It is also the one answer written outside the frame that asked for it - bringing a stack to
hand and using it are two round trips to the server - so **its `state` and its `vitals` are
the state and the reading the request was admitted under**, not a re-read at write time.
The frame states `ready` because the body was `ready` when the meal was taken on, which is
where the numbers beside it came from; a body that has since died or disconnected still gets
its completed meal reported rather than a refusal.
The vitals prefer a fresher reading only while the body is still `ready` and the three can
still be stated together, and carry the admitted reading whole otherwise - a server plugin
raising the food level past vanilla's ceiling makes it unstatable, and a body that has died
reads back statable numbers that are no longer this answer's body.
The two are never mixed.
That is one rule for both fields, and `src/eating.js`'s header owns it.

A `moveTo` names a destination in world coordinates, how far short of it still counts as
arriving, how long the request may take, and whether it may sprint.
The coordinates are the controller's, never the mind's: Loom's JVM derives them from one block
an earlier `sense` answer of this connection reported - the cell above a solid block, or the
block's own cell where the server reports it passable - because the mind names a walk by that
block's ref. The body does not move a destination it was given.
A `flee` names a creature rather than a place: the `senseId` of the answer that showed it,
and that creature's own index in the answer's `entities` list.
Both are answered with one of eleven closed outcomes rather than with an error, for the reason
`eat` is - `arrived`, the six bounds `outOfRange`, `noRoute`, `searchOverBudget`, `stalled`,
`overTravelled` and `timedOut`, the cancellation `cancelled`, and the three facts about a named
threat that only a flight can reach: `senseForgotten`, `threatUnnamed` and `threatGone`.
`noRoute` and `searchOverBudget` are the pair worth reading twice, because they were one
outcome and should never have been: `noRoute` is the *ground's* answer, and
`searchOverBudget` is the *body's* - its own search budget stopped a search that had not
finished, so nothing at all is known about the ground.
Told the first where the second is true, a controller reads a closed world where the truth is a
busy process, and for a `flee` that reads as nowhere to run.
Every one of them states where the body started, where it ended, how far it walked and how
long it took, so a request that ended on a bound reports the ground it did cover rather
than nothing at all.
A flight states two things more - `separation`, how far it ended from the creature, and
`separationGained`, how much of that it won - and states neither when its outcome is one of
the three threat refusals, because there was then no creature to measure from.
That relation is a codec rule rather than a schema conditional, and `src/protocol.js` owns
it.
A `halt` cancels whichever cancellable exclusive operation is running and answers `stopped`:
`true` if movement, digging, collection, crafting, combat or sleep was stopped, and `false`
if none was stoppable.
`false` is a report and not a refusal, because a controller that halts twice, or halts after
an operation has already ended, is asking what can still be stopped.
**One accepted movement or native interaction owns the body**, so a conflicting request
arriving while it still runs is refused `notReady` rather than quietly taking the route or
transaction over.
An answer goes on the connection that asked for it, and a controller that asks and then
waits sends nothing in the meantime, which is why `timeoutMs` is bounded well below
`CONTROL_IDLE_MS`: a deadline at or past the idle window would let the socket's own timer
close the channel before the answer could be written.

A `shutdown` is answered `stopping` **before** anything closes, and then the Minecraft
connection, the control channel and the process itself go, in that order.
`SIGINT` and `SIGTERM` do the same thing without a frame.
Keep a connection busier than `CONTROL_IDLE_MS` (30 s) or the body closes it with
`timeout`, which is the protocol's own word for a controller that stopped saying
anything.

The exit status is what a supervisor reads, and each one means something different
about whether restarting is worth trying: **0** is this process being asked to go, by a
`shutdown` frame or by a signal; **1** is a failure a restart might well fix, such as a
lost connection; **2** is a refused configuration, which will be refused identically
until something outside this process changes; and **3** is a control channel that could
not be bound, which is the same.

### Proving a movement in world

`test/movement.test.js` drives the real pathfinder walking a real bot, so the routes
themselves are proven at a desk.
What a server adds is the three things no harness has: terrain the *server* streamed a chunk
at a time, real chunk boundaries in it, and a server correcting the client's own simulation.
So a movement change is not finished until it has been walked on a disposable Paper 1.21.1
server, over four shapes and no fewer.
A straight line on flat ground proves the least of them and must not be the only case run.

Set `difficulty peaceful` first, so nothing hostile interferes with a walk being measured,
and build the ground from the server console with the body connected as `loom_demo`:

```
fill -8 64 -8 40 64 40 minecraft:stone
tp loom_demo 4 65 4
```

Then send one `moveTo` per shape on one control connection, each with its own `requestId`,
and read the whole answer rather than the outcome alone.

| Shape | Build | Destination | What it proves |
| --- | --- | --- | --- |
| open ground | nothing | `[12,65,4]` | `arrived`, and `travelled` within a block of the straight line 8 |
| a wall to route around | `fill 8 65 0 8 68 8 minecraft:stone` | `[12,65,4]` | `arrived` with `travelled` clearly *past* 8, which is the route going round rather than into it |
| a one-block rise | `setblock 12 65 4 minecraft:stone` | `[12,66,4]` | `arrived` with `position` a block higher, stepped up with parkour off |
| across a chunk boundary | nothing | `[20,65,4]` | `arrived` 16 blocks away, in the next chunk, over terrain the search read from a chunk the server streamed later |

Clear each build before the next shape, or the later cases are walked against the earlier
one's obstacle.

Four things about the run are worth knowing before it is read.
The body must be `ready`, or a `moveTo` is `notReady` and nothing is searched for.
A destination further than `MOVE_REACH_BLOCKS` (32) from where the body *is* is `outOfRange`
and costs no search at all, so the reach is measured against the body's live position and not
against the coordinates it started the session at.
`travelled` is the ground covered rather than the ground gained, which is the whole point of
the wall case: a route round an obstacle is longer than the line, and a `travelled` equal to
the line is a body that walked through something.
And a `noRoute` on the chunk-boundary case is not necessarily a fault - a client can be asked
about a chunk it has not been sent yet - so give the body a moment after the teleport and read
a `sense` answer first, which is also how the position the destinations are relative to is
confirmed.
A `searchOverBudget` on any of them is a different thing again and is not a fault of the ground
at all: the body's own search budget stopped a search that had not finished, so ask again rather
than reading it as a closed world.

Two more shapes are worth running once, though neither is required.
A `flee` naming a creature a `sense` answer showed proves `separation` and `separationGained`
against a real mob, which needs the difficulty raised again.
And a `halt` sent a second after a `moveTo` proves `cancelled` with the ground covered
reported, and a second `halt` after it answers `stopped` false.

## What is in here

- `src/body.js` - the process: the configuration it refuses, the Mineflayer options it
  chooses, the lifecycle it follows, and the bounded control server. See the rules
  below before changing it.
- `bin/loom-body.js` - the executable. Reads the environment, starts one body, and
  stops it once however this process came to be ending.
- `src/protocol.js` - every bound the contract states, and the codec that enforces
  them. It is pure: no `require`, no I/O, no clock. The bounds are declared here
  rather than in the reader that enforces them, because they are facts about the
  contract and every reader of it needs one place to read them from.
- `src/sensing.js` - one `sense` answer, read from the library and bounded against the
  same exported constants the codec states. It refuses a whole reading rather than
  trimming one field of it, and its own header owns why.
- `src/eating.js` - the eight closed outcomes one meal can have, and the meal itself. Its
  header owns the order of those outcomes and why the slot is read again at apply time.
- `src/interaction.js`, `src/digging.js`, `src/collecting.js`, `src/placing.js`,
  `src/equipping.js`, `src/crafting.js`, `src/combat.js` and `src/sleeping.js` - shared
  offer resolution and seven one-shot native interactions. Each module owns its
  revalidation, deadline, closed outcomes and cleanup; none selects a target, tool,
  stack, recipe, bed or follow-on action.
- `src/senses.js` - the bounded memory of what recent `sense` answers offered, which slot,
  creature, block, dropped-item and recipe handles are resolved against. All kinds are
  remembered together, which its header states is the invariant rather than tidiness.
- `src/movement.js` - the request scope around `mineflayer-pathfinder`: the whole movement
  policy stated field by field, the search settings, and the one owner, four measurements
  and deterministic cancellation the library has no notion of. Nothing in it searches,
  steps, jumps, falls or sends a movement packet. It is also where Mineflayer's own
  `physics` plugin is loaded and held shut, because that plugin is the client-side
  simulation a walk needs and is the one thing in the process that would act unasked.
  Read its header before changing what a body may be asked to walk.
- `src/gamestate.js` - the two things no loaded plugin provides and the one plugin this
  process may not load reports: the login event, and the world the player is in. It starts
  no timer and sends no packet of its own accord. The clientbound `position` packet is
  deliberately **not** read here any more; its header owns why that moved.
- `test/body.test.js` - the process, driven the way a controller drives it: over a real
  loopback socket, against a fake Mineflayer client that emits exactly the events the
  real one emits. The client is fake and the socket is not, because the two halves fail
  in different ways - a lifecycle is Mineflayer's event order and can be stated at a
  desk, while framing, flood behaviour and a deterministic close are socket behaviour,
  and a mock that is not a socket cannot express them.
- `test/plugins.test.js` - the plugin policy, proven against the **real** Mineflayer
  loader over a fake minecraft-protocol client. `body.test.js` proves which options
  this process asks for; only this file proves what those options actually load, which
  is the claim that matters.
- `test/perception.test.js` - `sense` and `eat`, driven over a real socket against the
  real Mineflayer loader: the bounds, the omission count, a handle scoped to one round
  trip on one connection, every one of the eight outcomes, and that nothing is sensed or
  eaten unless a frame asked for it.
- `test/interactions.test.js` - all seven native interactions below their modules and
  across the real control socket: stale, foreign and changed offers, reach and visibility,
  ingredients, workstations, target identity, health, beds, deadlines, exact pickup and
  craft counts, transformed placement results, conflicting ownership, death, disconnect,
  halt and shutdown cleanup.
- `test/movement.test.js` - the request scope, driven against the **real** pathfinder
  walking a **real** bot over ground the harness built. A player's movement is
  client-simulated, so `arrived`, `noRoute`, `timedOut`, `cancelled` and `outOfRange` are
  real routes over real ground with no server, no network and no account; only `stalled` and
  `overTravelled` use a recorder, because each needs minutes of wall clock. Cancellation is
  proven by counting live timers and live listeners rather than by reading the source.
- `test/native-move-terminal.test.js` and `test/native-dig-terminal.test.js` - a walk's
  arrival and a dig, through the real body process, bot, pathfinder and physics.
  **`arrived` means landed, not near.** The pathfinder emits `goal_reached` once the last node
  is within 0.35 blocks horizontally and less than one block vertically, so a walk ending in a
  step down reached its goal mid-fall, and answering then shut the simulation with the body in
  the air - which the first ten-agent live run did, after which that agent's every walk was
  `noRoute`. A reached goal now holds the drained gate until the body has stood still on the
  ground for `ARRIVAL_SETTLE_TICKS` steps with that same grounded position already written to
  the server, then re-checks the goal where it came to rest and re-plans under the stall bound
  if it no longer holds. **No held simulation is released mid-fall either.** A walk that ends
  any other way with the body in the air - a stall, a deadline, a halt - and a dig that removed
  the block its body stood on both keep the gate until the body is at rest by the same rule,
  bounded by `REST_SETTLE_MAX_TICKS`; the fourth ten-agent run left two agents hanging that
  way, one after digging its own support and one after a stalled flight.
- `test/libraries.test.js` - the two claims neither the lockfile nor the policy can make
  alone: that every pinned version is the version actually **installed**, and that the
  policy covers every field the library has. Each permissive default is shown to *be*
  permissive before it is shown to be off, so a case cannot pass against a library that had
  already turned it off for its own reasons.
- `test/harness.js` - the one harness those suites share, from above over a real socket
  and from below over a fake protocol client. It is deliberately not a suite of its own,
  which is why `npm test` names an explicit `test/*.test.js` glob.
- `test/entry.test.js` - the executable, run as a process, because that is the only
  place two of its properties exist: an exit status, and a signal.
- `test/fixtures/faults.js` - the fault injector those process tests install with
  `node --require`, so that the shipped executable carries no test seam of its own.
- `test/protocol.test.js` - focused codec tests, including that the longest frame the
  encoder can produce fits well inside `MAX_FRAME_BYTES`, that an id is bounded in
  code points rather than UTF-16 units, that a refusal can only carry a code from
  the closed set, that a builder refuses what its own decoder would refuse, that a
  member named twice is refused on the bytes before anything is parsed, and that the
  three status counts are bounded against each other as well as one at a time.
- `test/conformance.test.js` - the shared corpus. Every valid fixture is decoded and
  re-encoded to the same bytes, every invalid fixture is refused for the reason its
  name gives, and the numeric-spelling cases are swept separately. A fixture this file
  cannot classify fails the suite.

## Rules the process depends on

**It holds no policy, and that is what it is for.**
The line every rule below is drawn on is **report against act**: this process reads the
state the server sends of its own accord and answers the operations it is asked for, and
it runs no loop and no gameplay decision of its own.
So it cannot choose a meal, a threat, a resource, a target, a destination, or a fight, and
cannot do repeated work of its own.

**Every timer in the process either bounds a request a controller asked for, or does nothing
until one is in flight.** The first kind is a movement or native interaction's own deadline
and a meal's wait for the slot the server restates. The second is one timer and it is the
client library's:
Mineflayer's `physics` plugin arms a 50 ms interval on the bot's own `login` and clears it
only when the connection ends, with no supported way to stop and start it again - so that
interval is unavoidable and is instead made *inert*. With no `moveTo`, `flee`, or explicitly
approaching `collect` in flight the body is not stepped, no `physicsTick` is emitted, and
every packet the client writes to move the body or to say where a step left it is dropped
before it reaches the socket - the position reports and the two packets a control state
writes alike. `src/movement.js` owns that scope and `test/movement.test.js` proves it by
counting simulated ticks and written packets across every way a request can end, rather than
by reading the source.
**The scope needs a second seam beside the wire, because a terminal event is not the end of the library's tick.**
On the search-refusal path the library restores the best-effort partial route after emitting its terminal status and then looks at, walks and sprints the body.
The terminal callback therefore puts the packet gate into a drain state without answering.
That state admits only the current frame's final position and control-deactivation packets, rejects new control activations, and keeps a later request from taking ownership early.
Loom's later listener on the same `physicsTick` samples the completed displacement and live threat, applies every movement bound, and clears whatever route and controls the library left.
The first microtask after the physics frame then closes the gate, releases ownership and reports the outcome, after Mineflayer has written the final position.
The settle checks that teardown once more before the answer is delivered, so an ending outside that normal listener order also leaves no control held and no route behind.
Each half is needed and neither is enough: a dropped deactivation leaves the server sprinting, a dropped final position leaves it one tick behind the report, and an open activation path lets the library move after its terminal event.
The consequence is deliberate: a body the server left in mid-air stays there until a request
owns it. Falling is simulation, and simulation is a request's.

**The client's collision must be the server's.**
Prismarine's physics compares a body's face with a block's face exactly, and vanilla compares them with a 1e-7 tolerance.
A body clamped flush against a face at a negative coordinate (x = -2.3) has its box face at -1.9999999999999998, 2e-16 inside the block, so the exact comparison misses the collision and the client steps into it.
The server refuses that move and puts the player back every tick until the stall bound ends the walk or flight.
`src/collision.js` gives the library's AABB vanilla's tolerance once, from `installMovement`; `test/libraries.test.js` pins the pinned library's own exact comparison, and `test/native-flee-terrain.test.js` replays the two stalled flights on the real s2-ring ground against a server that refuses colliding moves.
**The search may offer only moves the library's own executor can make.**
`getMoveDiagonal` admits a diagonal step up one block when either side corridor of the corner is clear, because it costs the cheaper of the two, but a body's box crossing the corner overlaps both side cells as it rises, so a solid block above either one stops the jump; the executor simulates every step before pressing a control and, finding none reaches the node, presses nothing until its futility check resets the route to the identical one.
r34 delta stood at (-8.43, 66, -27.5) on seed 20011 under an oak leaf at (-8, 68, -28), and three collects and a walking flight stalled there without moving; `ClearanceMovements` refuses such a diagonal unless both side cells are clear at the jump's two heights, and `test/native-r34-delta-terrain.test.js` replays all four on the run's own ground.
**A walk checks it can be left, and may correct itself once, with typed evidence.**
A `moveTo` or `flee` that arrives states its `departure`: whether one move the search offers out of the cell it came to rest in is one the executor can make from that exact position.
Where none is and from the cell's centre one would be, it makes one straight `recentre` step first; a route the library abandons as stuck while the body has not left its start makes one straight `sidestep` into a level cell beside it that the search offers and the executor can make, then resumes the same goal.
Either is stated as the report's `recovery`, at most one per request, never a teleport and never a loop: a second stall counts against `MAX_STALL_RETRIES` as before.
**A dig by a body standing on support also holds the simulation, for its ground state alone.**
Mineflayer answers every server teleport with `onGround: false`, and with the simulation shut
nothing corrects it, so both Mineflayer's dig time and the server's destroy progress ran at
vanilla's five-times-slower airborne rate: bare-hand terracotta took 31.25 s against the 20 s
interaction deadline, and every such dig in the first ten-agent live run timed out.
`holdSimulation` in `src/movement.js` takes the gate only when nothing owns it, and `digging.js`
asks for it only when the client is not already grounded and a collision box under the feet
tops out exactly at the feet, so the step it permits establishes the ground state and moves
nothing. A body in mid-air is not simulated by a dig and digs at the airborne rate it has.
`test/native-dig-terminal.test.js` proves both against a server model that judges a finish by
the last ground state the client wrote.
**`dug` is the server's answer, not Mineflayer's prediction.** Mineflayer sets the block to air
locally as it writes the finish and resolves `bot.dig` on that local update, before the server
has judged the dig. `digging.js` therefore answers `dug` only once a server `block_change` or
`multi_block_change` at the offered position has left it empty; a server that put the block back,
or no server answer inside the deadline, is `libraryFailure`, which the contract reserves for a
result Mineflayer could not confirm.

**None of the six plugins loaded beside the reporting table decides anything, and the line is drawn per
plugin rather than per method.**
The plugin section below names all six; what matters here is that none chooses.
`mineflayer-pathfinder` *searches* - it answers "is there a way from here to the block you
named, and what is it" - and a search is arithmetic over the chunks the body already has,
not a decision about where to go.
`physics` *simulates*, which is the client's own role rather than a choice, and it runs only
while a request owns the path, which the timer paragraph above is the authority on.
Mineflayer's `digging` and `generic_place` plugins define the packet-level primitives one
bounded module may invoke; loading either one does not select a block, tool, item or face.
Its `crafting` and `bed` plugins likewise define one explicit recipe application and one
explicit bed use; neither chooses when, what or where to craft or sleep.
Every field of the pathfinder that would decide something is turned off, and a route is only
ever run for a destination a frame named.
Auto-eat, PVP, collectblock and tool are still excluded, because each of them picks its own
target - what to eat, whom to hit, what to break, which tool to hold - and every one of
those choices is the mind's.
A disconnected body is left disconnected rather than reconnected, because when and
whether to come back is the controller's call and a reconnect loop here would be
gameplay policy in the one place that must hold none.

**All ten operations that act on the world are answer-only, which is a stronger
statement than "no auto-eat plugin" and "no pathfinder goal loop".**
There is no threshold, autonomous target acquisition or timer that starts anything: a
starving body with bread in its inventory does nothing at all until a mind names one slot,
a body with a zombie in front of it stands there until a mind names the zombie, and a body
nobody asks reads nothing.
A route runs only for a destination a frame stated, ends within the bounds that frame
carried, and is never re-issued: nothing here retries, resumes or walks again of its own
accord.
Every field a `sense` reports is state the server sent of its own accord, read back off the
library at the moment it was asked for - `bot.entities`, `bot.blockAt`, `bot.world.raycast`,
`bot.time`, `bot.health`, `bot.food`, `bot.foodSaturation` and `bot.inventory` - so this
process keeps no entity index, no inventory model, no hunger simulation and no food table
of its own.
Hunger is stated in vanilla's own terms for the same reason: `health`, `food` and
`saturation` are three separate facts, not one reserve of Loom's, and what a food is worth
is the server's answer rather than arithmetic here.
An `eat` is `bot.equip` followed by `bot.consume` and nothing else, and a full food bar is
refused by the library rather than by a number this process keeps.

**A handle is scoped to one round trip on one connection, and both halves of that are
load-bearing.**
Every operation against sensed authority names the `senseId` of the answer that offered it,
so a slot, creature, block, dropped-item or recipe handle means something only against the reading
it came from. `src/senses.js` keeps the last `MAX_REMEMBERED_SENSES` (4) answers rather
than a lookup in anything unbounded - a superseded handle is `senseForgotten`, never a
growing map.
All five kinds are remembered together, which is the invariant rather than tidiness: a
partial memory could accept a flight and reject a meal or world interaction from the same
answer.
They are **spent** differently, though, and that difference is about the world rather than
about handles.
A slot handle names a mutable position and is spent by a successful meal, placement,
equipment move or emitted attack. A craft attempt invalidates every inventory and recipe
handle from that answer because it may change several positions. A block offer is spent
when that exact block is dug, and a dropped-item
offer when that exact entity is collected. A creature keeps its identity wherever it goes
and however often it is run from, so one sighting may be fled from more than once; an
unchanged support block may likewise support more than one explicit placement.
That identity is the exact Mineflayer entity object beside its numeric id, because the id may be reused after the creature leaves.
An `entityDead` event marks every remembered offer of that object dead before Mineflayer necessarily removes it from the live table, and an active flight listens for the same exact object until settlement.
An entry is named by the *connection* as well as by the id, because a request id is the
controller's own name for its round trip and two of the connections this process allows
share no numbering: keyed on the id alone, the second controller's answer replaced the
first's, and an eat from the first then ate a stack its own `sense` never offered and
reported it as `eaten`.
The bound stays on the whole memory rather than per connection, so no number of peers can
make it grow, and a handle a connection never held is the `senseForgotten` a superseded one
already earns - which is why nothing on the wire changed for it.
The eight outcomes are closed and each is a distinct fact about the agent's own inventory:
`eaten`, and the seven refusals `senseForgotten` (the answer is no longer remembered),
`slotUnnamed` (that answer does not offer the slot - either it never did, or it did and the
offer has since been spent on a meal), `slotEmpty`, `slotChanged` (something else stands
there now), `notEdible`, `reserveFull` and `slotInaccessible`.
The slot is read again at apply time for exactly that reason, and `src/eating.js`'s own
header owns the order those checks are made in.
**One answer's offer of one slot buys one meal**: a handle a meal was eaten against is spent,
so an eat re-sent against it is `slotUnnamed` - the second of that outcome's two readings, and
the reason it has two.
The re-read cannot stand in for that, because it compares the item's *name* and a half-eaten
stack still has the same name - and `bot.equip` moves nothing when the stack is already in the
held slot, so a hotbar slot still reads the whole pre-meal stack after the meal.
A spent handle is refused before any slot is read, so a re-sent eat is never `slotEmpty` or
`slotChanged` however the equip left the named slot: that is the price of keeping the handle
check first, and the check order is load-bearing rather than incidental.
A mind that wants a second meal senses again, which is also the only way it learns what the
first one left.
`remaining` is the body's **latest observation** of what it still holds rather than a
promised consumption delta, and it is waited for rather than read the instant `bot.consume`
resolves: that promise settles on whichever of three packets arrives first and only one of
them is the slot update, so the completion can arrive first and leave the library's window
still holding the pre-meal count - which reported 3 for a stack of 3, and 1 rather than 0 for
the last loaf.
`SLOT_SETTLE_MS` (250 ms, five game ticks) is how long the slot's own restatement is waited
for, and past it the count is read as it stands and the meal is still reported `eaten`.
That imprecision is deliberate and the alternative is worse: **nothing after `bot.consume`
resolves may be refused**, because the only refusal available for it is `notReady`, which on
this wire means *ask again* - and a stack already in the hotbar is equipped by
`setQuickBarSlot` alone, so the slot still reads the pre-meal item, `slotChanged` is not
reached, and a controller that obeyed ate a second loaf for one decision.
Creative mode reaches that deadline every time, since vanilla consumes nothing there and so
restates no slot, and a creative body must still be told it ate.
A reading the field will not carry is **bounded into it** rather than lost, for the same
reason: a count above 255 is capped, since a server can over-stack the held slot with a
varint the sense answer would have refused, and a count that is not a number at all reads as
nothing left, which is the safe direction for an agent deciding whether to go looking for
food. Losing it left no answer for a meal that had already happened, which is the refusal
this rule forbids.
So `eaten` is exactly "`bot.consume` resolved", with no path between them - which is what
makes spending the slot handle on `eaten` a rule about the meal rather than about a label.
Exactly one item is consumed whatever the count says, because `bot.consume` is what consumed
it.

**The seven native world interactions are one-shot applications of retained authority.**
`dig` names one offered block and uses only the item already in the main hand.
A dig of the block under the body's own feet cell by a body more than 0.2 off its cell's centre, whose box therefore rests on the column beside its own as well, first takes the same one bounded straight step to the centre of its own cell (`movement.finalApproach`), so the body drops into the cell it dug rather than hanging on the neighbour's edge above it: the provider-free v36 rehearsal's delta did, for the rest of the run, and died there. Nothing is chosen, a halt during the step ends the dig unattempted, and from the centre the block, its reach and its sight are read again.
`collect` names one exact dropped-item entity and either waits within passive pickup reach - inside vanilla's grown pickup box from where the body stands (`withinPickup`), never a straight-line distance - or,
when `approach` is true, gives the existing bounded movement wrapper one search for any of the
cells a body could stand in whose pickup box covers that entity's sensed position
(`pickupCells`, `movement.approach`).
Those cells are fixed by the item's position and vanilla's geometry, not chosen: vanilla picks an item up only inside the player's box grown by one block sideways and half a block vertically, a stop one cell short of an item lying far into its block arrives outside that reach, and the item's own cell may have nothing to stand on - r15's stick hung over a one-block trench from the edge of the block beside it, and five walks to its own cell were refused `noRoute`.
A cell counts when its box reaches the item, with 0.1 to spare, from at least one collision-safe point - a centre where the body's box fits the cell whole (`pickupPoint`) - and because a walk ends where the pathfinder first stands in the cell rather than at that point, a route that ends out of reach is followed by one bounded straight step inside the cell to it (`movement.finalApproach`: under one block, at most 20 simulated ticks, the simulation held and released exactly as a route's).
Counting only cells that reached the item from every point of their floor (`covers`) refused the r28 plains rehearsal's alpha `noRoute` for its own dirt in a one-high nook beside its pit, 0.08 past the box from where it stood and inside it from its cell's east side (`test/native-collect-terminal.test.js`).
`noRoute` therefore means no point a body can stand at within pickup reach of the item has a route.
`place` names one support block, one offered slot and one face; the exact offered stack must
already be in the selected main hand, so placement never equips or searches.
A destination a living creature the body can see stands in, the body's own feet or head cell, or one that holds what a placed block does not replace is refused as `destinationBlocked` before any packet, because vanilla will not place a block there. A destination is air or a block vanilla's 1.21.1 `minecraft:replaceable` tag lets the placed block replace (short grass, a fern, water, one snow layer, never a flower or a torch): the provider-free v36 rehearsal's delta could close no shelter face that held short grass.
A destination only the body's own box reaches into, because the body stands off its cell's centre (a route ends up to 0.35 of a block off it, and a pickup or a blow carries a body further), is cleared first by one bounded straight step to the centre of the body's own cell (`movement.finalApproach`, which lets go a glide short of its point so the body comes to rest on it), after which the destination, reach and sight are read again; where no such step can be taken on the same support, it is refused before any packet. Nothing is chosen: the centre is the cell's own, and a halt during the step ends it with nothing sent.
The server answers one placement packet by resending the destination, placed or not, and then acknowledging the packet's sequence (`acknowledge_player_digging`), so the placement settles at that acknowledgement: `placed` when the destination now holds a block other than the one it held, `destinationBlocked` when a body stands in it, and `libraryFailure` otherwise. Only a server that never answers waits out the deadline.
Mineflayer writes every block sequence as 0, and the server acknowledges the highest sequence it handled in a tick, so the packet carries a fresh sequence of its own and only an acknowledgement of it settles the placement: the acknowledgement of a dig cancelled for the placement, handled a tick earlier, is not its answer.
r39 iota closed a face its own box reached 0.227 of a block into; on real Paper the server refused it, resent air and acknowledged it in 54 ms, and a body that read only a placed block as an answer held its one exclusive owner for 330 ticks under fire, until the agent died.
The shift key is held for that one packet and released when the placement settles, however it settles, as a player places against a block: unheld, vanilla uses an interactable support instead - a crafting table opens its window and nothing is placed (the provider-free v36 rehearsal's delta, twelve times) - and held, the server places against any support. Both shift packets pass the shut movement gate as its one-packet permit (`writeShift`), so no other control packet escapes with them.
`equip` names one offered slot and one of the closed destinations `hand`, `offHand`, `head`,
`torso`, `legs` or `feet`; Mineflayer may choose the concrete hotbar slot for `hand`, and the
report states the slot it actually used.
`craft` names one recipe the sense answer offered and applies it from one to eight times. The
offer privately retains the exact Mineflayer recipe, the complete ingredient snapshot, and
the exact reachable crafting table when one is required. It gathers nothing, substitutes
nothing, and revalidates all three before every application.
Mineflayer has no cancellation primitive for a craft already waiting on a window update, so
`crafting.js` scopes both `bot.clickWindow` and its `window_click` packets to the accepted
request. Timeout, halt and teardown close that gate, stop later clicks, and hold ownership
through a 500 ms loopback drain. If the library still has not settled, or attempts another
click packet after the terminal boundary, the terminal answer is sent with the gate closed
and the Minecraft client disconnects in the next microtask; later work is never allowed to
share an inventory transaction whose server result is still unknown.
Mineflayer predicts a craft in the client's own window, so the inventory shows the recipe's delta before the server has handled one click, and an answer there reached the plugin before Paper's `CraftItemEvent` (path rehearsal run-rd3c1K alpha, a disputed craft the server applied). Each applied application therefore waits for one `client_command` statistics request (action 1, never the respawn's 0) to come back as `statistics`, which vanilla handles on its main thread after every click written before it, before a waiting sense reads its boundary or the craft answers - a sense read at a boundary the server had not yet reached claimed one log while the server still held two (the path rehearsal's epsilon); the wait is bounded by `CRAFT_SERVER_ORDER_MS` (500) and the request deadline, and a halt or lost connection ends it at once. An answer names no request, so answers are matched to requests by their order on the connection, and a late answer to a wait that timed out never releases a later craft.
`attack` names one exact retained living entity and one exact offered weapon stack. It emits
at most four swings, stops at the supplied health floor, and may make at most one bounded
approach to the target's sensed position. It never follows a moving target, begins a second
search, changes targets, or chooses another weapon.
`sleep` names one exact offered bed. It rechecks the block, dimension, occupancy, reach and
visibility, asks vanilla to sleep once, and holds ownership until vanilla wakes the player
or bounded cleanup requests a wake. It neither decides that it is bedtime nor treats one
agent's request as authority to skip the world's night.

Each module re-reads the mutable block, entity or stack immediately before its first
Mineflayer call, including the private state/NBT/component fingerprint the public sense frame
does not expose. Dig and place also re-check native reach and line of sight. A collection
keeps watching the exact retained entity object and its stack metadata until pickup or
settlement. Craft rechecks its ingredients, recipe and workstation before each application;
combat rechecks weapon, target identity, target life, reach, pursuit and health before each
swing; sleep rechecks the offered bed before entry. No module selects a tool, recipe,
replacement target, nearby item, bed or follow-on goal.

`body.js` gives movement and all seven interactions one shared owner. A conflicting request
is `notReady`, never a takeover or a queue. Every accepted request has one deadline and one
terminal report; connection loss, death and shutdown abort its listeners, timers and any
owned route. `halt` cancels a dig, collection, craft, combat request or sleep wait when the
operation exposes a stopping boundary. Placement and equipment transactions cannot be
recalled once sent, so ordinary `halt` reports `stopped: false` for them while teardown still
clears Loom's bounded wait.

The operation modules live beside `movement.js` and `eating.js`: `digging.js`,
`collecting.js`, `placing.js`, `equipping.js`, `crafting.js`, `combat.js` and `sleeping.js`,
with their shared mechanical resolution in `interaction.js`. Their headers own the exact
closed outcome ordering and cancellation semantics. The JVM coordinator separately
reconciles block breaks, pickups, placements, crafts, target damage/death and bed
entry/leave against Paper events, and equipment against final server inventory. Every
same-kind event in the bounded request window is consumed by that audit, so an effect on a
different recipe result, target or bed is disagreement rather than an invisible
"no matching event."

**The internal plugin table contains only readers and primitives an operation explicitly
names.**
Mineflayer's default is to load all of them, which would put an automatic respawn and a
whole shelf of action plugins inside a process whose purpose is to make no choices - so
`loadInternalPlugins` is off and `INTERNAL_REPORTING_PLUGINS` is the whole of what is kept.
That is `health` for `spawn`, `death`, `bot.health`, `bot.food` and `bot.foodSaturation`;
`entities` for `bot.entities` and this body's own `bot.entity`; `blocks` for `bot.blockAt`
and `bot.world`, whose `raycast` is what a line of sight is measured with; `time` for
`bot.time`; `inventory` with `simple_inventory` for `bot.inventory` and the two calls one
explicit meal is made of - `bot.equip`, which is defined in the second of them, and
`bot.consume`, which is defined in the first. The same explicit `bot.equip` primitive
now serves the bounded `equip` operation too.
Left out are `kick` (an automatic respawn), `game` (the vanilla client's respawn on a
`win_game` change), and every unrelated action plugin.
The line is drawn per *plugin* rather than per method, though, so two of the ones kept
bring actions the wire does not expose: `entities` defines `bot.attack`,
`bot.mount` and `bot.useOn` beside the entity index, and `simple_inventory` defines
`bot.toss` and `bot.tossStack` beside `equip`.
Neither plugin can be left behind, so the closed operation set and focused call sites are
what keep those unrelated methods unreachable.

`bot.quit` is *not* among them: it is `kick`'s, and `kick` is not loaded, which is why
this process ends a connection through `bot.end` and a reason of its own.
`test/plugins.test.js` proves all of it against the real loader, because an option map is
not evidence about what a loader does with it - including that a whole run of the
operations calls none of those actions.

**Six action plugins are loaded beside that list, each by the module that scopes it.**
They arrive through `bot.loadPlugin`, a separate seam from `loadInternalPlugins` that
leaves that setting off and the list above unchanged.
`mineflayer-pathfinder` is the third-party one, and it is affordable for the same reason the
readers are: it *searches* - it answers "is there a way from here to the block you named, and
what is it" - and a search over chunks the body already has decides nothing about where to go.
What it arrives willing to do is another matter, and the movement section below is about
turning all of that off.
`physics` is one of Mineflayer's own and is here rather than in the list because it is **not**
a reader at all: it steps, falls, collides and writes the client's own position roughly once a
second of nobody's asking, which is the one thing in this process that would act unasked.
It cannot be left out, because a player's movement in Minecraft is *client*-simulated - there
is no "server, please walk me there" packet - so a body that can be asked to walk has to
simulate and report its own position, and that plugin is where all of it is.
So it is loaded by the module that scopes it: both halves of it are held shut unless one
accepted `moveTo`, `flee`, or explicitly approaching `collect` or `attack` owns the path, or
a dig by a supported body holds it for its ground state, and the timer paragraph above is the whole of what that does and does not cover.
Mineflayer's `digging` plugin is loaded by `digging.js`, and `generic_place` by
`placing.js`. Loading them defines `bot.dig`, `bot.stopDigging` and `_genericPlace`; it
does not call one. The surrounding module supplies the retained offer, revalidation,
deadline, one owner and closed result every time the primitive is reached.
The `crafting` plugin is loaded by `crafting.js`, and `bed` by `sleeping.js`. They define
the recipe query/application and sleep/wake primitives used by those exact requests; they
start no crafting or sleep of their own.
One part of it is unscoped and has to be: the clientbound `position` handler is what moves
`bot.entity.position` off `(0,0,0)` and what records the facing a meal is sent with, so every
perception and every meal depends on it whether or not anything is walking.
It also brings `bot.elytraFly` with it, which is in the same category as the unrelated
actions above and is reached by no operation for the same reason.

**Mineflayer's own `game` plugin is not loadable here whatever else it is useful for.**
On a `win_game` game-state change it writes `client_command` back at the server, which is
the vanilla client's *respawn* - exactly the automatic respawn `respawn: false` exists to
prevent, arriving by another route and reachable by any server that sends the packet.
`src/gamestate.js` is Loom's own replacement for it: the reporting half, with none of the
deciding half.
It owns the `login` lifecycle event and the four `bot.game` fields the loaded plugins
actually read - `dimension`, `minY` and `height` for `blocks`, `gameMode` for `inventory`.
The clientbound `position` packet is deliberately **not** among them any more.
It was, while `physics` was left out entirely, and `physics` handles it: that handler is both
what moves `bot.entity.position` off `(0,0,0)` and what writes the `teleport_confirm` the
protocol demands, so a second reader here would confirm one teleport twice rather than
merely duplicate a read.
That file's own header is the authority on the sharp edges in it: why it registers no
`game_state_change` handler at all rather than merely declining that branch, why facing
still has to be recorded by whichever handler owns that packet (1.21.1's serverbound
`use_item` carries the client's rotation, so an unrecorded facing would turn the body on
every meal), and why the world shape is read for 1.19 and above only.
What is given up with `game` is the client brand and the `ping`/`pong` latency reply,
neither of which this process reports and neither of which keeps a connection alive:
`keep_alive` is answered by minecraft-protocol's own client, below any plugin.

**Two packets are written that nothing asked for, and both are protocol flow control rather
than gameplay.**
`physics` answers a position report with `teleport_confirm`, because an unconfirmed
teleport leaves the server ignoring the client; and `blocks` answers a terrain batch with
`chunk_batch_received`, without which terrain stops arriving after the first batch.
Both are in the same category as the `keep_alive` reply above - the protocol's own
handshakes, not choices - and they are the only unasked writes in the process.
The client's own account of where it is standing used to be a third, written at least once a
second for as long as the body was alive; it is now written only while a movement request owns
the path, which is what the timer paragraph above is about.
`test/plugins.test.js` drives a placed body on real ground for several seconds and asserts
that the confirmation is the only thing on the wire.

**The route is the library's and the physics is Prismarine's, and what `src/movement.js`
adds is a request scope the library has none of.**
Nothing in `body/` searches, steps, jumps, falls or writes a movement packet, and there is
no A star, no collision, no stepping and no gravity of Loom's anywhere.
What is added is bounds, one owner, one answer, deterministic cancellation, four
measurements, and the request scope around the simulation itself that the timer paragraph
above states.
That file's header owns the whole of it and is the authority; what follows is the shape of
it and the numbers, so a reader knows which rules exist before opening it.

**The two libraries are pinned exactly, and the pin is checked against what is
*installed*.**
`package.json` names `mineflayer` and `mineflayer-pathfinder` with no caret and no range,
because every claim below is a claim about one version's behaviour: a permissive default
turned off here is off for the version that was read, and a patch release is free to add a
field that arrives at its own default.
Prismarine's physics and its position and vector types arrive as that pair's own transitive
dependencies and are pinned by the committed lockfile with them.
A lockfile alone is not the proof, though, so `test/libraries.test.js` reads the version out
of each installed package: a lockfile states what should have been fetched, and only the
installed tree states what a run actually loads.

**Every movement-policy field is set explicitly, all thirty-three of them, rather than the
handful that look dangerous.**
`new Movements(bot)` arrives willing to dig, pillar, scaffold, parkour, sprint, push through
entities and drop four blocks, so overriding a chosen subset would leave every field the
pinned library adds later arriving at its own default with nothing to notice it.
Digging, pillaring, scaffolding, parkour, door opening, entity pushing and tool selection are
therefore off by name - tool selection as `bestHarvestTool` answering null, since it is a
function rather than a flag - and each is a thing a later explicit skill would own rather
than something this process may reach for on its own.
`test/libraries.test.js` pins the coverage against the *real* library rather than against a
list here, and shows each default to **be** permissive before showing it to be off, so a
case cannot pass against a library that had already turned it off for its own reasons.
The plugin's own six search settings are stated the same way and applied **per request**
rather than once at startup, for two reasons: Mineflayer defers the plugin's injection, so
there is no ordering a startup call could rely on, and a per-request setting cannot be left
behind by something that reset it - every search a body ever runs is run under exactly these
numbers.

**Water is impassable for routing, and that is what "no swimming" means here.**
The ability is not a flag at all: `Movements` has no swim setting, so the mechanism is water
in `blocksToAvoid`, which makes a route through it cost more than the search will pay.
A body that falls in still floats, because that is Prismarine physics doing what vanilla
does rather than a policy of Loom's.
**A walk that starts in water may swim out of it, and only such a walk.**
r27 epsilon drowned at the bottom of its own flooded pit: every walk onto the dry floor two blocks above its feet was refused `noRoute`, because the search had no move at all from a water cell.
For a walk whose body stands in water, `EscapeMovements` lets water be passed and adds the moves the library lacks: straight up through water while the head is under it, then out along an axis onto a block whose top is at most one above the feet, with the library's own jump measured from the water rather than from the block under it, and a node floating over the water treated as the top water cell it is.
A body that starts dry still treats water as a wall.
`test/native-water-escape.test.js` replays the pit offline, and the same walks out of epsilon's real pit on seed 20011 arrive on Paper.

**The server's world border is a wall, and the one step exclusion a route is searched under.**
Mineflayer keeps no border state and Prismarine's physics collides with blocks alone, so a search that did not know the border routed over it, and the server put the player back on every step until the stall bound ended the request.
`src/border.js` reads the four border packets, takes the smaller end of a changing border, and prices every cell of a column a body's box would cross the border from as more than the search will pay.
The v29 plains rehearsal's theta stalled three times against the 128-block border and iota died against it; `test/native-flee-border.test.js` replays theta's crater flight on its converged ground, and the JVM's `BukkitBlockGround` closes the same cells so no walk past the border is offered.
The border also bounds what a body may touch: vanilla refuses every dig of, and placement against, a block outside it (`WorldBorder.isWithinBounds`) and puts the block back, so a sense reports no block past it (`blockInside`), and a dig or placement the border has since moved past answers `notDiggable` or `destinationBlocked` with no packet written.
r36 zeta was offered short grass one column past the 128-block border, and Mineflayer's predicted break waited out the 20 s deadline as `libraryFailure`; `test/native-dig-border.test.js` replays it on zeta's ground.

**A flight may end only on safe ground.**
`SafeFlightGoal` holds the library's inverted goal to an end at least `FLIGHT_BORDER_MARGIN_BLOCKS` (3) inside the world border and no more than `MAX_FLIGHT_DESCENT_BLOCKS` (4) below the lower of where the flight began and the ground around it (`flightFloorOf`: the median standing height of sixteen columns six blocks off, with trees read through); the route may pass anywhere.
In r23 eight flights ended against the border and one agent spent 1100 ticks pinned in a ravine along it.
**Every end the goal refuses is search work the inverted goal no longer ends on its first pop for**, so a restriction measured from the wrong place turns an ordinary flight into one the budget stops.
Measured from the start alone, the floor held a body on an oak's canopy six blocks above the grass to the canopy, and its flight searched 9108 nodes to the budget on real Paper (v32 plains rehearsal, beta); measured from the ground the same flight finds its way down in 67 (`test/native-flee-canopy.test.js`).
A flee may also name `avoid`, other entities of the same sense answer: the end must then be no nearer to any than the flight began, at least `FLIGHT_OTHER_CLEARANCE_BLOCKS` (8) from any it began further from, and its least separation from all of them must grow. The controller names them, so the body still chooses nothing, and an entity gone since the answer is passed over (r24: gamma's flight from a spider ended 0.6 blocks from a creeper).

**Sprinting is the one ability a request decides, which is why the policy is built per
request.**
Sprinting spends food, so a body allowed to sprint whenever it could would be quietly
deciding to be hungrier, and that is a survival judgement and the controller's.
Every other field is the same for every request this process will ever serve.

**Seven numbers bound a movement, and each is derived rather than picked.**
The reach is `MOVE_REACH_BLOCKS` (32), equal to the wire's own `MAX_RETREAT_DISTANCE`,
because a flight is a walk with the goal inverted and running away may not buy a body a
wider reach than walking does.
It is enforced in `movement.js` rather than in the codec because a `moveTo` names absolute
coordinates and the reach is measured against the body's live position, which a codec never
sees.
The travel allowance is that reach times four (128 blocks), because ground covered is not
ground gained and a body oscillating between two nodes covers a great deal while going
nowhere - short enough that a loop is caught in seconds rather than at the deadline, which
matters because `overTravelled` and `timedOut` are separate outcomes on purpose.
`MAX_STALL_RETRIES` (2) counts the library's own futility abandonments, which are what a
wedged body produces, and two is the smallest number that still lets a mob in a doorway be
walked around.
Three of those abandonments are about 10.5 s of standing still and the three searches between them
add at most 1.5 s, so `stalled` is reachable inside the 20 s the wire's widest deadline allows -
and below about 12 s the deadline answers `timedOut` first, so the deadline a controller names is
what decides which of the two a wedged body meets.
**There is no per-call slice, and that is the load-bearing half of the search policy.**
The library slices a search by wall clock and resumes the remainder on the next physics tick,
and a resumed search is not the search it interrupted: it re-runs `postProcessPath` on each
slice, which rewrites the very nodes the A star context still holds in its open heap, so the
resumed search can lose the goal it was one step from and exhaust itself instead.
That arrived as `noRoute` for ground the body had walked most of the way across, about one run
in nine on a loaded machine, and for a `flee` it is an agent told there is nowhere to run.
So `NO_SEARCH_SLICE` is unbounded and one search runs to its end inside one call, which makes a
resume unreachable by construction rather than unlikely.
`SEARCH_BUDGET_MS` (516) is what pays for that, and it is a **control-plane latency figure**
rather than a search-generosity one: it is how long this process may stop reading its control
channel, stop simulating its body and stop answering every other connection it holds.
Each search receives the smaller of that ceiling and the request time still remaining, because
the request timer cannot run while one synchronous search owns the event loop.
The supplied ceiling is retained with the result, so a pathfinder `timeout` is
`searchOverBudget` when the body ceiling supplied it and `timedOut` when the request ceiling did;
any other search result that arrives after the request deadline is `timedOut` before it can
answer first.
It is measured rather than chosen, at the bounds this package already enforces - a walk to the
far edge of the reach visits 32 nodes in 12 to 30 ms, a flight at the full retreat distance
visits 301 and is the worst search that can succeed at 112 to 172 ms, and an unreachable
destination at the full reach exhausts 6115 nodes in 1.8 to 2.9 s, and up to 5.2 s on a loaded
host.
Three times the worst succeeding search is the smallest budget those measurements support, and
`src/movement.js` states them beside the constant.
Reaching it is `searchOverBudget` and never `noRoute`, and a heavily loaded host can reach it on
a search that would have succeeded - which is a fact a controller is told rather than one it is
lied to about.
The budget is the only ceiling of the two: `NO_SEARCH_SLICE` is deliberately unbounded, so there is
one number to raise and one that must stay unbounded.
That ceiling is a body-side one rather than a field of the request, because a search is a cost to
*this process* and a controller able to raise it could stall the process from outside.
`test/movement.test.js` measures the widest search the wire admits and asserts the stall against
the budget, so a change that makes a search slower fails there rather than in world as a quiet
control channel - and it proves the `noRoute` against `searchOverBudget` split at the status seam
rather than by racing a real search against a clock, because which of the two a real search reaches
depends on the host and the split must not.
`MAX_DROP_BLOCKS` (3) is below vanilla's own fall-damage threshold, so a body never hurts
itself walking a route Loom asked for; the library's default of four is exactly the height
that starts to hurt.
`SEARCH_RADIUS_BLOCKS` (64) is twice the reach, against the library's own default of -1,
which is no limit at all - and it is the one bound here that is **weak for a flight and must
not be read as a hard one**, because the library derives its ceiling from the start node's
heuristic and a flee's inverted goal can make that ceiling zero or less, which disables the
radius entirely.
The budget is the one that holds unconditionally.
The wire carries two more of its own, in `src/protocol.js` rather than here:
`MAX_MOVE_TOLERANCE` (16), half the retreat distance, and `MAX_MOVE_DEADLINE_MS` (20 000),
which is below `CONTROL_IDLE_MS` so an answer is never cut off by the socket's own timer.

**One accepted request owns the path, and a second is refused rather than served.**
`bot.pathfinder` has exactly one goal, so a second `setGoal` would silently displace a route
whose answer the first controller is still waiting for: a `moveTo` or a `flee` arriving while
either is running is `notReady`, and the running request is untouched.
Cancellation is `setGoal(null)` and **never** `bot.pathfinder.stop()`, because `stop()` only
sets a flag the library acts on at some later tick while `setGoal(null)` runs the library's
own reset synchronously - the path emptied, the search context dropped, the item locks
released and the control states cleared.
Loom's own listeners are removed first, so the teardown's events are never observed, and what
is left is no listener, no goal, no timer and no control: `test/movement.test.js` proves that
by counting live timers and live listeners rather than by reading this file or that one.
`monitorMovement` is then never called again either, because the same ending shuts the
simulation gate and the plugin stops emitting `physicsTick` - so the body **stops dead and
freezes where it was** rather than decelerating.
That is the request scope working as designed rather than a control left running: the next
accepted request is what starts the body falling or walking again, which an in-world run
confirmed by finding a body hovering at the height a flight left it.

**`undefined` out of the movement functions always means *ask again*.**
It has four causes and they share that meaning honestly: a movement already owns the path,
the library has not injected the pathfinder yet, the body cannot state a bounded position
at all - which is a body that has died or disconnected - or a call into the library threw,
which is a request nothing was searched for and nothing was left running by.
None of the four readings a report carries may be invented, so a body that cannot measure
where it is refuses the answer rather than defaulting one, which is the rule `sensing.js`
states and both `eating.js` and this inherit.
`inventory` also echoes a window `state_id`, but only when acknowledging a click this
process made on purpose.

**`respawn: false` is set explicitly, because Mineflayer defaults it to true.**
Left alone, coming back to life would be a decision this process took by omission, which
is exactly the kind of decision it may not take.
It is pinned in a test rather than trusted to the option list.
It is deliberately *not* the mechanism that keeps a dead agent dead: the server witnesses
that and refuses the login, and this option and vanilla's own playerdata are independent
protections beside it, so three things must fail before a dead agent walks again.
`AGENTS.md` records why the witness is server-side.

**A lifecycle is not a wait for `spawn`.**
A body that logs in already dead gets `login` then `death` with no `spawn` at all, so a
process that reported readiness on `spawn` alone would sit in `connecting` through a
whole death.
Every state in `LIFECYCLE_STATES` is reached from the event that actually means it.

**Only a loopback literal is accepted, to bind or to connect, and the *name*
`localhost` is refused.**
A name means whatever the host's resolver last said, and the one thing this process must
be able to promise is that neither the player nor the control channel left the machine.

**An inherited debug setting is cleared before a dependency can read it, and one of them
cannot be.**
The `debug` package decides whether each of its loggers writes at the moment the logger
is *created*, which is when its module is required, so `silenceDependencyDebug` runs
ahead of `require('mineflayer')` - after which an inherited `DEBUG=*` would print peer
text, host names and local paths straight to stderr, outside the bounded output path this
process promises.
`NODE_DEBUG` is Node's own equivalent and is the exception worth knowing: Node writes its
core debug output while *bootstrapping* the entry point, before a line of this process has
run, so no process can suppress its own and re-execing does not help because the parent
leaks first.
It is cleared all the same, for any logger created later and for any child process.

**`bot.end` and not `bot.quit`, and the lifecycle listeners stay attached.**
`quit` is a plugin function Mineflayer injects during login, while `end` is defined on
the bot at construction, so a body stopped while still connecting - which is every body
whose server is not there - had no `quit` to call and left its socket open.
Removing the listeners on the way out is the other half of the same mistake: it takes
Mineflayer's own `error` listener with them, after which a late client error is an
uncaught exception rather than a disconnection.
Leaving them attached is safe because the lifecycle is terminal at `stopping`.

**Shutdown closes the Minecraft connection first, then the control channel, then the
process, and the order is a promise rather than a coincidence.**
A controller must not be able to read a live state off a body that is already leaving, so
`stop` waits for the client's own `end` - bounded by `SHUTDOWN_GRACE_MS` (2 s), because a
client that will never answer must not hold the process - before the control channel goes.
That ordering was passing vacuously until the wait was added: a synchronous fake client
always finishes before a socket that has to be closed, so the test's fake now takes time
to go, which is the only case in which an order exists to observe.
A shutdown arriving *after* the client has already dropped must not spend that grace at
all, since there is no `end` still to come: a real run measured one taking the whole of
`CONTROL_IDLE_MS` - 30 s of a mute process holding the username it logged in with -
against 11 ms for the same shutdown from `ready`.

**A repeated `shutdown` is an acknowledgement, not a refusal, and teardown still runs
once.**
A controller that did not see the first answer retries, and a process being asked to do
what it is already doing has not been asked for anything it cannot do.
So `createBody`'s `stop` puts its promise in place *before* announcing `stopping`, and a
second call is handed the same promise rather than closing everything again; anything
else pipelined behind the shutdown is told `shuttingDown` on the socket it is still
holding rather than served or dropped silently.

**A stopped body must not outlive its own shutdown, so `bin/loom-body.js` exits
explicitly.**
No teardown route frees the Node event loop: a real client leaves two sockets and two
timers behind, and a body stopped before it ever logged in kept its process alive
indefinitely with nothing to show for it.
**Every route out therefore ends at `leave`, including a `stopping` reached over the
control channel**, and the first status given to it is the one the process exits with.
Reaching `leave` only from `failed` and from a signal was not a smaller version of that
rule but a different one, and it is what left the 30 s shutdown above waiting on whatever
handle happened to be outstanding.
Everything that file says is written with `fs.writeSync`, because
`process.stderr.write` queues into libuv when standard error is a pipe and the last
thing a stopping body says is exactly the line that would be lost to the exit.

**One bad channel is not a reason to abandon a live player.**
The process admits at most `MAX_CONTROL_CONNECTIONS` (32) control connections at once,
and an excess connection is refused `tooManyRequests` and closed.
A connection is closed after `MAX_REFUSALS_PER_CONNECTION` (8) refusals or
`CONTROL_IDLE_MS` (30 s) of silence, and one connection is remembered for at most
`MAX_TRACKED_REQUESTS` (64) request ids.
That refusal budget counts every refusal except `notReady`, which has its own,
`MAX_NOT_READY_PER_CONNECTION` (64), because it is not a fault at all but the answer a
controller is told to ask again after.
So it is mostly a **peer-error** budget - `malformedFrame`, `wrongAgent`,
`duplicateRequestId` and the rest - but not only that: `tooManyRequests` is charged to it
too, and that one an honest controller earns by exhausting a per-session bound rather than by
sending anything wrong. Separating those is part of the follow-up recorded below, not
something this change makes.
Counted as abuse, a controller polling `sense` at 1 Hz through a real login lost its channel
after eight seconds of doing exactly what it was told, and the close forgot every slot handle
on it as well; eight overlapping meals did the same.
Both budgets close the same way: the last allowed refusal is written and the connection goes
behind it, so the 64th `notReady` is answered and there is no 65th request on that channel.
A `notReady` also **gives its request id back**, because that round trip minted no handle and
the controller was told to send the same request again - so an expected poll costs nothing
against `MAX_TRACKED_REQUESTS`, and offering the id again is not a duplicate.
Held, it spent that bound instead: 63 polls filled it, the 64th earned `tooManyRequests`,
which *is* a peer error, and eight more of those closed the channel out of the abuse budget -
putting the failure the separate budget exists to remove back on the same connection, 72
frames in rather than 8.
Every id an *answered* request spends is still kept, so duplicate detection is unchanged for
the round trips that completed.

**The whole family of session bounds is owed a later look, and it is recorded here rather
than changed here.** `MAX_TRACKED_REQUESTS`, `MAX_REFUSALS_PER_CONNECTION`,
`MAX_NOT_READY_PER_CONNECTION` and `CONTROL_IDLE_MS` were sized for a lifecycle-only wire
and are not yet related to each other, and now that `sense` and `eat` are the traffic they
meet, a long-lived controller runs into them: it spends one tracked id per answered round
trip, so past the ceiling its next frames are refused `tooManyRequests`, that refusal is
charged to the abuse budget, and eight of them close the channel - taking every slot handle
on it with them - while the idle bound simultaneously requires it to keep sending. Whether
that ends in a wider ceiling, an eviction rule, a refusal exempt from the abuse budget, or a
stated reconnect protocol is a wire-policy decision with a handle-lifetime question inside
it, so it belongs to the controller milestone that will first meet it rather than to a change
about reading state. Nothing connects to this process in Loom's runtime yet, which is what
makes leaving it safe.
A line with no newline in it is bounded by `MAX_FRAME_BYTES` as a finished frame is,
since a peer that never terminates a line is the one case a per-frame bound cannot see.
That bound is **unconditional**, and a shutdown is when it matters most: a stopping
process reads on until the client's own `end` arrives, so a peer that asks it to stop and
then sends a newline-free blob has a whole `SHUTDOWN_GRACE_MS` of loopback to spend, on a
scan that rereads the accumulation per chunk.
Relaxed while stopping, one shutdown frame turned that bound off for every connection at
once, and what ends such a run is the unbounded fatal report `bin/loom-body.js` exists to
keep off stderr.
The process itself carries on, and the control channel keeps serving other connections.

**A correlated refusal spends its request id and counts as a request handled, and the
one exception is the id bound itself.**
Read the other way round a controller could spend one id for ever: a refusal that
reserved nothing would let the same id be retried indefinitely, and `requestsHandled`
would not reconcile against what the controller sent.
So a refusal a `requestId` can be echoed into reserves it - the same id afterwards is a
`duplicateRequestId`, not a second refusal of the original kind - while an
*uncorrelated* refusal, such as a line that could not be parsed at all, reserves nothing
because there is nothing to reserve.
Past `MAX_TRACKED_REQUESTS` a further id is refused `tooManyRequests` and **never
recorded**, which is what makes that bound a bound: duplicate detection stays exact for
every id the process did accept, the refusal is repeatable rather than turning into a
duplicate, and both the process and a fresh connection stay usable.
Every *correlated* refusal counts in `requestsHandled` all the same - the id bound's own
refusal included, since it is still a request this process answered - so a flood is
bounded in bytes, in answers and in memory while the traffic a controller correlated
stays reconcilable from the outside.
An uncorrelated refusal counts in neither, so the number is short by however many lines
a controller sent that could not be read as a request at all.

## The contract itself

`../contract/body-control.schema.json` is the single source of truth, and it is a
**separate document from `../contract/loom.schema.json`**: that one carries what an
agent perceives and decides, this one carries this process's own sixteen operations -
lifecycle and shutdown, one bounded raw reading, one named meal, one walk, one flight,
seven request-scoped native interactions, and one cancellation of the exclusive work
that can still be stopped. The contracts
define separate message types, and neither codec imports the other contract's bindings.
The overlap in subject is deliberate and is not a shared type: a `sense` answer is what
one player client was told, and turning it into an agent's `Perception` - the radius, the
observation bound, the semantic translation, the threat classification and the
request-scoped refs - stays above this wire, in the coordinator.

Three hand-written descriptions of one wire, none generated from the schema:

| Description | Conformance test |
| --- | --- |
| `src/protocol.js` | `test/conformance.test.js` |
| `../plugin/src/main/kotlin/dev/loom/bodycontrol/` (`BodyControl.kt` + `BodyControlCodec.kt`) | `BodyControlConformanceTest` |
| the schema | validated by the JVM test with a real JSON Schema validator |

The shared corpus is what keeps them in step, which is the same discipline the
perception/decision contract uses and the reason for it: a generated binding would
only prove the generator ran. A schema-shaped case belongs in a valid or invalid
fixture, and both suites fail until both codecs follow it. Numeric spellings that
cannot round-trip to their original bytes belong in `numeric-spellings.json`, described
below.

The corpus has **three** directories, and the third one is the interesting one:

| Directory | The schema | Both codecs |
| --- | --- | --- |
| `valid/` | accepts | accept, and re-encode to the same bytes |
| `invalid/` | rejects | refuse, with the code the file name states |
| `beyond-schema/` | **accepts** | refuse, with the code the file name states |

`beyond-schema/` exists because the schema is a description of a *shape* and several of
this wire's rules are relations between fields rather than shapes. Two of them Draft
2020-12 cannot express at all - that a saturation is no larger than the food level it
sits in front of, and that an inventory's slots arrive in strictly increasing order with
none repeated. The rest it *could* express as conditionals and deliberately does not:
that `what` and `remaining` are present exactly when an eat succeeded, and that an
omission count is only reported against a list something was in fact cut from. Splitting
them would mean a reader meeting two mechanisms for one kind of rule, so every relation
is stated once per runtime instead and the schema is left permissive throughout.

One of those two relations is owed a later look, and it is recorded here rather than
changed here. Vanilla's saturation is bounded by the food level, so `saturation <= food`
is the rule both codecs enforce and `sensing.js` refuses a whole reading against - but
that ceiling is a *server* rule about a live player, and a client can be told a pair that
briefly disagrees with it. Widening or dropping it is a change to the shared wire, so it
belongs in a contract review of its own with fixtures on both sides, not in a change about
reading state; until then a body refuses the reading rather than reporting a pair the
contract calls impossible, which is the safe direction for evidence a survival run is
measured from.

Each such fixture is therefore pinned from **both** sides at once, and the two halves are
in different suites. `test/conformance.test.js` proves the Node codec refuses the frame;
`BodyControlConformanceTest` proves the JVM codec refuses it *and* that the schema really
does admit it, since `body/` carries no schema validator. Both halves are needed: a
category proved on one side only would let a case that in fact belongs in `invalid/` sit
here unnoticed.

Every fixture is compact JSON on one line with **no trailing newline**. Valid fixtures
use the exact key order both encoders emit - `op` first on internally tagged frames.
The valid round-trip checks and the corpus-format checks make reformatting any fixture
fail.

## Seven bounds worth knowing before changing anything

**Every bound is re-checked at decode, because nothing on this wire is
schema-validated at runtime.** `minLength`, `maxLength`, `pattern`, `minimum` and
`maximum`, along with every `const` in the schema, are rules a socket walks straight
past. Each runtime states each bound once, for itself, and the conformance corpus
proves the two statements agree.

**A frame's size and its nesting are settled before anything reads it, and the order is
the whole of that guarantee.** `MAX_FRAME_BYTES` is 32 KiB, eight times tighter than the
cognition wire's 256 KiB, which is sized for a whole perception rather than for one
bounded answer about a body.
The largest frame the contract admits is a sense report with every entity, block,
dropped-item and inventory list at its own bound, every string at its limit and every
number written as long as a formatter writes one. At the 48/48/48 limits it is 27,823
bytes including the newline under Node's formatter and 26,346 bytes before the newline
under the JVM formatter. The 32 KiB ceiling therefore keeps 4,945 bytes of headroom on
the wider production encoding; both suites construct and measure the extremal frame and
also require at least 4 KiB plus one widest repeated offer of spare capacity.
On an Apple M3 Pro, warmed local loops over that extremal frame averaged 371 microseconds
to decode and 468 microseconds to encode in Node 25.9 (10,000 iterations), and 673
microseconds to decode and 76 microseconds to encode on Corretto 21 (1,000 iterations).
These are local codec-cost baselines, not network timings; remeasure them when a list
bound or field shape changes.
A real's contribution is the length of its decimal *text*, which is longest nowhere near
the world border: the widest legal coordinate is the double just inside the magnitude at
which `Number.prototype.toString` stops writing plain decimals, and it is six characters
wider than the largest coordinate the border admits.
The test derives that rather than naming it, and the JVM twin derives its own because
`Double.toString` reaches its longest text at a different magnitude. A sense-list or
string bound raised later still has to be checked against the wider measurement, and a
frame-bound change must move in both runtimes and the schema description at once.
The bound is
enforced at *every* public decode rather than only in the framing reader `src/body.js`
puts in front of the decoders, since a controller can reach a decoder without it. A
bound applied after the work it exists to bound is not a bound, so it runs ahead of the
duplicate scan and ahead of the parse, both of which walk the whole line. It is counted
in UTF-8 bytes, because that is what the wire carries: eight thousand astral characters
are 32 KiB on the socket, and measuring them as eight thousand would let a peer spend
four times the bound. On the JVM the measurement is deliberately two clauses
(`exceedsFrameBound`), because finding an unbounded line's byte length allocates an
unbounded array first - and UTF-8 never spends fewer bytes than a string spends UTF-16
units, which makes the `String.length` clause a sound guard rather than a shortcut with
a different meaning.
`MAX_FRAME_DEPTH` (8) is a second bound and not the byte bound in another form: 32 KiB
buys more than sixteen thousand open brackets, and a deeply nested line well inside that
byte bound raises a
`StackOverflowError` on the JVM off the walk for explicit nulls, which spends several
call frames a level rather than one. A `StackOverflowError` is an `Error` and not an
exception, so nothing in either decode path catches it. Both runtimes therefore settle
the depth on the raw line, with a walk that does not itself recurse - a check against
stack exhaustion that recursed per level would be the thing it exists to refuse. V8's
`JSON.parse` is iterative and reads a hundred thousand levels, so **the Node side
carries the number for parity rather than for safety**: both ends refusing the same
frames is the point of three hand-written descriptions of one wire. Eight is far above
anything this generation can hold: the deepest legal frame is a sense answer at **four** -
the top object, one sense list, an offered object, and its `position` array - and raising
any list bound widens rather than nests it, so the four spare levels are spent only by a
shape this generation does not have. There is
deliberately no "too deep" code - over the byte bound is `frameTooLarge`, and a frame
refused before it is parsed is a malformed frame. Both suites pin the depth at the
deepest line the byte bound admits rather than at a round number, because a round number
passes with the guard gone.

**A frame is never encoded unchecked, so what a builder writes is what a decoder would
accept.** There is no `encodeLine`: `encodeRequest` and `encodeResponse` feed the line
they produced back through their own decoder and throw
`the body-control protocol will not carry this frame: <code>` if it comes back refused,
and every frame builder routes through the same check. The throw carries only the
closed-set code, never the frame. What they then write is the *canonical* line rather
than the caller's - the schema's own key order with the `op` tag first - so a frame built
with its fields in any order encodes to the bytes the fixtures carry. The JVM half needs
no equivalent check, because there a frame is a type: every variant's `init` refuses the
same bounds, so a frame that cannot be encoded cannot be constructed either.

**Refusals carry only a closed error code and bounded identifiers.** The first rule is
that `error` is a closed enum, never a message - the one place this protocol is
deliberately stricter than the cognition wire's free-text refusal, since an exception
string, a file path, a machine name or a token structurally cannot pass through a field
that can only hold one of twelve words.
`refusal()` coerces a code it does not recognise to `malformedFrame` rather than passing
it through.
The second is that **every id is validated, in a built frame as well as in a decoded
one**, because a `requestId` or an `agentId` is echoed back into a frame and written
into logs. `ControlId` is `^[A-Za-z0-9._:-]+$`, 1 to 64 code points, so what the bound
rules out includes slashes, backslashes, whitespace, quotes and newlines - which keeps
a POSIX or Windows path, a credential path, and ordinary transport-error text out of
anything that gets written down.
It is deliberately **not** "anything that resembles a machine": a dotted,
whitespace-free token such as `build-host-07.internal.example` **is a legal id**, since
that is the shape an ordinary agent name takes, and narrowing the pattern to refuse one
would refuse legitimate names. Both suites sweep the five illegal shapes through every
id field of every builder, and both pin the dotted token as legal so the bound cannot
be quietly narrowed.
The third rule is that the guarantee holds on the way out of a *failed decode* as well,
which is where a serialization library otherwise hands the frame straight back:
kotlinx-serialization names the text at an offset, the key it did not know, the enum
value it could not match and the discriminator it dispatched on, so `BodyControlCodec.kt`
translates every parser, enum, unknown-key and binding failure into one fixed sentence
with **no cause attached** - a cause would carry back the very message the catch exists
for. `SerializationException` is a subclass of `IllegalArgumentException`, so that catch
deliberately names the subtype, which is what lets this package's own bounded refusals -
and the `init` blocks' - keep the sentences they chose. The JVM suite plants a
credential-and-path marker in each of five failure positions in turn and asserts none of
them comes back, in the message or as a cause; the Node suite sweeps the same
leak-bearing shapes through what its builders throw.

**Two schema rules need checks before binding decode has discarded the evidence.**
A **member named twice** never reaches JSON's data model at all - a parse keeps one of
the two values, drops the other, and reports nothing - so a controller and a body
process reading the same bytes could act on different agents. An **explicit null**
survives a JSON parse, but Kotlin's `explicitNulls = false` binding decode maps
`"requestId":null` to the same absent value as an omitted `requestId`, making a refusal
that named null indistinguishable from one that named no request.
Both runtimes therefore scan the raw line for duplicate names. Node preserves null in
the parsed object and rejects it during field validation; the JVM walks the parse tree
for null before kotlinx-serialization constructs a binding. Those checks are why JVM
callers must use `BodyControlCodec.kt`'s `decodeBodyRequest` / `decodeBodyResponse` /
`decodeBodyError` rather than calling `decodeFromString` directly. No failure message
repeats a member name or value from the frame, since both are peer data.
Only one of the two can be a fixture. A null is a value the JSON Schema data model
has, so `invalid/error-null-request-id.json` is refused by the schema and by both
decoders like any other invalid case. A duplicate never reaches that model at all, and
the JVM suite asserts the schema refuses every file under `invalid`, so a duplicate
fixture would fail that sweep: it is a codec test on both sides by necessity.

**A status count is bounded above at 9007199254740991, which is JavaScript's largest
exactly-representable integer and not the JVM's.** The other end of this wire is a Node
process, so a `Long` the JVM would encode happily is a number this codec cannot read
back: bounding at what the JVM can hold would make a frame legal to the contract and to
one runtime and unreadable to the other. The narrower runtime's bound is the only one
both sides can hold, and each binding states the same number for itself.
Two of the three counts are also bounded *against each other* rather than by a range: a
process starts in a state, so `stateMs` can never exceed `uptimeMs`, and answering the
status request is itself handling one, so `requestsHandled` of zero has miscounted its
own answer. Draft 2020-12 cannot compare two properties, so the first is stated in the
schema as prose and enforced by both codecs; equality stays legal, since a process in
its first state has been there for its whole life.

**A whole number is a value and not a spelling, and that is a bound on how a *literal*
may be written rather than on what it may say.** To JSON Schema 2020-12 and to
`JSON.parse` alike, an `integer` is any number whose fractional part is zero, so `1`,
`1.0`, `1.00` and `1e0` are four spellings of one protocol generation and `1e3` is a
thousand milliseconds. kotlinx-serialization reads an `Int` or a `Long` from the raw
literal *text* instead, so it accepted the exponent spellings and refused the
decimal-point ones - a frame legal to the contract and readable by one runtime and
unreadable by the other, on a wire whose whole purpose is that the two agree.
`IntegralNumbers.kt` is the JVM's statement of the schema's rule, reached through a
file-level `UseSerializers` so that every number on this wire inherits it and a numeric
field added later cannot forget it. Encoding is untouched, which is what keeps the valid
fixtures round-tripping byte-for-byte - and is also why a zero-fraction frame **cannot**
be a valid fixture, for the reason a duplicate member cannot be one: a zero-fraction spelling
of the generation re-encodes to the plain one, so it would fail the round-trip sweep. The parity is
therefore pinned in code on both sides, and the JVM suite asserts the *schema's* verdict
on the same bytes so the two readings are proven equal rather than asserted to be. A
*fractional* value has no such problem and is an ordinary shared case,
`invalid/response-status-fractional-count.json`.
Each codec first caps a numeric literal at 32 characters, before doing exact arithmetic.
JSON Schema's parsed-number data model cannot express that lexical work bound, so the
schema owns it in its top-level description and each codec enforces it locally.
Within that bound, two things are still refused. A fraction, however it is spelled,
since `1.5e0` is no more a count than `1.5` is. And a literal whose cost is its point.
That guard is two-sided: it refuses `1e2000000000` before constructing a
two-billion-digit integer, and `1e-100000000` before exact integrality checking can
construct a power-of-ten divisor with a hundred million digits. A bound on the
literal's length alone catches neither.
**The positive-side digit count uses the *significant* digits, so neither end of a
mantissa's padding is charged to it.** Counting the digits the literal was *written*
with refused `1.00000000000000000` for its trailing zeros, and counting them after the
shift but before stripping the leading ones refused `0.0000000000000000001e19` - both
spellings of `1`, and both accepted by the schema. Node's pre-materialisation ceiling
is the exact 16 digits of `MAX_COUNT`; the JVM keeps a 20-digit conversion-work ceiling
so representable values can reach the field's own range check. Conversion and that
range check still enforce the same wire maximum.
A mantissa of nothing but zeros is answered before the count and never scaled, because
the exponent cannot change a zero: `0e2000000000` and `0e-9999999999999999999999999`
are legal zeros with neither a number to build nor a bound to check, and the JVM needs
that path for a second reason - `BigDecimal(String)` throws outright when the scale
will not fit an `int`.
The whole set of spellings both runtimes must agree on is checked in one place,
`contract/fixtures/body-control/numeric-spellings.json`, which each suite sweeps over
identical bytes; the JVM side also measures the schema's own verdict on each of them
with a real validator. It is not under `valid`/`invalid` because a valid fixture must
re-encode to its own bytes, which is precisely what a zero-fraction spelling cannot do.
**Both readings are taken from the frame's own bytes, because a parsed number no longer
knows how it was written.** `JSON.parse` answers with the nearest double, so
`9007199254740991.4` arrives as a whole number, and `1.0000000000000000001` and
`0.9999999999999999999` both arrive as exactly `1` - fractions to the schema, invisible
to any check of the value, and not even catchable by comparing against the protocol
version. `spellsWholeNumber` therefore re-reads the literal out of the line, using the
same walk `namesAMemberTwice` uses to skip string literals, and every number on this
wire is checked twice: once as the value it parsed to and once as the text it was
written as. The JVM reads `BigDecimal` for the same reason, so neither runtime inherits
its parser's idea of an integer and the schema's reading is the only one either of them
has.
