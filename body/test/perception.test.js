'use strict'

/**
 * What one player body can read, and the one meal it can be asked for.
 *
 * Every case here drives the real client library. The scene is put together the way a
 * server puts one together - a login, a health report, a clock, a position, a chunk
 * column, `set_slot` packets, an entity spawn - and then the body is asked what it can
 * see, so what is proven is the library's own answer rather than a fake of one.
 * `test/harness.js` records why the Minecraft client is fake and the control socket is
 * not.
 *
 * The reading cases call `senseReading` directly and the wire cases go over the socket,
 * because they are different claims. What a body can read is a fact about the library;
 * that a slot handle belongs to the sense answer that minted it is a fact about
 * *requests*, and cannot be stated below the wire at all. The byte bound is over the
 * socket for the same reason: what has to fit one frame is the answer this body actually
 * produces for a crowded scene. The widest frame the encoder will accept is
 * `protocol.test.js`'s job.
 *
 * The eat cases are as much the point of this file as the sense cases are. There is
 * exactly one library call that consumes anything, and the only way to know it consumed
 * one item rather than two - or that a refusal cost the agent nothing at all - is to watch
 * the packets the body writes. So `client.writes` is asserted on directly: a body that ate
 * without being asked, or twice for one request, is a run whose food figures are fiction.
 */

const assert = require('node:assert/strict')
const { afterEach, test } = require('node:test')

const { SLOT_SETTLE_MS } = require('../src/eating')
const { stackDetail } = require('../src/interaction')
const { MAX_REMEMBERED_SENSES } = require('../src/senses')
const { MAX_REFUSALS_PER_CONNECTION } = require('../src/body')
const {
  MAX_FOOD,
  MAX_FRAME_BYTES,
  MAX_SENSE_OFFERS_PER_KIND,
  MAX_SENSE_RADIUS,
  MAX_SENSE_STACKS,
  MAX_SLOT_INDEX,
  MAX_STACK_COUNT,
  decodeResponse
} = require('../src/protocol')
const { senseReading } = require('../src/sensing')
const { eatFromSlot } = require('../src/eating')
const {
  Item,
  OTHER_AGENT,
  QUICK_BAR_START,
  WATCHED_MS,
  clockReported,
  disconnected,
  embodied,
  emptied,
  ground,
  handshaken,
  positionPacket,
  promptly,
  realBot,
  registry,
  request,
  settled,
  spawned,
  spotted,
  stack,
  stocked
} = require('./harness')

// Every case here builds a real bot, and a real bot that is not given up keeps a timer
// this process never started. See `harness.js`'s `disconnected`.
afterEach(disconnected)

/** Where a body stands for these cases: inside the one chunk column that is loaded. */
const FEET = Object.freeze({ x: 8.5, y: 64, z: 8.5 })

/** The ground's own surface, so the block below the body's feet is stone. */
const GROUND_LEVEL = 63

/**
 * The player eye height the library sets on a spawn, which every line of sight starts at.
 *
 * Stated so the geometry below is readable rather than because anything asserts the number
 * itself: a wall between two bodies has to stand between their *eyes*, which is why the
 * one below is two blocks tall rather than one.
 */
const EYE_HEIGHT = 1.62

/** Comfortably inside the loaded chunk, and wide enough for the geometry to be legible. */
const RADIUS = 5

/** Small on purpose, so a scene of four entities can overflow it. */
const LIMIT = 3

/** Vitals a vanilla server reports for an untouched, well-fed player. */
const FED = Object.freeze({ health: 20, food: 20, foodSaturation: 5 })

/** Vitals hungry enough for vanilla to accept an ordinary food. */
const HUNGRY = Object.freeze({ health: 20, food: 8, foodSaturation: 0 })

/** A main-inventory slot: neither the hotbar the library equips into nor an armour slot. */
const BREAD_SLOT = 20

/**
 * A held count past the wire's own stack bound, for the case that bounds one into the field.
 *
 * Reachable rather than contrived: `Slot.itemCount` is a varint, so a server can put this in
 * the held slot after the sense answer that offered it was given - and the sense answer's own
 * bound is what makes it unreachable at sense time and not afterwards.
 */
const OVER_STACKED_COUNT = MAX_STACK_COUNT + 45

/** Noon, so the clock reads as day and the number is legible in the assertions. */
const NOON = 6_000

/** The longest item name in this version's registry, for the crowded-frame case. */
const LONGEST_ITEM = 'wayfinder_armor_trim_smithing_template'

/** The longest entity name in this version's registry, for the same case. */
const LONGEST_ENTITY = 'command_block_minecart'

/**
 * What a broken library below this process throws, for the case that proves none of it
 * reaches the wire. Unbounded on purpose: a stack trace quoting it is exactly what a
 * refusal may not carry.
 */
const BROKEN_LIBRARY = 'private/loom/broken-library'

/**
 * A real bot standing on real ground, with a clock and a position the server reported.
 *
 * Every reading a sense answer needs arrives as a packet, in the order a server sends
 * them, so nothing below is a field a test wrote onto the bot.
 */
async function inTheWorld (given, { vitals = FED, walls = [], at = FEET, gamemode } = {}) {
  const { bot, client } = given
  spawned(client, vitals, { gamemode })
  clockReported(client, { timeOfDay: NOON, day: 3 })
  client.emit('position', positionPacket({ ...at, yaw: 0, pitch: 0, teleportId: 1 }))
  ground(bot, { level: GROUND_LEVEL, walls })
  await settled()
  return given
}

/** A real bot in the world, with no body process around it. */
async function standing (scene) {
  return inTheWorld(await realBot(), scene)
}

/**
 * What this body can read, at the bounds these cases use.
 *
 * The reading alone: `senseReading` answers it beside the entity handles a sense mints, and
 * these cases are about what goes on the wire. The handles are covered where they mean
 * something, which is a flee.
 */
function read (bot, { radius = RADIUS, limit = LIMIT } = {}) {
  return senseReading(bot, { radius, limit })?.reading
}

/** What a sense answer offering these slots would have remembered about them. */
function offering (stacks) {
  return new Map(stacks.map(([slot, what, count]) => {
    const described = stack(what, count)
    return [slot, { what, count, detail: stackDetail(described) }]
  }))
}

/** The server's own report that the item in hand finished being used. */
function used (client, what) {
  client.emit('set_cooldown', { itemID: registry.itemsByName[what].id })
}

/** The server's own restatement of the slot the meal came out of. */
function restated (client, what, { slot = QUICK_BAR_START, remaining = 2 } = {}) {
  if (remaining === 0) {
    emptied(client, slot)
    return
  }
  client.emit('set_slot', {
    windowId: 0,
    stateId: 2,
    slot,
    item: Item.toNotch(stack(what, remaining))
  })
}

