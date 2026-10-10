'use strict'

/**
 * The four explicit native world interactions, below and across the control wire.
 *
 * Unit-level cases use Mineflayer-shaped objects so every closed outcome can be driven
 * without a server clock. The wire case then proves that the same exact offers are scoped
 * to one sense answer and one connection, and that all four operations can complete through
 * the body process without any target or inventory choice being made below the request.
 */

const assert = require('node:assert/strict')
const { EventEmitter } = require('node:events')
const { test } = require('node:test')

const { Vec3 } = require('vec3')

const { collectOfferedItem } = require('../src/collecting')
const { digOfferedBlock } = require('../src/digging')
const { equipOfferedStack } = require('../src/equipping')
const { stackDetail } = require('../src/interaction')
const { placeHeldBlock } = require('../src/placing')
const { senseMemory } = require('../src/senses')
const { senseReading } = require('../src/sensing')
const {
  eventually,
  fakeBot,
  handshaken,
  promptly,
  request,
  startedBody
} = require('./harness')

const FEET = new Vec3(0.5, 64, 0.5)
const HOTBAR_SLOT = 36
const INVENTORY_SLOT = 20

function itemStack (what, count, details = {}) {
  return {
    name: what,
    count,
    type: details.type ?? 1,
    metadata: details.metadata ?? 0,
    nbt: details.nbt ?? null,
    components: details.components ?? null,
    removedComponents: details.removedComponents ?? null
  }
}

function airBlock (position) {
  return {
    name: 'air',
    type: 0,
    stateId: 0,
    diggable: false,
    position: position.floored()
  }
}

function solidBlock (
  position,
  what = 'stone',
  { type = 1, stateId = type, diggable = true } = {}
) {
  return {
    name: what,
    type,
    stateId,
    diggable,
    position: position.floored()
  }
}

function blockKey (position) {
  const whole = position.floored()
  return `${whole.x},${whole.y},${whole.z}`
}

function worldBot (bot = new EventEmitter()) {
  const blocks = new Map()
  bot.entity = {
    id: 7,
    name: 'player',
    position: FEET.clone(),
    eyeHeight: 1.62
  }
  bot.entities = { 7: bot.entity }
  bot.health = 20
  bot.food = 12
  bot.foodSaturation = 2
  bot.time = { timeOfDay: 6000, day: 1, isDay: true }
  bot.inventory = { slots: Array(46).fill(null) }
  bot.QUICK_BAR_START = HOTBAR_SLOT
  bot.quickBarSlot = 0
  bot.registry = {
    blocksByName: {
      dirt: { id: 3 },
      stone: { id: 1 }
    },
    foodsByName: {}
  }
  bot._client = bot._client ?? new EventEmitter()
  bot._client.writes = []
  bot._client.write = (name, parameters) => {
    bot._client.writes.push({ name, parameters })
  }
  bot.blockAt = (position) => blocks.get(blockKey(position)) ?? airBlock(position)
  bot.world = {
    raycast: () => bot.raycastHit ?? null
  }
  bot.getEquipmentDestSlot = (destination) => ({
    hand: HOTBAR_SLOT + bot.quickBarSlot,
    'off-hand': 45,
    head: 5,
    torso: 6,
    legs: 7,
    feet: 8
  })[destination]
  Object.defineProperty(bot, 'heldItem', {
    configurable: true,
    get: () => bot.inventory.slots[HOTBAR_SLOT + bot.quickBarSlot]
  })
  bot.putBlock = (block) => {
    blocks.set(blockKey(block.position), block)
    return block
  }
  bot.removeBlock = (position) => {
    blocks.delete(blockKey(position))
  }
  return bot
}

function blockOffer (block) {
  return {
    what: block.name,
    position: [block.position.x, block.position.y, block.position.z],
    type: block.type,
    stateId: block.stateId
  }
}

