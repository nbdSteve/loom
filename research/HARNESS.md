# The bounded tribe experiment harness

`research/bin/loom-tribe.js` creates one disposable, loopback-only Paper world,
runs a configured Loom fleet under explicit cognition and lifecycle budgets,
and publishes only a small sanitized artifact. It controls experimental
conditions, not agent goals. It never assigns roles, partners, hierarchy,
territory, conflict, cooperation, resource policy, or a story outcome.

The runnable configurations are the routine-v20 typed-goal pilot,
`research/config/first-tribe-routine-v20-agency-pilot.json`, and its
provider-free twin, `research/config/first-tribe-routine-v20-agency-pilot-scripted.json`.
This primary behavior mode has no controlled crash and sends no `/loombody think`
command. Its only cognition sources are Loom's native `GoalReviewSchedule` and
native event drivers. It uses the v20 prompts, which show each agent its own
carried goal and let the model return an optional typed goal choice, and it
attests `categoryFairSelection: true` because the 48-per-kind selector it runs on
is category-fair. Since v14 a `moveTo` names an observed block by ref rather than
coordinates, because a perception never states coordinates and every coordinate a
v13 mind wrote for a refused walk named open air. v15 states where that walk ends:
on top of a solid block, or in the own cell of a block the server gives no
collision, such as a dead bush, because 24 of the v14 run's 25 walks named a dead
bush and were sent to the open air above it. v16 places a thing in the agent's own
block column - the block under its feet, a plant it stands in - by where it is
relative to the agent (bearing `here`) rather than by a compass point, because 16
of the v15 run's 18 walks went to the block the body already stood on, which the
prompt had called half a block to the north. v17 keeps both and makes the held goal a
working goal: adopt and revise state a success condition, every affirming review names
the one step its action takes, every answer to a carried goal judges the previous step
(`done`, `progressing`, `noProgress`), the JVM measures from the agent's own perceptions
whether that step changed its inventory or moved it, and a `continue` after three
affirmed steps in a row that changed neither is refused as `goalStalled` and ends the
goal. Metrics derive `goalContinuity.workingGoal` from that evidence. v16 never
published, so its configurations were replaced rather than kept historical. v18 keeps v17
and holds every action's refs to the handles that perception offered in the action's own
namespace by a per-request strict schema, never offers a block in the agent's own column
as a walk target, and carries the no-progress count through a `revise`, because r9 had five
wrong-kind refs, five walks that moved nothing, and goals reworded instead of advanced.
v19 keeps v18 and offers each action only what the body can reach with it: blocks in sight
within interaction reach to dig, place or sleep, walks only to blocks the server says a body can
stand at (a perception's block now states `standable`), and collect and attack refs within the
reach of the approach mode that names them, because r10's two refusals were a covered walk
target and a collect without moving 18 blocks off.
v20 keeps v19 and types the working goal: the success condition is an item and count the body
measures, the step is the intent's own action and ref, a step that disagrees with its intent or a
met condition left neither completed nor revised is refused before the body acts, and only
evidence that advances the condition or carries out the step resets the stall count, because the
independent r11 audit found the run's goal progress was movement and three met conditions unseen. `research/lib/experiment-mode.js` owns the pins. In the
scripted twin the stand-in adopts one fixed placeholder goal at each agent's
first review and continues it at the second; that exercises the goal round
trip and is not evidence of agency.

Each primary-behavior configuration names a frozen profile, which
`research/lib/profiles.js` owns: `pilot-180s` (the r5 scene, three agents, 180
seconds or 4,000 ticks, two reviews each, 6/12/3 calls, $2.34),
`two-cycle-3` and `two-cycle-10` (the `s2-ring` scene on seed
`27183455263279`, the first three or all ten surveyed ring slots, 3,600 seconds or
48,000 ticks, up to 21 reviews each, 56/150/3 or 56/400/6 calls, $29.19 or
$77.83, and the measured log, evidence and publication bounds below). Preflight refuses any drift from the named profile. The `s2-ring`
positions are the survey's marker-confirmed slots, under its disclosed cover rule:
a collision-free, non-fluid cover block in the feet cell counts as passable, and
nothing is cleared or placed; the manifest states that rule. Their configurations
are `research/config/tribe-two-cycle-3*.json` and `tribe-two-cycle-10*.json`; the
live ones carry proposal ids and need their own explicit approval before any paid
run. `shakedown-10m-10` is `two-cycle-10` with only its window and envelope
changed: 600 seconds or 12,000 ticks, six reviews each, 24/150/6 calls, $29.19,
and 16 MiB log and publication bounds with 8,000 evidence records, about eight
times the 12,000-tick share of the ten-agent normal sample. The v13
configurations that ran `-r1` to `-r4` are `research/config/tribe-shakedown-10m-10*.json`
and are historical, as are the v14 `research/config/tribe-grounded-walk-10m-10*.json`
that ran `-r5` and the v15 `research/config/tribe-grounded-walk-v15-10m-10*.json` that
ran `-r6` and the v17 `research/config/tribe-working-goal-10m-10*.json` that ran `-r8` and
`-r9`, the v18 `research/config/tribe-offered-refs-10m-10*.json` that ran `-r10`, and the v19
`research/config/tribe-actionable-refs-10m-10*.json` that ran `-r11`; `typed-goal-10m-10` is the
same profile under the v20 mode, configured by `research/config/tribe-typed-goal-10m-10*.json`.
The live one
carries the next fresh `2026-10-16-openai-10-agent-10m-r<n>` id. Each id is consumed
by its one attempt and its published artifact names the commit it ran from.
Outside the pilot profile the mechanical pilot verdict is `notApplicable`.

The other committed configurations are historical:
`first-tribe-scripted-proof.json` and `first-tribe-openai.json` (the
crash-containment proof, routine-v11 prompts) and the two
`first-tribe-routine-v12-agency-pilot*.json` files (the routine-v12
primary-behavior mode), and the `first-tribe-routine-v13-agency-pilot*.json` and
`tribe-shakedown-10m-10*.json` files (the routine-v13 goal-continuity mode), and the
`tribe-grounded-walk-10m-10*.json` and `tribe-grounded-walk-v15-10m-10*.json` files (the
routine-v14 and routine-v15 grounded-walk modes), and the `tribe-working-goal-10m-10*.json`
files (the routine-v17 working-goal mode), and the `tribe-offered-refs-10m-10*.json` files (the
routine-v18 offered-refs mode), and the `tribe-actionable-refs-10m-10*.json` files (the
routine-v19 actionable-refs mode). They pin prompts the current mind no longer serves, so
preflight refuses them before anything is built, started or read; the artifacts
committed under them still validate. `contract/fixtures/capability-support.json`
is the one classification of which configurations, modes, intents, triggers and
feature flags are runnable, supported, dormant or historical. Every feature flag
must be `true`, because no runtime route reads one and a `false` would be a false
manifest claim.

## Reproduce from a clean checkout

Prerequisites are Java 21, Node 22 or newer, npm, Rust/Cargo, and `lsof`.
The checkout must be clean so the manifest's source commit names all code that
produced the run. On macOS the harness discovers Java 21 with
`/usr/libexec/java_home`; elsewhere set `LOOM_JAVA_21` to the Java 21
executable.

```sh
npm --prefix research ci
npm --prefix research run tribe -- run \
  --config research/config/first-tribe-routine-v20-agency-pilot-scripted.json
```

The default path verifies and caches the pinned Paper jar, installs and checks
the body package, builds the release cognition binary, runs the plugin checks
and shaded build, creates a temporary server, runs the proof, validates the
publication, and removes the temporary server. A previously verified local
Paper jar may be supplied with `--paper-jar`; its SHA-256 must still match the
configuration. `--skip-build` is for a repeated local rehearsal only and fails
if the expected build outputs do not exist.

The command refuses an existing artifact directory rather than overwriting
evidence. It builds and validates the artifact in a hidden `.publishing-*`
sibling and renames it into place only once it is valid, so a partial
directory never appears under the experiment id. Rehearse into a disposable parent directory, delete that parent after
inspection, then run once without `--artifact-root` to publish:

```sh
npm --prefix research run tribe -- run \
  --config research/config/first-tribe-routine-v20-agency-pilot-scripted.json \
  --artifact-root .scratch/routine-v20-agency-pilot-rehearsal \
  --skip-build
```

Validate any generation-3 artifact independently:

```sh
npm --prefix research run tribe -- validate \
  research/experiments/2026-10-12-scripted-tribe-crash-proof
```

## What is fixed

The configuration pins the experiment id, Paper and Minecraft versions, Paper
hash, world seed, border and loaded distances, canonical agent set, consecutive
control ports, difficulty and mob-spawning rule, model/provider ids, prompt
versions, provider endpoint and timeout, integer nano-USD token prices, feature
flags, and every resource ceiling. Primary behavior mode additionally pins the
three r5 positions, an exact initial world clock, empty inventories, the native
2,400-tick cadence and 100-tick slices, and the absence of injected roles,
resource targets, social goals, hierarchy, pairings, conflict, and scripted
objectives. The plugin derives each `loom_<hash>` username and offline UUID
from the sorted agent id set. The publisher independently derives the same
mapping and rejects a manifest that disagrees.

All listeners use the literal `127.0.0.1`, Paper runs in offline mode, and the
artifact records zero real player accounts. Never relax those properties for
this harness: offline Minecraft identity is safe here only because no listener
is network-reachable.

## Bounds and call accounting

The run configuration limits:

- total startup time, startup concurrency, and body startup attempts;
- experiment wall time and simulation tick span;
- forwarded model calls per agent and globally;
- globally outstanding cognition, with excess work refused rather than queued;
- provider output tokens and maximum external spend;
- the complete serialized provider request before a paid socket is opened;
- world size, border, view distance, and simulation distance;
- each captured process log, the append-only harness journal, evidence records,
  and the complete publication size;
- graceful shutdown and forced termination time.

The proxy counts a call only when it forwards one perception upstream. A
budget refusal is a separate normalized record. One accepted request is never
retried by the proxy or provider; the existing body transport retains only its
single pre-write retry, which cannot duplicate a billed request. Those three
limits are explicit in the manifest. Preflight prices the worst possible
configured call against the request/output bounds and refuses a spend ceiling
too small to cover every allowed call, so the ceiling does not depend on an
average token estimate. At run time the proxy also reserves that worst-case
price for every forwarded call and refuses, as the `spend` bound, a call whose
reservation would cross the ceiling. The plugin still treats every decision as untrusted
typed intent and validates request-scoped references against fresh body and
server state.

The crash-containment requirement owns part of both cognition ceilings. Before
the configured crash, the proxy keeps one call unavailable under each peer's
per-agent ceiling and keeps their combined reserve unavailable under the global
ceiling. Once the body disconnect is recorded, the reserve is released, but
each peer retains its own share until it has forwarded a post-crash call. This
prevents ordinary scheduled reviews or event-driven traffic before the
intervention—and one busy peer afterward—from consuming the calls the
containment check requires.

Cognition remains event-driven. The proof uses `/loombody think` to produce
bounded goal-review events; it does not create a per-tick call path. The normal
driver's threat and scheduled-review triggers remain unchanged.

Primary behavior mode does not use that proof intervention or reserve. Its
planned native schedule is six calls: two reviews for each of three agents. Its
preflight rejects a configuration unless those calls fit both the per-agent and
global ceilings. The historical routine-v12 pilot permitted four calls per agent,
twelve globally, and three outstanding. The routine-v13 pilot permits six per agent under the same twelve
global and three outstanding, because a live run saw daytime threat sightings
draw three flight calls from one agent before its second review; the global
ceiling, and so the worst-case spend, is unchanged. Preflight also refuses more cognition bridge workers than outstanding
slots, because the bridge would then send a fourth concurrent request that the
proxy can only refuse; with three workers that request waits in the bridge's
bounded queue instead. Every forwarded or refused cognition record carries either
`nativeGoalReviewSchedule` or `nativeEventDriver` as its origin. Publication
also requires the one run-level attestation that the harness issued zero think
commands and that native schedulers were the only cognition source.

## Measured two-cycle budgets

The two-cycle profiles' ceilings come from four provider-free rehearsals on the
`s2-ring` scene at 40 ticks per second, not from estimates. The structure runs
are peaceful, so every agent lives the whole 48,000 ticks; the normal-difficulty
samples are where calls, deaths and danger episodes are measured.

| Run | Ended | Calls | Records | Evidence | Body log | Paper RSS | Startup |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 3 agents, peaceful | tick 48,061 | 60 | 266 | 0.61 MB | 0.53 MB | 1.43 GB | 14.2 s |
| 3 agents, normal | all died, tick 17,313 | 41 | 368 | 0.74 MB | 0.65 MB | 1.28 GB | 22.2 s |
| 10 agents, peaceful | tick 48,015 | 201 | 858 | 2.29 MB | 2.03 MB | 1.31 GB | 18.2 s |
| 10 agents, normal | all died, tick 16,734 | 125 | 1,343 | 2.85 MB | 2.54 MB | 1.41 GB | 16.8 s |

Graceful shutdown took 2.2 to 3.1 seconds. The normal runs coalesced 15 and 53
repeated danger perceptions; the ten-agent run peaked at four outstanding calls
against its six.

The per-agent ceiling is the part a run is most likely to meet. The busiest
agent forwarded 11 danger calls and 7 reviews in its one night, so an agent that
lives through two nights and all 21 reviews needs about 43; 56 is that with a
quarter of headroom. The global ceilings are about one and a half times (three
agents) and one and a quarter times (ten agents) the extrapolated mean of 34
and 32 calls per agent. The log, evidence-record and publication bounds are at
least four times the need extrapolated to 48,000 ticks with every agent alive.
Paper's 1 GiB heap held three agents; ten use 2 GiB. A real-clock sample ran at
19.9 ticks per second, so 48,000 ticks fit the 3,600-second wall bound. Any
refusal under these ceilings is a typed controlled stop, never a silent loss.

## Controlled stops

In primary behavior mode two outcomes end a run without making it an integrity failure.
A typed `perAgentCall`, `globalCall`, `globalOutstanding`, or `spend` refusal is a `cognitionBudgetExhausted` stop.
A provider failure that cognition classified as `providerTimeout`, `providerConnection`, `providerResponseRead`, `providerTransport`, `providerRateLimit`, or `providerServer` is a `providerUnavailable` stop.
An HTTP 401 or 403 still stops the run immediately as a failure, and every other cognition failure is still fatal.
`research/lib/controlled-stop.js` owns these closed sets.

On a stop the proxy journals one `cognitionAdmissionClosed` record before it answers, and refuses every later request under the `admissionClosed` bound.
The harness then waits, inside an explicit bounded drain, until no forwarded call is outstanding and every perception has a terminal body record.
A drain that does not settle is the fatal `cognitionDrainTimedOut`.
Cleanup and usage import then run as for any completed run, and the publisher writes an artifact with stop reason `controlledStop`.

Its manifest, metrics, and README carry a `controlledStop` section: the stop kind, the exact refusal or provider classification, the calls forwarded and outstanding at the closure, planned versus completed native reviews, complete and refused chain counts, provider usage and spend, and behavioral verdict `no-go`.
Validation derives the owed reviews from the stop window: review k of each agent may be due from k cadences after the start, and must be due one population stagger plus one slice after that.
It refuses a stop with no matching refusal or classification, an untyped refusal, an impossible count, any refusal that is not the first before the closure, a missing earlier chain, provider usage that does not reconcile, a dirty shutdown, and a killed process.
Crash-containment evidence can never claim a controlled stop.

A normal duration or tick-ceiling ending in primary behavior mode closes admission the same way, with one `cognitionAdmissionEnded` record, and drains inside the same bound before shutdown, so a decision in flight at the deadline completes instead of becoming an incomplete chain.
Requests refused after that record are typed `admissionClosed` refusals and are not owed an answer.
Validation then owes each agent the scheduled reviews the window up to the ending made due, so a slow host that ends before a second review is due still publishes; the behavioral gate still requires the full configured plan.

## Danger episodes

A threat or damage perception the JVM coalesced into the agent's active danger episode ends its chain as `cognitionUnavailable` with outcome `dangerCoalesced`; the mind was deliberately not asked, so it is neither a failure nor a call. Metrics derive a `dangerEpisodes` section counting them by agent and trigger, present only when one occurred. `research/lib/danger.js` owns the rule.

## Witnessed deaths

In primary behavior mode a server-witnessed `died` record is an experiment outcome, not a failure.
That agent's chain ends there and its peers continue under the same deadline.
After its death only that agent's own `bodyDisconnected` (at most once), `decisionDiscarded`, and a `damageWitnessed` that asked no cognition (`damageDiedBeforeCognition` or `damageAgentDead`, such as the killing blow), and the typed settlement of a request decided before the death (its one `intentApplied` or `decisionDiscarded`, and at most one `moved` and one `retreating` report for it) may follow; a settled request is listed in `witnessedDeaths` as `settledAfterDeath` and never counts as behavior, and a `worldInteraction` or anything new after the death is fatal; anything else for it, a second death, or a disconnect or discard of an agent that did not die, stays fatal.
Witness disputes, revivals, denials, quits, and an unsafe ledger stay fatal as well.
The publisher derives a `witnessedDeaths` section, present only when a death occurred, and owes a dead agent only the scheduled reviews its life window made due, by the same rule as a controlled stop.
Survivors still owe the full configured plan. When every agent has died there is nothing left to observe, so the run stops with `allAgentsDied` and publishes; validation requires every agent's death on record.
`research/lib/witnessed-death.js` owns the rule.

## Behavioral gate

Every primary behavior publication derives a `behaviorGate` section in `metrics.json`, copied into the manifest and summarized in the README.
`research/lib/behavior-gate.js` owns it.
It measures meaningful behavior per agent (a non-idle native goal-review action the body accepted and later evidence confirmed: its own server-confirmed interaction, a movement report, or a food change for an eat), typed goal continuity, accepted and confirmed action counts, per-kind omissions, night perceptions by world-clock day, calls by agent, peak outstanding, spend, and cleanup.
The mechanical pilot verdict is `pass` only when at least two agents show meaningful behavior, at least one carries a typed goal forward, every agent completed the configured reviews, no failure evidence exists, and no JVM cut occurred.
A controlled stop is always `no-go`.
Generation 2 of the gate, named in the stimulus attestation, also breaks server-witnessed native interactions down by operation into confirmed and disputed counts; evidence published before it names no generation and derives generation 1, so committed metrics stay re-derivable.
A mechanical `pass` still names the criteria only a reader can judge, so it is necessary for the pre-registered gate and not sufficient.

Generation 3 adds `behaviorGate.readiness`, owned by `research/lib/readiness.js`, and a rate is not its target.
A run is `ready` only when decision validity, typed terminal accounting and server truthfulness are each exactly 1 and every blocker count is zero.
Every non-passive decision ends in one class, joined by request key to the record that ended it: `confirmed`; a legitimate race (`targetRace` for a target that went or changed, `superseded` for one the agent's own later decision cancelled, `concurrent` for `controlNotReady`, `death`); or a blocker (`wrongKindRef`, `noOpTarget`, `inapplicable`, `unknownTimeout`, `incompleteMovement`, `falseSuccess`, `protocolError`, `staleRef`, `unconfirmed`, `untypedTerminal`, `unexplainedRefusal`).
`actions.nonSuccess` lists every action that did not succeed, races included, with the exact record that ended it.
A walk or flight's `moved` and `retreating` records carry its request id and a typed ending (`movementArrived`, `movementStalled`, `movementTimedOut` and so on) for this join, and an approaching collect or attack states its journey against its target in the interaction record.
A test reads the JVM's refusal tags and fails if any would classify as unexplained.
`readiness.goalCoherence` counts the affirmed steps the body measured as having changed the agent's inventory or place, the goals advanced by two or more such steps, and the goals completed after at least one.

Generation 4 (`loom-readiness-gate/2`) makes coherence part of the verdict, because r11 was mechanically `ready` and the independent audit found no evidence-driven multi-step pursuit.
`goalCoherence` is then read from the typed goal: steps that advanced the success condition, steps that only carried out the typed step, steps that advanced nothing, met conditions and how each was answered, step mismatches, unmet completions and stalls.
It is `coherent` only with no step mismatch, no unmet completion, no met condition left unanswered, and at least one goal whose condition advanced at two or more steps; `verdict` is `ready` only when the mechanical half (`mechanicalVerdict`) and coherence both pass.
A decision the goal ledger refused is the `goalIncoherent` blocker and an invalid decision, even when its intent was passive.
`test/readiness-coherence.test.js` holds r11's own evidence to that gate, where it is `notReady`.

## Lifecycle and cleanup

Before it creates the disposable server or reads a credential, the harness refuses to start unless the run volume has at least 2 GiB free, or more when the configured log and artifact bounds need it.
It then waits at most 180 seconds for the one-minute load average to fall to 0.5 per CPU and refuses to start a run on a busier host, because Paper's tick rate and the bodies' path-search budgets are wall-clock bounded.
Runs are allowed on battery. If the host is suspended mid-run (a closed laptop lid enters clamshell sleep, which no assertion prevents), the primary-behavior poll loop sees a wall-clock gap of more than ten seconds between its 250 ms polls and fails the run as `hostSuspended`, with the usual cleanup and retained diagnostic, rather than as a body failure: a rehearsal that slept for 422 seconds was cut when every Mineflayer client timed out on wake.
Each run directory is seeded from a verified Paper runtime cache under `.scratch/paper/runtime-*` (the Mojang server jar, libraries, patched server and remap cache, each file checked against the cache's own manifest), built once by a bare loopback Paper start, so startup no longer downloads and remaps on every run.
Paper, its world, the JVM, the bounded logs, and publication all write there, and a volume that fills mid-run loses the run.

The harness starts cognition, the bounded proxy, and Paper in that order. Paper
then starts the canonical fleet through Loom's existing bounded startup gate.
In primary behavior mode Paper starts with `LOOM_NATIVE_COGNITION=held`, so no
scheduled review, threat scan or damage witness runs until the harness has
applied the initial conditions and sent `loombody begin` after them. Without the
hold, the first agent's first review could arrive while later agents were still
starting, before the conditions existed; the `-r2` shakedown failed that way.
One agent has at most one scheduled review and one event with the mind at a time: a
further event is ended as `cognitionUnavailable` with outcome `cognitionCoalesced`
(never sent, not a failure, counted under `cognitionCoalescing`), and a review that falls
due while the agent's previous review is still unanswered is deferred to the first slice
it fits rather than claimed into a full per-agent budget. The `-r7` shakedown failed as
`agentBudgetExhausted` because neither held. The retained failure diagnostic keeps the
most recent cognition traffic (`cognition.timeline`: per forwarded call its trigger,
origin, outcome, outstanding count and latency; per body record its tick and outcome).
A shared startup deadline covers the service, server, and all bodies. Any
partial startup enters the same cleanup path as cancellation, timeout, disk
failure, cognition failure, or Paper failure.

Paper and cognition are separate process groups. Cleanup sends Paper's ordinary
`stop` and terminates cognition immediately, waits for cooperative exit, then
terminates and finally kills only a process group that still survives inside
the one shared shutdown ceiling. Every group signal is recorded without a
process id. A successful primary behavior artifact is invalid if any managed
group received `SIGKILL`. Cleanup still closes the in-process proxy and probes
the Minecraft, cognition, and every body-control port if an earlier cleanup
step failed. Publication is possible only after every managed group and
listener is gone. `SIGINT` and `SIGTERM` enter this same path.

The proof resolves the one configured crash target from its body-control
listener, verifies that the process command is Loom's body executable, and
sends `SIGKILL`. No process id reaches evidence. Peer bodies remain under their
own supervisors, connections, ref stores, cognition budgets, and journals.

## Evidence and publication

The mutable server directory, worlds, raw process logs, raw provider failures,
credentials, binaries, Paper jar, and synchronous death ledger are destroyed or
kept only in ignored local caches. They are never research artifacts.

If a run fails after its disposable directory has been created, cleanup still removes that directory and all raw records.
Before removal, the harness writes one mode-0600 operator diagnostic under `.scratch/tribe-failures/<experiment-id>.json`.
That file is capped at 64 KiB and contains only allow-listed event counts, per-agent cycle counts, call-budget state, aggregate provider token/cost totals, crash-reserve state (crash-containment mode only), input completeness flags, a stable failure code, aggregate cognition-failure classifications, and up to 32 typed budget refusals.
Each classified tuple contains only the routed model, numeric HTTP status, allow-listed provider type/code, structural response shape, Loom mapping, and count.
A missing or route-inconsistent classification stays explicitly unclassified; the harness never guesses it from a zero-byte usage journal.
Each disputed world interaction, up to 32, keeps only its agent, tick, operation, the body's own outcome token, and whether vanilla recorded a matching effect at all; the r3 shakedown failed on one dispute that the earlier aggregate counts could not attribute.
Each typed refusal names the agent, the bound (`perAgentCall`, `globalCall`, `globalOutstanding`, `postCrashReserve`, or `spend`), its unit, the configured ceiling, the observed count, that the call was not forwarded, and the trigger, importance, and native origin.
The diagnostic never copies prompts, reasoning, credentials, provider/request/thread ids, process ids, local paths, raw provider messages, or raw evidence, and it is explicitly not a publishable research artifact.

If any stage of that diagnostic fails - reading inputs, summarizing, serializing, the secret scan, the byte bound, or the write - the harness writes a minimal fallback to `.scratch/tribe-failures/<experiment-id>.fallback.json` instead.
The fallback is capped at 8 KiB, names the stage that failed, and keeps only closed facts: the failure code, the cleanup probe result, proxy call and refusal counts, and provider calls, tokens, and spend aggregated by an independent reader of the usage journal.
A string field is kept only if it matches its closed shape and passes the secret scan; otherwise it is null, and an unusable experiment id never becomes a file name.
Both files are written to a temporary name, synced, and linked into place, so a partial file never appears under a diagnostic name and an existing diagnostic is never overwritten.

The committed directory contains:

```text
README.md
manifest.json
evidence.jsonl
metrics.json
```

`evidence.jsonl` preserves source order within the body log and within the
harness journal. Every body record carries a fleet-safe `requestKey` made from
agent id and local request id. New body records include the complete bounded
perception and typed decision, including bounded reasoning and routed
provenance. Action, movement, vitals, inventory, interaction, witness,
disconnect, death, and lifecycle evidence retain their existing typed or
countable fields.

Each primary behavior perception also carries a research-only
`perceptionAudit`. For entities, blocks, and dropped items separately it records
the raw body-list count, the body's reported omission count, the JVM-selected
count, and the JVM cut count. The same audit records the body's exact raw world
clock. These fields never enter the perception/decision contract or model
prompt. Each body list is capped at 48 and the model-visible scene is capped at
144, so a current-generation 48/48/48 answer passes through without a JVM cut.
Normalization and publication reject a missing category, negative count,
impossible omission, a raw or selected per-kind count above 48, JVM cut other
than raw minus selected, any current-generation JVM cut, or a selected total
above 144. The production selector still applies deterministic category
reservation and global ordering to malformed or future inputs outside that
envelope.

Normalization recursively removes credential-shaped text, local absolute
paths, provider request/thread ids, and process ids; it also truncates public
strings. A cognition failure is published only as its stable typed outcome, not
its raw provider or transport text. The validator scans every committed file
again and refuses any remaining match.

For a paid run, cognition writes a private append-only usage sidecar in the
disposable directory. Each successful provider response contributes only agent
id, request id, routed model, prompt/completion counts, and cached prompt
tokens. The harness validates those counts, prices them with the manifest's
pinned integer nano-USD rates, and imports the result as normalized
`providerUsage` evidence. The manifest reports both the worst-case spend ceiling
and actual provider-reported spend; no provider request id or thread id is
retained.

`metrics.json` is generated from normalized records and is never hand-edited.
Validation re-runs the derivation byte-for-value and checks its hash, counts,
timing, call ceilings, deterministic identities, artifact bounds, and README
sections. The README generator separates setup facts, observations,
interpretations, counterevidence/null results, and limitations. Absence of
speech, co-location, attack, or server-confirmed interaction remains explicit
counterevidence rather than being turned into a positive result. Co-location
without recorded movement is identified as compatible with shared spawn
placement. Every `witnessDisputed` record is counted into counterevidence and a
failed `serverEvidenceUncontested` integrity check instead of being buried among
lifecycle counts.

Absolute permadeath is unchanged. The witness is installed before bodies start,
the synchronous ledger remains the login authority, and the ledger is excluded
from publication because it is mutable authorisation state rather than research
evidence.

## Live-model approval boundary

Do not change `cognition.backend` to `openai` and run it merely because a key is
available. Before the first paid run, publish a `needs-decision` report naming
the exact models, agent count, simulated and wall duration, maximum calls,
expected maximum cost, credential source, artifact plan, and stop/rollback
conditions. The credential must reach cognition only as `OPENAI_API_KEY` and
must not be copied into a configuration, command line, log, or artifact.

On macOS, `--openai-keychain-service <service>` makes the harness invoke
`security find-generic-password -s <service> -w` exactly once, after builds,
preflight checks, and listener probes have completed and immediately before
cognition starts. Standard output is held only in memory, trimmed, and placed
only in the cognition child's environment. Lookup diagnostics are discarded.
An empty value or lookup failure stops before cognition starts. An HTTP 401 or
403 from the provider stops the whole run after that refusal, with no retry and
only a stable provider-safe error.

The routine-v20 pilot routes routine and notable decisions to
`gpt-5.4-nano` and only the existing pivotal tier to `gpt-5.4`. It runs three
agents for at most 180 wall-clock seconds or 4,000 server ticks, whichever
comes first, and permits at most six calls per agent, twelve calls globally,
and three calls outstanding. Its 65,536-byte request and 2,048-output-token
bounds make the estimate-independent worst case $2.33472 at the pinned rates;
the configured spend ceiling is $2.34.

The paid two-cycle profiles use the same routing and bounds. Three agents at
56/150/3 calls make the worst case $29.184 against a $29.19 ceiling; ten agents
at 56/400/6 make it $77.824 against $77.83. The ten-minute shakedown at 24/150/6 makes it $29.184 against $29.19.
Each needs its own exact approval.

After explicit approval, replace the proposal placeholder with the approved
fresh experiment id and run the bounded live configuration once. The earlier
`-r2` and `-r3` containment shakedowns were each consumed by their one
authorized attempt and must never be retried. Changing an experiment id is not
itself authorization.