/**
 * The server's whole account of a finished meal: the use completed, then the slot restated.
 *
 * In that order on purpose. Vanilla completes the item use inside the entity tick and
 * broadcasts the container change at the end of the same tick, so the completion is free to
 * arrive first - which is the order that once let `remaining` report the count the slot held
 * *before* the meal.
 */
function consumed (client, what, options = {}) {
  used(client, what)
  restated(client, what, options)
}

/** Every packet the body has written, by name, since the record was last cleared. */
function written (client) {
  return client.writes.map((write) => write.name)
}

/** One eat over the socket, with the server accounting for `what` if the meal starts. */
async function ate (peer, client, { requestId, senseId, slot, what, remaining = 2 }) {
  peer.send(request('eat', requestId, { senseId, slot }))
  await settled()
  if (what !== undefined) consumed(client, what, { remaining })
  return promptly(peer.read(), `the ${requestId} eat report`)
}

test('a sense states the position, three vitals, the clock, the footing and the inventory', async () => {
  const { bot, client } = await standing()
  stocked(client, [[BREAD_SLOT, 'bread', 3]])
  await settled()
  const reading = read(bot)
  assert.deepEqual(reading.position, [FEET.x, FEET.y, FEET.z])
  // Three separate facts from three separate library fields. A body can be beaten to death
  // on a full stomach and can starve without a scratch, so none of the three is derived
  // from either of the others - and none of them is a reserve this process simulated.
  assert.deepEqual(reading.vitals, { health: 20, food: 20, saturation: 5 })
  assert.deepEqual(reading.time, { timeOfDay: NOON, day: 3, isDay: true })
  // The block the body stands in and the one holding it up, in the registry's own
  // vocabulary. Both are the library's answers over a real chunk column.
  assert.deepEqual(reading.ground, { standingIn: 'air', standingOn: 'stone' })
  assert.deepEqual(reading.inventory, [{ slot: BREAD_SLOT, what: 'bread', count: 3 }])
  // Unprefixed, because that is what the library answers. Nothing is translated on the way
  // out: a body that renamed `bread` would have a vocabulary of its own to fall behind the
  // game's, which is the second inventory model this process is not allowed to build.
  assert.equal(bot.inventory.slots[BREAD_SLOT].name, 'bread')
})

test('an unloaded chunk leaves the footing off the answer rather than calling it air', async () => {
  const { bot, client } = await standing()
  assert.notEqual(read(bot).ground, undefined)
  // One chunk column is loaded, so a body a hundred blocks out stands where the library has
  // no block to report. Answering `air` there would tell a mind the ground under an agent
  // had gone, which is the one reading a survival run must never be given.
  client.emit('position', positionPacket({ x: 100.5, y: 64, z: 100.5, yaw: 0, pitch: 0, teleportId: 2 }))
  await settled()
  const reading = read(bot)
  assert.equal(reading.ground, undefined)
  assert.equal('ground' in reading, false)
  // Everything else is still stated. The footing is the one field allowed to be missing.
  assert.deepEqual(reading.position, [100.5, 64, 100.5])
  assert.notEqual(reading.vitals, undefined)
})

test('a wall makes a known entity invisible and open ground makes it visible', async () => {
  const target = { id: 42, what: 'zombie', x: 12.5, y: FEET.y, z: 8.5 }
  // Four blocks east, so inside the radius in both scenes. What changes is only the wall.
  const open = await standing()
  spotted(open.client, target)
  await settled()
  assert.deepEqual(read(open.bot).entities, [
    { what: 'zombie', position: [12.5, FEET.y, 8.5], visible: true }
  ])
  const walled = await standing({
    walls: [
      { x: 10, y: FEET.y, z: 8 },
      { x: 10, y: FEET.y + 1, z: 8 }
    ]
  })
  spotted(walled.client, target)
  await settled()
  // Known and not seen, which is the difference between a threat behind a wall and one in
  // the open. The ray is the library's own, over real block collision shapes, and it is
  // cast from the eyes - which is why the wall has to reach above them.
  assert.deepEqual(read(walled.bot).entities, [
    { what: 'zombie', position: [12.5, FEET.y, 8.5], visible: false }
  ])
  assert.ok(EYE_HEIGHT < 2, 'a two-block wall must reach above the eyes')
})

/** A dropped stack lying where a server put it, with the item metadata a server sends. */
function droppedAt (client, { id, what, x, y, z }) {
  spotted(client, { id, what: 'item', x, y, z })
  client.emit('entity_metadata', {
    entityId: id,
    metadata: [{ key: 8, type: 'item_stack', value: Item.toNotch(stack(what, 1)) }]
  })
}

test('a dropped item on open ground is offered and one behind a wall is not offered at all', async () => {
  const seeds = { id: 77, what: 'wheat_seeds', x: 11.5, y: FEET.y, z: 8.5 }
  // Resting on the floor: a ray aimed at the point it rests on would meet the floor first.
  const open = await standing()
  droppedAt(open.client, seeds)
  await settled()
  assert.deepEqual(read(open.bot).items, [
    { what: 'wheat_seeds', count: 1, position: [11.5, FEET.y, 8.5], visible: true }
  ])
  const walled = await standing({ walls: [{ x: 10, y: FEET.y, z: 8 }] })
  droppedAt(walled.client, seeds)
  await settled()
  const hidden = senseReading(walled.bot, { radius: RADIUS, limit: LIMIT })
  // Unseen is not "did not fit", so it is neither listed, offered nor counted.
  assert.deepEqual(hidden.reading.items, [])
  assert.equal(hidden.reading.itemsOmitted, 0)
  assert.deepEqual(hidden.itemOffers, [])
})

test('the nearest entities fill the limit and the rest are counted as omitted', async () => {
  const { bot, client } = await standing()
  // Deliberately spawned furthest first, so the order below is the sense answer's own
  // rather than the order the library happens to hold them in.
  spotted(client, { id: 44, what: 'pig', x: 12.5, y: FEET.y, z: 8.5 })
  spotted(client, { id: 43, what: 'sheep', x: 11.5, y: FEET.y, z: 8.5 })
  spotted(client, { id: 42, what: 'cow', x: 10.5, y: FEET.y, z: 8.5 })
  spotted(client, { id: 41, what: 'chicken', x: 9.5, y: FEET.y, z: 8.5 })
  await settled()
  const reading = read(bot)
  assert.deepEqual(reading.entities.map((entity) => entity.what), ['chicken', 'cow', 'sheep'])
  // The count means "inside the radius and did not fit the limit", so a crowded scene
  // trimmed to the bound can never read afterwards as a quiet one.
  assert.equal(reading.entitiesOmitted, 1)
  // A wider limit reaches exactly the entity that was omitted, which is what makes the
  // count a statement about the cut rather than about the radius.
  const wider = read(bot, { limit: LIMIT + 1 })
  assert.deepEqual(wider.entities.map((entity) => entity.what), ['chicken', 'cow', 'sheep', 'pig'])
  assert.equal(wider.entitiesOmitted, 0)
})

