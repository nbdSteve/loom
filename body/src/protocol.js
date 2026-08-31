'use strict'

/**
 * The body-control protocol: bounds, and the only place a frame is turned into
 * or out of JSON.
 *
 * `contract/body-control.schema.json` is the source of truth for the shapes; the
 * types here are hand-written against it and kept honest by
 * `test/conformance.test.js`, exactly as the perception/decision contract's two
 * runtimes are. This module is pure - no socket, no clock, no Mineflayer - so
 * every bound and every refusal is provable at a desk.
 *
 * Four properties are load-bearing rather than stylistic:
 *
 * - **Nothing is trusted before it is bounded.** A frame arrives off a socket,
 *   where no schema runs, so `decodeRequest` re-checks every rule the schema
 *   states rather than assuming a well-behaved peer.
 * - **A refusal carries a code from a closed set and never a message.** An
 *   exception string, a file path, a host name or a token cannot leave through a
 *   field that can only hold one of `ERROR_CODES`. See the schema's own note on
 *   why this is stricter than the cognition wire's free-text refusal.
 * - **Nothing is written that this module would not read back.** Every builder
 *   routes its finished frame through this module's own decoder and throws
 *   rather than hand back a frame the decoder refuses, and `encodeRequest` /
 *   `encodeResponse` do it again at the one place a frame becomes bytes. The
 *   closed `error` enum bounds only the *reason* on a refusal; without this the
 *   ids beside it were free text, and an unchecked `agentId` carried a local
 *   path, a transport error or a newline through the very frame whose purpose is
 *   to carry none of them.
 * - **A duplicate member name is refused on the bytes.** `JSON.parse` keeps the
 *   last value and says nothing, so the question cannot be asked after the
 *   parse - and the two runtimes do not even lose it the same way.
 * - **A frame is bounded before it is looked at at all.** Its size and its
 *   nesting are settled by [inadmissibleFrame] ahead of the duplicate scan and
 *   ahead of `JSON.parse`, because both of those walk the whole line: a bound
 *   applied after the work it exists to bound is not a bound. See that function
 *   for why the order is the whole of the guarantee.
 */

/** The one protocol generation. A frame naming any other is refused, not interpreted. */
const PROTOCOL_VERSION = 1

/**
 * Longest frame this protocol will carry, in bytes.
 *
 * The largest legal frame in this generation is a status report with both ids at
 * their full length, the longest state name, and all three counters at
 * `MAX_COUNT` - 297 bytes - so 8 KiB is generous by a factor of twenty-seven
 * while still being a real bound, two orders of magnitude tighter than the
 * cognition wire's 256 KiB, which is sized for a whole perception rather than
 * for a lifecycle question. That there *is* a largest legal frame is what
 * `MAX_COUNT` buys: an `integer` bounded only from below admits arbitrarily many
 * digits, so before it this margin was a claim about no particular number.
 *
 * It is stated here, with the rest of the protocol's bounds, and enforced by
 * [inadmissibleFrame] at every public decode - so the codec and its future framing
 * layer have one exported value to use, and a peer cannot reach the parse by
 * skipping the framing layer that was once going to be the only reader.
 * `protocol.test.js` pins that every frame the encoder can produce fits inside it
 * with room to spare, which is what makes the margin above a measurement rather
 * than a claim.
 *
 * It is counted in **UTF-8 bytes** rather than in characters, because that is what
 * the wire carries and what the JVM binding's own copy of this number counts: a
 * line of eight thousand astral characters is 32 KiB on the socket, and measuring
 * it as eight thousand would let a peer spend four times the bound.
 */
const MAX_FRAME_BYTES = 8192

/**
 * Deepest a frame may nest before it is refused unparsed.
 *
 * Not a size bound, and the two ends of this wire need it for different reasons.
 * Within the 8 KiB above a peer can write four thousand open brackets, and what that
 * costs depends on the reader: kotlinx's `parseToJsonElement` recurses once per level,
 * as does the JVM's own walk for explicit nulls, so there it arrives as a
 * `StackOverflowError` - an `Error` and not an exception any caller catches. V8's
 * `JSON.parse` is iterative and is troubled by none of it, so **this side carries the
 * number for parity rather than for safety**: one wire refusing the same frames at both
 * ends is the whole point of three hand-written descriptions of it, and a frame this
 * runtime read happily while the other refused it outright would be exactly the drift
 * the shared corpus exists to catch. What it buys here is the smaller thing - a bound on
 * the work a peer can ask the scans below to do.
 *
 * Eight is far above anything this generation can legally hold and far below anything
 * either parser is troubled by. No frame here nests at all - every field is a string, a
 * number or a bare enum, so a legal frame's depth is one - and a nested value could only
 * ever arrive under a key this generation does not know, which is refused on its own
 * with the more precise `invalidField`. So the slack costs nothing and the bound refuses
 * only a line that is asking to be expensive. The JVM binding states the same number for
 * itself, where it is load-bearing.
 */
