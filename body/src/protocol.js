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

/**
 * The current protocol generation. A frame naming any other is refused, not interpreted.
 *
 * It moved from 4 to 5 when the body-chosen entity, block, and dropped-item response
 * lists widened from 24 to 48 entries. Adding operation names alone would not have
 * moved it; widening a body-chosen answer does.
 */
const PROTOCOL_VERSION = 6

/** Most dropped items one sense may ask to have reported out of sight. */
const MAX_RETAINED_ITEMS = 8

/** An entity uuid as the server spawns it and Mineflayer stores it: lower-case, hyphenated. */
const ENTITY_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

/**
 * Longest frame this protocol will carry, in bytes.
 *
 * The largest legal frame in this generation is a sense report at every one of its
 * own bounds - both ids at their full length, [MAX_SENSE_ENTITIES] entities,
 * [MAX_SENSE_BLOCKS] blocks, [MAX_SENSE_ITEMS] dropped items,
 * [MAX_SENSE_RECIPES] recipes, every inventory slot, every resource name at
 * [MAX_RESOURCE_NAME_CHARS] and every coordinate at its widest. The 32 KiB ceiling
 * carries it with explicit headroom while remaining a small control-plane bound.
 * `protocol.test.js` constructs and measures that exact worst case rather than
 * trusting this arithmetic.
 *
 * It is still eight times tighter than the cognition wire's 256 KiB,
 * which is sized for a whole perception with its prose. That there *is* a largest
 * legal frame is what `MAX_COUNT` buys: an `integer` bounded only from below admits
 * arbitrarily many digits, so before it this margin was a claim about no particular
 * number.
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
const MAX_FRAME_BYTES = 32768

/**
 * Deepest a frame may nest before it is refused unparsed.
 *
 * Not a size bound, and the two ends of this wire need it for different reasons.
 * Within the 32 KiB above a peer can write sixteen thousand open brackets, and what that
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
 * either parser is troubled by. The deepest legal frame is a sense answer at **four**: the
 * top object, its `entities` array, an entity object, and that entity's `position` array.
 * Raising any sense-list bound widens a list rather than nesting it, so the headroom this
 * bound leaves is spent by a *shape* the generation does not have rather than by any list
 * bound growing. So the slack costs nothing and the bound refuses only a line that is
 * asking to be expensive. The JVM binding states the same number for itself, where it is
 * load-bearing.
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
 * Longest a resource name may be, in characters.
 *
 * The longest name vanilla ships is 35 characters, so this admits every one of them
 * and a little room for a modded registry. It is a bound on *arithmetic* as much as
 * on the name: a resource name is one byte per character, so the largest legal frame
 * is a sum rather than a guess - see [MAX_FRAME_BYTES].
 */
const MAX_RESOURCE_NAME_CHARS = 40

/**
 * What a resource name may be shaped like: an optional namespace, then a path of one
 * or more non-empty segments.
 *
 * Lowercase because a Minecraft resource location is lowercase by the game's own rule,
 * so a name outside this set is not a name the registry could have held. Narrow for
 * the reason [ID_PATTERN] is narrow: a name is echoed into a frame and into a log, and
 * a free-text error, a host name or a local path must not be able to arrive through a
 * field that looks like a registry name.
 *
 * The segment structure is what makes that last part true rather than nearly true. A
 * flat character class of the same characters admitted `/tmp/secret`, which is
 * lowercase throughout and so passed every other guard this module has - the one
 * leak-bearing shape left on the wire. A resource location never begins or ends with
 * `/` and never doubles one, so requiring non-empty segments refuses every absolute
 * path while still admitting the nested paths a modded registry can hold. A *relative*
 * lowercase token like `tmp/secret` is still admitted, and honestly so: it is
 * indistinguishable from a legal nested id, exactly as a dotted host name is
 * indistinguishable from a legal [ID_PATTERN] id.
 */
const RESOURCE_NAME_PATTERN = /^([a-z0-9_.-]+:)?[a-z0-9_.-]+(\/[a-z0-9_.-]+)*$/

/**
 * How far from the origin a coordinate may be, in blocks.
 *
 * The world border's own limit. It bounds the value, and a position is a known width - but
 * the width is not this number's: a real's contribution to a frame is the length of its
 * decimal *text*, and where a formatter writes most is nowhere near the border. The
 * coordinate of largest magnitude is nineteen characters and the widest legal one is
 * twenty-five, so the width is measured rather than read off here. `protocol.test.js`
 * derives it, and `MAX_FRAME_BYTES` is what carries it.
 */
const MAX_WORLD_COORDINATE = 30000000

/** How many numbers a position is. Three, in x, y, z order. */
const WORLD_POSITION_AXES = 3

/** Widest a caller may ask a sense to look, in blocks. */
const MAX_SENSE_RADIUS = 48

/**
 * Most world-observation offers of each kind one sense answer may carry.
 *
 * One number rather than three equal ones: it bounds what a caller may ask for and what
 * each entity, block, or dropped-item list may hold. Those are the same bound seen from
 * the two ends - a caller allowed to ask for more than an answer can carry would be
 * promised a cut it could not be told about. What fixes it is [MAX_FRAME_BYTES]: all
 * three lists at this bound beside a full inventory are the widest answer that fits.
 */
const MAX_SENSE_OFFERS_PER_KIND = 48

/** Most entity offers one sense answer may carry. */
const MAX_SENSE_ENTITIES = MAX_SENSE_OFFERS_PER_KIND

/** Most nearby block offers one sense answer may carry. */
const MAX_SENSE_BLOCKS = MAX_SENSE_OFFERS_PER_KIND

/** Most dropped-item offers one sense answer may carry. */
const MAX_SENSE_ITEMS = MAX_SENSE_OFFERS_PER_KIND

/** Most recipe offers one sense answer may carry. */
const MAX_SENSE_RECIPES = 24

/** Highest slot index of a player's own inventory window. */
const MAX_SLOT_INDEX = 45

/**
 * Most stacks one sense answer may carry.
 *
 * How many slots the window has, so a complete inventory always fits and no answer
 * has to leave a stack out. There is deliberately no omission count beside the
 * inventory, and this is what makes that safe.
 */
const MAX_SENSE_STACKS = 46

/**
 * Fewest items a reported stack may hold.
 *
 * One, because an empty slot is left out of the answer rather than reported as a stack
 * of nothing: a slot reported as holding zero would tell a mind it is carrying food it
 * does not have.
 */
const MIN_STACK_COUNT = 1

/**
 * Most items one slot may be reported as holding.
 *
 * One byte's worth rather than vanilla's own stack limit of 64. A server that
 * over-stacks a slot is reported rather than refused, because refusing the frame would
 * blind the body over a count it read correctly.
 */
const MAX_STACK_COUNT = 255

/**
 * Highest hit points a body may report.
 *
 * The game's own ceiling on a max-health attribute rather than vanilla's twenty, so a
 * server that raises it is reported rather than refused.
 */
const MAX_HEALTH = 1024

/** Highest food level and highest saturation, as vanilla counts both. */
const MAX_FOOD = 20

/** The last tick of a day. */
const MAX_TIME_OF_DAY = 23999

/**
 * Widest a caller may say counts as having arrived, in blocks.
 *
 * Half [MAX_RETREAT_DISTANCE], so a tolerance can never be so wide that every block a
 * body could reach satisfies it and a request answers `arrived` without the body having
 * moved at all.
 */
const MAX_MOVE_TOLERANCE = 16

/**
 * Longest a caller may let one movement run, in milliseconds.
 *
 * Below the body process's own control-connection idle window, and the *relation* is
 * what matters rather than either number: a movement is answered on the connection that
 * asked for it and nothing flows on that connection while the body walks, so a deadline
 * at or past the idle window would let the socket's own timer close the channel before
 * the answer could be written. The controller would then see a dropped connection where
 * this contract promises exactly one answer per request. This module may not import
 * `body.js`, which owns the idle window, so each states its own number and a test pins
 * the ordering.
 */
const MAX_MOVE_DEADLINE_MS = 20000

/**
 * Furthest from a named threat a flee may ask the body to get, in blocks.
 *
 * The same number the body's own movement reach is, because a retreat is a walk with the
 * goal inverted and nothing about running away may buy a body a wider reach than walking
 * does. The reach itself is not here: it is measured against the body's live position,
 * which a codec never sees, so it lives where it is enforced and a test pins the two
 * numbers equal.
 */
const MAX_RETREAT_DISTANCE = 32

/**
 * Highest entity index a flee may name.
 *
 * One below [MAX_SENSE_ENTITIES], so every index this codec admits could name an entity
 * of some legal sense answer. An index past the end of the answer that was in fact named
 * is a `threatUnnamed` outcome rather than a malformed field, which is the same division
 * of labour a slot index and `slotUnnamed` already have.
 */
const MAX_ENTITY_INDEX = 23

/** Highest request-local block index an interaction may name. */
const MAX_BLOCK_INDEX = MAX_SENSE_BLOCKS - 1

/** Highest request-local dropped-item index a collection may name. */
const MAX_ITEM_INDEX = MAX_SENSE_ITEMS - 1

/** Highest request-local recipe index a craft may name. */
const MAX_RECIPE_INDEX = MAX_SENSE_RECIPES - 1

/** Most recipe applications one request may ask for. */
const MAX_CRAFTS = 8

/** Most discrete attack packets one request may emit. */
const MAX_ATTACKS = 4

