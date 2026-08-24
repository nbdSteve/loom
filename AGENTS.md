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

The fixtures are also the wire: the mind's integration test replays every `perception-*.json` at a real socket, and the JVM transport test uses the `decision-*.json` bytes verbatim as responses, so the two runtimes meet at the fixture rather than at a mock on either side.

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
`NmsEntityBodyPort.tick` retires a body that has left the world - it drops from `bodies` - and then asks *why* it left before charging anything to the agent.
Only a departure that ends the agent reaches `Mortality`; then the death is recorded and `spawn` refuses that agent id from then on.
An agent can also end without anything happening to its body, by spending its energy reserve, so `NmsEntityBodyPort.die` is the single funnel both causes go through: "died once" must not depend on which cause reached the record first.
The rule is held by `Mortality` in the engine-free domain, which records a death and deliberately offers no way to clear one, so a revive path cannot be added by accident.
`BodyDeparture` (also engine-free) is the verdict on what a removal was, and `DEATH` is the only variant with `endsTheAgent`.
Despawning is not death: it is how Loom puts a body away (as on plugin unload) and leaves the agent embodiable.
A death owes the mind a *terminal* body-death `Trigger` once cognition is wired - "this agent has DIED", not "take another body".
That signal is deliberately not sent from `tick`, and no death `Trigger` exists in the contract yet, so it is left as a marked owed signal at the retire site rather than built now.
`Mortality` currently holds the dead in memory, so its refusal spans the server process; making death outlive a restart belongs with agent-identity persistence, which does not exist yet.
The append-only event log is meanwhile the durable record of who has died.

**The first survival drive is an energy reserve, and running it out is a death.**
`Metabolism` (engine-free) holds the reserve, spends it from the tick the port is given, and reports it - nothing more: ending the agent that ran out is the port's job through `die`, and whether an agent has ended stays `Mortality`'s to say, so death keeps one source of truth.
The reserve is counted in **ticks**, never in wall-clock time and never as the fraction the mind sees: the same tick sequence has to end the same agent at the same tick however fast it is replayed, a paused server must starve nobody, and a fraction decremented every tick would accumulate float error across a boundary that is a permadeath.
`SelfState.energy` is that reserve as a 0.0-1.0 fraction, derived on demand, and it rides the ordinary perception - so an agent reacts to its own hunger through the goal review it already does, with no new `Trigger` and nothing to ask for it.
The one knob is `DEFAULT_ENERGY_LIFESPAN_TICKS` (36 000 ticks, half an hour at twenty a second), a `Metabolism` constructor default rather than a config file: long enough that a body spawned to be looked at is not dying while it is looked at, short enough that a whole life fits in one sitting.
The one restoration is `Intent.Rest`, which refills the reserve completely.
It is carried out by the *port*, not the entity, because a dimension transit replaces a body and must not replace the reserve; standing still is the body flavor's half of it.
Exhaustion is terminal in the type and not only in the record - `Metabolism.restore` refuses a spent reserve, so an agent cannot rest its way back out of a permadeath.
The reserve is charged at the top of `NmsEntityBodyPort.tick`, so a body whose reserve has just run out takes no further step, and the deaths it causes are applied at the *end* of the tick, once every body is where the tick leaves it: an agent starving mid-transit is re-embodied on the far side first and dies there, rather than leaving the engine's leftover villager standing in the destination.
The reserve is in memory only, like `Mortality`'s dead; making either outlive a restart belongs with agent-identity persistence, which does not exist yet.

**An agent has two vitals, and how each reaches the log is `BodyVitals.kt`'s whole job.**
The reserve above is one; the body's health is the other, and a body can be beaten to death on a full reserve, so a run that could not tell the two apart could not say what ended an agent.
`VitalsWatch` therefore gives each vital **its own `EnergyWatch`**, because they move for unrelated reasons: the reserve drains on a half-hour ramp while health drops in one hit.
A shared step function let a wound sit unrecorded until the reserve next stepped - up to eighteen seconds in world, by which time the in-world run that found it had a dead agent.
Each vital has its own event (`energyChanged`, `healthChanged`) and every such record carries **both** readings: which one moved is the event's name, and what the other stood at is what makes the record readable alone.
Records that carry a reading anyway (a spawn, a perception) tell the watch through `noted` so the next tick does not restate it; a record with *no* health reading passes none, since taking an unstated reading as recorded is how a wound would be silently dropped.
**No vitals record may follow a death.**
Only the actuate branch of `NmsEntityBodyPort.tick` samples them, for the same reason a movement record after a death is a lie: the other branches have no body of that agent's left to read, and an in-world run wrote two readings *after* the `died` record off an entity the engine had already removed.
Nothing is lost by it - a `died` or `despawned` record states the readings it ended on, and a traveller states its own from the spawn of the body it arrives in.

