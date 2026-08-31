'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')

const {
  PROTOCOL_VERSION,
  MAX_FRAME_BYTES,
  MAX_FRAME_DEPTH,
  MAX_ID_CHARS,
  MAX_COUNT,
  MIN_REQUESTS_HANDLED,
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
} = require('../src/protocol')

const AGENT = 'agent-alpha'

/**
 * Strings a frame must never carry in a field that holds an id.
 *
 * A POSIX path, a credential path with a newline in it, a Windows path, a
 * transport error quoting an address, and a bare space: the shapes the protocol's
 * stated error-output guarantee is about. They are swept through every field that
 * holds an id, in both directions, because the closed `error` enum bounds the
 * *reason* on a refusal and says nothing about the ids beside it.
 *
 * What is deliberately *not* here is a bare dotted token like
 * `build-host-07.internal.example`. `ControlId` admits letters, digits, `.`, `_`,
 * `-` and `:`, so a hostname with no separator in it is a legal id and the next
 * test says so outright. That is the honest bound: an id is refused for carrying a
 * slash, backslash, whitespace, quote or newline - the things that make up the
 * path and diagnostic shapes above - and a token indistinguishable from a legitimate
 * agent name cannot be refused for resembling a machine.
 */
const LEAK_BEARING = [
  '/tmp/secret',
  '/Users/alice/.aws/credentials\nTOKEN',
  'C:\\Users\\alice\\secrets.txt',
  'Error: connect ECONNREFUSED 127.0.0.1:25565',
  'has space'
]

/** A request with everything the contract requires, so a test can spoil one field. */
function request (overrides = {}) {
  return { op: 'status', protocol: PROTOCOL_VERSION, requestId: 'req-1', agentId: AGENT, ...overrides }
}

function lineOf (frame) {
  return JSON.stringify(frame)
}

test('a well-formed request decodes to exactly the four contract fields', () => {
  const decoded = decodeRequest(lineOf(request({ op: 'handshake' })))
  assert.deepEqual(decoded, {
    ok: true,
    request: { op: 'handshake', protocol: PROTOCOL_VERSION, requestId: 'req-1', agentId: AGENT }
  })
})

test('every operation this generation has decodes, and nothing else does', () => {
  for (const op of OPERATIONS) {
    assert.equal(decodeRequest(lineOf(request({ op }))).ok, true, op)
  }
  for (const op of ['perceive', 'moveTo', 'eat', 'ping', '', 'HANDSHAKE', 42, null]) {
    const decoded = decodeRequest(lineOf(request({ op })))
    assert.deepEqual(decoded, { ok: false, code: 'unknownOperation', requestId: 'req-1' }, String(op))
  }
})

test('an unsupported protocol is refused before any generation-specific field is interpreted', () => {
  // Every generation-specific field is also wrong here. The generation is what
  // must be reported, because a later generation may spell the rest differently
  // and a field interpreted under the wrong generation is worse than a refusal.
  // requestId is bounded only so that refusal can still be correlated.
  const decoded = decodeRequest(lineOf({ op: 'perceive', protocol: 2, requestId: 'req-1', agentId: '' }))
  assert.deepEqual(decoded, { ok: false, code: 'unsupportedProtocol', requestId: 'req-1' })

  for (const protocol of [0, 2, -1, 1.5, '1', null, undefined]) {
    assert.equal(decodeRequest(lineOf(request({ protocol }))).code, 'unsupportedProtocol', String(protocol))
  }
})

test('a malformed frame is refused as malformed and never as something more specific', () => {
  for (const line of ['', 'nonsense', '{', '[1,2]', '"a string"', '17', 'null', 'true']) {
    assert.deepEqual(decodeRequest(line), { ok: false, code: 'malformedFrame' }, JSON.stringify(line))
  }
})

test('a refusal echoes a readable request id and never invents one', () => {
  // Readable id, refusable frame: the refusal can be correlated.
  assert.equal(decodeRequest(lineOf(request({ protocol: 9 }))).requestId, 'req-1')

  // No readable id: the field is absent rather than blank or a placeholder, so a
  // controller can tell "about that request" from "a request happened".
  for (const requestId of ['', ' ', 'has space', 'a/path', undefined, null, 7]) {
    const decoded = decodeRequest(lineOf(request({ requestId, protocol: 9 })))
    assert.equal(decoded.requestId, undefined, String(requestId))
    assert.equal(Object.hasOwn(decoded, 'requestId'), true, 'the key is present and undefined')
  }
})