test('an entity outside the radius is not counted as omitted', async () => {
  const { bot, client } = await standing()
  spotted(client, { id: 41, what: 'chicken', x: 9.5, y: FEET.y, z: 8.5 })
  // Ten blocks east, twice the radius. The library is tracking it and it is no part of this
  // scene, so counting it would tell a coordinator the body had trimmed something it never
  // held - and the omission count is the one number that says how much was left unsaid.
  spotted(client, { id: 42, what: 'cow', x: 18.5, y: FEET.y, z: 8.5 })
  await settled()
  const reading = read(bot)
  assert.deepEqual(reading.entities.map((entity) => entity.what), ['chicken'])
  assert.equal(reading.entitiesOmitted, 0)
  // The same scene at a radius that reaches it answers both, which is what proves the cow
  // was left out by the radius rather than dropped on the way.
  assert.deepEqual(
    read(bot, { radius: MAX_SENSE_RADIUS }).entities.map((entity) => entity.what),
    ['chicken', 'cow']
  )
})

test('an entity the library cannot name yet is left out of the list and out of the count', async () => {
  const { bot, client } = await standing()
  spotted(client, { id: 41, what: 'chicken', x: 9.5, y: FEET.y, z: 8.5 })
  // A real library path rather than a contrivance: a packet about an entity whose spawn has
  // not arrived creates that entity with no name at all. One block from the body, so
  // nothing about the radius can be what excludes it.
  client.emit('bed', { entityId: 99, location: { x: 9, y: FEET.y, z: 8 } })
  await settled()
  assert.equal(bot.entities[99].name, undefined, 'the library must be holding a nameless entity')
  const reading = read(bot)
  assert.deepEqual(reading.entities.map((entity) => entity.what), ['chicken'])
  // Neither reported nor counted. Counted, it would say the scene was crowded when what
  // happened is that the library had not finished describing one thing in it.
  assert.equal(reading.entitiesOmitted, 0)
})

test('a body whose clock has not arrived states no reading at all', async () => {
  const { bot, client } = await realBot()
  spawned(client, FED)
  client.emit('position', positionPacket({ ...FEET, yaw: 0, pitch: 0, teleportId: 1 }))
  await settled()
  // Every field of `bot.time` is null until the server's first `update_time`, and a default
  // standing in for a measurement is the one thing a reading may not contain.
  assert.equal(bot.time.timeOfDay, null)
  assert.equal(read(bot), undefined)
  clockReported(client, { timeOfDay: NOON, day: 0 })
  await settled()
  assert.notEqual(read(bot), undefined)
})

test('a saturation above the food level is refused rather than reported', async () => {
  const { bot, client } = await standing()
  // Vanilla spends saturation before the food level falls, so saturation above food is a
  // reading this process misread rather than a state a server can be in.
  client.emit('update_health', { health: 20, food: 3, foodSaturation: 5 })
  await settled()
  assert.equal(read(bot), undefined)
  client.emit('update_health', { health: 20, food: 3, foodSaturation: 3 })
  await settled()
  assert.deepEqual(read(bot).vitals, { health: 20, food: 3, saturation: 3 })
})

test('the whole inventory window is reported ascending and nothing is trimmed', async () => {
  const { bot, client } = await standing()
  // Every slot of the player's own window, which is all of 0 to 45: the crafting output and
  // grid, the four armour slots, the main inventory, the hotbar and the offhand. A body
  // reporting only the main slots would hide the armour an agent wears and the food in its
  // other hand, and both are things a mind has to decide about.
  const filled = Array.from({ length: MAX_SENSE_STACKS }, (_, slot) => [slot, 'bread', 1])
  stocked(client, filled)
  await settled()
  const { inventory } = read(bot)
  assert.equal(inventory.length, MAX_SENSE_STACKS)
  assert.equal(MAX_SENSE_STACKS, MAX_SLOT_INDEX + 1)
  assert.deepEqual(inventory.map((held) => held.slot), filled.map(([slot]) => slot))
  // Strictly increasing, which the contract requires and which is a property of the
  // ascending walk rather than of a sort to be trusted.
  for (let at = 1; at < inventory.length; at += 1) {
    assert.ok(inventory[at].slot > inventory[at - 1].slot, `slot ${at}`)
  }
  // An emptied slot is absent rather than a stack of nothing, which is what makes every
  // count the answer carries at least one.
  emptied(client, BREAD_SLOT)
  await settled()
  const trimmed = read(bot).inventory
  assert.equal(trimmed.length, MAX_SENSE_STACKS - 1)
  assert.equal(trimmed.some((held) => held.slot === BREAD_SLOT), false)
  assert.ok(trimmed.every((held) => held.count >= 1))
})

test('one meal is brought to hand, used once, and counted from the server own report', async () => {
  // The favourable packet order: the slot is restated *before* the use is reported complete,
  // so the count `consume` resolves on is already the post-meal one. The unfavourable order
  // is the case below, and the suite could once not tell the two apart.
  const { bot, client } = await standing({ vitals: HUNGRY })
  stocked(client, [[BREAD_SLOT, 'bread', 3]])
  await settled()
  client.writes.length = 0
  const eating = eatFromSlot(bot, {
    offered: offering([[BREAD_SLOT, 'bread', 3]]),
    slot: BREAD_SLOT
  })
  await settled()
  // The library brought the stack to the held hotbar slot. That is why `remaining` is
  // counted in the hand rather than in the slot the request named: the move empties it.
  assert.equal(bot.inventory.slots[BREAD_SLOT], null)
  assert.equal(bot.heldItem.name, 'bread')
  assert.equal(bot.quickBarSlot + QUICK_BAR_START, QUICK_BAR_START)
  // The server's own report of the finished use, and its own decrement of the stack. The
  // count is read back from the library rather than worked out here, because vanilla owns
  // how many items a use costs.
  client.emit('set_slot', {
    windowId: 0,
    stateId: 2,
    slot: QUICK_BAR_START,
    item: Item.toNotch(stack('bread', 2))
  })
  used(client, 'bread')
  assert.deepEqual(await promptly(eating, 'the meal'), {
    outcome: 'eaten',
    what: 'bread',
    remaining: 2
  })
  // Two clicks to bring the stack to hand, and exactly one use of it. A second `use_item`
  // here would be a second item eaten for one request, which is the failure no reading of
  // the inventory afterwards could distinguish from a server that gave one back.
  assert.deepEqual(written(client), ['window_click', 'window_click', 'use_item'])
})

