'use strict'

/**
 * A flight from one hunter that must end clear of the others the controller names, driven offline
 * end to end through the real body process, Mineflayer, pathfinder and physics on a real chunk.
 *
 * r24 gamma fled a spider and ended 0.6 blocks from a creeper, and alpha's flight ended beside one:
 * the flight's goal knew only the creature it ran from. Here a spider stands two blocks east and a
 * creeper six blocks west, where running from the spider alone would lead.
 */

const assert = require('node:assert/strict')
const { afterEach, test } = require('node:test')

const H = require('./harness')
const { FLIGHT_OTHER_CLEARANCE_BLOCKS } = require('../src/movement')

afterEach(H.disconnected)

const FEET = Object.freeze({ x: 8.5, y: 64, z: 8.5 })
const FED = Object.freeze({ health: 20, food: 20, foodSaturation: 5 })
// Each pair the r24 audit cites: gamma's spider beside a creeper, alpha's zombie beside a creeper,
// and eta's and theta's skeletons beside a zombie or a spider.
const PAIRS = [['spider', 'creeper'], ['zombie', 'creeper'], ['skeleton', 'zombie'], ['spider', 'skeleton']]
const DEADLINE_MS = 8_000

let openBody = null
afterEach(async () => {
  if (openBody !== null) await openBody.stop()
  openBody = null
})

const apart = (position, at) => Math.hypot(position[0] - at.x, position[2] - at.z)

for (const [ranFrom, besides] of PAIRS) {
  test(`a flight from a ${ranFrom} ends no nearer the ${besides} it was told to avoid, and further from both`, async () => {
    const SPIDER = Object.freeze({ id: 51, what: ranFrom, x: 10.5, y: 64, z: 8.5 })
    const CREEPER = Object.freeze({ id: 52, what: besides, x: 2.5, y: 64, z: 8.5 })
    const { body, bot, client, port } = await H.embodied()
    openBody = body
    H.spawned(client, FED)
    H.clockReported(client, { timeOfDay: 14_000, day: 0 })
    client.emit('position', H.positionPacket({ ...FEET, yaw: 0, pitch: 0, teleportId: 1 }))
    H.ground(bot)
    H.spotted(client, SPIDER)
    H.spotted(client, CREEPER)
    await H.settled(300)
    const peer = await H.handshaken(port)
    const seen = await peer.answer(H.request('sense', 's1', { radius: 16, limit: 48 }))
    const spider = seen.entities.findIndex((offered) => offered.what === ranFrom)
    const creeper = seen.entities.findIndex((offered) => offered.what === besides)
    assert.ok(spider >= 0 && creeper >= 0, JSON.stringify(seen.entities))
    const report = await H.promptly(peer.answer(H.request('flee', 'f1', {
      senseId: 's1', entity: spider, distance: 6, timeoutMs: DEADLINE_MS, sprint: true, avoid: [creeper]
    })), 'the flight', DEADLINE_MS + 3000)
    assert.equal(report.outcome, 'arrived', JSON.stringify(report))
    const fromCreeper = apart(report.position, CREEPER)
    const began = apart([FEET.x, FEET.y, FEET.z], CREEPER)
    assert.ok(fromCreeper >= Math.min(began, FLIGHT_OTHER_CLEARANCE_BLOCKS) - 0.5, `${fromCreeper} from the creeper`)
    const least = Math.min(fromCreeper, apart(report.position, SPIDER))
    assert.ok(least > Math.min(began, apart([FEET.x, FEET.y, FEET.z], SPIDER)), `least separation ${least} grew`)
    peer.stop()
  })
}