function slotOffers (slot, stack) {
  return new Map([[
    slot,
    {
      what: stack.name,
      count: stack.count,
      detail: stackDetail(stack)
    }
  ]])
}

function droppedEntity (id, position, stack) {
  let standing = stack
  return {
    id,
    name: 'item',
    position,
    isValid: true,
    getDroppedItem: () => standing,
    replace: (replacement) => {
      standing = replacement
    }
  }
}

function itemOffer (entity) {
  const stack = entity.getDroppedItem()
  return {
    id: entity.id,
    entity,
    what: stack.name,
    count: stack.count,
    detail: stackDetail(stack),
    gone: false
  }
}

async function reportOf (running, what = 'the interaction') {
  assert.notEqual(running, undefined)
  return promptly(running.promise, what)
}

test('sense offers blocks and dropped items independently and retains only private authority', () => {
  const bot = worldBot()
  bot.putBlock(solidBlock(new Vec3(0, 63, 0)))
  bot.putBlock(solidBlock(new Vec3(1, 64, 0), 'dirt', { type: 3 }))
  bot.putBlock(solidBlock(new Vec3(-1, 64, 0), 'stone', { type: 1, stateId: 2 }))
  const first = droppedEntity(40, new Vec3(1.5, 64, 0.5), itemStack('apple', 2))
  const second = droppedEntity(41, new Vec3(-1.5, 64, 0.5), itemStack('bread', 1))
  bot.entities[40] = first
  bot.entities[41] = second
  bot.entities[42] = {
    id: 42,
    name: 'zombie',
    position: new Vec3(0.5, 64, 2.5),
    height: 1.95
  }

  const sensed = senseReading(bot, { radius: 5, limit: 1 })
  assert.notEqual(sensed, undefined)
  assert.equal(sensed.reading.entities.length, 1)
  assert.equal(sensed.reading.blocks.length, 1)
  assert.equal(sensed.reading.items.length, 1)
  assert.equal(sensed.reading.entitiesOmitted, 0)
  assert.ok(sensed.reading.blocksOmitted >= 1)
  assert.equal(sensed.reading.itemsOmitted, 1)
  assert.equal(sensed.reading.entities[0].what, 'zombie')
  assert.equal('id' in sensed.reading.items[0], false)
  assert.equal('type' in sensed.reading.blocks[0], false)
  assert.equal(sensed.blockOffers[0].type, 1)
  assert.equal(sensed.itemOffers[0].entity, first)
  assert.equal(typeof sensed.itemOffers[0].detail, 'string')
})

test('sense memory scopes every offered authority to its connection and bounded lifetime', () => {
  const memory = senseMemory()
  const first = {}
  const foreign = {}
  const block = solidBlock(new Vec3(1, 64, 0))
  const stack = itemStack('dirt', 2)
  const item = droppedEntity(40, new Vec3(1, 64, 1), itemStack('apple', 1))
  const offers = {
    slotOffers: [{
      slot: INVENTORY_SLOT,
      what: stack.name,
      count: stack.count,
      detail: stackDetail(stack)
    }],
    entityOffers: [],
    blockOffers: [blockOffer(block)],
    itemOffers: [itemOffer(item)]
  }

  memory.remember(first, 'sense-1', offers)
  assert.equal(memory.recall(foreign, 'sense-1'), undefined)
  assert.equal(memory.recall(first, 'sense-1').blockOffers[0].what, 'stone')
  memory.spendBlock(first, 'sense-1', 0)
  memory.spendItem(first, 'sense-1', 0)
  assert.equal(memory.recall(first, 'sense-1').blockOffers[0], undefined)
  assert.equal(memory.recall(first, 'sense-1').itemOffers[0], undefined)

  for (let at = 2; at <= 6; at += 1) {
    memory.remember(first, `sense-${at}`, offers)
  }
  assert.equal(memory.recall(first, 'sense-1'), undefined)
})

