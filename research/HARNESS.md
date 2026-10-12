# The bounded tribe experiment harness

`research/bin/loom-tribe.js` creates one disposable, loopback-only Paper world,
runs a configured Loom fleet under explicit cognition and lifecycle budgets,
and publishes only a small sanitized artifact. It controls experimental
conditions, not agent goals. It never assigns roles, partners, hierarchy,
territory, conflict, cooperation, resource policy, or a story outcome.

The first committed configuration is
`research/config/first-tribe-scripted-proof.json`. It uses three independently
supervised Mineflayer bodies and the shipped credential-free scripted cognition
backend. Its deliberate body crash is an acceptance intervention, not behavior
and not evidence of emergence.

## Reproduce from a clean checkout

Prerequisites are Java 21, Node 22 or newer, npm, Rust/Cargo, and `lsof`.
The checkout must be clean so the manifest's source commit names all code that
produced the run. On macOS the harness discovers Java 21 with
`/usr/libexec/java_home`; elsewhere set `LOOM_JAVA_21` to the Java 21
executable.

```sh
npm --prefix research ci
npm --prefix research run tribe -- run \
  --config research/config/first-tribe-scripted-proof.json
```

The default path verifies and caches the pinned Paper jar, installs and checks
the body package, builds the release cognition binary, runs the plugin checks
and shaded build, creates a temporary server, runs the proof, validates the
publication, and removes the temporary server. A previously verified local
Paper jar may be supplied with `--paper-jar`; its SHA-256 must still match the
configuration. `--skip-build` is for a repeated local rehearsal only and fails
if the expected build outputs do not exist.

The command refuses an existing artifact directory rather than overwriting
evidence. Rehearse into a disposable parent directory, delete that parent after
inspection, then run once without `--artifact-root` to publish:

```sh
npm --prefix research run tribe -- run \
  --config research/config/first-tribe-scripted-proof.json \
  --artifact-root .scratch/tribe-rehearsal \
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
flags, and every resource ceiling. The plugin derives each `loom_<hash>`
username and offline UUID from the sorted agent id set. The publisher
independently derives the same mapping and rejects a manifest that disagrees.

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
average token estimate. The plugin still treats every decision as untrusted
typed intent and validates request-scoped references against fresh body and
server state.

Cognition remains event-driven. The proof uses `/loombody think` to produce
bounded goal-review events; it does not create a per-tick call path. The normal
driver's threat and scheduled-review triggers remain unchanged.

## Lifecycle and cleanup

The harness starts cognition, the bounded proxy, and Paper in that order. Paper
then starts the canonical fleet through Loom's existing bounded startup gate.
A shared startup deadline covers the service, server, and all bodies. Any
partial startup enters the same cleanup path as cancellation, timeout, disk
failure, cognition failure, or Paper failure.

Paper and cognition are separate process groups. Cleanup sends Paper's ordinary
`stop` and terminates cognition immediately, then terminates and finally kills
any surviving complete process group inside one shared shutdown ceiling. It
still closes the in-process proxy and probes the Minecraft, cognition, and
every body-control port if an earlier cleanup step failed. Publication is
possible only after every managed group and listener is gone. `SIGINT` and
`SIGTERM` enter this same path.

The proof resolves the one configured crash target from its body-control
listener, verifies that the process command is Loom's body executable, and
sends `SIGKILL`. No process id reaches evidence. Peer bodies remain under their
own supervisors, connections, ref stores, cognition budgets, and journals.

## Evidence and publication

The mutable server directory, worlds, raw process logs, raw provider failures,
credentials, binaries, Paper jar, and synchronous death ledger are destroyed or
kept only in ignored local caches. They are never research artifacts.

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
counterevidence rather than being turned into a positive result.

Absolute permadeath is unchanged. The witness is installed before bodies start,
the synchronous ledger remains the login authority, and the ledger is excluded
from publication because it is mutable authorisation state rather than research
evidence.

## Live-model approval boundary

Do not change `cognition.backend` to `openai` and run it merely because a key is
available. Before the first paid run, publish a `needs-decision` report naming
the exact models, agent count, simulated and wall duration, maximum calls,
expected maximum cost, credential source, artifact plan, and stop/rollback
conditions. The credential must enter through `OPENAI_API_KEY` in the process
environment and must not be copied into a configuration, command line, log, or
artifact.

After explicit approval, run the bounded live configuration once. A second run
is a new experiment id and a new approval decision, not a retry hidden under
the first artifact.
