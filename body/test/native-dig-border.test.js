'use strict'

/**
 * r36 zeta's dig past the world border, replayed on its own ground, offline.
 *
 * Zeta stood at (-63.5, 64, -35.5) and was offered short grass at (-65, 64, -36), one column
 * past the 128-block border centred on (0, 0). The server refuses every interaction with a block
 * past its border and puts the block back, so Mineflayer's predicted break was never answered
 * and the dig waited out its 20 s deadline as `libraryFailure` (`body:00000551`), with no break
 * on the server (`body:00000552`). A real Paper 1.21.1 probe showed the same: the grass inside the
 * border broke at once and the grass past it was restored.
 *
 * The cases drive the real body process over a real control socket around a real Mineflayer bot
 * fed real 1.21.1 packets, on real Prismarine chunks of zeta's corner of the scene.
 */

const assert = require('node:assert/strict')
const { afterEach, test } = require('node:test')

const { Vec3 } = require('vec3')

const H = require('./harness')

afterEach(H.disconnected)

let openBody = null
afterEach(async () => {
  if (openBody !== null) await openBody.stop()
  openBody = null
})

const FEET = Object.freeze({ x: -63.5, y: 64, z: -35.5 })
const FED = Object.freeze({ health: 20, food: 20, foodSaturation: 5 })
const BORDER = Object.freeze({ x: 0, z: 0, oldDiameter: 128, newDiameter: 128, speed: 0 })
// r36 zeta's `bbd7`, past the border, and the same kind one column inside it.
const PAST = Object.freeze({ x: -65, y: 64, z: -36 })
const INSIDE = Object.freeze({ x: -63, y: 64, z: -37 })
// A dirt block inside the border whose west face looks onto open air past it.
const EDGE = Object.freeze({ x: -64, y: 64, z: -37 })

/** Grass ground at y 63 over the two chunk columns zeta's corner spans, west and east of x -64. */
function zetaGround (bot) {
  const grass = H.registry.blocksByName.grass_block.defaultState
  for (const chunkX of [-5, -4]) {
    const column = new H.Chunk(H.OVERWORLD)
    for (let x = 0; x < 16; x += 1) {
      for (let z = 0; z < 16; z += 1) column.setBlockStateId(new Vec3(x, 63, z), grass)
    }
    bot.world.setColumn(chunkX, -3, column)
  }
}

/** A body at zeta's feet on zeta's ground, with the border stated, and a controller. */
async function zeta ({ border = BORDER } = {}) {
  const given = await H.embodied()
  const { body, bot, client, port } = given
  openBody = body
  H.spawned(client, FED)
  H.clockReported(client, { timeOfDay: 6000, day: 0 })
  if (border !== null) client.emit('initialize_world_border', border)
  client.emit('position', H.positionPacket({ ...FEET, yaw: 0, pitch: 0, teleportId: 1 }))
  zetaGround(bot)
  const shortGrass = H.registry.blocksByName.short_grass.defaultState
  for (const at of [PAST, INSIDE]) bot.world.setBlockStateId(new Vec3(at.x, at.y, at.z), shortGrass)
  bot.world.setBlockStateId(new Vec3(EDGE.x, EDGE.y, EDGE.z), H.registry.blocksByName.dirt.defaultState)
  await H.settled(200)
  const peer = await H.handshaken(port)
  return { ...given, peer }
}

function indexOf (sensed, at) {
  return sensed.blocks.findIndex(({ position }) =>
    position[0] === at.x && position[1] === at.y && position[2] === at.z)
}