/** Most result items one bounded craft report may confirm. */
const MAX_CRAFTED_ITEMS = MAX_CRAFTS * 64

/** Widest one fixed-position combat approach may travel. */
const MAX_PURSUIT_DISTANCE = 8

/** Longest one native world interaction may remain active. */
const MAX_ACTION_DEADLINE_MS = 20000

/**
 * Widest distance a movement answer may report, in blocks.
 *
 * Derived from the full diagonal between two accepted [WorldPosition] values, plus the
 * final bounded accumulated movement sample. A request may have accumulated the whole
 * 128-block travel allowance immediately before its last physics sample moves between
 * two accepted positions, and the report must still carry that truthful measurement.
 * One ceiling is used for `travelled`, `separation` and [SeparationChange] so the two
 * codecs and every movement report share one generation-2 distance domain.
 */
const MAX_FINAL_BOUNDED_TRAVEL = MAX_RETREAT_DISTANCE * 4
const WORLD_POSITION_DIAMETER = Math.hypot(
  ...Array(WORLD_POSITION_AXES).fill(MAX_WORLD_COORDINATE * 2)
)
const MAX_TRAVEL_DISTANCE = Math.ceil(
  WORLD_POSITION_DIAMETER + MAX_FINAL_BOUNDED_TRAVEL
)

/** The one outcome that consumed something, and so the one that reports what. */
const OUTCOME_EATEN = 'eaten'

/** The one lifecycle state a sense or an eat is answered in. */
const READY = 'ready'

/** The three vitals, in the order the schema declares them. */
const VITALS_FIELDS = ['health', 'food', 'saturation']

/** The world clock's three readings, in declaration order. */
const TIME_FIELDS = ['timeOfDay', 'day', 'isDay']

/** The two blocks a footing is made of, in declaration order. */
const GROUND_FIELDS = ['standingIn', 'standingOn']

/** What one sensed entity carries, in declaration order. */
const SENSED_ENTITY_FIELDS = ['what', 'position', 'visible']

/** What one sensed block carries, in declaration order. */
const SENSED_BLOCK_FIELDS = ['what', 'position', 'visible']

/** What one sensed dropped item carries, in declaration order. */
const SENSED_ITEM_FIELDS = ['what', 'count', 'position', 'visible']

/** What one sensed stack carries, in declaration order. */
const SENSED_STACK_FIELDS = ['slot', 'what', 'count']

/** What one sensed recipe carries, in declaration order. */
const SENSED_RECIPE_FIELDS = ['what', 'resultCount', 'maxCrafts', 'requiresTable']

/** What a sense answer carries beyond the envelope, in declaration order. */
const SENSE_FIELDS = [
  'position',
  'vitals',
  'time',
  'ground',
  'entities',
  'entitiesOmitted',
  'blocks',
  'blocksOmitted',
  'items',
  'itemsOmitted',
  'inventory',
  'recipes',
  'recipesOmitted'
]

/** What an eat answer carries beyond the envelope, in declaration order. */
const EAT_FIELDS = ['outcome', 'vitals', 'what', 'remaining']

/** What a walk answer carries beyond the envelope, in declaration order. */
const MOVE_FIELDS = ['outcome', 'from', 'position', 'travelled', 'elapsedMs']

/**
 * What a flight answer carries beyond the envelope, in declaration order.
 *
 * A walk's fields and then the two a flight alone can measure, which is why they are
 * spelled as an extension rather than as a second list: the shared prefix is what lets a
 * run count arrivals and bounds across walks and flights without translating between two
 * shapes.
 */
const FLEE_FIELDS = [...MOVE_FIELDS, 'separation', 'separationGained']

/** What a dig answer carries beyond the envelope, in declaration order. */
const DIG_FIELDS = ['outcome', 'elapsedMs', 'attempted', 'tool']

/** What a collection answer carries beyond the envelope, in declaration order. */
const COLLECT_FIELDS = [...MOVE_FIELDS, 'what', 'count']

/** What a placement answer carries beyond the envelope, in declaration order. */
const PLACE_FIELDS = ['outcome', 'elapsedMs', 'what', 'position', 'remaining']

/** What an equip answer carries beyond the envelope, in declaration order. */
const EQUIP_FIELDS = ['outcome', 'elapsedMs', 'what', 'count', 'toSlot']

/** What a craft answer carries beyond the envelope, in declaration order. */
const CRAFT_FIELDS = ['outcome', 'elapsedMs', 'attempted', 'what', 'count', 'crafts']

/** What a combat answer carries beyond the envelope, in declaration order. */
const ATTACK_FIELDS = [...MOVE_FIELDS, 'attacks']

/** What a sleep answer carries beyond the envelope, in declaration order. */
const SLEEP_FIELDS = ['outcome', 'elapsedMs', 'entered']

/** What a halt answer carries beyond the envelope, in declaration order. */
const HALT_FIELDS = ['stopped']

/** What a say answer carries beyond the envelope, in declaration order. */
const SAY_FIELDS = ['outcome']

/** The most code points one say may carry; vanilla's chat limit is 256 UTF-16 units. */
const MAX_SAY_CHARS = 128

/** The most UTF-8 bytes one say may carry, which JSON Schema cannot state. */
const MAX_SAY_BYTES = 256

/**
 * One word of say text: no control, DEL, C1, section-sign escape, Unicode space,
 * zero-width, bidi, line-separator, BOM or interlinear annotation character.
 * Mirrors the schema's SayText pattern exactly.
 */
// eslint-disable-next-line no-control-regex -- refusing controls is the point
const SAY_TEXT_PATTERN = /^(?!\/)[^\u0000-\u0020\u007f-\u00a0\u00a7\u061c\u1680\u2000-\u200f\u2028-\u202f\u205f-\u206f\u3000\ufeff\ufff9-\ufffb]+( [^\u0000-\u0020\u007f-\u00a0\u00a7\u061c\u1680\u2000-\u200f\u2028-\u202f\u205f-\u206f\u3000\ufeff\ufff9-\ufffb]+)*$/u

/** An unpaired UTF-16 surrogate, which has no UTF-8 spelling. */
const LONE_SURROGATE = /[\ud800-\udfff]/u

/** The envelope every request shares, before its own payload. */
const REQUEST_ENVELOPE = ['protocol', 'requestId', 'agentId', 'op']

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

/**
 * Every operation a controller may ask for.
 *
 * This list grows and never changes, which is why adding three operations did not move
 * the generation: a controller that does not know an operation will never send it, and a
 * body that does not know one refuses it as `unknownOperation` - which is the answer a
 * newer controller needs anyway. Generation 2 was instead introduced when the body-side
 * [MOVE_OUTCOMES] set grew, because a controller can receive an outcome it did not ask
 * for by name. Renaming or removing an operation, or changing what an existing one
 * carries, would move the generation too.
 */
const OPERATIONS = Object.freeze([
  'handshake',
  'status',
  'shutdown',
  'sense',
  'eat',
  'moveTo',
  'flee',
  'dig',
  'collect',
  'place',
  'equip',
  'craft',
  'attack',
  'sleep',
  'halt',
  'say'
])

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
  'timeout',
  'notReady'
])

/**
 * Every way an eat can end, as a closed set.
 *
 * A refusal is a *reported outcome* rather than an error frame, because all seven of them
 * are answers about the agent's own inventory and reserve rather than about the frame
 * the controller sent - and every one of them is a fact a mind should learn from. They
 * are kept apart for the reason Loom's own eat refusals are: read as one uncountable
 * failure, a run could not tell an agent that had nothing edible from one that ate to a
 * standstill and wasted the rest - which is `notEdible` against `reserveFull`, the pair
 * that reading would collapse.
 */
const EAT_OUTCOMES = Object.freeze([
  'eaten',
  'senseForgotten',
  'slotUnnamed',
  'slotEmpty',
  'slotChanged',
  'notEdible',
  'reserveFull',
  'slotInaccessible'
])

/**
 * Every way a movement can end, as a closed set shared by a walk and a flight.
 *
 * One arrival, six bounds and one cancellation, then three facts about a named threat
 * that only a flight can reach. `noRoute` is the ground's own answer and `stalled` is a
 * route that was found and then abandoned, which are opposite facts about the world;
 * `overTravelled` and `timedOut` are the distance bound and the duration bound, which
 * fail for unrelated reasons and so are never one outcome. `cancelled` is an outcome
 * rather than a refusal, because a halted movement did happen and its displacement is
 * worth reporting.
 *
 * `searchOverBudget` is the sixth bound and is kept apart from `noRoute` for the sharpest
 * reason on this list: one is a fact about the world and the other is a fact about *this
 * process*. The ground refusing a route and Loom refusing to look any longer are opposite
 * things for a mind to do something about, and they were one outcome until a body reported
 * ground it could have walked to as ground that refused it.
 */
const MOVE_OUTCOMES = Object.freeze([
  'arrived',
  'outOfRange',
  'noRoute',
  'searchOverBudget',
  'stalled',
  'overTravelled',
  'timedOut',
  'cancelled',
  'senseForgotten',
  'threatUnnamed',
  'threatGone'
])

/** Every terminal answer to one explicit dig. */
const DIG_OUTCOMES = Object.freeze([
  'dug',
  'senseForgotten',
  'blockUnnamed',
  'blockGone',
  'blockChanged',
  'outOfReach',
  'notVisible',
  'notDiggable',
  'timedOut',
  'cancelled',
  'libraryFailure'
])

