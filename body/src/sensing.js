'use strict'

/**
 * What one player body can read about where it is standing, and nothing about what any
 * of it means.
 *
 * Every reading here comes straight out of the client library - `bot.entity`,
 * `bot.health`, `bot.food`, `bot.foodSaturation`, `bot.time`, `bot.blockAt`,
 * `bot.world.raycast`, `bot.inventory` - and is reported in the library's own
 * vocabulary. There is no second entity index, no inventory model of this module's own
 * and no hunger arithmetic: this file reads and bounds, and the coordinator above the
 * wire decides what a reading is worth. That is why nothing here names a threat, a
 * hostile, a food or a phase of the day.
 *
 * **The one rule is that a reading is read, never invented.** A body that cannot state
 * a field the wire requires answers `undefined` for the whole reading, and `body.js`
 * turns that into a `notReady` refusal - which is honest, because the alternative is a
 * default standing in for a measurement. The rule reaches further than it first looks:
 * a scene is *also* a reading, so a body whose eye height the library has not set
 * cannot say where its eyes are, and an answer whose `visible` was cast from its feet
 * would be an answer about a different origin than the contract names.
 *
 * Two facts about the client library are load-bearing and neither is guessable from
 * this code. `bot.world` is the *sync* world - mineflayer assigns `new World(...).sync`
 * - so `raycast` returns a block rather than a promise and the whole of a sense answer
 * is synchronous. And `bot.time`'s every field is `null` until the server's first
 * `update_time` packet, which is why the clock is bounded rather than trusted.
 */

const {
  MAX_COUNT,
  MAX_FOOD,
  MAX_HEALTH,
  MAX_SLOT_INDEX,
  MAX_STACK_COUNT,
  MAX_TIME_OF_DAY,
  MAX_WORLD_COORDINATE,
  MIN_STACK_COUNT,
  isResourceName
} = require('./protocol')

/** Whether `value` is a whole number the wire will carry between `least` and `most`. */
function wholeWithin (value, least, most) {
  return Number.isInteger(value) && value >= least && value <= most
}

/** Whether `value` is a finite real the wire will carry between `least` and `most`. */
function realWithin (value, least, most) {
  return typeof value === 'number' && Number.isFinite(value) && value >= least && value <= most
}

/**
 * `point` as the three numbers the wire carries, or undefined when it will not carry it.
 *
 * A world coordinate is bounded because the wire bounds it, and a position outside that
 * bound is not a position this body can state - not a position it may round into range,
 * which would put a place the agent is not on the record.
 *
 * Exported because a movement report states four positions of its own, and reading them any
 * other way would give one wire one field with two bounds on it.
 */
function positionOf (point) {
  if (point === null || point === undefined) return undefined
  const axes = [point.x, point.y, point.z]
  const carried = axes.every(
    (axis) => realWithin(axis, -MAX_WORLD_COORDINATE, MAX_WORLD_COORDINATE)
  )
  return carried ? axes : undefined
}

/**
 * The three vanilla vitals, or undefined when the game has not reported all of them.
 *
 * They are read as three separate numbers because they are three separate facts: a body
 * can be beaten to death on a full stomach and can starve without a scratch. Saturation
 * is refused above food rather than clamped, because vanilla spends saturation before
 * food falls and a reading that broke that order is a reading this body misread.
 */
function vitalsOf (bot) {
  const health = bot.health
  const food = bot.food
  const saturation = bot.foodSaturation
  if (!realWithin(health, 0, MAX_HEALTH)) return undefined
  if (!wholeWithin(food, 0, MAX_FOOD)) return undefined
  if (!realWithin(saturation, 0, MAX_FOOD)) return undefined
  if (saturation > food) return undefined
  return { health, food, saturation }
}

/**
 * The world clock as the client library reports it, or undefined before it has.
 *
 * Every field of `bot.time` is `null` until the server's first `update_time` packet, so
 * a body that read the clock without bounding it would answer `null` for a required
 * field and the protocol's own encoder would refuse the frame. Nothing is derived here:
 * the phase and the countdown to nightfall are the coordinator's, and a body that named
 * a phase would be judging what part of a day an agent is in.
 */
