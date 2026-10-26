'use strict'

/**
 * r34 delta's four blockers, and the one item three agents were refused a route to, replayed
 * offline on the run's own ground.
 *
 * Delta's walk at tick 2878 ended at (-8.43, 66, -27.5) on seed 20011, at the foot of a one-block
 * rise under an oak whose leaves overhang it at y 68. From there three approaching collects of its
 * own dug logs (ticks 3622, 6026 and 7835) and a flight from a skeleton (8951) stalled without the
 * body moving: every route east began with a diagonal jump up past the leaf at (-8, 68, -28), which
 * the library's search admits because the other side of the corner is clear and its own executor
 * then cannot carry out, so it pressed no control until its futility check reset the route to the
 * identical one (`ClearanceMovements` in `src/movement.js`).
 *
 * The terrain is the real ground there, read from a disposable Paper 1.21.1 world generated from
 * the run's seed (`fixtures/v32-beta-canopy-terrain.json`), with each log delta had dug by then
 * taken out. The body, bot, pathfinder and physics are real; the fake server picks an item up the
 * way vanilla does and refuses a reported move into a block the way vanilla does.
 */

const assert = require('node:assert/strict')
const path = require('node:path')
const { afterEach, test } = require('node:test')

const { Movements } = require('mineflayer-pathfinder')
const Move = require('mineflayer-pathfinder/lib/move')
const { Vec3 } = require('vec3')

const H = require('./harness')
const { COLLISION_EPSILON } = require('../src/collision')
const { pickupCells } = require('../src/collecting')
const { ClearanceMovements } = require('../src/movement')

const TERRAIN = require(path.join(__dirname, 'fixtures', 'v32-beta-canopy-terrain.json'))

afterEach(H.disconnected)

/** Where delta's walk at tick 2878 came to rest. */
const STUCK = Object.freeze({ x: -8.434705761507354, y: 66, z: -27.5 })
const FED = Object.freeze({ health: 20, food: 20, foodSaturation: 5 })
/** The oak leaf over the corner every stalled route jumped past. */
const LEAF = Object.freeze([-8, 68, -28])
/** Delta's three dug logs, in the order it dug them (ticks 3077, 5272 and 5473). */
const DUG = Object.freeze([[-5, 67, -27], [-6, 67, -30], [-5, 68, -27]])
const ITEM_ID = 77
const ITEM_HALF = 0.125
const DEADLINE_MS = 20_000

let openBody = null
afterEach(async () => {
  if (openBody !== null) await openBody.stop()
  openBody = null
})

