'use strict'

/**
 * tribe-beta's two over-budget flights in plains rehearsal 13, replayed offline.
 *
 * Beta's flight at tick 5603 ended on the canopy of an oak, its feet at y 73 on leaves six blocks
 * above the grass. At tick 5750 a zombie 6.6 blocks east sighted it, and the flight from that
 * zombie answered `searchOverBudget` with the body never moving; so did the next one at 5789.
 * The terrain is the real ground around the tree, read from a disposable Paper 1.21.1 world
 * generated from the run's seed (`fixtures/v32-beta-canopy-terrain.json`). On that Paper world the
 * same flight searched 9108 nodes to the 516 ms budget: its end was held to four blocks below where
 * it began, the canopy, and every way off the tree and away from the zombie lies lower than that.
 * The body, bot, pathfinder and physics here are real.
 */

const assert = require('node:assert/strict')
const path = require('node:path')
const { afterEach, test } = require('node:test')

const { Vec3 } = require('vec3')

const H = require('./harness')
const { MAX_FLIGHT_DESCENT_BLOCKS, flightFloorOf } = require('../src/movement')

const TERRAIN = require(path.join(__dirname, 'fixtures', 'v32-beta-canopy-terrain.json'))

afterEach(H.disconnected)

const FEET = Object.freeze({ x: -0.5, y: 73, z: -32.3 })
const FED = Object.freeze({ health: 20, food: 20, foodSaturation: 5 })
// The zombie, e2kj, six and a half blocks east and two below, as at tick 5750.
const THREAT = Object.freeze({ id: 88, what: 'zombie', x: 6.1, y: 71.2, z: -32.3 })
const DEADLINE_MS = 20_000

let openBody = null
afterEach(async () => {
  if (openBody !== null) await openBody.stop()
  openBody = null
})

/** The surveyed blocks, set into real chunk columns the way a server would have sent them. */
function surveyedGround (bot) {
  const columns = new Map()
  for (const [key, cells] of Object.entries(TERRAIN.columns)) {
    const [x, z] = key.split(',').map(Number)
    const chunk = `${Math.floor(x / 16)},${Math.floor(z / 16)}`
    if (!columns.has(chunk)) columns.set(chunk, new H.Chunk(H.OVERWORLD))
    for (const [y, index] of cells) {
      const state = H.registry.blocksByName[TERRAIN.palette[index]].defaultState
      columns.get(chunk).setBlockStateId(new Vec3(((x % 16) + 16) % 16, y, ((z % 16) + 16) % 16), state)
    }
  }
  for (const [chunk, column] of columns) {
    const [cx, cz] = chunk.split(',').map(Number)
    bot.world.setColumn(cx, cz, column)
  }
}

/** Beta on the canopy with the zombie in sight, and the flight the JVM sent. */
async function flight () {
  const given = await H.embodied()
  const { body, bot, client, port } = given
  openBody = body
  H.spawned(client, FED)
  H.clockReported(client, { timeOfDay: 14_000, day: 0 })
  client.emit('position', H.positionPacket({ ...FEET, yaw: 0, pitch: 0, teleportId: 1 }))
  surveyedGround(bot)
  H.spotted(client, THREAT)
  await H.settled(300)
  const searches = []
  const searched = (update) => searches.push({ status: update.status, visited: update.visitedNodes })
  bot.on('path_update', searched)
  const peer = await H.handshaken(port)
  const sensed = await peer.answer(H.request('sense', 's1', { radius: 24, limit: 48 }))
  const entity = sensed.entities.findIndex((offered) => offered.what === THREAT.what)
  assert.ok(entity >= 0, JSON.stringify(sensed.entities))
  const report = await H.promptly(peer.answer(H.request('flee', 'f1', {
    senseId: 's1', entity, distance: 24, timeoutMs: DEADLINE_MS, sprint: true
  })), 'the flight', DEADLINE_MS + 3000)
  bot.off('path_update', searched)
  peer.stop()
  return { report, searches, bot }
}

test('the floor of a flight from the canopy is measured from the ground around the tree', async () => {
  const given = await H.embodied()
  openBody = given.body
  H.spawned(given.client, FED)
  given.client.emit('position', H.positionPacket({ ...FEET, yaw: 0, pitch: 0, teleportId: 1 }))
  surveyedGround(given.bot)
  await H.settled(100)
  // The grass under and around the oak stands a body at 66 to 68; the canopy is at 73.
  const floor = flightFloorOf(given.bot, new Vec3(FEET.x, FEET.y, FEET.z))
  assert.ok(floor <= 67 - MAX_FLIGHT_DESCENT_BLOCKS, `floor ${floor}`)
  assert.ok(floor >= 63 - MAX_FLIGHT_DESCENT_BLOCKS, `floor ${floor}`)
})

test('a flight off the canopy finds its way down and away in one small search, and arrives', async () => {
  const { report, searches } = await flight()
  assert.equal(report.outcome, 'arrived', JSON.stringify({ report, searches }))
  // The goal is judged on the end node's block cell, so the body itself may rest a fraction short.
  assert.ok(report.separation > 23, JSON.stringify(report))
  // Measured: 67 nodes. Held by the canopy's floor the same search visited 9108 to its budget.
  assert.ok(searches.length > 0 && searches.every((search) => search.visited < 1000), JSON.stringify(searches))
})