function clockOf (bot) {
  const clock = bot.time
  if (clock === null || clock === undefined) return undefined
  const { timeOfDay, day, isDay } = clock
  if (!wholeWithin(timeOfDay, 0, MAX_TIME_OF_DAY)) return undefined
  if (!wholeWithin(day, 0, MAX_COUNT)) return undefined
  if (typeof isDay !== 'boolean') return undefined
  return { timeOfDay, day, isDay }
}

/**
 * The two blocks a walking body's footing is made of, or undefined when either is
 * unreadable.
 *
 * Absent is not air. A chunk the client library has not been sent has no block to
 * report, and answering `air` there would tell a mind the ground under an agent is
 * missing - so the field is left off the answer and the contract says outright that a
 * reader must never take its absence for air. Air itself *is* reported, because air is
 * a block the registry names.
 *
 * Both positions are floored before they are read, so the block the body stands in and
 * the block below it are exactly one block apart whatever fraction of a block the
 * player's own position carries.
 */
function footingOf (bot, standing) {
  const feet = standing.floored()
  const inside = bot.blockAt(feet)
  const below = bot.blockAt(feet.offset(0, -1, 0))
  if (inside === null || inside === undefined) return undefined
  if (below === null || below === undefined) return undefined
  if (!isResourceName(inside.name) || !isResourceName(below.name)) return undefined
  return { standingIn: inside.name, standingOn: below.name }
}

/**
 * Whether a straight line from `eyes` to `aim` arrives without meeting a block.
 *
 * This is a line and never a field of view: the body's facing is not consulted, so
 * everything within the radius is equally known and `visible` false means the body
 * knows something is there and cannot see it. The ray is the client library's own, cast
 * over block collision shapes, so an entity behind tall grass stays visible and one
 * behind a wall does not.
 */
function inSight (bot, eyes, aim) {
  const delta = aim.minus(eyes)
  const range = delta.norm()
  if (range === 0) return true
  return bot.world.raycast(eyes, delta.scaled(1 / range), range) === null
}

/**
 * Every entity the client library is tracking that this body could describe, each with
 * the distance from the body's eyes that both the radius and the order are measured by.
 *
 * The body's own entity is left out, because a body is not part of its own surroundings.
 *
 * An entity the body cannot describe at all - no name the wire will carry, or no
 * position - is skipped here rather than counted as an omission. The count beside the
 * list means "inside the radius and did not fit the limit", and an entity whose type
 * packet has not arrived yet is neither: read into that count it would tell a
 * coordinator the scene was crowded when what happened is that the library had not
 * finished describing one thing in it. `unknown` is not that case - it is the library's
 * own answer for a type its registry does not know, and is carried through verbatim
 * like any other name, because translating it would be this body deciding.
 */
function describable (bot, eyes) {
  const seen = []
  for (const entity of Object.values(bot.entities)) {
    if (entity === null || entity === undefined) continue
    if (entity.id === bot.entity.id) continue
    if (!isResourceName(entity.name)) continue
    const position = positionOf(entity.position)
    if (position === undefined) continue
    const height = realWithin(entity.height, 0, MAX_WORLD_COORDINATE) ? entity.height : 0
    seen.push({
      entity,
      position,
      away: entity.position.distanceTo(eyes),
      aim: entity.position.offset(0, height / 2, 0)
    })
  }
  return seen
}

/**
 * The nearest entities within `radius`, cut to `limit`, and how many the cut dropped.
 *
 * Distance is measured once per candidate, from the body's eyes, and that one reading is
 * what admits the entity, orders the list and is thrown away: three readings of one
 * moving scene can disagree, and an answer ordered by one reading and admitted by
 * another is an answer no test can state. The reported position is the entity's own,
 * which is deliberately not what the order is measured from - the contract says so,
 * because a check at either end of the wire would be a second and disagreeing
 * measurement.
 *
 * Ties keep the order the library's own entity table offered, so the same scene answers
 * the same way twice. The order is not checked by either codec on purpose: it is pinned
 * where it is decided, which is here.
 *
 * **`entityOffers` comes back beside the list and never inside it.** A controller names a
 * creature by its *place* in the reported list, so what makes that handle mean something
 * later is the client library's id and exact object for the thing at each place. The id can
 * be reused after a creature leaves, while the object identity cannot; retaining both is what
 * prevents a later creature at the same id from being accepted as the one the controller saw.
 * Both are private library details and may not reach the wire. Kept in the same order and cut
 * by the same slice, the offers are the handles the coordinator resolves through; see
 * `senses.js` for how long one lasts.
 */
