'use strict'

/**
 * A walk's arrival, driven to its real terminal outcome offline.
 *
 * The case drives the real body process over a real control socket, around a real
 * Mineflayer bot with the real pathfinder and physics, fed real 1.21.1 packets by the fake
 * protocol client and standing on a real Prismarine chunk. Nothing is stubbed.
 *
 * The pathfinder emits `goal_reached` once the last node is within 0.35 blocks horizontally
 * and less than one block vertically, so a walk that ends in a step down reaches its goal
 * while the body is still falling onto it. An arrival is only true once the body has landed:
 * the client is on the ground, its position has stopped changing, and the last movement
 * report written to the server states that same grounded position.
 */

const assert = require('node:assert/strict')
const { afterEach, test } = require('node:test')

const H = require('./harness')

afterEach(H.disconnected)

// On a one-block step, so the destination below and beside it is reached by dropping down.
const STEP = Object.freeze({ x: 8, y: 64, z: 8 })
const FEET = Object.freeze({ x: 8.5, y: 65, z: 8.5 })
const DESTINATION = Object.freeze([8, 64, 9])
const FED = Object.freeze({ health: 20, food: 20, foodSaturation: 5 })
const AMPLE_MS = 8000

let openBody = null
afterEach(async () => {
  if (openBody !== null) await openBody.stop()
  openBody = null
})

/** The last position and ground state the client wrote to the server. */
function lastReported (client) {
  let position
  let onGround
  for (const { name, parameters } of client.writes) {
    if (!['position', 'position_look', 'look', 'flying'].includes(name)) continue
    if (typeof parameters?.onGround === 'boolean') onGround = parameters.onGround
    if (Number.isFinite(parameters?.x)) position = [parameters.x, parameters.y, parameters.z]
  }
  return { position, onGround }
}

test('a walk that ends in a step down arrives only once the body has landed', async () => {
  const given = await H.embodied()
  const { body, bot, client, port } = given
  openBody = body
  H.spawned(client, FED)
  H.clockReported(client, { timeOfDay: 6000, day: 2 })
  client.emit('position', H.positionPacket({ ...FEET, yaw: 0, pitch: 0, teleportId: 1 }))
  H.ground(bot, { walls: [STEP] })
  await H.settled(200)
  const peer = await H.handshaken(port)
  await peer.answer(H.request('sense', 's1', { radius: 8, limit: 8 }))

  const report = await H.promptly(peer.answer(H.request('moveTo', 'm1', {
    destination: DESTINATION, tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  })), 'the walk', AMPLE_MS + 3000)

  assert.equal(report.outcome, 'arrived', JSON.stringify(report))
  // The reported position is the landed one, not a sample taken on the way down.
  assert.equal(report.position[1], 64, JSON.stringify(report))
  assert.equal(bot.entity.onGround, true)
  assert.equal(bot.entity.position.y, 64)
  // The server was told the same grounded position the controller was.
  const reported = lastReported(client)
  assert.equal(reported.onGround, true)
  assert.deepEqual(reported.position, report.position)
  // And nothing is left to fall later: the simulation is shut with the body at rest.
  assert.equal(bot.physicsEnabled, false)
  await H.settled(300)
  assert.equal(bot.entity.position.y, 64)
  peer.stop()
})

test('a walk halted in mid-air is answered only once the body has landed', async () => {
  const given = await H.embodied()
  const { body, bot, client, port } = given
  openBody = body
  H.spawned(client, FED)
  H.clockReported(client, { timeOfDay: 6000, day: 2 })
  client.emit('position', H.positionPacket({ ...FEET, yaw: 0, pitch: 0, teleportId: 1 }))
  H.ground(bot, { walls: [STEP] })
  await H.settled(200)
  const owner = await H.handshaken(port)
  const other = await H.handshaken(port)
  await owner.answer(H.request('sense', 's1', { radius: 8, limit: 8 }))

  // Halt the walk on the first step the body is off the ground on its way down.
  let halted
  const airborne = () => {
    if (halted !== undefined || bot.entity.onGround !== false || bot.entity.position.y >= 65) return
    bot.off('physicsTick', airborne)
    halted = other.answer(H.request('halt', 'halt-1'))
  }
  bot.on('physicsTick', airborne)
  owner.send(H.request('moveTo', 'm1', {
    destination: [8, 64, 11], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  }))
  const report = await H.promptly(owner.read(), 'the halted walk', AMPLE_MS + 3000)
  const haltAnswer = await halted
  assert.equal(haltAnswer.stopped, true, JSON.stringify(haltAnswer))

  assert.equal(report.outcome, 'cancelled', JSON.stringify(report))
  assert.equal(report.position[1], 64, JSON.stringify(report))
  assert.equal(bot.entity.onGround, true)
  const reported = lastReported(client)
  assert.equal(reported.onGround, true)
  assert.deepEqual(reported.position, report.position)
  // The journey includes the fall, so it is never shorter than the straight line.
  const [fx, fy, fz] = report.from
  const [px, py, pz] = report.position
  assert.ok(report.travelled >= Math.hypot(px - fx, py - fy, pz - fz) - 1e-9, JSON.stringify(report))
  assert.equal(bot.physicsEnabled, false)
  owner.stop()
  other.stop()
})
