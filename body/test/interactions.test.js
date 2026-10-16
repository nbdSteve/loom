'use strict'

/**
 * The seven explicit native world interactions, below and across the control wire.
 *
 * Unit-level cases use Mineflayer-shaped objects so every closed outcome can be driven
 * without a server clock. The wire case then proves that the same exact offers are scoped
 * to one sense answer and one connection, and that all seven operations can complete through
 * the body process without any target or inventory choice being made below the request.
 */

const assert = require('node:assert/strict')
const { EventEmitter } = require('node:events')
const { test } = require('node:test')

const { Vec3 } = require('vec3')

const { collectOfferedItem } = require('../src/collecting')
const {
  ATTACK_REACH_BLOCKS,
  attackOfferedTarget,
  offeredTarget
} = require('../src/combat')
const {
  craftOfferedRecipe,
  recipeReading
} = require('../src/crafting')
const { digOfferedBlock } = require('../src/digging')
const { equipOfferedStack } = require('../src/equipping')
const { stackDetail } = require('../src/interaction')
const { placeHeldBlock } = require('../src/placing')
const { MAX_SENSE_OFFERS_PER_KIND } = require('../src/protocol')
const { senseMemory } = require('../src/senses')
const { INTERACTION_REACH_BLOCKS, senseReading } = require('../src/sensing')
const { sleepInOfferedBed } = require('../src/sleeping')
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
  bot.game = { dimension: 'overworld', gameMode: 'survival' }
  bot.inventory = {
    slots: Array(46).fill(null),
    count: (type, metadata) =>
      bot.inventory.slots.reduce((count, stack) =>
        count + (
          stack?.type === type &&
          (metadata === null || metadata === undefined || stack.metadata === metadata)
            ? stack.count
            : 0
        ), 0)
  }
  bot.QUICK_BAR_START = HOTBAR_SLOT
  bot.quickBarSlot = 0
  bot.registry = {
    recipes: {},
    items: {},
    entities: {},
    blocksByName: {
      dirt: { id: 3 },
      stone: { id: 1 }
    },
    foodsByName: {}
  }
  bot.recipesAll = () => []
  bot._client = bot._client ?? new EventEmitter()
  bot._client.writes = []
  bot._client.write = (name, parameters) => {
    bot._client.writes.push({ name, parameters })
  }
  bot.clickWindow = bot.clickWindow ?? (async () => {})
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

