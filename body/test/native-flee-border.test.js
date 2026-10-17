'use strict'

/**
 * tribe-theta's stalled flights in the v29 plains rehearsal, replayed offline.
 *
 * The terrain is the real ground around the crater a creeper blew under theta at tick 8372,
 * read from a disposable Paper 1.21.1 world generated from the run's seed and converged by
 * `setblock` until a sense from theta's feet reported exactly the 48 blocks of its perception
 * at tick 8404 (`fixtures/v29-theta-crater-terrain.json`). The run's world border is 128
 * blocks centred on the origin, so theta stood with its box touching the border's north face
 * at z -64. The body, bot, pathfinder and physics are real; the fake server refuses a reported
 * position whose box crosses the border, as vanilla does, and puts the player back.
 *
 * On real Paper the flight from the skeleton nine blocks east searched north, over the border,
 * and jumped in place against it for eleven seconds until the stall bound ended it - 54 blocks
 * travelled and a fifth of a block from where it began, against the run's 53.7 - while the
 * crater itself had a way out to the west the whole time.
 */

const assert = require('node:assert/strict')
const path = require('node:path')
const { afterEach, test } = require('node:test')

const { Vec3 } = require('vec3')

const H = require('./harness')
const { PLAYER_HALF_WIDTH, columnInside } = require('../src/border')

const TERRAIN = require(path.join(__dirname, 'fixtures', 'v29-theta-crater-terrain.json'))

afterEach(H.disconnected)

const FEET = Object.freeze({ x: 27.3, y: 66, z: -63.7 })
const FED = Object.freeze({ health: 20, food: 20, foodSaturation: 5 })
// The skeleton, e382, nine and a half blocks east and a block up, as at tick 8404.
const THREAT = Object.freeze({ id: 77, what: 'skeleton', x: 37, y: 67, z: -63.7 })
const BORDER = Object.freeze({ x: 0, z: 0, diameter: 128 })
const DEADLINE_MS = 20_000

let openBody = null
afterEach(async () => {
  if (openBody !== null) await openBody.stop()
  openBody = null
})

/** The converged blocks, set into real chunk columns the way a server would have sent them. */
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

/** Whether a player's box at `at` lies wholly inside the border. */
function boxInside (at) {
  const half = BORDER.diameter / 2
  const within = (value, centre) =>
    value - PLAYER_HALF_WIDTH >= centre - half - 1e-7 && value + PLAYER_HALF_WIDTH <= centre + half + 1e-7
  return within(at.x, BORDER.x) && within(at.z, BORDER.z)
}

/** Refuses a reported move past the border the way vanilla does, and counts the refusals. */
function vanillaBorder (client) {
  const refused = []
  let accepted = { ...FEET }
  let teleportId = 100
  const write = client.write
  client.write = (name, parameters) => {
    const result = write(name, parameters)
    if (!['position', 'position_look'].includes(name) || !Number.isFinite(parameters?.x)) return result
    const at = { x: parameters.x, y: parameters.y, z: parameters.z }
    if (boxInside(at)) {
      accepted = at
      return result
    }
    refused.push(at)
    const back = accepted
    setImmediate(() => client.emit('position', H.positionPacket({ ...back, yaw: 0, pitch: 0, teleportId: teleportId++ })))
    return result
  }
  return refused
}

/** Theta in its crater with the skeleton in sight, and the flight the JVM sent. */
async function flight ({ border }) {
  const given = await H.embodied()
  const { body, bot, client, port } = given
  openBody = body
  H.spawned(client, FED)
  if (border) {
    client.emit('initialize_world_border', {
      x: BORDER.x,
      z: BORDER.z,
      oldDiameter: BORDER.diameter,
      newDiameter: BORDER.diameter,
      speed: 0,
      portalTeleportBoundary: 29999984,
      warningBlocks: 5,
      warningTime: 15
    })
  }
  H.clockReported(client, { timeOfDay: 18_000, day: 0 })
  client.emit('position', H.positionPacket({ ...FEET, yaw: 0, pitch: 0, teleportId: 1 }))
  surveyedGround(bot)
  H.spotted(client, THREAT)
  await H.settled(300)
  const refused = vanillaBorder(client)
  const outside = []
  const step = () => { if (!boxInside(bot.entity.position)) outside.push(bot.entity.position.clone()) }
  bot.on('physicsTick', step)
  const peer = await H.handshaken(port)
  const sensed = await peer.answer(H.request('sense', 's1', { radius: 24, limit: 48 }))
  const entity = sensed.entities.findIndex((offered) => offered.what === THREAT.what)
  assert.ok(entity >= 0, JSON.stringify(sensed.entities))
  const report = await H.promptly(peer.answer(H.request('flee', 'f1', {
    senseId: 's1', entity, distance: 24, timeoutMs: DEADLINE_MS, sprint: true
  })), 'the flight', DEADLINE_MS + 3000)
  bot.off('physicsTick', step)
  peer.stop()
  return { report, refused, outside }
}

test('theta stood in a column inside the border, and the column north of it is past it', () => {
  assert.equal(columnInside(BORDER, 27, -64), true)
  assert.equal(columnInside(BORDER, 27, -65), false)
  // The box touches the north face exactly: -63.7 - 0.3 is the border's -64.
  assert.equal(boxInside(FEET), true)
  assert.equal(boxInside({ ...FEET, z: FEET.z - 0.01 }), false)
})

test('a flight searched as if the border were open ground stalls against it, as on real Paper', async () => {
  const { report, refused } = await flight({ border: false })
  assert.equal(report.outcome, 'stalled', JSON.stringify(report))
  assert.ok(Math.hypot(report.position[0] - FEET.x, report.position[2] - FEET.z) < 0.5, JSON.stringify(report))
  assert.ok(refused.length > 0)
})

test('a flight that knows the border leaves the crater west and arrives, never stepping past it', async () => {
  const { report, refused, outside } = await flight({ border: true })
  assert.equal(report.outcome, 'arrived', JSON.stringify(report))
  assert.ok(report.separation > 24, JSON.stringify(report))
  assert.deepEqual(outside, [])
  assert.deepEqual(refused, [])
})