const MAX_FRAME_DEPTH = 8

/** Longest a requestId or agentId may be, in characters. */
const MAX_ID_CHARS = 64

/**
 * Largest value a status counter may carry.
 *
 * This is JavaScript's `Number.MAX_SAFE_INTEGER`, written as a literal because
 * it is a fact about the *contract* rather than about this runtime: the schema
 * states the same number and the JVM binding states it a third time. Without it
 * the two bindings disagreed about a legal frame - a `Long` duration the JVM
 * encoded happily came back from `JSON.parse` as a different number, so Node
 * refused bytes the schema and the other binding had both accepted. Bounding it
 * to what the narrower runtime can read back is the only version of the bound
 * that both sides can hold.
 */
const MAX_COUNT = 9007199254740991

/**
 * Longest numeric literal this wire will look at, in characters.
 *
 * The largest legal value on it is sixteen digits, and this admits twice that: a
 * sign, a full mantissa with a decimal point, and an exponent. Past it a literal
 * is not a peer spelling a number a different way but a line asking to be
 * expensive, and the bound is applied before anything measures it. The JVM
 * binding states the same number for itself.
 */
const MAX_NUMBER_LITERAL_CHARS = 32

/**
 * The most *integer* digits a value on this wire may have before it is built.
 *
 * How many digits `MAX_COUNT` has, so nothing affordable is refused and nothing
 * refused here could have been in bounds anyway. It counts the digits left of the
 * point after the exponent is applied, never the digits the literal happens to be
 * written with: charging a mantissa's zeros as integer digits is what made
 * `1.00000000000000000` - a schema-legal spelling of `1`, and one the JVM reads -
 * come back refused. The JVM states its own `MAX_INTEGER_DIGITS` for the same
 * measurement and sets it at 20, one past a `Long`, because there the field's own
 * range check is what refuses an over-bound value and says so in its message;
 * here the range check below is this function's, so the bound can be exact.
 */
const MAX_INTEGER_DIGITS = 16

/**
 * A JSON number, split into the four parts that decide what it means.
 *
 * Deliberately looser than JSON's own grammar - it admits a leading zero - because
 * it is only ever applied to a literal `JSON.parse` has already accepted, so
 * re-stating the grammar here would be a second, driftable copy of it.
 */