/** A dig the server accepted: the block goes, and the server says so at that position. */
function serverBroke (bot, position) {
  bot.removeBlock(position)
  bot._client.emit('block_change', { location: { x: position.x, y: position.y, z: position.z }, type: 0 })
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

function livingEntity (id, position, what = 'zombie', entityType = 54) {
  return {
    id,
    name: what,
    type: 'hostile',
    entityType,
    position,
    height: 1.95,
    isValid: true
  }
}

function entityOffer (entity) {
  return {
    id: entity.id,
    entity,
    dead: false
  }
}

function craftingSetup (
  bot,
  { logs = 3, requiresTable = false, tablePosition = new Vec3(1, 64, 0) } = {}
) {
  const recipe = {
    result: { id: 5, metadata: 0, count: 4 },
    inShape: null,
    ingredients: [{ id: 17, metadata: 0, count: 1 }],
    outShape: null,
    delta: [
      { id: 17, metadata: 0, count: -1 },
      { id: 5, metadata: 0, count: 4 }
    ],
    requiresTable
  }
  bot.inventory.slots[INVENTORY_SLOT] = itemStack('oak_log', logs, { type: 17 })
  bot.registry.items[5] = { name: 'oak_planks' }
  bot.registry.recipes = { 5: [recipe] }
  bot.recipesAll = () => [recipe]
  bot.recipesFor = (resultId, metadata, resultCount, table) => {
    const needed = Math.ceil(resultCount / recipe.result.count)
    if (resultId !== recipe.result.id ||
        metadata !== recipe.result.metadata ||
        (requiresTable && table === null) ||
        bot.inventory.count(17, 0) < needed) return []
    return [recipe]
  }
  let table
  let blockOffers = []
  if (requiresTable) {
    table = bot.putBlock(
      solidBlock(tablePosition, 'crafting_table', { type: 58, stateId: 58 })
    )
    blockOffers = [{ ...blockOffer(table), visible: true }]
  }
  const reading = recipeReading(bot, { blockOffers, limit: 8 })
  assert.notEqual(reading, undefined)
  assert.equal(reading.recipes.length, 1)
  return { recipe, table, reading }
}

function applyCraft (bot) {
  const log = bot.inventory.slots[INVENTORY_SLOT]
  assert.ok(log?.count > 0)
  bot.inventory.slots[INVENTORY_SLOT] =
    log.count === 1 ? null : itemStack('oak_log', log.count - 1, { type: 17 })
  const resultSlot = INVENTORY_SLOT + 1
  const held = bot.inventory.slots[resultSlot]
  bot.inventory.slots[resultSlot] = itemStack(
    'oak_planks',
    (held?.count ?? 0) + 4,
    { type: 5 }
  )
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

test('sense retains forty-eight entities blocks and items with exact per-kind omissions', () => {
  const bot = worldBot()
  const offeredPerKind = MAX_SENSE_OFFERS_PER_KIND + 7
  const eyes = FEET.offset(0, bot.entity.eyeHeight, 0)
  const blockPositions = []
  for (let x = -4; x <= 4 && blockPositions.length < offeredPerKind; x += 1) {
    for (let y = 61; y <= 67 && blockPositions.length < offeredPerKind; y += 1) {
      for (let z = -4; z <= 4 && blockPositions.length < offeredPerKind; z += 1) {
        const position = new Vec3(x, y, z)
        if (position.offset(0.5, 0.5, 0.5).distanceTo(eyes) <= INTERACTION_REACH_BLOCKS) {
          blockPositions.push(position)
        }
      }
    }
  }
  assert.equal(blockPositions.length, offeredPerKind, 'the bounded scene must contain enough blocks')
  blockPositions.forEach((position) => bot.putBlock(solidBlock(position)))

  for (let index = 0; index < offeredPerKind; index += 1) {
    const position = FEET.offset((index % 5) * 0.1, 0, Math.floor(index / 5) * 0.1)
    const living = livingEntity(1_000 + index, position)
    const dropped = droppedEntity(2_000 + index, position.offset(0.05, 0, 0.05), itemStack('apple', 1))
    bot.entities[living.id] = living
    bot.entities[dropped.id] = dropped
  }

  // The wire refuses 49, and the collector itself still clamps a direct/future caller
  // before any response validation has to save it.
  const sensed = senseReading(bot, { radius: 24, limit: MAX_SENSE_OFFERS_PER_KIND + 1 })
  assert.notEqual(sensed, undefined)
  for (const field of ['entities', 'blocks', 'items']) {
    assert.equal(sensed.reading[field].length, MAX_SENSE_OFFERS_PER_KIND, field)
    assert.equal(sensed.reading[`${field}Omitted`], 7, `${field} omissions`)
  }
  assert.equal(sensed.entityOffers.length, MAX_SENSE_OFFERS_PER_KIND)
  assert.equal(sensed.blockOffers.length, MAX_SENSE_OFFERS_PER_KIND)
  assert.equal(sensed.itemOffers.length, MAX_SENSE_OFFERS_PER_KIND)
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
  // A spent offer leaves a hole, and Mineflayer's entity_destroy that follows every pickup
  // reaches these listeners from inside its packet handler: a throw there is the client's
  // `error`, which failed the whole body and stopped its packets after every collection.
  const second = droppedEntity(41, new Vec3(2, 64, 1), itemStack('apple', 1))
  memory.remember(first, 'sense-2', { ...offers, entityOffers: [], itemOffers: [itemOffer(second)] })
  memory.spendItem(first, 'sense-2', 0)
  memory.remember(first, 'sense-3', { ...offers, itemOffers: [itemOffer(second)] })
  assert.doesNotThrow(() => memory.sawEntityGone(item))
  assert.doesNotThrow(() => memory.sawEntityGone(second))
  assert.doesNotThrow(() => memory.sawEntityDead(second))
  assert.equal(memory.recall(first, 'sense-3').itemOffers[0].gone, true)

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
    serverBroke(bot, block.position)
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

test('a dig is dug only once the server itself has removed the block', async () => {
  const bot = worldBot()
  const target = bot.putBlock(solidBlock(new Vec3(1, 64, 0)))
  const offered = [blockOffer(target)]
  bot.digTime = () => 25
  bot.stopDigging = () => {}

  // Mineflayer's prediction alone: the block is air locally and nothing from the server.
  bot.dig = async (block) => { bot.removeBlock(block.position) }
  assert.equal((await reportOf(digOfferedBlock(bot, { offered, block: 0, timeoutMs: 100 }))).outcome,
    'libraryFailure')

  // The server refused the finish and put the block back.
  bot.putBlock(target)
  bot.dig = async (block) => {
    bot.removeBlock(block.position)
    setImmediate(() => {
      bot.putBlock(target)
      bot._client.emit('block_change', { location: { x: 1, y: 64, z: 0 }, type: target.stateId })
    })
  }
  assert.equal((await reportOf(digOfferedBlock(bot, { offered, block: 0, timeoutMs: 1000 }))).outcome,
    'libraryFailure')

  // The server's own answer arrives after the prediction and confirms it.
  bot.dig = async (block) => {
    bot.removeBlock(block.position)
    setImmediate(() => bot._client.emit('block_change', { location: { x: 1, y: 64, z: 0 }, type: 0 }))
  }
  assert.equal((await reportOf(digOfferedBlock(bot, { offered, block: 0, timeoutMs: 1000 }))).outcome, 'dug')
})

test('a server multi-block change at the offered position confirms a dig, and one elsewhere does not', async () => {
  const bot = worldBot()
  bot.supportFeature = (feature) => ['usesMultiblockSingleLong', 'usesMultiblock3DChunkCoords'].includes(feature)
  const target = bot.putBlock(solidBlock(new Vec3(1, 64, 0)))
  const offered = [blockOffer(target)]
  bot.digTime = () => 25
  bot.stopDigging = () => {}
  // One record packs a section-local position as x << 8 | z << 4 | y and the state above it.
  const record = (x, y, z, state) => (state << 12) | (x << 8) | (z << 4) | y
  const section = { x: 0, y: 4, z: 0 }
  bot.dig = async (block) => {
    bot.removeBlock(block.position)
    setImmediate(() => {
      // A neighbour's change first, which says nothing about the offered block.
      bot._client.emit('multi_block_change', { chunkCoordinates: section, records: [record(2, 0, 0, 0)] })
      setImmediate(() => bot._client.emit('multi_block_change', {
        chunkCoordinates: section,
        records: [record(5, 0, 5, 1), record(1, 0, 0, 0)]
      }))
    })
  }
  assert.equal((await reportOf(digOfferedBlock(bot, { offered, block: 0, timeoutMs: 1000 }))).outcome, 'dug')
  // A neighbour's change alone leaves the prediction unconfirmed.
  bot.putBlock(target)
  bot.dig = async (block) => {
    bot.removeBlock(block.position)
    setImmediate(() => bot._client.emit('multi_block_change', { chunkCoordinates: section, records: [record(2, 0, 0, 0)] }))
  }
  assert.equal((await reportOf(digOfferedBlock(bot, { offered, block: 0, timeoutMs: 200 }))).outcome, 'libraryFailure')
  // A body on an older wire, whose records spell the position as fields.
  bot.putBlock(target)
  bot.supportFeature = () => false
  bot.dig = async (block) => {
    bot.removeBlock(block.position)
    setImmediate(() => bot._client.emit('multi_block_change', {
      chunkX: 0, chunkZ: 0, records: [{ horizontalPos: (1 << 4) | 0, y: 64, blockId: 0 }]
    }))
  }
  assert.equal((await reportOf(digOfferedBlock(bot, { offered, block: 0, timeoutMs: 1000 }))).outcome, 'dug')
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

test('craft applies only one retained recipe and revalidates ingredients and workstation', async () => {
  const bot = worldBot()
  const { recipe, reading } = craftingSetup(bot, { logs: 2 })
  let applications = 0
  bot.currentWindow = null
  bot.closeWindow = () => {}
  bot.craft = async (selected, count, table) => {
    assert.equal(selected, recipe)
    assert.equal(count, 1)
    assert.equal(table, null)
    applications += 1
    applyCraft(bot)
  }

  const report = await reportOf(craftOfferedRecipe(bot, {
    offered: reading.recipeOffers,
    recipe: 0,
    crafts: 2,
    timeoutMs: 100
  }))
  assert.deepEqual(report, {
    outcome: 'crafted',
    elapsedMs: report.elapsedMs,
    attempted: true,
    what: 'oak_planks',
    count: 8,
    crafts: 2
  })
  assert.equal(applications, 2)
  assert.equal(bot.inventory.count(17, 0), 0)
  assert.equal(bot.inventory.count(5, 0), 8)

  const staleBot = worldBot()
  const stale = craftingSetup(staleBot)
  staleBot.inventory.slots[INVENTORY_SLOT] =
    itemStack('oak_log', 3, { type: 17, nbt: { changed: true } })
  assert.deepEqual(
    await reportOf(craftOfferedRecipe(staleBot, {
      offered: stale.reading.recipeOffers,
      recipe: 0,
      crafts: 1,
      timeoutMs: 100
    })),
    {
      outcome: 'ingredientsChanged',
      elapsedMs: 0,
      attempted: false
    }
  )

  assert.equal((await reportOf(craftOfferedRecipe(staleBot, {
    offered: undefined,
    recipe: 0,
    crafts: 1,
    timeoutMs: 100
  }))).outcome, 'senseForgotten')
  assert.equal((await reportOf(craftOfferedRecipe(staleBot, {
    offered: stale.reading.recipeOffers,
    recipe: 7,
    crafts: 1,
    timeoutMs: 100
  }))).outcome, 'recipeUnnamed')
})

test('craft refuses a missing, changed, remote, or hidden retained workstation', async () => {
  const goneBot = worldBot()
  const gone = craftingSetup(goneBot, { requiresTable: true })
  goneBot.removeBlock(gone.table.position)
  assert.equal((await reportOf(craftOfferedRecipe(goneBot, {
    offered: gone.reading.recipeOffers,
    recipe: 0,
    crafts: 1,
    timeoutMs: 100
  }))).outcome, 'workstationGone')

  const changedBot = worldBot()
  const changed = craftingSetup(changedBot, { requiresTable: true })
  changedBot.putBlock(
    solidBlock(changed.table.position, 'crafting_table', { type: 58, stateId: 59 })
  )
  assert.equal((await reportOf(craftOfferedRecipe(changedBot, {
    offered: changed.reading.recipeOffers,
    recipe: 0,
    crafts: 1,
    timeoutMs: 100
  }))).outcome, 'workstationChanged')

  const farBot = worldBot()
  const far = craftingSetup(farBot, {
    requiresTable: true,
    tablePosition: new Vec3(8, 64, 0)
  })
  assert.equal((await reportOf(craftOfferedRecipe(farBot, {
    offered: far.reading.recipeOffers,
    recipe: 0,
    crafts: 1,
    timeoutMs: 100
  }))).outcome, 'workstationOutOfReach')

  const hiddenBot = worldBot()
  const hidden = craftingSetup(hiddenBot, { requiresTable: true })
  hiddenBot.raycastHit = solidBlock(new Vec3(0, 64, 1))
  assert.equal((await reportOf(craftOfferedRecipe(hiddenBot, {
    offered: hidden.reading.recipeOffers,
    recipe: 0,
    crafts: 1,
    timeoutMs: 100
  }))).outcome, 'workstationNotVisible')
})

test('craft confirms the exact recipe inventory delta before reporting success', async () => {
  const unchangedBot = worldBot()
  const unchanged = craftingSetup(unchangedBot)
  unchangedBot.craft = async () => {}
  assert.equal((await reportOf(craftOfferedRecipe(unchangedBot, {
    offered: unchanged.reading.recipeOffers,
    recipe: 0,
    crafts: 1,
    timeoutMs: 100
  }))).outcome, 'libraryFailure')

  const collateralBot = worldBot()
  const collateral = craftingSetup(collateralBot)
  collateralBot.craft = async () => {
    applyCraft(collateralBot)
    collateralBot.inventory.slots[INVENTORY_SLOT + 2] = itemStack('dirt', 1, { type: 3 })
  }
  assert.equal((await reportOf(craftOfferedRecipe(collateralBot, {
    offered: collateral.reading.recipeOffers,
    recipe: 0,
    crafts: 1,
    timeoutMs: 100
  }))).outcome, 'libraryFailure')

  const mutatedBot = worldBot()
  const mutated = craftingSetup(mutatedBot, { logs: 2 })
  mutatedBot.craft = async () => {
    applyCraft(mutatedBot)
    mutatedBot.inventory.slots[INVENTORY_SLOT] =
      itemStack('oak_log', 1, { type: 17, nbt: { changed: true } })
  }
  assert.equal((await reportOf(craftOfferedRecipe(mutatedBot, {
    offered: mutated.reading.recipeOffers,
    recipe: 0,
    crafts: 1,
    timeoutMs: 100
  }))).outcome, 'libraryFailure')
})

test('craft cancellation and deadline close one window and bound an in-flight application', async () => {
  const beforeBot = worldBot()
  const before = craftingSetup(beforeBot)
  let beforeCalls = 0
  let beforeCloses = 0
  beforeBot.currentWindow = {}
  beforeBot.closeWindow = () => { beforeCloses += 1 }
  beforeBot.craft = async () => { beforeCalls += 1 }
  const cancelledBefore = craftOfferedRecipe(beforeBot, {
    offered: before.reading.recipeOffers,
    recipe: 0,
    crafts: 1,
    timeoutMs: 1000
  })
  assert.equal(cancelledBefore.cancel(), true)
  const beforeReport = await reportOf(cancelledBefore)
  assert.equal(beforeReport.outcome, 'cancelled')
  assert.equal(beforeReport.attempted, false)
  assert.equal(beforeCalls, 0)
  assert.equal(beforeCloses, 1)

  const duringBot = worldBot()
  const during = craftingSetup(duringBot)
  let releaseClick
  let duringCalls = 0
  let duringCloses = 0
  let duringDisconnects = 0
  duringBot.currentWindow = {}
  duringBot.closeWindow = () => { duringCloses += 1 }
  duringBot._client.end = () => { duringDisconnects += 1 }
  duringBot.clickWindow = () => new Promise((resolve, reject) => {
    releaseClick = () => {
      try {
        duringBot._client.write('window_click', { slot: 1 })
        resolve()
      } catch (failure) {
        reject(failure)
      }
    }
  })
  duringBot.craft = () => {
    duringCalls += 1
    return duringBot.clickWindow(1, 0, 0)
  }
  const cancelledDuring = craftOfferedRecipe(duringBot, {
    offered: during.reading.recipeOffers,
    recipe: 0,
    crafts: 2,
    timeoutMs: 1000,
    cleanupGraceMs: 50
  })
  await eventually(() => assert.equal(duringCalls, 1))
  await eventually(() => assert.equal(typeof releaseClick, 'function'))
  assert.equal(cancelledDuring.cancel(), true)
  releaseClick()
  const duringReport = await reportOf(cancelledDuring)
  assert.equal(duringReport.outcome, 'cancelled')
  assert.equal(duringReport.attempted, true)
  assert.equal(duringCloses, 1)
  await eventually(() => assert.equal(duringDisconnects, 1))
  assert.equal(
    duringBot._client.writes.some(({ name }) => name === 'window_click'),
    false
  )

  const timedBot = worldBot()
  const timed = craftingSetup(timedBot)
  let timedDisconnects = 0
  timedBot.currentWindow = {}
  timedBot.closeWindow = () => {}
  timedBot._client.end = () => { timedDisconnects += 1 }
  timedBot.craft = () => new Promise(() => {})
  const timedReport = await reportOf(craftOfferedRecipe(timedBot, {
    offered: timed.reading.recipeOffers,
    recipe: 0,
    crafts: 1,
    timeoutMs: 5,
    cleanupGraceMs: 5
  }))
  assert.equal(timedReport.outcome, 'timedOut')
  assert.equal(timedReport.attempted, true)
  await eventually(() => assert.equal(timedDisconnects, 1))
})

test('combat keeps one living target, weapon, range, and health authority', async () => {
  const bot = worldBot()
  const target = livingEntity(40, new Vec3(2.5, 64, 0.5))
  bot.entities[target.id] = target
  const weapon = itemStack('iron_sword', 1, { type: 267 })
  bot.inventory.slots[INVENTORY_SLOT] = weapon
  bot.equip = async (stack) => {
    bot.inventory.slots[INVENTORY_SLOT] = null
    bot.inventory.slots[HOTBAR_SLOT] = stack
  }
  const struck = []
  bot.attack = (entity, swing) => struck.push({ entity, swing })
  const movement = { halt: () => false }
  const report = await reportOf(attackOfferedTarget(bot, movement, {
    entityOffers: [entityOffer(target)],
    slots: slotOffers(INVENTORY_SLOT, weapon),
    entity: 0,
    slot: INVENTORY_SLOT,
    attacks: 1,
    approach: false,
    maxPursuitDistance: 7.5,
    minHealth: 4.5,
    timeoutMs: 1000,
    sprint: false,
    owner: {}
  }))
  assert.equal(report.outcome, 'attacked')
  assert.equal(report.attacks, 1)
  assert.equal(report.travelled, 0)
  assert.deepEqual(struck, [{ entity: target, swing: true }])

  const replacement = livingEntity(target.id, target.position.clone())
  bot.entities[target.id] = replacement
  assert.equal((await reportOf(attackOfferedTarget(bot, movement, {
    entityOffers: [entityOffer(target)],
    slots: slotOffers(HOTBAR_SLOT, weapon),
    entity: 0,
    slot: HOTBAR_SLOT,
    attacks: 1,
    approach: false,
    maxPursuitDistance: 7.5,
    minHealth: 4.5,
    timeoutMs: 100,
    sprint: false,
    owner: {}
  }))).outcome, 'targetChanged')

  bot.entities[target.id] = target
  target.position = new Vec3(ATTACK_REACH_BLOCKS + 1, 64, 0.5)
  assert.equal((await reportOf(attackOfferedTarget(bot, movement, {
    entityOffers: [entityOffer(target)],
    slots: slotOffers(HOTBAR_SLOT, weapon),
    entity: 0,
    slot: HOTBAR_SLOT,
    attacks: 1,
    approach: false,
    maxPursuitDistance: 7.5,
    minHealth: 4.5,
    timeoutMs: 100,
    sprint: false,
    owner: {}
  }))).outcome, 'outOfRange')

  target.position = new Vec3(2.5, 64, 0.5)
  bot.health = 4.5
  assert.equal((await reportOf(attackOfferedTarget(bot, movement, {
    entityOffers: [entityOffer(target)],
    slots: slotOffers(HOTBAR_SLOT, weapon),
    entity: 0,
    slot: HOTBAR_SLOT,
    attacks: 1,
    approach: false,
    maxPursuitDistance: 7.5,
    minHealth: 4.5,
    timeoutMs: 100,
    sprint: false,
    owner: {}
  }))).outcome, 'healthBound')

  bot.health = 20
  target.position = new Vec3(4.5, 64, 0.5)
  const overTravelled = await reportOf(attackOfferedTarget(bot, {
    halt: () => true,
    moveTo: async () => ({
      outcome: 'arrived',
      from: [0.5, 64, 0.5],
      position: [4.5, 64, 0.5],
      travelled: 8,
      elapsedMs: 20
    })
  }, {
    entityOffers: [entityOffer(target)],
    slots: slotOffers(HOTBAR_SLOT, weapon),
    entity: 0,
    slot: HOTBAR_SLOT,
    attacks: 1,
    approach: true,
    maxPursuitDistance: 7.5,
    minHealth: 4.5,
    timeoutMs: 100,
    sprint: false,
    owner: {}
  }))
  assert.equal(overTravelled.outcome, 'overTravelled')
})

test('combat accepts every living Mineflayer registry category and no nonliving one', () => {
  const bot = worldBot()
  const living = [
    'ambient',
    'animal',
    'hostile',
    'living',
    'mob',
    'passive',
    'player',
    'water_creature'
  ]
  for (const [offset, type] of living.entries()) {
    const target = livingEntity(40 + offset, new Vec3(2.5, 64, 0.5))
    target.type = type
    bot.entities[target.id] = target
    assert.equal(offeredTarget(bot, [entityOffer(target)], 0).entity, target)
  }
  for (const [offset, type] of ['other', 'projectile'].entries()) {
    const target = livingEntity(60 + offset, new Vec3(2.5, 64, 0.5))
    target.type = type
    bot.entities[target.id] = target
    assert.equal(offeredTarget(bot, [entityOffer(target)], 0).outcome, 'targetChanged')
  }
})

test('combat stops on target death, replacement, cancellation, and timeout without switching', async () => {
  function readyBot () {
    const bot = worldBot()
    const target = livingEntity(40, new Vec3(2.5, 64, 0.5))
    const weapon = itemStack('iron_sword', 1, { type: 267 })
    bot.entities[target.id] = target
    bot.inventory.slots[HOTBAR_SLOT] = weapon
    bot.equip = async () => {}
    return { bot, target, weapon }
  }
  const requestFor = (target, weapon) => ({
    entityOffers: [entityOffer(target)],
    slots: slotOffers(HOTBAR_SLOT, weapon),
    entity: 0,
    slot: HOTBAR_SLOT,
    attacks: 2,
    approach: false,
    maxPursuitDistance: 7.5,
    minHealth: 4.5,
    timeoutMs: 2000,
    sprint: false,
    owner: {}
  })
  const movement = { halt: () => true }

  const goneBeforeStrike = readyBot()
  goneBeforeStrike.bot.attack = () => assert.fail('a dead target must not be attacked')
  const goneRunning = attackOfferedTarget(
    goneBeforeStrike.bot,
    movement,
    requestFor(goneBeforeStrike.target, goneBeforeStrike.weapon)
  )
  goneBeforeStrike.bot.emit('entityDead', goneBeforeStrike.target)
  const goneReport = await reportOf(goneRunning)
  assert.equal(goneReport.outcome, 'targetGone')
  assert.equal(goneReport.attacks, 0)

  const died = readyBot()
  died.bot.attack = (entity) => died.bot.emit('entityDead', entity)
  const deathReport = await reportOf(attackOfferedTarget(
    died.bot,
    movement,
    requestFor(died.target, died.weapon)
  ))
  assert.equal(deathReport.outcome, 'targetDied')
  assert.equal(deathReport.attacks, 1)

  const replaced = readyBot()
  let replacementCalls = 0
  replaced.bot.attack = () => {
    replacementCalls += 1
    replaced.bot.entities[replaced.target.id] =
      livingEntity(replaced.target.id, replaced.target.position.clone())
  }
  const replacementReport = await reportOf(attackOfferedTarget(
    replaced.bot,
    movement,
    requestFor(replaced.target, replaced.weapon)
  ))
  assert.equal(replacementReport.outcome, 'targetChanged')
  assert.equal(replacementReport.attacks, 1)
  assert.equal(replacementCalls, 1)

  const cancelled = readyBot()
  let cancelCalls = 0
  cancelled.bot.attack = () => { cancelCalls += 1 }
  const running = attackOfferedTarget(
    cancelled.bot,
    movement,
    requestFor(cancelled.target, cancelled.weapon)
  )
  await eventually(() => assert.equal(cancelCalls, 1))
  assert.equal(running.cancel(), true)
  const cancelReport = await reportOf(running)
  assert.equal(cancelReport.outcome, 'cancelled')
  assert.equal(cancelReport.attacks, 1)

  const timed = readyBot()
  timed.bot.attack = () => {}
  const timeoutReport = await reportOf(attackOfferedTarget(timed.bot, movement, {
    ...requestFor(timed.target, timed.weapon),
    timeoutMs: 5
  }))
  assert.equal(timeoutReport.outcome, 'timedOut')
  assert.equal(timeoutReport.attacks, 1)
})

test('sleep revalidates one bed and reports vanilla refusal, wake, and bounded cleanup', async () => {
  function readyBed () {
    const bot = worldBot()
    const bed = bot.putBlock(
      solidBlock(new Vec3(1, 64, 0), 'red_bed', { type: 200, stateId: 200 })
    )
    bot.isABed = (block) => block.name.endsWith('_bed')
    bot.parseBedMetadata = () => ({ occupied: false })
    bot.isSleeping = false
    return { bot, bed, offered: [blockOffer(bed)] }
  }

  const slept = readyBed()
  let wakeCalls = 0
  slept.bot.sleep = async () => {
    slept.bot.isSleeping = true
    slept.bot.emit('sleep')
  }
  slept.bot.wake = async () => {
    wakeCalls += 1
    slept.bot.isSleeping = false
    slept.bot.emit('wake')
  }
  const sleeping = sleepInOfferedBed(slept.bot, {
    offered: slept.offered,
    block: 0,
    timeoutMs: 1000
  })
  await eventually(() => assert.equal(slept.bot.isSleeping, true))
  assert.equal(sleeping.cancel(), true)
  const cancelled = await reportOf(sleeping)
  assert.equal(cancelled.outcome, 'cancelled')
  assert.equal(cancelled.entered, true)
  assert.equal(wakeCalls, 1)

  const woke = readyBed()
  woke.bot.sleep = async () => {
    woke.bot.isSleeping = true
    woke.bot.emit('sleep')
    setImmediate(() => {
      woke.bot.isSleeping = false
      woke.bot.emit('wake')
    })
  }
  woke.bot.wake = async () => {}
  const wakeReport = await reportOf(sleepInOfferedBed(woke.bot, {
    offered: woke.offered,
    block: 0,
    timeoutMs: 1000
  }))
  assert.equal(wakeReport.outcome, 'woke')
  assert.equal(wakeReport.entered, true)

  const refused = readyBed()
  refused.bot.sleep = async () => { throw new Error('there are monsters nearby') }
  refused.bot.wake = async () => {}
  assert.equal((await reportOf(sleepInOfferedBed(refused.bot, {
    offered: refused.offered,
    block: 0,
    timeoutMs: 100
  }))).outcome, 'unsafe')

  const occupied = readyBed()
  occupied.bot.parseBedMetadata = () => ({ occupied: true })
  occupied.bot.sleep = async () => {}
  occupied.bot.wake = async () => {}
  assert.equal((await reportOf(sleepInOfferedBed(occupied.bot, {
    offered: occupied.offered,
    block: 0,
    timeoutMs: 100
  }))).outcome, 'occupied')

  const wrongWorld = readyBed()
  wrongWorld.bot.game.dimension = 'the_nether'
  assert.equal((await reportOf(sleepInOfferedBed(wrongWorld.bot, {
    offered: wrongWorld.offered,
    block: 0,
    timeoutMs: 100
  }))).outcome, 'wrongDimension')
})

test('sleep guards a terminal request against late entry and a stuck library wait', async () => {
  function readyBed () {
    const bot = worldBot()
    const bed = bot.putBlock(
      solidBlock(new Vec3(1, 64, 0), 'red_bed', { type: 200, stateId: 200 })
    )
    bot.isABed = () => true
    bot.parseBedMetadata = () => ({ occupied: false })
    bot.isSleeping = false
    return { bot, offered: [blockOffer(bed)] }
  }

  const late = readyBed()
  let wakes = 0
  late.bot.sleep = () => new Promise((resolve) => {
    setTimeout(() => {
      late.bot.isSleeping = true
      late.bot.emit('sleep')
      resolve()
    }, 10)
  })
  late.bot.wake = async () => {
    wakes += 1
    late.bot.isSleeping = false
    late.bot.emit('wake')
  }
  const lateReport = await reportOf(sleepInOfferedBed(late.bot, {
    offered: late.offered,
    block: 0,
    timeoutMs: 5,
    entryDrainMs: 50
  }))
  assert.equal(lateReport.outcome, 'timedOut')
  assert.equal(lateReport.entered, true)
  assert.equal(wakes, 1)
  assert.equal(late.bot.isSleeping, false)

  const rejectedThenLate = readyBed()
  let rejectedWakes = 0
  rejectedThenLate.bot.sleep = () => new Promise((resolve, reject) => {
    setTimeout(() => reject(new Error('bot is not sleeping')), 8)
    setTimeout(() => {
      rejectedThenLate.bot.isSleeping = true
      rejectedThenLate.bot.emit('sleep')
      resolve()
    }, 12)
  })
  rejectedThenLate.bot.wake = async () => {
    rejectedWakes += 1
    rejectedThenLate.bot.isSleeping = false
    rejectedThenLate.bot.emit('wake')
  }
  const rejectedLateReport = await reportOf(sleepInOfferedBed(rejectedThenLate.bot, {
    offered: rejectedThenLate.offered,
    block: 0,
    timeoutMs: 5,
    entryDrainMs: 30
  }))
  assert.equal(rejectedLateReport.outcome, 'timedOut')
  assert.equal(rejectedLateReport.entered, true)
  assert.equal(rejectedWakes, 1)
  assert.equal(rejectedThenLate.bot.isSleeping, false)

  const stuck = readyBed()
  stuck.bot.sleep = () => new Promise(() => {})
  stuck.bot.wake = async () => {}
  const stuckReport = await reportOf(sleepInOfferedBed(stuck.bot, {
    offered: stuck.offered,
    block: 0,
    timeoutMs: 5,
    entryDrainMs: 10
  }))
  assert.equal(stuckReport.outcome, 'timedOut')
  assert.equal(stuckReport.entered, false)
})

test('sleep refuses stale, missing, remote, and hidden beds before entering', async () => {
  function bedBot (position) {
    const bot = worldBot()
    const bed = bot.putBlock(
      solidBlock(position, 'red_bed', { type: 200, stateId: 200 })
    )
    bot.isABed = (block) => block.name.endsWith('_bed')
    bot.parseBedMetadata = () => ({ occupied: false })
    bot.sleep = async () => {}
    bot.wake = async () => {}
    return { bot, bed, offered: [blockOffer(bed)] }
  }

  const gone = bedBot(new Vec3(1, 64, 0))
  gone.bot.removeBlock(gone.bed.position)
  assert.equal((await reportOf(sleepInOfferedBed(gone.bot, {
    offered: gone.offered,
    block: 0,
    timeoutMs: 100
  }))).outcome, 'bedGone')

  const changed = bedBot(new Vec3(1, 64, 0))
  changed.bot.putBlock(
    solidBlock(changed.bed.position, 'blue_bed', { type: 201, stateId: 201 })
  )
  assert.equal((await reportOf(sleepInOfferedBed(changed.bot, {
    offered: changed.offered,
    block: 0,
    timeoutMs: 100
  }))).outcome, 'bedChanged')

  const far = bedBot(new Vec3(8, 64, 0))
  assert.equal((await reportOf(sleepInOfferedBed(far.bot, {
    offered: far.offered,
    block: 0,
    timeoutMs: 100
  }))).outcome, 'outOfReach')

  const hidden = bedBot(new Vec3(1, 64, 0))
  hidden.bot.raycastHit = solidBlock(new Vec3(0, 64, 1))
  assert.equal((await reportOf(sleepInOfferedBed(hidden.bot, {
    offered: hidden.offered,
    block: 0,
    timeoutMs: 100
  }))).outcome, 'notVisible')

  assert.equal((await reportOf(sleepInOfferedBed(hidden.bot, {
    offered: undefined,
    block: 0,
    timeoutMs: 100
  }))).outcome, 'senseForgotten')
  assert.equal((await reportOf(sleepInOfferedBed(hidden.bot, {
    offered: hidden.offered,
    block: 7,
    timeoutMs: 100
  }))).outcome, 'bedUnnamed')
})

test('survival teardown preserves the exact death and disconnect causes', async () => {
  const craftBot = worldBot()
  const craft = craftingSetup(craftBot)
  craftBot.craft = async () => assert.fail('a synchronous death must prevent the craft')
  const dyingCraft = craftOfferedRecipe(craftBot, {
    offered: craft.reading.recipeOffers,
    recipe: 0,
    crafts: 1,
    timeoutMs: 1000
  })
  assert.equal(dyingCraft.abort('bodyDied'), true)
  assert.deepEqual(await reportOf(dyingCraft), {
    outcome: 'bodyDied',
    elapsedMs: 0,
    attempted: false
  })

  const combatBot = worldBot()
  const target = livingEntity(40, new Vec3(2.5, 64, 0.5))
  combatBot.entities[target.id] = target
  const weapon = itemStack('iron_sword', 1, { type: 267 })
  combatBot.inventory.slots[INVENTORY_SLOT] = weapon
  combatBot.equip = async (stack) => {
    combatBot.inventory.slots[INVENTORY_SLOT] = null
    combatBot.inventory.slots[HOTBAR_SLOT] = stack
  }
  let attacks = 0
  combatBot.attack = () => { attacks += 1 }
  const disconnectingAttack = attackOfferedTarget(combatBot, undefined, {
    entityOffers: [entityOffer(target)],
    slots: slotOffers(INVENTORY_SLOT, weapon),
    entity: 0,
    slot: INVENTORY_SLOT,
    attacks: 2,
    approach: false,
    maxPursuitDistance: 7.5,
    minHealth: 4.5,
    timeoutMs: 2000,
    sprint: false,
    owner: {}
  })
  await eventually(() => assert.equal(attacks, 1))
  assert.equal(disconnectingAttack.abort('disconnected'), true)
  const attackReport = await reportOf(disconnectingAttack)
  assert.equal(attackReport.outcome, 'disconnected')
  assert.equal(attackReport.attacks, 1)

  const sleepBot = worldBot()
  const bed = sleepBot.putBlock(
    solidBlock(new Vec3(1, 64, 0), 'red_bed', { type: 200, stateId: 200 })
  )
  sleepBot.isABed = () => true
  sleepBot.parseBedMetadata = () => ({ occupied: false })
  sleepBot.isSleeping = false
  sleepBot.sleep = async () => {
    sleepBot.isSleeping = true
    sleepBot.emit('sleep')
  }
  sleepBot.wake = async () => assert.fail('death must not request a wake packet')
  const dyingSleep = sleepInOfferedBed(sleepBot, {
    offered: [blockOffer(bed)],
    block: 0,
    timeoutMs: 1000
  })
  await eventually(() => assert.equal(sleepBot.isSleeping, true))
  assert.equal(dyingSleep.abort('bodyDied'), true)
  const sleepReport = await reportOf(dyingSleep)
  assert.equal(sleepReport.outcome, 'bodyDied')
  assert.equal(sleepReport.entered, true)
})

test('all four original operations cross the wire using only one connection own sense offers', async () => {
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
    serverBroke(bot, block.position)
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

test('craft, combat, and sleep cross the wire using only the asking connection authority', async () => {
  const bot = worldBot(fakeBot())
  bot.putBlock(solidBlock(new Vec3(0, 63, 0)))
  const bed = bot.putBlock(
    solidBlock(new Vec3(1, 64, 0), 'red_bed', { type: 200, stateId: 200 })
  )
  const target = livingEntity(40, new Vec3(2.5, 64, 0.5))
  bot.entities[target.id] = target
  const craft = craftingSetup(bot, { logs: 1 })
  const weaponSlot = INVENTORY_SLOT + 2
  const weapon = itemStack('iron_sword', 1, { type: 267 })
  bot.inventory.slots[weaponSlot] = weapon
  bot.currentWindow = null
  bot.closeWindow = () => {}
  bot.craft = async (selected) => {
    assert.equal(selected, craft.recipe)
    applyCraft(bot)
  }
  bot.equip = async (stack) => {
    bot.inventory.slots[weaponSlot] = null
    bot.inventory.slots[HOTBAR_SLOT] = stack
  }
  let attacked
  bot.attack = (entity) => { attacked = entity }
  bot.isABed = (block) => block.name.endsWith('_bed')
  bot.parseBedMetadata = () => ({ occupied: false })
  bot.isSleeping = false
  bot.sleep = async (selected) => {
    assert.equal(selected, bed)
    bot.isSleeping = true
    bot.emit('sleep')
    setImmediate(() => {
      bot.isSleeping = false
      bot.emit('wake')
    })
  }
  bot.wake = async () => {}

  const { body, port } = await startedBody({ bot })
  const owner = await handshaken(port)
  const foreign = await handshaken(port)
  try {
    bot.emit('spawn')
    const craftSense = await owner.answer(request('sense', 'sense-craft', {
      radius: 5,
      limit: 8
    }))
    assert.deepEqual(craftSense.recipes, [{
      what: 'oak_planks',
      resultCount: 4,
      maxCrafts: 1,
      requiresTable: false
    }])
    assert.equal((await foreign.answer(request('craft', 'foreign-craft', {
      senseId: 'sense-craft',
      recipe: 0,
      crafts: 1,
      timeoutMs: 100
    }))).outcome, 'senseForgotten')
    const crafted = await owner.answer(request('craft', 'craft-1', {
      senseId: 'sense-craft',
      recipe: 0,
      crafts: 1,
      timeoutMs: 100
    }))
    assert.equal(crafted.outcome, 'crafted')
    assert.equal(crafted.count, 4)

    const attackSense = await owner.answer(request('sense', 'sense-attack', {
      radius: 5,
      limit: 8
    }))
    const entity = attackSense.entities.findIndex((it) => it.what === 'zombie')
    assert.ok(entity >= 0)
    assert.equal((await foreign.answer(request('attack', 'foreign-attack', {
      senseId: 'sense-attack',
      entity,
      slot: weaponSlot,
      attacks: 1,
      approach: false,
      maxPursuitDistance: 7.5,
      minHealth: 4.5,
      timeoutMs: 1000,
      sprint: false
    }))).outcome, 'senseForgotten')
    const attack = await owner.answer(request('attack', 'attack-1', {
      senseId: 'sense-attack',
      entity,
      slot: weaponSlot,
      attacks: 1,
      approach: false,
      maxPursuitDistance: 7.5,
      minHealth: 4.5,
      timeoutMs: 1000,
      sprint: false
    }))
    assert.equal(attack.outcome, 'attacked')
    assert.equal(attack.attacks, 1)
    assert.equal(attacked, target)

    const sleepSense = await owner.answer(request('sense', 'sense-sleep', {
      radius: 5,
      limit: 8
    }))
    const block = sleepSense.blocks.findIndex((it) =>
      it.position[0] === bed.position.x &&
      it.position[1] === bed.position.y &&
      it.position[2] === bed.position.z
    )
    assert.ok(block >= 0)
    assert.equal((await foreign.answer(request('sleep', 'foreign-sleep', {
      senseId: 'sense-sleep',
      block,
      timeoutMs: 1000
    }))).outcome, 'senseForgotten')
    const sleep = await owner.answer(request('sleep', 'sleep-1', {
      senseId: 'sense-sleep',
      block,
      timeoutMs: 1000
    }))
    assert.equal(sleep.outcome, 'woke')
    assert.equal(sleep.entered, true)
  } finally {
    owner.stop()
    foreign.stop()
    await body.stop()
  }
})

test('a sleeping owner refuses conflicting work and halt wakes only that request', async () => {
  const bot = worldBot(fakeBot())
  bot.putBlock(solidBlock(new Vec3(0, 63, 0)))
  const bed = bot.putBlock(
    solidBlock(new Vec3(1, 64, 0), 'red_bed', { type: 200, stateId: 200 })
  )
  bot.isABed = (block) => block.name.endsWith('_bed')
  bot.parseBedMetadata = () => ({ occupied: false })
  bot.isSleeping = false
  bot.sleep = async () => {
    bot.isSleeping = true
    bot.emit('sleep')
  }
  let wakes = 0
  bot.wake = async () => {
    wakes += 1
    bot.isSleeping = false
    bot.emit('wake')
  }

  const { body, port } = await startedBody({ bot })
  const owner = await handshaken(port)
  const peer = await handshaken(port)
  try {
    bot.emit('spawn')
    const sensed = await owner.answer(request('sense', 'sense-sleep-owner', {
      radius: 5,
      limit: 8
    }))
    const block = sensed.blocks.findIndex((it) =>
      it.position[0] === bed.position.x &&
      it.position[1] === bed.position.y &&
      it.position[2] === bed.position.z
    )
    owner.send(request('sleep', 'sleep-owner', {
      senseId: 'sense-sleep-owner',
      block,
      timeoutMs: 1000
    }))
    await eventually(() => assert.equal(bot.isSleeping, true))
    const conflict = await peer.answer(request('craft', 'craft-conflict', {
      senseId: 'sense-sleep-owner',
      recipe: 0,
      crafts: 1,
      timeoutMs: 100
    }))
    assert.equal(conflict.error, 'notReady')
    const halt = await peer.answer(request('halt', 'halt-sleep'))
    assert.equal(halt.stopped, true)
    const stopped = await owner.read()
    assert.equal(stopped.outcome, 'cancelled')
    assert.equal(stopped.entered, true)
    assert.equal(wakes, 1)
  } finally {
    owner.stop()
    peer.stop()
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