/** Every terminal answer to collecting one exact dropped item. */
const COLLECT_OUTCOMES = Object.freeze([
  'collected',
  'senseForgotten',
  'itemUnnamed',
  'itemGone',
  'itemChanged',
  'outOfRange',
  'noRoute',
  'searchOverBudget',
  'stalled',
  'overTravelled',
  'timedOut',
  'cancelled',
  'libraryFailure'
])

/** Every terminal answer to placing one already-held offered stack. */
const PLACE_OUTCOMES = Object.freeze([
  'placed',
  'senseForgotten',
  'blockUnnamed',
  'slotUnnamed',
  'blockGone',
  'blockChanged',
  'slotEmpty',
  'slotChanged',
  'itemNotHeld',
  'notPlaceable',
  'destinationBlocked',
  'outOfReach',
  'notVisible',
  'timedOut',
  'cancelled',
  'libraryFailure'
])

/** Every terminal answer to equipping one offered stack. */
const EQUIP_OUTCOMES = Object.freeze([
  'equipped',
  'senseForgotten',
  'slotUnnamed',
  'slotEmpty',
  'slotChanged',
  'incompatible',
  'timedOut',
  'cancelled',
  'libraryFailure'
])

/** Every terminal answer to one explicitly offered bounded recipe. */
const CRAFT_OUTCOMES = Object.freeze([
  'crafted',
  'senseForgotten',
  'recipeUnnamed',
  'ingredientsChanged',
  'recipeUnavailable',
  'workstationGone',
  'workstationChanged',
  'workstationOutOfReach',
  'workstationNotVisible',
  'timedOut',
  'cancelled',
  'bodyDied',
  'disconnected',
  'libraryFailure'
])

/** Every terminal answer to one exact retained combat target. */
const ATTACK_OUTCOMES = Object.freeze([
  'attacked',
  'targetDied',
  'senseForgotten',
  'targetUnnamed',
  'targetGone',
  'targetChanged',
  'slotUnnamed',
  'slotEmpty',
  'slotChanged',
  'healthBound',
  'outOfRange',
  'noRoute',
  'searchOverBudget',
  'stalled',
  'overTravelled',
  'timedOut',
  'cancelled',
  'bodyDied',
  'disconnected',
  'libraryFailure'
])

/** Every terminal answer to one exact retained bed. */
const SLEEP_OUTCOMES = Object.freeze([
  'woke',
  'senseForgotten',
  'bedUnnamed',
  'bedGone',
  'bedChanged',
  'notBed',
  'wrongDimension',
  'outOfReach',
  'notVisible',
  'occupied',
  'notNight',
  'unsafe',
  'sleepRefused',
  'timedOut',
  'cancelled',
  'bodyDied',
  'disconnected',
  'libraryFailure'
])

/** Every terminal answer to one say. */
const SAY_OUTCOMES = Object.freeze(['sent', 'rateLimited'])

/**
 * Whether `text` is exactly one sendable say line.
 *
 * Nothing is normalised: text that would need trimming, collapsing or escaping is refused,
 * so the accepted text is the text Mineflayer sends and the server observes.
 */
function isSayText (text) {
  if (typeof text !== 'string') return false
  if (LONE_SURROGATE.test(text)) return false
  if ([...text].length > MAX_SAY_CHARS) return false
  if (Buffer.byteLength(text, 'utf8') > MAX_SAY_BYTES) return false
  return SAY_TEXT_PATTERN.test(text)
}

/** Faces a placement request can name. */
const BLOCK_FACES = Object.freeze(['down', 'up', 'north', 'south', 'west', 'east'])

/** Equipment destinations Mineflayer accepts and this wire exposes. */
const EQUIPMENT_DESTINATIONS = Object.freeze([
  'hand',
  'offHand',
  'head',
  'torso',
  'legs',
  'feet'
])

/**
 * The three outcomes that say the body had no threat to measure against.
 *
 * They are the whole of what makes the two movement reports different shapes, and both
 * relations are checked here because neither is expressible in the schema. A walk names
 * no threat, so none of them may answer one; a flight that ends on one carries no
 * separation, because there was nothing to measure from.
 */
const THREAT_REFUSALS = Object.freeze(
  new Set(['senseForgotten', 'threatUnnamed', 'threatGone'])
)

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
 * Whether the one literal at `name` spells a whole number in bounds.
 *
 * The single-literal case of [spellsWholeNumbers], which every integer field of one
 * value is checked with. A missing literal answers false, because every number of a
 * frame this module has already parsed is found by [numberLiterals], so an absent one
 * means the field held something that is not a number at all.
 */
function spellsWholeNumber (literals, name) {
  return spellsWholeNumbers(literals, name, 1)
}

/**
 * Whether `name` holds exactly `expected` literals and every one spells a whole number.
 *
 * The count is part of the check rather than beside it, because a path can hold more
 * than one literal - `inventory.count` is one per stack - and a rule stated only over
 * the literals that happen to be there would pass a frame that carried none. An
 * optional integer field is therefore checked with an `expected` of 0 when it is
 * absent, which says outright that no number was written where none belongs.
 */
function spellsWholeNumbers (literals, name, expected) {
  const written = literals.get(name)
  if (written === undefined) return expected === 0
  if (written.length !== expected) return false
  return written.every(wholeNumberLiteral)
}

/** Whether every literal at `name` is an integer by value, including signed coordinates. */
function spellsIntegralNumbers (literals, name, expected) {
  const written = literals.get(name)
  if (written === undefined) return expected === 0
  if (written.length !== expected) return false
  return written.every(integralLiteral)
}

/** Whether one bounded literal has no fractional value, without imposing a sign. */
function integralLiteral (literal) {
  if (typeof literal !== 'string' || literal.length > MAX_NUMBER_LITERAL_CHARS) return false
  const parts = NUMBER_LITERAL.exec(literal)
  if (parts === null) return false
  const [, , whole, fraction = '', exponent = '0'] = parts
  return isWholeShift(whole + fraction, Number(exponent) - fraction.length)
}

/**
 * Whether the text `literal` spells a whole number within `0..MAX_COUNT`.
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
 * Only an *integer* field needs this. A real one does not, and deliberately gets no
 * literal check at all: the two runtimes round a decimal to the same IEEE 754 double
 * and both answer `Infinity` for an exponent past the double range, so a bound on the
 * parsed value already agrees across the wire where a bound on its spelling would only
 * add a rule one side could hold more strictly than the other.
 */