test('dig revalidates the exact block, reach, sight, and tool before one attempt', async () => {
  const bot = worldBot()
  const target = bot.putBlock(solidBlock(new Vec3(1, 64, 0)))
  const offered = [blockOffer(target)]
  bot.inventory.slots[HOTBAR_SLOT] = itemStack('iron_pickaxe', 1)
  bot.digTime = () => 25
  let dug
  bot.dig = async (block, face) => {
    dug = { block, face }
    bot.removeBlock(block.position)
  }
  bot.stopDigging = () => {}

  const report = await reportOf(digOfferedBlock(bot, {
    offered,
    block: 0,
    timeoutMs: 100
  }))
  assert.deepEqual(report, {
    outcome: 'dug',
    elapsedMs: report.elapsedMs,
    attempted: true,
    tool: 'iron_pickaxe'
  })
  assert.equal(dug.block, target)
  assert.equal(dug.face, 'raycast')

  bot.putBlock(solidBlock(target.position, 'stone', { type: 1, stateId: 99 }))
  assert.equal((await reportOf(digOfferedBlock(bot, {
    offered,
    block: 0,
    timeoutMs: 100
  }))).outcome, 'blockChanged')
  bot.removeBlock(target.position)
  assert.equal((await reportOf(digOfferedBlock(bot, {
    offered,
    block: 0,
    timeoutMs: 100
  }))).outcome, 'blockGone')

  const far = bot.putBlock(solidBlock(new Vec3(8, 64, 0)))
  assert.equal((await reportOf(digOfferedBlock(bot, {
    offered: [blockOffer(far)],
    block: 0,
    timeoutMs: 100
  }))).outcome, 'outOfReach')

  const hidden = bot.putBlock(solidBlock(new Vec3(1, 64, 1)))
  bot.raycastHit = solidBlock(new Vec3(0, 64, 1))
  assert.equal((await reportOf(digOfferedBlock(bot, {
    offered: [blockOffer(hidden)],
    block: 0,
    timeoutMs: 100
  }))).outcome, 'notVisible')
})

test('dig timeout, halt, and teardown all stop the same bounded task', async () => {
  const bot = worldBot()
  const target = bot.putBlock(solidBlock(new Vec3(1, 64, 0)))
  bot.digTime = () => 1000
  bot.dig = () => new Promise(() => {})
  let stops = 0
  bot.stopDigging = () => { stops += 1 }

  const timed = digOfferedBlock(bot, {
    offered: [blockOffer(target)],
    block: 0,
    timeoutMs: 5
  })
  assert.equal((await reportOf(timed)).outcome, 'timedOut')
  assert.equal(stops, 1)
  assert.equal(timed.cancel(), false)

  const halted = digOfferedBlock(bot, {
    offered: [blockOffer(target)],
    block: 0,
    timeoutMs: 1000
  })
  assert.equal(halted.cancel(), true)
  assert.equal(halted.abort(), false)
  assert.equal((await reportOf(halted)).outcome, 'cancelled')
  assert.equal(stops, 2)
})

