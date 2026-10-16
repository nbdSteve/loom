'use strict'

/**
 * tribe-alpha's three refused walks in the fourth ten-agent live run, replayed offline.
 *
 * The terrain is the real s2-ring ground around tribe-alpha's slot, read from a disposable
 * Paper 1.21.1 world generated from the run's seed with no provider and no credential
 * (`fixtures/s2-ring-alpha-terrain.json`). The body process, the bot, the pathfinder and the
 * physics are all real. Each target the mind chose was a cell of open air with nothing to
 * stand on - two or six blocks above the ground beneath it - so `noRoute` was the ground's
 * true answer, while the standable cell in the same column is reached.
 */

const assert = require('node:assert/strict')
const path = require('node:path')
const { afterEach, test } = require('node:test')

const { Vec3 } = require('vec3')

const H = require('./harness')
const TERRAIN = require(path.join(__dirname, 'fixtures', 's2-ring-alpha-terrain.json'))

afterEach(H.disconnected)

const SLOT = Object.freeze({ x: 12.5, y: 105, z: 0.5 })
const FED = Object.freeze({ health: 20, food: 20, foodSaturation: 5 })
const AMPLE_MS = 8000
// The three moveTo targets of the run's evidence, in order, and what stood in each column.
const REFUSED = Object.freeze([[12, 107, 3], [12, 107, 0], [12, 105, 1]])

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

/** The highest surveyed collision block in one column. */
function groundIn (x, z) {
  const solid = (TERRAIN.columns[`${x},${z}`] ?? []).filter(([, index]) => TERRAIN.palette[index] !== 'dead_bush')
  return Math.max(...solid.map(([y]) => y))
}

async function standingAtSlot () {
  const given = await H.embodied()
  const { body, bot, client, port } = given
  openBody = body
  H.spawned(client, FED)
  H.clockReported(client, { timeOfDay: 6000, day: 0 })
  client.emit('position', H.positionPacket({ ...SLOT, yaw: 0, pitch: 0, teleportId: 1 }))
  surveyedGround(bot)
  await H.settled(200)
  const peer = await H.handshaken(port)
  await peer.answer(H.request('sense', 's1', { radius: 8, limit: 8 }))
  return { ...given, peer }
}

test('each refused target was open air with no ground under it', () => {
  for (const [x, y, z] of REFUSED) {
    assert.ok(y > groundIn(x, z) + 1, `${[x, y, z]} stands ${y - groundIn(x, z) - 1} above its ground`)
  }
  // The slot itself stands on the surveyed ground.
  assert.equal(groundIn(12, 0) + 1, SLOT.y)
})

for (const target of REFUSED) {
  test(`the real pathfinder refuses the open-air target ${target} as noRoute`, async () => {
    const { bot, peer } = await standingAtSlot()
    const report = await H.promptly(peer.answer(H.request('moveTo', 'm1', {
      destination: target, tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
    })), 'the walk', AMPLE_MS + 3000)
    assert.equal(report.outcome, 'noRoute', JSON.stringify(report))
    assert.equal(report.travelled, 0)
    assert.ok(bot.entity.position.distanceTo(new Vec3(SLOT.x, SLOT.y, SLOT.z)) < 1e-6)
    peer.stop()
  })
}

test('the standable cell in the refused column is reached from the slot', async () => {
  const { peer } = await standingAtSlot()
  const standable = [12, groundIn(12, 1) + 1, 1]
  const report = await H.promptly(peer.answer(H.request('moveTo', 'm1', {
    destination: standable, tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  })), 'the walk', AMPLE_MS + 3000)
  assert.equal(report.outcome, 'arrived', JSON.stringify(report))
  assert.equal(report.position[1], standable[1])
  peer.stop()
})

// The fifth live run's walks named dead bushes: a cover plant has no collision box, so the
// cell above it is open air and the place to stand is the plant's own cell.
const BUSH = Object.freeze([14, 102, 2])

test('the cell above a surveyed dead bush is open air, and the bush cell itself is standable', async () => {
  assert.equal(TERRAIN.palette[TERRAIN.columns['14,2'].find(([y]) => y === BUSH[1])[1]], 'dead_bush')
  assert.equal(groundIn(14, 2) + 1, BUSH[1])
  const above = await (async () => {
    const { peer } = await standingAtSlot()
    const report = await H.promptly(peer.answer(H.request('moveTo', 'm1', {
      destination: [BUSH[0], BUSH[1] + 1, BUSH[2]], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
    })), 'the walk', AMPLE_MS + 3000)
    peer.stop()
    return report
  })()
  assert.equal(above.outcome, 'noRoute', JSON.stringify(above))
  await openBody.stop()
  openBody = null
  H.disconnected()
  const { peer } = await standingAtSlot()
  const into = await H.promptly(peer.answer(H.request('moveTo', 'm2', {
    destination: [...BUSH], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  })), 'the walk', AMPLE_MS + 3000)
  assert.equal(into.outcome, 'arrived', JSON.stringify(into))
  assert.equal(into.position[1], BUSH[1])
  peer.stop()
})
