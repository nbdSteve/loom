# The body-control protocol, Node side

This package is the Node half of Loom's **body-control** contract: the versioned,
newline-delimited JSON protocol a body process is driven over.

It is currently the contract and nothing else - one pure module, `src/protocol.js`,
which decodes a frame, encodes a frame, and refuses everything the contract forbids.
There is no socket here, no Mineflayer, no process to run, and nothing is wired into
the Paper plugin.
**This package does not replace the Villager body**, and Loom's runtime does not know this
package exists.
The process that opens a loopback socket and drives a real player comes in a later
slice; the contract lands first so both runtimes are already agreed on the wire before
either of them speaks it.

## Build and test

```sh
cd body
npm ci          # installs exactly the committed lockfile
npm run check   # lint (standard) + tests (node:test)
```

There is no build step - the package is plain CommonJS - and no network is touched by
the tests.
`npm run format` applies `standard --fix`.

Node 20.11 or newer, as `package.json` states.
The tests run under the built-in `node:test` runner, invoked as bare `node --test`:
on Node 25 a directory positional such as `node --test test/` is `require()`d rather
than scanned, so it fails with `MODULE_NOT_FOUND`.

## What is in here

- `src/protocol.js` - every bound the contract states, and the codec that enforces
  them. It is pure: no `require`, no I/O, no clock. The bounds are declared here
  rather than in the reader that will enforce them, because they are facts about the
  contract and a future framing layer needs one place to read them from.
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
enforced at *every* public decode rather than in the framing layer a body process will
one day put in front of the decoders, since a controller can reach one without it. A
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