test('a completion ahead of the slot update is still counted from the slot', async () => {
  const { bot, client } = await standing({ vitals: HUNGRY })
  stocked(client, [[BREAD_SLOT, 'bread', 3]])
  await settled()
  const eating = eatFromSlot(bot, {
    offered: offering([[BREAD_SLOT, 'bread', 3]]),
    slot: BREAD_SLOT
  })
  await settled()
  // The unfavourable order, and the one a tick boundary makes ordinary: vanilla completes
  // the use inside the entity tick and broadcasts the container change at the end of the
  // same tick, so `consume` resolves with the library's window still holding three.
  used(client, 'bread')
  await settled()
  assert.equal(bot.heldItem.count, 3, 'the case must reach the wait with the count unmoved')
  // Read the instant `consume` resolved, `remaining` was that stale three - on the one field
  // a run counts consumption from. So the slot's own restatement is waited for.
  restated(client, 'bread')
  assert.deepEqual(await promptly(eating, 'the meal'), {
    outcome: 'eaten',
    what: 'bread',
    remaining: 2
  })
})

test('the last loaf of a stack is counted as nothing left', async () => {
  const { bot, client } = await standing({ vitals: HUNGRY })
  stocked(client, [[BREAD_SLOT, 'bread', 1]])
  await settled()
  const eating = eatFromSlot(bot, {
    offered: offering([[BREAD_SLOT, 'bread', 1]]),
    slot: BREAD_SLOT
  })
  await settled()
  // Same order, and the reading that matters most: an agent that has just eaten its last
  // loaf must not be told it still has one, which is what the pre-meal count said.
  used(client, 'bread')
  await settled()
  restated(client, 'bread', { remaining: 0 })
  assert.deepEqual(await promptly(eating, 'the last loaf'), {
    outcome: 'eaten',
    what: 'bread',
    remaining: 0
  })
})

test('a full food bar is the library own refusal and costs the agent nothing', async () => {
  const { bot, client } = await standing({ vitals: FED })
  stocked(client, [[BREAD_SLOT, 'bread', 3]])
  await settled()
  client.writes.length = 0
  const report = await eatFromSlot(bot, {
    offered: offering([[BREAD_SLOT, 'bread', 3]]),
    slot: BREAD_SLOT
  })
  // This is the case the constant `FULL_FOOD_MESSAGE` is matched against: the real library
  // throwing its own text on its own rule. A version bump that reworded it fails here
  // rather than turning every full stomach into a fault to go and look at.
  assert.equal(report.outcome, 'reserveFull')
  // Brought to hand and then declined, so the stack is still whole and nothing was used.
  assert.equal(bot.heldItem.name, 'bread')
  assert.equal(bot.heldItem.count, 3)
  assert.equal(written(client).includes('use_item'), false)
})

test('a food vanilla always accepts is eaten on a full bar', async () => {
  const { bot, client } = await standing({ vitals: FED })
  stocked(client, [[BREAD_SLOT, 'golden_apple', 1]])
  await settled()
  const eating = eatFromSlot(bot, {
    offered: offering([[BREAD_SLOT, 'golden_apple', 1]]),
    slot: BREAD_SLOT
  })
  await settled()
  consumed(client, 'golden_apple', { remaining: 0 })
  const report = await promptly(eating, 'the golden apple')
  // Vanilla's rule for a full bar is not `food === 20`, which is exactly why this module
  // asks the library rather than pre-checking the number itself. A pre-check would have to
  // restate the exception, and would refuse a meal the game accepts.
  assert.equal(report.outcome, 'eaten')
  assert.equal(report.what, 'golden_apple')
})

test('a stack the game does not call food is refused before anything is brought to hand', async () => {
  const { bot, client } = await standing({ vitals: HUNGRY })
  stocked(client, [[BREAD_SLOT, 'iron_pickaxe', 1]])
  await settled()
  client.writes.length = 0
  const report = await eatFromSlot(bot, {
    offered: offering([[BREAD_SLOT, 'iron_pickaxe', 1]]),
    slot: BREAD_SLOT
  })
  // Edibility is minecraft-data's own food table for the version the server runs, so there
  // is no second table here to fall behind it.
  assert.equal(report.outcome, 'notEdible')
  assert.equal(report.what, undefined)
  // Settled before the stack moved, which is what keeps a refusal from costing an agent its
  // inventory - the whole reason the checks are ordered the way they are.
  assert.deepEqual(written(client), [])
  assert.equal(bot.inventory.slots[BREAD_SLOT].count, 1)
})

test('an emptied slot, a changed slot and an unoffered slot are three separate refusals', async () => {
  const { bot, client } = await standing({ vitals: HUNGRY })
  const offered = offering([[BREAD_SLOT, 'bread', 3]])
  stocked(client, [[BREAD_SLOT, 'bread', 3]])
  await settled()
  // A slot handle names a *position*, so the position is read again at the meal. Something
  // else ate the stack, or moved it, between the sense answer and the decision.
  emptied(client, BREAD_SLOT)
  await settled()
  assert.deepEqual(await eatFromSlot(bot, { offered, slot: BREAD_SLOT }), { outcome: 'slotEmpty' })
  // The position is occupied again and by something else. Read as `slotEmpty` this would
  // say the agent had nothing there; read as `eaten` it would eat whatever arrived.
  stocked(client, [[BREAD_SLOT, 'iron_pickaxe', 1]])
  await settled()
  assert.deepEqual(await eatFromSlot(bot, { offered, slot: BREAD_SLOT }), { outcome: 'slotChanged' })
  // A slot the sense answer never offered is a handle the mind invented rather than named
  // back, and is refused whatever happens to be standing in it.
  stocked(client, [[BREAD_SLOT + 1, 'bread', 3]])
  await settled()
  assert.deepEqual(
    await eatFromSlot(bot, { offered, slot: BREAD_SLOT + 1 }),
    { outcome: 'slotUnnamed' }
  )
  assert.deepEqual(written(client).filter((name) => name === 'use_item'), [])
})

test('a meal the server never completes is refused as inaccessible', async () => {
  const { bot, client } = await standing({ vitals: HUNGRY })
  stocked(client, [[BREAD_SLOT, 'bread', 3]])
  await settled()
  // No `set_cooldown` and no `entity_status`, so the library's own consume timeout is what
  // ends this. `slotInaccessible` is the residual outcome, and it is the one that cannot
  // promise the food is still there - which is why every refusal that *can* promise it is
  // settled before the two library calls rather than folded in here.
  const report = await promptly(
    eatFromSlot(bot, {
      offered: offering([[BREAD_SLOT, 'bread', 3]]),
      slot: BREAD_SLOT
    }),
    'the abandoned meal',
    10_000
  )
  assert.equal(report.outcome, 'slotInaccessible')
  assert.equal(report.what, undefined)
})