/** The surveyed blocks less `removed`, set into real chunk columns the way a server would send them. */
function surveyedGround (bot, removed = []) {
  const gone = new Set(removed.map((cell) => cell.join(',')))
  const columns = new Map()
  for (const [key, cells] of Object.entries(TERRAIN.columns)) {
    const [x, z] = key.split(',').map(Number)
    const chunk = `${Math.floor(x / 16)},${Math.floor(z / 16)}`
    if (!columns.has(chunk)) columns.set(chunk, new H.Chunk(H.OVERWORLD))
    for (const [y, index] of cells) {
      if (gone.has(`${x},${y},${z}`)) continue
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
  let accepted = { ...STUCK }
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

/** Vanilla's pickup, judged on the positions the client reports. */
function vanillaPickup (client, bot, item) {
  let taken = false
  const write = client.write
  client.write = (name, parameters) => {
    const result = write(name, parameters)
    if (!taken && ['position', 'position_look'].includes(name) && Number.isFinite(parameters?.x)) {
      const near = (centre, reach, at) => Math.abs(at - centre) <= reach
      const reach = 0.3 + 1 + ITEM_HALF
      const inY = item.y + ITEM_HALF >= parameters.y - 0.5 && item.y - ITEM_HALF <= parameters.y + 1.8 + 0.5
      if (near(parameters.x, reach, item.x) && near(parameters.z, reach, item.z) && inY) {
        taken = true
        setImmediate(() => {
          client.emit('collect', { collectedEntityId: ITEM_ID, collectorEntityId: bot.entity.id, pickupItemCount: 1 })
          client.emit('entity_destroy', { entityIds: [ITEM_ID] })
        })
      }
    }
    return result
  }
  return () => taken
}

/** Delta at the stuck cell with `dug` taken out of the ground. */
async function delta (dug) {
  const given = await H.embodied()
  openBody = given.body
  H.spawned(given.client, FED)
  H.clockReported(given.client, { timeOfDay: 9000, day: 0 })
  given.client.emit('position', H.positionPacket({ ...STUCK, yaw: 0, pitch: 0, teleportId: 1 }))
  surveyedGround(given.bot, dug)
  return given
}

/** The diagonal moves the search offers from where delta stood, under `movements`. */
function diagonalsFrom (bot, movements) {
  const start = new Move(Math.floor(STUCK.x), STUCK.y, Math.floor(STUCK.z), 0, 0)
  const neighbours = []
  for (const [x, z] of [[1, 1], [1, -1], [-1, 1], [-1, -1]]) movements.getMoveDiagonal(start, { x, z }, neighbours)
  return neighbours.map((move) => [move.x, move.y, move.z])
}

test('the library offers the diagonal jump past the leaf that its own executor cannot make', async () => {
  const { bot } = await delta(DUG.slice(0, 1))
  await H.settled(100)
  const jump = [-8, 67, -27]
  assert.deepEqual(TERRAIN.palette.includes('oak_leaves'), true)
  assert.equal(bot.blockAt(new Vec3(...LEAF)).name, 'oak_leaves')
  assert.ok(diagonalsFrom(bot, new Movements(bot)).some((move) => move.join() === jump.join()))
  // Both side cells of the corner must be clear at the heights the jump sweeps.
  assert.ok(!diagonalsFrom(bot, new ClearanceMovements(bot)).some((move) => move.join() === jump.join()))
})

test('with the leaf gone the same diagonal jump is offered again', async () => {
  const { bot } = await delta([...DUG.slice(0, 1), LEAF])
  await H.settled(100)
  assert.ok(diagonalsFrom(bot, new ClearanceMovements(bot)).some((move) => move.join() === '-8,67,-27'))
})

/** One approaching collect of an oak log lying at `item`, with `dug` out of the ground. */
async function collected (item, dug) {
  const { bot, client, port } = await delta(dug)
  H.spotted(client, { id: ITEM_ID, what: 'item', ...item })
  client.emit('entity_metadata', {
    entityId: ITEM_ID,
    metadata: [{ key: 8, type: 'item_stack', value: H.Item.toNotch(H.stack('oak_log', 1)) }]
  })
  await H.settled(300)
  const refused = vanillaMovement(bot, client)
  const taken = vanillaPickup(client, bot, item)
  const peer = await H.handshaken(port)
  const sensed = await peer.answer(H.request('sense', 's1', { radius: 16, limit: 48 }))
  const index = sensed.items.findIndex((offered) => offered.what === 'oak_log')
  assert.ok(index >= 0, JSON.stringify(sensed.items))
  const report = await H.promptly(peer.answer(H.request('collect', 'c1', {
    senseId: 's1', item: index, approach: true, timeoutMs: 10_000, sprint: false
  })), 'the collection', 13_000)
  peer.stop()
  return { report, refused, taken: taken() }
}

test('the collect at tick 3622 walks off the stuck cell and picks its log up', async () => {
  const { report, refused, taken } = await collected({ x: -3.99072265625, y: 67, z: -26.64599609375 }, DUG.slice(0, 1))
  assert.equal(report.outcome, 'collected', JSON.stringify(report))
  assert.ok(report.travelled > 3, JSON.stringify(report))
  assert.equal(taken, true)
  assert.deepEqual(refused, [])
})

test('the collects at ticks 6026 and 7835 walk off it and pick the second log up', async () => {
  const { report, refused, taken } = await collected({ x: -4.704345703125, y: 67, z: -26.59521484375 }, DUG)
  assert.equal(report.outcome, 'collected', JSON.stringify(report))
  assert.equal(taken, true)
  assert.deepEqual(refused, [])
})

test('the flight at tick 8951 runs from the skeleton without stalling', async () => {
  const { bot, client, port } = await delta(DUG)
  // e3a7, 15.6 blocks west and one below, in view, as the sighting at tick 8698 reported it.
  H.spotted(client, { id: 88, what: 'skeleton', x: -24.0, y: 65, z: -27.5 })
  await H.settled(300)
  const refused = vanillaMovement(bot, client)
  const peer = await H.handshaken(port)
  const sensed = await peer.answer(H.request('sense', 's1', { radius: 24, limit: 48 }))
  const entity = sensed.entities.findIndex((offered) => offered.what === 'skeleton')
  assert.ok(entity >= 0, JSON.stringify(sensed.entities))
  // Walking, as the JVM sent it: the skeleton was neither near nor closing. A sprinting flight's
  // sprint-jump simulation clears the leaf, which is why the stall met only a walking one.
  const report = await H.promptly(peer.answer(H.request('flee', 'f1', {
    senseId: 's1', entity, distance: 24, timeoutMs: DEADLINE_MS, sprint: false
  })), 'the flight', DEADLINE_MS + 3000)
  peer.stop()
  assert.equal(report.outcome, 'arrived', JSON.stringify(report))
  assert.ok(report.separationGained > 8, JSON.stringify(report))
  assert.deepEqual(refused, [])
})

/**
 * The oak log delta's dig at tick 5272 dropped, which delta (5291), kappa (8218) and theta (10916)
 * were each refused a route to: it lies on the grass at (-5, 66, -30) under the oak's leaves at
 * y 68, a one-high gap no body stands in, and every cell whose pickup box reaches it has leaves or
 * the oak's own trunk over the feet. The ground's answer was true, and only a dig of the leaves would change it.
 */
test('the log three agents were refused a route to lies where no body can stand to pick it up', async () => {
  const item = { x: -4.86328125, y: 67, z: -29.15185546875 }
  for (const dug of [DUG.slice(0, 2), DUG]) {
    const { bot, client, port } = await delta(dug)
    await H.settled(100)
    assert.deepEqual(pickupCells(bot, [item.x, item.y, item.z], [STUCK.x, STUCK.y, STUCK.z]), [])
    for (const [x, z] of [[-6, -31], [-5, -31], [-4, -31], [-6, -30], [-5, -30], [-4, -30], [-6, -29], [-5, -29], [-4, -29]]) {
      assert.equal(bot.blockAt(new Vec3(x, 68, z)).boundingBox, 'block', `${x},68,${z}`)
    }
    H.spotted(client, { id: ITEM_ID, what: 'item', ...item })
    client.emit('entity_metadata', {
      entityId: ITEM_ID,
      metadata: [{ key: 8, type: 'item_stack', value: H.Item.toNotch(H.stack('oak_log', 1)) }]
    })
    await H.settled(200)
    const peer = await H.handshaken(port)
    const sensed = await peer.answer(H.request('sense', 's1', { radius: 16, limit: 48 }))
    const index = sensed.items.findIndex((offered) => offered.what === 'oak_log')
    assert.ok(index >= 0, JSON.stringify(sensed.items))
    const report = await peer.answer(H.request('collect', 'c1', {
      senseId: 's1', item: index, approach: true, timeoutMs: 10_000, sprint: false
    }))
    assert.equal(report.outcome, 'noRoute', JSON.stringify(report))
    assert.equal(report.travelled, 0)
    peer.stop()
    await openBody.stop()
    openBody = null
    H.disconnected()
  }
})
