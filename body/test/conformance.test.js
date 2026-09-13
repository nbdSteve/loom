'use strict'

/**
 * The Node half of the body-control contract's conformance suite.
 *
 * `contract/fixtures/body-control` is shared with the JVM, whose own conformance
 * test validates the same files against `contract/body-control.schema.json` and
 * decodes them with its own hand-written bindings. Neither runtime's types are
 * generated from the schema, so the shared corpus is the only thing that keeps
 * the three descriptions of one wire from drifting - exactly as the fixtures do
 * for the perception/decision contract.
 *
 * Every fixture is used, and a fixture this file cannot classify fails the suite
 * rather than being skipped: a case added to the corpus and silently ignored here
 * is worse than no case at all.
 *
 * The corpus has three categories, and which one a frame belongs in is a statement
 * about the schema as well as about the codecs:
 *
 *   valid          the schema admits it and both codecs accept it
 *   invalid        the schema refuses it and both codecs refuse it
 *   beyond-schema  the schema admits it and both codecs refuse it
 *
 * That last category is the relations Draft 2020-12 cannot express - a saturation
 * no larger than the food level, strictly increasing inventory slots - together
 * with the ones it could express as conditionals and where the schema deliberately
 * does not, so that a reader meets one mechanism rather than two. The schema says as
 * much at `remaining`'s own description: "Draft 2020-12 could express that relation
 * as a conditional, and deliberately does not: the two codecs enforce it and the
 * conformance suites assert it, as they do for every other relation this schema
 * states in prose." This is that assertion for the Node codec; the JVM suite adds
 * the other half, that the schema really does admit each of those frames, since
 * `body/` carries no schema validator and a category proved on one side only would
 * let a case that belongs in `invalid` hide here.
 */

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const {
  decodeRequest,
  decodeResponse,
  encodeRequest,
  encodeResponse,
  handshakeAccepted,
  statusReport,
  senseReport,
  eatReport,
  moveReport,
  fleeReport,
  haltReport,
  shutdownAccepted,
  refusal,
  EAT_OUTCOMES,
  MOVE_OUTCOMES,
  THREAT_REFUSALS
} = require('../src/protocol')

const FIXTURES = path.join(__dirname, '..', '..', 'contract', 'fixtures', 'body-control')

/** How many spellings `numeric-spellings.json` states; pinned by both suites. */
const SHARED_NUMERIC_CASES = 46

/** Every category of the corpus. A frame the schema admits is stored in one of these. */
const KINDS = ['valid', 'invalid', 'beyond-schema']

/** Every fixture in `kind`, as `{ name, text }`, sorted so a failure names a stable case. */
function fixtures (kind) {
  const dir = path.join(FIXTURES, kind)
  return fs.readdirSync(dir)
    .filter((name) => name.endsWith('.json'))
    .sort()
    .map((name) => ({ name, text: fs.readFileSync(path.join(dir, name), 'utf8') }))
}

/**
 * Which direction of the wire a fixture describes, from its name.
 *
 * A refusal is a response - it is what the process writes when it will not answer
 * a request - so `error-` frames are decoded as responses.
 */
function directionOf (name) {
  if (name.startsWith('request-')) return 'request'
  if (name.startsWith('response-') || name.startsWith('error-')) return 'response'
  return undefined
}

/**
 * The fixture's own frame, rebuilt through the builder that would have produced it.
 *
 * This is what makes the round-trip below a real check rather than a tautology:
 * `decodeResponse` hands back the object it parsed, so re-encoding that would
 * only prove `JSON.parse` and `JSON.stringify` are inverses. Going back out
 * through the builders proves the *key order the process emits* is the order the
 * fixture is stored in, which is the property the JVM and Rust sides of Loom's
 * other contract depend on for byte-for-byte agreement.
 */
function rebuild (frame) {
  if (frame.error !== undefined) {
    return refusal({ requestId: frame.requestId, agentId: frame.agentId, code: frame.error })
  }
  if (frame.op === 'handshake') return handshakeAccepted(frame)
  if (frame.op === 'status') return statusReport(frame)
  if (frame.op === 'sense') return senseReport(frame)
  if (frame.op === 'eat') return eatReport(frame)
  if (frame.op === 'moveTo') return moveReport(frame)
  if (frame.op === 'flee') return fleeReport(frame)
  if (frame.op === 'halt') return haltReport(frame)
  return shutdownAccepted(frame)
}

