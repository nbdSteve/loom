'use strict'

/**
 * The offline model-action matrix, below the JVM: every offer a `sense` answer makes must be
 * spendable against the unchanged scene, and every offer must fail closed once the scene moves.
 *
 * Every case drives the real body process over a real control socket, around a real Mineflayer
 * bot fed real 1.21.1 packets, standing on a real Prismarine chunk. Nothing here writes a field
 * onto the bot or stubs an action module: the offer is read off the sense answer the process
 * itself produced, and the action is asked for with only that answer's own handles.
 *
 * Block and dropped-item geometry (dig/collect) is the rolling branch's subject and is not
 * repeated here. Craft, attack and sleep cannot reach a terminal outcome against a real bot
 * without a server window/damage/bed protocol the fake client does not speak; their matrix rows
 * stop at the real sense offer and the immediate fail-closed preconditions.
 */

const assert = require('node:assert/strict')
const { afterEach, test } = require('node:test')

const { EQUIP_SETTLE_MS } = require('../src/equipping')
const H = require('./harness')

afterEach(H.disconnected)

const FEET = Object.freeze({ x: 8.5, y: 64, z: 8.5 })
const HUNGRY = Object.freeze({ health: 20, food: 8, foodSaturation: 0 })
const FED = Object.freeze({ health: 20, food: 20, foodSaturation: 5 })
const BREAD_SLOT = 20
const HOTBAR = 36
const AMPLE_MS = 8_000

let openBody = null
afterEach(async () => {
  if (openBody !== null) await openBody.stop()
  openBody = null
})

/** A body process in the world on real ground, with a handshaken controller. */
async function scene ({ vitals = FED, walls = [], stacks = [], creatures = [] } = {}) {
  const given = await H.embodied()
  const { body, bot, client, port } = given
  openBody = body
  H.spawned(client, vitals)
  H.clockReported(client, { timeOfDay: 6000, day: 2 })
  client.emit('position', H.positionPacket({ ...FEET, yaw: 0, pitch: 0, teleportId: 1 }))
  H.ground(bot, { walls })
  if (stacks.length > 0) H.stocked(client, stacks)
  for (const creature of creatures) H.spotted(client, creature)
  await H.settled(200)
  const peer = await H.handshaken(port)
  return { ...given, peer }
}

async function sensed (peer, requestId = 's1') {
  const answer = await peer.answer(H.request('sense', requestId, { radius: 16, limit: 48 }))
  assert.equal(answer.op, 'sense', JSON.stringify(answer))
  return answer
}

function slotOf (answer, slot) {
  return answer.inventory.find((entry) => entry.slot === slot)
}

// moveTo

test('moveTo: a reachable bounded coordinate on the sensed ground arrives', async () => {
  const { peer, bot } = await scene()
  await sensed(peer)
  const report = await peer.answer(H.request('moveTo', 'm1', {
    destination: [12.5, 64, 8.5], tolerance: 1, timeoutMs: AMPLE_MS, sprint: false
  }))
  assert.equal(report.outcome, 'arrived', JSON.stringify(report))
  assert.ok(bot.entity.position.distanceTo({ x: 12.5, y: 64, z: 8.5 }) <= 1.5)
  peer.stop()
})

test('moveTo: a vertical coordinate into solid ground or open air is refused, not walked', async () => {
  const { peer, bot } = await scene()
  const before = bot.entity.position.clone()
  for (const [id, destination] of [['m-up', [8.5, 80, 8.5]], ['m-down', [8.5, 40, 8.5]]]) {
    const report = await peer.answer(H.request('moveTo', id, {
      destination, tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
    }))
    assert.notEqual(report.outcome, 'arrived', JSON.stringify(report))
    assert.ok(['noRoute', 'searchOverBudget'].includes(report.outcome), JSON.stringify(report))
  }
  assert.ok(bot.entity.position.distanceTo(before) < 1)
  peer.stop()
})

test('moveTo: a coordinate past the reach bound is outOfRange and moves nothing', async () => {
  const { peer } = await scene()
  const report = await peer.answer(H.request('moveTo', 'm-far', {
    destination: [8.5, 64, 200.5], tolerance: 1, timeoutMs: AMPLE_MS, sprint: false
  }))
  assert.equal(report.outcome, 'outOfRange', JSON.stringify(report))
  assert.equal(report.travelled, 0)
  peer.stop()
})

