# The research record

Loom exists to find out what an agent does when it has to stay alive.
That is a research question, so the answers need somewhere to live that is as durable as the code, and this directory is it.

Everything here is **append-only**.
An experiment that has been committed is a statement about what happened on a particular day, against a particular commit, and rewriting it destroys the only thing it was for.
When an experiment turns out to have been wrong, it stays and gets an erratum.

## One experiment, one directory

Generations 1 and 2 use the original raw-log layout:

```
research/experiments/<YYYY-MM-DD>-<short-slug>/
  README.md
  manifest.json
  body-events.jsonl
```

Generation 3 is emitted by the bounded tribe harness:

```
research/experiments/<YYYY-MM-DD>-<short-slug>/
  README.md
  manifest.json
  evidence.jsonl       sanitized normalized evidence
  metrics.json         deterministic derivation of evidence.jsonl
```

The date is the date the run happened, not the date it was written up.
The slug names the question, not the outcome, so a directory does not have to be renamed when the result surprises.

### Evidence is immutable

For generations 1 and 2, `body-events.jsonl` is the append-only record
`plugins/Loom/body-events.jsonl` produced by the run, copied byte for byte and never touched again.
Its `sha256` is recorded in `manifest.json`, so a later reader can prove the file they have is the file the numbers came from.
No reformatting, no pretty-printing, no sorting, no removing records that turned out to be uninteresting.

Generation 3 publishes `evidence.jsonl` instead. The harness preserves the
bounded body and lifecycle facts, adds source sequence and fleet-safe request
identity, removes unsafe provider/process/path material, and then derives
`metrics.json`. The raw server directory and its logs remain disposable.
`research/HARNESS.md` is the operator contract for that path.

If a record in it is wrong, the record is still what the run wrote, and that is the fact being preserved.
Say so in an erratum instead.

### `manifest.json` is derived, configured, or attested, and says which

Most of it is **derived**: computable from the generation's evidence file, and nothing the evidence could have produced may be hand-typed instead.
Hand-typing a number the log already carries is how a report and its evidence quietly stop agreeing.
The rest is either a **setting the run was configured with**, which the protocol states and a repeat run would set again, or an **attestation**: a value the operator supplies from outside the raw evidence, which nobody reading this directory can re-derive.

Wall-clock start and end, tool versions, the hashes of artifacts that are not committed,
`knownWarnings`, whether a client was connected, whether anything was watching, and the
dropped-record check are attestations.
They come from the harness and the server log, and neither is committed.
Every one of them sits inside an `attested` block naming its source, or inside an entry whose own `attested` block declares the whole entry to be log-sourced, so a reader can tell at a glance which numbers they can check and which they are taking on trust.
An attestation that the event log could have produced instead is a derivation that was not done.

The manifest carries `schemaVersion`, and which changes raise it - and which do not - is `schema/README.md`, beside the schemas that enforce it.
Ask `schema/manifest-schema.js` for the version a new manifest declares rather than copying a tag from the run before it.

### `README.md` is the argument

The manifest says what happened; the README says what it means and, more importantly, what it does not mean.
Generation 1 and 2 reports carry these sections, in this order:

1. **Question** - the one thing the run was designed to answer.
2. **Protocol** - enough to repeat it: the commit, the environment, the backend, the cadence, and the preconditions that had to hold for the run to count.
3. **Timeline** - what happened, in ticks.
4. **Results** - the numbers, with the manifest as their source.
5. **Limitations** - what the run cannot support, stated as plainly as the results.
6. **Conclusion** - whether the run is scientifically usable, and for what.
7. **Next experiment** - the smallest run that answers the next question.

Generation 3's deterministic publisher uses the corresponding fleet-oriented
sections: **Question**, **Setup facts**, **Observations**, **Interpretations**,
**Counterevidence and null results**, **Limitations**, and **Reproduction**.
The split between observation and interpretation is deliberate: a model action,
co-location sample, or line of speech is evidence that it happened, not by
itself evidence of cooperation, role specialization, motive, or emergence.

## What must never enter this directory

- Credentials, key fragments, or anything a credential can be reconstructed from.
- Full prompts, or unrestricted provider error text, since both can quote back what a model was given.
- Local filesystem paths, machine names, or account identifiers.
- Server logs, generated worlds, binaries, or server jars.
- Harness scripts, scratch tooling, or the runs that were rehearsals rather than experiments.

Generation 1 and 2 event logs were checked directly. Generation 3 takes the
stronger route: its publisher sanitizes bounded fields, omits raw failure text,
and then scans every committed file. Every manifest records that scan and the
number of redactions.

## Corrections and errata

**Committed** means published on this repository's default branch.
An experiment on a branch that is still under review is not committed yet, so a correction to it there is an ordinary edit; from the moment it lands on the default branch the rules below apply.

A committed experiment is never edited to make it right.
It is edited only to append, and an appended correction says what changed and why.

**Erratum.** A number, claim, or interpretation in the README turns out to be wrong.
Add an `## Errata` section at the end of that experiment's README and append an entry:

```markdown
### <YYYY-MM-DD> - <what was wrong>

**Was:** the original claim, quoted.
**Is:** the corrected claim.
**Why:** what was found, and how.
**Reach:** which other claims in this experiment depend on the corrected one.
```

Leave the original sentence in place in the body and mark it, so a reader who quotes the body cannot miss the correction:

```markdown
The reserve fell at 1 percent per 360 ticks. [^e1]

[^e1]: Corrected - see Errata 2026-09-04.
```

**Retraction.** The run itself was invalid, because a precondition failed or the evidence is not what it claims to be.
The experiment stays, and the README gains a `## Retracted` block directly under its title stating what invalidates it and which claims do not survive.
A retracted experiment is still useful: it records a way of running an experiment that does not work.

**Never** delete an experiment directory, never rewrite history to remove one, and never silently update `manifest.json`.
If the manifest was derived by a buggy derivation, fix the derivation, regenerate, and record the change as an erratum naming both values.

## Reading an experiment critically

Start at `manifest.json` and read `integrityChecks` before anything else.
Each check states what it requires and why, and a run with a failing check is not evidence for the thing it was measuring, however interesting its other numbers look.
Generation 1 and 2 checks derive from `body-events.jsonl` except for their
server-log warning attestation. Generation 3 checks derive from
`evidence.jsonl` and `metrics.json`, except for the bounded warning scan and
environment/build attestations named in the manifest. The generation-3
validator re-derives the metrics and cost totals before accepting the artifact.
Only then read the results.
