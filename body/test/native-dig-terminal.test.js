'use strict'

/**
 * A dig driven to its real terminal outcome, offline.
 *
 * The case drives the real body process over a real control socket, around a real
 * Mineflayer bot fed real 1.21.1 packets by the fake protocol client, standing on a real
 * Prismarine chunk. Nothing on the bot is stubbed: the dig time is the library's own, from
 * the library's own block and ground state, and the dig is asked for with only the handle
 * one `sense` answer offered.
 *
 * The server half is the one fact vanilla adds: it accepts a finished dig only once the
 * player's destroy progress, at the player's *server-side* ground state, has reached it.
 * The server learns that ground state only from the movement packets a client writes, so the
 * fake server here tracks the last `onGround` the bot wrote and answers a finish the way
 * `ServerPlayerGameMode` does - the block goes, or it is restored.
 */

const assert = require('node:assert/strict')
const { afterEach, test } = require('node:test')

const { Vec3 } = require('vec3')

const H = require('./harness')

afterEach(H.disconnected)

const FEET = Object.freeze({ x: 8.5, y: 64, z: 8.5 })
const FED = Object.freeze({ health: 20, food: 20, foodSaturation: 5 })
// The block diagonally below the feet, the one a standing body is offered at
// `distance 0.5, verticalOffset -0.5` - the shape of most timed-out digs in the live shakedown.
const TARGET = Object.freeze({ x: 8, y: 63, z: 7 })
// Vanilla's bare-hand dirt time: hardness 0.5, harvestable by hand, so 0.5 * 30 = 15 ticks
// on the ground, and five times that while the player is not on the ground.
const GROUNDED_DIG_MS = 750
const AIRBORNE_DIG_MS = 3750
// Ample for either rate, so the outcome does not race the host's clock: which rate a dig ran
// at is read from the ground state the client timed it with and the server judged it with.
const DEADLINE_MS = 8000

let openBody = null
afterEach(async () => {
  if (openBody !== null) await openBody.stop()
  openBody = null
})

/** The ground state the server holds for the player: the last one any movement packet wrote. */
function serverGround (client) {
  let onGround = false
  const write = client.write
  client.write = (name, parameters) => {
    if (['position', 'position_look', 'look', 'flying'].includes(name) &&
      typeof parameters?.onGround === 'boolean') onGround = parameters.onGround
    return write(name, parameters)
  }
  return () => onGround
}

/**
 * Answers dig packets the way a vanilla server does: a finish is accepted only when the
 * progress at the server-side ground state has been reached, and otherwise the block is
 * restored to the client.
 */
function vanillaDigging (bot, client, grounded, block) {
  let startedAt
  let clientGrounded
  const verdicts = []
  const write = client.write
  client.write = (name, parameters) => {
    const result = write(name, parameters)
    if (name === 'block_dig' && parameters.status === 0) {
      startedAt = performance.now()
      // Mineflayer reads its dig time from this ground state as the dig starts.
      clientGrounded = bot.entity.onGround
    }
    if (name === 'block_dig' && parameters.status === 2) {
      const onGround = grounded()
      const needed = onGround ? GROUNDED_DIG_MS : AIRBORNE_DIG_MS
      const accepted = performance.now() - startedAt >= needed * 0.7
      verdicts.push({ clientGrounded, onGround, accepted })
      setImmediate(() => client.emit('block_change', {
        location: TARGET,
        type: accepted ? 0 : block.stateId
      }))
    }
    return result
  }
  return verdicts
}

/** A body process with a controller, its feet at `feet`, beside a dirt block at [TARGET]. */
async function scene (feet = FEET) {
  const given = await H.embodied()
  const { body, bot, client, port } = given
  openBody = body
  const grounded = serverGround(client)
  H.spawned(client, FED)
  H.clockReported(client, { timeOfDay: 6000, day: 2 })
  // The join teleport: Mineflayer answers it with `onGround: false`, as a real client does.
  client.emit('position', H.positionPacket({ ...feet, yaw: 0, pitch: 0, teleportId: 1 }))
  H.ground(bot)
  bot.world.setBlockStateId(new Vec3(TARGET.x, TARGET.y, TARGET.z), H.registry.blocksByName.dirt.defaultState)
  await H.settled(200)
  const target = bot.blockAt(new Vec3(TARGET.x, TARGET.y, TARGET.z))
  assert.equal(target.name, 'dirt')
  const verdicts = vanillaDigging(bot, client, grounded, target)
  const peer = await H.handshaken(port)
  const sensed = await peer.answer(H.request('sense', 's1', { radius: 4, limit: 48 }))
  const block = sensed.blocks.findIndex((entry) =>
    entry.position[0] === TARGET.x && entry.position[1] === TARGET.y && entry.position[2] === TARGET.z)
  assert.ok(block >= 0, JSON.stringify(sensed.blocks))
  return { ...given, block, peer, verdicts }
}

function dig (peer, block) {
  return H.promptly(peer.answer(H.request('dig', 'd1', {
    senseId: 's1', block, timeoutMs: DEADLINE_MS
  })), 'the dig', DEADLINE_MS + 3000)
}

test('a teleported body reads the airborne dig time until its ground state is established', async () => {
  const { bot, client } = await H.realBot()
  H.spawned(client, FED)
  client.emit('position', H.positionPacket({ ...FEET, yaw: 0, pitch: 0, teleportId: 1 }))
  H.ground(bot)
  await H.settled(200)
  const terracotta = bot.blockAt(new Vec3(8, 63, 8))
  bot.world.setBlockStateId(terracotta.position, H.registry.blocksByName.terracotta.defaultState)
  const block = bot.blockAt(terracotta.position)
  // The live failure: bare-hand terracotta is 6.25 s on the ground and 31.25 s off it, and
  // the player body's interaction deadline is 20 s.
  assert.equal(bot.entity.onGround, false)
  assert.equal(bot.digTime(block), 31_250)
  bot.entity.onGround = true
  assert.equal(bot.digTime(block), 6250)
})

test('a standing body digs a time-based block at its vanilla grounded rate', async () => {
  const { bot, block, peer, verdicts } = await scene()
  const before = bot.entity.position.clone()

  const report = await dig(peer, block)

  assert.equal(report.outcome, 'dug', JSON.stringify(report))
  // The client timed the dig, and the server judged it, at the grounded rate.
  assert.deepEqual(verdicts, [{ clientGrounded: true, onGround: true, accepted: true }])
  assert.equal(bot.blockAt(new Vec3(TARGET.x, TARGET.y, TARGET.z)).name, 'air')
  // Establishing the ground state moved nothing, and the simulation is shut again after.
  assert.ok(bot.entity.position.distanceTo(before) < 1e-6, `moved to ${bot.entity.position}`)
  assert.equal(bot.physicsEnabled, false)
  peer.stop()
})

test('a body the server left in mid-air is not simulated by a dig and keeps its airborne rate', async () => {
  const { bot, client, block, peer, verdicts } = await scene({ ...FEET, y: 65 })
  const before = bot.entity.position.clone()
  const written = client.writes.length

  const report = await dig(peer, block)

  assert.equal(report.outcome, 'dug', JSON.stringify(report))
  assert.deepEqual(verdicts, [{ clientGrounded: false, onGround: false, accepted: true }])
  assert.ok(bot.entity.position.distanceTo(before) < 1e-6, `moved to ${bot.entity.position}`)
  assert.equal(bot.physicsEnabled, false)
  const moved = client.writes.slice(written).filter(({ name }) =>
    ['position', 'position_look', 'flying', 'entity_action', 'player_input'].includes(name))
  assert.deepEqual(moved, [])
  peer.stop()
})
