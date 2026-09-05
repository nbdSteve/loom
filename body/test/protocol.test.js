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
  MAX_RESOURCE_NAME_CHARS,
  MAX_WORLD_COORDINATE,
  MAX_SENSE_RADIUS,
  MAX_SENSE_ENTITIES,
  MAX_SLOT_INDEX,
  MAX_SENSE_STACKS,
  MAX_STACK_COUNT,
  MAX_HEALTH,
  MAX_FOOD,
  MAX_TIME_OF_DAY,
  LIFECYCLE_STATES,
  OPERATIONS,
  ERROR_CODES,
  EAT_OUTCOMES,
  isControlId,
  isUsername,
  isResourceName,
  decodeRequest,
  decodeResponse,
  encodeRequest,
  encodeResponse,
  handshakeAccepted,
  statusReport,
  senseReport,
  eatReport,
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

/**
 * The fields each operation requires beyond the envelope.
 *
 * A request is only well-formed for the operation it names, so the helper below
 * has to know this to build one: `sense` and `eat` carry a payload the other
 * three do not, and a test spoiling one field must start from a frame that was
 * otherwise complete.
 */
const PAYLOAD = {
  handshake: {},
  status: {},
  shutdown: {},
  sense: { radius: 24, limit: 16 },
  eat: { senseId: 'sense-1', slot: 0 }
}