test('an id is bounded, and bounded in code points rather than UTF-16 units', () => {
  assert.equal(isControlId('a'.repeat(MAX_ID_CHARS)), true)
  assert.equal(isControlId('a'.repeat(MAX_ID_CHARS + 1)), false)

  // A non-BMP character is two UTF-16 units and one code point. Measuring in units
  // would refuse this at half the documented length, and the JVM's own
  // String.length would do exactly that - which is the drift the schema's
  // maxLength is counted in code points to rule out. It is refused here anyway,
  // because the pattern admits no such character; what is pinned is the count.
  assert.equal([...'\u{1F600}'].length, 1)
  assert.equal('\u{1F600}'.length, 2)
})

test('an id may not carry what would break a frame or a log line', () => {
  for (const id of ['', 'has space', 'a/path', 'C:\\\\windows', 'new\nline', 'quote"d', 'semi;colon', 'a,b']) {
    assert.equal(isControlId(id), false, JSON.stringify(id))
  }
  for (const id of ['agent-alpha', 'loom:world', 'a.b_c-d:1', 'A1']) {
    assert.equal(isControlId(id), true, id)
  }
})

test('a blank or missing id is an invalid field, not a missing frame', () => {
  for (const field of ['requestId', 'agentId']) {
    for (const value of ['', undefined, null, 0, {}, 'two words']) {
      const decoded = decodeRequest(lineOf(request({ [field]: value })))
      assert.equal(decoded.code, 'invalidField', `${field}=${String(value)}`)
    }
  }
})

test('a field the contract forbids is refused rather than ignored', () => {
  // Extra data is a controller believing something this process will not do, so
  // ignoring it would let the two sides disagree silently.
  for (const extra of [{ nonce: 7 }, { payload: {} }, { target: [1, 2, 3] }, { op2: 'eat' }]) {
    const decoded = decodeRequest(lineOf({ ...request(), ...extra }))
    assert.deepEqual(decoded, { ok: false, code: 'invalidField', requestId: 'req-1' }, JSON.stringify(extra))
  }
})

test('a username is Minecraft\'s own rule, since anything else cannot log in', () => {
  for (const name of ['loom_alpha', 'abc', 'a'.repeat(16), 'A_1']) {
    assert.equal(isUsername(name), true, name)
  }
  for (const name of ['ab', 'a'.repeat(17), 'has space', 'dash-ed', '', undefined, 3]) {
    assert.equal(isUsername(name), false, String(name))
  }
})

test('each response the process can write decodes back to itself', () => {
  const frames = [
    handshakeAccepted({ requestId: 'req-1', agentId: AGENT, state: 'loggedIn', username: 'loom_alpha' }),
    statusReport({
      requestId: 'req-2',
      agentId: AGENT,
      state: 'ready',
      uptimeMs: 4210,
      stateMs: 1980,
      requestsHandled: 2
    }),
    shutdownAccepted({ requestId: 'req-3', agentId: AGENT }),
    refusal({ requestId: 'req-4', agentId: AGENT, code: 'unknownOperation' }),
    refusal({ agentId: AGENT, code: 'malformedFrame' })
  ]
  for (const frame of frames) {
    const decoded = decodeResponse(encodeResponse(frame))
    assert.equal(decoded.ok, true, encodeResponse(frame))
    assert.deepEqual(decoded.response, frame)
  }
})

test('every lifecycle state is reportable, and nothing outside the set is', () => {
  for (const state of LIFECYCLE_STATES) {
    const frame = handshakeAccepted({ requestId: 'req-1', agentId: AGENT, state, username: 'loom_alpha' })
    assert.equal(decodeResponse(encodeResponse(frame)).ok, true, state)
  }
  for (const state of ['respawning', 'alive', '', 'READY', null, 1]) {
    const line = lineOf({ ...handshakeAccepted({ requestId: 'req-1', agentId: AGENT, state: 'ready', username: 'loom_alpha' }), state })
    assert.deepEqual(decodeResponse(line), { ok: false, code: 'invalidField' }, String(state))
  }
})

