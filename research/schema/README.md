# The experiment manifest schema, and when its version changes

This directory is the authority on the shape of `research/experiments/*/manifest.json`.
It holds one frozen schema per generation of that shape, the validator that selects among them (`manifest-schema.js`), and this policy.
The policy is stated here and nowhere else; `research/README.md` and the project's `AGENTS.md` point at it rather than restating it.

## What a version is

`schemaVersion` names a **compatibility class**, not a release.
`loom-experiment-manifest/3` means "a reader written for generation 3 can read every field of the shared core out of this file without checking anything else first".
There is no minor number, because there is nothing a minor number would be for: either an old reader still works, in which case the generation is unchanged, or it does not, in which case the generation is new.

## The shared core, and everything else

A manifest has two halves, and only one of them is versioned.

The **shared core** is what every experiment must carry whatever it measured: where the evidence is and its hash, which commit produced it, what it ran on, which mind answered, the record census, the integrity verdict, and what the run may be cited for.
It is exactly the set of fields a generation's schema constrains, and its `properties` keys are the definitive list - do not keep a second copy of that list anywhere.

Everything else is that experiment's own **measurement**, and it is schema-neutral by construction: no object in any generation's schema sets `additionalProperties: false`, nor `unevaluatedProperties: false`, which closes an object just as completely in draft 2020-12 - so a section the schema does not name is neither required, forbidden, nor constrained.
`energyTrajectory` in the survival run and `threatAccounting` in the sighting run are both invisible to the schema, and that is deliberate - an experiment that measures something new adds a section and declares the same generation as the run before it.

A section may only be schema-neutral if the shared core can be read without it.
The moment a reader would have to know which experiment it is looking at to find a core field, that field is core, and moving it there is a new generation.

## The rule

> **A published generation may only ever be loosened. Any change that would reject a manifest the generation already accepts is a new generation instead.**

That is the whole policy.
It is the only formulation that survives the append-only rule in `research/README.md`: evidence committed under a generation is never rewritten, so the schema it was committed under can never grow teeth it did not have on the day.

Written out over the cases, a **new generation** is required when, in the shared core:

1. **A field's type changes.**
   A reader does not error on this; it reads the wrong thing.
   `scannedFor` going from a string to an array is exactly this case - see below.
2. **A required field is removed or renamed.**
3. **A required field moves to a different path.**
   A move is a removal and an addition, and the removal is what breaks the reader.
   `clientPresence` leaving `environment` for the root is this case.
4. **A constraint narrows.**
   A newly required field, a tightened pattern, a narrowed enum, a new bound: anything that would refuse a manifest already committed.

The **same generation** stands when:

1. An experiment adds, drops, or reshapes a measurement section outside the shared core.
   This is the ordinary case and it needs nothing.
2. An optional field is added to a shared-core object, leaving everything already there alone.
3. A constraint loosens - a field becomes optional, a bound widens, an enum gains a member.
4. Only prose changes: a `description`, a `$comment`, or a manifest's own note fields.

Case 2 and case 3 are edits *in place* to a published generation's schema file, and they are legitimate precisely because they cannot reject anything already committed.
Nothing else may be edited in place.

One tempting way round case 1 is closed by the validator.
Widening a field to `"type": ["string", "array"]` looks like a loosening, and it would make one generation accept both shapes of a field that changed - which is the collapse this whole policy exists to prevent, since a reader would then have to check the type before reading it.
`manifest-schema.js` compiles every generation with Ajv `strict`, whose `strictTypes` refuses a type union unless it is a single type paired with `"null"`, so a schema written that way does not validate anything at all: it fails to compile, and it says so.
A nullable pair such as `["array", "null"]` does compile, and it is not this collapse: a reader still reads one shape, or nothing.
`test/manifest-schema.test.js` pins both halves against the options `manifest-schema.js` actually compiles with, so the guard does not rest on an Ajv default nothing exercises.

## The resolved case: `rawEvidence.credentialScan.scannedFor`

Generation 1 carries the patterns the evidence file was scanned for as one prose sentence.
Generation 2 carries them as an array, one entry per pattern, so the scan's coverage is countable and comparable between runs.

That is a shared-core type change, so it required generation 2, and it got it: the two committed manifests declare 1 and 2 respectively and neither was rewritten.
`loom-experiment-manifest-1.schema.json` still pins `scannedFor` as a string and always will; generation 2 pins it as a non-empty array of non-empty strings.
Adding a further pattern to a later run is now an extra **item**, not a reshaped field, so no generation after 2 is owed for one - which is the reason the array is the better shape and the reason the bump was worth taking once.