test('there is a fixture corpus, and this file classifies all of it', () => {
  for (const kind of KINDS) {
    const found = fixtures(kind)
    assert.ok(found.length > 0, `no ${kind} fixtures found - is the path right?`)
    for (const { name } of found) {
      assert.ok(
        directionOf(name) !== undefined,
        `${name} is named so that no decoder claims it; prefix it request-, response- or error-`
      )
    }
  }
})

test('every fixture is stored canonically: one compact line, no trailing newline', () => {
  // The fixtures are the wire, so how they are stored is part of the contract: a
  // reformatted fixture is a different byte sequence, and the round-trip below is
  // the only thing that would notice.
  for (const { name, text } of KINDS.flatMap((kind) => fixtures(kind))) {
    assert.ok(!text.includes('\n'), `${name} spans more than one line`)
    assert.equal(text, JSON.stringify(JSON.parse(text)), `${name} is not compact, or its keys are reordered`)
  }
})

test('every valid fixture decodes, and re-encodes to the same bytes', () => {
  for (const { name, text } of fixtures('valid')) {
    if (directionOf(name) === 'request') {
      const decoded = decodeRequest(text)
      assert.equal(decoded.ok, true, `${name}: ${decoded.code}`)
      assert.equal(encodeRequest(decoded.request), text, `${name} did not round-trip`)
    } else {
      const decoded = decodeResponse(text)
      assert.equal(decoded.ok, true, `${name}: ${decoded.code}`)
      assert.equal(encodeResponse(rebuild(decoded.response)), text, `${name} did not round-trip`)
    }
  }
})

test('every invalid fixture is refused', () => {
  for (const { name, text } of fixtures('invalid')) {
    const decoded = directionOf(name) === 'request' ? decodeRequest(text) : decodeResponse(text)
    assert.equal(decoded.ok, false, `${name} was accepted`)
  }
})

test('a refused fixture is refused for the reason its name gives', () => {
  // Pinned case by case rather than derived from the names, because the point is
  // that the *specific* refusal is right: a decoder that answered malformedFrame
  // to everything would pass the test above.
  const reasons = {
    'request-unknown-operation.json': 'unknownOperation',
    'request-unsupported-protocol.json': 'unsupportedProtocol',
    'request-missing-protocol.json': 'unsupportedProtocol',
    'request-blank-agent-id.json': 'invalidField',
    'request-blank-request-id.json': 'invalidField',
    'request-request-id-too-long.json': 'invalidField',
    'request-agent-id-with-path.json': 'invalidField',
    'request-extra-field.json': 'invalidField',
    'request-sense-zero-radius.json': 'invalidField',
    'request-sense-radius-too-large.json': 'invalidField',
    'request-sense-missing-limit.json': 'invalidField',
    'request-eat-missing-slot.json': 'invalidField',
    'request-eat-slot-too-large.json': 'invalidField',
    'request-eat-blank-sense-id.json': 'invalidField',
    'request-status-with-sense-payload.json': 'invalidField',
    'response-unknown-state.json': 'invalidField',
    'response-shutdown-not-stopping.json': 'invalidField',
    'response-status-negative-uptime.json': 'invalidField',
    'response-status-missing-requests-handled.json': 'invalidField',
    'response-status-count-too-large.json': 'invalidField',
    'response-status-zero-requests-handled.json': 'invalidField',
    'response-status-fractional-count.json': 'invalidField',
    'response-handshake-short-username.json': 'invalidField',
    'response-sense-not-ready.json': 'invalidField',
    'response-sense-null-ground.json': 'invalidField',
    'response-sense-fractional-food.json': 'invalidField',
    'response-sense-time-past-day.json': 'invalidField',
    'response-sense-position-four-coordinates.json': 'invalidField',
    'response-sense-coordinate-outside-world.json': 'invalidField',
    'response-sense-slot-too-large.json': 'invalidField',
    'response-sense-empty-stack.json': 'invalidField',
    'response-sense-display-name.json': 'invalidField',
    'response-sense-too-many-entities.json': 'invalidField',
    'response-eat-unknown-outcome.json': 'invalidField',
    'request-moveTo-zero-timeout.json': 'invalidField',
    'request-moveTo-timeout-too-large.json': 'invalidField',
    'request-moveTo-tolerance-too-large.json': 'invalidField',
    'request-moveTo-missing-sprint.json': 'invalidField',
    'request-flee-zero-distance.json': 'invalidField',
    'request-flee-distance-too-large.json': 'invalidField',
    'request-flee-entity-too-large.json': 'invalidField',
    'request-flee-blank-sense-id.json': 'invalidField',
    'request-halt-with-move-payload.json': 'invalidField',
    'response-moveTo-unknown-outcome.json': 'invalidField',
    'response-moveTo-negative-travelled.json': 'invalidField',
    'response-moveTo-missing-elapsed.json': 'invalidField',
    'response-flee-negative-separation.json': 'invalidField',
    'response-halt-missing-stopped.json': 'invalidField',
    'error-unknown-code.json': 'invalidField',
    'error-free-text-code.json': 'invalidField',
    'error-missing-agent-id.json': 'invalidField',
    'error-null-request-id.json': 'invalidField'
  }
  const present = fixtures('invalid').map(({ name }) => name)
  assert.deepEqual(Object.keys(reasons).sort(), present, 'a refusal fixture has no expected reason here')

  for (const { name, text } of fixtures('invalid')) {
    const decoded = directionOf(name) === 'request' ? decodeRequest(text) : decodeResponse(text)
    assert.equal(decoded.code, reasons[name], name)
  }
})