**Losing a body is not always losing the agent.** Loom is multi-dimension: an agent may walk into a nether or end portal, and that transit is continuity, not death.
So a removal has to be explained before it is charged to the agent, which is what `BodyDeparture` and `dev.loom.body.nms.departureOf` are for.
Read `BodyTransit.kt` before touching that classification; it records what the engine actually does at each seam.
Two of those seams are counter-intuitive:

- A dimension transit does not carry the body across. The engine builds a *plain vanilla* entity in the destination, copies state onto it, and removes the original with `CHANGED_DIMENSION`; the successor cannot be turned into a `LoomBodyEntity`, so Loom reads its arrival position, discards it, and re-places its own body there through the ordinary spawn path - which is also what takes the destination hold before the body counts as live.
  Loom's replacement body therefore inherits nothing automatically, and the portal cooldown has to be carried across by hand (`carryPortalCooldown`): the body arrives *inside* the destination portal, so without it the agent travels back and forth once per tick. An in-world run caught exactly that, 1465 times in under a minute.
- A conversion cannot be recognised after the fact: lightning turning a villager into a witch discards the original with `DISCARDED`, the same reason an ordinary discard uses. The only uniform seam is the cancellable Bukkit `EntityTransformEvent` (`BodyConversions`), which Loom cancels - a witch is not a body Loom can drive or perceive through - and records on the agent's own chain.

Both are logged as `travelled` / `converted` on the travelling agent's chain, never as a death followed by a birth, so the research record keeps one continuous life per agent.
A `converted` record says in its `outcome` which of the two conversions it was - `conversionRefused` for the body Loom kept, `bodyTaken` for the failsafe branch where the conversion took it and the agent is left unembodied - because those are opposite facts about whether the agent still has a body and nothing but the tag distinguishes them.