test('a shutdown answer may only report the one state a shutdown reaches', () => {
  for (const state of LIFECYCLE_STATES.filter((each) => each !== 'stopping')) {
    const line = lineOf({ ...shutdownAccepted({ requestId: 'req-1', agentId: AGENT }), state })
    assert.deepEqual(decodeResponse(line), { ok: false, code: 'invalidField' }, state)
  }
})

/** The smallest status report the contract allows, for a test to spoil one field of. */
function status (overrides = {}) {
  return {
    requestId: 'req-1',
    agentId: AGENT,
    state: 'ready',
    uptimeMs: 0,
    stateMs: 0,
    requestsHandled: MIN_REQUESTS_HANDLED,
    ...overrides
  }
}

test('a status report\'s numbers are whole and never negative', () => {
  assert.equal(decodeResponse(encodeResponse(statusReport(status()))).ok, true)

  // Spoiled on the bytes rather than through the builder, because the builder now
  // refuses these outright - which the next test is about. Both matter: the wire
  // must refuse what a peer sends, and the builder must refuse what Loom asks it
  // to send.
  for (const field of ['uptimeMs', 'stateMs', 'requestsHandled']) {
    for (const value of [-1, 1.5, Number.NaN, '4', null, undefined, MAX_COUNT + 2]) {
      const line = lineOf({ ...statusReport(status()), [field]: value })
      assert.equal(decodeResponse(line).code, 'invalidField', `${field}=${String(value)}`)
    }
  }
})

test('a count is bounded above at the largest integer both runtimes can hold', () => {
  // The bound the JVM binding and the schema state as 9007199254740991. Below it
  // the contract admitted a Long the JVM encoded happily and this runtime could
  // not read back, so one frame was legal to one binding and malformed to the
  // other.
  assert.equal(MAX_COUNT, 9007199254740991)
  assert.equal(MAX_COUNT, Number.MAX_SAFE_INTEGER)

  const atBound = status({ uptimeMs: MAX_COUNT, stateMs: MAX_COUNT, requestsHandled: MAX_COUNT })
  assert.equal(decodeResponse(encodeResponse(statusReport(atBound))).ok, true)

  for (const field of ['uptimeMs', 'stateMs', 'requestsHandled']) {
    const line = lineOf({ ...statusReport(status()), [field]: MAX_COUNT + 1 })
    assert.equal(decodeResponse(line).code, 'invalidField', field)
  }
})

test('a status report\'s three numbers must be measurable together', () => {
  // Neither rule can be a JSON Schema keyword - one compares two properties and
  // the other contradicts a prose promise the schema made and did not enforce - so
  // both are the codec's to hold, on the bytes and in the builder alike.
  const backwards = { ...statusReport(status({ uptimeMs: 1, stateMs: 1 })), stateMs: 2 }
  assert.deepEqual(decodeResponse(lineOf(backwards)), { ok: false, code: 'invalidField' })
  assert.throws(() => statusReport(status({ uptimeMs: 1, stateMs: 2 })), /invalidField$/)

  // A status report is itself a request answered, so a report of none has
  // miscounted its own answer.
  const uncounted = { ...statusReport(status()), requestsHandled: 0 }
  assert.deepEqual(decodeResponse(lineOf(uncounted)), { ok: false, code: 'invalidField' })
  assert.throws(() => statusReport(status({ requestsHandled: 0 })), /invalidField$/)

  // Equal is legal: a process that has just started is in its first state for the
  // whole of its life so far.
  assert.equal(decodeResponse(encodeResponse(statusReport(status({ uptimeMs: 7, stateMs: 7 })))).ok, true)
})

