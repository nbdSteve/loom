'use strict'

/**
 * A flight that may replace a running flight, driven offline against a real Mineflayer body.
 *
 * r33 gamma: a second danger answer's flight was refused `notReady` behind gamma's own running
 * flight, the JVM halted that flight to make room, and the second flight then failed anyway, so a
 * valid flight was stopped by a refused one and no flight was applied for 150 ticks. A `flee` with
 * `replace` is checked first and leaves the running flight alone when it is refused; only a flight
 * whose threat checks out ends the running one, as `cancelled`, and takes over in the same turn.
 */

const assert = require('node:assert/strict')
const { afterEach, test } = require('node:test')

const H = require('./harness')

afterEach(H.disconnected)

const FEET = Object.freeze({ x: 8.5, y: 64, z: 8.5 })
const FED = Object.freeze({ health: 20, food: 20, foodSaturation: 5 })
const SPIDER = Object.freeze({ id: 41, what: 'spider', x: 8.5, y: 64, z: 11.5 })
const ZOMBIE = Object.freeze({ id: 42, what: 'zombie', x: 12.5, y: 64, z: 8.5 })
const DEADLINE_MS = 8_000

let openBody = null
afterEach(async () => {
  if (openBody !== null) await openBody.stop()
  openBody = null
})

test('a replacing flight that is refused leaves the running flight alone, and a valid one takes over', async () => {
  const given = await H.embodied()
  const { body, bot, client, port } = given
  openBody = body
  H.spawned(client, FED)
  H.clockReported(client, { timeOfDay: 14_000, day: 0 })
  client.emit('position', H.positionPacket({ ...FEET, yaw: 0, pitch: 0, teleportId: 1 }))
  H.ground(bot)
  H.spotted(client, SPIDER)
  H.spotted(client, ZOMBIE)
  await H.settled(300)

  const peer = await H.handshaken(port)
  const seen = await peer.answer(H.request('sense', 's1', { radius: 16, limit: 48 }))
  const spider = seen.entities.findIndex((offered) => offered.what === 'spider')
  const zombie = seen.entities.findIndex((offered) => offered.what === 'zombie')
  assert.ok(spider >= 0 && zombie >= 0, JSON.stringify(seen.entities))
  const flight = (id, entity, extra = {}) => H.request('flee', id, {
    senseId: 's1', entity, distance: 10, timeoutMs: DEADLINE_MS, sprint: false, ...extra
  })
  peer.send(flight('f1', spider))
  const busy = () => H.promptly(peer.answer(flight(`probe-${Math.random()}`.slice(0, 12), spider)), 'a probe')
  assert.equal((await busy()).error, 'notReady', 'the first flight owns the body')

  // The zombie leaves before the second answer is ready: that flight is refused, and f1 runs on.
  client.emit('entity_destroy', { entityIds: [ZOMBIE.id] })
  await H.settled(100)
  const refused = await H.promptly(peer.answer(flight('f2', zombie, { replace: true })), 'the refused replacement')
  assert.equal(refused.requestId, 'f2', JSON.stringify(refused))
  assert.equal(refused.outcome, 'threatGone', JSON.stringify(refused))
  assert.equal((await busy()).error, 'notReady', 'the running flight was not stopped')

  // A valid replacement ends f1 as cancelled and owns the path itself.
  peer.send(flight('f3', spider, { replace: true }))
  const first = await H.promptly(peer.read(), 'the replaced flight')
  assert.equal(first.requestId, 'f1', JSON.stringify(first))
  assert.equal(first.outcome, 'cancelled', JSON.stringify(first))
  const third = await H.promptly(peer.read(), 'the replacing flight', DEADLINE_MS + 3000)
  assert.equal(third.requestId, 'f3', JSON.stringify(third))
  assert.notEqual(third.outcome, 'cancelled', JSON.stringify(third))

  peer.stop()
})

test('a replacing flight still waiting to take over is displaced by a newer one and never starts', async () => {
  // r34 beta: an older sighting's flight was waiting to take over when a newer damage's flight
  // arrived; the older one was refused notReady, its retry's halt stopped the newer flight, and the
  // older one then ran in its place for 43 ticks.
  const given = await H.embodied()
  const { body, bot, client, port } = given
  openBody = body
  H.spawned(client, FED)
  H.clockReported(client, { timeOfDay: 14_000, day: 0 })
  client.emit('position', H.positionPacket({ ...FEET, yaw: 0, pitch: 0, teleportId: 1 }))
  H.ground(bot)
  H.spotted(client, SPIDER)
  H.spotted(client, ZOMBIE)
  await H.settled(300)

  const peer = await H.handshaken(port)
  const seen = await peer.answer(H.request('sense', 's1', { radius: 16, limit: 48 }))
  const spider = seen.entities.findIndex((offered) => offered.what === 'spider')
  const zombie = seen.entities.findIndex((offered) => offered.what === 'zombie')
  const flight = (id, entity, extra = {}) => H.request('flee', id, {
    senseId: 's1', entity, distance: 10, timeoutMs: DEADLINE_MS, sprint: false, ...extra
  })
  peer.send(flight('f1', spider))
  await H.settled(150)
  // The older answer and the newer one reach the body in one chunk, so both arrive before f1 drains.
  peer.pipeline([flight('f2', zombie, { replace: true }), flight('f3', spider, { replace: true })])
  const answers = new Map()
  while (answers.size < 3) {
    const frame = await H.promptly(peer.read(), 'the three flights', DEADLINE_MS + 3000)
    answers.set(frame.requestId, frame)
  }
  assert.equal(answers.get('f1').outcome, 'cancelled', JSON.stringify(answers.get('f1')))
  // The displaced takeover never began: cancelled where it stood, measured against its threat.
  const displaced = answers.get('f2')
  assert.equal(displaced.outcome, 'cancelled', JSON.stringify(displaced))
  assert.equal(displaced.travelled, 0)
  assert.equal(displaced.separationGained, 0)
  assert.ok(displaced.separation > 0)
  // The newest flight is the one that ran.
  assert.notEqual(answers.get('f3').outcome, 'cancelled', JSON.stringify(answers.get('f3')))
  assert.ok(answers.get('f3').travelled > 0, JSON.stringify(answers.get('f3')))
  peer.stop()
})