test('a sense offers no block past the border, and the same kind inside it', async () => {
  const { peer } = await zeta()
  const sensed = await peer.answer(H.request('sense', 's1', { radius: 5, limit: 48 }))
  assert.equal(indexOf(sensed, PAST), -1, 'r36 zeta was offered this grass as bbd7')
  assert.ok(indexOf(sensed, INSIDE) >= 0, JSON.stringify(sensed.blocks))
  // Column -65 is past the border at every height, so no block of it is offered at all.
  assert.deepEqual(sensed.blocks.filter(({ position }) => position[0] < -64), [])
  // A sought kind past the border is not looked for there either.
  const sought = await peer.answer(H.request('sense', 's2', { radius: 16, limit: 48, seek: ['short_grass'] }))
  assert.deepEqual(sought.blocks.filter(({ position }) => position[0] < -64), [])
  peer.stop()
})

test('before the server states a border, the grass at x -65 is offered as any block is', async () => {
  const { peer } = await zeta({ border: null })
  const sensed = await peer.answer(H.request('sense', 's1', { radius: 5, limit: 48 }))
  assert.ok(indexOf(sensed, PAST) >= 0, JSON.stringify(sensed.blocks))
  peer.stop()
})

test('a dig of a block the border has moved past is refused before any dig is sent', async () => {
  // The border is stated wider when the sense is taken, then shrinks to the scene's 128.
  const { client, peer } = await zeta({ border: { ...BORDER, oldDiameter: 140, newDiameter: 140 } })
  const sensed = await peer.answer(H.request('sense', 's1', { radius: 5, limit: 48 }))
  const block = indexOf(sensed, PAST)
  assert.ok(block >= 0, JSON.stringify(sensed.blocks))
  client.emit('world_border_size', { diameter: 128 })
  client.writes.length = 0
  const dug = await H.promptly(peer.answer(H.request('dig', 'd1', {
    senseId: 's1', block, timeoutMs: 20_000
  })), 'the dig', 2_000)
  assert.equal(dug.outcome, 'notDiggable', JSON.stringify(dug))
  assert.equal(dug.attempted, false)
  assert.deepEqual(client.writes.filter(({ name }) => name === 'block_dig'), [])
  peer.stop()
})

test('a dig of the same kind inside the border is sent and answered', async () => {
  const { bot, client, peer } = await zeta()
  const sensed = await peer.answer(H.request('sense', 's1', { radius: 5, limit: 48 }))
  const block = indexOf(sensed, INSIDE)
  assert.ok(block >= 0, JSON.stringify(sensed.blocks))
  // The server answers this client's finish the way vanilla does for a block it lets go.
  const write = client.write
  client.write = (name, parameters) => {
    const result = write(name, parameters)
    if (name === 'block_dig' && parameters.status === 2) {
      setImmediate(() => client.emit('block_change', { location: INSIDE, type: 0 }))
    }
    return result
  }
  const dug = await H.promptly(peer.answer(H.request('dig', 'd1', {
    senseId: 's1', block, timeoutMs: 20_000
  })), 'the dig', 5_000)
  assert.equal(dug.outcome, 'dug', JSON.stringify(dug))
  assert.equal(bot.blockAt(new Vec3(INSIDE.x, INSIDE.y, INSIDE.z)).name, 'air')
  peer.stop()
})

test('a placement into a cell past the border is refused before any use is sent', async () => {
  const { client, peer } = await zeta()
  // A held dirt block against the west face of the dirt at x -64 would stand at x -65, in open
  // air past the border.
  H.stocked(client, [[36, 'dirt', 4]], { quickBarSlot: 0 })
  await H.settled()
  const sensed = await peer.answer(H.request('sense', 's1', { radius: 5, limit: 48 }))
  const support = indexOf(sensed, EDGE)
  assert.ok(support >= 0, JSON.stringify(sensed.blocks))
  client.writes.length = 0
  const placed = await H.promptly(peer.answer(H.request('place', 'p1', {
    senseId: 's1', block: support, slot: 36, face: 'west', timeoutMs: 2_000
  })), 'the placement', 3_000)
  assert.equal(placed.outcome, 'destinationBlocked', JSON.stringify(placed))
  assert.deepEqual(client.writes.filter(({ name }) => name === 'block_place'), [])
  peer.stop()
})