test('collect accepts only the exact dropped entity and exact server pickup count', async () => {
  const bot = worldBot()
  const entity = droppedEntity(40, new Vec3(1.5, 64, 0.5), itemStack('apple', 3))
  bot.entities[40] = entity
  let halts = 0
  const movement = {
    halt: () => {
      halts += 1
      return true
    }
  }
  const running = collectOfferedItem(bot, movement, {
    offered: [itemOffer(entity)],
    item: 0,
    approach: false,
    timeoutMs: 100,
    sprint: false,
    owner: {}
  })
  bot.emit('playerCollect', bot.entity, entity)
  bot._client.emit('collect', {
    collectorEntityId: bot.entity.id,
    collectedEntityId: entity.id,
    pickupItemCount: 2
  })
  const report = await reportOf(running)
  assert.equal(report.outcome, 'collected')
  assert.equal(report.what, 'apple')
  assert.equal(report.count, 2)
  assert.equal(report.travelled, 0)
  assert.equal(halts, 1)

  const changed = droppedEntity(41, new Vec3(1.5, 64, 0.5), itemStack('apple', 1))
  const changedOffer = itemOffer(changed)
  changed.replace(itemStack('apple', 1, { nbt: { changed: true } }))
  bot.entities[41] = changed
  assert.equal((await reportOf(collectOfferedItem(bot, movement, {
    offered: [changedOffer],
    item: 0,
    approach: false,
    timeoutMs: 100,
    sprint: false,
    owner: {}
  }))).outcome, 'itemChanged')

  const replacement = droppedEntity(40, entity.position, itemStack('apple', 3))
  bot.entities[40] = replacement
  assert.equal((await reportOf(collectOfferedItem(bot, movement, {
    offered: [itemOffer(entity)],
    item: 0,
    approach: false,
    timeoutMs: 100,
    sprint: false,
    owner: {}
  }))).outcome, 'itemGone')
})

test('collect stops when its exact dropped stack changes after the request starts', async () => {
  const bot = worldBot()
  const entity = droppedEntity(40, new Vec3(1.5, 64, 0.5), itemStack('apple', 3))
  bot.entities[40] = entity
  let halts = 0
  const running = collectOfferedItem(bot, {
    halt: () => {
      halts += 1
      return true
    }
  }, {
    offered: [itemOffer(entity)],
    item: 0,
    approach: false,
    timeoutMs: 100,
    sprint: false,
    owner: {}
  })

  entity.replace(itemStack('apple', 2))
  bot.emit('entityUpdate', entity)

  const report = await reportOf(running)
  assert.equal(report.outcome, 'itemChanged')
  assert.equal(halts, 1)
  assert.equal(bot.listenerCount('playerCollect'), 0)
  assert.equal(bot.listenerCount('entityUpdate'), 0)
})

test('collect refuses a changed stack even when the pickup packet wins the race', async () => {
  const bot = worldBot()
  const entity = droppedEntity(40, new Vec3(1.5, 64, 0.5), itemStack('apple', 3))
  bot.entities[40] = entity
  const running = collectOfferedItem(bot, { halt: () => true }, {
    offered: [itemOffer(entity)],
    item: 0,
    approach: false,
    timeoutMs: 100,
    sprint: false,
    owner: {}
  })

  entity.replace(itemStack('apple', 2))
  bot.emit('playerCollect', bot.entity, entity)
  bot._client.emit('collect', {
    collectorEntityId: bot.entity.id,
    collectedEntityId: entity.id,
    pickupItemCount: 2
  })

  assert.equal((await reportOf(running)).outcome, 'itemChanged')
})

test('collect approach, passive reach, deadline, and cancellation stay explicitly bounded', async () => {
  const bot = worldBot()
  const entity = droppedEntity(40, new Vec3(6.5, 64, 0.5), itemStack('apple', 1))
  bot.entities[40] = entity
  const offered = [itemOffer(entity)]

  const passive = await reportOf(collectOfferedItem(bot, { halt: () => false }, {
    offered,
    item: 0,
    approach: false,
    timeoutMs: 100,
    sprint: false,
    owner: {}
  }))
  assert.equal(passive.outcome, 'outOfRange')
  assert.equal(passive.travelled, 0)

  let destination
  const noRoute = {
    halt: () => false,
    moveTo: (request) => {
      destination = request.destination
      return Promise.resolve({
        outcome: 'noRoute',
        from: [0.5, 64, 0.5],
        position: [0.5, 64, 0.5],
        travelled: 0,
        elapsedMs: 5
      })
    }
  }
  const refused = await reportOf(collectOfferedItem(bot, noRoute, {
    offered,
    item: 0,
    approach: true,
    timeoutMs: 100,
    sprint: false,
    owner: {}
  }))
  assert.deepEqual(destination, [6.5, 64, 0.5])
  assert.equal(refused.outcome, 'noRoute')

  entity.position = new Vec3(1.5, 64, 0.5)
  const timed = collectOfferedItem(bot, { halt: () => true }, {
    offered,
    item: 0,
    approach: false,
    timeoutMs: 5,
    sprint: false,
    owner: {}
  })
  assert.equal((await reportOf(timed)).outcome, 'timedOut')

  const cancelled = collectOfferedItem(bot, { halt: () => true }, {
    offered,
    item: 0,
    approach: false,
    timeoutMs: 1000,
    sprint: false,
    owner: {}
  })
  assert.equal(cancelled.cancel(), true)
  assert.equal((await reportOf(cancelled)).outcome, 'cancelled')
})