test('a whole sense answer for a crowded scene fits one frame', async () => {
  const { body, bot, client, port } = await inTheWorld(await embodied())
  // The most a sense answer can carry: every slot of the window holding the longest item
  // name in the registry, and the entity bound filled with the longest entity name. The
  // encoder's own widest frame is pinned in `protocol.test.js`; what is proven here is
  // that the answer this body produces for a crowded scene stays inside the bound.
  stocked(client, Array.from({ length: MAX_SENSE_STACKS }, (_, slot) => [slot, LONGEST_ITEM, 1]))
  for (let at = 0; at < MAX_SENSE_OFFERS_PER_KIND + 6; at += 1) {
    spotted(client, {
      id: 100 + at,
      what: LONGEST_ENTITY,
      x: FEET.x + ((at % 6) - 2.5) * 0.2,
      y: FEET.y,
      z: FEET.z + (Math.floor(at / 6) - 4) * 0.2
    })
  }
  await settled()
  const peer = await handshaken(port)
  const line = await promptly(
    peer.answer(request('sense', 's1', { radius: RADIUS, limit: MAX_SENSE_OFFERS_PER_KIND }))
      .then((frame) => ({ frame, bytes: Buffer.byteLength(JSON.stringify(frame), 'utf8') })),
    'the crowded sense answer'
  )
  const { frame, bytes } = line
  assert.equal(frame.op, 'sense')
  assert.equal(frame.inventory.length, MAX_SENSE_STACKS)
  assert.equal(frame.entities.length, MAX_SENSE_OFFERS_PER_KIND)
  // Crowded *and* trimmed, so the answer states both bounds it met at once.
  assert.equal(frame.entitiesOmitted, 6)
  assert.ok(bytes < MAX_FRAME_BYTES, `${bytes} bytes of ${MAX_FRAME_BYTES}`)
  assert.ok(bot.entity !== undefined)
  peer.stop()
  await body.stop()
})

test('a sense before the body is in the world is refused as not ready', async () => {
  const { body, port } = await embodied()
  const peer = await handshaken(port)
  // No login and no health report, so there is nothing to read. A body that answered a
  // reading here would be answering from defaults, which is a scene it never saw.
  const refusal = await promptly(
    peer.answer(request('sense', 's1', { radius: RADIUS, limit: LIMIT })),
    'the refusal'
  )
  // A refusal carries the code in `error` and no `op` at all: a frame that named an
  // operation as well would read as an answer to it rather than as a refusal of it.
  assert.equal(refusal.error, 'notReady')
  assert.equal('op' in refusal, false)
  peer.stop()
  await body.stop()
})

test('a sense that throws below this process is refused rather than ending it', async () => {
  const { body, bot, client, port } = await inTheWorld(await embodied())
  spotted(client, { id: 42, what: 'zombie', x: FEET.x + 2, y: FEET.y, z: FEET.z })
  stocked(client, [[BREAD_SLOT, 'bread', 3]])
  await settled()
  const peer = await handshaken(port)
  // One of the library calls a sense answer is read through, broken the way something below
  // this process can break. The whole of an answer - the reading and its encoding - runs
  // inside a socket `data` listener, so an escaping throw is not a refused request but the
  // end of the process, taking every other controller's connection with it.
  bot.world.raycast = () => {
    throw new Error(BROKEN_LIBRARY)
  }
  const refusal = await promptly(
    peer.answer(request('sense', 's1', { radius: RADIUS, limit: LIMIT })),
    'the refusal'
  )
  // The refusal this body already gives for a reading it cannot state, rather than a new
  // outcome: to a controller both are one fact, which is to ask again.
  assert.equal(refusal.error, 'notReady')
  assert.equal('op' in refusal, false)
  // And nothing from below this process rides out on it. A stack trace quotes whatever it
  // was handed, which on this path is a scene the body was reading.
  assert.equal(JSON.stringify(refusal).includes(BROKEN_LIBRARY), false)
  // Still answering, which is the property the guard exists for. A different operation on
  // the same connection proves the process survived rather than that one frame was
  // tolerated.
  const after = await promptly(peer.answer(request('status', 'q1')), 'the status after it')
  assert.equal(after.op, 'status')
  // And an answer that never went out left no slot handle behind, so a meal naming it is
  // refused instead of resolving against an inventory no controller was ever shown.
  const meal = await ate(peer, client, { requestId: 'e1', senseId: 's1', slot: BREAD_SLOT })
  assert.equal(meal.outcome, 'senseForgotten')
  peer.stop()
  await body.stop()
})

test('only the newest few sense answers can still name a slot', async () => {
  const { body, client, port } = await inTheWorld(await embodied(), { vitals: HUNGRY })
  stocked(client, [[BREAD_SLOT, 'bread', 3]])
  await settled()
  const peer = await handshaken(port)
  const senses = Array.from({ length: MAX_REMEMBERED_SENSES + 1 }, (_, at) => `s${at + 1}`)
  for (const senseId of senses) {
    const answer = await promptly(
      peer.answer(request('sense', senseId, { radius: RADIUS, limit: LIMIT })),
      `sense ${senseId}`
    )
    assert.equal(answer.op, 'sense')
    assert.equal(answer.inventory[0].slot, BREAD_SLOT)
  }
  // A slot handle is scoped to the round trip that minted it, and the bound on that scope
  // is a count of recent answers rather than a clock. The oldest of the five is gone.
  const stale = await ate(peer, client, { requestId: 'e1', senseId: senses[0], slot: BREAD_SLOT })
  assert.equal(stale.outcome, 'senseForgotten')
  // The next-oldest still resolves, which is what proves the eviction was the bound rather
  // than the memory failing to hold anything at all.
  const fresh = await ate(peer, client, {
    requestId: 'e2',
    senseId: senses[1],
    slot: BREAD_SLOT,
    what: 'bread'
  })
  assert.equal(fresh.outcome, 'eaten')
  assert.equal(fresh.what, 'bread')
  peer.stop()
  await body.stop()
})

test('a slot handle cannot name another connection\'s sense answer', async () => {
  const { body, client, port } = await inTheWorld(await embodied(), { vitals: HUNGRY })
  stocked(client, [[BREAD_SLOT, 'bread', 3]])
  await settled()
  const first = await handshaken(port)
  const second = await handshaken(port)
  await promptly(first.answer(request('sense', 's1', { radius: RADIUS, limit: LIMIT })), 'the first sense')
  const offered = await promptly(
    second.answer(request('sense', 's2', { radius: RADIUS, limit: LIMIT })),
    'the second sense'
  )
  assert.equal(offered.inventory[0].slot, BREAD_SLOT)
  // A round trip belongs to one connection as well as to one request, so an id this
  // controller never used is a handle it never held - whatever another peer was offered
  // under it.
  const foreign = await ate(first, client, { requestId: 'e1', senseId: 's2', slot: BREAD_SLOT })
  assert.equal(foreign.outcome, 'senseForgotten')
  // And the connection that was in fact offered the slot still resolves it, which is what
  // proves the refusal was the scoping rather than the memory holding nothing.
  const meal = await ate(second, client, {
    requestId: 'e1',
    senseId: 's2',
    slot: BREAD_SLOT,
    what: 'bread'
  })
  assert.equal(meal.outcome, 'eaten')
  first.stop()
  second.stop()
  await body.stop()
})