test('a builder refuses a frame its own decoder would refuse', () => {
  const refused = [
    // An id that would carry a path, a credential location, a transport error or
    // a newline into a frame - the shapes the protocol's error-output guarantee
    // is about, and the ones the closed `error` enum does nothing to bound.
    ...LEAK_BEARING.flatMap((id) => [
      () => handshakeAccepted({ requestId: id, agentId: AGENT, state: 'ready', username: 'loom_alpha' }),
      () => handshakeAccepted({ requestId: 'req-1', agentId: id, state: 'ready', username: 'loom_alpha' }),
      () => statusReport(status({ agentId: id })),
      () => shutdownAccepted({ requestId: 'req-1', agentId: id }),
      () => refusal({ requestId: 'req-1', agentId: id, code: 'timeout' }),
      () => refusal({ requestId: id, agentId: AGENT, code: 'timeout' })
    ]),
    // An id past its length bound, and one missing altogether.
    () => statusReport(status({ agentId: 'a'.repeat(MAX_ID_CHARS + 1) })),
    () => statusReport(status({ requestId: undefined })),
    () => refusal({ agentId: undefined, code: 'timeout' }),
    // A state or a username outside its set.
    () => handshakeAccepted({ requestId: 'req-1', agentId: AGENT, state: 'respawning', username: 'loom_alpha' }),
    () => handshakeAccepted({ requestId: 'req-1', agentId: AGENT, state: 'ready', username: 'x' }),
    () => statusReport(status({ state: 'respawning' })),
    // A count that is not one.
    () => statusReport(status({ uptimeMs: -1 })),
    () => statusReport(status({ stateMs: 1.5 })),
    () => statusReport(status({ requestsHandled: MAX_COUNT + 2 }))
  ]
  for (const build of refused) {
    assert.throws(build, (thrown) => {
      // The throw is bounded too, or it would be the leak one layer further out:
      // the message names the closed-set code and never the frame it refused.
      assert.match(thrown.message, /^the body-control protocol will not carry this frame: [a-z][A-Za-z]*$/)
      for (const secret of LEAK_BEARING) {
        assert.equal(thrown.message.includes(secret), false, thrown.message)
      }
      return true
    })
  }
})

test('there is no encoder that writes a frame unchecked', () => {
  // The bypass this closes: a bare stringify wrote whatever it was handed, so a
  // caller that skipped a builder put a frame on the wire that this module's own
  // decoder refused.
  assert.throws(() => encodeResponse({ protocol: 1, agentId: '/tmp/secret', error: 'timeout' }), /invalidField$/)
  assert.throws(() => encodeRequest(request({ agentId: '/tmp/secret' })), /invalidField$/)
  assert.throws(() => encodeRequest(request({ op: 'perceive' })), /unknownOperation$/)

  // A value JSON cannot hold at all is malformed, and says only that.
  assert.throws(() => encodeResponse(undefined), /malformedFrame$/)
  assert.throws(() => encodeResponse({ get protocol () { throw new Error('/tmp/secret') } }), (thrown) => {
    assert.equal(thrown.message.includes('/tmp/secret'), false, thrown.message)
    return /malformedFrame$/.test(thrown.message)
  })

  // What it does accept is exactly what its decoder accepts, byte for byte.
  const line = encodeRequest(request())
  assert.equal(line, lineOf(request()))
  assert.equal(decodeRequest(line).ok, true)
})

test('encoders emit canonical bytes regardless of caller key order', () => {
  assert.equal(
    encodeRequest({ agentId: AGENT, requestId: 'req-1', protocol: 1, op: 'status' }),
    `{"op":"status","protocol":1,"requestId":"req-1","agentId":"${AGENT}"}`
  )

  const responses = [
    [
      { username: 'loom_alpha', state: 'loggedIn', agentId: AGENT, requestId: 'req-1', protocol: 1, op: 'handshake' },
      `{"op":"handshake","protocol":1,"requestId":"req-1","agentId":"${AGENT}","state":"loggedIn","username":"loom_alpha"}`
    ],
    [
      { requestsHandled: 2, stateMs: 5, uptimeMs: 10, state: 'ready', agentId: AGENT, requestId: 'req-2', protocol: 1, op: 'status' },
      `{"op":"status","protocol":1,"requestId":"req-2","agentId":"${AGENT}","state":"ready","uptimeMs":10,"stateMs":5,"requestsHandled":2}`
    ],
    [
      { state: 'stopping', agentId: AGENT, requestId: 'req-3', protocol: 1, op: 'shutdown' },
      `{"op":"shutdown","protocol":1,"requestId":"req-3","agentId":"${AGENT}","state":"stopping"}`
    ],
    [
      { error: 'timeout', agentId: AGENT, requestId: 'req-4', protocol: 1 },
      `{"protocol":1,"requestId":"req-4","agentId":"${AGENT}","error":"timeout"}`
    ],
    [
      { error: 'malformedFrame', agentId: AGENT, protocol: 1 },
      `{"protocol":1,"agentId":"${AGENT}","error":"malformedFrame"}`
    ]
  ]
  for (const [frame, canonical] of responses) {
    assert.equal(encodeResponse(frame), canonical)
  }
})