test('place requires the exact offered stack already in hand and confirms one destination', async () => {
  const bot = worldBot()
  const support = bot.putBlock(solidBlock(new Vec3(1, 64, 0)))
  const held = itemStack('dirt', 2)
  bot.inventory.slots[HOTBAR_SLOT] = held
  let invocation
  bot._genericPlace = async (block, face, options) => {
    invocation = { block, face, options }
    const destination = block.position.plus(face)
    const placed = bot.putBlock(solidBlock(destination, 'dirt', { type: 3 }))
    bot.inventory.slots[HOTBAR_SLOT] = itemStack('dirt', 1)
    bot.emit(`blockUpdate:${destination}`, airBlock(destination), placed)
  }

  const report = await reportOf(placeHeldBlock(bot, {
    blockOffers: [blockOffer(support)],
    slots: slotOffers(HOTBAR_SLOT, held),
    block: 0,
    slot: HOTBAR_SLOT,
    face: 'up',
    timeoutMs: 100
  }))
  assert.equal(report.outcome, 'placed')
  assert.equal(report.what, 'dirt')
  assert.deepEqual(report.position, [1, 65, 0])
  assert.equal(report.remaining, 1)
  assert.equal(invocation.block, support)
  assert.equal(invocation.options.forceLook, 'ignore')
  assert.equal(invocation.options.swingArm, 'right')

  const changed = itemStack('dirt', 1)
  const staleSlots = slotOffers(HOTBAR_SLOT, changed)
  bot.inventory.slots[HOTBAR_SLOT] = itemStack('dirt', 1, { nbt: { changed: true } })
  assert.equal((await reportOf(placeHeldBlock(bot, {
    blockOffers: [blockOffer(support)],
    slots: staleSlots,
    block: 0,
    slot: HOTBAR_SLOT,
    face: 'east',
    timeoutMs: 100
  }))).outcome, 'slotChanged')
})

test('place reports the actual vanilla block when a held item changes form', async () => {
  const bot = worldBot()
  const support = bot.putBlock(solidBlock(new Vec3(1, 64, 0)))
  const held = itemStack('oak_sign', 1)
  bot.registry.blocksByName.oak_sign = { id: 63 }
  bot.inventory.slots[HOTBAR_SLOT] = held
  bot._genericPlace = async (block, face) => {
    const destination = block.position.plus(face)
    const placed = bot.putBlock(solidBlock(destination, 'oak_wall_sign', { type: 68 }))
    bot.inventory.slots[HOTBAR_SLOT] = null
    bot.emit(`blockUpdate:${destination}`, airBlock(destination), placed)
  }

  const report = await reportOf(placeHeldBlock(bot, {
    blockOffers: [blockOffer(support)],
    slots: slotOffers(HOTBAR_SLOT, held),
    block: 0,
    slot: HOTBAR_SLOT,
    face: 'east',
    timeoutMs: 100
  }))

  assert.equal(report.outcome, 'placed')
  assert.equal(report.what, 'oak_wall_sign')
  assert.deepEqual(report.position, [2, 64, 0])
  assert.equal(report.remaining, 0)
})