const NUMBER_LITERAL = /^(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/

/** Where a number may begin, for the scanner that finds one in a frame. */
const NUMBER_START = /[-0-9]/

/** Every character a JSON number can be made of, for finding where one ends. */
const NUMBER_CHARACTERS = /[-+0-9.eE]/

/** Any digit that would make a value nonzero, for the two tests that need one. */
const NONZERO_DIGIT = /[1-9]/

/**
 * Fewest requests a status report may say the process has handled.
 *
 * One, not zero: answering the status request is itself handling a request, so a
 * report claiming none has counted its own answer wrong. The schema's prose said
 * so and its `minimum` did not.
 */
const MIN_REQUESTS_HANDLED = 1

/**
 * The three numbers a status report carries, named once.
 *
 * Every one of them is bounded the same way and read from the frame's own bytes
 * the same way, so naming them once is what stops a fourth count being added and
 * inheriting only half of that.
 */
const STATUS_COUNTS = ['uptimeMs', 'stateMs', 'requestsHandled']

/**
 * What an id may contain. Deliberately narrow: an id is echoed into a frame and
 * into logs, so a newline would break the framing, and a path separator or
 * whitespace is how a local path or a free-text transport error would arrive in
 * something that gets written down.
 */
const ID_PATTERN = /^[A-Za-z0-9._:-]+$/

/** Minecraft's own account-name rule, which is what makes a login possible at all. */
const USERNAME_PATTERN = /^[A-Za-z0-9_]{3,16}$/

/** Every lifecycle state, in the order a life passes through them. */
const LIFECYCLE_STATES = Object.freeze([
  'connecting',
  'loggedIn',
  'ready',
  'dead',
  'disconnected',
  'stopping',
  'failed'
])

/** Every operation a controller may ask for. */
const OPERATIONS = Object.freeze(['handshake', 'status', 'shutdown'])

/** Every refusal, as a closed set. */
const ERROR_CODES = Object.freeze([
  'unsupportedProtocol',
  'malformedFrame',
  'frameTooLarge',
  'unknownOperation',
  'invalidField',
  'duplicateRequestId',
  'tooManyRequests',
  'handshakeRequired',
  'wrongAgent',
  'shuttingDown',
  'timeout'
])

/** JSON's four whitespace characters, which may sit between a name and its colon. */
const JSON_WHITESPACE = Object.freeze(new Set([' ', '\t', '\n', '\r']))

/**
 * What a builder or an encoder says when it will not write a frame.
 *
 * The refusal code is appended and nothing else is: the frame itself is what may
 * hold a path or a credential, so repeating it here would move the leak from the
 * wire into the exception, where it reaches a log just as readily.
 */
const UNSENDABLE = 'the body-control protocol will not carry this frame'

/** Whether `value` is a bounded id the protocol will carry. */
function isControlId (value) {
  return typeof value === 'string' &&
    // Counted in code points, matching the schema's maxLength, rather than in
    // UTF-16 units - which would measure a non-BMP character double and let one
    // runtime accept an id the other refuses.
    [...value].length <= MAX_ID_CHARS &&
    ID_PATTERN.test(value)
}

/** Whether `value` is a username that could actually log in. */
function isUsername (value) {
  return typeof value === 'string' && USERNAME_PATTERN.test(value)
}

/** Whether `value` is a plain JSON object rather than an array, null or a scalar. */
function isPlainObject (value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/**
 * Whether `value` is a whole number within the range the schema allows.
 *
 * `Number.isSafeInteger` already implies the upper bound on this runtime; the
 * comparison against `MAX_COUNT` is written out anyway, because that is the
 * number the schema and the JVM binding state and a reader here should be able
 * to see the same bound rather than infer it from a runtime's own limit.
 */
function isCount (value) {
  return Number.isSafeInteger(value) && value >= 0 && value <= MAX_COUNT
}

/**
 * Whether the literal `name` was written with spells a whole number in bounds.
 *
 * This is the half of a number's contract that `JSON.parse` destroys, and it is
 * the reason every number on this wire is read from the frame's own text as well
 * as from the parsed object. `JSON.parse` answers with the nearest double, so
 * `9007199254740991.4` arrives as a whole number and `1.0000000000000000001`
 * arrives as `1`: both are fractions to the schema, both were accepted here, and
 * the checks above them cannot tell because the fraction is gone before they run.
 * The other direction is the one the JVM had to fix - `1.0` and `1e0` *are* whole
 * numbers to the schema - so both runtimes now read the same rule off the same
 * bytes rather than each inheriting its own parser's idea of an integer.
 *
 * A missing literal answers false. Every top-level number in a frame this module
 * has already parsed is found by [topLevelNumbers], so an absent one means the
 * field held something that is not a number at all.
 */
function spellsWholeNumber (literals, name) {
  const literal = literals.get(name)
  if (typeof literal !== 'string' || literal.length > MAX_NUMBER_LITERAL_CHARS) return false
  const parts = NUMBER_LITERAL.exec(literal)
  if (parts === null) return false
  const [, sign, whole, fraction = '', exponent = '0'] = parts
  const digits = whole + fraction
  // Where the decimal point lands: the exponent, less the digits already right of
  // the point. Positive shifts the point right, negative leaves it inside.
  const shift = Number(exponent) - fraction.length
  return isWholeShift(digits, shift) && isInRange(sign, digits, shift)
}

/**
 * Whether `digits` shifted by `shift` places leaves nothing after the point.
 *
 * Positive or zero always does. Negative does only when every digit the shift
 * would move right of the point is a zero, which is what makes `1.0`, `1.00`,
 * `0.1e1` and `20e-1` whole numbers and `1.5`, `15e-1` and `1e-30` not.
 */
function isWholeShift (digits, shift) {
  if (shift >= 0) return true
  return !NONZERO_DIGIT.test(digits.slice(Math.max(digits.length + shift, 0)))
}

/**
 * Whether the exact value `digits` shifted by `shift` spells is `0..MAX_COUNT`.
 *
 * The digit count is checked *before* the value is built, which is the whole point
 * of doing this arithmetic by hand: `1e2000000000` is twelve characters asking for
 * a two-billion-digit number, and a bound on the literal's length does not catch
 * it. Only once the count is known to be affordable is the value materialised, on
 * a string short enough that a `BigInt` of it is free.
 *
 * What is counted is the digits the *value* has left of the point once the shift is
 * applied, which is the measure the JVM takes from the scale - and it is counted
 * from the significant digits, so neither end of a mantissa's padding is charged as
 * an integer digit. Counting the digits the literal was *written* with instead
 * refused `1.00000000000000000` (a spelling of `1` the schema and the JVM both
 * read) for its trailing zeros, and counting them after the shift but before
 * stripping still refused `0.0000000000000000001e19` (another spelling of `1`) for
 * its leading ones. The bound therefore carries no slack for either: it is exactly
 * how many digits `MAX_COUNT` has.
 *
 * A mantissa of nothing but zeros is a spelling of zero whatever its exponent says,
 * so it is answered before the count and never scaled. That is the same fast path
 * `IntegralNumbers.kt` takes for the same reason: `0e2000000000` and
 * `0e-9999999999999999999999999` are both legal zeros, and there is neither a
 * number to build nor a bound to check for a value the exponent cannot change.
 */
function isInRange (sign, digits, shift) {
  const significant = digits.replace(/^0+/, '')
  if (significant === '') return true
  // Past here the value is nonzero, so a sign puts it below a count's own minimum.
  if (sign === '-') return false
  if (Math.max(significant.length + shift, 0) > MAX_INTEGER_DIGITS) return false
  const scaled = shift >= 0
    ? significant + '0'.repeat(shift)
    : significant.slice(0, Math.max(significant.length + shift, 0)) || '0'
  return BigInt(scaled) <= BigInt(MAX_COUNT)
}

/**
 * Every top-level member of `text` whose value is a number, as written.
 *
 * A frame's numbers have to be read from its bytes, and by the time either decoder
 * holds an object the text of them is gone - so this walks the line the way
 * [namesAMemberTwice] does, skipping string literals rather than trying to
 * recognise a number inside one.
 *
 * It runs only on text `JSON.parse` has already accepted, which is what lets it
 * stay a plain walk: it does not have to recognise malformed JSON, because a frame
 * that is not JSON was refused as `malformedFrame` before this is called. A number
 * nested inside an object or an array is deliberately not collected, since no
 * field of this generation holds either and an unknown key is refused anyway.
 */
function topLevelNumbers (text) {
  const literals = new Map()
  let depth = 0
  let name
  let index = 0
  while (index < text.length) {
    const character = text[index]
    if (character === '{' || character === '[') {
      depth += 1
      index += 1
    } else if (character === '}' || character === ']') {
      depth -= 1
      name = undefined
      index += 1
    } else if (character === '"') {
      const after = endOfStringLiteral(text, index)
      if (after === undefined) return literals
      if (depth === 1 && isFollowedByColon(text, after)) name = memberName(text.slice(index, after))
      index = after
    } else if (NUMBER_START.test(character)) {
      const after = endOfNumberLiteral(text, index)
      if (depth === 1 && name !== undefined) {
        literals.set(name, text.slice(index, after))
        name = undefined
      }
      index = after
    } else {
      index += 1
    }
  }
  return literals
}

/**
 * One past the last character of the number beginning at `open`.
 *
 * Every character a JSON number can hold and nothing else, so the run stops at the
 * comma, brace or whitespace that must follow it. On text that is not valid JSON
 * this may take in more than one number; that costs nothing, because such text is
 * refused before this runs and a literal it produced would fail its own check.
 */
function endOfNumberLiteral (text, open) {
  let index = open
  while (index < text.length && NUMBER_CHARACTERS.test(text[index])) index += 1
  return index
}

/**
 * Whether a status report's three numbers could have been measured together.
 *
 * Each is bounded on its own above. This is the part no JSON Schema can state,
 * since draft 2020-12 cannot compare one property with another: a process starts
 * in a state, so it has been in the state it reports for no longer than it has
 * been running, and a status report is itself a request answered, so the tally
 * is at least one. The schema's prose promised both and its keywords enforced
 * neither, which left the executable contract accepting telemetry no process
 * could have produced.
 */
function isCoherentStatus (raw) {
  return raw.stateMs <= raw.uptimeMs && raw.requestsHandled >= MIN_REQUESTS_HANDLED
}

/**
 * The refusal a line earns before anything reads it, or undefined when it earns none.
 *
 * Every public decode starts here, and the order inside is the whole guarantee: the
 * size is settled first because everything after it walks the line at least once, and
 * the nesting is settled before `JSON.parse` because on the other end of this wire that
 * parser is what the depth bound protects - see [MAX_FRAME_DEPTH] for why this side
 * refuses the same frames anyway. A duplicate scan or a parse placed in front of either
 * would be work done on an unbounded line, which is what having a bound was for.
 * [MAX_FRAME_BYTES]
 * was exported and enforced nowhere for exactly that reason, and the framing layer it
 * was waiting for would still not have covered a controller calling a decoder itself.
 *
 * A non-string is refused here too rather than left to coerce: `JSON.parse` stringifies
 * whatever it is given, so `decodeRequest(1)` used to parse the number `1` and refuse it
 * as a non-object one check later, while nothing had measured anything at all.
 *
 * Both refusals are `malformedFrame` or `frameTooLarge` and neither carries a
 * requestId: nothing has been parsed, so no id has been received to echo.
 */
function inadmissibleFrame (line) {
  if (typeof line !== 'string') return 'malformedFrame'
  if (Buffer.byteLength(line, 'utf8') > MAX_FRAME_BYTES) return 'frameTooLarge'
  if (nestsTooDeeply(line)) return 'malformedFrame'
  if (namesAMemberTwice(line)) return 'malformedFrame'
  return undefined
}

/**
 * Whether the raw text opens more than [MAX_FRAME_DEPTH] nested objects or arrays.
 *
 * A flat walk with a counter, deliberately not a recursive one: a check against stack
 * exhaustion that recursed per level would be the thing it exists to refuse. String
 * literals are stepped over whole, the way [namesAMemberTwice] and [topLevelNumbers]
 * step over them, so a bracket inside a value is not read as structure.
 *
 * Text this cannot walk to the end of is not reported as too deep - it is not valid
 * JSON either, and the parse a moment later refuses it for that instead.
 */
function nestsTooDeeply (text) {
  let depth = 0
  let index = 0
  while (index < text.length) {
    const character = text[index]
    if (character === '{' || character === '[') {
      depth += 1
      if (depth > MAX_FRAME_DEPTH) return true
      index += 1
    } else if (character === '}' || character === ']') {
      depth -= 1
      index += 1
    } else if (character === '"') {
      const after = endOfStringLiteral(text, index)
      if (after === undefined) return false
      index = after
    } else {
      index += 1
    }
  }
  return false
}

/**
 * Reads one request line.
 *
 * Returns `{ ok: true, request }`, or `{ ok: false, code, requestId }` where
 * `requestId` is present only when the frame carried a readable one - a refusal
 * must never echo an id the process did not receive.
 *
 * The order of the checks is load-bearing rather than tidy: the frame's size and
 * nesting are settled on the raw line first ([inadmissibleFrame]), because every
 * check after them walks it; a repeated member name is settled on the bytes too,
 * before anything is parsed, because after the parse there is nothing left to
 * notice; then the protocol generation is settled before any generation-specific
 * field is interpreted, because a later generation may spell the same field
 * differently and a value read under the wrong generation is worse than a refusal.
 * The requestId is the deliberate exception: it is bounded first only so an
 * unsupported-protocol refusal can be correlated.
 */
function decodeRequest (line) {
  const inadmissible = inadmissibleFrame(line)
  if (inadmissible !== undefined) return { ok: false, code: inadmissible }

  let raw
  try {
    raw = JSON.parse(line)
  } catch {
    return { ok: false, code: 'malformedFrame' }
  }
  if (!isPlainObject(raw)) return { ok: false, code: 'malformedFrame' }
  const literals = topLevelNumbers(line)

  // Echoed on every refusal below, so a controller can retire the right request.
  const requestId = isControlId(raw.requestId) ? raw.requestId : undefined

  if (raw.protocol !== PROTOCOL_VERSION || !spellsWholeNumber(literals, 'protocol')) {
    return { ok: false, code: 'unsupportedProtocol', requestId }
  }
  if (!isControlId(raw.requestId) || !isControlId(raw.agentId)) {
    return { ok: false, code: 'invalidField', requestId }
  }
  if (!OPERATIONS.includes(raw.op)) {
    return { ok: false, code: 'unknownOperation', requestId }
  }
  // Extra data is refused rather than ignored: a field this generation does not
  // know is a controller believing something the process will not do.
  const known = new Set(['protocol', 'requestId', 'agentId', 'op'])
  if (Object.keys(raw).some((key) => !known.has(key))) {
    return { ok: false, code: 'invalidField', requestId }
  }
  return {
    ok: true,
    request: { op: raw.op, protocol: raw.protocol, requestId: raw.requestId, agentId: raw.agentId }
  }
}

/**
 * Reads one response line, for a controller and for the conformance tests.
 *
 * The process itself never calls this to read - it writes responses and reads
 * requests - but it does call it to *check* what it is about to write, and the
 * shapes it writes are only worth pinning if something independent can refuse a
 * malformed one. A future JVM controller decodes exactly this.
 */
function decodeResponse (line) {
  const inadmissible = inadmissibleFrame(line)
  if (inadmissible !== undefined) return { ok: false, code: inadmissible }

  let raw
  try {
    raw = JSON.parse(line)
  } catch {
    return { ok: false, code: 'malformedFrame' }
  }
  if (!isPlainObject(raw)) return { ok: false, code: 'malformedFrame' }
  const literals = topLevelNumbers(line)
  if (raw.protocol !== PROTOCOL_VERSION || !spellsWholeNumber(literals, 'protocol')) {
    return { ok: false, code: 'unsupportedProtocol' }
  }
  if (!isControlId(raw.agentId)) return { ok: false, code: 'invalidField' }

  // A refusal carries no op, which is what tells the two frames apart.
  if (raw.op === undefined) {
    if (!ERROR_CODES.includes(raw.error)) return { ok: false, code: 'invalidField' }
    if (raw.requestId !== undefined && !isControlId(raw.requestId)) {
      return { ok: false, code: 'invalidField' }
    }
    return noUnknownKeys(raw, ['protocol', 'requestId', 'agentId', 'error'])
      ? { ok: true, response: raw }
      : { ok: false, code: 'invalidField' }
  }

  if (!isControlId(raw.requestId)) return { ok: false, code: 'invalidField' }
  if (!OPERATIONS.includes(raw.op)) return { ok: false, code: 'unknownOperation' }
  if (!LIFECYCLE_STATES.includes(raw.state)) return { ok: false, code: 'invalidField' }

  const envelope = ['protocol', 'requestId', 'agentId', 'op', 'state']
  if (raw.op === 'handshake') {
    if (!isUsername(raw.username)) return { ok: false, code: 'invalidField' }
    return noUnknownKeys(raw, [...envelope, 'username'])
      ? { ok: true, response: raw }
      : { ok: false, code: 'invalidField' }
  }
  if (raw.op === 'status') {
    // Each count twice: once as the value it parsed to, and once as the literal it
    // was written as, because a fraction is gone by the time the value exists.
    const counted = STATUS_COUNTS.every(
      (name) => isCount(raw[name]) && spellsWholeNumber(literals, name)
    )
    if (!counted) return { ok: false, code: 'invalidField' }
    if (!isCoherentStatus(raw)) return { ok: false, code: 'invalidField' }
    return noUnknownKeys(raw, [...envelope, ...STATUS_COUNTS])
      ? { ok: true, response: raw }
      : { ok: false, code: 'invalidField' }
  }
  // shutdown: the state is fixed, because there is only one shutdown to report.
  if (raw.state !== 'stopping') return { ok: false, code: 'invalidField' }
  return noUnknownKeys(raw, envelope)
    ? { ok: true, response: raw }
    : { ok: false, code: 'invalidField' }
}

/**
 * Whether `raw` carries no key outside `allowed`.
 *
 * Only the unknown-key half of the contract: that every *required* field is
 * present is settled by the checks above each call site, which have to run
 * anyway because a present field can still hold the wrong thing.
 */
function noUnknownKeys (raw, allowed) {
  return Object.keys(raw).every((key) => allowed.includes(key))
}

/**
 * Whether `text` names any member twice inside one object.
 *
 * `JSON.parse` keeps the last value for a repeated name and reports nothing, so
 * by the time either decoder holds an object the duplicate is already gone. Worse
 * than losing it, the two runtimes lose it differently: kotlinx-serialization
 * refuses a repeated union discriminator while `JSON.parse` takes the second one,
 * so a single frame meant `handshake` to one reader and `status` to the other.
 * Two meanings for one field is not a shape a schema can rule out either - the
 * duplicate never reaches the JSON Schema data model - so this reads the bytes,
 * which is the only place the question can still be asked.
 *
 * It is conservative on text that is not valid JSON: a false answer there costs
 * nothing, because the parse a moment later refuses that text with the same code.
 */
function namesAMemberTwice (text) {
  if (typeof text !== 'string') return false
  const namesByDepth = []
  let index = 0
  while (index < text.length) {
    const character = text[index]
    if (character === '{') {
      namesByDepth.push(new Set())
      index += 1
    } else if (character === '}') {
      namesByDepth.pop()
      index += 1
    } else if (character === '"') {
      const after = endOfStringLiteral(text, index)
      if (after === undefined) return false
      if (repeatsAName(text, index, after, namesByDepth)) return true
      index = after
    } else {
      index += 1
    }
  }
  return false
}

/**
 * Whether the literal at `[open, after)` is a member name already seen at this
 * depth, recording it when it is not.
 *
 * Split out from `namesAMemberTwice` so the scanner above stays a plain walk over
 * the bytes: the two questions - where does this literal end, and is this literal
 * a name - are independent, and mixing them is how a scanner starts counting a
 * brace inside a string.
 */
function repeatsAName (text, open, after, namesByDepth) {
  const names = namesByDepth[namesByDepth.length - 1]
  // A string outside every object, or one not followed by a colon, names nothing:
  // it is an array element or a value.
  if (names === undefined || !isFollowedByColon(text, after)) return false
  const name = memberName(text.slice(open, after))
  if (name === undefined) return false
  if (names.has(name)) return true
  names.add(name)
  return false
}

/**
 * One past the closing quote of the string literal opening at `open`, or
 * `undefined` if the text runs out first.
 */
function endOfStringLiteral (text, open) {
  let index = open + 1
  while (index < text.length) {
    const character = text[index]
    // A backslash escapes whatever follows it, including a quote, so the pair is
    // stepped over as one. Nothing here needs to know which escape it was.
    if (character === '\\') {
      index += 2
    } else if (character === '"') {
      return index + 1
    } else {
      index += 1
    }
  }
  return undefined
}

/** Whether the next thing at or after `index`, ignoring whitespace, is a colon. */
function isFollowedByColon (text, index) {
  let at = index
  while (at < text.length && JSON_WHITESPACE.has(text[at])) at += 1
  return text[at] === ':'
}

/**
 * The name a string literal spells, with its escapes resolved, or `undefined` if
 * it is not a literal at all.
 *
 * Resolved rather than compared as written, because `"op"` and `"\u006fp"` are
 * one name to a parser: comparing the raw text would let a duplicate spelled the
 * second way through the very check that exists to catch it.
 */
function memberName (literal) {
  try {
    return JSON.parse(literal)
  } catch {
    return undefined
  }
}

/**
 * The canonical line for a request, refusing to write one this module would not
 * read back.
 *
 * Key order is the schema's own declaration order with the `op` tag first, which
 * is what both the JVM's kotlinx-serialization and Rust's serde emit for an
 * internally tagged union - so the shared fixtures round-trip byte-for-byte on
 * every side rather than only validating.
 */
function encodeRequest (frame) {
  return encodeChecked(frame, decodeRequest, (verdict) => verdict.request)
}

/** The canonical line for a response or a refusal, on the same terms. */
function encodeResponse (frame) {
  return encodeChecked(frame, decodeResponse, (verdict) => canonicalResponse(verdict.response))
}

/**
 * The one place a frame becomes bytes.
 *
 * There is deliberately no encoder that writes whatever it is handed. A bare
 * `JSON.stringify` was the bypass around every builder below, and the frame it
 * wrote was refused by this module's own decoder - which on the error path meant
 * a controller was sent a frame it could not read, carrying in its unchecked ids
 * exactly the paths and credentials the id bounds exist to keep out.
 */
function encodeChecked (frame, decode, canonical) {
  const line = lineFor(frame)
  const verdict = decode(line)
  if (!verdict.ok) throw new Error(`${UNSENDABLE}: ${verdict.code}`)
  return lineFor(canonical(verdict))
}

function canonicalResponse (frame) {
  if (frame.op === undefined) {
    const canonical = { protocol: frame.protocol }
    if (frame.requestId !== undefined) canonical.requestId = frame.requestId
    canonical.agentId = frame.agentId
    canonical.error = frame.error
    return canonical
  }
  const canonical = {
    op: frame.op,
    protocol: frame.protocol,
    requestId: frame.requestId,
    agentId: frame.agentId,
    state: frame.state
  }
  if (frame.op === 'handshake') canonical.username = frame.username
  if (frame.op === 'status') {
    canonical.uptimeMs = frame.uptimeMs
    canonical.stateMs = frame.stateMs
    canonical.requestsHandled = frame.requestsHandled
  }
  return canonical
}

/** `frame` as JSON text, or a bounded throw if it is not JSON at all. */
function lineFor (frame) {
  let line
  try {
    line = JSON.stringify(frame)
  } catch {
    // Not the caught error's own message: a stringify failure names the property
    // it choked on, which is caller data and so is exactly what may not be
    // repeated. A frame that cannot be written is malformed and says only that.
    throw new Error(`${UNSENDABLE}: malformedFrame`)
  }
  // `JSON.stringify(undefined)` is not a string, which no decoder can be asked
  // about.
  if (typeof line !== 'string') throw new Error(`${UNSENDABLE}: malformedFrame`)
  return line
}

/**
 * `frame`, once this module has agreed it is a response it would read back.
 *
 * The builders below hand back the frame rather than the line, because a caller
 * may want to look at what it is about to send; the line is encoded here anyway
 * and discarded, so the frame is checked at the moment it is built as well as at
 * the moment it is written. That the two overlap is deliberate: each is a public
 * entry point, and a lifecycle protocol answers a question a second, so the cost
 * of checking twice is not worth a way in that checks once.
 */
function checkedResponse (frame) {
  encodeResponse(frame)
  return frame
}

/** The answer to a handshake: the protocol is agreed and the process names itself. */
function handshakeAccepted ({ requestId, agentId, state, username }) {
  return checkedResponse({
    op: 'handshake',
    protocol: PROTOCOL_VERSION,
    requestId,
    agentId,
    state,
    username
  })
}

/** The answer to a status request, which is also this protocol's correlated ping. */
function statusReport ({ requestId, agentId, state, uptimeMs, stateMs, requestsHandled }) {
  return checkedResponse({
    op: 'status',
    protocol: PROTOCOL_VERSION,
    requestId,
    agentId,
    state,
    uptimeMs,
    stateMs,
    requestsHandled
  })
}

/** The answer to a shutdown, written before anything is closed. */
function shutdownAccepted ({ requestId, agentId }) {
  return checkedResponse({
    op: 'shutdown',
    protocol: PROTOCOL_VERSION,
    requestId,
    agentId,
    state: 'stopping'
  })
}

/**
 * A refusal.
 *
 * `requestId` is omitted when the frame carried none the process could read, so
 * the field's presence means "this is about that request" rather than "a request
 * happened". An unknown `code` is impossible to send: it is coerced to
 * `malformedFrame` rather than put on the wire, because a caller that reached
 * here with a typo has a bug and a peer should still get a legal frame.
 *
 * An id outside its bound is *not* coerced - it throws, and on the one path where
 * something has already gone wrong. That is the right way round. Neither id here
 * is peer data: `agentId` is the process's own identity and `requestId` is one
 * `decodeRequest` already bounded before echoing it, so either being unusable is
 * a Loom bug rather than a hostile frame, and the alternative to a loud throw is
 * a refusal that quietly carries whatever the caller had - which is how a local
 * path reaches a controller in the frame that exists to carry a single word.
 */
function refusal ({ requestId, agentId, code }) {
  const error = ERROR_CODES.includes(code) ? code : 'malformedFrame'
  const frame = { protocol: PROTOCOL_VERSION }
  if (requestId !== undefined) frame.requestId = requestId
  frame.agentId = agentId
  frame.error = error
  return checkedResponse(frame)
}

module.exports = {
  PROTOCOL_VERSION,
  MAX_FRAME_BYTES,
  MAX_FRAME_DEPTH,
  MAX_ID_CHARS,
  MAX_COUNT,
  MIN_REQUESTS_HANDLED,
  ID_PATTERN,
  USERNAME_PATTERN,
  LIFECYCLE_STATES,
  OPERATIONS,
  ERROR_CODES,
  isControlId,
  isUsername,
  decodeRequest,
  decodeResponse,
  encodeRequest,
  encodeResponse,
  handshakeAccepted,
  statusReport,
  shutdownAccepted,
  refusal
}