test('a member named twice is refused on the bytes, before anything is parsed', () => {
  // JSON.parse keeps the last value and reports nothing, so after the parse there
  // is nothing left to notice - and the two runtimes do not even lose it the same
  // way: kotlinx-serialization refuses a repeated union discriminator while
  // JSON.parse takes the second one, so one frame meant `handshake` to one reader
  // and `status` to the other.
  const duplicated = [
    '{"op":"handshake","op":"status","protocol":1,"requestId":"req-1","agentId":"agent-alpha"}',
    '{"op":"status","protocol":1,"protocol":1,"requestId":"req-1","agentId":"agent-alpha"}',
    '{"op":"status","protocol":1,"requestId":"req-1","requestId":"req-2","agentId":"agent-alpha"}',
    '{"op":"status","protocol":1,"requestId":"req-1","agentId":"agent-alpha","agentId":"agent-beta"}',
    // Spelled with an escape, which is the same name to a parser: comparing the
    // raw text rather than the resolved name would miss this one.
    '{"\\u006fp":"handshake","op":"status","protocol":1,"requestId":"req-1","agentId":"agent-alpha"}'
  ]
  for (const line of duplicated) {
    assert.deepEqual(decodeRequest(line), { ok: false, code: 'malformedFrame' }, line)
  }

  const responses = [
    '{"op":"handshake","protocol":1,"requestId":"req-1","agentId":"agent-alpha","state":"ready","username":"abc","username":"loom_alpha"}',
    '{"op":"handshake","protocol":1,"requestId":"req-1","agentId":"agent-alpha","state":"dead","state":"ready","username":"loom_alpha"}',
    '{"op":"status","protocol":1,"requestId":"req-1","agentId":"agent-alpha","state":"ready","uptimeMs":10,"uptimeMs":20,"stateMs":5,"requestsHandled":1}',
    '{"op":"status","protocol":1,"requestId":"req-1","agentId":"agent-alpha","state":"ready","uptimeMs":10,"stateMs":5,"stateMs":6,"requestsHandled":1}',
    '{"op":"status","protocol":1,"requestId":"req-1","agentId":"agent-alpha","state":"ready","uptimeMs":10,"stateMs":5,"requestsHandled":1,"requestsHandled":2}',
    '{"op":"shutdown","protocol":1,"requestId":"req-1","requestId":"req-1","agentId":"agent-alpha","state":"stopping"}',
    '{"protocol":1,"agentId":"agent-alpha","error":"timeout","error":"malformedFrame"}'
  ]
  for (const line of responses) {
    assert.deepEqual(decodeResponse(line), { ok: false, code: 'malformedFrame' }, line)
  }

  // And the scanner does not invent one. A brace, a colon or a quote inside a
  // string value is not structure, and a name repeated at a *different* depth is
  // not a duplicate - neither shape can occur in this generation's frames, which
  // is exactly why the scanner has to be right about them rather than lucky.
  assert.equal(decodeRequest('{"op":"status","protocol":1,"requestId":"req-1","agentId":"agent-alpha"}').ok, true)
  const nested = '{"op":"status","protocol":1,"requestId":"req-1","agentId":"agent-alpha","x":{"op":"status"}}'
  assert.deepEqual(decodeRequest(nested), { ok: false, code: 'invalidField', requestId: 'req-1' })
  const quoted = '{"op":"status","protocol":1,"requestId":"req-1","agentId":"agent-alpha","x":"{\\"op\\": 1}"}'
  assert.deepEqual(decodeRequest(quoted), { ok: false, code: 'invalidField', requestId: 'req-1' })
})

