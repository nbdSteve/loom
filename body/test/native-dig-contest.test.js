'use strict'

/**
 * Two agents digging one block, driven offline end to end: the r23 rehearsal's tick-3268 race.
 *
 * Two real body processes, each around a real Mineflayer bot on its own fake protocol client,
 * stand beside one dirt block. The test is their shared server: it holds the one break the first
 * finish wins, then broadcasts it to both clients, the way vanilla answers a finished dig to the
 * digger and to every player tracking the chunk. kappa digs first, writes its finish, and is
 * halted while the server's answer is still in flight - its next decision idled. iota is still
 * digging when kappa's break reaches it. The server credits the break to kappa alone, so kappa's
 * body must answer `dug` and iota's must not.
 */

const assert = require('node:assert/strict')
const { afterEach, test } = require('node:test')

const { Vec3 } = require('vec3')

const H = require('./harness')

afterEach(H.disconnected)

const FED = Object.freeze({ health: 20, food: 20, foodSaturation: 5 })
const BLOCK = Object.freeze({ x: 8, y: 64, z: 9 })
const DEADLINE_MS = 8000

const open = []
afterEach(async () => {
  for (const body of open.splice(0)) await body.stop()
})

/** One body beside the shared dirt block, with a controller that has sensed it. */
async function digger (feet) {
  const given = await H.embodied()
  open.push(given.body)
  const { bot, client, port } = given
  H.spawned(client, FED)
  H.clockReported(client, { timeOfDay: 6000, day: 2 })
  client.emit('position', H.positionPacket({ ...feet, yaw: 0, pitch: 0, teleportId: 1 }))
  H.ground(bot)
  bot.world.setBlockStateId(new Vec3(BLOCK.x, BLOCK.y, BLOCK.z), H.registry.blocksByName.dirt.defaultState)
  await H.settled(200)
  const peer = await H.handshaken(port)
  const sensed = await peer.answer(H.request('sense', 's1', { radius: 4, limit: 48 }))
  const block = sensed.blocks.findIndex((entry) =>
    entry.position[0] === BLOCK.x && entry.position[1] === BLOCK.y && entry.position[2] === BLOCK.z)
  assert.ok(block >= 0, JSON.stringify(sensed.blocks))
  return { ...given, peer, block }
}

/** Whether `client` has written its finish of the shared block. */
function finished (client) {
  return client.writes.some(({ name, parameters }) =>
    name === 'block_dig' && parameters.status === 2 &&
    parameters.location.x === BLOCK.x && parameters.location.y === BLOCK.y && parameters.location.z === BLOCK.z)
}

test('the agent whose finish the server took is told dug, and the other is told the block went', async () => {
  const kappa = await digger({ x: 8.5, y: 65, z: 8.5 })
  const iota = await digger({ x: 7.5, y: 65, z: 9.5 })

  kappa.peer.send(H.request('dig', 'kd', { senseId: 's1', block: kappa.block, timeoutMs: DEADLINE_MS }))
  await H.eventually(() => assert.ok(
    kappa.client.writes.some(({ name, parameters }) => name === 'block_dig' && parameters.status === 0)))
  await H.settled(300)
  iota.peer.send(H.request('dig', 'id', { senseId: 's1', block: iota.block, timeoutMs: DEADLINE_MS }))

  // kappa finishes first. The server has not answered yet when kappa's next decision halts it.
  await H.eventually(() => assert.ok(finished(kappa.client), 'kappa wrote its finish'), DEADLINE_MS)
  assert.equal(finished(iota.client), false, 'iota is still digging')
  const halter = await H.handshaken(kappa.port)
  const halted = await halter.answer(H.request('halt', 'halt-1'))

  // The server takes kappa's finish and tells both clients the block is gone.
  for (const { client } of [kappa, iota]) {
    client.emit('block_change', { location: { ...BLOCK }, type: 0 })
  }
  const kappaReport = await H.promptly(kappa.peer.read(), 'kappa dig', DEADLINE_MS)
  const iotaReport = await H.promptly(iota.peer.read(), 'iota dig', DEADLINE_MS)

  // A halt cannot take back a finish already written: kappa's dig is the server's break.
  assert.equal(halted.stopped, false, JSON.stringify(halted))
  assert.equal(kappaReport.outcome, 'dug', JSON.stringify(kappaReport))
  // iota never finished: the block went to another player's dig, a race and no break of iota's.
  assert.equal(finished(iota.client), false)
  assert.equal(iotaReport.outcome, 'blockGone', JSON.stringify(iotaReport))
  assert.equal(iotaReport.attempted, true, JSON.stringify(iotaReport))
  kappa.peer.stop()
  iota.peer.stop()
  halter.stop()
})
