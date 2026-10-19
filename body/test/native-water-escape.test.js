'use strict'

/**
 * r27 epsilon's drowning, replayed offline.
 *
 * Epsilon dug sand underfoot beside water, and water filled the pit to above its head. Four walks
 * onto the sand beside it, one block up and standable, ended `noRoute` while it drowned
 * (body:00001278 to 00001479), because the movement policy makes water a wall: the search refused
 * every move whose cells held water, the body's own cells included. The body, bot, pathfinder and
 * physics here are real; the ground is a stone floor with a one-block pit flooded to the head.
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

/** A stone floor at y 63, a pit at (8, 63, 8) with stone below it, and water in the pit to the head. */
function floodedPit (bot, { flooded = true } = {}) {
  const column = new H.Chunk(H.OVERWORLD)
  const stone = H.registry.blocksByName.stone.defaultState
  const water = H.registry.blocksByName.water.defaultState
  for (let x = 0; x < 16; x += 1) {
    for (let z = 0; z < 16; z += 1) column.setBlockStateId(new Vec3(x, 63, z), stone)
  }
  column.setBlockStateId(new Vec3(8, 62, 8), stone)
  column.setBlockStateId(new Vec3(8, 63, 8), flooded ? water : H.registry.blocksByName.air.defaultState)
  if (flooded) column.setBlockStateId(new Vec3(8, 64, 8), water)
  bot.world.setColumn(0, 0, column)
}

async function inThePit ({ flooded }) {
  const given = await H.embodied()
  const { body, bot, client, port } = given
  openBody = body
  H.spawned(client)
  client.emit('position', H.positionPacket({ x: 8.5, y: 63, z: 8.5, yaw: 0, pitch: 0, teleportId: 1 }))
  floodedPit(bot, { flooded })
  await H.settled(200)
  const peer = await H.handshaken(port)
  const walk = await H.promptly(peer.answer(H.request('moveTo', 'm1', {
    destination: [7, 64, 8], tolerance: 0, timeoutMs: 10_000, sprint: false
  })), 'the walk out of the pit', 14_000)
  peer.stop()
  return { walk, bot }
}

test('a body in a dry pit walks out onto the floor beside it', async () => {
  const { walk } = await inThePit({ flooded: false })
  assert.equal(walk.outcome, 'arrived', JSON.stringify(walk))
})

/**
 * r27 epsilon's own shape: a two-deep pit flooded to the floor's level, with open air above it, so
 * the only dry cell to stand in is on the floor, two blocks above the feet - reached only by
 * swimming up, which a search that treats water as a wall never tries.
 */
function deepFloodedPit (bot) {
  const column = new H.Chunk(H.OVERWORLD)
  const stone = H.registry.blocksByName.stone.defaultState
  const water = H.registry.blocksByName.water.defaultState
  for (let x = 0; x < 16; x += 1) {
    for (let z = 0; z < 16; z += 1) column.setBlockStateId(new Vec3(x, 62, z), stone)
  }
  column.setBlockStateId(new Vec3(8, 60, 8), stone)
  column.setBlockStateId(new Vec3(8, 61, 8), water)
  column.setBlockStateId(new Vec3(8, 62, 8), water)
  bot.world.setColumn(0, 0, column)
}

test('a body at the bottom of a deep flooded pit swims up and walks out onto the floor', async () => {
  const given = await H.embodied()
  const { body, bot, client, port } = given
  openBody = body
  H.spawned(client)
  client.emit('position', H.positionPacket({ x: 8.5, y: 61, z: 8.5, yaw: 0, pitch: 0, teleportId: 1 }))
  deepFloodedPit(bot)
  await H.settled(200)
  const peer = await H.handshaken(port)
  const walk = await H.promptly(peer.answer(H.request('moveTo', 'm1', {
    destination: [7, 63, 8], tolerance: 0, timeoutMs: 10_000, sprint: false
  })), 'the swim out of the pit', 14_000)
  peer.stop()
  assert.equal(walk.outcome, 'arrived', JSON.stringify(walk))
  assert.ok(walk.position[1] >= 63, JSON.stringify(walk))
})

test('a body in a pit flooded to its head walks out onto the floor beside it', async () => {
  const { walk } = await inThePit({ flooded: true })
  assert.equal(walk.outcome, 'arrived', JSON.stringify(walk))
})
