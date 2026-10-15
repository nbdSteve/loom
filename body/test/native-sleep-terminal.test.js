'use strict'

/**
 * Sleep driven to its real terminal outcomes, from above and below at once.
 *
 * The body process runs around a real Mineflayer 1.21.1 bot, loaded with the body's own
 * `installSleeping` seam, standing on a real Prismarine chunk that holds a real two-part
 * bed. The bed offer is minted by the process's own `sense` answer, and the server half
 * is the packets vanilla sends: `entity_metadata` with the sleeping pose for bed entry,
 * `animation` 2 (leave bed) for the wake, and `block_change` for a bed that changes after
 * it was offered. Nothing in `sleeping.js` or Mineflayer's bed plugin is stubbed.
 */

const assert = require('node:assert/strict')
const { afterEach, test } = require('node:test')
const { Vec3 } = require('vec3')

const H = require('./harness')

afterEach(H.disconnected)

let openBody = null
afterEach(async () => {
  if (openBody !== null) await openBody.stop()
  openBody = null
})

const Block = require('prismarine-block')(H.registry)

const STANDING = [8.5, 64, 8.5]
const FOOT = [9, 64, 8]
const HEAD = [10, 64, 8]
const NIGHT = 13_000
const DAY = 6_000
/** Long enough for a real sleep request plus its server answers, never a test's bound. */
const AMPLE_MS = 8_000
/** The player metadata index Mineflayer reads `pose` from under 1.21.1. */
const POSE_KEY = H.registry.entitiesByName.player.metadataKeys.indexOf('pose')
const SLEEPING_POSE = 2
const LEAVE_BED_ANIMATION = 2
const SELF = 7

/** One real bed state, spelled through the registry's own property encoding. */
function bedState (part, { occupied = false, facing = 'east' } = {}) {
  return Block.fromProperties('red_bed', { facing, occupied, part }, 0).stateId
}

/** A bed lying east-west beside the standing body, foot nearer, on the harness ground. */
function bedded (bot, { occupied = false } = {}) {
  H.ground(bot, {})
  bot.world.setBlockStateId(new Vec3(...FOOT), bedState('foot', { occupied }))
  bot.world.setBlockStateId(new Vec3(...HEAD), bedState('head', { occupied }))
}

/** A body in the world at `timeOfDay`, with a bed beside it and a handshaken controller. */
async function bedroom ({ timeOfDay = NIGHT, occupied = false } = {}) {
  const started = await H.embodied()
  openBody = started.body
  const { client, bot, port } = started
  H.spawned(client)
  H.clockReported(client, { timeOfDay })
  client.emit('position', H.positionPacket({
    x: STANDING[0], y: STANDING[1], z: STANDING[2], yaw: 0, pitch: 0, teleportId: 1
  }))
  bedded(bot, { occupied })
  await H.settled(150)
  const peer = await H.handshaken(port)
  return { ...started, peer }
}

/** The ordinal the sense answer gave one exact block position, asserted offered. */
function ordinalOf (sensed, position) {
  const index = sensed.blocks.findIndex((block) =>
    block.position.every((value, axis) => value === position[axis]))
  assert.notEqual(index, -1, `the sense offered no block at ${position}`)
  assert.equal(sensed.blocks[index].what, 'red_bed')
  return index
}

async function sensedBed (peer, senseId = 's1', position = HEAD) {
  const sensed = await peer.answer(H.request('sense', senseId, { radius: 6, limit: 48 }))
  assert.equal(sensed.op, 'sense', JSON.stringify(sensed))
  return ordinalOf(sensed, position)
}

/** What vanilla sends when it accepts the bed: the player's pose becomes sleeping. */
function serverSlept (client) {
  client.emit('entity_metadata', {
    entityId: SELF,
    metadata: [{ key: POSE_KEY, type: 'pose', value: SLEEPING_POSE }]
  })
}

/** What vanilla sends when the player leaves the bed, at morning or on request. */
function serverWoke (client) {
  client.emit('animation', { entityId: SELF, animation: LEAVE_BED_ANIMATION })
}

/** Whether the client wrote a use-item-on-block for this exact position. */
function clickedAt (client, position) {
  return client.writes.some(({ name, parameters }) =>
    name === 'block_place' &&
    parameters.location.x === position[0] &&
    parameters.location.y === position[1] &&
    parameters.location.z === position[2])
}

/** The leave-bed `entity_action` Mineflayer's `wake` writes. */
function wakeWrites (client) {
  return client.writes.filter(({ name, parameters }) =>
    name === 'entity_action' && parameters.actionId === 2).length
}

/** Waits for a client write the body must make before the server can answer it. */
async function wrote (client, predicate, what) {
  return H.eventually(() => assert.ok(predicate(client), what), 3_000)
}

