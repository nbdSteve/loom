'use strict'

/**
 * An approaching collection driven to its real terminal outcome, offline.
 *
 * The real body process, bot, pathfinder and physics, on a real chunk, with one dropped stack
 * spawned the way a server spawns one. The fake server picks an item up the way vanilla does:
 * when the player's box, grown by one block sideways and half a block up and down, overlaps
 * the item's own box, it sends `collect` and then `entity_destroy`, judged on the positions the
 * client actually wrote to it.
 */

const assert = require('node:assert/strict')
const { afterEach, test } = require('node:test')

const H = require('./harness')

afterEach(H.disconnected)

const FEET = Object.freeze({ x: 0.5, y: 64, z: 0.5 })
const FED = Object.freeze({ health: 20, food: 20, foodSaturation: 5 })
const ITEM_ID = 77
const ITEM_HALF = 0.125
const DEADLINE_MS = 4000

let openBody = null
afterEach(async () => {
  if (openBody !== null) await openBody.stop()
  openBody = null
})

/** Vanilla's pickup, from the positions the client reports. */
function vanillaPickup (client, bot, item) {
  let taken = false
  const write = client.write
  client.write = (name, parameters) => {
    const result = write(name, parameters)
    if (!taken && ['position', 'position_look'].includes(name) && Number.isFinite(parameters?.x)) {
      const near = (centre, reach, at) => Math.abs(at - centre) <= reach
      const reachX = 0.3 + 1 + ITEM_HALF
      const inY = item.y + ITEM_HALF >= parameters.y - 0.5 && item.y - ITEM_HALF <= parameters.y + 1.8 + 0.5
      if (near(parameters.x, reachX, item.x) && near(parameters.z, reachX, item.z) && inY) {
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

test('an approaching collection ends within vanilla pickup reach of the item, not merely near it', async () => {
  const given = await H.embodied()
  const { body, bot, client, port } = given
  openBody = body
  H.spawned(client, FED)
  H.clockReported(client, { timeOfDay: 6000, day: 2 })
  client.emit('position', H.positionPacket({ ...FEET, yaw: 0, pitch: 0, teleportId: 1 }))
  H.ground(bot)
  // Far enough into its block that a stop one block short of that block is out of reach.
  const item = { x: 3.95, y: 64, z: 0.5 }
  H.spotted(client, { id: ITEM_ID, what: 'item', ...item })
  client.emit('entity_metadata', {
    entityId: ITEM_ID,
    metadata: [{ key: 8, type: 'item_stack', value: H.Item.toNotch(H.stack('stick', 1)) }]
  })
  await H.settled(200)
  const taken = vanillaPickup(client, bot, item)
  const peer = await H.handshaken(port)
  const sensed = await peer.answer(H.request('sense', 's1', { radius: 8, limit: 8 }))
  const index = sensed.items.findIndex((offered) => offered.what === 'stick')
  assert.ok(index >= 0, JSON.stringify(sensed.items))

  const report = await H.promptly(peer.answer(H.request('collect', 'c1', {
    senseId: 's1', item: index, approach: true, timeoutMs: DEADLINE_MS, sprint: false
  })), 'the collection', DEADLINE_MS + 3000)

  assert.equal(report.outcome, 'collected', JSON.stringify(report))
  assert.equal(taken(), true)
  // The destroy that follows every pickup leaves the body ready for its next sense.
  await H.settled(100)
  const after = await peer.answer(H.request('sense', 's2', { radius: 8, limit: 8 }))
  assert.equal(after.op, 'sense', JSON.stringify(after))
  peer.stop()
})
