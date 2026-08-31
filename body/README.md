# The Loom body process, and the Node side of the body-control protocol

This package is two things: the **body process**, one persistent Mineflayer player
driven over a loopback control channel, and the Node half of Loom's **body-control**
contract, which is the versioned newline-delimited JSON protocol it is driven over.

**It is inert, and nothing in Loom speaks to it.**
No Paper wiring exists yet: the plugin does not start this process, does not connect to
its control channel, and does not know this package exists.
**This package does not replace the Villager body.**
`/loombody` is unchanged and the Villager body is still the only body Loom's runtime
drives; the Paper runtime wiring, and the cutover that eventually retires the Villager,
come in later slices.
What lands here is the foundation both of those need: a process that can be started,
asked how it is, and told to stop, and a contract two runtimes already agree on before
either of them speaks it in anger.

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
is `require()`d rather than scanned on Node 25, and the glob also keeps
`test/fixtures/` - which holds a fault injector rather than a suite - out of the run.

## Running it against a disposable Paper server

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
{ printf '{"op":"handshake","protocol":1,"requestId":"h1","agentId":"loom.demo.body"}\n'
  sleep 1
  printf '{"op":"status","protocol":1,"requestId":"s1","agentId":"loom.demo.body"}\n'
  sleep 1
  printf '{"op":"shutdown","protocol":1,"requestId":"x1","agentId":"loom.demo.body"}\n'
  sleep 1
} | nc 127.0.0.1 25585
```

A `status` answer carries the lifecycle state and three counts; a `shutdown` is answered
`stopping` **before** anything closes, and then the Minecraft connection, the control
channel and the process itself go, in that order.
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
The whole Mineflayer subscription is five lifecycle events - `login`, `spawn`, `death`,
`end`, `error` - and nothing else: no tick listener, no physics, no chat, so this process
cannot choose a meal, a threat, a resource, a target, a route, or a fight, and cannot do
repeated work of its own.
No action plugin is installed either, and none belongs here: pathfinder, auto-eat, PVP,
collectblock and tool all decide things, and every one of those decisions is the mind's.
A disconnected body is left disconnected rather than reconnected, because when and
whether to come back is the controller's call and a reconnect loop here would be
gameplay policy in the one place that must hold none.

**One Mineflayer internal plugin is loaded, and the login half of the lifecycle is
Loom's own.**
Mineflayer's default is to load all of them, which would put a physics interval,
autonomous position updates, an automatic respawn and a whole shelf of action plugins
inside a process whose purpose is to make no choices - so `loadInternalPlugins` is off
and `INTERNAL_LIFECYCLE_PLUGINS` is the whole of what is kept, which is `health`, for
`spawn` and `death`.
`login` is `announceLogin`, a plugin of Loom's own that emits the lifecycle event off the
raw packet and reads nothing out of it.
Mineflayer's own `game` plugin is **not loadable here whatever else it is useful for**:
on a `win_game` game-state change it writes `client_command` back at the server, which is
the vanilla client's *respawn* - exactly the automatic respawn `respawn: false` exists to
prevent, arriving by another route and reachable by any server that sends the packet.
What is given up with it is `bot.game`, the dimension codec, the client brand and the
`ping`/`pong` latency reply, none of which this process reports and none of which keeps a
connection alive: `keep_alive` is answered by minecraft-protocol's own client, below any
plugin.
`test/plugins.test.js` proves that against the real loader, because an option map is not
evidence about what a loader does with it.

**`respawn: false` is set explicitly, because Mineflayer defaults it to true.**
Left alone, coming back to life would be a decision this process took by omission, which
is exactly the kind of decision it may not take.
It is pinned in a test rather than trusted to the option list.

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
agent perceives and decides, this one carries only whether a body process is alive,
which lifecycle state it is in, and when to stop. The contracts define separate message
types, and neither codec imports the other contract's bindings.

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
the whole of that guarantee.** `MAX_FRAME_BYTES` is 8 KiB against a largest legal frame
of 297 bytes - two orders of magnitude tighter than the cognition wire's 256 KiB, which
is sized for a whole perception rather than for a lifecycle question - and it is
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
`MAX_FRAME_DEPTH` (8) is a second bound and not the byte bound in another form: 8 KiB
buys 4 095 open brackets, and the margin between the two measured out at none. A
4 095-level line is 8 190 bytes, and with the guard removed it raises a
`StackOverflowError` on the JVM off the walk for explicit nulls, which spends several
call frames a level rather than one. A `StackOverflowError` is an `Error` and not an
exception, so nothing in either decode path catches it. Both runtimes therefore settle
the depth on the raw line, with a walk that does not itself recurse - a check against
stack exhaustion that recursed per level would be the thing it exists to refuse. V8's
`JSON.parse` is iterative and reads a hundred thousand levels, so **the Node side
carries the number for parity rather than for safety**: both ends refusing the same
frames is the point of three hand-written descriptions of one wire. Eight is far above
anything this generation can hold, since no legal frame nests at all. There is
deliberately no "too deep" code - over the byte bound is `frameTooLarge`, and a frame
refused before it is parsed is a malformed frame. Both suites pin the depth at the
deepest line the byte bound admits rather than at a round number, because a round number
passes with the guard gone.

**A frame is never encoded unchecked, so what a builder writes is what a decoder would
accept.** There is no `encodeLine`: `encodeRequest` and `encodeResponse` feed the line
they produced back through their own decoder and throw
`the body-control protocol will not carry this frame: <code>` if it comes back refused,
and all four builders route through the same check. The throw carries only the
closed-set code, never the frame. What they then write is the *canonical* line rather
than the caller's - the schema's own key order with the `op` tag first - so a frame built
with its fields in any order encodes to the bytes the fixtures carry. The JVM half needs
no equivalent check, because there a frame is a type: every variant's `init` refuses the
same bounds, so a frame that cannot be encoded cannot be constructed either.

**Refusals carry only a closed error code and bounded identifiers.** The first rule is
that `error` is a closed enum, never a message - the one place this protocol is
deliberately stricter than the cognition wire's free-text refusal, since an exception
string, a file path, a machine name or a token structurally cannot pass through a field
that can only hold one of eleven words.
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
be a valid fixture, for the reason a duplicate member cannot be one: `{"protocol":1.0}`
re-encodes to `{"protocol":1}`, so it would fail the round-trip sweep. The parity is
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