test('two connections may use one request id and each keeps its own answer', async () => {
  const { body, client, port } = await inTheWorld(await embodied(), { vitals: HUNGRY })
  stocked(client, [[BREAD_SLOT, 'iron_pickaxe', 1]])
  await settled()
  const first = await handshaken(port)
  const answered = await promptly(
    first.answer(request('sense', 's1', { radius: RADIUS, limit: LIMIT })),
    'the first sense'
  )
  assert.equal(answered.inventory[0].what, 'iron_pickaxe')
  // The slot's contents change, and a second controller senses under the *same* request id.
  // Nothing refuses that: the duplicate-id guard is per connection, because a request id is
  // the controller's own name for its round trip and two controllers share no numbering.
  stocked(client, [[BREAD_SLOT, 'bread', 3]])
  await settled()
  const second = await handshaken(port)
  const replaced = await promptly(
    second.answer(request('sense', 's1', { radius: RADIUS, limit: LIMIT })),
    'the second sense'
  )
  assert.equal(replaced.inventory[0].what, 'bread')
  // The first connection's `s1` still means what its own answer offered, so the slot reads
  // as the changed position it is. Keyed on the id alone this was `eaten`: the body ate a
  // stack the sense it named never offered, and reported the meal as ordinary.
  const changed = await ate(first, client, { requestId: 'e1', senseId: 's1', slot: BREAD_SLOT })
  assert.equal(changed.outcome, 'slotChanged')
  // The second's own `s1` resolves all the same, so one id names two entries side by side.
  const meal = await ate(second, client, {
    requestId: 'e1',
    senseId: 's1',
    slot: BREAD_SLOT,
    what: 'bread'
  })
  assert.equal(meal.outcome, 'eaten')
  assert.equal(meal.what, 'bread')
  first.stop()
  second.stop()
  await body.stop()
})

test('a second meal while one is running is refused as not ready', async () => {
  const { body, client, port } = await inTheWorld(await embodied(), { vitals: HUNGRY })
  stocked(client, [[BREAD_SLOT, 'bread', 3]])
  await settled()
  const peer = await handshaken(port)
  await promptly(peer.answer(request('sense', 's1', { radius: RADIUS, limit: LIMIT })), 'the sense')
  peer.send(request('eat', 'e1', { senseId: 's1', slot: BREAD_SLOT }))
  await settled()
  // One meal at a time, because there is one hand: two overlapping consumes would have the
  // library cancel the first, and the agent would be told two meals happened.
  //
  // More of them than `MAX_REFUSALS_PER_CONNECTION` allows of a peer error, on a channel
  // that stays open throughout: a mind asking while the body's mouth is full is not abuse,
  // and counted as abuse eight overlapping meals closed the channel and took every slot
  // handle on it with them.
  for (let extra = 2; extra <= MAX_REFUSALS_PER_CONNECTION + 2; extra += 1) {
    peer.send(request('eat', `e${extra}`, { senseId: 's1', slot: BREAD_SLOT }))
    const refusal = await promptly(peer.read(), `the e${extra} meal refusal`)
    assert.equal(refusal.error, 'notReady')
    assert.equal('op' in refusal, false)
    assert.equal(refusal.requestId, `e${extra}`)
  }
  // The first meal is still running and still answers, so the refusals cost it nothing.
  consumed(client, 'bread')
  const report = await promptly(peer.read(), 'the first meal report')
  assert.equal(report.requestId, 'e1')
  assert.equal(report.outcome, 'eaten')
  peer.stop()
  await body.stop()
})

test('a meal the server never accounts for is still eaten, on the count as it stands', async () => {
  const { body, client, port } = await inTheWorld(await embodied(), { vitals: HUNGRY })
  stocked(client, [[BREAD_SLOT, 'bread', 3]])
  await settled()
  const peer = await handshaken(port)
  await promptly(peer.answer(request('sense', 's1', { radius: RADIUS, limit: LIMIT })), 'the sense')
  peer.send(request('eat', 'e1', { senseId: 's1', slot: BREAD_SLOT }))
  await settled()
  // The use completes and the slot is never restated, so the wait runs out. The meal happened
  // all the same, and nothing after `bot.consume` resolves may be refused: the only refusal
  // available is `notReady`, which on this wire means ask again, and there is nothing safe to
  // ask again for once the food is gone. So the outcome is `eaten` and `remaining` is the
  // latest observation rather than a promised delta.
  used(client, 'bread')
  const report = await promptly(peer.read(), 'the unaccounted meal', SLOT_SETTLE_MS + 5_000)
  assert.equal(report.op, 'eat')
  assert.equal(report.requestId, 'e1')
  assert.equal(report.outcome, 'eaten')
  assert.equal(report.what, 'bread')
  assert.equal(typeof report.remaining, 'number')
  // Exactly one item was consumed whatever the count says, which is the claim `remaining`
  // cannot make on its own.
  assert.deepEqual(written(client).filter((name) => name === 'use_item'), ['use_item'])
  const after = await promptly(peer.answer(request('status', 'q1')), 'the status after it')
  assert.equal(after.op, 'status')
  peer.stop()
  await body.stop()
})

test('a meal from a hotbar slot is eaten once and cannot be re-sent for a second', async () => {
  const { body, bot, client, port } = await inTheWorld(await embodied(), { vitals: HUNGRY })
  // In the held slot itself, which is the case that needs the handle spent: `bot.equip` sees
  // the stack is already where the hand is and moves nothing, so no window click is written
  // and the named slot still reads the whole pre-meal stack once the meal is over.
  stocked(client, [[QUICK_BAR_START, 'bread', 3]], { quickBarSlot: 0 })
  await settled()
  const peer = await handshaken(port)
  const sensed = await promptly(
    peer.answer(request('sense', 's1', { radius: RADIUS, limit: LIMIT })),
    'the sense'
  )
  assert.equal(sensed.inventory[0].slot, QUICK_BAR_START)
  client.writes.length = 0
  peer.send(request('eat', 'e1', { senseId: 's1', slot: QUICK_BAR_START }))
  await settled()
  used(client, 'bread')
  const first = await promptly(peer.read(), 'the first meal', SLOT_SETTLE_MS + 5_000)
  assert.equal(first.outcome, 'eaten')
  assert.deepEqual(written(client), ['use_item'], 'no click, and exactly one use')
  assert.equal(
    bot.inventory.slots[QUICK_BAR_START].count,
    3,
    'the slot must still read the pre-meal stack for this case to prove anything'
  )

  // The mind sends the same eat again. One answer's offer of one slot buys one meal, so this
  // is refused rather than resolved - and nothing is consumed. Left unspent, the re-read
  // passes (the name has not changed) and a second loaf went for one decision.
  const repeat = await promptly(
    peer.answer(request('eat', 'e2', { senseId: 's1', slot: QUICK_BAR_START })),
    'the repeat'
  )
  assert.equal(repeat.outcome, 'slotUnnamed')
  assert.deepEqual(written(client), ['use_item'], 'the repeat must consume nothing')
  peer.stop()
  await body.stop()
})