test('moveTo: a destination enclosed by a collision box is a route refusal', async () => {
  // A one-block pocket at (12, 64, 8) walled on all four sides and roofed two high.
  const walls = []
  for (const [x, z] of [[11, 8], [13, 8], [12, 7], [12, 9]]) {
    walls.push({ x, y: 64, z }, { x, y: 65, z })
  }
  walls.push({ x: 12, y: 66, z: 8 })
  const { peer } = await scene({ walls })
  const report = await peer.answer(H.request('moveTo', 'm-box', {
    destination: [12.5, 64, 8.5], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  }))
  assert.ok(['noRoute', 'searchOverBudget'].includes(report.outcome), JSON.stringify(report))
  peer.stop()
})

// flee

test('flee: a visible exact creature ref from the unchanged scene is spendable', async () => {
  const { peer } = await scene({ creatures: [{ id: 44, what: 'zombie', x: 11.5, y: 64, z: 8.5 }] })
  const answer = await sensed(peer)
  const index = answer.entities.findIndex((entity) => entity.what === 'zombie')
  assert.ok(index >= 0, JSON.stringify(answer.entities))
  assert.equal(answer.entities[index].visible, true)
  const report = await peer.answer(H.request('flee', 'f1', {
    senseId: 's1', entity: index, distance: 6, timeoutMs: AMPLE_MS, sprint: false
  }))
  assert.equal(report.outcome, 'arrived', JSON.stringify(report))
  peer.stop()
})

test('flee: a stale, dead, or gone target fails closed', async () => {
  const { peer, bot, client } = await scene({
    creatures: [
      { id: 44, what: 'zombie', x: 11.5, y: 64, z: 8.5 },
      { id: 45, what: 'zombie', x: 5.5, y: 64, z: 8.5 }
    ]
  })
  const answer = await sensed(peer)
  const dead = answer.entities.findIndex((e) => e.position[0] === 11.5)
  const gone = answer.entities.findIndex((e) => e.position[0] === 5.5)
  assert.ok(dead >= 0 && gone >= 0)
  bot.emit('entityDead', bot.entities[44])
  client.emit('entity_destroy', { entityIds: [45] })
  await H.settled()
  for (const [id, entity] of [['f-dead', dead], ['f-gone', gone]]) {
    const report = await peer.answer(H.request('flee', id, {
      senseId: 's1', entity, distance: 6, timeoutMs: AMPLE_MS, sprint: false
    }))
    assert.equal(report.outcome, 'threatGone', JSON.stringify(report))
    assert.equal(report.travelled, 0)
  }
  const unnamed = await peer.answer(H.request('flee', 'f-unnamed', {
    senseId: 's1', entity: 3, distance: 6, timeoutMs: AMPLE_MS, sprint: false
  }))
  assert.equal(unnamed.outcome, 'threatUnnamed', JSON.stringify(unnamed))
  const forgotten = await peer.answer(H.request('flee', 'f-forgotten', {
    senseId: 'never-sensed', entity: 0, distance: 6, timeoutMs: AMPLE_MS, sprint: false
  }))
  assert.equal(forgotten.outcome, 'senseForgotten', JSON.stringify(forgotten))
  peer.stop()
})

test('flee: an occluded creature is reported not visible and is still an exact ref', async () => {
  // A two-high wall between the eyes and the creature.
  const walls = [{ x: 10, y: 64, z: 8 }, { x: 10, y: 65, z: 8 }]
  const { peer } = await scene({ walls, creatures: [{ id: 44, what: 'zombie', x: 11.5, y: 64, z: 8.5 }] })
  const answer = await sensed(peer)
  const index = answer.entities.findIndex((entity) => entity.what === 'zombie')
  assert.ok(index >= 0)
  assert.equal(answer.entities[index].visible, false)
  const report = await peer.answer(H.request('flee', 'f-hidden', {
    senseId: 's1', entity: index, distance: 6, timeoutMs: AMPLE_MS, sprint: false
  }))
  // The body offered it, so it must be spendable or not offered; an occluded threat is still a threat.
  assert.ok(['arrived', 'lostGround'].includes(report.outcome), JSON.stringify(report))
  peer.stop()
})

// eat

test('eat: an offered food slot in a hungry body is eaten', async () => {
  const { peer, client } = await scene({ vitals: HUNGRY, stacks: [[BREAD_SLOT, 'bread', 3]] })
  const answer = await sensed(peer)
  assert.equal(slotOf(answer, BREAD_SLOT).what, 'bread')
  peer.send(H.request('eat', 'e1', { senseId: 's1', slot: BREAD_SLOT }))
  await H.settled()
  client.emit('set_slot', {
    windowId: 0, stateId: 2, slot: HOTBAR, item: H.Item.toNotch(H.stack('bread', 2))
  })
  client.emit('set_cooldown', { itemID: H.registry.itemsByName.bread.id })
  const report = await H.promptly(peer.read(), 'the eat report')
  assert.equal(report.outcome, 'eaten', JSON.stringify(report))
  peer.stop()
})