test('a frame the schema cannot refuse is still refused by the codec', () => {
  // The `beyond-schema` category, and the whole reason it is a category: each of
  // these frames satisfies every keyword the schema states, and states a *relation*
  // between two of its fields that is false. A codec that read fields one at a time
  // would accept all six, and nothing else in either suite would notice.
  //
  // Keyed by name for the same reason the refusal map above is: the relation each
  // one breaks is named here, so a fixture added to the directory and left out of
  // this list fails rather than passing unread.
  const relations = {
    'response-eat-eaten-without-food.json': 'a meal was eaten and names no food',
    'response-eat-refusal-names-food.json': 'a refused meal names food it did not eat',
    'response-sense-descending-slots.json': 'inventory slots do not ascend',
    'response-sense-omitted-with-no-entities.json': 'entities were omitted from an empty list',
    'response-sense-repeated-slot.json': 'one slot is reported twice',
    'response-sense-saturation-over-food.json': 'saturation is above the food level',
    'response-moveTo-names-threat-refusal.json': 'a walk answers a refusal only a flight can reach',
    'response-flee-refusal-names-separation.json': 'a flight that never found its threat measures separation',
    'response-flee-arrival-without-separation.json': 'a flight that ran measures no separation',
    'response-moveTo-out-of-range-nonzero-travel.json': 'a pre-search refusal claims movement'
  }
  const present = fixtures('beyond-schema').map(({ name }) => name)
  assert.deepEqual(Object.keys(relations).sort(), present, 'a beyond-schema fixture has no stated relation here')

  for (const { name, text } of fixtures('beyond-schema')) {
    const decoded = directionOf(name) === 'request' ? decodeRequest(text) : decodeResponse(text)
    assert.equal(decoded.ok, false, `${name} was accepted, but ${relations[name]}`)
    assert.equal(decoded.code, 'invalidField', name)
  }
})