test('a refusal carries a code from the closed set and never free text', () => {
  for (const code of ERROR_CODES) {
    assert.equal(refusal({ agentId: AGENT, code }).error, code)
  }
  // The one place a caller's mistake could put text on the wire. It cannot: an
  // unknown code becomes malformedFrame rather than travelling, which is what makes
  // "no exception string, path, machine name or token as a refusal reason"
  // structural rather than a matter of every call site being careful.
  for (const code of [
    'boom',
    'Error: connect ECONNREFUSED 127.0.0.1:25565',
    '/Users/someone/loom/body',
    'some-machine.local',
    undefined,
    null,
    ''
  ]) {
    assert.equal(refusal({ agentId: AGENT, code }).error, 'malformedFrame', String(code))
  }
})

test('a refusal omits the request id it never received', () => {
  assert.equal(Object.hasOwn(refusal({ agentId: AGENT, code: 'timeout' }), 'requestId'), false)
  assert.equal(refusal({ requestId: 'req-1', agentId: AGENT, code: 'timeout' }).requestId, 'req-1')
})

test('a response decoder refuses the frames a request decoder would accept', () => {
  // The two directions are separate shapes. A request is not a response, and a
  // decoder that accepted either would let a controller's own echo look like news.
  assert.equal(decodeResponse(lineOf(request())).ok, false)
})

test('every frame the encoder can produce fits well inside the frame bound', () => {
  // Every field at its own bound at once. The counters are at MAX_COUNT rather
  // than merely large because that is now the largest number the contract admits,
  // which is what makes this the longest legal frame rather than a long one.
  const longest = statusReport({
    requestId: 'r'.repeat(MAX_ID_CHARS),
    agentId: 'a'.repeat(MAX_ID_CHARS),
    state: 'disconnected',
    uptimeMs: MAX_COUNT,
    stateMs: MAX_COUNT,
    requestsHandled: MAX_COUNT
  })
  const bytes = Buffer.byteLength(`${encodeResponse(longest)}\n`, 'utf8')
  assert.ok(bytes < MAX_FRAME_BYTES, `the longest legal frame is ${bytes} bytes`)
  // The margin is the point of the number, so it is measured rather than asserted
  // loosely: a frame bound that a legal frame could approach is a bound that would
  // start refusing legal traffic the first time a field grew.
  assert.ok(bytes * 20 < MAX_FRAME_BYTES, `only ${bytes} bytes of ${MAX_FRAME_BYTES}`)
})

test('a frame past the byte bound is refused by every decoder, unread', () => {
  // The bound above was stated, exported, and enforced by no decoder at all - it was
  // left to the framing layer that would one day read the socket, which is a layer a
  // caller of these functions does not go through. So the check belongs at the
  // ingress, and the code is the contract's own `frameTooLarge` rather than the
  // catch-all: "longer than the wire carries" is a different fact about a peer from
  // "not JSON", and a controller reading a log has to be able to tell them apart.
  const canonical = lineOf(request())
  const room = MAX_FRAME_BYTES - Buffer.byteLength(canonical, 'utf8')

  // Leading whitespace is the padding because JSON admits it between tokens: the line
  // stays legal in every way except its size, which is what makes the refusal about
  // the size and not about a field.
  const atTheBound = ' '.repeat(room) + canonical
  assert.equal(Buffer.byteLength(atTheBound, 'utf8'), MAX_FRAME_BYTES)
  assert.equal(decodeRequest(atTheBound).ok, true, 'a frame at the bound was refused')

  const pastIt = ' '.repeat(room + 1) + canonical
  for (const decode of [decodeRequest, decodeResponse]) {
    assert.deepEqual(decode(pastIt), { ok: false, code: 'frameTooLarge' })
  }

  // Bytes and not characters. Three thousand astral characters are six thousand
  // UTF-16 units - inside the bound to `String.length` - and twelve thousand bytes on
  // the socket, so a runtime counting the shorter number carries twice what it
  // promised, and the two ends of one wire disagree about the one bound they share.
  const astral = canonical.replace('"agent-alpha"', `"${'\u{1F642}'.repeat(3000)}"`)
  assert.ok(astral.length <= MAX_FRAME_BYTES, 'the case must be inside the bound in UTF-16 units')
  assert.ok(Buffer.byteLength(astral, 'utf8') > MAX_FRAME_BYTES, 'the case must be past the bound in bytes')
  assert.deepEqual(decodeRequest(astral), { ok: false, code: 'frameTooLarge' })
})