test('place refuses blocked or remote support and bounds an unacknowledged packet', async () => {
  const bot = worldBot()
  const held = itemStack('dirt', 1)
  bot.inventory.slots[HOTBAR_SLOT] = held
  const support = bot.putBlock(solidBlock(new Vec3(1, 64, 0)))
  bot.putBlock(solidBlock(new Vec3(1, 65, 0), 'stone'))
  const common = {
    blockOffers: [blockOffer(support)],
    slots: slotOffers(HOTBAR_SLOT, held),
    block: 0,
    slot: HOTBAR_SLOT,
    face: 'up',
    timeoutMs: 100
  }
  assert.equal((await reportOf(placeHeldBlock(bot, common))).outcome, 'destinationBlocked')

  const far = bot.putBlock(solidBlock(new Vec3(9, 64, 0)))
  assert.equal((await reportOf(placeHeldBlock(bot, {
    ...common,
    blockOffers: [blockOffer(far)]
  }))).outcome, 'outOfReach')

  bot.removeBlock(new Vec3(1, 65, 0))
  bot._genericPlace = () => new Promise(() => {})
  const timed = placeHeldBlock(bot, { ...common, timeoutMs: 5 })
  assert.equal(timed.cancel(), false)
  assert.equal((await reportOf(timed)).outcome, 'timedOut')

  const aborted = placeHeldBlock(bot, { ...common, timeoutMs: 1000 })
  assert.equal(aborted.abort(), true)
  assert.equal((await reportOf(aborted)).outcome, 'cancelled')
})

test('equip revalidates one offered stack and reports Mineflayer actual destination slot', async () => {
  const bot = worldBot()
  const offered = itemStack('iron_helmet', 1)
  bot.inventory.slots[INVENTORY_SLOT] = offered
  bot.equip = async (stack, destination) => {
    const to = bot.getEquipmentDestSlot(destination)
    bot.inventory.slots[INVENTORY_SLOT] = null
    bot.inventory.slots[to] = stack
  }
  const report = await reportOf(equipOfferedStack(bot, {
    slots: slotOffers(INVENTORY_SLOT, offered),
    slot: INVENTORY_SLOT,
    destination: 'head',
    timeoutMs: 100
  }))
  assert.equal(report.outcome, 'equipped')
  assert.equal(report.what, 'iron_helmet')
  assert.equal(report.count, 1)
  assert.equal(report.toSlot, 5)

  const changed = itemStack('dirt', 2)
  bot.inventory.slots[INVENTORY_SLOT] = changed
  const stale = slotOffers(INVENTORY_SLOT, changed)
  bot.inventory.slots[INVENTORY_SLOT] = itemStack('dirt', 2, { metadata: 1 })
  assert.equal((await reportOf(equipOfferedStack(bot, {
    slots: stale,
    slot: INVENTORY_SLOT,
    destination: 'hand',
    timeoutMs: 100
  }))).outcome, 'slotChanged')
})

test('equip distinguishes incompatibility, deadline, and teardown cancellation', async () => {
  const bot = worldBot()
  const stack = itemStack('dirt', 1)
  bot.inventory.slots[INVENTORY_SLOT] = stack
  const offered = slotOffers(INVENTORY_SLOT, stack)
  bot.equip = async () => {}
  assert.equal((await reportOf(equipOfferedStack(bot, {
    slots: offered,
    slot: INVENTORY_SLOT,
    destination: 'head',
    timeoutMs: 100
  }))).outcome, 'incompatible')

  bot.equip = () => new Promise(() => {})
  const timed = equipOfferedStack(bot, {
    slots: offered,
    slot: INVENTORY_SLOT,
    destination: 'hand',
    timeoutMs: 5
  })
  assert.equal((await reportOf(timed)).outcome, 'timedOut')

  const aborted = equipOfferedStack(bot, {
    slots: offered,
    slot: INVENTORY_SLOT,
    destination: 'hand',
    timeoutMs: 1000
  })
  assert.equal(aborted.cancel(), false)
  assert.equal(aborted.abort(), true)
  assert.equal((await reportOf(aborted)).outcome, 'cancelled')
})