test('eat: full food, a changed slot, and an unsuitable stack fail closed', async () => {
  const { peer, client } = await scene({
    vitals: FED,
    stacks: [[BREAD_SLOT, 'bread', 3], [21, 'stone', 4], [22, 'bread', 1]]
  })
  await sensed(peer)
  const full = await peer.answer(H.request('eat', 'e-full', { senseId: 's1', slot: BREAD_SLOT }))
  assert.equal(full.outcome, 'reserveFull', JSON.stringify(full))
  const stone = await peer.answer(H.request('eat', 'e-stone', { senseId: 's1', slot: 21 }))
  assert.equal(stone.outcome, 'notEdible', JSON.stringify(stone))
  client.emit('set_slot', {
    windowId: 0, stateId: 3, slot: 22, item: H.Item.toNotch(H.stack('rotten_flesh', 1))
  })
  await H.settled()
  const changed = await peer.answer(H.request('eat', 'e-changed', { senseId: 's1', slot: 22 }))
  assert.equal(changed.outcome, 'slotChanged', JSON.stringify(changed))
  peer.stop()
})

// equip

test('equip: an offered unchanged stack is equipped to the hand', async () => {
  const { peer, client } = await scene({ stacks: [[BREAD_SLOT, 'iron_pickaxe', 1]] })
  await sensed(peer)
  const running = peer.answer(H.request('equip', 'q1', {
    senseId: 's1', slot: BREAD_SLOT, destination: 'hand', timeoutMs: 4000
  }))
  await H.settled()
  // The server's own restatement of the window after the swap the body asked for.
  H.emptied(client, BREAD_SLOT)
  client.emit('set_slot', {
    windowId: 0, stateId: 4, slot: HOTBAR, item: H.Item.toNotch(H.stack('iron_pickaxe', 1))
  })
  const report = await H.promptly(running, 'the equip report')
  assert.equal(report.outcome, 'equipped', JSON.stringify(report))
  // Success waited out the server's chance to refuse the click.
  assert.ok(report.elapsedMs >= EQUIP_SETTLE_MS, JSON.stringify(report))
  peer.stop()
})

test('equip: a changed slot and an incompatible destination fail closed', async () => {
  const { peer, client } = await scene({ stacks: [[BREAD_SLOT, 'iron_pickaxe', 1], [21, 'bread', 2]] })
  await sensed(peer)
  client.emit('set_slot', {
    windowId: 0, stateId: 5, slot: BREAD_SLOT, item: H.Item.toNotch(H.stack('stone_pickaxe', 1))
  })
  await H.settled()
  const changed = await peer.answer(H.request('equip', 'q-changed', {
    senseId: 's1', slot: BREAD_SLOT, destination: 'hand', timeoutMs: 4000
  }))
  assert.equal(changed.outcome, 'slotChanged', JSON.stringify(changed))
  // Mineflayer moves the bread into the head slot locally; a vanilla server refuses the
  // click and restates the window, which is the only server answer a refusal gets.
  const wrongly = peer.answer(H.request('equip', 'q-head', {
    senseId: 's1', slot: 21, destination: 'head', timeoutMs: 4000
  }))
  await H.settled()
  client.emit('set_slot', { windowId: 0, stateId: 6, slot: 5, item: { present: false } })
  client.emit('set_slot', { windowId: 0, stateId: 7, slot: 21, item: H.Item.toNotch(H.stack('bread', 2)) })
  const wrong = await H.promptly(wrongly, 'the refused equip')
  assert.equal(wrong.outcome, 'incompatible', JSON.stringify(wrong))
  peer.stop()
})

// place

test('place: a visible support block and the offered held stack place one block', async () => {
  const { peer, client } = await scene({ stacks: [[HOTBAR, 'dirt', 4]] })
  const answer = await sensed(peer)
  // The ground block one ahead of the feet: its top face is open and in reach.
  const support = answer.blocks.findIndex((block) =>
    block.position[0] === 9 && block.position[1] === 63 && block.position[2] === 8)
  assert.ok(support >= 0, `ground (9,63,8) is offered: ${JSON.stringify(answer.blocks.slice(0, 4))}`)
  const running = peer.answer(H.request('place', 'p1', {
    senseId: 's1', block: support, slot: HOTBAR, face: 'up', timeoutMs: 4000
  }))
  await H.settled()
  client.emit('block_change', {
    location: { x: 9, y: 64, z: 8 }, type: H.registry.blocksByName.dirt.defaultState
  })
  const report = await H.promptly(running, 'the place report')
  assert.equal(report.outcome, 'placed', JSON.stringify(report))
  peer.stop()
})

