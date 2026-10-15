'use strict'

/**
 * Combat driven to its real terminal outcomes, offline.
 *
 * Every case drives the real body process over a real control socket, around a real
 * Mineflayer bot fed real 1.21.1 packets by the fake protocol client, standing on a real
 * Prismarine chunk. The server half of a landed hit is the packets a vanilla server sends
 * for it - `damage_event`, `entity_status` and the target's `entity_metadata` health - and
 * target motion is `entity_teleport`. Nothing writes a field onto the bot and no action
 * module is stubbed: the attack is asked for with only the handles one `sense` answer
 * offered, and what the body did is read back from the packets it wrote.
 */

const assert = require('node:assert/strict')
const { afterEach, test } = require('node:test')

const H = require('./harness')
const { ATTACK_INTERVAL_MS } = require('../src/combat')

afterEach(H.disconnected)

const FEET = Object.freeze({ x: 8.5, y: 64, z: 8.5 })
const FED = Object.freeze({ health: 20, food: 20, foodSaturation: 5 })
const HOTBAR = H.QUICK_BAR_START
const ZOMBIE = 44
const OTHER = 45

/** The packets a body writes to move or turn itself. */
const MOVEMENT_PACKETS = new Set(['position', 'position_look', 'look', 'flying', 'entity_action', 'player_input'])

let openBody = null
afterEach(async () => {
  if (openBody !== null) await openBody.stop()
  openBody = null
})