test('a frame nesting past the depth bound is refused the way the other end refuses it', () => {
  // Four thousand open brackets fit inside 8 KiB with room to spare, so the byte bound
  // does not imply this one. On the JVM that line spends the reader's *stack* -
  // `parseToJsonElement` and the walk for explicit nulls each recurse once per level -
  // and arrives as a StackOverflowError. V8's `JSON.parse` is iterative and reads
  // 200 000 levels without complaint, so this side is not at risk and carries the bound
  // for parity: one wire that refuses the same frames at both ends. Which makes the
  // *code* the thing worth pinning here rather than the mere absence of a crash.
  const deep = '['.repeat(4000) + ']'.repeat(4000)
  assert.ok(Buffer.byteLength(deep, 'utf8') < MAX_FRAME_BYTES, 'the case must fit the byte bound')
  for (const decode of [decodeRequest, decodeResponse]) {
    assert.deepEqual(decode(deep), { ok: false, code: 'malformedFrame' })
  }

  // The case that separates the bound from everything else that would refuse a deep
  // line anyway: an otherwise entirely legal request whose one unknown key nests past
  // the bound. Unbounded, this parses and is refused for the key, with the frame's own
  // requestId echoed back - a frame this end read and the other end could not.
  assert.deepEqual(
    decodeRequest(canonicalWithNesting(MAX_FRAME_DEPTH)),
    { ok: false, code: 'malformedFrame' }
  )

  // A bracket inside a string is a character and not a level, or the bound would be
  // reachable by naming an agent `[[[[[[[[[` - and the id rule, not the depth rule,
  // is what has to refuse that.
  const bracketed = lineOf(request({ agentId: '['.repeat(MAX_ID_CHARS) }))
  assert.deepEqual(decodeRequest(bracketed), { ok: false, code: 'invalidField', requestId: 'req-1' })

  // At the bound the depth rule is not what refuses; the unknown key is. A legal frame
  // here nests once, so the bound is far above anything this generation holds.
  assert.deepEqual(
    decodeRequest(canonicalWithNesting(MAX_FRAME_DEPTH - 1)),
    { ok: false, code: 'invalidField', requestId: 'req-1' }
  )
})

test('a line that is not a string is refused rather than coerced into one', () => {
  // `JSON.parse` takes anything it can stringify, so a caller handing a decoder a
  // number used to get the number parsed and then refused for not being an object -
  // a refusal by accident, one step past the point where a bound could still be
  // measured. Nothing here can be measured in bytes or scanned for a name, so the
  // frame is refused before either is attempted.
  for (const line of [1, null, undefined, {}, [], Buffer.from('{}')]) {
    assert.deepEqual(decodeRequest(line), { ok: false, code: 'malformedFrame' }, String(line))
    assert.deepEqual(decodeResponse(line), { ok: false, code: 'malformedFrame' }, String(line))
  }
})

/** A legal request with one extra key nesting `levels` deep, for the depth cases. */
function canonicalWithNesting (levels) {
  let value = 1
  for (let level = 0; level < levels; level += 1) {
    value = [value]
  }
  return lineOf(request({ x: value }))
}

test('a whole number is read the same however the frame spells it', () => {
  // The other half of the JVM's parity case. To JSON Schema an integer is any
  // number with a zero fractional part, so `1.0` and `1e0` are spellings of `1`
  // and this decoder reads them because `JSON.parse` does. Written as raw text
  // rather than through `lineOf`, since `JSON.stringify` cannot produce them -
  // which is also why no valid fixture can carry one, and why this has to be a
  // codec test on both sides.
  for (const literal of ['1', '1.0', '1.00', '1e0', '1E0', '0.1e1']) {
    const line = `{"op":"handshake","protocol":${literal},"requestId":"req-1","agentId":"${AGENT}"}`
    assert.equal(decodeRequest(line).ok, true, `protocol ${literal}`)
  }

  const counts = `{"op":"status","protocol":1,"requestId":"req-2","agentId":"${AGENT}",` +
    '"state":"ready","uptimeMs":1e3,"stateMs":1.0,"requestsHandled":2.0e0}'
  const report = decodeResponse(counts)
  assert.equal(report.ok, true, report.code)
  assert.equal(report.response.uptimeMs, 1000)
  assert.equal(report.response.stateMs, 1)
  assert.equal(report.response.requestsHandled, 2)
})