test('place: occupied target, hidden support, and changed inventory fail closed', async () => {
  const walls = [{ x: 9, y: 64, z: 8 }]
  const { peer, client } = await scene({ walls, stacks: [[HOTBAR, 'dirt', 4]] })
  const answer = await sensed(peer)
  const occupied = answer.blocks.findIndex((b) =>
    b.position[0] === 9 && b.position[1] === 63 && b.position[2] === 8)
  if (occupied >= 0) {
    const report = await peer.answer(H.request('place', 'p-occupied', {
      senseId: 's1', block: occupied, slot: HOTBAR, face: 'up', timeoutMs: 4000
    }))
    assert.ok(['destinationBlocked', 'notVisible'].includes(report.outcome), JSON.stringify(report))
  }
  // A ground block straight under a wall is never offered as a top-face support the body can see.
  const hidden = answer.blocks.findIndex((b) =>
    b.position[0] === 9 && b.position[1] === 63 && b.position[2] === 8 && b.visible === true)
  assert.equal(hidden, -1, 'a support under a wall is not offered as visible')
  const open = answer.blocks.findIndex((b) =>
    b.position[0] === 8 && b.position[1] === 63 && b.position[2] === 9)
  assert.ok(open >= 0)
  H.emptied(client, HOTBAR)
  await H.settled()
  const changed = await peer.answer(H.request('place', 'p-changed', {
    senseId: 's1', block: open, slot: HOTBAR, face: 'up', timeoutMs: 4000
  }))
  assert.ok(['slotEmpty', 'slotChanged'].includes(changed.outcome), JSON.stringify(changed))
  peer.stop()
})

// craft, attack, sleep: real sense offers and immediate fail-closed preconditions

test('craft: an offered recipe exists only when its ingredients are held, and changes fail closed', async () => {
  const { peer, client } = await scene({ stacks: [[BREAD_SLOT, 'oak_log', 2]] })
  const answer = await sensed(peer)
  const planks = answer.recipes.findIndex((recipe) => recipe.what === 'oak_planks')
  assert.ok(planks >= 0, `oak planks offered from held logs: ${JSON.stringify(answer.recipes)}`)
  assert.equal(answer.recipes[planks].requiresTable, false)
  H.emptied(client, BREAD_SLOT)
  await H.settled()
  const report = await peer.answer(H.request('craft', 'c-changed', {
    senseId: 's1', recipe: planks, crafts: 1, timeoutMs: 4000
  }))
  assert.equal(report.outcome, 'ingredientsChanged', JSON.stringify(report))
  const unnamed = await peer.answer(H.request('craft', 'c-unnamed', {
    senseId: 's1', recipe: 3, crafts: 1, timeoutMs: 4000
  }))
  assert.equal(unnamed.outcome, 'recipeUnnamed', JSON.stringify(unnamed))
  peer.stop()
})

test('attack: a visible living target and weapon are offered; death and destroy fail closed', async () => {
  const { peer, bot, client } = await scene({
    stacks: [[HOTBAR, 'iron_sword', 1]],
    creatures: [
      { id: 44, what: 'zombie', x: 10.5, y: 64, z: 8.5 },
      { id: 45, what: 'zombie', x: 6.5, y: 64, z: 8.5 }
    ]
  })
  const answer = await sensed(peer)
  const dead = answer.entities.findIndex((e) => e.position[0] === 10.5)
  const gone = answer.entities.findIndex((e) => e.position[0] === 6.5)
  assert.ok(dead >= 0 && gone >= 0)
  assert.equal(slotOf(answer, HOTBAR).what, 'iron_sword')
  bot.emit('entityDead', bot.entities[44])
  client.emit('entity_destroy', { entityIds: [45] })
  await H.settled()
  for (const [id, entity, expected] of [['a-dead', dead, 'targetDied'], ['a-gone', gone, 'targetGone']]) {
    const report = await peer.answer(H.request('attack', id, {
      senseId: 's1',
      entity,
      slot: HOTBAR,
      attacks: 1,
      approach: false,
      maxPursuitDistance: 0,
      minHealth: 4,
      timeoutMs: 4000,
      sprint: false
    }))
    assert.ok([expected, 'targetGone', 'targetDied'].includes(report.outcome), JSON.stringify(report))
    assert.equal(report.attacksLanded ?? 0, 0)
  }
  peer.stop()
})
