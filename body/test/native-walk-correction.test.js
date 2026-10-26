'use strict'

/**
 * A walk's departure check and its one correction, driven offline through the real body.
 *
 * An arrival states whether another route can start where it came to rest; a route the library
 * abandons as stuck while the body has not left its start makes one sidestep and resumes; an
 * arrival from which no move is one the executor can make is re-centred once when the centre
 * would do. r34 delta stood still under an oak's leaves for four requests in a row, which is what
 * each of these is measured against. The body, bot, pathfinder and physics are real.
 */

const assert = require('node:assert/strict')
const path = require('node:path')
const { afterEach, test } = require('node:test')

const { Movements } = require('mineflayer-pathfinder')
const { Vec3 } = require('vec3')

const H = require('./harness')
const { ClearanceMovements, departureOf } = require('../src/movement')

const TERRAIN = require(path.join(__dirname, 'fixtures', 'v32-beta-canopy-terrain.json'))

afterEach(H.disconnected)

const FED = Object.freeze({ health: 20, food: 20, foodSaturation: 5 })
const DEADLINE_MS = 10_000
const ITEM_ID = 77

let openBody = null
afterEach(async () => {
  if (openBody !== null) await openBody.stop()
  openBody = null
})

/** Two layers of stone under the whole chunk, and the cells in `open` left as air. */
function thickGround (bot, { open = [], floor = [] } = {}) {
  const walls = []
  for (let x = 0; x < 16; x += 1) for (let z = 0; z < 16; z += 1) walls.push({ x, y: 62, z })
  H.ground(bot, { walls: [...walls, ...floor] })
  for (const [x, y, z] of open) bot.world.setBlockStateId(new Vec3(x, y, z), H.registry.blocksByName.air.defaultState)
}

async function standing (at, movement) {
  const given = await H.embodied({ movement })
  openBody = given.body
  H.spawned(given.client, FED)
  H.clockReported(given.client, { timeOfDay: 6000, day: 0 })
  given.client.emit('position', H.positionPacket({ ...at, yaw: 0, pitch: 0, teleportId: 1 }))
  return given
}

async function walked (port, destination) {
  const peer = await H.handshaken(port)
  const report = await H.promptly(peer.answer(H.request('moveTo', 'm1', {
    destination, tolerance: 0, timeoutMs: DEADLINE_MS, sprint: false
  })), 'the walk', DEADLINE_MS + 3000)
  peer.stop()
  return report
}

test('a walk that arrives on open ground states that another route can start there', async () => {
  const { bot, port } = await standing({ x: 2.5, y: 64, z: 2.5 })
  thickGround(bot)
  await H.settled(200)
  const report = await walked(port, [6, 64, 2])
  assert.equal(report.outcome, 'arrived', JSON.stringify(report))
  assert.equal(report.departure, 'clear')
  assert.equal(report.recovery, undefined)
})

test('a walk that ends at the bottom of a two-deep shaft states it blocked and corrects nothing', async () => {
  const { bot, port } = await standing({ x: 6.5, y: 64, z: 8.5 })
  thickGround(bot, { open: [[8, 63, 8], [8, 62, 8]], floor: [{ x: 8, y: 61, z: 8 }] })
  await H.settled(200)
  const report = await walked(port, [8, 62, 8])
  assert.equal(report.outcome, 'arrived', JSON.stringify(report))
  assert.equal(report.departure, 'blocked')
  // From the centre it is no better, so no step is made: never a teleport and never a loop.
  assert.equal(report.recovery, undefined)
  assert.equal(departureOf(bot, new ClearanceMovements(bot)), 'blocked')
})

test('an arrival blocked where it stands and clear from its centre is re-centred once', async () => {
  const asked = []
  // The check is the real one from the centre, and blocked where the body first comes to rest.
  const departure = (bot, movements, at) => {
    asked.push(at === undefined ? 'here' : 'centre')
    return asked.length === 1 ? 'blocked' : departureOf(bot, movements, at)
  }
  // A one-cell walk from near its cell's edge, slow enough to stop short of the next centre.
  const { bot, port } = await standing({ x: 5.2, y: 64, z: 2.5 }, { departure })
  thickGround(bot)
  await H.settled(200)
  const report = await walked(port, [6, 64, 2])
  assert.equal(report.outcome, 'arrived', JSON.stringify(report))
  assert.deepEqual(asked, ['here', 'centre', 'here'])
  assert.equal(report.recovery?.kind, 'recentre', JSON.stringify(report))
  assert.equal(report.recovery.cause, 'departureBlocked')
  assert.equal(report.departure, 'clear')
  assert.ok(Math.hypot(report.position[0] - 6.5, report.position[2] - 2.5) <= 0.1, JSON.stringify(report))
  assert.deepEqual(report.recovery.to, report.position)
})

/** Vanilla's pickup, judged on the positions the client reports. */
function vanillaPickup (client, bot, item) {
  let taken = false
  const write = client.write
  client.write = (name, parameters) => {
    const result = write(name, parameters)
    if (!taken && ['position', 'position_look'].includes(name) && Number.isFinite(parameters?.x)) {
      const near = (centre, reach, at) => Math.abs(at - centre) <= reach
      const reach = 0.3 + 1 + 0.125
      const inY = item.y + 0.125 >= parameters.y - 0.5 && item.y - 0.125 <= parameters.y + 1.8 + 0.5
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

/** The surveyed blocks less `removed`, set into real chunk columns the way a server would send them. */
function surveyedGround (bot, removed) {
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

test('a route stuck at its first move sidesteps once and resumes, where r34 delta stood still', async () => {
  // The library's own diagonal rule, which offers the jump past delta's leaf the executor cannot make.
  const clearance = ClearanceMovements.prototype.getMoveDiagonal
  ClearanceMovements.prototype.getMoveDiagonal = Movements.prototype.getMoveDiagonal
  try {
    const { bot, client, port } = await standing({ x: -8.434705761507354, y: 66, z: -27.5 })
    surveyedGround(bot, [[-5, 67, -27]])
    const item = { x: -3.99072265625, y: 67, z: -26.64599609375 }
    H.spotted(client, { id: ITEM_ID, what: 'item', ...item })
    client.emit('entity_metadata', {
      entityId: ITEM_ID,
      metadata: [{ key: 8, type: 'item_stack', value: H.Item.toNotch(H.stack('oak_log', 1)) }]
    })
    await H.settled(300)
    const taken = vanillaPickup(client, bot, item)
    const peer = await H.handshaken(port)
    const sensed = await peer.answer(H.request('sense', 's1', { radius: 16, limit: 48 }))
    const index = sensed.items.findIndex((offered) => offered.what === 'oak_log')
    const report = await H.promptly(peer.answer(H.request('collect', 'c1', {
      senseId: 's1', item: index, approach: true, timeoutMs: DEADLINE_MS * 2, sprint: false
    })), 'the collection', DEADLINE_MS * 2 + 3000)
    peer.stop()
    assert.equal(report.outcome, 'collected', JSON.stringify(report))
    assert.equal(taken(), true)
    assert.equal(report.recovery?.kind, 'sidestep', JSON.stringify(report))
    assert.equal(report.recovery.cause, 'firstMoveStuck')
    assert.deepEqual(report.recovery.from, report.from)
    assert.ok(report.travelled > 3, JSON.stringify(report))
  } finally {
    ClearanceMovements.prototype.getMoveDiagonal = clearance
  }
})