/** A request with everything the contract requires, so a test can spoil one field. */
function request (overrides = {}) {
  // `hasOwn` rather than `??`, because a test that spoils the operation with
  // `null` or `42` must get that value on the wire and not the default.
  const op = Object.hasOwn(overrides, 'op') ? overrides.op : 'status'
  return {
    op,
    protocol: PROTOCOL_VERSION,
    requestId: 'req-1',
    agentId: AGENT,
    ...(PAYLOAD[op] ?? {}),
    ...overrides
  }
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
  for (const op of ['perceive', 'moveTo', 'look', 'ping', '', 'HANDSHAKE', 42, null]) {
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
    senseReport({
      requestId: 'req-3',
      agentId: AGENT,
      position: [12.5, 71.5, -40.5],
      vitals: { health: 19.5, food: 17, saturation: 4.25 },
      time: { timeOfDay: 6500, day: 12, isDay: true },
      ground: { standingIn: 'minecraft:air', standingOn: 'minecraft:grass_block' },
      entities: [{ what: 'minecraft:zombie', position: [14.5, 71.5, -40.5], visible: true }],
      entitiesOmitted: 2,
      inventory: [{ slot: 0, what: 'minecraft:bread', count: 3 }]
    }),
    eatReport({
      requestId: 'req-4',
      agentId: AGENT,
      outcome: 'eaten',
      vitals: { health: 19.5, food: 17, saturation: 4.25 },
      what: 'minecraft:bread',
      remaining: 2
    }),
    eatReport({
      requestId: 'req-5',
      agentId: AGENT,
      outcome: 'slotEmpty',
      vitals: { health: 19.5, food: 17, saturation: 4.25 }
    }),
    shutdownAccepted({ requestId: 'req-6', agentId: AGENT }),
    refusal({ requestId: 'req-7', agentId: AGENT, code: 'unknownOperation' }),
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

/**
 * A reading of the two vitals and the third number behind one of them.
 *
 * Every value here is deliberately non-integral. The two runtimes do not spell an
 * integral real the same way - this one writes `20` where Java writes `20.0` - so a
 * frame that round-trips byte-for-byte on both sides can only carry a real whose
 * text both agree on.
 */
function vitals (overrides = {}) {
  return { health: 19.5, food: 17, saturation: 4.25, ...overrides }
}

function whenItIs (overrides = {}) {
  return { timeOfDay: 6500, day: 12, isDay: true, ...overrides }
}

/** The smallest sense report the contract allows, for a test to spoil one field of. */
function sensed (overrides = {}) {
  return {
    requestId: 'req-1',
    agentId: AGENT,
    position: [12.5, 71.5, -40.5],
    vitals: vitals(),
    time: whenItIs(),
    entities: [],
    entitiesOmitted: 0,
    inventory: [],
    ...overrides
  }
}

/**
 * The largest real strictly below `bound`, which is longer to write down than `bound` is.
 *
 * A real's contribution to a frame's size is the length of its decimal text, and that is
 * longer one step inside a round bound than at it: `30000000` is nine characters and the
 * double below it is nineteen. Taking the step as half an epsilon of the bound keeps it
 * arithmetic rather than a copied literal - the same expression is right at every magnitude.
 */
function justInside (bound) {
  return bound - bound * Number.EPSILON / 2
}

/** The smallest real strictly above `bound`, for the bounds a formatter has of its own. */
function justOutside (bound) {
  return bound + bound * Number.EPSILON / 2
}

/**
 * The magnitude at which `Number.prototype.toString` stops writing plain decimals.
 *
 * A formatter's own boundary rather than the contract's, and the one that matters for a
 * frame: just inside it a coordinate is written as `0.` plus five leading zeros plus a full
 * seventeen-digit mantissa, which is the longest plain text this runtime can produce for a
 * legal coordinate. Below it the same double is written with an exponent instead, and more
 * briefly.
 */
const PLAIN_FORM_FLOOR = 1e-6

/**
 * A magnitude small enough that both runtimes write an exponent, and a three-digit one.
 *
 * The other end of the same question: an exponential form carries a whole mantissa, a sign
 * and up to three exponent digits, which is the longest text a legal coordinate has on the
 * JVM even though it is one character shorter than the plain form above is here.
 */
const DEEP_EXPONENT = 1e-300

/** Whichever of `values` is longest to write down, as the value itself. */
function widestWritten (values) {
  return values.reduce((widest, value) => (
    String(value).length > String(widest).length ? value : widest
  ))
}

/**
 * The legal coordinate whose decimal text is longest, which is *not* the largest one.
 *
 * Where a formatter writes most is a property of the formatter, so the answer is taken over
 * its own boundaries rather than named: the world border, the plain-form floor, and a deep
 * exponent. Negative, because a minus sign is a character too and a coordinate may carry one.
 *
 * This is what a worst-case frame has to be measured with. Measured with the coordinate of
 * largest *magnitude*, which reads nine characters shorter, the guard below under-reported
 * the widest legal answer by 225 bytes across the twenty-five positions in it - on the one
 * measurement `MAX_SENSE_ENTITIES` and `MAX_SENSE_STACKS` are derived from.
 */
const WIDEST_COORDINATE = widestWritten([
  -justInside(MAX_WORLD_COORDINATE),
  -justOutside(PLAIN_FORM_FLOOR),
  -justOutside(DEEP_EXPONENT)
])

/** The same magnitude unsigned, since neither health nor saturation may be negative. */
const WIDEST_VITAL = Math.abs(WIDEST_COORDINATE)

/** The smallest eat report the contract allows: a refusal, which carries no item. */
function ate (overrides = {}) {
  return { requestId: 'req-1', agentId: AGENT, outcome: 'slotEmpty', vitals: vitals(), ...overrides }
}

test('a sense report carries a place, two vitals, a clock and two bounded lists', () => {
  const full = sensed({
    ground: { standingIn: 'minecraft:air', standingOn: 'minecraft:grass_block' },
    entities: [
      { what: 'minecraft:zombie', position: [14.5, 71.5, -40.5], visible: true },
      { what: 'minecraft:cow', position: [20.5, 70.5, -44.5], visible: false }
    ],
    entitiesOmitted: 3,
    inventory: [
      { slot: 0, what: 'minecraft:bread', count: 3 },
      { slot: 8, what: 'minecraft:cooked_beef', count: 1 },
      { slot: 45, what: 'minecraft:torch', count: 64 }
    ]
  })
  const decoded = decodeResponse(encodeResponse(senseReport(full)))
  assert.equal(decoded.ok, true, encodeResponse(senseReport(full)))
  assert.deepEqual(decoded.response, senseReport(full))

  // The smallest legal one: nothing in sight, nothing carried, and no ground read.
  assert.equal(decodeResponse(encodeResponse(senseReport(sensed()))).ok, true)
})

test('unreadable ground is absent, and there is no stand-in value for it', () => {
  // `minecraft:air` is a real answer a real reading gives, so it cannot double as
  // "not known" - which is why the field is omitted rather than filled.
  assert.equal(Object.hasOwn(senseReport(sensed()), 'ground'), false)
  const line = encodeResponse(senseReport(sensed()))
  assert.equal(line.includes('ground'), false, line)

  // Half a ground reading is not a reading. Both blocks or neither.
  for (const ground of [{ standingIn: 'minecraft:air' }, { standingOn: 'minecraft:stone' }, {}, null, 'air']) {
    assert.throws(() => senseReport(sensed({ ground })), /invalidField$/, JSON.stringify(ground))
  }
})

test('the two vitals and the saturation behind one of them stay three distinct facts', () => {
  // The whole point of the sense report for a player body: health is damage, food is
  // hunger, and saturation is the buffer in front of food. Nothing derives one from
  // another, so each has its own bound and each is checked on its own.
  assert.equal(decodeResponse(encodeResponse(senseReport(sensed({
    vitals: { health: 0.5, food: 0, saturation: 0 }
  })))).ok, true)
  assert.equal(decodeResponse(encodeResponse(senseReport(sensed({
    vitals: { health: MAX_HEALTH, food: MAX_FOOD, saturation: MAX_FOOD }
  })))).ok, true)

  for (const spoiled of [
    { health: -0.5 },
    { health: MAX_HEALTH + 0.5 },
    { health: Number.NaN },
    { health: '19.5' },
    { health: undefined },
    { food: -1 },
    { food: MAX_FOOD + 1 },
    { food: 17.5 },
    { food: undefined },
    { saturation: -0.5 },
    { saturation: MAX_FOOD + 0.5 },
    { saturation: undefined },
    { extra: 1 }
  ]) {
    const frame = { ...senseReport(sensed()), vitals: { ...vitals(), ...spoiled } }
    assert.deepEqual(decodeResponse(lineOf(frame)), { ok: false, code: 'invalidField' }, JSON.stringify(spoiled))
    assert.throws(() => senseReport(sensed({ vitals: { ...vitals(), ...spoiled } })), /invalidField$/)
  }
})

test('saturation may not exceed the food level it sits in front of', () => {
  // Vanilla's own relation, and one no JSON Schema keyword can state: saturation is
  // spent before food is, so a reading above it is a reading of something that is not
  // this player. Equal is legal - a full meal leaves both at the same number.
  assert.equal(decodeResponse(encodeResponse(senseReport(sensed({
    vitals: { health: 19.5, food: 4, saturation: 4 }
  })))).ok, true)
  const above = { ...senseReport(sensed()), vitals: { health: 19.5, food: 4, saturation: 4.5 } }
  assert.deepEqual(decodeResponse(lineOf(above)), { ok: false, code: 'invalidField' })
  assert.throws(() => senseReport(sensed({ vitals: { health: 19.5, food: 4, saturation: 4.5 } })), /invalidField$/)
})

test('a clock reading is a time of day, a day count and whether it is light', () => {
  for (const timeOfDay of [0, MAX_TIME_OF_DAY]) {
    assert.equal(decodeResponse(encodeResponse(senseReport(sensed({
      time: whenItIs({ timeOfDay })
    })))).ok, true, String(timeOfDay))
  }
  for (const spoiled of [
    { timeOfDay: -1 },
    { timeOfDay: MAX_TIME_OF_DAY + 1 },
    { timeOfDay: 6500.5 },
    { timeOfDay: undefined },
    { day: -1 },
    { day: 12.5 },
    { day: MAX_COUNT + 2 },
    { day: undefined },
    { isDay: 'true' },
    { isDay: 1 },
    { isDay: undefined },
    { extra: 1 }
  ]) {
    const frame = { ...senseReport(sensed()), time: { ...whenItIs(), ...spoiled } }
    assert.deepEqual(decodeResponse(lineOf(frame)), { ok: false, code: 'invalidField' }, JSON.stringify(spoiled))
  }
})

test('a position is three real coordinates inside the world, and never four', () => {
  for (const position of [[0, 0, 0], [MAX_WORLD_COORDINATE, 319.5, -MAX_WORLD_COORDINATE]]) {
    assert.equal(decodeResponse(encodeResponse(senseReport(sensed({ position })))).ok, true, JSON.stringify(position))
  }
  for (const position of [
    [12.5, 71.5],
    [12.5, 71.5, -40.5, 0],
    [],
    [12.5, 71.5, MAX_WORLD_COORDINATE + 1],
    [12.5, 71.5, Number.NaN],
    [12.5, 71.5, Number.POSITIVE_INFINITY],
    [12.5, 71.5, '-40.5'],
    [12.5, 71.5, null],
    { x: 1, y: 2, z: 3 },
    undefined
  ]) {
    assert.throws(() => senseReport(sensed({ position })), /invalidField$/, JSON.stringify(position))
  }
})

test('an observation list is bounded, and every entry is a name, a place and a sight line', () => {
  const one = { what: 'minecraft:zombie', position: [14.5, 71.5, -40.5], visible: true }
  const atBound = Array.from({ length: MAX_SENSE_ENTITIES }, () => one)
  assert.equal(decodeResponse(encodeResponse(senseReport(sensed({ entities: atBound })))).ok, true)
  assert.throws(() => senseReport(sensed({ entities: [...atBound, one] })), /invalidField$/)

  for (const entity of [
    { what: 'minecraft:zombie', position: [14.5, 71.5, -40.5] },
    { what: 'minecraft:zombie', visible: true },
    { position: [14.5, 71.5, -40.5], visible: true },
    { what: 'Minecraft:Zombie', position: [14.5, 71.5, -40.5], visible: true },
    { what: 'minecraft:zombie', position: [14.5, 71.5], visible: true },
    { what: 'minecraft:zombie', position: [14.5, 71.5, -40.5], visible: 1 },
    { what: 'minecraft:zombie', position: [14.5, 71.5, -40.5], visible: true, extra: 1 },
    'minecraft:zombie',
    null
  ]) {
    assert.throws(() => senseReport(sensed({ entities: [entity] })), /invalidField$/, JSON.stringify(entity))
  }
})

test('an omission count is stated, and cannot claim a cut that never happened', () => {
  // A caller may ask for as little as one observation, so anything omitted was
  // omitted *after* the list filled: a count above zero beside an empty list is a
  // report of a cut that could not have been made.
  assert.equal(decodeResponse(encodeResponse(senseReport(sensed({ entitiesOmitted: 0 })))).ok, true)
  assert.throws(() => senseReport(sensed({ entitiesOmitted: 1 })), /invalidField$/)
  assert.equal(decodeResponse(encodeResponse(senseReport(sensed({
    entities: [{ what: 'minecraft:cow', position: [1.5, 2.5, 3.5], visible: true }],
    entitiesOmitted: MAX_COUNT
  })))).ok, true)

  for (const entitiesOmitted of [-1, 1.5, MAX_COUNT + 2, '3', null, undefined]) {
    assert.throws(() => senseReport(sensed({ entitiesOmitted })), /invalidField$/, String(entitiesOmitted))
  }
})

test('the whole inventory fits, and no slot is described twice or out of order', () => {
  // Every slot a player has - 36 main, 4 armour, the off hand, and the crafting
  // grid the container exposes - reaches the wire at once, so a full body is never
  // silently truncated.
  const everySlot = Array.from({ length: MAX_SENSE_STACKS }, (each, slot) => ({
    slot,
    what: 'minecraft:bread',
    count: 1
  }))
  assert.equal(everySlot.length, MAX_SLOT_INDEX + 1)
  assert.equal(decodeResponse(encodeResponse(senseReport(sensed({ inventory: everySlot })))).ok, true)

  // Order is part of the contract, because it is what makes "no slot twice" a rule
  // a reader can check in one pass rather than a set it has to build.
  const outOfOrder = [everySlot[1], everySlot[0]]
  assert.throws(() => senseReport(sensed({ inventory: outOfOrder })), /invalidField$/)
  assert.throws(() => senseReport(sensed({ inventory: [everySlot[0], everySlot[0]] })), /invalidField$/)

  for (const stack of [
    { slot: -1, what: 'minecraft:bread', count: 1 },
    { slot: MAX_SLOT_INDEX + 1, what: 'minecraft:bread', count: 1 },
    { slot: 0.5, what: 'minecraft:bread', count: 1 },
    { slot: 0, what: 'minecraft:bread', count: 0 },
    { slot: 0, what: 'minecraft:bread', count: MAX_STACK_COUNT + 1 },
    { slot: 0, what: 'minecraft:bread', count: 1.5 },
    { slot: 0, what: '', count: 1 },
    { slot: 0, count: 1 },
    { what: 'minecraft:bread', count: 1 },
    { slot: 0, what: 'minecraft:bread' },
    { slot: 0, what: 'minecraft:bread', count: 1, extra: 1 }
  ]) {
    assert.throws(() => senseReport(sensed({ inventory: [stack] })), /invalidField$/, JSON.stringify(stack))
  }
})

test('an empty slot is left out rather than reported as nothing', () => {
  // The one shape the count bound rules out on its own: a slot holding no item is
  // absent from the list, so there is no such thing as a stack of zero and a
  // reader never has to decide what one would mean.
  const line = lineOf({
    ...senseReport(sensed()),
    inventory: [{ slot: 3, what: 'minecraft:bread', count: 0 }]
  })
  assert.deepEqual(decodeResponse(line), { ok: false, code: 'invalidField' })
})

test('an item or block name is the engine\'s own vocabulary and nothing else', () => {
  for (const name of ['minecraft:bread', 'minecraft:grass_block', 'a', 'mod.pack:some/thing-1', 'a'.repeat(MAX_RESOURCE_NAME_CHARS)]) {
    assert.equal(isResourceName(name), true, name)
  }
  for (const name of [
    '',
    'a'.repeat(MAX_RESOURCE_NAME_CHARS + 1),
    'Minecraft:Bread',
    'has space',
    'quote"d',
    'new\nline',
    'C:\\windows',
    ...LEAK_BEARING,
    7,
    null,
    undefined
  ]) {
    assert.equal(isResourceName(name), false, String(name))
  }
})

test('an eat answer states one outcome from the closed set and nothing else', () => {
  for (const outcome of EAT_OUTCOMES) {
    const ok = outcome === 'eaten'
    const frame = eatReport(ate({
      outcome,
      what: ok ? 'minecraft:bread' : undefined,
      remaining: ok ? 2 : undefined
    }))
    const decoded = decodeResponse(encodeResponse(frame))
    assert.equal(decoded.ok, true, outcome)
    assert.deepEqual(decoded.response, frame)
  }
  // The refusals are a closed set, so a reason outside it cannot travel - which is
  // what keeps a path, a host name or an exception string out of this field.
  for (const outcome of ['ok', 'failed', 'EATEN', '', ...LEAK_BEARING, null, undefined, 3]) {
    assert.throws(() => eatReport(ate({ outcome })), /invalidField$/, String(outcome))
  }
  assert.deepEqual(EAT_OUTCOMES.filter((each) => each !== 'eaten').length, 7)
})

test('an eat names what it consumed exactly when it consumed something', () => {
  // The equivalence both ways, because either half alone lies: a refusal that named
  // an item would read as a meal, and a meal that named none would leave the one
  // record of what was eaten empty.
  assert.throws(() => eatReport(ate({ outcome: 'eaten', remaining: 2 })), /invalidField$/)
  assert.throws(() => eatReport(ate({ outcome: 'eaten', what: 'minecraft:bread' })), /invalidField$/)
  assert.throws(() => eatReport(ate({ what: 'minecraft:bread', remaining: 2 })), /invalidField$/)
  assert.throws(() => eatReport(ate({ remaining: 2 })), /invalidField$/)
  assert.throws(() => eatReport(ate({ what: 'minecraft:bread' })), /invalidField$/)

  const eaten = (overrides) => ate({ outcome: 'eaten', what: 'minecraft:bread', remaining: 2, ...overrides })
  assert.equal(decodeResponse(encodeResponse(eatReport(eaten({ remaining: 0 })))).ok, true)
  for (const remaining of [-1, MAX_STACK_COUNT + 1, 2.5, '2', null]) {
    assert.throws(() => eatReport(eaten({ remaining })), /invalidField$/, String(remaining))
  }
  for (const what of ['', 'Minecraft:Bread', 'a'.repeat(MAX_RESOURCE_NAME_CHARS + 1), ...LEAK_BEARING, 7]) {
    assert.throws(() => eatReport(eaten({ what })), /invalidField$/, String(what))
  }
})

test('an eat answer carries the vitals it left behind, on a refusal too', () => {
  // A mind told its slot was empty needs to know what its food level is *now*, not
  // what it was when it decided - so the reading is on every outcome.
  for (const outcome of EAT_OUTCOMES.filter((each) => each !== 'eaten')) {
    assert.throws(() => eatReport(ate({ outcome, vitals: undefined })), /invalidField$/, outcome)
  }
  const decoded = decodeResponse(encodeResponse(eatReport(ate({ outcome: 'notEdible' }))))
  assert.deepEqual(decoded.response.vitals, vitals())
})

test('a sense or an eat answer may only report a body that is ready to be read', () => {
  // Every other lifecycle state is a body with nothing to report: a reading taken
  // before the spawn or after the death would be an answer about a player that is
  // not there. The process refuses such a request as `notReady` instead.
  for (const state of LIFECYCLE_STATES.filter((each) => each !== 'ready')) {
    for (const frame of [senseReport(sensed()), eatReport(ate())]) {
      assert.deepEqual(decodeResponse(lineOf({ ...frame, state })), { ok: false, code: 'invalidField' }, state)
    }
  }
  assert.equal(ERROR_CODES.includes('notReady'), true)
})

test('a sense request is asked with a bounded radius and a bounded limit', () => {
  for (const payload of [{ radius: 1, limit: 1 }, { radius: MAX_SENSE_RADIUS, limit: MAX_SENSE_ENTITIES }]) {
    const decoded = decodeRequest(lineOf(request({ op: 'sense', ...payload })))
    assert.equal(decoded.ok, true, JSON.stringify(payload))
    assert.deepEqual(decoded.request, { op: 'sense', protocol: PROTOCOL_VERSION, requestId: 'req-1', agentId: AGENT, ...payload })
  }
  for (const payload of [
    { radius: 0 },
    { radius: MAX_SENSE_RADIUS + 1 },
    { radius: 24.5 },
    { radius: '24' },
    { radius: undefined },
    { limit: 0 },
    // A caller allowed to ask for more than an answer can carry would be promised a
    // cut it could not be told about, so the two bounds are the one number.
    { limit: MAX_SENSE_ENTITIES + 1 },
    { limit: 16.5 },
    { limit: undefined },
    { extra: 1 }
  ]) {
    const decoded = decodeRequest(lineOf(request({ op: 'sense', ...payload })))
    assert.deepEqual(decoded, { ok: false, code: 'invalidField', requestId: 'req-1' }, JSON.stringify(payload))
  }
})

test('an eat request names one slot of one sense, and cannot be asked without either', () => {
  const decoded = decodeRequest(lineOf(request({ op: 'eat', senseId: 'sense-1', slot: MAX_SLOT_INDEX })))
  assert.deepEqual(decoded.request, {
    op: 'eat',
    protocol: PROTOCOL_VERSION,
    requestId: 'req-1',
    agentId: AGENT,
    senseId: 'sense-1',
    slot: MAX_SLOT_INDEX
  })
  for (const payload of [
    { slot: -1 },
    { slot: MAX_SLOT_INDEX + 1 },
    { slot: 0.5 },
    { slot: '0' },
    { slot: undefined },
    { senseId: '' },
    { senseId: 'has space' },
    { senseId: '/tmp/secret' },
    { senseId: undefined },
    { extra: 1 }
  ]) {
    const spoiled = decodeRequest(lineOf(request({ op: 'eat', ...payload })))
    assert.deepEqual(spoiled, { ok: false, code: 'invalidField', requestId: 'req-1' }, JSON.stringify(payload))
  }
})

test('a payload belongs to the operation that names it and to no other', () => {
  // The bound that makes each operation's field list its own: a sense payload on an
  // eat is a controller believing something this process will not do.
  for (const [op, payload] of [
    ['status', { radius: 24, limit: 16 }],
    ['handshake', { senseId: 'sense-1', slot: 0 }],
    ['shutdown', { radius: 24, limit: 16 }],
    ['sense', { senseId: 'sense-1', slot: 0 }],
    ['eat', { radius: 24, limit: 16 }]
  ]) {
    const line = lineOf({ ...request({ op }), ...payload })
    assert.deepEqual(decodeRequest(line), { ok: false, code: 'invalidField', requestId: 'req-1' }, `${op} ${JSON.stringify(payload)}`)
  }
})

test('a whole number nested inside a frame is read the same however it is spelled', () => {
  // The check used to run at the top level only, which was enough while every whole
  // number was a top-level field. It is not now, and a nested integer left unchecked is
  // one frame the two runtimes read differently off the same bytes:
  // `17.0000000000000001` parses to 17 here and is refused by the JVM's own integral
  // reader - exactly the drift the shared corpus exists to catch.
  //
  // Both directions are driven for every nested field, because they fail apart. A path
  // no scan reaches accepts the fraction; a path the scan files under the wrong name
  // refuses the *legal* spelling too, since a rule that asks for one literal and finds
  // none reads as a field that held no number at all.
  const crowded = senseReport(sensed({
    entities: [{ what: 'minecraft:zombie', position: [14.5, 71.5, -40.5], visible: true }],
    entitiesOmitted: 2,
    inventory: [{ slot: 3, what: 'minecraft:bread', count: 5 }]
  }))
  const meal = eatReport(ate({ outcome: 'eaten', what: 'minecraft:bread', remaining: 2 }))
  const nested = [
    [crowded, 'food', '17'],
    [crowded, 'timeOfDay', '6500'],
    [crowded, 'day', '12'],
    [crowded, 'entitiesOmitted', '2'],
    [crowded, 'slot', '3'],
    [crowded, 'count', '5'],
    [meal, 'remaining', '2']
  ]
  for (const [response, field, written] of nested) {
    const line = encodeResponse(response)
    const at = `"${field}":${written}`
    assert.equal(line.includes(at), true, at)
    // To the schema an integer is any number with a zero fractional part, so these are
    // spellings of the same value and both runtimes read them.
    for (const whole of [`${written}.0`, `${written}e0`]) {
      assert.equal(decodeResponse(line.replace(at, `"${field}":${whole}`)).ok, true, `${field} ${whole}`)
    }
    // And this is a fraction that `JSON.parse` has already rounded away by the time
    // any check on the parsed value could see it.
    const fraction = `${written}.0000000000000001`
    assert.equal(JSON.parse(fraction), Number(written), fraction)
    assert.deepEqual(
      decodeResponse(line.replace(at, `"${field}":${fraction}`)),
      { ok: false, code: 'invalidField' },
      `${field} ${fraction}`
    )
  }
  // A real is deliberately *not* spelling-checked, because both runtimes round the same
  // decimal text to the same double: the value's own range is the whole rule.
  assert.equal(decodeResponse(encodeResponse(crowded).replace('"health":19.5', '"health":1.95e1')).ok, true)
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
      // The nested members are canonicalised too, and each object is rebuilt rather
      // than copied: a caller's key order inside `vitals` or a stack would otherwise
      // reach the wire, and the corpus is compared byte for byte.
      {
        inventory: [{ count: 3, what: 'minecraft:bread', slot: 0 }],
        entitiesOmitted: 2,
        entities: [{ visible: true, position: [14.5, 71.5, -40.5], what: 'minecraft:zombie' }],
        ground: { standingOn: 'minecraft:grass_block', standingIn: 'minecraft:air' },
        time: { isDay: true, day: 12, timeOfDay: 6500 },
        vitals: { saturation: 4.25, food: 17, health: 19.5 },
        position: [12.5, 71.5, -40.5],
        state: 'ready',
        agentId: AGENT,
        requestId: 'req-4',
        protocol: 1,
        op: 'sense'
      },
      `{"op":"sense","protocol":1,"requestId":"req-4","agentId":"${AGENT}","state":"ready",` +
        '"position":[12.5,71.5,-40.5],"vitals":{"health":19.5,"food":17,"saturation":4.25},' +
        '"time":{"timeOfDay":6500,"day":12,"isDay":true},' +
        '"ground":{"standingIn":"minecraft:air","standingOn":"minecraft:grass_block"},' +
        '"entities":[{"what":"minecraft:zombie","position":[14.5,71.5,-40.5],"visible":true}],' +
        '"entitiesOmitted":2,"inventory":[{"slot":0,"what":"minecraft:bread","count":3}]}'
    ],
    [
      {
        remaining: 2,
        what: 'minecraft:bread',
        vitals: { saturation: 4.25, food: 17, health: 19.5 },
        outcome: 'eaten',
        state: 'ready',
        agentId: AGENT,
        requestId: 'req-5',
        protocol: 1,
        op: 'eat'
      },
      `{"op":"eat","protocol":1,"requestId":"req-5","agentId":"${AGENT}","state":"ready",` +
        '"outcome":"eaten","vitals":{"health":19.5,"food":17,"saturation":4.25},' +
        '"what":"minecraft:bread","remaining":2}'
    ],
    [
      { error: 'timeout', agentId: AGENT, requestId: 'req-6', protocol: 1 },
      `{"protocol":1,"requestId":"req-6","agentId":"${AGENT}","error":"timeout"}`
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

test('the longest frame the contract admits is a sense report, and it still fits', () => {
  // This is the frame the byte bound is *for*, and `MAX_SENSE_ENTITIES` and
  // `MAX_SENSE_STACKS` are derived from what fits inside it - so the arithmetic in
  // that comment is measured here rather than trusted. Every string is at its own
  // length bound, every list at its own count bound, and every number spelled as
  // long as the contract lets it be.
  //
  // "As long as the contract lets it be" is the whole of what a real contributes, and that
  // is a longest *text* rather than a largest value - see `WIDEST_COORDINATE`, whose
  // derivation is what this measurement rests on.
  const name = `${'n'.repeat(MAX_RESOURCE_NAME_CHARS - 1)}0`
  const place = [WIDEST_COORDINATE, WIDEST_COORDINATE, WIDEST_COORDINATE]
  assert.ok(
    String(WIDEST_COORDINATE).length > String(-justInside(MAX_WORLD_COORDINATE)).length,
    'the widest coordinate must not be the largest one'
  )
  const widest = (entities) => senseReport({
    requestId: 'r'.repeat(MAX_ID_CHARS),
    agentId: 'a'.repeat(MAX_ID_CHARS),
    position: place,
    vitals: { health: WIDEST_VITAL, food: MAX_FOOD, saturation: WIDEST_VITAL },
    time: { timeOfDay: MAX_TIME_OF_DAY, day: MAX_COUNT, isDay: false },
    ground: { standingIn: name, standingOn: name },
    entities: Array.from({ length: entities }, () => ({
      what: name,
      position: place,
      visible: false
    })),
    entitiesOmitted: MAX_COUNT,
    inventory: Array.from({ length: MAX_SENSE_STACKS }, (each, slot) => ({
      slot,
      what: name,
      count: MAX_STACK_COUNT
    }))
  })
  const sizeOf = (response) => Buffer.byteLength(`${encodeResponse(response)}\n`, 'utf8')
  const bytes = sizeOf(widest(MAX_SENSE_ENTITIES))
  assert.ok(bytes < MAX_FRAME_BYTES, `the longest legal frame is ${bytes} bytes of ${MAX_FRAME_BYTES}`)

  // It is the longest, so it is also the one with the least room to spare, and the
  // margin is stated rather than left to be discovered by a refusal in world. What is
  // left has to be more than one whole entity description, measured rather than
  // estimated: that is the largest thing the frame repeats, so a bound sitting inside
  // one of them is a bound that a later `MAX_SENSE_ENTITIES` of one more would carry
  // past the wire. The margin is a twentieth of the bound and no more, because the
  // two list bounds are requirements - a player has the slots a player has - so it is
  // the byte bound that would have to move, in both runtimes and the schema at once.
  const perEntity = bytes - sizeOf(widest(MAX_SENSE_ENTITIES - 1))
  assert.ok(
    MAX_FRAME_BYTES - bytes > perEntity,
    `${MAX_FRAME_BYTES - bytes} bytes spare of ${MAX_FRAME_BYTES}, against ${perEntity} for one more entity`
  )
  // And it goes back through the decoder, so what was measured is a frame the far
  // end would actually accept rather than merely one this side would write.
  assert.equal(decodeResponse(encodeResponse(widest(MAX_SENSE_ENTITIES))).ok, true)
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
