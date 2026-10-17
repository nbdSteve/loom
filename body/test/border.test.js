'use strict'

/**
 * The world border, read from the server's own packets and searched as the wall it is.
 */

const assert = require('node:assert/strict')
const { EventEmitter } = require('node:events')
const { afterEach, test } = require('node:test')

const H = require('./harness')
const { BORDER_EXCLUDED, borderExclusion, columnInside, trackWorldBorder, worldBorderOf } = require('../src/border')

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