Three other shared-core shapes changed in the same step, and any one of them alone would also have required it: `outcome` became a prose statement rather than a structured object, `scientificUsability.scope` became an array of claims rather than one sentence, and `clientPresence` moved from inside `environment` to the root.
`test/manifest-schema.test.js` declares this step in its table of adjacent generations and pins all four paths, by validating each committed manifest against the *other* generation and requiring it to be refused, so these two generations cannot quietly collapse into one.
All four are type or path changes, which is why refusal runs both ways here; a later step need not.

## The resolved case: bounded fleet publication

Generation 3 makes the information needed to repeat and audit a shared-world
fleet run part of the shared core. It newly requires component hashes, tool and
Paper versions, deterministic agent allocations, cognition models/prompts/call
and spend ceilings, lifecycle/artifact bounds, feature flags, normalized
metrics, wall-clock timing, and the two publication validation passes.
`eventCounts.agent` also becomes `eventCounts.agents`, because a single-agent
object cannot faithfully represent a fleet.

Every one of those additions narrows the accepted set, and the census changes
path and shape, so generation 2 could not be edited in place. The adjacent-step
test uses a committed generation-2 manifest as the witness: generation 2
accepts it and generation 3 refuses the missing bounded-fleet core.

## Choosing a version for a new manifest

Ask `manifest-schema.js`.

```js
const { currentSchemaVersion, versionTag } = require('./schema/manifest-schema.js')
const schemaVersion = versionTag(currentSchemaVersion()) // "loom-experiment-manifest/3"
```

`currentSchemaVersion()` is the highest generation with a schema file in this directory, read from the filesystem on every call.
It is derived rather than written down so that adding `loom-experiment-manifest-3.schema.json` moves it with nothing else to remember, and so there is no constant that can disagree with the schemas beside it.

`research/lib/artifact.js` is the generation-3 publisher. It takes the version
from `currentSchemaVersion()` and validates the prospective manifest before
launch and the completed artifact before publication. Older committed
manifests remain hand-derived under their own frozen generations.
That is what "deterministic" means here: one function, one filesystem answer,
no second copy.

## Adding a generation

1. Copy the newest schema to `loom-experiment-manifest-<n+1>.schema.json` and change `$id`, `title`, the root `description`, and the `schemaVersion` const.
2. Make the shared-core change there.
   Leave every earlier generation's file untouched.
3. Say in the new schema's root `description` which shared-core shapes changed and why, as generation 2 does.
4. Declare the step in `test/manifest-schema.test.js`'s table of adjacent generations: the shared-core paths that changed, and at least one **witness** - a manifest the two generations disagree about, saying which of the two accepts it.
   A witness may run in either direction, and which direction exists follows the case the bump was taken under.
   For a **type change** (case 1) both directions exist, and the committed evidence is itself the witness: each generation's own manifest is refused by the other.
   For a **renamed** or **relocated** required field (cases 2 and 3) both directions exist too, because each generation requires the field somewhere the other does not: a manifest carrying only the new name or the new path is accepted by the newer and refused by the older, and one carrying only the old is the reverse.
   For a required field that is **dropped outright** (case 2) only one direction exists, and it is not the obvious one: the newer generation is strictly looser on that field, so no value exists that the older accepts and the newer refuses, and the witness is a manifest without the field, accepted by the **newer** generation and refused by the older.
   For a **narrowed constraint** (case 4) only the other direction exists: a value the **older** generation accepts and the newer refuses, built by hand, since the committed evidence may well still be valid under the new generation.
5. Run `npm run check`.

The suite proves that every published generation still compiles, that the committed manifests still validate under their own generations, that the new step is declared at all, and that its witness makes the two generations disagree.
The compile half covers a generation nothing has been committed under yet, which no other case would reach.
It deliberately does **not** require the new generation to refuse evidence committed under the old one.
A bump taken for a narrowed constraint or a dropped required field leaves that evidence valid by design, and evidence may never be rewritten to make it otherwise - so a suite that demanded blanket mutual refusal would have to be weakened the day such a bump landed, which is the opposite of a gate.

**The loosen-only rule itself is not mechanically enforced, and cannot be.**
Whether one schema accepts everything another does is not decidable from the two schemas in general, so the gate catches a narrowing edit to a published generation only where a committed manifest happens to violate it.
Add `"maxLength": 200` to generation 1's `title` and every case still passes, because the committed generation-1 title is well under it - and the frozen generation has quietly grown teeth all the same.
So an in-place edit to a published generation is checked against the rule by the person making it, and by review, rather than by the suite; what the suite guarantees is only that the evidence already committed still reads.

The generations are **self-contained**: no schema `$ref`s another file, which is the point of the duplication between them.
A shared core factored into a common file would mean an edit there silently redefining what a generation already published means, and the whole policy above rests on that being impossible.

## Running the gate

```
cd research && npm ci && npm run check
```

`standard` lint plus the `node:test` suite; no network, no server, no credential.
Node 22 or newer, as `package.json` states.
