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
  MAX_SENSE_BLOCKS,
  MAX_SENSE_ENTITIES,
  MAX_SENSE_ITEMS,
  MAX_SLOT_INDEX,
  MAX_STACK_COUNT,
  MAX_TIME_OF_DAY,
  MAX_WORLD_COORDINATE,
  MIN_STACK_COUNT,
  isResourceName
} = require('./protocol')

/** Mineflayer's native direct interaction reach, in blocks. */
const INTERACTION_REACH_BLOCKS = 5.1

/**
 * How far from the eyes a sense looks for the block kinds a controller names in `seek`, in
 * blocks: past interaction reach, so a tree the body cannot dig yet is still seen (r34: the woods
 * lay 10.8 to 25.7 blocks from eight of ten slots, and zeta walked for 9000 ticks without one).
 */
const OBSERVATION_REACH_BLOCKS = 16

/** Most blocks of named kinds beyond interaction reach one answer reports, of its block limit. */
const MAX_SOUGHT_BLOCKS = 12

/** Most blocks of one named kind tried for sight, nearest first, before its rank is passed over. */
const MAX_SIGHT_TRIES_PER_KIND = 6

/** Registry names the game uses for empty space rather than an interactable block. */
const AIR_BLOCKS = Object.freeze(new Set(['air', 'cave_air', 'void_air']))

/** Stable private fingerprint of the item details the wire deliberately omits. */
function stackDetail (stack) {
  try {
    return JSON.stringify({
      type: stack.type,
      metadata: stack.metadata,
      nbt: stack.nbt ?? null,
      components: stack.components ?? null,
      removedComponents: stack.removedComponents ?? null
    })
  } catch {
    return undefined
  }
}

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

/** How far inside a face a sight ray is aimed, so it ends in the block rather than on it. */
const FACE_INSET = 0.001

/**
 * Where a sight ray to `block` may be aimed: its centre, then the centre of every face
 * turned toward the eyes. A centre ray alone meets a nearer floor block before it reaches
 * any floor block more than about two blocks away, which would call open ground unseen.
 */
function blockAims (eyes, block) {
  const { x, y, z } = block.position
  const aims = [block.position.offset(0.5, 0.5, 0.5)]
  if (eyes.x < x) aims.push(block.position.offset(FACE_INSET, 0.5, 0.5))
  if (eyes.x > x + 1) aims.push(block.position.offset(1 - FACE_INSET, 0.5, 0.5))
  if (eyes.y < y) aims.push(block.position.offset(0.5, FACE_INSET, 0.5))
  if (eyes.y > y + 1) aims.push(block.position.offset(0.5, 1 - FACE_INSET, 0.5))
  if (eyes.z < z) aims.push(block.position.offset(0.5, 0.5, FACE_INSET))
  if (eyes.z > z + 1) aims.push(block.position.offset(0.5, 0.5, 1 - FACE_INSET))
  return aims
}

/** Whether a ray from the body's eyes reaches `block`, through some face, before any other block. */
function blockInSight (bot, eyes, block) {
  return blockAims(eyes, block).some((aim) => {
    const delta = aim.minus(eyes)
    const range = delta.norm()
    if (range === 0) return true
    const struck = bot.world.raycast(eyes, delta.scaled(1 / range), range)
    if (struck === null || struck === undefined) return true
    return struck.position?.equals(block.position) === true
  })
}

