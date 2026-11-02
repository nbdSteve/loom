'use strict'

/**
 * tribe-theta's two stalled flights in the scripted ten-agent rehearsal, replayed offline.
 *
 * The terrain is the real s2-ring ground around tribe-theta's slot, read from a disposable
 * Paper 1.21.1 world generated from the run's seed with no provider and no credential
 * (`fixtures/s2-ring-theta-terrain.json`). The body, bot, pathfinder and physics are real; the
 * fake server judges each reported position the way vanilla does - a move that newly puts the
 * player's box into a block, by more than vanilla's 1e-7, is refused and the player is put
 * back where it was. On the real server the flight was refused every tick for 10.8 s once the
 * body came to rest flush against the yellow terracotta at (-2, 109, -11), because the client's
 * physics, comparing faces exactly, stepped 0.07 into it; see `src/collision.js`.
 */

const assert = require('node:assert/strict')
const path = require('node:path')
const { afterEach, test } = require('node:test')

const { Vec3 } = require('vec3')

const H = require('./harness')
const { COLLISION_EPSILON } = require('../src/collision')

const TERRAIN = require(path.join(__dirname, 'fixtures', 's2-ring-theta-terrain.json'))

afterEach(H.disconnected)

const SLOT = Object.freeze({ x: -2.5, y: 109, z: -10.5 })
const FED = Object.freeze({ health: 20, food: 20, foodSaturation: 5 })
// The creature the flight runs from: 20 blocks west on the ridge, as the rehearsal's skeleton was.
const THREAT = Object.freeze({ id: 77, what: 'cow', x: -22.5, y: 118, z: -10.5 })
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

/** The block a player's box at `at` overlaps by more than vanilla's tolerance, if any. */
function collidingBlock (bot, at) {
  const box = [at.x - 0.3, at.y, at.z - 0.3, at.x + 0.3, at.y + 1.8, at.z + 0.3]
  const into = (low, high, min, max) => high > min + COLLISION_EPSILON && low < max - COLLISION_EPSILON
  for (let x = Math.floor(box[0]); x <= Math.floor(box[3]); x++) {
    for (let y = Math.floor(box[1]); y <= Math.floor(box[4]); y++) {
      for (let z = Math.floor(box[2]); z <= Math.floor(box[5]); z++) {
        const block = bot.blockAt(new Vec3(x, y, z))
        for (const [x0, y0, z0, x1, y1, z1] of block?.shapes ?? []) {
          if (into(box[0], box[3], x + x0, x + x1) && into(box[1], box[4], y + y0, y + y1) &&
            into(box[2], box[5], z + z0, z + z1)) return [x, y, z, block.name]
        }
      }
    }
  }
  return null
}

/** Refuses a reported move into a block the way vanilla does, and counts the refusals. */
function vanillaMovement (bot, client) {
  const refused = []
  let accepted = { ...SLOT }
  let teleportId = 100
  const write = client.write
  client.write = (name, parameters) => {
    const result = write(name, parameters)
    if (!['position', 'position_look'].includes(name) || !Number.isFinite(parameters?.x)) return result
    const at = { x: parameters.x, y: parameters.y, z: parameters.z }
    const into = collidingBlock(bot, at)
    if (into === null || collidingBlock(bot, accepted) !== null) {
      accepted = at
      return result
    }
    refused.push({ at, into })
    const back = accepted
    setImmediate(() => client.emit('position', H.positionPacket({ ...back, yaw: 0, pitch: 0, teleportId: teleportId++ })))
    return result
  }
  return refused
}

test('the slot is flush against the block the stalled flights stepped into', () => {
  // Standing at x = -2.3 the box face is exactly the yellow terracotta's west face, and the
  // floating-point sum that places it there lands 2e-16 inside the block.
  assert.equal(-2.3 + 0.3, -1.9999999999999998)
  const cell = TERRAIN.columns['-2,-11'].find(([y]) => y === 109)
  assert.equal(TERRAIN.palette[cell[1]], 'yellow_terracotta')
})

test('a flight from the ridge leaves the slot without the server refusing a single step', async () => {
  const given = await H.embodied()
  const { body, bot, client, port } = given
  openBody = body
  H.spawned(client, FED)
  H.clockReported(client, { timeOfDay: 14_000, day: 0 })
  client.emit('position', H.positionPacket({ ...SLOT, yaw: 0, pitch: 0, teleportId: 1 }))
  surveyedGround(bot)
  H.spotted(client, THREAT)
  await H.settled(300)
  const refused = vanillaMovement(bot, client)
  const inside = []
  const step = () => {
    const into = collidingBlock(bot, bot.entity.position)
    if (into !== null) inside.push({ at: bot.entity.position.clone(), into })
  }
  bot.on('physicsTick', step)
  const peer = await H.handshaken(port)
  const sensed = await peer.answer(H.request('sense', 's1', { radius: 32, limit: 48 }))
  const entity = sensed.entities.findIndex((offered) => offered.what === THREAT.what)
  assert.ok(entity >= 0, JSON.stringify(sensed.entities))

  const report = await H.promptly(peer.answer(H.request('flee', 'f1', {
    senseId: 's1', entity, distance: 24, timeoutMs: DEADLINE_MS, sprint: true
  })), 'the flight', DEADLINE_MS + 3000)
  bot.off('physicsTick', step)

  assert.equal(report.outcome, 'arrived', JSON.stringify(report))
  assert.ok(report.separation > 24, JSON.stringify(report))
  // The client never put the body inside a block, so the server had nothing to refuse.
  assert.deepEqual(inside, [])
  assert.deepEqual(refused, [])
  peer.stop()
})