function wholeNumberLiteral (literal) {
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
 * Every number in `text`, as written, keyed by the member names that lead to it.
 *
 * A frame's numbers have to be read from its bytes, and by the time either decoder
 * holds an object the text of them is gone - so this walks the line the way
 * [namesAMemberTwice] does, skipping string literals rather than trying to
 * recognise a number inside one.
 *
 * It runs only on text `JSON.parse` has already accepted, which is what lets it
 * stay a plain walk: it does not have to recognise malformed JSON, because a frame
 * that is not JSON was refused as `malformedFrame` before this is called.
 *
 * The key is the member names of the enclosing objects joined with a dot, and an array
 * contributes nothing to it - so `time.day` names one literal and `inventory.count`
 * names one per stack, both as a list. Leaving the index out is what keeps this a plain
 * walk, and it costs nothing that matters: every rule stated over these literals is
 * stated over all of them at once ("every count spells a whole number"), and *which*
 * stack a bad spelling was in is a question the parsed object answers. A collision
 * between a nested path and a literal dotted key can only lengthen a list, so it can
 * only cause a refusal and never an acceptance - and such a top-level key is refused as
 * an unknown one anyway.
 *
 * An earlier generation of this collected only the numbers at depth one, on the grounds
 * that no field held an object or an array. Two do now, and a scan that stopped at the
 * top level would have left every nested integer with no spelling check at all - so
 * this side would accept `{"vitals":{"food":19.0000000000000001}}` (which parses to 19)
 * where the JVM reads the raw literal and refuses it, which is exactly the drift the
 * shared fixture corpus exists to catch.
 */
function numberLiterals (text) {
  const literals = new Map()
  const open = []
  let index = 0
  while (index < text.length) {
    const character = text[index]
    if (character === '{' || character === '[') {
      open.push({ object: character === '{', name: undefined })
      index += 1
    } else if (character === '}' || character === ']') {
      open.pop()
      if (open.length > 0) open[open.length - 1].name = undefined
      index += 1
    } else if (character === '"') {
      const after = endOfStringLiteral(text, index)
      if (after === undefined) return literals
      const frame = open[open.length - 1]
      if (frame !== undefined && frame.object && isFollowedByColon(text, after)) {
        frame.name = memberName(text.slice(index, after))
      }
      index = after
    } else if (NUMBER_START.test(character)) {
      const after = endOfNumberLiteral(text, index)
      recordLiteral(literals, open, text.slice(index, after))
      index = after
    } else {
      index += 1
    }
  }
  return literals
}

/**
 * Files `literal` under the path the open frames spell, and closes the innermost name.
 *
 * Closing the name is what keeps one member from claiming the next member's number: a
 * name is set by its own string and cleared by the value that follows it. An array
 * frame holds no name, so the name a list of numbers is filed under survives every one
 * of them.
 */
function recordLiteral (literals, open, literal) {
  let path = ''
  for (const frame of open) {
    if (!frame.object || frame.name === undefined) continue
    path = path === '' ? frame.name : `${path}.${frame.name}`
  }
  const written = literals.get(path)
  if (written === undefined) literals.set(path, [literal])
  else written.push(literal)
  const innermost = open[open.length - 1]
  if (innermost !== undefined) innermost.name = undefined
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

/** Whether `value` is a whole number within an inclusive range. */
function isBoundedInteger (value, least, most) {
  return Number.isSafeInteger(value) && value >= least && value <= most
}

/**
 * Whether `value` is a real number within an inclusive range.
 *
 * Finiteness is what the range buys beyond the schema's own bounds: `1e400` parses to
 * `Infinity` on both runtimes, and a comparison against `Infinity` fails every bound,
 * so a nonsense exponent is refused here without a second rule about its spelling.
 * `NaN` cannot arrive at all, since JSON has no spelling for it.
 */
function isReal (value, least, most) {
  return typeof value === 'number' && Number.isFinite(value) && value >= least && value <= most
}

/** Whether `value` is a resource name of a length and shape a registry could hold. */
function isResourceName (value) {
  return typeof value === 'string' &&
    [...value].length >= 1 &&
    [...value].length <= MAX_RESOURCE_NAME_CHARS &&
    RESOURCE_NAME_PATTERN.test(value)
}

/** Whether `value` is a position: three real numbers inside the world border. */
function isWorldPosition (value) {
  return Array.isArray(value) &&
    value.length === WORLD_POSITION_AXES &&
    value.every((axis) => isReal(axis, -MAX_WORLD_COORDINATE, MAX_WORLD_COORDINATE))
}

/** Whether `value` is one integral block position inside the world border. */
function isBlockPosition (value) {
  return Array.isArray(value) &&
    value.length === WORLD_POSITION_AXES &&
    value.every((axis) => isBoundedInteger(axis, -MAX_WORLD_COORDINATE, MAX_WORLD_COORDINATE))
}

/**
 * Whether `raw` is a coherent set of vitals, with `food` spelled as a whole number.
 *
 * The relation is the part no JSON Schema can state, and it is a real one rather than a
 * tidy one: saturation is the reserve *behind* the food bar, so vanilla holds it at or
 * below the food level and a report claiming more has read one of the two wrongly.
 * Health is deliberately unrelated to either, which is the whole point of carrying
 * three numbers - a body can starve on full health and be beaten to death on a full
 * stomach, and a run that could not tell those apart could not say what ended an agent.
 *
 * `vitals` is a top-level member of both frames that carry it, so the one literal path
 * checked here is right for a sense answer and for an eat answer alike.
 */
function isSensedVitals (raw, literals) {
  return isPlainObject(raw) &&
    isReal(raw.health, 0, MAX_HEALTH) &&
    isBoundedInteger(raw.food, 0, MAX_FOOD) &&
    spellsWholeNumber(literals, 'vitals.food') &&
    isReal(raw.saturation, 0, MAX_FOOD) &&
    raw.saturation <= raw.food &&
    noUnknownKeys(raw, VITALS_FIELDS)
}

/** Whether `raw` is a world clock reading, with both its numbers spelled whole. */
function isSensedTime (raw, literals) {
  return isPlainObject(raw) &&
    isBoundedInteger(raw.timeOfDay, 0, MAX_TIME_OF_DAY) &&
    spellsWholeNumber(literals, 'time.timeOfDay') &&
    isCount(raw.day) &&
    spellsWholeNumber(literals, 'time.day') &&
    typeof raw.isDay === 'boolean' &&
    noUnknownKeys(raw, TIME_FIELDS)
}

/** Whether `raw` names the two blocks a footing is made of. */
function isSensedGround (raw) {
  return isPlainObject(raw) &&
    isResourceName(raw.standingIn) &&
    isResourceName(raw.standingOn) &&
    noUnknownKeys(raw, GROUND_FIELDS)
}

/** Whether `raw` is one sensed entity. */
function isSensedEntity (raw) {
  return isPlainObject(raw) &&
    isResourceName(raw.what) &&
    isWorldPosition(raw.position) &&
    typeof raw.visible === 'boolean' &&
    noUnknownKeys(raw, SENSED_ENTITY_FIELDS)
}

/** Whether `raw` is one request-local nearby block offer. */
function isSensedBlock (raw) {
  return isPlainObject(raw) &&
    isResourceName(raw.what) &&
    isBlockPosition(raw.position) &&
    typeof raw.visible === 'boolean' &&
    noUnknownKeys(raw, SENSED_BLOCK_FIELDS)
}

/** Whether `raw` is one request-local dropped-item offer. */
function isSensedItem (raw) {
  return isPlainObject(raw) &&
    isResourceName(raw.what) &&
    isBoundedInteger(raw.count, MIN_STACK_COUNT, MAX_STACK_COUNT) &&
    isWorldPosition(raw.position) &&
    typeof raw.visible === 'boolean' &&
    noUnknownKeys(raw, SENSED_ITEM_FIELDS)
}

/** Whether `raw` is one occupied slot. */
function isSensedStack (raw) {
  return isPlainObject(raw) &&
    isBoundedInteger(raw.slot, 0, MAX_SLOT_INDEX) &&
    isResourceName(raw.what) &&
    isBoundedInteger(raw.count, MIN_STACK_COUNT, MAX_STACK_COUNT) &&
    noUnknownKeys(raw, SENSED_STACK_FIELDS)
}

/** Whether `raw` is one bounded recipe offer. */
function isSensedRecipe (raw) {
  return isPlainObject(raw) &&
    isResourceName(raw.what) &&
    isBoundedInteger(raw.resultCount, 1, 64) &&
    isBoundedInteger(raw.maxCrafts, 1, MAX_CRAFTS) &&
    typeof raw.requiresTable === 'boolean' &&
    noUnknownKeys(raw, SENSED_RECIPE_FIELDS)
}

/**
 * Whether `raw` is a whole sense answer.
 *
 * Three relations here are beyond what the schema's keywords can say, and each is a
 * fact about the body rather than about the frame.
 *
 * An omission count above zero with no entity reported cannot have happened: a caller
 * may ask for as few as one, so anything left out was left out *after* the limit was
 * filled. Reported alone it would say "there were things here and I am telling you
 * about none of them", which is the one reading a survival run must never be given.
 *
 * The slots are strictly increasing, which is both the slot order the schema's prose
 * asks for and the statement that no slot is described twice. A repeated slot is a body
 * that read its own window wrongly, and an eat naming it would then resolve against
 * whichever description happened to be found first.
 *
 * The entities are ordered nearest first, and that is deliberately *not* checked: the
 * distance the body ordered by is measured from its eyes rather than from the position
 * this wire carries, so a check here would be a second, disagreeing measurement. It is
 * pinned where it is decided instead, in the body's own tests.
 */
function isSenseReport (raw, literals) {
  if (!isWorldPosition(raw.position)) return false
  if (!isSensedVitals(raw.vitals, literals)) return false
  if (!isSensedTime(raw.time, literals)) return false
  if (raw.ground !== undefined && !isSensedGround(raw.ground)) return false
  if (!Array.isArray(raw.entities) || raw.entities.length > MAX_SENSE_ENTITIES) return false
  if (!raw.entities.every(isSensedEntity)) return false
  if (!isCount(raw.entitiesOmitted) || !spellsWholeNumber(literals, 'entitiesOmitted')) return false
  if (raw.entitiesOmitted > 0 && raw.entities.length === 0) return false
  if (!Array.isArray(raw.blocks) || raw.blocks.length > MAX_SENSE_BLOCKS) return false
  if (!raw.blocks.every(isSensedBlock)) return false
  if (!spellsIntegralNumbers(literals, 'blocks.position', raw.blocks.length * WORLD_POSITION_AXES)) return false
  if (!isCount(raw.blocksOmitted) || !spellsWholeNumber(literals, 'blocksOmitted')) return false
  if (raw.blocksOmitted > 0 && raw.blocks.length === 0) return false
  if (!Array.isArray(raw.items) || raw.items.length > MAX_SENSE_ITEMS) return false
  if (!raw.items.every(isSensedItem)) return false
  if (!spellsWholeNumbers(literals, 'items.count', raw.items.length)) return false
  if (!isCount(raw.itemsOmitted) || !spellsWholeNumber(literals, 'itemsOmitted')) return false
  if (raw.itemsOmitted > 0 && raw.items.length === 0) return false
  if (!Array.isArray(raw.inventory) || raw.inventory.length > MAX_SENSE_STACKS) return false
  if (!raw.inventory.every(isSensedStack)) return false
  if (!spellsWholeNumbers(literals, 'inventory.slot', raw.inventory.length)) return false
  if (!spellsWholeNumbers(literals, 'inventory.count', raw.inventory.length)) return false
  if (!raw.inventory.every(
    (stack, at) => at === 0 || stack.slot > raw.inventory[at - 1].slot
  )) return false
  if (!Array.isArray(raw.recipes) || raw.recipes.length > MAX_SENSE_RECIPES) return false
  if (!raw.recipes.every(isSensedRecipe)) return false
  if (!spellsWholeNumbers(literals, 'recipes.resultCount', raw.recipes.length)) return false
  if (!spellsWholeNumbers(literals, 'recipes.maxCrafts', raw.recipes.length)) return false
  if (!isCount(raw.recipesOmitted) || !spellsWholeNumber(literals, 'recipesOmitted')) return false
  return raw.recipesOmitted === 0 || raw.recipes.length > 0
}

/**
 * Whether `raw` is a whole eat answer.
 *
 * `what` and `remaining` are present exactly when something was eaten, and that is one
 * relation rather than two optional fields: every other outcome is a refusal, and a
 * refusal that named a food would be reporting a meal it did not serve. Stating it as
 * an equivalence also refuses the other direction - an `eaten` with nothing to say what
 * it ate is an answer a mind cannot learn anything from, and the accepted-meal record
 * is the only place a run says what an agent consumed.
 */
function isEatReport (raw, literals) {
  if (!EAT_OUTCOMES.includes(raw.outcome)) return false
  if (!isSensedVitals(raw.vitals, literals)) return false
  const ate = raw.outcome === OUTCOME_EATEN
  if (ate !== (raw.what !== undefined)) return false
  if (ate !== (raw.remaining !== undefined)) return false
  if (ate && !isResourceName(raw.what)) return false
  if (ate && !isBoundedInteger(raw.remaining, 0, MAX_STACK_COUNT)) return false
  return spellsWholeNumbers(literals, 'remaining', ate ? 1 : 0)
}

/**
 * Whether `raw` carries the journey every movement answer states, whichever it is.
 *
 * The two positions are stated rather than one displacement, because a caller can
 * subtract them and a body that subtracted for it would be a second measurement able to
 * disagree with the sense answers on either side. `travelled` is the exception and the
 * reason there is one: it is the length of the route walked rather than a straight line,
 * so nothing above this wire could derive it, and it is the number the distance bound was
 * in fact applied to.
 */
function isJourney (raw, literals) {
  return isWorldPosition(raw.from) &&
    isWorldPosition(raw.position) &&
    isReal(raw.travelled, 0, MAX_TRAVEL_DISTANCE) &&
    isCount(raw.elapsedMs) &&
    spellsWholeNumber(literals, 'elapsedMs')
}

/**
 * Whether `raw` is a whole walk answer.
 *
 * The relation is that a walk never answers with a threat refusal, and it is here rather
 * than in the schema on purpose: narrowing the enum for this variant would give the wire
 * two movement outcome sets to keep in step, where one shared set is what lets a run
 * count arrivals and bounds across walks and flights alike. A walk names no threat, so a
 * walk claiming to have lost one has confused itself with a flight.
 */
function isMoveReport (raw, literals) {
  if (!MOVE_OUTCOMES.includes(raw.outcome)) return false
  if (THREAT_REFUSALS.has(raw.outcome)) return false
  if (!isJourney(raw, literals)) return false
  return raw.outcome !== 'outOfRange' || raw.travelled === 0
}

/**
 * Whether `raw` is a whole flight answer.
 *
 * `separation` and `separationGained` are present exactly when the outcome is not a
 * threat refusal, which is one relation rather than two optional fields. Each of the
 * three refusals is the body saying it had no threat to measure against, so a separation
 * beside one would be a distance from nothing; and a flight that did run without saying
 * what it gained is the one answer a mind cannot learn from, since distance covered is
 * not distance gained.
 *
 * `separationGained` is signed, and that is the point of it: a negative value says the
 * threat closed faster than the body ran, which is a flight that failed while every other
 * field says it went somewhere.
 */
function isFleeReport (raw, literals) {
  if (!MOVE_OUTCOMES.includes(raw.outcome)) return false
  if (!isJourney(raw, literals)) return false
  if (raw.outcome === 'outOfRange' && raw.travelled !== 0) return false
  const measured = !THREAT_REFUSALS.has(raw.outcome)
  if (measured !== (raw.separation !== undefined)) return false
  if (measured !== (raw.separationGained !== undefined)) return false
  if (!measured) return true
  return isReal(raw.separation, 0, MAX_TRAVEL_DISTANCE) &&
    isReal(raw.separationGained, -MAX_TRAVEL_DISTANCE, MAX_TRAVEL_DISTANCE)
}

/** Whether `raw` is a whole dig answer. */
function isDigReport (raw, literals) {
  if (!DIG_OUTCOMES.includes(raw.outcome)) return false
  if (!isCount(raw.elapsedMs) || !spellsWholeNumber(literals, 'elapsedMs')) return false
  if (typeof raw.attempted !== 'boolean') return false
  const handedToMineflayer = new Set([
    'dug',
    'timedOut',
    'cancelled',
    'libraryFailure'
  ]).has(raw.outcome)
  // A block can be found gone before Mineflayer is invoked, or be broken by another player while
  // this body is still digging it, from generation 6.
  if (raw.outcome === 'blockGone') {
    if (raw.attempted === false && raw.tool !== undefined) return false
  } else if (raw.attempted !== handedToMineflayer) return false
  if (raw.tool !== undefined && (!raw.attempted || !isResourceName(raw.tool))) return false
  return true
}

/** Whether `raw` is a whole dropped-item collection answer. */
function isCollectReport (raw, literals) {
  if (!COLLECT_OUTCOMES.includes(raw.outcome)) return false
  if (!isJourney(raw, literals)) return false
  if (raw.outcome === 'outOfRange' && raw.travelled !== 0) return false
  const collected = raw.outcome === 'collected'
  if (collected !== (raw.what !== undefined)) return false
  if (collected !== (raw.count !== undefined)) return false
  if (collected && !isResourceName(raw.what)) return false
  if (collected && !isBoundedInteger(raw.count, MIN_STACK_COUNT, MAX_STACK_COUNT)) return false
  return spellsWholeNumbers(literals, 'count', collected ? 1 : 0)
}

/** Whether `raw` is a whole block placement answer. */
function isPlaceReport (raw, literals) {
  if (!PLACE_OUTCOMES.includes(raw.outcome)) return false
  if (!isCount(raw.elapsedMs) || !spellsWholeNumber(literals, 'elapsedMs')) return false
  const placed = raw.outcome === 'placed'
  if (placed !== (raw.what !== undefined)) return false
  if (placed !== (raw.position !== undefined)) return false
  if (placed !== (raw.remaining !== undefined)) return false
  if (placed && !isResourceName(raw.what)) return false
  if (placed && !isBlockPosition(raw.position)) return false
  if (placed && !isBoundedInteger(raw.remaining, 0, MAX_STACK_COUNT)) return false
  if (!spellsIntegralNumbers(literals, 'position', placed ? WORLD_POSITION_AXES : 0)) return false
  return spellsWholeNumbers(literals, 'remaining', placed ? 1 : 0)
}

/** Whether `raw` is a whole equip answer. */
function isEquipReport (raw, literals) {
  if (!EQUIP_OUTCOMES.includes(raw.outcome)) return false
  if (!isCount(raw.elapsedMs) || !spellsWholeNumber(literals, 'elapsedMs')) return false
  const equipped = raw.outcome === 'equipped'
  if (equipped !== (raw.what !== undefined)) return false
  if (equipped !== (raw.count !== undefined)) return false
  if (equipped !== (raw.toSlot !== undefined)) return false
  if (equipped && !isResourceName(raw.what)) return false
  if (equipped && !isBoundedInteger(raw.count, MIN_STACK_COUNT, MAX_STACK_COUNT)) return false
  if (equipped && !isBoundedInteger(raw.toSlot, 0, MAX_SLOT_INDEX)) return false
  if (!spellsWholeNumbers(literals, 'count', equipped ? 1 : 0)) return false
  return spellsWholeNumbers(literals, 'toSlot', equipped ? 1 : 0)
}

/** Whether `raw` is a whole craft answer. */
function isCraftReport (raw, literals) {
  if (!CRAFT_OUTCOMES.includes(raw.outcome)) return false
  if (!isCount(raw.elapsedMs) || !spellsWholeNumber(literals, 'elapsedMs')) return false
  if (typeof raw.attempted !== 'boolean') return false
  if (raw.outcome === 'crafted' && !raw.attempted) return false
  if (new Set(['senseForgotten', 'recipeUnnamed']).has(raw.outcome) && raw.attempted) return false
  const crafted = raw.outcome === 'crafted'
  if (crafted !== (raw.what !== undefined)) return false
  if (crafted !== (raw.count !== undefined)) return false
  if (crafted !== (raw.crafts !== undefined)) return false
  if (crafted && !isResourceName(raw.what)) return false
  if (crafted && !isBoundedInteger(raw.count, 1, MAX_CRAFTED_ITEMS)) return false
  if (crafted && !isBoundedInteger(raw.crafts, 1, MAX_CRAFTS)) return false
  if (!spellsWholeNumbers(literals, 'count', crafted ? 1 : 0)) return false
  return spellsWholeNumbers(literals, 'crafts', crafted ? 1 : 0)
}

/** Whether `raw` is a whole bounded combat answer. */
function isAttackReport (raw, literals) {
  if (!ATTACK_OUTCOMES.includes(raw.outcome)) return false
  if (!isJourney(raw, literals)) return false
  if (!isBoundedInteger(raw.attacks, 0, MAX_ATTACKS)) return false
  if (!spellsWholeNumber(literals, 'attacks')) return false
  if (raw.outcome === 'attacked' && raw.attacks === 0) return false
  if (raw.outcome === 'targetDied' && raw.attacks === 0) return false
  return true
}

/** Whether `raw` is a whole sleep answer. */
function isSleepReport (raw, literals) {
  if (!SLEEP_OUTCOMES.includes(raw.outcome)) return false
  if (!isCount(raw.elapsedMs) || !spellsWholeNumber(literals, 'elapsedMs')) return false
  if (typeof raw.entered !== 'boolean') return false
  if (raw.outcome === 'woke' && !raw.entered) return false
  const beforeEntry = new Set([
    'senseForgotten',
    'bedUnnamed',
    'bedGone',
    'bedChanged',
    'notBed',
    'wrongDimension',
    'outOfReach',
    'notVisible',
    'occupied',
    'notNight',
    'unsafe',
    'sleepRefused'
  ])
  return !beforeEntry.has(raw.outcome) || !raw.entered
}

/**
 * Whether `raw` is a whole halt answer.
 *
 * It carries one boolean and deliberately no position and no measurement: those belong to
 * the request that did the walking, and stating them twice would give a run two accounts
 * of one journey.
 */
function isHaltReport (raw) {
  return typeof raw.stopped === 'boolean'
}

/** Whether `raw` is a whole say answer. */
function isSayReport (raw) {
  return SAY_OUTCOMES.includes(raw.outcome)
}

/**
 * Every answer a `ready` body gives, as its shape check beside the fields it may carry.
 *
 * The pair is what keeps the two from drifting: the fields a decode allows and the fields
 * the check reads are the same entry, so an answer widened on one side without the other
 * either refuses a legal frame or admits an unknown key, rather than doing so on one
 * operation while the rest stay right. The entries here are exactly the operations
 * whose answer only a live, spawned body can give.
 */
const REPORT_SHAPES = Object.freeze({
  sense: { holds: isSenseReport, fields: SENSE_FIELDS },
  eat: { holds: isEatReport, fields: EAT_FIELDS },
  moveTo: { holds: isMoveReport, fields: MOVE_FIELDS },
  flee: { holds: isFleeReport, fields: FLEE_FIELDS },
  dig: { holds: isDigReport, fields: DIG_FIELDS },
  collect: { holds: isCollectReport, fields: COLLECT_FIELDS },
  place: { holds: isPlaceReport, fields: PLACE_FIELDS },
  equip: { holds: isEquipReport, fields: EQUIP_FIELDS },
  craft: { holds: isCraftReport, fields: CRAFT_FIELDS },
  attack: { holds: isAttackReport, fields: ATTACK_FIELDS },
  sleep: { holds: isSleepReport, fields: SLEEP_FIELDS },
  halt: { holds: isHaltReport, fields: HALT_FIELDS },
  say: { holds: isSayReport, fields: SAY_FIELDS }
})

/**
 * The payload `raw` carries beyond the envelope, or undefined when it is not coherent.
 *
 * The keys of what comes back are what the unknown-key check then allows, so a payload
 * and the fields a caller may send cannot drift apart. Every one of these numbers is a
 * top-level member, so none needs the nested literal paths an answer does.
 *
 * A radius or a limit of zero is refused rather than answered with nothing: a caller
 * asking to see no blocks or no entities has not asked a question, and a sense that
 * reported an empty world would look exactly like one taken somewhere empty.
 *
 * A movement's `timeoutMs` of zero is refused for the same shape of reason: a caller
 * allowing no time has not asked the body to go anywhere. A `tolerance` of zero is not,
 * because it is the shape of the goal rather than a bound on effort - it says the body
 * must stand on the named block itself, which is a perfectly reachable request.
 */
function requestPayload (raw, literals) {
  if (raw.op === 'sense') {
    const bounded = isBoundedInteger(raw.radius, 1, MAX_SENSE_RADIUS) &&
      spellsWholeNumber(literals, 'radius') &&
      isBoundedInteger(raw.limit, 1, MAX_SENSE_OFFERS_PER_KIND) &&
      spellsWholeNumber(literals, 'limit')
    if (!bounded) return undefined
    if (raw.retainItems === undefined) return { radius: raw.radius, limit: raw.limit }
    // The controller's own dropped items to report out of sight: unique entity uuids, at most eight.
    const retained = Array.isArray(raw.retainItems) &&
      raw.retainItems.length <= MAX_RETAINED_ITEMS &&
      raw.retainItems.every((uuid) => typeof uuid === 'string' && ENTITY_UUID.test(uuid)) &&
      new Set(raw.retainItems).size === raw.retainItems.length
    return retained ? { radius: raw.radius, limit: raw.limit, retainItems: [...raw.retainItems] } : undefined
  }
  if (raw.op === 'eat') {
    const bounded = isControlId(raw.senseId) &&
      isBoundedInteger(raw.slot, 0, MAX_SLOT_INDEX) &&
      spellsWholeNumber(literals, 'slot')
    return bounded ? { senseId: raw.senseId, slot: raw.slot } : undefined
  }
  if (raw.op === 'moveTo') {
    const bounded = isWorldPosition(raw.destination) &&
      isBoundedInteger(raw.tolerance, 0, MAX_MOVE_TOLERANCE) &&
      spellsWholeNumber(literals, 'tolerance') &&
      isMoveDeadline(raw, literals) &&
      typeof raw.sprint === 'boolean'
    if (!bounded) return undefined
    return {
      destination: raw.destination,
      tolerance: raw.tolerance,
      timeoutMs: raw.timeoutMs,
      sprint: raw.sprint
    }
  }
  if (raw.op === 'flee') {
    const bounded = isControlId(raw.senseId) &&
      isBoundedInteger(raw.entity, 0, MAX_ENTITY_INDEX) &&
      spellsWholeNumber(literals, 'entity') &&
      isBoundedInteger(raw.distance, 1, MAX_RETREAT_DISTANCE) &&
      spellsWholeNumber(literals, 'distance') &&
      isMoveDeadline(raw, literals) &&
      typeof raw.sprint === 'boolean'
    if (!bounded) return undefined
    return {
      senseId: raw.senseId,
      entity: raw.entity,
      distance: raw.distance,
      timeoutMs: raw.timeoutMs,
      sprint: raw.sprint
    }
  }
  if (raw.op === 'dig') {
    const bounded = isControlId(raw.senseId) &&
      isBoundedInteger(raw.block, 0, MAX_BLOCK_INDEX) &&
      spellsWholeNumber(literals, 'block') &&
      isActionDeadline(raw, literals)
    if (!bounded) return undefined
    return { senseId: raw.senseId, block: raw.block, timeoutMs: raw.timeoutMs }
  }
  if (raw.op === 'collect') {
    const bounded = isControlId(raw.senseId) &&
      isBoundedInteger(raw.item, 0, MAX_ITEM_INDEX) &&
      spellsWholeNumber(literals, 'item') &&
      typeof raw.approach === 'boolean' &&
      isActionDeadline(raw, literals) &&
      typeof raw.sprint === 'boolean'
    if (!bounded) return undefined
    return {
      senseId: raw.senseId,
      item: raw.item,
      approach: raw.approach,
      timeoutMs: raw.timeoutMs,
      sprint: raw.sprint
    }
  }
  if (raw.op === 'place') {
    const bounded = isControlId(raw.senseId) &&
      isBoundedInteger(raw.block, 0, MAX_BLOCK_INDEX) &&
      spellsWholeNumber(literals, 'block') &&
      isBoundedInteger(raw.slot, 0, MAX_SLOT_INDEX) &&
      spellsWholeNumber(literals, 'slot') &&
      BLOCK_FACES.includes(raw.face) &&
      isActionDeadline(raw, literals)
    if (!bounded) return undefined
    return {
      senseId: raw.senseId,
      block: raw.block,
      slot: raw.slot,
      face: raw.face,
      timeoutMs: raw.timeoutMs
    }
  }
  if (raw.op === 'equip') {
    const bounded = isControlId(raw.senseId) &&
      isBoundedInteger(raw.slot, 0, MAX_SLOT_INDEX) &&
      spellsWholeNumber(literals, 'slot') &&
      EQUIPMENT_DESTINATIONS.includes(raw.destination) &&
      isActionDeadline(raw, literals)
    if (!bounded) return undefined
    return {
      senseId: raw.senseId,
      slot: raw.slot,
      destination: raw.destination,
      timeoutMs: raw.timeoutMs
    }
  }
  if (raw.op === 'craft') {
    const bounded = isControlId(raw.senseId) &&
      isBoundedInteger(raw.recipe, 0, MAX_RECIPE_INDEX) &&
      spellsWholeNumber(literals, 'recipe') &&
      isBoundedInteger(raw.crafts, 1, MAX_CRAFTS) &&
      spellsWholeNumber(literals, 'crafts') &&
      isActionDeadline(raw, literals)
    if (!bounded) return undefined
    return {
      senseId: raw.senseId,
      recipe: raw.recipe,
      crafts: raw.crafts,
      timeoutMs: raw.timeoutMs
    }
  }
  if (raw.op === 'attack') {
    const bounded = isControlId(raw.senseId) &&
      isBoundedInteger(raw.entity, 0, MAX_ENTITY_INDEX) &&
      spellsWholeNumber(literals, 'entity') &&
      isBoundedInteger(raw.slot, 0, MAX_SLOT_INDEX) &&
      spellsWholeNumber(literals, 'slot') &&
      isBoundedInteger(raw.attacks, 1, MAX_ATTACKS) &&
      spellsWholeNumber(literals, 'attacks') &&
      typeof raw.approach === 'boolean' &&
      isReal(raw.maxPursuitDistance, 0, MAX_PURSUIT_DISTANCE) &&
      isReal(raw.minHealth, 0, MAX_HEALTH) &&
      isActionDeadline(raw, literals) &&
      typeof raw.sprint === 'boolean'
    if (!bounded) return undefined
    return {
      senseId: raw.senseId,
      entity: raw.entity,
      slot: raw.slot,
      attacks: raw.attacks,
      approach: raw.approach,
      maxPursuitDistance: raw.maxPursuitDistance,
      minHealth: raw.minHealth,
      timeoutMs: raw.timeoutMs,
      sprint: raw.sprint
    }
  }
  if (raw.op === 'sleep') {
    const bounded = isControlId(raw.senseId) &&
      isBoundedInteger(raw.block, 0, MAX_BLOCK_INDEX) &&
      spellsWholeNumber(literals, 'block') &&
      isActionDeadline(raw, literals)
    if (!bounded) return undefined
    return { senseId: raw.senseId, block: raw.block, timeoutMs: raw.timeoutMs }
  }
  if (raw.op === 'say') return isSayText(raw.text) ? { text: raw.text } : undefined
  return {}
}

/** Whether `raw` carries a movement deadline a body may be asked to run under. */
function isMoveDeadline (raw, literals) {
  return isBoundedInteger(raw.timeoutMs, 1, MAX_MOVE_DEADLINE_MS) &&
    spellsWholeNumber(literals, 'timeoutMs')
}

/** Whether `raw` carries a native-interaction deadline this body can own. */
function isActionDeadline (raw, literals) {
  return isBoundedInteger(raw.timeoutMs, 1, MAX_ACTION_DEADLINE_MS) &&
    spellsWholeNumber(literals, 'timeoutMs')
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
  const literals = numberLiterals(line)

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
  const payload = requestPayload(raw, literals)
  if (payload === undefined) return { ok: false, code: 'invalidField', requestId }
  // Extra data is refused rather than ignored: a field this generation does not
  // know is a controller believing something the process will not do.
  if (!noUnknownKeys(raw, [...REQUEST_ENVELOPE, ...Object.keys(payload)])) {
    return { ok: false, code: 'invalidField', requestId }
  }
  return {
    ok: true,
    request: {
      op: raw.op,
      protocol: raw.protocol,
      requestId: raw.requestId,
      agentId: raw.agentId,
      ...payload
    }
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
  const literals = numberLiterals(line)
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
  // Every answer below is one only a live, spawned body can give, so the state is fixed
  // at `ready` for all of them: any other state is a `notReady` refusal instead, and an
  // answer claiming to have read a world - or walked one - from a body that is not in one
  // is a lie about where its numbers came from. The state an answer names is the state the
  // request was *admitted under*, which is the same turn for a sense and an earlier one
  // for everything else, most sharply for a movement: see `eatReport` and `moveReport`.
  const reported = REPORT_SHAPES[raw.op]
  if (reported !== undefined) {
    if (raw.state !== READY || !reported.holds(raw, literals)) {
      return { ok: false, code: 'invalidField' }
    }
    return noUnknownKeys(raw, [...envelope, ...reported.fields])
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
  if (frame.op === 'sense') {
    canonical.position = [...frame.position]
    canonical.vitals = canonicalVitals(frame.vitals)
    canonical.time = {
      timeOfDay: frame.time.timeOfDay,
      day: frame.time.day,
      isDay: frame.time.isDay
    }
    if (frame.ground !== undefined) {
      canonical.ground = {
        standingIn: frame.ground.standingIn,
        standingOn: frame.ground.standingOn
      }
    }
    canonical.entities = frame.entities.map((entity) => ({
      what: entity.what,
      position: [...entity.position],
      visible: entity.visible
    }))
    canonical.entitiesOmitted = frame.entitiesOmitted
    canonical.blocks = frame.blocks.map((block) => ({
      what: block.what,
      position: [...block.position],
      visible: block.visible
    }))
    canonical.blocksOmitted = frame.blocksOmitted
    canonical.items = frame.items.map((item) => ({
      what: item.what,
      count: item.count,
      position: [...item.position],
      visible: item.visible
    }))
    canonical.itemsOmitted = frame.itemsOmitted
    canonical.inventory = frame.inventory.map((stack) => ({
      slot: stack.slot,
      what: stack.what,
      count: stack.count
    }))
    canonical.recipes = frame.recipes.map((recipe) => ({
      what: recipe.what,
      resultCount: recipe.resultCount,
      maxCrafts: recipe.maxCrafts,
      requiresTable: recipe.requiresTable
    }))
    canonical.recipesOmitted = frame.recipesOmitted
  }
  if (frame.op === 'eat') {
    canonical.outcome = frame.outcome
    canonical.vitals = canonicalVitals(frame.vitals)
    if (frame.what !== undefined) canonical.what = frame.what
    if (frame.remaining !== undefined) canonical.remaining = frame.remaining
  }
  if (frame.op === 'moveTo' || frame.op === 'flee') {
    canonical.outcome = frame.outcome
    canonical.from = [...frame.from]
    canonical.position = [...frame.position]
    canonical.travelled = frame.travelled
    canonical.elapsedMs = frame.elapsedMs
  }
  if (frame.op === 'flee' && frame.separation !== undefined) {
    canonical.separation = frame.separation
    canonical.separationGained = frame.separationGained
  }
  if (frame.op === 'dig') {
    canonical.outcome = frame.outcome
    canonical.elapsedMs = frame.elapsedMs
    canonical.attempted = frame.attempted
    if (frame.tool !== undefined) canonical.tool = frame.tool
  }
  if (frame.op === 'collect') {
    canonical.outcome = frame.outcome
    canonical.from = [...frame.from]
    canonical.position = [...frame.position]
    canonical.travelled = frame.travelled
    canonical.elapsedMs = frame.elapsedMs
    if (frame.what !== undefined) canonical.what = frame.what
    if (frame.count !== undefined) canonical.count = frame.count
  }
  if (frame.op === 'place') {
    canonical.outcome = frame.outcome
    canonical.elapsedMs = frame.elapsedMs
    if (frame.what !== undefined) canonical.what = frame.what
    if (frame.position !== undefined) canonical.position = [...frame.position]
    if (frame.remaining !== undefined) canonical.remaining = frame.remaining
  }
  if (frame.op === 'equip') {
    canonical.outcome = frame.outcome
    canonical.elapsedMs = frame.elapsedMs
    if (frame.what !== undefined) canonical.what = frame.what
    if (frame.count !== undefined) canonical.count = frame.count
    if (frame.toSlot !== undefined) canonical.toSlot = frame.toSlot
  }
  if (frame.op === 'craft') {
    canonical.outcome = frame.outcome
    canonical.elapsedMs = frame.elapsedMs
    canonical.attempted = frame.attempted
    if (frame.what !== undefined) canonical.what = frame.what
    if (frame.count !== undefined) canonical.count = frame.count
    if (frame.crafts !== undefined) canonical.crafts = frame.crafts
  }
  if (frame.op === 'attack') {
    canonical.outcome = frame.outcome
    canonical.from = [...frame.from]
    canonical.position = [...frame.position]
    canonical.travelled = frame.travelled
    canonical.elapsedMs = frame.elapsedMs
    canonical.attacks = frame.attacks
  }
  if (frame.op === 'sleep') {
    canonical.outcome = frame.outcome
    canonical.elapsedMs = frame.elapsedMs
    canonical.entered = frame.entered
  }
  if (frame.op === 'halt') canonical.stopped = frame.stopped
  if (frame.op === 'say') canonical.outcome = frame.outcome
  return canonical
}

/**
 * One reading of the three vitals, in declaration order.
 *
 * Rebuilt rather than passed through for the reason the whole of [canonicalResponse] is:
 * key order is what the shared fixtures agree on byte-for-byte, and a nested object a
 * peer sent in its own order would carry that order into the line this side writes.
 */
function canonicalVitals (vitals) {
  return { health: vitals.health, food: vitals.food, saturation: vitals.saturation }
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

/**
 * The answer to a sense: what the body read, at the bounds this protocol carries.
 *
 * `ground` is left out when the block the body stands in or on could not be read, which
 * is an ordinary answer rather than a fault: a chunk the client has not been sent yet
 * has no blocks in it, and a body that reported stone it never saw would be inventing
 * evidence. Absent means "not known", and there is deliberately no stand-in value for
 * it - `minecraft:air` is a real answer a real reading gives.
 */
function senseReport ({
  requestId,
  agentId,
  position,
  vitals,
  time,
  ground,
  entities,
  entitiesOmitted,
  blocks = [],
  blocksOmitted = 0,
  items = [],
  itemsOmitted = 0,
  inventory,
  recipes = [],
  recipesOmitted = 0
}) {
  const frame = {
    op: 'sense',
    protocol: PROTOCOL_VERSION,
    requestId,
    agentId,
    state: READY,
    position,
    vitals,
    time
  }
  if (ground !== undefined) frame.ground = ground
  frame.entities = entities
  frame.entitiesOmitted = entitiesOmitted
  frame.blocks = blocks
  frame.blocksOmitted = blocksOmitted
  frame.items = items
  frame.itemsOmitted = itemsOmitted
  frame.inventory = inventory
  frame.recipes = recipes
  frame.recipesOmitted = recipesOmitted
  return checkedResponse(frame)
}

/**
 * The answer to an eat: what became of it, and the vitals of the body that took it on.
 *
 * The vitals are on every outcome, refusals included, because a refusal is still an
 * answer taken at a moment - a mind that asked to eat and was told the slot was empty
 * needs to know how full the agent is as of that answer rather than as of the decision
 * that led to it.
 *
 * **`state` and `vitals` are both the moment the request was admitted, not the moment the
 * frame is written**, and this is the one operation where those differ: a meal is answered
 * after two round trips to the server, so the body can die, disconnect or be handed a
 * nonsense food level in between. `ready` is therefore not re-read here and is not a claim
 * about now - it is the state the body was in when the meal was taken on, which is where
 * every number beside it came from. The caller prefers a fresher vitals reading only while
 * the body is still in that state and the reading can still be stated, and carries the
 * admitted one otherwise; `src/eating.js`'s header owns that rule for both fields at once.
 */
function eatReport ({ requestId, agentId, outcome, vitals, what, remaining }) {
  const frame = {
    op: 'eat',
    protocol: PROTOCOL_VERSION,
    requestId,
    agentId,
    state: READY,
    outcome,
    vitals
  }
  if (what !== undefined) frame.what = what
  if (remaining !== undefined) frame.remaining = remaining
  return checkedResponse(frame)
}

/**
 * The answer to a walk: how it ended, and the journey it made getting there.
 *
 * **`state` is the moment the request was admitted**, as an eat's is and for a sharper
 * reason: a walk is answered when its route ends, which is seconds later, so the body may
 * have died or disconnected in between. `ready` here says the body was live and spawned
 * when it took the walk on, which is where `from` and every number beside it came from.
 *
 * The journey is stated as two positions and a route length rather than as a displacement,
 * because a controller can subtract two positions and cannot reconstruct the walked
 * length from them - and `travelled` is the number the distance bound was applied to.
 */
function moveReport ({ requestId, agentId, outcome, from, position, travelled, elapsedMs }) {
  return checkedResponse({
    op: 'moveTo',
    protocol: PROTOCOL_VERSION,
    requestId,
    agentId,
    state: READY,
    outcome,
    from,
    position,
    travelled,
    elapsedMs
  })
}

/**
 * The answer to a flight: a walk's journey, and what it gained on the named threat.
 *
 * `separation` and `separationGained` are passed together or not at all, which is the
 * shape of the one relation this frame carries: they are absent exactly when the outcome
 * says the body had no threat to measure against. The gain is signed on purpose, so a
 * flight the threat won is reported as the loss it was rather than as the distance the
 * body happened to cover.
 */
function fleeReport ({
  requestId,
  agentId,
  outcome,
  from,
  position,
  travelled,
  elapsedMs,
  separation,
  separationGained
}) {
  const frame = {
    op: 'flee',
    protocol: PROTOCOL_VERSION,
    requestId,
    agentId,
    state: READY,
    outcome,
    from,
    position,
    travelled,
    elapsedMs
  }
  if (separation !== undefined) {
    frame.separation = separation
    frame.separationGained = separationGained
  }
  return checkedResponse(frame)
}

/** The answer to one request to dig one exact offered block. */
function digReport ({ requestId, agentId, outcome, elapsedMs, attempted, tool }) {
  const frame = {
    op: 'dig',
    protocol: PROTOCOL_VERSION,
    requestId,
    agentId,
    state: READY,
    outcome,
    elapsedMs,
    attempted
  }
  if (tool !== undefined) frame.tool = tool
  return checkedResponse(frame)
}

/** The answer to collecting one exact offered dropped item. */
function collectReport ({
  requestId,
  agentId,
  outcome,
  from,
  position,
  travelled,
  elapsedMs,
  what,
  count
}) {
  const frame = {
    op: 'collect',
    protocol: PROTOCOL_VERSION,
    requestId,
    agentId,
    state: READY,
    outcome,
    from,
    position,
    travelled,
    elapsedMs
  }
  if (what !== undefined) frame.what = what
  if (count !== undefined) frame.count = count
  return checkedResponse(frame)
}

/** The answer to placing one exact offered held stack. */
function placeReport ({
  requestId,
  agentId,
  outcome,
  elapsedMs,
  what,
  position,
  remaining
}) {
  const frame = {
    op: 'place',
    protocol: PROTOCOL_VERSION,
    requestId,
    agentId,
    state: READY,
    outcome,
    elapsedMs
  }
  if (what !== undefined) frame.what = what
  if (position !== undefined) frame.position = position
  if (remaining !== undefined) frame.remaining = remaining
  return checkedResponse(frame)
}

/** The answer to equipping one exact offered inventory stack. */
function equipReport ({
  requestId,
  agentId,
  outcome,
  elapsedMs,
  what,
  count,
  toSlot
}) {
  const frame = {
    op: 'equip',
    protocol: PROTOCOL_VERSION,
    requestId,
    agentId,
    state: READY,
    outcome,
    elapsedMs
  }
  if (what !== undefined) frame.what = what
  if (count !== undefined) frame.count = count
  if (toSlot !== undefined) frame.toSlot = toSlot
  return checkedResponse(frame)
}

/** The answer to applying one exact offered recipe. */
function craftReport ({
  requestId,
  agentId,
  outcome,
  elapsedMs,
  attempted,
  what,
  count,
  crafts
}) {
  const frame = {
    op: 'craft',
    protocol: PROTOCOL_VERSION,
    requestId,
    agentId,
    state: READY,
    outcome,
    elapsedMs,
    attempted
  }
  if (what !== undefined) frame.what = what
  if (count !== undefined) frame.count = count
  if (crafts !== undefined) frame.crafts = crafts
  return checkedResponse(frame)
}

/** The answer to one bounded attack request, including its optional approach. */
function attackReport ({
  requestId,
  agentId,
  outcome,
  from,
  position,
  travelled,
  elapsedMs,
  attacks
}) {
  return checkedResponse({
    op: 'attack',
    protocol: PROTOCOL_VERSION,
    requestId,
    agentId,
    state: READY,
    outcome,
    from,
    position,
    travelled,
    elapsedMs,
    attacks
  })
}

/** The answer to one exact offered bed, held through vanilla wake or a bound. */
function sleepReport ({ requestId, agentId, outcome, elapsedMs, entered }) {
  return checkedResponse({
    op: 'sleep',
    protocol: PROTOCOL_VERSION,
    requestId,
    agentId,
    state: READY,
    outcome,
    elapsedMs,
    entered
  })
}

/**
 * The answer to a halt: whether anything cancellable was in fact running.
 *
 * `false` is not a refusal, which is why this is a report rather than an error frame: a
 * controller that halts twice, or halts after a route has already ended, is asking what
 * state the body is in. The request that was stopped is answered on its own request,
 * with that operation's `cancelled` outcome.
 */
function haltReport ({ requestId, agentId, stopped }) {
  return checkedResponse({
    op: 'halt',
    protocol: PROTOCOL_VERSION,
    requestId,
    agentId,
    state: READY,
    stopped
  })
}

/** The answer to one say; it echoes no text. */
function sayReport ({ requestId, agentId, outcome }) {
  return checkedResponse({
    op: 'say',
    protocol: PROTOCOL_VERSION,
    requestId,
    agentId,
    state: READY,
    outcome
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
  MAX_RESOURCE_NAME_CHARS,
  MAX_WORLD_COORDINATE,
  MAX_SENSE_RADIUS,
  MAX_SENSE_OFFERS_PER_KIND,
  MAX_SENSE_ENTITIES,
  MAX_SENSE_BLOCKS,
  MAX_SENSE_ITEMS,
  MAX_SENSE_RECIPES,
  MAX_SLOT_INDEX,
  MAX_SENSE_STACKS,
  MIN_STACK_COUNT,
  MAX_STACK_COUNT,
  MAX_HEALTH,
  MAX_FOOD,
  MAX_TIME_OF_DAY,
  MAX_MOVE_TOLERANCE,
  MAX_MOVE_DEADLINE_MS,
  MAX_ACTION_DEADLINE_MS,
  MAX_RETREAT_DISTANCE,
  MAX_ENTITY_INDEX,
  MAX_BLOCK_INDEX,
  MAX_ITEM_INDEX,
  MAX_RECIPE_INDEX,
  MAX_CRAFTS,
  MAX_ATTACKS,
  MAX_CRAFTED_ITEMS,
  MAX_PURSUIT_DISTANCE,
  MAX_TRAVEL_DISTANCE,
  ID_PATTERN,
  USERNAME_PATTERN,
  RESOURCE_NAME_PATTERN,
  LIFECYCLE_STATES,
  OPERATIONS,
  ERROR_CODES,
  EAT_OUTCOMES,
  MOVE_OUTCOMES,
  DIG_OUTCOMES,
  COLLECT_OUTCOMES,
  PLACE_OUTCOMES,
  EQUIP_OUTCOMES,
  CRAFT_OUTCOMES,
  ATTACK_OUTCOMES,
  SLEEP_OUTCOMES,
  SAY_OUTCOMES,
  MAX_SAY_CHARS,
  MAX_SAY_BYTES,
  isSayText,
  BLOCK_FACES,
  EQUIPMENT_DESTINATIONS,
  THREAT_REFUSALS,
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
  moveReport,
  fleeReport,
  digReport,
  collectReport,
  placeReport,
  equipReport,
  craftReport,
  attackReport,
  sleepReport,
  haltReport,
  sayReport,
  shutdownAccepted,
  refusal
}
