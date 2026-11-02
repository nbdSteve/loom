'use strict'

/**
 * The world border, read from the server's own packets and searched as the wall it is.
 */

const assert = require('node:assert/strict')
const { EventEmitter } = require('node:events')
const { afterEach, test } = require('node:test')

const H = require('./harness')
const { BORDER_EXCLUDED, blockInside, borderExclusion, columnInside, trackWorldBorder, worldBorderOf } = require('../src/border')

afterEach(H.disconnected)

let openBody = null
afterEach(async () => {
  if (openBody !== null) await openBody.stop()
  openBody = null
})

test('the border is what the server last stated, and the smaller end while it changes', () => {
  const bot = { _client: new EventEmitter() }
  assert.equal(trackWorldBorder(bot), trackWorldBorder(bot), 'one reader per bot')
  assert.equal(worldBorderOf(bot), undefined, 'no border is stated before the server sends one')
  bot._client.emit('initialize_world_border', { x: 0, z: 0, oldDiameter: 128, newDiameter: 128, speed: 0 })
  assert.deepEqual(worldBorderOf(bot), { x: 0, z: 0, diameter: 128 })
  bot._client.emit('world_border_center', { x: 8.5, z: -3 })
  bot._client.emit('world_border_size', { diameter: 64 })
  assert.deepEqual(worldBorderOf(bot), { x: 8.5, z: -3, diameter: 64 })
  // A border shrinking or growing is searched as its smaller end throughout.
  bot._client.emit('world_border_lerp_size', { oldDiameter: 64, newDiameter: 32, speed: 10_000 })
  assert.equal(worldBorderOf(bot).diameter, 32)
  bot._client.emit('world_border_lerp_size', { oldDiameter: 32, newDiameter: 96, speed: 10_000 })
  assert.equal(worldBorderOf(bot).diameter, 32)
  // A packet the wire could not carry honestly changes nothing.
  bot._client.emit('world_border_size', { diameter: Number.NaN })
  bot._client.emit('world_border_center', { x: undefined, z: 0 })
  assert.deepEqual(worldBorderOf(bot), { x: 8.5, z: -3, diameter: 32 })
  assert.equal(worldBorderOf({}), undefined)
})

test('a column is inside only when a body standing in it is wholly inside', () => {
  const border = { x: 0, z: 0, diameter: 128 }
  assert.equal(columnInside(border, -64, -64), true)
  assert.equal(columnInside(border, 63, 63), true)
  assert.equal(columnInside(border, -65, 0), false)
  assert.equal(columnInside(border, 0, 64), false)
  // An off-grid border cuts a column a body's box would cross.
  assert.equal(columnInside({ x: 0.5, z: 0, diameter: 128 }, -64, 0), false)
  const excluded = borderExclusion(border)
  assert.equal(excluded({ position: { x: 0, y: 64, z: 64 } }), BORDER_EXCLUDED)
  assert.equal(excluded({ position: { x: 0, y: 64, z: 63 } }), 0)
  assert.equal(excluded({}), 0)
})

test('a block is inside when any of it is inside, as the server judges an interaction', () => {
  // r36 zeta's short grass stood at x -65, one column past a 128-block border centred on (0, 0).
  const border = { x: 0, z: 0, diameter: 128 }
  assert.equal(blockInside(border, -64, -36), true)
  assert.equal(blockInside(border, -65, -36), false)
  assert.equal(blockInside(border, 63, 63), true)
  assert.equal(blockInside(border, 64, 0), false)
  assert.equal(blockInside(border, 0, -65), false)
  // An off-grid border keeps a block it cuts, which a standing body's column would not be.
  assert.equal(blockInside({ x: 0.5, z: 0, diameter: 128 }, -64, 0), true)
  assert.equal(columnInside({ x: 0.5, z: 0, diameter: 128 }, -64, 0), false)
})

test('a walk past the border has no route, and one inside it arrives', async () => {
  const given = await H.embodied()
  const { body, bot, client, port } = given
  openBody = body
  H.spawned(client)
  // Columns 4 to 11 lie inside a border eight blocks across, centred on (8, 8).
  client.emit('initialize_world_border', { x: 8, z: 8, oldDiameter: 8, newDiameter: 8, speed: 0 })
  client.emit('position', H.positionPacket({ x: 5.5, y: 64, z: 8.5, yaw: 0, pitch: 0, teleportId: 1 }))
  H.ground(bot)
  await H.settled(200)
  const peer = await H.handshaken(port)
  const past = await H.promptly(peer.answer(H.request('moveTo', 'm1', {
    destination: [13, 64, 8], tolerance: 0, timeoutMs: 10_000, sprint: false
  })), 'the walk past the border', 14_000)
  assert.equal(past.outcome, 'noRoute', JSON.stringify(past))
  const inside = await H.promptly(peer.answer(H.request('moveTo', 'm2', {
    destination: [10, 64, 8], tolerance: 0, timeoutMs: 10_000, sprint: false
  })), 'the walk inside the border', 14_000)
  assert.equal(inside.outcome, 'arrived', JSON.stringify(inside))
  peer.stop()
})