function listenerCounts (bot) {
  return Object.fromEntries(['sleep', 'wake', 'entitySleep', 'entityWake']
    .map((event) => [event, bot.listenerCount(event)]))
}

test('a real bed entry and vanilla wake answer woke at the exact retained bed', async () => {
  const { bot, client, peer } = await bedroom()
  const before = listenerCounts(bot)
  const block = await sensedBed(peer)

  peer.send(H.request('sleep', 'z1', { senseId: 's1', block, timeoutMs: AMPLE_MS }))
  await wrote(client, (c) => clickedAt(c, HEAD), 'the body clicked the retained head')
  serverSlept(client)
  await H.settled()
  assert.equal(bot.isSleeping, true)

  // The request still owns the body while vanilla holds it in bed.
  const contested = await peer.answer(H.request('sleep', 'z2', {
    senseId: 's1', block, timeoutMs: AMPLE_MS
  }))
  assert.equal(contested.error, 'notReady')
  assert.equal(contested.requestId, 'z2')

  serverWoke(client)
  const report = await H.promptly(peer.read(), 'the sleep report')
  assert.equal(report.op, 'sleep')
  assert.equal(report.requestId, 'z1')
  assert.equal(report.outcome, 'woke')
  assert.equal(report.entered, true)
  assert.equal(bot.isSleeping, false)
  assert.equal(wakeWrites(client), 0, 'a vanilla wake needs no client wake request')
  assert.deepEqual(listenerCounts(bot), before, 'the request left no sleep listener behind')

  // The owner was released: a second sleep from a fresh sense is served, not notReady.
  const again = await sensedBed(peer, 's2')
  peer.send(H.request('sleep', 'z3', { senseId: 's2', block: again, timeoutMs: AMPLE_MS }))
  await H.settled()
  serverSlept(client)
  await H.settled()
  serverWoke(client)
  const second = await H.promptly(peer.read(), 'the second sleep report')
  assert.equal(second.requestId, 'z3')
  assert.equal(second.outcome, 'woke')
  assert.deepEqual(listenerCounts(bot), before)
})

test('the foot half that was offered is the exact block clicked', async () => {
  const { client, peer } = await bedroom()
  const block = await sensedBed(peer, 's1', FOOT)
  peer.send(H.request('sleep', 'z1', { senseId: 's1', block, timeoutMs: AMPLE_MS }))
  // Mineflayer checks the head exists but clicks the offered half; vanilla accepts either.
  await wrote(client, (c) => clickedAt(c, FOOT), 'the offered foot was clicked')
  assert.equal(clickedAt(client, HEAD), false)
  serverSlept(client)
  await H.settled()
  serverWoke(client)
  const report = await H.promptly(peer.read(), 'the sleep report')
  assert.equal(report.outcome, 'woke')
  assert.equal(report.entered, true)
  const places = client.writes.filter(({ name }) => name === 'block_place')
  assert.equal(places.length, 1, 'exactly one bed use was written')
})

test('daytime is refused by the library before any click', async () => {
  const { bot, client, peer } = await bedroom({ timeOfDay: DAY })
  const before = listenerCounts(bot)
  const block = await sensedBed(peer)
  const report = await H.promptly(peer.answer(H.request('sleep', 'z1', {
    senseId: 's1', block, timeoutMs: AMPLE_MS
  })), 'the daytime refusal')
  assert.equal(report.op, 'sleep')
  assert.equal(report.outcome, 'notNight')
  assert.equal(report.entered, false)
  assert.equal(clickedAt(client, HEAD), false)
  assert.deepEqual(listenerCounts(bot), before)
})

test('an occupied bed is refused from its own block state before any click', async () => {
  const { client, peer } = await bedroom({ occupied: true })
  const block = await sensedBed(peer)
  const report = await H.promptly(peer.answer(H.request('sleep', 'z1', {
    senseId: 's1', block, timeoutMs: AMPLE_MS
  })), 'the occupied refusal')
  assert.equal(report.outcome, 'occupied')
  assert.equal(report.entered, false)
  assert.equal(clickedAt(client, HEAD), false)
})

test('a bed whose state changed after the sense is refused as bedChanged', async () => {
  const { client, peer } = await bedroom()
  const block = await sensedBed(peer)
  // Someone else lay down: vanilla restates both halves as occupied.
  client.emit('block_change', {
    location: { x: HEAD[0], y: HEAD[1], z: HEAD[2] },
    type: bedState('head', { occupied: true })
  })
  await H.settled()
  const report = await H.promptly(peer.answer(H.request('sleep', 'z1', {
    senseId: 's1', block, timeoutMs: AMPLE_MS
  })), 'the changed-bed refusal')
  assert.equal(report.outcome, 'bedChanged')
  assert.equal(report.entered, false)
  assert.equal(clickedAt(client, HEAD), false)

  // A bed gone entirely is its own closed answer.
  const fresh = await sensedBed(peer, 's2')
  client.emit('block_change', {
    location: { x: HEAD[0], y: HEAD[1], z: HEAD[2] },
    type: H.registry.blocksByName.air.defaultState
  })
  await H.settled()
  const gone = await H.promptly(peer.answer(H.request('sleep', 'z2', {
    senseId: 's2', block: fresh, timeoutMs: AMPLE_MS
  })), 'the gone-bed refusal')
  assert.equal(gone.outcome, 'bedGone')
})