test('every shared numeric spelling is read the way the schema reads it', () => {
  // The one thing the fixtures cannot state, and the reason it is stated here: a
  // valid fixture must re-encode to its own bytes, and `{"protocol":1.0}` re-encodes
  // to `{"protocol":1}`. So the spellings live in a list of their own beside the
  // fixtures, and the JVM suite sweeps the same bytes - against the real schema
  // validator as well as its own decoder. Each runtime proving itself against its own
  // list is what let two parity defects ship: neither list held a literal the other
  // one disagreed about.
  const { cases } = JSON.parse(fs.readFileSync(path.join(FIXTURES, 'numeric-spellings.json'), 'utf8'))
  // The count is pinned rather than merely nonzero, and both suites pin the same number:
  // a sweep over whatever it finds passes just as happily over a corpus somebody has
  // deleted a case from, which is the one failure a shared list is here to prevent.
  // Adding a case means raising this in `body/test/conformance.test.js` and in
  // `BodyControlConformanceTest`, which is the point - neither runtime can quietly stop
  // reading a spelling the other one still does.
  assert.equal(cases.length, SHARED_NUMERIC_CASES, 'the shared numeric corpus has gained or lost cases')
  for (const { why, direction, field, line, accepted, value } of cases) {
    const decoded = direction === 'request' ? decodeRequest(line) : decodeResponse(line)
    assert.equal(decoded.ok, accepted, `${why}: ${line}`)
    if (accepted) {
      const frame = direction === 'request' ? decoded.request : decoded.response
      // `===` rather than `assert.equal`, which is `Object.is` here and so reads `-0`
      // and `0` as different numbers. They are one number, and the schema's `minimum`
      // admits the spelling, so the corpus states the value it names.
      assert.ok(frame[field] === value, `${why}: ${line} read ${frame[field]}, not ${value}`)
    }
  }
})

test('the corpus covers every operation and both directions of each', () => {
  // A contract slice that pinned only the shapes it found easy to write would
  // leave a whole operation unproven, so the coverage is asserted rather than
  // eyeballed.
  const valid = fixtures('valid').map(({ name }) => name)
  for (const op of ['handshake', 'status', 'sense', 'eat', 'moveTo', 'flee', 'halt', 'shutdown']) {
    assert.ok(valid.includes(`request-${op}.json`), `no valid request fixture for ${op}`)
    assert.ok(
      valid.some((name) => name.startsWith(`response-${op}`)),
      `no valid response fixture for ${op}`
    )
  }
  assert.ok(valid.some((name) => name.startsWith('error-')), 'no valid refusal fixture')
})

test('the corpus states every outcome an eat can have', () => {
  // `EAT_OUTCOMES` is a closed enum, and the refusals in it are the whole answer to
  // "why did this agent not eat". An outcome with no fixture is one the JVM side has
  // never had to decode, which is how a refusal reaches a run as an unreadable frame
  // rather than as the reason it names.
  const stated = fixtures('valid')
    .filter(({ name }) => name.startsWith('response-eat'))
    .map(({ text }) => JSON.parse(text).outcome)
  for (const outcome of EAT_OUTCOMES) {
    assert.ok(stated.includes(outcome), `no valid fixture reports the eat outcome ${outcome}`)
  }
})

test('the corpus states every outcome a movement can have, on the request that can reach it', () => {
  // The same reason the eat sweep above exists, with one difference that is the whole
  // point of doing it in two halves: `MOVE_OUTCOMES` is one enum shared by two requests
  // and neither request can reach all of it. A walk names no threat, so the three threat
  // refusals are unreachable there; a flight always names one, so `outOfRange` is about
  // the retreat it computed rather than a destination a controller gave it. Swept over
  // both directories at once, a corpus stating every outcome only under `flee` would
  // pass while leaving every `moveTo` shape the JVM must decode unproven.
  const outcomesOf = (prefix) => fixtures('valid')
    .filter(({ name }) => name.startsWith(prefix))
    .map(({ text }) => JSON.parse(text).outcome)

  const walked = outcomesOf('response-moveTo')
  const fled = outcomesOf('response-flee')
  for (const outcome of MOVE_OUTCOMES) {
    if (THREAT_REFUSALS.has(outcome)) {
      assert.ok(fled.includes(outcome), `no valid flee fixture reports ${outcome}`)
      assert.ok(!walked.includes(outcome), `a walk fixture reports ${outcome}, which names a threat`)
    } else {
      assert.ok(walked.includes(outcome), `no valid moveTo fixture reports the move outcome ${outcome}`)
    }
  }
})

test('a refusal fixture carries a code and never free text', () => {
  // The security property the closed enum exists for, checked against the corpus
  // rather than only against the encoder: a fixture is what a future runtime will
  // copy its expectations from.
  for (const { name, text } of fixtures('valid').filter(({ name }) => name.startsWith('error-'))) {
    const { error } = JSON.parse(text)
    assert.equal(typeof error, 'string', name)
    assert.ok(/^[a-z][A-Za-z]*$/.test(error), `${name} carries "${error}", which is not a bare code`)
  }
})