function nearest (bot, eyes, { radius, limit }) {
  const within = describable(bot, eyes)
    .filter((candidate) => candidate.away <= radius)
    .sort((earlier, later) => earlier.away - later.away)
  const reported = within.slice(0, limit)
  const entities = reported.map((candidate) => ({
    what: candidate.entity.name,
    position: candidate.position,
    visible: inSight(bot, eyes, candidate.aim)
  }))
  return {
    entities,
    entitiesOmitted: within.length - entities.length,
    entityOffers: reported.map((candidate) => ({
      id: candidate.entity.id,
      entity: candidate.entity
    }))
  }
}

/**
 * Every occupied slot of the player's own inventory window, or undefined when one of
 * them will not go on the wire.
 *
 * Nothing is trimmed. There is no bound to omit against and so no omission count, which
 * is what makes an unstatable stack a refusal of the whole answer rather than a quiet
 * gap: a body that dropped one slot to fit could be dropping the last food an agent has,
 * and a mind reading the rest would have no way to know. Empty slots are simply absent,
 * which is what makes a reported count at least one.
 *
 * The ascending walk is what makes the slots strictly increasing, so the order the
 * contract requires is a property of how the list is built rather than a sort to trust.
 * The slot bound is also the list's: forty-six distinct slots is the whole of 0 to 45,
 * so a window with more slots than the wire numbers is refused at the first extra one
 * and the list can never outgrow what a frame will carry.
 */
function inventoryOf (bot) {
  const slots = bot.inventory === null || bot.inventory === undefined
    ? undefined
    : bot.inventory.slots
  if (!Array.isArray(slots)) return undefined
  const inventory = []
  for (let slot = 0; slot < slots.length; slot += 1) {
    const stack = slots[slot]
    if (stack === null || stack === undefined) continue
    if (!wholeWithin(slot, 0, MAX_SLOT_INDEX)) return undefined
    if (!isResourceName(stack.name)) return undefined
    if (!wholeWithin(stack.count, MIN_STACK_COUNT, MAX_STACK_COUNT)) return undefined
    inventory.push({ slot, what: stack.name, count: stack.count })
  }
  return inventory
}

/**
 * Everything one sense answer carries and the handles it mints, or undefined when the body
 * cannot state all of it.
 *
 * `ground` is the one field allowed to be missing on its own, because the contract makes
 * it optional and says what its absence means. Everything else is required, so a reading
 * the body does not have makes the whole answer a refusal rather than a frame with a
 * guess in it.
 *
 * **The reading and the handles come back as two named things, deliberately.** `reading`
 * is exactly what the wire carries and `entityOffers` is exactly what may not, so the seam is
 * in the shape rather than in a caller's discipline: spread into one object, the private ids
 * and entity objects would have travelled toward the wire the day someone passed the whole
 * answer to an encoder. The contract's frames are built field by field from named properties.
 * One reading, two consumers, and no way to hand the wrong one to the wrong one by accident.
 */
function senseReading (bot, { radius, limit }) {
  const standing = bot.entity === null || bot.entity === undefined
    ? undefined
    : bot.entity.position
  const position = positionOf(standing)
  const vitals = vitalsOf(bot)
  const time = clockOf(bot)
  const inventory = inventoryOf(bot)
  if (position === undefined || vitals === undefined) return undefined
  if (time === undefined || inventory === undefined) return undefined
  if (!realWithin(bot.entity.eyeHeight, 0, MAX_WORLD_COORDINATE)) return undefined
  const eyes = standing.offset(0, bot.entity.eyeHeight, 0)
  const { entities, entitiesOmitted, entityOffers } = nearest(bot, eyes, { radius, limit })
  const reading = { position, vitals, time, entities, entitiesOmitted, inventory }
  const ground = footingOf(bot, standing)
  if (ground !== undefined) reading.ground = ground
  return { reading, entityOffers }
}

module.exports = {
  clockOf,
  inventoryOf,
  positionOf,
  senseReading,
  vitalsOf
}