test('a spent handle is refused before the slot is read, whatever the equip left there', async () => {
  const { body, bot, client, port } = await inTheWorld(await embodied(), { vitals: HUNGRY })
  // A main-inventory slot this time, which `bot.equip` empties by moving the stack to the
  // hand - so the slot the handle names reads as empty once the meal is over. The refusal is
  // still `slotUnnamed` and deliberately not `slotEmpty`: the handle check comes first
  // because an unresolvable handle names no slot to look in, and a spent handle is exactly
  // that. Buying the slot-true label would mean spending after the re-read, which is the
  // check order this wire states is load-bearing.
  stocked(client, [[BREAD_SLOT, 'bread', 3], [BREAD_SLOT + 1, 'iron_pickaxe', 1]])
  await settled()
  const peer = await handshaken(port)
  const sensed = await promptly(
    peer.answer(request('sense', 's1', { radius: RADIUS, limit: LIMIT })),
    'the sense'
  )
  assert.deepEqual(sensed.inventory.map((held) => held.slot), [BREAD_SLOT, BREAD_SLOT + 1])
  const first = await ate(peer, client, {
    requestId: 'e1',
    senseId: 's1',
    slot: BREAD_SLOT,
    what: 'bread'
  })
  assert.equal(first.outcome, 'eaten')
  assert.equal(bot.inventory.slots[BREAD_SLOT], null, 'the equip must have emptied the slot')
  client.writes.length = 0

  const repeat = await promptly(
    peer.answer(request('eat', 'e2', { senseId: 's1', slot: BREAD_SLOT })),
    'the repeat'
  )
  assert.equal(repeat.outcome, 'slotUnnamed')
  assert.deepEqual(written(client), [], 'the repeat must consume nothing')
  // Only the slot a meal was served from is spent. The answer's other offer still resolves,
  // which its refusal proves by being about the *stack* rather than about the handle: reaching
  // `notEdible` means the handle check let it through.
  const other = await promptly(
    peer.answer(request('eat', 'e3', { senseId: 's1', slot: BREAD_SLOT + 1 })),
    'another slot of the same answer'
  )
  assert.equal(other.outcome, 'notEdible')
  assert.deepEqual(written(client), [], 'and nothing was brought to hand for it')
  peer.stop()
  await body.stop()
})

test('a post-meal count the field will not carry is bounded into it, not lost', async () => {
  const { body, bot, client, port } = await inTheWorld(await embodied(), { vitals: HUNGRY })
  // The hotbar again, so a retry would really reach `bot.consume` a second time: the named
  // slot keeps reading `bread` whatever the count does.
  stocked(client, [[QUICK_BAR_START, 'bread', 3]], { quickBarSlot: 0 })
  await settled()
  const peer = await handshaken(port)
  const sensed = await promptly(
    peer.answer(request('sense', 's1', { radius: RADIUS, limit: LIMIT })),
    'the sense'
  )
  assert.equal(sensed.inventory[0].count, 3, 'the sense answer must be a legal one')
  client.writes.length = 0
  peer.send(request('eat', 'e1', { senseId: 's1', slot: QUICK_BAR_START }))
  await settled()
  used(client, 'bread')
  await settled()
  // The server restates the held slot with a count the wire's own stack bound will not carry,
  // which `Slot.itemCount` being a varint makes reachable after the sense answer was given.
  // Lost rather than bounded, this left no answer for a meal that had already happened - so
  // the request was refused `notReady` and the handle stayed live for a second loaf.
  const overStacked = OVER_STACKED_COUNT
  assert.ok(overStacked > MAX_STACK_COUNT)
  client.emit('set_slot', {
    windowId: 0,
    stateId: 2,
    slot: QUICK_BAR_START,
    item: Item.toNotch(stack('bread', overStacked))
  })
  assert.equal(bot.inventory.slots[QUICK_BAR_START].count, overStacked, 'the library must carry it')
  const report = await promptly(peer.read(), 'the over-stacked meal', SLOT_SETTLE_MS + 5_000)
  assert.equal(report.op, 'eat')
  assert.equal(report.outcome, 'eaten')
  assert.equal(report.what, 'bread')
  assert.equal(report.remaining, MAX_STACK_COUNT, 'capped, which is as much as the field can say')
  assert.deepEqual(written(client), ['use_item'], 'exactly one use')

  // And the handle is spent, so no path after a resolved consume leaves a second meal open.
  const repeat = await promptly(
    peer.answer(request('eat', 'e2', { senseId: 's1', slot: QUICK_BAR_START })),
    'the repeat'
  )
  assert.equal(repeat.outcome, 'slotUnnamed')
  assert.deepEqual(written(client), ['use_item'], 'the repeat must consume nothing')
  peer.stop()
  await body.stop()
})

test('a meal whose vitals stop being statable is answered with the reading it was admitted under', async () => {
  const { body, client, port } = await inTheWorld(await embodied(), { vitals: HUNGRY })
  // The hotbar, so a retry would really reach `bot.consume` a second time.
  stocked(client, [[QUICK_BAR_START, 'bread', 3]], { quickBarSlot: 0 })
  await settled()
  const peer = await handshaken(port)
  await promptly(peer.answer(request('sense', 's1', { radius: RADIUS, limit: LIMIT })), 'the sense')
  client.writes.length = 0
  peer.send(request('eat', 'e1', { senseId: 's1', slot: QUICK_BAR_START }))
  await settled()
  // Mid-meal, a server plugin raises the food level past vanilla's own ceiling - nothing
  // clamps it - so the three vitals can no longer be stated together. Read at write time and
  // refused for it, this told the controller to ask again for a loaf that was already gone.
  client.emit('update_health', { health: 20, food: MAX_FOOD + 10, foodSaturation: 0 })
  used(client, 'bread')
  const report = await promptly(peer.read(), 'the meal', SLOT_SETTLE_MS + 5_000)
  assert.equal(report.op, 'eat')
  assert.equal(report.outcome, 'eaten')
  assert.equal(report.what, 'bread')
  // The reading the request was admitted under, whole and unmixed - never the unstatable one.
  assert.deepEqual(report.vitals, { health: 20, food: HUNGRY.food, saturation: HUNGRY.foodSaturation })
  assert.deepEqual(written(client), ['use_item'], 'exactly one use')

  // And the handle is spent all the same, so the retry the old refusal invited eats nothing.
  // The body is let state itself again first, or the retry would be refused for that instead
  // and would prove nothing about the handle.
  client.emit('update_health', { health: 20, food: HUNGRY.food, foodSaturation: 0 })
  await settled()
  const repeat = await promptly(
    peer.answer(request('eat', 'e2', { senseId: 's1', slot: QUICK_BAR_START })),
    'the repeat'
  )
  assert.equal(repeat.outcome, 'slotUnnamed')
  assert.deepEqual(written(client), ['use_item'], 'the repeat must consume nothing')
  peer.stop()
  await body.stop()
})

