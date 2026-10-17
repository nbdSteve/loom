'use strict'

/**
 * An urgent flight preempting a routine dig in flight, driven offline end to end.
 *
 * The case drives the real body process over real control sockets, around a real Mineflayer
 * bot with the real pathfinder and physics, fed real 1.21.1 packets by the fake protocol client
 * and standing on a real Prismarine chunk. A bare-hand dig of stone holds the one exclusive owner
 * for seconds; a zombie is in view. The flight the JVM sends is refused `notReady` behind the
 * dig - the r13 eta refusal - and the JVM's preemption then halts the dig and asks again, which
 * this proves the body serves: the halt cancels the dig with the block unbroken, and the flight
 * that follows owns the path and arrives clear of the threat.
 */

const assert = require('node:assert/strict')
const { afterEach, test } = require('node:test')

const { Vec3 } = require('vec3')

const H = require('./harness')

afterEach(H.disconnected)

const FEET = Object.freeze({ x: 8.5, y: 64, z: 8.5 })
const FED = Object.freeze({ health: 20, food: 20, foodSaturation: 5 })
// Bare-hand stone: hardness 1.5 and not harvestable by hand, so 7.5 s on the ground - long
// enough that the dig is certainly in flight when the threat is answered.
const STONE = Object.freeze({ x: 9, y: 64, z: 8 })
const ZOMBIE = Object.freeze({ id: 41, what: 'zombie', x: 8.5, y: 64, z: 14.5 })
const DIG_DEADLINE_MS = 20_000
const FLIGHT_DEADLINE_MS = 8_000

let openBody = null
afterEach(async () => {
  if (openBody !== null) await openBody.stop()
  openBody = null
})

test('a flight refused behind a routine dig is served once the dig is halted', async () => {
  const given = await H.embodied()
  const { body, bot, client, port } = given
  openBody = body
  H.spawned(client, FED)
  H.clockReported(client, { timeOfDay: 14_000, day: 0 })
  client.emit('position', H.positionPacket({ ...FEET, yaw: 0, pitch: 0, teleportId: 1 }))
  H.ground(bot)
  bot.world.setBlockStateId(new Vec3(STONE.x, STONE.y, STONE.z), H.registry.blocksByName.stone.defaultState)
  H.spotted(client, ZOMBIE)
  await H.settled(300)

  // The routine half: one connection senses and digs the stone.
  const routine = await H.handshaken(port)
  const seen = await routine.answer(H.request('sense', 's1', { radius: 16, limit: 48 }))
  const block = seen.blocks.findIndex((offered) =>
    offered.position[0] === STONE.x && offered.position[1] === STONE.y && offered.position[2] === STONE.z)
  assert.ok(block >= 0, JSON.stringify(seen.blocks))
  routine.send(H.request('dig', 'd1', { senseId: 's1', block, timeoutMs: DIG_DEADLINE_MS }))
  await H.eventually(() => assert.ok(
    client.writes.some(({ name, parameters }) => name === 'block_dig' && parameters.status === 0),
    'the dig has started'
  ))

  // The urgent half: a sense of the threat, and a flight the busy body must refuse.
  const urgent = await H.handshaken(port)
  const threat = await urgent.answer(H.request('sense', 's2', { radius: 16, limit: 48 }))
  const entity = threat.entities.findIndex((offered) => offered.what === 'zombie')
  assert.ok(entity >= 0, JSON.stringify(threat.entities))
  const flight = (id) => H.request('flee', id, {
    senseId: 's2', entity, distance: 9, timeoutMs: FLIGHT_DEADLINE_MS, sprint: true
  })
  const refused = await urgent.answer(flight('f1'))
  assert.equal(refused.error, 'notReady', JSON.stringify(refused))

  // Preemption: halt the routine work, which answers the dig as cancelled, then fly.
  const halt = await urgent.answer(H.request('halt', 'halt-1'))
  assert.equal(halt.stopped, true, JSON.stringify(halt))
  const dug = await H.promptly(routine.read(), 'the cancelled dig')
  assert.equal(dug.outcome, 'cancelled', JSON.stringify(dug))
  assert.equal(bot.blockAt(new Vec3(STONE.x, STONE.y, STONE.z)).name, 'stone')
  const report = await H.promptly(urgent.answer(flight('f2')), 'the flight', FLIGHT_DEADLINE_MS + 3000)
  assert.equal(report.outcome, 'arrived', JSON.stringify(report))
  assert.ok(report.separationGained > 0, JSON.stringify(report))
  assert.equal(bot.physicsEnabled, false)
  routine.stop()
  urgent.stop()
})