/** Whether an entity is the dropped-item shape Mineflayer exposes. */
function isDroppedEntity (entity) {
  return entity?.name === 'item' || entity?.name === 'Item' || entity?.name === 'item_stack'
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
    if (isDroppedEntity(entity)) continue
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
 * Nearby non-air blocks the body can see, bounded independently from the general entity list.
 *
 * Only a block in sight is listed, because every listed block is a handle a `dig` or
 * `place` may name and both refuse a block the body cannot see. An unseen block is
 * skipped rather than counted as omitted, for the reason dropped items are.
 *
 * The scan is capped at Mineflayer's own direct interaction reach even when the caller asks
 * for a wider scene, except for the kinds the caller names in `seek`, which [soughtBlocks] looks
 * for out to [OBSERVATION_REACH_BLOCKS] in at most a quarter of the list: a block seen there is a
 * place to walk toward, never one in reach of a dig. It does not form a map: at most
 * [MAX_SENSE_BLOCKS] candidates are reported, and the exact type/state snapshot is retained beside
 * the wire offer so a later action can distinguish a changed block from the one the controller chose.
 */
/**
 * The nearest `bound` of `within`, already sorted nearest first, taken fairly across block kinds:
 * each kind's nearest, then each kind's second nearest, and so on, the kinds in order of their
 * nearest, and the chosen ones returned nearest first. On plains the grass around the feet filled
 * every one of the 48 places by 4.15 blocks, so a tree trunk 3.8 blocks off was never reported
 * (r24 eta). The cut is still a bounded count, and every kind in reach is reported before a kind
 * gets its second entry.
 */
function fairBlocks (within, bound) {
  const byKind = new Map()
  for (const entry of within) {
    if (!byKind.has(entry.block.name)) byKind.set(entry.block.name, [])
    byKind.get(entry.block.name).push(entry)
  }
  const kinds = [...byKind.values()]
  const chosen = new Set()
  for (let rank = 0; chosen.size < bound && kinds.some((queue) => queue.length > rank); rank += 1) {
    for (const queue of kinds) {
      if (chosen.size >= bound) break
      if (queue.length > rank) chosen.add(queue[rank])
    }
  }
  return within.filter((entry) => chosen.has(entry))
}

/**
 * The blocks of the kinds a controller named, beyond interaction reach and within
 * [OBSERVATION_REACH_BLOCKS] of the eyes, that the body can see: each kind's nearest in sight,
 * then each kind's second, the kinds in order of their nearest, at most `bound` in all.
 *
 * The controller chooses the kinds, so the body still decides nothing; it only looks further for
 * what it was asked about. The scan reads state ids rather than building a block per cell, and casts
 * sight only at a kind's nearest few, so a sphere of sixteen costs a sense little. A block of a named
 * kind that is never cast at is neither reported nor counted as omitted: its sight is unknown, and
 * an unseen block is skipped for the reason [nearestBlocks] gives.
 */
function soughtBlocks (bot, eyes, standing, { radius, seek, bound }) {
  if (!Array.isArray(seek) || seek.length === 0 || bound <= 0) return []
  const reach = Math.min(radius, OBSERVATION_REACH_BLOCKS)
  const states = new Map()
  for (const name of seek) {
    const known = bot.registry?.blocksByName?.[name]
    if (known !== undefined && Number.isInteger(known.minStateId) && Number.isInteger(known.maxStateId)) {
      states.set(name, [known.minStateId, known.maxStateId])
    }
  }
  if (states.size === 0 || typeof bot.world?.getBlockStateId !== 'function') return []
  const kindOf = (stateId) => {
    for (const [name, [least, most]] of states) if (stateId >= least && stateId <= most) return name
    return undefined
  }
  const span = Math.ceil(reach)
  const feet = standing.floored()
  const byKind = new Map()
  for (let dx = -span; dx <= span; dx += 1) {
    for (let dy = -span; dy <= span; dy += 1) {
      for (let dz = -span; dz <= span; dz += 1) {
        const cell = feet.offset(dx, dy, dz)
        const name = kindOf(bot.world.getBlockStateId(cell))
        if (name === undefined) continue
        const away = cell.offset(0.5, 0.5, 0.5).distanceTo(eyes)
        if (away <= INTERACTION_REACH_BLOCKS || !realWithin(away, 0, reach)) continue
        if (!byKind.has(name)) byKind.set(name, [])
        byKind.get(name).push({ cell, away })
      }
    }
  }
  const queues = [...byKind.values()].map((cells) => ({
    cells: cells.sort((one, other) => one.away - other.away || one.cell.x - other.cell.x ||
      one.cell.y - other.cell.y || one.cell.z - other.cell.z),
    next: 0
  })).sort((one, other) => one.cells[0].away - other.cells[0].away)
  const found = []
  const nextInSight = (queue) => {
    for (let tries = 0; tries < MAX_SIGHT_TRIES_PER_KIND && queue.next < queue.cells.length; tries += 1) {
      const block = bot.blockAt(queue.cells[queue.next].cell)
      const away = queue.cells[queue.next].away
      queue.next += 1
      if (block === null || block === undefined || !isResourceName(block.name)) continue
      if (!Number.isInteger(block.type) || !Number.isInteger(block.stateId)) continue
      if (blockInSight(bot, eyes, block)) return { block, away }
    }
    return undefined
  }
  while (found.length < bound && queues.some((queue) => queue.next < queue.cells.length)) {
    for (const queue of queues) {
      if (found.length >= bound) break
      const seen = nextInSight(queue)
      if (seen !== undefined) found.push(seen)
    }
  }
  return found.sort((one, other) => one.away - other.away)
}

function nearestBlocks (bot, eyes, standing, { radius, limit, seek }) {
  const reach = Math.min(radius, INTERACTION_REACH_BLOCKS)
  const span = Math.ceil(reach)
  const feet = standing.floored()
  const within = []
  for (let dx = -span; dx <= span; dx += 1) {
    for (let dy = -span; dy <= span; dy += 1) {
      for (let dz = -span; dz <= span; dz += 1) {
        const block = bot.blockAt(feet.offset(dx, dy, dz))
        if (block === null || block === undefined || AIR_BLOCKS.has(block.name)) continue
        if (!isResourceName(block.name)) continue
        if (!Number.isInteger(block.type) || !Number.isInteger(block.stateId)) continue
        const away = block.position.offset(0.5, 0.5, 0.5).distanceTo(eyes)
        if (!realWithin(away, 0, reach)) continue
        if (!blockInSight(bot, eyes, block)) continue
        within.push({ block, away })
      }
    }
  }
  within.sort((one, other) =>
    one.away - other.away ||
    one.block.position.x - other.block.position.x ||
    one.block.position.y - other.block.position.y ||
    one.block.position.z - other.block.position.z
  )
  const room = Math.min(limit, MAX_SENSE_BLOCKS)
  // The named kinds beyond reach take at most a quarter of the list, so what can be dug stays.
  const sought = soughtBlocks(bot, eyes, standing, { radius, seek, bound: Math.min(MAX_SOUGHT_BLOCKS, Math.floor(room / 4)) })
  const near = fairBlocks(within, room - sought.length)
  const reported = [...near, ...sought]
  return {
    blocks: reported.map(({ block }) => ({
      what: block.name,
      position: [block.position.x, block.position.y, block.position.z],
      visible: true
    })),
    blocksOmitted: within.length - near.length,
    blockOffers: reported.map(({ block }) => ({
      what: block.name,
      position: [block.position.x, block.position.y, block.position.z],
      type: block.type,
      stateId: block.stateId,
      visible: true
    }))
  }
}

/** Where a line of sight to a dropped item is aimed: the middle of its small box. */
function itemAim (entity) {
  const height = realWithin(entity.height, 0, MAX_WORLD_COORDINATE) ? entity.height : 0
  return entity.position.offset(0, height / 2, 0)
}

/** The item stack one dropped entity carries, or undefined until it is describable. */
function droppedStack (entity) {
  if (!isDroppedEntity(entity) || typeof entity.getDroppedItem !== 'function') return undefined
  let item
  try {
    item = entity.getDroppedItem()
  } catch {
    return undefined
  }
  if (item === null || item === undefined) return undefined
  if (!isResourceName(item.name)) return undefined
  if (!wholeWithin(item.count, MIN_STACK_COUNT, MAX_STACK_COUNT)) return undefined
  const detail = stackDetail(item)
  if (detail === undefined) return undefined
  return { what: item.name, count: item.count, detail }
}

/**
 * Exact dropped-item entities inside the requested scene radius that the body can see.
 *
 * Only an item in sight is offered, because every offer is a handle a `collect` may name
 * and an item behind a wall or under the ground is one the body cannot reach by any
 * route it would take - unless the controller named it in `retainItems`, as Loom names an
 * agent's own dig drops. An unseen item is skipped rather than counted as omitted: the
 * omission count means "seen and did not fit the limit". Sight is aimed at the item's
 * centre, as it is for a creature, because a ray to the point the item rests on meets
 * the floor under it and would call every item on open ground unseen.
 *
 * The wire gets the vanilla item name/count and position. The retained half keeps the
 * Mineflayer id and exact object as well, because ids are reused after despawn and a later
 * object under the same number is not the item this sense offered.
 */
function nearestItems (bot, eyes, { radius, limit, retainItems = [] }) {
  const retained = new Set(retainItems)
  const within = []
  for (const entity of Object.values(bot.entities)) {
    if (entity === null || entity === undefined || entity.id === bot.entity.id) continue
    const stack = droppedStack(entity)
    if (stack === undefined) continue
    const position = positionOf(entity.position)
    if (position === undefined) continue
    const away = entity.position.distanceTo(eyes)
    if (!realWithin(away, 0, radius)) continue
    // An item out of sight is reported only when the controller named it, and said to be out
    // of sight: Loom names the agent's own dig drops, so an agent told of one can collect it.
    const visible = inSight(bot, eyes, itemAim(entity))
    if (!visible && !retained.has(entity.uuid)) continue
    within.push({ entity, stack, position, away, visible })
  }
  within.sort((one, other) => one.away - other.away || one.entity.id - other.entity.id)
  const reported = within.slice(0, Math.min(limit, MAX_SENSE_ITEMS))
  return {
    items: reported.map(({ stack, position, visible }) => ({
      what: stack.what,
      count: stack.count,
      position,
      visible
    })),
    itemsOmitted: within.length - reported.length,
    itemOffers: reported.map(({ entity, stack }) => ({
      id: entity.id,
      entity,
      what: stack.what,
      count: stack.count,
      detail: stack.detail
    }))
  }
}

/**
 * The nearest `bound` of the distance-ordered `within`, except that every entity whose uuid is in
 * `retained` is kept and takes the place of the farthest one the bound would otherwise have kept.
 * The controller names them - Loom names the creature that has just hurt the agent - so the body
 * chooses nothing, and the order stays the distance order.
 */
function retainedNearest (within, bound, retained) {
  const kept = within.filter((candidate) => retained.has(candidate.entity.uuid)).slice(0, bound)
  const keptSet = new Set(kept)
  let room = bound - kept.length
  return within.filter((candidate) => {
    if (keptSet.has(candidate)) return true
    if (room === 0) return false
    room -= 1
    return true
  })
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
function nearest (bot, eyes, { radius, limit, retainEntities = [] }) {
  const within = describable(bot, eyes)
    .filter((candidate) => candidate.away <= radius)
    .sort((earlier, later) => earlier.away - later.away)
  const reported = retainedNearest(within, Math.min(limit, MAX_SENSE_ENTITIES), new Set(retainEntities))
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
function inventorySnapshot (bot) {
  const slots = bot.inventory === null || bot.inventory === undefined
    ? undefined
    : bot.inventory.slots
  if (!Array.isArray(slots)) return undefined
  const inventory = []
  const slotOffers = []
  for (let slot = 0; slot < slots.length; slot += 1) {
    const stack = slots[slot]
    if (stack === null || stack === undefined) continue
    if (!wholeWithin(slot, 0, MAX_SLOT_INDEX)) return undefined
    if (!isResourceName(stack.name)) return undefined
    if (!wholeWithin(stack.count, MIN_STACK_COUNT, MAX_STACK_COUNT)) return undefined
    inventory.push({ slot, what: stack.name, count: stack.count })
    const detail = stackDetail(stack)
    if (detail === undefined) return undefined
    slotOffers.push({ slot, what: stack.name, count: stack.count, detail })
  }
  return { inventory, slotOffers }
}

/** Every occupied slot as the wire reports it, without the retained fingerprints. */
function inventoryOf (bot) {
  return inventorySnapshot(bot)?.inventory
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
function senseReading (bot, { radius, limit, retainItems, retainEntities, seek }) {
  const standing = bot.entity === null || bot.entity === undefined
    ? undefined
    : bot.entity.position
  const position = positionOf(standing)
  const vitals = vitalsOf(bot)
  const time = clockOf(bot)
  const inventoryRead = inventorySnapshot(bot)
  if (position === undefined || vitals === undefined) return undefined
  if (time === undefined || inventoryRead === undefined) return undefined
  if (!realWithin(bot.entity.eyeHeight, 0, MAX_WORLD_COORDINATE)) return undefined
  const eyes = standing.offset(0, bot.entity.eyeHeight, 0)
  const { entities, entitiesOmitted, entityOffers } = nearest(bot, eyes, { radius, limit, retainEntities })
  const { blocks, blocksOmitted, blockOffers } =
    nearestBlocks(bot, eyes, standing, { radius, limit, seek })
  const { items, itemsOmitted, itemOffers } = nearestItems(bot, eyes, { radius, limit, retainItems })
  const reading = {
    position,
    vitals,
    time,
    entities,
    entitiesOmitted,
    blocks,
    blocksOmitted,
    items,
    itemsOmitted,
    inventory: inventoryRead.inventory
  }
  const ground = footingOf(bot, standing)
  if (ground !== undefined) reading.ground = ground
  return {
    reading,
    entityOffers,
    blockOffers,
    itemOffers,
    slotOffers: inventoryRead.slotOffers
  }
}

module.exports = {
  INTERACTION_REACH_BLOCKS,
  MAX_SOUGHT_BLOCKS,
  OBSERVATION_REACH_BLOCKS,
  blockInSight,
  clockOf,
  droppedStack,
  inventoryOf,
  positionOf,
  senseReading,
  stackDetail,
  vitalsOf
}