test('a body that dies mid-meal is still told it ate, and not in the dead body\'s numbers', async () => {
  const { body, client, port } = await inTheWorld(await embodied(), { vitals: HUNGRY })
  stocked(client, [[QUICK_BAR_START, 'bread', 3]], { quickBarSlot: 0 })
  await settled()
  const peer = await handshaken(port)
  await promptly(peer.answer(request('sense', 's1', { radius: RADIUS, limit: LIMIT })), 'the sense')
  peer.send(request('eat', 'e1', { senseId: 's1', slot: QUICK_BAR_START }))
  await settled()
  // Killed while the meal is in flight: the library's health plugin reports it, so the
  // process leaves `ready`. The meal still happened and is still answered.
  client.emit('update_health', { health: 0, food: HUNGRY.food, foodSaturation: 0 })
  await settled()
  assert.equal(body.state(), 'dead', 'the case must reach the write with the body out of ready')
  used(client, 'bread')
  const report = await promptly(peer.read(), 'the meal', SLOT_SETTLE_MS + 5_000)
  assert.equal(report.op, 'eat')
  assert.equal(report.outcome, 'eaten')
  // An eat is answered outside the frame that asked for it, so `state` and `vitals` are both
  // the moment the request was admitted. A dead body's readings are perfectly statable and
  // are not this answer's body, so they are not what it carries.
  assert.equal(report.state, 'ready')
  assert.deepEqual(report.vitals, { health: 20, food: HUNGRY.food, saturation: HUNGRY.foodSaturation })
  // And it is a frame the far end accepts, which is what makes the `ready` legal rather than
  // merely written.
  assert.equal(decodeResponse(JSON.stringify(report)).ok, true)
  peer.stop()
  await body.stop()
})

test('an eat asked of a body whose vitals cannot be stated is refused before anything is eaten', async () => {
  const { body, client, port } = await inTheWorld(await embodied(), { vitals: HUNGRY })
  stocked(client, [[QUICK_BAR_START, 'bread', 3]], { quickBarSlot: 0 })
  await settled()
  const peer = await handshaken(port)
  await promptly(peer.answer(request('sense', 's1', { radius: RADIUS, limit: LIMIT })), 'the sense')
  // Unstatable *before* the meal, which is the one time `notReady` is honest on this path:
  // nothing has been consumed, so asking again costs the agent nothing.
  client.emit('update_health', { health: 20, food: MAX_FOOD + 10, foodSaturation: 0 })
  await settled()
  client.writes.length = 0
  const refusal = await promptly(
    peer.answer(request('eat', 'e1', { senseId: 's1', slot: QUICK_BAR_START })),
    'the refusal'
  )
  assert.equal(refusal.error, 'notReady')
  assert.equal('op' in refusal, false)
  assert.deepEqual(written(client), [], 'nothing was brought to hand and nothing was used')
  // The handle is untouched, so the meal the mind asked for is still there to be taken once
  // the body can state itself again.
  client.emit('update_health', { health: 20, food: HUNGRY.food, foodSaturation: 0 })
  await settled()
  const meal = await ate(peer, client, {
    requestId: 'e2',
    senseId: 's1',
    slot: QUICK_BAR_START,
    what: 'bread'
  })
  assert.equal(meal.outcome, 'eaten')
  peer.stop()
  await body.stop()
})

test('a creative body is still told it ate, though vanilla consumes nothing', async () => {
  const { body, client, port } = await inTheWorld(
    await embodied(),
    { vitals: HUNGRY, gamemode: 'creative' }
  )
  stocked(client, [[BREAD_SLOT, 'bread', 3]])
  await settled()
  const peer = await handshaken(port)
  await promptly(peer.answer(request('sense', 's1', { radius: RADIUS, limit: LIMIT })), 'the sense')
  peer.send(request('eat', 'e1', { senseId: 's1', slot: BREAD_SLOT }))
  await settled()
  // Vanilla takes no item in creative, so it restates no slot and every meal reaches the
  // deadline. A body that reported a refusal there could never be told it ate at all.
  used(client, 'bread')
  const report = await promptly(peer.read(), 'the creative meal', SLOT_SETTLE_MS + 5_000)
  assert.equal(report.op, 'eat')
  assert.equal(report.outcome, 'eaten')
  assert.equal(report.what, 'bread')
  assert.equal(report.remaining, 3, 'creative keeps the stack, and the count says so')
  peer.stop()
  await body.stop()
})

test('an eat naming another agent is refused before any slot is resolved', async () => {
  const { body, client, port } = await inTheWorld(await embodied(), { vitals: HUNGRY })
  stocked(client, [[BREAD_SLOT, 'bread', 3]])
  await settled()
  const peer = await handshaken(port)
  await promptly(peer.answer(request('sense', 's1', { radius: RADIUS, limit: LIMIT })), 'the sense')
  client.writes.length = 0
  // A handle is scoped to one agent as well as to one request. This body's own sense answer
  // offered the slot, so nothing but the agent id makes this wrong - and it is refused at
  // the envelope, before the operation is reached at all.
  const refusal = await promptly(
    peer.answer({ ...request('eat', 'e1', { senseId: 's1', slot: BREAD_SLOT }), agentId: OTHER_AGENT }),
    'the refusal'
  )
  assert.equal(refusal.error, 'wrongAgent')
  assert.equal('op' in refusal, false)
  assert.deepEqual(written(client), [])
  peer.stop()
  await body.stop()
})

test('a starving body eats nothing and asks for no respawn of its own accord', async () => {
  const { body, client } = await inTheWorld(await embodied(), { vitals: { health: 20, food: 0, foodSaturation: 0 } })
  stocked(client, [[BREAD_SLOT, 'bread', 3]])
  await settled()
  client.writes.length = 0
  // Starving, with food in the inventory and nobody asking for a meal. There is no
  // threshold, no timer, no target selection and no auto-eat plugin, so the body does
  // nothing at all - which is what makes eating a decision the mind owns.
  await settled(WATCHED_MS)
  assert.deepEqual(client.writes, [])
  // And still nothing after a death: `respawn: false` means a dead body waits to be told,
  // rather than putting itself back in the world.
  client.emit('update_health', { health: 0, food: 0, foodSaturation: 0 })
  await settled(WATCHED_MS)
  assert.deepEqual(client.writes, [])
  await body.stop()
})