test('a bot with no protocol client states no border', () => {
  const bot = {}
  assert.equal(trackWorldBorder(bot)(), undefined)
  assert.equal(worldBorderOf(bot), undefined)
})

test('a client that cannot start stops the control channel it opened, and the start fails', async () => {
  const { createBody } = require('../src/body')
  const net = require('node:net')
  const port = await H.freePort()
  const body = createBody({
    agentId: H.AGENT,
    username: 'border',
    minecraft: { host: '127.0.0.1', port: await H.freePort(), version: '1.21.1' },
    control: { host: '127.0.0.1', port },
    createBot: () => { throw new Error('no client') }
  })
  await assert.rejects(body.start(), /no client/)
  // Nothing listens on the control port any more.
  await assert.rejects(new Promise((resolve, reject) => {
    const socket = net.connect(port, '127.0.0.1', () => { socket.end(); resolve() })
    socket.on('error', reject)
  }), /ECONNREFUSED/)
})

test('a flight ends only three blocks inside the border and no more than four below its floor', () => {
  const { FLIGHT_BORDER_MARGIN_BLOCKS, MAX_FLIGHT_DESCENT_BLOCKS, SafeFlightGoal } = require('../src/movement')
  const anywhere = { heuristic: () => 0, isEnd: () => true, hasChanged: () => false, isValid: () => true }
  const goal = new SafeFlightGoal(anywhere, { border: { x: 0, z: 0, diameter: 128 }, floor: 66 - MAX_FLIGHT_DESCENT_BLOCKS })
  // r23 kappa's flights ended along z -63.4; the end must now lie three blocks further in.
  assert.equal(goal.isEnd({ x: 10, y: 66, z: -64 }), false)
  assert.equal(goal.isEnd({ x: 10, y: 66, z: -64 + FLIGHT_BORDER_MARGIN_BLOCKS }), true)
  assert.equal(goal.isEnd({ x: 63 - FLIGHT_BORDER_MARGIN_BLOCKS, y: 66, z: 0 }), true)
  assert.equal(goal.isEnd({ x: 64 - FLIGHT_BORDER_MARGIN_BLOCKS, y: 66, z: 0 }), false)
  // A ravine sixteen blocks down is no refuge; four down is.
  assert.equal(goal.isEnd({ x: 0, y: 50, z: 0 }), false)
  assert.equal(goal.isEnd({ x: 0, y: 62, z: 0 }), true)
  // No border stated holds only the descent.
  assert.equal(new SafeFlightGoal(anywhere, { border: undefined, floor: 0 }).isEnd({ x: 1000, y: 1, z: 0 }), true)
})

test('a flight floor is four below the lower of its start and the ground around it, trees read through', () => {
  const { MAX_FLIGHT_DESCENT_BLOCKS, flightFloorOf } = require('../src/movement')
  // Grass at y 64 everywhere, so a body stands at 65; an oak's leaves fill y 66..70 at the origin.
  const world = (shaft) => ({
    blockAt: (at) => {
      if (at.y === 64 && !(shaft && at.x === 0 && at.z === 0)) return { name: 'grass_block', boundingBox: 'block' }
      if (at.x === 0 && at.z === 0 && at.y >= 66 && at.y <= 70) return { name: 'oak_leaves', boundingBox: 'block' }
      if (at.y === 54) return { name: 'stone', boundingBox: 'block' }
      return { name: 'air', boundingBox: 'empty' }
    }
  })
  const start = (y) => ({ x: 0.5, y, z: 0.5 })
  assert.equal(flightFloorOf(world(false), start(65)), 65 - MAX_FLIGHT_DESCENT_BLOCKS)
  // On the canopy the floor is the grass's, not the leaves' (v32 plains rehearsal, beta).
  assert.equal(flightFloorOf(world(false), start(71)), 65 - MAX_FLIGHT_DESCENT_BLOCKS)
  // At the foot of its own shaft a body's floor is its own, the lower of the two.
  assert.equal(flightFloorOf(world(true), start(55)), 55 - MAX_FLIGHT_DESCENT_BLOCKS)
  // A client that knows no ground keeps the start's floor; one with no position has none.
  assert.equal(flightFloorOf({ blockAt: () => null }, start(71)), 71 - MAX_FLIGHT_DESCENT_BLOCKS)
  assert.equal(flightFloorOf({ blockAt: () => null }, undefined), -Infinity)
})