/** A body process in the world on real ground, holding `stacks`, beside `creatures`. */
async function scene ({ stacks = [[HOTBAR, 'iron_sword', 1]], creatures = [], vitals = FED } = {}) {
  const given = await H.embodied()
  const { body, bot, client, port } = given
  openBody = body
  H.spawned(client, vitals)
  H.clockReported(client, { timeOfDay: 6000, day: 2 })
  client.emit('position', H.positionPacket({ ...FEET, yaw: 0, pitch: 0, teleportId: 1 }))
  H.ground(bot)
  H.stocked(client, stacks)
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

function ordinalAt (answer, x) {
  const index = answer.entities.findIndex((entry) => entry.position[0] === x)
  assert.ok(index >= 0, `no entity offered at x=${x}: ${JSON.stringify(answer.entities)}`)
  return index
}

function attackRequest (requestId, overrides) {
  return H.request('attack', requestId, {
    senseId: 's1',
    slot: HOTBAR,
    attacks: 1,
    approach: false,
    maxPursuitDistance: 0,
    minHealth: 4,
    timeoutMs: 6000,
    sprint: false,
    ...overrides
  })
}

/** Every `use_entity` attack the bot wrote, and against which entity id. */
function strikes (client, since = 0) {
  return client.writes.slice(since)
    .filter((write) => write.name === 'use_entity' && write.parameters.mouse === 1)
    .map((write) => write.parameters.target)
}

function movements (client, since = 0) {
  return client.writes.slice(since).filter((write) => MOVEMENT_PACKETS.has(write.name))
}

/** The packets a vanilla server sends for one landed melee hit on `id`. */
function landedHit (client, id, health) {
  client.emit('damage_event', {
    entityId: id,
    sourceTypeId: 0,
    sourceCauseId: 8,
    sourceDirectId: 8,
    sourcePosition: undefined
  })
  client.emit('entity_status', { entityId: id, entityStatus: 2 })
  client.emit('entity_metadata', {
    entityId: id,
    metadata: [{ key: 9, type: 'float', value: health }]
  })
}

/** Resolves on the first `use_entity` the bot writes after `since`. */
function nextStrike (client, since) {
  return H.promptly(H.eventually(() => {
    const seen = strikes(client, since)
    if (seen.length === 0) throw new Error('no strike yet')
    return seen
  }, 5_000), 'a strike')
}

test('attack: a landed hit the server confirms ends attacked, against the exact target with the exact weapon', async () => {
  const { peer, client, bot } = await scene({
    creatures: [
      { id: ZOMBIE, what: 'zombie', x: 10.5, y: 64, z: 8.5 },
      { id: OTHER, what: 'zombie', x: 6.5, y: 64, z: 8.5 }
    ]
  })
  const answer = await sensed(peer)
  const entity = ordinalAt(answer, 10.5)
  const hurt = []
  bot.on('entityHurt', (entity) => hurt.push(entity))
  const since = client.writes.length
  peer.send(attackRequest('a-hit', { entity, attacks: 1 }))
  await nextStrike(client, since)
  landedHit(client, ZOMBIE, 14)
  const report = await H.promptly(peer.read(), 'the attack report')
  assert.equal(report.outcome, 'attacked', JSON.stringify(report))
  assert.equal(report.attacks, 1)
  assert.deepEqual(strikes(client, since), [ZOMBIE])
  assert.equal(bot.heldItem.name, 'iron_sword')
  // Mineflayer keeps a living entity's health as raw metadata index 9; there is no
  // `entity.health` field, so the server's confirmation is read where the library put it.
  assert.equal(bot.entities[ZOMBIE].metadata[9], 14)
  assert.equal(hurt.length, 2, 'damage_event and entity_status 2 each report entityHurt')
  assert.ok(hurt.every((entity) => entity === bot.entities[ZOMBIE]))
  // No held-item switch: the offered sword was already in hand.
  assert.equal(client.writes.slice(since).filter((w) => w.name === 'held_item_slot').length, 0)
  peer.stop()
})

test('attack: a strike budget is emitted exactly, spaced by the interval, then nothing more', async () => {
  const { peer, client } = await scene({ creatures: [{ id: ZOMBIE, what: 'zombie', x: 10.5, y: 64, z: 8.5 }] })
  const answer = await sensed(peer)
  const since = client.writes.length
  const startedAt = Date.now()
  const report = await H.promptly(peer.answer(attackRequest('a-budget', {
    entity: ordinalAt(answer, 10.5), attacks: 3
  })), 'the budget report', 8_000)
  assert.equal(report.outcome, 'attacked', JSON.stringify(report))
  assert.equal(report.attacks, 3)
  assert.ok(Date.now() - startedAt >= 2 * ATTACK_INTERVAL_MS)
  const after = client.writes.length
  await H.settled(ATTACK_INTERVAL_MS * 2)
  assert.deepEqual(strikes(client, since), [ZOMBIE, ZOMBIE, ZOMBIE])
  assert.deepEqual(strikes(client, after), [], 'a strike after the terminal answer')
  assert.deepEqual(movements(client, after), [], 'a movement packet after the terminal answer')
  peer.stop()
})

test('attack: the server killing the exact target mid-budget ends targetDied with no further strike', async () => {
  const { peer, client } = await scene({ creatures: [{ id: ZOMBIE, what: 'zombie', x: 10.5, y: 64, z: 8.5 }] })
  const answer = await sensed(peer)
  const since = client.writes.length
  peer.send(attackRequest('a-kill', { entity: ordinalAt(answer, 10.5), attacks: 4 }))
  await nextStrike(client, since)
  landedHit(client, ZOMBIE, 0)
  client.emit('entity_status', { entityId: ZOMBIE, entityStatus: 3 })
  const report = await H.promptly(peer.read(), 'the kill report')
  assert.equal(report.outcome, 'targetDied', JSON.stringify(report))
  assert.equal(report.attacks, 1)
  const after = client.writes.length
  await H.settled(ATTACK_INTERVAL_MS * 2)
  assert.deepEqual(strikes(client, since), [ZOMBIE])
  assert.deepEqual(strikes(client, after), [])
  peer.stop()
})

test('attack: the exact target destroyed mid-budget ends targetGone', async () => {
  const { peer, client } = await scene({ creatures: [{ id: ZOMBIE, what: 'zombie', x: 10.5, y: 64, z: 8.5 }] })
  const answer = await sensed(peer)
  const since = client.writes.length
  peer.send(attackRequest('a-gone', { entity: ordinalAt(answer, 10.5), attacks: 4 }))
  await nextStrike(client, since)
  client.emit('entity_destroy', { entityIds: [ZOMBIE] })
  const report = await H.promptly(peer.read(), 'the gone report')
  assert.equal(report.outcome, 'targetGone', JSON.stringify(report))
  assert.equal(report.attacks, 1)
  await H.settled(ATTACK_INTERVAL_MS * 2)
  assert.deepEqual(strikes(client, since), [ZOMBIE])
  peer.stop()
})

test('attack: a target id reused by another creature is targetChanged, never struck', async () => {
  const { peer, client } = await scene({ creatures: [{ id: ZOMBIE, what: 'zombie', x: 10.5, y: 64, z: 8.5 }] })
  const answer = await sensed(peer)
  const entity = ordinalAt(answer, 10.5)
  client.emit('entity_destroy', { entityIds: [ZOMBIE] })
  H.spotted(client, { id: ZOMBIE, what: 'skeleton', x: 10.5, y: 64, z: 8.5 })
  await H.settled()
  const since = client.writes.length
  const report = await peer.answer(attackRequest('a-reused', { entity }))
  assert.equal(report.outcome, 'targetChanged', JSON.stringify(report))
  assert.equal(report.attacks, 0)
  assert.deepEqual(strikes(client, since), [])
  peer.stop()
})

test('attack: a target that moves out of reach between strikes ends outOfRange and is not followed', async () => {
  const { peer, client, bot } = await scene({ creatures: [{ id: ZOMBIE, what: 'zombie', x: 10.5, y: 64, z: 8.5 }] })
  const answer = await sensed(peer)
  const since = client.writes.length
  const before = bot.entity.position.clone()
  peer.send(attackRequest('a-moved', { entity: ordinalAt(answer, 10.5), attacks: 4, maxPursuitDistance: 0 }))
  await nextStrike(client, since)
  client.emit('entity_teleport', { entityId: ZOMBIE, x: 14.5, y: 64, z: 8.5, yaw: 0, pitch: 0, onGround: true })
  const report = await H.promptly(peer.read(), 'the moved report')
  assert.equal(report.outcome, 'outOfRange', JSON.stringify(report))
  assert.equal(report.attacks, 1)
  assert.equal(report.travelled, 0)
  const after = client.writes.length
  await H.settled(ATTACK_INTERVAL_MS * 2)
  assert.deepEqual(strikes(client, since), [ZOMBIE])
  assert.deepEqual(movements(client, after), [], 'the body followed the moved target')
  assert.ok(bot.entity.position.distanceTo(before) < 0.01)
  peer.stop()
})

test('attack: approach walks one bounded route to the admitted position, strikes, and stops', async () => {
  const { peer, client, bot } = await scene({ creatures: [{ id: ZOMBIE, what: 'zombie', x: 13.5, y: 64, z: 8.5 }] })
  const answer = await sensed(peer)
  const since = client.writes.length
  const report = await H.promptly(peer.answer(attackRequest('a-approach', {
    entity: ordinalAt(answer, 13.5), approach: true, maxPursuitDistance: 8, attacks: 1, timeoutMs: 8000
  })), 'the approach report', 10_000)
  assert.equal(report.outcome, 'attacked', JSON.stringify(report))
  assert.equal(report.attacks, 1)
  assert.ok(report.travelled > 0 && report.travelled <= 8, JSON.stringify(report))
  assert.ok(bot.entity.position.distanceTo({ x: 13.5, y: 64, z: 8.5 }) <= 3.1)
  assert.deepEqual(strikes(client, since), [ZOMBIE])
  const after = client.writes.length
  await H.settled(ATTACK_INTERVAL_MS * 2)
  assert.deepEqual(movements(client, after), [], 'a movement packet after the terminal answer')
  assert.deepEqual(strikes(client, after), [])
  peer.stop()
})

test('attack: approach past the pursuit bound is refused before any route or strike', async () => {
  const { peer, client, bot } = await scene({ creatures: [{ id: ZOMBIE, what: 'zombie', x: 14.5, y: 64, z: 8.5 }] })
  const answer = await sensed(peer)
  const since = client.writes.length
  const before = bot.entity.position.clone()
  const report = await peer.answer(attackRequest('a-far', {
    entity: ordinalAt(answer, 14.5), approach: true, maxPursuitDistance: 4, attacks: 1
  }))
  assert.equal(report.outcome, 'outOfRange', JSON.stringify(report))
  assert.equal(report.attacks, 0)
  await H.settled(ATTACK_INTERVAL_MS)
  assert.deepEqual(strikes(client, since), [])
  assert.deepEqual(movements(client, since), [])
  assert.ok(bot.entity.position.distanceTo(before) < 0.01)
  peer.stop()
})

test('attack: a target that runs during the approach is not followed past its admitted position', async () => {
  const { peer, client, bot } = await scene({ creatures: [{ id: ZOMBIE, what: 'zombie', x: 13.5, y: 64, z: 8.5 }] })
  const answer = await sensed(peer)
  const since = client.writes.length
  peer.send(attackRequest('a-runs', {
    entity: ordinalAt(answer, 13.5), approach: true, maxPursuitDistance: 6, attacks: 2, timeoutMs: 8000
  }))
  await H.settled(30)
  client.emit('entity_teleport', { entityId: ZOMBIE, x: 15.5, y: 64, z: 8.5, yaw: 0, pitch: 0, onGround: true })
  const report = await H.promptly(peer.read(), 'the runs report', 10_000)
  // The route goes only to the admitted position (13.5); a target now 2 further on is
  // within the displacement bound but out of melee reach of where the route ended.
  assert.equal(report.outcome, 'outOfRange', JSON.stringify(report))
  assert.ok(bot.entity.position.x <= 13.5 + 0.5, `body followed to x=${bot.entity.position.x}`)
  const after = client.writes.length
  await H.settled(ATTACK_INTERVAL_MS * 2)
  assert.deepEqual(movements(client, after), [], 'a movement packet after the terminal answer')
  assert.deepEqual(strikes(client, after), [])
  for (const target of strikes(client, since)) assert.equal(target, ZOMBIE)
  peer.stop()
})

test('attack: a weapon slot swapped after sense is slotChanged and strikes nothing', async () => {
  const { peer, client } = await scene({ creatures: [{ id: ZOMBIE, what: 'zombie', x: 10.5, y: 64, z: 8.5 }] })
  const answer = await sensed(peer)
  H.stocked(client, [[HOTBAR, 'stone_sword', 1]])
  await H.settled()
  const since = client.writes.length
  const report = await peer.answer(attackRequest('a-swap', { entity: ordinalAt(answer, 10.5) }))
  assert.equal(report.outcome, 'slotChanged', JSON.stringify(report))
  assert.equal(report.attacks, 0)
  assert.deepEqual(strikes(client, since), [])
  peer.stop()
})

test('attack: body health falling to the floor mid-budget ends healthBound', async () => {
  const { peer, client } = await scene({ creatures: [{ id: ZOMBIE, what: 'zombie', x: 10.5, y: 64, z: 8.5 }] })
  const answer = await sensed(peer)
  const since = client.writes.length
  peer.send(attackRequest('a-floor', { entity: ordinalAt(answer, 10.5), attacks: 4, minHealth: 6 }))
  await nextStrike(client, since)
  client.emit('update_health', { health: 5, food: 20, foodSaturation: 5 })
  const report = await H.promptly(peer.read(), 'the floor report')
  assert.equal(report.outcome, 'healthBound', JSON.stringify(report))
  assert.equal(report.attacks, 1)
  await H.settled(ATTACK_INTERVAL_MS * 2)
  assert.deepEqual(strikes(client, since), [ZOMBIE])
  peer.stop()
})

test('attack: halt mid-budget ends cancelled and strikes nothing further', async () => {
  const { peer, client } = await scene({ creatures: [{ id: ZOMBIE, what: 'zombie', x: 10.5, y: 64, z: 8.5 }] })
  const answer = await sensed(peer)
  const since = client.writes.length
  peer.send(attackRequest('a-halt', { entity: ordinalAt(answer, 10.5), attacks: 4 }))
  await nextStrike(client, since)
  peer.send(H.request('halt', 'h-1'))
  const frames = [await H.promptly(peer.read(), 'first'), await H.promptly(peer.read(), 'second')]
  const report = frames.find((frame) => frame.op === 'attack')
  assert.ok(report !== undefined, JSON.stringify(frames))
  assert.equal(report.outcome, 'cancelled', JSON.stringify(report))
  assert.equal(report.attacks, 1)
  await H.settled(ATTACK_INTERVAL_MS * 2)
  assert.deepEqual(strikes(client, since), [ZOMBIE])
  peer.stop()
})