test('all four operations cross the wire using only one connection own sense offers', async () => {
  const bot = worldBot(fakeBot())
  const digTarget = bot.putBlock(solidBlock(new Vec3(1, 64, 0)))
  bot.putBlock(solidBlock(new Vec3(0, 63, 0)))
  const dirt = itemStack('dirt', 2)
  bot.inventory.slots[INVENTORY_SLOT] = dirt
  const dropped = droppedEntity(40, new Vec3(1.5, 64, 0.5), itemStack('apple', 1))
  bot.entities[40] = dropped
  bot.digTime = () => 1
  bot.stopDigging = () => {}
  bot.dig = async (block) => {
    bot.removeBlock(block.position)
  }
  bot.equip = async (stack, destination) => {
    const to = bot.getEquipmentDestSlot(destination)
    bot.inventory.slots[INVENTORY_SLOT] = null
    bot.inventory.slots[to] = stack
  }
  bot._genericPlace = async (support, face) => {
    const destination = support.position.plus(face)
    const placed = bot.putBlock(solidBlock(destination, 'dirt', { type: 3 }))
    bot.inventory.slots[HOTBAR_SLOT] = itemStack('dirt', 1)
    bot.emit(`blockUpdate:${destination}`, airBlock(destination), placed)
  }

  const { body, port } = await startedBody({ bot })
  const first = await handshaken(port)
  const foreign = await handshaken(port)
  try {
    bot.emit('spawn')
    const sensed = await first.answer(request('sense', 'sense-dig', { radius: 5, limit: 8 }))
    const block = sensed.blocks.findIndex((it) =>
      it.position[0] === digTarget.position.x &&
      it.position[1] === digTarget.position.y &&
      it.position[2] === digTarget.position.z
    )
    assert.ok(block >= 0)

    const foreignRefusal = await foreign.answer(request('dig', 'foreign-dig', {
      senseId: 'sense-dig',
      block,
      timeoutMs: 100
    }))
    assert.equal(foreignRefusal.outcome, 'senseForgotten')

    const dug = await first.answer(request('dig', 'dig-1', {
      senseId: 'sense-dig',
      block,
      timeoutMs: 100
    }))
    assert.equal(dug.outcome, 'dug')
    assert.equal(dug.attempted, true)

    const equipSense = await first.answer(request('sense', 'sense-equip', {
      radius: 5,
      limit: 8
    }))
    assert.ok(equipSense.inventory.some((it) => it.slot === INVENTORY_SLOT))
    const equipped = await first.answer(request('equip', 'equip-1', {
      senseId: 'sense-equip',
      slot: INVENTORY_SLOT,
      destination: 'hand',
      timeoutMs: 100
    }))
    assert.equal(equipped.outcome, 'equipped')
    assert.equal(equipped.toSlot, HOTBAR_SLOT)

    const placeSense = await first.answer(request('sense', 'sense-place', {
      radius: 5,
      limit: 8
    }))
    const support = placeSense.blocks.findIndex((it) =>
      it.position[0] === 0 && it.position[1] === 63 && it.position[2] === 0
    )
    assert.ok(support >= 0)
    const placed = await first.answer(request('place', 'place-1', {
      senseId: 'sense-place',
      block: support,
      slot: HOTBAR_SLOT,
      face: 'east',
      timeoutMs: 100
    }))
    assert.equal(placed.outcome, 'placed')
    assert.deepEqual(placed.position, [1, 63, 0])

    const collectSense = await first.answer(request('sense', 'sense-collect', {
      radius: 5,
      limit: 8
    }))
    assert.equal(collectSense.items.length, 1)
    first.send(request('collect', 'collect-1', {
      senseId: 'sense-collect',
      item: 0,
      approach: false,
      timeoutMs: 100,
      sprint: false
    }))
    await eventually(() => {
      assert.equal(bot.listenerCount('playerCollect'), 1)
    })
    bot.emit('playerCollect', bot.entity, dropped)
    bot._client.emit('collect', {
      collectorEntityId: bot.entity.id,
      collectedEntityId: dropped.id,
      pickupItemCount: 1
    })
    const collected = await first.read()
    assert.equal(collected.outcome, 'collected')
    assert.equal(collected.what, 'apple')
    assert.equal(collected.count, 1)
  } finally {
    first.stop()
    foreign.stop()
    await body.stop()
  }
})