test('a fraction is refused however the frame spells it', () => {
  // Accepting the spellings above must not become accepting the values below: a
  // `requestsHandled` that had been rounded is a tally no run could trust.
  for (const literal of ['1.5', '1.5e0', '0.5', '15e-1']) {
    const line = `{"op":"handshake","protocol":${literal},"requestId":"req-1","agentId":"${AGENT}"}`
    assert.equal(decodeRequest(line).code, 'unsupportedProtocol', `protocol ${literal}`)
  }

  const fractional = `{"op":"status","protocol":1,"requestId":"req-2","agentId":"${AGENT}",` +
    '"state":"ready","uptimeMs":10.5,"stateMs":5,"requestsHandled":1}'
  assert.equal(decodeResponse(fractional).code, 'invalidField')

  // The case the value alone cannot catch, and the reason the literal is read at
  // all: `JSON.parse` answers a long literal with the nearest double, so the
  // fraction is gone before any check of the number could run. Every one of these
  // rounds to something a value-only check accepts - the first two to `MAX_COUNT`
  // itself, the last two to exactly `1`, so even a comparison against the protocol
  // version could not see them.
  for (const literal of [`${MAX_COUNT}.4`, `${MAX_COUNT}.5`, '1.0000000000000000001', '0.9999999999999999999']) {
    const line = `{"op":"status","protocol":1,"requestId":"req-2","agentId":"${AGENT}",` +
      `"state":"ready","uptimeMs":${literal},"stateMs":5,"requestsHandled":1}`
    assert.equal(decodeResponse(line).code, 'invalidField', `uptimeMs ${literal}`)
    const asProtocol = `{"op":"handshake","protocol":${literal},"requestId":"req-1","agentId":"${AGENT}"}`
    assert.equal(decodeRequest(asProtocol).code, 'unsupportedProtocol', `protocol ${literal}`)
  }
})

test('a count outside the bound is refused however few characters it takes to write', () => {
  // Three shapes of the same refusal, and only the first of them is a long number.
  // `1e16` is four characters over the bound and `1e2000000000` is twelve
  // characters asking for a two-billion-digit one, so a bound on the literal's
  // length would let both through: the digits are counted from the exponent before
  // any value is built, which is what keeps the refusal cheap.
  const over = [`${MAX_COUNT + 2}`, '9007199254740993', '1e16', '1e400', '1e2000000000', '-1', '-1e0', `-${MAX_COUNT}`]
  for (const literal of over) {
    const line = `{"op":"status","protocol":1,"requestId":"req-2","agentId":"${AGENT}",` +
      `"state":"ready","uptimeMs":${literal},"stateMs":5,"requestsHandled":1}`
    assert.equal(decodeResponse(line).code, 'invalidField', `uptimeMs ${literal}`)
  }

  // A literal longer than the wire will look at is refused on its length alone,
  // before it is measured: the bound exists so that no arithmetic here is ever
  // handed an unbounded string.
  const long = `1.${'0'.repeat(64)}`
  const padded = `{"op":"status","protocol":1,"requestId":"req-2","agentId":"${AGENT}",` +
    `"state":"ready","uptimeMs":${long},"stateMs":5,"requestsHandled":1}`
  assert.equal(decodeResponse(padded).code, 'invalidField', 'a padded whole number is still too long to read')

  // And the largest legal count is still legal, spelled long-hand and short.
  for (const literal of [`${MAX_COUNT}`, '9007199254740991.0', '0', '-0', '20e-1', '9007199254740991e0']) {
    const line = `{"op":"status","protocol":1,"requestId":"req-2","agentId":"${AGENT}",` +
      `"state":"ready","uptimeMs":${MAX_COUNT},"stateMs":${literal},"requestsHandled":1}`
    assert.equal(decodeResponse(line).ok, true, `stateMs ${literal}`)
  }
})