test('a deadline inside the bed asks vanilla to wake and answers timedOut on its wake', async () => {
  const { bot, client, peer } = await bedroom()
  const before = listenerCounts(bot)
  const block = await sensedBed(peer)
  peer.send(H.request('sleep', 'z1', { senseId: 's1', block, timeoutMs: 400 }))
  await wrote(client, (c) => clickedAt(c, HEAD), 'the bed was clicked')
  serverSlept(client)
  await wrote(client, (c) => wakeWrites(c) === 1, 'the deadline wrote one leave-bed request')
  serverWoke(client)
  const report = await H.promptly(peer.read(), 'the deadline report')
  assert.equal(report.outcome, 'timedOut')
  assert.equal(report.entered, true)
  assert.equal(bot.isSleeping, false)
  assert.equal(wakeWrites(client), 1)
  assert.deepEqual(listenerCounts(bot), before)
})

test('a deadline whose wake vanilla never confirms is bounded by the wake grace', async () => {
  const { bot, client, peer } = await bedroom()
  const before = listenerCounts(bot)
  const block = await sensedBed(peer)
  peer.send(H.request('sleep', 'z1', { senseId: 's1', block, timeoutMs: 300 }))
  await wrote(client, (c) => clickedAt(c, HEAD), 'the bed was clicked')
  serverSlept(client)
  const startedAt = Date.now()
  const report = await H.promptly(peer.read(), 'the bounded wake cleanup', 4_000)
  assert.equal(report.outcome, 'timedOut')
  assert.equal(report.entered, true)
  assert.equal(wakeWrites(client), 1)
  // Ownership was held through WAKE_GRACE_MS (1000) after the 300 ms deadline.
  assert.ok(Date.now() - startedAt >= 900, `released after ${Date.now() - startedAt} ms`)
  assert.deepEqual(listenerCounts(bot), before)
})

test('a bed vanilla never accepts ends bounded with no listener left behind', async () => {
  const { bot, client, peer } = await bedroom()
  const before = listenerCounts(bot)
  const block = await sensedBed(peer)
  peer.send(H.request('sleep', 'z1', { senseId: 's1', block, timeoutMs: AMPLE_MS }))
  await wrote(client, (c) => clickedAt(c, HEAD), 'the bed was clicked')
  // No entry packet arrives. Mineflayer's own wait rejects at 3000 ms, then the body's
  // late-entry guard holds to 3250 ms before answering.
  const report = await H.promptly(peer.read(), 'the unaccepted-bed report', 6_000)
  assert.equal(report.outcome, 'sleepRefused')
  assert.equal(report.entered, false)
  assert.ok(report.elapsedMs >= 3_000, `answered after ${report.elapsedMs} ms`)
  assert.deepEqual(listenerCounts(bot), before, 'an unaccepted bed left a sleep listener behind')

  // And the owner was released.
  const again = await sensedBed(peer, 's2')
  peer.send(H.request('sleep', 'z2', { senseId: 's2', block: again, timeoutMs: AMPLE_MS }))
  await H.settled()
  serverSlept(client)
  await H.settled()
  serverWoke(client)
  const second = await H.promptly(peer.read(), 'the next sleep report')
  assert.equal(second.requestId, 'z2')
  assert.equal(second.outcome, 'woke')
})

test('a halt inside the bed wakes through vanilla and answers cancelled', async () => {
  const { bot, client, peer } = await bedroom()
  const block = await sensedBed(peer)
  peer.send(H.request('sleep', 'z1', { senseId: 's1', block, timeoutMs: AMPLE_MS }))
  await wrote(client, (c) => clickedAt(c, HEAD), 'the bed was clicked')
  serverSlept(client)
  await H.settled()
  peer.send(H.request('halt', 'h2'))
  await wrote(client, (c) => wakeWrites(c) === 1, 'the halt wrote one leave-bed request')
  serverWoke(client)
  const answers = [await H.promptly(peer.read(), 'first answer'),
    await H.promptly(peer.read(), 'second answer')]
  const sleep = answers.find((frame) => frame.op === 'sleep')
  assert.ok(sleep, JSON.stringify(answers))
  assert.equal(sleep.outcome, 'cancelled')
  assert.equal(sleep.entered, true)
  assert.equal(bot.isSleeping, false)
})