test('conflicting requests are refused and halt, death, and shutdown clean an owner', async () => {
  const bot = worldBot(fakeBot())
  const target = bot.putBlock(solidBlock(new Vec3(1, 64, 0)))
  bot.putBlock(solidBlock(new Vec3(0, 63, 0)))
  bot.inventory.slots[INVENTORY_SLOT] = itemStack('dirt', 1)
  bot.digTime = () => 1000
  bot.stopDigging = () => {}
  let digCalls = 0
  bot.dig = () => {
    digCalls += 1
    return new Promise(() => {})
  }
  bot.equip = () => new Promise(() => {})

  const { body, port } = await startedBody({ bot })
  const owner = await handshaken(port)
  const peer = await handshaken(port)
  try {
    bot.emit('spawn')
    const sensed = await owner.answer(request('sense', 'sense-1', { radius: 5, limit: 8 }))
    const block = sensed.blocks.findIndex((it) =>
      it.position[0] === target.position.x &&
      it.position[1] === target.position.y &&
      it.position[2] === target.position.z
    )
    owner.send(request('dig', 'dig-1', {
      senseId: 'sense-1',
      block,
      timeoutMs: 1000
    }))
    await eventually(() => assert.equal(digCalls, 1))
    const conflict = await peer.answer(request('equip', 'equip-conflict', {
      senseId: 'sense-1',
      slot: INVENTORY_SLOT,
      destination: 'hand',
      timeoutMs: 100
    }))
    assert.equal(conflict.error, 'notReady')
    const halt = await peer.answer(request('halt', 'halt-1'))
    assert.equal(halt.stopped, true)
    assert.equal((await owner.read()).outcome, 'cancelled')

    const again = await owner.answer(request('sense', 'sense-2', { radius: 5, limit: 8 }))
    const againBlock = again.blocks.findIndex((it) =>
      it.position[0] === target.position.x &&
      it.position[1] === target.position.y &&
      it.position[2] === target.position.z
    )
    owner.send(request('dig', 'dig-2', {
      senseId: 'sense-2',
      block: againBlock,
      timeoutMs: 1000
    }))
    await eventually(() => assert.equal(digCalls, 2))
    bot.emit('death')
    assert.equal((await owner.read()).outcome, 'cancelled')
    assert.equal(body.state(), 'dead')

    bot.emit('spawn')
    const finalSense = await owner.answer(request('sense', 'sense-3', { radius: 5, limit: 8 }))
    const finalBlock = finalSense.blocks.findIndex((it) =>
      it.position[0] === target.position.x &&
      it.position[1] === target.position.y &&
      it.position[2] === target.position.z
    )
    owner.send(request('dig', 'dig-3', {
      senseId: 'sense-3',
      block: finalBlock,
      timeoutMs: 1000
    }))
    await eventually(() => assert.equal(digCalls, 3))
    const shutdown = await peer.answer(request('shutdown', 'shutdown-1'))
    assert.equal(shutdown.state, 'stopping')
    assert.equal((await owner.read()).outcome, 'cancelled')
  } finally {
    owner.stop()
    peer.stop()
    await body.stop()
  }
})