**Agents carry their own loaded world, and permadeath detection depends on it.**
Loom has no human players, so by default nothing keeps a chunk loaded and an agent nobody is watching stops ticking.
That is not merely a liveness problem: `RemovalReason.UNLOADED_TO_CHUNK` removes a body without killing it, so a bare "removed means dead" check would mark an unwatched agent permanently dead - an agent dying for want of an observer.
Instead Loom holds the world open around every living agent, in *every* dimension it has agents in, so an unload is never among the removals the port has to explain.
That is what lets `departureOf` treat an unexplained removal as a real death, and it has to hold in every dimension an agent can reach or the rule stops being sound there.
The policy is `WorldFootprint` in the engine-free domain, keyed by world, so nether and end holds are ordinary entries rather than a special case and a body that walks through a portal simply moves its hold (the coalesced union of a chunk area around each living agent, so cost tracks the population's spatial footprint rather than its head count, and a group that splits off keeps its own area); `dev.loom.body.nms.HeldChunks` is the engine half that makes the holds real and records each change to the event log under the reserved world-level agent id `loom:world`, which `AgentId` refuses so no agent's records can be mistaken for the world's.
The hold is taken before a body registers as live - spawn refuses rather than leave a live body unheld - and `follow` keeps it sliding with the agents from then on.
The mechanism is Bukkit's plugin chunk ticket, which lands at chunk level 31 - `ChunkLevel.ENTITY_TICKING_LEVEL` - so a held chunk ticks its entities.
That level *is* the invariant: hold chunks any more weakly and false deaths come back silently.
Tickets outlive the tick that added them, so they must be released on disable or the world stays pinned loaded with no Loom running.

**A hold failure must never be silent, and `follow` cannot refuse.**
Spawn can turn a bad hold down; `follow` cannot, because the bodies are already live and already there, so a hold failure during `follow` *is* the invariant broken rather than a request to decline.
Every attempt therefore returns a `ChunkHoldOutcome` (engine-free domain) rather than a count or a boolean, and an incomplete one is recorded as its own `worldHoldFailed` event *and* raised on the plugin logger ("world hold failed: ..."), naming the agents standing in an unheld chunk.
Before trusting any run's permadeath numbers, check the event log for `worldHoldFailed` - its presence means some removals classified as deaths may have been unloads.
The two ways a hold can fail are kept distinct (`unknownWorld` versus `ticketDeclined`): one is a Loom-side mistake, the other the engine declining a correct request, and an earlier version collapsed both into a bare `false` that made every failure invisible.
Note that Bukkit also returns false for a ticket already held, which is why only chunks outside the held set are ever asked for.

## The one adapter, and the NMS seam

`dev.loom.body.nms` is the only package in the plugin allowed to touch `net.minecraft` or CraftBukkit, and `LoomPlugin` is the only place that names a concrete adapter.
`BodyBoundaryTest` fails the build if either stops being true, so the seam is enforced rather than documented.
A Minecraft version bump should touch this package and nothing else.

Read the header of `dev/loom/body/nms/LoomBodyEntity.kt` before changing the body's mechanism.
It records why a literally-new registered entity type is not viable here, why the body extends a vanilla class instead, and why the base type is Villager.
Two sharp edges are easy to reintroduce:

- Spawning must use `SpawnReason.CUSTOM`; any other reason routes through natural-spawn gating that silently discards the body.
- `Mob.serverAiStep` is `final`, so suppressing vanilla behavior means overriding **both** `Villager.customServerAiStep()` (deprecated) and `customServerAiStep(boolean)` without calling `super`. Missing one gives the body vanilla behavior back.
- Bodies must keep renewing their own activation (`LoomBodyEntity.readyForTheEngineTick`). Spigot's activation range only activates entities near a *player*, and Loom has no players, so an untouched body drops to `inactiveTick` after ten seconds. That does not just idle it: it silently disables portal travel, because the transit is driven from `Entity.postTick`, which an inactive entity never reaches - a body would stand in a lit portal forever with nothing in any log to say why.

Engine helpers specified in extents rather than radii have already caused one bug (a perception radius that covered half its documented area).
Prefer expressing such bounds in the domain, as `PerceptionBound` does, where a test can pin them.

**Adding a future flavor** (fake player, external client, headless sim) means: a new package under `dev.loom.body`, a new `BodyPort` implementation in it, and one changed construction line in `LoomPlugin`.
Nothing above the port should need editing; if it does, the port is leaking. The ArchUnit rules already require any new implementation to live in its own adapter package.

The plugin ships with the stable `plugin.yml` descriptor, not `paper-plugin.yml`: Paper's own descriptor drops declarative `commands:` in favor of Brigadier registration, which is still experimental API on 1.21.1 and cannot be opted into under warnings-as-errors.

## The mind, and the bridge to it

`cognition/crates/cognition` is the mind: it accepts a `Perception` and returns a routed, provenance-stamped `Decision`.
Five pieces, each its own module - `router` (tier to model), `model` (the backend seam), `openai` (the one real backend), `mind` (compose and stamp), `server`/`wire` (newline-delimited JSON over TCP).
`ScriptedBackend` is still the default, so a fresh checkout runs with no credentials and both suites stay hermetic, but it is the fallback rather than the state of the art: `LOOM_COGNITION_BACKEND=openai` selects real inference, and `cognition/README.md` is the operator documentation for every setting it reads.
It does answer a goal review whose `energy` is at or below `RESTING_ENERGY` (0.35) with `rest`, which is what lets an unattended agent survive on the shipped stand-in.
That is deliberately a goal-review rule and not a reflex: a damaged agent still flees, because staying alive now outranks resting.
A misspelled backend name stops the service instead of falling back, because scripted decisions look real in the log until someone reads their provenance.
Every number a `reasoning` states is rounded where it is interpolated, because `reasoning` is the one field `ObserverView.showsDetail` carries verbatim to a boss bar: a reserve derived from a tick count printed as `down to 0.3198888888888889` in world, and a health that is an `f32` widens to `19.899999618530273`.
The exact values stay in the perception and in the record's own fields, where anything counting them looks.

**Provenance is stamped by `Mind::decide`, never by a backend.** `ModelRoute` has private fields whose only constructor is `route(importance)`, so a decision cannot claim a model it was not routed to; `mind.rs`'s `LyingBackend` test pins that.
Routing is total over `DecisionImportance` with no default branch - a new tier will not compile until its model is chosen, because a tier silently defaulting is how routine traffic reaches a frontier model.
The refusal frame `{"error":...}` is deliberately **not** a contract type: a mind that could not answer has not decided anything, and inventing an `Intent` for it would put a fiction in the decision record.

**No failure out of `openai` may carry the prompt or the credential, and that is the module's main design constraint rather than a habit.**
Four defences hold it, and each is easy to undo by being helpful: the credential exists only as an already-sensitive default header; the provider's error `message` is *not deserialized at all* (only its short `type`/`code` are repeated, bounded), so no code path can start logging it later; a malformed answer is reported as a `serde_json` category plus line and column, never as text, because a model answer can quote back the perception it was given; and `reqwest`'s own `Display` is used only for the residual transport case, via `root_cause`, where request data has never been.
`tests/openai_provider.rs` sweeps every provider failure path against a credential marker and a prompt marker at once, so a leak added later fails there.

**The answer's shape is enforced by a strict `json_schema`, and that schema is the one thing here that can silently fall behind the contract.**
It mirrors the contract's `Intent` union by hand, so a new variant would compile everywhere and simply never be chosen - the provider would refuse to emit an action the schema does not list.
`tests/openai_request_schema.rs` therefore pins it against `contract/fixtures/valid/decision-*.json` in both directions, and adding a contract case (which already means adding a fixture) fails it until the request schema follows.
Strict mode also forbids `minItems`/`maxItems`/`oneOf`, so `moveTo`'s three coordinates are enforced by the contract type instead and arrive as a bounded malformed-answer error.

Three more sharp edges in `openai`, none of them guessable from the code:

- **The timeout chain spans both runtimes**: `LOOM_OPENAI_TIMEOUT_MS` (12s) < the body's `readTimeout` (14.5s) < the body's bridge `timeout` (30s). The mind's bounded "the provider did not answer" only reaches an operator if the body is still waiting for it; `CognitionSettingsTest` pins the ordering from the JVM side, restating 12s because the two runtimes share a contract and not a configuration. Moving one means moving all three.
- **`max_completion_tokens`, and no `temperature`.** The current models refuse `max_tokens` outright and accept only the default temperature, and a request refused at the API is a decision an agent does not get. The token bound is deliberately loose because reasoning models spend hidden tokens from the same budget and running out arrives as a *truncated answer*, not an error.
- **Nothing in the mind retries** - see the at-most-once note below. `http` base URLs are allowed only on loopback, which is what makes the crate's own mock provider possible without ever letting a credential cross a network in the clear.

On the JVM side `dev.loom.mind` is the port and is engine-free by rule (`MindBoundaryTest`), with `dev.loom.mind.tcp` the one transport adapter.
Three properties are the whole point of `AsyncCognitionBridge` and each has a test that fails if it is lost: `submit` never runs cognition, every request has a deadline, and back pressure is an explicit `Saturated` failure rather than an unbounded queue.
Three subtleties are load-bearing:

- **The watchdog frees the caller, not the worker.** A thread parked in a socket read stays parked, so `CognitionSettings.readTimeout` is derived from the bridge deadline and is deliberately shorter. Configure them independently and a stalled mind quietly consumes every worker.
  The derivation budgets a *whole* `ask` - both connects and both reads of its one retry - not a single read, and `ATTEMPTS_PER_ASK` in `dev.loom.mind` is that one fact: the settings divide the bridge deadline by it and the transport multiplies its read timeout back up by it, so raising the transport's retry count means raising that constant and nothing else.
  That budget is spent as a **wall-clock deadline inside `TcpCognitionTransport`, not as a socket timeout**, because `soTimeout` bounds one read syscall rather than one line: `BufferedReader.readLine` loops until a newline and every arriving byte restarts the clock, so a mind dripping one byte per interval held a worker 40s inside a 2s read timeout.
  The transport therefore frames response lines itself - chunk-wise, scanning the bytes already in hand for the newline and carrying whatever followed it into the next response, the way the mind's own `read_frame` works - re-narrowing the socket timeout to what is left of the budget before every read that could block, and capping the accumulated line at the same 256 KiB `read_frame` caps requests at.
  What the deadline actually cuts short is the **read** side, across a whole `ask` including both reads of its one retry.
  It does not narrow the connect legs, which are charged to the budget but bounded by `connectTimeout`, and it does not reach the write leg at all: Java exposes no `SO_SNDTIMEO`, so the write is bounded by `PerceptionBound` keeping a perception small enough to fit a send buffer, not by the clock.
  Fitting both connects and both reads inside the bridge deadline is `CognitionSettings`' arithmetic, not the transport's.
  A drip test pins the clock half and a cap test the byte half, and the drip test needs its preemptive timeout: on regression the `ask` never returns at all, so a plain assertion would hang the suite instead of failing it.
- **The deadline is armed before the work is queued**, so queue time counts against it. A per-attempt timer would let a request behind a stalled worker wait indefinitely.
- **Delivery to the mind is at-most-once, and the write is the seam.** `TcpCognitionTransport` retries only a failure at or before the write, where nothing can have been decided; anything from the read onward is reported. Re-sending past the write would risk a second billed inference for one perception, and a lost decision is cheaper than a duplicated one. Widening the retry to cover the read is the easy mistake, so a test pins that a perception reaches the mind exactly once.

Importance is classified above the body port by `importanceOf` (`Importance.kt`), not by the body and not by the mind: the body reports what happened, and what that costs is this side's call.
`EmbodiedCognition` is the only place the two ports meet - perceive on the main thread, decide off it, apply back on it through the `MainThread` port.
`BodyPort.tick` still never invokes cognition; the contract has no per-tick `Trigger`, and `BodyBoundaryTest` forbids `dev.loom.body..` from depending on `dev.loom.mind..` at all, so the tick path cannot reach a mind even by accident.

**Agents drive themselves, on a schedule that is policy rather than plumbing.**
`AutonomousDriver` sweeps on its own Bukkit timer - never off the per-tick body timer - and asks `GoalReviewSchedule` whose turn it is; both are engine-free, so the cadence and the stagger are pinned by unit tests with a fake clock.
The one knob is `DEFAULT_GOAL_REVIEW_CADENCE`, and the sweep period is *derived* from it (`GoalReviewSchedule.slice`): sweep at any other rate and the per-slice share silently stops meaning one review per agent per cadence.
The cadence is a floor, so observed spacing is the cadence plus up to one slice - in world, 2500 ticks for a 2400-tick cadence, which is the design rather than drift.
A never-reviewed agent is due at once, which is what keeps a single-agent demo watchable at a conservative cadence.
The driver is cancelled explicitly in `onDisable`, before the bridge it feeds closes.

**A decision enters the research record from the applied path, and only from there.**
`EmbodiedCognition.actOn` is the one place that holds the decision, the mind's stamp on it, and what the body did about it at the same time, so `DecisionJournal` writes `decided` there - before `apply`, so a run reads perception -> decision -> action - and `decisionDiscarded` when the body has gone in between.
Provenance is *carried* from the `Decision`, never re-derived on this side, and the record is filed under Loom's own `AgentId` rather than the mind's echo of it.
`requestId` is on all three records because several agents can think in one tick, so the join is by id and not by tick; `BodyPort.apply` takes one for that reason alone and defaults it to `null` for an intent no perception asked for.
The invariant is one `intentApplied` *or* one `decisionDiscarded` per `decided`, which is why `NmsEntityBodyPort.apply` journals nothing on its `NoSuchBody` early return.
`LoomPlugin.reportApplied` is an operator convenience for watching a round trip on a console, not the record.

A decision reaching a body is proven three ways, in increasing fidelity: `EmbodiedCognitionTest` (fake mind, queue-draining `MainThread` that asserts which thread touched the body), `TcpCognitionTransportTest` (real socket, canonical fixture bytes as responses), and `LiveCognitionServiceTest` (the real Rust service).
The last is opt-in via `LOOM_COGNITION_ADDR=host:port` so the default suite stays hermetic - `cargo run --bin loom-cognition`, then run the plugin tests with that variable set.
CI's `bridge` job does exactly that and then greps the JUnit XML for `skipped="0"`, because a skipped opt-in test would otherwise make the job green while proving nothing.

The provider path is proven the same way, one layer further out: `tests/openai_provider.rs` drives the whole mind against a hand-rolled HTTP/1.1 mock on an ephemeral loopback port.
It is hand-rolled rather than a mocking crate because several of the failures worth pinning - a provider that never answers, one that sends more than Loom will read - are socket behavior, and a mock that is not a socket cannot express them.
**No test may reach the live provider, and no test file may contain a credential**; the mock asserts nothing itself, since a panic on its spawned task surfaces at the client as a dropped connection rather than as the assertion message.

## The observation path

`dev.loom.observe` is the operator display: one watcher, one agent, `/loomwatch`.
`DEMO.md` is its operator documentation, the way `cognition/README.md` is the mind's - the exact local procedure, the field guide to the standing line, and the honest caveats all live there rather than here.
The package is engine-free by rule (`ObserveBoundaryTest`) and its one adapter is `dev.loom.plugin.BukkitObserverSink`.

**It is read-only by construction, not by discipline.**
`Observatory` holds no `BodyPort`, no cognition bridge, and no world handle, and its only input is the `BodyEventRecord` stream that `ObservedEventLog` offers it *after* the real log has taken each record.
So the proof that watching cannot disturb a run is a statement about types, not about timing, and adding any of those three handles to reach "just one more field" is what would break it.
The sink is one-way and cannot be read from, which is what makes "operator-only diagnostics never become agent-facing perception" structural: nothing shown to an operator has anywhere to travel back to.

**Energy and health are two vitals, and the display must never alias one to the other.**
Energy is the `Metabolism` reserve: spent by living, tick by tick, refilled only by `Intent.Rest`, and lethal at zero.
Health is physical damage to the body.
The gauge and the `energy` field both follow the *reserve*, because that is what an unattended agent dies of and what a survival curve is drawn from; health joins the standing line only once a body is hurt, since a body can also be beaten to death on a full reserve.
The colour is the one thing that reads *both*, at the same threshold, for the same reason.
Both ride the record as separate fields (`BodyEventRecord.vitals` for the reserve, `.health` for health), and the reserve is sourced from `Metabolism.energyOf` - the same reading the contract's `SelfState.energy` carries - never read off the entity.
Inside either field a reading is `level` of `capacity` (`AgentVitals.energy` carries a `@SerialName`), because `AgentVitals` is the shape *both* vitals are reported in: a health reading read back out of `health.energy` would be the same aliasing one field further down.
Before the reserve existed the display used health *as* energy; that shortcut is what the wire test in `BodyEventLogTest` now pins against, with the two readings deliberately on different scales so feeding one from the other fails.
`resting` and `idle` are likewise separate activities now that `Intent.Rest` and `Intent.Idle` are separate intents, and telling a recovering agent from a merely waiting one is most of what a survival run is about.

**What a record *means* is read from a field, never from its prose, and two events need a field to say it.**
The view reads `event` plus the countable tags, so an event whose name carries two opposite meanings is an event the display gets wrong.
`spawnRejected` is one: `alreadyEmbodied` is refused *because* the agent still has its body, where every other refusal happens to an agent that has none, and reading them alike labelled a live, visible body `no body` for a whole goal-review cadence.
`converted` is the other: `conversionRefused` left the body untouched, while `bodyTaken` took it and nothing re-embodies the agent.
Both now carry an `EventTag` in `BodyEventRecord.outcome`, `BodyEventJournal.reject` and `recordConversion` require it so a new refusal cannot forget to say which it is, and `WatchState`/`ObserverView` branch on the tag.

**A watch belongs to an observer, not to a sink.**
`WatchCommand.onCommand` builds a fresh `BukkitObserverSink` every time the command is typed, so `Observatory.watch` compares `ObserverSink.observer` - a player's uuid - and not sink identity.
It also *keeps* the standing sink rather than swapping in the fresh one, because only the sink that put a boss bar up can take it down: releasing and repainting would flicker the display, and skipping the release without keeping the old sink would leave an orphaned second bar.
A stale surface (the same account after a reconnect) fails the `isPresent` half of that check and is replaced.
**Every question about a watch is asked as an observer**, for the same reason: `standing` and `stop` both take who is asking and answer as `WatchStanding` (`Mine` / `NotMine` / `Nobody`), so ending a watch and reporting one obey the rule `watch` already does.
`stop` ends the asker's own watch and refuses anybody else's rather than releasing it - a sink is one-way, so the operator who typed the command cannot say anything to the watcher whose bar it would have taken down, and a refusal needs no message because nothing happened.
`NotMine` deliberately names no agent, since which agent somebody else is watching is not part of the run.
`Observatory.shutDown` is the one unconditional way out and exists only for `LoomPlugin.onDisable`, where Loom is going away for everybody and there is nobody to ask on their behalf.

Seven things there are easy to undo by being helpful:

- **The colour follows how much of a vital is left, never which way it is going.** A reserve is spent by living, so "declining" is the standing condition of every living agent rather than news about one: a trend-driven colour turned an in-world run yellow four seconds after the spawn and kept it yellow for the whole of the agent's life, spending the one channel an operator reads at a glance on the fact that time passes. `ObserverView.vitalLevel` is level-only for that reason - half a vital left is `CONCERN`, a quarter is `ALARM` - and is applied to the reserve and to health alike, since either can end the agent. The trend word stays on the *line*, where its absence is the signal: a reserve that is not falling has just been rested, or is frozen because the agent has no body spending it (`WatchState.isReserveFrozen`, which covers a put-away body, a refused spawn, and a body a conversion took, as well as a death).
- **The redaction rule is one total function**, `ObserverView.showsDetail`, and `decided` is its single `true`. Every other event gets the view's own fixed sentence, because a record's `detail` can carry a chunk list, free coordinates, or a provider failure that quotes back the perception or the credential it was given. `ObserverViewTest` sweeps a marker through every event's detail rather than naming today's events, so an event added later is covered the day it is added.
- **`noteFor` is total over `BodyEvent` with no default branch**, for the same reason routing is total over `DecisionImportance`: a new event defaulting into the stream is how a bounded display becomes a firehose.
- **A reading is carried forward, never invented - and the model stamp belongs to one cycle.** `WatchState.advanced` holds the last reading of either vital, the trigger, and the stamp over records that restate none of them, because an agent does not stop having them. A stamp is the exception, and `WatchState.stamp` keeps the `requestId` it answered so it can be: `stampAfter` drops it at a `cognitionUnavailable`, which says outright that this cycle has no model, and at a `perceived` naming a *different* request, which has started one no model has answered yet. Either way a held stamp renders `goal review -> model/prompt tier`, which reads as "this trigger was answered by this model"; the in-flight case is the whole of the wait during a silent mind, and it becomes a wrong *value* rather than a wrong claim the day a second tier is reached, since the router gives each tier its own model and prompt version.
- **There are two bounds, and collapsing them back into one costs the most valuable line on the screen.** `ObserverBound.lineChars` (96) is sized for a boss bar title; `noteChars` (192) is sized for chat. A first in-world run had the mind's reasoning cut in half by the boss bar's width in a chat message that had room for all of it.
- **No line reaches an operator except from `refresh`, and the three methods that do touch the sink are main-thread only.** `saw` runs on whichever thread appended the record and Bukkit is main-thread only, so `saw` accumulates and `refresh` delivers, on the plugin's own timer. That is also where the noise bound and the dropped-watcher check live - a disconnected player is noticed nowhere else. `watch` (which notes a displacement), `stop` and `shutDown` do touch the sink, so they must be called on the main thread; nothing enforces it, and it holds only because each is reached from `WatchCommand.onCommand` or `LoomPlugin.onDisable`. The earlier "only `refresh` touches the sink" was false and is exactly what a future change would trust before calling `watch` from a worker.
- **The queue cap bounds a burst; the duplicate rule bounds a *condition*, and both are needed.** `Watch.offer` drops a note identical to the one before it, and does not count it as skipped, because nothing was lost. Some events recur every tick for as long as something is wrong - a refused world hold is recorded on every `follow` - and queued each time they fill all eight lines with one sentence and evict the `decided` and `died` lines that sentence exists to qualify; counting them as skipped would only trade the duplicates for a running "(N lines skipped)". The comparison is against the last note *taken*, not the queue's tail, so draining at a refresh does not turn a standing condition back into one line per refresh, and only *consecutive* duplicates go, so a condition recurring after real news is stated again.

`WatchState.isFinal` is derived from the activity rather than stored, so the tombstone and the state cannot disagree, and a final state renders no energy trend: a dying agent's last reading is always a falling one, and a live trend would sit on the tombstone claiming something was still happening.

## Verifying a body change

Gates do not cover in-world behavior; a body change is not done until it has been seen working on a real server.
Build the jar (`./gradlew shadowJar`), drop it into a Paper 1.21.1 server's `plugins/`, and drive it with `/loombody spawn|move|perceive|think|despawn|list`.
`DEMO.md` is the written-down version of that run, including the server settings worth setting and what each case is supposed to look like; `/loomwatch <agentId>` is the fastest way to see a whole round trip, since it shows the perception, the model, the reasoning, and the outcome as they are recorded.
`think` is the round trip and needs `loom-cognition` running; the reply only confirms the perception was sent, and the decision appears in the server log a moment later with its model and prompt version.
Verify from a **client's** point of view rather than from the plugin's own log: a body that moves server-side only, or that a vanilla client cannot render, looks identical in the log to one that works.
A client is not optional even for a headless run: `spawn` places the body where the *sender* stands, so it refuses the console, and a scripted client on an offline-mode server (a mineflayer bot will do) both issues the commands and reports what a player would actually see.
Watching autonomy move a body needs a mind that decides a move: `ScriptedBackend` only ever answers a goal review with `idle` or, once the reserve is low, `rest`, so a self-driving agent perceives, decides and applies on cadence while standing perfectly still - point the plugin at a stand-in mind that replies `moveTo` if motion is what is being checked.
Watching the survival drive instead needs patience or a shorter reserve: with the shipped 36 000-tick default a body rests roughly every twenty minutes, and a starvation takes half an hour of an agent whose mind never rests it.
Read it from the record, or watch it with `/loomwatch`, rather than from the world: `perceived` carries the reserve on every review and the end is one `died` with detail "spent its energy reserve", while `/loomwatch` shows the reserve gauge falling and tells `resting`, `idle`, and `standing by` apart - three states that look identical to a bare client.
Dimension travel needs a lit portal and a body walked into it (`/loombody move`), then a `travelled` record and a rendering body on the far side; the driving client must be opped, since `/loombody` is gated on `loom.body` (`default: op`).
Two geometry mistakes each cost a whole run and produce no record and no error: a portal whose interior starts *above* the height a walking body stands at leaves the body at the frame with its feet under the lowest portal block, and a `move` target *at* the portal plane rather than past it stops the body just short of touching it.
Losing that gate is silent - the command keeps working for the op who tests it - so `PluginDescriptorTest` pins the shipped descriptor, and `plugin.yml` records why at the key itself.
Check the *count* of `travelled` records, not just their presence - one round trip is two, and a bouncing body is the failure this most easily hides.
Walk one trip with no client connected too, since a body travelling unobserved is the case that would otherwise read as a death.
Two things about the test server will otherwise waste a run: `difficulty peaceful` makes `Villager.thunderHit` skip the whole conversion path, so lightning proves nothing about the conversion seam; and `execute in <dimension> ... @e` resolves against the *command's* position, so it happily reports an entity from the dimension you were checking for orphans in. Scope such checks with `positioned ... distance=..`, or read the client's entity list.
The append-only `plugins/Loom/body-events.jsonl` is the research record - newline-delimited JSON, one record per line, written off the main thread - and metrics should be derived from it rather than added as separate counters.
The write queue is bounded so a stalled disk never blocks the tick; records that never reach the file are counted and surfaced as a server-log warning, so before trusting a run's metrics check the server log for "body event log" warnings.
`research/README.md` is the authority on a run that is kept as an experiment, and its sharpest edge is that a committed experiment's evidence must never be edited or reformatted.

## Gates (fail-the-build from commit 1)

- Rust: `cd cognition && cargo fmt --all --check && cargo clippy --all-targets --all-features -- -D warnings && cargo test`. Lint policy lives in `[workspace.lints]` in `cognition/Cargo.toml` (clippy pedantic/all denied, no unwrap/expect/panic in non-test code, `unsafe_code` forbidden).
- JVM: `cd plugin && ./gradlew check`. Runs detekt + ktlint + ArchUnit + warnings-as-errors compile, none ignoring failures. Build on **Corretto 21** (`jvmToolchain(21)`), Gradle wrapper pinned to 8.14 - which refuses a newer JDK outright, so `./gradlew` failing with nothing but a version number (`* What went wrong: 25.0.3`) means `JAVA_HOME` points at a default JDK that is too new rather than anything being wrong with the build. detekt config: `plugin/config/detekt/detekt.yml` (formatting delegated to ktlint), and the Kotlin line length *both* gates enforce is stated once in the root `.editorconfig`, which records why the two numbers have to stay equal.
  The plugin compiles against a Mojang-mapped server via `paperweight-userdev`; the first build downloads and decompiles it (a few hundred MB, several minutes), and later builds are fast. Paper's runtime is Mojang-mapped, so the jar ships as compiled and is never reobfuscated (`ReobfArtifactConfiguration.MOJANG_PRODUCTION`).
  detekt thresholds are treated as design feedback: clear them by splitting along a real seam rather than by adding suppressions.
- CI runs both suites plus the live cross-runtime round trip: `.github/workflows/contract.yml`.

- Add durable project-specific notes here as they are discovered through real work.

## Maintaining this file

Keep this file for knowledge useful to almost every future agent session in this project.
Do not repeat what the codebase already shows; point to the authoritative file or command instead.
Prefer rewriting or pruning existing entries over appending new ones.
When updating this file, preserve this bar for all agents and keep entries concise.
