# The bounded tribe experiment harness

`research/bin/loom-tribe.js` creates one disposable, loopback-only Paper world,
runs a configured Loom fleet under explicit cognition and lifecycle budgets,
and publishes only a small sanitized artifact. It controls experimental
conditions, not agent goals. It never assigns roles, partners, hierarchy,
territory, conflict, cooperation, resource policy, or a story outcome.

The original containment configuration is
`research/config/first-tribe-scripted-proof.json`. It uses three independently
supervised Mineflayer bodies and the shipped credential-free scripted cognition
backend. Its deliberate body crash is an acceptance intervention, not behavior
and not evidence of emergence. Its approval-gated OpenAI counterpart is
`research/config/first-tribe-openai.json`; the committed `-r3` id was consumed
by its failed shakedown and must not be run again.

The research-only primary behavior mode is pinned in
`research/config/first-tribe-routine-v12-agency-pilot.json`, with a
provider-free rehearsal in
`research/config/first-tribe-routine-v12-agency-pilot-scripted.json`. This mode
has no controlled crash and sends no `/loombody think` command. Its only
cognition sources are Loom's native `GoalReviewSchedule` and native event
drivers. The OpenAI experiment id is an unconsumed proposal placeholder, not
authorization to run; a paid run still requires a fresh approved id and the
separate decision described below.

The routine-v13 goal-continuity pilot is pinned in
`research/config/first-tribe-routine-v13-agency-pilot.json`, with its
provider-free twin in
`research/config/first-tribe-routine-v13-agency-pilot-scripted.json`.
Its treatment is the routine-v12 pilot's, unchanged, except for three pins
that `research/lib/experiment-mode.js` owns per mode.
It uses the v13 prompts, which show each agent its own carried goal and let the
model return an optional typed goal choice.
It attests `categoryFairSelection: true`, because the 48-per-kind selector it
runs on is category-fair, while the v12 pilot keeps `false` for its evidence.
Preflight and artifact validation refuse either mode with the other's prompts
or attestation.
In the scripted twin the stand-in adopts one fixed placeholder goal at each
agent's first review and continues it at the second.
That exercises the goal round trip; it is not evidence of agency.

## Reproduce from a clean checkout

Prerequisites are Java 21, Node 22 or newer, npm, Rust/Cargo, and `lsof`.
The checkout must be clean so the manifest's source commit names all code that
produced the run. On macOS the harness discovers Java 21 with
`/usr/libexec/java_home`; elsewhere set `LOOM_JAVA_21` to the Java 21
executable.

```sh
npm --prefix research ci
npm --prefix research run tribe -- run \
  --config research/config/first-tribe-routine-v12-agency-pilot-scripted.json
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
  --config research/config/first-tribe-routine-v12-agency-pilot-scripted.json \
  --artifact-root .scratch/routine-v12-agency-pilot-rehearsal \
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
global ceilings. The pilot proposal permits four calls per agent, twelve
globally, and three outstanding, leaving bounded room for native event-triggered
cognition. Preflight also refuses more cognition bridge workers than outstanding
slots, because the bridge would then send a fourth concurrent request that the
proxy can only refuse; with three workers that request waits in the bridge's
bounded queue instead. Every forwarded or refused cognition record carries either
`nativeGoalReviewSchedule` or `nativeEventDriver` as its origin. Publication
also requires the one run-level attestation that the harness issued zero think
commands and that native schedulers were the only cognition source.

## Controlled stops

In primary behavior mode two outcomes end a run without making it an integrity failure.
A typed `perAgentCall`, `globalCall`, `globalOutstanding`, or `spend` refusal is a `cognitionBudgetExhausted` stop.
A provider failure that cognition classified as `providerTimeout`, `providerConnection`, `providerResponseRead`, `providerTransport`, `providerRateLimit`, or `providerServer` is a `providerUnavailable` stop.
An HTTP 401 or 403 still stops the run immediately as a failure, and every other cognition failure is still fatal.
`research/lib/controlled-stop.js` owns these closed sets.

On a stop the proxy journals one `cognitionAdmissionClosed` record before it answers, and refuses every later request under the `admissionClosed` bound.
The harness then waits, inside an explicit bounded drain, until no forwarded call is outstanding and every perception has a terminal body record.
A drain that does not settle is the fatal `controlledStopDrainTimedOut`.
Cleanup and usage import then run as for any completed run, and the publisher writes an artifact with stop reason `controlledStop`.

Its manifest, metrics, and README carry a `controlledStop` section: the stop kind, the exact refusal or provider classification, the calls forwarded and outstanding at the closure, planned versus completed native reviews, complete and refused chain counts, provider usage and spend, and behavioral verdict `no-go`.
Validation derives the owed reviews from the stop window: review k of each agent may be due from k cadences after the start, and must be due one population stagger plus one slice after that.
It refuses a stop with no matching refusal or classification, an untyped refusal, an impossible count, any refusal that is not the first before the closure, a missing earlier chain, provider usage that does not reconcile, a dirty shutdown, and a killed process.
A normal duration or tick-ceiling completion still requires the exact configured review plan, and crash-containment evidence can never claim a controlled stop.

## Witnessed deaths

In primary behavior mode a server-witnessed `died` record is an experiment outcome, not a failure.
That agent's chain ends there and its peers continue under the same deadline.
After its death only that agent's own `bodyDisconnected` (at most once) and `decisionDiscarded` records may follow; anything else for it, a second death, or a disconnect or discard of an agent that did not die, stays fatal.
Witness disputes, revivals, denials, quits, and an unsafe ledger stay fatal as well.
The publisher derives a `witnessedDeaths` section, present only when a death occurred, and owes a dead agent only the scheduled reviews its life window made due, by the same rule as a controlled stop.
Survivors still owe the full configured plan.
`research/lib/witnessed-death.js` owns the rule.

## Behavioral gate

Every primary behavior publication derives a `behaviorGate` section in `metrics.json`, copied into the manifest and summarized in the README.
`research/lib/behavior-gate.js` owns it.
It measures meaningful behavior per agent (a non-idle native goal-review action the body accepted and later evidence confirmed: its own server-confirmed interaction, a movement report, or a food change for an eat), typed goal continuity, accepted and confirmed action counts, per-kind omissions, night perceptions by world-clock day, calls by agent, peak outstanding, spend, and cleanup.
The mechanical pilot verdict is `pass` only when at least two agents show meaningful behavior, at least one carries a typed goal forward, every agent completed the configured reviews, no failure evidence exists, and no JVM cut occurred.
A controlled stop is always `no-go`.
A mechanical `pass` still names the criteria only a reader can judge, so it is necessary for the pre-registered gate and not sufficient.

## Lifecycle and cleanup

The harness starts cognition, the bounded proxy, and Paper in that order. Paper
then starts the canonical fleet through Loom's existing bounded startup gate.
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

The routine-v12 pilot proposal routes routine and notable decisions to
`gpt-5.4-nano` and only the existing pivotal tier to `gpt-5.4`. It runs three
agents for at most 180 wall-clock seconds or 4,000 server ticks, whichever
comes first, and permits at most four calls per agent, twelve calls globally,
and three calls outstanding. Its 65,536-byte request and 2,048-output-token
bounds make the estimate-independent worst case $2.33472 at the pinned rates;
the configured spend ceiling is $2.34.

After explicit approval, replace the proposal placeholder with the approved
fresh experiment id and run the bounded live configuration once. The earlier
`-r2` and `-r3` containment shakedowns were each consumed by their one
authorized attempt and must never be retried. Changing an experiment id is not
itself authorization.
